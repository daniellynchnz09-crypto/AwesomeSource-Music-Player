# Expo/React Native attempt (archived)

**Superseded.** After the native Kotlin attempt (see
`legacy-android-native-attempt/`) the project switched to Expo/React Native to
reuse the user's existing Expo Go workflow and avoid a local Android Studio
install. That got as far as a working organization pipeline (scan, tag reading,
MusicBrainz matching/scoring, Gemini grounding, AcoustID lookup, SQLite
persistence), a basic Setup/Library/Settings UI, and a successful EAS Build
custom dev client. The user then decided to go back to native Android Studio
after all, before shipping anything beyond that dev client.

Kept here rather than deleted because real bugs were found and fixed during
this attempt that the next (native Kotlin) rewrite should carry over rather
than reintroduce:

- `src/organize/metadata/geminiGroundingClient.ts` - `gemini-2.0-flash` is
  decommissioned; verified live that `gemini-3.6-flash` (or whatever the
  current model is by the time this is read - check https://ai.google.dev/gemini-api/docs/models)
  works with `responseMimeType: "application/json"` structured output.
- `src/organize/metadata/acoustIdClient.ts` - the AcoustID lookup endpoint
  defaults to XML; needs `format=json` explicitly (missing in the original
  Python client too). Also: the AcoustID key the user has is a personal/user
  key, not an *application* key - lookups reject it with "invalid API key".
  A real application key from https://acoustid.org/new-applications is needed.
- `src/organize/metadata/musicBrainzClient.ts` - response shapes (artist-credit
  array with name/joinphrase, media/track-count, release-group/primary-type)
  were verified against the real live API, not assumed.
- `src/organize/matching/scorer.ts` + `.test.ts` - the weighted-scoring/
  three-band decision logic ported from `legacy-desktop-tagger/musictagger/matching/scorer.py`,
  using `fuzzball` (a real fuzzywuzzy/rapidfuzz port) for the fuzzy-match
  primitives, with the full Python test suite ported and passing.
- `src/organize/tags/filenameParser.ts` / `edmCreditParser.ts` + `.test.ts` -
  EDM collab/VIP/remix artist-vs-composer crediting logic, with tests.
- `src/organize/grouping/albumGrouper.ts` + `.test.ts` - folder-first,
  tag-confirmed, singleton-fallback album grouping, with tests.
- `src/organize/persistence/database.ts` - the SQLite schema (tracks, per-track
  artist credits, libraries, sidecar metadata, undo log, MB/Gemini query
  caches) - a reference for the equivalent Room schema.
- `src/organize/fingerprint/fingerprinter.ts` - Chromaprint fingerprinting was
  researched but never implemented in either native attempt: no React
  Native/Android-compatible wrapper for the real libchromaprint C library was
  found, and a from-scratch reimplementation risks producing fingerprints
  AcoustID's database won't match. Still needs a proper native module/JNI spike
  in the Kotlin rewrite, not a blind reimplementation.
- The one real gap found by actually building and installing something: reading
  embedded tags needs a native module Expo Go doesn't bundle
  (`@missingcore/audio-metadata`), which only surfaced once a real device/dev
  client build was tried - a reminder to smoke-test tag reading against real
  files early in the Kotlin rewrite too, not just unit-test the surrounding logic.

See `Claude/ANDROID ARCHITECTURE.md` for the full decision history (now
superseded again by whatever the current native Kotlin architecture doc says).
