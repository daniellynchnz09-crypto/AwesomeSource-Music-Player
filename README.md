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
`Claude/ANDROID ARCHITECTURE.md`. A minimal scaffold exists and is verified for
real: `./gradlew assembleDebug` builds, and the APK installs and runs on a real
emulator (screenshotted to confirm). It's just a placeholder `MainActivity` so far -
the organization pipeline (scanning, tag reading, MusicBrainz/Gemini
grounding/AcoustID matching, scoring, persistence) still needs re-porting from
`legacy-expo-attempt/src/organize/` into Kotlin, carrying forward the real bugs
already found and fixed during the Expo attempt (see that folder's README and
ANDROID ARCHITECTURE.md's "LESSONS TO CARRY FORWARD" section) rather than
re-deriving them from scratch. The AcoustID key still needs replacing with an
*application* key (see ANDROID ARCHITECTURE.md) regardless of stack.

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

The retired attempts each have their own test suites, preserved for reference:
- `legacy-expo-attempt/`: `npx jest` (from inside that folder, after `npm install`)
  runs the ported Scorer/FilenameParser/AlbumGrouper/EdmCreditParser suites - 58
  tests, all passing as of that attempt.
- `legacy-android-native-attempt/`: JUnit ports of the same suites (never
  build-verified in that attempt, since no JDK/Android SDK was available in that
  session).
