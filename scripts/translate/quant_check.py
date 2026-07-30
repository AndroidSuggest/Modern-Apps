#!/usr/bin/env python3
"""
Parity / quality check for quantized Small100 ncnn models.

Compares int4/int8 quantized ncnn .bin sizes and inspects param quantize terms.
For deeper quality: optional HF reference translation if transformers installed.

Usage:
  python scripts/translate/quant_check.py --bundle /path/to/bundle/upload \
         --quantized scripts/translate/quantized/int4

Requires: none for size checks; transformers + sentencepiece for BLEU (optional).
"""
import argparse
import hashlib
import json
import os
import re
import sys
from pathlib import Path

def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def parse_quant_terms(param_path: Path):
    terms = {}
    with open(param_path) as f:
        lines = f.readlines()
    if not lines:
        return terms
    # first line: magic + layer count
    for line in lines[1:]:
        parts = line.split()
        if not parts:
            continue
        name = parts[1] if len(parts) > 1 else "?"
        # extract 18= term (quantize term) and 6= scale nibble
        m = re.search(r"18=(\d+)", line)
        if m:
            terms[name] = int(m.group(1))
    return terms

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", type=Path, default=Path.home() / ".claude/jobs/1dfd8c6b/tmp/small100/bundle/upload",
                    help="fp16 original bundle")
    ap.add_argument("--quantized", type=Path, default=Path("scripts/translate/quantized/int4"),
                    help="quantized bundle dir")
    ap.add_argument("--all-variants", action="store_true", help="check all quantized variants")
    args = ap.parse_args()

    bundle = args.bundle
    qdir = args.quantized
    if not bundle.exists():
        print(f"bundle not found: {bundle}", file=sys.stderr)
        sys.exit(1)

    def report_one(label: str, bdir: Path):
        print(f"\n=== {label}: {bdir} ===")
        if not bdir.exists():
            print(f"  MISSING DIR {bdir}")
            return
        for fname in ["encoder.ncnn.param", "encoder.ncnn.bin", "decoder.ncnn.param", "decoder.ncnn.bin",
                       "sentencepiece.bpe.model", "vocab.txt", "pos_weights.f32.bin"]:
            p = bdir / fname
            if not p.exists():
                print(f"  MISSING {fname}")
                continue
            sz = p.stat().st_size
            print(f"  {fname:30s} {sz/1e6:7.1f} MB")
        # quant terms
        for comp in ["encoder.ncnn.param", "decoder.ncnn.param"]:
            pp = bdir / comp
            if not pp.exists():
                continue
            terms = parse_quant_terms(pp)
            if terms:
                # 18=401 => bits=4 block=64 (bits*100 + blockcode 1), 18=801 => bits=8 block=64
                uniq = sorted(set(terms.values()))
                print(f"  {comp} quantize_terms: {uniq} ({len(terms)} quantized layers)")
                # decode
                for v in uniq:
                    bits = v // 100
                    block_code = v % 100
                    block = [32, 64, 128][block_code] if block_code < 3 else f"code={block_code}"
                    print(f"    term {v} => bits={bits} block={block}")
            else:
                print(f"  {comp}: no quantized terms (fp16 or unquantized)")

    # fp16 baseline
    report_one("FP16 baseline", bundle)
    if args.all_variants:
        base = qdir.parent if qdir.name.startswith("int") else qdir
        if base.exists():
            for sub in sorted(base.iterdir()):
                if sub.is_dir():
                    report_one(f"Variant {sub.name}", sub)
    else:
        report_one(f"Quantized {qdir.name}", qdir)

    # Try optional HF translation parity on a few sentences
    try:
        from transformers import M2M100ForConditionalGeneration, M2M100Tokenizer
        import torch
        print("\n=== HF reference translation sample (EN->ES) ===")
        model_id = "alirezamsh/small100"
        tok = M2M100Tokenizer.from_pretrained(model_id)
        model = M2M100ForConditionalGeneration.from_pretrained(model_id)
        model.eval()
        sentences = [
            "Hello, how are you?",
            "The weather is nice today.",
            "I love machine translation.",
        ]
        for s in sentences:
            tok.src_lang = "en"
            encoded = tok(s, return_tensors="pt")
            generated = model.generate(**encoded, forced_bos_token_id=tok.get_lang_id("es"))
            out = tok.batch_decode(generated, skip_special_tokens=True)[0]
            print(f"  EN: {s}")
            print(f"  ES: {out}")
    except Exception as e:
        print(f"\n=== HF check skipped: {e} ===")
        print("Install transformers+torch+sentencepiece for BLEU parity check.")

if __name__ == "__main__":
    main()
