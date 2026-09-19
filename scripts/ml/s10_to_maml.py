#!/usr/bin/env python3
"""S10-sourced control conversion: transcribe Section 10's OWN transformer
weights (same layout/norms/scales the golden ran) into a MAML, to decide
whether the parity failure is in the ARTISAN weight decode or the runtime.

Also emits the S10 embed file: S2-dump working table + S10 head table
(t2698, raw-scale `embedder.decode` composite) + shared projection/norm
from S10's own tensors + 35 per-layer mmap tables. The head table is the
piece the artisan bundle cannot supply (its 2-bit embedder packing is a
different checkpoint's arrangement); the tied head reads it raw-scale
(see nets::gemma4::EMBED_GAIN).

Usage:
  GEMMA4_EMBED_DUMP=analysis/maml-rebuild/embed_s2_fp32.bin \
    python scripts/ml/s10_to_maml.py -o build/gemma4-s10 --print-digest

Then:
  cargo run --offline --release -p modelrunner --example check_gemma4_parity -- \
    build/gemma4-s10/gemma4_text.maml build/gemma4-s10/gemma4_embed.maml \
    build/gemma4/logits_golden.json build/gemma4/trace.json

PASS => artisan payloads are the problem (port S10's tables).
FAIL => the runtime/net is the problem (same reading convention both sides).
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maml_convert as mc
from litertlm_to_maml import emit4, emit8, emit_vec

import tflite
from tflite.Model import Model

S10 = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'analysis',
                   'base-sections', 'Section10_TFLiteModel_tf_lite_prefill_decode.tflite')


def m_OperatorCode(sg, o):
    raise NotImplementedError


class S10Reader:
    """Section-10 tensor access with TFLite-native int4 reading."""

    def __init__(self, path):
        with open(path, 'rb') as f:
            self.sec = f.read()
        self.model = Model.GetRootAs(bytearray(self.sec), 0)
        self.sg = self.model.Subgraphs(0)
        self.tensors = {}
        for ti in range(self.sg.TensorsLength()):
            t = self.sg.Tensors(ti)
            self.tensors[t.Name().decode('utf-8')] = ti

    def const(self, ti):
        t = self.sg.Tensors(ti)
        b = self.model.Buffers(t.Buffer())
        return bytes(b.DataAsNumpy())

    def f32(self, ti):
        return np.frombuffer(self.const(ti), dtype=np.float32).copy()

    def dequant4(self, ti, rows, cols):
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.uint8)
        assert raw.size * 2 >= rows * cols, (ti, raw.size, rows, cols)
        lo = (raw & 0xF).astype(np.float32)
        hi = ((raw >> 4) & 0xF).astype(np.float32)
        flat = np.empty(raw.size * 2, dtype=np.float32)
        flat[0::2] = lo
        flat[1::2] = hi
        flat = np.where(flat >= 8, flat - 16, flat).reshape(rows, cols)
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        out = flat * s[:, None]
        m = float(np.abs(out).mean())
        if not (1e-6 < m < 10.0):
            print(f'WARN t{ti}: mean abs {m:.2e}')
        return out

    def dequant2(self, ti, rows, cols):
        """2-bit table with the head codebook ([0,+s,-2s,-s]) -> fp32."""
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.uint8)
        assert raw.size * 4 >= rows * cols, (ti, raw.size, rows, cols)
        v = np.empty(raw.size * 4, dtype=np.float64)
        for k in range(4):
            v[k::4] = ((raw >> (2 * k)) & 0x3).astype(np.float64)
        v = v[:rows * cols].reshape(rows, cols)
        codebook = np.array([0.0, 1.0, -2.0, -1.0])
        v = codebook[v.astype(int)]
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        return (v * s[:, None]).astype(np.float32)

    def dequant8(self, ti, rows, cols):
        t = self.sg.Tensors(ti)
        raw = np.frombuffer(self.const(ti), dtype=np.int8).astype(np.float32)
        raw = raw[:rows * cols].reshape(rows, cols)
        q = t.Quantization()
        s = np.array([q.Scale(i) for i in range(q.ScaleLength())], dtype=np.float32)
        assert s.size == rows, (ti, s.size, rows)
        return raw * s[:, None]


# tensor ids mapped in collect_text (verified against the graph walk).
class T:
    # norms: (tensor id, len)
    FINAL = 37
    PRE = 303
    QN = 300
    KN = 299
    POSTA = 280
    PREFF = None  # filled per layer below
    POSTFF = 40
    POSTPL = 39
    SKIP = 276
    SHARED_NORM = None  # embed file (shared)


def fc_weight(rdr, layer, frag):
    """Find the FC weight tensor id for layer's fragment (the FC op itself)."""
    import re
    sg = rdr.sg
    pat = re.compile(rf'layer_{layer}/')
    for oi in range(sg.OperatorsLength()):
        o = sg.Operators(oi)
        if rdr.model.OperatorCodes(o.OpcodeIndex()).BuiltinCode() != 9:  # FULLY_CONNECTED
            continue
        outs = [o.Outputs(i) for i in range(o.OutputsLength())]
        if not outs:
            continue
        nm = sg.Tensors(outs[0]).Name().decode('utf-8')
        if not pat.search(nm) or frag not in nm:
            continue
        ins = [o.Inputs(i) for i in range(o.InputsLength())]
        for ii in ins:
            if ii < 0:
                continue
            t = sg.Tensors(ii)
            if t.ShapeLength() == 2:
                return ii
    raise KeyError((layer, frag))


def collect_text(rdr):
    layers, tensors = [], []
    fid = mc.Fidelity()
    D = 1536
    sg = rdr.sg
    # final norm + rotary tables. The angle tables are read off S10's own
    # `maybe_rope` div constants (NOT generated from theta: S10's full-layer
    # table has only 64 nonzero freqs of 256 — theta 1e6 over head_dim 512
    # for those, zeros after — and generating 256 freqs rotates 192 channel
    # pairs the reference leaves fixed, breaking every full layer's Q and K).
    # S10 tensor ids: 296 (sliding, 128 freqs), 245 (full, 256 entries).
    emit_vec(layers, tensors, 'transformer.final_norm.scale', rdr.f32(T.FINAL))
    for kind, dim, freq_ti in (('local', 256, 296), ('global', 512, 245)):
        freq = rdr.f32(freq_ti).astype(np.float64)
        assert freq.size == dim // 2, (kind, freq.size, dim)
        pos = np.arange(16384, dtype=np.float64)[:, None]
        ang = pos * freq[None, :]
        table = np.concatenate([np.cos(ang), np.sin(ang)], axis=1).astype(np.float32)
        tensors.append(table)
        layers.append(mc.Layer(len(layers), 'Rotary', f'{kind}_rotary_table',
                               f'Rotary t={list(table.shape)} S10-freq',
                               len(tensors) - 1, 1))
    # Shared all-ones vectors: the gamma of S10's parameter-free value_norm
    # (see nets::gemma4::ONE_SLIDING). Head-dim-wide: [256] and [512].
    emit_vec(layers, tensors, 'transformer.ones_sliding', np.ones(256, dtype=np.float32))
    emit_vec(layers, tensors, 'transformer.ones_full', np.ones(512, dtype=np.float32))
    O_SCALE, FF1_SCALE, PL_SCALE = 4.0, 256.0, 4.0
    # norm tensor ids per layer_0; other layers resolved by name scan
    for index in range(35):
        at = f'layer_{index}'
        full = index % 5 == 4
        dim = 512 if full else 256
        qrows = 8 * dim
        # locate per-layer norm GAMMAS: the composite outputs (e.g. 354) carry
        # no bytes; resolve each to its const gamma input (2nd composite input).
        import re
        pat = re.compile(rf'layer_{index}/')
        norms = {}
        for ti in range(sg.TensorsLength()):
            nm = sg.Tensors(ti).Name().decode('utf-8')
            if not pat.search(nm):
                continue
            for key, frag in [('pre', 'pre_attention_norm/composite'),
                              ('posta', 'post_attention_norm/composite'),
                              ('preff', 'pre_ffw_norm/composite'),
                              ('postff', 'post_ffw_norm/composite'),
                              ('postpl', 'post_per_layer_input_norm/composite'),
                              ('qn', 'query_norm/composite'),
                              ('kn', 'key_norm/composite')]:
                if frag in nm and key not in norms:
                    norms[key] = ti
        for key, ti in list(norms.items()):
            for oi in range(sg.OperatorsLength()):
                o = sg.Operators(oi)
                outs = [o.Outputs(i) for i in range(o.OutputsLength())]
                if ti not in outs:
                    continue
                ins = [o.Inputs(i) for i in range(o.InputsLength())]
                # composite ins: [activation, gamma]
                if len(ins) >= 2 and ins[1] >= 0:
                    norms[key] = ins[1]
        qw = fc_weight(rdr, index, 'q_einsum/reshape;')
        if index < 15:
            kw = fc_weight(rdr, index, 'k_einsum/reshape;')
            vw = fc_weight(rdr, index, 'v_einsum/reshape;')
        ow = fc_weight(rdr, index, 'attn_vec_einsum/btH')
        gw = fc_weight(rdr, index, 'gating_einsum1')
        uw = fc_weight(rdr, index, 'gating_einsum2')
        dw = fc_weight(rdr, index, 'mlp/linear')
        pew = fc_weight(rdr, index, 'per_layer_embedding_gate')
        pew2 = fc_weight(rdr, index, 'per_layer_embedding_projection')
        emit_vec(layers, tensors, f'transformer.layer_{index}.pre_attention_norm.scale',
                 rdr.f32(norms['pre']))
        emit_vec(layers, tensors, f'transformer.layer_{index}.attn.q_norm.scale',
                 rdr.f32(norms['qn']))
        emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.q.w',
              rdr.dequant4(qw, qrows, D))
        if index < 15:
            emit_vec(layers, tensors, f'transformer.layer_{index}.attn.k_norm.scale',
                     rdr.f32(norms['kn']))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.k.w',
                  rdr.dequant4(kw, dim, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.v.w',
                  rdr.dequant4(vw, dim, D))
        emit4(fid, layers, tensors, f'transformer.layer_{index}.attn.o.w',
              rdr.dequant4(ow, D, qrows) / O_SCALE)
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_attention_norm.scale',
                 rdr.f32(norms['posta']))
        emit_vec(layers, tensors, f'transformer.layer_{index}.pre_ffw_norm.scale',
                 rdr.f32(norms['preff']))
        if index < 15:
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.gate.w',
                  rdr.dequant4(gw, 6144, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.ff1.w',
                  rdr.dequant4(uw, 6144, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.linear.w',
                  rdr.dequant4(dw, D, 6144))
        else:
            # Slim layers: full-width 2-bit gate/up/down (same codebook as
            # the head table). Gate and up are separate 12288-wide tables;
            # the runtime's ffn() is 12288 here, so no split.
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.gate.w',
                  rdr.dequant2(gw, 12288, D))
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.ff1.w',
                  rdr.dequant2(uw, 12288, D) / FF1_SCALE)
            emit4(fid, layers, tensors, f'transformer.layer_{index}.mlp.linear.w',
                  rdr.dequant2(dw, D, 12288))
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_ffw_norm.scale',
                 rdr.f32(norms['postff']))
        emit8(fid, layers, tensors, f'transformer.layer_{index}.per_layer.gate.w',
              rdr.dequant8(pew, 256, 1536))
        emit8(fid, layers, tensors, f'transformer.layer_{index}.per_layer.proj.w',
              rdr.dequant8(pew2, 1536, 256) / PL_SCALE)
        emit_vec(layers, tensors, f'transformer.layer_{index}.post_per_layer_input_norm.scale',
                 rdr.f32(norms['postpl']))
        # skip: per-layer scalar via the skip-mul consumer chain (op ~100 for L0)
        layers_skip = None
        for oi in range(sg.OperatorsLength()):
            o = sg.Operators(oi)
            outs = [o.Outputs(i) for i in range(o.OutputsLength())]
            nm = sg.Tensors(outs[0]).Name().decode('utf-8') if outs else ''
            if f'layer_{index}.' in nm and '_maybe_apply_skip_scale/mul' in nm:
                ins = [o.Inputs(i) for i in range(o.InputsLength())]
                # the scalar input is the [1,1,1] one
                for ii in ins:
                    t = sg.Tensors(ii)
                    if t.ShapeLength() == 3:
                        layers_skip = rdr.f32(ii)[:1]
            if layers_skip is not None:
                break
        skip = np.ascontiguousarray(layers_skip, dtype=np.float32)
        tensors.append(skip)
        layers.append(mc.Layer(len(layers), 'MulScalar',
                               f'transformer.layer_{index}.skip.scale',
                               'MulScalar s=[1]', len(tensors) - 1, 1))
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-o', '--outdir', required=True)
    ap.add_argument('--print-digest', action='store_true')
    args = ap.parse_args()
    os.makedirs(args.outdir, exist_ok=True)
    rdr = S10Reader(S10)
    layers, tensors = collect_text(rdr)
    digest = mc.layer_table_digest(layers)
    print(f's10_text: {len(layers)} layers, {len(tensors)} tensors, digest {digest}')
    import hashlib
    sha = hashlib.sha256(open(S10, 'rb').read()).digest()
    blob, _ = mc.build(layers, tensors, mc.GRAPHS['gemma4_text'], sha)
    out = os.path.join(args.outdir, 'gemma4_text.maml')
    open(out, 'wb').write(blob)
    print(f'wrote {out} ({len(blob)} bytes)')
    elayers, etensors = collect_embed(rdr)
    edigest = mc.layer_table_digest(elayers)
    print(f's10_embed: {len(elayers)} layers, {len(etensors)} tensors, digest {edigest}')
    eblob, _ = mc.build(elayers, etensors, mc.GRAPHS['gemma4_embed'], sha)
    eout = os.path.join(args.outdir, 'gemma4_embed.maml')
    open(eout, 'wb').write(eblob)
    print(f'wrote {eout} ({len(eblob)} bytes)')


def collect_embed(rdr):
    """gemma4_embed from S10's own tensors (+ S2 dump for the working table).

    Layout matches the artisan path's embed file exactly (same tensor order,
    same layer kinds), so the runtime reads either file identically:
    - working table: exact S2 `embedder` fp32 rows [262144, 1536] via
      GEMMA4_EMBED_DUMP (same requirement as the artisan path; the 2-bit
      in-file packing is a different checkpoint's arrangement in both files).
    - head table: S10 t2698 (`embedder.decode` composite, type 19 2-bit,
      codebook [0,+s,-2s,-s] lo-first — 10/10 vs the golden ranking). The
      tied head reads this raw-scale table (see nets::gemma4::EMBED_GAIN).
    - shared projection: the ARTISAN file's
      `per_layer_model_projection.w` (int8, two's-complement) + norm gamma.
      S10's t312 is the same table's shape/scales but a DIFFERENT
      checkpoint's arrangement (global signed multiset matches, elementwise
      does not — like the INT4 projections; the golden combination path
      scores 1.00000 with the artisan table, -0.09 with S10's).
    - 35 per-layer mmap tables: S10 has no per-layer embedder (that is
      Section 3, not Section 10); these come from the artisan file's
      `per_layer_embeddings.w` INT4 tables, whose signed-nibble read is
      proven (cos 1.0000 vs base S3 output).
    """
    import litertlm_to_maml as lt
    layers, tensors = [], []
    fid = mc.Fidelity()
    dump = os.environ.get('GEMMA4_EMBED_DUMP')
    if not dump or not os.path.exists(dump):
        raise SystemExit(
            'collect_embed needs GEMMA4_EMBED_DUMP=<embed_s2_fp32.bin> '
            '(scripts/ml/gemma4_embed_dump.py)')
    out = np.memmap(dump, dtype=np.float32, mode='r',
                    shape=(262144, 1536)).astype(np.float32)
    m = float(np.abs(out).mean())
    print(f'input_embedding S2 dump {dump}: mean abs {m:.4f}')
    codes, scale = fid.quantise4('input_embedding', out)
    bias = np.zeros(262144, dtype=np.float32)
    tensors.extend([codes, scale, bias])
    layers.append(mc.Layer(len(layers), 'Embedding4', 'input_embedding',
                           'Embedding4 2-bit source', len(tensors) - 3, 3))
    # Per-layer mmap tables: S10 carries none; artisan's signed-nibble read is
    # proven vs S3, so transcribe via a LitertlmReader over the artisan file.
    art_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'analysis',
                            'gpu-sections',
                            'Section2_TFLiteModel_tf_lite_artisan_text_decoder.tflite')
    art = lt.LitertlmReader(art_path)
    emit8(fid, layers, tensors, 'transformer.embedder.per_layer_model_projection.w',
          art.dequant8('transformer.embedder.per_layer_model_projection.w', 8960, 1536))
    emit_vec(layers, tensors, 'transformer.embedder.per_layer_projection_norm.scale',
             art.f32('transformer.embedder.per_layer_projection_norm.scale'))
    for index in range(35):
        at = f'transformer.layer_{index}.per_layer_embeddings.w'
        w = np.frombuffer(art.raw(at), dtype=np.uint8)
        assert w.size >= 262144 * 128, f'{at}: {w.size}'
        w = w[:262144 * 128]
        lo = (w & 0xF).astype(np.float32)
        hi = ((w >> 4) & 0xF).astype(np.float32)
        flat = np.empty(262144 * 256, dtype=np.float32)
        flat[0::2] = lo
        flat[1::2] = hi
        flat = np.where(flat >= 8, flat - 16, flat).reshape(262144, 256)
        s = np.frombuffer(art.raw(at + '_quantized_scale'), dtype=np.float32)
        assert s.size == 262144, f'{at} scales: {s.size}'
        out = flat * s[:, None]
        codes, scale = fid.quantise4(f'layer_{index}.per_layer_embeddings', out)
        bias = np.zeros(262144, dtype=np.float32)
        tensors.extend([codes, scale, bias])
        layers.append(mc.Layer(len(layers), 'Linear4', f'layer_{index}.per_layer_embeddings',
                               'Linear4 mmap table', len(tensors) - 3, 3))
    # Head table LAST (see TABLES): appended after the per-layer tables so
    # their file entries (7+i*3) match the artisan-path file exactly and old
    # files without a head table still parse for gather.
    head = rdr.dequant2(2698, 262144, 1536)
    tensors.append(np.ascontiguousarray(head, dtype=np.float32).astype(np.float16))
    layers.append(mc.Layer(len(layers), 'Embedding4', 'head_table',
                           'Embedding4 raw-scale S10 decode table fp16',
                           len(tensors) - 1, 1))
    fid.report(mc.MIN_INT4_COSINE)
    return layers, tensors


if __name__ == '__main__':
    main()
