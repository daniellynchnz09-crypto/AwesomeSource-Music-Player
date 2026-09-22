package com.mslynch.awesomesource.organize.matching

import com.mslynch.awesomesource.organize.model.MbCandidate
import kotlin.math.abs

/**
 * Ported from `legacy-desktop-tagger/musictagger/matching/scorer.py`. Scores
 * MusicBrainz candidates against local (tag/filename-derived) data and decides
 * whether a match is confident enough to auto-apply, needs human review, or should
 * be treated as no match. Pure logic, no I/O - unit test this the same way
 * `tests/test_scorer.py` did for the Python version, using the same edge cases, so
 * the port doesn't regress bugs that version's comments document already having
 * been found and fixed once.
 *
 * See [FuzzyMatch]'s doc comment: the underlying similarity functions are a
 * from-scratch re-implementation of rapidfuzz, not a dependency on it, so these
 * threshold constants may need recalibration once real ported-test output is in.
 */
object Scorer {

    const val AUTO_APPLY_THRESHOLD = 90.0
    const val NEEDS_REVIEW_THRESHOLD = 60.0
    const val AUTO_APPLY_MARGIN = 10.0

    private const val ARTIST_WEIGHT = 0.45
    private const val ALBUM_WEIGHT = 0.35
    private const val TRACK_COUNT_WEIGHT = 0.15
    private const val YEAR_WEIGHT = 0.05

    // A recording (track-level) search is already filtered *by* the target artist,
    // so artist_score is nearly non-discriminating for recordings; title match must
    // dominate there instead (see scorer.py's identical comment).
    private const val RECORDING_ARTIST_WEIGHT = 0.30
    private const val RECORDING_TITLE_WEIGHT = 0.70
    private const val RECORDING_ALBUM_HINT_WEIGHT = 0.35

    private val WHITESPACE_RE = Regex("\\s+")
    private val APOSTROPHE_RE = Regex("[‘’ʼ]")
    private val VERSION_GROUP_RE = Regex("[(\\[]([^)\\]]*)[)\\]]")
    private val NON_DISTINGUISHING_VERSION_RE = Regex("^(?:(?:feat|ft|featuring|with)\\b|original mix$)", RegexOption.IGNORE_CASE)
    private val DIGITS_RE = Regex("\\d+")
    private const val VERSION_MISMATCH_TITLE_CAP = 60.0

    private fun normalize(text: String?): String {
        val folded = APOSTROPHE_RE.replace(text ?: "", "'")
        return WHITESPACE_RE.replace(folded.trim(), " ").lowercase()
    }

    private fun versionMarkers(text: String): String {
        val groups = VERSION_GROUP_RE.findAll(text)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() && !NON_DISTINGUISHING_VERSION_RE.containsMatchIn(it) }
            .toList()
        return groups.joinToString(" ").replace("×", " x ").replace(Regex("[^\\w ]+"), " ").trim()
    }

    /** WRatio similarity between two already-normalized titles, capped when their
     * version credits or numerals differ (see scorer.py's identical comment for the
     * real false-tie cases this fixes: "Kereberot" vs "Kereberot (D'LION remix)"). */
    private fun titleScore(local: String, candidate: String): Double {
        val score = FuzzyMatch.wRatio(local, candidate)
        val localMarkers = versionMarkers(local)
        val candidateMarkers = versionMarkers(candidate)
        if (localMarkers.isNotEmpty() != candidateMarkers.isNotEmpty()) {
            return min(score, VERSION_MISMATCH_TITLE_CAP)
        }
        if (localMarkers.isNotEmpty() && FuzzyMatch.tokenSortRatio(localMarkers, candidateMarkers) < 80.0) {
            return min(score, VERSION_MISMATCH_TITLE_CAP)
        }
        if (DIGITS_RE.findAll(local).map { it.value }.toList() != DIGITS_RE.findAll(candidate).map { it.value }.toList()) {
            return min(score, VERSION_MISMATCH_TITLE_CAP)
        }
        return score
    }

    private fun min(a: Double, b: Double) = if (a < b) a else b

    /**
     * `localAlbum` is compared against `candidate.title` for both callers use this
     * for: an album title (release search) or a track title (recording search, for
     * singleton files). See scorer.py's identical doc comment for why the album
     * component is only included when both sides actually have data, rather than
     * scoring "" against every candidate and silently capping every score.
     */
    fun scoreCandidate(
        localArtist: String?,
        localAlbum: String?,
        localTrackCount: Int?,
        localYear: Int?,
        candidate: MbCandidate,
        localAlbumHint: String? = null,
    ): Double {
        val artistScore = if (candidate.isRecording) {
            FuzzyMatch.tokenSetRatio(normalize(localArtist), normalize(candidate.artistCredit))
        } else {
            FuzzyMatch.wRatio(normalize(localArtist), normalize(candidate.artistCredit))
        }
        val (artistWeight, albumWeight) = if (candidate.isRecording) {
            RECORDING_ARTIST_WEIGHT to RECORDING_TITLE_WEIGHT
        } else {
            ARTIST_WEIGHT to ALBUM_WEIGHT
        }

        val components = mutableListOf(artistWeight to artistScore)

        if (!localAlbum.isNullOrEmpty()) {
            val albumScore = titleScore(normalize(localAlbum), normalize(candidate.title))
            components.add(albumWeight to albumScore)
        }

        if (candidate.isRecording && !localAlbumHint.isNullOrEmpty() && !candidate.album.isNullOrEmpty()) {
            val albumHintScore = FuzzyMatch.tokenSetRatio(normalize(localAlbumHint), normalize(candidate.album))
            components.add(RECORDING_ALBUM_HINT_WEIGHT to albumHintScore)
        }

        if (localTrackCount != null && localTrackCount > 0 && candidate.trackCount != null && candidate.trackCount > 0) {
            val trackCountScore = when {
                localTrackCount == candidate.trackCount -> 100.0
                // Owning fewer tracks than the real release is normal (a partial
                // rip); scale partial credit by coverage rather than treating it
                // as a mismatch - see scorer.py's identical comment.
                localTrackCount < candidate.trackCount -> 50.0 + 50.0 * (localTrackCount.toDouble() / candidate.trackCount)
                // Owning *more* tracks than the release actually has is real
                // evidence against the match.
                else -> 0.0
            }
            components.add(TRACK_COUNT_WEIGHT to trackCountScore)
        }

        if (localYear != null && localYear > 0 && candidate.firstReleaseDate != null &&
            candidate.firstReleaseDate.length >= 4 && candidate.firstReleaseDate.take(4).all { it.isDigit() }
        ) {
            val candidateYear = candidate.firstReleaseDate.take(4).toInt()
            val yearScore = (100.0 - abs(candidateYear - localYear) * 10).coerceAtLeast(0.0)
            components.add(YEAR_WEIGHT to yearScore)
        }

        val totalWeight = components.sumOf { it.first }
        if (totalWeight == 0.0) return 0.0
        return components.sumOf { it.first * it.second } / totalWeight
    }

    /** Returns candidates sorted best-first, each with `.score` populated. */
    fun scoreCandidates(
        localArtist: String?,
        localAlbum: String?,
        localTrackCount: Int?,
        localYear: Int?,
        candidates: List<MbCandidate>,
        localAlbumHint: String? = null,
    ): List<MbCandidate> {
        candidates.forEach {
            it.score = scoreCandidate(localArtist, localAlbum, localTrackCount, localYear, it, localAlbumHint)
        }
        return candidates.sortedByDescending { it.score }
    }

    enum class Outcome { AUTO_APPLY, NEEDS_REVIEW, NO_MATCH }

    data class Decision(val outcome: Outcome, val chosen: MbCandidate?)

    /**
     * See scorer.py's `decide()` doc comment for the full reasoning: MusicBrainz
     * commonly returns several near-identically-scored candidates that are
     * genuinely the same release (different countries/remasters/formats) - that
     * kind of tie must not block auto-apply the way a tie between two actually
     * different albums should. Told apart by whether the near-top candidates agree
     * on normalized (artist, title[, linked album for recordings]).
     */
    fun decide(candidates: List<MbCandidate>): Decision {
        if (candidates.isEmpty()) return Decision(Outcome.NO_MATCH, null)

        val ranked = candidates.sortedByDescending { it.score }
        val top = ranked.first()

        if (top.score < NEEDS_REVIEW_THRESHOLD) return Decision(Outcome.NO_MATCH, null)
        if (top.score < AUTO_APPLY_THRESHOLD) return Decision(Outcome.NEEDS_REVIEW, null)

        val nearTop = ranked.filter { top.score - it.score < AUTO_APPLY_MARGIN }
        val distinctAlbums = nearTop.map {
            Triple(normalize(it.artistCredit), normalize(it.title), if (it.isRecording) normalize(it.album) else "")
        }.toSet()

        return if (distinctAlbums.size <= 1) Decision(Outcome.AUTO_APPLY, top) else Decision(Outcome.NEEDS_REVIEW, null)
    }
}
