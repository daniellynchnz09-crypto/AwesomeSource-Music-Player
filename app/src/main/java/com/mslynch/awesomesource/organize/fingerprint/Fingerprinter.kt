package com.mslynch.awesomesource.organize.fingerprint

import android.net.Uri

/**
 * Abstraction over generating a Chromaprint audio fingerprint from a track, for the
 * classical-music case in Claude/MUSIC ORGANIZATION.md: some titles were previously
 * hand-edited into a form MusicBrainz text search can no longer match, so the
 * fallback is to fingerprint the audio itself and resolve it via AcoustID (see
 * [com.mslynch.awesomesource.organize.metadata.AcoustIdClient]) instead of text search.
 *
 * **Not yet implemented - still an open spike, across all three attempts at this
 * app so far.** Findings from researching this during the Expo/React Native
 * attempt (see `legacy-expo-attempt/README.md`) still apply: no maintained
 * React-Native-ecosystem wrapper for the real libchromaprint C library was found,
 * and no maintained Android/JVM Maven-published wrapper was found either - a
 * from-scratch reimplementation risks producing fingerprints AcoustID's
 * crowdsourced database won't match, which is worse than not having the feature at
 * all. For native Kotlin specifically, the credible path is:
 *  1. A native Chromaprint build compiled for Android's ABIs (arm64-v8a,
 *     armeabi-v7a, x86_64) exposed via a small JNI wrapper (Android's NDK toolchain
 *     is genuinely available now, unlike either prior attempt's dev environment) -
 *     most control, but needs vetting whichever prebuilt `.so`/source is used.
 *  2. Decoding audio to raw PCM (Media3/ExoPlayer's decoder can do this) and
 *     reimplementing Chromaprint's chroma-feature + fingerprint algorithm directly
 *     in Kotlin - avoids native code entirely but is a substantial undertaking on
 *     its own, and the same "must match AcoustID's real algorithm exactly" risk
 *     applies as it did to the JS reimplementation researched during the Expo pass.
 * Tracked in Claude/To Do list.md; this interface exists now so the rest of the
 * classical-music matching flow can be wired against it without waiting on that
 * decision.
 */
interface Fingerprinter {
    /** Returns a Chromaprint fingerprint string (as AcoustID's API expects) and the
     * track duration in whole seconds, or null if fingerprinting isn't available/failed. */
    suspend fun fingerprint(uri: Uri, durationSeconds: Int): FingerprintResult?
}

data class FingerprintResult(val chromaprint: String, val durationSeconds: Int)
