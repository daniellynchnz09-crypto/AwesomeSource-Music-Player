from pathlib import Path

from musictagger.models import TrackMetadata
from musictagger.tags.filename_parser import (
    clean_noise_text,
    parse_filename,
    resolve_track_number,
    split_artist_title_text,
    strip_leading_track_number,
)


def test_structured_artist_album_title():
    path = Path("Music/Pink Floyd/The Wall/03 - Another Brick in the Wall.mp3")
    guess = parse_filename(path)
    assert guess.confidence == "structured"
    assert guess.artist == "Pink Floyd"
    assert guess.album == "The Wall"
    assert guess.title == "Another Brick in the Wall"
    assert guess.track_number == 3


def test_structured_artist_dash_album_folder():
    path = Path("Music/Daft Punk - Discovery/02 One More Time.mp3")
    guess = parse_filename(path)
    assert guess.confidence == "structured"
    assert guess.artist == "Daft Punk"
    assert guess.album == "Discovery"
    assert guess.title == "One More Time"


def test_structured_artist_title_loose_file():
    path = Path("Downloads/Coldplay - Yellow.mp3")
    guess = parse_filename(path)
    assert guess.confidence == "structured"
    assert guess.artist == "Coldplay"
    assert guess.title == "Yellow"


def test_strips_noise_tokens():
    path = Path("Downloads/Coldplay - Yellow [Explicit] (Remastered 2011).mp3")
    guess = parse_filename(path)
    assert guess.artist == "Coldplay"
    assert guess.title == "Yellow"


def test_gibberish_filename_falls_back_to_loose_search():
    path = Path("Downloads/xY7_2gK9-titlefragment_final(2).mp3")
    guess = parse_filename(path)
    assert guess.confidence == "loose"
    # Junk fragments (random IDs, "final", the "(2)" copy marker) must not appear,
    # but the one recognizable word should survive to seed a free-text search.
    assert guess.search_text is not None
    assert "titlefragment" in guess.search_text
    assert "xY7" not in guess.search_text
    assert "final" not in guess.search_text.lower()


def test_pure_random_id_filename_yields_no_search_text():
    path = Path("Downloads/8f3a91cd_004.mp3")
    guess = parse_filename(path)
    assert guess.confidence == "loose"
    assert not guess.search_text


def test_track_number_survives_loose_fallback():
    # Regression: a leading "## " track number was correctly detected, but if the
    # remainder text then read as junk (falling through to _loose_fallback), the
    # already-found track number was silently discarded instead of carried through
    # — even though it's still real, useful information (grouping depends on it).
    path = Path("Downloads/10 Track10.mp3")
    guess = parse_filename(path)
    assert guess.confidence == "loose"
    assert guess.track_number == 10


def test_split_artist_title_text_recovers_embedded_title():
    # Real-world case: a YouTube-sourced rip's TITLE tag literally contains the
    # whole "Artist - Track" video title (e.g. title="Tipper - Baleen"), which
    # never matches a real MusicBrainz recording title verbatim — callers need
    # just the track portion back out.
    artist, title = split_artist_title_text("Tipper - Baleen")
    assert artist == "Tipper"
    assert title == "Baleen"


def test_split_artist_title_text_strips_visualizer_suffix_first():
    # "Song | Uploader (4K music visualizer)" — the pipe-separated suffix is
    # channel/video branding, not song info, and must be gone before the split.
    artist, title = split_artist_title_text("Tipper - Air Biscuits | Insolito (4K music visualizer)")
    assert artist == "Tipper"
    assert title == "Air Biscuits"


def test_split_artist_title_text_rejects_implausible_split():
    artist, title = split_artist_title_text("just one plain title with no dash")
    assert artist is None
    assert title is None


def test_strip_leading_track_number_recovers_number_and_clean_title():
    # Real-world case: Tipper's "Cloaked" album has the track position baked
    # directly into the TITLE tag itself (not just the filename), e.g.
    # title="1 Goldilocks Zone" rather than a separate track_number field.
    number, title = strip_leading_track_number("1 Goldilocks Zone")
    assert number == 1
    assert title == "Goldilocks Zone"


def test_strip_leading_track_number_leaves_plain_titles_untouched():
    number, title = strip_leading_track_number("Goldilocks Zone")
    assert number is None
    assert title == "Goldilocks Zone"


def test_strip_leading_track_number_does_not_consume_a_purely_numeric_title():
    # A number with nothing left over after it (e.g. a title that's just "13")
    # isn't actually a track-number prefix on a real title — must not strip it
    # down to an empty string.
    number, title = strip_leading_track_number("13")
    assert number is None
    assert title == "13"


def test_disc_subfolder_is_not_mistaken_for_an_artist_folder():
    # Regression against the real library: "Jeff Wayne's War of the Worlds/Act 1/
    # Jeff Wayne - Horsell Common and the Heatray.mp3" was matching Pattern 1
    # (Artist/Album/Track) and treating the real ALBUM name as the artist and the
    # "Act 1" subdivision as the album — poisoning grouping and search with a
    # completely wrong artist guess for every track in the folder. "Act 1" (and
    # "Disc 2", "CD1", "Part 3", etc.) must be recognized as a disc/act
    # subdivision of one album, not a real Artist/Album two-level split.
    path = Path("Music/Jeff Wayne's War of the Worlds/Act 1/Jeff Wayne - Horsell Common and the Heatray.mp3")
    guess = parse_filename(path)
    assert guess.artist == "Jeff Wayne"
    assert guess.album == "Jeff Wayne's War of the Worlds"
    assert guess.title == "Horsell Common and the Heatray"


def test_disc_subfolder_pattern_matches_common_variants():
    for subfolder in ("Disc 2", "CD1", "Part 3", "Volume 1", "Vol. 4"):
        path = Path(f"My Album/{subfolder}/Some Artist - Some Title.mp3")
        guess = parse_filename(path)
        assert guess.album == "My Album", f"failed for subfolder {subfolder!r}"
        assert guess.artist == "Some Artist"


def test_clean_noise_text_strips_newgrounds_id_suffix():
    # Real-world case: Newgrounds Audio Portal rips consistently tack the
    # submission's numeric ID onto the TITLE tag itself, e.g. "Dr. Finkelfracken's
    # Cure (ID: 383158)" — this has no "Artist - Title" dash to split on at all, so
    # split_artist_title_text's internal noise-cleaning never surfaces; callers
    # need a way to clean text like this independent of the dash-split.
    assert clean_noise_text("Dr. Finkelfracken's Cure (ID: 383158)") == "Dr. Finkelfracken's Cure"


def test_clean_noise_text_strips_curly_brace_tags():
    assert clean_noise_text("{dj-N} Rain Full") == "Rain Full"


def test_clean_noise_text_strips_free_and_original_mix_tags():
    assert clean_noise_text("Song Title (free)") == "Song Title"
    assert clean_noise_text("Song Title (Original Mix)") == "Song Title"


def test_clean_noise_text_strips_wrapping_dashes():
    # "-Clownparty remix- (ID: 286138)" style Newgrounds titles wrap the whole
    # title in dashes rather than using them as a separator.
    assert clean_noise_text("-Clownparty remix- (ID: 286138)") == "Clownparty remix"


def test_resolve_track_number_falls_back_to_number_embedded_in_tag_title():
    # Regression: when neither the track_number tag nor the filename carries a
    # position (e.g. a generic filename like "track.mp3"), a leading track number
    # baked into the TITLE tag itself must still be recovered rather than treated
    # as position-unknown.
    track = TrackMetadata(path=Path("Music/Cloaked/track.mp3"), title="1 Goldilocks Zone")
    assert resolve_track_number(track) == 1
