from musictagger.matching.scorer import decide, score_candidate, score_candidates
from musictagger.models import MBCandidate


def test_case_mismatch_does_not_tank_the_score():
    # Regression: rapidfuzz's WRatio is case-SENSITIVE ("ECRAZE" vs "Ecraze" scores
    # 0.0), so an all-caps-stylized local tag (extremely common for EDM producer
    # names, e.g. "ECRAZE", "PEEKABOO") scored against MusicBrainz's normally-cased
    # data was silently gutting the artist/title score components before this was
    # normalized away, on top of whatever else was wrong with the query.
    candidate = MBCandidate(release_id="a", title="Kill Off", artist_credit="Ecraze")
    score = score_candidate("ECRAZE", "KILL OFF", None, None, candidate)
    assert score > 95.0


def test_clear_winner_auto_applies():
    candidates = [
        MBCandidate(release_id="good", title="Discovery", artist_credit="Daft Punk", track_count=14),
        MBCandidate(release_id="bad", title="Some Other Album", artist_credit="Someone Else", track_count=8),
    ]
    ranked = score_candidates("Daft Punk", "Discovery", 14, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "good"


def test_multiple_editions_of_the_same_album_still_auto_apply():
    # MusicBrainz commonly returns several releases for one real album (different
    # countries/remasters/digital vs CD) that score identically — that tie must not
    # block auto-apply, since any of them yields the same artist/album/tracklist.
    candidates = [
        MBCandidate(release_id="us-cd", title="Discovery", artist_credit="Daft Punk",
                    first_release_date="2001-03-12", track_count=14),
        MBCandidate(release_id="eu-cd", title="Discovery", artist_credit="Daft Punk",
                    first_release_date="2001-02-26", track_count=14),
        MBCandidate(release_id="digital", title="Discovery", artist_credit="Daft Punk",
                    first_release_date="2014", track_count=14),
    ]
    ranked = score_candidates("Daft Punk", "Discovery", 14, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen is not None


def test_near_tied_candidates_go_to_review_not_auto_apply():
    candidates = [
        MBCandidate(release_id="a", title="Greatest Hits", artist_credit="Queen", track_count=17),
        MBCandidate(release_id="b", title="Greatest Hits II", artist_credit="Queen", track_count=17),
    ]
    ranked = score_candidates("Queen", "Greatest Hits", 17, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome in ("needs_review", "no_match")
    assert chosen is None


def test_weak_matches_are_no_match():
    candidates = [
        MBCandidate(release_id="x", title="Completely Unrelated", artist_credit="Nobody", track_count=3),
    ]
    ranked = score_candidates("Daft Punk", "Discovery", 14, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "no_match"
    assert chosen is None


def test_unknown_album_does_not_cap_the_score():
    # Regression: a group with no album name at all (e.g. clustered by matching
    # artist + track number, per album_grouper._group_untagged) used to compare ""
    # against every candidate's title, always scoring 0 and permanently capping the
    # composite — even a perfect artist + track-count match couldn't reach auto-apply.
    candidates = [
        MBCandidate(release_id="right", title="Broken Soul Jamboree", artist_credit="Tipper", track_count=13),
        MBCandidate(release_id="wrong", title="Some Other Album", artist_credit="Tipper", track_count=8),
    ]
    ranked = score_candidates("Tipper", None, 13, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "right"
    assert ranked[0].score == 100.0


def test_partial_local_track_set_does_not_block_auto_apply():
    # Regression against the real library: owning only 4 of a real 15-track EP
    # ("Caps On, Hats Off") — completely normal, e.g. a partial download — used to
    # score a hard 0 on the track-count component purely because 4 != 15, dragging
    # an otherwise perfect artist+album match below the auto-apply threshold.
    # Owning fewer tracks than the release actually has must not be scored the
    # same as genuine mismatch evidence.
    candidates = [
        MBCandidate(release_id="right", title="Caps On, Hats Off", artist_credit="Bossfight", track_count=15),
        MBCandidate(release_id="wrong", title="Hats Off", artist_credit="Ethan Tasch", track_count=4),
    ]
    ranked = score_candidates("Bossfight", "Caps On, Hats Off", 4, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "right"


def test_owning_more_tracks_than_the_release_has_is_still_a_real_mismatch_signal():
    # The inverse of the partial-ownership case above: a local group somehow
    # larger than the candidate's own track count is real evidence against the
    # match (impossible for a genuine release), so it must still score low, not
    # get the same partial credit as the "fewer tracks" case.
    exact = MBCandidate(release_id="exact", title="Discovery", artist_credit="Daft Punk", track_count=14)
    smaller = MBCandidate(release_id="smaller", title="Discovery", artist_credit="Daft Punk", track_count=8)
    ranked = score_candidates("Daft Punk", "Discovery", 14, None, [exact, smaller])
    assert ranked[0].release_id == "exact"
    assert ranked[0].score > ranked[1].score


def test_no_candidates_is_no_match():
    outcome, chosen = decide([])
    assert outcome == "no_match"
    assert chosen is None


def test_track_count_mismatch_lowers_score_below_exact_match():
    exact = MBCandidate(release_id="exact", title="Discovery", artist_credit="Daft Punk", track_count=14)
    mismatched = MBCandidate(release_id="mismatch", title="Discovery", artist_credit="Daft Punk", track_count=20)
    ranked = score_candidates("Daft Punk", "Discovery", 14, None, [exact, mismatched])
    assert ranked[0].release_id == "exact"
    assert ranked[0].score > ranked[1].score


def test_singleton_recording_title_is_actually_compared_not_skipped():
    # Regression: recording candidates were scored with a hardcoded title match of
    # 100, so same-artist recordings with completely different titles were
    # indistinguishable from the correct one — this must not happen.
    correct = MBCandidate(release_id="a", title="Bloodlust", artist_credit="Eptic", is_recording=True)
    unrelated = MBCandidate(release_id="b", title="Spellbound", artist_credit="Eptic", is_recording=True)
    ranked = score_candidates("Eptic", "Bloodlust", None, None, [correct, unrelated])
    assert ranked[0].release_id == "a"
    assert ranked[0].score > ranked[1].score


def test_singleton_perfect_match_can_reach_auto_apply():
    # Regression: track-count/year weights that can never be earned for a singleton
    # (no local track count to compare) must not create a hard ceiling below the
    # auto-apply threshold for an otherwise perfect artist+title match, as long as
    # nothing else scores close enough to make it genuinely ambiguous.
    candidates = [
        MBCandidate(release_id="a", title="Bloodlust", artist_credit="Eptic", is_recording=True),
        MBCandidate(release_id="c", title="Spellbound", artist_credit="Eptic", is_recording=True),
    ]
    ranked = score_candidates("Eptic", "Bloodlust", None, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "a"


def test_recording_search_unrelated_song_by_same_artist_does_not_block_auto_apply():
    # Regression against the real library: a recording search is already filtered
    # BY the target artist, so nearly every candidate it returns already has a
    # near-perfect artist match almost by construction — that makes artist_score
    # barely discriminating for recordings, yet under a flat weighting it still
    # dominated over half the composite once title was the only other signal. That
    # let a completely unrelated Tipper song ("Dead Pixels (Instrumental)") score
    # within the auto-apply margin of the actually-correct, unambiguous title
    # match ("Dead Soon"), forcing an obvious match to "needs review" for no reason.
    candidates = [
        MBCandidate(release_id="right", title="Dead Soon", artist_credit="Tipper", is_recording=True),
        MBCandidate(release_id="unrelated", title="Dead Pixels (Instrumental)", artist_credit="Tipper", is_recording=True),
    ]
    ranked = score_candidates("Tipper", "Dead Soon", None, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "right"


def test_recording_search_title_collision_between_unrelated_artists_still_picks_correct_one():
    # Regression against the real library: MusicBrainz returned a completely
    # unrelated recording sharing the exact same title ("Nishapur") by a totally
    # different artist ("Renaud Garcia-Fons & Derya Turkan" vs. the real "Soltan &
    # DR MAD"). fuzz.WRatio scored that unrelated artist name a deceptively high
    # 85.5 against the real one (shared connector words/character overlap), which
    # under the old artist weighting nearly tied the real match and forced it to
    # needs_review despite being unambiguous. token_set_ratio correctly recognizes
    # the two names don't actually share real words, keeping this an auto-apply.
    candidates = [
        MBCandidate(release_id="right", title="Nishapur", artist_credit="Soltan & DR MAD", is_recording=True),
        MBCandidate(
            release_id="wrong", title="Nishapur",
            artist_credit="Renaud Garcia-Fons & Derya Turkan", is_recording=True,
        ),
    ]
    ranked = score_candidates("Soltan & DR MAD", "Nishapur", None, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "right"


def test_recording_candidates_with_identical_text_but_different_linked_albums_go_to_review():
    # Regression against the real library: MusicBrainz can return several
    # genuinely different RECORDINGS that all share the exact same artist+title
    # text — "Forever Autumn" by "Jeff Wayne" exists as the original 1978 album
    # recording, a live-concert recording, and an unrelated Moody Blues singles
    # compilation, all scoring identically on artist/title text alone. Without also
    # comparing which album each is actually linked to, these were treated as a
    # harmless "same content, different edition" tie and auto-applied to whichever
    # one the API happened to return first.
    candidates = [
        MBCandidate(
            release_id="main", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="Jeff Wayne's Musical Version of The War of the Worlds",
        ),
        MBCandidate(
            release_id="wrong", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="The Singles+",
        ),
    ]
    ranked = score_candidates("Jeff Wayne", "Forever Autumn", None, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "needs_review"
    assert chosen is None


def test_recording_candidates_for_the_same_album_with_apostrophe_variants_still_auto_apply():
    # The flip side of the test above: many editions of the literal SAME album,
    # differing only in curly vs. straight apostrophe encoding across different
    # MusicBrainz data-entry sessions, must still be recognized as one real album
    # and allowed to auto-apply — not treated as spuriously "different" albums.
    candidates = [
        MBCandidate(
            release_id="a", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="Jeff Wayne's Musical Version of The War of the Worlds",
        ),
        MBCandidate(
            release_id="b", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="Jeff Wayne’s Musical Version of The War of the Worlds",
        ),
    ]
    ranked = score_candidates("Jeff Wayne", "Forever Autumn", None, None, candidates)
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen is not None


def test_local_album_hint_breaks_a_tie_between_identically_titled_recordings():
    # A folder-structure-derived album guess (e.g. "Jeff Wayne's War of the
    # Worlds", recovered from an "Album Name/Act 1/track.mp3" layout — see
    # album_grouper._singleton and filename_parser's disc/act-subfolder handling)
    # should let the correct recording confidently win over a same-titled one
    # linked to an unrelated album, rather than needing a human to break the tie
    # every time.
    candidates = [
        MBCandidate(
            release_id="main", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="Jeff Wayne's Musical Version of The War of the Worlds",
        ),
        MBCandidate(
            release_id="wrong", title="Forever Autumn", artist_credit="Jeff Wayne",
            is_recording=True, album="The Singles+",
        ),
    ]
    ranked = score_candidates(
        "Jeff Wayne", "Forever Autumn", None, None, candidates,
        local_album_hint="Jeff Wayne's War of the Worlds",
    )
    outcome, chosen = decide(ranked)
    assert outcome == "auto_apply"
    assert chosen.release_id == "main"


def test_singleton_exact_title_beats_a_version_variant_of_the_same_song():
    # "Bloodlust (VIP)" is a genuinely different recording from "Bloodlust", so a
    # file titled plainly "Bloodlust" is not ambiguous between them. This used to be
    # sent to review because WRatio scored the two titles within the auto-apply
    # margin of each other — which meant every original track that has remixes
    # (e.g. "Kereberot" next to "Kereberot (D'LION remix)") needed manual review.
    candidates = [
        MBCandidate(release_id="a", title="Bloodlust", artist_credit="Eptic", is_recording=True),
        MBCandidate(release_id="b", title="Bloodlust (VIP)", artist_credit="Eptic", is_recording=True),
    ]
    outcome, chosen = decide(score_candidates("Eptic", "Bloodlust", None, None, candidates))
    assert outcome == "auto_apply"
    assert chosen.release_id == "a"


def test_singleton_version_credit_selects_that_specific_remix():
    candidates = [
        MBCandidate(release_id="a", title="Kereberot", artist_credit="SVDDEN DEATH", is_recording=True),
        MBCandidate(release_id="b", title="Kereberot (BVSSIC remix)", artist_credit="SVDDEN DEATH", is_recording=True),
        MBCandidate(release_id="c", title="Kereberot (D’LION remix)", artist_credit="SVDDEN DEATH", is_recording=True),
    ]
    outcome, chosen = decide(score_candidates("SVDDEN DEATH", "Kereberot (BVSSIC Remix)", None, None, candidates))
    assert outcome == "auto_apply"
    assert chosen.release_id == "b"


def test_remixer_credit_spelled_with_x_or_multiplication_sign_still_matches():
    candidate = MBCandidate(
        release_id="a", title="Rings of Pluto (Sora × SweetTooth remix)", artist_credit="SVDDEN DEATH", is_recording=True
    )
    score = score_candidate("SVDDEN DEATH", "Rings of Pluto (Sora X SweetTooth Remix)", None, None, candidate)
    assert score > 95


def test_featured_artist_parenthetical_is_not_a_version_difference():
    candidate = MBCandidate(release_id="a", title="Burn It Down", artist_credit="Marshmello & SVDDEN DEATH", is_recording=True)
    score = score_candidate("Marshmello & SVDDEN DEATH", "Burn It Down (feat. Jedwill)", None, None, candidate)
    assert score > 90


def test_release_titles_differing_only_by_number_are_not_near_ties():
    candidates = [
        MBCandidate(release_id="1", title="Vaultage 001", artist_credit="Space Laces"),
        MBCandidate(release_id="2", title="Vaultage 002", artist_credit="Space Laces"),
        MBCandidate(release_id="3", title="Vaultage 003", artist_credit="Space Laces"),
    ]
    outcome, chosen = decide(score_candidates("Space Laces", "Vaultage 002", 1, None, candidates))
    assert outcome == "auto_apply"
    assert chosen.release_id == "2"
