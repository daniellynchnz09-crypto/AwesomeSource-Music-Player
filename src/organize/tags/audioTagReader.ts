/**
 * Reads embedded tags from an audio file - the TypeScript/React Native equivalent
 * of `legacy-desktop-tagger/musictagger/tags/reader.py`'s `mutagen`-based
 * abstraction. Backed by `@missingcore/audio-metadata` (MP3 ID3v1/v2, FLAC, MP4/M4A;
 * New Architecture compatible, verified via `expo-doctor`).
 *
 * Known gap vs. the Python original: this library's field set is smaller than
 * `mutagen`'s - no genre, composer, disc number, track-total, or duration. Genre/
 * composer/disc-number simply won't be read-only-available for now (they stay
 * `undefined` and the organization pipeline treats them the same as "tag absent");
 * duration needs a separate source (the eventual `expo-audio` player can report it
 * once a track loads - not wired up yet, see Claude/To Do list.md). WAV is not
 * supported at all by this library (consistent with the Python original's own
 * "WAV is best-effort read-only" note, and with Claude/MUSIC ORGANIZATION.md's
 * sidecar-metadata design for exactly this case - see
 * `organize/persistence/database.ts`'s `sidecar_metadata` table).
 *
 * Read-only: writing corrected tags back into a file's embedded metadata is a
 * separate, harder problem with no verified library yet - tracked as an open spike
 * in Claude/To Do list.md, same treatment as Chromaprint fingerprinting. Until
 * that exists, "accepting" a match can only update the app's own database, not the
 * file itself - still useful (the app is the source of truth for browsing/playback
 * either way), just not yet round-tripped back into the file for use by other
 * players, unlike the Python tool's atomic-write pattern.
 */

import { getAudioMetadata } from '@missingcore/audio-metadata';
import { pathSuffix } from '../model/libraryPath';

const SUPPORTED_EXTENSIONS = new Set(['.mp3', '.flac', '.m4a', '.mp4', '.aac']);

const REQUESTED_KEYS = ['album', 'albumArtist', 'artist', 'artwork', 'name', 'track', 'year'] as const;

export interface ReadTagsResult {
  fileFormat: string;
  artist?: string;
  albumArtist?: string;
  album?: string;
  title?: string;
  trackNumber?: number;
  year?: number;
  hasCoverArt: boolean;
  /** A `content://`/`file://` reference to embedded artwork, when present - see
   * the library's `artwork` field; kept as-is rather than decoded here. */
  coverArtUri?: string;
}

export type ReadTagsOutcome =
  | { status: 'ok'; tags: ReadTagsResult }
  | { status: 'unsupported_format' }
  /** Mirrors the Python original's "one corrupt file can't crash a batch" rule -
   * caught here and reported, not thrown, so a scan over hundreds of files doesn't
   * abort on the first bad one. */
  | { status: 'unreadable'; error: string };

/** `path` is only used to check the file extension is one this library supports;
 * `uri` is what's actually read. Formats outside `SUPPORTED_EXTENSIONS` (WAV, OGG)
 * return `unsupported_format` immediately rather than attempting a read that would
 * just throw - callers should route those straight to sidecar metadata instead. */
export async function readTags(uri: string, path: string): Promise<ReadTagsOutcome> {
  const extension = pathSuffix(path);
  if (!SUPPORTED_EXTENSIONS.has(extension)) {
    return { status: 'unsupported_format' };
  }

  try {
    const { format, metadata } = await getAudioMetadata(uri, REQUESTED_KEYS);
    return {
      status: 'ok',
      tags: {
        fileFormat: format,
        artist: metadata.artist || undefined,
        albumArtist: metadata.albumArtist || undefined,
        album: metadata.album || undefined,
        title: metadata.name || undefined,
        trackNumber: metadata.track || undefined,
        year: metadata.year || undefined,
        hasCoverArt: Boolean(metadata.artwork),
        coverArtUri: metadata.artwork || undefined,
      },
    };
  } catch (error) {
    return { status: 'unreadable', error: error instanceof Error ? error.message : String(error) };
  }
}
