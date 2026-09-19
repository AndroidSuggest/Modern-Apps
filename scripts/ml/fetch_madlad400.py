#!/usr/bin/env python3
"""Fetch MADLAD400-3B-MT's checkpoint and build the files `:translate` downloads.

Mirrors `fetch_nllb600.py`: pin the HF revision, download + digest-check every input,
assert `config.json` against transcribed architecture constants, assert the embedding
is untied (shared vs lm_head differ, so emit both), build Unigram `tokenizer.bin`
from `spiece.model`, convert `model.safetensors` (fp32) to `madlad400.maml` via
`maml_convert.py --graph madlad400`.

It is not folded into `fetch_and_convert.sh` because these are **runtime downloads**,
not bundled assets: the output goes to a build directory and then to the mirror.

# The decode protocol (contract with rust-eng's `nets::madlad`, read from transformers)

* **Source side:** the target-language tag LEADS the source sequence:
  `[<2tgt>, pieces..., </s>]`. MADLAD is trained with the target tag as a source
  prefix, unlike NLLB's source-lang token. Easy to get backwards.
* **Target side:** the decoder's first input is `decoder_start_token_id` = 0
  (`<unk>`, which doubles as the start token). Decode step 0 input is `[0]`,
  then autoregressive. No forced-BOS override.
* **Positions:** none learned and none sinusoidal. T5 uses relative position
  biases computed on the host (`nets::madlad::relative_bias`) and added to the
  score maps as plan inputs. The embedding is gathered with NO scale.
* **FFN activation:** gated-gelu (`feed_forward_proj: gated-gelu` in config.json):
  `wo(GELU(wi_0(x)) * wi_1(x))`, via the fused `GatedActivate` op with `Act::Gelu`.
* **Norms:** RMSNorm (`T5LayerNorm`, gamma only, eps 1e-6), pre-norm like NLLB.
* **Embeddings:** untied (`tie_word_embeddings: false`) — `shared.weight` is the
  encoder/decoder input table and `lm_head.weight` the logits kernel, emitted as
  two separate 4-way head splits.
* **Specials:** UNK 0 (`<unk>`), PAD 1 (`<s>`), EOS 2 (`</s>`). The tokenizer is
  Unigram (type 1): Viterbi over log-probabilities, NOT greedy BPE merging.

# The pins, and what each one catches

* **File SHA-256** below. Catches an upstream re-export at the same revision, or a
  truncated download.
* **The parameter inventory** `maml_convert.check_checkpoint` asserts (encoder 32 +
  decoder 32 layers + norms + untied head). Catches a transposed or resized
  checkpoint; `wi_0 [10240, 1024]` / `wo [1024, 8192]` is the asymmetric set.
* **The layer table digest** `maml_convert.EXPECTED_DIGEST["madlad400"]` pins the
  emission order.
* **`config.json`**, fetched and checked but not converted — the ARCHITECTURE constants
  below are transcribed from it and `nets::madlad` hardcodes them.

Usage:

    python scripts/ml/fetch_madlad400.py                 # verify digests, build the files
    python scripts/ml/fetch_madlad400.py --work DIR      # keep the checkpoint somewhere specific
    python scripts/ml/fetch_madlad400.py --print-digests # after an intentional upstream bump
"""

import argparse
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile

REPO = "google/madlad400-3b-mt"

# Pinned commit. Re-pin with --print-digests after an intentional bump (and only then).
REVISION = "fa184c675da0b5c9e1c8694fccd4e12e2d422094"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

# Not an assets directory: these files are fetched at run time from
# `data.vayunmathur.com/models/madlad400/`, so the build only has to produce them for upload.
OUT = os.path.join(ROOT, "build", "madlad400")

CHECKPOINT = "model.safetensors"
SPM = "spiece.model"
TOKENIZER = "tokenizer.json"
CONFIG = "config.json"
GENERATION_CONFIG = "generation_config.json"
SPECIAL_TOKENS = "special_tokens_map.json"
TOKENIZER_CONFIG = "tokenizer_config.json"

# SHA-256 of every file this reads, at the pinned revision. Fill in with --print-digests
# on the first run (placeholder zeros fail loudly until then).
DIGESTS = {
    CHECKPOINT: "66ff5f8fcaf92291da486fdfbd4d5233cec90e1359348a56e3172c978b3a76d4",
    SPM: "ef11ac9a22c7503492f56d48dce53be20e339b63605983e9f27d2cd0e0f3922c",
    TOKENIZER: "a2799ccc696b752ba00c34f58726bfe253a04921ceb6cfc620400f560474790b",
    CONFIG: "cad399cab799b99409a6ec2d90d72552257c2bb752861261d2016691e0643e7c",
    GENERATION_CONFIG: "0849b38987568ccfe4ebefc22bbda1cec4bee01c345e93bfab207d4692b0a1d5",
    SPECIAL_TOKENS: "7f79f1d5063d56c4b980eec0692f3c7429bdef335071d34e566bd00fd4b5e3e0",
    TOKENIZER_CONFIG: "641fc660745306dfb935f666a68f8bc10a44c39241cfb357be518fda8c09662d",
}

# Transcribed from the pinned `config.json`, and asserted against it below rather
# than trusted. `nets::madlad` is written against these.
ARCHITECTURE = {
    "d_model": 1024,
    "d_kv": 128,
    "num_heads": 16,
    "num_layers": 32,
    "num_decoder_layers": 32,
    "d_ff": 8192,
    "vocab_size": 256_000,
    "feed_forward_proj": "gated-gelu",
    "tie_word_embeddings": False,
    "relative_attention_num_buckets": 32,
    "relative_attention_max_distance": 128,
    "layer_norm_epsilon": 1e-06,
    "pad_token_id": 1,
    "eos_token_id": 2,
    "decoder_start_token_id": 0,
    "model_type": "t5",
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def convert(script, *args):
    """Run one of the sibling converters, letting its own output and exit code through."""
    subprocess.run([sys.executable, os.path.join(HERE, script), *args], check=True, cwd=ROOT)


def check_architecture(path):
    """Fail if the checkpoint's config is not the one `nets::madlad` is written against."""
    held = json.load(open(path, encoding="utf-8"))
    wrong = {k: (held.get(k), v) for k, v in ARCHITECTURE.items() if held.get(k) != v}
    if wrong:
        lines = "\n".join(f"  {k}: {got!r} against {want!r}" for k, (got, want) in wrong.items())
        raise SystemExit(
            f"{path} is not the architecture the runtime hardcodes:\n{lines}\n"
            "nets::madlad has these baked in, so re-read it before re-pinning."
        )
    print(f"config.json matches {len(ARCHITECTURE)} hardcoded architecture constants")


def check_untied(checkpoint_path):
    """Fail unless the input and output tables differ (so emitting both is correct).

    The mirror of `fetch_nllb600.check_tied`: NLLB asserts its four copies are
    byte-identical and emits one; MADLAD's `tie_word_embeddings` is false, so the
    input table (`decoder.embed_tokens.weight` — this export materialised only the
    decoder clone, not `shared.weight`) and `lm_head.weight` must differ and the
    converter emits both. If they ever matched, emitting both would put ~250 MiB
    back into the download for nothing.
    """
    from safetensors import safe_open

    handle = safe_open(checkpoint_path, framework="np")
    shared = handle.get_tensor("decoder.embed_tokens.weight")
    head = handle.get_tensor("lm_head.weight")
    if shared.shape != head.shape or bool((shared == head).all()):
        raise SystemExit(
            "decoder.embed_tokens.weight and lm_head.weight are identical: the embedding "
            "is tied, so emitting both copies is wrong. Re-read nets::madlad's head handling."
        )
    print(f"untied embedding confirmed: decoder.embed_tokens {list(shared.shape)} != lm_head (values differ)")


def read_varint(blob, at):
    value = 0
    shift = 0
    while True:
        if at >= len(blob):
            raise SystemExit("a varint runs off the end of the model")
        byte = blob[at]
        at += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, at
        shift += 7


def proto_fields(blob):
    at = 0
    while at < len(blob):
        tag, at = read_varint(blob, at)
        number, wire = tag >> 3, tag & 7
        if wire == 2:
            length, at = read_varint(blob, at)
            yield number, wire, blob[at : at + length]
            at += length
        elif wire == 5:
            yield number, wire, blob[at : at + 4]
            at += 4
        elif wire == 0:
            value, at = read_varint(blob, at)
            yield number, wire, value
        else:
            raise SystemExit(f"unhandled protobuf wire type {wire} in the model")


# `trainer_spec.model_type`, from sentencepiece's ModelType enum. Unigram, not BPE:
# encoding is Viterbi over log-probabilities, and the Rust merge loop would be wrong.
MODEL_TYPE_UNIGRAM = 1


def read_unigram(spm_path):
    """`[(piece, score)]` in id order from `spiece.model`, asserting Unigram + identity norm.

    Scores are log-probabilities (negative floats, NOT merge ranks). The Rust
    `Table::encode_unigram` runs Viterbi over them.
    """
    import re

    blob = open(spm_path, "rb").read()
    pieces = []
    checked_type = False
    for number, wire, payload in proto_fields(blob):
        if number == 1:
            piece, score = None, 0.0
            for inner, w, value in proto_fields(payload):
                if inner == 1:
                    piece = value
                elif inner == 2 and w == 5:
                    score = struct.unpack("<f", value)[0]
            if piece is None:
                raise SystemExit(f"{spm_path}: a piece with no `piece` field")
            pieces.append((piece, score))
        elif number == 2:
            for inner, _, value in proto_fields(payload):
                if inner == 3:
                    checked_type = True
                    if value != MODEL_TYPE_UNIGRAM:
                        raise SystemExit(
                            f"{spm_path}: trainer_spec.model_type is {value}, not Unigram"
                            f" ({MODEL_TYPE_UNIGRAM}); the Viterbi path in post::sentencepiece only"
                            " applies to Unigram"
                        )
        elif number == 3:
            for inner, _, value in proto_fields(payload):
                if inner == 1 and value.decode("utf-8") != "identity":
                    raise SystemExit(
                        f"{spm_path}: normalizer is {value.decode('utf-8')!r}, not identity;"
                        " the Kotlin caller must not NFKC-normalise"
                    )
    if not checked_type:
        raise SystemExit(f"{spm_path}: no trainer_spec.model_type, so this may not be Unigram")
    if len(pieces) != 256_000:
        raise SystemExit(f"{spm_path}: {len(pieces)} pieces, not the 256000 the runtime hardcodes")
    # T5 specials, in T5's order — the Rust `Flavour::T5` checks these, restated here so
    # a re-export that moved them fails at fetch rather than at inference.
    names = [pieces[i][0].decode("utf-8") for i in range(3)]
    if names != ["<unk>", "<s>", "</s>"]:
        raise SystemExit(f"{spm_path}: ids 0..2 are {names}, not T5's <unk> <s> </s>")
    # Byte fallback must be complete: 256 `<0xNN>` pieces, or uncovered bytes are lost.
    have = {p.decode("utf-8") for p, _ in pieces}
    missing = [f"<0x{b:02X}>" for b in range(256) if f"<0x{b:02X}>" not in have]
    if missing:
        raise SystemExit(f"{spm_path}: byte fallback incomplete, missing {missing[:4]}...")
    print(
        f"{os.path.basename(spm_path)}: {len(pieces)} Unigram pieces, identity normalizer, "
        "byte fallback complete"
    )
    return pieces


def write_tokenizer(pieces, out_path):
    """The `SPM1` blob for Unigram `[(piece, score)]` in id order.

    Same container as `nllb_tokenizer.pack`: magic, count, then `(score i32, len u16,
    bytes)` per id. Unigram log-probabilities are floats, so they are stored as
    score * 1e6 rounded to an i32 — exact to 1e-6, far below fp16 resolution, and
    it keeps the table format (and its parser) shared between the two models.
    """
    out = bytearray(b"SPM1")
    out += struct.pack("<I", len(pieces))
    for piece, score in pieces:
        if len(piece) > 0xFFFF:
            raise SystemExit(f"a piece of {len(piece)} bytes does not fit a u16 length")
        quantised = int(round(score * 1_000_000))
        out += struct.pack("<iH", quantised, len(piece))
        out += piece
    with open(out_path, "wb") as f:
        f.write(bytes(out))
    print(f"{os.path.basename(out_path)}: {len(pieces)} pieces, {len(out)} bytes")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--work", help="where to keep the checkpoint (default: a temp directory)")
    parser.add_argument("-o", "--out", default=OUT, help="where to write the files")
    parser.add_argument(
        "--print-digests",
        action="store_true",
        help="print the fetched files' SHA-256 instead of checking them, to re-pin after a bump",
    )
    parser.add_argument(
        "--local", help="use an already-downloaded snapshot dir instead of downloading"
    )
    args = parser.parse_args()

    try:
        from huggingface_hub import snapshot_download
    except ImportError:
        raise SystemExit("needs huggingface_hub: python -m pip install huggingface_hub")

    if args.local:
        local = args.local
    else:
        work = args.work or os.path.join(tempfile.gettempdir(), "madlad400")
        local = snapshot_download(
            repo_id=REPO, revision=REVISION, allow_patterns=list(DIGESTS), local_dir=work
        )
    print(f"{REPO}@{REVISION[:12]} in {local}")

    if args.print_digests:
        for name in DIGESTS:
            print(f'    {name}: "{sha256(os.path.join(local, name))}",')
        return 0

    for name, expected in DIGESTS.items():
        if expected.startswith("0000"):
            raise SystemExit(
                f"{name}: no pinned digest yet. Run with --print-digests once the files "
                "are downloaded, verify the revision, and pin them here."
            )
        got = sha256(os.path.join(local, name))
        if got != expected:
            raise SystemExit(
                f"{name}\n  sha256 {got}\n  pinned {expected}\n"
                "The upstream export changed. Re-read it, check the Rust forward pass still\n"
                "matches, then re-pin with --print-digests."
            )
    print(f"{len(DIGESTS)} upstream digests match")
    check_architecture(os.path.join(local, CONFIG))
    check_untied(os.path.join(local, CHECKPOINT))

    os.makedirs(args.out, exist_ok=True)
    pieces = read_unigram(os.path.join(local, SPM))
    write_tokenizer(pieces, os.path.join(args.out, "tokenizer.bin"))
    convert(
        "maml_convert.py",
        os.path.join(local, CHECKPOINT),
        "--graph", "madlad400",
        "-o", os.path.join(args.out, "madlad400.maml"),
        "--print-digest",
    )

    print(f"\n{args.out}")
    for name in sorted(os.listdir(args.out)):
        path = os.path.join(args.out, name)
        if os.path.isfile(path):
            size = os.path.getsize(path)
            print(f"  {name}: {size} bytes ({size / (1 << 20):.1f} MiB) {sha256(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
