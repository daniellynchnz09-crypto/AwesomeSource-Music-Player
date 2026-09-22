# AwesomeSource

An Android music player (via Expo/React Native) that reads music from a folder on
the device, organizes it into albums/artists/genres/separate libraries, and plays it
in the background with lock-screen and notification controls. See `Claude.md` and
the planning documents in `Claude/` for the full context and feature spec, and
`Claude/ANDROID ARCHITECTURE.md` for the technical architecture and current build
status.

The project went through two earlier forms before settling here:
- A Windows desktop Python metadata-tagging tool, archived at
  `legacy-desktop-tagger/` (see its own README) - its scanning/matching logic has
  been ported into this app's own organization pipeline.
- A native Kotlin/Jetpack Compose Android app, archived at
  `legacy-android-native-attempt/` (see its own README) - superseded, in the same
  session it was written, by a switch to Expo/React Native so the project could
  reuse the user's existing Expo workflow and build via EAS (Expo's cloud build
  service) instead of installing Android Studio locally.

## Status

Scaffolding stage, but verified for real this time (Node/npm has actual registry
access on this machine, unlike the native Android toolchain, which had none):
`npm install`/`npx expo install` have resolved and installed real dependencies,
`npx tsc --noEmit` passes with zero errors, `npx expo-doctor` passes 21/21 checks,
and the ported organization-pipeline test suite (58 tests) passes for real. A
complete scan-to-database pipeline exists (`pipeline/organizeLibrary.ts`) and the
MusicBrainz/Gemini clients have been verified against live responses - but
**nothing has run inside the actual app on a device yet**, since that needs an EAS
Build (see "Running on a device" below). The AcoustID key needs replacing with an
*application* key (see `Claude/ANDROID ARCHITECTURE.md`), and there's no Settings
screen yet to enter API keys through the app itself.

## Running on a device

This project uses a custom Expo dev client (not the stock Expo Go app - it can't
include this app's native modules), built via EAS Build so no local Android Studio
install is needed:

1. `npx eas login` (your own Expo account).
2. `npx eas build --profile development --platform android` (builds in Expo's
   cloud; an `eas.json` build profile still needs to be added - not done yet).
3. Install the resulting build on your phone.
4. `npx expo start --dev-client` and connect from that build.

## Module layout

```
app/                - Expo Router screens (placeholder so far)
src/organize/
  model/            - TrackMetadata, MbCandidate, AlbumGroup, enums, path helpers
  matching/         - scorer.ts (ported from the legacy tool's scorer.py, via fuzzball)
  tags/             - filenameParser.ts (ported), edmCreditParser.ts (new),
                      audioTagReader.ts (reads embedded MP3/FLAC/MP4 tags, read-only)
  grouping/         - albumGrouper.ts (ported)
  scanner/          - SAF folder picking + recursive audio-file walk
  metadata/         - MusicBrainz/Cover Art Archive/Gemini/AcoustID clients (fetch-based)
  pipeline/         - releaseResolver.ts + queryGroup.ts (ported orchestration/scoring
                      heuristics) + organizeLibrary.ts (top-level scan-to-database pipeline)
  persistence/      - expo-sqlite database (tracks, libraries, undo log, caches)
  settings/         - expo-secure-store wrapper for API keys (no UI screen yet)
  fingerprint/      - Chromaprint fingerprinting interface (not yet implemented - open spike)
```

Writing corrected tags back into files isn't implemented yet either (no verified
write-capable tagging library found) - the pipeline above updates the app's own
database, not the files themselves, for now.

## Tests

`npx jest` runs the ported test suites (Scorer, FilenameParser, AlbumGrouper,
EdmCreditParser) - 58 tests, all passing, ported from the legacy tool's `pytest`
suite where a Python original exists.
