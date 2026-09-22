"""Writes approved metadata back into an audio file using an atomic write pattern.

Safety approach (see Plan/design.md "Safety / reversibility"): edits are applied to a
temporary copy of the file, the copy is verified to re-open cleanly with mutagen
before anything touches the original, and only then is the original atomically
replaced via os.replace(). The original is never edited in place.
"""

from __future__ import annotations

import base64
import os
import shutil
from pathlib import Path
from typing import Optional

import mutagen

from musictagger.models import FileStatus, TrackMetadata
from musictagger.tags.reader import read_tags

TEXT_FIELDS = ["artist", "album_artist", "album", "title", "genre"]

EASY_KEY_MAP = {
    "artist": "artist",
    "album_artist": "albumartist",
    "album": "album",
    "title": "title",
    "genre": "genre",
}

# Extends EASY_KEY_MAP with the number-ish fields, for the undo path below, which
# writes exact raw values rather than going through the structured number formatting
# that write_tags() uses for fresh (non-undo) writes.
RESTORABLE_KEY_MAP = {
    **EASY_KEY_MAP,
    "track_number": "tracknumber",
    "disc_number": "discnumber",
    "year": "date",
}

Change = tuple[str, Optional[str], Optional[str]]


class TagWriteError(Exception):
    pass


def _apply_text_fields(audio_easy, field_values: dict[str, str]) -> None:
    for field, value in field_values.items():
        key = EASY_KEY_MAP.get(field)
        if key is not None:
            audio_easy[key] = str(value)


def _apply_number_fields(
    audio_easy,
    new_values: TrackMetadata,
    apply_track: bool,
    apply_disc: bool,
    apply_year: bool,
) -> None:
    if apply_track:
        val = str(new_values.track_number)
        if new_values.track_total:
            val += f"/{new_values.track_total}"
        audio_easy["tracknumber"] = val
    if apply_disc:
        val = str(new_values.disc_number)
        if new_values.disc_total:
            val += f"/{new_values.disc_total}"
        audio_easy["discnumber"] = val
    if apply_year:
        audio_easy["date"] = str(new_values.year)


def _write_cover_art(path: Path, data: bytes, mime: str) -> None:
    suffix = path.suffix.lower()
    if suffix == ".mp3":
        from mutagen.id3 import ID3, APIC, ID3NoHeaderError

        try:
            tags = ID3(str(path))
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall("APIC")
        tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
        tags.save(str(path))
    elif suffix == ".flac":
        from mutagen.flac import FLAC, Picture

        flac = FLAC(str(path))
        flac.clear_pictures()
        pic = Picture()
        pic.data = data
        pic.mime = mime
        pic.type = 3
        flac.add_picture(pic)
        flac.save()
    elif suffix in (".m4a", ".aac"):
        from mutagen.mp4 import MP4, MP4Cover

        mp4 = MP4(str(path))
        fmt = MP4Cover.FORMAT_PNG if "png" in mime else MP4Cover.FORMAT_JPEG
        mp4.tags["covr"] = [MP4Cover(data, imageformat=fmt)]
        mp4.save()
    elif suffix == ".ogg":
        from mutagen.flac import Picture
        from mutagen.oggvorbis import OggVorbis

        ogg = OggVorbis(str(path))
        pic = Picture()
        pic.data = data
        pic.mime = mime
        pic.type = 3
        ogg["metadata_block_picture"] = [base64.b64encode(pic.write()).decode("ascii")]
        ogg.save()
    elif suffix == ".wav":
        from mutagen.id3 import ID3, APIC, ID3NoHeaderError

        try:
            tags = ID3(str(path))
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall("APIC")
        tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=data))
        tags.save(str(path))
    else:
        raise TagWriteError(f"cover art writing not supported for {suffix} files")


def _write_to_file(
    path: Path,
    field_values: dict[str, str],
    new_values: TrackMetadata,
    apply_track: bool,
    apply_disc: bool,
    apply_year: bool,
    apply_cover: bool,
) -> None:
    audio = mutagen.File(str(path), easy=True)
    if audio is None:
        raise TagWriteError("could not reopen temp file for writing")
    _apply_text_fields(audio, field_values)
    if apply_track or apply_disc or apply_year:
        _apply_number_fields(audio, new_values, apply_track, apply_disc, apply_year)
    audio.save()

    if apply_cover:
        _write_cover_art(path, new_values.cover_art_bytes, new_values.cover_art_mime or "image/jpeg")


def _decide_changes(
    current: TrackMetadata,
    new_values: TrackMetadata,
    fields_to_write: set[str],
    overwrite: bool,
) -> tuple[dict[str, str], bool, bool, bool, bool, list[Change]]:
    changes: list[Change] = []
    field_values: dict[str, str] = {}

    for field in TEXT_FIELDS:
        if field not in fields_to_write:
            continue
        new_val = getattr(new_values, field)
        old_val = getattr(current, field)
        if not new_val:
            continue
        if not overwrite and old_val:
            continue
        if new_val == old_val:
            continue
        field_values[field] = new_val
        changes.append((field, old_val, new_val))

    def wants(field_name: str, new_val, old_val) -> bool:
        if field_name not in fields_to_write or new_val is None:
            return False
        if not overwrite and old_val:
            return False
        return new_val != old_val

    apply_track = wants("track_number", new_values.track_number, current.track_number)
    if apply_track:
        changes.append(("track_number", str(current.track_number or "") or None, str(new_values.track_number)))

    apply_disc = wants("disc_number", new_values.disc_number, current.disc_number)
    if apply_disc:
        changes.append(("disc_number", str(current.disc_number or "") or None, str(new_values.disc_number)))

    apply_year = wants("year", new_values.year, current.year)
    if apply_year:
        changes.append(("year", str(current.year or "") or None, str(new_values.year)))

    apply_cover = (
        "cover_art" in fields_to_write
        and new_values.cover_art_bytes is not None
        and (overwrite or not current.has_cover_art)
    )
    if apply_cover:
        changes.append(("cover_art", "present" if current.has_cover_art else None, "present"))

    return field_values, apply_track, apply_disc, apply_year, apply_cover, changes


def _backup_path(path: Path, backup_root: Path) -> Path:
    """Mirrors path's full absolute location under backup_root, so files with the
    same name in different folders never collide and no knowledge of "the" library
    root is required — e.g. C:\\Users\\me\\Music\\a.mp3 -> backup_root\\C\\Users\\me\\Music\\a.mp3."""
    resolved = path.resolve()
    drive = resolved.drive.rstrip(":") or "root"
    return backup_root / drive / Path(*resolved.parts[1:])


def _backup_original_if_needed(path: Path, backup_root: Optional[Path]) -> None:
    if backup_root is None:
        return
    target = _backup_path(path, backup_root)
    if target.exists():
        return  # already backed up before this file's first edit
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(path, target)


def write_tags(
    path: Path,
    new_values: TrackMetadata,
    fields_to_write: set[str],
    overwrite: bool,
    dry_run: bool = False,
    backup_root: Optional[Path] = None,
) -> tuple[bool, list[Change], str]:
    """Applies approved fields to path's tags. Returns (success, changes, error).

    If backup_root is given, the original file is copied there (mirroring its full
    path) before its first modification — a "restore the original file" safety net
    beyond the tag-value undo log, per Plan/design.md. It's skipped if a backup at
    that mirrored path already exists, so only the pre-any-edit original is ever kept.
    """
    if path.suffix.lower() == ".wav":
        # WAV tag support varies too much across encoders to write reliably (see
        # Plan/design.md); mutagen's easy-tag interface doesn't apply cleanly to WAV,
        # so attempting it fails with a confusing low-level error instead of this
        # clear, documented one. Reading WAV tags remains best-effort; writing is
        # deferred past v1.
        return False, [], "WAV tag writing is not supported in v1 (read-only best-effort format)"

    current = read_tags(path)
    if current.status == FileStatus.UNREADABLE:
        return False, [], "file is unreadable, cannot write tags"

    field_values, apply_track, apply_disc, apply_year, apply_cover, changes = _decide_changes(
        current, new_values, fields_to_write, overwrite
    )

    if not changes:
        return True, [], ""
    if dry_run:
        return True, changes, ""

    try:
        _backup_original_if_needed(path, backup_root)
    except OSError as exc:
        return False, [], f"could not create backup before writing: {exc}"

    tmp_path = path.with_name(f".{path.stem}.musictagger_tmp{path.suffix}")
    try:
        shutil.copy2(path, tmp_path)
        _write_to_file(tmp_path, field_values, new_values, apply_track, apply_disc, apply_year, apply_cover)
        verify = read_tags(tmp_path)
        if verify.status == FileStatus.UNREADABLE:
            raise TagWriteError("written file failed to re-open cleanly, original left untouched")
        os.replace(tmp_path, path)
    except Exception as exc:
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except OSError:
                pass
        return False, [], str(exc)

    return True, changes, ""


def apply_raw_fields(path: Path, field_values: dict[str, Optional[str]]) -> tuple[bool, str]:
    """Directly sets or clears exact field values, bypassing all overwrite-policy
    checks. Used only by the undo path (musictagger/persistence/db.py) to restore
    prior tag values exactly as recorded in the undo log.

    Note: cover art is not restorable through this path in v1 — the undo log records
    only whether art was present before a change, not the original image bytes, so a
    cover-art change can be reversed by re-running a fresh lookup/manual entry but not
    by undo. This is a known v1 limitation, not a silent gap.
    """
    tmp_path = path.with_name(f".{path.stem}.musictagger_undo_tmp{path.suffix}")
    try:
        shutil.copy2(path, tmp_path)
        audio = mutagen.File(str(tmp_path), easy=True)
        if audio is None:
            raise TagWriteError("could not reopen temp file for writing")
        for field, value in field_values.items():
            key = RESTORABLE_KEY_MAP.get(field)
            if key is None:
                continue
            if value is None:
                audio.pop(key, None)
            else:
                audio[key] = str(value)
        audio.save()
        verify = read_tags(tmp_path)
        if verify.status == FileStatus.UNREADABLE:
            raise TagWriteError("restored file failed to re-open cleanly, original left untouched")
        os.replace(tmp_path, path)
    except Exception as exc:
        if tmp_path.exists():
            try:
                tmp_path.unlink()
            except OSError:
                pass
        return False, str(exc)
    return True, ""
