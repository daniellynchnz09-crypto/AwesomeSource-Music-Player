/**
 * New logic (no Python original) - directly encodes the exact examples from
 * Claude/MUSIC ORGANIZATION.md's EDM naming-convention section, so this test suite
 * doubles as executable documentation of what the user actually specified.
 */

import { ArtistCreditRole } from '../model/types';
import { deriveCredits } from './edmCreditParser';

test('solo track is a single artist credit', () => {
  expect(deriveCredits('Eptic', 'Octane')).toEqual([{ name: 'Eptic', role: ArtistCreditRole.Artist }]);
});

test('two artist collab splits into one credit each', () => {
  expect(deriveCredits('Zomboy & Eptic', 'Bop It')).toEqual([
    { name: 'Zomboy', role: ArtistCreditRole.Artist },
    { name: 'Eptic', role: ArtistCreditRole.Artist },
  ]);
});

test('three plus artist collab with comma list ending in ampersand', () => {
  expect(deriveCredits('Virtual Riot, Barely Alive, PhaseOne & Myro', 'Rampage')).toEqual([
    { name: 'Virtual Riot', role: ArtistCreditRole.Artist },
    { name: 'Barely Alive', role: ArtistCreditRole.Artist },
    { name: 'PhaseOne', role: ArtistCreditRole.Artist },
    { name: 'Myro', role: ArtistCreditRole.Artist },
  ]);
});

test('same artist VIP keeps the original artist credit', () => {
  expect(deriveCredits('Herobust', 'Blockbuster VIP')).toEqual([{ name: 'Herobust', role: ArtistCreditRole.Artist }]);
});

test('collab VIP by one member credits the VIP artist and composers the originals', () => {
  expect(deriveCredits('Excision and Space Laces', 'Crusaders (Space Laces VIP)')).toEqual([
    { name: 'Space Laces', role: ArtistCreditRole.Artist },
    { name: 'Excision', role: ArtistCreditRole.Composer },
    { name: 'Space Laces', role: ArtistCreditRole.Composer },
  ]);
});

test('remix credits the remixer as artist and original as composer', () => {
  expect(deriveCredits('SVDDEN DEATH & Yakz', 'Rock Like This (Oddprophet Remix)')).toEqual([
    { name: 'Oddprophet', role: ArtistCreditRole.Artist },
    { name: 'SVDDEN DEATH', role: ArtistCreditRole.Composer },
    { name: 'Yakz', role: ArtistCreditRole.Composer },
  ]);
});

test('multi remixer list ending in ampersand credits every remixer as artist', () => {
  expect(deriveCredits('SVDDEN DEATH', 'Rings of Pluto (Blankface & Decimate Remix)')).toEqual([
    { name: 'Blankface', role: ArtistCreditRole.Artist },
    { name: 'Decimate', role: ArtistCreditRole.Artist },
    { name: 'SVDDEN DEATH', role: ArtistCreditRole.Composer },
  ]);
});
