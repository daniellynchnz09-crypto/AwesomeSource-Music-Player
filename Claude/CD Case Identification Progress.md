# CD Case Identification Progress

The user photographed the front and back of 20 physical CDs (40 photos total,
stored locally in `Music Case Images/` at the repo root - **gitignored**, since
they're copyrighted album artwork plus the user's own bookshelf in the background,
never meant for the public repo) so their track listings/artists could be used to
identify and verify matching entries already in the digitized library. This
document preserves the transcribed tracklist data and the matching results so a
future session doesn't have to re-read all 40 photos from scratch.

## How this data was applied

Two ways, depending on what the library already had:

1. **Direct field write, bypassing MusicBrainz entirely.** For a completely
   untagged file (no artist/album, but usually a correctly filename-parsed title
   and track number already), the sleeve-derived Artist/Album/Composer/Genre/Year
   were written straight into the track's own fields via a raw SQL `UPDATE`
   (`source = 'MANUAL_ENTRY'`, `matchedReleaseId` left `NULL`). Since
   `TrackEntity.reviewStatus()` computes `VERIFY` exactly when all four core
   fields are present but nothing is "recognized" (`matchedReleaseId IS NULL`),
   this lands the track in the **Verify** tab automatically - per the user's own
   instruction ("after the information from images... are scanned and matched
   with tracks, the tracks should go in the verify tab"), not auto-Approved.
2. **No action** - a handful of photographed CDs turned out to already be
   correctly tagged in the library from an earlier scan/match (see "Already
   correct" below); the photo just confirms the existing data is right.

Applying data this way (direct DB write with the app force-stopped, then pushing
a clean, WAL-checkpointed `.db` file back via `run-as`) was a deliberate choice
for this one-off backlog cleanup instead of the in-app multi-select UI, which
needs one long-press per track (30+ fragile taps for just the two albums done so
far) - see ANDROID ARCHITECTURE.md for the full writeup of why `run-as` writes
work at all (a git-bash argv-quoting artifact, not a real SELinux restriction).

## Fully matched and applied

**1. Titanic - Music From The Motion Picture** (James Horner, Sony Classical SK
63213, 1997) - all 15 tracks found completely untagged under
`Orch music/01 Never An Absolution.wav` through `15 Hymn To The Sea.wav`.
Applied: `artist = "James Horner"` (except track 14, `artist = "Celine Dion"`,
who performs "My Heart Will Go On"), `albumArtist = "James Horner"`,
`album = "Titanic: Music From The Motion Picture"`, `composer = "James Horner"`,
`genre = "Soundtrack"`, `year = 1997`. This album's front cover was also cropped
and attached via the new custom-cover-art feature (see ANDROID ARCHITECTURE.md)
as a live end-to-end test of both features together.

**16. Grieg - Peer Gynt: Overture, Suites Nos. 1 & 2, Lyric Pieces & Sigurd
Jorsalfar** (Naxos 8.550140, CSSR State Philharmonic Orchestra Kosice, cond.
Stephen Gunzenhauser, 1988 recording) - all 16 tracks found completely untagged
under `Orch music/01 Peer Gynt Overture.wav` through `16 Homage March.wav`.
Applied: `artist = albumArtist = "Stephen Gunzenhauser: CSSR State Philharmonic
Orchestra (Kosice)"`, `composer = "Edvard Grieg"`, `genre = "Classical"`,
`year = 1988`, `album = "Peer Gynt: Overture, Suites Nos. 1 & 2, Lyric Pieces &
Sigurd Jorsalfar"`. One track (`09 Solveig's Song.wav`) had a pre-existing gap
where `FilenameParser` had never actually extracted a title for it (blank, not
just missing artist/album) - fixed by also setting `title = "Solveig's Song"`.

## Already correct - no action needed

These three photographed CDs turned out to already be correctly tagged in the
library (real artist/album/title, `EMBEDDED_TAGS` or similar source), just never
matched to a MusicBrainz release (so they sit in Verify already) - the photo
confirms the existing data is accurate, including the budget-label reissue's own
performer credits, which look like pseudonyms/aliases reused across many
different reissue labels (Alberto Lizzio, Alfred Scholz) but are exactly what's
printed on these specific physical sleeves too:

- **20. Clarinet & Flute** (Mozart) - library has it as "Mozart: Clarinet & Flute
  Concertos" by "Kamil Sreter/Peter Jancovic; Alberto Lizzio: Mozart Festival
  Orchestra" - matches the sleeve's "Mozart Festival Orchestra, Conductor:
  Alberto Lizzio" exactly.
- **14. Tchaikovsky - Nutcracker/Swan Lake** (Onyx Classix) - library has it as
  "Tchaikovsky: The Nutcracker, Swan Lake (Ballet Suites)" by "Alberto Lizzio:
  London Festival Orchestra" - matches the sleeve's "London Festival Orchestra,
  Ltg./Cond.: Alberto Lizzio" exactly.
- **15. Mendelssohn & Schubert** (Gemini Collection, disc 1 only) - library has
  "Mendelssohn: Symphonies #3 & 5" by "Alfred Scholz: London Philharmonic
  Orchestra" - matches the sleeve's "Munich Symphony Orchestra (Albert Lizzio)"
  closely enough (same budget-reissue conductor alias, different credited
  orchestra name across reissues of the same recording - a known quirk of this
  era of budget classical licensing, not a real discrepancy worth chasing).

## Not yet resolved - only fragments found, or nothing found

For the remaining 15 CDs, keyword searches against the full library found either
nothing, or only 1-3 scattered tracks under generic movement titles (e.g. a lone
"Tritsch-Tratsch Polka" tagged to a completely different "Best Of Classical"
compilation) - meaning most of these CDs' actual audio either isn't digitized
into this library at all, or exists under filenames too different from the
sleeve tracklist to keyword-match. Revisiting these needs either a closer
per-file listen/compare, or accepting they may just not be in the digital
library yet. Full transcribed tracklists below so no CD needs re-photographing:

**2. Synthesizer Greatest** (Ed Starink/Star Inc., Dino Music, "Synthesizer
Greatest" series) - synth cover versions, composer credited per track on the
sleeve itself (not a guess): Theme From "Antarctica" (Vangelis), Moments In Love
(Dudley/Horn/Jeczalik), Mammagamma (A. Parsons/E. Woolfson), Axel F. (H.
Faltermeyer), Autobahn (Hütter/Schneider), Magnetic Fields Pt.2 (J.M. Jarre),
Electricity (McClusky/Humphries), Equinoxe Pt.5 (J.M. Jarre), Chariots Of Fire
(Vangelis), Hymne (Vangelis), Crockett's Theme (J. Hammer), Fourth Rendez-Vous
(J.M. Jarre), Pulstar (Vangelis), Oxygene (J.M. Jarre), To The Unknown Man
(Vangelis), Chase/Midnight Express (Moroder), Tubular Bells/The Exorcist (M.
Oldfield, bonus track).

**Atmospheric Synthesizer Vol.1** (Double Play/Tring, budget covers compilation,
no performer credited anywhere on the sleeve - do NOT attribute to the original
artists in metadata): Theme From 'Antarctica', The Eve Of The War, Equinoxe
(Part 5), Tubular Bells/The Exorcist, Autobahn, Aurora, Magnetic Fields (Part 2),
Theme From 'Rainman', Tubbs and Valerie, To The Unknown Man, Electrical Salsa,
The Model, Rockit, Chariots Of Fire, Living On Video, I'll Find My Way Home.

**4. A Tribute To Jon Vangelis** (OMBRA/Documents label) - cover versions,
composer Vangelis throughout, "Performed by M.A.S.S.": Chariots Of Fire, Eric's
Theme, Unknown Man, Twenty Eight Parallel, L'Opera Sauvage, China, Dervish D,
Hymne, Pulstar, Antartica, Apocalypse Des Animaux.

**5. Grieg - Peer Gynt Suites 1 & 2 / Piano Concerto in A Minor** (Everyman
series, Object Enterprises Ltd, 1988, no performer named): Suite No.1 (Morning,
Aase's Death, Anitra's Dance, In The Hall Of The Mountain King); Suite No.2 (The
Abduction And Ingrid's Complaint, Arabian Dance, Peer Gynt's Homecoming,
Solvejg's Song); Piano Concerto Op.16 (Allegro Molto Moderato, Adagio, Finale).

**6. Brahms - Symphony No.2 / Hungarian Dances** ("The Composers" series;
Süddeutsche Philharmoniker/Cesare Cantieri, Nürnberger Symphoniker/Urs
Schneider, London Festival Orchestra/Cesare Cantieri): Symphony No.2 Op.73 (4
movements), Tragic Overture Op.81, Hungarian Dances No.1-4.

**7. Haydn - The Philosopher / Lamentatione / The Imperial** (Onyx Classix, cat.
66352, Musici di San Marco/Alberto Lizzio): Symphony No.22 (4 mvts), Symphony
No.26 "Lamentatione" (3 mvts), Symphony No.53 "The Imperial" (4 mvts). Note: no
actual case-front photo exists for this one in the set (the "front" photo taken
was of the bare disc label instead) - would need a re-shoot if front art is
wanted.

**8. The Piano** (Michael Nyman, Virgin Records 1993, no track times printed):
To The Edge Of The Earth, Big My Secret, A Wild And Distant Shore, The Heart
Asks Pleasure First, Here To There, The Promise, A Bed Of Ferns, The Fling, The
Scent Of Love, Deep Into The Forest, The Mood That Passes Through You, Lost And
Found, The Embrace, Little Impulse, The Sacrifice, I Clipped Your Wing, The
Wounded, All Imperfect Things, Dreams Of A Journey.

**9. Romantic Piano - Tchaikovsky & Grieg** (Countdown Music; tracks 1-3: London
Festival Orchestra/Laurence Siegel, piano Ida Czernecka; tracks 4-6: Ljubljana
Symphony Orchestra/Anton Nanut, piano Dubravka Tomsic): Tchaikovsky Piano
Concerto No.1 (3 mvts), Grieg Piano Concerto Op.16 (3 mvts).

**10. Johann Strauss - More Waltzes & Polkas** (Maestro Masters MAES-1727,
Orchester der Wiener Volksoper/Carl Michalski, marked "Waltzes and Polkas.2" -
possibly volume 2 of a set): Voices of Spring Waltz, Tritsch-Tratsch Polka,
Artist's Life Waltz, Where the Citrons Bloom Waltz, Champagne Polka,
Accelerations Waltz, Lovesongs Waltz, Vienna Bonbons Waltz, Perpetuum Mobile.

**11. Mozart - Symphony No.41 "Jupiter" / Horn Concerto No.3** (Everyman series,
Object Enterprises Ltd 1988; Vienna State Orchestra/Alfredo Scholz, horn Johann
Monn): Symphony 41 K551 (4 mvts), Horn Concerto 3 K447 (3 mvts).

**12. French Organ Music** (Naxos 8.550581, Simon Lindley, organ, recorded Leeds
Parish Church 1991): Guilmant Grand Choeur, Vierne Berceuse, Charpentier Te
Deum, Langlais Trois Meditations (3 parts), Vierne Epitaph, Bonnet Romance,
Malengreau Suite Mariale, Boellmann Suite Gothique, Vierne Stele pour un enfant
defunt, Guilmant Cantilene-Pastorale, Widor Toccata (Symphony No.5).

**13. Chamber Music** (Mediaphon Classics; Musici di San Marco tracks 1-3,
Stuttgart Windquintet tracks 4-16) - front cover only credits "Vivaldi, Lickl &
Reicha" but the back tracklist also includes Danzi: Vivaldi Concerto for
Windquintet RV571 (3 mvts), Reicha Quintet Op.88/2 (4 mvts), Danzi Quintet
Op.56/1 (4 mvts), Lickl Quintetto Concertante (5 mvts).

**17. The Mozart Collection Volume 4** (Cadenza Collection DLCCD 226, 1991;
Slovak National Philharmonic/Libor Pesek, Camerata Labacensis/Alexander von
Pitamic, Mozart Festival Orchestra/Alberto Lizzio, piano Peter Schmalfuss):
Symphony No.38 "Prague" (3 mvts), March for Constanze K408/1, Symphony No.29
K201 (4 mvts), March in D K215.

**18. Romantic Piano - Beethoven, Satie & Schubert** (Mediaphon Classics;
pianists Marylene Dosse, Grant Johannesen, Sylvia Capova, Helene Gal, Peter
Schmalfuss, Dubravka Tomsic across different tracks): Satie Gymnopedie, Satie
Preludes Flasques Pour Un Chien (2 parts), Schubert Musical Moment No.3,
Schubert Impromptus (2), Schubert Landler, Schubert Waltzes (2 sets), Schubert
Scherzo No.1, Beethoven "Waldstein" Sonata Op.53 (3 mvts).

**19. Beethoven & Brahms** (Gemini Collection, disc 2 only relevant here since
disc 1 = the Beethoven "Classics For Poets"/"Spectacular Classics" fragments
already found - see below): Academic Festival Overture (London Symphony
Orchestra/Alberto Rizzio), Waltz No.15 Op.39 (pianist Martin Jones), Hungarian
Dance in G minor (Hamburg Philharmonic/Hans-Jurgen Walter), Clarinet Quintet
Op.115 Intermezzo (Bochmann String Quartet, clarinet David Campbell), Lullaby
Op.49 No.4 (pianist Martin Jones), Hungarian Dance in D major (Hamburg
Philharmonic/Hans-Jurgen Walter), Symphony No.3 Op.90 (Kiev Philharmonic/Nikolai
Sokolov). Disc 1 (Beethoven: Symphony No.5, Leonora Overture No.3, Piano Sonatas
14 "Moonlight" & "Pathetique", Symphony No.9 4th mvt excerpt) partially found -
"Academic Festival Overture" and a "Moonlight" 1st movement already exist under
different unrelated compilations ("Classics For Poets", "Spectacular Classics
[Box 4]") - not the same release as this Gemini disc, left alone.

## Second-look candidates from the photo batch itself

- `Music Case Images/20260927_104010.jpg` (the Haydn CD, #7 above) is a photo of
  the bare disc's printed label, not the case's front-cover artwork - no real
  front-cover photo exists for this album; re-shoot if its cover art matters.
- A handful of barcodes/running times were cut off at the frame edge or
  obscured by glare/fingers across several photos - none critical to
  identification, just not fully legible for cataloguing purposes.
