package com.mslynch.awesomesource.organize.metadata

import retrofit2.http.GET
import retrofit2.http.Query

/** AcoustID's public lookup API. Base URL: https://api.acoustid.org/v2/ */
interface AcoustIdApi {
    @GET("lookup")
    suspend fun lookup(
        @Query("client") clientApiKey: String,
        @Query("duration") durationSeconds: Int,
        @Query("fingerprint") fingerprint: String,
        @Query("meta") meta: String = "recordings",
        // AcoustID's lookup endpoint defaults to XML without this - a real bug
        // found (and fixed) while porting this same client to TypeScript, see
        // legacy-expo-attempt/README.md.
        @Query("format") format: String = "json",
    ): AcoustIdLookupResponse
}
