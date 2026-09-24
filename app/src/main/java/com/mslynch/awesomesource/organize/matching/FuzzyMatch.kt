package com.mslynch.awesomesource.organize.matching

import me.xdrop.fuzzywuzzy.FuzzySearch
import me.xdrop.fuzzywuzzy.ToStringFunction

/**
 * Thin wrapper around `me.xdrop:fuzzywuzzy` - a real, actively-published Java port
 * of the same `fuzzywuzzy`/`rapidfuzz` family the Python original depends on.
 *
 * The first (pre-Expo-detour) native attempt at this file was a from-scratch
 * reimplementation of these algorithms, with an explicit "recalibrate thresholds
 * against real output" caveat attached, since there was no way to verify it against
 * the real library in that session. The Expo/TypeScript attempt found and used
 * `fuzzball` (the JS equivalent of this same library) instead, and its ported test
 * suite passed with the Python original's thresholds completely unchanged - no
 * recalibration needed. This wrapper carries that same fix into Kotlin: [Scorer]'s
 * calls below are unchanged from the old hand-rolled version, since this preserves
 * the exact same function signatures.
 *
 * `ToStringFunction.NO_PROCESS` matches fuzzball's `full_process: false` - this
 * module's own `Scorer.normalize()` is the single source of text cleanup, exactly
 * as in the Python original; letting the library apply its own default
 * pre-processing on top would double-normalize and diverge from the tuned
 * thresholds.
 */
object FuzzyMatch {

    /** Equivalent to `rapidfuzz.fuzz.ratio`: normalized indel similarity, 0-100. */
    fun ratio(a: String, b: String): Double = FuzzySearch.ratio(a, b, ToStringFunction.NO_PROCESS).toDouble()

    /** Equivalent to `rapidfuzz.fuzz.token_sort_ratio`. */
    fun tokenSortRatio(a: String, b: String): Double =
        FuzzySearch.tokenSortRatio(a, b, ToStringFunction.NO_PROCESS).toDouble()

    /** Equivalent to `rapidfuzz.fuzz.token_set_ratio`. */
    fun tokenSetRatio(a: String, b: String): Double =
        FuzzySearch.tokenSetRatio(a, b, ToStringFunction.NO_PROCESS).toDouble()

    /** Equivalent to `rapidfuzz.fuzz.WRatio`: takes the best of the plain ratio and
     * several token-based/partial variants, weighted down so a full match on the
     * plain ratio always wins outright. Callers should pass already-lowercased,
     * whitespace-normalized text (see `Scorer.normalize`) - WRatio itself does not
     * fold case. */
    fun wRatio(a: String, b: String): Double =
        FuzzySearch.weightedRatio(a, b, ToStringFunction.NO_PROCESS).toDouble()
}
