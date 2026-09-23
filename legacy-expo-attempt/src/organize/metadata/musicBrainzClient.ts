/**
 * TypeScript/`fetch` re-port of
 * `legacy-desktop-tagger/musictagger/metadata_sources/musicbrainz_client.py` (via
 * its Kotlin/Retrofit re-implementation in `legacy-android-native-attempt/`).
 * Talks to MusicBrainz's public JSON web service directly (`ws/2/*?fmt=json`).
 *
 * Every field on the response DTOs is optional/defaulted on purpose, mirroring
 * musicbrainz_client.py's "defensive `.get()` access throughout ... so a schema
 * surprise degrades to a blank field, not a crash" rule. **Not yet verified against
 * a live response** - tracked in Claude/To Do list.md.
 */

import { MbCandidate } from '../model/types';

const BASE_URL = 'https://musicbrainz.org/ws/2/';
const RETRY_DELAYS_MS = [0, 1000, 2000, 4000];
const MIN_INTERVAL_MS = 1100;

export class MusicBrainzError extends Error {}

// --- response shapes ---------------------------------------------------------

interface ArtistCreditDto {
  name?: string;
  joinphrase?: string;
}

interface ReleaseGroupDto {
  'primary-type'?: string;
}

interface MediumDto {
  'track-count'?: number;
  track?: TrackDto[];
}

interface TrackDto {
  position?: number;
  title?: string;
  recording?: { id?: string; title?: string; 'artist-credit'?: ArtistCreditDto[] };
}

interface ReleaseDto {
  id?: string;
  title?: string;
  date?: string;
  'artist-credit'?: ArtistCreditDto[];
  media?: MediumDto[];
  'release-group'?: ReleaseGroupDto;
}

interface RecordingDto {
  id?: string;
  title?: string;
  'artist-credit'?: ArtistCreditDto[];
  releases?: ReleaseDto[];
}

interface ReleaseSearchResponse {
  releases?: ReleaseDto[];
}

interface RecordingSearchResponse {
  recordings?: RecordingDto[];
}

function artistCreditPhrase(credits: ArtistCreditDto[] | undefined): string {
  return (credits ?? []).map((c) => (c.name ?? '') + (c.joinphrase ?? '')).join('');
}

// --- rate limiting + retry ----------------------------------------------------
// MusicBrainz's API etiquette requires ~1 request/second. A single shared
// promise chain serializes every call through this client instance, however many
// concurrent callers there are - the JS equivalent of the Kotlin version's
// Mutex-guarded delay.

let lastCallAtMs = 0;
let callQueue: Promise<void> = Promise.resolve();

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function rateLimitedFetch(url: string, userAgent: string): Promise<Response> {
  const run = callQueue.then(async () => {
    const waitFor = MIN_INTERVAL_MS - (Date.now() - lastCallAtMs);
    if (waitFor > 0) await delay(waitFor);
    lastCallAtMs = Date.now();
  });
  callQueue = run.catch(() => undefined);
  await run;
  return fetch(url, { headers: { 'User-Agent': userAgent } });
}

async function withRetry<T>(fn: () => Promise<T>): Promise<T> {
  let lastError: unknown;
  for (const delayMs of RETRY_DELAYS_MS) {
    if (delayMs > 0) await delay(delayMs);
    try {
      return await fn();
    } catch (e) {
      lastError = e;
    }
  }
  throw new MusicBrainzError(lastError instanceof Error ? lastError.message : 'MusicBrainz request failed');
}

// --- Lucene query building -----------------------------------------------------
// Runs with every field required (strict, AND'd, quoted) first, and only if that
// finds nothing, retries with a loose OR-style query. See musicbrainz_client.py's
// `_search_strict_first` doc comment: an unquoted OR query can be hijacked when a
// stylized artist name's words are also common in song titles (its cited real
// example: artist "SVDDEN DEATH" + title "Demonic Curse" returning only unrelated
// "Death Curse" songs).

function escapeLucene(value: string): string {
  return value.replace(/([+\-!(){}[\]^"~*?:\\/])/g, '\\$1');
}

function luceneQuery(strict: boolean, fields: Record<string, string>): string {
  const entries = Object.entries(fields);
  return strict
    ? entries.map(([field, value]) => `${field}:"${escapeLucene(value)}"`).join(' AND ')
    : entries.map(([field, value]) => `${field}:(${escapeLucene(value)})`).join(' ');
}

export class MusicBrainzClient {
  private userAgent: string;

  constructor(contact: string | null) {
    this.userAgent = `AwesomeSource/0.1 (${contact || 'no contact info provided'})`;
  }

  private async get<T>(path: string, params: Record<string, string>): Promise<T> {
    const query = new URLSearchParams({ ...params, fmt: 'json' }).toString();
    const response = await withRetry(() => rateLimitedFetch(`${BASE_URL}${path}?${query}`, this.userAgent));
    if (!response.ok) throw new MusicBrainzError(`MusicBrainz returned ${response.status}`);
    return response.json() as Promise<T>;
  }

  async searchReleaseCandidates(artist: string, album: string | null, limit = 5): Promise<MbCandidate[]> {
    const fields: Record<string, string> = { artist };
    if (album) fields.release = album;
    const strict = await this.get<ReleaseSearchResponse>('release/', {
      query: luceneQuery(true, fields),
      limit: String(limit),
    });
    const releases = strict.releases?.length
      ? strict.releases
      : (
          await this.get<ReleaseSearchResponse>('release/', {
            query: luceneQuery(false, fields),
            limit: String(limit),
          })
        ).releases ?? [];
    return releases.map(releaseToCandidate);
  }

  async searchRecordingCandidates(artist: string, title: string, limit = 5): Promise<MbCandidate[]> {
    const fields = { artist, recording: title };
    const strict = await this.get<RecordingSearchResponse>('recording/', {
      query: luceneQuery(true, fields),
      limit: String(limit),
    });
    const recordings = strict.recordings?.length
      ? strict.recordings
      : (
          await this.get<RecordingSearchResponse>('recording/', {
            query: luceneQuery(false, fields),
            limit: String(limit),
          })
        ).recordings ?? [];
    return recordings.map(recordingToCandidate);
  }

  async getReleaseTracklist(releaseId: string): Promise<{
    artist: string | null;
    album: string | null;
    year: number | null;
    tracks: Record<number, { title: string; artist: string | null }>;
  }> {
    const release = await this.get<ReleaseDto>(`release/${releaseId}`, { inc: 'recordings+artist-credits' });
    const tracks: Record<number, { title: string; artist: string | null }> = {};
    for (const medium of release.media ?? []) {
      for (const track of medium.track ?? []) {
        if (track.position === undefined) continue;
        const title = track.title ?? track.recording?.title;
        if (!title) continue;
        tracks[track.position] = {
          title,
          artist: artistCreditPhrase(track.recording?.['artist-credit']) || null,
        };
      }
    }
    const year =
      release.date && /^\d{4}/.test(release.date) ? parseInt(release.date.slice(0, 4), 10) : null;
    return {
      artist: artistCreditPhrase(release['artist-credit']) || null,
      album: release.title ?? null,
      year,
      tracks,
    };
  }
}

function releaseToCandidate(release: ReleaseDto): MbCandidate {
  const trackCount = (release.media ?? []).reduce((sum, m) => sum + (m['track-count'] ?? 0), 0);
  return {
    releaseId: release.id ?? '',
    title: release.title ?? '',
    artistCredit: artistCreditPhrase(release['artist-credit']),
    firstReleaseDate: release.date ?? null,
    trackCount: trackCount > 0 ? trackCount : null,
    score: 0,
  };
}

// A track very commonly appears on both a standalone single AND a various-artists
// compilation; MusicBrainz doesn't guarantee any particular order for a recording's
// linked releases, so picking index 0 essentially picks at random. Prefer
// "Album"/"EP" over "Single"/anything else, matching musicbrainz_client.py's
// identical ranking.
const RELEASE_TYPE_RANK: Record<string, number> = { Album: 0, EP: 1, Single: 2 };
const DEFAULT_RELEASE_TYPE_RANK = 3;

function rankOfReleaseType(release: ReleaseDto): number {
  const type = release['release-group']?.['primary-type'];
  return type !== undefined && type in RELEASE_TYPE_RANK ? RELEASE_TYPE_RANK[type] : DEFAULT_RELEASE_TYPE_RANK;
}

function bestLinkedRelease(releases: ReleaseDto[]): ReleaseDto | undefined {
  if (releases.length === 0) return undefined;
  return [...releases].sort((a, b) => {
    const rankDiff = rankOfReleaseType(a) - rankOfReleaseType(b);
    if (rankDiff !== 0) return rankDiff;
    // A blank/missing date sorts last among equally-ranked releases.
    const aBlank = !a.date;
    const bBlank = !b.date;
    if (aBlank !== bBlank) return aBlank ? 1 : -1;
    return (a.date ?? '').localeCompare(b.date ?? '');
  })[0];
}

function recordingToCandidate(recording: RecordingDto): MbCandidate {
  const best = bestLinkedRelease(recording.releases ?? []);
  return {
    releaseId: best?.id ?? recording.id ?? '',
    title: recording.title ?? '',
    artistCredit: artistCreditPhrase(recording['artist-credit']),
    firstReleaseDate: best?.date ?? null,
    isRecording: true,
    album: best?.title ?? null,
    score: 0,
  };
}
