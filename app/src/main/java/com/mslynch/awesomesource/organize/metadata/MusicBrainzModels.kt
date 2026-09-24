package com.mslynch.awesomesource.organize.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs for MusicBrainz's JSON web service (the `ws/2/` endpoints, `fmt=json`). The legacy desktop
 * tool used `musicbrainzngs`, a Python library that queries MusicBrainz's XML API
 * and quietly reshapes it into the dict shape `musicbrainz_client.py` reads
 * (including a synthesized `artist-credit-phrase` the library builds for you). No
 * such library exists for Kotlin, so this talks to the JSON API directly and
 * rebuilds that same artist-credit-phrase concatenation itself (see
 * [ArtistCreditDto.phraseSegment]).
 *
 * Every field is nullable/defaulted on purpose, mirroring musicbrainz_client.py's
 * "defensive `.get()` access throughout ... so a schema surprise degrades to a
 * blank field, not a crash" rule. These shapes were verified against the real live
 * API during the Expo/TypeScript attempt (see
 * `legacy-expo-attempt/src/organize/metadata/musicBrainzClient.ts`) and matched
 * exactly - safe to rely on here, not just best-effort from the public docs.
 */

@JsonClass(generateAdapter = true)
data class ReleaseSearchResponse(
    @Json(name = "releases") val releases: List<ReleaseDto>? = null,
)

@JsonClass(generateAdapter = true)
data class RecordingSearchResponse(
    @Json(name = "recordings") val recordings: List<RecordingDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ReleaseLookupResponse(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<ArtistCreditDto>? = null,
    @Json(name = "media") val media: List<MediumDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ReleaseDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "date") val date: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<ArtistCreditDto>? = null,
    @Json(name = "media") val media: List<MediumDto>? = null,
    @Json(name = "release-group") val releaseGroup: ReleaseGroupDto? = null,
)

@JsonClass(generateAdapter = true)
data class RecordingDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<ArtistCreditDto>? = null,
    @Json(name = "releases") val releases: List<ReleaseDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ArtistCreditDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "joinphrase") val joinphrase: String? = null,
) {
    fun phraseSegment(): String = (name ?: "") + (joinphrase ?: "")
}

@JsonClass(generateAdapter = true)
data class MediumDto(
    @Json(name = "track-count") val trackCount: Int? = null,
    @Json(name = "track") val tracks: List<TrackDto>? = null,
    @Json(name = "position") val position: Int? = null,
)

@JsonClass(generateAdapter = true)
data class TrackDto(
    @Json(name = "position") val position: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "recording") val recording: RecordingRefDto? = null,
)

@JsonClass(generateAdapter = true)
data class RecordingRefDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "artist-credit") val artistCredit: List<ArtistCreditDto>? = null,
)

@JsonClass(generateAdapter = true)
data class ReleaseGroupDto(
    @Json(name = "primary-type") val primaryType: String? = null,
)

fun List<ArtistCreditDto>?.toPhrase(): String = this.orEmpty().joinToString("") { it.phraseSegment() }
