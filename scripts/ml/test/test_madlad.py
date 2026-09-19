#!/usr/bin/env python3
"""test_madlad.py - unit test for `maml_convert`'s MADLAD checkpoint path.

Mirrors `test_nllb.py`: `collect_madlad` is a converter arm with no ONNX graph
behind it, so `check_checkpoint` is the whole guard and `collect_madlad`'s emission
*order* is the whole contract with `nets::madlad`. Both are checked here against a
toy model of the same shape rather than the real 12 GB checkpoint, so this runs in
a second and needs no download.

What each check is defending against (NLLB's five, plus MADLAD's own):

* **The transpose.** A `torch.nn.Linear` weight is `[out, in]` and a 1x1 kernel is
  `[out, in, 1, 1]`, so a projection is a reshape. `wi_0` and `wo` are the asymmetric
  pair that makes it loud.
* **The order.** `nets::madlad` walks the tensor table positionally. A layer emitted
  out of order loads cleanly and infers nonsense.
* **The untied head appearing twice.** The input table and the logits kernel differ
  and both are emitted; emitting one twice would halve the head's fidelity report
  and mistranslate.
* **The int8 triple.** `Builder::conv_int8` reads kernel, then per-output-channel
  scale, then bias — and T5 carries no biases, so every bias here is synthesised.
* **The fused wi_01.** GEGLU needs `[gate | up]` rows in that order; concatenating
  them backwards still builds and still produces text.
* **The block-0-only tables.** Two `[32, 16]` relative tables at the very end, one
  per side. A table emitted per layer would add 126 tensors of dead weight.

Run:
    python3 scripts/ml/test/test_madlad.py

Exit code 0 = all assertions passed.
"""

from __future__ import annotations

import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import maml_convert  # noqa: E402  (needs the path above)

TOY = {
    "d_model": 8,
    "heads": 2,
    "head_dim": 4,
    "encoder_layers": 2,
    "decoder_layers": 2,
    "d_ff": 16,
    "vocab": 16,
    "head_splits": 4,
    "head_rows": [4, 4, 4, 4],
    "buckets": 8,
}

passed = 0
failed = 0


def check(what: str, ok: bool) -> None:
    global passed, failed
    if ok:
        passed += 1
        print(f"  ok    {what}")
    else:
        failed += 1
        print(f"  FAIL  {what}")


def toy_checkpoint(spec):
    """`(get, shapes)` for a checkpoint of `spec`'s shape, filled with distinguishable values."""
    rng = np.random.default_rng(7)
    held = {}
    for name, shape in maml_convert.madlad_inventory(spec).items():
        held[name] = rng.standard_normal(shape).astype(np.float32)
    return held.__getitem__, {name: list(v.shape) for name, v in held.items()}


def test_the_inventory_is_the_real_architecture() -> None:
    print("inventory:")
    want = maml_convert.madlad_inventory(maml_convert.CHECKPOINTS["madlad400"])
    # 2 tables + 32 enc * 9 names + 32 dec * 14 names + 2 final norms + 2 tables:
    # per encoder layer self norm + 4 projs + ff norm + wi_0/wi_1/wo = 9 names;
    # per decoder layer 9 + cross norm + 4 projs = 14. No biases anywhere.
    check("742 parameters", len(want) == 742)
    check("decoder.embed_tokens is [vocab, d_model]", want["decoder.embed_tokens.weight"] == [256_000, 1024])
    check("lm_head is [vocab, d_model]", want["lm_head.weight"] == [256_000, 1024])
    check("q is [inner, d_model]", want["encoder.block.0.layer.0.SelfAttention.q.weight"] == [2048, 1024])
    check("o is [d_model, inner]", want["encoder.block.0.layer.0.SelfAttention.o.weight"] == [1024, 2048])
    check("wi_0 is [d_ff, d_model]", want["encoder.block.0.layer.1.DenseReluDense.wi_0.weight"] == [8192, 1024])
    check("wo is [d_model, d_ff]", want["encoder.block.0.layer.1.DenseReluDense.wo.weight"] == [1024, 8192])
    check(
        "the decoder has cross-attention",
        "decoder.block.31.layer.1.EncDecAttention.q.weight" in want,
    )
    check(
        "the encoder has none",
        "encoder.block.0.layer.1.EncDecAttention.q.weight" not in want,
    )
    check(
        "the ff moves to layer.2 in the decoder",
        "decoder.block.0.layer.2.DenseReluDense.wi_0.weight" in want,
    )
    check(
        "no projection carries a bias",
        not any(n.endswith(".bias") for n in want if ".layer_norm" not in n and "final_layer_norm" not in n),
    )
    check(
        "no norm carries a bias either",
        not any("layer_norm.bias" in n for n in want),
    )
    check(
        "block-0 holds both relative tables",
        "encoder.block.0.layer.0.SelfAttention.relative_attention_bias.weight" in want
        and "decoder.block.0.layer.0.SelfAttention.relative_attention_bias.weight" in want,
    )
    check(
        "block-1 holds neither",
        "encoder.block.1.layer.0.SelfAttention.relative_attention_bias.weight" not in want
        and "decoder.block.1.layer.0.SelfAttention.relative_attention_bias.weight" not in want,
    )
    total = sum(int(np.prod(shape)) for shape in want.values())
    print(f"  info  inventory parameters: {total}")


def test_a_transposed_checkpoint_is_refused() -> None:
    print("the transpose:")
    real = maml_convert.madlad_inventory(maml_convert.CHECKPOINTS["madlad400"])
    flipped = {
        name: (list(reversed(shape)) if name.endswith(".weight") and len(shape) == 2 else shape)
        for name, shape in real.items()
    }
    try:
        maml_convert.check_checkpoint(flipped, "madlad400")
        caught = ""
    except SystemExit as exit:
        caught = str(exit)
    check("a transposed checkpoint is refused", bool(caught))
    # The message shows the first few mismatches alphabetically, which are the
    # self-attention projections — so it names the offender, not just the count.
    check("and a projection is named in the message", "SelfAttention.q.weight" in caught)
    maml_convert.check_checkpoint(real, "madlad400")
    check("the real inventory is accepted", True)


def test_the_table_order_is_the_contract() -> None:
    print("order:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_madlad(get, TOY)
    ops = [layer.op for layer in layers]

    heads = [i for i, op in enumerate(ops) if op == "Head"]
    check("all eight head splits come first", heads == list(range(8)))

    encoder = ops[8 : 8 + 8]
    check(
        "an encoder layer is norm, four projections, norm, fused wi_01, wo",
        encoder == ["RmsNorm", "Linear8", "Linear8", "Linear8", "Linear8",
                    "RmsNorm", "Linear8", "Linear8"],
    )
    per_encoder = 8
    at = 8 + TOY["encoder_layers"] * per_encoder
    check("the encoder's final norm follows its layers", ops[at] == "RmsNorm")
    decoder = ops[at + 1 : at + 1 + 13]
    check(
        "a decoder layer has two attentions",
        decoder == ["RmsNorm"] + ["Linear8"] * 4 + ["RmsNorm"] + ["Linear8"] * 4
        + ["RmsNorm", "Linear8", "Linear8"],
    )
    check("the decoder's final norm is followed by the tables", ops[-3] == "RmsNorm")
    check("the two relative tables are last", ops[-2:] == ["Relative", "Relative"])

    names = [layer.name for layer in layers]
    check(
        "the layers are named after the checkpoint",
        names[8] == "encoder.block.0.layer.0.layer_norm",
    )
    check("an input-table split names its class range", names[0].endswith("[0:4]"))
    check("an lm_head split names its table", names[4].startswith("lm_head.weight["))
    check("the last split ends at vocab", names[7].endswith(f"[12:{TOY['vocab']}]"))

    at_tensor = 0
    contiguous = True
    for layer in layers:
        contiguous = contiguous and layer.first_tensor == at_tensor
        at_tensor += layer.tensor_count
    check("layer tensor ranges tile the table", contiguous and at_tensor == len(tensors))

    # 8 head layers + 2*8 enc + 1 + 2*13 dec + 1 + 2 tables.
    want_layers = 8 + TOY["encoder_layers"] * 8 + 1 + TOY["decoder_layers"] * 13 + 1 + 2
    check(f"{want_layers} layers", len(layers) == want_layers)
    want_tensors = (
        8 * 3
        + TOY["encoder_layers"] * 20
        + 1
        + TOY["decoder_layers"] * 33
        + 1
        + 2
    )
    check(f"{want_tensors} tensors", len(tensors) == want_tensors)


def test_an_int8_layer_is_kernel_then_scale_then_bias() -> None:
    print("the int8 triple:")
    get, _ = toy_checkpoint(TOY)
    layers, tensors = maml_convert.collect_madlad(get, TOY)

    for index, rows in enumerate(TOY["head_rows"]):
        head = layers[index]
        kernel, scale, bias = tensors[head.first_tensor : head.first_tensor + 3]
        check(f"input-table split {index} kernel [{rows}, {TOY['d_model']}, 1, 1] int8",
              kernel.dtype == np.int8 and list(kernel.shape) == [rows, TOY["d_model"], 1, 1])
        check(f"input-table split {index} one scale per class",
              scale.shape == (rows,) and scale.dtype != np.int8)
        check(f"input-table split {index} bias is synthesised zero",
              bias.shape == (rows,) and not bias.any())

    fused = next(layer for layer in layers if layer.name.endswith("wi_01"))
    kernel, scale, bias = tensors[fused.first_tensor : fused.first_tensor + 3]
    check(
        f"wi_01's kernel is [{2 * TOY['d_ff']}, {TOY['d_model']}, 1, 1]",
        list(kernel.shape) == [2 * TOY["d_ff"], TOY["d_model"], 1, 1],
    )
    check("with one scale per fused row", scale.shape == (2 * TOY["d_ff"],))
    check("and a synthesised zero bias", bias.shape == (2 * TOY["d_ff"],) and not bias.any())

    wo = next(layer for layer in layers if layer.name.endswith(".wo"))
    kernel, _, bias = tensors[wo.first_tensor : wo.first_tensor + 3]
    check(
        f"wo's kernel is [{TOY['d_model']}, {TOY['d_ff']}, 1, 1]",
        list(kernel.shape) == [TOY["d_model"], TOY["d_ff"], 1, 1],
    )
    check("with a synthesised zero bias too", not bias.any())


def test_the_fused_halves_are_gate_then_up() -> None:
    print("the fused halves:")
    get, _ = toy_checkpoint(TOY)
    held = {name: get(name) for name in maml_convert.madlad_inventory(TOY)}
    # No marking needed: the toy fill is already distinct randoms per tensor, and
    # random matrices are near-orthogonal — so a swapped concatenation reads as
    # near-zero cosine rather than a subtle error.
    layers, tensors = maml_convert.collect_madlad(held.__getitem__, TOY)
    fused = next(layer for layer in layers if layer.name == "encoder.block.0.layer.1.DenseReluDense.wi_01")
    kernel = tensors[fused.first_tensor]
    scale = tensors[fused.first_tensor + 1]
    half = TOY["d_ff"]
    # Dequantise each half and check it reconstructs its own source — and not the
    # other's. Random matrices are near-orthogonal, so a swapped concatenation
    # reads as near-zero cosine rather than a subtle error.
    import numpy as _np

    def cosine(a, b):
        a, b = a.ravel().astype(_np.float64), b.ravel().astype(_np.float64)
        return float(a @ b / max(_np.linalg.norm(a) * _np.linalg.norm(b), 1e-30))

    wi_0 = held["encoder.block.0.layer.1.DenseReluDense.wi_0.weight"].reshape(half, -1)
    wi_1 = held["encoder.block.0.layer.1.DenseReluDense.wi_1.weight"].reshape(half, -1)
    gate_back = kernel[:half].astype(_np.float32).reshape(half, -1) * scale[:half, None]
    up_back = kernel[half:].astype(_np.float32).reshape(half, -1) * scale[half:, None]
    check("gate half reconstructs wi_0", cosine(gate_back, wi_0) > 0.99)
    check("up half reconstructs wi_1", cosine(up_back, wi_1) > 0.99)
    check("gate half is not wi_1", abs(cosine(gate_back, wi_1)) < 0.5)


def test_the_digest_covers_the_order() -> None:
    print("the digest:")
    get, _ = toy_checkpoint(TOY)
    layers, _ = maml_convert.collect_madlad(get, TOY)
    before = maml_convert.layer_table_digest(layers)
    q_proj = next(i for i, layer in enumerate(layers) if layer.name.endswith("SelfAttention.q"))
    wi_01 = next(i for i, layer in enumerate(layers) if layer.name.endswith("wi_01"))
    swapped = list(layers)
    swapped[q_proj], swapped[wi_01] = swapped[wi_01], swapped[q_proj]
    check("a reordered pair changes it", maml_convert.layer_table_digest(swapped) != before)
    check("the same table does not", maml_convert.layer_table_digest(list(layers)) == before)


def test_q2k_block_transcription_is_candle_exact() -> None:
    print("the Q2_K block:")
    # A hand-built superblock: scales 0..15, quants 0..63, d=1.5, dmin=0.5 (fp16).
    # Both transcriptions must agree bit-exactly — a lane/shift/scale slip changes values.
    import struct

    scales = bytes(range(16))
    qs = bytes(range(64))
    payload = scales + qs + struct.pack("<e", 1.5) + struct.pack("<e", 0.5)
    assert len(payload) == 84
    mine = maml_convert.q2k_to_float(payload, [1, 256], [1, 256])
    indep = q2k_reference(payload, 1, 256)
    check("converter and independent transcription agree", bool((mine == indep).all()))
    # Spot values by hand: lane 0 (scales[0]=0: lo=0, hi=0) is all zeros;
    # lane 1 (scales[1]=1: lo=1, hi=0): y = 2.25 * q, q = qs[l]>>2&3.
    check("a zero-scale lane is zeros", bool((mine[16:32] == 0).all()))
    # Orientation: out is [rows, taps] with torch flat == GGUF flat.
    check("shape is [1, 256]", list(mine.shape) == [1, 256])


def q2k_reference(payload, rows, taps):
    """Independent Q2_K transcription (no shared code with `q2k_to_float`).

    Written directly from candle's `BlockQ2K::to_float`; shares only the format constants.
    """
    import struct

    out = np.empty((rows, taps), dtype=np.float32)
    blocks = taps // maml_convert.Q2K_BLOCK
    for r in range(rows):
        for b in range(blocks):
            blk = payload[(r * blocks + b) * 84 : (r * blocks + b + 1) * 84]
            scales = list(blk[:16])
            qs = list(blk[16:80])
            d = struct.unpack("<e", bytes(blk[80:82]))[0]
            dmin = struct.unpack("<e", bytes(blk[82:84]))[0]
            is_ = 0
            for half in range(2):
                hq = qs[half * 32 : half * 32 + 32]
                shift = 0
                for lane in range(4):
                    lo, hi = scales[is_] & 0xF, scales[is_] >> 4
                    is_ += 1
                    for l in range(16):
                        out[r, b * 256 + half * 128 + lane * 32 + l] = (
                            d * (((hq[l] >> shift) & 3) * lo) - dmin * hi
                        )
                    lo, hi = scales[is_] & 0xF, scales[is_] >> 4
                    is_ += 1
                    for l in range(16):
                        out[r, b * 256 + half * 128 + lane * 32 + 16 + l] = (
                            d * (((hq[16 + l] >> shift) & 3) * lo) - dmin * hi
                        )
                    shift += 2
    return out


def main() -> int:
    test_the_inventory_is_the_real_architecture()
    test_a_transposed_checkpoint_is_refused()
    test_the_table_order_is_the_contract()
    test_an_int8_layer_is_kernel_then_scale_then_bias()
    test_the_fused_halves_are_gate_then_up()
    test_the_digest_covers_the_order()
    test_q2k_block_transcription_is_candle_exact()
    print(f"\n{passed} passed, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
