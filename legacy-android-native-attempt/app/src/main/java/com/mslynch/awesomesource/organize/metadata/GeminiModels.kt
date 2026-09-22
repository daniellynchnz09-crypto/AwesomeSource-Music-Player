package com.mslynch.awesomesource.organize.metadata

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** DTOs for the Gemini `generateContent` REST API. Structured JSON output is
 * requested via `generationConfig.responseMimeType = "application/json"` (see
 * GeminiGroundingClient) so [GroundingVerdict] can be parsed directly out of the
 * single text part, rather than trying to extract JSON from free-form prose. */

@JsonClass(generateAdapter = true)
data class GeminiGenerateContentRequest(
    @Json(name = "contents") val contents: List<GeminiContent>,
    @Json(name = "generationConfig") val generationConfig: GeminiGenerationConfig,
)

@JsonClass(generateAdapter = true)
data class GeminiContent(
    @Json(name = "parts") val parts: List<GeminiPart>,
)

@JsonClass(generateAdapter = true)
data class GeminiPart(
    @Json(name = "text") val text: String,
)

@JsonClass(generateAdapter = true)
data class GeminiGenerationConfig(
    @Json(name = "responseMimeType") val responseMimeType: String = "application/json",
    @Json(name = "temperature") val temperature: Double = 0.1,
)

@JsonClass(generateAdapter = true)
data class GeminiGenerateContentResponse(
    @Json(name = "candidates") val candidates: List<GeminiResponseCandidate>? = null,
)

@JsonClass(generateAdapter = true)
data class GeminiResponseCandidate(
    @Json(name = "content") val content: GeminiContent? = null,
)

/** The structured verdict Gemini is prompted to return as its entire response body. */
@JsonClass(generateAdapter = true)
data class GroundingVerdict(
    @Json(name = "chosen_index") val chosenIndex: Int? = null,
    @Json(name = "confident") val confident: Boolean = false,
    @Json(name = "reasoning") val reasoning: String = "",
)
