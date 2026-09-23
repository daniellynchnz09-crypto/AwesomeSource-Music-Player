/**
 * TypeScript re-port of `legacy-desktop-tagger/musictagger/grouping/album_grouper.py`.
 * Clusters a flat list of TrackMetadata into AlbumGroups before any online query.
 * See that file's module doc comment for the full strategy (folder-first,
 * tag-confirmed, track-number-clustered fallback for untagged files, singleton
 * queries at track level) - every threshold and real-world edge case fix documented
 * there is preserved here.
 */

import { pathParent } from '../model/libraryPath';
import { AlbumGroup, TrackMetadata } from '../model/types';
import { parseFilename, resolveTrackNumber } from '../tags/filenameParser';

// An artist cluster within a mixed, untagged folder needs at least this many
// members to be treated as "probably one release" rather than a loose file that
// happens to share the folder with unrelated tracks.
const MIN_CLUSTER_SIZE = 2;

// A dominant tagged group within a folder needs at least this many members,
// sharing resolvable track numbers, before a lone same-folder outlier is trusted
// enough to reclaim into it.
const MIN_DOMINANT_GROUP_SIZE = 3;

function normalize(text: string | null | undefined): string {
  return (text ?? '').trim().replace(/\s+/g, ' ').toLowerCase();
}

function artistGuess(track: TrackMetadata): string {
  if (track.artist) return normalize(track.artist);
  const guess = parseFilename(track.path);
  return normalize(guess.artist);
}

function hasTrackNumber(track: TrackMetadata): boolean {
  return resolveTrackNumber(track) !== undefined;
}

function singleton(track: TrackMetadata, flagged = false): AlbumGroup {
  const guess = parseFilename(track.path);
  return {
    groupKey: `singleton::${track.path}`,
    files: [track],
    bestGuessArtist: track.artist ?? guess.artist,
    bestGuessAlbum: track.album ?? guess.album,
    isSingleton: true,
    flaggedInconsistent: flagged,
  };
}

/**
 * Clusters untagged files within one folder by matching artist AND a track number
 * (tag or filename-derived) - see album_grouper.py's `_group_untagged` doc comment
 * for why both signals together are needed (a folder can hold either several
 * artists' loose singles, or one artist's assorted singles collected over years -
 * only shared track numbering tells "one real album" apart from either).
 */
function groupUntagged(folder: string | undefined, untagged: TrackMetadata[]): AlbumGroup[] {
  const byArtist = new Map<string, TrackMetadata[]>();
  const ungrouped: TrackMetadata[] = [];

  for (const track of untagged) {
    const guess = artistGuess(track);
    if (guess && hasTrackNumber(track)) {
      const list = byArtist.get(guess) ?? [];
      list.push(track);
      byArtist.set(guess, list);
    } else {
      ungrouped.push(track);
    }
  }

  const groups: AlbumGroup[] = [];
  for (const [artistKey, members] of byArtist) {
    if (members.length >= MIN_CLUSTER_SIZE) {
      groups.push({
        groupKey: `${folder}::${artistKey}`,
        files: members,
        bestGuessArtist: members[0].artist ?? artistKey,
      });
    } else {
      groups.push(...members.map((t) => singleton(t, true)));
    }
  }
  groups.push(...ungrouped.map((t) => singleton(t, true)));
  return groups;
}

/**
 * A single mistagged file can sit in the same folder as a large, otherwise
 * sequentially-numbered compilation and still get split into its own group purely
 * because it carries a different (wrong) album tag - see album_grouper.py's
 * `_reclaim_sequential_outliers` doc comment for the real observed case (a
 * various-artists compilation ripped as tracks 1-17, with track 16 alone mistagged,
 * sitting exactly at the one gap in an otherwise unbroken 1-15,17 sequence).
 */
function reclaimSequentialOutliers(tagGroups: Map<string, TrackMetadata[]>): Map<string, TrackMetadata[]> {
  const candidates = [...tagGroups.entries()].filter(([, members]) => members.length >= MIN_DOMINANT_GROUP_SIZE);
  if (candidates.length === 0) return tagGroups;

  const reclaimed = new Map<string, TrackMetadata[]>();
  for (const [key, members] of tagGroups) reclaimed.set(key, [...members]);

  for (const [outlierKey, outlierMembers] of tagGroups) {
    if (outlierMembers.length !== 1) continue;
    const track = outlierMembers[0];
    // The filename's own position - not resolveTrackNumber()'s tag-first result -
    // is checked here: a mistagged file's own track_number TAG is exactly what
    // can't be trusted (real example: tagged track_number=7 matching the WRONG
    // album, while the filename "16 Trill Clinton.mp3" still correctly encodes its
    // real position in the original rip batch).
    const number = parseFilename(track.path).trackNumber;
    if (number === undefined) continue;

    for (const [dominantKey, dominantMembers] of candidates) {
      if (dominantKey === outlierKey) continue;
      const dominantNumbers = new Set(
        dominantMembers.map(resolveTrackNumber).filter((n): n is number => n !== undefined),
      );
      if (dominantNumbers.size === 0) continue;
      const lo = Math.min(...dominantNumbers);
      const hi = Math.max(...dominantNumbers);
      if (number >= lo && number <= hi && !dominantNumbers.has(number)) {
        reclaimed.get(dominantKey)!.push(track);
        const outlierList = reclaimed.get(outlierKey)!;
        reclaimed.set(outlierKey, outlierList.filter((t) => t !== track));
        break;
      }
    }
  }

  return new Map([...reclaimed.entries()].filter(([, members]) => members.length > 0));
}

export function groupIntoAlbums(tracks: TrackMetadata[]): AlbumGroup[] {
  const byFolder = new Map<string | undefined, TrackMetadata[]>();
  for (const track of tracks) {
    const folder = pathParent(track.path);
    const list = byFolder.get(folder) ?? [];
    list.push(track);
    byFolder.set(folder, list);
  }

  const groups: AlbumGroup[] = [];
  for (const [folder, folderTracks] of byFolder) {
    if (folderTracks.length === 1) {
      groups.push(singleton(folderTracks[0]));
      continue;
    }

    const tagged = folderTracks.filter((t) => t.album);
    const untagged = folderTracks.filter((t) => !t.album);

    // Grouped by album name alone, not also by (album_artist or artist) - see
    // album_grouper.py's identical comment: a various-artists compilation very
    // commonly has album_artist set on only SOME tracks, and requiring agreement
    // there fragments one real album purely because of a tagging gap.
    const tagGroups = new Map<string, TrackMetadata[]>();
    for (const track of tagged) {
      const key = normalize(track.album);
      const list = tagGroups.get(key) ?? [];
      list.push(track);
      tagGroups.set(key, list);
    }

    const reclaimedGroups = reclaimSequentialOutliers(tagGroups);

    for (const members of reclaimedGroups.values()) {
      // Prefers a member that actually carries an explicit album_artist tag
      // ("Various Artists") over an arbitrary first file.
      const representative = members.find((t) => t.albumArtist) ?? members[0];
      groups.push({
        groupKey: `${folder}::${normalize(representative.album)}`,
        files: members,
        bestGuessArtist: representative.albumArtist ?? representative.artist,
        bestGuessAlbum: representative.album,
      });
    }

    if (untagged.length > 0) groups.push(...groupUntagged(folder, untagged));
  }

  return groups;
}
