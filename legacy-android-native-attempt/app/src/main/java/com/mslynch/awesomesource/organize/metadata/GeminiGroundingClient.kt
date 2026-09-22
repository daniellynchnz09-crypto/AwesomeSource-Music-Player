package com.mslynch.awesomesource.organize.metadata

import com.mslynch.awesomesource.organize.model.MbCandidate
import com.squareup.moshi.Moshi
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Implements the "LLM double-check" pass from Claude/MUSIC ORGANIZATION.md: "if I
 * get Music Brains to scan the album and then get the claude model ... to double
 * check the album, the [model] would get it right every time based on the
 * information from music brains and the files themselves." The user chose Google
 * Gemini over Claude for this specifically for its free tier (this session's
 * architecture decision) - the prompt/verdict shape below is otherwise
 * model-agnostic and could point at a different provider later without changing
 * any other pipeline code.
 *
 * This is advisory input to the app's own auto-apply/needs-review/no-match
 * decision, not verbatim external website data - Claude.md's "data from websites
 * must be used verbatim" rule covers reference text like the aesthetics
 * definitions quoted in Claude/App DESIGN.md, not runtime metadata matching.
 *
 * [apiKey] comes from [com.mslynch.awesomesource.organize.settings.SecureSettings]
 * - see that class's doc comment for why it's never read from anywhere else.
 * **Unverified against a live response** - no network/JDK toolchain in this dev
 * environment yet; the request/response shapes follow Gemini's public REST API
 * docs but haven't been round-tripped for real (tracked in Claude/To Do list.md).
 */
class GeminiGroundingClient(
    private val apiKey: String?,
    private val model: String = "gemini-2.0-flash",
    okHttpClient: OkHttpClient = OkHttpClient(),
) {
    data class LocalEvidence(
        val artist: String?,
        val album: String?,
        val title: String?,
        val trackCount: Int?,
        val year: Int?,
        val filenameHint: String?,
    )

    data class Verdict(val chosen: MbCandidate?, val confident: Boolean, val reasoning: String)

    private val moshi = Moshi.Builder().build()
    private val verdictAdapter = moshi.adapter(GroundingVerdict::class.java)

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/v1beta/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    /** Returns null (rather than throwing) when no API key is configured, so
     * callers can treat "not set up" the same as "declined to ground this one" and
     * fall back to the MusicBrainz-only scorer decision. */
    suspend fun groundMatch(local: LocalEvidence, candidates: List<MbCandidate>): Verdict? {
        val key = apiKey ?: return null
        if (candidates.isEmpty()) return null

        val prompt = buildPrompt(local, candidates)
        val request = GeminiGenerateContentRequest(
            contents = listOf(GeminiContent(parts = listOf(GeminiPart(prompt)))),
            generationConfig = GeminiGenerationConfig(),
        )
        val response = api.generateContent(model, key, request)
        val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text ?: return null
        val verdict = runCatching { verdictAdapter.fromJson(text) }.getOrNull() ?: return null

        val chosen = verdict.chosenIndex?.let { index -> candidates.getOrNull(index) }
        return Verdict(chosen, verdict.confident, verdict.reasoning)
    }

    private fun buildPrompt(local: LocalEvidence, candidates: List<MbCandidate>): String {
        val candidateLines = candidates.mapIndexed { index, c ->
            "  $index. artist=\"${c.artistCredit}\" title=\"${c.title}\" " +
                "date=${c.firstReleaseDate ?: "?"} trackCount=${c.trackCount ?: "?"} score=${"%.1f".format(c.score)}"
        }.joinToString("\n")

        return """
            You are double-checking a MusicBrainz metadata match for a personal music library organizer.
            Local file evidence (from embedded tags and/or filename): artist="${local.artist ?: "?"}",
            album/title="${local.album ?: local.title ?: "?"}", trackCount=${local.trackCount ?: "?"},
            year=${local.year ?: "?"}, filenameHint="${local.filenameHint ?: "?"}".

            Candidate MusicBrainz matches:
            $candidateLines

            Pick the candidate index that best matches the local evidence, accounting for
            spelling variants, inconsistent formatting, and human error on either side. If none
            of the candidates plausibly match, or you are not confident, say so honestly rather
            than guessing.

            Respond with ONLY a JSON object of this exact shape, no other text:
            {"chosen_index": <integer index or null>, "confident": <true|false>, "reasoning": "<one sentence>"}
        """.trimIndent()
    }
}
