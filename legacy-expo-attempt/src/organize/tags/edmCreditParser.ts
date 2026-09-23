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

import { ArtistCredit, ArtistCreditRole } from '../model/types';

const REMIX_SUFFIX_RE = /\(([^)]+?)\s+remix\)\s*$/i;
const VIP_OF_OTHERS_SUFFIX_RE = /\(([^)]+?)\s+vip\)\s*$/i;
const ARTIST_LIST_SPLIT_RE = /\s*(?:,|&|\band\b)\s*/i;

export function splitArtistList(text: string): string[] {
  return text
    .split(ARTIST_LIST_SPLIT_RE)
    .map((s) => s.trim())
    .filter(Boolean);
}

export function deriveCredits(rawArtistText: string | null | undefined, rawTitleText: string | null | undefined): ArtistCredit[] {
  const artistText = rawArtistText ?? '';
  const titleText = rawTitleText ?? '';

  const remixMatch = REMIX_SUFFIX_RE.exec(titleText);
  if (remixMatch) {
    const remixArtists = splitArtistList(remixMatch[1]);
    const originalArtists = splitArtistList(artistText);
    return [
      ...remixArtists.map((name): ArtistCredit => ({ name, role: ArtistCreditRole.Artist })),
      ...originalArtists.map((name): ArtistCredit => ({ name, role: ArtistCreditRole.Composer })),
    ];
  }

  const collabVipMatch = VIP_OF_OTHERS_SUFFIX_RE.exec(titleText);
  if (collabVipMatch) {
    const vipArtists = splitArtistList(collabVipMatch[1]);
    const originalArtists = splitArtistList(artistText);
    return [
      ...vipArtists.map((name): ArtistCredit => ({ name, role: ArtistCreditRole.Artist })),
      ...originalArtists.map((name): ArtistCredit => ({ name, role: ArtistCreditRole.Composer })),
    ];
  }

  // Plain track, collab, or same-artist VIP: every listed artist is a
  // straightforward ARTIST credit.
  return splitArtistList(artistText).map((name): ArtistCredit => ({ name, role: ArtistCreditRole.Artist }));
}
