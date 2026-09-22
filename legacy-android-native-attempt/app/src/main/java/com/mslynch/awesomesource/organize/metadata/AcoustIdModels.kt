package com.mslynch.awesomesource.organize.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AcoustIdLookupResponse(
    @Json(name = "status") val status: String? = null,
    @Json(name = "results") val results: List<AcoustIdResult>? = null,
)

@JsonClass(generateAdapter = true)
data class AcoustIdResult(
    @Json(name = "id") val id: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "recordings") val recordings: List<AcoustIdRecording>? = null,
)

@JsonClass(generateAdapter = true)
data class AcoustIdRecording(
    @Json(name = "id") val id: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "artists") val artists: List<AcoustIdArtist>? = null,
)

@JsonClass(generateAdapter = true)
data class AcoustIdArtist(
    @Json(name = "id") val id: String? = null,
    @Json(name = "name") val name: String? = null,
)
