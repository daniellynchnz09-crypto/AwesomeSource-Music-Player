from pathlib import Path

from musictagger.metadata_sources.release_resolver import format_remix_title, resolve_group_to_proposed
from musictagger.models import AlbumGroup, MBCandidate, TrackMetadata


def test_format_remix_title_normalizes_inconsistent_casing():
    # Real-world case: MusicBrainz's own canonical title for this recording is
    # literally "He's a Pirate (F-777 ReMiX)" — real data, just inconsistently
    # styled — normalized to a single consistent "(Name Remix)" form.
    assert format_remix_title("He's a Pirate (F-777 ReMiX)") == "He's a Pirate (F-777 Remix)"


def test_format_remix_title_handles_re_dash_mix_spelling():
    assert format_remix_title("Song (DJ Whoever Re-Mix)") == "Song (DJ Whoever Remix)"


def test_format_remix_title_leaves_generic_version_descriptors_alone():
    # "(Original Mix)", "(Radio Mix)", "(Extended Mix)" etc. are legitimate version
    # descriptors, not a remix credited to someone named Original/Radio/Extended —
    # only the literal word "remix" (not a bare "mix") triggers reformatting.
    assert format_remix_title("Song (Original Mix)") == "Song (Original Mix)"
    assert format_remix_title("Song (Radio Mix)") == "Song (Radio Mix)"
    assert format_remix_title("Song (Extended Mix)") == "Song (Extended Mix)"


def test_format_remix_title_leaves_unrelated_titles_alone():
    assert format_remix_title("Song (VIP)") == "Song (VIP)"
    assert format_remix_title("Plain Song Title") == "Plain Song Title"


def test_format_remix_title_handles_none():
    assert format_remix_title(None) is None

# The real Tipper "Cloaked" tracklist that exposed the original scrambling bug —
# files literally named "1 Goldilocks Zone.mp3" through "13 Launchers.mp3", with no
# track_number tag at all (position only ever existed in the filename).
_CLOAKED_TRACKLIST = {
    1: {"title": "Goldilocks Zone", "artist": "Tipper"},
    2: {"title": "Cloaked", "artist": "Tipper"},
    3: {"title": "Meadow Foam", "artist": "Tipper"},
    4: {"title": "Scaffolder", "artist": "Tipper"},
    5: {"title": "Kites Couldn't Even", "artist": "Tipper"},
    6: {"title": "Pono", "artist": "Tipper"},
    7: {"title": "Magpies", "artist": "Tipper"},
    8: {"title": "Wookstock", "artist": "Tipper"},
    9: {"title": "Dispacio", "artist": "Tipper"},
    10: {"title": "Little Peeks", "artist": "Tipper"},
    11: {"title": "Loosies", "artist": "Tipper"},
    12: {"title": "Preparations for Departure", "artist": "Tipper"},
    13: {"title": "Launchers", "artist": "Tipper"},
}

_TITLE_TO_FILENAME_STEM = {
    1: "1 Goldilocks Zone",
    2: "2 Cloaked",
    3: "3 Meadow Foam",
    4: "4 Scaffolder",
    5: "5 Kites Couldn't Even",
    6: "6 Pono",
    7: "7 Magpies",
    8: "8 Wookstock",
    9: "9 Dispacio",
    10: "10 Little Peeks",
    11: "11 Loosies",
    12: "12 Preparations For Departure",
    13: "13 Launchers",
}

# The real "Never Say Die, Vol. 7" compilation that exposed the position-guessing
# bug — every local file is "Artist - Title.mp3" with no track number anywhere (no
# tag, no filename prefix), and the release-level artist-credit is a generic label
# name, not any individual track's real performer.
_NSD7_TRACKLIST = {
    6: {"title": "CLIQUE", "artist": "IVORY & Hammerhead"},
    12: {"title": "BONK", "artist": "SPAG"},
    21: {"title": "Flute Song", "artist": "Jiqui"},
}


def _fake_cloaked_tracklist(_release_id: str) -> dict:
    return {"artist": "Tipper", "album": "Cloaked", "year": 2026, "tracks": _CLOAKED_TRACKLIST}


def _fake_nsd7_tracklist(_release_id: str) -> dict:
    return {
        "artist": "Never Say Die Records",
        "album": "Never Say Die, Vol. 7",
        "year": 2024,
        "tracks": _NSD7_TRACKLIST,
    }


def test_untagged_numbered_files_resolve_titles_by_numeric_not_alphabetical_order(monkeypatch):
    # Regression: every one of these files has track_number=None in its tag (only
    # the filename encodes position) — sorting/positioning by the raw tag field
    # alone made every file look like "position unknown" and fall back to
    # alphabetical filename order ("1, 10, 11, 12, 13, 2, 3, ..."), silently
    # mismatching every title from position 2 onward against the wrong file.
    files = [
        TrackMetadata(path=Path(f"Music/EDM/{stem}.mp3"), artist="Tippermusic")
        for stem in _TITLE_TO_FILENAME_STEM.values()
    ]
    group = AlbumGroup(group_key="test", files=files, best_guess_artist="Tipper")
    chosen = MBCandidate(release_id="abc123", title="Cloaked", artist_credit="Tipper", track_count=13)

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_cloaked_tracklist,
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen)

    for position, info in _CLOAKED_TRACKLIST.items():
        stem = _TITLE_TO_FILENAME_STEM[position]
        path = Path(f"Music/EDM/{stem}.mp3")
        assert proposed[path].title == info["title"], f"{stem} should resolve to {info['title']!r}"
        assert proposed[path].track_number == position


def test_tag_title_with_embedded_track_number_still_matches_by_content(monkeypatch):
    # Regression against the real Tipper "Cloaked" library: these files' TITLE tag
    # (not just the filename) literally reads "1 Goldilocks Zone", "2 Cloaked", etc.
    # — the leading digit must be stripped before fuzzy-matching against the
    # release's real, clean tracklist titles, or every title match falls below
    # threshold and the track number stays glued onto the front of the title.
    files = [
        TrackMetadata(path=Path(f"Music/Cloaked/track{position}.mp3"), artist="Tippermusic", title=stem)
        for position, stem in _TITLE_TO_FILENAME_STEM.items()
    ]
    group = AlbumGroup(group_key="test", files=files, best_guess_artist="Tipper")
    chosen = MBCandidate(release_id="abc123", title="Cloaked", artist_credit="Tipper", track_count=13)

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_cloaked_tracklist,
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen)

    for position, info in _CLOAKED_TRACKLIST.items():
        path = Path(f"Music/Cloaked/track{position}.mp3")
        assert proposed[path].title == info["title"], f"track{position} should resolve to {info['title']!r}"
        assert proposed[path].track_number == position


def test_compilation_with_no_track_numbers_matches_by_title_not_alphabetical_order(monkeypatch):
    # Regression against the real "Never Say Die, Vol. 7" compilation: files named
    # "Artist - Title.mp3" with NO track number anywhere (tag or filename) were
    # being assigned positions by alphabetical-filename order, which pairs each
    # file with a essentially random position's title/artist — e.g. "SPAG -
    # BONK.mp3" ending up tagged with the wrong song and the release's generic
    # label-name artist instead of "SPAG". Matching by title similarity must find
    # each file's real position regardless of file order.
    files = [
        TrackMetadata(path=Path("Music/NSD7/IVORY & Hammerhead - CLIQUE.mp3"), artist="IVORY & Hammerhead"),
        TrackMetadata(path=Path("Music/NSD7/SPAG - BONK.mp3"), artist="SPAG"),
        TrackMetadata(path=Path("Music/NSD7/Jiqui - Flute Song.mp3"), artist="Jiqui"),
    ]
    group = AlbumGroup(group_key="test", files=files, best_guess_artist="Various Artists")
    chosen = MBCandidate(
        release_id="nsd7", title="Never Say Die, Vol. 7", artist_credit="Never Say Die Records", track_count=25
    )

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_nsd7_tracklist,
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen)

    bonk = proposed[Path("Music/NSD7/SPAG - BONK.mp3")]
    assert bonk.title == "BONK"
    assert bonk.artist == "SPAG"  # the per-track artist, not the release's generic label credit
    assert bonk.track_number == 12
    assert bonk.album == "Never Say Die, Vol. 7"

    clique = proposed[Path("Music/NSD7/IVORY & Hammerhead - CLIQUE.mp3")]
    assert clique.title == "CLIQUE"
    assert clique.artist == "IVORY & Hammerhead"
    assert clique.track_number == 6

    flute = proposed[Path("Music/NSD7/Jiqui - Flute Song.mp3")]
    assert flute.title == "Flute Song"
    assert flute.artist == "Jiqui"
    assert flute.track_number == 21


def test_unmatched_file_keeps_its_own_artist_rather_than_the_release_label_credit(monkeypatch):
    # If a file's title doesn't confidently match anything in the fetched
    # tracklist, its own existing artist tag is kept rather than being overwritten
    # with the release-level credit (often a generic "Various Artists" or label
    # name) — that would be a worse guess than what was already there.
    files = [
        TrackMetadata(path=Path("Music/NSD7/Totally Unrelated Title.mp3"), artist="Some Real Artist"),
    ]
    group = AlbumGroup(group_key="test", files=files, best_guess_artist="Various Artists")
    chosen = MBCandidate(
        release_id="nsd7", title="Never Say Die, Vol. 7", artist_credit="Never Say Die Records", track_count=25
    )

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_nsd7_tracklist,
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen)
    result = proposed[Path("Music/NSD7/Totally Unrelated Title.mp3")]
    assert result.artist == "Some Real Artist"
    assert result.track_number is None


def test_singleton_match_recovers_track_number_from_the_matched_albums_tracklist(monkeypatch):
    # Regression: a singleton (individually-tagged) file has no track_number tag
    # of its own — but once it resolves to its real album (e.g. "BONK" by SPAG
    # correctly resolving to "Never Say Die, Vol. 7" rather than a standalone
    # single, see musicbrainz_client tests), that album's tracklist is already
    # being fetched for the album/year fields. Not using it to also recover the
    # track's real position left track_number blank even for a fully successful,
    # correctly-albummed match.
    files = [TrackMetadata(path=Path("Music/NSD7/SPAG - BONK.mp3"), artist="SPAG")]
    group = AlbumGroup(group_key="test", files=files, is_singleton=True)
    chosen = MBCandidate(release_id="nsd7", title="BONK", artist_credit="SPAG", is_recording=True)

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_nsd7_tracklist,
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen)
    result = proposed[Path("Music/NSD7/SPAG - BONK.mp3")]
    assert result.album == "Never Say Die, Vol. 7"
    assert result.track_number == 12


def test_fetch_cover_art_false_skips_the_network_call(monkeypatch):
    # The bulk "Accept All Auto-Matches" preview pass deliberately skips cover art
    # to keep it off the already rate-limited MusicBrainz critical path — it's
    # fetched later, just-in-time, only once the user actually accepts (see
    # WriteWorker._ensure_cover_art). This locks in that fetch_cover_art=False
    # really does skip the call rather than just ignoring its result.
    calls = []
    files = [TrackMetadata(path=Path("Music/NSD7/SPAG - BONK.mp3"), artist="SPAG")]
    group = AlbumGroup(group_key="test", files=files, is_singleton=True)
    chosen = MBCandidate(release_id="nsd7", title="BONK", artist_credit="SPAG", is_recording=True)

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        _fake_nsd7_tracklist,
    )

    def spy_fetch(release_id):
        calls.append(release_id)
        return (b"fake-cover-bytes", "image/jpeg")

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image", spy_fetch
    )

    proposed = resolve_group_to_proposed(group, chosen, fetch_cover_art=False)
    assert calls == []
    assert proposed[Path("Music/NSD7/SPAG - BONK.mp3")].cover_art_bytes is None


def test_singleton_match_applies_remix_title_formatting(monkeypatch):
    # End-to-end: a chosen recording candidate with MusicBrainz's own
    # inconsistently-styled remix title must come out through resolve_group_to_
    # proposed already normalized, since that's the title that actually gets
    # written to the file.
    files = [TrackMetadata(path=Path("Music/Old Music/He's a Pirate - F-777 Remix.mp3"), artist="F-777")]
    group = AlbumGroup(group_key="test", files=files, is_singleton=True)
    chosen = MBCandidate(
        release_id="pirate", title="He's a Pirate (F-777 ReMiX)", artist_credit="F-777", is_recording=True,
    )

    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.musicbrainz_client.get_release_tracklist",
        lambda _release_id: {},
    )
    monkeypatch.setattr(
        "musictagger.metadata_sources.release_resolver.cover_art_client.fetch_full_image",
        lambda _release_id: None,
    )

    proposed = resolve_group_to_proposed(group, chosen, fetch_cover_art=False)
    result = proposed[files[0].path]
    assert result.title == "He's a Pirate (F-777 Remix)"
    assert result.artist == "F-777"
