# Music Details Generator — Design & Build Plan

## Context

The user wants a Windows PC application that bulk-scans a local music library, reads
each file's existing metadata, looks up the correct artist/album/cover art/etc. from
an online music database, and writes the corrected tags back into the files. When no
confident online match exists, the app should fall back to a manual-entry form rather
than silently failing or guessing wrong. The project directory is currently empty, so
this is a from-scratch design.

This plan's own output is a planning document — once approved, the immediate next
step (still within this same engagement, but outside Plan Mode) is to create a
`plans/` folder in the project root containing this design as `design.md`, followed by
scaffolding the project per Phase 1 below.

Decisions already made with the user:
- **Tech stack**: Python + PySide6 (Qt) for the GUI, packaged later via PyInstaller
  into a standalone Windows `.exe`. PySide6 is preferred over PyQt6 for licensing
  (LGPL, no commercial license needed for free distribution) with a near-identical API.
- **Metadata source**: MusicBrainz (album/artist/tracklist data) + Cover Art Archive
  (cover images), both free and keyless.
- **Matching method for v1**: search using existing tags and/or filename/folder-derived
  text only — no audio fingerprinting (AcoustID/Chromaprint) in v1, to keep the
  dependency footprint small. Can be added later as a v2 enhancement if matches are
  too often ambiguous.
- **Real-world data note**: the user's library is disorganized, and some files have
  gibberish/computer-generated filenames with only a stray word or two of the real
  title/artist embedded. The filename parser and confidence scoring below are
  designed with this in mind — messy names are expected to often land in the
  needs-review/manual queue rather than being auto-applied, which is the correct,
  safe behavior rather than a gap to fix.
- **Duplicate detection**: added at the user's request — see "Duplicate detection"
  section below. Detected duplicates are only ever surfaced to the user for a
  decision; the app never deletes a file without explicit per-group confirmation.
- **Initial test data**: the user will drop their music files into a `Music/` folder
  in the project root to use as the real scan target during Phase 1 development.

## Architecture

### Module breakdown (package root: `musictagger/`)
- **`scanner/filesystem.py`** — recursively walks chosen root folder(s), yielding audio
  files (`.mp3 .flac .m4a .aac .ogg .wav`) as a generator so the UI can show progress
  incrementally on large libraries. Skips locked/hidden/zero-byte files.
- **`tags/reader.py` + `tags/writer.py`** — a `mutagen`-based abstraction that maps
  each format's quirks (ID3 for MP3, Vorbis comments for FLAC/OGG, MP4 atoms for
  M4A/AAC) into one normalized `TrackMetadata` dataclass (artist, album_artist, album,
  title, track#, disc#, year, genre, cover art bytes, format, duration). WAV is
  best-effort read-only in v1 (inconsistent tag support). All mutagen calls are
  wrapped so one corrupt file can't crash a batch — it's marked `Unreadable` instead.
- **`tags/filename_parser.py`** — when tags are sparse, derives candidate
  artist/album/title from common path patterns (`Artist/Album/## - Title.ext`,
  `Artist - Album/## Title.ext`, `Artist - Title.ext`), stripping noise tokens
  (`[Explicit]`, `(Remastered 2011)`, bitrate suffixes, etc.). Tag data always takes
  priority over filename guesses. For messy/gibberish filenames (e.g.
  `xY7_2gK9-titlefragment_final(2).mp3`) where no clean pattern matches, falls back to
  a looser strategy: split on non-alphanumeric separators, discard tokens that look
  like random IDs/hashes (high-entropy alphanumeric strings, pure digit runs, common
  junk words like `final`, `copy`, `new`), and feed the remaining recognizable word
  tokens to MusicBrainz as a loose free-text query rather than a structured
  artist+album search. Results from this path are inherently lower-confidence and are
  deliberately capped so they can't reach the auto-apply band in the scorer (see
  "Matching / scoring" below) — they always land in needs-review or manual entry so a
  wrong guess never gets silently written.
- **`grouping/album_grouper.py`** — clusters flat file list into `AlbumGroup` objects
  *before* querying, since querying per-track wastes the MusicBrainz rate budget and
  risks matching different tracks to different releases of the same album. See
  "Album grouping" below.
- **`metadata_sources/musicbrainz_client.py`** — wraps `musicbrainzngs`:
  `search_releases()` for album-level search, `get_release_by_id(includes=[...])` to
  pull the authoritative tracklist once a release is chosen, `search_recordings()` as
  a fallback for loose singleton files. Must set a descriptive User-Agent
  (`musicbrainzngs.set_useragent(app_name, version, contact)`) per MusicBrainz's API
  etiquette — **the contact value should be a user-editable Settings field, not a
  hardcoded default**, so the person running the app decides what contact info (if
  any) to expose to this third-party API. Enforces the required 1 req/sec rate limit
  (library does this internally; the client wrapper should also serialize calls
  through one queue so bulk scans never burst concurrent requests). Retries on
  `WebServiceError` with exponential backoff (1s/2s/4s), then marks the group
  `LookupFailed` (distinct from `NoMatchFound`) so the UI can offer a Retry.
- **`metadata_sources/cover_art_client.py`** — Cover Art Archive lookups keyed by
  MusicBrainz release ID (`GET /release/{mbid}/front-500` for UI thumbnails,
  full-res only fetched on commit). A 404 (no art archived) is common/expected, not
  an error — proceeds with tag fields only.
- **`matching/scorer.py`** — pure logic, no I/O, easily unit-tested. Scores each
  candidate release with a weighted blend of fuzzy string similarity (`rapidfuzz`) on
  artist/album plus a track-count-match bonus (strong disambiguator, e.g. distinguishes
  a "Greatest Hits" reissue from the correct studio album) and minor year proximity.
  Three configurable outcome bands: **auto-apply** (high score + clear margin over
  2nd place), **needs review** (moderate score or near-tied candidates — user picks
  from a candidate list), **no match** (routes to manual entry).
- **`ui/`** (PySide6) — main window, review/diff panel, manual entry form, settings
  dialog, table model. All scanning/querying/writing runs on `QThreadPool`
  workers — the GUI thread is never blocked.
- **`dedup/duplicate_finder.py`** — finds candidate duplicate songs and reports them
  for a user decision; see "Duplicate detection" below for the full design.
- **`persistence/db.py`** — local SQLite (stdlib `sqlite3`, no extra dependency) for:
  1. an **undo log** (`tag_changes`: file, field, old value, new value) — the primary
     reversibility mechanism, build this in Phase 1 since "don't corrupt files" is a
     hard requirement;
  2. scan-session resume, so closing mid-review of a large library doesn't force a
     full rescan;
  3. a cross-session MusicBrainz query cache, since re-querying thousands of albums at
     1 req/sec is slow (~8+ min for a 500-album library) and wasteful to repeat.

### End-to-end data flow
Pick folder(s) → scan (fast directory walk, decoupled from tag parsing) → per-file tag
read (or filename-parser fallback) → group into albums → one MusicBrainz query per
album group (not per track) → score candidates → route to auto-apply / needs-review /
no-match → fetch cover art thumbnail for the chosen candidate → user reviews in the
table/diff panel (bulk accept, per-row accept/reject/edit) → on commit: write undo-log
entry → write tags via the atomic-write pattern below → mark applied → summary report.

### Album grouping strategy
1. **Primary key: parent folder path** — most personal libraries are organized
   `Artist/Album/tracks`, so files sharing an immediate parent folder are assumed to
   be one album.
2. **Confirming signal: embedded album/album_artist tags** — within a folder group, if
   files disagree, split into subgroups by `(album_artist, album)` pairs instead of
   trusting the folder blindly.
3. **Fallback when tags are absent**: folder-only grouping, sanity-checked against
   filename-parser-derived artist; wildly inconsistent folders get flagged for
   individual review rather than forced into one group.
4. **Singletons** (loose files, single-track folders) skip album-level search and use
   `search_recordings` (track-level) instead, since there's no album context.

### Duplicate detection
Two detection tiers, run as a separate pass over the library (not blocking the main
scan/tag pipeline):
1. **Exact duplicates** — byte-identical files, likely copies in different folders.
   Compare file size first (cheap), then hash only same-size files (fast hash, e.g.
   `xxhash`, falling back to stdlib `hashlib.sha256` if a new dependency isn't wanted)
   to confirm. Doesn't depend on tags/metadata quality at all, so it works even before
   MusicBrainz resolution — can run as early as Phase 1.
2. **Metadata duplicates** — the same song ripped/downloaded more than once in
   different formats or bitrates (different bytes, same content). Matched on
   normalized `(artist, title)` — lowercased, punctuation-stripped, fuzzy-compared via
   `rapidfuzz` above a threshold — with track duration as a confirming signal (same
   artist/title but very different duration suggests a live version, remix, or radio
   edit, not a true duplicate, so it's flagged for the user to judge rather than
   pre-selected for deletion). Because the user's tags/filenames are often unreliable,
   this tier is most accurate once metadata has been resolved via MusicBrainz (Phase
   2), so it's designed to also re-run after auto-fill, not just once at scan time.

**UI**: a dedicated "Duplicates" view listing each duplicate group with every member's
path, format, bitrate, file size, and duration side by side, so the user can judge
which copy is worth keeping (e.g. prefer the FLAC over the MP3). A "suggest higher
quality" hint pre-highlights the likely keeper per group, but nothing is ever deleted
without the user reviewing and confirming that specific group — same
preview-before-commit principle as tag writes.

**Deletion safety**: confirmed deletions are sent to the Windows Recycle Bin via
`send2trash` rather than permanently removed, so a wrong call is still recoverable
outside the app.

### UI screens
- **Main scan view**: toolbar (add folders, start/pause/cancel), two-phase progress
  (files scanned, then albums queried — querying is the slow, rate-limited phase), a
  `QTableView` (not `QTableWidget`, for large-library performance) with status color
  coding (auto-matched / needs review / no match / error), optional grouped tree view,
  status filters.
- **Review/diff panel**: before/after field comparison, side-by-side cover art
  thumbnails with a "replace" checkbox, a candidate picker (radio list with
  score/thumbnail) for needs-review items, per-item Accept/Reject/Edit
  Manually/Search Again, plus bulk accept/reject actions.
- **Manual entry form**: fields for all standard tags, cover art via file upload,
  clipboard paste, or a "search again" button that re-queries MusicBrainz using
  user-edited text; an "apply to whole album" checkbox to propagate album-level fields
  to sibling tracks.
- **Duplicates view**: separate tab/panel listing duplicate groups (see "Duplicate
  detection" above) with per-file details and per-group keep/delete checkboxes; a
  group-level "Delete selected (to Recycle Bin)" action, never a global "delete all
  duplicates" auto-action.
- **Settings**: library folders; which fields to auto-fill; overwrite policy (fill
  blanks only vs. overwrite existing); matching score thresholds (advanced/collapsed);
  dry-run toggle; optional backup-before-write toggle; the MusicBrainz contact string.

### Safety / reversibility (layered, since corruption risk is a hard constraint)
1. Preview-before-commit is mandatory — nothing writes to disk until the user
   explicitly accepts (per-item or bulk); there's no silent auto-apply-and-write mode.
2. SQLite undo log records prior field values before every write, enabling "undo last
   batch" after the fact.
3. Atomic write pattern per file: copy to a temp file → apply mutagen edits to the
   copy → verify it re-opens cleanly with expected fields → `os.replace()` the
   original (atomic on NTFS same-volume renames) — avoids mutating the original file
   in place.
4. Optional full backup copies of originals before first modification (Settings
   toggle), for a true "restore original file" safety net beyond tag-value undo.
5. Dry-run mode: full scan/match/review pipeline runs normally, but commit becomes a
   no-op summary of what would have been written.

### Error handling
Network failure/503 → retry w/ backoff, then `LookupFailed` (not `NoMatchFound`), skip
to next group, offer Retry in UI. Cover Art 404 → expected, not an error, proceed
tag-only. Corrupt/unsupported file → caught at read, marked `Unreadable`, shown but
skipped. Permission errors (locked/open-elsewhere file) → caught on read and
especially on write/rename, marked `Error: Permission Denied`, retryable. No usable
tag/filename info → skip the wasted MB query entirely, route straight to manual entry
flagged "Insufficient Info". Partial batch failures → per-file atomic writes mean one
failure doesn't affect others; summary report separates Applied/Skipped/Failed.
Malformed MB responses → defensive `.get()` access throughout the client wrapper
rather than direct indexing, so a schema surprise degrades to a blank field, not a crash.

### Project structure
```
Music details Generator/
├── plans/design.md
├── Music/                (user's own files, dropped in for Phase 1 testing)
├── musictagger/
│   ├── app.py, config.py, models.py
│   ├── scanner/filesystem.py
│   ├── tags/{reader,writer,filename_parser}.py
│   ├── grouping/album_grouper.py
│   ├── metadata_sources/{musicbrainz_client,cover_art_client}.py
│   ├── matching/scorer.py
│   ├── dedup/duplicate_finder.py
│   ├── persistence/db.py
│   ├── workers/{scan_worker,query_worker,write_worker}.py
│   └── ui/{main_window,review_panel,manual_entry_form,settings_dialog,file_table_model}.py
├── tests/ (unit tests for grouping/scoring/parsing + fixtures)
├── requirements.txt
├── pyinstaller.spec   (added in Phase 3)
└── README.md
```

### Key dependencies
`PySide6` (GUI, LGPL) · `mutagen` (tag read/write) · `musicbrainzngs` (MusicBrainz
client, built-in rate limiting) · `requests` (Cover Art Archive image fetches) ·
`rapidfuzz` (fast fuzzy scoring) · `Pillow` (cover art resize/re-encode, clipboard
image handling) · `send2trash` (safe, recoverable deletion of confirmed duplicates) ·
`pyinstaller` (packaging) · `pytest` (tests) · stdlib `sqlite3` (persistence),
`hashlib` (exact-duplicate hashing, or `xxhash` if faster hashing is worth the extra
dependency).

### Phased build milestones
- **Phase 1 — local-only foundation (no network)**: scaffolding, SQLite schema incl.
  undo log, file scanner + mutagen reader for all formats, filename parser, album
  grouping with unit tests, main window with file table, fully working manual-entry
  form with atomic writes + undo logging, dry-run toggle, settings screen, exact
  (byte-identical) duplicate detection pass with the Duplicates view and
  `send2trash`-based confirmed deletion. Deliverable: a working local tag editor and
  safe write path — including safe duplicate cleanup — usable standalone with no
  online lookup yet, tested directly against the user's real `Music/` folder.
- **Phase 2 — MusicBrainz integration + auto-fill**: MB client (User-Agent, rate
  limiting, retry/backoff), Cover Art Archive client + thumbnail caching, scorer with
  configurable thresholds, wire querying into the pipeline with status routing, full
  review/diff panel with candidate picker and re-query, query result caching, and a
  re-run of duplicate detection using resolved (post-fill) metadata to catch
  same-song-different-rip duplicates that exact hashing and messy original tags
  couldn't. Deliverable: end-to-end auto-matching against MusicBrainz with manual
  fallback intact, plus metadata-aware duplicate detection.
- **Phase 3 — bulk review UX polish + packaging**: grouped tree view, filters, bulk
  accept/reject, session resume, undo-last-batch UI, performance tuning for large
  libraries, optional backup-before-write, PyInstaller packaging + test on a clean
  Windows machine (Qt plugin bundling is a common pitfall — verify via
  `--collect-all PySide6` or an explicit `.spec` `hiddenimports`/`datas`).
  Deliverable: a polished, distributable standalone `.exe`.

## Critical files
- `musictagger/tags/reader.py` / `writer.py` — the mutagen abstraction underpinning
  every tag read/write and its safety.
- `musictagger/grouping/album_grouper.py` — determines query efficiency and match
  correctness.
- `musictagger/metadata_sources/musicbrainz_client.py` — MusicBrainz integration,
  rate-limiting, and API-etiquette compliance.
- `musictagger/matching/scorer.py` — drives the auto-apply / needs-review / no-match
  decision that shapes the whole review workflow.
- `musictagger/persistence/db.py` — undo log, session resume, query cache schema.
- `musictagger/dedup/duplicate_finder.py` — exact and metadata-based duplicate
  detection that the Duplicates view and deletion flow depend on.

## Verification
- Unit tests (`pytest`) for the pure-logic modules first: `album_grouper.py`,
  `scorer.py`, `filename_parser.py`, using small fixture file sets and mocked
  MusicBrainz JSON responses — these don't need real audio files or network access.
- Manual smoke test with a small real folder of sample MP3/FLAC files (a handful of
  albums, including at least one with missing tags and one with wrong tags) to confirm
  end-to-end: scan → group → (Phase 2+) query → review → manual entry fallback →
  commit → tags/cover art correctly readable afterward in another player or via
  `mutagen` inspection.
- Confirm undo works: apply a batch, then undo, then verify original tag values are
  restored exactly.
- Confirm dry-run mode makes zero filesystem writes (diff file mtimes/hashes
  before/after a dry-run commit).
- Before Phase 3 packaging is considered done, run the built `.exe` on a machine
  without Python installed to confirm it launches and functions identically.
- Confirm duplicate detection: place two copies of the same file (one byte-identical
  copy, one re-encoded at a different bitrate/format) into the test `Music/` folder
  and verify both are correctly grouped and reported, that no deletion happens without
  explicit confirmation, and that a confirmed delete lands in the Recycle Bin
  (recoverable) rather than being permanently removed.
- Confirm messy-filename handling: include a few files with gibberish names (per the
  user's real library) among the test fixtures and verify they're routed to
  needs-review/manual entry rather than incorrectly auto-applied.
