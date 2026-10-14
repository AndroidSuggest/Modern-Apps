#!/usr/bin/env python3
"""Fold a MODNet photographic-portrait export into `.maml`, ahead of time.

The official MODNet weights (Apache-2.0, `ZHKKKe/MODNet`, mirrored for this
repo) export with two constructs `:library:ml` cannot lower verbatim:

* `IBNorm`: every branch convolution is followed by a channel split whose
  first half runs through a frozen `BatchNormalization` and whose second half
  runs through an `InstanceNormalization(affine=False)`, re-concatenated.
  `maml_convert.collect_layers` only folds a batch norm that directly follows
  its convolution (`BatchNormalization` after a `Slice` is rejected), and the
  slice between them blocks the fold.
* The SE block's two projections arrive as `MatMul` against 2-D constants,
  which `collect_layers` does not emit (only `Gemm`-over-`Flatten` is
  lowered, and these feed `Reshape`, not `Flatten`).

# Why this is a separate script

Same reason as `ppocr_fold.py`: `maml_convert.py`'s contract is that a
`.maml` is the ONNX's tensors *verbatim*, so teaching it slice-aware folds
and MatMul rewrites would tax every other model. The fold happens here, once,
ahead of time; the Rust transcribes the folded table.

# What is folded, and why it is exact

* Each `Conv -> Slice[0:k] -> BatchNormalization(frozen)` half is pushed into
  the producer convolution's weight rows `[0:k)` and bias rows, in fp64:
  `W'[o] = W[o] * s[o]`, `b'[o] = (b[o] - mean[o]) * s[o] + beta[o]` with
  `s = gamma / sqrt(var + eps)`. At inference a frozen batch norm is a
  per-channel affine, so this is exact, and the slice survives untouched —
  only which rows are scaled changes, which the digest records via the
  unchanged shapes plus the folded values' provenance in `--print-graph`.
* Each SE `MatMul(x, W)` with a constant 2-D `W` becomes a `Conv` node with
  kernel `[out, in, 1, 1]` and a synthesised zero bias: a matrix product over
  a `[1, C]` row vector is a 1x1 convolution, exactly. The surrounding
  `Unsqueeze`/`Concat`/`Reshape` shape scaffolding is left in place and dies
  as dead code — the Rust never transcribes it, the way U^2-Netp's 266
  `Constant` + 114 `Shape` nodes are ignored.

Deliberately **not** folded:

* `InstanceNormalization(affine=False)`: per-inference statistics, so there
  is nothing to fold. The runtime implements it as `Kind::InstanceNorm`
  (see `nets/modnet.rs`); the fold script only verifies every one is
  `affine=False` (scale 1, bias 0) with the export's epsilon.
* `Clip(0, 6)` (ReLU6): a real nonlinearity, fused into the producing
  convolution as `Act::Relu6` by the Rust transcription, not rewritten here.
* Standalone `Relu` after a `Concat` (the 16 IBNorm rejoins): no convolution
  produced that value, so the Rust runs it as `Kind::Activate`.

    ./scripts/ml/modnet_fold.py model.onnx --graph modnet -o out.maml
    ./scripts/ml/modnet_fold.py model.onnx --graph modnet --print-graph
"""

import argparse
import copy
import hashlib
import os
import sys

import numpy as np
import onnx
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import maml_convert  # noqa: E402  (needs the path above)

# Every IBNorm in this export: BatchNorm over channels [0:k), InstanceNorm
# over [k:), epsilon from the InstanceNorm2d default.
IBN_EPSILON = 1e-5
TOLERANCE = 1e-6


def const_arrays(model):
    """Every constant tensor by name, from initializers and Constant nodes."""
    inits = {i.name: i for i in model.graph.initializer}
    consts = {}
    for node in model.graph.node:
        if node.op_type == "Constant":
            for attr in node.attribute:
                if attr.name == "value":
                    consts[node.output[0]] = attr.t
    def array(name):
        t = inits.get(name, consts.get(name))
        return numpy_helper.to_array(t) if t is not None else None
    return inits, consts, array


def fold_ibnorm(model):
    """Push each Slice-fed frozen BatchNorm into its producer Conv's rows.

    Returns the count of folded sites. Verifies the full IBNorm shape at each
    site: Conv -> Slice[0:k] -> BN -> Concat <- IN <- Slice[k:] with the IN
    affine-free, and the Concat feeding only the post-join Relu path.
    """
    inits, consts, array = const_arrays(model)
    nodes = list(model.graph.node)
    out2node = {}
    for n in nodes:
        for o in n.output:
            out2node[o] = n
    consumers = {}
    for n in nodes:
        for i in n.input:
            consumers.setdefault(i, []).append(n)

    folded = 0
    for bn in [n for n in nodes if n.op_type == "BatchNormalization"]:
        # The BN half must hang off a channel-axis Slice [0:k) of a Conv.
        sl = out2node.get(bn.input[0])
        if sl is None or sl.op_type != "Slice":
            raise SystemExit(f"{bn.output[0]}: batch norm input is not a Slice")
        axes = array(sl.input[3])
        if axes is None or list(axes) != [1]:
            raise SystemExit(f"{sl.output[0]}: IBNorm slice is not channel-axis")
        starts, ends = array(sl.input[1]), array(sl.input[2])
        if starts is None or list(starts) != [0] or ends is None or len(ends) != 1:
            raise SystemExit(f"{sl.output[0]}: BN slice is not [0:k)")
        k = int(ends[0])
        conv = out2node.get(sl.input[0])
        if conv is None or conv.op_type != "Conv":
            raise SystemExit(f"{sl.output[0]}: BN slice source is not a Conv")
        # The sibling slice [k:] must feed an affine-free InstanceNorm, and
        # both halves must rejoin at one Concat.
        sib = [c for c in consumers.get(sl.input[0], []) if c is not sl]
        if len(sib) != 1 or sib[0].op_type != "Slice":
            raise SystemExit(f"{sl.input[0]}: expected exactly two Slice consumers")
        sib = sib[0]
        s_starts, s_ends = array(sib.input[1]), array(sib.input[2])
        if list(s_starts) != [k]:
            raise SystemExit(f"{sib.output[0]}: IN slice does not start at {k}")
        inorm = consumers.get(sib.output[0], [])
        if len(inorm) != 1 or inorm[0].op_type != "InstanceNormalization":
            raise SystemExit(f"{sib.output[0]}: IN slice does not feed InstanceNorm")
        inorm = inorm[0]
        sc, bi = array(inorm.input[1]), array(inorm.input[2])
        if sc is None or bi is None or not np.all(sc == 1) or not np.all(bi == 0):
            raise SystemExit(f"{inorm.output[0]}: InstanceNorm is not affine-free")
        eps = next((a.f for a in inorm.attribute if a.name == "epsilon"), 1e-5)
        if abs(eps - IBN_EPSILON) > TOLERANCE:
            raise SystemExit(f"{inorm.output[0]}: epsilon {eps} is not {IBN_EPSILON}")
        bn_cons = consumers.get(bn.output[0], [])
        in_cons = consumers.get(inorm.output[0], [])
        if len(bn_cons) != 1 or len(in_cons) != 1 or bn_cons[0] is not in_cons[0]:
            raise SystemExit(f"{bn.output[0]}: BN/IN halves do not rejoin at one Concat")
        if bn_cons[0].op_type != "Concat":
            raise SystemExit(f"{bn.output[0]}: halves rejoin at {bn_cons[0].op_type}")
        # Fold: scale Conv rows [0:k) and bias rows by the BN affine, fp64.
        attrs = {a.name: a for a in bn.attribute}
        epsilon = attrs["epsilon"].f if "epsilon" in attrs else 1e-5
        gamma = array(bn.input[1]).astype(np.float64)
        beta = array(bn.input[2]).astype(np.float64)
        mean = array(bn.input[3]).astype(np.float64)
        var = array(bn.input[4]).astype(np.float64)
        if any(v is None for v in (gamma, beta, mean, var)):
            raise SystemExit(f"{bn.output[0]}: batch norm parameter is computed")
        if gamma.shape[0] != k:
            raise SystemExit(f"{bn.output[0]}: BN channels {gamma.shape[0]} != slice {k}")
        w_name, b_name = conv.input[1], conv.input[2] if len(conv.input) > 2 else None
        if b_name is None:
            raise SystemExit(f"{conv.output[0]}: IBNorm Conv has no bias to fold into")
        w = array(w_name).astype(np.float64)
        b = array(b_name).astype(np.float64)
        scale = gamma / np.sqrt(var + epsilon)
        w[:k] = w[:k] * scale.reshape([-1] + [1] * (w.ndim - 1))
        b[:k] = (b[:k] - mean) * scale + beta
        folded_w = numpy_helper.from_array(w.astype(np.float32), name=w_name)
        folded_b = numpy_helper.from_array(b.astype(np.float32), name=b_name)
        if w_name in inits:
            model.graph.initializer.remove(inits[w_name])
            model.graph.initializer.append(folded_w)
        else:
            raise SystemExit(f"{w_name}: IBNorm Conv weight is not an initializer")
        if b_name in inits:
            model.graph.initializer.remove(inits[b_name])
            model.graph.initializer.append(folded_b)
        else:
            raise SystemExit(f"{b_name}: IBNorm Conv bias is not an initializer")
        # Drop the BN node, rewiring its consumer to read the Slice directly.
        for c in bn_cons:
            c.input[:] = [sl.output[0] if i == bn.output[0] else i for i in c.input]
        model.graph.node.remove(bn)
        folded += 1
    return folded


def rewrite_se_matmuls(model):
    """Rewrite the SE block's constant MatMuls as 1x1 Convs with zero bias.

    A `MatMul(x, W)` over a row vector is a 1x1 convolution with kernel
    `[out, in, 1, 1]`; the surrounding Unsqueeze/Concat/Reshape scaffolding
    stays and is ignored structurally, like U^2-Netp's shape machinery.
    """
    inits, consts, array = const_arrays(model)
    rewritten = 0
    for node in list(model.graph.node):
        if node.op_type != "MatMul":
            continue
        if len(node.input) != 2 or array(node.input[1]) is None:
            raise SystemExit(f"{node.output[0]}: non-constant SE MatMul")
        w = array(node.input[1])
        if w.ndim != 2:
            raise SystemExit(f"{node.output[0]}: SE weight is {w.ndim}-D")
        out_c, in_c = w.shape[1], w.shape[0]
        kernel = w.T.reshape(out_c, in_c, 1, 1).astype(np.float32)
        k_name = node.input[1] + "__conv1x1_weight"
        b_name = node.input[1] + "__conv1x1_bias"
        model.graph.initializer.append(
            numpy_helper.from_array(kernel, name=k_name))
        model.graph.initializer.append(
            numpy_helper.from_array(np.zeros(out_c, dtype=np.float32), name=b_name))
        node.op_type = "Conv"
        node.input[:] = [node.input[0], k_name, b_name]
        del node.attribute[:]
        rewritten += 1
    return rewritten


def fold(model):
    """Folded copy of `model`: BNs pushed into Convs, MatMuls as Convs."""
    model = copy.deepcopy(model)
    n_bn = fold_ibnorm(model)
    n_mm = rewrite_se_matmuls(model)
    return model, n_bn, n_mm


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("onnx")
    parser.add_argument("--graph", required=True, choices=sorted(maml_convert.GRAPHS))
    parser.add_argument("-o", "--out", help="where to write the .maml")
    parser.add_argument("--print-graph", action="store_true", help="dump the folded op flow")
    parser.add_argument("--print-layers", action="store_true")
    parser.add_argument("--print-digest", action="store_true")
    args = parser.parse_args()

    model = onnx.load(args.onnx)
    folded, n_bn, n_mm = fold(model)
    if args.print_graph:
        print(f"folded {n_bn} IBNorm batch norms, rewrote {n_mm} SE MatMuls as 1x1 Conv")
        for n in folded.graph.node:
            if n.op_type not in ("Constant",):
                print(f"  {n.op_type:22s} {n.name}")

    onnx_sha256 = hashlib.sha256()
    with open(args.onnx, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            onnx_sha256.update(block)
    layers, tensors = maml_convert.collect_layers(
        folded, quantise=args.graph in maml_convert.INT8_GRAPHS
    )
    if args.print_layers:
        for layer in layers:
            print(f"{layer.index:4d} t{layer.first_tensor:<4d} {layer.key()}  # {layer.name}")
    if args.print_digest:
        print(f'    "{args.graph}": "{maml_convert.layer_table_digest(layers)}",')
    maml_convert.check_graph(folded, args.graph)

    digest = maml_convert.layer_table_digest(layers)
    pinned = maml_convert.EXPECTED_DIGEST.get(args.graph)
    if pinned is None:
        message = (
            f"{args.graph} has no pinned layer table digest. Add\n"
            f'    "{args.graph}": "{digest}",\n'
            "to EXPECTED_DIGEST once the Rust net agrees with this table, so that a later\n"
            "change to either side fails loudly instead of shifting every tensor."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)
    elif digest != pinned:
        message = (
            f"layer table digest {digest}\n"
            f"           pinned  {pinned}\n"
            "The ordered Conv/ConvTranspose table changed, so the hardcoded forward\n"
            f"pass in library/ml/src/main/rust/src/nets/{args.graph}.rs no longer\n"
            "matches this export. Re-read it with --print-layers, update the Rust,\n"
            "then re-pin with --print-digest."
        )
        if not args.print_digest:
            raise SystemExit(message)
        print(message, file=sys.stderr)
    if args.out:
        blob, count = maml_convert.build(
            layers, tensors, maml_convert.GRAPHS[args.graph], onnx_sha256.digest()
        )
        with open(args.out, "wb") as f:
            f.write(blob)
        print(f"wrote {args.out} ({len(blob)} bytes, {count} tensors)")


if __name__ == "__main__":
    main()
