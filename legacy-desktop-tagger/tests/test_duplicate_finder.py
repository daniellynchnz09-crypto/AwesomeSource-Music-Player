from pathlib import Path

from musictagger.dedup.duplicate_finder import find_exact_duplicates, find_metadata_duplicates
from musictagger.models import TrackMetadata


def test_exact_duplicates_detected_by_content(tmp_path: Path):
    content = b"identical audio bytes" * 100
    file_a = tmp_path / "song_copy1.mp3"
    file_b = tmp_path / "subfolder" / "song_copy2.mp3"
    file_b.parent.mkdir()
    file_a.write_bytes(content)
    file_b.write_bytes(content)
    different = tmp_path / "different.mp3"
    different.write_bytes(b"not the same content at all" * 100)

    groups = find_exact_duplicates([file_a, file_b, different])
    assert len(groups) == 1
    assert set(groups[0].files) == {file_a, file_b}
    assert groups[0].reason == "exact"


def test_same_size_different_content_is_not_a_duplicate(tmp_path: Path):
    file_a = tmp_path / "a.mp3"
    file_b = tmp_path / "b.mp3"
    file_a.write_bytes(b"aaaaaaaaaa")
    file_b.write_bytes(b"bbbbbbbbbb")
    groups = find_exact_duplicates([file_a, file_b])
    assert groups == []


def test_metadata_duplicates_matched_across_formats():
    tracks = [
        TrackMetadata(path=Path("a.mp3"), artist="Muse", title="Uprising", duration_seconds=134.0),
        TrackMetadata(path=Path("b.flac"), artist="Muse", title="Uprising", duration_seconds=135.0),
        TrackMetadata(path=Path("c.mp3"), artist="Muse", title="Starlight", duration_seconds=241.0),
    ]
    groups = find_metadata_duplicates(tracks)
    assert len(groups) == 1
    assert set(groups[0].files) == {Path("a.mp3"), Path("b.flac")}
    assert groups[0].reason == "metadata"


def test_same_title_but_very_different_duration_is_not_flagged():
    tracks = [
        TrackMetadata(path=Path("studio.mp3"), artist="Muse", title="Uprising", duration_seconds=134.0),
        TrackMetadata(path=Path("live.mp3"), artist="Muse", title="Uprising", duration_seconds=310.0),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_sequential_parts_are_not_flagged_as_duplicates():
    # Regression: fuzzy title matching alone scores "Atom Bomb Part 1" vs "Part 2"
    # above the duplicate threshold since only one character differs.
    tracks = [
        TrackMetadata(path=Path("part1.m4a"), artist="Excision", title="Atom Bomb Part 1", duration_seconds=180.0),
        TrackMetadata(path=Path("part2.m4a"), artist="Excision", title="Atom Bomb Part 2", duration_seconds=182.0),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_classical_movements_with_different_catalog_numbers_not_flagged():
    # Regression against the real library: classical titles built from a shared
    # template ("Concerto In X Major, RV##, Nth Movement") fuzzy-match as near-
    # identical even when the catalog number and movement both differ — these are
    # different pieces entirely, not a duplicate rip.
    tracks = [
        TrackMetadata(
            path=Path("a.m4a"), artist="Budapest Strings",
            title='Vivaldi: Violin Concerto In E, Op. 8/1, RV 269, "The Four Seasons (Spring)" - 1. Allegro',
            duration_seconds=217.1,
        ),
        TrackMetadata(
            path=Path("b.m4a"), artist="Budapest Strings",
            title='Vivaldi: Violin Concerto In F, Op. 8/3, RV 293, "The Four Seasons (Autumn)" - 3. Allegro',
            duration_seconds=220.4,
        ),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_classical_movements_distinguished_only_by_tempo_word_not_flagged():
    # Regression: same piece (RV450), same catalog number in both titles, but
    # different movements named only by tempo marking ("Larghetto" vs "Allegro") —
    # no numeric difference exists to catch this, so the tempo word itself must be
    # compared.
    tracks = [
        TrackMetadata(
            path=Path("a.m4a"), artist="Failoni Chamber Orchestra",
            title="Vivaldi: Oboe Concerto In C, RV 450 - Larghetto", duration_seconds=173.5,
        ),
        TrackMetadata(
            path=Path("b.m4a"), artist="Failoni Chamber Orchestra",
            title="Vivaldi: Oboe Concerto In C, RV 450 - Allegro", duration_seconds=175.2,
        ),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_zero_duration_is_not_treated_as_missing_data():
    # Regression: `if track.duration_seconds` treats a genuine 0.0 the same as
    # "no data" (both are falsy), which let two unrelated tracks slip past the
    # duration check entirely just because one had a duration of exactly zero.
    tracks = [
        TrackMetadata(path=Path("a.m4a"), artist="Same Artist", title="Morning Forest", duration_seconds=11.9),
        TrackMetadata(path=Path("b.m4a"), artist="Same Artist", title="Rain Forest", duration_seconds=0.0),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_long_identical_artist_credit_does_not_mask_a_different_title():
    # Regression against the real library: a long, identical joint-performer artist
    # credit ("Aaron Heick and Anja Wood and ... MacDonnell") dominated the combined
    # artist+title fuzzy ratio, so two completely different pieces by the same
    # ensemble ("Hungarian Dance No. 5" vs "Farandole" — no shared words, no
    # conflicting numbers to catch it) scored above the duplicate threshold. Artist
    # and title must be scored independently so a mismatched title alone rejects it.
    performers = "Aaron Heick and Anja Wood and Antoine Silverman and Chris Cardona"
    tracks = [
        TrackMetadata(path=Path("a.m4a"), artist=performers, title="Hungarian Dance No. 5", duration_seconds=166.0),
        TrackMetadata(path=Path("b.m4a"), artist=performers, title="Farandole", duration_seconds=161.2),
    ]
    groups = find_metadata_duplicates(tracks)
    assert groups == []


def test_near_identical_titles_with_no_conflicting_signals_still_flagged():
    # Genuine near-duplicate case from the real library: same short clip, tagged
    # slightly differently ("Tune Up" vs "Tune-Up", artist with/without "The"),
    # nearly identical duration, no conflicting numbers or tempo words — this
    # should still be caught.
    tracks = [
        TrackMetadata(
            path=Path("a.m4a"), artist="Baby Einstein Music Box Orchestra",
            title="Orchestra Tune Up", duration_seconds=22.0,
        ),
        TrackMetadata(
            path=Path("b.m4a"), artist="The Baby Einstein Music Box Orchestra",
            title="Orchestra Tune-Up", duration_seconds=23.4,
        ),
    ]
    groups = find_metadata_duplicates(tracks)
    assert len(groups) == 1
    assert set(groups[0].files) == {Path("a.m4a"), Path("b.m4a")}
