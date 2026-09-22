"""Recursive filesystem walk that yields candidate audio files for scanning."""

from __future__ import annotations

from pathlib import Path
from typing import Iterator

AUDIO_EXTENSIONS = {".mp3", ".flac", ".m4a", ".aac", ".ogg", ".wav"}


def _is_junk_file(path: Path) -> bool:
    """Filters out non-audio sidecar/system files that show up in real libraries.

    AppleDouble resource-fork files (``._Song Name.mp3``) are created whenever files
    are copied off a Mac or an exFAT/FAT32 drive that macOS has touched, and they
    carry the same extension as the real audio file next to them but contain no
    usable audio — they must never be scanned as if they were tracks.
    """
    name = path.name
    if name.startswith("._"):
        return True
    if name in {"Thumbs.db", "desktop.ini", ".DS_Store"}:
        return True
    return False


def scan_folder(root: Path) -> Iterator[Path]:
    """Yields audio file paths under root, skipping junk files and unreadable entries."""
    root = Path(root)
    if not root.exists():
        return
    try:
        entries = sorted(root.rglob("*"))
    except OSError:
        return
    for entry in entries:
        try:
            if not entry.is_file():
                continue
            if entry.suffix.lower() not in AUDIO_EXTENSIONS:
                continue
            if _is_junk_file(entry):
                continue
            if entry.stat().st_size == 0:
                continue
        except OSError:
            # Locked, permission-denied, or a race where the file vanished mid-walk.
            continue
        yield entry


def scan_folders(roots: list[Path]) -> Iterator[Path]:
    for root in roots:
        yield from scan_folder(root)
