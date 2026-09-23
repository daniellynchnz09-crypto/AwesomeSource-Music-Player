/**
 * TypeScript re-port of `legacy-desktop-tagger/musictagger/matching/scorer.py`.
 * Scores MusicBrainz candidates against local (tag/filename-derived) data and
 * decides whether a match is confident enough to auto-apply, needs human review, or
 * should be treated as no match. Pure logic, no I/O.
 *
 * Unlike the first (Kotlin) port - see legacy-android-native-attempt/ - this one
 * uses `fuzzball` (an npm port of the same `fuzzywuzzy`/`rapidfuzz` family of
 * algorithms the Python original depends on) instead of a hand-rolled
 * reimplementation, so scores should track the original much more closely.
 * `full_process: false` is passed on every call to match rapidfuzz's default
 * behavior (no extra automatic cleanup) - this module's own `normalize()` is the
 * single source of text cleanup, exactly as in the Python original.
 */

import * as fuzz from 'fuzzball';
import { MbCandidate } from '../model/types';

export const AUTO_APPLY_THRESHOLD = 90.0;
export const NEEDS_REVIEW_THRESHOLD = 60.0;
export const AUTO_APPLY_MARGIN = 10.0;

const ARTIST_WEIGHT = 0.45;
const ALBUM_WEIGHT = 0.35;
const TRACK_COUNT_WEIGHT = 0.15;
const YEAR_WEIGHT = 0.05;

// A recording (track-level) search is already filtered *by* the target artist, so
// artist_score is nearly non-discriminating for recordings; title match must
// dominate there instead (see scorer.py's identical comment).
const RECORDING_ARTIST_WEIGHT = 0.3;
const RECORDING_TITLE_WEIGHT = 0.7;
const RECORDING_ALBUM_HINT_WEIGHT = 0.35;

const NO_PROCESS = { full_process: false } as const;

const WHITESPACE_RE = /\s+/g;
const APOSTROPHE_RE = /[‘’ʼ]/g;
const VERSION_GROUP_RE = /[([]([^)\]]*)[)\]]/g;
const NON_DISTINGUISHING_VERSION_RE = /^(?:(?:feat|ft|featuring|with)\b|original mix$)/i;
const VERSION_MISMATCH_TITLE_CAP = 60.0;

function normalize(text: string | null | undefined): string {
  const folded = (text ?? '').replace(APOSTROPHE_RE, "'");
  return folded.trim().replace(WHITESPACE_RE, ' ').toLowerCase();
}

function versionMarkers(text: string): string {
  const groups: string[] = [];
  for (const match of text.matchAll(VERSION_GROUP_RE)) {
    const inner = match[1].trim();
    if (inner && !NON_DISTINGUISHING_VERSION_RE.test(inner)) groups.push(inner);
  }
  return groups
    .join(' ')
    .replace(/×/g, ' x ')
    .replace(/[^\w ]+/g, ' ')
    .trim();
}

function extractDigitRuns(text: string): string[] {
  return text.match(/\d+/g) ?? [];
}

/** WRatio similarity between two already-normalized titles, capped when their
 * version credits or numerals differ - see scorer.py's identical comment for the
 * real false-tie cases this fixes: "Kereberot" vs "Kereberot (D'LION remix)". */
function titleScore(local: string, candidate: string): number {
  const score = fuzz.WRatio(local, candidate, NO_PROCESS);
  const localMarkers = versionMarkers(local);
  const candidateMarkers = versionMarkers(candidate);
  if (Boolean(localMarkers) !== Boolean(candidateMarkers)) {
    return Math.min(score, VERSION_MISMATCH_TITLE_CAP);
  }
  if (localMarkers && fuzz.token_sort_ratio(localMarkers, candidateMarkers, NO_PROCESS) < 80.0) {
    return Math.min(score, VERSION_MISMATCH_TITLE_CAP);
  }
  const localDigits = extractDigitRuns(local);
  const candidateDigits = extractDigitRuns(candidate);
  if (localDigits.join(',') !== candidateDigits.join(',')) {
    return Math.min(score, VERSION_MISMATCH_TITLE_CAP);
  }
  return score;
}

/**
 * `localAlbum` is compared against `candidate.title` for both callers use this
 * for: an album title (release search) or a track title (recording search, for
 * singleton files). See scorer.py's identical doc comment for why the album
 * component is only included when both sides actually have data, rather than
 * scoring "" against every candidate and silently capping every score.
 */
export function scoreCandidate(
  localArtist: string | null | undefined,
  localAlbum: string | null | undefined,
  localTrackCount: number | null | undefined,
  localYear: number | null | undefined,
  candidate: MbCandidate,
  localAlbumHint?: string | null,
): number {
  const artistScore = candidate.isRecording
    ? fuzz.token_set_ratio(normalize(localArtist), normalize(candidate.artistCredit), NO_PROCESS)
    : fuzz.WRatio(normalize(localArtist), normalize(candidate.artistCredit), NO_PROCESS);

  const [artistWeight, albumWeight] = candidate.isRecording
    ? [RECORDING_ARTIST_WEIGHT, RECORDING_TITLE_WEIGHT]
    : [ARTIST_WEIGHT, ALBUM_WEIGHT];

  const components: Array<[number, number]> = [[artistWeight, artistScore]];

  if (localAlbum) {
    components.push([albumWeight, titleScore(normalize(localAlbum), normalize(candidate.title))]);
  }

  if (candidate.isRecording && localAlbumHint && candidate.album) {
    const albumHintScore = fuzz.token_set_ratio(normalize(localAlbumHint), normalize(candidate.album), NO_PROCESS);
    components.push([RECORDING_ALBUM_HINT_WEIGHT, albumHintScore]);
  }

  if (localTrackCount && candidate.trackCount) {
    let trackCountScore: number;
    if (localTrackCount === candidate.trackCount) {
      trackCountScore = 100.0;
    } else if (localTrackCount < candidate.trackCount) {
      // Owning fewer tracks than the real release is normal (a partial rip);
      // scale partial credit by coverage rather than treating it as a mismatch.
      trackCountScore = 50.0 + 50.0 * (localTrackCount / candidate.trackCount);
    } else {
      // Owning *more* tracks than the release actually has is real evidence
      // against the match.
      trackCountScore = 0.0;
    }
    components.push([TRACK_COUNT_WEIGHT, trackCountScore]);
  }

  if (localYear && candidate.firstReleaseDate && /^\d{4}/.test(candidate.firstReleaseDate)) {
    const candidateYear = parseInt(candidate.firstReleaseDate.slice(0, 4), 10);
    const yearScore = Math.max(0.0, 100.0 - Math.abs(candidateYear - localYear) * 10);
    components.push([YEAR_WEIGHT, yearScore]);
  }

  const totalWeight = components.reduce((sum, [weight]) => sum + weight, 0);
  if (totalWeight === 0) return 0.0;
  return components.reduce((sum, [weight, score]) => sum + weight * score, 0) / totalWeight;
}

/** Returns candidates sorted best-first, each with `.score` populated. */
export function scoreCandidates(
  localArtist: string | null | undefined,
  localAlbum: string | null | undefined,
  localTrackCount: number | null | undefined,
  localYear: number | null | undefined,
  candidates: MbCandidate[],
  localAlbumHint?: string | null,
): MbCandidate[] {
  for (const candidate of candidates) {
    candidate.score = scoreCandidate(localArtist, localAlbum, localTrackCount, localYear, candidate, localAlbumHint);
  }
  return [...candidates].sort((a, b) => b.score - a.score);
}

export type Outcome = 'auto_apply' | 'needs_review' | 'no_match';

export interface Decision {
  outcome: Outcome;
  chosen: MbCandidate | null;
}

/**
 * See scorer.py's `decide()` doc comment for the full reasoning: MusicBrainz
 * commonly returns several near-identically-scored candidates that are genuinely
 * the same release (different countries/remasters/formats) - that kind of tie must
 * not block auto-apply the way a tie between two actually different albums should.
 * Told apart by whether the near-top candidates agree on normalized (artist,
 * title[, linked album for recordings]).
 */
export function decide(candidates: MbCandidate[]): Decision {
  if (candidates.length === 0) return { outcome: 'no_match', chosen: null };

  const ranked = [...candidates].sort((a, b) => b.score - a.score);
  const top = ranked[0];

  if (top.score < NEEDS_REVIEW_THRESHOLD) return { outcome: 'no_match', chosen: null };
  if (top.score < AUTO_APPLY_THRESHOLD) return { outcome: 'needs_review', chosen: null };

  const nearTop = ranked.filter((c) => top.score - c.score < AUTO_APPLY_MARGIN);
  const distinctAlbums = new Set(
    nearTop.map((c) =>
      [normalize(c.artistCredit), normalize(c.title), c.isRecording ? normalize(c.album) : ''].join('\u0000'),
    ),
  );

  return distinctAlbums.size <= 1 ? { outcome: 'auto_apply', chosen: top } : { outcome: 'needs_review', chosen: null };
}
