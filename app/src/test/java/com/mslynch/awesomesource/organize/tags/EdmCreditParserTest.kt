package com.mslynch.awesomesource.organize.tags

import com.mslynch.awesomesource.organize.model.ArtistCredit
import com.mslynch.awesomesource.organize.model.ArtistCreditRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * New logic (no Python original) - directly encodes the exact examples from
 * Claude/MUSIC ORGANIZATION.md's EDM naming-convention section, so this test suite
 * doubles as executable documentation of what the user actually specified.
 */
class EdmCreditParserTest {

    @Test
    fun `solo track is a single artist credit`() {
        val credits = EdmCreditParser.deriveCredits("Eptic", "Octane")
        assertEquals(listOf(ArtistCredit("Eptic", ArtistCreditRole.ARTIST)), credits)
    }

    @Test
    fun `two artist collab splits into one credit each`() {
        val credits = EdmCreditParser.deriveCredits("Zomboy & Eptic", "Bop It")
        assertEquals(
            listOf(
                ArtistCredit("Zomboy", ArtistCreditRole.ARTIST),
                ArtistCredit("Eptic", ArtistCreditRole.ARTIST),
            ),
            credits,
        )
    }

    @Test
    fun `three plus artist collab with comma list ending in ampersand`() {
        val credits = EdmCreditParser.deriveCredits("Virtual Riot, Barely Alive, PhaseOne & Myro", "Rampage")
        assertEquals(
            listOf(
                ArtistCredit("Virtual Riot", ArtistCreditRole.ARTIST),
                ArtistCredit("Barely Alive", ArtistCreditRole.ARTIST),
                ArtistCredit("PhaseOne", ArtistCreditRole.ARTIST),
                ArtistCredit("Myro", ArtistCreditRole.ARTIST),
            ),
            credits,
        )
    }

    @Test
    fun `same artist VIP keeps the original artist credit`() {
        val credits = EdmCreditParser.deriveCredits("Herobust", "Blockbuster VIP")
        assertEquals(listOf(ArtistCredit("Herobust", ArtistCreditRole.ARTIST)), credits)
    }

    @Test
    fun `collab VIP by one member credits the VIP artist and composers the originals`() {
        val credits = EdmCreditParser.deriveCredits("Excision and Space Laces", "Crusaders (Space Laces VIP)")
        assertEquals(
            listOf(
                ArtistCredit("Space Laces", ArtistCreditRole.ARTIST),
                ArtistCredit("Excision", ArtistCreditRole.COMPOSER),
                ArtistCredit("Space Laces", ArtistCreditRole.COMPOSER),
            ),
            credits,
        )
    }

    @Test
    fun `remix credits the remixer as artist and original as composer`() {
        val credits = EdmCreditParser.deriveCredits("SVDDEN DEATH & Yakz", "Rock Like This (Oddprophet Remix)")
        assertEquals(
            listOf(
                ArtistCredit("Oddprophet", ArtistCreditRole.ARTIST),
                ArtistCredit("SVDDEN DEATH", ArtistCreditRole.COMPOSER),
                ArtistCredit("Yakz", ArtistCreditRole.COMPOSER),
            ),
            credits,
        )
    }

    @Test
    fun `multi remixer list ending in ampersand credits every remixer as artist`() {
        val credits = EdmCreditParser.deriveCredits("SVDDEN DEATH", "Rings of Pluto (Blankface & Decimate Remix)")
        assertEquals(
            listOf(
                ArtistCredit("Blankface", ArtistCreditRole.ARTIST),
                ArtistCredit("Decimate", ArtistCreditRole.ARTIST),
                ArtistCredit("SVDDEN DEATH", ArtistCreditRole.COMPOSER),
            ),
            credits,
        )
    }
}
