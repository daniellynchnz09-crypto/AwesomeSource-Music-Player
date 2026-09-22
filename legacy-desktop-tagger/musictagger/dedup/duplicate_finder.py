"""Finds candidate duplicate songs for the user to review (see Plan/design.md
"Duplicate detection"). Nothing here ever deletes a file — it only reports groups;
deletion happens in the UI layer via send2trash after explicit per-group confirmation.

Two tiers:
1. Exact duplicates — byte-identical files, detected by size then hash. Works
   regardless of tag quality, so it can run in Phase 1 before any online lookup.
2. Metadata duplicates — same song in different formats/bitrates, matched on
   normalized (artist, title) with duration as a confirming signal. This tier is most
   accurate once tags have been resolved via MusicBrainz (Phase 2), so it's designed
   to be re-run after auto-fill rather than relying on a single scan-time pass.
"""

from __future__ import annotations

import hashlib
import re
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

from rapidfuzz import fuzz

from musictagger.models import TrackMetadata

_WHITESPACE_RE = re.compile(r"\s+")
_PUNCTUATION_RE = re.compile(r"[^\w\s]")

METADATA_SCORE_THRESHOLD = 90
DURATION_TOLERANCE_SECONDS = 5

# Classical/orchestral tagging very commonly gives every movement of a piece a
# title built from the same template (e.g. "Concerto In D Major, RV93, 3rd
# Movement" vs "Concerto In F Major, RV539, 1st Movement") — nearly all of the words
# are shared boilerplate, so fuzzy ratio alone scores these as near-identical even
# though they're different movements or entirely different pieces. Real duplicates
# (the same recording re-encoded) keep identical title text, including any catalog
# numbers and tempo markings — so a difference in either is treated as decisive
# proof these are NOT the same recording, regardless of the overall fuzzy score.
_TEMPO_MARKING_WORDS = {
    "allegro", "allegretto", "andante", "andantino", "adagio", "adagietto",
    "largo", "larghetto", "lento", "presto", "prestissimo", "vivace",
    "moderato", "grave", "cantabile", "scherzo", "minuetto", "minuet", "rondo",
}
_NUMBER_TOKEN_RE = re.compile(r"\d+")
_WORD_RE = re.compile(r"[a-z]+")


@dataclass
class DuplicateGroup:
    files: list[Path] = field(default_factory=list)
    reason: str = "exact"  # "exact" or "metadata"


def _normalize(text: str | None) -> str:
    text = _PUNCTUATION_RE.sub("", (text or "").lower())
    return _WHITESPACE_RE.sub(" ", text).strip()


_TRAILING_NUMBER_RE = re.compile(r"(\d+)\s*$")


def _differ_only_by_trailing_number(title_a: str, title_b: str) -> bool:
    """True when two titles are identical except for a trailing number, e.g. "atom
    bomb part 1" vs "atom bomb part 2" — a strong sign these are sequential
    tracks/movements, not the same song ripped twice, regardless of how high they
    fuzzy-match on shared words."""
    match_a = _TRAILING_NUMBER_RE.search(title_a)
    match_b = _TRAILING_NUMBER_RE.search(title_b)
    if not (match_a and match_b) or match_a.group(1) == match_b.group(1):
        return False
    return title_a[: match_a.start()].strip() == title_b[: match_b.start()].strip()


def _has_conflicting_numeric_tokens(title_a: str, title_b: str) -> bool:
    """True when both titles contain numbers but the sets differ — catches movement
    numbers, opus/catalog numbers (RV/BWV/K...) anywhere in the title, not just at
    the end, e.g. "RV93" vs "RV539" or "1st Movement" vs "3rd Movement". A genuine
    duplicate's title text (from the same recording, just re-encoded) would carry
    identical numbers; template-sharing but musically different titles usually
    don't."""
    tokens_a = set(_NUMBER_TOKEN_RE.findall(title_a))
    tokens_b = set(_NUMBER_TOKEN_RE.findall(title_b))
    if not tokens_a or not tokens_b:
        return False
    return tokens_a != tokens_b


def _has_conflicting_tempo_markings(title_a: str, title_b: str) -> bool:
    """True when both titles name a recognized tempo/movement marking (Allegro,
    Adagio, Cantabile, ...) but disagree on which one — catches movements
    distinguished only by tempo name rather than a number, e.g. "... - Cantabile"
    vs "... - Allegro" for the same piece."""
    words_a = set(_WORD_RE.findall(title_a)) & _TEMPO_MARKING_WORDS
    words_b = set(_WORD_RE.findall(title_b)) & _TEMPO_MARKING_WORDS
    if not words_a or not words_b:
        return False
    return words_a != words_b


def _hash_file(path: Path, chunk_size: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest()


def find_exact_duplicates(paths: list[Path]) -> list[DuplicateGroup]:
    by_size: dict[int, list[Path]] = defaultdict(list)
    for path in paths:
        try:
            size = path.stat().st_size
        except OSError:
            continue
        by_size[size].append(path)

    groups: list[DuplicateGroup] = []
    for candidates in by_size.values():
        if len(candidates) < 2:
            continue
        by_hash: dict[str, list[Path]] = defaultdict(list)
        for path in candidates:
            try:
                by_hash[_hash_file(path)].append(path)
            except OSError:
                continue
        for files in by_hash.values():
            if len(files) >= 2:
                groups.append(DuplicateGroup(files=files, reason="exact"))
    return groups


def find_metadata_duplicates(tracks: list[TrackMetadata]) -> list[DuplicateGroup]:
    candidates = [t for t in tracks if t.artist and t.title]
    used_paths: set[Path] = set()
    groups: list[DuplicateGroup] = []

    for i, track_a in enumerate(candidates):
        if track_a.path in used_paths:
            continue
        artist_a = _normalize(track_a.artist)
        title_a = _normalize(track_a.title)
        cluster = [track_a]
        for track_b in candidates[i + 1:]:
            if track_b.path in used_paths:
                continue
            artist_b = _normalize(track_b.artist)
            title_b = _normalize(track_b.title)
            # Artist and title are scored separately and must each independently
            # clear the threshold — concatenating them into one string let an
            # unusually long, identical artist credit (common in classical tagging,
            # e.g. a joint list of several performers) swamp a completely different
            # title's contribution to the ratio, scoring two different pieces by the
            # same ensemble as if they were near-identical.
            if fuzz.token_sort_ratio(artist_a, artist_b) < METADATA_SCORE_THRESHOLD:
                continue
            if fuzz.token_sort_ratio(title_a, title_b) < METADATA_SCORE_THRESHOLD:
                continue
            if _differ_only_by_trailing_number(title_a, title_b):
                continue
            if _has_conflicting_numeric_tokens(title_a, title_b):
                continue
            if _has_conflicting_tempo_markings(title_a, title_b):
                continue
            if (
                track_a.duration_seconds is not None
                and track_b.duration_seconds is not None
                and abs(track_a.duration_seconds - track_b.duration_seconds) > DURATION_TOLERANCE_SECONDS
            ):
                # Same title/artist but very different length -> likely a live/remix/
                # radio-edit version, not a true duplicate. Leave it for the user.
                continue
            cluster.append(track_b)
            used_paths.add(track_b.path)
        if len(cluster) >= 2:
            used_paths.add(track_a.path)
            groups.append(DuplicateGroup(files=[t.path for t in cluster], reason="metadata"))

    return groups
