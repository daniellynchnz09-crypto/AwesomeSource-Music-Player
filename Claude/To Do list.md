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
8h. ~~A pre-existing gap noticed while testing 8f: the system back button from the
    track detail screen exits the app entirely instead of returning to the Library
    list, since dismissal is only wired to the screen's own in-app back arrow~~ -
    done: a plain `BackHandler` now calls the same `onBack` the in-app arrow already
    used. Verified via `dumpsys window`'s focused-activity check before and after.
    The user later confirmed this was almost certainly the real cause of an earlier
    "I think I crashed the app" report - accepting matches on tracks one at a time
    and hitting back to return to the list would have silently exited the app
    instead, which reads exactly like a crash.
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
8k. ~~Album art thumbnails on the left of each Library row, like a real music
    player~~ - done: see ANDROID ARCHITECTURE.md's "ALBUM ART THUMBNAILS IN THE
    LIBRARY LIST" section. Embedded artwork is extracted to a cache file during tag
    reading and shown via Coil; a Cover Art Archive network fallback for
    matched-but-artless tracks was deliberately left for later. Existing tracks need
    a fresh rescan to populate their thumbnail, since extraction only happens during
    tag reading - see 8l below for why that's no longer a casual thing to do.
8l. ~~A full rescan ("Choose Folder & Organize" again on an already-organized
    library) was found, the hard way, to silently revert every previously
    accepted-match or manually/bulk-edited correction back to the tracks' raw,
    unedited file tags~~ - done: fixed for real, not just worked around. Initially
    framed (wrongly) as blocked on tag-writing (item 5) - the user corrected this:
    the database itself is meant to be the protected, authoritative record once a
    track has details added to it, independent of whether the file is ever written
    to. Fixed with `TrackEntity.isCurated()` and a check in
    `OrganizeLibrary.organize()`'s tag-reading loop that skips re-processing any
    already-matched or manually-edited track entirely - see ANDROID ARCHITECTURE.md's
    "THE ACTUAL FIX FOR THE DATA-LOSS INCIDENT" section. Verified by deliberately
    re-running the exact rescan that caused the original incident and confirming the
    Quest For Fire collaboration data survived intact this time, while tracks nobody
    had touched yet were still refreshed normally (629 got fresh tag reads, correctly
    picking up new `coverArtPath` thumbnails along the way).
8m. ~~"Hysteric" by Badklaat & PYKE wouldn't advance past Match Found no matter how
    many times "Accept proposed match" was tapped~~ - done: `hasAllDetails`
    unconditionally required a track number, which this single (from a
    various-artists compilation) genuinely could never resolve even though every
    other field agreed with the confirmed match. Dropped the requirement - see
    ANDROID ARCHITECTURE.md's "A BATCH OF REAL BUGS FOUND WHILE USING THE APP FOR
    REAL" section, item 1. Fixing it retroactively reclassified hundreds of tracks
    on install alone, no rescan needed.
8n. ~~Search and the status-filter chips looked broken together~~ - done: they
    both worked correctly in isolation, but an isolated stats-tile filter silently
    scoped search results too, with no visual reminder. Decoupled: a non-blank
    search query now searches the whole library regardless of the active filter
    chips. Also fixed two dead-ends: tapping an already-isolated stats tile now
    resets to "show everything", and toggling off the last filter chip no longer
    leaves an empty, permanently-blank list.
8o. ~~Cover art was never fetched from Cover Art Archive for a confirmed match,
    only ever read from a file's own embedded tags~~ - done: `coverArtPath` was
    simply never wired to the already-fetched cover image (a half-built feature),
    compounded by `QueryGroup` explicitly disabling the fetch and `isCurated()`
    permanently blocking a retry for already-matched tracks. Fixed all three
    layers, plus a new `OrganizeLibrary.backfillMissingCoverArt` (grouped by
    release so an album downloads its cover once, not once per track) to reach the
    ~560 tracks already matched before this fix existed. Verified end-to-end: all
    15 Quest For Fire tracks and Tipper's "Broken Soul Jamboree" picked up real art;
    remaining gaps confirmed via direct `curl` checks to be genuine
    nothing-archived/server-error cases, not an app bug.
8p. ~~The above backfill's first real run looked exactly like a hang~~ - done:
    added a fifth `OrganizeLibrary.Phase.FETCHING_ART` so the progress bar keeps
    visibly moving through a ~100-release backfill instead of sitting unchanged at
    "Organizing…" for several minutes after the querying phase already hit 100%.
8q. ~~Bulk multi-select gained an "Approve" action next to Edit, and the tiny
    corner edit icon became a full-width rectangular button~~ - done, per direct
    request. Approve reuses the single-track accept logic in one batched write per
    tap rather than one per track, and can never force a status directly - a track
    with nothing proposed is a no-op, so the status filters still mean exactly what
    they always meant.
8r. ~~Alphabetical / "recently updated" sort for the Library list~~ - done, per
    direct request. Added a real `updatedAt` column via `Migration(3, 4)`.
8s. ~~A false-positive album-sibling match: "Mr. Bill - For A Friend.mp3" was
    proposed as Tipper's "Preparations for Departure" (Cloaked, position 12), a
    position already correctly claimed by a real, separate track elsewhere in the
    library~~ - done: `discoverAlbumSiblings` only checked the *current group's*
    own files for already-claimed positions, not the whole library, so a release
    split across multiple groups over time could hand out a position twice. Fixed
    via a new library-wide `TrackDao.getByMatchedReleaseId` check. Also added a
    "Reject" action (`MainViewModel.rejectProposedMatch`) next to "Accept" for any
    future false positive, since `isCurated()` otherwise leaves a bad draft stuck
    forever - used it for real to clear this exact row, verified against the
    database and the stats bar (Match Found 19->18, No Match 226->227).
8t. ~~The same "no library-wide awareness of already-claimed positions" gap also
    exists in the regular per-group matching path (`ReleaseResolver`, used by every
    ordinary match, not just sibling detection) - found three more pairs of tracks
    sharing a `matchedReleaseId` + `trackNumber` (e.g. two different Bach works, BWV
    1041 and BWV 1047, both claiming position 3 of the same release)~~ - done, with
    the user's go-ahead: `ReleaseResolver.resolveGroupToProposed` now takes a
    `TrackDao`, queries every other track already matched to the same release (the
    current group's own files excluded, so they don't block themselves from
    reclaiming their own position), and excludes those positions from both the
    singleton `bestMatchingPosition` call and `matchFilesToPositions`'s three
    passes. `matchFilesToPositions` also gained a same-group uniqueness check on
    pass 1 (an explicit tag number) that it never had before.
8u. ~~A number of tracks that were already Approved in an earlier session had
    reverted back to Match Found in a later session, showing the *same* proposed
    suggestions as before~~ - root-caused and fixed: `OrganizeLibrary.requeryTracks`
    (the automatic re-query `MainViewModel.bulkUpdateTrackDetails` runs after every
    bulk edit) never checked `isCurated()` at all, unlike `organize()`'s own scan
    loop - so a bulk edit applied to a selection that happened to include some
    already-Approved tracks alongside new ones re-queried MusicBrainz for *all* of
    them. A fresh search can return a slightly different-formatted (not wrong, just
    different capitalization/collab-ordering/remix-suffix styling) candidate than
    whatever string was already accepted into the real field, which
    `reviewStatus()`'s `artistConfirmed` check then reads as a disagreement -
    silently demoting an already-reviewed APPROVED track back to MATCH_FOUND with
    what looks like "the same suggestion" (it IS the same match, just a fresh
    string). Fixed by filtering curated entities out of `requeryTracks` before
    grouping, exactly like `organize()` already does. Verified for real: bulk-edited
    an Approved Quest For Fire track's Genre field alone, confirmed the Genre change
    applied while `artist`/`proposedArtist`/`matchedReleaseId` stayed byte-for-byte
    identical and the track stayed Approved (previously this same action would have
    re-triggered a MusicBrainz search for it). A `Mr. Bill - For A Friend.mp3` row
    that looked reverted turned out to be a red herring from an incomplete database
    read on my end (forgot to include the SQLite WAL file, which held the actual
    fix) - not a real second regression, and a useful reminder to always pull
    `-wal`/`-shm` alongside the main `.db` file when checking live app state.
8v. ~~Given the user a way to identify/verify library entries against their own
    physical CDs, and attach real cover art~~ - built two small features and used
    them on a real batch of 40 photos (front+back of 20 CDs) the user took:
    - A "custom cover art" feature: `TrackEntity` gained `customCoverArtPath`
      (schema v4->v5, a real `Migration`, not the destructive fallback), the
      Library screen's multi-select bulk toolbar gained a third "Cover Art"
      button (alongside Approve/Edit) that opens the system image picker and
      calls `MainViewModel.setCustomCoverArt`, which copies the picked image
      into `filesDir/custom-art/` once and points every selected track's
      `customCoverArtPath` at it. Takes priority over the existing
      `coverArtPath` (embedded-tag/Cover-Art-Archive) wherever art is shown,
      and is never touched by the organize pipeline, so a rescan can't lose it.
    - Fixed a real latent bug found while building this: `updateTrackDetails`
      and `bulkUpdateTrackDetails` never cleared `statusDetail`, so a track
      manually resolved this way could still show a stale pipeline message
      (e.g. "no usable artist/title to search with") under an otherwise-Verify
      row - both now clear it, matching what `rejectProposedMatch` already did.
    - Applied the two features to real data: read all 40 CD-case photos (see
      `Claude/CD Case Identification Progress.md` for the full transcribed
      tracklists and matching results, so future sessions don't need to
      re-photograph anything), matched 2 of the 20 CDs against completely
      untagged files already in the library (Titanic OST, 15 tracks; a Naxos
      Grieg Peer Gynt disc, 16 tracks), wrote their sleeve-derived metadata
      directly into the tracks' own fields (bypassing MusicBrainz, so they land
      in Verify per the user's request rather than being auto-matched), and
      attached the Titanic front cover as cropped art via the new feature. 3
      more photographed CDs turned out to already be correctly tagged from an
      earlier scan (confirmed accurate, no changes needed); the remaining 15
      are only partially digitized or not found under any recognizable
      filename - flagged for a future session in the same doc.
    - Applied the 31 direct field writes via a raw SQL `UPDATE` against the
      pulled `.db` file (app fully stopped, WAL checkpointed first, clean file
      pushed back via `run-as`) rather than the in-app multi-select UI, which
      needs one long-press per track - genuinely impractical for a 30-track
      batch. Confirmed while doing this that `run-as` actually CAN write to
      app-private storage on this setup after all - last session's "Permission
      denied" was a git-bash argv-quoting artifact
      (`adb shell run-as PKG sh -c '...'` as separate argv tokens vs. one
      fully-quoted string), not a real SELinux restriction. See
      ANDROID ARCHITECTURE.md.
8w. ~~Approving tracks in the Verify section did not move them to Approved~~ -
    real bug, fixed. `ReviewStatus.APPROVED` requires `recognized` to be true,
    which was defined purely as `matchedReleaseId != null` - but a VERIFY track
    (every core field already present, just never matched to anything) has no
    `matchedReleaseId` and never will, so the "Approve" action's
    `withProposedAccepted()` - which only ever copies a `proposed*` draft onto
    the real fields - was a silent no-op for it (nothing proposed to copy, so
    every `?:` fell through to the existing value). There was no way for a
    Verify track to *ever* leave Verify, which matters a lot given the CD-photo
    identification work above landed dozens of tracks there. Fixed by adding
    `TrackEntity.userConfirmed` (schema v5->v6), which counts as `recognized`
    in `reviewStatus()` exactly like a real match does; `withProposedAccepted()`
    now sets it when a track has nothing proposed but is currently Verify.
    Added a single-track "Mark as Correct" button to the track detail screen
    for parity with the bulk toolbar's Approve. Verified for real: selected
    Verify tracks in the running app, tapped Approve, watched them turn green
    and move to the Approved count.
8x. ~~Gboard's suggestion strip rendered pinned to the very top of the screen
    instead of docked above the keyboard, covering the top app bar's back
    button and making it hard to navigate back~~ - real bug, reported by the
    user with a screenshot, fixed. `MainActivity` calls `enableEdgeToEdge()`
    but the manifest never declared `android:windowSoftInputMode`, leaving the
    system to guess how to lay out around the IME; combined with edge-to-edge,
    this let the IME's own window render in the wrong position on-device.
    Fixed by adding `android:windowSoftInputMode="adjustResize"` to
    `MainActivity` in `AndroidManifest.xml`, so the framework actually resizes
    content around the keyboard and reports real `WindowInsets.ime` insets.
    Verified live: rebuilt, reinstalled, opened a text field on the track
    detail screen - the suggestion strip now docks correctly above the
    keyboard and the back button is unobstructed.
8y. ~~A lot of "Orch music" tracks with bird/animal/nature titles were sitting
    unidentified in Verify/Match Found/No Match, and the user said many of
    them come from a real CD, "Paradise: New Zealand's Natural Soundscape"~~ -
    identified via direct database work (same backend-SQL approach as the CD
    photo batch), not a UI feature. 41 tracks (numbered 1-52, with real gaps)
    already had correct embedded tags for this album, which gave a verified
    naming convention to extend: wildlife tracks credit the species' Māori
    name as artist, pure ambience/scene tracks credit "David Clarke & Les
    McPherson", album is "Paradise - New Zealand's Natural Soundscape" /
    albumArtist "Nature". Filling in the numbering gaps (26-33, 53-75) turned
    up 27 more tracks with clean but blank metadata, plus 4 tracks (55, 58,
    69, 75) whose embedded audio headers were corrupt, which had produced a
    bad filename-guess (e.g. "Long-Tailed Cuckoo" split into title "Tailed
    Cuckoo" / artist "Long") and, worse, a completely wrong MusicBrainz
    auto-match on the bare word "Cuckoo" (matched to a real Long John Baldry
    album) that was sitting in Match Found waiting to be accepted. All 31
    were identified and moved to Verify by direct SQL (`source='MANUAL_ENTRY'`
    plus clearing `matchedReleaseId`/`proposed*`/the stale cached cover art
    for the 4 bad-match tracks), landing the album at a complete, gapless 1-75
    except track 44 ("Mud Pool", per the user - not present as a file in the
    library at all, so nothing to tag). Verified live via search + screenshot.
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
