package com.mslynch.awesomesource.organize.metadata

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** MusicBrainz's public JSON web service. Base URL: https://musicbrainz.org/ws/2/ */
interface MusicBrainzApi {

    @GET("release/")
    suspend fun searchReleases(
        @Query("query") query: String,
        @Query("limit") limit: Int,
        @Query("fmt") format: String = "json",
    ): ReleaseSearchResponse

    @GET("recording/")
    suspend fun searchRecordings(
        @Query("query") query: String,
        @Query("limit") limit: Int,
        @Query("fmt") format: String = "json",
    ): RecordingSearchResponse

    @GET("release/{id}")
    suspend fun getRelease(
        @Path("id") releaseId: String,
        @Query("inc") includes: String = "recordings+artist-credits",
        @Query("fmt") format: String = "json",
    ): ReleaseLookupResponse
}
