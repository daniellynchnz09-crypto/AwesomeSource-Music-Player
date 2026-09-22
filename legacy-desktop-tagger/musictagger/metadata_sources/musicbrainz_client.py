"""Thin wrapper around musicbrainzngs: User-Agent setup, retry/backoff, and mapping
raw responses into the app's MBCandidate shape.

Rate limiting: musicbrainzngs enforces the required 1 request/second itself before
every call (see set_rate_limit, on by default), so as long as queries are issued
sequentially from one worker thread — which is how QueryWorker uses this module —
bulk scans never burst concurrent requests against MusicBrainz's shared infra.
"""

from __future__ import annotations

import time
from typing import Callable, Optional, TypeVar

import musicbrainzngs

from musictagger.models import MBCandidate

_T = TypeVar("_T")

_RETRY_DELAYS = (1, 2, 4)
_configured_contact: Optional[str] = None


class MusicBrainzError(Exception):
    """Raised after all retries are exhausted; distinct from "no results found"."""


def configure(contact: str) -> None:
    """Sets the User-Agent MusicBrainz's API etiquette requires. `contact` comes from
    the user-editable Settings field (see musictagger/config.py) — never a hardcoded
    identity — so the person running the app controls what's shown to this
    third-party API."""
    global _configured_contact
    musicbrainzngs.set_useragent(
        "MusicDetailsGenerator", "0.1", contact or "no contact info provided"
    )
    _configured_contact = contact


def is_configured() -> bool:
    return _configured_contact is not None


def _with_retry(func: Callable[..., _T], *args, **kwargs) -> _T:
    last_exc: Optional[Exception] = None
    for attempt, delay in enumerate((0,) + _RETRY_DELAYS):
        if delay:
            time.sleep(delay)
        try:
            return func(*args, **kwargs)
        except musicbrainzngs.WebServiceError as exc:
            last_exc = exc
            continue
    raise MusicBrainzError(str(last_exc))


def _release_to_candidate(release: dict) -> MBCandidate:
    artist_credit = release.get("artist-credit-phrase") or ""
    track_count = None
    medium_list = release.get("medium-list") or []
    if medium_list:
        track_count = sum(m.get("track-count", 0) for m in medium_list)
    return MBCandidate(
        release_id=release.get("id", ""),
        title=release.get("title", ""),
        artist_credit=artist_credit,
        first_release_date=release.get("date"),
        track_count=track_count,
    )


# A track very commonly appears on BOTH a standalone single release AND a
# various-artists compilation/album — MusicBrainz's API returns the linked
# releases in no meaningful order, so picking whichever came first (index 0)
# essentially picked at random between them, and in practice landed on the single
# far more often, discarding the fact the track genuinely belongs to a bigger
# album too. Each release carries its release-group's type, so the real album
# context can be recovered by preferring "Album"/"EP" releases over "Single" (or
# anything else, e.g. a DJ-mix broadcast) whenever more than one is linked.
_RELEASE_TYPE_RANK = {"Album": 0, "EP": 1, "Single": 2}
_DEFAULT_RELEASE_TYPE_RANK = 3


def _release_type_rank(release: dict) -> int:
    release_group = release.get("release-group") or {}
    primary_type = release_group.get("primary-type") or release_group.get("type")
    return _RELEASE_TYPE_RANK.get(primary_type, _DEFAULT_RELEASE_TYPE_RANK)


def _release_date_sort_key(release: dict) -> tuple:
    date = release.get("date") or ""
    # A blank/missing date sorts last among equally-ranked releases — an undated
    # release is more likely a stub/incomplete entry than the flagship original,
    # so it shouldn't win a tiebreak against a real dated one.
    return (date == "", date)


def _best_linked_release(releases: list[dict]) -> Optional[dict]:
    """Among a recording's linked releases, picks by type rank first (see
    _RELEASE_TYPE_RANK), then by earliest release date as a deterministic
    tiebreak — MusicBrainz's own API doesn't guarantee any particular order, and a
    single recording can legitimately be linked to dozens of same-type releases
    (e.g. every country/format pressing of one album spanning decades of
    reissues); picking whichever happened to come first in the API response was
    effectively random. Earliest date approximates "the original, flagship
    release" reasonably well without needing to actually understand reissue
    semantics."""
    if not releases:
        return None
    return min(releases, key=lambda r: (_release_type_rank(r), _release_date_sort_key(r)))


def _recording_to_candidate(recording: dict) -> MBCandidate:
    artist_credit = recording.get("artist-credit-phrase") or ""
    releases = recording.get("release-list") or []
    best_release = _best_linked_release(releases)
    release_id = best_release.get("id", "") if best_release else recording.get("id", "")
    date = best_release.get("date") if best_release else None
    album = best_release.get("title") if best_release else None
    return MBCandidate(
        release_id=release_id,
        title=recording.get("title", ""),
        artist_credit=artist_credit,
        first_release_date=date,
        is_recording=True,
        album=album,
    )


def _search_strict_first(search: Callable[..., dict], list_key: str, **fields) -> list[dict]:
    """Runs a MusicBrainz search with every field required (strict=True), and only
    if that finds nothing, again with musicbrainzngs' default loose matching.

    The loose default ORs the fields together, so a stylized artist name whose words
    are also common in song titles hijacks the results: searching artist="SVDDEN
    DEATH" for the track "Demonic Curse" returned only unrelated "Death Curse" songs
    by other artists (each matching "death" from the artist and "curse" from the
    title) and never the real recording, likewise "Prelude I" / "ACT I" turned up
    "Death I" tracks. Requiring the artist AND the title fixes that; the loose retry
    is kept for the cases where a strict query is too exact (a title that's spelled
    differently in MusicBrainz still gets a fuzzy shot at a suggestion).
    """
    strict_result = _with_retry(search, strict=True, **fields).get(list_key, [])
    if strict_result:
        return strict_result
    return _with_retry(search, **fields).get(list_key, [])


def search_release_candidates(
    artist: str, album: Optional[str], limit: int = 5
) -> list[MBCandidate]:
    """Album-level search — the primary path for a grouped AlbumGroup.

    `album` may be None for a group with no reliable album name at all (tracks
    clustered by matching artist + track number, but no album tag anywhere — see
    album_grouper._group_untagged); the search then falls back to artist-only,
    relying on the scorer's track-count-match signal to pick the right release out
    of the artist's whole discography.
    """
    fields = {"artist": artist}
    if album:
        fields["release"] = album
    result = _search_strict_first(musicbrainzngs.search_releases, "release-list", limit=limit, **fields)
    return [_release_to_candidate(r) for r in result]


def search_recording_candidates(artist: str, title: str, limit: int = 5) -> list[MBCandidate]:
    """Track-level search — used for singleton files with no album context."""
    result = _search_strict_first(
        musicbrainzngs.search_recordings, "recording-list", artist=artist, recording=title, limit=limit
    )
    return [_recording_to_candidate(r) for r in result]


def get_release_tracklist(release_id: str) -> dict:
    """Fetches the authoritative tracklist for a chosen release.

    Returns a dict: {"artist": str, "album": str, "year": Optional[int],
    "tracks": {position: {"title": str, "artist": Optional[str]}}}. Each track's own
    artist-credit is captured separately from the release-level one — a
    various-artists compilation's release-level artist ("Various Artists", or
    sometimes just the label name) is not the right artist for any individual
    track, only the per-recording artist-credit is. Defensive .get() access
    throughout (never direct indexing) so an unexpected schema surprise degrades to
    a blank field rather than crashing the batch.
    """
    result = _with_retry(
        musicbrainzngs.get_release_by_id,
        release_id,
        includes=["recordings", "artist-credits"],
    )
    release = result.get("release", {})
    tracks: dict[int, dict] = {}
    for medium in release.get("medium-list", []) or []:
        for track in medium.get("track-list", []) or []:
            try:
                position = int(track.get("position"))
            except (TypeError, ValueError):
                continue
            recording = track.get("recording", {}) or {}
            title = track.get("title") or recording.get("title")
            if title:
                tracks[position] = {
                    "title": title,
                    "artist": recording.get("artist-credit-phrase"),
                }

    year = None
    date = release.get("date")
    if date and len(date) >= 4 and date[:4].isdigit():
        year = int(date[:4])

    return {
        "artist": release.get("artist-credit-phrase"),
        "album": release.get("title"),
        "year": year,
        "tracks": tracks,
    }
