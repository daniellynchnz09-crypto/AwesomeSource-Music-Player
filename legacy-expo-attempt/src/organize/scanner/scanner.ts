/**
 * TypeScript/`expo-file-system` re-port of
 * `legacy-desktop-tagger/musictagger/scanner/filesystem.py`. Lets the user pick a
 * library folder via Android's Storage Access Framework (persisted permission) and
 * recursively walks it for audio files.
 *
 * Builds each file's `LibraryPath` (see `model/libraryPath.ts`) by accumulating
 * folder names during its own recursive walk, rather than trying to parse one back
 * out of the file's `content://` URI - SAF document URIs are provider-dependent and
 * not reliably parseable as a hierarchical path string in general, but this walk
 * already knows the real hierarchy as it descends.
 *
 * Unverified on a real device yet - `Directory.pickDirectoryAsync`'s persisted-
 * permission behavior across app restarts needs confirming once a dev client build
 * exists (tracked in Claude/To Do list.md).
 */

import { Directory, File } from 'expo-file-system';
import { TrackMetadata } from '../model/types';

const AUDIO_EXTENSIONS = new Set(['.mp3', '.flac', '.m4a', '.aac', '.ogg', '.wav']);

/** Opens the SAF folder picker and returns the chosen root directory (persisted
 * access). Throws if the user cancels the picker. */
export async function pickLibraryFolder(): Promise<Directory> {
  return Directory.pickDirectoryAsync();
}

/**
 * Recursively walks `root`, yielding one bare `TrackMetadata` per audio file found
 * (uri + path + fileSizeBytes only - tag reading is a separate stage, same
 * scan/read split as the Python original, so the UI can show scan progress
 * incrementally on large libraries before the slower tag-reading pass starts).
 *
 * Skips hidden/AppleDouble files (e.g. macOS's "._Track.mp3" resource-fork
 * siblings, seen in the user's real library) and zero-byte files, matching
 * filesystem.py's "Skips locked/hidden/zero-byte files" rule.
 */
export function scanFolder(root: Directory): TrackMetadata[] {
  const results: TrackMetadata[] = [];
  walk(root, '', results);
  return results;
}

function walk(dir: Directory, relativePrefix: string, results: TrackMetadata[]): void {
  let entries: (Directory | File)[];
  try {
    entries = dir.list();
  } catch {
    // A folder that can't be listed (permission revoked mid-scan, provider
    // hiccup) is skipped rather than aborting the whole scan - matches the
    // Python original's "one bad entry can't crash a batch" philosophy.
    return;
  }

  for (const entry of entries) {
    if (entry instanceof Directory) {
      if (entry.name.startsWith('.')) continue;
      walk(entry, joinPath(relativePrefix, entry.name), results);
      continue;
    }

    if (entry.name.startsWith('.')) continue;
    const extension = extensionOf(entry.name);
    if (!AUDIO_EXTENSIONS.has(extension)) continue;

    let sizeBytes = 0;
    try {
      sizeBytes = entry.size;
    } catch {
      continue;
    }
    if (sizeBytes === 0) continue;

    results.push({
      uri: entry.uri,
      path: joinPath(relativePrefix, entry.name),
      fileSizeBytes: sizeBytes,
    });
  }
}

function joinPath(prefix: string, name: string): string {
  return prefix ? `${prefix}/${name}` : name;
}

function extensionOf(name: string): string {
  const dot = name.lastIndexOf('.');
  return dot > 0 ? name.slice(dot).toLowerCase() : '';
}
