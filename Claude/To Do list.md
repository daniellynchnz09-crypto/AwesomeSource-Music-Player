To Do list

A number of tasks outlined in either the supporting documents, or tasks that the user has given to claude code that aren't to be resolved right away due to their size or the fact that they aren't currently relevant to the task on hand.

CURRENT (native Android, Kotlin - see Claude/ANDROID ARCHITECTURE.md for full context):

The project is back on native Android after two prior attempts (native Kotlin, then
Expo/React Native - both archived, see `legacy-android-native-attempt/` and
`legacy-expo-attempt/`). A minimal scaffold now exists at the repo root, verified by
actually building it, installing it on a running emulator, launching it, and
screenshotting the result - see Claude/ANDROID ARCHITECTURE.md's "CURRENT STATE" for
the real AGP 9.x migration issues hit and fixed along the way (the built-in-Kotlin
plugin change, KSP/toolchain compatibility toggles, JDK auto-provisioning).

1. ~~Install/confirm Android Studio + JDK + Android SDK, scaffold the project~~ -
   done. Android Studio, the SDK, and a `Pixel_8` emulator are installed and
   confirmed working; `./gradlew assembleDebug` succeeds and the app runs on the
   emulator.
2. ~~Re-port the organization pipeline into Kotlin~~ - done: scanner (SAF/
   DocumentFile), tag reader (`net.jthink:jaudiotagger`, a real improvement over
   the Expo attempt's read-only gap list - see ANDROID ARCHITECTURE.md), scorer/
   fuzzy-matching (`me.xdrop:fuzzywuzzy`, a real published library replacing the
   from-scratch reimplementation the first native attempt had), filename/EDM-credit
   parsers, album grouper, MusicBrainz/Gemini/AcoustID clients (Retrofit/Moshi),
   Room persistence, secure settings, and the full pipeline orchestration
   (scan -> tag/sidecar/filename resolve -> group -> query -> score -> Gemini-ground
   -> persist). All 58 ported unit tests pass for real against the real
   `fuzzywuzzy`/Robolectric toolchain (see ANDROID ARCHITECTURE.md for the
   real bugs hit and fixed while doing this - a KDoc parsing bug, a Kotlin
   smart-cast/closure-capture error, and the `Uri.EMPTY`-is-null-under-plain-JVM-
   tests gap that needed Robolectric to fix for real, not just assumed to work).
3. The AcoustID key the user provided is a personal/user key, not an *application*
   key - lookups return "invalid API key". Needs a new key registered at
   https://acoustid.org/new-applications. Unresolved across all three attempts so
   far.
4. Chromaprint fingerprint *generation* is still unimplemented across all three
   attempts. See ANDROID ARCHITECTURE.md's dedicated section - the credible path
   for native Kotlin is a real JNI binding to libchromaprint, spiked once a device/
   build loop exists, not guessed at blind.
5. Writing corrected tags back into files' embedded metadata is unimplemented in
   the pipeline, though `AudioTagReader.kt`'s `jaudiotagger` dependency actually
   supports writing (unlike the Expo attempt's read-only library) - real progress
   on this specific blocker, just not wired up yet. "Applying" a match today still
   only updates the app's own database, not the file itself.
6. Library auto-separation (classical vs. EDM vs. other) is still not yet designed
   in code - needs a genre-tag-first, heuristic-classifier-fallback approach per
   Claude/MUSIC ORGANIZATION.md.
7. The Flagged review queue UI (chat-style verification per Claude/MUSIC
   ORGANIZATION.md) has no implementation yet in any attempt.
8. No UI wires up the new pipeline yet (`OrganizeLibrary.kt` is a complete,
   tested scan-to-database pipeline, but nothing calls it from a screen) - the
   equivalent of the Expo attempt's Setup/Library/Settings screens hasn't been
   built for Compose yet.
9. Playlist/queue logic (up next, add-to-queue-front/back per Claude/Design.md)
   needs to be built once playback (Media3/ExoPlayer) is wired up.
10. The design pass (Neo-Aero/Dark-Aero/skeuomorphic per Claude/App DESIGN.md) is
    still outstanding.
11. Confirm Media3 background playback survives screen lock, switching apps, and a
    real headset/Bluetooth play-pause-skip test, before considering playback done -
    this was flagged as a risk area from the very first plan and still needs a real
    on-device test regardless of stack.

SUPERSEDED - FROM THE EXPO/REACT NATIVE ATTEMPT (archived at
`legacy-expo-attempt/`; kept for the real bugs/fixes found, listed in its own
README and folded into ANDROID ARCHITECTURE.md's "LESSONS TO CARRY FORWARD"):

12. ~~Set up EAS Build and produce a custom dev client~~ - done: a real cloud build
    succeeded and produced an installable APK, linked to the user's own EAS project
    (`kringlepringles-team/awesomesource-media-player`). Moot now that the project
    is back on native Android Studio instead.
13. ~~Port the MusicBrainz/Cover Art Archive/Gemini/AcoustID network clients~~,
    ~~build the `expo-sqlite` persistence layer~~, ~~build the `expo-secure-store`
    wrapper~~, ~~wire up a Settings screen~~, ~~wire up a basic Setup/Library UI~~,
    ~~add a unified progress bar~~ - all done in TypeScript; superseded by the
    Kotlin re-port (item 2 above), not carried over as running code.
14. ~~Fix the Gemini model name~~, ~~fix the AcoustID missing format=json bug~~,
    ~~verify MusicBrainz JSON response shapes against the real API~~ - done, and
    these specific findings carry forward regardless of stack (see
    ANDROID ARCHITECTURE.md).
15. Wire up `expo-audio` for real playback - moot, replaced by Media3/ExoPlayer in
    the native rewrite (item 9/11 above).

SUPERSEDED - FROM THE FIRST NATIVE ANDROID ATTEMPT (also archived, at
`legacy-android-native-attempt/`; the underlying issues are conceptually the same
now that the project is native again):

16. ~~Install a JDK + Android SDK + Gradle, and generate the real Gradle wrapper~~ -
    done for real this time (item 1 above): Android Studio's bundled JDK plus a
    separately auto-provisioned JDK 17 (for Gradle's toolchain), the Android SDK,
    and a real generated wrapper (`gradlew`/`gradle-wrapper.jar`), not deferred a
    second time.
17. ~~Recalibrate Scorer's thresholds~~ - resolved for real now, on the second try:
    a real, maintained JVM fuzzywuzzy port (`me.xdrop:fuzzywuzzy`) was found and
    used instead of reimplementing WRatio/token_set_ratio/token_sort_ratio from
    scratch a second time - all 22 ported ScorerTest cases pass with the Python
    original's unmodified thresholds, including the exact numeric assertions.
18. WAV/tag-less-format sidecar metadata read/write - the Room `sidecar_metadata`
    table and the `OrganizeLibrary.kt` fallback-read logic exist; nothing writes to
    it yet in any attempt (no UI to enter sidecar metadata exists).
