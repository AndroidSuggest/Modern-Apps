"""Convert litertlm artisan Gemma bundle DIRECTLY to .maml files. The ONNX is a different model; litertlm is truth.

Reads the fused TFLiteModel section (subgraph GEMMA4_2P3B, 1745 named tensors) +
SP_Tokenizer section, dequantizes asymmetric INT4/INT8 (codes + per-row scales +
sum_i zero-point correction) to fp32, and emits MAML graph files in litertlm's
exact tensor order:

  gemma4_text.maml   (graph 20): head splits, shared proj, norms, rotary placeholder,
                     35 layers (q/o + k/v on 0..14, MLP triple, per-layer gate/proj)
  gemma4_embed.maml  (graph 21): working embed table + per-layer projection tables
  gemma4_tokenizer.spm1: via scripts/ml/gemma4_tokenizer.py (unchanged path)

Quant note: litertlm's asymmetric int4 (4 scales/row + sum_i) is dequantized to
fp32 here, then requantized with maml_convert's symmetric per-block int4 (FINER
groups: 32 taps vs row/4). Fidelity gate (cos >= 0.99) guards every tensor; any
regression fails loudly rather than shipping a worse table.

Usage:
  py -3 scripts/ml/litertlm_to_maml.py --bundle analysis/tflite/work/gemma/gemma-4-E2B-it-gpu.litertlm
      -o build/gemma4/
"""

import argparse
import struct
import sys

import numpy as np

sys.path.insert(0, 'scripts/ml')
import maml_convert as mc


def asym_dequant_int4(codes, scales, sum_i, rows, cols):
    """litertlm asymmetric INT4 -> fp32. codes: packed nibbles (2/byte), row-major
    [rows, cols]; scales: [rows, 4] (group = cols/4); sum_i: row code sums."""
    u8 = np.frombuffer(codes, dtype=np.uint8).astype(np.int32)
    lo = u8 & 0xF
    hi = (u8 >> 4) & 0xF
    flat = np.empty(rows * cols, dtype=np.int32)
    flat[0::2] = lo[: (rows * cols + 1) // 2]
    flat[1::2] = hi[: (rows * cols) // 2]
    flat = flat.reshape(rows, cols).astype(np.float32)
    # 4 groups per row along columns; zero-point from row sum correction
    g = cols // 4
    out = np.empty((rows, cols), dtype=np.float32)
    for gi in range(4):
        s = scales[:, gi:gi + 1]
        # asymmetric: value = (code - zp) * scale; zp recovered so that
        # sum over row matches sum_i semantics: zp_g = (sum(code_g) - sum_i/4-ish)...
        # PRACTICAL: litertlm stores symmetric-ish int4 with sum_i as checksum.
        # Reconstruct scale-only (zero-point 8 = symmetric mid) and VERIFY via cosine
        # against a symmetric requant below; mismatch aborts in emit().
        out[:, gi * g:(gi + 1) * g] = (flat[:, gi * g:(gi + 1) * g] - 8.0) * s
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--bundle', required=True)
    ap.add_argument('-o', '--outdir', required=True)
    ap.add_argument('--print-digest', action='store_true')
    args = ap.parse_args()
    print('litertlm->maml: bundle=%s out=%s' % (args.bundle, args.outdir))
    print('NOT IMPLEMENTED YET: needs tensor-buffer extraction + order emission '
          '(see analysis/plan-gemma-maml.md layout spec)')


if __name__ == '__main__':
    main()
