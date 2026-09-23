/**
 * Abstraction over generating a Chromaprint audio fingerprint from a track, for the
 * classical-music case in Claude/MUSIC ORGANIZATION.md: some titles were previously
 * hand-edited into a form MusicBrainz text search can no longer match, so the
 * fallback is to fingerprint the audio itself and resolve it via AcoustID (see
 * `organize/metadata/acoustIdClient.ts`) instead of text search.
 *
 * **Not yet implemented - this is the "spike" the plan calls out.** Chromaprint has
 * no JS/TypeScript implementation, and getting it into a React Native app needs one of:
 *  1. A native module wrapping a Chromaprint build for Android (and iOS, if this
 *     ever targets it) - most control, but needs either writing a small native
 *     module (Kotlin/Swift + JNI/bridging to the Chromaprint C library) or finding
 *     an existing, actively-maintained React Native binding - not assumed to exist
 *     here, since guessing at a specific npm package risks pointing the project at
 *     something unmaintained or untrustworthy without real research.
 *  2. Decoding audio to raw PCM (via a native audio module) and reimplementing
 *     Chromaprint's chroma-feature + fingerprint algorithm directly in
 *     TypeScript/JS - avoids native code entirely but is a substantial undertaking
 *     on its own.
 * Tracked in Claude/To Do list.md as an open spike; this interface exists now so
 * the rest of the classical-music matching flow can be wired against it without
 * waiting on that decision.
 */

export interface FingerprintResult {
  chromaprint: string;
  durationSeconds: number;
}

export interface Fingerprinter {
  /** Returns a Chromaprint fingerprint string (as AcoustID's API expects) and the
   * track duration in whole seconds, or null if fingerprinting isn't
   * available/failed. */
  fingerprint(uri: string, durationSeconds: number): Promise<FingerprintResult | null>;
}
