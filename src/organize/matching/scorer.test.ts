/**
 * Ported from `legacy-desktop-tagger/tests/test_scorer.py` - same regression
 * cases, same reasoning in each comment. Unlike the Kotlin port's `FuzzyMatch` (a
 * from-scratch reimplementation), this version runs on `fuzzball`, an actual npm
 * port of the same fuzzywuzzy/rapidfuzz family the Python original uses - so scores
 * should track much more closely, and these tests can actually run for real.
 */

import { MbCandidate } from '../model/types';
import { decide, scoreCandidate, scoreCandidates } from './scorer';

function candidate(overrides: Partial<MbCandidate> & Pick<MbCandidate, 'releaseId' | 'title' | 'artistCredit'>): MbCandidate {
  return { score: 0, ...overrides };
}

test('case mismatch does not tank the score', () => {
  const c = candidate({ releaseId: 'a', title: 'Kill Off', artistCredit: 'Ecraze' });
  const score = scoreCandidate('ECRAZE', 'KILL OFF', null, null, c);
  expect(score).toBeGreaterThan(95.0);
});

test('clear winner auto applies', () => {
  const candidates = [
    candidate({ releaseId: 'good', title: 'Discovery', artistCredit: 'Daft Punk', trackCount: 14 }),
    candidate({ releaseId: 'bad', title: 'Some Other Album', artistCredit: 'Someone Else', trackCount: 8 }),
  ];
  const ranked = scoreCandidates('Daft Punk', 'Discovery', 14, null, candidates);
  const { outcome, chosen } = decide(ranked);
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('good');
});

test('multiple editions of the same album still auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'us-cd', title: 'Discovery', artistCredit: 'Daft Punk', firstReleaseDate: '2001-03-12', trackCount: 14 }),
    candidate({ releaseId: 'eu-cd', title: 'Discovery', artistCredit: 'Daft Punk', firstReleaseDate: '2001-02-26', trackCount: 14 }),
    candidate({ releaseId: 'digital', title: 'Discovery', artistCredit: 'Daft Punk', firstReleaseDate: '2014', trackCount: 14 }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Daft Punk', 'Discovery', 14, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen).not.toBeNull();
});

test('near tied candidates go to review not auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'a', title: 'Greatest Hits', artistCredit: 'Queen', trackCount: 17 }),
    candidate({ releaseId: 'b', title: 'Greatest Hits II', artistCredit: 'Queen', trackCount: 17 }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Queen', 'Greatest Hits', 17, null, candidates));
  expect(['needs_review', 'no_match']).toContain(outcome);
  expect(chosen).toBeNull();
});

test('weak matches are no match', () => {
  const candidates = [candidate({ releaseId: 'x', title: 'Completely Unrelated', artistCredit: 'Nobody', trackCount: 3 })];
  const { outcome, chosen } = decide(scoreCandidates('Daft Punk', 'Discovery', 14, null, candidates));
  expect(outcome).toBe('no_match');
  expect(chosen).toBeNull();
});

test('unknown album does not cap the score', () => {
  const candidates = [
    candidate({ releaseId: 'right', title: 'Broken Soul Jamboree', artistCredit: 'Tipper', trackCount: 13 }),
    candidate({ releaseId: 'wrong', title: 'Some Other Album', artistCredit: 'Tipper', trackCount: 8 }),
  ];
  const ranked = scoreCandidates('Tipper', null, 13, null, candidates);
  const { outcome, chosen } = decide(ranked);
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('right');
  expect(ranked[0].score).toBeCloseTo(100.0, 3);
});

test('partial local track set does not block auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'right', title: 'Caps On, Hats Off', artistCredit: 'Bossfight', trackCount: 15 }),
    candidate({ releaseId: 'wrong', title: 'Hats Off', artistCredit: 'Ethan Tasch', trackCount: 4 }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Bossfight', 'Caps On, Hats Off', 4, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('right');
});

test('owning more tracks than the release has is still a real mismatch signal', () => {
  const exact = candidate({ releaseId: 'exact', title: 'Discovery', artistCredit: 'Daft Punk', trackCount: 14 });
  const smaller = candidate({ releaseId: 'smaller', title: 'Discovery', artistCredit: 'Daft Punk', trackCount: 8 });
  const ranked = scoreCandidates('Daft Punk', 'Discovery', 14, null, [exact, smaller]);
  expect(ranked[0].releaseId).toBe('exact');
  expect(ranked[0].score).toBeGreaterThan(ranked[1].score);
});

test('no candidates is no match', () => {
  const { outcome, chosen } = decide([]);
  expect(outcome).toBe('no_match');
  expect(chosen).toBeNull();
});

test('track count mismatch lowers score below exact match', () => {
  const exact = candidate({ releaseId: 'exact', title: 'Discovery', artistCredit: 'Daft Punk', trackCount: 14 });
  const mismatched = candidate({ releaseId: 'mismatch', title: 'Discovery', artistCredit: 'Daft Punk', trackCount: 20 });
  const ranked = scoreCandidates('Daft Punk', 'Discovery', 14, null, [exact, mismatched]);
  expect(ranked[0].releaseId).toBe('exact');
  expect(ranked[0].score).toBeGreaterThan(ranked[1].score);
});

test('singleton recording title is actually compared not skipped', () => {
  const correct = candidate({ releaseId: 'a', title: 'Bloodlust', artistCredit: 'Eptic', isRecording: true });
  const unrelated = candidate({ releaseId: 'b', title: 'Spellbound', artistCredit: 'Eptic', isRecording: true });
  const ranked = scoreCandidates('Eptic', 'Bloodlust', null, null, [correct, unrelated]);
  expect(ranked[0].releaseId).toBe('a');
  expect(ranked[0].score).toBeGreaterThan(ranked[1].score);
});

test('singleton perfect match can reach auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'a', title: 'Bloodlust', artistCredit: 'Eptic', isRecording: true }),
    candidate({ releaseId: 'c', title: 'Spellbound', artistCredit: 'Eptic', isRecording: true }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Eptic', 'Bloodlust', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('a');
});

test('recording search unrelated song by same artist does not block auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'right', title: 'Dead Soon', artistCredit: 'Tipper', isRecording: true }),
    candidate({ releaseId: 'unrelated', title: 'Dead Pixels (Instrumental)', artistCredit: 'Tipper', isRecording: true }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Tipper', 'Dead Soon', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('right');
});

test('recording search title collision between unrelated artists still picks correct one', () => {
  const candidates = [
    candidate({ releaseId: 'right', title: 'Nishapur', artistCredit: 'Soltan & DR MAD', isRecording: true }),
    candidate({ releaseId: 'wrong', title: 'Nishapur', artistCredit: 'Renaud Garcia-Fons & Derya Turkan', isRecording: true }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Soltan & DR MAD', 'Nishapur', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('right');
});

test('recording candidates with identical text but different linked albums go to review', () => {
  const candidates = [
    candidate({ releaseId: 'main', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: "Jeff Wayne's Musical Version of The War of the Worlds" }),
    candidate({ releaseId: 'wrong', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: 'The Singles+' }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Jeff Wayne', 'Forever Autumn', null, null, candidates));
  expect(outcome).toBe('needs_review');
  expect(chosen).toBeNull();
});

test('recording candidates for the same album with apostrophe variants still auto apply', () => {
  const candidates = [
    candidate({ releaseId: 'a', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: "Jeff Wayne's Musical Version of The War of the Worlds" }),
    candidate({ releaseId: 'b', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: 'Jeff Wayne’s Musical Version of The War of the Worlds' }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Jeff Wayne', 'Forever Autumn', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen).not.toBeNull();
});

test('local album hint breaks a tie between identically titled recordings', () => {
  const candidates = [
    candidate({ releaseId: 'main', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: "Jeff Wayne's Musical Version of The War of the Worlds" }),
    candidate({ releaseId: 'wrong', title: 'Forever Autumn', artistCredit: 'Jeff Wayne', isRecording: true, album: 'The Singles+' }),
  ];
  const ranked = scoreCandidates('Jeff Wayne', 'Forever Autumn', null, null, candidates, "Jeff Wayne's War of the Worlds");
  const { outcome, chosen } = decide(ranked);
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('main');
});

test('singleton exact title beats a version variant of the same song', () => {
  const candidates = [
    candidate({ releaseId: 'a', title: 'Bloodlust', artistCredit: 'Eptic', isRecording: true }),
    candidate({ releaseId: 'b', title: 'Bloodlust (VIP)', artistCredit: 'Eptic', isRecording: true }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Eptic', 'Bloodlust', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('a');
});

test('singleton version credit selects that specific remix', () => {
  const candidates = [
    candidate({ releaseId: 'a', title: 'Kereberot', artistCredit: 'SVDDEN DEATH', isRecording: true }),
    candidate({ releaseId: 'b', title: 'Kereberot (BVSSIC remix)', artistCredit: 'SVDDEN DEATH', isRecording: true }),
    candidate({ releaseId: 'c', title: 'Kereberot (D’LION remix)', artistCredit: 'SVDDEN DEATH', isRecording: true }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('SVDDEN DEATH', 'Kereberot (BVSSIC Remix)', null, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('b');
});

test('remixer credit spelled with x or multiplication sign still matches', () => {
  const c = candidate({ releaseId: 'a', title: 'Rings of Pluto (Sora × SweetTooth remix)', artistCredit: 'SVDDEN DEATH', isRecording: true });
  const score = scoreCandidate('SVDDEN DEATH', 'Rings of Pluto (Sora X SweetTooth Remix)', null, null, c);
  expect(score).toBeGreaterThan(95);
});

test('featured artist parenthetical is not a version difference', () => {
  const c = candidate({ releaseId: 'a', title: 'Burn It Down', artistCredit: 'Marshmello & SVDDEN DEATH', isRecording: true });
  const score = scoreCandidate('Marshmello & SVDDEN DEATH', 'Burn It Down (feat. Jedwill)', null, null, c);
  expect(score).toBeGreaterThan(90);
});

test('release titles differing only by number are not near ties', () => {
  const candidates = [
    candidate({ releaseId: '1', title: 'Vaultage 001', artistCredit: 'Space Laces' }),
    candidate({ releaseId: '2', title: 'Vaultage 002', artistCredit: 'Space Laces' }),
    candidate({ releaseId: '3', title: 'Vaultage 003', artistCredit: 'Space Laces' }),
  ];
  const { outcome, chosen } = decide(scoreCandidates('Space Laces', 'Vaultage 002', 1, null, candidates));
  expect(outcome).toBe('auto_apply');
  expect(chosen?.releaseId).toBe('2');
});
