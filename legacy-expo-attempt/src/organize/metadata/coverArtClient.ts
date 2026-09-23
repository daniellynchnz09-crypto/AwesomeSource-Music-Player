/**
 * TypeScript re-port of
 * `legacy-desktop-tagger/musictagger/metadata_sources/cover_art_client.py`.
 * Cover Art Archive lookups keyed by MusicBrainz release ID. A 404 (no art
 * archived) is common/expected, not an error - callers proceed with tag fields only.
 *
 * Downloads straight to a cache file via `expo-file-system`'s `File.downloadFileAsync`
 * rather than returning bytes/a data URI - avoids base64-encoding image data in JS
 * (React Native's runtime doesn't guarantee `btoa`/`atob`), and a `file://` URI is
 * what an `<Image>` component wants anyway.
 */

import { Directory, File, Paths } from 'expo-file-system';

const CACHE_DIR = new Directory(Paths.cache, 'cover-art');

function ensureCacheDir(): void {
  if (!CACHE_DIR.exists) CACHE_DIR.create({ intermediates: true });
}

async function downloadToCache(url: string, filename: string): Promise<string | null> {
  ensureCacheDir();
  const destination = new File(CACHE_DIR, filename);
  if (destination.exists) return destination.uri;

  try {
    const file = await File.downloadFileAsync(url, destination, { idempotent: true });
    return file.uri;
  } catch {
    // downloadFileAsync rejects with an UnableToDownload error (message includes
    // the status code) on any non-2xx response, including the very common "no art
    // archived for this release" 404 - not distinguishable from a real network
    // failure without parsing the message, so both are treated the same way
    // (return null, tags-only) rather than throwing, matching the Python
    // original's "404 is expected, not an error" rule.
    return null;
  }
}

/** Fetches the front-cover thumbnail (500px) for a release - used for UI previews
 * (review panel, candidate picker) where a full-resolution image isn't needed. */
export async function fetchFrontThumbnail(releaseId: string): Promise<string | null> {
  return downloadToCache(`https://coverartarchive.org/release/${releaseId}/front-500`, `${releaseId}-thumb.jpg`);
}

/** Fetches the full-resolution front cover - only called once a match is actually
 * committed (see `pipeline/releaseResolver.ts`), to avoid downloading large images
 * for candidates that get rejected during review. */
export async function fetchFullImage(releaseId: string): Promise<string | null> {
  return downloadToCache(`https://coverartarchive.org/release/${releaseId}/front`, `${releaseId}-full.jpg`);
}
