#!/usr/bin/env python3
"""Golden for Gemma 4's text decoder: litertlm's own answer on a short prompt.

`examples/check_gemma4_parity.rs` reads what this writes. Nothing else in the
port compares the text tower's *numbers* against the model they came from -
the layout tests check that 999 tensors are declared in the order the
converter writes them, which a transposed read satisfies perfectly.

# Reference choice

The plan lists candidates in order: the ExecuTorch `.pte` via an
optimum-executorch runner, the litertlm bundle via its python engine, the
artisan TFLite via ai-edge-litert, else cross-check vs base sections. The
first two are not runnable on this host (no optimum, no litertlm engine
build); what IS runnable is the next best thing, and it is exact, not a
fallback: the BASE bundle's own Sections 2 (embedder) + 3 (per-layer
embedder) + 10 (prefill_decode), driven through ai-edge-litert's interpreter.
`litertlm_to_maml.py` reads those same sections, so this is the reference the
converter claims to transcribe.

# Drive protocol (read off litertlm's own executor)

`runtime/executor/litert_compiled_model_executor_utils.cc`,
`FillSingleBufferCacheParamTensor`: `param_tensor = [start, end, end, 0...]`
with `end = start + update_length`. `FillAttentionMask`: bool mask memset 0,
then the attended `[causal_start, causal_end)` range filled - True attends.
The prompt is short (6 tokens, inside every sliding window), so a plain
causal mask is exactly right for both layer types. KV caches start zeroed
and are carried step to step; `input_pos = [position]`.

# What it writes

    logits_golden.json   {"tokens": [...], "top_ids": [...10], "top_logits": [...10]}
                         from the last decode step's [1,1,262144] logits.
    trace.json           {"ple_combined": [...]} for the Rust bisect.

    Trace coverage note: the Rust harness also looks for `l0_out`/`l1_out`
    (hidden states after layers 0/1) and prints "not in the trace file" when
    absent. Those are layer outputs, which Section 10 does not expose - its
    intermediates are not readable through the interpreter API, and rebuilding
    the 818 MB flatbuffer with extra outputs is not worth it for a bisect aid.
    `ple_combined` is the one that arbitrates the embed-assembly suspect, and
    any composition error inside the layers moves the logits ranking, which the
    gate catches. If the gate fails, layer outputs can be lifted by naming the
    `layer_N.post_qkv/add2` tensors (ids 453/531) as extra model outputs.

    `ple_combined` is computed in numpy from the reference's own S2/S3 outputs
    plus the shared-projection weights from the file (same dequantisation the
    converter uses - the check this arbitrates is the gather + combination,
    not the dequant): projected = W @ hidden / sqrt(1536), grouped RMS norm
    over 35x256, plus the S3 gather, over sqrt(2). It matches the `per_layer`
    the Rust `Trace { layers: 0 }` path returns.

    python scripts/ml/gemma4_text_parity.py -o build/gemma4
"""
import argparse
import json
import os

import numpy as np
from ai_edge_litert.interpreter import Interpreter
from tokenizers import Tokenizer

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from litertlm_to_maml import LitertlmReader

# A short text-only prompt, inside every sliding window so one causal mask
# serves both layer types. Tokenizer already prepends <bos> (id 2).
PROMPT = "The capital of France is"
CONTEXT = 32003

EPSILON = 1e-6


def load_interpreter(path):
    it = Interpreter(model_path=path)
    it.allocate_tensors()
    return it


def ple_combined(hidden, embedded, proj_w, proj_norm):
    """The per-layer combination exactly as `gemma4::build` computes it.

    `hidden` [1536] is S2's embedding; `embedded` [35, 256] is S3's gather;
    the projection/norm come from the file. Returns [8960].
    """
    projected = (proj_w.astype(np.float64) @ hidden.astype(np.float64))
    projected = projected / np.sqrt(1536.0)
    grouped = projected.reshape(35, 256)
    var = (grouped ** 2).mean(axis=1, keepdims=True)
    normed = grouped / np.sqrt(var + EPSILON) * proj_norm.reshape(1, 256)
    combined = embedded.reshape(35, 256) + normed
    return (combined / np.sqrt(2.0)).reshape(-1)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--out", required=True, help="output dir")
    ap.add_argument("--tokenizer", default="analysis/et-gemma/tokenizer.json")
    ap.add_argument("--s2", default="analysis/base-sections/Section2_TFLiteModel_tf_lite_embedder.tflite")
    ap.add_argument("--s3", default="analysis/base-sections/Section3_TFLiteModel_tf_lite_per_layer_embedder.tflite")
    ap.add_argument("--s10", default="analysis/base-sections/Section10_TFLiteModel_tf_lite_prefill_decode.tflite")
    ap.add_argument("--weights", default="analysis/gpu-sections/Section2_TFLiteModel_tf_lite_artisan_text_decoder.tflite",
                    help="artisan text decoder holding the shared projection/norm")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    tokens = Tokenizer.from_file(args.tokenizer).encode(PROMPT).ids
    print(f"prompt {PROMPT!r} -> {len(tokens)} tokens {tokens}")

    s2 = Interpreter(model_path=args.s2).get_signature_runner("embedder")
    s3 = Interpreter(model_path=args.s3).get_signature_runner("per_layer_embedder")
    rdr = LitertlmReader(args.weights)
    proj_w = rdr.dequant8("transformer.embedder.per_layer_model_projection.w", 8960, 1536)
    proj_norm = rdr.f32("transformer.embedder.per_layer_projection_norm.scale")
    s10 = load_interpreter(args.s10)
    details = {d["name"].split(":")[0]: d for d in s10.get_input_details()}
    out_details = {d["name"].split(":")[0]: d for d in s10.get_output_details()}
    index_of = {k: v["index"] for k, v in details.items()}

    # Zero caches, shaped from the inputs. Names here are the raw tensor
    # names (decode_kv_cache_k_0:0), keyed short below.
    short = {k.split(":")[0].replace("decode_", ""): k for k in details}
    cache_keys = [k for k in details if "kv_cache" in k]
    cache = {}
    for k in cache_keys:
        cache[k] = np.zeros(list(details[k]["shape"]), np.int8)

    logits = None
    for step, token in enumerate(tokens):
        emb = s2(token_ids=np.array([[token]], np.int32))["embeddings"]
        ple = s3(token_ids=np.array([[token]], np.int32))["embeddings"]
        if step == len(tokens) - 1:
            last_hidden = emb.ravel().copy()
            last_embedded = ple.ravel().copy()
        mask = np.zeros([1, 1, 1, CONTEXT], bool)
        mask[0, 0, 0, : step + 1] = True
        feed = dict(cache)
        feed[short["embeddings"]] = emb
        feed[short["per_layer_embeddings"]] = ple
        feed[short["input_pos"]] = np.array([step], np.int32)
        feed[short["mask"]] = mask
        feed[short["param_tensor"]] = np.array([[[[step, step + 1, step + 1, 0, 0, 0, 0]]]], np.int32)
        for k, v in feed.items():
            s10.set_tensor(index_of[k], v)
        s10.invoke()
        for k in cache_keys:
            cache[k] = s10.get_tensor(index_of[k]).copy()
        logits = s10.get_tensor(2702).ravel().astype(np.float64)
        top = np.argsort(logits)[-3:]
        print(f"  step {step} token {token}: top {top.tolist()}")

    order = np.argsort(logits)[::-1][:10]
    golden = {
        "tokens": tokens,
        "top_ids": [int(i) for i in order],
        "top_logits": [float(logits[i]) for i in order],
    }
    with open(os.path.join(args.out, "logits_golden.json"), "w") as f:
        json.dump(golden, f)
    print(f"argmax {order[0]} logit {logits[order[0]]:.4f}")

    trace = {"ple_combined": ple_combined(last_hidden, last_embedded, proj_w, proj_norm).tolist()}
    print(f"trace shapes: ple {len(trace['ple_combined'])}")
    with open(os.path.join(args.out, "trace.json"), "w") as f:
        json.dump(trace, f)
    print(f"wrote {args.out}/logits_golden.json + trace.json")


if __name__ == "__main__":
    raise SystemExit(main())
