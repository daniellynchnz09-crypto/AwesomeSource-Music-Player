package com.mslynch.awesomesource.organize.pipeline

import android.content.Context
import android.net.Uri
import com.mslynch.awesomesource.organize.grouping.AlbumGrouper
import com.mslynch.awesomesource.organize.metadata.CoverArtClient
import com.mslynch.awesomesource.organize.metadata.GeminiGroundingClient
import com.mslynch.awesomesource.organize.metadata.MusicBrainzClient
import com.mslynch.awesomesource.organize.model.AlbumGroup
import com.mslynch.awesomesource.organize.model.FileStatus
import com.mslynch.awesomesource.organize.model.LibraryType
import com.mslynch.awesomesource.organize.model.MetadataSource
import com.mslynch.awesomesource.organize.model.TrackMetadata
import com.mslynch.awesomesource.organize.persistence.AppDatabase
import com.mslynch.awesomesource.organize.persistence.entity.ScanSessionEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackArtistCreditEntity
import com.mslynch.awesomesource.organize.persistence.entity.TrackEntity
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

        val readable = tracks.filter { it.status != FileStatus.UNREADABLE }
        val groups = AlbumGrouper.groupIntoAlbums(readable)
        options.onProgress?.invoke(Progress(Phase.GROUPING, groups.size, groups.size))

        val mbClient = MusicBrainzClient(options.musicBrainzContact)
        val geminiClient = GeminiGroundingClient(options.geminiApiKey)
        val queryGroup = QueryGroup(mbClient, coverArtClient, db.queryCacheDao())
        for ((i, group) in groups.withIndex()) {
            processGroup(queryGroup, geminiClient, group)
            options.onProgress?.invoke(Progress(Phase.QUERYING, i + 1, groups.size))
        }

        db.undoLogDao().completeSession(sessionId, Instant.now().toString())
    }

    /** Reads embedded tags when the format supports them; falls back to sidecar
     * metadata (WAV etc. - Claude/MUSIC ORGANIZATION.md's "list document" concept)
     * or a filename guess otherwise. One corrupt/unsupported file can't crash the
     * whole scan - it's marked Unreadable and skipped, matching the Python
     * original's rule. */
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
                return bare.copy(libraryType = libraryType, status = FileStatus.UNREADABLE, statusDetail = outcome.error)
            }
            AudioTagReader.Outcome.UnsupportedFormat -> {
                // WAV/OGG-without-tag-support: sidecar metadata first, then a
                // filename guess.
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
                    )
                }

                val guess = FilenameParser.parseFilename(bare.path)
                return bare.copy(
                    artist = guess.artist,
                    album = guess.album,
                    title = guess.title,
                    trackNumber = guess.trackNumber,
                    libraryType = libraryType,
                    source = MetadataSource.FILENAME_GUESS,
                    status = if (guess.confidence == FilenameParser.Confidence.STRUCTURED) FileStatus.PENDING else FileStatus.INSUFFICIENT_INFO,
                    statusDetail = if (guess.confidence == FilenameParser.Confidence.LOOSE) {
                        "loose filename guess: ${guess.searchText ?: "(no usable text)"}"
                    } else "",
                )
            }
        }
    }

    private suspend fun processGroup(queryGroup: QueryGroup, geminiClient: GeminiGroundingClient, group: AlbumGroup) {
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
                resolved = resolved.copy(
                    status = FileStatus.AUTO_MATCHED,
                    chosenReleaseId = verdict.chosen.releaseId,
                    statusDetail = "Gemini-grounded: ${verdict.reasoning}",
                )
            }
        }

        for (track in resolved.files) {
            val withStatus = track.copy(
                status = resolved.status,
                statusDetail = resolved.statusDetail.ifEmpty { track.statusDetail },
                source = if (resolved.status == FileStatus.AUTO_MATCHED) MetadataSource.ONLINE_LOOKUP else track.source,
            )
            persistTrack(withStatus)
        }
    }

    private suspend fun persistTrack(track: TrackMetadata) {
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
