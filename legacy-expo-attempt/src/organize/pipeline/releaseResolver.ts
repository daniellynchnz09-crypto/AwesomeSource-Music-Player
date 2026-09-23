/**
 * TypeScript re-port of
 * `legacy-desktop-tagger/musictagger/metadata_sources/release_resolver.py`.
 * Resolves a chosen MusicBrainz candidate into per-file proposed [TrackMetadata],
 * by fetching the authoritative tracklist (for a full release) and cover art.
 * Shared by both the auto-apply path (`pipeline/queryGroup.ts`) and a future
 * review-panel "Accept this candidate" action, so a match is only ever built the
 * same way regardless of who picked it.
 */

import * as fuzz from 'fuzzball';
import { fetchFullImage } from '../metadata/coverArtClient';
import { MusicBrainzClient } from '../metadata/musicBrainzClient';
import {
  parseFilename,
  resolveTrackNumber,
  splitArtistTitleText,
  stripLeadingTrackNumber,
} from '../tags/filenameParser';
import { AlbumGroup, MbCandidate, MetadataSource, TrackMetadata } from '../model/types';

const TITLE_MATCH_THRESHOLD = 65.0;

// MusicBrainz's own titles for remixes are real but inconsistently styled (e.g.
// "He's a Pirate (F-777 ReMiX)"). Normalizes any "(<name> remix)"/"(<name>
// re-mix)" suffix into a single consistent "(<Name> Remix)" form. Deliberately
// scoped to the literal word "remix" only, not a bare "mix" - "(Original Mix)",
// "(Radio Mix)" etc. are legitimate version descriptors, not a remixer named
// Original/Radio, and must never be rewritten into a fake "(X Remix)" credit.
const REMIX_TITLE_RE = /^(.*?)\s*\(([^)]*?)\s*re-?mix\)\s*$/i;

/** Normalizes a "(<name> remix)"-style title suffix, e.g. "He's a Pirate (F-777
 * ReMiX)" -> "He's a Pirate (F-777 Remix)". Applied to whatever title actually
 * ends up written, not to search queries. Returns the title unchanged when it
 * isn't a recognized remix-credit suffix. */
export function formatRemixTitle(title: string | undefined): string | undefined {
  if (!title) return title;
  const match = REMIX_TITLE_RE.exec(title);
  if (!match) return title;
  const song = match[1].trim();
  const remixer = match[2].trim();
  if (!song || !remixer) return title;
  return `${song} (${remixer} Remix)`;
}

function normalize(text: string | null | undefined): string {
  return (text ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

function yearFromDate(date: string | null | undefined): number | undefined {
  return date && /^\d{4}/.test(date) ? parseInt(date.slice(0, 4), 10) : undefined;
}

interface ReleaseTrack {
  title: string;
  artist: string | null;
}

/** The best guess at a file's own song title, independent of the release - used to
 * match it against the release's real tracklist by content, since track position
 * is often missing or unreliable on its own. */
function localTitleGuess(track: TrackMetadata): string | undefined {
  if (track.title) {
    const [, embeddedTitle] = splitArtistTitleText(track.title);
    let title = embeddedTitle ?? track.title;
    // Some rips bake the track position directly into the TITLE tag itself (e.g.
    // "1 Goldilocks Zone") - left in, that drags down the fuzzy match against the
    // release's real, clean tracklist title ("Goldilocks Zone").
    [, title] = stripLeadingTrackNumber(title);
    return title;
  }
  return parseFilename(track.path).title;
}

/** Finds which release position's title best matches a given local title, treating
 * content (the actual song title) as stronger evidence of position than any number
 * scraped from a tag or filename. Returns undefined if nothing clears
 * TITLE_MATCH_THRESHOLD, rather than guessing. */
function bestMatchingPosition(
  localTitle: string | undefined,
  tracksByPosition: Record<number, ReleaseTrack>,
  exclude: Set<number> = new Set(),
): number | undefined {
  if (!localTitle || Object.keys(tracksByPosition).length === 0) return undefined;
  const normalizedLocal = normalize(localTitle);
  let bestPosition: number | undefined;
  let bestScore = 0;
  for (const [positionStr, info] of Object.entries(tracksByPosition)) {
    const position = Number(positionStr);
    if (exclude.has(position)) continue;
    const score = fuzz.WRatio(normalizedLocal, normalize(info.title), { full_process: false });
    if (score > bestScore) {
      bestScore = score;
      bestPosition = position;
    }
  }
  return bestPosition !== undefined && bestScore >= TITLE_MATCH_THRESHOLD ? bestPosition : undefined;
}

/**
 * Matches each local file to the release position whose title it resembles most,
 * rather than assuming position order.
 *
 * Many real-world rips carry no track number anywhere. Falling back to
 * sorted-enumerate order in that situation effectively assigns positions at
 * random, silently pairing the wrong title (and the wrong per-track artist) with
 * the wrong file. An explicit tag track_number is trusted outright when present;
 * otherwise the best-scoring, not-yet-claimed position by title similarity wins; a
 * filename-derived track number is only the last resort, tried after title
 * matching rather than before it, since a real title comparison is stronger
 * evidence than a number scraped from a filename.
 */
function matchFilesToPositions(
  files: TrackMetadata[],
  tracksByPosition: Record<number, ReleaseTrack>,
): Map<string, number> {
  const assigned = new Map<string, number>();
  const usedPositions = new Set<number>();
  const remaining: TrackMetadata[] = [];

  // Pass 1: an explicit tag track_number is authoritative - claim it outright.
  for (const track of files) {
    if (track.trackNumber !== undefined && track.trackNumber in tracksByPosition) {
      assigned.set(track.path, track.trackNumber);
      usedPositions.add(track.trackNumber);
    } else {
      remaining.push(track);
    }
  }

  // Pass 2: fuzzy-match everyone else by title.
  const stillRemaining: TrackMetadata[] = [];
  for (const track of remaining) {
    const position = bestMatchingPosition(localTitleGuess(track), tracksByPosition, usedPositions);
    if (position !== undefined) {
      assigned.set(track.path, position);
      usedPositions.add(position);
    } else {
      stillRemaining.push(track);
    }
  }

  // Pass 3: last resort - a filename-derived track number, if any.
  for (const track of stillRemaining) {
    const number = resolveTrackNumber(track);
    if (number !== undefined && number in tracksByPosition && !usedPositions.has(number)) {
      assigned.set(track.path, number);
      usedPositions.add(number);
    }
  }

  return assigned;
}

/**
 * Returns a Map of file path -> proposed [TrackMetadata] for every file in the
 * group. `fetchCoverArt=false` skips the Cover Art Archive request entirely -
 * cover fields fall back to whatever the file already has. Cover art isn't part of
 * MusicBrainz's own rate-limited API, but it's still a real network round trip;
 * during a bulk lookup pass that extra latency lands squarely on the slowest part
 * of the whole operation for no benefit if nothing shows a preview yet. Fetch it
 * JIT once the user actually accepts a match instead.
 */
export async function resolveGroupToProposed(
  mbClient: MusicBrainzClient,
  group: AlbumGroup,
  chosen: MbCandidate,
  fetchCoverArt = true,
): Promise<Map<string, TrackMetadata>> {
  if (group.isSingleton || chosen.isRecording) {
    const track = group.files[0];
    // A recording is linked to the release it appeared on - fetching that
    // release's info is what fills in the album field for a singleton match at
    // all; without this, even a correct, confident match would leave "album"
    // blank forever.
    const releaseInfo = chosen.releaseId
      ? await mbClient.getReleaseTracklist(chosen.releaseId)
      : { artist: null, album: null, year: null, tracks: {} };
    const coverUri = fetchCoverArt && chosen.releaseId ? await fetchFullImage(chosen.releaseId) : null;

    const tracksByPosition: Record<number, ReleaseTrack> = releaseInfo.tracks;
    let trackNumber = bestMatchingPosition(chosen.title || track.title, tracksByPosition);
    if (trackNumber === undefined) trackNumber = resolveTrackNumber(track);

    const proposed: TrackMetadata = {
      ...track,
      artist: chosen.artistCredit || track.artist,
      albumArtist: releaseInfo.artist ?? track.albumArtist,
      album: releaseInfo.album ?? track.album,
      title: formatRemixTitle(chosen.title || track.title),
      trackNumber,
      year: yearFromDate(chosen.firstReleaseDate) ?? releaseInfo.year ?? track.year,
      hasCoverArt: Boolean(coverUri) || track.hasCoverArt,
      coverArtMime: coverUri ? 'image/jpeg' : track.coverArtMime,
      source: MetadataSource.OnlineLookup,
    };
    return new Map([[track.path, proposed]]);
  }

  const release = await mbClient.getReleaseTracklist(chosen.releaseId);
  const coverUri = fetchCoverArt ? await fetchFullImage(chosen.releaseId) : null;
  const tracksByPosition: Record<number, ReleaseTrack> = release.tracks;
  const releaseArtist = release.artist;

  const positionByPath = matchFilesToPositions(group.files, tracksByPosition);

  const proposedMap = new Map<string, TrackMetadata>();
  for (const track of group.files) {
    const position = positionByPath.get(track.path);
    const info = position !== undefined ? tracksByPosition[position] : undefined;
    proposedMap.set(track.path, {
      ...track,
      // A various-artists compilation's per-track artist is only known once this
      // file is matched to its real position - without a confident match, keep
      // the file's own existing artist tag rather than overwriting it with the
      // release-level credit (often "Various Artists" or the label name).
      artist: info?.artist || track.artist || releaseArtist || undefined,
      albumArtist: releaseArtist ?? track.albumArtist,
      album: release.album ?? track.album,
      title: formatRemixTitle(info?.title || track.title),
      trackNumber: position ?? track.trackNumber,
      year: release.year ?? track.year,
      hasCoverArt: Boolean(coverUri) || track.hasCoverArt,
      coverArtMime: coverUri ? 'image/jpeg' : track.coverArtMime,
      source: MetadataSource.OnlineLookup,
    });
  }
  return proposedMap;
}
