"""Background MusicBrainz lookup for album groups. Runs sequentially in one worker
thread so queries stay serialized against the required 1 request/second rate limit —
musicbrainzngs enforces the delay itself, but issuing every call from a single
thread (rather than parallel workers) is what actually guarantees no burst happens.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Optional

from PySide6.QtCore import QObject, QRunnable, Signal, Slot

from musictagger.matching.scorer import decide, score_candidates
from musictagger.metadata_sources import musicbrainz_client
from musictagger.metadata_sources.musicbrainz_client import MusicBrainzError
from musictagger.metadata_sources.release_resolver import resolve_group_to_proposed
from musictagger.models import AlbumGroup, FileStatus, MBCandidate
from musictagger.persistence import db as db_module
from musictagger.tags.filename_parser import clean_noise_text, parse_filename, split_artist_title_text

# YouTube-sourced music very commonly tags the uploading channel's brand name as
# the artist — e.g. "Tippermusic" for the real artist "Tipper" — which won't match
# MusicBrainz's actual artist entry at all. Stripping a common channel-suffix is
# only ever tried as a fallback *after* the primary search finds nothing, so it can
# never override an already-successful match with a worse guess.
_CHANNEL_SUFFIXES = ("music", "official", "vevo", "records", "band")

# An artist-only release search (no album name available at all) can't rely on
# MusicBrainz's text relevance ranking the way an album-title search can — there's
# no title text to narrow it, so the correct release can rank arbitrarily far down
# an artist's whole discography (observed: a real, exact 13-track match ranked
# #50 out of 79 for one real-world artist). Fetching many more candidates gives the
# scorer's track-count signal an actual chance to find it, in the same single request.
_ARTIST_ONLY_SEARCH_LIMIT = 100
_DEFAULT_SEARCH_LIMIT = 5

# A bare, unattributed "(remix)"/"(mix)"/"(edit)" title suffix — as opposed to a
# named "(Artist Remix)", which genuinely identifies a different recording and
# must never be stripped — is frequently just how a personal rip/download happened
# to be labeled, not part of the real recording's MusicBrainz title. Confirmed for
# real: searching "Spawn (remix)" pushed the actual, correct "Spawn" recording
# entirely out of the top 15 MusicBrainz results (its own full-text relevance
# ranking penalized the extra word), while the bare title found it immediately at
# a perfect score. Only tried as a fallback retry, after the as-tagged title
# search hasn't already found a confident match, so a genuinely different,
# specifically-named remix recording is never second-guessed.
_BARE_VERSION_SUFFIX_RE = re.compile(r"\s*\((?:remix|mix|edit|rmx)\)\s*$", re.I)


class QueryWorkerSignals(QObject):
    group_processed = Signal(object)  # AlbumGroup, status/candidates/proposed populated
    progress = Signal(int, int)  # (done, total)
    finished = Signal()


def _normalize_key(*parts: Optional[str]) -> str:
    return "|".join((p or "").strip().lower() for p in parts)


def _normalize(text: Optional[str]) -> str:
    return (text or "").strip().lower()


def _strip_channel_suffix(artist: str) -> Optional[str]:
    lowered = artist.lower()
    for suffix in _CHANNEL_SUFFIXES:
        if lowered.endswith(suffix) and len(lowered) > len(suffix) + 2:
            return artist[: -len(suffix)].strip()
    return None


def _candidate_to_dict(c: MBCandidate) -> dict:
    return {
        "release_id": c.release_id,
        "title": c.title,
        "artist_credit": c.artist_credit,
        "first_release_date": c.first_release_date,
        "track_count": c.track_count,
        "is_recording": c.is_recording,
        "album": c.album,
    }


class QueryWorker(QRunnable):
    def __init__(self, db_path: Path, contact: str, groups: list[AlbumGroup]):
        super().__init__()
        self.db_path = db_path
        self.contact = contact
        self.groups = groups
        self.signals = QueryWorkerSignals()

    def _cached_candidates(self, conn, key: str) -> Optional[list[MBCandidate]]:
        cached = db_module.get_cached_query(conn, key)
        if cached is None:
            return None
        try:
            return [MBCandidate(**d) for d in json.loads(cached)]
        except (json.JSONDecodeError, TypeError, ValueError):
            return None

    def _store_cache(self, conn, key: str, candidates: list[MBCandidate]) -> None:
        db_module.set_cached_query(conn, key, json.dumps([_candidate_to_dict(c) for c in candidates]))

    def _query_group(self, conn, group: AlbumGroup) -> None:
        first = group.files[0]

        # A group with exactly one file — whether flagged is_singleton by the
        # grouper or not — is queried by track title rather than by album. A
        # release search on the local "album" tag fails surprisingly often here
        # because streaming services commonly write album tags like "Song - Single"
        # or "Song - EP" that don't match MusicBrainz's actual release title, while
        # a plain recording-title search finds the right track directly; there's
        # also no real tracklist to gain from resolving a "release" of one track.
        if group.is_singleton or len(group.files) == 1:
            artist = first.artist or group.best_guess_artist
            # The TITLE tag is very commonly just blank for these individually-
            # tagged/singleton files, even when the real title is sitting right
            # there in the filename (e.g. "Bend.mp3", tagged artist="Liquid
            # Stranger" but title=None) — falling back to the filename guess here
            # is what parse_filename already exists for; without it, a blank title
            # meant no search was even attempted (insufficient_info) despite the
            # title being trivially recoverable.
            title = first.title or parse_filename(first.path).title
            if not artist or not title:
                group.status = FileStatus.INSUFFICIENT_INFO
                group.status_detail = "no usable artist/title to search with"
                return

            # Strip known upload/rip noise (Newgrounds' "(ID: 12345)" submission-ID
            # suffix, {bracket}-style tags, etc.) up front, independent of whether an
            # "Artist - Title" split below also applies — a title that never had an
            # embedded artist to recover (e.g. "Dr. Finkelfracken's Cure (ID:
            # 383158)", already correctly tagged artist="Holyyeah") still needs this
            # cleanup before it's used to search, and split_artist_title_text alone
            # only surfaces its internal noise-cleaning when the dash-split succeeds.
            title = clean_noise_text(title)

            # YouTube-sourced rips very often dump the whole "Artist - Track" video
            # title into just the TITLE tag (e.g. title="Tipper - Baleen"), while
            # the ARTIST tag holds something else entirely — the uploading
            # channel/reposter's name (observed in the real library: the same real
            # artist's tracks tagged variously as "Tippermusic", "Sayther", or
            # "sprish" depending which upload each file came from). The artist
            # embedded in the title text is the one actually describing the song,
            # so when this pattern is detected it's preferred over the tag artist
            # outright, not just tried as a fallback after the tag artist fails —
            # a wrong-but-real-sounding tag artist can still return *some* (wrong)
            # candidates, so waiting for zero results before correcting it would
            # miss exactly this case.
            _rest_after_artist = title[len(artist):] if artist else ""
            _artist_is_whole_word_prefix = bool(_rest_after_artist) and (
                not _rest_after_artist[0].isalnum()
            )
            # A comma or "&" right after the artist name means more collaborator
            # names follow (e.g. tag artist="PEEKABOO", title="PEEKABOO, Flava D,
            # Scrufizzer - Pump It Up") — that's the multi-artist-credit-dumped-
            # into-title pattern below, not "the title repeats just this one
            # artist's name", so the literal-prefix handling must not claim it.
            _continues_with_more_artists = _rest_after_artist.lstrip()[:1] in (",", "&")
            if (
                artist
                and _artist_is_whole_word_prefix
                and not _continues_with_more_artists
                and _normalize(title).startswith(_normalize(artist))
            ):
                # The TITLE tag sometimes repeats the already-known artist name as
                # a literal prefix (e.g. tag artist="F-777", title='F-777 "Dark
                # Dragon Fire"'). When that artist name itself contains a hyphen —
                # common for Newgrounds-era handles like "F-777" or "Acid-Paradox"
                # — blindly dash-splitting the title mistook the hyphen *inside the
                # artist's own name* for the "Artist - Title" separator, splitting
                # a known-good artist apart from itself ("F" / "777 ..."). Stripping
                # the already-known artist off as a literal prefix sidesteps the
                # dash-based heuristic entirely for this case.
                remainder = title[len(artist):].strip(" -_:\"'")
                if remainder:
                    title = remainder
            else:
                embedded_artist, embedded_title = split_artist_title_text(title)
                if embedded_artist and embedded_title:
                    if artist and _normalize(artist) in _normalize(embedded_title):
                        # The classic Newgrounds Audio Portal convention runs the
                        # other way — "Title - Artist" rather than "Artist - Title"
                        # (e.g. "Ludicrous Speed - F-777 (ID: 467267)", tag artist
                        # already the correct "F-777") — so the known-good tag
                        # artist turning up on the *title* side of the split means
                        # the two sides are swapped relative to what's already
                        # known, not that the split found a better artist. Keep the
                        # trusted tag artist and recover the real title from the
                        # other side instead of overwriting a known-good artist
                        # with a wrong guess.
                        title = embedded_artist
                    else:
                        artist, title = embedded_artist, embedded_title

            key = _normalize_key("recording", artist, title)
            candidates = self._cached_candidates(conn, key)
            if candidates is None:
                candidates = musicbrainz_client.search_recording_candidates(artist, title)
                self._store_cache(conn, key, candidates)
            if not candidates:
                alt_artist = _strip_channel_suffix(artist)
                if alt_artist:
                    alt_key = _normalize_key("recording", alt_artist, title)
                    alt_candidates = self._cached_candidates(conn, alt_key)
                    if alt_candidates is None:
                        alt_candidates = musicbrainz_client.search_recording_candidates(alt_artist, title)
                        self._store_cache(conn, alt_key, alt_candidates)
                    if alt_candidates:
                        candidates, artist = alt_candidates, alt_artist

            # Prefer a folder-structure-derived album guess over the file's own
            # existing album tag as the disambiguation hint here specifically —
            # this branch exists to (re)search and potentially CORRECT a file's
            # metadata, so trusting an existing, possibly-wrong tag value would
            # just reinforce whatever it was already (mis)tagged as (observed for
            # real: a file tagged album="The Singles+" — actually an unrelated
            # Moody Blues compilation — sitting inside ".../Jeff Wayne's War of
            # the Worlds/Act 1/", where the folder is the reliable signal and the
            # tag is exactly the thing being corrected). group.best_guess_album
            # still covers the group_into_albums "tagged" path, which doesn't run
            # through album_grouper._singleton and so never gets a folder-derived
            # fallback of its own.
            album_hint = parse_filename(first.path).album or group.best_guess_album
            ranked = score_candidates(artist, title, None, first.year, candidates, album_hint)

            if _BARE_VERSION_SUFFIX_RE.search(title) and decide(ranked)[0] != "auto_apply":
                stripped_title = _BARE_VERSION_SUFFIX_RE.sub("", title).strip()
                if stripped_title:
                    stripped_key = _normalize_key("recording", artist, stripped_title)
                    stripped_candidates = self._cached_candidates(conn, stripped_key)
                    if stripped_candidates is None:
                        stripped_candidates = musicbrainz_client.search_recording_candidates(artist, stripped_title)
                        self._store_cache(conn, stripped_key, stripped_candidates)
                    stripped_ranked = score_candidates(
                        artist, stripped_title, None, first.year, stripped_candidates, album_hint
                    )
                    if decide(stripped_ranked)[0] == "auto_apply":
                        ranked, title = stripped_ranked, stripped_title
        else:
            artist = group.best_guess_artist or first.album_artist or first.artist
            album = group.best_guess_album or first.album
            if not artist:
                group.status = FileStatus.INSUFFICIENT_INFO
                group.status_detail = "no usable artist to search with"
                return
            # album may be None here — a group clustered by matching artist + track
            # number can have no album tag anywhere (see album_grouper's
            # _group_untagged). search_release_candidates falls back to an
            # artist-only search, and the scorer leans on track-count agreement
            # (len(group.files) below) to pick the right release out of the
            # artist's whole discography instead.
            search_limit = _ARTIST_ONLY_SEARCH_LIMIT if not album else _DEFAULT_SEARCH_LIMIT
            key = _normalize_key("release", artist, album or "")
            candidates = self._cached_candidates(conn, key)
            if candidates is None:
                candidates = musicbrainz_client.search_release_candidates(artist, album, limit=search_limit)
                self._store_cache(conn, key, candidates)
            if not candidates:
                alt_artist = _strip_channel_suffix(artist)
                if alt_artist:
                    alt_key = _normalize_key("release", alt_artist, album or "")
                    alt_candidates = self._cached_candidates(conn, alt_key)
                    if alt_candidates is None:
                        alt_candidates = musicbrainz_client.search_release_candidates(
                            alt_artist, album, limit=search_limit
                        )
                        self._store_cache(conn, alt_key, alt_candidates)
                    if alt_candidates:
                        candidates, artist = alt_candidates, alt_artist
            ranked = score_candidates(artist, album, len(group.files), first.year, candidates)

        group.candidates = ranked
        outcome, chosen = decide(ranked)

        if outcome == "auto_apply":
            try:
                # Cover art is skipped here — it's a real network round trip on
                # top of the already rate-limited MusicBrainz calls, for a field
                # the table has no preview for anyway. It's fetched JIT once the
                # user actually accepts the match (see main_window's
                # _on_accept_all_auto_matches), which keeps it off the slow,
                # sequential critical path during a bulk lookup.
                group.proposed = resolve_group_to_proposed(group, chosen, fetch_cover_art=False)
                group.status = FileStatus.AUTO_MATCHED
                group.chosen_release_id = chosen.release_id
            except MusicBrainzError as exc:
                # Matched with high confidence but couldn't fetch the full release
                # details to build a proposal — surface as needs_review so the user
                # can retry rather than silently losing the match.
                group.status = FileStatus.NEEDS_REVIEW
                group.status_detail = f"matched but details fetch failed: {exc}"
        elif outcome == "needs_review":
            group.status = FileStatus.NEEDS_REVIEW
        else:
            group.status = FileStatus.NO_MATCH

    @Slot()
    def run(self) -> None:
        if not musicbrainz_client.is_configured():
            musicbrainz_client.configure(self.contact)
        conn = db_module.get_connection(self.db_path)
        total = len(self.groups)
        for i, group in enumerate(self.groups, start=1):
            try:
                self._query_group(conn, group)
            except MusicBrainzError as exc:
                group.status = FileStatus.LOOKUP_FAILED
                group.status_detail = str(exc)
            self.signals.group_processed.emit(group)
            self.signals.progress.emit(i, total)
        conn.close()
        self.signals.finished.emit()
