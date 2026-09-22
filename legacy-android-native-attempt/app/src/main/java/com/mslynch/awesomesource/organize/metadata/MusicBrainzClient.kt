package com.mslynch.awesomesource.organize.metadata

import com.mslynch.awesomesource.organize.model.MbCandidate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException

/**
 * Ported from `legacy-desktop-tagger/musictagger/metadata_sources/musicbrainz_client.py`.
 * Wraps [MusicBrainzApi]: enforces MusicBrainz's required ~1 request/second rate
 * limit (the Python version got this for free from `musicbrainzngs`; here it's a
 * small mutex-guarded delay around every call, so bulk scans - however many
 * coroutines kick off queries - still only ever hit MusicBrainz serially), retries
 * on failure with backoff, and maps raw responses into [MbCandidate].
 *
 * `contact` is the User-Agent identification string MusicBrainz's API etiquette
 * asks for - **user-editable in Settings, never hardcoded** (see
 * organize/settings/SecureSettings.kt), matching the Python version's identical rule.
 */
class MusicBrainzClient(
    private val contact: String?,
    baseUrl: String = "https://musicbrainz.org/ws/2/",
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    class MusicBrainzException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val userAgent = "AwesomeSource/0.1 (${contact ?: "no contact info provided"})"

    private val api: MusicBrainzApi = Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(
            okHttpClient.newBuilder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                    chain.proceed(request)
                }
                .build()
        )
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(MusicBrainzApi::class.java)

    private val rateLimitMutex = Mutex()
    private var lastCallAtMillis = 0L
    private val minIntervalMillis = 1100L

    private suspend fun rateLimited() {
        rateLimitMutex.withLock {
            val waitFor = minIntervalMillis - (System.currentTimeMillis() - lastCallAtMillis)
            if (waitFor > 0) delay(waitFor)
            lastCallAtMillis = System.currentTimeMillis()
        }
    }

    private val retryDelaysMillis = longArrayOf(1000, 2000, 4000)

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var lastError: Throwable? = null
        val attempts = longArrayOf(0) + retryDelaysMillis
        for (delayMillis in attempts) {
            if (delayMillis > 0) delay(delayMillis)
            try {
                rateLimited()
                return block()
            } catch (e: IOException) {
                lastError = e
            } catch (e: HttpException) {
                lastError = e
            }
        }
        throw MusicBrainzException(lastError?.message ?: "MusicBrainz request failed", lastError)
    }

    /**
     * Runs a search with every field required (an AND'd, quoted Lucene query), and
     * only if that finds nothing, retries with a loose OR-style query. See
     * musicbrainz_client.py's `_search_strict_first` doc comment: an unquoted OR
     * query can be hijacked when a stylized artist name's words are also common in
     * song titles (its cited real example: artist "SVDDEN DEATH" + title "Demonic
     * Curse" returning only unrelated "Death Curse" songs).
     */
    private fun luceneQuery(strict: Boolean, fields: Map<String, String>): String =
        if (strict) {
            fields.entries.joinToString(" AND ") { (field, value) -> "$field:\"${escapeLucene(value)}\"" }
        } else {
            fields.entries.joinToString(" ") { (field, value) -> "$field:(${escapeLucene(value)})" }
        }

    private fun escapeLucene(value: String): String =
        value.replace(Regex("""([+\-!(){}\[\]^"~*?:\\/])"""), "\\\\$1")

    suspend fun searchReleaseCandidates(artist: String, album: String?, limit: Int = 5): List<MbCandidate> {
        val fields = buildMap {
            put("artist", artist)
            if (!album.isNullOrEmpty()) put("release", album)
        }
        val strictResult = withRetry { api.searchReleases(luceneQuery(true, fields), limit) }.releases.orEmpty()
        val result = strictResult.ifEmpty {
            withRetry { api.searchReleases(luceneQuery(false, fields), limit) }.releases.orEmpty()
        }
        return result.map { releaseToCandidate(it) }
    }

    suspend fun searchRecordingCandidates(artist: String, title: String, limit: Int = 5): List<MbCandidate> {
        val fields = mapOf("artist" to artist, "recording" to title)
        val strictResult = withRetry { api.searchRecordings(luceneQuery(true, fields), limit) }.recordings.orEmpty()
        val result = strictResult.ifEmpty {
            withRetry { api.searchRecordings(luceneQuery(false, fields), limit) }.recordings.orEmpty()
        }
        return result.map { recordingToCandidate(it) }
    }

    data class ReleaseTrack(val title: String, val artist: String?)
    data class ReleaseTracklist(
        val artist: String?,
        val album: String?,
        val year: Int?,
        val tracks: Map<Int, ReleaseTrack>,
    )

    /** Fetches the authoritative tracklist for a chosen release. Each track's own
     * artist-credit is captured separately from the release-level one, since a
     * various-artists compilation's release-level artist is never the right artist
     * for an individual track - see musicbrainz_client.py's identical comment. */
    suspend fun getReleaseTracklist(releaseId: String): ReleaseTracklist {
        val release = withRetry { api.getRelease(releaseId) }
        val tracks = mutableMapOf<Int, ReleaseTrack>()
        for (medium in release.media.orEmpty()) {
            for (track in medium.tracks.orEmpty()) {
                val position = track.position ?: continue
                val title = track.title ?: track.recording?.title ?: continue
                tracks[position] = ReleaseTrack(
                    title = title,
                    artist = track.recording?.artistCredit.toPhrase().ifEmpty { null },
                )
            }
        }
        val year = release.date?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) }?.take(4)?.toInt()
        return ReleaseTracklist(
            artist = release.artistCredit.toPhrase().ifEmpty { null },
            album = release.title,
            year = year,
            tracks = tracks,
        )
    }

    private fun releaseToCandidate(release: ReleaseDto): MbCandidate = MbCandidate(
        releaseId = release.id.orEmpty(),
        title = release.title.orEmpty(),
        artistCredit = release.artistCredit.toPhrase(),
        firstReleaseDate = release.date,
        trackCount = release.media?.sumOf { it.trackCount ?: 0 }?.takeIf { it > 0 },
    )

    // A track very commonly appears on both a standalone single AND a
    // various-artists compilation; MusicBrainz doesn't guarantee any particular
    // order for a recording's linked releases, so picking index 0 essentially
    // picks at random. Prefer "Album"/"EP" over "Single"/anything else, matching
    // musicbrainz_client.py's identical ranking.
    private val releaseTypeRanks = mapOf("Album" to 0, "EP" to 1, "Single" to 2)
    private val defaultReleaseTypeRank = 3

    private fun rankOfReleaseType(release: ReleaseDto): Int =
        releaseTypeRanks[release.releaseGroup?.primaryType] ?: defaultReleaseTypeRank

    private fun bestLinkedRelease(releases: List<ReleaseDto>): ReleaseDto? =
        releases.minWithOrNull(
            compareBy<ReleaseDto> { rankOfReleaseType(it) }
                // A blank/missing date sorts last among equally-ranked releases.
                .thenBy { it.date.isNullOrEmpty() }
                .thenBy { it.date ?: "" }
        )

    private fun recordingToCandidate(recording: RecordingDto): MbCandidate {
        val best = bestLinkedRelease(recording.releases.orEmpty())
        return MbCandidate(
            releaseId = best?.id ?: recording.id.orEmpty(),
            title = recording.title.orEmpty(),
            artistCredit = recording.artistCredit.toPhrase(),
            firstReleaseDate = best?.date,
            isRecording = true,
            album = best?.title,
        )
    }
}
