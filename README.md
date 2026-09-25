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
`Claude/ANDROID ARCHITECTURE.md` (the two superseded attempts' full write-ups now
live in `Claude/ANDROID ARCHITECTURE - LEGACY ATTEMPTS.md`, split out to keep the
main doc focused on the current implementation). The organization pipeline (scanning,
tag reading, MusicBrainz/Gemini grounding/AcoustID matching, scoring, persistence) is
fully
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

A follow-up investigation into why the review-status stats (1368) didn't match the
~2500-file library count found and fixed a real bug: 31 real `.m4a` files were
permanently unrecoverable because a `jaudiotagger` tag-parse failure had no fallback,
unlike the WAV/OGG path - fixed so a tag-read failure now falls back to sidecar/
filename-guessing too (see ANDROID ARCHITECTURE.md's "MISSING-FILES INVESTIGATION"
section). The rest of the apparent gap was confirmed to be non-music project
internals and macOS resource-fork junk, correctly excluded by the scanner. The
Library screen also gained long-press multi-select with bulk field editing (set
Artist/Album/etc. across many tracks at once, leaving unfilled fields untouched per
track - see "BULK MULTI-SELECT EDITING"), the app now requests the display's highest
available refresh rate, and the MusicBrainz query cache's already-working pattern was
extended to Gemini grounding responses so rescanning an already-organized library
stops re-spending Gemini quota on matches it already graded.

A bulk edit now automatically re-queries MusicBrainz/Gemini for the tracks it just
touched, and looks for other files in the same folder that plausibly belong to the
same now-confirmed album (see ANDROID ARCHITECTURE.md's "AUTOMATIC RE-QUERY AFTER A
BULK EDIT" section) - verified end-to-end against the user's real Skrillex "Quest For
Fire" album. That same real-data test surfaced and led to fixing a genuine,
foundational, pre-existing bug affecting every prior multi-track match in the app:
`MediumDto.tracks` had the wrong JSON key name, so per-track title/artist/track-number
data from a matched MusicBrainz release has silently never been available - only
release-level fields (album/albumArtist/year) ever worked. Fixed the key, and also
fixed `TrackEntity.reviewStatus()` (the single un-persisted formula every screen
reads) to treat a `proposedArtist` that disagrees with the real artist as unconfirmed,
so real collaboration credits (e.g. "Skrillex, Missy Elliott & Mr. Oizo") now
correctly surface as a draft to accept instead of being silently discarded (see
ANDROID ARCHITECTURE.md's "A REAL FOUNDATIONAL BUG FOUND VIA THE SKRILLEX ALBUM"
section).

The Library list now shows a small album-art thumbnail per track (extracted from
embedded tags, loaded via Coil, with a custom default placeholder mark for tracks
with no art yet - see "ALBUM ART THUMBNAILS IN THE LIBRARY LIST"). Verifying it
against real data surfaced a genuinely serious finding, since fixed for real: a full
"Choose Folder & Organize" rescan of an *already-organized* library was silently
reverting every previously accepted-match or manually-edited correction back to the
tracks' raw, unwritten file tags. Caught mid-incident and fully recovered with zero
data loss from an incidental database backup (verified directly against the
database, not just the UI). The real fix, once the intended design was correctly
understood - the database is meant to be the protected, authoritative record once a
track has details, independent of whether the file itself is ever written to - was a
guard (`TrackEntity.isCurated()`) that makes a rescan skip any already-matched or
manually-edited track entirely, rather than anything to do with tag-writing. Verified
by deliberately re-running the exact rescan that caused the original incident and
confirming the data survived intact this time - see ANDROID ARCHITECTURE.md's "THE
ACTUAL FIX FOR THE DATA-LOSS INCIDENT" section.

A session of actually using the organized library (searching, filtering, accepting
matches, bulk-editing) surfaced and fixed five more real bugs: a track could get
permanently stuck at Match Found because "complete" wrongly required a track number
that a genuine various-artists-compilation single could never resolve; search and
the status-filter chips looked broken together because an isolated stats-tile filter
silently scoped search results too, with no visual reminder; cover art was fetched
from Cover Art Archive but never actually wired into the field the UI reads, and
`isCurated()` blocked any already-matched track from ever getting it backfilled
(fixed with a dedicated backfill pass, verified against the real Skrillex "Quest For
Fire" album picking up its real cover); that backfill's own first real run then
looked exactly like a hang with no progress indication, so it gained one; and system
back from the track-detail screen exited the whole app instead of returning to
Library. Also added, per direct request: a bulk "Approve" action next to the
existing bulk "Edit" (both now full-width rectangular buttons, not a small corner
icon) that reuses the single-track accept logic in one batched write, and an
alphabetical/recently-updated sort control - see ANDROID ARCHITECTURE.md's "A BATCH
OF REAL BUGS FOUND WHILE USING THE APP FOR REAL" section.

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
