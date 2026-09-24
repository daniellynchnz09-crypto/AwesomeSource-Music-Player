package com.mslynch.awesomesource.organize.metadata

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Resolves a Chromaprint fingerprint (see
 * [com.mslynch.awesomesource.organize.fingerprint.Fingerprinter] - fingerprint
 * *generation* is a separate, not-yet-implemented spike) to MusicBrainz recording
 * IDs via AcoustID, for the classical-music renamed-title fallback in
 * Claude/MUSIC ORGANIZATION.md. [apiKey] is the user's own free AcoustID client key,
 * from [com.mslynch.awesomesource.organize.settings.SecureSettings] - never hardcoded.
 *
 * Note: the key currently on file is a *personal/user* AcoustID key (for submitting
 * fingerprints), not an *application* key (needed for this lookup's `client=`
 * parameter) - lookups will return "invalid API key" until it's replaced with one
 * from https://acoustid.org/new-applications (found during the Expo/TypeScript
 * attempt; unresolved regardless of stack).
 */
class AcoustIdClient(
    private val apiKey: String?,
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    data class Match(val recordingId: String, val title: String?, val artist: String?, val score: Double)

    private val api: AcoustIdApi = Retrofit.Builder()
        .baseUrl("https://api.acoustid.org/v2/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(AcoustIdApi::class.java)

    /** Returns matches sorted best-first, or an empty list if no key is configured,
     * nothing matched, or the lookup failed. */
    suspend fun lookup(fingerprint: String, durationSeconds: Int): List<Match> {
        val key = apiKey ?: return emptyList()
        val response = runCatching { api.lookup(key, durationSeconds, fingerprint) }.getOrNull() ?: return emptyList()
        return response.results.orEmpty()
            .sortedByDescending { it.score ?: 0.0 }
            .flatMap { result ->
                result.recordings.orEmpty().map { recording ->
                    Match(
                        recordingId = recording.id.orEmpty(),
                        title = recording.title,
                        artist = recording.artists?.joinToString(", ") { it.name.orEmpty() },
                        score = result.score ?: 0.0,
                    )
                }
            }
            .filter { it.recordingId.isNotEmpty() }
    }
}
