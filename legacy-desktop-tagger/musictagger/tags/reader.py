"""Reads embedded tags from an audio file into a normalized TrackMetadata record.

Text fields are read through mutagen's "easy" interface, which exposes a uniform
set of string keys (artist, album, title, tracknumber, date, genre, ...) across
MP3/FLAC/MP4/OGG. Cover art is NOT exposed by the easy interface, so it's read in a
second, format-specific pass.
"""

from __future__ import annotations

import base64
import re
from pathlib import Path
from typing import Optional

import mutagen

from musictagger.models import FileStatus, TrackMetadata

_YEAR_RE = re.compile(r"(\d{4})")


def _split_slash_number(raw: Optional[str]) -> tuple[Optional[int], Optional[int]]:
    if not raw:
        return None, None
    parts = str(raw).split("/", 1)
    try:
        number = int(parts[0]) if parts[0].strip() else None
    except ValueError:
        number = None
    total = None
    if len(parts) > 1:
        try:
            total = int(parts[1].strip()) if parts[1].strip() else None
        except ValueError:
            total = None
    return number, total


def _extract_year(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    match = _YEAR_RE.search(str(raw))
    return int(match.group(1)) if match else None


def _read_cover_art(path: Path, meta: TrackMetadata) -> None:
    """Best-effort, format-specific cover art extraction. Never raises."""
    suffix = path.suffix.lower()
    try:
        if suffix == ".mp3":
            from mutagen.id3 import ID3

            tags = ID3(str(path))
            apics = tags.getall("APIC")
            if apics:
                meta.cover_art_bytes = apics[0].data
                meta.cover_art_mime = apics[0].mime
        elif suffix == ".flac":
            from mutagen.flac import FLAC

            flac = FLAC(str(path))
            if flac.pictures:
                meta.cover_art_bytes = flac.pictures[0].data
                meta.cover_art_mime = flac.pictures[0].mime
        elif suffix in (".m4a", ".aac"):
            from mutagen.mp4 import MP4, MP4Cover

            mp4 = MP4(str(path))
            covers = mp4.tags.get("covr") if mp4.tags else None
            if covers:
                cover = covers[0]
                meta.cover_art_bytes = bytes(cover)
                meta.cover_art_mime = (
                    "image/png" if cover.imageformat == MP4Cover.FORMAT_PNG else "image/jpeg"
                )
        elif suffix == ".ogg":
            from mutagen.flac import Picture
            from mutagen.oggvorbis import OggVorbis

            ogg = OggVorbis(str(path))
            raw = ogg.get("metadata_block_picture")
            if raw:
                picture = Picture(base64.b64decode(raw[0]))
                meta.cover_art_bytes = picture.data
                meta.cover_art_mime = picture.mime
        elif suffix == ".wav":
            # WAV tag support (ID3 chunk inside RIFF) is inconsistent across encoders;
            # treat cover art here as a bonus, never a required read.
            from mutagen.id3 import ID3

            try:
                tags = ID3(str(path))
                apics = tags.getall("APIC")
                if apics:
                    meta.cover_art_bytes = apics[0].data
                    meta.cover_art_mime = apics[0].mime
            except Exception:
                pass
    except Exception:
        # Cover art is a bonus field; a failure here must not affect text-tag results.
        return
    meta.has_cover_art = meta.cover_art_bytes is not None


def read_tags(path: Path) -> TrackMetadata:
    meta = TrackMetadata(path=path, file_format=path.suffix.lower().lstrip("."))
    try:
        audio = mutagen.File(str(path), easy=True)
    except Exception as exc:
        meta.status = FileStatus.UNREADABLE
        meta.status_detail = f"mutagen error: {exc}"
        return meta

    if audio is None:
        meta.status = FileStatus.UNREADABLE
        meta.status_detail = "unrecognized or unsupported audio format"
        return meta

    def first(key: str) -> Optional[str]:
        try:
            values = audio.get(key)
        except Exception:
            return None
        return values[0] if values else None

    meta.artist = first("artist")
    meta.album_artist = first("albumartist")
    meta.album = first("album")
    meta.title = first("title")
    meta.genre = first("genre")

    meta.track_number, meta.track_total = _split_slash_number(first("tracknumber"))
    meta.disc_number, meta.disc_total = _split_slash_number(first("discnumber"))
    meta.year = _extract_year(first("date") or first("originaldate"))

    if getattr(audio, "info", None) is not None:
        meta.duration_seconds = getattr(audio.info, "length", None)

    _read_cover_art(path, meta)

    meta.status = FileStatus.TAGS_READ
    return meta
