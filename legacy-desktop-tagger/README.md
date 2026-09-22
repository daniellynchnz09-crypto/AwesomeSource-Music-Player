# Music Details Generator (archived)

**This standalone Windows desktop tool is retired and no longer actively developed.**
It's kept here as a working reference implementation — its scanning, filename-parsing,
MusicBrainz matching/scoring, and undo-log logic are being ported into the new Android
music player app's on-device organization pipeline (see `../Claude/MUSIC
ORGANIZATION.md` and the architecture plan referenced from `../Claude.md`). The code
below still runs standalone if needed, but new feature work happens in the Android app.
Note: the `Music/` sample library referenced below now lives one level up, at the
repo root (`../Music/`), not inside this archived folder — pass its path explicitly
via **Add Folder** in the app, or copy it back in temporarily, if you need to run this
tool again.

---

Bulk-scans a local music library, reads embedded tags, and looks up missing
artist/album/cover art metadata online (MusicBrainz + Cover Art Archive), with a
manual entry form as the fallback whenever a confident match isn't found. See
`../Plan/design.md` for the full design and roadmap.

## Setup

Requires Python 3.9+ (PySide6 does not support Python 3.7). This project was built
and tested with Python 3.11.

```
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
```

## Run the app

```
.venv\Scripts\python -m musictagger.app
```

On first run it creates `data/settings.json` (your settings) and `data/app.db`
(scan sessions and the undo log) — both are gitignored, since they're local state,
not source. The default library folder is `Music/` in the project root.

## Run the tests

```
.venv\Scripts\python -m pytest tests/
```

Some tests in `tests/test_tag_writer.py` copy a real `.mp3` from `Music/` into a
temp directory to test the write/undo round trip end-to-end (never touching your
actual library files) — they're skipped automatically if `Music/` has no `.mp3` yet.

## Build a standalone .exe

```
.venv\Scripts\python -m PyInstaller --clean --noconfirm pyinstaller.spec
```

The built exe lands at `dist\MusicDetailsGenerator.exe` — a single file, no Python
install required on the target machine. It creates its own `data\settings.json` and
`data\app.db` next to wherever the exe itself is placed (not the project folder), so
it's portable: copy the one file anywhere and it manages its own local state there.
On first launch, use **Add Folder** to point it at your real music library — the
built exe doesn't know about this project's `Music/` folder.

## Current status: Phase 3 (packaging)

Implemented: recursive scanner, tag reading/writing (MP3/FLAC/M4A/AAC/OGG; WAV is
read-only — writing WAV tags isn't supported, see `musictagger/tags/writer.py`),
filename/folder-based guessing for missing tags, album grouping, exact- and
metadata-based duplicate detection, the manual entry form, the undo log, dry-run
mode, optional backup-before-write (mirrors each file's pre-edit original under
`data/backups/` on its first change), settings, MusicBrainz release/recording search
with retry and a query cache, Cover Art Archive lookup, confidence scoring
(auto-apply / needs-review / no-match), the review panel for picking among
candidates, "Accept All Auto-Matches", and PyInstaller packaging into a standalone
`.exe` (see above).

MusicBrainz contact info (Settings dialog) is optional but recommended — MusicBrainz's
API etiquette asks for a way to identify who's making requests; leaving it blank still
works, just with stricter throttling if MusicBrainz's servers are busy.

Verified against the completed real library (1,221 files: MP3/M4A/WAV): every file
reads cleanly (0 unreadable), and duplicate detection was tuned against real
false-positive cases that surfaced during that check — see `Plan/design.md` isn't
updated per-bugfix, but the fixes live in `musictagger/dedup/duplicate_finder.py`'s
docstrings and `tests/test_duplicate_finder.py`.

Known gaps, deferred as lower-priority polish (not required for correctness or
safety): a grouped/tree view of the file table (currently flat), in-app status
filtering, and scan-session resume after closing mid-review (the undo log and
dry-run mode already cover the safety-critical part of "don't lose or corrupt
data" — resume is purely a convenience for very large libraries).
