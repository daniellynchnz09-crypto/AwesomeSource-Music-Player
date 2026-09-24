# AwesomeSource

An Android music player that reads music from a folder on the device, organizes it
into albums/artists/genres/separate libraries, and plays it in the background with
lock-screen and notification controls. See `Claude.md` and the planning documents in
`Claude/` for the full context and feature spec, and `Claude/ANDROID ARCHITECTURE.md`
for the technical architecture and current build status.

The project has gone through two earlier forms before settling here:
- A Windows desktop Python metadata-tagging tool, archived at
  `legacy-desktop-tagger/` (see its own README) - its scanning/matching logic has
  been ported into this app's own organization pipeline, first in Kotlin, then in
  TypeScript.
- A native Kotlin/Jetpack Compose Android app, archived at
  `legacy-android-native-attempt/` (see its own README) - superseded by a switch to
  Expo/React Native so the project could reuse the user's existing Expo workflow.
- An Expo/React Native app, archived at `legacy-expo-attempt/` (see its own README)
  - got as far as a working organization pipeline, a basic Setup/Library/Settings
    UI, and a successful EAS Build custom dev client install on a real device, before
    the user decided to install Android Studio and go back to native Android after
    all.

## Status

Back to native Android (Kotlin + Jetpack Compose + Media3/ExoPlayer + Room) - see
`Claude/ANDROID ARCHITECTURE.md`. The organization pipeline (scanning, tag reading,
MusicBrainz/Gemini grounding/AcoustID matching, scoring, persistence) is fully
re-ported into Kotlin at `app/src/main/java/com/mslynch/awesomesource/organize/`
and verified for real: `./gradlew assembleDebug` builds, all 58 ported unit tests
pass, and the APK installs and runs on a real emulator (screenshotted to confirm).
Real fixes carried forward from the Expo attempt (Gemini model name, AcoustID
`format=json`, verified MusicBrainz shapes) plus new ones found while actually
compiling this port for the first time (see ANDROID ARCHITECTURE.md) - a Kotlin
nested-block-comment parse bug, a smart-cast/closure-capture error, and a
`Uri.EMPTY`-is-null-under-plain-JVM-tests gap fixed with Robolectric. Two open
gaps from every prior attempt got real progress as a side effect of picking better
libraries this time: tag reading now also supports *writing* (`net.jthink:jaudiotagger`,
not wired into the pipeline yet), and fuzzy matching uses a real published library
(`me.xdrop:fuzzywuzzy`) instead of a from-scratch reimplementation. A Compose
Setup/Library/Settings UI now wires up the pipeline (`app/src/main/java/com/mslynch/awesomesource/ui/`).
The user's real ~2500-file music library was pushed to the emulator's SD card and
organized end-to-end through the UI - this surfaced (and led to fixing) a real
on-device-only crash in `FilenameParser.kt` (an unescaped regex brace that Android's
ART regex engine rejects but desktop-JVM unit tests never caught); see
ANDROID ARCHITECTURE.md's "REAL ORGANIZE RUN" section. The Library screen now
classifies every scanned track into one of four review statuses (Approved/Verify/
Match Found/No Match Found), with a tap-to-isolate stats bar, search-by-field,
multi-select status filter chips, a scrollbar, and a tap-to-edit detail screen that
can also accept a drafted match without ever touching the file itself - verified
end-to-end against the same real library, including a real database write via
"Accept proposed match" (see ANDROID ARCHITECTURE.md's "REVIEW-STATUS MODEL"
section). The AcoustID key still needs replacing with an *application* key (see
ANDROID ARCHITECTURE.md) regardless of stack.

## Running it

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mslynch.awesomesource/.MainActivity
```

Requires the Android SDK (`local.properties` -> `sdk.dir`, not committed) and a
device/emulator connected via `adb devices`. See `Claude/ANDROID ARCHITECTURE.md`
for the real AGP 9.x/Kotlin/KSP version-compatibility issues hit and fixed while
setting this up.

## Tests

`./gradlew testDebugUnitTest` runs the current, real test suite - 58 tests
(Scorer 22, FilenameParser 19, EdmCreditParser 7, AlbumGrouper 10), all passing.
`AlbumGrouperTest` runs under Robolectric (needed for a working `Uri` in plain JVM
tests - see ANDROID ARCHITECTURE.md).

The retired attempts' own test suites are preserved for reference only:
- `legacy-expo-attempt/`: `npx jest` (from inside that folder, after `npm install`)
  - the same 58 cases, passing against `fuzzball` instead of `fuzzywuzzy`.
- `legacy-android-native-attempt/`: JUnit ports of the same suites (never
  build-verified in that attempt, since no JDK/Android SDK was available in that
  session).
