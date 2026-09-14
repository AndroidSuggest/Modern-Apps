# MAML v2 — format and loader contract

Status: **spec, no code**. Clean-slate redesign. v1 (header + ordered
tensors, topology hardcoded in `nets/`) is dropped outright: no reader
compat, no version negotiation, no migration path. A v2 reader rejects
anything that is not v2; there is no v1 reader anymore.

## 0. Why v1 had to go

v1 stored weights only: `(name, dtype, shape, offset, length)` per tensor,
no operators, no topology, no shapes usable at load. Every consequence was
measured on-device (Pixel 9 Pro XL, Mali-G715) before this spec was written:

1. **No fusion, structurally.** Topology lived in twelve hand-written Rust
   forward passes emitting a flat `Vec<Op>`. By the time an optimisation
   could recognise a producer/consumer pair, the edges were gone.
   Epilogue fusion alone is ~26% of Supertonic's elements and 18.8% of
   Gemma 4 prefill's (measured from the plan builder).
2. **Shapes were runtime push constants, not load-time literals.** Every
   shader compiled once, generically; the driver could not unroll, vectorise,
   or strength-reduce. Doubling arithmetic intensity of the hottest kernel
   moved wall time 0.6% (measured).
3. **NCHW put every reduction on the strided axis.** A 1x1 convolution —
   all of a transformer, 92% of the sampler by parameter count — reduces over
   channels `positions * 2` bytes apart. Never one vector load.
4. **One barrier per op, always.** The recorder emitted
   `bind -> push -> dispatch -> BARRIER` per node because the plan claimed
   "each depends on the results of the ones before it". Most pairs do not.
5. **One static graph could not express an LLM.** Prefill (compute-bound,
   matrix-matrix) and decode (bandwidth-bound, matrix-vector, growing
   KV-cache) were forced through one plan shape with per-token re-record.

v2 fixes all five in the format, not in per-net code: the file carries the
graph, every shape, and the state descriptors, and the loader lowers that to
an optimised plan once.

## 1. What v2 steals, from where

| # | Technique | Source | v2 mechanism |
|---|-----------|--------|--------------|
| 1 | Graph + weights in one versioned container | ONNX `ModelProto`/`GraphProto` | §2, §5 |
| 2 | `constant` vs `placeholder` split; simplification before backend | ONNX `initializers` vs `inputs`, `onnxsim` | §5.1, §10 |
| 3 | mmap/zero-copy; read-only constants vs mutable scratch; offsets not pointers | LiteRT/ExecuTorch `.pte` (`constant_data` + `execution_plan` + delegates) | §2, §4 |
| 4 | Per-tensor shape + dtype + lifetime; memory planned ahead, I/O unplanned for zero-copy import | ExecuTorch `allocation_info`, `EValue` | §4, §7 |
| 5 | Quant params as data (per-channel scales, blockwise details), not opcode variants; opaque delegate blobs | LiteRT `QuantizationParameters` / `BlockwiseQuantization`, `BackendDelegateInlineData` | §3.3 |
| 6 | Backend specialisation at init: fusion, layout transform, per-shape kernels, binary cache | LiteRT GPU delegate | §7, §8 |
| 7 | Prefill vs decode as **two entry points**; KV-cache as runtime state with a layout descriptor, never file bytes | LiteRT-LM | §6 |
| 8 | Version + pin ruthlessly (`runtime_min_version`, converter version, backend feature bits) | LiteRT-LM import practice | §9 |
| 9 | Verbatim weight upload (file offset = device offset, one `VkBuffer`) | v1's one real advantage, measured 86 ms for 105 MB | §4.2, kept |
| 10 | Dependency-correct barriers from exact read/write ranges (RAW/WAR/WAW over recycled offsets) | v1 `nets/schedule.rs` | §7.5, generalised to SSA |

What v2 explicitly does **not** take: protobuf (FlatBuffers mmaps cleanly;
§2), single static signature for LLMs (§6), JIT-on-device or per-shape
re-trace in the hot path (§8), NPU-mandated paths (§11).

## 2. Container

FlatBuffers. `file_identifier "MAM2"`, `file_extension "maml"`.
Schema lives at `library/ml/src/main/rust/schema/maml2.fbs` and is the
normative definition; this document is the rationale. Tables (all new fields
appended at the end, LiteRT schema rule):

```
Model {
  version: uint;              // file format version. Loader rejects mismatch. No minor/major game: unequal means reject.
  opset_version: uint;        // op catalog version (§5.3). Loader rejects newer than it implements.
  runtime_min_version: string;// semver floor for the loader, e.g. "2.3.0".
  converter_version: string;  // what wrote this file, for bisection.
  source_sha256: [ubyte:32];  // upstream artifact hash. Traceability, as v1.
  graph_digest: [ubyte:32];   // SHA-256 over the canonical op/edge/attr sequence (§9.2). Replaces v1's graph_id integer.
  description: string;        // human-readable, e.g. "gemma4-text int4".
  backends: [BackendHint];    // optional per-entry-point placement (§6.3).

  tensors: [Tensor];          // §4. Every value in every graph, weights and computed alike.
  buffers: [Buffer];          // §4.2. Weight bytes. Computed tensors reference no buffer.
  graphs: [Graph];            // §5. Usually one; LLM text models ship prefill + decode sharing tensors.
  entry_points: [EntryPoint]; // §6. Named signatures over graphs.
  metadata: [KeyValue];       // free-form (tokenizer asset name, license, calibration note). Never load-bearing.
}
```

Loading is `mmap` + verify + dereference in place. The loader never
deserialises weights into a second allocation (§4.2).

## 3. Type system

### 3.1 Dtypes

`F16, F32, I32, U32, I8, I4, BF16`. Integer dtypes are always quantised
weights (§3.3); activations and all computed tensors are float. There is no
`STRING`, no `RESOURCE`, no sparse support — the catalog (§5.2) has no op
that needs them, and the schema must not carry generality the runtime pays
to ignore.

### 3.2 Shapes — the load-bearing section of this spec

Every `Tensor` carries a **static shape**: `dims: [int]` with all values
`> 0`. A static shape is a promise the loader may bake into compiled
pipelines. In addition, a tensor may carry `dim_params: [DimParam]` for
sequence-like axes:

```
DimParam { axis: uint; symbol: string; max: int; }
```

e.g. a decode-step query is `dims = [1536, 1, 2048]`, `dim_params = [{axis: 2,
symbol: "K", max: 2048}]`: stride 2048 baked, live length `K ≤ 2048` supplied
per submit as a step uniform (v1's `StepParams::prefix` mechanism, kept).
Rules:

* Rank is fixed. Only named axes vary, and only with an explicit `max`.
* The loader specialises pipelines against `dims` (the maxima), never
  re-compiling per live length. One decode plan serves all positions.
* Shape inference runs at load (§7.2): every computed tensor's static shape
  is re-derived from inputs and attributes and must equal the stored shape.
  A mismatch is a load error, not a re-record trigger.
* No `-1`-without-`max` anywhere. LiteRT's `shape` vs `shape_signature`
  split taught this: unbounded dynamics force re-plan in the hot path.

### 3.3 Quantization — data, not opcodes

```
Quantization {
  kind: QuantKind;         // NONE | PER_CHANNEL | BLOCKWISE
  scale_tensor: int;       // index into Model.tensors. Always fp16. -1 when kind == NONE.
  zero_point: int;         // default -1 (symmetric, zp 0). Kept for schema completeness; symmetric only is enforced at validation.
  quantized_dim: int;      // which axis scales run along (PER_CHANNEL), or the contraction axis (BLOCKWISE).
  block_size: int;         // taps per scale for BLOCKWISE. v1's 32-wide I4 blocks carry over.
}
```

* int8: one fp16 scale per output channel (`PER_CHANNEL`).
* int4: one fp16 scale per block of 32 taps along the contraction axis
  (`BLOCKWISE`), nibbles packed two per byte low-first — v1's layout,
  unchanged, because every kernel already implements it.
* A quantised tensor's `buffer` holds packed bytes; `elem_count` is logical
  elements. The loader checks `buffer_bytes == ceil(elems / stride)`.
* Scales are ordinary tensors in `Model.tensors` (LiteRT `BlockwiseQuantization.scales`
  precedent), so they upload verbatim with everything else and need no
  special path. Activations stay fp16: the export's dynamic activation
  quantisation is out of scope, as in v1.

### 3.4 Layouts

```
Layout : byte { NCHW = 0; CHANNEL_BLOCKED_4 = 1; POSITION_MAJOR = 2; }
```

* Each tensor declares its layout. Kernels are **always NCHW** in the file:
  every kernel — NCHW or blocked twin — reads kernel bytes NCHW, so one
  stored layout serves every execution layout and the host interpreter
  (NCHW-only) stays a valid oracle for all of them. Only activations block.
  (The pilot stored blocked kernels; that forked the interpreter from the
  device with both sides self-consistent, and was reverted.)
* Computed tensors are `CHANNEL_BLOCKED_4` where the dispatched kernels
  consume blocked activations (the PHWC4 equivalent): channel groups of 4
  contiguous, so a channel reduction is a `vec4` load. The v1 NCHW
  strided-reduction defect (§0.3) becomes inexpressible wherever twins run.
* Mixed plans carry explicit transpose ops at NCHW↔blocked boundaries; a
  producer/consumer pair with different layouts and no transpose between
  them is a loader bug, caught by shape+layout inference at load.
* During the migration most files are all-NCHW (few dispatched kernels have
  blocked twins yet): they declare NCHW throughout, honestly. A file is
  re-emitted blocked when its net's transpose boundaries + twins land —
  files are generated artifacts, and the layout declaration always matches
  the execution the file was emitted for.
* KV-caches are `POSITION_MAJOR`: `[K, 1, d]` positions appended per step
  (v1's decode-cache layout, kept and now declared instead of implicit).
* Layout is part of the pipeline cache key (§8.2).

## 4. Tensors and buffers

```
Tensor {
  name: string;            // debug + error messages only. Never resolved by name at load.
  dtype: DType;
  dims: [int];             // §3.2, all > 0.
  dim_params: [DimParam];  // §3.2, optional.
  layout: Layout;          // §3.4.
  quantization: Quantization; // §3.3, NONE for computed tensors.
  buffer: int;             // index into Model.buffers, or -1 for computed/state tensors.
  buffer_offset: ulong;    // byte offset within the buffer.
  elem_count: ulong;       // logical elements; cross-checked against dims product.
  placement: Placement;    // DEVICE (default) | HOST. HOST = gathered on CPU (v1's GEMMA4_EMBED precedent: one row per step, never bound whole).
  state: StateKind;        // NONE | KV_CACHE | PINNED. §6.2.
}
Buffer {
  data: [ubyte];           // force_align 16. One buffer per file in practice; many allowed.
}
```

### 4.1 SSA discipline

Within a graph, every computed tensor has exactly one writer. File tensors
(weights, constants) have none. Graph inputs are written by the caller.
Validation rejects multi-writer and cycles (§9.2). This is what makes fusion
(§7.3), memory planning (§7.4), and barrier scheduling (§7.5) one pass each
instead of per-net code.

### 4.2 Verbatim upload, preserved

The v1 property that matters — file offset = device offset, whole blob to
one `VkBuffer` with no host copy — is kept and strengthened: `Buffer.data`
is 16-aligned, every tensor's `buffer_offset` is 16-aligned, and the loader
uploads each buffer with chunked `cmd_copy_buffer`s straight from the mmap
(v1's `Blob`/`Streamed` precedent: peak host cost one chunk, never 3x the
model in transient heap). Kernel-chosen repacking happens in the converter,
so verbatim upload and optimal layout stop being in conflict.

## 5. Graph

```
Graph {
  name: string;            // "prefill", "decode_step", "encode", ...
  tensors: [int];          // indices into Model.tensors forming this graph's SSA scope.
  nodes: [Node];           // topological order. The file order IS the schedule seed.
  inputs: [int];           // tensor indices, declaration order = binding order.
  outputs: [int];          // tensor indices.
}
Node {
  op: Op;                  // §5.2 catalog enum.
  inputs: [int];           // tensor indices.
  outputs: [int];          // tensor indices (usually one).
  attrs: [Attribute];      // typed attributes, op-specific (§5.2). No untyped blobs except CustomOp.payload.
}
Attribute {
  name: string;
  value: AttrValue;        // int | ints | float | floats | bool | string. FlatBuffers union.
}
```

### 5.1 Convert-time obligations (ONNX lessons)

The converter runs, in order: **simplify → constant-fold → fold-into-weights
→ DCE**, and the file holds the result:

* Fold `BatchNorm` into the preceding linear op (exact affine fold).
* Fold affine-into-unpadded-conv; never into padded conv (border correction
  is position-dependent — v1 `ppocr_fold.py` lesson, now a spec rule).
* Fold `Mul(x, Sigmoid(x))` → `Swish`, `Erf`-spelled gates → `Gelu`,
  gate/up-`Mul` over a fused projection → `GatedSwish` (§5.2).
* Constant subgraphs (style keys, angle tables with fixed length, masks)
  become `Constant` tensors, not nodes.
* DCE removes shape-scaffolding initializers and anything with no path to
  an output. What the runtime loads is the minimal executable graph.
* `Gemm` over a flattened map is `Conv` with a full-extent kernel (v1's
  MobileFaceNet precedent — no `InnerProduct` pipeline exists).

### 5.2 Op catalog (high-level, fusable)

Coarse ops with typed attributes. The runtime fuses epilogues and layouts at
load (§7.3); the file never stores a fused mega-op, so fusion stays a loader
decision that improves with the runtime, not a converter lock-in.

| Op | Inputs → Outputs | Key attrs |
|----|-----------------|-----------|
| `Conv` | x, (w, b) → y | `kernel, stride, dilation, pads, groups, pad_edge, activation, res, shift` |
| `MatMul` | a, b → y | `transpose_b, activation, res` — covers 1x1 convs, projections, heads; lowering (tiled GEMM vs GEMV) is the loader's choice per shape |
| `DepthwiseConv` | x, (w, b) → y | `kernel, stride, pads, activation` — separate op, separate kernel family |
| `Embedding` | ids, table → y | `rows` — ids lane-split in converter for vocabs past fp16-exact range (v1 `EMBED_LANE` precedent) |
| `LayerNorm` / `RmsNorm` | x, (gamma, beta?) → y | `epsilon, groups` |
| `Attention` | q, k, v → y | `heads, kv_heads, scale, causal, sliding_window, softcap, out_proj` (fused output projection tensor ref, or -1). Banded/sliding lowering chosen by loader from `sliding_window` + live length |
| `AttentionDecode` | q, k_cache[state], v_cache[state] → y | `heads, kv_heads, scale, sliding_window, softcap, out_proj, dynamic_keys` — one query against position-major caches; key count from step uniform |
| `CacheWrite` | row, cache[state] → (cache) | none — destination position from step uniform; the only op whose write address is submit-time |
| `RotaryEmbedding` | x, angles → y | `heads, axes` (1 = 1-D RoPE, 2 = split row/col) |
| `Softmax` | x → y | `mode: FULL \| CAUSAL \| PREFIX`, `window` |
| `Add, Mul, MulBroadcast, AddBroadcast` | a, b → y | none — broadcast kinds explicit (v1's channel-broadcast precedent) |
| `Affine` | x → y | `scale, shift` scalars — only where folding is illegal (post-activation, padded consumer) |
| `GatedSwish` | fused `[gate\|up]` projection → y | `activation` — replaces slice + act + mul |
| `Clamp, MulScalar, Softcap` | x → y | `min/max | scale | cap` |
| `Resize` | x → y | `mode: BILINEAR_HALF_PIXEL \| NEAREST_ASYMMETRIC`, `scales` |
| `MaxPool, AvgPool, GlobalAvgPool` | x → y | `kernel, stride` |
| `Concat` | parts → y | `axis` — channel concat lowers to arena copies, no shader (v1 precedent); width concat is strided and keeps a kernel |
| `View` | x → y | `offset, dims` — zero-copy reinterpret (head splits, squeezes). Must be within the source allocation; validated |
| `Constant` | (weights) → y | none — learned tensor copied to arena once |
| `Copy` | x → y | none — contiguous move the loader may implement as transfer or elide |
| `CustomOp` | declared → declared | `name, payload:[ubyte]` — opaque; loader rejects unknown names. Escape hatch, not a habit |

Epilogue fusion surface (stored as attributes on the producer, §7.3):
`activation` (None/Relu/Swish/Gelu/Sigmoid/Clip01/HardSwish/PRelu-with-slope-ref),
`res` (tensor index of a same-shape residual addend, or -1),
`shift` (tensor index of a per-channel shift, or -1). Only single-consumer
epilogues fuse; the loader checks consumer count from SSA.

### 5.3 Opset and extension

`Model.opset_version` pins this catalog. New ops bump it; loaders reject
`opset_version` newer than implemented — loudly, at open, never as wrong
answers. `CustomOp` names are namespaced (`vendor.op`) and never promoted
silently into the catalog.

## 6. Entry points and state (the LiteRT-LM section)

```
EntryPoint {
  name: string;            // "encode" | "prefill" | "decode_step" | "image" | "text" ...
  graph: uint;             // index into Model.graphs.
  inputs: [string];        // roles, e.g. ["tokens", "images", "mel"]. Binds positionally to Graph.inputs.
  step_uniforms: [string]; // live values per submit: e.g. ["prefix_len", "window_start"].
}
```

### 6.1 Prefill vs decode

An LLM ships **two graphs sharing one tensor table**: `prefill` (full
sequence, matrix-matrix, compute-bound) and `decode_step` (one query,
matrix-vector against caches). They share weight tensors by index — the file
holds 4.7 GB of embeddings once even when both graphs reference them. TTFT
(prefill) and TTIT (decode) are accepted per entry point, never as one
blended latency (§8.3).

### 6.2 State tensors

```
StateKind : byte { NONE = 0; KV_CACHE = 1; PINNED = 2; }
```

* `KV_CACHE`: shape declares capacity (`max_tokens` via `dim_params`),
  dtype, and `POSITION_MAJOR` layout. The file embeds **no cache contents** —
  capacity, not data. The loader allocates per session; the host may
  export/import pinned prefix caches across launches (v1's
  `export_pinned`/`import_pinned` precedent for recomputed system blocks).
* `PINNED`: survives across submits at a stable offset (inputs the caller
  reuses, prefix constants). Declared so the memory planner never recycles
  them.
* Cache growth never re-plans: capacity is the baked maximum, live length
  is a step uniform. Variable-length re-record per token is the defect this
  section exists to forbid.

### 6.3 Placement

`backends: [{entry_point, preferred: GPU|CPU, fallback: GPU|CPU}]` records
the tested placement (e.g. embeddings gathered on host, sampler on GPU).
Advisory: the loader may override, but the default path must match what the
converter validated parity against.

## 7. Load-time pipeline (normative order)

1. **Verify** (§9.1): magic, versions, bounds, alignment.
2. **Shape+layout inference**: re-derive every computed tensor's shape and
   layout from inputs and attributes; must equal stored. `View` bounds,
   single-writer SSA, acyclicity checked here.
3. **Lowering choice**: `MatMul` → tiled-GEMM / GEMV / vec kernel by shape
   (the v1 `ConvPoint` vs `ConvVec` routing, now data-driven); `Attention`
   → full / banded / cached by `sliding_window` and graph role.
4. **Fusion**: fold single-consumer elementwise epilogues, `res`/`shift`
   stores, and `GatedSwish` patterns into producers via the §5.2 attributes.
   Fusion never changes numerics beyond fp16 reassociation already accepted
   by parity (§8.3).
5. **Memory plan**: liveness from SSA + `pinned`/`state` exclusions → arena
   offsets with free-list packing (v1 `Builder::finish` precedent). Arena
   size is an output of the loader, not a file field — it depends on fusion.
6. **Barrier schedule**: from exact read/write ranges per lowered op
   (RAW/WAR/WAW over recycled offsets, v1 `schedule.rs` generalised from
   per-`Kind` audits to SSA-derived ranges). Barriers only where the schedule
   says so; the one-barrier-per-op recorder is gone. Transfer-stage
   inclusion wherever a `Copy`/upload is on either side.
7. **Specialisation**: shapes, strides, and layout constants baked per
   pipeline (specialization constants / generated variants); one pipeline
   creation per (op, shape-class) cached by `(graph_digest, entry_point,
   tensor dims…)` fingerprint. Measured creation cost (~12 ms) is paid once
   at load, never per inference.

## 8. Runtime contract (what the loader guarantees)

* §8.1 **One record, then submit.** Each entry point records once per shape
  class into one command buffer. An inference is upload-inputs, one submit,
  readback — no per-frame allocation, no per-token re-record.
* §8.2 **Pipelines are cached and keyed.** Cache key includes graph digest,
  entry point, op, full static dims, layout, and dtype. Cold-driver compile
  (~1.4 s first launch) is amortised across sessions via the driver's cache
  under this key, never recompiled silently.
* §8.3 **Parity-gated.** Every (graph, entry point) ships with a converter-
  produced reference vector (`metadata["parity_sha256"]` + out-of-band
  fixture). Loader changes that move any output past the accepted tolerance
  fail `onnx_parity`-equivalent checks before review. Performance tables
  always carry an output-correctness column (frame/token count) — the
  truncated-utterance lesson is now a process rule.
* §8.4 **Host boundary is at entry-point I/O only.** Intermediates never
  leave the device. Inputs upload once per submit; outputs read back once.
  (v1's per-net-call f32↔f16 staging dance is replaced by entry-point
  bindings with declared dtypes.)

## 9. Versioning and validation

* §9.1 **Structural validation at open** (before any device touch):
  magic `MAM2`; `version` equal to the loader's; `opset_version ≤`
  loader's; every tensor ref `< tensor_count`; every buffer range within
  its buffer and 16-aligned; `elem_count == product(dims)` (with packed-size
  math for I4); `dim_params` axes in range with `max ≥ dims[axis]`;
  `Views` within source bounds; quant `scale_tensor` shapes (`[out_c]` /
  `[out_c, ceil(taps/block)]`).
* §9.2 **Semantic validation after inference**: single-writer SSA,
  acyclicity, every weight tensor read-or-host (v1's every-tensor rule),
  every graph output traceable to inputs, `graph_digest` recomputed and
  matched. Failure is a load error with tensor/node identity — never a
  wrong answer, never a driver reset.
* §9.3 **No silent fallback.** A file that validates but names an unknown
  `CustomOp`, or an entry point the loader cannot place, fails. Falling back
  to a different topology is the failure mode v1's graph_id gate existed to
  prevent; v2's digest gate inherits it.

## 10. Converter responsibilities

The converter (successor to `maml_convert.py`) owns everything that can be
decided offline: ONNX/checkpoint/TFLite import, §5.1 simplification passes,
layout repacking (§3.4), quantisation with per-tensor cosine gates (v1's
`MIN_INT8_COSINE`/`MIN_INT4_COSINE` precedent), digest computation, parity
fixtures, and the layer-table emission checklist per model. The runtime owns
everything that needs the device: §7 lowering, fusion, planning,
specialisation, scheduling. The boundary is: converter decides **what** to
compute and in **what layout**; runtime decides **how** to execute it.

## 11. Non-goals (explicit)

* No NPU/delegate pre-compiled blobs in v2 files. The schema reserves
  `BackendHint` and a future `delegate_blobs` section, but no backend
  consumes them yet; GPU-Vulkan is the only lowering target.
* No training, no gradients, no control flow beyond the entry-point set.
  Data-dependent loops stay on the host (sample Euler steps, sampler loops,
  beam handling) calling entry points per step — as v1's `post/` did.
* No text tokenizer inside the model file. Tokenizer assets are referenced
  by `metadata["tokenizer"]` and shipped alongside, LiteRT-LM-bundle style,
  never parsed by the Vulkan loader.
* No multi-file sharding in v2.0. Large-model splitting (embeddings apart
  from text, tower-per-file optionality) stays a file-selection decision,
  expressed by shipping several single files, not by intra-file partitions.

## 12. Open questions

1. FlatBuffers schema evolution vs strict `version` equality (§2): LiteRT
   appends fields for back-compat, but v2 has no installed base. Keep strict
   equality until the first shipped release, then relax to `≤`? **Proposed:
   strict until v2 ships on-device, then append-only.**
2. Tuning hints table: should the converter emit preferred
   `(op, shape-class) → (workgroup, tile)` hints from offline benchmarking,
   or does the runtime own all workgroup selection? **Proposed: optional
   `hints` section loaders may ignore; runtime autotune wins on conflict.**
3. `Attention`'s fused `out_proj`: tensor ref inside the attribute vs a
   separate `MatMul` node the fusion pass folds? Separate node keeps SSA
   pure but costs the loader a mandatory fusion; attribute keeps the common
   case atomic. **Proposed: separate node + mandatory fusion rule, so the
   unfused form stays expressible for parity.**
4. Prefix-cache serialization format for `export_pinned` across app
   versions: digest-keyed raw snapshots vs versioned container?
   **Proposed: raw snapshots keyed by `(graph_digest, entry_point)` with a
   header carrying both; loader rejects mismatched digests.**
