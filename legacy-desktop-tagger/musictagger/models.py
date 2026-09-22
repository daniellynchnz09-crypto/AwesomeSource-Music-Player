"""Shared data structures used across the scanner, tags, grouping, and UI layers."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Optional


class FileStatus(str, Enum):
    PENDING = "pending"
    TAGS_READ = "tags_read"
    UNREADABLE = "unreadable"
    INSUFFICIENT_INFO = "insufficient_info"
    NEEDS_REVIEW = "needs_review"
    MANUAL_PENDING = "manual_pending"
    AUTO_MATCHED = "auto_matched"
    LOOKUP_FAILED = "lookup_failed"
    NO_MATCH = "no_match"
    APPLIED = "applied"
    SKIPPED = "skipped"
    ERROR = "error"


class MetadataSource(str, Enum):
    """Where a TrackMetadata's field values most recently came from."""

    EMBEDDED_TAGS = "embedded_tags"
    FILENAME_GUESS = "filename_guess"
    ONLINE_LOOKUP = "online_lookup"
    MANUAL_ENTRY = "manual_entry"


@dataclass
class TrackMetadata:
    path: Path
    file_format: str = ""
    artist: Optional[str] = None
    album_artist: Optional[str] = None
    album: Optional[str] = None
    title: Optional[str] = None
    track_number: Optional[int] = None
    track_total: Optional[int] = None
    disc_number: Optional[int] = None
    disc_total: Optional[int] = None
    year: Optional[int] = None
    genre: Optional[str] = None
    duration_seconds: Optional[float] = None
    has_cover_art: bool = False
    cover_art_bytes: Optional[bytes] = None
    cover_art_mime: Optional[str] = None
    source: MetadataSource = MetadataSource.EMBEDDED_TAGS
    status: FileStatus = FileStatus.PENDING
    status_detail: str = ""

    @property
    def file_size(self) -> int:
        try:
            return self.path.stat().st_size
        except OSError:
            return 0

    def display_name(self) -> str:
        return self.path.name


@dataclass
class MBCandidate:
    """A single MusicBrainz release (or, for singletons, recording) candidate."""

    release_id: str
    title: str
    artist_credit: str
    first_release_date: Optional[str] = None
    track_count: Optional[int] = None
    score: float = 0.0
    is_recording: bool = False  # True for search_recordings results (singleton files)
    # For a recording candidate, the title of its linked release (see
    # musicbrainz_client._best_linked_release) — distinct from `title`, which for a
    # recording is the TRACK title, not the album. Needed because a search can
    # return several genuinely different recordings sharing the exact same
    # artist+track title (e.g. a studio album cut, a live-concert recording, and an
    # unrelated compilation, all literally titled "Forever Autumn" by "Jeff
    # Wayne") — without also knowing which album each is actually linked to,
    # nothing can tell those apart from real "same content, different edition"
    # ties. Left unset (None) for release-level candidates, where `title` already
    # is the album name.
    album: Optional[str] = None


@dataclass
class AlbumGroup:
    group_key: str
    files: list[TrackMetadata] = field(default_factory=list)
    best_guess_artist: Optional[str] = None
    best_guess_album: Optional[str] = None
    is_singleton: bool = False
    flagged_inconsistent: bool = False
    status: FileStatus = FileStatus.PENDING
    status_detail: str = ""
    candidates: list[MBCandidate] = field(default_factory=list)
    chosen_release_id: Optional[str] = None
    proposed: dict[Path, TrackMetadata] = field(default_factory=dict)
