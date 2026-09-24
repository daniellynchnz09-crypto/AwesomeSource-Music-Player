package com.mslynch.awesomesource.organize.pipeline

import com.mslynch.awesomesource.organize.matching.Scorer
import com.mslynch.awesomesource.organize.metadata.CoverArtClient
import com.mslynch.awesomesource.organize.metadata.MusicBrainzClient
import com.mslynch.awesomesource.organize.model.AlbumGroup
import com.mslynch.awesomesource.organize.model.FileStatus
import com.mslynch.awesomesource.organize.model.MbCandidate
import com.mslynch.awesomesource.organize.persistence.dao.QueryCacheDao
import com.mslynch.awesomesource.organize.persistence.entity.MbQueryCacheEntity
import com.mslynch.awesomesource.organize.tags.FilenameParser
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant

/**
 * Ported from
 * `legacy-desktop-tagger/musictagger/workers/query_worker.py`'s `_query_group`.
 * Queries MusicBrainz for one [AlbumGroup], scores the candidates, and decides the
 * outcome - every heuristic here (channel-suffix stripping, embedded-artist-in-title
 * recovery, the bare-version-suffix retry) is preserved from the Python original,
 * each one tuned against a real observed failure case (cited inline below).
 *
 * Calls are naturally serialized by [MusicBrainzClient]'s own rate limiter, so
 * processing groups one at a time (not in parallel) is what actually guarantees no
 * burst happens - same reasoning as the Python original's "runs sequentially in one
 * worker thread" design.
 */
class QueryGroup(
    private val mbClient: MusicBrainzClient,
    private val coverArtClient: CoverArtClient,
    private val queryCacheDao: QueryCacheDao,
) {
    private val moshi = Moshi.Builder().build()
    private val candidateListType = Types.newParameterizedType(List::class.java, MbCandidate::class.java)
    private val candidateListAdapter = moshi.adapter<List<MbCandidate>>(candidateListType)

    // YouTube-sourced music very commonly tags the uploading channel's brand name
    // as the artist (e.g. "Tippermusic" for the real artist "Tipper"), which won't
    // match MusicBrainz's actual artist entry at all. Only tried as a fallback
    // *after* the primary search finds nothing, so it can never override an
    // already-successful match with a worse guess.
    private val channelSuffixes = listOf("music", "official", "vevo", "records", "band")

    // An artist-only release search (no album name available at all) can't rely on
    // MusicBrainz's text relevance ranking the way an album-title search can - the
    // correct release can rank arbitrarily far down an artist's whole discography
    // (observed: a real, exact 13-track match ranked #50 out of 79). Fetching many
    // more candidates gives the scorer's track-count signal an actual chance to
    // find it.
    private val artistOnlySearchLimit = 100
    private val defaultSearchLimit = 5

    // A bare, unattributed "(remix)"/"(mix)"/"(edit)" title suffix - as opposed to
    // a named "(Artist Remix)", which genuinely identifies a different recording -
    // is frequently just how a personal rip/download happened to be labeled.
    // Confirmed for real: searching "Spawn (remix)" pushed the actual, correct
    // "Spawn" recording entirely out of the top 15 results, while the bare title
    // found it immediately at a perfect score. Only tried as a fallback retry,
    // after the as-tagged title search hasn't already found a confident match.
    private val bareVersionSuffixRe = Regex("""\s*\((?:remix|mix|edit|rmx)\)\s*$""", RegexOption.IGNORE_CASE)

    private fun normalizeKey(vararg parts: String?): String = parts.joinToString("|") { (it ?: "").trim().lowercase() }
    private fun normalize(text: String?): String = (text ?: "").trim().lowercase()

    private fun stripChannelSuffix(artist: String): String? {
        val lowered = artist.lowercase()
        for (suffix in channelSuffixes) {
            if (lowered.endsWith(suffix) && lowered.length > suffix.length + 2) {
                return artist.substring(0, artist.length - suffix.length).trim()
            }
        }
        return null
    }

    private suspend fun cachedOrSearch(key: String, search: suspend () -> List<MbCandidate>): List<MbCandidate> {
        val cached = queryCacheDao.getCachedMbQuery(key)
        if (cached != null) {
            val parsed = runCatching { candidateListAdapter.fromJson(cached) }.getOrNull()
            if (parsed != null) return parsed
            // Fall through to a real search if the cached JSON is somehow corrupt.
        }
        val candidates = search()
        queryCacheDao.setCachedMbQuery(
            MbQueryCacheEntity(queryKey = key, responseJson = candidateListAdapter.toJson(candidates), cachedAt = Instant.now().toString())
        )
        return candidates
    }

    suspend fun queryGroup(group: AlbumGroup): AlbumGroup {
        val first = group.files[0]
        val ranked: List<MbCandidate>

        try {
            if (group.isSingleton || group.files.size == 1) {
                val result = querySingleton(group, first)
                    ?: return group.copy(status = FileStatus.INSUFFICIENT_INFO, statusDetail = "no usable artist/title to search with")
                ranked = result
            } else {
                val artist = group.bestGuessArtist ?: first.albumArtist ?: first.artist
                val album = group.bestGuessAlbum ?: first.album
                if (artist.isNullOrEmpty()) {
                    return group.copy(status = FileStatus.INSUFFICIENT_INFO, statusDetail = "no usable artist to search with")
                }
                // album may be null - a group clustered by matching artist + track
                // number can have no album tag anywhere. searchReleaseCandidates
                // falls back to an artist-only search, and the scorer leans on
                // track-count agreement to pick the right release out of the
                // artist's whole discography instead.
                val searchLimit = if (!album.isNullOrEmpty()) defaultSearchLimit else artistOnlySearchLimit
                val key = normalizeKey("release", artist, album)
                var candidates = cachedOrSearch(key) { mbClient.searchReleaseCandidates(artist, album, searchLimit) }
                if (candidates.isEmpty()) {
                    val altArtist = stripChannelSuffix(artist)
                    if (altArtist != null) {
                        val altKey = normalizeKey("release", altArtist, album)
                        val altCandidates = cachedOrSearch(altKey) { mbClient.searchReleaseCandidates(altArtist, album, searchLimit) }
                        if (altCandidates.isNotEmpty()) candidates = altCandidates
                    }
                }
                ranked = Scorer.scoreCandidates(artist, album, group.files.size, first.year, candidates)
            }
        } catch (e: MusicBrainzClient.MusicBrainzException) {
            return group.copy(status = FileStatus.LOOKUP_FAILED, statusDetail = e.message ?: "MusicBrainz lookup failed")
        }

        val decision = Scorer.decide(ranked)
        val withCandidates = group.copy(candidates = ranked)

        if (decision.outcome == Scorer.Outcome.AUTO_APPLY && decision.chosen != null) {
            return try {
                val proposed = ReleaseResolver.resolveGroupToProposed(mbClient, coverArtClient, withCandidates, decision.chosen, fetchCoverArt = false)
                withCandidates.copy(status = FileStatus.AUTO_MATCHED, chosenReleaseId = decision.chosen.releaseId, proposedByPath = proposed)
            } catch (e: Exception) {
                // Matched with high confidence but couldn't fetch the full release
                // details to build a proposal - surface as needs_review so the
                // user can retry rather than silently losing the match.
                withCandidates.copy(status = FileStatus.NEEDS_REVIEW, statusDetail = "matched but details fetch failed: ${e.message}")
            }
        }
        return when (decision.outcome) {
            Scorer.Outcome.NEEDS_REVIEW -> withCandidates.copy(status = FileStatus.NEEDS_REVIEW)
            else -> withCandidates.copy(status = FileStatus.NO_MATCH)
        }
    }

    /** Resolves an already-chosen candidate into per-track proposed metadata,
     * reusing this instance's own [mbClient]/[coverArtClient] - used by the Gemini
     * grounding pass in `OrganizeLibrary`, which picks a candidate *after*
     * [queryGroup] has already returned, so it needs the same resolution step
     * [queryGroup] runs internally for its own auto-apply path. Never throws -
     * a details-fetch failure just means no draft is available, not a crash. */
    suspend fun resolveProposed(group: AlbumGroup, chosen: MbCandidate): Map<String, com.mslynch.awesomesource.organize.model.TrackMetadata> =
        try {
            ReleaseResolver.resolveGroupToProposed(mbClient, coverArtClient, group, chosen, fetchCoverArt = false)
        } catch (e: Exception) {
            emptyMap()
        }

    /** Returns ranked candidates, or null if there's no usable artist/title to
     * search with at all. A group with exactly one file is queried by track title
     * rather than by album - a release search on the local "album" tag fails
     * surprisingly often here (streaming services commonly write album tags like
     * "Song - Single" that don't match MusicBrainz's actual release title), while a
     * plain recording-title search finds the right track directly. */
    private suspend fun querySingleton(group: AlbumGroup, first: com.mslynch.awesomesource.organize.model.TrackMetadata): List<MbCandidate>? {
        val artist = first.artist ?: group.bestGuessArtist
        // The TITLE tag is very commonly blank for individually-tagged/singleton
        // files, even when the real title is sitting right there in the filename -
        // falling back to the filename guess is what parseFilename already exists
        // for.
        var title = first.title ?: FilenameParser.parseFilename(first.path).title
        if (artist.isNullOrEmpty() || title.isNullOrEmpty()) return null

        // Strip known upload/rip noise up front, independent of whether an
        // "Artist - Title" split below also applies.
        title = FilenameParser.cleanNoiseText(title)

        var resolvedArtist = artist

        // YouTube-sourced rips very often dump the whole "Artist - Track" video
        // title into just the TITLE tag, while the ARTIST tag holds the uploading
        // channel's name. The artist embedded in the title text is the one
        // actually describing the song, so when this pattern is detected it's
        // preferred over the tag artist outright, not just tried as a fallback
        // after the tag artist fails.
        val restAfterArtist = if (title.length >= artist.length) title.substring(artist.length) else ""
        val artistIsWholeWordPrefix = restAfterArtist.isNotEmpty() && !restAfterArtist.first().isLetterOrDigit()
        // A comma or "&" right after the artist name means more collaborator names
        // follow - that's the multi-artist-credit-dumped-into-title pattern, not
        // "the title repeats just this one artist's name".
        val continuesWithMoreArtists = restAfterArtist.trimStart().firstOrNull() in listOf(',', '&')

        if (artistIsWholeWordPrefix && !continuesWithMoreArtists && normalize(title).startsWith(normalize(artist))) {
            // The TITLE tag sometimes repeats the already-known artist name as a
            // literal prefix. When that artist name itself contains a hyphen
            // (common for Newgrounds-era handles like "F-777"), blindly
            // dash-splitting the title mistook the hyphen inside the artist's own
            // name for the "Artist - Title" separator. Stripping the already-known
            // artist off as a literal prefix sidesteps the dash-based heuristic
            // entirely for this case.
            val remainder = title.substring(artist.length).trim(' ', '-', '_', ':', '"', '\'')
            if (remainder.isNotEmpty()) title = remainder
        } else {
            val (embeddedArtist, embeddedTitle) = FilenameParser.splitArtistTitleText(title)
            if (!embeddedArtist.isNullOrEmpty() && !embeddedTitle.isNullOrEmpty()) {
                if (normalize(embeddedTitle).contains(normalize(artist))) {
                    // The classic Newgrounds Audio Portal convention runs the
                    // other way - "Title - Artist" rather than "Artist - Title" -
                    // so the known-good tag artist turning up on the *title* side
                    // of the split means the two sides are swapped, not that the
                    // split found a better artist.
                    title = embeddedArtist
                } else {
                    resolvedArtist = embeddedArtist
                    title = embeddedTitle
                }
            }
        }

        val key = normalizeKey("recording", resolvedArtist, title)
        var candidates = cachedOrSearch(key) { mbClient.searchRecordingCandidates(resolvedArtist!!, title!!) }

        if (candidates.isEmpty()) {
            val altArtist = stripChannelSuffix(resolvedArtist)
            if (altArtist != null) {
                val altKey = normalizeKey("recording", altArtist, title)
                val altCandidates = cachedOrSearch(altKey) { mbClient.searchRecordingCandidates(altArtist, title!!) }
                if (altCandidates.isNotEmpty()) {
                    candidates = altCandidates
                    resolvedArtist = altArtist
                }
            }
        }

        // Prefer a folder-structure-derived album guess over the file's own
        // existing album tag as the disambiguation hint here specifically - this
        // branch exists to (re)search and potentially CORRECT a file's metadata,
        // so trusting an existing, possibly-wrong tag value would just reinforce
        // whatever it was already (mis)tagged as.
        val albumHint = FilenameParser.parseFilename(first.path).album ?: group.bestGuessAlbum
        var ranked = Scorer.scoreCandidates(resolvedArtist, title, null, first.year, candidates, albumHint)

        if (bareVersionSuffixRe.containsMatchIn(title) && Scorer.decide(ranked).outcome != Scorer.Outcome.AUTO_APPLY) {
            val strippedTitle = bareVersionSuffixRe.replace(title, "").trim()
            if (strippedTitle.isNotEmpty()) {
                val strippedKey = normalizeKey("recording", resolvedArtist, strippedTitle)
                val strippedCandidates = cachedOrSearch(strippedKey) { mbClient.searchRecordingCandidates(resolvedArtist, strippedTitle) }
                val strippedRanked = Scorer.scoreCandidates(resolvedArtist, strippedTitle, null, first.year, strippedCandidates, albumHint)
                if (Scorer.decide(strippedRanked).outcome == Scorer.Outcome.AUTO_APPLY) {
                    ranked = strippedRanked
                }
            }
        }

        return ranked
    }
}
