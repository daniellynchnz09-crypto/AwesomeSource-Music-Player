/**
 * The top-level orchestrator - the TypeScript equivalent of the Python original's
 * `workers/` package (scan_worker.py, query_worker.py, write_worker.py) combined,
 * minus the Qt-specific threading (React Native's JS is single-threaded anyway;
 * network calls are naturally async). Ties every already-ported piece together:
 * scan -> read tags (or route to sidecar/filename-guess) -> group into albums ->
 * query MusicBrainz -> score -> (optionally) Gemini-ground an ambiguous result ->
 * persist.
 *
 * Not yet exercised against a real device/library - see Claude/To Do list.md.
 * Deliberately does NOT write corrected tags back into files yet (no verified
 * write-capable tagging library exists - see `tags/audioTagReader.ts`'s doc
 * comment); "applying" a match currently only updates the app's own database.
 */

import { Directory } from 'expo-file-system';
import { AlbumGroup, FileStatus, LibraryType, MetadataSource, TrackMetadata } from '../model/types';
import { scanFolder } from '../scanner/scanner';
import { readTags } from '../tags/audioTagReader';
import { parseFilename } from '../tags/filenameParser';
import { groupIntoAlbums } from '../grouping/albumGrouper';
import { deriveCredits } from '../tags/edmCreditParser';
import { MusicBrainzClient } from '../metadata/musicBrainzClient';
import { queryGroup } from './queryGroup';
import * as gemini from '../metadata/geminiGroundingClient';
import {
  completeScanSession,
  getSidecarMetadata,
  setTrackArtistCredits,
  startScanSession,
  upsertTrack,
  TrackRow,
} from '../persistence/database';

export type OrganizePhase = 'scanning' | 'reading_tags' | 'grouping' | 'querying';

export interface OrganizeProgress {
  phase: OrganizePhase;
  processed: number;
  total: number;
}

export interface OrganizeOptions {
  musicBrainzContact: string | null;
  geminiApiKey: string | null;
  libraryType?: LibraryType;
  onProgress?: (progress: OrganizeProgress) => void;
}

/** Scans `root`, reads/guesses every file's metadata, groups into albums, and
 * queries MusicBrainz (with a Gemini double-check for ambiguous results) for each
 * group - persisting every track's resolved status to the database as it goes, so
 * a scan interrupted partway through doesn't lose the work already done. */
export async function organizeLibrary(root: Directory, options: OrganizeOptions): Promise<void> {
  const sessionId = await startScanSession([root.uri]);

  const bareFiles = scanFolder(root);
  options.onProgress?.({ phase: 'scanning', processed: bareFiles.length, total: bareFiles.length });

  const tracks: TrackMetadata[] = [];
  for (let i = 0; i < bareFiles.length; i++) {
    const track = await resolveInitialMetadata(bareFiles[i], options.libraryType);
    tracks.push(track);
    await persistTrack(track);
    options.onProgress?.({ phase: 'reading_tags', processed: i + 1, total: bareFiles.length });
  }

  const readable = tracks.filter((t) => t.status !== FileStatus.Unreadable);
  const groups = groupIntoAlbums(readable);
  options.onProgress?.({ phase: 'grouping', processed: groups.length, total: groups.length });

  const mbClient = new MusicBrainzClient(options.musicBrainzContact);
  for (let i = 0; i < groups.length; i++) {
    await processGroup(mbClient, groups[i], options.geminiApiKey);
    options.onProgress?.({ phase: 'querying', processed: i + 1, total: groups.length });
  }

  await completeScanSession(sessionId);
}

/** Reads embedded tags when the format supports them; falls back to sidecar
 * metadata (WAV etc. - Claude/MUSIC ORGANIZATION.md's "list document" concept) or a
 * filename guess otherwise. One corrupt/unsupported file can't crash the whole
 * scan - it's marked Unreadable and skipped, matching the Python original's rule. */
async function resolveInitialMetadata(bare: TrackMetadata, libraryType: LibraryType | undefined): Promise<TrackMetadata> {
  const tagResult = await readTags(bare.uri, bare.path);

  if (tagResult.status === 'ok') {
    return {
      ...bare,
      ...tagResult.tags,
      libraryType,
      source: MetadataSource.EmbeddedTags,
      status: FileStatus.TagsRead,
    };
  }

  if (tagResult.status === 'unreadable') {
    return { ...bare, libraryType, status: FileStatus.Unreadable, statusDetail: tagResult.error };
  }

  // unsupported_format (WAV, OGG): sidecar metadata first, then a filename guess.
  const sidecar = await getSidecarMetadata(bare.path);
  if (sidecar) {
    return {
      ...bare,
      artist: sidecar.artist ?? undefined,
      albumArtist: sidecar.albumArtist ?? undefined,
      album: sidecar.album ?? undefined,
      title: sidecar.title ?? undefined,
      trackNumber: sidecar.trackNumber ?? undefined,
      year: sidecar.year ?? undefined,
      genre: sidecar.genre ?? undefined,
      composer: sidecar.composer ?? undefined,
      hasCoverArt: Boolean(sidecar.coverArtUri),
      libraryType,
      source: MetadataSource.EmbeddedTags,
      status: FileStatus.TagsRead,
    };
  }

  const guess = parseFilename(bare.path);
  return {
    ...bare,
    artist: guess.artist,
    album: guess.album,
    title: guess.title,
    trackNumber: guess.trackNumber,
    libraryType,
    source: MetadataSource.FilenameGuess,
    status: guess.confidence === 'structured' ? FileStatus.Pending : FileStatus.InsufficientInfo,
    statusDetail: guess.confidence === 'loose' ? `loose filename guess: ${guess.searchText ?? '(no usable text)'}` : '',
  };
}

async function processGroup(mbClient: MusicBrainzClient, group: AlbumGroup, geminiApiKey: string | null): Promise<void> {
  let resolved = await queryGroup(mbClient, group);

  // For an ambiguous (needs_review) result, ask Gemini to double-check against
  // the local file evidence - the "LLM double-check" pass from
  // Claude/MUSIC ORGANIZATION.md. A confident Gemini pick is treated the same as
  // an auto-applied MusicBrainz match; anything else is left as needs_review for
  // a human, exactly as before.
  if (resolved.status === FileStatus.NeedsReview && geminiApiKey && resolved.candidates?.length) {
    const first = resolved.files[0];
    const verdict = await gemini.groundMatch(
      geminiApiKey,
      {
        artist: resolved.bestGuessArtist ?? first.artist ?? null,
        album: resolved.bestGuessAlbum ?? first.album ?? null,
        title: first.title ?? null,
        trackCount: resolved.files.length || null,
        year: first.year ?? null,
        filenameHint: parseFilename(first.path).searchText ?? null,
      },
      resolved.candidates,
    );
    if (verdict?.confident && verdict.chosen) {
      resolved = {
        ...resolved,
        status: FileStatus.AutoMatched,
        chosenReleaseId: verdict.chosen.releaseId,
        statusDetail: `Gemini-grounded: ${verdict.reasoning}`,
      };
    }
  }

  for (const track of resolved.files) {
    const withStatus: TrackMetadata = {
      ...track,
      status: resolved.status,
      statusDetail: resolved.statusDetail ?? track.statusDetail,
      source: resolved.status === FileStatus.AutoMatched ? MetadataSource.OnlineLookup : track.source,
    };
    await persistTrack(withStatus);
  }
}

async function persistTrack(track: TrackMetadata): Promise<void> {
  const row: TrackRow = {
    path: track.path,
    uri: track.uri,
    fileFormat: track.fileFormat ?? null,
    artist: track.artist ?? null,
    albumArtist: track.albumArtist ?? null,
    album: track.album ?? null,
    title: track.title ?? null,
    trackNumber: track.trackNumber ?? null,
    trackTotal: track.trackTotal ?? null,
    discNumber: track.discNumber ?? null,
    discTotal: track.discTotal ?? null,
    year: track.year ?? null,
    genre: track.genre ?? null,
    durationSeconds: track.durationSeconds ?? null,
    hasCoverArt: track.hasCoverArt ? 1 : 0,
    coverArtMime: track.coverArtMime ?? null,
    composer: track.composer ?? null,
    libraryType: track.libraryType ?? null,
    fileSizeBytes: track.fileSizeBytes ?? 0,
    source: track.source ?? MetadataSource.EmbeddedTags,
    status: track.status ?? FileStatus.Pending,
    statusDetail: track.statusDetail ?? '',
  };
  await upsertTrack(row);

  if (track.artist) {
    const credits = deriveCredits(track.artist, track.title);
    await setTrackArtistCredits(
      track.path,
      credits.map((c) => ({ trackPath: track.path, artistName: c.name, role: c.role })),
    );
  }
}
