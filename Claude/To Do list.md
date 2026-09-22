To Do list

A number of tasks outlined in either the supporting documents, or tasks that the user has given to claude code that aren't to be resolved right away due to their size or the fact that they aren't currently relevant to the task on hand.

FROM THE PHASE 2 SCAFFOLDING PASS, NATIVE ANDROID ATTEMPT (superseded - see below; kept for reference since the underlying issues are conceptually the same in the new stack):

1. ~~Install a JDK + Android SDK + Gradle~~ - moot, the project switched to Expo/React Native mid-session specifically to avoid this. See the new items below instead.
2. ~~Generate the real Gradle wrapper~~ - moot, same reason.
3. ~~Chromaprint fingerprint generation~~ - still unimplemented (`src/organize/fingerprint/fingerprinter.ts` is an interface only), see item 20 below.
4. ~~Recalibrate Scorer's thresholds~~ - resolved by the switch: the TypeScript port uses `fuzzball` (a real npm port of the fuzzywuzzy/rapidfuzz family) instead of a hand-rolled reimplementation, and the ported test suite (58 tests) passes with the Python original's thresholds unchanged. No recalibration needed.
5. ~~Verify the MusicBrainz/Gemini/AcoustID JSON response shapes against real API responses~~ - done for MusicBrainz (matches exactly) and Gemini (works, after fixing the model name - see item 18). AcoustID blocked on a key type issue - see item 19.
6. Library auto-separation (classical vs. EDM vs. other) is still not yet designed in code - needs a genre-tag-first, heuristic-classifier-fallback approach per Claude/MUSIC ORGANIZATION.md.
7. ~~WAV/tag-less-format sidecar metadata read/write~~ - the `expo-sqlite` schema exists (`sidecar_metadata` table) and `pipeline/organizeLibrary.ts` reads it as a fallback when a format's tags can't be read; nothing writes to it yet (no UI to enter sidecar metadata exists).
8. The Flagged review queue UI (chat-style verification per Claude/MUSIC ORGANIZATION.md) has no implementation yet in either stack.

FROM THE EXPO/REACT NATIVE SWITCH (current stack - see Claude/ANDROID ARCHITECTURE.md for full context):

9. Set up EAS Build and produce the first custom Expo dev client build - needs the user's own Expo account (login is interactive, not a key to hand over). Nothing has run on an actual device yet.
10. ~~Port the MusicBrainz/Cover Art Archive/Gemini/AcoustID network clients~~ - done (`src/organize/metadata/`), fetch-based, and verified live (see items 18-19).
11. ~~Build the `expo-sqlite` persistence layer~~ - done (`src/organize/persistence/database.ts`): tracks, per-track artist credits, libraries, sidecar metadata, the undo log (scan_sessions/tag_changes), and both query caches. Now actually used by `pipeline/organizeLibrary.ts`.
12. ~~Build the `expo-secure-store` wrapper~~ - done (`src/organize/settings/secureSettings.ts`), and now wired to a real Settings screen (`app/settings.tsx`) - see item 22.
13. Wire up `expo-audio` for real playback, and specifically verify Android's `setActiveForLockScreen` requirement (found via research this session) actually keeps playback alive past the ~3-minute OS background-audio limit on a real device.
14. Playlist/queue logic (up next, add-to-queue-front/back per Claude/Design.md) needs to be built at the app layer, since `expo-audio` is a lower-level primitive than `react-native-track-player` would have been (the library initially chosen, then dropped after `expo-doctor` flagged it as incompatible with React Native's New Architecture).
15. ~~Chromaprint fingerprint generation~~ - see item 20.
16. ~~Wire the network clients' results into `database.ts`'s query caches~~ - done: `pipeline/queryGroup.ts` calls `getCachedMbQuery`/`setCachedMbQuery` for every MusicBrainz search. Gemini's cache table exists but isn't wired in yet (grounding calls aren't cached today - low priority, since they only run for already-ambiguous results, not the bulk case).
17. ~~Wire a first real end-to-end smoke test~~ - not run yet; blocked on EAS Build/a device to actually run the app on (item 9), not on missing code - `pipeline/organizeLibrary.ts` is a complete scan-to-database pipeline, just never executed.
18. ~~Fix the Gemini model name~~ - done: `gemini-2.0-flash` is decommissioned, switched to `gemini-3.6-flash` after verifying live.
19. The AcoustID key the user provided is a personal/user key, not an *application* key - lookups return "invalid API key". Needs a new key registered at https://acoustid.org/new-applications.
20. Chromaprint fingerprint *generation* - researched this session (see Claude/ANDROID ARCHITECTURE.md's dedicated section): no existing React Native wrapper for the real libchromaprint C library exists, and a from-scratch JS reimplementation would very likely produce fingerprints AcoustID's database won't match (plus still needs a PCM-decoding capability this stack doesn't have either way). Real next step is a focused native-module task once EAS Build/a device exists for actual iteration - not attempted blind. AcoustID *lookup* itself is implemented and ready once both this and item 19 are resolved.
21. Writing corrected tags back into files' embedded metadata is unimplemented - `tags/audioTagReader.ts` (backed by `@missingcore/audio-metadata`) is read-only; no verified write-capable library found yet. "Applying" a match today only updates the app's database, not the file itself.
22. ~~A Settings screen~~ - done (`app/settings.tsx`): Gemini/AcoustID keys and MusicBrainz contact, auto-saving on blur via `secureSettings.ts`.
23. ~~A basic UI screen~~ - done (`app/index.tsx`, the "Library" screen): pick-a-folder button wired to `pipeline/organizeLibrary.ts`, live progress, and a list of every scanned track's status. Verified via a real `npx expo export --platform android` Metro bundle (1352 modules, zero errors) - not yet seen running on an actual device/Expo Go, since that's the user's next step.
24. Testing the app in plain Expo Go (not a custom dev client) will show navigation and Settings working, but tapping "Choose Folder & Organize" won't actually read tags - `@missingcore/audio-metadata`/`@dr.pogodin/react-native-fs` are third-party native modules Expo Go doesn't bundle. This fails gracefully per-file (shows as an error status in the list) rather than crashing, but the user should know real scanning still needs the EAS-built dev client.