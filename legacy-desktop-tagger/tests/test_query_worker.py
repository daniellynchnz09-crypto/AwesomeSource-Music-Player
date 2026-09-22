from pathlib import Path

from musictagger.models import AlbumGroup, FileStatus, MBCandidate, TrackMetadata
from musictagger.persistence import db as db_module
from musictagger.workers.query_worker import QueryWorker, _candidate_to_dict


def test_candidate_to_dict_round_trip_preserves_album():
    # Regression: caching a candidate (see QueryWorker._store_cache /
    # _cached_candidates) serializes it through _candidate_to_dict and back via
    # MBCandidate(**d) — the .album field (added to disambiguate same-titled
    # recordings linked to different albums, see scorer.py's local_album_hint) was
    # missing from this dict entirely, so any candidate read back from cache
    # silently lost it, defeating the disambiguation the moment a query got cached
    # and re-read on a later scan.
    candidate = MBCandidate(
        release_id="a", title="Forever Autumn", artist_credit="Jeff Wayne",
        is_recording=True, album="Jeff Wayne's Musical Version of The War of the Worlds",
    )
    round_tripped = MBCandidate(**_candidate_to_dict(candidate))
    assert round_tripped.album == candidate.album


def test_reversed_title_artist_convention_keeps_trusted_tag_artist(tmp_path, monkeypatch):
    # Regression against the real Newgrounds Audio Portal library: these titles run
    # "Title - Artist (ID: NNNNN)" — the opposite of the YouTube "Artist - Title"
    # convention the embedded-artist override was designed for — while the ARTIST
    # tag is already correct. Blindly applying the existing split would overwrite a
    # known-good artist ("F-777") with the wrong half of the dash ("Ludicrous
    # Speed"). Detecting that the known-good artist shows up on the *title* side of
    # the split (rather than blindly trusting whichever side looks plausible) is
    # what tells the two conventions apart.
    track = TrackMetadata(
        path=Path("Music/Old Music/F-777 - Ludicrous Speed.mp3"),
        artist="F-777",
        title="Ludicrous Speed - F-777 (ID: 467267)",
    )
    group = AlbumGroup(group_key="test", files=[track], is_singleton=True)

    seen_queries = []

    def fake_search(artist, title, limit=5):
        seen_queries.append((artist, title))
        return [MBCandidate(release_id="a", title="Ludicrous Speed", artist_credit="F-777", is_recording=True)]

    monkeypatch.setattr(
        "musictagger.workers.query_worker.musicbrainz_client.search_recording_candidates", fake_search
    )
    monkeypatch.setattr(
        "musictagger.workers.query_worker.resolve_group_to_proposed",
        lambda group, chosen, fetch_cover_art=False: {track.path: track},
    )

    worker = QueryWorker(db_path=tmp_path / "test.db", contact="test", groups=[group])
    conn = db_module.get_connection(worker.db_path)
    worker._query_group(conn, group)
    conn.close()

    assert seen_queries == [("F-777", "Ludicrous Speed")]
    assert group.status == FileStatus.AUTO_MATCHED


def test_hyphenated_artist_name_repeated_in_title_is_not_split_apart(tmp_path, monkeypatch):
    # Regression against the real library: tag artist="F-777" (a real Newgrounds-
    # era handle with a hyphen baked into the name itself), title='F-777 "Dark
    # Dragon Fire"' — blindly dash-splitting the title mistook the hyphen *inside
    # the artist's own name* for an "Artist - Title" separator, producing
    # artist="F" (wrong!) and title='777 "Dark Dragon Fire"' (mangled). The known-
    # good tag artist appearing as a literal prefix of the title must be stripped
    # directly rather than run through the dash-split heuristic at all.
    track = TrackMetadata(
        path=Path("Music/Old Music/f-777 - Dark Dragon Fire.mp3"),
        artist="F-777",
        title='F-777 "Dark Dragon Fire"',
    )
    group = AlbumGroup(group_key="test", files=[track], is_singleton=True)

    seen_queries = []

    def fake_search(artist, title, limit=5):
        seen_queries.append((artist, title))
        return []

    monkeypatch.setattr(
        "musictagger.workers.query_worker.musicbrainz_client.search_recording_candidates", fake_search
    )

    worker = QueryWorker(db_path=tmp_path / "test.db", contact="test", groups=[group])
    conn = db_module.get_connection(worker.db_path)
    worker._query_group(conn, group)
    conn.close()

    assert seen_queries[0] == ("F-777", "Dark Dragon Fire")


def test_multi_artist_credit_starting_with_the_tag_artist_is_not_mistaken_for_a_repeated_prefix(
    tmp_path, monkeypatch
):
    # Regression: the hyphenated-artist-name prefix guard above (test just above
    # this one) was firing on multi-artist collab titles too, since the tag artist
    # ("PEEKABOO") is also a literal prefix of the full collab credit dumped into
    # the title ("PEEKABOO, Flava D, Scrufizzer - Pump It Up (Lyric Video)") —
    # stripping just that prefix left a broken, comma-leading title (", Flava D,
    # Scrufizzer - Pump It Up") instead of correctly recognizing this as the
    # existing "whole artist-credit line dumped into title, dash-separated from
    # the real title" pattern. A comma or "&" right after the artist name in the
    # title must route to the dash-split path instead of the literal-prefix path.
    track = TrackMetadata(
        path=Path("Music/EDM/PEEKABOO,Flava D,Scrufizzer - Pump It Up (Lyric Video).mp3"),
        artist="PEEKABOO",
        title="PEEKABOO, Flava D, Scrufizzer - Pump It Up (Lyric Video)",
    )
    group = AlbumGroup(group_key="test", files=[track], is_singleton=True)

    seen_queries = []

    def fake_search(artist, title, limit=5):
        seen_queries.append((artist, title))
        return []

    monkeypatch.setattr(
        "musictagger.workers.query_worker.musicbrainz_client.search_recording_candidates", fake_search
    )

    worker = QueryWorker(db_path=tmp_path / "test.db", contact="test", groups=[group])
    conn = db_module.get_connection(worker.db_path)
    worker._query_group(conn, group)
    conn.close()

    assert seen_queries[0] == ("PEEKABOO, Flava D, Scrufizzer", "Pump It Up")


def test_newgrounds_id_suffix_is_stripped_before_searching(tmp_path, monkeypatch):
    track = TrackMetadata(
        path=Path("Music/Old Music/Bossfight - Dr.Finkelfracken's Cure.mp3"),
        artist="Holyyeah",
        title="Dr. Finkelfracken's Cure (ID: 383158)",
    )
    group = AlbumGroup(group_key="test", files=[track], is_singleton=True)

    seen_queries = []

    def fake_search(artist, title, limit=5):
        seen_queries.append((artist, title))
        return []

    monkeypatch.setattr(
        "musictagger.workers.query_worker.musicbrainz_client.search_recording_candidates", fake_search
    )

    worker = QueryWorker(db_path=tmp_path / "test.db", contact="test", groups=[group])
    conn = db_module.get_connection(worker.db_path)
    worker._query_group(conn, group)
    conn.close()

    assert seen_queries[0] == ("Holyyeah", "Dr. Finkelfracken's Cure")
