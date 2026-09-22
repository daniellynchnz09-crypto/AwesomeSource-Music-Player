package com.mslynch.awesomesource.organize.metadata

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Ported from `legacy-desktop-tagger/musictagger/metadata_sources/cover_art_client.py`.
 * Cover Art Archive lookups keyed by MusicBrainz release ID. A 404 (no art
 * archived) is common/expected, not an error - callers proceed with tag fields only.
 */
class CoverArtClient(private val okHttpClient: OkHttpClient = OkHttpClient()) {

    /** Fetches the front-cover thumbnail (500px) for a release, or null if none is
     * archived (a 404, which is a normal outcome, not a failure). */
    fun fetchFrontThumbnail(releaseId: String): ByteArray? {
        val request = Request.Builder()
            .url("https://coverartarchive.org/release/$releaseId/front-500")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) return null
            return response.body?.bytes()
        }
    }
}
