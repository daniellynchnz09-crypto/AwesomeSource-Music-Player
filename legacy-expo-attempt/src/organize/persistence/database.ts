/**
 * TypeScript/`expo-sqlite` re-port of the persistence design in
 * `legacy-desktop-tagger/musictagger/persistence/db.py` and its Kotlin/Room
 * re-implementation in `legacy-android-native-attempt/`. Same schema shape, same
 * undo-log-as-primary-reversibility-mechanism design (see db.py's module doc
 * comment) - the SQL is deliberately close to line-for-line comparable across all
 * three versions.
 *
 * Kept as a set of plain async functions over one shared `SQLiteDatabase` handle
 * rather than a class, since `expo-sqlite` is most idiomatically used this way (a
 * `useSQLiteContext()` hook wires up live-updating queries at the UI layer later -
 * not needed yet, since there's no UI consuming this yet).
 */

import * as SQLite from 'expo-sqlite';
import * as Crypto from 'expo-crypto';
import { FileStatus, LibraryType, MetadataSource } from '../model/types';

const DATABASE_NAME = 'awesomesource.db';

const SCHEMA = `
CREATE TABLE IF NOT EXISTS tracks (
  path TEXT PRIMARY KEY NOT NULL,
  uri TEXT NOT NULL,
  fileFormat TEXT,
  artist TEXT,
  albumArtist TEXT,
  album TEXT,
  title TEXT,
  trackNumber INTEGER,
  trackTotal INTEGER,
  discNumber INTEGER,
  discTotal INTEGER,
  year INTEGER,
  genre TEXT,
  durationSeconds REAL,
  hasCoverArt INTEGER NOT NULL DEFAULT 0,
  coverArtMime TEXT,
  composer TEXT,
  libraryType TEXT,
  fileSizeBytes INTEGER NOT NULL DEFAULT 0,
  source TEXT NOT NULL,
  status TEXT NOT NULL,
  statusDetail TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS track_artist_credits (
  trackPath TEXT NOT NULL,
  artistName TEXT NOT NULL,
  role TEXT NOT NULL,
  PRIMARY KEY (trackPath, artistName, role)
);

CREATE TABLE IF NOT EXISTS libraries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  type TEXT NOT NULL,
  rootUri TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS sidecar_metadata (
  path TEXT PRIMARY KEY NOT NULL,
  artist TEXT,
  albumArtist TEXT,
  album TEXT,
  title TEXT,
  trackNumber INTEGER,
  year INTEGER,
  genre TEXT,
  composer TEXT,
  coverArtUri TEXT
);

CREATE TABLE IF NOT EXISTS scan_sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  rootFoldersJson TEXT NOT NULL,
  startedAt TEXT NOT NULL,
  completedAt TEXT,
  status TEXT NOT NULL DEFAULT 'in_progress'
);

CREATE TABLE IF NOT EXISTS tag_changes (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  sessionId INTEGER,
  filePath TEXT NOT NULL,
  fieldName TEXT NOT NULL,
  oldValue TEXT,
  newValue TEXT,
  batchId TEXT NOT NULL,
  appliedAt TEXT NOT NULL,
  undone INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS mb_query_cache (
  queryKey TEXT PRIMARY KEY NOT NULL,
  responseJson TEXT NOT NULL,
  cachedAt TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS gemini_grounding_cache (
  queryKey TEXT PRIMARY KEY NOT NULL,
  responseJson TEXT NOT NULL,
  cachedAt TEXT NOT NULL
);
`;

let dbPromise: Promise<SQLite.SQLiteDatabase> | null = null;

export function getDatabase(): Promise<SQLite.SQLiteDatabase> {
  if (!dbPromise) {
    dbPromise = SQLite.openDatabaseAsync(DATABASE_NAME).then(async (db) => {
      await db.execAsync('PRAGMA foreign_keys = ON;');
      await db.execAsync(SCHEMA);
      return db;
    });
  }
  return dbPromise;
}

function now(): string {
  return new Date().toISOString();
}

// --- tracks ---------------------------------------------------------------

export interface TrackRow {
  path: string;
  uri: string;
  fileFormat: string | null;
  artist: string | null;
  albumArtist: string | null;
  album: string | null;
  title: string | null;
  trackNumber: number | null;
  trackTotal: number | null;
  discNumber: number | null;
  discTotal: number | null;
  year: number | null;
  genre: string | null;
  durationSeconds: number | null;
  hasCoverArt: number;
  coverArtMime: string | null;
  composer: string | null;
  libraryType: LibraryType | null;
  fileSizeBytes: number;
  source: MetadataSource;
  status: FileStatus;
  statusDetail: string;
}

export async function upsertTrack(row: TrackRow): Promise<void> {
  const db = await getDatabase();
  await db.runAsync(
    `INSERT INTO tracks (
      path, uri, fileFormat, artist, albumArtist, album, title, trackNumber, trackTotal,
      discNumber, discTotal, year, genre, durationSeconds, hasCoverArt, coverArtMime,
      composer, libraryType, fileSizeBytes, source, status, statusDetail
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(path) DO UPDATE SET
      uri=excluded.uri, fileFormat=excluded.fileFormat, artist=excluded.artist,
      albumArtist=excluded.albumArtist, album=excluded.album, title=excluded.title,
      trackNumber=excluded.trackNumber, trackTotal=excluded.trackTotal,
      discNumber=excluded.discNumber, discTotal=excluded.discTotal, year=excluded.year,
      genre=excluded.genre, durationSeconds=excluded.durationSeconds,
      hasCoverArt=excluded.hasCoverArt, coverArtMime=excluded.coverArtMime,
      composer=excluded.composer, libraryType=excluded.libraryType,
      fileSizeBytes=excluded.fileSizeBytes, source=excluded.source, status=excluded.status,
      statusDetail=excluded.statusDetail`,
    [
      row.path, row.uri, row.fileFormat, row.artist, row.albumArtist, row.album, row.title,
      row.trackNumber, row.trackTotal, row.discNumber, row.discTotal, row.year, row.genre,
      row.durationSeconds, row.hasCoverArt, row.coverArtMime, row.composer, row.libraryType,
      row.fileSizeBytes, row.source, row.status, row.statusDetail,
    ],
  );
}

export async function getTrackByPath(path: string): Promise<TrackRow | null> {
  const db = await getDatabase();
  return db.getFirstAsync<TrackRow>('SELECT * FROM tracks WHERE path = ?', [path]);
}

export async function getTracksByStatus(status: FileStatus): Promise<TrackRow[]> {
  const db = await getDatabase();
  return db.getAllAsync<TrackRow>('SELECT * FROM tracks WHERE status = ?', [status]);
}

export async function getAllTracks(): Promise<TrackRow[]> {
  const db = await getDatabase();
  return db.getAllAsync<TrackRow>('SELECT * FROM tracks ORDER BY path');
}

export async function getTrackCount(): Promise<number> {
  const db = await getDatabase();
  const row = await db.getFirstAsync<{ count: number }>('SELECT COUNT(*) as count FROM tracks');
  return row?.count ?? 0;
}

export async function deleteTrack(path: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM tracks WHERE path = ?', [path]);
  await db.runAsync('DELETE FROM track_artist_credits WHERE trackPath = ?', [path]);
}

export interface ArtistCreditRow {
  trackPath: string;
  artistName: string;
  role: string;
}

export async function setTrackArtistCredits(trackPath: string, credits: ArtistCreditRow[]): Promise<void> {
  const db = await getDatabase();
  await db.withTransactionAsync(async () => {
    await db.runAsync('DELETE FROM track_artist_credits WHERE trackPath = ?', [trackPath]);
    for (const credit of credits) {
      await db.runAsync(
        'INSERT INTO track_artist_credits (trackPath, artistName, role) VALUES (?, ?, ?)',
        [credit.trackPath, credit.artistName, credit.role],
      );
    }
  });
}

export async function getTrackArtistCredits(trackPath: string): Promise<ArtistCreditRow[]> {
  const db = await getDatabase();
  return db.getAllAsync<ArtistCreditRow>('SELECT * FROM track_artist_credits WHERE trackPath = ?', [trackPath]);
}

// --- libraries --------------------------------------------------------------

export interface LibraryRow {
  id: number;
  name: string;
  type: LibraryType;
  rootUri: string;
}

export async function upsertLibrary(library: Omit<LibraryRow, 'id'> & { id?: number }): Promise<number> {
  const db = await getDatabase();
  if (library.id !== undefined) {
    await db.runAsync('UPDATE libraries SET name = ?, type = ?, rootUri = ? WHERE id = ?', [
      library.name, library.type, library.rootUri, library.id,
    ]);
    return library.id;
  }
  const result = await db.runAsync('INSERT INTO libraries (name, type, rootUri) VALUES (?, ?, ?)', [
    library.name, library.type, library.rootUri,
  ]);
  return result.lastInsertRowId;
}

export async function getLibraries(): Promise<LibraryRow[]> {
  const db = await getDatabase();
  return db.getAllAsync<LibraryRow>('SELECT * FROM libraries ORDER BY name');
}

export async function deleteLibrary(id: number): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM libraries WHERE id = ?', [id]);
}

// --- sidecar metadata (tag-less formats like WAV) ---------------------------

export interface SidecarMetadataRow {
  path: string;
  artist: string | null;
  albumArtist: string | null;
  album: string | null;
  title: string | null;
  trackNumber: number | null;
  year: number | null;
  genre: string | null;
  composer: string | null;
  coverArtUri: string | null;
}

export async function upsertSidecarMetadata(row: SidecarMetadataRow): Promise<void> {
  const db = await getDatabase();
  await db.runAsync(
    `INSERT INTO sidecar_metadata (path, artist, albumArtist, album, title, trackNumber, year, genre, composer, coverArtUri)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(path) DO UPDATE SET
       artist=excluded.artist, albumArtist=excluded.albumArtist, album=excluded.album,
       title=excluded.title, trackNumber=excluded.trackNumber, year=excluded.year,
       genre=excluded.genre, composer=excluded.composer, coverArtUri=excluded.coverArtUri`,
    [
      row.path, row.artist, row.albumArtist, row.album, row.title, row.trackNumber,
      row.year, row.genre, row.composer, row.coverArtUri,
    ],
  );
}

export async function getSidecarMetadata(path: string): Promise<SidecarMetadataRow | null> {
  const db = await getDatabase();
  return db.getFirstAsync<SidecarMetadataRow>('SELECT * FROM sidecar_metadata WHERE path = ?', [path]);
}

// --- undo log / scan sessions -----------------------------------------------
// Mirrors db.py's tag_changes/scan_sessions tables - the primary reversibility
// mechanism, so "don't corrupt the user's files" holds in this stack too.

export async function startScanSession(rootFolders: string[]): Promise<number> {
  const db = await getDatabase();
  const result = await db.runAsync(
    "INSERT INTO scan_sessions (rootFoldersJson, startedAt, status) VALUES (?, ?, 'in_progress')",
    [JSON.stringify(rootFolders), now()],
  );
  return result.lastInsertRowId;
}

export async function completeScanSession(sessionId: number): Promise<void> {
  const db = await getDatabase();
  await db.runAsync("UPDATE scan_sessions SET completedAt = ?, status = 'completed' WHERE id = ?", [
    now(), sessionId,
  ]);
}

export function newBatchId(): string {
  return Crypto.randomUUID();
}

export interface TagChange {
  fieldName: string;
  oldValue: string | null;
  newValue: string | null;
}

export async function recordTagChanges(
  sessionId: number | null,
  filePath: string,
  changes: TagChange[],
  batchId: string,
): Promise<void> {
  if (changes.length === 0) return;
  const db = await getDatabase();
  const appliedAt = now();
  await db.withTransactionAsync(async () => {
    for (const change of changes) {
      await db.runAsync(
        `INSERT INTO tag_changes (sessionId, filePath, fieldName, oldValue, newValue, batchId, appliedAt)
         VALUES (?, ?, ?, ?, ?, ?, ?)`,
        [sessionId, filePath, change.fieldName, change.oldValue, change.newValue, batchId, appliedAt],
      );
    }
  });
}

export async function getLastBatchId(): Promise<string | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync<{ batchId: string }>(
    'SELECT batchId FROM tag_changes WHERE undone = 0 ORDER BY id DESC LIMIT 1',
  );
  return row?.batchId ?? null;
}

export async function getRecentBatchIds(limit = 20): Promise<string[]> {
  const db = await getDatabase();
  const rows = await db.getAllAsync<{ batchId: string }>(
    'SELECT DISTINCT batchId FROM tag_changes WHERE undone = 0 ORDER BY id DESC LIMIT ?',
    [limit],
  );
  return rows.map((r) => r.batchId);
}

export interface TagChangeRow extends TagChange {
  filePath: string;
}

/** Rows to replay for an undo, excluding cover_art (binary field, not restored
 * this way) - mirrors db.py's `undo_batch` query exactly. The actual write-back to
 * disk is the caller's job (needs the tag reader/writer, not built yet - see
 * Claude/To Do list.md), same separation of concerns as the Kotlin/Python versions. */
export async function getUndoableChanges(batchId: string): Promise<TagChangeRow[]> {
  const db = await getDatabase();
  return db.getAllAsync<TagChangeRow>(
    "SELECT filePath, fieldName, oldValue, newValue FROM tag_changes WHERE batchId = ? AND undone = 0 AND fieldName != 'cover_art'",
    [batchId],
  );
}

export async function markBatchUndone(batchId: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('UPDATE tag_changes SET undone = 1 WHERE batchId = ?', [batchId]);
}

// --- query caches (MusicBrainz + Gemini) ------------------------------------
// A cached result is a verbatim replay of whatever the matching logic decided at
// cache time - if that logic later changes, stale entries keep serving the pre-fix
// answer until cleared. No automatic invalidation tied to code changes; exposed as
// a manual action, same as db.py's `clear_query_cache`.

export async function getCachedMbQuery(queryKey: string): Promise<string | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync<{ responseJson: string }>(
    'SELECT responseJson FROM mb_query_cache WHERE queryKey = ?', [queryKey],
  );
  return row?.responseJson ?? null;
}

export async function setCachedMbQuery(queryKey: string, responseJson: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync(
    `INSERT INTO mb_query_cache (queryKey, responseJson, cachedAt) VALUES (?, ?, ?)
     ON CONFLICT(queryKey) DO UPDATE SET responseJson = excluded.responseJson, cachedAt = excluded.cachedAt`,
    [queryKey, responseJson, now()],
  );
}

export async function clearMbQueryCache(): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM mb_query_cache');
}

export async function getCachedGeminiResponse(queryKey: string): Promise<string | null> {
  const db = await getDatabase();
  const row = await db.getFirstAsync<{ responseJson: string }>(
    'SELECT responseJson FROM gemini_grounding_cache WHERE queryKey = ?', [queryKey],
  );
  return row?.responseJson ?? null;
}

export async function setCachedGeminiResponse(queryKey: string, responseJson: string): Promise<void> {
  const db = await getDatabase();
  await db.runAsync(
    `INSERT INTO gemini_grounding_cache (queryKey, responseJson, cachedAt) VALUES (?, ?, ?)
     ON CONFLICT(queryKey) DO UPDATE SET responseJson = excluded.responseJson, cachedAt = excluded.cachedAt`,
    [queryKey, responseJson, now()],
  );
}

export async function clearGeminiCache(): Promise<void> {
  const db = await getDatabase();
  await db.runAsync('DELETE FROM gemini_grounding_cache');
}
