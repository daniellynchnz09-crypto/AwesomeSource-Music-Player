# Native Android attempt (archived)

**Superseded, same session it was written in.** This was the first Phase 2 attempt:
a native Kotlin + Jetpack Compose + Media3 + Room Android project. The user then
decided to switch to Expo/React Native instead (to reuse their existing Expo
workflow and avoid a local Android Studio install, using EAS Build's cloud
compilation instead), before any of this was ever built or run.

Kept here rather than deleted because the ported organization-pipeline logic is
still a useful reference for the TypeScript re-port (see the new project root):

- `app/src/main/java/com/mslynch/awesomesource/organize/matching/Scorer.kt` +
  `FuzzyMatch.kt` - the MusicBrainz candidate scoring/decision logic ported from
  `legacy-desktop-tagger/musictagger/matching/scorer.py`.
- `.../organize/tags/FilenameParser.kt` - ported from `filename_parser.py`.
- `.../organize/tags/EdmCreditParser.kt` - the new EDM collab/VIP/remix
  artist-vs-composer crediting logic (no Python original - see
  `Claude/MUSIC ORGANIZATION.md`).
- `.../organize/grouping/AlbumGrouper.kt` - ported from `album_grouper.py`.
- `app/src/test/` - JUnit ports of the Python `pytest` suites, useful as a
  reference for the equivalent Jest/Vitest tests in the TypeScript version.

See `Claude/ANDROID ARCHITECTURE.md` for the full decision history (now superseded
by the Expo/React Native architecture doc).
