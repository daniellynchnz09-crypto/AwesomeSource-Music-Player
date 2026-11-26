"""
Prototype for the user's "block-reuse" audio format idea:

    Store one copy of each distinct ~N-second chunk of audio ("the palette"),
    plus a tiny per-chunk "map" that says which palette entry (and what small
    time-shift) to replay for every N-second window of the song. A window
    that doesn't closely match anything already in the palette gets added to
    the palette as new raw audio.

This script is a real (if simplified) encoder/decoder for that idea, not just
a theoretical estimate: it actually produces a reconstructed WAV so the
result can be listened to, and reports real size/quality numbers.

Usage:
    .venv/Scripts/python.exe block_reuse_codec.py <source audio file> \
        [--seconds 60] [--block-seconds 2.5] [--threshold 0.92] \
        [--out-dir <dir for decoded/reconstructed wavs>]

Deliberately NOT reading/writing any audio into this repo: source tracks are
decoded from the user's local (gitignored) Music/ library, and all WAV
output goes to --out-dir, which should point outside the repo (e.g. the
session scratchpad) - see this folder's own .gitignore for why.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SAMPLE_RATE = 44100
SAMPLE_WIDTH_BYTES = 2  # 16-bit PCM


def decode_to_wav(src_path: Path, out_wav: Path, max_seconds: float) -> None:
    out_wav.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(src_path),
            "-t", str(max_seconds),
            "-ac", "1", "-ar", str(SAMPLE_RATE), "-sample_fmt", "s16",
            str(out_wav),
        ],
        check=True,
        capture_output=True,
    )


def load_wav_mono_i16(path: Path) -> np.ndarray:
    with wave.open(str(path), "rb") as w:
        assert w.getframerate() == SAMPLE_RATE
        assert w.getsampwidth() == SAMPLE_WIDTH_BYTES
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.float64)


def save_wav_mono_i16(path: Path, samples: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    clipped = np.clip(samples, -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(SAMPLE_WIDTH_BYTES)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(clipped.tobytes())


@dataclass
class EncodedBlock:
    kind: str  # "new" or "reuse"
    palette_index: int
    shift_samples: int
    correlation: float


# How far a matched palette block is allowed to be nudged in time before
# being replayed - the "rearrangement" part of the user's idea. A handful of
# discrete offsets (not a full per-sample search) keeps this fast and is
# enough to test the hypothesis.
SHIFT_CHOICES_MS = [-100, -75, -50, -25, 0, 25, 50, 75, 100]


def normalized_correlation(a: np.ndarray, b: np.ndarray) -> float:
    denom = np.linalg.norm(a) * np.linalg.norm(b)
    if denom == 0:
        return 1.0 if np.allclose(a, b) else 0.0
    return float(np.dot(a, b) / denom)


def magnitude_spectrum(block: np.ndarray) -> np.ndarray:
    return np.abs(np.fft.rfft(block * np.hanning(len(block))))


def best_match_time_domain(window: np.ndarray, palette: list[np.ndarray]) -> tuple[int, int, float]:
    """Best (palette_index, shift_samples, correlation) for `window` against
    every palette entry, trying each of SHIFT_CHOICES_MS as a time-shift of
    the palette entry (zero-padded at the edge it's shifted away from)."""
    best_idx, best_shift, best_corr = -1, 0, -1.0
    for idx, ref in enumerate(palette):
        for shift_ms in SHIFT_CHOICES_MS:
            shift = int(SAMPLE_RATE * shift_ms / 1000)
            shifted = np.roll(ref, shift)
            if shift > 0:
                shifted[:shift] = 0
            elif shift < 0:
                shifted[shift:] = 0
            corr = normalized_correlation(window, shifted)
            if corr > best_corr:
                best_idx, best_shift, best_corr = idx, shift, corr
    return best_idx, best_shift, best_corr


def best_match_spectral(window: np.ndarray, palette: list[np.ndarray], palette_spectra: list[np.ndarray]) -> tuple[int, int, float]:
    """Picks the palette entry whose FREQUENCY content (not raw waveform)
    best matches `window` - phase/micro-timing-invariant, so it can find
    "musically the same" blocks that best_match_time_domain can't.

    Picking a block by frequency content alone says nothing about WHERE in
    the beat/bar that block happens to start - the fixed block_seconds grid
    isn't tempo-aligned, so a spectrally-similar block is quite likely to
    land at a different rhythmic phase than its neighbors once substituted,
    even though its duration and position are exactly correct. That's a
    real reported symptom (swapped sections sounding "delayed"/out of sync)
    traced back to this function always returning shift=0 - so once the
    best spectral candidate is chosen, its own best TIME-domain phase
    alignment is searched for separately (same shift search
    best_match_time_domain uses), purely to decide how to line up playback
    of that one already-chosen block - it never changes WHICH block gets
    picked, only how it's phase-aligned once picked.
    """
    window_spectrum = magnitude_spectrum(window)
    best_idx, best_corr = -1, -1.0
    for idx, ref_spectrum in enumerate(palette_spectra):
        corr = normalized_correlation(window_spectrum, ref_spectrum)
        if corr > best_corr:
            best_idx, best_corr = idx, corr
    if best_idx == -1:
        return -1, 0, -1.0

    ref = palette[best_idx]
    best_shift, best_phase_corr = 0, -1.0
    for shift_ms in SHIFT_CHOICES_MS:
        shift = int(SAMPLE_RATE * shift_ms / 1000)
        shifted = np.roll(ref, shift)
        if shift > 0:
            shifted[:shift] = 0
        elif shift < 0:
            shifted[shift:] = 0
        phase_corr = normalized_correlation(window, shifted)
        if phase_corr > best_phase_corr:
            best_shift, best_phase_corr = shift, phase_corr
    return best_idx, best_shift, best_corr


def encode(samples: np.ndarray, block_seconds: float, threshold: float, match_domain: str = "time") -> tuple[list[EncodedBlock], list[np.ndarray]]:
    block_len = int(SAMPLE_RATE * block_seconds)
    palette: list[np.ndarray] = []
    palette_spectra: list[np.ndarray] = []
    blocks: list[EncodedBlock] = []

    for start in range(0, len(samples), block_len):
        window = samples[start:start + block_len]
        if len(window) < block_len:
            window = np.pad(window, (0, block_len - len(window)))

        if not palette:
            idx, shift, corr = -1, 0, -1.0
        elif match_domain == "spectral":
            idx, shift, corr = best_match_spectral(window, palette, palette_spectra)
        else:
            idx, shift, corr = best_match_time_domain(window, palette)

        if corr >= threshold:
            blocks.append(EncodedBlock("reuse", idx, shift, corr))
        else:
            palette.append(window.copy())
            palette_spectra.append(magnitude_spectrum(window))
            blocks.append(EncodedBlock("new", len(palette) - 1, 0, 1.0))

    return blocks, palette


def _resolved_block(b: EncodedBlock, palette: list[np.ndarray]) -> np.ndarray:
    ref = palette[b.palette_index]
    if not b.shift_samples:
        return ref
    shifted = np.roll(ref, b.shift_samples)
    if b.shift_samples > 0:
        shifted[:b.shift_samples] = 0
    elif b.shift_samples < 0:
        shifted[b.shift_samples:] = 0
    return shifted


def reconstruct(blocks: list[EncodedBlock], palette: list[np.ndarray]) -> np.ndarray:
    out = [_resolved_block(b, palette) for b in blocks]
    return np.concatenate(out) if out else np.array([])


def reconstruct_crossfaded(blocks: list[EncodedBlock], palette: list[np.ndarray], crossfade_ms: float) -> np.ndarray:
    """Same reconstruction as `reconstruct`, but overlaps every block
    boundary by a short linear crossfade instead of a hard cut - the
    standard fix for an audible click/jump at a splice point in
    concatenative audio. Harmless (effectively a no-op) at a "new"-to-"new"
    boundary, since those blocks were already sample-contiguous in the
    source audio to begin with; it only actually matters at a boundary
    touching a "reuse" block, where the two sides genuinely don't connect.
    """
    resolved = [_resolved_block(b, palette) for b in blocks]
    if not resolved:
        return np.array([])

    crossfade_len = int(SAMPLE_RATE * crossfade_ms / 1000)
    crossfade_len = min(crossfade_len, min(len(r) for r in resolved) // 2) if resolved else 0
    if crossfade_len <= 0:
        return np.concatenate(resolved)

    fade_out = np.linspace(1.0, 0.0, crossfade_len)
    fade_in = np.linspace(0.0, 1.0, crossfade_len)

    out = resolved[0].copy()
    for block in resolved[1:]:
        overlap = out[-crossfade_len:] * fade_out + block[:crossfade_len] * fade_in
        out = np.concatenate([out[:-crossfade_len], overlap, block[crossfade_len:]])
    return out


def encoded_size_bytes(blocks: list[EncodedBlock], palette: list[np.ndarray]) -> dict:
    # Palette: raw 16-bit PCM for every distinct block actually stored.
    palette_bytes = sum(len(p) for p in palette) * SAMPLE_WIDTH_BYTES
    # Map: 1 byte block-type tag + 2 bytes palette index (supports up to
    # 65536 distinct blocks) + 1 byte shift choice (index into
    # SHIFT_CHOICES_MS) per block, for every block in the song - this is the
    # "map of placements" the user described. "new" blocks don't need a
    # separate map entry beyond this tag, since the palette IS the block.
    map_bytes = len(blocks) * (1 + 2 + 1)
    return {
        "palette_bytes": palette_bytes,
        "map_bytes": map_bytes,
        "total_bytes": palette_bytes + map_bytes,
    }


def opus_roundtrip_palette(palette: list[np.ndarray], bitrate_kbps: int, tmp_dir: Path, stem: str) -> tuple[int, list[np.ndarray]]:
    """Encodes the whole palette - concatenated into ONE continuous stream,
    not one file per block - through real Opus compression, THEN decodes it
    back, and returns (compressed byte size, the decoded-back blocks).

    Concatenating first matters for the size number: encoding N small files
    separately would pay Ogg container/header overhead N times, which has
    nothing to do with the block-reuse idea being tested. A real
    implementation of this format would store one continuous compressed
    palette blob with a byte-offset index per block anyway.

    Decoding back matters for anyone who wants to actually *listen* to what
    the combined scheme sounds like: the blocks this returns carry real
    Opus quantization loss, not the pristine original PCM the no-`--opus-
    bitrate-kbps` reconstruction uses - that's the only way to hear the true
    combined (block-reuse + lossy compression) result without the listener
    needing any Opus tooling of their own, since the final reconstruction
    this feeds into is plain WAV. Opus's own encode/decode roundtrip can
    shift the total decoded length by a few dozen samples (encoder
    pre-skip/priming) - padded or trimmed back to the original concatenated
    length so per-block boundaries still line up with the palette's own,
    since the map was built against those boundaries.
    """
    if not palette:
        return 0, []
    tmp_dir.mkdir(parents=True, exist_ok=True)
    concatenated = np.concatenate(palette)
    wav_path = tmp_dir / f"_{stem}_palette_concat.wav"
    opus_path = tmp_dir / f"_{stem}_palette_concat.opus"
    decoded_path = tmp_dir / f"_{stem}_palette_concat_decoded.wav"
    save_wav_mono_i16(wav_path, concatenated)
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(wav_path),
            "-c:a", "libopus", "-b:a", f"{bitrate_kbps}k",
            str(opus_path),
        ],
        check=True,
        capture_output=True,
    )
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(opus_path),
            "-ac", "1", "-ar", str(SAMPLE_RATE), "-sample_fmt", "s16",
            str(decoded_path),
        ],
        check=True,
        capture_output=True,
    )
    compressed_bytes = opus_path.stat().st_size
    decoded = load_wav_mono_i16(decoded_path)
    if len(decoded) < len(concatenated):
        decoded = np.pad(decoded, (0, len(concatenated) - len(decoded)))
    else:
        decoded = decoded[:len(concatenated)]
    block_len = len(palette[0])
    decoded_blocks = [decoded[i * block_len:(i + 1) * block_len] for i in range(len(palette))]
    return compressed_bytes, decoded_blocks


def opus_only_size_bytes(samples: np.ndarray, bitrate_kbps: int, tmp_dir: Path, stem: str) -> int:
    """Plain Opus on a whole track, no block-reuse involved - just the
    compressed size, for the baseline comparison."""
    tmp_dir.mkdir(parents=True, exist_ok=True)
    wav_path = tmp_dir / f"_{stem}_whole_track.wav"
    opus_path = tmp_dir / f"_{stem}_whole_track.opus"
    save_wav_mono_i16(wav_path, samples)
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(wav_path),
            "-c:a", "libopus", "-b:a", f"{bitrate_kbps}k",
            str(opus_path),
        ],
        check=True,
        capture_output=True,
    )
    return opus_path.stat().st_size


def snr_db(original: np.ndarray, reconstructed: np.ndarray) -> float:
    n = min(len(original), len(reconstructed))
    o, r = original[:n], reconstructed[:n]
    signal_power = np.mean(o.astype(np.float64) ** 2)
    noise_power = np.mean((o.astype(np.float64) - r.astype(np.float64)) ** 2)
    if noise_power == 0:
        return float("inf")
    return float(10 * np.log10(signal_power / noise_power))


def run(
    source: Path,
    out_dir: Path,
    seconds: float,
    block_seconds: float,
    threshold: float,
    match_domain: str = "time",
    opus_bitrate_kbps: int | None = None,
    crossfade_ms: float = 0.0,
) -> dict:
    stem = source.stem.replace(" ", "_")
    decoded_wav = out_dir / f"{stem}__original.wav"
    reconstructed_wav = out_dir / f"{stem}__reconstructed_{match_domain}_{block_seconds}s_t{threshold}.wav"

    decode_to_wav(source, decoded_wav, seconds)
    samples = load_wav_mono_i16(decoded_wav)

    blocks, palette = encode(samples, block_seconds, threshold, match_domain)
    reconstructed = reconstruct(blocks, palette)
    save_wav_mono_i16(reconstructed_wav, reconstructed)

    sizes = encoded_size_bytes(blocks, palette)
    raw_pcm_bytes = len(samples) * SAMPLE_WIDTH_BYTES
    source_file_bytes = source.stat().st_size
    reuse_count = sum(1 for b in blocks if b.kind == "reuse")

    result = {
        "source": str(source),
        "seconds_analyzed": seconds,
        "block_seconds": block_seconds,
        "threshold": threshold,
        "total_blocks": len(blocks),
        "new_blocks": len(palette),
        "reused_blocks": reuse_count,
        "reuse_rate_pct": round(100 * reuse_count / len(blocks), 1) if blocks else 0.0,
        "raw_pcm_bytes": raw_pcm_bytes,
        # "Raw palette" = block-reuse savings only, palette still stored as
        # uncompressed 16-bit PCM - isolates what the user's own idea
        # contributes, separate from any general-purpose audio compression.
        "raw_palette_encoded_bytes": sizes["total_bytes"],
        "raw_palette_ratio_vs_raw_pcm": round(raw_pcm_bytes / sizes["total_bytes"], 2) if sizes["total_bytes"] else None,
        "original_lossy_file_bytes": source_file_bytes,
        "reconstruction_snr_db": round(snr_db(samples, reconstructed), 1),
        "decoded_wav": str(decoded_wav),
        "reconstructed_wav": str(reconstructed_wav),
    }

    if crossfade_ms > 0:
        crossfaded_wav = out_dir / f"{stem}__reconstructed_{match_domain}_{block_seconds}s_t{threshold}_xfade{crossfade_ms:g}ms.wav"
        crossfaded = reconstruct_crossfaded(blocks, palette, crossfade_ms)
        save_wav_mono_i16(crossfaded_wav, crossfaded)
        result.update({
            "crossfade_ms": crossfade_ms,
            "crossfaded_reconstruction_snr_db": round(snr_db(samples, crossfaded), 1),
            "crossfaded_reconstructed_wav": str(crossfaded_wav),
        })

    if opus_bitrate_kbps:
        opus_bytes, decoded_palette = opus_roundtrip_palette(palette, opus_bitrate_kbps, out_dir / "_tmp", stem)
        combined_bytes = opus_bytes + sizes["map_bytes"]
        # The actual compressed artifact, kept as a permanent named file (not
        # left in _tmp) since comparing ITS size against the WAVs above is
        # the only size comparison that means anything - every WAV here is
        # raw PCM and will always be ~the same size regardless of what
        # compression happened upstream of it.
        kept_opus_path = out_dir / f"{stem}__palette_{opus_bitrate_kbps}k.opus"
        (out_dir / "_tmp" / f"_{stem}_palette_concat.opus").replace(kept_opus_path)

        # The actual "what would this sound like for real" artifact: same
        # reuse map, but replaying it against the Opus-roundtripped palette
        # instead of the pristine original PCM - carries real Opus
        # quantization loss on top of the block-substitution loss. Plain
        # WAV, so it needs no Opus player/decoder to listen to - that's the
        # whole point of rendering it here rather than handing over the raw
        # .opus file.
        opus_reconstructed_wav = out_dir / f"{stem}__reconstructed_{match_domain}_{block_seconds}s_t{threshold}_opus{opus_bitrate_kbps}k.wav"
        opus_reconstructed = reconstruct(blocks, decoded_palette)
        save_wav_mono_i16(opus_reconstructed_wav, opus_reconstructed)

        if crossfade_ms > 0:
            # The one that actually matters for listening: block-reuse +
            # real Opus quantization loss + crossfaded boundaries, all
            # stacked - the true "best version of this idea" result.
            opus_crossfaded_wav = out_dir / f"{stem}__reconstructed_{match_domain}_{block_seconds}s_t{threshold}_opus{opus_bitrate_kbps}k_xfade{crossfade_ms:g}ms.wav"
            opus_crossfaded = reconstruct_crossfaded(blocks, decoded_palette, crossfade_ms)
            save_wav_mono_i16(opus_crossfaded_wav, opus_crossfaded)
            result.update({
                "opus_crossfaded_reconstruction_snr_db": round(snr_db(samples, opus_crossfaded), 1),
                "opus_crossfaded_reconstructed_wav": str(opus_crossfaded_wav),
            })

        result.update({
            "opus_bitrate_kbps": opus_bitrate_kbps,
            # Palette audio actually compressed with Opus, PLUS the same
            # tiny reuse map on top - the real "stack both savings" number.
            "opus_palette_bytes": opus_bytes,
            "combined_encoded_bytes": combined_bytes,
            "combined_ratio_vs_raw_pcm": round(raw_pcm_bytes / combined_bytes, 2) if combined_bytes else None,
            "combined_ratio_vs_original_lossy_file": round(source_file_bytes / combined_bytes, 2) if combined_bytes else None,
            # Opus on the FULL original track with no block-reuse at all -
            # the baseline this scheme actually needs to beat to be worth
            # the added complexity (decoder needs a reuse map AND an Opus
            # decoder either way).
            "opus_only_baseline_bytes": opus_only_size_bytes(samples, opus_bitrate_kbps, out_dir / "_tmp", stem),
            "opus_reconstruction_snr_db": round(snr_db(samples, opus_reconstructed), 1),
            "opus_reconstructed_wav": str(opus_reconstructed_wav),
            "actual_compressed_palette_file": str(kept_opus_path),
        })

    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--seconds", type=float, default=60.0)
    parser.add_argument("--block-seconds", type=float, default=2.5)
    parser.add_argument("--threshold", type=float, default=0.92)
    parser.add_argument("--match-domain", choices=["time", "spectral"], default="time")
    parser.add_argument("--opus-bitrate-kbps", type=int, default=None, help="Also Opus-compress the palette (and compute an Opus-only baseline) at this bitrate.")
    parser.add_argument("--crossfade-ms", type=float, default=0.0, help="Crossfade this many ms at every block boundary instead of a hard cut.")
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()

    result = run(args.source, args.out_dir, args.seconds, args.block_seconds, args.threshold, args.match_domain, args.opus_bitrate_kbps, args.crossfade_ms)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
