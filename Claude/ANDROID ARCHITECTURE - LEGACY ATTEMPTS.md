ANDROID ARCHITECTURE - LEGACY ATTEMPTS:
This document holds the full write-ups of the two superseded attempts at this app,
split out of `Claude/ANDROID ARCHITECTURE.md` (which now covers only the current,
living native-Kotlin implementation) so that document stays focused on what's
actually true right now rather than growing indefinitely. These are kept, not
deleted, per Claude.md's "keep the original plan in some sort of backlog" instruction -
the real bugs/fixes each attempt found are cited from `ANDROID ARCHITECTURE.md`'s
"LESSONS TO CARRY FORWARD" section and still inform the current implementation even
though the code itself is archived (`legacy-expo-attempt/`, `legacy-android-native-attempt/`).

--- Attempt 2: Expo/React Native ---

Framework: Expo + React Native, not native Kotlin. The user already ran Expo on
their phone and wanted to reuse that workflow rather than install Android Studio.
The concrete path used: a custom Expo "dev client" (not the stock Expo Go app,
which can't include the custom native modules this app needed) built via EAS
Build, which compiles in Expo's cloud - so no local Android Studio/JDK/SDK install
was needed at any point. This got as far as a working organization pipeline, a
basic Setup/Library/Settings UI, and one successful EAS Build dev-client install,
before the user decided to switch back to native Android Studio after all. Full
source preserved at `legacy-expo-attempt/` (see its README for the specific bugs
found and fixed during this attempt, carried forward into `ANDROID ARCHITECTURE.md`'s
"LESSONS TO CARRY FORWARD" section).

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
logic (up next, add-to-queue-front/back) would have needed to be built at the app
layer on top of it.

Organization pipeline: TypeScript, ported a second time from the same Python
original. Used `fuzzball` (an actual npm port of the fuzzywuzzy/rapidfuzz family)
for fuzzy string matching, rather than a hand-rolled reimplementation - a real
improvement over the Kotlin attempt, where no such library existed and the scoring
logic had to reimplement WRatio/token_set_ratio/token_sort_ratio from scratch with
an explicit "needs recalibration" caveat. The fuzzball-based port's ported test
suite passed 58/58 with the *original* thresholds unchanged (AUTO_APPLY_THRESHOLD=90,
NEEDS_REVIEW_THRESHOLD=60, AUTO_APPLY_MARGIN=10) - no recalibration needed, unlike
the Kotlin attempt.

Persistence: `expo-sqlite` (SQLite, same shape as the Python original's schema).

Secrets (Gemini/AcoustID API keys, MusicBrainz contact string): `expo-secure-store`
(Keystore-backed encrypted storage on Android), mirroring the native plan's
`SecureSettings` design.

File access: `expo-document-picker` + `expo-file-system` (SAF-backed, persistable
URI permissions) in place of native Storage Access Framework calls.

VERIFIED DURING THIS ATTEMPT (Node/npm had real registry access, unlike the native
Android toolchain during the first attempt, which had none - so this pass could
actually install real dependencies and run real checks, not just write unverified
source):
- `npm install` / `npx expo install` - real dependency resolution, including
  discovering and fixing real compatibility issues (the New Architecture
  incompatibility above, a missing `expo-asset` peer dependency, and later a
  missing `expo-font` peer dependency of `@expo/vector-icons` that `expo-doctor`
  caught before it could crash a build).
- `npx tsc --noEmit` - passed with zero errors across the whole project.
- `npx jest` - the ported test suites (Scorer, FilenameParser, AlbumGrouper,
  EdmCreditParser - 58 tests total) all passed for real, against the real fuzzball
  library, using the Python original's unmodified thresholds.
- `npx expo-doctor` - 21/21 checks passed.
- `npx expo export --platform android` - a real Metro bundle (1352-1579 modules
  depending on when it was run) with zero resolution errors.
- A real EAS Build cloud build succeeded and produced an installable Android dev
  client APK, linked to the user's own EAS account/project
  (`kringlepringles-team/awesomesource-media-player`).

CURRENT STATE AT THE TIME OF THE SWITCH BACK: `legacy-expo-attempt/` holds the full
scaffold (`package.json`, `app.json`, `tsconfig.json`, `babel.config.js`, `eas.json`),
a working Expo Router UI (`app/index.tsx` "Setup" first-run screen, `app/library.tsx`
returning-user track list, `app/settings.tsx`, `src/components/ProgressBar.tsx` - a
unified, per-phase-colored progress bar with a percentage readout), and the full
`src/organize/` pipeline described in its README. `NOT YET STARTED` at the point of
the switch: library auto-separation (classical vs. EDM classifier), the Flagged
review queue UI, actual playback wiring via `expo-audio`, writing corrected tags
back into files, and the visual design pass (Neo-Aero/Dark-Aero/skeuomorphic per
Claude/App DESIGN.md).

A note on one non-technical wrinkle from this attempt worth remembering: installing
new native dependencies (e.g. `@expo/vector-icons`, `expo-dev-client`, `expo-font`)
while a Metro dev server is already running left it with a stale module-resolution
cache, producing "Unable to resolve" errors for files that genuinely existed on
disk. Fix was always the same: stop the dev server (on Windows, its child `node.exe`
sometimes survives the parent shell being killed and keeps holding the port - check
with `Get-NetTCPConnection`/`Stop-Process` if a restart claims the port is still in
use) and restart with `--clear`. Worth remembering for any future dev-server-plus-
package-manager workflow, not just this one.

--- Attempt 1: Native Android (Kotlin), original plan ---

Framework/UI: Kotlin, Jetpack Compose (declarative UI fits the many themeable/
animated screens in Claude/Design.md better than XML views).

Playback: Media3 (`androidx.media3`) - `ExoPlayer` for playback,
`MediaSessionService` for background playback, lock-screen/notification controls,
and external device (headset/Bluetooth) button handling. This is the standard,
actively-maintained successor to `ExoPlayer`+`MediaSessionCompat` and is built
specifically for the "plays in the background, controllable from lock
screen/notification/external devices" requirement in Claude/Design.md.

Local storage: Room (SQLite) for the library database (tracks, albums, artists,
playlists, libraries, likes, ratings, play history, queue state, undo log, MB/Gemini
query caches) - a direct architectural descendant of
`legacy-desktop-tagger/musictagger/persistence/db.py`, same undo-log pattern.

File access: Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) to let the user
pick their music folder(s) once and retain persistent URI permissions - required on
modern Android instead of raw filesystem paths.

Tag reading/writing: needs a JVM/Android tagging library spike early (candidates:
`jaudiotagger`, or `MediaMetadataRetriever` + a writer-capable library for ID3/MP4/
Vorbis atoms). This replaces `mutagen`.

Networking: Retrofit/OkHttp for MusicBrainz, Cover Art Archive, Gemini API, and
AcoustID calls.

Fuzzy matching: no direct `rapidfuzz` equivalent on Android was found at the time -
the plan was to port the specific algorithms actually used (Levenshtein-based
WRatio/token_set_ratio/token_sort_ratio) or adopt a JVM fuzzy-string library with
equivalent primitives. (The Expo attempt later found `fuzzball`, a real npm port of
the same family, for the TypeScript side - worth checking whether an equivalent
real, maintained JVM port exists now before reimplementing from scratch again.)

Audio fingerprinting: Chromaprint via a JNI/native build for Android (or an existing
Android-compatible wrapper) + the AcoustID web API for lookup - still the plan, see
`ANDROID ARCHITECTURE.md`'s "CHROMAPRINT FINGERPRINT GENERATION" section.

Visualizer: Media3's audio processing / `Visualizer` (`android.media.audiofx`) for
FFT/beat data, rendered in Compose (Canvas) for the pulsing-album-art effect; the
lock-screen and screen-edge-overlay variants will need `SYSTEM_ALERT_WINDOW`
(overlay) permission - flag this to the user before implementing, since it's a
sensitive permission Play Store treats carefully (moot if sideloaded only, but worth
confirming distribution method later).

This attempt got as far as project scaffolding and ported (but never
build-verified, since the toolchain wasn't available in that session) Kotlin
versions of the scorer, filename parser, EDM credit parser, and album grouper, with
JUnit test ports. Preserved at `legacy-android-native-attempt/` (see its README) -
still a useful reference alongside the TypeScript version in `legacy-expo-attempt/`.
