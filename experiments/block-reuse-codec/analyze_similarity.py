"""
Diagnostic companion to block_reuse_codec.py: instead of committing to one
correlation threshold up front, this reports the actual best-achievable
match quality for every block against every OTHER block in the track (not
just previously-seen palette entries), so we can see the real ceiling of
"how similar do same-length chunks of this song ever get" before deciding
whether any threshold could plausibly work.

Usage:
    .venv/Scripts/python.exe analyze_similarity.py <source audio file> \
        [--seconds 60] [--block-seconds 2.5]
"""
from __future__ import annotations

import argparse
import json
import tempfile
from pathlib import Path

import numpy as np

from block_reuse_codec import (
    SAMPLE_RATE,
    SHIFT_CHOICES_MS,
    decode_to_wav,
    load_wav_mono_i16,
    normalized_correlation,
)


def best_pairwise_correlations(samples: np.ndarray, block_seconds: float) -> list[float]:
    block_len = int(SAMPLE_RATE * block_seconds)
    blocks = []
    for start in range(0, len(samples), block_len):
        w = samples[start:start + block_len]
        if len(w) < block_len:
            w = np.pad(w, (0, block_len - len(w)))
        blocks.append(w)

    best_per_block = []
    for i, a in enumerate(blocks):
        best = -1.0
        for j, b in enumerate(blocks):
            if i == j:
                continue
            for shift_ms in SHIFT_CHOICES_MS:
                shift = int(SAMPLE_RATE * shift_ms / 1000)
                shifted = np.roll(b, shift)
                if shift > 0:
                    shifted[:shift] = 0
                elif shift < 0:
                    shifted[shift:] = 0
                corr = normalized_correlation(a, shifted)
                if corr > best:
                    best = corr
        best_per_block.append(best)
    return best_per_block


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

    correlations = best_pairwise_correlations(samples, args.block_seconds)
    arr = np.array(correlations)
    result = {
        "source": str(args.source),
        "seconds_analyzed": args.seconds,
        "block_seconds": args.block_seconds,
        "num_blocks": len(correlations),
        "best_possible_match_per_block": [round(c, 3) for c in correlations],
        "max_correlation": round(float(arr.max()), 3),
        "mean_correlation": round(float(arr.mean()), 3),
        "median_correlation": round(float(np.median(arr)), 3),
        "pct_blocks_above_0.92": round(100 * float((arr >= 0.92).mean()), 1),
        "pct_blocks_above_0.80": round(100 * float((arr >= 0.80).mean()), 1),
        "pct_blocks_above_0.60": round(100 * float((arr >= 0.60).mean()), 1),
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
