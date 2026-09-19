#!/usr/bin/env python3
"""Dump all 262144 S2 embedder rows to exact fp32 (A1 fallback).

The artisan 2-bit `input_embedding.w` packing is undecoded (every mapping
tried gives cosine ~0 vs S2 rows, histograms differ, no row permutation
helps). S2's own `embedder` signature output IS the reference embedding the
converter claims to transcribe, so dump it directly and hand the exact rows
to the existing `fid.quantise4` path in `collect_embed`.

Writes raw fp32 [262144, 1536] to the given path (~1.6GB; read back with
`np.memmap(path, dtype=np.float32, shape=(262144, 1536))`). Fast (~1 min:
the signature is a gather+mul at ~5us/call).

    python scripts/ml/gemma4_embed_dump.py -o build/gemma4/embed_s2_fp32.bin
"""
import argparse
import os
import sys
import time

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

VOCAB = 262144
DIM = 1536


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--out", required=True, help="output raw fp32 path (.bin)")
    ap.add_argument("--s2", default="analysis/base-sections/Section2_TFLiteModel_tf_lite_embedder.tflite")
    ap.add_argument("--resume", action="store_true",
                    help="continue into existing .npy, skipping nonzero rows")
    args = ap.parse_args()

    from ai_edge_litert.interpreter import Interpreter
    s2 = Interpreter(model_path=args.s2).get_signature_runner("embedder")

    if args.resume and os.path.exists(args.out):
        all_rows = np.memmap(args.out, dtype=np.float32, mode="r+", shape=(VOCAB, DIM))
        done = int((all_rows.any(axis=1)).sum())
        print(f"resuming {args.out}: {done}/{VOCAB} rows present")
        start = done
    else:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        all_rows = np.memmap(args.out, dtype=np.float32, mode="w+", shape=(VOCAB, DIM))
        start = 0
        # probe row 0 to fail fast on a bad section file
        probe = s2(token_ids=np.array([[0]], np.int32))["embeddings"].ravel()
        assert probe.shape == (DIM,), probe.shape
        print(f"probe row 0: rms {float(np.sqrt((probe.astype(np.float64) ** 2).mean())):.4f}")

    t0 = time.time()
    for tok in range(start, VOCAB):
        row = s2(token_ids=np.array([[tok]], np.int32))["embeddings"].ravel()
        all_rows[tok] = row
        if (tok + 1) % 32768 == 0:
            dt = time.time() - t0
            print(f"  {tok + 1}/{VOCAB} ({dt:.0f}s, {(tok + 1 - start) / dt:.0f} rows/s)",
                  flush=True)
    all_rows.flush()
    dt = time.time() - t0
    print(f"wrote {args.out} ({VOCAB}x{DIM} fp32) in {dt:.0f}s")
    # validate: reload rows for the parity prompt tokens and compare vs live S2
    check = np.memmap(args.out, dtype=np.float32, mode="r", shape=(VOCAB, DIM))
    worst = 0.0
    for tok in [2, 818, 5279, 529, 7001, 563]:
        live = s2(token_ids=np.array([[tok]], np.int32))["embeddings"].ravel().astype(np.float64)
        got = check[tok].astype(np.float64)
        cos = float(live @ got / max(1e-30, np.linalg.norm(live) * np.linalg.norm(got)))
        worst = max(worst, abs(1.0 - cos))
        print(f"  tok {tok}: dump-vs-live cosine {cos:.6f}")
    assert worst < 1e-6, f"dump mismatch: {worst}"
    print("dump self-check PASS")


if __name__ == "__main__":
    raise SystemExit(main())
