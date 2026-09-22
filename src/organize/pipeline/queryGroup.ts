/**
 * TypeScript re-port of
 * `legacy-desktop-tagger/musictagger/workers/query_worker.py`'s `_query_group`.
 * Queries MusicBrainz for one [AlbumGroup], scores the candidates, and decides the
 * outcome - every heuristic here (channel-suffix stripping, embedded-artist-in-title
 * recovery, the bare-version-suffix retry) is preserved from the Python original,
 * each one tuned against a real observed failure case (cited inline below).
 *
 * Calls are naturally serialized by `MusicBrainzClient`'s own rate limiter, so
 * processing groups one at a time (not in parallel) is what actually guarantees no
 * burst happens - same reasoning as the Python original's "runs sequentially in one
 * worker thread" design.
 */

import { MbCandidate, FileStatus, AlbumGroup } from '../model/types';
import { MusicBrainzClient, MusicBrainzError } from '../metadata/musicBrainzClient';
import { decide, scoreCandidates } from '../matching/scorer';
import { cleanNoiseText, parseFilename, splitArtistTitleText } from '../tags/filenameParser';
import { getCachedMbQuery, setCachedMbQuery } from '../persistence/database';
import { resolveGroupToProposed } from './releaseResolver';

// YouTube-sourced music very commonly tags the uploading channel's brand name as
// the artist (e.g. "Tippermusic" for the real artist "Tipper"), which won't match
// MusicBrainz's actual artist entry at all. Only tried as a fallback *after* the
// primary search finds nothing, so it can never override an already-successful
// match with a worse guess.
const CHANNEL_SUFFIXES = ['music', 'official', 'vevo', 'records', 'band'];

// An artist-only release search (no album name available at all) can't rely on
// MusicBrainz's text relevance ranking the way an album-title search can - the
// correct release can rank arbitrarily far down an artist's whole discography
// (observed: a real, exact 13-track match ranked #50 out of 79). Fetching many
// more candidates gives the scorer's track-count signal an actual chance to find it.
const ARTIST_ONLY_SEARCH_LIMIT = 100;
const DEFAULT_SEARCH_LIMIT = 5;

// A bare, unattributed "(remix)"/"(mix)"/"(edit)" title suffix - as opposed to a
// named "(Artist Remix)", which genuinely identifies a different recording - is
// frequently just how a personal rip/download happened to be labeled. Confirmed
// for real: searching "Spawn (remix)" pushed the actual, correct "Spawn" recording
// entirely out of the top 15 results, while the bare title found it immediately at
// a perfect score. Only tried as a fallback retry, after the as-tagged title search
// hasn't already found a confident match.
const BARE_VERSION_SUFFIX_RE = /\s*\((?:remix|mix|edit|rmx)\)\s*$/i;

function normalizeKey(...parts: (string | null | undefined)[]): string {
  return parts.map((p) => (p ?? '').trim().toLowerCase()).join('|');
}

function normalize(text: string | null | undefined): string {
  return (text ?? '').trim().toLowerCase();
}

function stripChannelSuffix(artist: string): string | null {
  const lowered = artist.toLowerCase();
  for (const suffix of CHANNEL_SUFFIXES) {
    if (lowered.endsWith(suffix) && lowered.length > suffix.length + 2) {
      return artist.slice(0, artist.length - suffix.length).trim();
    }
  }
  return null;
}

async function cachedOrSearch(
  key: string,
  search: () => Promise<MbCandidate[]>,
): Promise<MbCandidate[]> {
  const cached = await getCachedMbQuery(key);
  if (cached !== null) {
    try {
      return JSON.parse(cached) as MbCandidate[];
    } catch {
      // Fall through to a real search if the cached JSON is somehow corrupt.
    }
  }
  const candidates = await search();
  await setCachedMbQuery(key, JSON.stringify(candidates));
  return candidates;
}

export async function queryGroup(mbClient: MusicBrainzClient, group: AlbumGroup): Promise<AlbumGroup> {
  const first = group.files[0];
  let ranked: MbCandidate[];

  try {
    if (group.isSingleton || group.files.length === 1) {
      const result = await querySingleton(mbClient, group, first);
      if (result === null) {
        return { ...group, status: FileStatus.InsufficientInfo, statusDetail: 'no usable artist/title to search with' };
      }
      ranked = result;
    } else {
      const artist = group.bestGuessArtist || first.albumArtist || first.artist;
      const album = group.bestGuessAlbum || first.album;
      if (!artist) {
        return { ...group, status: FileStatus.InsufficientInfo, statusDetail: 'no usable artist to search with' };
      }
      // album may be undefined - a group clustered by matching artist + track
      // number can have no album tag anywhere. searchReleaseCandidates falls back
      // to an artist-only search, and the scorer leans on track-count agreement
      // to pick the right release out of the artist's whole discography instead.
      const searchLimit = album ? DEFAULT_SEARCH_LIMIT : ARTIST_ONLY_SEARCH_LIMIT;
      const key = normalizeKey('release', artist, album);
      let candidates = await cachedOrSearch(key, () =>
        mbClient.searchReleaseCandidates(artist, album ?? null, searchLimit),
      );
      if (candidates.length === 0) {
        const altArtist = stripChannelSuffix(artist);
        if (altArtist) {
          const altKey = normalizeKey('release', altArtist, album);
          const altCandidates = await cachedOrSearch(altKey, () =>
            mbClient.searchReleaseCandidates(altArtist, album ?? null, searchLimit),
          );
          if (altCandidates.length > 0) candidates = altCandidates;
        }
      }
      ranked = scoreCandidates(artist, album, group.files.length, first.year ?? null, candidates);
    }
  } catch (error) {
    if (error instanceof MusicBrainzError) {
      return { ...group, status: FileStatus.LookupFailed, statusDetail: error.message };
    }
    throw error;
  }

  const decision = decide(ranked);
  const withCandidates: AlbumGroup = { ...group, candidates: ranked };

  if (decision.outcome === 'auto_apply' && decision.chosen) {
    try {
      await resolveGroupToProposed(mbClient, withCandidates, decision.chosen, false);
      return { ...withCandidates, status: FileStatus.AutoMatched, chosenReleaseId: decision.chosen.releaseId };
    } catch (error) {
      // Matched with high confidence but couldn't fetch the full release details
      // to build a proposal - surface as needs_review so the user can retry
      // rather than silently losing the match.
      const message = error instanceof Error ? error.message : String(error);
      return { ...withCandidates, status: FileStatus.NeedsReview, statusDetail: `matched but details fetch failed: ${message}` };
    }
  }
  if (decision.outcome === 'needs_review') return { ...withCandidates, status: FileStatus.NeedsReview };
  return { ...withCandidates, status: FileStatus.NoMatch };
}

/** Returns ranked candidates, or null if there's no usable artist/title to search
 * with at all. A group with exactly one file is queried by track title rather than
 * by album - a release search on the local "album" tag fails surprisingly often
 * here (streaming services commonly write album tags like "Song - Single" that
 * don't match MusicBrainz's actual release title), while a plain recording-title
 * search finds the right track directly. */
async function querySingleton(
  mbClient: MusicBrainzClient,
  group: AlbumGroup,
  first: AlbumGroup['files'][number],
): Promise<MbCandidate[] | null> {
  const artist = first.artist || group.bestGuessArtist;
  // The TITLE tag is very commonly blank for individually-tagged/singleton files,
  // even when the real title is sitting right there in the filename - falling
  // back to the filename guess is what parseFilename already exists for.
  let title = first.title || parseFilename(first.path).title;
  if (!artist || !title) return null;

  // Strip known upload/rip noise up front, independent of whether an
  // "Artist - Title" split below also applies.
  title = cleanNoiseText(title);

  let resolvedArtist = artist;

  // YouTube-sourced rips very often dump the whole "Artist - Track" video title
  // into just the TITLE tag, while the ARTIST tag holds the uploading channel's
  // name. The artist embedded in the title text is the one actually describing
  // the song, so when this pattern is detected it's preferred over the tag artist
  // outright, not just tried as a fallback after the tag artist fails.
  const restAfterArtist = title.slice(artist.length);
  const artistIsWholeWordPrefix = Boolean(restAfterArtist) && !/^[A-Za-z0-9]/.test(restAfterArtist);
  // A comma or "&" right after the artist name means more collaborator names
  // follow - that's the multi-artist-credit-dumped-into-title pattern, not "the
  // title repeats just this one artist's name".
  const continuesWithMoreArtists = [',', '&'].includes(restAfterArtist.trimStart().slice(0, 1));

  if (artist && artistIsWholeWordPrefix && !continuesWithMoreArtists && normalize(title).startsWith(normalize(artist))) {
    // The TITLE tag sometimes repeats the already-known artist name as a literal
    // prefix. When that artist name itself contains a hyphen (common for
    // Newgrounds-era handles like "F-777"), blindly dash-splitting the title
    // mistook the hyphen inside the artist's own name for the "Artist - Title"
    // separator. Stripping the already-known artist off as a literal prefix
    // sidesteps the dash-based heuristic entirely for this case.
    const remainder = title.slice(artist.length).replace(/^[ \-_:"']+|[ \-_:"']+$/g, '');
    if (remainder) title = remainder;
  } else {
    const [embeddedArtist, embeddedTitle] = splitArtistTitleText(title);
    if (embeddedArtist && embeddedTitle) {
      if (artist && normalize(embeddedTitle).includes(normalize(artist))) {
        // The classic Newgrounds Audio Portal convention runs the other way -
        // "Title - Artist" rather than "Artist - Title" - so the known-good tag
        // artist turning up on the *title* side of the split means the two sides
        // are swapped, not that the split found a better artist.
        title = embeddedArtist;
      } else {
        resolvedArtist = embeddedArtist;
        title = embeddedTitle;
      }
    }
  }

  const key = normalizeKey('recording', resolvedArtist, title);
  let candidates = await cachedOrSearch(key, () => mbClient.searchRecordingCandidates(resolvedArtist, title!));

  if (candidates.length === 0) {
    const altArtist = stripChannelSuffix(resolvedArtist);
    if (altArtist) {
      const altKey = normalizeKey('recording', altArtist, title);
      const altCandidates = await cachedOrSearch(altKey, () => mbClient.searchRecordingCandidates(altArtist, title!));
      if (altCandidates.length > 0) {
        candidates = altCandidates;
        resolvedArtist = altArtist;
      }
    }
  }

  // Prefer a folder-structure-derived album guess over the file's own existing
  // album tag as the disambiguation hint here specifically - this branch exists
  // to (re)search and potentially CORRECT a file's metadata, so trusting an
  // existing, possibly-wrong tag value would just reinforce whatever it was
  // already (mis)tagged as.
  const albumHint = parseFilename(first.path).album || group.bestGuessAlbum;
  let ranked = scoreCandidates(resolvedArtist, title, null, first.year ?? null, candidates, albumHint);

  if (BARE_VERSION_SUFFIX_RE.test(title) && decide(ranked).outcome !== 'auto_apply') {
    const strippedTitle = title.replace(BARE_VERSION_SUFFIX_RE, '').trim();
    if (strippedTitle) {
      const strippedKey = normalizeKey('recording', resolvedArtist, strippedTitle);
      const strippedCandidates = await cachedOrSearch(strippedKey, () =>
        mbClient.searchRecordingCandidates(resolvedArtist, strippedTitle),
      );
      const strippedRanked = scoreCandidates(resolvedArtist, strippedTitle, null, first.year ?? null, strippedCandidates, albumHint);
      if (decide(strippedRanked).outcome === 'auto_apply') {
        ranked = strippedRanked;
      }
    }
  }

  return ranked;
}
