/** Ported from `legacy-desktop-tagger/tests/test_filename_parser.py` - same cases,
 * adapted from `pathlib.Path` to plain library-relative path strings. */

import {
  cleanNoiseText,
  parseFilename,
  splitArtistTitleText,
  stripLeadingTrackNumber,
} from './filenameParser';

test('structured artist album title', () => {
  const guess = parseFilename('Music/Pink Floyd/The Wall/03 - Another Brick in the Wall.mp3');
  expect(guess.confidence).toBe('structured');
  expect(guess.artist).toBe('Pink Floyd');
  expect(guess.album).toBe('The Wall');
  expect(guess.title).toBe('Another Brick in the Wall');
  expect(guess.trackNumber).toBe(3);
});

test('structured artist dash album folder', () => {
  const guess = parseFilename('Music/Daft Punk - Discovery/02 One More Time.mp3');
  expect(guess.confidence).toBe('structured');
  expect(guess.artist).toBe('Daft Punk');
  expect(guess.album).toBe('Discovery');
  expect(guess.title).toBe('One More Time');
});

test('structured artist title loose file', () => {
  const guess = parseFilename('Downloads/Coldplay - Yellow.mp3');
  expect(guess.confidence).toBe('structured');
  expect(guess.artist).toBe('Coldplay');
  expect(guess.title).toBe('Yellow');
});

test('strips noise tokens', () => {
  const guess = parseFilename('Downloads/Coldplay - Yellow [Explicit] (Remastered 2011).mp3');
  expect(guess.artist).toBe('Coldplay');
  expect(guess.title).toBe('Yellow');
});

test('gibberish filename falls back to loose search', () => {
  const guess = parseFilename('Downloads/xY7_2gK9-titlefragment_final(2).mp3');
  expect(guess.confidence).toBe('loose');
  expect(guess.searchText).toBeDefined();
  expect(guess.searchText).toContain('titlefragment');
  expect(guess.searchText).not.toContain('xY7');
  expect(guess.searchText?.toLowerCase()).not.toContain('final');
});

test('pure random id filename yields no search text', () => {
  const guess = parseFilename('Downloads/8f3a91cd_004.mp3');
  expect(guess.confidence).toBe('loose');
  expect(guess.searchText).toBeFalsy();
});

test('track number survives loose fallback', () => {
  const guess = parseFilename('Downloads/10 Track10.mp3');
  expect(guess.confidence).toBe('loose');
  expect(guess.trackNumber).toBe(10);
});

test('split artist title text recovers embedded title', () => {
  const [artist, title] = splitArtistTitleText('Tipper - Baleen');
  expect(artist).toBe('Tipper');
  expect(title).toBe('Baleen');
});

test('split artist title text strips visualizer suffix first', () => {
  const [artist, title] = splitArtistTitleText('Tipper - Air Biscuits | Insolito (4K music visualizer)');
  expect(artist).toBe('Tipper');
  expect(title).toBe('Air Biscuits');
});

test('split artist title text rejects implausible split', () => {
  const [artist, title] = splitArtistTitleText('just one plain title with no dash');
  expect(artist).toBeNull();
  expect(title).toBeNull();
});

test('strip leading track number recovers number and clean title', () => {
  const [number, title] = stripLeadingTrackNumber('1 Goldilocks Zone');
  expect(number).toBe(1);
  expect(title).toBe('Goldilocks Zone');
});

test('strip leading track number leaves plain titles untouched', () => {
  const [number, title] = stripLeadingTrackNumber('Goldilocks Zone');
  expect(number).toBeNull();
  expect(title).toBe('Goldilocks Zone');
});

test('strip leading track number does not consume a purely numeric title', () => {
  const [number, title] = stripLeadingTrackNumber('13');
  expect(number).toBeNull();
  expect(title).toBe('13');
});

test('disc subfolder is not mistaken for an artist folder', () => {
  const guess = parseFilename(
    "Music/Jeff Wayne's War of the Worlds/Act 1/Jeff Wayne - Horsell Common and the Heatray.mp3",
  );
  expect(guess.artist).toBe('Jeff Wayne');
  expect(guess.album).toBe("Jeff Wayne's War of the Worlds");
  expect(guess.title).toBe('Horsell Common and the Heatray');
});

test('disc subfolder pattern matches common variants', () => {
  for (const subfolder of ['Disc 2', 'CD1', 'Part 3', 'Volume 1', 'Vol. 4']) {
    const guess = parseFilename(`My Album/${subfolder}/Some Artist - Some Title.mp3`);
    expect(guess.album).toBe('My Album');
    expect(guess.artist).toBe('Some Artist');
  }
});

test('clean noise text strips newgrounds id suffix', () => {
  expect(cleanNoiseText("Dr. Finkelfracken's Cure (ID: 383158)")).toBe("Dr. Finkelfracken's Cure");
});

test('clean noise text strips curly brace tags', () => {
  expect(cleanNoiseText('{dj-N} Rain Full')).toBe('Rain Full');
});

test('clean noise text strips free and original mix tags', () => {
  expect(cleanNoiseText('Song Title (free)')).toBe('Song Title');
  expect(cleanNoiseText('Song Title (Original Mix)')).toBe('Song Title');
});

test('clean noise text strips wrapping dashes', () => {
  expect(cleanNoiseText('-Clownparty remix- (ID: 286138)')).toBe('Clownparty remix');
});
