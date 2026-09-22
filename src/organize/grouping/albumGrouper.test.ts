/** Ported from `legacy-desktop-tagger/tests/test_album_grouper.py` - same
 * regression cases and reasoning. */

import { pathName } from '../model/libraryPath';
import { TrackMetadata } from '../model/types';
import { groupIntoAlbums } from './albumGrouper';

function track(path: string, overrides: Partial<TrackMetadata> = {}): TrackMetadata {
  return { uri: `content://fake/${path}`, path, ...overrides };
}

test('tagged tracks in one folder form one group', () => {
  const tracks = [
    track('Music/Daft Punk/Discovery/01 One More Time.mp3', { artist: 'Daft Punk', album: 'Discovery' }),
    track('Music/Daft Punk/Discovery/02 Aerodynamic.mp3', { artist: 'Daft Punk', album: 'Discovery' }),
    track('Music/Daft Punk/Discovery/03 Digital Love.mp3', { artist: 'Daft Punk', album: 'Discovery' }),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(1);
  expect(groups[0].files).toHaveLength(3);
  expect(groups[0].isSingleton).toBeFalsy();
});

test('disagreeing album tags split into subgroups', () => {
  const tracks = [
    track('Music/Mixed/01.mp3', { artist: 'Artist A', album: 'Album A' }),
    track('Music/Mixed/02.mp3', { artist: 'Artist A', album: 'Album A' }),
    track('Music/Mixed/03.mp3', { artist: 'Artist B', album: 'Album B' }),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(2);
  expect(groups.map((g) => g.files.length).sort()).toEqual([1, 2]);
  const albumAGroup = groups.find((g) => g.bestGuessAlbum === 'Album A')!;
  expect(albumAGroup.files).toHaveLength(2);
});

test('compilation with inconsistent album artist tagging still forms one group', () => {
  const tracks = [
    track('Music/EDM/01 Track1.mp3', { artist: 'Freddy Todd & NOTE', albumArtist: 'Various Artists', album: 'MEANWHILE...' }),
    track('Music/EDM/02 Track2.mp3', { artist: 'ConRank', albumArtist: 'Various Artists', album: 'MEANWHILE...' }),
    track('Music/EDM/04 Track4.mp3', { artist: 'Liquid Stranger & Space Jesus', album: 'MEANWHILE...' }),
    track('Music/EDM/06 Track6.mp3', { artist: 'Shlump', album: 'MEANWHILE...' }),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(1);
  expect(groups[0].files).toHaveLength(4);
  expect(groups[0].bestGuessArtist).toBe('Various Artists');
});

test('lone mistagged outlier in a real sequential gap reclaimed into the compilation', () => {
  const tracks = [
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17].map((n) =>
      track(`Music/EDM/${String(n).padStart(2, '0')} Track${n}.mp3`, {
        artist: 'Various', albumArtist: 'Various Artists', album: 'MEANWHILE...',
      }),
    ),
    track('Music/EDM/16 Trill Clinton.mp3', {
      artist: 'Mr. Bill & Tha Fruitbat', albumArtist: 'Mr. Bill', album: 'Corrective Scene Surgery', trackNumber: 16,
    }),
  ];
  const groups = groupIntoAlbums(tracks);
  const meanwhileGroup = groups.find((g) => g.bestGuessAlbum === 'MEANWHILE...')!;
  expect(meanwhileGroup.files).toHaveLength(17);
  expect(meanwhileGroup.files.some((f) => pathName(f.path) === '16 Trill Clinton.mp3')).toBe(true);
  expect(groups.some((g) => g.bestGuessAlbum === 'Corrective Scene Surgery')).toBe(false);
});

test('outlier group with multiple files is not reclaimed', () => {
  const tracks = [
    ...[1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 17].map((n) =>
      track(`Music/EDM/${String(n).padStart(2, '0')} Track${n}.mp3`, {
        artist: 'Various', albumArtist: 'Various Artists', album: 'MEANWHILE...',
      }),
    ),
    track('Music/EDM/16 Other A.mp3', { artist: 'X', album: 'Some Other EP', trackNumber: 16 }),
    track('Music/EDM/18 Other B.mp3', { artist: 'X', album: 'Some Other EP', trackNumber: 18 }),
  ];
  const groups = groupIntoAlbums(tracks);
  const otherGroup = groups.find((g) => g.bestGuessAlbum === 'Some Other EP')!;
  expect(otherGroup.files).toHaveLength(2);
});

test('single file folder is singleton', () => {
  const tracks = [track('Music/Loose/only_track.mp3', { artist: 'Solo Artist', title: 'Solo Song' })];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(1);
  expect(groups[0].isSingleton).toBe(true);
});

test('untagged but consistent filenames group together', () => {
  const tracks = [
    track('Music/SomeRip/01 Muse - Uprising.mp3'),
    track('Music/SomeRip/02 Muse - Starlight.mp3'),
    track('Music/SomeRip/03 Muse - Supermassive Black Hole.mp3'),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(1);
  expect(groups[0].bestGuessArtist).toBe('muse');
  expect(groups[0].files).toHaveLength(3);
});

test('untagged same artist without track numbers stays ungrouped', () => {
  const tracks = [
    track('Music/SomeRip/Muse - Uprising.mp3'),
    track('Music/SomeRip/Muse - Starlight.mp3'),
    track('Music/SomeRip/Muse - Supermassive Black Hole.mp3'),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(3);
  expect(groups.every((g) => g.isSingleton)).toBe(true);
});

test('wildly inconsistent untagged folder splits to flagged singletons', () => {
  const tracks = [
    track('Music/Mess/Muse - Uprising.mp3'),
    track('Music/Mess/Coldplay - Yellow.mp3'),
    track('Music/Mess/Adele - Hello.mp3'),
  ];
  const groups = groupIntoAlbums(tracks);
  expect(groups).toHaveLength(3);
  expect(groups.every((g) => g.isSingleton && g.flaggedInconsistent)).toBe(true);
});

test('same artist cluster survives a busy mixed folder', () => {
  const tipperAlbumTracks = Array.from({ length: 13 }, (_, i) => i + 1).map((n) =>
    track(`Music/EDM/${n} Track${n}.mp3`, { artist: 'Tippermusic' }),
  );
  const tipperLooseSingles = Array.from({ length: 5 }, (_, i) => i + 1).map((n) =>
    track(`Music/EDM/Tipper - Single${n}.mp3`, { artist: 'Tippermusic' }),
  );
  const otherLooseSingles = [
    track('Music/EDM/Some Song.mp3', { artist: 'Liquid Stranger' }),
    track('Music/EDM/Another Song.mp3', { artist: 'Space Laces' }),
    track('Music/EDM/Yet Another.mp3', { artist: 'Dubloadz' }),
    track('Music/EDM/no_tags_at_all.wav'),
  ];
  const tracks = [...tipperAlbumTracks, ...tipperLooseSingles, ...otherLooseSingles];
  const groups = groupIntoAlbums(tracks);

  const tipperGroup = groups.find((g) => g.bestGuessArtist === 'Tippermusic')!;
  expect(tipperGroup.files).toHaveLength(13);
  expect(tipperGroup.isSingleton).toBeFalsy();
  expect(tipperGroup.files.every((f) => /^(?:[1-9]|1[0-3]) /.test(pathName(f.path)))).toBe(true);

  const otherGroups = groups.filter((g) => g !== tipperGroup);
  expect(otherGroups.every((g) => g.isSingleton)).toBe(true);
  expect(otherGroups).toHaveLength(9);
});
