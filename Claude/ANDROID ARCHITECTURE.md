ANDROID ARCHITECTURE:
This document captures the technical architecture decisions for the Android music
player. Originally written for a native Kotlin build; superseded mid-session when the
user asked to reuse their existing Expo workflow instead. Update this document as
decisions change, per Claude.md's instruction to keep planning docs current - note
superseded decisions rather than silently deleting them (see "SUPERSEDED" below).

CURRENT STACK: Expo / React Native (TypeScript)

DECISIONS:

Framework: Expo + React Native, not native Kotlin. The user already runs Expo on
their phone and wanted to reuse that workflow rather than install Android Studio.
The concrete path: a custom Expo "dev client" (not the stock Expo Go app, which can't
include the custom native modules this app needs) built via EAS Build, which compiles
in Expo's cloud - so no local Android Studio/JDK/SDK install is needed at any point.

Background playback / lock-screen / notification controls: `expo-audio` (Expo's
own first-party module), not `react-native-track-player`. This was a real finding,
not a preference call: `expo-doctor` flagged `react-native-track-player` (the
initially-installed choice) as unsupported on React Native's New Architecture, and
research confirmed its stable release line (4.x) predates New Architecture support -
the rewrite that adds it (5.0.0-alpha0) is still alpha. `expo-audio` supports
background playback and lock-screen/notification controls natively (Android needs
`setActiveForLockScreen`; the config plugin is set with
`enableBackgroundPlayback: true` in `app.json`) and is New Architecture-native by
construction, avoiding a third-party compatibility gamble for the app's most
safety-critical feature (audio dying mid-playback). Trade-off: `expo-audio` is a
lower-level primitive than track-player's built-in queue management, so playlist/queue
logic (up next, add-to-queue-front/back) will need to be built at the app layer on top
of it - tracked as ordinary feature work, not a blocker.

Organization pipeline: TypeScript, ported a second time this session (see
"SUPERSEDED" below) from the same Python original. Uses `fuzzball` (an actual npm
port of the fuzzywuzzy/rapidfuzz family) for fuzzy string matching, rather than a
hand-rolled reimplementation - this is a real improvement over the Kotlin attempt,
where no such library existed and the scoring logic had to reimplement WRatio/
token_set_ratio/token_sort_ratio from scratch with an explicit "needs recalibration"
caveat. The fuzzball-based port's ported test suite passes 58/58 with the *original*
thresholds unchanged (AUTO_APPLY_THRESHOLD=90, NEEDS_REVIEW_THRESHOLD=60,
AUTO_APPLY_MARGIN=10) - no recalibration needed, unlike the Kotlin attempt.

Persistence: `expo-sqlite` (SQLite, same shape as the Python original's schema) -
not yet implemented as TypeScript code this pass (see "NOT YET STARTED" below).

Secrets (Gemini/AcoustID API keys, MusicBrainz contact string): `expo-secure-store`
(Keystore-backed encrypted storage on Android), mirroring the native plan's
`SecureSettings` design - not yet implemented as TypeScript code this pass.

File access: `expo-document-picker` + `expo-file-system` (SAF-backed, persistable
URI permissions) in place of native Storage Access Framework calls - not yet wired
up.

LLM grounding pass and audio fingerprinting decisions are unchanged from the
original plan (see "SUPERSEDED" for that plan's full reasoning, still valid):
Google Gemini (free tier) for the MusicBrainz double-check pass, AcoustID/Chromaprint
for the classical-music renamed-title fallback (Chromaprint fingerprint *generation*
is still an open spike either way - it needs native code or a from-scratch
reimplementation regardless of Kotlin vs. React Native).

VERIFIED THIS SESSION (Node/npm has real registry access here, unlike the native
Android toolchain, which had none - so this pass could actually install real
dependencies and run real checks, not just write unverified source):
- `npm install` / `npx expo install` - real dependency resolution, including
  discovering and fixing two real compatibility issues (the New Architecture
  incompatibility above, and a missing `expo-asset` peer dependency).
- `npx tsc --noEmit` - passes with zero errors across the whole project.
- `npx jest` - the ported test suites (Scorer, FilenameParser, AlbumGrouper,
  EdmCreditParser - 58 tests total) all pass for real, against the real fuzzball
  library, using the Python original's unmodified thresholds.
- `npx expo-doctor` - 21/21 checks pass.

CURRENT STATE (as of this Expo scaffolding pass):

Scaffolded: `package.json`, `app.json`, `tsconfig.json`, `babel.config.js`, a
minimal Expo Router shell (`app/_layout.tsx`, `app/index.tsx`). Dependencies
installed for real: expo, expo-router (+ its peer deps: react-native-safe-area-context,
react-native-screens, expo-linking, expo-constants, react-dom, react-native-web),
expo-sqlite, expo-document-picker, expo-file-system, expo-secure-store, expo-audio (+
expo-asset), fuzzball, and the Jest/jest-expo/babel-preset-expo test toolchain.

Ported (pure logic, in `src/organize/`, all verified passing):
- `model/types.ts`, `model/libraryPath.ts` - data types and the path-string helpers
  standing in for Python's `pathlib.Path` (a phone's user-picked folder is only
  reachable through `expo-document-picker`/SAF URIs, not a raw filesystem path, so
  `TrackMetadata.uri` carries the real URI and these helpers carry path-string logic).
- `matching/scorer.ts` - ported from `scorer.py`, backed by `fuzzball` instead of a
  hand-rolled fuzzy-match reimplementation.
- `tags/filenameParser.ts` - ported from `filename_parser.py`.
- `tags/edmCreditParser.ts` - new (no Python original), the EDM collab/VIP/remix
  artist-vs-composer crediting rules from Claude/MUSIC ORGANIZATION.md.
- `grouping/albumGrouper.ts` - ported from `album_grouper.py`.

Ported test suites (`*.test.ts` beside each source file, all 58 passing): scorer,
filenameParser, edmCreditParser, albumGrouper.

Network/persistence/settings layer (`src/organize/metadata/`, `persistence/`,
`settings/` - written and type-checked cleanly, not yet exercised against real API
responses or a running app):
- `metadata/musicBrainzClient.ts` - `fetch`-based port of `musicbrainz_client.py`
  (rate limiting via a shared promise-chain queue, strict-then-loose Lucene search,
  retry/backoff, best-linked-release ranking for recordings).
- `metadata/coverArtClient.ts` - downloads straight to a cached file via
  `expo-file-system`'s `File.downloadFileAsync` rather than a base64 data URI
  (React Native doesn't guarantee `btoa`/`atob`) - an improvement over the Kotlin
  version's approach, found while porting rather than carried over unexamined.
- `metadata/geminiGroundingClient.ts` - the LLM double-check pass via Gemini's
  structured-JSON-output mode.
- `metadata/acoustIdClient.ts` - AcoustID lookup. Fixed a real latent bug the
  Kotlin version had: AcoustID's API defaults to XML unless `format=json` is passed
  explicitly, which the Kotlin client omitted (never caught, since nothing ever
  actually called it against the live API).
- `persistence/database.ts` - `expo-sqlite` schema and query functions covering
  tracks, per-track artist credits, libraries, sidecar metadata (tag-less formats
  like WAV), the undo log (`scan_sessions`/`tag_changes`, mirroring `db.py`
  exactly), and both MusicBrainz/Gemini query caches.
- `settings/secureSettings.ts` - `expo-secure-store` wrapper for the Gemini/AcoustID
  API keys and MusicBrainz contact string.
- `fingerprint/fingerprinter.ts` - interface only, same open spike as the Kotlin
  attempt (see "OPEN SPIKES" below).

Scan/tag/organize pipeline (`src/organize/scanner/`, `tags/audioTagReader.ts`,
`pipeline/` - written and type-checked, not yet run on a device):
- `scanner/scanner.ts` - SAF folder picking (`Directory.pickDirectoryAsync`) and a
  recursive walk that builds each file's library-relative path by accumulating
  folder names during the walk itself, rather than trying to parse one back out of
  a `content://` URI (provider-dependent, not reliably parseable in general).
- `tags/audioTagReader.ts` - wraps `@missingcore/audio-metadata` (MP3 ID3v1/v2,
  FLAC, MP4/M4A; verified New Architecture-compatible via `expo-doctor`) for the
  mutagen-equivalent "read a file's own embedded tags" step. Read-only, and a
  smaller field set than mutagen (no genre/composer/disc-number/duration) - see
  its doc comment. WAV/OGG aren't supported by this library either, consistent
  with routing those to the `sidecar_metadata` table instead.
- `pipeline/releaseResolver.ts` - ported from `release_resolver.py`: resolves a
  chosen MusicBrainz candidate into per-file proposed metadata, including the
  title-similarity-based file-to-tracklist-position matching and remix-title
  normalization.
- `pipeline/queryGroup.ts` - ported from `query_worker.py`'s `_query_group`: the
  full per-group MusicBrainz query/score/decide flow, including the
  channel-suffix-stripping and embedded-artist-in-title-recovery heuristics.
- `pipeline/organizeLibrary.ts` - the new top-level orchestrator (no direct Python
  equivalent by this name, but combines what `workers/scan_worker.py` +
  `query_worker.py` + `write_worker.py` did together): scan → read tags (or
  sidecar/filename-guess fallback) → group → query → (Gemini-ground ambiguous
  results) → persist every track's status to the database as it goes.

NOT YET STARTED: real UI screens beyond the placeholder, library auto-separation
(classical vs. EDM classifier), the Flagged review queue UI, actual playback wiring
via `expo-audio`, writing corrected tags back into files (no verified write-capable
tagging library yet - `audioTagReader.ts` is read-only; "applying" a match today
only updates the app's own database, not the file), and the design pass
(Neo-Aero/Dark-Aero/skeuomorphic per Claude/App DESIGN.md).

LIVE API VERIFICATION (user-provided keys, tested via a throwaway script run
outside the project directory - never written into any tracked file):
- MusicBrainz: works as designed; the JSON shapes assumed in `musicBrainzClient.ts`
  (artist-credit array with name/joinphrase, media/track-count, release-group
  primary-type) match the real API exactly.
- Gemini: `gemini-2.0-flash` (the original model choice) is decommissioned - the
  API's own 404 pointed at `gemini-3.6-flash`, which was verified working and is
  now the default in `geminiGroundingClient.ts`. Gemini model names get retired
  periodically; if this 404s again later, check the current model list.
- AcoustID: the key the user provided returned "invalid API key" - very likely
  because it's their personal/user API key (for submitting fingerprints), not an
  *application* API key (needed for the `client=` lookup parameter this app uses).
  These are registered separately at https://acoustid.org/new-applications. Not
  yet resolved - the user needs to register an application there for a working
  lookup key.
- One real bug found and fixed while writing the AcoustID client: it was missing
  `format=json` (AcoustID defaults to XML otherwise).

UI (`app/` - Expo Router, verified via a real `npx expo export --platform android`
Metro bundle: 1352 modules, zero resolution errors):
- `app/index.tsx` - the "Library" screen: a button to pick a folder (SAF) and run
  it through `pipeline/organizeLibrary.ts`, a live progress readout, and a
  `FlatList` of every scanned track's status from the database. This is a
  functional window onto the pipeline, not the real Tracks/Album/Artist pages from
  Claude/Design.md (those are a later phase) - its purpose is letting the pipeline
  actually be watched working end-to-end on a device.
- `app/settings.tsx` - the Gemini/AcoustID API key and MusicBrainz contact fields,
  auto-saving on blur via `secureSettings.ts`.
- **Important caveat for testing in plain Expo Go** (not a custom dev client):
  `@missingcore/audio-metadata` and its `@dr.pogodin/react-native-fs` peer
  dependency are third-party native modules not bundled into the stock Expo Go
  app - navigation and the Settings screen will work fine in Expo Go, but tapping
  "Choose Folder & Organize" and having it actually read tags needs the custom dev
  client (EAS Build) described elsewhere in this doc. `audioTagReader.ts`'s
  existing per-file try/catch means this fails as a visible per-track error status
  in the list rather than crashing the app, so it's still safe to try in Expo Go.

CHROMAPRINT FINGERPRINT GENERATION: researched this session, not implemented.
Findings: no existing React Native/Expo wrapper for the real libchromaprint C
library exists (checked directly - none found), and no maintained native-module
project appears to be attempting one either. A pure-JS reimplementation
(`chromaprint.js` on GitHub) exists but (a) still requires PCM audio samples as
input - it does not decode compressed audio itself, and no PCM-decoding capability
exists anywhere in this stack yet (that's the actual hard blocker, common to any
fingerprint implementation choice) - and (b) is minimally maintained (29 commits,
unclear activity), and a from-scratch reimplementation not verified bit-exact
against the real algorithm is very likely to produce fingerprints AcoustID's
crowdsourced database won't match, making it worse than useless for this feature's
actual purpose. Building a real native module (JNI binding to the actual C
library) is the credible path, but is native Android development this session
can't verify without a device/build loop - same category of risk as the earlier
native-Kotlin attempt this project already moved away from once. Recommended next
step: revisit once EAS Build/a dev client exists and real on-device iteration is
possible, as a focused native-module task rather than guessed-at JS.

OPEN SPIKES (tracked in more detail in Claude/To Do list.md):
1. Set up EAS Build (needs an Expo account/login - the user's own, not something to
   hand over as a static key) to produce the first custom dev client build, so the
   app can actually run on-device.
2. Chromaprint fingerprint *generation* - see the dedicated section above; needs a
   real native module once on-device iteration is possible. AcoustID *lookup*
   (once a fingerprint exists) is implemented and isn't blocked by this.
3. The user's AcoustID key needs replacing with an *application* API key from
   https://acoustid.org/new-applications - the one provided returned "invalid API
   key" against the real lookup endpoint (see "LIVE API VERIFICATION" above).
4. Confirm `expo-audio`'s Android background-playback behavior end-to-end on a real
   device once a dev client build exists (the `setActiveForLockScreen` requirement
   found during research needs to actually be wired up and tested, not just
   documented).
5. Writing corrected tags back into files' embedded metadata - no verified
   write-capable library found yet (`@missingcore/audio-metadata` is read-only).
   Needs research once this becomes the active blocker, same treatment as
   Chromaprint - not guessed at without checking.

SUPERSEDED: the original native-Android plan (Kotlin + Jetpack Compose + Media3 +
Room), including its full reasoning for why native was initially recommended over
Expo (deep OS media-integration concerns) and the Kotlin scorer/filename-parser/
album-grouper/EDM-credit-parser ports with their own JUnit test suites, is preserved
at `legacy-android-native-attempt/` (see its README) rather than deleted, since the
ported logic there is still a useful reference alongside the TypeScript version.
