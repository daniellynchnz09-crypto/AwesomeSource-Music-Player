package com.mslynch.awesomesource.organize.matching

import kotlin.math.max
import kotlin.math.min

/**
 * A from-scratch Kotlin re-implementation of the specific `rapidfuzz`/`fuzzywuzzy`
 * string-similarity functions [Scorer] depends on (`ratio`, `token_sort_ratio`,
 * `token_set_ratio`, `WRatio`) - there's no direct Android/JVM equivalent of
 * `rapidfuzz` to depend on instead.
 *
 * IMPORTANT: this reproduces the *algorithms* (same indel-distance ratio, same
 * token-sort/token-set/partial-ratio combination that WRatio takes the max of), but
 * is not guaranteed to return bit-identical scores to `rapidfuzz` on every input.
 * `legacy-desktop-tagger/musictagger/matching/scorer.py`'s thresholds
 * (AUTO_APPLY_THRESHOLD=90, NEEDS_REVIEW_THRESHOLD=60, AUTO_APPLY_MARGIN=10) were
 * tuned against real rapidfuzz output - **recalibrate them against real Kotlin
 * output once ported test fixtures are running**, rather than assuming the same
 * numbers hold exactly. Tracked in Claude/To Do list.md.
 */
object FuzzyMatch {

    /** Equivalent to `rapidfuzz.fuzz.ratio`: normalized indel similarity, 0-100. */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 100.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val lcs = lcsLength(a, b)
        return 2.0 * lcs / (a.length + b.length) * 100.0
    }

    /** Equivalent to `rapidfuzz.fuzz.token_sort_ratio`. */
    fun tokenSortRatio(a: String, b: String): Double = ratio(sortedTokenString(a), sortedTokenString(b))

    /** Equivalent to `rapidfuzz.fuzz.token_set_ratio`. */
    fun tokenSetRatio(a: String, b: String): Double {
        val (t0, t1, t2) = tokenSetStrings(a, b)
        return maxOf(ratio(t0, t1), ratio(t0, t2), ratio(t1, t2))
    }

    /** Equivalent to `rapidfuzz.fuzz.partial_ratio`: best alignment of the shorter
     * string against every equal-length window of the longer one. */
    fun partialRatio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        if (shorter.length == longer.length) return ratio(shorter, longer)
        var best = 0.0
        for (i in 0..(longer.length - shorter.length)) {
            val window = longer.substring(i, i + shorter.length)
            best = max(best, ratio(shorter, window))
            if (best >= 100.0) return 100.0
        }
        return best
    }

    private fun partialTokenSortRatio(a: String, b: String): Double =
        partialRatio(sortedTokenString(a), sortedTokenString(b))

    private fun partialTokenSetRatio(a: String, b: String): Double {
        val (t0, t1, t2) = tokenSetStrings(a, b)
        return maxOf(partialRatio(t0, t1), partialRatio(t0, t2), partialRatio(t1, t2))
    }

    /**
     * Equivalent to `rapidfuzz.fuzz.WRatio`: takes the best of the plain ratio and
     * several token-based/partial variants, weighted down so a full match on the
     * plain ratio always wins outright. Callers should pass already-lowercased,
     * whitespace-normalized text (see `Scorer._normalize`) - WRatio itself does not
     * fold case.
     */
    fun wRatio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val base = ratio(a, b)
        val lenRatio = max(a.length, b.length).toDouble() / min(a.length, b.length).toDouble()
        val unbaseScale = 0.95

        return if (lenRatio < 1.5) {
            val tsor = tokenSortRatio(a, b) * unbaseScale
            val tser = tokenSetRatio(a, b) * unbaseScale
            maxOf(base, tsor, tser)
        } else {
            val partialScale = if (lenRatio < 8.0) 0.90 else 0.60
            val partial = partialRatio(a, b) * partialScale
            val ptsor = partialTokenSortRatio(a, b) * unbaseScale * partialScale
            val ptser = partialTokenSetRatio(a, b) * unbaseScale * partialScale
            maxOf(base, partial, ptsor, ptser)
        }
    }

    private fun tokenize(s: String): List<String> =
        s.split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun sortedTokenString(s: String): String = tokenize(s).sorted().joinToString(" ")

    /** The three strings token_set_ratio/partial_token_set_ratio compare pairwise:
     * the sorted shared-token intersection alone, and the intersection plus each
     * side's own leftover tokens. */
    private fun tokenSetStrings(a: String, b: String): Triple<String, String, String> {
        val tokensA = tokenize(a).toSet()
        val tokensB = tokenize(b).toSet()
        val intersection = tokensA.intersect(tokensB).sorted().joinToString(" ")
        val diffA = (tokensA - tokensB).sorted().joinToString(" ")
        val diffB = (tokensB - tokensA).sorted().joinToString(" ")
        val t1 = listOf(intersection, diffA).filter { it.isNotEmpty() }.joinToString(" ")
        val t2 = listOf(intersection, diffB).filter { it.isNotEmpty() }.joinToString(" ")
        return Triple(intersection, t1, t2)
    }

    private fun lcsLength(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1 else max(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}
