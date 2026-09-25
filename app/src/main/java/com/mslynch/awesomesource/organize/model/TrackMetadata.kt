package com.mslynch.awesomesource.organize.model

import android.net.Uri
import com.squareup.moshi.JsonClass

/**
 * Mirrors `musictagger.models.TrackMetadata`, extended with fields
 * Claude/MUSIC ORGANIZATION.md calls for that the legacy tool didn't need:
 * [composer] (classical works are catalogued by composer, separately from the
 * performing artist/orchestra credited as `artist`), [libraryType] (EDM vs.
 * classical vs. other), and [artistCredits] (EDM tracks split one artist-credit
 * entry per collaborating artist rather than one combined string - see
 * organize/tags/EdmCreditParser.kt).
 *
 * [uri] is the real, openable SAF content URI for I/O; [path] is the
 * library-root-relative path string used for grouping/parsing logic (see
 * LibraryPath's doc comment for why these are separate on Android).
 */
data class TrackMetadata(
    val uri: Uri,
    val path: LibraryPath,
    val fileFormat: String = "",
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val title: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val durationSeconds: Double? = null,
    val hasCoverArt: Boolean = false,
    val coverArtMime: String? = null,
    /** A local `file://`-loadable path to a cached copy of this track's artwork
     * (embedded tag art extracted by `AudioTagReader`, or a sidecar's cover), for
     * the Library row thumbnail - see `ui/LibraryScreen.kt`'s `TrackRow`. Distinct
     * from [hasCoverArt] (a plain boolean flag used elsewhere) since actually
     * *displaying* a thumbnail needs a real loadable path, not just a yes/no. */
    val coverArtPath: String? = null,
    val composer: String? = null,
    val libraryType: LibraryType? = null,
    val artistCredits: List<ArtistCredit> = emptyList(),
    val fileSizeBytes: Long = 0L,
    val source: MetadataSource = MetadataSource.EMBEDDED_TAGS,
    val status: FileStatus = FileStatus.PENDING,
    val statusDetail: String = "",
) {
    fun displayName(): String = path.name()
}

/**
 * One artist credit on a track, per Claude/MUSIC ORGANIZATION.md's EDM crediting
 * rules: a collaboration splits into one entry per artist rather than a single
 * combined string; a remix credits the remixer as [role] ARTIST and the original
 * act(s) as COMPOSER; a solo-artist VIP credits the VIP artist as ARTIST and the
 * original as COMPOSER, while a collab-VIP (only one member did the VIP) keeps the
 * original collaborators as ARTIST.
 */
data class ArtistCredit(
    val name: String,
    val role: ArtistCreditRole,
)

enum class ArtistCreditRole { ARTIST, COMPOSER }

/** Mirrors `musictagger.models.MBCandidate`. `@JsonClass` since ranked candidate
 * lists are cached verbatim (see organize/pipeline/QueryGroup.kt) via Moshi. */
@JsonClass(generateAdapter = true)
data class MbCandidate(
    val releaseId: String,
    val title: String,
    val artistCredit: String,
    val firstReleaseDate: String? = null,
    val trackCount: Int? = null,
    var score: Double = 0.0,
    val isRecording: Boolean = false,
    val album: String? = null,
)

/** Mirrors `musictagger.models.AlbumGroup`. */
data class AlbumGroup(
    val groupKey: String,
    val files: List<TrackMetadata>,
    val bestGuessArtist: String? = null,
    val bestGuessAlbum: String? = null,
    val isSingleton: Boolean = false,
    val flaggedInconsistent: Boolean = false,
    val status: FileStatus = FileStatus.PENDING,
    val statusDetail: String = "",
    val candidates: List<MbCandidate> = emptyList(),
    val chosenReleaseId: String? = null,
    /** File path -> proposed metadata from [ReleaseResolver.resolveGroupToProposed],
     * keyed by [TrackMetadata.path]'s string value. Populated whenever a match is
     * actually resolved (auto-apply or Gemini-grounded) so the per-track draft data
     * a [ReviewStatus.MATCH_FOUND] track needs to display is available to whoever
     * persists the result - previously this resolution ran but its result was
     * discarded, so no drafted proposal was ever actually kept anywhere. */
    val proposedByPath: Map<String, TrackMetadata> = emptyMap(),
)
