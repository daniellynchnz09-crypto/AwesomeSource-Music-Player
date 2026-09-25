package com.mslynch.awesomesource.organize.pipeline

import android.content.Context
import android.net.Uri
import com.mslynch.awesomesource.organize.grouping.AlbumGrouper
import com.mslynch.awesomesource.organize.metadata.CoverArtClient
import com.mslynch.awesomesource.organize.metadata.GeminiGroundingClient
import com.mslynch.awesomesource.organize.metadata.MusicBrainzClient
import com.mslynch.awesomesource.organize.model.AlbumGroup
import com.mslynch.awesomesource.organize.model.FileStatus
import com.mslynch.awesomesource.organize.model.LibraryPath
import com.mslynch.awesomesource.organize.model.LibraryType
import com.mslynch.awesomesource.organize.model.MetadataSource
import com.mslynch.awesomesource.organize.model.ReviewStatus
import com.mslynch.awesomesource.organize.model.TrackMetadata
import com.mslynch.awesomesource.organize.model.hasAllDetails
import com.mslynch.awesomesource.organize.persistence.AppDatabase
import com.mslynch.awesomesource.organize.persistence.entity.ScanSessionEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackArtistCreditEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
import com.mslynch.awesomesource.organize.persistence.entity.toTrackMetadata
import com.mslynch.awesomesource.organize.scanner.Scanner
import com.mslynch.awesomesource.organize.tags.AudioTagReader
import com.mslynch.awesomesource.organize.tags.EdmCreditParser
import com.mslynch.awesomesource.organize.tags.FilenameParser
import java.time.Instant

/**
 * The top-level orchestrator - the Kotlin equivalent of the Python original's
 * `workers/` package (scan_worker.py, query_worker.py, write_worker.py) combined.
 * Ties every already-ported piece together: scan -> read tags (or route to
 * sidecar/filename-guess) -> group into albums -> query MusicBrainz -> score ->
 * (optionally) Gemini-ground an ambiguous result -> persist.
 *
 * Not yet exercised against a real user library - only the scaffold's placeholder
 * screen has been run on-device so far (see Claude/ANDROID ARCHITECTURE.md).
 * Deliberately does NOT write corrected tags back into files yet (no verified
 * write-capable Android tagging *write* path exists yet - `AudioTagReader` is
 * read-only); "applying" a match currently only updates the app's own database.
 */
class OrganizeLibrary(private val context: Context) {

    enum class Phase { SCANNING, READING_TAGS, GROUPING, QUERYING }
    data class Progress(val phase: Phase, val processed: Int, val total: Int)

    data class Options(
        val musicBrainzContact: String?,
        val geminiApiKey: String?,
        val libraryType: LibraryType? = null,
        val onProgress: ((Progress) -> Unit)? = null,
    )

    private val db by lazy { AppDatabase.getInstance(context) }
    private val tagReader by lazy { AudioTagReader(context) }
    private val coverArtClient by lazy { CoverArtClient(context.cacheDir) }

    /** Scans `rootUri`, reads/guesses every file's metadata, groups into albums,
     * and queries MusicBrainz (with a Gemini double-check for ambiguous results)
     * for each group - persisting every track's resolved status to the database as
     * it goes, so a scan interrupted partway through doesn't lose the work already
     * done. `rootUri` must already have a persisted permission grant (see
     * `Scanner`'s doc comment). */
    suspend fun organize(rootUri: Uri, options: Options) {
        val sessionId = db.undoLogDao().insertSession(
            ScanSessionEntity(rootFoldersJson = "[\"$rootUri\"]", startedAt = Instant.now().toString(), completedAt = null, status = "running")
        )

        val bareFiles = Scanner.scanFolder(context, rootUri)
        options.onProgress?.invoke(Progress(Phase.SCANNING, bareFiles.size, bareFiles.size))

        val tracks = mutableListOf<TrackMetadata>()
        for ((i, bare) in bareFiles.withIndex()) {
            val track = resolveInitialMetadata(bare, options.libraryType)
            tracks.add(track)
            persistTrack(track)
            options.onProgress?.invoke(Progress(Phase.READING_TAGS, i + 1, bareFiles.size))
        }

        val groups = AlbumGrouper.groupIntoAlbums(tracks)
        options.onProgress?.invoke(Progress(Phase.GROUPING, groups.size, groups.size))

        val mbClient = MusicBrainzClient(options.musicBrainzContact)
        val geminiClient = GeminiGroundingClient(options.geminiApiKey, queryCacheDao = db.queryCacheDao())
        val queryGroup = QueryGroup(mbClient, coverArtClient, db.queryCacheDao())
        for ((i, group) in groups.withIndex()) {
            processGroup(queryGroup, geminiClient, group)
            options.onProgress?.invoke(Progress(Phase.QUERYING, i + 1, groups.size))
        }

        db.undoLogDao().completeSession(sessionId, Instant.now().toString())
    }

    /** Reads embedded tags when the format supports them; falls back to sidecar
     * metadata (WAV etc. - Claude/MUSIC ORGANIZATION.md's "list document" concept)
     * or a filename guess otherwise. A tag-read failure (corrupt/malformed embedded
     * tags - e.g. jaudiotagger's "Unable to determine start of audio in file" on
     * some M4As) gets the exact same fallback rather than being permanently
     * stranded as unreadable: the file's *path* is still perfectly readable even
     * when its embedded tags aren't, so a filename guess can still recover it and
     * let it proceed to MusicBrainz querying like any other track. One corrupt file
     * still can't crash the whole scan, matching the Python original's rule - it
     * just no longer means "give up on this file forever". */
    private suspend fun resolveInitialMetadata(bare: TrackMetadata, libraryType: LibraryType?): TrackMetadata {
        when (val outcome = tagReader.readTags(bare.uri, bare.path)) {
            is AudioTagReader.Outcome.Ok -> {
                val t = outcome.tags
                return bare.copy(
                    fileFormat = t.fileFormat,
                    artist = t.artist,
                    albumArtist = t.albumArtist,
                    album = t.album,
                    title = t.title,
                    trackNumber = t.trackNumber,
                    trackTotal = t.trackTotal,
                    discNumber = t.discNumber,
                    discTotal = t.discTotal,
                    year = t.year,
                    genre = t.genre,
                    composer = t.composer,
                    durationSeconds = t.durationSeconds,
                    hasCoverArt = t.hasCoverArt,
                    coverArtMime = t.coverArtMime,
                    libraryType = libraryType,
                    source = MetadataSource.EMBEDDED_TAGS,
                    status = FileStatus.TAGS_READ,
                )
            }
            is AudioTagReader.Outcome.Unreadable -> {
                return resolveFromSidecarOrFilename(bare, libraryType, readError = outcome.error)
            }
            AudioTagReader.Outcome.UnsupportedFormat -> {
                return resolveFromSidecarOrFilename(bare, libraryType, readError = null)
            }
        }
    }

    /** Sidecar metadata first, then a filename guess - shared by the
     * unsupported-format (WAV/OGG-without-tag-support) and tag-read-failure paths.
     * `readError` (non-null only for the latter) is preserved in `statusDetail` for
     * diagnostics even when a filename guess successfully recovers the track. */
    private suspend fun resolveFromSidecarOrFilename(bare: TrackMetadata, libraryType: LibraryType?, readError: String?): TrackMetadata {
        val sidecar = db.sidecarMetadataDao().getByPath(bare.path.value)
        if (sidecar != null) {
            return bare.copy(
                artist = sidecar.artist,
                albumArtist = sidecar.albumArtist,
                album = sidecar.album,
                title = sidecar.title,
                trackNumber = sidecar.trackNumber,
                year = sidecar.year,
                genre = sidecar.genre,
                composer = sidecar.composer,
                hasCoverArt = sidecar.coverArtUri != null,
                libraryType = libraryType,
                source = MetadataSource.EMBEDDED_TAGS,
                status = FileStatus.TAGS_READ,
                statusDetail = readError?.let { "tag read failed ($it); used sidecar metadata" } ?: "",
            )
        }

        val guess = FilenameParser.parseFilename(bare.path)
        val guessDetail = if (guess.confidence == FilenameParser.Confidence.LOOSE) {
            "loose filename guess: ${guess.searchText ?: "(no usable text)"}"
        } else ""
        return bare.copy(
            artist = guess.artist,
            album = guess.album,
            title = guess.title,
            trackNumber = guess.trackNumber,
            libraryType = libraryType,
            source = MetadataSource.FILENAME_GUESS,
            status = if (guess.confidence == FilenameParser.Confidence.STRUCTURED) FileStatus.PENDING else FileStatus.INSUFFICIENT_INFO,
            statusDetail = when {
                readError != null && guessDetail.isNotEmpty() -> "tag read failed ($readError); $guessDetail"
                readError != null -> "tag read failed ($readError); used filename guess"
                else -> guessDetail
            },
        )
    }

    /** Returns the resolved group (status, `chosenReleaseId`, drafts) after
     * persisting every track in it - the return value is unused by `organize()`'s
     * own scan loop but is what `requeryTracks` needs to decide whether
     * `discoverAlbumSiblings` is worth running. */
    private suspend fun processGroup(queryGroup: QueryGroup, geminiClient: GeminiGroundingClient, group: AlbumGroup): AlbumGroup {
        var resolved = queryGroup.queryGroup(group)

        // For an ambiguous (needs_review) result, ask Gemini to double-check
        // against the local file evidence - the "LLM double-check" pass from
        // Claude/MUSIC ORGANIZATION.md. A confident Gemini pick is treated the
        // same as an auto-applied MusicBrainz match; anything else is left as
        // needs_review for a human, exactly as before.
        if (resolved.status == FileStatus.NEEDS_REVIEW && resolved.candidates.isNotEmpty()) {
            val first = resolved.files[0]
            val verdict = geminiClient.groundMatch(
                GeminiGroundingClient.LocalEvidence(
                    artist = resolved.bestGuessArtist ?: first.artist,
                    album = resolved.bestGuessAlbum ?: first.album,
                    title = first.title,
                    trackCount = resolved.files.size.takeIf { it > 0 },
                    year = first.year,
                    filenameHint = FilenameParser.parseFilename(first.path).searchText,
                ),
                resolved.candidates,
            )
            if (verdict?.confident == true && verdict.chosen != null) {
                // Same resolution step queryGroup() runs internally for its own
                // auto-apply path - needed here too so a Gemini-confirmed match
                // gets a proposed draft, not just a status change.
                val proposed = queryGroup.resolveProposed(resolved, verdict.chosen)
                resolved = resolved.copy(
                    status = FileStatus.AUTO_MATCHED,
                    chosenReleaseId = verdict.chosen.releaseId,
                    statusDetail = "Gemini-grounded: ${verdict.reasoning}",
                    proposedByPath = proposed,
                )
            }
        }

        // "Recognized" for the review-status model means the same thing regardless
        // of whether MusicBrainz alone or a Gemini-grounded confirmation produced
        // it - both end up AUTO_MATCHED. Everything else (NEEDS_REVIEW that Gemini
        // couldn't resolve either, NO_MATCH, LOOKUP_FAILED, INSUFFICIENT_INFO) is
        // "not recognized" for this purpose - the pipeline doesn't have a confident
        // answer, whatever the specific internal reason.
        val recognized = resolved.status == FileStatus.AUTO_MATCHED
        for (track in resolved.files) {
            val withStatus = track.copy(
                status = resolved.status,
                statusDetail = resolved.statusDetail.ifEmpty { track.statusDetail },
                source = if (recognized) MetadataSource.ONLINE_LOOKUP else track.source,
            )
            // Persisted whenever recognized, regardless of whether the track's own
            // fields already look "complete" - a track can have every field filled
            // in (often from a bulk edit) while the confirmed match's own per-track
            // data still disagrees (e.g. a real collaboration credit), and
            // `TrackEntity.reviewStatus()` is what actually decides APPROVED vs.
            // MATCH_FOUND from this, by comparing `proposedArtist` against `artist`
            // - not this function, and not `hasAllDetails()` alone. Storing a
            // proposal that happens to agree with the current fields is harmless.
            val proposed = if (recognized) resolved.proposedByPath[track.path.value] else null
            persistTrack(withStatus, proposed = proposed, matchedReleaseId = resolved.chosenReleaseId.takeIf { recognized })
        }
        return resolved
    }

    /** Re-runs grouping + MusicBrainz/Gemini matching for an already-scanned set of
     * tracks, identified by path, without touching the rest of the library or
     * re-scanning the folder - for the case where a manual/bulk edit fills in enough
     * new detail (e.g. Album + Album Artist) that a track which previously had
     * insufficient info to search with now does. Reuses the exact same
     * `AlbumGrouper`/`QueryGroup`/`processGroup` path a full `organize()` run uses,
     * just seeded from the database instead of a fresh SAF scan. */
    suspend fun requeryTracks(paths: Collection<String>, options: Options) {
        if (paths.isEmpty()) return
        val entities = db.trackDao().getByPaths(paths.toList())
        if (entities.isEmpty()) return
        val tracks = entities.map { it.toTrackMetadata() }

        val mbClient = MusicBrainzClient(options.musicBrainzContact)
        val geminiClient = GeminiGroundingClient(options.geminiApiKey, queryCacheDao = db.queryCacheDao())
        val queryGroup = QueryGroup(mbClient, coverArtClient, db.queryCacheDao())

        for (group in AlbumGrouper.groupIntoAlbums(tracks)) {
            val resolved = processGroup(queryGroup, geminiClient, group)
            if (resolved.status == FileStatus.AUTO_MATCHED && resolved.chosenReleaseId != null) {
                discoverAlbumSiblings(mbClient, resolved)
            }
        }
    }

    /** Once a group is confidently matched to a real MusicBrainz release, checks
     * whether that release has track positions the group's own files didn't claim -
     * a strong, concrete signal (not a folder-name guess) that more of the same
     * album might be sitting nearby unmatched. Only looks at files in the *same
     * immediate folder* as the matched group, and only proposes a sibling as a
     * MATCH_FOUND draft (never writes into its real fields) when its own title
     * fuzzy-matches one of those specific unclaimed positions - per the user's
     * explicit choice of "only if MusicBrainz confirms it" over a looser
     * folder-plus-filename-pattern heuristic. A sibling already confidently matched
     * to something else (`matchedReleaseId != null`) is left alone. */
    private suspend fun discoverAlbumSiblings(mbClient: MusicBrainzClient, resolved: AlbumGroup) {
        val releaseId = resolved.chosenReleaseId ?: return
        val release = try { mbClient.getReleaseTracklist(releaseId) } catch (e: Exception) { return }
        if (release.tracks.isEmpty()) return

        val claimedPositions = resolved.files.mapNotNull { it.trackNumber }.toSet()
        val missingTracks = release.tracks.filterKeys { it !in claimedPositions }
        if (missingTracks.isEmpty()) return

        val groupPaths = resolved.files.map { it.path.value }
        val parents = resolved.files.mapNotNull { it.path.parent()?.value }.toSet()
        val siblings = parents
            .flatMap { parent -> db.trackDao().getPathsUnderFolder(parent, groupPaths).map { parent to it } }
            .distinctBy { it.second.path }
            // getPathsUnderFolder matches nested subfolders too (SQLite has no
            // portable "no further '/'" clause) - keep only direct children.
            .filter { (parent, sibling) -> !sibling.path.substringAfter("$parent/").contains('/') }
            .map { it.second }

        val usedPositions = mutableSetOf<Int>()
        for (sibling in siblings) {
            if (sibling.matchedReleaseId != null) continue
            val remaining = missingTracks.filterKeys { it !in usedPositions }
            if (remaining.isEmpty()) break
            val siblingTitle = sibling.title ?: FilenameParser.parseFilename(LibraryPath(sibling.path)).title
            val position = ReleaseResolver.matchTitleToPosition(siblingTitle, remaining) ?: continue
            usedPositions.add(position)
            val info = remaining.getValue(position)
            db.trackDao().upsert(
                sibling.copy(
                    matchedReleaseId = releaseId,
                    proposedArtist = info.artist?.ifEmpty { null } ?: release.artist,
                    proposedAlbumArtist = release.artist,
                    proposedAlbum = release.album,
                    proposedTitle = ReleaseResolver.formatRemixTitle(info.title),
                    proposedTrackNumber = position,
                    proposedYear = release.year,
                    statusDetail = "Possibly part of \"${release.album ?: "this album"}\" - found via album sibling detection",
                )
            )
        }
    }

    /** `proposed`/`matchedReleaseId` are omitted for the initial tag-reading-phase
     * persist (before any group has been queried yet) - at that point nothing has
     * been matched, and it will be overwritten (upsert on conflict REPLACE) once
     * [processGroup] actually resolves this track's group. [TrackEntity.reviewStatus]
     * is always recomputed from the persisted fields, never decided here. */
    private suspend fun persistTrack(
        track: TrackMetadata,
        proposed: TrackMetadata? = null,
        matchedReleaseId: String? = null,
    ) {
        db.trackDao().upsert(
            TrackEntity(
                path = track.path.value,
                uri = track.uri.toString(),
                fileFormat = track.fileFormat,
                artist = track.artist,
                albumArtist = track.albumArtist,
                album = track.album,
                title = track.title,
                trackNumber = track.trackNumber,
                trackTotal = track.trackTotal,
                discNumber = track.discNumber,
                discTotal = track.discTotal,
                year = track.year,
                genre = track.genre,
                durationSeconds = track.durationSeconds,
                hasCoverArt = track.hasCoverArt,
                coverArtMime = track.coverArtMime,
                composer = track.composer,
                libraryType = track.libraryType,
                fileSizeBytes = track.fileSizeBytes,
                source = track.source,
                status = track.status,
                statusDetail = track.statusDetail,
                matchedReleaseId = matchedReleaseId,
                proposedArtist = proposed?.artist,
                proposedAlbumArtist = proposed?.albumArtist,
                proposedAlbum = proposed?.album,
                proposedTitle = proposed?.title,
                proposedTrackNumber = proposed?.trackNumber,
                proposedYear = proposed?.year,
            )
        )

        if (!track.artist.isNullOrEmpty()) {
            val credits = EdmCreditParser.deriveCredits(track.artist, track.title)
            db.trackDao().clearCredits(track.path.value)
            db.trackDao().upsertCredits(
                credits.map { TrackArtistCreditEntity(trackPath = track.path.value, artistName = it.name, role = it.role.name) }
            )
        }
    }
}
