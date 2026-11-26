"""
Second-opinion version of analyze_similarity.py: raw time-domain sample
correlation (what block_reuse_codec.py actually uses) collapses towards zero
from even a handful of microseconds of misalignment between two "same"
musical passages - real rendered audio essentially never repeats bit-for-bit.
That could be hiding genuine musical repetition behind a metric that's just
too strict.

This instead compares each block's magnitude spectrum (FFT magnitude, which
is phase/micro-timing-invariant) - the same style of representation real
audio fingerprinting and chorus-detection tools use - to see whether
*perceptual* content repeats even when raw PCM doesn't.

Usage:
    .venv/Scripts/python.exe analyze_spectral_similarity.py <source audio file> \
        [--seconds 60] [--block-seconds 2.5]
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import numpy as np

from block_reuse_codec import SAMPLE_RATE, decode_to_wav, load_wav_mono_i16


def magnitude_spectrum(block: np.ndarray) -> np.ndarray:
    windowed = block * np.hanning(len(block))
    spectrum = np.fft.rfft(windowed)
    return np.abs(spectrum)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--seconds", type=float, default=60.0)
    parser.add_argument("--block-seconds", type=float, default=2.5)
    args = parser.parse_args()

    with tempfile.TemporaryDirectory() as tmp:
        wav_path = Path(tmp) / "decoded.wav"
        decode_to_wav(args.source, wav_path, args.seconds)
        samples = load_wav_mono_i16(wav_path)

    block_len = int(SAMPLE_RATE * args.block_seconds)
    blocks = []
    for start in range(0, len(samples), block_len):
        w = samples[start:start + block_len]
        if len(w) < block_len:
            w = np.pad(w, (0, block_len - len(w)))
        blocks.append(w)

    spectra = [magnitude_spectrum(b) for b in blocks]

    best_per_block = []
    for i, a in enumerate(spectra):
        best = -1.0
        for j, b in enumerate(spectra):
            if i == j:
                continue
            denom = np.linalg.norm(a) * np.linalg.norm(b)
            corr = float(np.dot(a, b) / denom) if denom else 0.0
            best = max(best, corr)
        best_per_block.append(best)

    arr = np.array(best_per_block)
    result = {
        "source": str(args.source),
        "domain": "spectral_magnitude (phase-invariant)",
        "seconds_analyzed": args.seconds,
        "block_seconds": args.block_seconds,
        "num_blocks": len(best_per_block),
        "best_possible_match_per_block": [round(c, 3) for c in best_per_block],
        "max_correlation": round(float(arr.max()), 3),
        "mean_correlation": round(float(arr.mean()), 3),
        "median_correlation": round(float(np.median(arr)), 3),
        "pct_blocks_above_0.95": round(100 * float((arr >= 0.95).mean()), 1),
        "pct_blocks_above_0.90": round(100 * float((arr >= 0.90).mean()), 1),
        "pct_blocks_above_0.80": round(100 * float((arr >= 0.80).mean()), 1),
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
