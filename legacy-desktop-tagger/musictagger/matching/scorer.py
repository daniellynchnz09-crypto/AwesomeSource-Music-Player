"""Scores MusicBrainz candidates against local (tag/filename-derived) data and
decides whether a match is confident enough to auto-apply, needs human review, or
should be treated as no match at all. Pure logic, no I/O — see Plan/design.md
"Matching / scoring".

Three outcome bands:
- auto_apply: the top candidate scores highly AND clears the next-best candidate by
  a comfortable margin (so two near-tied candidates never get silently resolved).
- needs_review: a plausible but not confident match — the candidate list is handed
  to the UI for the user to pick from.
- no_match: nothing scored high enough to be worth showing as a suggestion; routes
  straight to manual entry.
"""

from __future__ import annotations

import re
from typing import Optional

from rapidfuzz import fuzz

from musictagger.models import MBCandidate

_WHITESPACE_RE = re.compile(r"\s+")
# MusicBrainz data isn't consistent about straight (') vs. "smart"/curly (' ')
# apostrophes across different entries of what's otherwise the exact same text
# (e.g. "Jeff Wayne's..." vs "Jeff Wayne's..." differing only in this one
# character) — without folding them to the same character, two representations of
# the literal same album title compare as different strings everywhere _normalize
# is used, including decide()'s "is this really just a different edition of the
# same thing" tie-check below.
_APOSTROPHE_RE = re.compile(r"[‘’ʼ]")


def _normalize(text: Optional[str]) -> str:
    text = _APOSTROPHE_RE.sub("'", (text or ""))
    return _WHITESPACE_RE.sub(" ", text.strip().lower())

# rapidfuzz's WRatio treats a short title as a near-perfect *partial* match of a
# longer one that merely adds text, and barely notices a changed digit, so real,
# different recordings scored as near-ties with the exact match: "Kereberot" vs.
# "Kereberot (D'LION remix)" scored 90, and "Vaultage 001" vs. "Vaultage 002" 92 —
# inside decide()'s auto-apply margin, which sent every original track that has
# remixes (and every numbered release) to manual review. What actually tells two
# such titles apart is the parenthesized version credit and any numerals, so when
# those disagree the title similarity is capped.
_VERSION_GROUP_RE = re.compile(r"[(\[]([^)\]]*)[)\]]")
# A parenthesized featured-artist credit or "(Original Mix)" is credit/label noise
# that MusicBrainz and local tags handle inconsistently, not a different recording.
_NON_DISTINGUISHING_VERSION_RE = re.compile(r"^(?:(?:feat|ft|featuring|with)\b|original mix$)", re.I)
_DIGITS_RE = re.compile(r"\d+")
_VERSION_MISMATCH_TITLE_CAP = 60.0


def _version_markers(text: str) -> str:
    groups = [
        g.strip()
        for g in _VERSION_GROUP_RE.findall(text)
        if g.strip() and not _NON_DISTINGUISHING_VERSION_RE.match(g.strip())
    ]
    return re.sub(r"[^\w ]+", " ", " ".join(groups).replace("×", " x ")).strip()


def _title_score(local: str, candidate: str) -> float:
    """WRatio similarity between two already-normalized titles, capped when their
    version credits or numerals differ (see the note above)."""
    score = fuzz.WRatio(local, candidate)
    local_markers, candidate_markers = _version_markers(local), _version_markers(candidate)
    if bool(local_markers) != bool(candidate_markers):
        return min(score, _VERSION_MISMATCH_TITLE_CAP)
    if local_markers and fuzz.token_sort_ratio(local_markers, candidate_markers) < 80:
        return min(score, _VERSION_MISMATCH_TITLE_CAP)
    if _DIGITS_RE.findall(local) != _DIGITS_RE.findall(candidate):
        return min(score, _VERSION_MISMATCH_TITLE_CAP)
    return score


AUTO_APPLY_THRESHOLD = 90.0
NEEDS_REVIEW_THRESHOLD = 60.0
AUTO_APPLY_MARGIN = 10.0

_ARTIST_WEIGHT = 0.45
_ALBUM_WEIGHT = 0.35
_TRACK_COUNT_WEIGHT = 0.15
_YEAR_WEIGHT = 0.05

# A recording (track-level) search is already filtered *by* the target artist, so
# essentially every candidate it returns is already a strong artist match almost by
# construction — that makes artist_score nearly non-discriminating for recordings,
# yet at a flat weighting it still dominates over half the composite once title is
# the only other available signal. That let a completely unrelated song by the same
# artist ("Dead Pixels (Instrumental)") score close enough to a perfect title match
# ("Dead Soon") to trip the auto-apply margin check and force it to "needs review"
# even though the top match was unambiguous. Title match must dominate for
# recordings; artist is still worth a real share (see _RECORDING_ARTIST_ALGO below
# for why it can safely carry more weight than that original tuning allowed).
_RECORDING_ARTIST_WEIGHT = 0.30
_RECORDING_TITLE_WEIGHT = 0.70

# When a recording search has an independent album-name guess to work with (e.g.
# recovered from folder structure — see album_grouper._singleton and
# filename_parser's disc/act-subfolder handling), it's a strong disambiguator: a
# track-level search can return several genuinely different *recordings* that all
# share the exact same artist+title text (a studio album cut, a live-concert
# recording, and an unrelated compilation can all be literally titled the same
# thing), and only the album each is actually linked to tells them apart. Given
# real weight rather than left for decide()'s tie-check alone, since a wrong
# candidate that's this identical on artist+title otherwise ties the correct one
# outright.
_RECORDING_ALBUM_HINT_WEIGHT = 0.35


def score_candidate(
    local_artist: Optional[str],
    local_album: Optional[str],
    local_track_count: Optional[int],
    local_year: Optional[int],
    candidate: MBCandidate,
    local_album_hint: Optional[str] = None,
) -> float:
    """`local_album` is compared against candidate.title for both cases the caller
    uses this for: an album title (release search) or a track title (recording
    search, for singleton files) — when it's available, it must always be scored,
    never skipped, since two recordings by the same artist (e.g. "Bloodlust" vs.
    "Bloodlust (VIP)" vs. an unrelated "Spellbound") are otherwise indistinguishable
    by artist match alone.

    But local_album is sometimes genuinely unknown — a group of untagged files
    clustered by matching artist + track number (see album_grouper._group_untagged)
    has no album name to offer at all, only an artist and a track count. Comparing
    "" against every candidate's title would always score 0 and cap every candidate
    at the same artificial ceiling regardless of how well everything else matches,
    silently defeating the lookup for exactly the tracks that most need it. So, like
    track-count and year below, the album component is only included when both
    sides actually have the data — the weighted average is renormalized over only
    the signals actually available, so a perfect artist+track-count match can still
    reach 100 on its own.
    """
    # rapidfuzz's fuzz.WRatio is case-SENSITIVE — "ECRAZE" vs "Ecraze" scores 0.0,
    # not "very similar" — so every comparison here goes through _normalize() first
    # (lowercased) rather than passing the raw tag/candidate text straight in. This
    # matters constantly in practice: MusicBrainz stores title-case names ("Ecraze"),
    # while a great many local tags are all-caps stylized ("ECRAZE") the way the
    # artist brands themselves — without normalizing, that alone was silently
    # gutting the artist-match component for any such track, on top of whatever
    # else was already wrong.
    if candidate.is_recording:
        # WRatio is unreliable for comparing two multi-word artist *names* — it
        # picked up enough incidental character/substring overlap between
        # genuinely unrelated artists (observed for real: "Soltan & DR MAD" vs.
        # "Renaud Garcia-Fons & Derya Türkan" scored 85.5, nowhere near as low as
        # it should) to nearly tie a same-titled recording by a completely
        # different artist against the real match, tripping needs-review on an
        # exact-title collision that should have been unambiguous. token_set_ratio
        # instead compares the two names' actual word sets — it stays high when a
        # real match just has extra words tacked on (e.g. local "Ecraze" against a
        # credit of "Ecraze ft. Dyno", or a local "PEEKABOO, Flava D, Scrufizzer"
        # against "PEEKABOO w/ Flava D, Scrufizzer") while correctly dropping to
        # near-zero for two names that don't actually share their real words.
        artist_score = fuzz.token_set_ratio(_normalize(local_artist), _normalize(candidate.artist_credit))
    else:
        artist_score = fuzz.WRatio(_normalize(local_artist), _normalize(candidate.artist_credit))
    artist_weight, album_weight = (
        (_RECORDING_ARTIST_WEIGHT, _RECORDING_TITLE_WEIGHT) if candidate.is_recording else (_ARTIST_WEIGHT, _ALBUM_WEIGHT)
    )
    components = [(artist_weight, artist_score)]

    if local_album:
        album_score = _title_score(_normalize(local_album), _normalize(candidate.title))
        components.append((album_weight, album_score))

    if candidate.is_recording and local_album_hint and candidate.album:
        # Same unreliability as the artist-name comparison above, and just as
        # consequential here: WRatio scored "Jeff Wayne's War of the Worlds" vs.
        # the totally unrelated "The Singles+" at 85.5 — nowhere near low enough
        # to do its one job of ruling out the wrong album. token_set_ratio (100 vs.
        # 40 for the same pair) actually discriminates.
        album_hint_score = fuzz.token_set_ratio(_normalize(local_album_hint), _normalize(candidate.album))
        components.append((_RECORDING_ALBUM_HINT_WEIGHT, album_hint_score))

    if local_track_count and candidate.track_count:
        if local_track_count == candidate.track_count:
            track_count_score = 100.0
        elif local_track_count < candidate.track_count:
            # Owning fewer tracks than the real release has is completely normal —
            # a partial rip, or just never having downloaded the whole EP/album —
            # and must not be scored the same as genuine evidence against the
            # match (e.g. a "Greatest Hits" with a wildly different count). This
            # was a real, general bug: 4 real tracks off a real 15-track EP,
            # correctly artist- and album-matched, still scored a hard 0 here and
            # dropped the whole match below the auto-apply threshold. A partial
            # local set still gets partial credit, scaled by how much of the
            # release it covers, rather than being treated as a mismatch.
            track_count_score = 50.0 + 50.0 * (local_track_count / candidate.track_count)
        else:
            # Owning *more* tracks than the release actually has is impossible for
            # a genuine match — that's real evidence this is the wrong candidate.
            track_count_score = 0.0
        components.append((_TRACK_COUNT_WEIGHT, track_count_score))

    if local_year and candidate.first_release_date and candidate.first_release_date[:4].isdigit():
        candidate_year = int(candidate.first_release_date[:4])
        year_score = max(0.0, 100.0 - abs(candidate_year - local_year) * 10)
        components.append((_YEAR_WEIGHT, year_score))

    total_weight = sum(weight for weight, _ in components)
    if total_weight == 0:
        return 0.0
    return sum(weight * score for weight, score in components) / total_weight


def score_candidates(
    local_artist: Optional[str],
    local_album: Optional[str],
    local_track_count: Optional[int],
    local_year: Optional[int],
    candidates: list[MBCandidate],
    local_album_hint: Optional[str] = None,
) -> list[MBCandidate]:
    """Returns candidates sorted best-first, each with .score populated."""
    for candidate in candidates:
        candidate.score = score_candidate(
            local_artist, local_album, local_track_count, local_year, candidate, local_album_hint
        )
    return sorted(candidates, key=lambda c: c.score, reverse=True)


def decide(candidates: list[MBCandidate]) -> tuple[str, Optional[MBCandidate]]:
    """Returns (outcome, chosen_candidate). chosen_candidate is only set for
    "auto_apply" — for "needs_review" the caller shows the full candidate list.

    MusicBrainz very commonly returns several near-identically-scored candidates for
    what is genuinely the same album — different countries, remasters, or digital
    vs. CD releases of one release-group. That kind of tie isn't real ambiguity (any
    of them yields the same artist/album/tracklist), so it must not block auto-apply
    the way a tie between two actually different albums (e.g. a "Greatest Hits" vs.
    the correct studio album) should. The two cases are told apart by whether the
    near-top candidates agree on normalized (artist, title) — and, for recording
    candidates specifically, also on which album each is actually linked to (see
    MBCandidate.album): a track-level search can return several genuinely different
    *recordings* that all share the exact same artist+title text (observed for
    real: "Forever Autumn" by "Jeff Wayne" exists as the original 1978 album
    recording, several different live-concert recordings, an unrelated Moody Blues
    singles compilation, and a 2012 reboot soundtrack — all scoring identically on
    text alone). Without also comparing the linked album, those were being treated
    as harmless "same content, different edition" ties and auto-applied to
    whichever one the API happened to return first — effectively arbitrary. A
    release-level candidate's own `title` already *is* the album, so this only
    changes behavior for recording candidates.
    """
    if not candidates:
        return "no_match", None

    ranked = sorted(candidates, key=lambda c: c.score, reverse=True)
    top = ranked[0]

    if top.score < NEEDS_REVIEW_THRESHOLD:
        return "no_match", None
    if top.score < AUTO_APPLY_THRESHOLD:
        return "needs_review", None

    near_top = [c for c in ranked if top.score - c.score < AUTO_APPLY_MARGIN]
    distinct_albums = {
        (_normalize(c.artist_credit), _normalize(c.title), _normalize(c.album) if c.is_recording else "")
        for c in near_top
    }
    if len(distinct_albums) <= 1:
        return "auto_apply", top
    return "needs_review", None
