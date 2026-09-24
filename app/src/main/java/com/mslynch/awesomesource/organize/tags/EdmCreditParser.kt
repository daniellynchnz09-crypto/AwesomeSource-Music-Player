package com.mslynch.awesomesource.organize.tags

import com.mslynch.awesomesource.organize.model.ArtistCredit
import com.mslynch.awesomesource.organize.model.ArtistCreditRole

/**
 * New logic (the legacy desktop tool had no artist-credit splitting at all) derived
 * directly from the EDM naming conventions the user specified in
 * Claude/MUSIC ORGANIZATION.md:
 *
 * - Solo: `Artist - Title` -> one ARTIST credit.
 * - Collab (2+): `Artist & Artist - Title` / `Artist, Artist, Artist & Artist -
 *   Title` -> one ARTIST credit per collaborator (not one combined string), so each
 *   artist's page shows every track they're on.
 * - Same-artist VIP: `Artist - Title VIP` -> credited exactly like a normal track;
 *   the "VIP" stays in the title text as a distinguishing marker, not stripped here.
 * - Collab-VIP (only one member made the VIP): `Artist & Artist - Title (VIP Artist
 *   VIP)` -> the VIP artist(s) get ARTIST credit, the original collaborators get
 *   COMPOSER credit instead.
 * - Remix: `Artist - Title (Remix Artist Remix)` (or a `&`/comma-separated list of
 *   remixers) -> the remixer(s) get ARTIST credit, the original artist(s) get
 *   COMPOSER credit.
 *
 * Deliberately does not touch the title string itself (e.g. does not strip the
 * "(X Remix)"/"(X VIP)" suffix) - that parenthetical is real, meaningful title text
 * a user would recognize the track by, distinct from the artist-credit question of
 * who counts as "the artist" for browsing/filtering purposes.
 */
object EdmCreditParser {

    private val REMIX_SUFFIX_RE = Regex("""\(([^)]+?)\s+remix\)\s*$""", RegexOption.IGNORE_CASE)
    private val VIP_OF_OTHERS_SUFFIX_RE = Regex("""\(([^)]+?)\s+vip\)\s*$""", RegexOption.IGNORE_CASE)
    private val ARTIST_LIST_SPLIT_RE = Regex("""\s*(?:,|&|\band\b)\s*""", RegexOption.IGNORE_CASE)

    fun splitArtistList(text: String): List<String> =
        text.split(ARTIST_LIST_SPLIT_RE).map { it.trim() }.filter { it.isNotEmpty() }

    fun deriveCredits(rawArtistText: String?, rawTitleText: String?): List<ArtistCredit> {
        val artistText = rawArtistText.orEmpty()
        val titleText = rawTitleText.orEmpty()

        REMIX_SUFFIX_RE.find(titleText)?.let { match ->
            val remixArtists = splitArtistList(match.groupValues[1])
            val originalArtists = splitArtistList(artistText)
            return remixArtists.map { ArtistCredit(it, ArtistCreditRole.ARTIST) } +
                originalArtists.map { ArtistCredit(it, ArtistCreditRole.COMPOSER) }
        }

        VIP_OF_OTHERS_SUFFIX_RE.find(titleText)?.let { match ->
            val vipArtists = splitArtistList(match.groupValues[1])
            val originalArtists = splitArtistList(artistText)
            return vipArtists.map { ArtistCredit(it, ArtistCreditRole.ARTIST) } +
                originalArtists.map { ArtistCredit(it, ArtistCreditRole.COMPOSER) }
        }

        // Plain track, collab, or same-artist VIP: every listed artist is a
        // straightforward ARTIST credit.
        return splitArtistList(artistText).map { ArtistCredit(it, ArtistCreditRole.ARTIST) }
    }
}
