"""Resolves a chosen MusicBrainz candidate into per-file proposed TrackMetadata, by
fetching the authoritative tracklist (for a full release) and cover art. Shared by
both the auto-apply path (Phase 2 QueryWorker) and the review panel's "Accept this
candidate" action, so a match is only ever built the same way regardless of who
picked it.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import Optional

from rapidfuzz import fuzz

from musictagger.metadata_sources import cover_art_client, musicbrainz_client
from musictagger.models import AlbumGroup, MBCandidate, MetadataSource, TrackMetadata
from musictagger.tags.filename_parser import (
    parse_filename,
    resolve_track_number,
    split_artist_title_text,
    strip_leading_track_number,
)

_WHITESPACE_RE = re.compile(r"\s+")
_TITLE_MATCH_THRESHOLD = 65.0

# MusicBrainz's own titles for remixes are real but inconsistently styled — e.g.
# "He's a Pirate (F-777 ReMiX)" — and the same inconsistency shows up in raw tag
# text. This normalizes any "(<name> remix)"/"(<name> re-mix)" suffix into a
# single consistent "(<Name> Remix)" form. Deliberately scoped to the literal word
# "remix" only, not a bare "mix" — "(Original Mix)", "(Radio Mix)", "(Extended
# Mix)", "(Club Mix)" are legitimate version descriptors, not "remix credited to
# someone named Original/Radio/Extended/Club", and must never be rewritten into a
# fake "(X Remix)" credit.
_REMIX_TITLE_RE = re.compile(r"^(.*?)\s*\(([^)]*?)\s*re-?mix\)\s*$", re.I)


def _normalize(text: Optional[str]) -> str:
    return _WHITESPACE_RE.sub(" ", (text or "").strip().lower())


def _year_from_date(date: Optional[str]) -> Optional[int]:
    if date and len(date) >= 4 and date[:4].isdigit():
        return int(date[:4])
    return None


def format_remix_title(title: Optional[str]) -> Optional[str]:
    """Normalizes a "(<name> remix)"-style title suffix to a consistent "(<Name>
    Remix)" form, e.g. "He's a Pirate (F-777 ReMiX)" -> "He's a Pirate (F-777
    Remix)". Applied to whatever title actually ends up written (the real
    MusicBrainz title when matched, or the local guess otherwise), not to search
    queries — this is purely a final-display/write-time formatting pass, matching
    on the exact same field regardless of casing/spacing quirks in the source
    data. Returns the title unchanged when it isn't a recognized remix-credit
    suffix (including generic version descriptors like "(Original Mix)", which
    must never be mistaken for a remixer's name)."""
    if not title:
        return title
    match = _REMIX_TITLE_RE.match(title)
    if not match:
        return title
    song, remixer = match.group(1).strip(), match.group(2).strip()
    if not song or not remixer:
        return title
    return f"{song} ({remixer} Remix)"


def _local_title_guess(track: TrackMetadata) -> Optional[str]:
    """The best guess at this file's own song title, independent of the release —
    used to match it against the release's real tracklist by content, since track
    position (number) is often missing or unreliable on its own."""
    if track.title:
        _, embedded_title = split_artist_title_text(track.title)
        title = embedded_title or track.title
        # Some rips (e.g. Tipper's "Cloaked") bake the track position directly into
        # the TITLE tag itself, e.g. "1 Goldilocks Zone" — left in, that leading
        # digit measurably drags down the fuzzy match against the release's real,
        # clean tracklist title ("Goldilocks Zone"), which is exactly the content
        # match this function exists to get right.
        _, title = strip_leading_track_number(title)
        return title
    return parse_filename(track.path).title


def _best_matching_position(
    local_title: Optional[str], tracks_by_position: dict[int, dict], exclude: frozenset = frozenset()
) -> Optional[int]:
    """Finds which release position's title best matches a given local title,
    treating content (the actual song title) as stronger evidence of position than
    any number scraped from a tag or filename. Returns None if nothing clears
    _TITLE_MATCH_THRESHOLD, rather than guessing."""
    if not local_title or not tracks_by_position:
        return None
    normalized_local = _normalize(local_title)
    best_position, best_score = None, 0.0
    for position, info in tracks_by_position.items():
        if position in exclude:
            continue
        score = fuzz.WRatio(normalized_local, _normalize(info.get("title")))
        if score > best_score:
            best_score, best_position = score, position
    if best_position is not None and best_score >= _TITLE_MATCH_THRESHOLD:
        return best_position
    return None


def _match_files_to_positions(
    files: list[TrackMetadata], tracks_by_position: dict[int, dict]
) -> dict[Path, int]:
    """Matches each local file to the release position whose title it resembles
    most, rather than assuming position order.

    Many real-world rips carry no track number anywhere (no tag, no filename
    prefix) — a various-artists compilation where every file is "Artist -
    Title.mp3" is a common real case. Falling back to sorted-enumerate order in
    that situation effectively assigns positions at random, silently pairing the
    wrong title (and the wrong per-track artist) with the wrong file. An explicit
    tag track_number is trusted outright when present (real metadata beats a
    heuristic); otherwise the best-scoring, not-yet-claimed position by title
    similarity wins; a filename-derived track number is only the last resort,
    tried after title matching rather than before it, since a real title
    comparison is stronger evidence than a number scraped from a filename.
    """
    assigned: dict[Path, int] = {}
    used_positions: set[int] = set()
    remaining: list[TrackMetadata] = []

    # Pass 1: an explicit tag track_number is authoritative — claim it outright.
    for track in files:
        if track.track_number is not None and track.track_number in tracks_by_position:
            assigned[track.path] = track.track_number
            used_positions.add(track.track_number)
        else:
            remaining.append(track)

    # Pass 2: fuzzy-match everyone else by title.
    still_remaining: list[TrackMetadata] = []
    for track in remaining:
        position = _best_matching_position(_local_title_guess(track), tracks_by_position, frozenset(used_positions))
        if position is not None:
            assigned[track.path] = position
            used_positions.add(position)
        else:
            still_remaining.append(track)

    # Pass 3: last resort — a filename-derived track number, if any.
    for track in still_remaining:
        number = resolve_track_number(track)
        if number is not None and number in tracks_by_position and number not in used_positions:
            assigned[track.path] = number
            used_positions.add(number)

    return assigned


def resolve_group_to_proposed(
    group: AlbumGroup, chosen: MBCandidate, fetch_cover_art: bool = True
) -> dict[Path, TrackMetadata]:
    """Returns {file_path: proposed TrackMetadata} for every file in the group.

    fetch_cover_art=False skips the Cover Art Archive request entirely (cover
    fields fall back to whatever the file already has). Cover art isn't part of
    MusicBrainz's own rate-limited API, but it's still a real network round trip,
    and QueryWorker calls this once per auto-matched group *during* the bulk,
    already rate-limited lookup pass — that extra latency was landing squarely on
    the slowest part of the whole operation for no benefit, since the table has
    nowhere to show a cover art preview anyway. The caller is expected to fetch it
    JIT (via cover_art_client directly, keyed by the group's chosen_release_id)
    once the user actually accepts a match, not during the search/preview phase.
    """
    if group.is_singleton or chosen.is_recording:
        track = group.files[0]
        # A recording is linked to the release it appeared on — fetching that
        # release's info (not just the cover art) is what fills in the album field
        # for a singleton match at all; without this, even a correct, confident
        # match would leave "album" blank forever, since nothing else here ever
        # sets it.
        release_info = musicbrainz_client.get_release_tracklist(chosen.release_id) if chosen.release_id else {}
        cover = (
            cover_art_client.fetch_full_image(chosen.release_id)
            if fetch_cover_art and chosen.release_id
            else None
        )
        cover_bytes, cover_mime = cover if cover else (None, None)
        # The recording itself carries no track-number data (only a release's own
        # tracklist does) — now that a singleton match resolves to its real album
        # (see musicbrainz_client._best_linked_release), that album's tracklist is
        # sitting right here in release_info, so it's used the same way the
        # multi-file branch below does: match this track's own title against the
        # tracklist's titles to recover its real position, rather than only ever
        # trusting a tag/filename number that these individually-mistagged files
        # typically don't have at all.
        track_number = _best_matching_position(chosen.title or track.title, release_info.get("tracks", {}))
        if track_number is None:
            track_number = resolve_track_number(track)
        proposed = TrackMetadata(
            path=track.path,
            file_format=track.file_format,
            artist=chosen.artist_credit or track.artist,
            album_artist=release_info.get("artist") or track.album_artist,
            album=release_info.get("album") or track.album,
            title=format_remix_title(chosen.title or track.title),
            track_number=track_number,
            year=_year_from_date(chosen.first_release_date) or release_info.get("year") or track.year,
            genre=track.genre,
            cover_art_bytes=cover_bytes or track.cover_art_bytes,
            cover_art_mime=cover_mime or track.cover_art_mime,
            has_cover_art=bool(cover_bytes) or track.has_cover_art,
            source=MetadataSource.ONLINE_LOOKUP,
        )
        return {track.path: proposed}

    release = musicbrainz_client.get_release_tracklist(chosen.release_id)
    cover = cover_art_client.fetch_full_image(chosen.release_id) if fetch_cover_art else None
    cover_bytes, cover_mime = cover if cover else (None, None)
    tracks_by_position = release.get("tracks", {})
    release_artist = release.get("artist")

    position_by_path = _match_files_to_positions(group.files, tracks_by_position)

    proposed_map: dict[Path, TrackMetadata] = {}
    for track in group.files:
        position = position_by_path.get(track.path)
        info = tracks_by_position.get(position, {}) if position is not None else {}
        per_track_artist = info.get("artist")
        proposed_map[track.path] = TrackMetadata(
            path=track.path,
            file_format=track.file_format,
            # A various-artists compilation's per-track artist (e.g. "SPAG") is
            # only known once this file is matched to its real position — without
            # a confident match, keep the file's own existing artist tag rather
            # than overwriting it with the release-level credit (often "Various
            # Artists" or just the label name), which would be a worse guess than
            # what was already there.
            artist=per_track_artist or track.artist or release_artist,
            album_artist=release_artist or track.album_artist,
            album=release.get("album") or track.album,
            title=format_remix_title(info.get("title") or track.title),
            track_number=position if position is not None else track.track_number,
            year=release.get("year") or track.year,
            genre=track.genre,
            cover_art_bytes=cover_bytes or track.cover_art_bytes,
            cover_art_mime=cover_mime or track.cover_art_mime,
            has_cover_art=bool(cover_bytes) or track.has_cover_art,
            source=MetadataSource.ONLINE_LOOKUP,
        )
    return proposed_map
