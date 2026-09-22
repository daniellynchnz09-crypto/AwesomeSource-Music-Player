"""Clusters a flat list of TrackMetadata into AlbumGroups before any online query.

Strategy (see Plan/design.md "Album grouping strategy"):
1. Primary key: parent folder path — most personal libraries are Artist/Album/tracks.
2. Confirming signal: within a folder, tracks are split by (album_artist, album) tag
   pairs rather than trusting the folder blindly, in case a folder holds mixed content.
3. When album tags are absent, cluster by matching artist guess *and* a resolvable
   track number (tag or filename-derived) — both signals together, since a folder
   can hold either several different artists' loose singles, or many unrelated
   singles by the *same* artist collected over time, and only a shared track
   number reliably tells "one ripped album" apart from either. Files that don't
   clear both bars become individually-flagged singleton groups.
4. Singletons (loose files, single-track folders) are queried at the track level,
   not the album level, since there is no album context to leverage.
"""

from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

from musictagger.models import AlbumGroup, TrackMetadata
from musictagger.tags.filename_parser import parse_filename, resolve_track_number

_WHITESPACE_RE = re.compile(r"\s+")

# An artist cluster within a mixed, untagged folder needs at least this many members
# to be treated as "probably one release" rather than a single loose file that just
# happens to share the folder with unrelated tracks.
_MIN_CLUSTER_SIZE = 2


def _normalize(text: str | None) -> str:
    return _WHITESPACE_RE.sub(" ", (text or "").strip().lower())


def _artist_guess(track: TrackMetadata) -> str:
    if track.artist:
        return _normalize(track.artist)
    guess = parse_filename(track.path)
    return _normalize(guess.artist)


def _has_track_number(track: TrackMetadata) -> bool:
    return resolve_track_number(track) is not None


def _singleton(track: TrackMetadata, flagged: bool = False) -> AlbumGroup:
    return AlbumGroup(
        group_key=f"singleton::{track.path}",
        files=[track],
        best_guess_artist=track.artist or parse_filename(track.path).artist,
        # Falls back to a folder-structure-derived guess (e.g. recovered from a
        # "Album Name/Act 1/track.mp3" layout) when the tag itself is blank.
        # (query_worker's singleton branch additionally prefers the folder guess
        # OVER a present-but-possibly-wrong tag value when using this as a
        # disambiguation hint during matching — see the comment there — since this
        # field is also read elsewhere as a display/fallback value where the tag,
        # when present, is normally the more trustworthy default.)
        best_guess_album=track.album or parse_filename(track.path).album,
        is_singleton=True,
        flagged_inconsistent=flagged,
    )


def _group_untagged(folder: Path, untagged: list[TrackMetadata]) -> list[AlbumGroup]:
    """Clusters untagged files within one folder by matching artist AND a track
    number (tag or filename-derived), one confirming signal each:

    A folder can easily hold loose tracks from many different artists at once (a
    general downloads/genre "junk drawer" folder, not one album's home), so matching
    artist alone isn't enough — a folder can just as easily hold many unrelated
    individually-downloaded songs by the *same* artist (an artist's assorted singles
    collected one-by-one over time), which is not "one album" either. What actually
    tells the two apart is track numbering: files ripped from one real album
    typically carry a track position (in a tag or as a "## " filename prefix)
    because that's how ripping software assigns them, while individually-downloaded
    standalone singles typically don't, since there's no album context to derive a
    position from. So only same-artist files that *also* have a track number are
    trusted as one probable album; matching-artist files without one are left as
    individual singletons rather than force-grouped into a false "album" that could
    actually span years of unrelated releases.

    Note there's no reliable album name here (these tracks have no album tag by
    definition), so best_guess_album is left unset; the query layer (Phase 2) falls
    back to an artist-only search for these groups, using track-count agreement to
    help pick the right release.
    """
    by_artist: dict[str, list[TrackMetadata]] = defaultdict(list)
    ungrouped: list[TrackMetadata] = []

    for track in untagged:
        guess = _artist_guess(track)
        if guess and _has_track_number(track):
            by_artist[guess].append(track)
        else:
            ungrouped.append(track)

    groups: list[AlbumGroup] = []
    for artist_key, members in by_artist.items():
        if len(members) >= _MIN_CLUSTER_SIZE:
            groups.append(
                AlbumGroup(
                    group_key=f"{folder}::{artist_key}",
                    files=members,
                    best_guess_artist=members[0].artist or artist_key,
                )
            )
        else:
            groups.extend(_singleton(t, flagged=True) for t in members)

    groups.extend(_singleton(t, flagged=True) for t in ungrouped)
    return groups


# A dominant tagged group within a folder needs at least this many members,
# sharing resolvable track numbers, before a lone same-folder outlier is trusted
# enough to reclaim into it — a small group isn't strong enough evidence that its
# own tag is more trustworthy than the outlier's.
_MIN_DOMINANT_GROUP_SIZE = 3


def _reclaim_sequential_outliers(
    tag_groups: dict[str, list[TrackMetadata]],
) -> dict[str, list[TrackMetadata]]:
    """A single mistagged file can sit in the same folder as a large, otherwise
    sequentially-numbered compilation and still get split into its own group,
    because it happens to carry a completely different (wrong) album tag —
    observed for real: a various-artists compilation ripped as tracks 1-17, where
    track 16 alone was tagged with an unrelated release's album name, yet it's
    still positioned exactly at the one gap in an otherwise unbroken 1-15,17
    sequence. A file's own existing tag isn't infallible — that's exactly what
    matching against MusicBrainz exists to correct — and a lone outlier landing
    precisely in a real sequential gap is a strong, narrow signal that it actually
    belongs with its neighbors, not evidence the file is genuinely a different
    release that happens to share a folder.

    Checks the outlier against every sufficiently-large group in the folder, not
    just the single largest one — a busy library folder often holds several
    substantial, genuinely unrelated albums at once (real example: this same "EDM"
    folder holds both a 16-track compilation AND an unrelated 25-track compilation
    side by side), so "the biggest group in the folder" is not reliably "the one
    this outlier actually belongs to".
    """
    candidates = [(key, members) for key, members in tag_groups.items() if len(members) >= _MIN_DOMINANT_GROUP_SIZE]
    if not candidates:
        return tag_groups

    reclaimed: dict[str, list[TrackMetadata]] = {key: list(members) for key, members in tag_groups.items()}
    for outlier_key, outlier_members in tag_groups.items():
        if len(outlier_members) != 1:
            continue
        track = outlier_members[0]
        # The filename's own position — not resolve_track_number()'s tag-first
        # result — is what's checked here: a mistagged file's own track_number
        # TAG is exactly the kind of value that can't be trusted (real example:
        # tagged track_number=7, matching the WRONG album it was mistagged with,
        # while the filename "16 Trill Clinton.mp3" still correctly encodes its
        # real position in the original rip batch).
        number = parse_filename(track.path).track_number
        if number is None:
            continue
        for dominant_key, dominant_members in candidates:
            if dominant_key == outlier_key:
                continue
            dominant_numbers = {n for n in (resolve_track_number(t) for t in dominant_members) if n is not None}
            if not dominant_numbers:
                continue
            lo, hi = min(dominant_numbers), max(dominant_numbers)
            if lo <= number <= hi and number not in dominant_numbers:
                reclaimed[dominant_key].append(track)
                reclaimed[outlier_key].remove(track)
                break

    return {key: members for key, members in reclaimed.items() if members}


def group_into_albums(tracks: list[TrackMetadata]) -> list[AlbumGroup]:
    by_folder: dict[Path, list[TrackMetadata]] = defaultdict(list)
    for track in tracks:
        by_folder[track.path.parent].append(track)

    groups: list[AlbumGroup] = []
    for folder, folder_tracks in by_folder.items():
        if len(folder_tracks) == 1:
            groups.append(_singleton(folder_tracks[0]))
            continue

        tagged = [t for t in folder_tracks if t.album]
        untagged = [t for t in folder_tracks if not t.album]

        # Grouped by album name alone, not also by (album_artist or artist) — a
        # various-artists compilation very commonly has the album_artist tag set
        # on only SOME of its tracks (real example: a 17-track "MEANWHILE..."
        # compilation where several tracks were missing it entirely), and falling
        # back to that track's own differing per-track artist as part of the key
        # split what's genuinely one album into several fragments purely because
        # of a tagging gap, not because they're actually different releases. It's
        # very rare for a folder to genuinely hold two different albums that
        # happen to share the exact same album title string, so the album name by
        # itself is a reliable enough identity signal without also requiring
        # album_artist to agree.
        tag_groups: dict[str, list[TrackMetadata]] = defaultdict(list)
        for track in tagged:
            tag_groups[_normalize(track.album)].append(track)

        tag_groups = _reclaim_sequential_outliers(tag_groups)

        for members in tag_groups.values():
            # Prefers a member that actually carries an explicit album_artist tag
            # (e.g. "Various Artists") over an arbitrary first file, since for a
            # compilation with inconsistent per-track tagging, picking a member
            # that merely lacks the tag and falling back to *its own* individual
            # artist would hand the whole group a misleading single-artist guess.
            representative = next((t for t in members if t.album_artist), members[0])
            groups.append(
                AlbumGroup(
                    group_key=f"{folder}::{_normalize(representative.album)}",
                    files=members,
                    best_guess_artist=representative.album_artist or representative.artist,
                    best_guess_album=representative.album,
                )
            )

        if untagged:
            groups.extend(_group_untagged(folder, untagged))

    return groups
