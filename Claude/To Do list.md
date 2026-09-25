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
8. ~~No UI wires up the new pipeline yet~~ - done: Compose Setup/Library/Settings
   screens exist at `app/src/main/java/com/mslynch/awesomesource/ui/` and
   `MainActivity.kt`, backed by a `MainViewModel` that owns the single in-flight
   `OrganizeLibrary` run, the Room track list, and the three `SecureSettings`
   fields - see ANDROID ARCHITECTURE.md's "UI" section for the full design and the
   real bug found (wrapping `organize()` in `Dispatchers.IO` so the synchronous
   `Scanner.scanFolder()` walk doesn't freeze the UI thread). ~~A real end-to-end
   organize run against actual audio files is still unexercised~~ - done: the
   user's real ~2547-file library was pushed to the emulator's SD card and
   organized end-to-end through the UI, which surfaced and led to fixing a real
   crash (see ANDROID ARCHITECTURE.md's "REAL ORGANIZE RUN" section) -
   `FilenameParser.kt`'s `\{[^}]*}` regex had an unescaped trailing brace that
   Android's on-device ART regex engine rejects (`PatternSyntaxException`) even
   though desktop-JVM unit tests never caught it. Fixed and reverified with a full
   successful run (real `AUTO_MATCHED`/`NEEDS_REVIEW` results, zero crashes).
8b. ~~The Library screen's review-status system (Approved/Verify/Match Found/No
    Match Found), stats bar, search+filter, scrollbar, and tap-to-edit detail
    screen~~ - done: see ANDROID ARCHITECTURE.md's "REVIEW-STATUS MODEL" section for
    the full design and a real bug it fixed along the way (`QueryGroup`'s resolved
    match proposal was being computed and silently discarded before this - no draft
    could ever have reached a track). Verified end-to-end against a real ~2500-file
    organize run: stats (505/432/25/406), stat-tile isolation, filter-chip
    multi-toggle, and - most importantly - a real database write via "Accept
    proposed match" that correctly filled in missing fields from the draft while
    correctly leaving the status as Match Found rather than false-flagging it
    Approved when the draft itself was still incomplete (missing track number).
    Manual free-text editing of any field is also wired up (`MainViewModel.updateTrackDetails`)
    but its own write path wasn't separately re-verified this pass, since it shares
    the exact same `upsert`-then-recompute code path already proven by the accept
    action.
8c. ~~Draggable right-edge scrollbar~~ - done: see ANDROID ARCHITECTURE.md's
    "REVIEW-STATUS MODEL" section addendum for the drag-gesture-cancellation bug
    found and fixed (a `pointerInput` key that changed mid-drag), verified with real
    `adb shell input swipe` gestures of varying length.
8d. ~~Investigated why the review-status stats (1368) didn't match the ~2500-file
    library count, and fixed a real bug found along the way~~ - done: see ANDROID
    ARCHITECTURE.md's "MISSING-FILES INVESTIGATION AND THE TAG-READ-FAILURE RECOVERY
    FIX" section. 31 real `.m4a` files were permanently unrecoverable due to a
    jaudiotagger parse failure with no fallback; now they fall back to sidecar/
    filename-guessing like WAV files already did. The remaining "missing" files were
    non-music project internals and macOS resource-fork junk, correctly excluded.
    AIFF support was investigated and declined - the 229 `.aif`/`.aiff` files are all
    internal `.band` (GarageBand/Logic) project assets, not real songs.
8e. ~~High refresh rate support~~ - done: `MainActivity.requestHighRefreshRate()`.
8f. ~~Bulk multi-select editing (long-press a track, select more, edit shared fields
    like Artist/Album across all of them at once)~~ - done: see ANDROID
    ARCHITECTURE.md's "BULK MULTI-SELECT EDITING" section. Verified end-to-end
    against real tracks, including reverting the test data afterward.
8g. ~~Wire up the existing-but-dead Gemini grounding cache table so rescans stop
    re-spending Gemini quota on already-graded matches~~ - done: see ANDROID
    ARCHITECTURE.md's "GEMINI GROUNDING CACHE WIRED UP" section. Compiles and unit-
    tests pass; not yet re-verified against a live cache hit (blocked on completing
    a full rescan - see the "TWO REAL MISTAKES" section for why one didn't finish
    cleanly this pass).
8h. A pre-existing gap noticed while testing 8f, not yet fixed: the system back
    button from the track detail screen exits the app entirely instead of returning
    to the Library list, since dismissal is only wired to the screen's own in-app
    back arrow.
8i. ~~Automatically re-query MusicBrainz/Gemini after a bulk edit fills in enough
    detail to make a track matchable, and look for other tracks in the same folder
    that plausibly belong to the same now-confirmed album~~ - done: see ANDROID
    ARCHITECTURE.md's "AUTOMATIC RE-QUERY AFTER A BULK EDIT, PLUS ALBUM-SIBLING
    DISCOVERY" section. Verified end-to-end against the user's own real Skrillex
    "Quest For Fire" album (all 15 tracks correctly flipped to Approved against a
    real MusicBrainz release id); sibling discovery correctly proposed nothing extra
    since every real track was already selected. This also gives 8g's Gemini cache a
    real, if incidental, re-verification path going forward: any future bulk-edit
    re-query against an already-graded ambiguous album will now exercise the cache
    hit path for real.
8j. ~~Investigated why real Skrillex "Quest For Fire" collaboration tracks (e.g.
    "Ratata" = "Skrillex, Missy Elliott & Mr. Oizo") were being marked Approved with
    just the flat bulk-edited "Skrillex" artist~~ - done: found and fixed a real,
    foundational, pre-existing bug, not something this session introduced - see
    ANDROID ARCHITECTURE.md's "A REAL FOUNDATIONAL BUG FOUND VIA THE SKRILLEX ALBUM"
    section. `MediumDto.tracks` had the wrong JSON key name (`"track"` instead of the
    real API's `"tracks"`), so `getReleaseTracklist()`'s per-track data has been
    empty on every release lookup this app has ever made, silently defeating
    per-track artist/title/track-number correction for every multi-track match, not
    just this album. Also fixed `TrackEntity.reviewStatus()` (the single un-persisted
    formula every screen reads) to treat a `proposedArtist` that disagrees with the
    real `artist` as "not actually confirmed", so a bulk-edited-complete track with a
    real collaboration correction available now correctly lands on Match Found
    instead of Approved. Verified end-to-end against the real album: 13 of 15 tracks
    correctly moved from Approved to Match Found with the real collaboration credit
    in the proposed-match card, the 2 genuinely solo tracks stayed Approved, and this
    is likely to have quietly improved artist-credit accuracy for every other
    already-matched multi-track album in the library too, though that wasn't
    separately re-verified this pass.
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
