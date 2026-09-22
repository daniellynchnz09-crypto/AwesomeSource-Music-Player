from pathlib import Path

from musictagger.grouping.album_grouper import group_into_albums
from musictagger.models import TrackMetadata


def _track(path: str, **kwargs) -> TrackMetadata:
    return TrackMetadata(path=Path(path), **kwargs)


def test_tagged_tracks_in_one_folder_form_one_group():
    tracks = [
        _track("Music/Daft Punk/Discovery/01 One More Time.mp3", artist="Daft Punk", album="Discovery"),
        _track("Music/Daft Punk/Discovery/02 Aerodynamic.mp3", artist="Daft Punk", album="Discovery"),
        _track("Music/Daft Punk/Discovery/03 Digital Love.mp3", artist="Daft Punk", album="Discovery"),
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 1
    assert len(groups[0].files) == 3
    assert not groups[0].is_singleton


def test_disagreeing_album_tags_split_into_subgroups():
    tracks = [
        _track("Music/Mixed/01.mp3", artist="Artist A", album="Album A"),
        _track("Music/Mixed/02.mp3", artist="Artist A", album="Album A"),
        _track("Music/Mixed/03.mp3", artist="Artist B", album="Album B"),
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 2
    sizes = sorted(len(g.files) for g in groups)
    assert sizes == [1, 2]  # Album A group has 2 tracks, Album B group has 1
    album_a_group = next(g for g in groups if g.best_guess_album == "Album A")
    assert len(album_a_group.files) == 2


def test_compilation_with_inconsistent_album_artist_tagging_still_forms_one_group():
    # Regression against the real library: a various-artists compilation
    # ("MEANWHILE...") had the album_artist tag set on most tracks ("Various
    # Artists") but missing on several — those fell back to their own differing
    # per-track artist as part of the old (album_artist_or_artist, album) grouping
    # key, splitting one real 17-track album into several fragments purely because
    # of a tagging gap, not because they're actually different releases.
    tracks = [
        _track("Music/EDM/01 Track1.mp3", artist="Freddy Todd & NOTE", album_artist="Various Artists", album="MEANWHILE..."),
        _track("Music/EDM/02 Track2.mp3", artist="ConRank", album_artist="Various Artists", album="MEANWHILE..."),
        _track("Music/EDM/04 Track4.mp3", artist="Liquid Stranger & Space Jesus", album="MEANWHILE..."),  # no album_artist
        _track("Music/EDM/06 Track6.mp3", artist="Shlump", album="MEANWHILE..."),  # no album_artist
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 1
    assert len(groups[0].files) == 4
    assert groups[0].best_guess_artist == "Various Artists"


def test_lone_mistagged_outlier_in_a_real_sequential_gap_reclaimed_into_the_compilation():
    # Regression against the real library: a 17-track "MEANWHILE..." compilation
    # (tracks 1-15, 17) with track 16 alone tagged album="Corrective Scene Surgery"
    # — a completely unrelated Mr. Bill release — despite being ripped as part of
    # the same batch, sitting at the exact one gap in the sequence. The file's own
    # tag isn't infallible; landing precisely in a real sequential gap of a large,
    # otherwise-consistent group is strong enough evidence to reclaim it.
    tracks = [
        _track(f"Music/EDM/{n:02d} Track{n}.mp3", artist="Various", album_artist="Various Artists", album="MEANWHILE...")
        for n in [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17]
    ]
    tracks.append(
        _track(
            "Music/EDM/16 Trill Clinton.mp3", artist="Mr. Bill & Tha Fruitbat",
            album_artist="Mr. Bill", album="Corrective Scene Surgery", track_number=16,
        )
    )
    groups = group_into_albums(tracks)
    meanwhile_group = next(g for g in groups if g.best_guess_album == "MEANWHILE...")
    assert len(meanwhile_group.files) == 17
    assert any(f.path.name == "16 Trill Clinton.mp3" for f in meanwhile_group.files)
    assert not any(g.best_guess_album == "Corrective Scene Surgery" for g in groups)


def test_outlier_group_with_multiple_files_is_not_reclaimed():
    # A same-folder "other album" group with more than one member is real evidence
    # of a genuinely different release, not a single mistagged file — must not be
    # swept into the dominant group just because its track numbers happen to fall
    # in range.
    tracks = [
        _track(f"Music/EDM/{n:02d} Track{n}.mp3", artist="Various", album_artist="Various Artists", album="MEANWHILE...")
        for n in [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17]
    ]
    tracks.append(_track("Music/EDM/16 Other A.mp3", artist="X", album="Some Other EP", track_number=16))
    tracks.append(_track("Music/EDM/18 Other B.mp3", artist="X", album="Some Other EP", track_number=18))
    groups = group_into_albums(tracks)
    other_group = next(g for g in groups if g.best_guess_album == "Some Other EP")
    assert len(other_group.files) == 2


def test_single_file_folder_is_singleton():
    tracks = [_track("Music/Loose/only_track.mp3", artist="Solo Artist", title="Solo Song")]
    groups = group_into_albums(tracks)
    assert len(groups) == 1
    assert groups[0].is_singleton


def test_untagged_but_consistent_filenames_group_together():
    tracks = [
        _track("Music/SomeRip/01 Muse - Uprising.mp3"),
        _track("Music/SomeRip/02 Muse - Starlight.mp3"),
        _track("Music/SomeRip/03 Muse - Supermassive Black Hole.mp3"),
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 1
    assert groups[0].best_guess_artist == "muse"
    assert len(groups[0].files) == 3


def test_untagged_same_artist_without_track_numbers_stays_ungrouped():
    # Without a track number, matching-artist loose files could just as easily be
    # several individually-downloaded singles spanning unrelated releases — this is
    # exactly the real-world Tipper case: 25 "Tipper - Song.mp3" one-off downloads
    # with no track number, sitting alongside a genuine 13-track album that DOES
    # have them. Same artist alone must not force-group them into a false "album".
    tracks = [
        _track("Music/SomeRip/Muse - Uprising.mp3"),
        _track("Music/SomeRip/Muse - Starlight.mp3"),
        _track("Music/SomeRip/Muse - Supermassive Black Hole.mp3"),
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 3
    assert all(g.is_singleton for g in groups)


def test_wildly_inconsistent_untagged_folder_splits_to_flagged_singletons():
    tracks = [
        _track("Music/Mess/Muse - Uprising.mp3"),
        _track("Music/Mess/Coldplay - Yellow.mp3"),
        _track("Music/Mess/Adele - Hello.mp3"),
    ]
    groups = group_into_albums(tracks)
    assert len(groups) == 3
    assert all(g.is_singleton and g.flagged_inconsistent for g in groups)


def test_same_artist_cluster_survives_a_busy_mixed_folder():
    # Regression against the real library: an "EDM" junk-drawer folder held loose
    # singles from a dozen+ different artists, PLUS 13 numbered tracks of one real
    # Tipper album (tagged artist="Tippermusic", no album tag), PLUS 25 individually
    # downloaded Tipper singles ("Tipper - Song.mp3", same artist tag, no track
    # number, spanning unrelated releases over time). The old approach required one
    # artist to cover most of the *whole folder* before trusting any grouping, so
    # the 13 real album tracks — which agreed perfectly with each other — still got
    # split into 13 individual singletons because dozens of unrelated artists also
    # shared that folder. And clustering by artist match alone (without also
    # requiring a track number) wrongly merged the 13 real album tracks together
    # with the 25 unrelated singles into one false 38-track "album". Both signals
    # together must find exactly the real 13-track album and nothing else.
    tipper_album_tracks = [
        _track(f"Music/EDM/{n} Track{n}.mp3", artist="Tippermusic")
        for n in range(1, 14)
    ]
    tipper_loose_singles = [
        _track(f"Music/EDM/Tipper - Single{n}.mp3", artist="Tippermusic")
        for n in range(1, 6)
    ]
    other_loose_singles = [
        _track("Music/EDM/Some Song.mp3", artist="Liquid Stranger"),
        _track("Music/EDM/Another Song.mp3", artist="Space Laces"),
        _track("Music/EDM/Yet Another.mp3", artist="Dubloadz"),
        _track("Music/EDM/no_tags_at_all.wav"),
    ]
    tracks = tipper_album_tracks + tipper_loose_singles + other_loose_singles
    groups = group_into_albums(tracks)

    tipper_group = next(g for g in groups if g.best_guess_artist == "Tippermusic")
    assert len(tipper_group.files) == 13
    assert not tipper_group.is_singleton
    assert all(f.path.name.startswith(tuple(f"{n} " for n in range(1, 14))) for f in tipper_group.files)

    # Everything else — the unrelated artists AND the unnumbered Tipper singles —
    # must end up as individual singletons, not swept into the album group.
    other_groups = [g for g in groups if g is not tipper_group]
    assert all(g.is_singleton for g in other_groups)
    assert len(other_groups) == 9
