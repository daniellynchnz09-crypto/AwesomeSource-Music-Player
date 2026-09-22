package com.mslynch.awesomesource.organize.matching

import com.mslynch.awesomesource.organize.model.MbCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from `legacy-desktop-tagger/tests/test_scorer.py` - same regression cases,
 * same reasoning in each comment. See [FuzzyMatch]'s doc comment: the underlying
 * similarity functions are a from-scratch re-implementation of rapidfuzz, not a
 * dependency on it, so exact score thresholds (the `> 95.0` style assertions below)
 * may need retuning once this suite is actually run - the *outcome* assertions
 * (auto_apply/needs_review/no_match, "candidate X beats candidate Y") are the ones
 * that must hold regardless, since they're what the real scoring behavior depends on.
 */
class ScorerTest {

    @Test
    fun `case mismatch does not tank the score`() {
        val candidate = MbCandidate(releaseId = "a", title = "Kill Off", artistCredit = "Ecraze")
        val score = Scorer.scoreCandidate("ECRAZE", "KILL OFF", null, null, candidate)
        assertTrue("expected > 95.0 but was $score", score > 95.0)
    }

    @Test
    fun `clear winner auto applies`() {
        val candidates = listOf(
            MbCandidate(releaseId = "good", title = "Discovery", artistCredit = "Daft Punk", trackCount = 14),
            MbCandidate(releaseId = "bad", title = "Some Other Album", artistCredit = "Someone Else", trackCount = 8),
        )
        val ranked = Scorer.scoreCandidates("Daft Punk", "Discovery", 14, null, candidates)
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("good", decision.chosen?.releaseId)
    }

    @Test
    fun `multiple editions of the same album still auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "us-cd", title = "Discovery", artistCredit = "Daft Punk", firstReleaseDate = "2001-03-12", trackCount = 14),
            MbCandidate(releaseId = "eu-cd", title = "Discovery", artistCredit = "Daft Punk", firstReleaseDate = "2001-02-26", trackCount = 14),
            MbCandidate(releaseId = "digital", title = "Discovery", artistCredit = "Daft Punk", firstReleaseDate = "2014", trackCount = 14),
        )
        val ranked = Scorer.scoreCandidates("Daft Punk", "Discovery", 14, null, candidates)
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertNotNull(decision.chosen)
    }

    @Test
    fun `near tied candidates go to review not auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "a", title = "Greatest Hits", artistCredit = "Queen", trackCount = 17),
            MbCandidate(releaseId = "b", title = "Greatest Hits II", artistCredit = "Queen", trackCount = 17),
        )
        val ranked = Scorer.scoreCandidates("Queen", "Greatest Hits", 17, null, candidates)
        val decision = Scorer.decide(ranked)
        assertTrue(decision.outcome == Scorer.Outcome.NEEDS_REVIEW || decision.outcome == Scorer.Outcome.NO_MATCH)
        assertNull(decision.chosen)
    }

    @Test
    fun `weak matches are no match`() {
        val candidates = listOf(MbCandidate(releaseId = "x", title = "Completely Unrelated", artistCredit = "Nobody", trackCount = 3))
        val ranked = Scorer.scoreCandidates("Daft Punk", "Discovery", 14, null, candidates)
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.NO_MATCH, decision.outcome)
        assertNull(decision.chosen)
    }

    @Test
    fun `unknown album does not cap the score`() {
        val candidates = listOf(
            MbCandidate(releaseId = "right", title = "Broken Soul Jamboree", artistCredit = "Tipper", trackCount = 13),
            MbCandidate(releaseId = "wrong", title = "Some Other Album", artistCredit = "Tipper", trackCount = 8),
        )
        val ranked = Scorer.scoreCandidates("Tipper", null, 13, null, candidates)
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("right", decision.chosen?.releaseId)
        assertEquals(100.0, ranked[0].score, 0.001)
    }

    @Test
    fun `partial local track set does not block auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "right", title = "Caps On, Hats Off", artistCredit = "Bossfight", trackCount = 15),
            MbCandidate(releaseId = "wrong", title = "Hats Off", artistCredit = "Ethan Tasch", trackCount = 4),
        )
        val ranked = Scorer.scoreCandidates("Bossfight", "Caps On, Hats Off", 4, null, candidates)
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("right", decision.chosen?.releaseId)
    }

    @Test
    fun `owning more tracks than the release has is still a real mismatch signal`() {
        val exact = MbCandidate(releaseId = "exact", title = "Discovery", artistCredit = "Daft Punk", trackCount = 14)
        val smaller = MbCandidate(releaseId = "smaller", title = "Discovery", artistCredit = "Daft Punk", trackCount = 8)
        val ranked = Scorer.scoreCandidates("Daft Punk", "Discovery", 14, null, listOf(exact, smaller))
        assertEquals("exact", ranked[0].releaseId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `no candidates is no match`() {
        val decision = Scorer.decide(emptyList())
        assertEquals(Scorer.Outcome.NO_MATCH, decision.outcome)
        assertNull(decision.chosen)
    }

    @Test
    fun `track count mismatch lowers score below exact match`() {
        val exact = MbCandidate(releaseId = "exact", title = "Discovery", artistCredit = "Daft Punk", trackCount = 14)
        val mismatched = MbCandidate(releaseId = "mismatch", title = "Discovery", artistCredit = "Daft Punk", trackCount = 20)
        val ranked = Scorer.scoreCandidates("Daft Punk", "Discovery", 14, null, listOf(exact, mismatched))
        assertEquals("exact", ranked[0].releaseId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `singleton recording title is actually compared not skipped`() {
        val correct = MbCandidate(releaseId = "a", title = "Bloodlust", artistCredit = "Eptic", isRecording = true)
        val unrelated = MbCandidate(releaseId = "b", title = "Spellbound", artistCredit = "Eptic", isRecording = true)
        val ranked = Scorer.scoreCandidates("Eptic", "Bloodlust", null, null, listOf(correct, unrelated))
        assertEquals("a", ranked[0].releaseId)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    @Test
    fun `singleton perfect match can reach auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "a", title = "Bloodlust", artistCredit = "Eptic", isRecording = true),
            MbCandidate(releaseId = "c", title = "Spellbound", artistCredit = "Eptic", isRecording = true),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Eptic", "Bloodlust", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("a", decision.chosen?.releaseId)
    }

    @Test
    fun `recording search unrelated song by same artist does not block auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "right", title = "Dead Soon", artistCredit = "Tipper", isRecording = true),
            MbCandidate(releaseId = "unrelated", title = "Dead Pixels (Instrumental)", artistCredit = "Tipper", isRecording = true),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Tipper", "Dead Soon", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("right", decision.chosen?.releaseId)
    }

    @Test
    fun `recording search title collision between unrelated artists still picks correct one`() {
        val candidates = listOf(
            MbCandidate(releaseId = "right", title = "Nishapur", artistCredit = "Soltan & DR MAD", isRecording = true),
            MbCandidate(releaseId = "wrong", title = "Nishapur", artistCredit = "Renaud Garcia-Fons & Derya Turkan", isRecording = true),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Soltan & DR MAD", "Nishapur", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("right", decision.chosen?.releaseId)
    }

    @Test
    fun `recording candidates with identical text but different linked albums go to review`() {
        val candidates = listOf(
            MbCandidate(releaseId = "main", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "Jeff Wayne's Musical Version of The War of the Worlds"),
            MbCandidate(releaseId = "wrong", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "The Singles+"),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Jeff Wayne", "Forever Autumn", null, null, candidates))
        assertEquals(Scorer.Outcome.NEEDS_REVIEW, decision.outcome)
        assertNull(decision.chosen)
    }

    @Test
    fun `recording candidates for the same album with apostrophe variants still auto apply`() {
        val candidates = listOf(
            MbCandidate(releaseId = "a", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "Jeff Wayne's Musical Version of The War of the Worlds"),
            MbCandidate(releaseId = "b", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "Jeff Wayne’s Musical Version of The War of the Worlds"),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Jeff Wayne", "Forever Autumn", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertNotNull(decision.chosen)
    }

    @Test
    fun `local album hint breaks a tie between identically titled recordings`() {
        val candidates = listOf(
            MbCandidate(releaseId = "main", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "Jeff Wayne's Musical Version of The War of the Worlds"),
            MbCandidate(releaseId = "wrong", title = "Forever Autumn", artistCredit = "Jeff Wayne", isRecording = true, album = "The Singles+"),
        )
        val ranked = Scorer.scoreCandidates(
            "Jeff Wayne", "Forever Autumn", null, null, candidates,
            localAlbumHint = "Jeff Wayne's War of the Worlds",
        )
        val decision = Scorer.decide(ranked)
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("main", decision.chosen?.releaseId)
    }

    @Test
    fun `singleton exact title beats a version variant of the same song`() {
        val candidates = listOf(
            MbCandidate(releaseId = "a", title = "Bloodlust", artistCredit = "Eptic", isRecording = true),
            MbCandidate(releaseId = "b", title = "Bloodlust (VIP)", artistCredit = "Eptic", isRecording = true),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Eptic", "Bloodlust", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("a", decision.chosen?.releaseId)
    }

    @Test
    fun `singleton version credit selects that specific remix`() {
        val candidates = listOf(
            MbCandidate(releaseId = "a", title = "Kereberot", artistCredit = "SVDDEN DEATH", isRecording = true),
            MbCandidate(releaseId = "b", title = "Kereberot (BVSSIC remix)", artistCredit = "SVDDEN DEATH", isRecording = true),
            MbCandidate(releaseId = "c", title = "Kereberot (D’LION remix)", artistCredit = "SVDDEN DEATH", isRecording = true),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("SVDDEN DEATH", "Kereberot (BVSSIC Remix)", null, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("b", decision.chosen?.releaseId)
    }

    @Test
    fun `remixer credit spelled with x or multiplication sign still matches`() {
        val candidate = MbCandidate(releaseId = "a", title = "Rings of Pluto (Sora × SweetTooth remix)", artistCredit = "SVDDEN DEATH", isRecording = true)
        val score = Scorer.scoreCandidate("SVDDEN DEATH", "Rings of Pluto (Sora X SweetTooth Remix)", null, null, candidate)
        assertTrue("expected > 95 but was $score", score > 95)
    }

    @Test
    fun `featured artist parenthetical is not a version difference`() {
        val candidate = MbCandidate(releaseId = "a", title = "Burn It Down", artistCredit = "Marshmello & SVDDEN DEATH", isRecording = true)
        val score = Scorer.scoreCandidate("Marshmello & SVDDEN DEATH", "Burn It Down (feat. Jedwill)", null, null, candidate)
        assertTrue("expected > 90 but was $score", score > 90)
    }

    @Test
    fun `release titles differing only by number are not near ties`() {
        val candidates = listOf(
            MbCandidate(releaseId = "1", title = "Vaultage 001", artistCredit = "Space Laces"),
            MbCandidate(releaseId = "2", title = "Vaultage 002", artistCredit = "Space Laces"),
            MbCandidate(releaseId = "3", title = "Vaultage 003", artistCredit = "Space Laces"),
        )
        val decision = Scorer.decide(Scorer.scoreCandidates("Space Laces", "Vaultage 002", 1, null, candidates))
        assertEquals(Scorer.Outcome.AUTO_APPLY, decision.outcome)
        assertEquals("2", decision.chosen?.releaseId)
    }
}
