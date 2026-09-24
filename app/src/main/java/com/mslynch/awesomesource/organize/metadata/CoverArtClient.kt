package com.mslynch.awesomesource.organize.metadata

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Ported from `legacy-desktop-tagger/musictagger/metadata_sources/cover_art_client.py`.
 * Cover Art Archive lookups keyed by MusicBrainz release ID. A 404 (no art
 * archived) is common/expected, not an error - callers proceed with tag fields only.
 *
 * Downloads to a cache file (under [cacheDir]) rather than returning raw bytes -
 * the same reasoning as the Expo attempt's `metadata/coverArtClient.ts` (a
 * `file://` `Uri` is what an `Image` composable wants directly, and this avoids
 * holding a whole image's bytes in memory). Wrapped in `Dispatchers.IO` since
 * OkHttp's synchronous `Call.execute()` blocks - unlike the original Kotlin
 * attempt's version of this class, which called it directly and would have
 * blocked whatever thread called it.
 */
class CoverArtClient(
    private val cacheDir: File,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) {
    private suspend fun downloadToCache(url: String, filename: String): Uri? = withContext(Dispatchers.IO) {
        val coverArtDir = File(cacheDir, "cover-art").apply { mkdirs() }
        val destination = File(coverArtDir, filename)
        if (destination.exists()) return@withContext destination.toUri()

        val request = Request.Builder().url(url).build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                // A 404 (no art archived for this release) is expected and common,
                // not an error - both it and any other non-2xx response are treated
                // the same way (return null, tags-only) rather than throwing,
                // matching the Python original's "404 is expected" rule.
                if (!response.isSuccessful) return@withContext null
                val body = response.body ?: return@withContext null
                destination.outputStream().use { output -> body.byteStream().copyTo(output) }
                destination.toUri()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Fetches the front-cover thumbnail (500px) for a release - used for UI
     * previews (review panel, candidate picker) where a full-resolution image
     * isn't needed. */
    suspend fun fetchFrontThumbnail(releaseId: String): Uri? =
        downloadToCache("https://coverartarchive.org/release/$releaseId/front-500", "$releaseId-thumb.jpg")

    /** Fetches the full-resolution front cover - only called once a match is
     * actually committed (see `pipeline/ReleaseResolver.kt`), to avoid downloading
     * large images for candidates that get rejected during review. */
    suspend fun fetchFullImage(releaseId: String): Uri? =
        downloadToCache("https://coverartarchive.org/release/$releaseId/front", "$releaseId-full.jpg")
}
