"""Convert litertlm artisan Gemma bundle DIRECTLY to .maml files. The ONNX is a different model; litertlm is truth.

FILE-DRIVEN shapes (TFLite INT4 shapefield = nibble count; INT8/U8/F32 = elements):
  sliding (0-3,5-8,10-13): q [1024,1536] (8x128!), k/v [128,1536], o [1536,1024]  => head_dim 128
  full (4,9,14,19,24,29,34): q [4096,1536] (8x512), k/v [512,1536], o [1536,4096]  => head_dim 512
  MLP owning (0-14): gate+ff1 [6144,1536], linear [1536,6144], signed INT4
  MLP slim (15-34): gate+ff1 [12288,1536], linear [1536,12288], artisan 2-bit
    (INT8-tagged, 4 vals/byte, 1 F32 scale/row); same geometry as the base
    bundle's own Section 10 weights (type 19)
  scales: q/k/v 8-per-row (group 192); o/mlp 4-per-row (group 384); embed INT8 4-per-row
  embedder: input [262144,384] INT8; proj [35840,384] INT8; per-layer [262144,128] U8 x35
  norms F32; q_norm/k_norm full-vector [1024]/[2048]; NO head (tied); sampler TOP_P 64/0.95/1.0

Emits via maml_convert.build (symmetric per-block int4 requant, fidelity-gated):
  gemma4_text.maml (graph 20), gemma4_embed.maml (graph 21).
"""

import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
import maml_convert as mc

import tflite
from tflite.Model import Model


class LitertlmReader:
    def __init__(self, path):
        with open(path, 'rb') as f:
            self.sec = f.read()
        self.model = Model.GetRootAs(bytearray(self.sec), 0)
        sg = self.model.Subgraphs(0)
        self.tensors = {}
        for ti in range(sg.TensorsLength()):
            t = sg.Tensors(ti)
            self.tensors[t.Name().decode('utf-8')] = t

    def raw(self, name):
        # FILE QUIRK: every shapefield is a BYTE count (INT4 included:
        # shape = bytes = nibbles/2). See dequant4 docstring.
        t = self.tensors[name]
        b = self.model.Buffers(t.Buffer())
        n = 1
        for i in range(t.ShapeLength()):
            n *= t.Shape(i)
        off = b.Offset()
        return self.sec[off:off + n]

    def f32(self, name):
        return np.frombuffer(self.raw(name), dtype=np.float32).copy()

    def dequant4(self, name, rows, cols):
        """INT4 signed nibbles + F32 scales (1 per row) -> fp32 [rows, cols].

        PROVEN (cos 1.0000 vs base S3 output): nibbles are SIGN-EXTENDED 4-bit
        (-8..7), not unsigned-with-zp-8. The old `(code - 8)` read every table
        sign-flipped. sum_i/static scales are not consumed by this converter.

        FILE QUIRK (verified by buffer extents): every shapefield in this file
        is a BYTE count (F32 [N] = N bytes = N/4 floats; INT4 [N] = N bytes =
        2N nibbles).
        """
        t = self.tensors[name]
        n = rows * cols
        raw = self.raw(name)
        assert len(raw) * 2 >= n, f'{name}: {len(raw)}B < {n} nibbles'
        u8 = np.frombuffer(raw, dtype=np.uint8)
        lo = (u8 & 0xF).astype(np.float32)
        hi = ((u8 >> 4) & 0xF).astype(np.float32)
        flat = np.empty(n, dtype=np.float32)
        flat[0::2] = lo[:n // 2]
        flat[1::2] = hi[:n // 2]
        flat = np.where(flat >= 8, flat - 16, flat).reshape(rows, cols)
        sname = name + '_quantized_scale'
        sraw = self.raw(sname)
        scales = np.frombuffer(sraw, dtype=np.float32)
        assert scales.size % rows == 0, f'{sname}: {scales.size} vs {rows} rows'
        per_row = scales.size // rows
        scales = scales.reshape(rows, per_row)
        g = cols // per_row
        out = np.empty((rows, cols), dtype=np.float32)
        for gi in range(per_row):
            out[:, gi * g:(gi + 1) * g] = flat[:, gi * g:(gi + 1) * g] * scales[:, gi:gi + 1]
        m = float(np.abs(out).mean())
        if not (1e-6 < m < 10.0):
            print(f'WARN {name}: mean abs {m:.2e}')
        return out

    def dequant8(self, name, rows, cols, zp=0.0):
        """INT8/U8 symmetric, 1 or 4 scales per row -> fp32."""
        raw = np.frombuffer(self.raw(name), dtype=np.int8).astype(np.float32)
        assert raw.size >= rows * cols, f'{name}: {raw.size} < {rows}x{cols}'
        raw = raw[:rows * cols].reshape(rows, cols)
        sname = name + '_quantized_scale'
        scales = np.frombuffer(self.raw(sname), dtype=np.float32)
        assert scales.size % rows == 0, f'{sname}: {scales.size} vs {rows}'
        per_row = scales.size // rows
        scales = scales.reshape(rows, per_row)
        g = cols // per_row
        out = np.empty((rows, cols), dtype=np.float32)
        for gi in range(per_row):
            out[:, gi * g:(gi + 1) * g] = (raw[:, gi * g:(gi + 1) * g] - zp) * scales[:, gi:gi + 1]
        return out


def dequant2(name_or_rdr, name=None, rows=None, cols=None):
    """Artisan 2-bit (INT8-tagged, 4 values/byte, row-major) -> fp32.

    The slim-layer MLP (layers 15-34) shares this packing with the embedder's
    working table, whose own packing is still undecoded (see collect_embed). The
    base bundle's Section 10 metadata for the same slim tensors (type 19,
    per-row scales bit-identical to the GPU bundle's `_quantized_scale` suffix,
    zero-points all 0, dim 0) matches the INT4 convention in the same file
    (type 17, zp 0, signed nibbles), whose signed read is proven (cos 1.0000 vs
    base S3): codes are consumed as two's complement (0, 1, -2, -1).

    Proven, not approximate: an unsigned read leaves zero negative weights in
    18.9M values (mean +0.049), while trained weights are ~zero-mean (the
    proven INT4 path: 58% negative). The signed read centers at -0.021 with
    68% negative, matching that convention. Parity still arbitrates end to end.
    """
    if name is None:
        raise SystemExit('dequant2 takes (rdr, name, rows, cols)')
    rdr, name = name_or_rdr, name
    raw = rdr.raw(name)
    assert len(raw) * 4 >= rows * cols, f'{name}: {len(raw)}B < {rows}x{cols} 2-bit'
    u8 = np.frombuffer(raw, dtype=np.uint8)
    vals = np.empty(rows * cols, dtype=np.float32)
    for k in range(4):
        vals[k::4] = ((u8 >> (2 * k)) & 0x3).astype(np.float32)
    vals = np.where(vals >= 2, vals - 4, vals).reshape(rows, cols)
    s = np.frombuffer(rdr.raw(name + '_quantized_scale'), dtype=np.float32)
    assert s.size == rows, f'{name} scales: {s.size} vs {rows} rows'
    out = vals * s[:, None]
    m = float(np.abs(out).mean())
    if not (1e-6 < m < 10.0):
        print(f'WARN {name}: mean abs {m:.2e}')
    return out


def emit4(fid, layers, tensors, logical, weight):
    # 4D `[out, in, 1, 1]`: the file convention every device-bound projection uses
    # (see Builder::conv_quantised, which checks `[m, per_group, kh, kw]`). The
    # quantiser flattens the tap axis, so this changes only the stored shape.
    rows, cols = weight.shape
    weight4 = np.ascontiguousarray(weight).reshape(rows, cols, 1, 1)
    codes, scale = fid.quantise4(logical, weight4)
    bias = np.zeros(rows, dtype=np.float32)
    tensors.extend([codes, scale, bias])
    layers.append(mc.Layer(len(layers), 'Linear4', logical,
                           f'Linear4 w=[{rows}, {cols}, 1, 1] dtype=int4', len(tensors) - 3, 3))


def emit8(fid, layers, tensors, logical, weight):
    rows, cols = weight.shape
    weight4 = np.ascontiguousarray(weight).reshape(rows, cols, 1, 1)
    kernel, scale = fid.quantise(logical, weight4)
    bias = np.zeros(rows, dtype=np.float32)
    tensors.extend([kernel, scale, bias])
    layers.append(mc.Layer(len(layers), 'Linear8', logical,
                           f'Linear8 w=[{rows}, {cols}, 1, 1] dtype=int8', len(tensors) - 3, 3))


def emit_vec(layers, tensors, logical, values):
    values = np.ascontiguousarray(values, dtype=np.float32)
    tensors.append(values)
    layers.append(mc.Layer(len(layers), 'RmsNorm', logical,
                           f'RmsNorm g={list(values.shape)}', len(tensors) - 1, 1))


def collect_text(rdr, rope_theta_local=10000.0, rope_theta_global=1000000.0):
    """gemma4_text in litertlm order. Returns (layers, tensors).

    The transformer ONLY: final norm, two rotary tables, 35 layers. The shared
    per-layer projection + norm live in the EMBED file and the host applies them
    in gather (see nets::gemma4::gather) - they are intentionally absent here.

    Rotary thetas read off the BASE bundle's own Section 10 graph: its
    `maybe_rope` div constants are freq tables with freq[1] = 0.93057203
    (sliding, 128 freqs over head_dim 256) and 0.9474635 (full, 128 freqs over
    head_dim 512), implying theta=1e4 and theta=1e6 (verified freq[2] both).
    Caution: the table ENTRY count is half the head dim (64 freqs would imply
    theta=100/1000 - wrong); the exponent's dim is the head dim 256/512, and
    S10's (1,1,8,128) pre_qk tensor is a norm reshape, not the head dim.
    --rope-theta sets BOTH (bisect escape hatch).
    """
    layers, tensors = [], []
    fid = mc.Fidelity()
    D = 1536
    emit_vec(layers, tensors, 'transformer.final_norm.scale',
             rdr.f32('transformer.final_norm.scale'))
    # Rotary tables: litertlm computes these internally (none stored). Emit standard
    # RoPE cut to MAX_CONTEXT, cos|sin interleaved per Builder::rotary. Thetas read
    # off the base bundle's own Section 10 `maybe_rope` constants (freq[1] implies
    # theta): 1e4 sliding / 1e6 full. Parity vs the base-bundle golden arbitrates.
    # If global diverges, try proportional scaling before anything else.
    for kind, dim, theta in (('local', 256, rope_theta_local),
                             ('global', 512, rope_theta_global)):
        pos = np.arange(16384, dtype=np.float32)[:, None]
        freq = theta ** (-2.0 * np.arange(dim // 2, dtype=np.float32) / dim)
        ang = pos * freq[None, :]
        table = np.concatenate([np.cos(ang), np.sin(ang)], axis=1).astype(np.float32)
        tensors.append(table)
        layers.append(mc.Layer(len(layers), 'Rotary', f'{kind}_rotary_table',
                               f'Rotary t={list(table.shape)} theta-{theta:g}', len(tensors) - 1, 1))
    # Shared all-ones vectors: the gamma of S10's parameter-free value_norm
    # (see nets::gemma4::ONE_SLIDING). Head-dim-wide: [256] and [512].
    emit_vec(layers, tensors, 'transformer.ones_sliding', np.ones(256, dtype=np.float32))
    emit_vec(layers, tensors, 'transformer.ones_full', np.ones(512, dtype=np.float32))
    # FP16-ARENA RESCALING (measured, analysis/probe_all_layers.py):
    # Gemma 3n's activations have huge outlier channels. Worst max|.| over all
    # 35 layers on real embeddings: gelu_up 233k, down_out 4.04M, pl_proj_out
    # 52k, o_proj_out 32k - every one of them a materialised fp16 tensor, and
    # anything past 65504 stores as inf, then NaNs the consuming norm
    # (inf/inf). The reference runs fp32 arithmetic, so it never sees this.
    # Each rescaled projection feeds ONLY its norm (o -> post_attention_norm,
    # ff1 -> mul -> down -> post_ffw_norm, pl proj -> post_per_layer norm),
    # and an RMS norm erases uniform input scaling exactly, so pow2 weight
    # scaling is computation-neutral: it only shrinks what fp16 must hold.
    # The gate stays full-scale (gelu is nonlinear; scaling it would move the
    # operating point), and scaling the LINEAR up-path is exact.
    O_SCALE, FF1_SCALE, PL_SCALE = 4.0, 256.0, 4.0
    for index in range(35):
        at = f'transformer.layer_{index}'
        full = index % 5 == 4
        dim = 512 if full else 256
        qrows = 8 * dim
        kvrows = dim
        # Slim layers (15-34, shared KV) run a 12288-wide MLP in artisan 2-bit
        # storage (type tag INT8, 4 values/byte, 1 F32 scale/row) while the 15
        # owning layers run 6144-wide signed-int4. Proven by byte counts
        # (4718592 B = 12288x1536/4 = 6144x1536/2), scale counts (12288 vs
        # 6144/1536), and the base bundle's own Section 10 weights
        # (gate/up [12288,1536], down [1536,12288], type 19 = artisan 2-bit).
        slim = index >= 15
        inner = 12288 if slim else 6144
        emit_vec(layers, tensors, f'{at}.pre_attention_norm.scale', rdr.f32(f'{at}.pre_attention_norm.scale'))
        emit_vec(layers, tensors, f'{at}.attn.q_norm.scale', rdr.f32(f'{at}.attn.q_norm.scale'))
        emit4(fid, layers, tensors, f'{at}.attn.q.w',
              rdr.dequant4(f'{at}.attn.q.w', qrows, D))
        if index < 15:
            emit_vec(layers, tensors, f'{at}.attn.k_norm.scale', rdr.f32(f'{at}.attn.k_norm.scale'))
            emit4(fid, layers, tensors, f'{at}.attn.k.w',
                  rdr.dequant4(f'{at}.attn.k.w', kvrows, D))
            emit4(fid, layers, tensors, f'{at}.attn.v.w',
                  rdr.dequant4(f'{at}.attn.v.w', kvrows, D))
        emit4(fid, layers, tensors, f'{at}.attn.o.w',
              rdr.dequant4(f'{at}.attn.attn_vec_einsum.w', D, qrows) / O_SCALE)
        emit_vec(layers, tensors, f'{at}.post_attention_norm.scale', rdr.f32(f'{at}.post_attention_norm.scale'))
        emit_vec(layers, tensors, f'{at}.pre_ffw_norm.scale', rdr.f32(f'{at}.pre_ffw_norm.scale'))
        if slim:
            emit4(fid, layers, tensors, f'{at}.mlp.gate.w',
                  dequant2(rdr, f'{at}.mlp.ff_gate.w', inner, D))
            emit4(fid, layers, tensors, f'{at}.mlp.ff1.w',
                  dequant2(rdr, f'{at}.mlp.ff1.w', inner, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'{at}.mlp.linear.w',
                  dequant2(rdr, f'{at}.mlp.linear.w', D, inner))
        else:
            emit4(fid, layers, tensors, f'{at}.mlp.gate.w',
                  rdr.dequant4(f'{at}.mlp.ff_gate.w', 6144, D))
            emit4(fid, layers, tensors, f'{at}.mlp.ff1.w',
                  rdr.dequant4(f'{at}.mlp.ff1.w', 6144, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'{at}.mlp.linear.w',
                  rdr.dequant4(f'{at}.mlp.linear.w', D, 6144))
        emit_vec(layers, tensors, f'{at}.post_ffw_norm.scale', rdr.f32(f'{at}.post_ffw_norm.scale'))
        emit8(fid, layers, tensors, f'{at}.per_layer.gate.w',
              rdr.dequant8(f'{at}.per_layer_embedding_gate.w', 256, 1536))
        emit8(fid, layers, tensors, f'{at}.per_layer.proj.w',
              rdr.dequant8(f'{at}.per_layer_embedding_projection.w', 1536, 256) / PL_SCALE)
        emit_vec(layers, tensors, f'{at}.post_per_layer_input_norm.scale',
                 rdr.f32(f'{at}.post_per_layer_input_norm.scale'))
        # Skip multiplier: the reference scales the whole residual (x + branch)
        # by this AFTER add2 (`_maybe_apply_skip_scale/mul` in S10). Missing it
        # leaves layer 0 at min -173.6/max 11.6 vs ideal -4.66/0.32 (37x = the
        # missing 0.027) and corrupts every downstream residual direction.
        # A 1-elem fp16 tensor consumed by Builder::mul_scalar. The assert is the
        # point: a file variant with a wider skip tensor must fail here rather
        # than silently taking its first element.
        skip_raw = rdr.f32(f'{at}.skip.scale')
        assert skip_raw.size == 1, f'{at}.skip.scale: {skip_raw.size} elems, not 1'
        skip = np.ascontiguousarray(skip_raw[:1], dtype=np.float32)
        tensors.append(skip)
        layers.append(mc.Layer(len(layers), 'MulScalar', f'{at}.skip.scale',
                               'MulScalar s=[1]', len(tensors) - 1, 1))
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


def collect_embed(rdr):
    """gemma4_embed: working table + shared projection + 35 mmap tables + norms.

    STORAGE DECODINGS (verified by byte counts + consumer dims):
    - input_embedding: exact S2 `embedder` fp32 rows [262144, 1536] via
      GEMMA4_EMBED_DUMP (gemma4_embed_dump.py); the artisan 2-bit packing is
      undecoded, legacy `(vals - 1.5) * s` path is approximate only.
      Token hidden rows + TIED head source.
    - per_layer_embeddings[L]: INT4 [262144, 256] in U8-typed storage
      (33.5MB = 262144x256/2), 1 fp32 scale/row. Nibbles are SIGNED (-8..7),
      same as every other int4 table. The per-layer
      path is 256-wide (gate [256,1536] takes hidden, output modulates the row).
    - per_layer_model_projection: INT8 [8960, 1536] (35x256 stacked), 1/row.
    """
    layers, tensors = [], []
    fid = mc.Fidelity()
    # working table: exact S2 fp32 rows, required. The artisan 2-bit packing of
    # `input_embedding.w` is undecoded - every mapping tried gives cosine ~0 vs
    # S2 rows - and the legacy `(vals - 1.5)` guess below it is uncorrelated
    # with the true embeddings (measured cosine ~-0.03 on sample rows), so
    # silently emitting it would ship a garbage working table and a garbage
    # tied head. Produce the dump with scripts/ml/gemma4_embed_dump.py and pass
    # it as GEMMA4_EMBED_DUMP; refusing here is what makes a missing dump a
    # loud build failure rather than a fluent wrong model.
    dump = os.environ.get('GEMMA4_EMBED_DUMP')
    if not dump or not os.path.exists(dump):
        raise SystemExit(
            'collect_embed needs GEMMA4_EMBED_DUMP=<embed_s2_fp32.bin> '
            '(scripts/ml/gemma4_embed_dump.py); the in-file fallback is '
            'proven uncorrelated with the true rows and is no longer emitted')
    out = np.memmap(dump, dtype=np.float32, mode='r',
                    shape=(262144, 1536)).astype(np.float32)
    m = float(np.abs(out).mean())
    print(f'input_embedding S2 dump {dump}: mean abs {m:.4f}')
    codes, scale = fid.quantise4('input_embedding', out)
    bias = np.zeros(262144, dtype=np.float32)
    tensors.extend([codes, scale, bias])
    layers.append(mc.Layer(len(layers), 'Embedding4', 'input_embedding',
                           'Embedding4 2-bit source', len(tensors) - 3, 3))
    emit8(fid, layers, tensors, 'transformer.embedder.per_layer_model_projection.w',
          rdr.dequant8('transformer.embedder.per_layer_model_projection.w', 8960, 1536))
    emit_vec(layers, tensors, 'transformer.embedder.per_layer_projection_norm.scale',
             rdr.f32('transformer.embedder.per_layer_projection_norm.scale'))
    for index in range(35):
        at = f'transformer.layer_{index}.per_layer_embeddings.w'
        w = np.frombuffer(rdr.raw(at), dtype=np.uint8)
        assert w.size >= 262144 * 128, f'{at}: {w.size}'
        w = w[:262144 * 128]
        # INT4 nibbles packed in U8 storage: 128 bytes = 256 nibbles per row.
        # SIGNED nibbles (proven cos 1.0000 vs base S3): sign-extend, no zp.
        lo = (w & 0xF).astype(np.float32)
        hi = ((w >> 4) & 0xF).astype(np.float32)
        flat = np.empty(262144 * 256, dtype=np.float32)
        flat[0::2] = lo
        flat[1::2] = hi
        flat = np.where(flat >= 8, flat - 16, flat).reshape(262144, 256)
        s = np.frombuffer(rdr.raw(at + '_quantized_scale'), dtype=np.float32)
        assert s.size == 262144, f'{at} scales: {s.size}'
        out = flat * s[:, None]
        codes, scale = fid.quantise4(f'layer_{index}.per_layer_embeddings', out)
        bias = np.zeros(262144, dtype=np.float32)
        tensors.extend([codes, scale, bias])
        layers.append(mc.Layer(len(layers), 'Linear4', f'layer_{index}.per_layer_embeddings',
                               'Linear4 mmap table', len(tensors) - 3, 3))
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--model', required=True)
    ap.add_argument('-o', '--outdir', required=True)
    ap.add_argument('--print-digest', action='store_true')
    ap.add_argument('--rope-theta', type=float, default=None,
                    help='RoPE base frequency for BOTH tables (bisect escape hatch; '
                         'default: 1e4 sliding / 1e6 full, read off the base bundle)')
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)
    rdr = LitertlmReader(args.model)
    print('tensors:', len(rdr.tensors))
    theta = args.rope_theta
    layers, tensors = collect_text(
        rdr,
        rope_theta_local=theta if theta is not None else 10000.0,
        rope_theta_global=theta if theta is not None else 1000000.0,
    )
    digest = mc.layer_table_digest(layers)
    print(f'gemma4_text: {len(layers)} layers, {len(tensors)} tensors, digest {digest}')
    import hashlib
    sha = hashlib.sha256(open(args.model, 'rb').read()).digest()
    blob, count = mc.build(layers, tensors, mc.GRAPHS['gemma4_text'], sha)
    out = os.path.join(args.outdir, 'gemma4_text.maml')
    open(out, 'wb').write(blob)
    print(f'wrote {out} ({len(blob)} bytes)')
    elayers, etensors = collect_embed(rdr)
    edigest = mc.layer_table_digest(elayers)
    print(f'gemma4_embed: {len(elayers)} layers, {len(etensors)} tensors, digest {edigest}')
    eblob, ecount = mc.build(elayers, etensors, mc.GRAPHS['gemma4_embed'], sha)
    eout = os.path.join(args.outdir, 'gemma4_embed.maml')
    open(eout, 'wb').write(eblob)
    print(f'wrote {eout} ({len(eblob)} bytes)')


if __name__ == '__main__':
    main()
