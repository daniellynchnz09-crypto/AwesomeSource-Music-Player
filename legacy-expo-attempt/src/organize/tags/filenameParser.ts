/**
 * TypeScript re-port of `legacy-desktop-tagger/musictagger/tags/filename_parser.py`.
 * Derives candidate artist/album/title text from a file's path when tags are
 * sparse. See that file's module doc comment for the two-tier design (structured
 * patterns for conventionally-organized libraries, a loose junk-token-filtering
 * fallback for gibberish filenames) - every regex, constant, and real-world bug fix
 * documented there is preserved here rather than re-derived.
 */

import { grandparentName, hasGrandparent, parentName, pathStem } from '../model/libraryPath';
import { TrackMetadata } from '../model/types';

const NOISE_PATTERNS: RegExp[] = [
  /\[[^\]]*]/g, // [Explicit], [HQ], ...
  // Newgrounds Audio Portal rips tack the submission's numeric ID onto the TITLE
  // tag itself, e.g. "Dr. Finkelfracken's Cure (ID: 383158)".
  /\(id:\s*\d+\)/gi,
  /\{[^}]*}/g, // {tag}-style bracket noise
  /\((?:remaster(?:ed)?|deluxe|explicit|clean|hq|official)[^)]*\)/gi,
  /\((?:free|original\s+mix)\)/gi,
  /\b\d{2,4}\s?kbps\b/gi,
  /\(\s*\d+\s*\)/g, // trailing (1), (2) copy markers
  /\s*-\s*copy\b/gi,
  // YouTube rips: "Song Name | Insolito (4K music visualizer)" - a bare "|"
  // essentially never appears in a real song title.
  /\s*\|.*$/g,
  /\([^)]*\b(?:video|audio|visualizer)\b[^)]*\)/gi,
  // A bare (non-parenthesized) "Ft. X"/"feat. X" suffix is upload-added credit
  // text, not part of the recording's own title; a parenthesized "(feat. X)" is
  // deliberately left alone.
  /\s+(?:ft\.?|feat\.?|featuring)\s+.+$/gi,
  // DJ-mix compilations: "Album Vol. N - Mixed by DJ Name" - the "Mixed by X" tail
  // is a credit line, not the title.
  /\s*-\s*(?:mixed|remixed|hosted|selected|compiled)\s+by\s+.+$/gi,
];

const TRACK_PREFIX_RE = /^\s*(\d{1,3})\s*[-.\s]+/;
const ARTIST_TITLE_RE = /^(.+?)\s*-\s*(.+)$/;

// "Album Name/Act 1/track.mp3" or ".../Disc 2/...", ".../CD1/...": the immediate
// parent is a disc/act subdivision of ONE album, not a real "Album" level.
const DISC_SUBFOLDER_RE = /^(?:disc|cd|act|part|volume|vol)\.?\s*\d+$/i;

const GENERIC_ANCESTOR_NAMES = new Set([
  'music', 'songs', 'downloads', 'itunes', 'mp3', 'mp3s', 'library',
  'unsorted', 'new music', 'audio', 'tracks', 'media', 'my music',
]);

const JUNK_WORDS = new Set([
  'copy', 'final', 'new', 'track', 'file', 'audio', 'untitled', 'download',
  'downloaded', 'unknown', 'song', 'music', 'temp', 'tmp', 'v2', 'v3',
]);

const TOKEN_SPLIT_RE = /[^A-Za-z0-9']+/;

export type Confidence = 'structured' | 'loose';

export interface FilenameGuess {
  artist?: string;
  album?: string;
  title?: string;
  trackNumber?: number;
  confidence: Confidence;
  searchText?: string;
}

function stripNoise(text: string): string {
  let result = text;
  for (const pattern of NOISE_PATTERNS) result = result.replace(pattern, '');
  return result.trim().replace(/^[-_.\s]+|[-_.\s]+$/g, '');
}

/** Public entry point for noise-stripping arbitrary tag text (not just a
 * filename) that may have no "Artist - Title" dash to split on at all. */
export function cleanNoiseText(text: string | null | undefined): string {
  return stripNoise(text ?? '');
}

function looksLikeJunkToken(token: string): boolean {
  if (!token) return true;
  const lowered = token.toLowerCase();
  if (JUNK_WORDS.has(lowered)) return true;
  if (/^\d+$/.test(token)) return true;
  const hasDigit = /\d/.test(token);
  const vowels = (lowered.match(/[aeiou]/g) ?? []).length;
  // A short alphanumeric mix with a digit and zero vowels reads as a
  // machine-generated fragment (e.g. "xY7", "2gK9"), not a real word.
  if (hasDigit && vowels === 0) return true;
  if (token.length >= 5 && hasDigit && vowels / token.length < 0.15) return true;
  return false;
}

function looksPlausible(text: string): boolean {
  const tokens = text.split(TOKEN_SPLIT_RE).filter(Boolean);
  if (tokens.length === 0) return false;
  const junkCount = tokens.filter(
    // A short (1-2 digit) standalone numeral is real title text ("Memories 2",
    // "Part 3"), not the gibberish looksLikeJunkToken targets.
    (t) => looksLikeJunkToken(t) && !(/^\d+$/.test(t) && t.length <= 2),
  ).length;
  return junkCount < tokens.length / 2;
}

/** Splits "Artist - Title" style text into [artist, title] when the pattern
 * plausibly applies; returns [null, null] otherwise. Also used on TITLE tag text
 * itself - a YouTube-sourced rip's TITLE tag can literally contain the whole
 * "Artist - Track" video title. */
export function splitArtistTitleText(text: string): [string | null, string | null] {
  const cleaned = stripNoise(text);
  const match = ARTIST_TITLE_RE.exec(cleaned);
  if (!match) return [null, null];
  if (!looksPlausible(match[1])) return [null, null];
  return [match[1].trim(), match[2].trim()];
}

/** Splits a leading "## " track-position prefix off raw text, e.g. a TITLE tag
 * literally "1 Goldilocks Zone" -> [1, "Goldilocks Zone"]. */
export function stripLeadingTrackNumber(text: string | null | undefined): [number | null, string] {
  if (!text) return [null, text ?? ''];
  const match = TRACK_PREFIX_RE.exec(text);
  if (!match) return [null, text];
  const remainder = text.slice(match[0].length).trim();
  if (!remainder) return [null, text];
  return [parseInt(match[1], 10), remainder];
}

/** Best-known track number: tag value, else filename-parsed, else a leading "## "
 * prefix baked into the TITLE tag's own text. */
export function resolveTrackNumber(track: TrackMetadata): number | undefined {
  if (track.trackNumber !== undefined) return track.trackNumber;
  const filenameNumber = parseFilename(track.path).trackNumber;
  if (filenameNumber !== undefined) return filenameNumber;
  const [number] = stripLeadingTrackNumber(track.title);
  return number ?? undefined;
}

function looseFallback(stem: string, trackNumber: number | undefined, album: string | undefined): FilenameGuess {
  const tokens = stem.split(TOKEN_SPLIT_RE);
  const kept = tokens.filter((t) => t && !looksLikeJunkToken(t));
  const searchText = kept.join(' ').trim();
  return {
    album,
    confidence: 'loose',
    searchText: searchText || undefined,
    // A track number already found before falling back here (e.g. a leading "## "
    // prefix) is still real, useful information - it must carry through rather
    // than silently reverting to undefined.
    trackNumber,
  };
}

export function parseFilename(path: string): FilenameGuess {
  const stem = stripNoise(pathStem(path));
  const parentNameValue = parentName(path);
  const hasGrandparentValue = hasGrandparent(path);
  const grandparentNameValue = grandparentName(path);

  let trackNumber: number | undefined;
  let remainder = stem;
  const trackMatch = TRACK_PREFIX_RE.exec(stem);
  if (trackMatch) {
    trackNumber = parseInt(trackMatch[1], 10);
    remainder = stem.slice(trackMatch[0].length).trim();
  }

  // Pattern 2 checked before Pattern 1: an explicit "Artist - Album" folder name is
  // a more specific, deliberate signal than merely having two ancestor folders,
  // which just as often means <library root>/<album>/ with no real artist level.
  const folderMatch = ARTIST_TITLE_RE.exec(stripNoise(parentNameValue));
  if (folderMatch && remainder && looksPlausible(folderMatch[1])) {
    return {
      artist: folderMatch[1].trim(),
      album: folderMatch[2].trim(),
      title: remainder,
      trackNumber,
      confidence: 'structured',
    };
  }

  // "Album Name/Act 1/track.mp3" etc: the immediate parent is a disc/act
  // subdivision, not a real "Album" level - the grandparent is the actual album,
  // and it is NOT a plausible artist name at all, so Pattern 1 below is skipped
  // entirely for this layout.
  let albumFromDiscSubfolder: string | undefined;
  if (hasGrandparentValue && DISC_SUBFOLDER_RE.test(parentNameValue.trim())) {
    const candidateAlbum = stripNoise(grandparentNameValue);
    if (candidateAlbum && looksPlausible(candidateAlbum)) {
      albumFromDiscSubfolder = candidateAlbum;
    }
  }

  // Pattern 1: Artist/Album/## - Title.ext
  if (hasGrandparentValue && albumFromDiscSubfolder === undefined) {
    const artistCandidate = stripNoise(grandparentNameValue);
    const albumCandidate = stripNoise(parentNameValue);
    if (
      artistCandidate &&
      albumCandidate &&
      remainder &&
      !GENERIC_ANCESTOR_NAMES.has(artistCandidate.toLowerCase()) &&
      looksPlausible(artistCandidate)
    ) {
      return {
        artist: artistCandidate,
        album: albumCandidate,
        title: remainder,
        trackNumber,
        confidence: 'structured',
      };
    }
  }

  // Pattern 3: "Artist - Title.ext" (loose single files)
  const fileMatch = ARTIST_TITLE_RE.exec(remainder);
  if (fileMatch && looksPlausible(fileMatch[1])) {
    return {
      artist: fileMatch[1].trim(),
      album: albumFromDiscSubfolder,
      title: fileMatch[2].trim(),
      trackNumber,
      confidence: 'structured',
    };
  }

  if (remainder && looksPlausible(remainder)) {
    return {
      album: albumFromDiscSubfolder,
      title: remainder,
      trackNumber,
      confidence: 'structured',
    };
  }

  // Nothing structured and plausible matched - likely gibberish; fall back to
  // junk-token filtering and a free-text search guess.
  return looseFallback(stem, trackNumber, albumFromDiscSubfolder);
}
