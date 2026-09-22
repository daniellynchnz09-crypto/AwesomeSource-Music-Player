"""Derives candidate artist/album/title text from a file's path when tags are sparse.

Two tiers, per Plan/design.md:
1. Structured patterns for conventionally-organized libraries
   (Artist/Album/## - Title.ext, Artist - Album/## Title.ext, Artist - Title.ext).
2. A loose fallback for messy/gibberish filenames that discards junk tokens (random
   IDs, pure digit runs, common noise words) and returns whatever recognizable words
   remain as a free-text search query. Loose-tier guesses are marked low-confidence
   so the scorer (Phase 2) can never let them reach the auto-apply band.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    from musictagger.models import TrackMetadata

_NOISE_PATTERNS = [
    re.compile(r"\[[^\]]*\]"),  # [Explicit], [HQ], ...
    # Newgrounds Audio Portal rips very consistently tack the submission's numeric
    # ID onto the end of the TITLE tag itself, e.g. "Dr. Finkelfracken's Cure (ID:
    # 383158)" — real metadata, but not part of the actual song title.
    re.compile(r"\(id:\s*\d+\)", re.I),
    re.compile(r"\{[^}]*\}"),  # {tag}-style bracket noise, e.g. "{dj-N} Rain Full"
    re.compile(r"\((?:remaster(?:ed)?|deluxe|explicit|clean|hq|official)[^)]*\)", re.I),
    # "(free)" (a free-download promo tag) and "(Original Mix)" (redundant when
    # there's no other mix/version to distinguish it from) are common rip/download
    # bracket tags that aren't part of the actual recording title.
    re.compile(r"\((?:free|original\s+mix)\)", re.I),
    re.compile(r"\b\d{2,4}\s?kbps\b", re.I),
    re.compile(r"\(\s*\d+\s*\)"),  # trailing (1), (2) copy markers
    re.compile(r"\s*-\s*copy\b", re.I),
    # YouTube-ripped titles commonly tack on a visualizer/uploader-channel credit
    # after a literal pipe, e.g. "Song Name | Insolito (4K music visualizer)" — a
    # bare "|" essentially never appears in a real song title, so treating
    # everything from the first one onward as junk is safe.
    re.compile(r"\s*\|.*$"),
    # YouTube upload conventions: "(Lyric Video)", "(Official Video)", "(Music
    # Video)", "(Official Audio)" — any parenthetical mentioning video/audio/
    # visualizer is upload-format branding, never part of a real song title.
    re.compile(r"\([^)]*\b(?:video|audio|visualizer)\b[^)]*\)", re.I),
    # A bare (non-parenthesized) "Ft. X"/"feat. X"/"featuring X" suffix is almost
    # always the uploader appending a featured-artist credit to the title text
    # itself, e.g. "KILL OFF Ft. DYNO" — MusicBrainz keeps that in the artist
    # credit ("Ecraze ft. Dyno"), not literally inside the recording's own title
    # ("KILL OFF"). Left in, that trailing text measurably drags down the fuzzy
    # title-match score against the real title and can let an unrelated candidate
    # that happens to share a word from it score deceptively close. A
    # parenthesized "(feat. X)" is deliberately NOT touched here — some
    # MusicBrainz titles do legitimately include that as canonical text, so only
    # the bare, un-parenthesized form is treated as upload noise.
    re.compile(r"\s+(?:ft\.?|feat\.?|featuring)\s+.+$", re.I),
    # DJ-mix compilations are very commonly ripped as a single file named "Album
    # Vol. N - Mixed by DJ Name" — the "Mixed by X" tail is a credit line, not part
    # of the actual recording title (MusicBrainz's own title for these is usually
    # just "Album Vol. N (Mix)"), and it was pulling the existing, already-correct
    # tag artist through the "Artist - Title" dash-split below and getting replaced
    # with the compilation's volume phrase (e.g. "Never Say Die Vol. 7") — not a
    # real artist name at all.
    re.compile(r"\s*-\s*(?:mixed|remixed|hosted|selected|compiled)\s+by\s+.+$", re.I),
]

_TRACK_PREFIX_RE = re.compile(r"^\s*(\d{1,3})\s*[-.\s]+")
_ARTIST_TITLE_RE = re.compile(r"^(.+?)\s*-\s*(.+)$")

# A multi-act/multi-disc release is commonly laid out as
# "Album Name/Act 1/track.mp3" or ".../Disc 2/...", ".../CD1/...", ".../Part 1/..."
# — the immediate parent here is a disc/act subdivision of ONE album, not a real
# "Album" level sitting under a real "Artist" level the way Pattern 1 assumes.
# Matching Pattern 1 against this layout anyway (as observed for real, against
# Jeff Wayne's "War of the Worlds/Act 1/...") mistook the actual album name for the
# *artist* and the act subfolder for the album, which then poisoned both grouping
# and search for every track in the folder with a completely wrong artist guess.
_DISC_SUBFOLDER_RE = re.compile(r"^(?:disc|cd|act|part|volume|vol)\.?\s*\d+$", re.I)

# Folder names that are almost never actually an artist — a generic library root or
# category folder sitting two levels above a track (e.g. "Music/EDM/track.mp3") must
# not be mistaken for the Artist/Album/Track structure Pattern 1 assumes.
_GENERIC_ANCESTOR_NAMES = {
    "music", "songs", "downloads", "itunes", "mp3", "mp3s", "library",
    "unsorted", "new music", "audio", "tracks", "media", "my music",
}

_JUNK_WORDS = {
    "copy", "final", "new", "track", "file", "audio", "untitled", "download",
    "downloaded", "unknown", "song", "music", "temp", "tmp", "v2", "v3",
}


@dataclass
class FilenameGuess:
    artist: Optional[str] = None
    album: Optional[str] = None
    title: Optional[str] = None
    track_number: Optional[int] = None
    confidence: str = "structured"  # "structured" or "loose"
    search_text: Optional[str] = None  # loose free-text fallback query


def _strip_noise(text: str) -> str:
    for pattern in _NOISE_PATTERNS:
        text = pattern.sub("", text)
    return text.strip(" -_.")


def clean_noise_text(text: Optional[str]) -> str:
    """Public entry point for _strip_noise, for callers cleaning arbitrary tag text
    (not just a filename) that may never contain an "Artist - Title" dash to split
    on at all — e.g. a Newgrounds-sourced title like "Dr. Finkelfracken's Cure (ID:
    383158)", which just needs the ID suffix gone, not splitting. Callers that also
    want the dash-split still get noise-cleaning for free via split_artist_title_text
    below; this exists for the case where that split doesn't apply but the noise
    should still be removed before the text is used to search."""
    return _strip_noise(text or "")


def _looks_like_junk_token(token: str) -> bool:
    lowered = token.lower()
    if not token:
        return True
    if lowered in _JUNK_WORDS:
        return True
    if token.isdigit():
        return True
    has_digit = any(c.isdigit() for c in token)
    vowels = sum(1 for c in lowered if c in "aeiou")
    # A short alphanumeric mix with a digit and zero vowels reads as a machine-
    # generated fragment (e.g. "xY7", "2gK9"), not a real word.
    if has_digit and vowels == 0:
        return True
    if len(token) >= 5 and has_digit and vowels / len(token) < 0.15:
        return True
    return False


def _looks_plausible(text: str) -> bool:
    """A candidate artist/title string is plausible only if most of its tokens
    aren't junk — guards against e.g. "xY7_2gK9-titlefragment" matching the "Artist -
    Title" pattern just because it happens to contain a dash and one real word."""
    tokens = [t for t in re.split(r"[^A-Za-z0-9']+", text) if t]
    if not tokens:
        return False
    junk_count = sum(
        1
        for t in tokens
        # A short (1-2 digit) standalone numeral is real, ordinary title text in
        # context — "Memories 2", "Part 3" — not the gibberish _looks_like_junk_token
        # exists to catch; only counting it as junk here rejected "Memories 2" as
        # implausible outright (a real F-777 track title recovered via the
        # reversed "Title - Artist" split in query_worker.py) purely because a
        # trailing sequel number happens to also satisfy the "pure digits" rule
        # that's meant for hash-like ID fragments, not short numbers.
        if _looks_like_junk_token(t) and not (t.isdigit() and len(t) <= 2)
    )
    return junk_count < len(tokens) / 2


def split_artist_title_text(text: str) -> tuple[Optional[str], Optional[str]]:
    """Splits "Artist - Title" style text into (artist, title) when the pattern
    plausibly applies; returns (None, None) otherwise.

    Not just for filenames — many YouTube-sourced rips (particularly the "Artist -
    Track" upload convention used off SoundCloud/Spotify) end up with this entire
    string dumped into the TITLE tag itself (e.g. a track literally tagged
    title="Tipper - Baleen"), with the real artist tag left wrong or generic (a
    channel/uploader name). Searching MusicBrainz with that whole redundant string
    as the title essentially never matches a real recording's title, so callers use
    this to recover just the actual title portion before searching — see
    musictagger/workers/query_worker.py.
    """
    cleaned = _strip_noise(text)
    match = _ARTIST_TITLE_RE.match(cleaned)
    if match and _looks_plausible(match.group(1)):
        return match.group(1).strip(), match.group(2).strip()
    return None, None


def strip_leading_track_number(text: Optional[str]) -> tuple[Optional[int], str]:
    """Splits a leading "## " track-position prefix off of raw text, e.g. the
    literal TITLE tag "1 Goldilocks Zone" -> (1, "Goldilocks Zone").

    Some rips (observed on Tipper's "Cloaked" album) bake the track position
    directly into the TITLE tag itself, not just the filename or a separate
    track-number field — split_artist_title_text can't help there since there's no
    "Artist - Title" dash to split on, so this is its own, narrower check. Returns
    (None, original text) when no such prefix is present, so callers can use the
    returned text unconditionally instead of branching on the match.
    """
    if not text:
        return None, text or ""
    match = _TRACK_PREFIX_RE.match(text)
    if not match:
        return None, text
    remainder = text[match.end():].strip()
    if not remainder:
        return None, text
    return int(match.group(1)), remainder


def resolve_track_number(track: "TrackMetadata") -> Optional[int]:
    """Returns the best-known track number: the tag value if present, otherwise
    whatever can be parsed from the filename (e.g. a "## " prefix), otherwise a
    leading "## " prefix baked directly into the tag's own TITLE text.

    Many real-world rips only ever encode track position via the filename and
    never write a proper tag at all — using track.track_number alone silently
    treats every such file as "position unknown", which breaks anything that needs
    real ordering (grouping's track-number confirming signal, and matching a
    resolved release's tracklist position back to the right file — sorting by a
    tag-only field that's None for an entire album falls back to alphabetical
    filename order, e.g. "1, 10, 11, 12, 13, 2, 3...", scrambling which title lands
    on which file).
    """
    if track.track_number is not None:
        return track.track_number
    filename_number = parse_filename(track.path).track_number
    if filename_number is not None:
        return filename_number
    number, _ = strip_leading_track_number(track.title)
    return number


def _loose_fallback(
    stem: str, track_number: Optional[int] = None, album: Optional[str] = None
) -> FilenameGuess:
    tokens = re.split(r"[^A-Za-z0-9']+", stem)
    kept = [t for t in tokens if t and not _looks_like_junk_token(t)]
    search_text = " ".join(kept).strip()
    return FilenameGuess(
        album=album,
        confidence="loose",
        search_text=search_text or None,
        # A track number already found before falling back here (e.g. a leading
        # "## " prefix) is still real, useful information — grouping relies on it
        # even when the rest of the title reads as junk — so it must carry through
        # rather than silently reverting to None.
        track_number=track_number,
    )


def parse_filename(path: Path, library_root: Optional[Path] = None) -> FilenameGuess:
    stem = _strip_noise(path.stem)
    parent = path.parent
    grandparent = parent.parent if parent != parent.parent else None

    track_number: Optional[int] = None
    track_match = _TRACK_PREFIX_RE.match(stem)
    remainder = stem
    if track_match:
        track_number = int(track_match.group(1))
        remainder = stem[track_match.end():].strip()

    # Pattern 2 is checked before Pattern 1: an explicit "Artist - Album" folder name
    # is a more specific, deliberate signal than merely having two ancestor folders,
    # which just as often means <library root>/<album>/ with no real artist level.
    # "Artist - Album" folder / "## Title.ext"
    folder_match = _ARTIST_TITLE_RE.match(_strip_noise(parent.name))
    if folder_match and remainder and _looks_plausible(folder_match.group(1)):
        return FilenameGuess(
            artist=folder_match.group(1).strip(),
            album=folder_match.group(2).strip(),
            title=remainder,
            track_number=track_number,
            confidence="structured",
        )

    # "Album Name/Act 1/track.mp3" etc: the immediate parent is a disc/act
    # subdivision, not a real "Album" level — so the grandparent is the actual
    # album, and (unlike Pattern 1) it is NOT a plausible artist name at all; skip
    # Pattern 1 entirely for this layout and carry the recovered album name through
    # whichever pattern below ends up supplying the artist/title instead.
    album_from_disc_subfolder: Optional[str] = None
    if grandparent is not None and _DISC_SUBFOLDER_RE.match(parent.name.strip()):
        candidate_album = _strip_noise(grandparent.name)
        if candidate_album and _looks_plausible(candidate_album):
            album_from_disc_subfolder = candidate_album

    # Pattern 1: Artist/Album/## - Title.ext
    if grandparent is not None and album_from_disc_subfolder is None:
        artist_candidate = _strip_noise(grandparent.name)
        album_candidate = _strip_noise(parent.name)
        if (
            artist_candidate
            and album_candidate
            and remainder
            and artist_candidate.lower() not in _GENERIC_ANCESTOR_NAMES
            and _looks_plausible(artist_candidate)
        ):
            return FilenameGuess(
                artist=artist_candidate,
                album=album_candidate,
                title=remainder,
                track_number=track_number,
                confidence="structured",
            )

    # Pattern 3: "Artist - Title.ext" (loose single files)
    file_match = _ARTIST_TITLE_RE.match(remainder)
    if file_match and _looks_plausible(file_match.group(1)):
        return FilenameGuess(
            artist=file_match.group(1).strip(),
            album=album_from_disc_subfolder,
            title=file_match.group(2).strip(),
            track_number=track_number,
            confidence="structured",
        )

    if remainder and _looks_plausible(remainder):
        return FilenameGuess(
            album=album_from_disc_subfolder,
            title=remainder,
            track_number=track_number,
            confidence="structured",
        )

    # Nothing structured and plausible matched — likely a gibberish/computer-generated
    # filename; fall back to junk-token filtering and a free-text search guess.
    return _loose_fallback(stem, track_number, album_from_disc_subfolder)
