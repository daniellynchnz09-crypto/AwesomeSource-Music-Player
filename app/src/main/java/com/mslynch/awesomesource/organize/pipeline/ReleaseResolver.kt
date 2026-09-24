package com.mslynch.awesomesource.organize.pipeline

import com.mslynch.awesomesource.organize.matching.FuzzyMatch
import com.mslynch.awesomesource.organize.metadata.CoverArtClient
import com.mslynch.awesomesource.organize.metadata.MusicBrainzClient
import com.mslynch.awesomesource.organize.model.AlbumGroup
import com.mslynch.awesomesource.organize.model.MbCandidate
import com.mslynch.awesomesource.organize.model.MetadataSource
import com.mslynch.awesomesource.organize.model.TrackMetadata
import com.mslynch.awesomesource.organize.tags.FilenameParser

/**
 * Ported from
 * `legacy-desktop-tagger/musictagger/metadata_sources/release_resolver.py`.
 * Resolves a chosen MusicBrainz candidate into per-file proposed [TrackMetadata], by
 * fetching the authoritative tracklist (for a full release) and cover art. Shared by
 * both the auto-apply path ([QueryGroup]) and a future review-panel "Accept this
 * candidate" action, so a match is only ever built the same way regardless of who
 * picked it.
 */
object ReleaseResolver {

    private const val TITLE_MATCH_THRESHOLD = 65.0

    // MusicBrainz's own titles for remixes are real but inconsistently styled (e.g.
    // "He's a Pirate (F-777 ReMiX)"). Normalizes any "(<name> remix)"/"(<name>
    // re-mix)" suffix into a single consistent "(<Name> Remix)" form. Deliberately
    // scoped to the literal word "remix" only, not a bare "mix" - "(Original Mix)",
    // "(Radio Mix)" etc. are legitimate version descriptors, not a remixer named
    // Original/Radio, and must never be rewritten into a fake "(X Remix)" credit.
    private val REMIX_TITLE_RE = Regex("""^(.*?)\s*\(([^)]*?)\s*re-?mix\)\s*$""", RegexOption.IGNORE_CASE)

    /** Normalizes a "(<name> remix)"-style title suffix, e.g. "He's a Pirate (F-777
     * ReMiX)" -> "He's a Pirate (F-777 Remix)". Applied to whatever title actually
     * ends up written, not to search queries. Returns the title unchanged when it
     * isn't a recognized remix-credit suffix. */
    fun formatRemixTitle(title: String?): String? {
        if (title.isNullOrEmpty()) return title
        val match = REMIX_TITLE_RE.find(title) ?: return title
        val song = match.groupValues[1].trim()
        val remixer = match.groupValues[2].trim()
        if (song.isEmpty() || remixer.isEmpty()) return title
        return "$song ($remixer Remix)"
    }

    private fun normalize(text: String?): String = (text ?: "").trim().replace(Regex("\\s+"), " ").lowercase()

    private fun yearFromDate(date: String?): Int? =
        if (date != null && date.length >= 4 && date.take(4).all { it.isDigit() }) date.take(4).toInt() else null

    /** The best guess at a file's own song title, independent of the release - used
     * to match it against the release's real tracklist by content, since track
     * position is often missing or unreliable on its own. */
    private fun localTitleGuess(track: TrackMetadata): String? {
        if (!track.title.isNullOrEmpty()) {
            val (_, embeddedTitle) = FilenameParser.splitArtistTitleText(track.title)
            var title = embeddedTitle ?: track.title
            // Some rips bake the track position directly into the TITLE tag itself
            // (e.g. "1 Goldilocks Zone") - left in, that drags down the fuzzy match
            // against the release's real, clean tracklist title ("Goldilocks Zone").
            title = FilenameParser.stripLeadingTrackNumber(title).second
            return title
        }
        return FilenameParser.parseFilename(track.path).title
    }

    /** Finds which release position's title best matches a given local title,
     * treating content (the actual song title) as stronger evidence of position
     * than any number scraped from a tag or filename. Returns null if nothing
     * clears [TITLE_MATCH_THRESHOLD], rather than guessing. */
    private fun bestMatchingPosition(
        localTitle: String?,
        tracksByPosition: Map<Int, MusicBrainzClient.ReleaseTrack>,
        exclude: Set<Int> = emptySet(),
    ): Int? {
        if (localTitle.isNullOrEmpty() || tracksByPosition.isEmpty()) return null
        val normalizedLocal = normalize(localTitle)
        var bestPosition: Int? = null
        var bestScore = 0.0
        for ((position, info) in tracksByPosition) {
            if (position in exclude) continue
            val score = FuzzyMatch.wRatio(normalizedLocal, normalize(info.title))
            if (score > bestScore) {
                bestScore = score
                bestPosition = position
            }
        }
        return if (bestPosition != null && bestScore >= TITLE_MATCH_THRESHOLD) bestPosition else null
    }

    /**
     * Matches each local file to the release position whose title it resembles
     * most, rather than assuming position order.
     *
     * Many real-world rips carry no track number anywhere. Falling back to
     * sorted-enumerate order in that situation effectively assigns positions at
     * random, silently pairing the wrong title (and the wrong per-track artist)
     * with the wrong file. An explicit tag track number is trusted outright when
     * present; otherwise the best-scoring, not-yet-claimed position by title
     * similarity wins; a filename-derived track number is only the last resort,
     * tried after title matching rather than before it, since a real title
     * comparison is stronger evidence than a number scraped from a filename.
     */
    private fun matchFilesToPositions(
        files: List<TrackMetadata>,
        tracksByPosition: Map<Int, MusicBrainzClient.ReleaseTrack>,
    ): Map<String, Int> {
        val assigned = mutableMapOf<String, Int>()
        val usedPositions = mutableSetOf<Int>()
        val remaining = mutableListOf<TrackMetadata>()

        // Pass 1: an explicit tag track_number is authoritative - claim it outright.
        for (track in files) {
            val number = track.trackNumber
            if (number != null && number in tracksByPosition) {
                assigned[track.path.value] = number
                usedPositions.add(number)
            } else {
                remaining.add(track)
            }
        }

        // Pass 2: fuzzy-match everyone else by title.
        val stillRemaining = mutableListOf<TrackMetadata>()
        for (track in remaining) {
            val position = bestMatchingPosition(localTitleGuess(track), tracksByPosition, usedPositions)
            if (position != null) {
                assigned[track.path.value] = position
                usedPositions.add(position)
            } else {
                stillRemaining.add(track)
            }
        }

        // Pass 3: last resort - a filename-derived track number, if any.
        for (track in stillRemaining) {
            val number = FilenameParser.resolveTrackNumber(track)
            if (number != null && number in tracksByPosition && number !in usedPositions) {
                assigned[track.path.value] = number
                usedPositions.add(number)
            }
        }

        return assigned
    }

    /**
     * Returns a Map of file path -> proposed [TrackMetadata] for every file in the
     * group. `fetchCoverArt=false` skips the Cover Art Archive request entirely -
     * cover fields fall back to whatever the file already has. Cover art isn't part
     * of MusicBrainz's own rate-limited API, but it's still a real network round
     * trip; during a bulk lookup pass that extra latency lands squarely on the
     * slowest part of the whole operation for no benefit if nothing shows a preview
     * yet. Fetch it JIT once the user actually accepts a match instead.
     */
    suspend fun resolveGroupToProposed(
        mbClient: MusicBrainzClient,
        coverArtClient: CoverArtClient,
        group: AlbumGroup,
        chosen: MbCandidate,
        fetchCoverArt: Boolean = true,
    ): Map<String, TrackMetadata> {
        if (group.isSingleton || chosen.isRecording) {
            val track = group.files[0]
            // A recording is linked to the release it appeared on - fetching that
            // release's info is what fills in the album field for a singleton
            // match at all; without this, even a correct, confident match would
            // leave "album" blank forever.
            val releaseInfo = if (chosen.releaseId.isNotEmpty()) {
                mbClient.getReleaseTracklist(chosen.releaseId)
            } else {
                MusicBrainzClient.ReleaseTracklist(null, null, null, emptyMap())
            }
            val coverUri = if (fetchCoverArt && chosen.releaseId.isNotEmpty()) {
                coverArtClient.fetchFullImage(chosen.releaseId)
            } else null

            var trackNumber = bestMatchingPosition(chosen.title.ifEmpty { track.title }, releaseInfo.tracks)
            if (trackNumber == null) trackNumber = FilenameParser.resolveTrackNumber(track)

            val proposed = track.copy(
                artist = chosen.artistCredit.ifEmpty { track.artist },
                albumArtist = releaseInfo.artist ?: track.albumArtist,
                album = releaseInfo.album ?: track.album,
                title = formatRemixTitle(chosen.title.ifEmpty { track.title }),
                trackNumber = trackNumber,
                year = yearFromDate(chosen.firstReleaseDate) ?: releaseInfo.year ?: track.year,
                hasCoverArt = coverUri != null || track.hasCoverArt,
                coverArtMime = if (coverUri != null) "image/jpeg" else track.coverArtMime,
                source = MetadataSource.ONLINE_LOOKUP,
            )
            return mapOf(track.path.value to proposed)
        }

        val release = mbClient.getReleaseTracklist(chosen.releaseId)
        val coverUri = if (fetchCoverArt) coverArtClient.fetchFullImage(chosen.releaseId) else null
        val positionByPath = matchFilesToPositions(group.files, release.tracks)

        return group.files.associate { track ->
            val position = positionByPath[track.path.value]
            val info = position?.let { release.tracks[it] }
            track.path.value to track.copy(
                // A various-artists compilation's per-track artist is only known
                // once this file is matched to its real position - without a
                // confident match, keep the file's own existing artist tag rather
                // than overwriting it with the release-level credit (often
                // "Various Artists" or the label name).
                artist = info?.artist?.ifEmpty { null } ?: track.artist ?: release.artist,
                albumArtist = release.artist ?: track.albumArtist,
                album = release.album ?: track.album,
                title = formatRemixTitle(info?.title ?: track.title),
                trackNumber = position ?: track.trackNumber,
                year = release.year ?: track.year,
                hasCoverArt = coverUri != null || track.hasCoverArt,
                coverArtMime = if (coverUri != null) "image/jpeg" else track.coverArtMime,
                source = MetadataSource.ONLINE_LOOKUP,
            )
        }
    }
}
