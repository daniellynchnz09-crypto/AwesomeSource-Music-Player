# Block-reuse audio format experiment

## The idea being tested

Store only ~2-3 seconds of actual audio data at a time (a "palette"), plus a
tiny map that says, for every ~2-3 second window of the song, which palette
entry to replay (and roughly where to shift it) instead of storing fresh
audio for that window. Sections that don't closely match anything already in
the palette get added as new raw audio. No per-window correction/residual is
stored - the map is the whole encoding.

This mirrors how old tracker/MOD formats work (store short samples once +
a pattern of when to trigger them), which is why it's a legitimate thing to
try - that format really does get tiny files from exactly this trick. The
open question was whether it also works on *arbitrary recorded/rendered
music*, not just music deliberately composed from a small sample set.

## Method

Two real (not just estimated) tools, both in this folder:

- `analyze_similarity.py` / `analyze_spectral_similarity.py` - for every
  ~2.5s block in a track, find the *best possible* match against every other
  block in the same track (not constrained by a threshold or by only
  looking at already-seen blocks), to see the real ceiling before picking
  any cutoff.
- `block_reuse_codec.py` - an actual encoder *and* decoder: builds the
  palette + map, and reconstructs a playable WAV from them, so results can
  be listened to, not just measured.

Two ways of deciding "is this block close enough to reuse":

- **Time-domain**: compare raw sample values directly (what the user's
  proposal literally describes - substitute audio data; `--match-domain
  time`, the codec's default).
  - Allows the matched palette block to be nudged by one of a handful of
    small time offsets (±25-100ms) to allow for the "rearrangement" part of
    the idea.
- **Spectral-domain**: compare each block's frequency-magnitude content
  (`--match-domain spectral`) - phase/micro-timing-invariant, the same style
  of representation audio fingerprinting tools use. Added after the
  time-domain result came back essentially zero everywhere, to check
  whether that was hiding real musical repetition behind too strict a
  metric.

Tracks compared (not committed to this repo - decoded on demand from the
user's own gitignored `Music/` library, see this folder's own `.gitignore`):
a loop-based EDM track (`EDM/01 Bloodlust.m4a`) and a continuously-varying
orchestral piece (`Orch music/01 Academic Festival Overture Op 80.m4a`).

## Results

### Time-domain: best achievable match, first 60s, 2.5s blocks

| Track | Max correlation | Mean | Blocks ≥ 0.92 |
|---|---|---|---|
| EDM (Bloodlust) | 0.27 | 0.17 | 0% |
| Orchestral | 0.30 | 0.15 | 0% |

Raw-sample matching never found a usable match in *either* track, even
allowing it to search the entire rest of the song, not just previously-seen
blocks. This isn't really about the music - it's that rendered audio
essentially never repeats bit-for-bit. Even a genuinely identical musical
loop will differ by a handful of samples of phase/timing from one playback
to the next, and at 2.5 seconds (≈110,000 samples), that's enough to
collapse a plain dot-product correlation towards zero. This is also exactly
why no real audio codec compares raw PCM blocks against each other.

### Spectral-domain: best achievable match, first 90s, 2.5s blocks

| Track | Max correlation | Mean | Blocks ≥ 0.80 |
|---|---|---|---|
| EDM (Bloodlust) | 0.98 | 0.78 | 58% |
| Orchestral | 0.92 | 0.67 | 19% |

This is the more interesting finding: once phase/micro-timing is taken out
of the comparison, the EDM track really does have substantial repeated
structure (matches the ear's intuition about drops/loops), and clearly more
of it than the orchestral piece, which keeps developing. So the core
intuition - "some music reuses the same material a lot, some doesn't" - is
correct and measurable.

### Actually encoding + reconstructing with spectral matching (threshold 0.85)

| Track | Reuse rate | Size vs raw PCM | Size vs original lossy file | Reconstruction SNR |
|---|---|---|---|---|
| EDM (Bloodlust) | 19.4% | 1.24x smaller | 1.39x smaller | **2.9 dB** |
| Orchestral | 11.1% | 1.12x smaller | 3.02x smaller | **11.3 dB** |

(`ratio vs original lossy file` looks good for the orchestral track, but
that's mostly because the source file itself is a high-bitrate file, not
because this scheme is actually competitive - see below.)

## Initial read (revised below - keep reading)

1. **Spectral similarity ≠ sample-identical audio.** A block can share
   frequency content with another block (same instruments, similar pitch
   range) without being an exact match, because the magnitude spectrum
   throws away *when* within that block things happen - exactly where a
   kick drum or vocal consonant lands. Substituting raw PCM from a
   "spectrally close" block scored a rough single-digit-dB SNR - but see
   the listening-test correction below before reading that as "broken."
2. **The size savings looked small in isolation.** 1.12-1.24x versus raw
   16-bit PCM, on its own, is a poor compression ratio next to what a real
   lossy codec already gets from raw PCM. That comparison turned out to be
   the wrong one - see below.
3. **The technique that already exists for literally-repeating content**
   is tracker/MOD formats, because that music is composed from a small set
   of samples to begin with - nothing to "discover" via correlation.

## Correction after actually listening, and combining with real compression

Two things moved after the above was first written:

- **The SNR number oversold how bad it sounds.** Listening to the actual
  reconstructed EDM file, the 2.9 dB version was judged "slightly lower
  quality," not broken. SNR punishes any time-misalignment between two
  *musically similar* passages almost as harshly as it punishes random
  noise, which makes it a bad proxy here - the substituted material is
  still coherent music from the same track, just not bar-for-bar identical,
  and the ear is far more forgiving of that than a raw sample-error metric
  is.
- **Comparing "raw palette PCM" against raw PCM was the wrong baseline.**
  The real question is whether block-reuse adds anything *on top of* an
  actual lossy codec, since nobody would ship a format that stores its
  kept audio uncompressed. Re-ran both tracks with the palette additionally
  Opus-compressed (64 kbps), and - critically - against a new baseline:
  plain Opus on the *whole, untouched* track, no block-reuse at all.

| Track | Reuse rate | Opus-only baseline | Block-reuse + Opus | Extra savings from block-reuse |
|---|---|---|---|---|
| EDM (Bloodlust) | 19.4% | 730 KB | 588 KB | **1.24x smaller than Opus alone** |
| Orchestral | 11.1% | 680 KB | 608 KB | **1.12x smaller than Opus alone** |

Both tracks end up 13-13.5x smaller than raw PCM overall (Opus is doing
nearly all of that; block-reuse adds the last 12-24% on top), and the
"extra savings from block-reuse" lines up almost exactly with each track's
reuse rate - which makes sense: skipping N% of blocks means Opus only has
to encode (100-N)% of the audio in the first place.

Also rendered the TRUE combined-pipeline audio - not just measured its
size - by actually Opus-encoding the palette and decoding it back before
reconstructing, so the result carries real Opus quantization loss on top
of the block-substitution loss (`*_opus64k.wav` files below). Opus's own
loss turned out to add very little on top of what block-reuse alone
already cost: SNR moved from 2.9 → 2.7 dB (EDM) and 11.3 → 10.9 dB
(orchestral) - confirming the block-substitution artifact, not Opus
itself, is what dominates the audible difference.

**So the idea does hold up as a real, if modest, win - with real caveats:**

- It only pays off proportional to how much a track actually repeats
  (19% here for a loop-heavy EDM track, 11% for a through-composed
  orchestral piece) - it's not a universal multiplier.
- It adds real decoder complexity on top of Opus (you now need the reuse
  map *and* an Opus decoder, not just the latter) for a 12-24% win - a
  real but not dramatic trade.
- The quality cost is real too, just smaller than the raw SNR number
  suggested - this is still literally substituting a different (if
  similar) passage of audio, which a careful listener could in principle
  notice, especially outside casual listening conditions.

## Listen for yourself

All decoded/reconstructed WAVs are written outside this repo (per this
folder's `.gitignore` - they're derived from the user's copyrighted library
and must never be committed), to:

```
C:\Users\OEM\AppData\Local\Temp\claude\e63168c9891cf6e5\5377abb6-c0f7-4ca5-ae42-c99acd78d330\scratchpad\block-reuse-audio\
```

- `01_Bloodlust__original.wav` - the source, untouched.
- `01_Bloodlust__reconstructed_spectral_2.5s_t0.85.wav` - block-reuse only,
  palette still pristine PCM (isolates the substitution artifact alone).
- `01_Bloodlust__reconstructed_spectral_2.5s_t0.85_opus64k.wav` - the real
  "what would actually ship" version: block-reuse AND the palette's real
  Opus 64kbps quantization loss, both applied. **This is the one that
  matters** - it's what the combined-savings numbers above actually sound
  like, not just an estimate of them. Same three files exist for
  `01_Academic_Festival_Overture_Op_80`.

All three are plain WAV - nothing extra needed to play them (not VLC-
specific; any player works, including just double-clicking). No separate
Opus decoder was needed either: Opus decoding happened once, offline, as
part of generating these files - the files themselves are already decoded
audio. (Aside: VLC does have Opus support built in regardless, if a raw
`.opus` file ever shows up here - no plugin required for that either.)

That scratchpad directory is temporary/session-scoped - copy the WAVs
somewhere durable first if you want to keep them.

## On the VLC plugin

Still holding off, but for a different reason now than when this was first
written. A VLC input/demuxer plugin is real, separate work (C module
against VLC's SDK, compiled native binary, its own build/signing story) -
worth doing once the format design has actually settled, not while
block/threshold/bitrate choices are still being tuned by hand in a Python
script. The WAV files above already play in VLC (or anything else) with
zero plugin work, which is enough to keep evaluating the idea without
paying that cost yet.

## If this is worth continuing

- **Try the crossfade idea next.** Block boundaries are currently a hard
  cut; a short (~20-50ms) crossfade at each splice is the standard trick
  for this kind of concatenative audio and should reduce whatever the ear
  does notice, for cheap.
- Store a small residual/correction per reused block instead of zero
  residual - would recover more quality, at the cost of most of the extra
  savings over plain Opus (the whole point of zero-residual is that the map
  entry costs almost nothing).
- Point this at music actually built from reusable samples (chiptune,
  trivial drum-loop tracks) to see the reuse rate - and the savings - go
  much higher than the 11-19% measured here.
- Test higher reuse thresholds/more source material to see how the
  12-24%-over-Opus number holds up outside these two 90-second clips -
  that's a small sample size to generalize from.

## Running it yourself

```
.venv/Scripts/python.exe -m venv .venv   # already created
.venv/Scripts/python.exe -m pip install -r requirements.txt
.venv/Scripts/python.exe analyze_spectral_similarity.py "<path to an audio file>" --seconds 90
.venv/Scripts/python.exe block_reuse_codec.py "<path to an audio file>" --match-domain spectral --threshold 0.85 --opus-bitrate-kbps 64 --out-dir "<some folder outside this repo>"
```

`--opus-bitrate-kbps` is optional - omit it to see the uncompressed-palette
numbers only (no ffmpeg Opus encode step, faster). Add it to see the
combined-with-real-compression numbers and the plain-Opus baseline this
scheme needs to beat.
