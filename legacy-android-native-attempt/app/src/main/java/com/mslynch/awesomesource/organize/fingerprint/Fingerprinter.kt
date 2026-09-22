package com.mslynch.awesomesource.organize.fingerprint

import android.net.Uri

/**
 * Abstraction over generating a Chromaprint audio fingerprint from a track, for the
 * classical-music case in Claude/MUSIC ORGANIZATION.md: some titles were previously
 * hand-edited into a form MusicBrainz text search can no longer match, so the
 * fallback is to fingerprint the audio itself and resolve it via AcoustID (see
 * [com.mslynch.awesomesource.organize.metadata.AcoustIdClient]) instead of text search.
 *
 * **Not yet implemented - this is the "spike" the plan calls out.** Chromaprint has
 * no pure-Kotlin/JVM implementation; getting it onto Android needs one of:
 *  1. A native Chromaprint build compiled for Android's ABIs (arm64-v8a,
 *     armeabi-v7a, x86_64) exposed via a small JNI wrapper - most control, but
 *     requires an NDK toolchain and vetting whichever prebuilt `.so`/source is used.
 *  2. An existing Android-packaged Chromaprint binding, if one is found to be
 *     actively maintained and trustworthy - needs real research once network
 *     access is available; not assumed here since guessing at a specific Maven
 *     coordinate risks pointing the build at something that doesn't exist or isn't
 *     trustworthy.
 *  3. Decoding audio to raw PCM (Media3/ExoPlayer's decoder can do this) and
 *     reimplementing Chromaprint's chroma-feature + fingerprint algorithm directly
 *     in Kotlin - avoids native code entirely but is a substantial undertaking on
 *     its own.
 * Tracked in Claude/To Do list.md as a Phase 2 follow-up spike; this interface
 * exists now so the rest of the classical-music matching flow can be wired against
 * it without waiting on that decision.
 */
interface Fingerprinter {
    /** Returns a Chromaprint fingerprint string (as AcoustID's API expects) and the
     * track duration in whole seconds, or null if fingerprinting isn't available/failed. */
    suspend fun fingerprint(uri: Uri, durationSeconds: Int): FingerprintResult?
}

data class FingerprintResult(val chromaprint: String, val durationSeconds: Int)
