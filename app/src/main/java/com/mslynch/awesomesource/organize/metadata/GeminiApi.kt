package com.mslynch.awesomesource.organize.metadata

import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Google Gemini's public REST API. Base URL:
 * https://generativelanguage.googleapis.com/v1beta/ */
interface GeminiApi {
    @POST("models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body request: GeminiGenerateContentRequest,
    ): GeminiGenerateContentResponse
}
