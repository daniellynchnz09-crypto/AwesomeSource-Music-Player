from musictagger.metadata_sources import musicbrainz_client
from musictagger.metadata_sources.musicbrainz_client import _recording_to_candidate

# Shaped like the real MusicBrainz search_recordings response for a collab track
# that's linked to both a standalone single AND a various-artists compilation album
# — the exact real-world case that exposed this bug ("Underground" by Kompany &
# RetroVision ft. KARRA, also track 17 on "Never Say Die, Vol. 7").
_SINGLE_RELEASE = {
    "id": "single-release-id",
    "title": "Underground",
    "date": "2024-01-01",
    "release-group": {"type": "Single", "primary-type": "Single"},
}
_ALBUM_RELEASE = {
    "id": "album-release-id",
    "title": "Never Say Die, Vol. 7",
    "date": "2024-06-05",
    "release-group": {"type": "Album", "primary-type": "Album"},
}
_BROADCAST_RELEASE = {
    "id": "broadcast-release-id",
    "title": "Some DJ Set",
    "date": "2023-01-01",
    "release-group": {"type": "Broadcast", "primary-type": "Broadcast"},
}


def _recording(release_list):
    return {
        "title": "Underground",
        "artist-credit-phrase": "Kompany & RetroVision ft. KARRA",
        "release-list": release_list,
    }


def test_prefers_album_release_over_single_when_both_are_linked():
    # Regression: releases[0] picked whichever the API happened to list first,
    # which was very often the single — discarding that the track genuinely
    # belongs to a bigger compilation album too.
    candidate = _recording_to_candidate(_recording([_SINGLE_RELEASE, _ALBUM_RELEASE]))
    assert candidate.release_id == "album-release-id"


def test_prefers_album_release_regardless_of_list_order():
    candidate = _recording_to_candidate(_recording([_ALBUM_RELEASE, _SINGLE_RELEASE]))
    assert candidate.release_id == "album-release-id"


def test_prefers_album_over_broadcast_dj_mix():
    candidate = _recording_to_candidate(_recording([_BROADCAST_RELEASE, _ALBUM_RELEASE]))
    assert candidate.release_id == "album-release-id"


def test_falls_back_to_the_only_release_when_nothing_else_is_linked():
    # A track that really was only ever released as a standalone single must stay
    # a single — this only changes behavior when a better-typed alternative exists.
    candidate = _recording_to_candidate(_recording([_SINGLE_RELEASE]))
    assert candidate.release_id == "single-release-id"


def test_no_linked_releases_falls_back_to_recording_id():
    recording = {"id": "recording-id", "title": "X", "artist-credit-phrase": "Y", "release-list": []}
    candidate = _recording_to_candidate(recording)
    assert candidate.release_id == "recording-id"


def test_recording_search_requires_all_fields_before_falling_back_to_loose_matching(monkeypatch):
    # Regression: musicbrainzngs' loose default ORs the artist and title together, so
    # artist="SVDDEN DEATH" + title="Demonic Curse" returned only unrelated
    # "Death Curse" songs by other artists and never the real recording.
    calls = []

    def fake_search(**kwargs):
        calls.append(kwargs)
        return {"recording-list": [{"id": "r", "title": "Demonic Curse", "artist-credit-phrase": "SVDDEN DEATH"}]}

    monkeypatch.setattr(musicbrainz_client.musicbrainzngs, "search_recordings", fake_search)
    candidates = musicbrainz_client.search_recording_candidates("SVDDEN DEATH", "Demonic Curse")
    assert [c.title for c in candidates] == ["Demonic Curse"]
    assert len(calls) == 1 and calls[0]["strict"] is True


def test_search_falls_back_to_loose_matching_when_strict_finds_nothing(monkeypatch):
    calls = []

    def fake_search(**kwargs):
        calls.append(kwargs)
        if kwargs.get("strict"):
            return {"release-list": []}
        return {"release-list": [{"id": "x", "title": "Pyscho", "artist-credit-phrase": "SVDDEN DEATH"}]}

    monkeypatch.setattr(musicbrainz_client.musicbrainzngs, "search_releases", fake_search)
    candidates = musicbrainz_client.search_release_candidates("SVDDEN DEATH", "Psycho")
    assert [c.title for c in candidates] == ["Pyscho"]
    assert [bool(c.get("strict")) for c in calls] == [True, False]
