//! Lower a verified + inferred v2 graph to an executable [`Plan`].
//!
//! The loader replays the file's SSA nodes through the existing `*_raw`
//! [`Builder`] entry points — the same door the v1 graph-section loader
//! uses — so shape propagation, fusion, arena packing, and `Op` emission
//! (including the tiled/GEMV routing) are shared rather than reimplemented.
//! The v2 tensor table is bridged by a [`WeightSource`] over the v2 buffers.
//!
//! Phase 1 covers the sampler op subset (spec 5.2): Conv (fp16), MatMul
//! (int8 1x1), Add/Mul/AddBroadcast, LayerNorm, Attention (scores + apply
//! phases), Softmax (full), RotaryEmbedding, View (slice-channels).

use std::collections::HashMap;

use crate::maml2::fb;
use crate::maml2::infer::InferredGraph;
use crate::maml2::verify::Verified;
use crate::nets::{Act, Builder, Id, Plan, Shape, WeightSource};

/// Activation from the v2 `activation` attribute code.
///
/// Inverse of the emitter's `act_code`: 0 none, 1 relu, 2 hardswish,
/// 3 sigmoid, 5 clip01, 6 swish, 8 gelu, 4 PRelu (whose slope rides a
/// `slope` tensor attr the caller resolves separately).
fn act_from_code(code: i32) -> Result<Act, String> {
    match code {
        0 => Ok(Act::None),
        1 => Ok(Act::Relu),
        2 => Ok(Act::HardSwish),
        3 => Ok(Act::Sigmoid),
        4 => Ok(Act::PRelu(0)),
        5 => Ok(Act::Clip01),
        6 => Ok(Act::Swish),
        8 => Ok(Act::Gelu),
        _ => Err(format!("activation code {code} is not a v2 activation")),
    }
}

/// A [`WeightSource`] over v2 weight tensors.
///
/// The bridge lays the v2 payloads out in tensor order at 16-aligned byte
/// offsets — v1's scheme — and hands out `byte / 2` element indices for
/// fp16 tensors and `byte / 4` word indices for quantized ones, exactly as
/// v1's `elem_offset` / `word_offset` do. One blob, two consistent address
/// spaces: the interpreter and the device both read the plan's offsets
/// against [`V2Weights::blob`], and an fp16 read at element `e` and a quant
/// read at word `w` land at blob bytes `2e` and `4w` with no rebase.
///
/// Read-marking lives in the loader, not here: `lower` marks every weight
/// tensor the nodes name before finishing (host tensors via an explicit
/// list), satisfying the every-tensor rule without the bridge needing
/// interior mutability.
pub struct V2Weights {
    /// Payload bytes per v2 tensor index (empty for computed tensors).
    payloads: Vec<Vec<u8>>,
    /// Logical dims per v2 tensor index, for the shape cross-check in
    /// `shaped` (the `host_tensor` path resolves through `weight`, which
    /// checks dims like any other tensor).
    dims: Vec<Vec<u32>>,
    /// Element offset per v2 tensor index (fp16): blob byte / 2.
    elem_offsets: HashMap<usize, u32>,
    /// Word offset per v2 tensor index (quantized kernels): blob byte / 4.
    word_offsets: HashMap<usize, u32>,
    /// Blob byte offset per v2 tensor index, 16-aligned.
    placements: HashMap<usize, u32>,
    /// Blob length in bytes (end of the last payload, unpadded).
    blob_len: usize,
    /// Tensor count (weights + computed), for the `count` gate.
    count: usize,
}

/// Payload start alignment in the blob, matching v1's tensor alignment so
/// every fp16 payload starts on an even byte and every quantized payload on
/// a word boundary.
const BLOB_ALIGN: usize = 16;

impl V2Weights {
    /// Build the bridge from the verified model: copy each buffered weight
    /// payload out of the FlatBuffers vectors.
    pub fn new(verified: &Verified<'_>) -> Result<V2Weights, String> {
        let model = verified.model;
        let tensors = model.tensors().ok_or("a model with no tensors")?;
        let buffers = model.buffers();
        let mut payloads = Vec::with_capacity(tensors.len());
        let mut stored_dims: Vec<Vec<u32>> = Vec::with_capacity(tensors.len());
        let mut elem_offsets = HashMap::new();
        let mut word_offsets = HashMap::new();
        let mut placements = HashMap::new();
        let mut cursor: usize = 0;
        for t in 0..tensors.len() {
            let tensor = tensors.get(t);
            stored_dims.push(
                tensor
                    .dims()
                    .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect())
                    .unwrap_or_default(),
            );
            if tensor.buffer() == -1 {
                payloads.push(Vec::new());
                continue;
            }
            let data = buffers
                .and_then(|b| {
                    if tensor.buffer() >= 0 {
                        b.get(tensor.buffer() as usize).data()
                    } else {
                        None
                    }
                })
                .ok_or_else(|| format!("tensor {t} names a missing buffer"))?;
            let mut bytes = Vec::with_capacity(data.len());
            for i in 0..data.len() {
                bytes.push(data.get(i));
            }
            // One address space, v1's way: the payload lands at the next
            // 16-aligned blob byte, and the offset is that byte divided by
            // the addressing unit — 2 for fp16 elements, 4 for quant words.
            // 16-alignment keeps both divisions exact.
            cursor = cursor.next_multiple_of(BLOB_ALIGN);
            match tensor.dtype() {
                fb::DType::F16 => {
                    elem_offsets.insert(t, (cursor / 2) as u32);
                }
                fb::DType::I8 | fb::DType::I4 => {
                    word_offsets.insert(t, (cursor / 4) as u32);
                }
                _ => return Err(format!("tensor {t} has a non-Phase-1 dtype")),
            }
            placements.insert(t, cursor as u32);
            cursor += bytes.len();
            payloads.push(bytes);
        }
        let blob_len = cursor;
        Ok(V2Weights {
            payloads,
            dims: stored_dims,
            elem_offsets,
            word_offsets,
            placements,
            blob_len,
            count: tensors.len(),
        })
    }

    /// Payload bytes for every v2 tensor index, in order. The caller uploads
    /// these verbatim (one buffer per payload, or concatenated — offsets are
    /// per-payload here and rebased by the caller).
    pub fn payloads(&self) -> &[Vec<u8>] {
        &self.payloads
    }

    /// The payloads laid out the way the resolved plan addresses them: in
    /// tensor order, each at its 16-aligned placement, zero padding between.
    /// The interpreter and the device both read the plan's offsets against
    /// this blob — it is what `Net` uploads when the v2 bridge (not the v1
    /// file) is the weights source.
    ///
    /// Computed tensors (no buffer) contribute nothing; host tensors (no
    /// buffer) likewise — neither is addressed by the plan.
    pub fn blob(&self) -> Vec<u8> {
        let mut blob = vec![0u8; self.blob_len.next_multiple_of(BLOB_ALIGN)];
        for (t, payload) in self.payloads.iter().enumerate() {
            if payload.is_empty() {
                continue;
            }
            let at = self.placements.get(&t).copied().unwrap_or(0) as usize;
            blob[at..at + payload.len()].copy_from_slice(payload);
        }
        blob
    }
}

impl WeightSource for V2Weights {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        // Dims are checked with rank-prefix agreement, not exact equality:
        // v2 weight rows carry their full stored rank (`[m, n, 1, 1]` for a
        // pointwise kernel), while the builders ask in the rank they reason
        // in (`[m, n]`, `[m]`, `[c, 1, 1]`). The stored row must start with
        // the asked dims and hold ones after — the same rule the v1 table
        // enforces structurally (trailing entries beyond rank are zero).
        // The payload class (fp16 vs quantized) is checked by map membership.
        let stored = self.dims.get(index).ok_or_else(|| {
            format!("tensor {index} of {}: out of range", self.dims.len())
        })?;
        if stored.len() < dims.len()
            || stored[..dims.len()] != *dims
            || stored[dims.len()..].iter().any(|&d| d != 1)
        {
            return Err(format!("tensor {index} is {stored:?}, the lowering wants {dims:?}"));
        }
        self.elem_offsets.get(&index).copied().ok_or_else(|| {
            format!("tensor {index} of {}: no fp16 payload", self.payloads.len())
        })
    }

    fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let stored = self.dims.get(index).ok_or_else(|| {
            format!("tensor {index} of {}: out of range", self.dims.len())
        })?;
        if stored.len() < dims.len()
            || stored[..dims.len()] != *dims
            || stored[dims.len()..].iter().any(|&d| d != 1)
        {
            return Err(format!("tensor {index} is {stored:?}, the lowering wants {dims:?}"));
        }
        self.word_offsets.get(&index).copied().ok_or_else(|| {
            format!("tensor {index} of {}: no quantized payload", self.payloads.len())
        })
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// Attribute readers over a v2 node.
struct NodeAttrs<'a> {
    node: fb::Node<'a>,
}

impl<'a> NodeAttrs<'a> {
    fn int(&self, name: &str) -> Result<i32, String> {
        let attrs = self.node.attrs().ok_or_else(|| format!("missing attribute {name}"))?;
        for i in 0..attrs.len() {
            let attr = attrs.get(i);
            if attr.name() == Some(name) {
                return attr
                    .value_as_attr_int()
                    .map(|v| v.value())
                    .ok_or_else(|| format!("attribute {name} is not an int"));
            }
        }
        Err(format!("missing attribute {name}"))
    }

    fn ints(&self, name: &str) -> Result<Vec<i32>, String> {
        let attrs = self.node.attrs().ok_or_else(|| format!("missing attribute {name}"))?;
        for i in 0..attrs.len() {
            let attr = attrs.get(i);
            if attr.name() == Some(name) {
                return attr
                    .value_as_attr_ints()
                    .and_then(|v| {
                        v.value().map(|vec| (0..vec.len()).map(|j| vec.get(j)).collect::<Vec<i32>>())
                    })
                    .ok_or_else(|| format!("attribute {name} is not an int vector"));
            }
        }
        Err(format!("missing attribute {name}"))
    }

    fn opt_int(&self, name: &str) -> Option<i32> {
        let attrs = self.node.attrs()?;
        for i in 0..attrs.len() {
            let attr = attrs.get(i);
            if attr.name() == Some(name) {
                return attr.value_as_attr_int().map(|v| v.value());
            }
        }
        None
    }

    fn float(&self, name: &str) -> Result<f32, String> {
        let attrs = self.node.attrs().ok_or_else(|| format!("missing attribute {name}"))?;
        for i in 0..attrs.len() {
            let attr = attrs.get(i);
            if attr.name() == Some(name) {
                return attr
                    .value_as_attr_float()
                    .map(|v| v.value())
                    .ok_or_else(|| format!("attribute {name} is not a float"));
            }
        }
        Err(format!("missing attribute {name}"))
    }

    fn opt_bool(&self, name: &str) -> bool {
        let Some(attrs) = self.node.attrs() else {
            return false;
        };
        for i in 0..attrs.len() {
            let attr = attrs.get(i);
            if attr.name() == Some(name) {
                return attr.value_as_attr_bool().is_some_and(|v| v.value());
            }
        }
        false
    }
}

/// Lower one inferred graph to a [`Plan`].
///
/// Replays the SSA nodes through the `*_raw` builders in file order (which
/// is topological), then finishes through the shared pipeline: fusion fold,
/// liveness, arena packing, `Op` emission. `weights` bridges the v2 tensor
/// indices the nodes name; `shapes` supplies the graph-input shapes.
///
/// Blocked-kernel selection happens here, after emission, unless `nchw_only`
/// is set: nodes whose activation input and output are both
/// `CHANNEL_BLOCKED_4` have their tiled int8 dispatches rewritten to the
/// blocked twin ([`Kind::ConvPointCb4Int8`]). Kernels are always NCHW in the
/// file — the twin reads its kernel NCHW and only its activations blocked —
/// so the kernel layout needs no check here. Routing stays shape-driven in
/// `emit`; this rewrite is layout-driven and lives with the layout
/// knowledge. See [`BLOCKED_KINDS`].
///
/// `nchw_only` forces the rewrite off. While every emitted file is all-NCHW
/// the two paths lower identically (the rewrite qualifies nothing); once
/// transpose boundaries + blocked twins land per net, `lower` runs the mixed
/// plan and `lower_nchw` stays the all-NCHW proof path.
pub fn lower(
    verified: &Verified<'_>,
    inferred: &InferredGraph,
    weights: &V2Weights,
    entry_graph: usize,
) -> Result<Plan, String> {
    lower_with_options(verified, inferred, weights, entry_graph, false)
}

/// [`lower`] with the blocked-kernel rewrite forced off.
///
/// See `nchw_only` on [`lower`].
pub fn lower_nchw(
    verified: &Verified<'_>,
    inferred: &InferredGraph,
    weights: &V2Weights,
    entry_graph: usize,
) -> Result<Plan, String> {
    lower_with_options(verified, inferred, weights, entry_graph, true)
}

/// Every tensor any graph names: node inputs plus attribute-carried weight
/// refs (PRelu's `slope`), plus host tensors (no buffer, read on the CPU).
///
/// File-wide union across all graphs: in a multi-graph file a weight the
/// lowered graph never names may belong to another graph. Tensors named by
/// nothing at all are dead weight the file refuses to carry — the same gate
/// the emitter enforces, re-checked here so a hand-built file gets no free
/// pass around emission.
fn file_refs(model: fb::Model<'_>) -> Result<Vec<bool>, String> {
    let tensors = model.tensors().ok_or("a model with no tensors")?;
    let graphs = model.graphs().ok_or("a model with no graphs")?;
    let mut read = vec![false; tensors.len()];
    for g in 0..graphs.len() {
        let nodes = graphs.get(g).nodes().ok_or_else(|| format!("graph {g} has no nodes"))?;
        for n in 0..nodes.len() {
            let node = nodes.get(n);
            if let Some(inputs) = node.inputs() {
                for i in 0..inputs.len() {
                    let t = inputs.get(i) as usize;
                    if t < read.len() {
                        read[t] = true;
                    }
                }
            }
            if let Some(attrs) = node.attrs() {
                for i in 0..attrs.len() {
                    let attr = attrs.get(i);
                    if attr.name() == Some("slope") {
                        if let Some(v) = attr.value_as_attr_int() {
                            let t = v.value() as usize;
                            if t < read.len() {
                                read[t] = true;
                            }
                        }
                    }
                }
            }
        }
    }
    for t in 0..tensors.len() {
        if tensors.get(t).buffer() == -1 {
            read[t] = true;
        }
    }
    if let Some(index) = read.iter().position(|&read| !read) {
        return Err(format!(
            "tensor {index} of {} is named by no graph; a v2 file carries no dead weights",
            read.len()
        ));
    }
    Ok(read)
}

/// [`lower`] with an explicit blocked-rewrite switch.
fn lower_with_options(
    verified: &Verified<'_>,
    inferred: &InferredGraph,
    weights: &V2Weights,
    entry_graph: usize,
    nchw_only: bool,
) -> Result<Plan, String> {
    let model = verified.model;
    let graphs = model.graphs().ok_or("a model with no graphs")?;
    if entry_graph >= graphs.len() {
        return Err(format!("entry graph {entry_graph} of {}", graphs.len()));
    }
    let graph = graphs.get(entry_graph);
    let nodes = graph.nodes().ok_or("a graph with no nodes")?;
    let tensors = model.tensors().ok_or("a model with no tensors")?;

    let mut builder = Builder::new(weights);
    // Tensor index → recorded Id.
    let mut ids: HashMap<i32, Id> = HashMap::new();
    // Every-tensor rule, pre-pass (mirrors `Builder::record`'s gate): each
    // buffered weight tensor must be named by a node, and each host tensor
    // declared. The `*_raw` door bypasses the `read` flags `record` gates on
    // (it takes resolved offsets, never file indices), so the loader proves
    // coverage here, from the node refs, and then marks the builder's flags
    // directly before finishing. A skipped weight is a layer the lowering
    // dropped — the file loads, the pass runs, and one layer reads whatever
    // its neighbour's payload happens to be.
    //
    // Coverage is file-wide, not per-graph: in a multi-graph file a weight
    // the lowered graph never names may belong to another graph, and that
    // is that graph's business. What this lowering must prove is that every
    // tensor IT names resolves — the resolvability check below — while the
    // union gate (every tensor named somewhere) already ran at emit time
    // and re-runs here cheaply.
    //
    // Marking happens up front (not after lowering) because `finish`
    // consumes the builder: the check must be armed before the nodes replay.
    {
        let read = file_refs(model)?;
        // Resolvability: every named weight must resolve through the bridge
        // in its dtype class (word vs element addressing). A mismatch fails
        // here rather than silently binding the wrong payload.
        for (index, mark) in read.iter().enumerate() {
            if !mark {
                continue;
            }
            let tensor = tensors.get(index);
            if tensor.buffer() == -1 {
                continue;
            }
            let dims: Vec<u32> = tensor
                .dims()
                .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect())
                .unwrap_or_default();
            let ok = match tensor.dtype() {
                fb::DType::I8 | fb::DType::I4 => weights.shaped_words(index, &dims).is_ok(),
                _ => weights.shaped(index, &dims).is_ok(),
            };
            if !ok {
                return Err(format!("tensor {index}: the bridge cannot resolve its stored shape"));
            }
        }
        builder.mark_read(&read);
    }
    // Graph inputs first, in declaration order = binding order.
    if let Some(inputs) = graph.inputs() {
        for i in 0..inputs.len() {
            let t = inputs.get(i);
            let shape = inferred
                .shapes
                .get(&t)
                .ok_or_else(|| format!("input tensor {t} was not inferred"))?;
            if shape.dims.len() != 3 {
                return Err(format!("input tensor {t} is not [c, h, w]"));
            }
            let id = builder.input(Shape::new(
                shape.dims[0] as u32,
                shape.dims[1] as u32,
                shape.dims[2] as u32,
            ));
            ids.insert(t, id);
        }
    }

    for n in 0..nodes.len() {
        let node = nodes.get(n);
        lower_node(&mut builder, weights, &tensors, &node, &mut ids, n)?;
    }

    let outputs: Vec<Id> = graph
        .outputs()
        .map(|v| (0..v.len()).map(|i| v.get(i)).collect::<Vec<i32>>())
        .unwrap_or_default()
        .iter()
        .map(|t| {
            ids.get(t)
                .copied()
                .ok_or_else(|| format!("output tensor {t} was never written"))
        })
        .collect::<Result<Vec<Id>, String>>()?;
    // File-wide coverage re-check before finishing (see the pre-pass): the
    // `*_raw` door never sets the builder's read flags, so the union refs
    // arm `finish`'s gate the same way up front.
    {
        builder.mark_read(&file_refs(model)?);
    }
    let mut plan = builder.finish(&outputs)?;
    if !nchw_only {
        rewrite_blocked_kinds(&mut plan, verified, entry_graph)?;
    }
    Ok(plan)
}

/// Tiled int8 kinds with a channel-blocked twin: `(NCHW kind, blocked kind)`.
///
/// Today one entry: the sampler's 1x1s. The fp16 tiled kind and the vec kinds
/// grow twins by the same mechanism when their blocked shaders land.
pub const BLOCKED_KINDS: [(crate::nets::Kind, crate::nets::Kind); 1] =
    [(crate::nets::Kind::ConvPointInt8, crate::nets::Kind::ConvPointCb4Int8)];

/// Rewrite tiled dispatches to their blocked twin where the layouts agree.
///
/// A dispatch rewrites when its input and output activations are
/// `CHANNEL_BLOCKED_4` in the file (kernels are always NCHW). The rewrite is
/// exact: same `Push` (word-indexed weight, fp16 scale, tile count), same
/// invocations, same fused addends — only the pipeline changes. Anything
/// else keeps the NCHW kernel, which is always correct and merely the old
/// speed.
fn rewrite_blocked_kinds(
    plan: &mut Plan,
    verified: &Verified<'_>,
    entry_graph: usize,
) -> Result<(), String> {
    use crate::nets::Op;
    let model = verified.model;
    let tensors = model.tensors().ok_or("a model with no tensors")?;
    let graphs = model.graphs().ok_or("a model with no graphs")?;
    let graph = graphs.get(entry_graph);
    let nodes = graph.nodes().ok_or("a graph with no nodes")?;
    let layout_of = |t: i32| -> Option<fb::Layout> {
        if t < 0 {
            return None;
        }
        Some(tensors.get(t as usize).layout())
    };
    let dims_of = |t: i32| -> Vec<i32> {
        tensors
            .get(t as usize)
            .dims()
            .map(|d| (0..d.len()).map(|i| d.get(i)).collect())
            .unwrap_or_default()
    };
    // Map each tiled int8 dispatch back to its v2 node. File order is
    // topological and emission preserves it, so the plan's tiled int8
    // dispatches correspond in order to the file's tiled MatMul nodes — but
    // ONLY the tiled ones: a MatMul node with groups, stride, padding, or a
    // single position lowers to the untiled or GEMV kernel, which has no
    // blocked twin. The routing is recomputed here from the node attrs and
    // tensor shapes, mirroring `Builder::emit`'s tiled test exactly
    // (ungrouped 1x1, stride 1, unpadded, same spatial extent, >1 position).
    // A drift between the two routings fails the count check below rather
    // than rewriting the wrong dispatch.
    let mut qualify: Vec<bool> = Vec::new();
    for n in 0..nodes.len() {
        let node = nodes.get(n);
        if node.op() != fb::Op::MatMul {
            continue;
        }
        let inputs: Vec<i32> = node.inputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect()).unwrap_or_default();
        let outputs: Vec<i32> = node.outputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect()).unwrap_or_default();
        // MatMul `[a, w, s, b] -> [y]`: activation a, kernel w, output y.
        if inputs.len() != 4 || outputs.len() != 1 {
            continue;
        }
        let attrs = NodeAttrs { node };
        let ints = |name: &str| attrs.ints(name).unwrap_or_default();
        let kernel = ints("kernel");
        let stride = ints("stride");
        let pads = ints("pads");
        let groups = attrs.int("groups").unwrap_or(1);
        let a = dims_of(inputs[0]);
        let y = dims_of(outputs[0]);
        let tiled = groups == 1
            && kernel == [1, 1]
            && stride == [1, 1]
            && pads == [0, 0, 0, 0]
            && a.len() == 3
            && y.len() == 3
            && a[1] == y[1]
            && a[2] == y[2]
            && y[1] * y[2] > 1;
        if !tiled {
            continue;
        }
        let blocked = [inputs[0], outputs[0]]
            .iter()
            .all(|t| layout_of(*t) == Some(fb::Layout::CHANNEL_BLOCKED_4));
        qualify.push(blocked);
    }
    // Rewrite the plan's tiled int8 dispatches in order, consuming one
    // qualification per dispatch.
    let mut qi = 0usize;
    for op in &mut plan.ops {
        if let Op::Dispatch { kind, .. } = op {
            let twin = BLOCKED_KINDS.iter().find(|(from, _)| *from == *kind).map(|(_, to)| *to);
            if let Some(to) = twin {
                match qualify.get(qi) {
                    Some(true) => *kind = to,
                    Some(false) => {}
                    None => {
                        return Err(format!(
                            "more tiled int8 dispatches than tiled MatMul nodes ({qi} seen)"
                        ));
                    }
                }
                qi += 1;
            }
        }
    }
    if qi != qualify.len() {
        return Err(format!(
            "tiled MatMul node count {} != tiled int8 dispatch count {qi}",
            qualify.len()
        ));
    }
    Ok(())
}

/// Resolve a cache tensor to its persistent builder Id, creating it first.
///
/// State tensors are arena storage, not values: the first node that names
/// one (a `CacheWrite`, a cached attention) allocates it via
/// [`Builder::persistent`] at the file's recorded shape, and later nodes
/// reuse the Id. Anything else — a cache named with a non-state row, or a
/// state row with a rank the arena cannot hold — is a corrupt file.
fn persistent_of(
    builder: &mut Builder<'_>,
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    ids: &mut HashMap<i32, Id>,
    t: i32,
    node_index: usize,
) -> Result<Id, String> {
    let err = |what: &str| format!("node {node_index}: {what}");
    if let Some(id) = ids.get(&t).copied() {
        return Ok(id);
    }
    if tensors.get(t as usize).state() == fb::StateKind::NONE {
        return Err(err(&format!("tensor {t} is not a state tensor")));
    }
    let dims = tensors
        .get(t as usize)
        .dims()
        .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect::<Vec<u32>>())
        .unwrap_or_default();
    if dims.len() != 3 {
        return Err(err(&format!("state tensor {t} is not [c, h, w]")));
    }
    let id = builder.persistent(Shape::new(dims[0], dims[1], dims[2]));
    ids.insert(t, id);
    Ok(id)
}

/// Lower one v2 node through the matching `*_raw` builder.
fn lower_node(
    builder: &mut Builder<'_>,
    weights: &V2Weights,
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    node: &fb::Node<'_>,
    ids: &mut HashMap<i32, Id>,
    node_index: usize,
) -> Result<(), String> {
    let err = |what: &str| format!("node {node_index} ({:?}): {what}", node.op());
    let inputs: Vec<i32> =
        node.inputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect::<Vec<i32>>()).unwrap_or_default();
    let outputs: Vec<i32> =
        node.outputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect::<Vec<i32>>()).unwrap_or_default();
    if outputs.len() != 1 {
        return Err(err("v2 nodes write exactly one tensor"));
    }
    let id_of = |t: i32| -> Result<Id, String> {
        ids.get(&t).copied().ok_or_else(|| err(&format!("tensor {t} has no Id yet")))
    };
    // Weight tensor rows carry their file dims; resolve offsets through the
    // bridge (which checks the payload class, not the dims — inference
    // already validated shapes).
    let weight_elem = |t: i32| -> Result<u32, String> {
        let tensor = tensors.get(t as usize);
        let dims = tensor
            .dims()
            .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect::<Vec<u32>>())
            .unwrap_or_default();
        weights.shaped(t as usize, &dims).map_err(|e| err(&e))
    };
    let weight_word = |t: i32, dims: &[u32]| -> Result<u32, String> {
        weights.shaped_words(t as usize, dims).map_err(|e| err(&e))
    };
    let attrs = NodeAttrs { node: *node };
    let out = match node.op() {
        fb::Op::Conv => {
            if inputs.len() != 3 {
                return Err(err("Conv wants [x, w, b]"));
            }
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let dilation = attrs.ints("dilation")?;
            let pads = attrs.ints("pads")?;
            if pads.len() != 4 {
                return Err(err("Conv pads are not [t, l, b, r]"));
            }
            let groups = attrs.int("groups")? as u32;
            let pad_edge = attrs.opt_bool("pad_edge");
            let act_code = attrs.int("activation")?;
            let act = act_from_code(act_code).map_err(|e| err(&e))?;
            // PRelu's slope is a tensor ref beside the code; every other
            // activation leaves the slope slot zero and unread.
            let act_weight = match act {
                Act::PRelu(_) => {
                    let slope = attrs.opt_int("slope").ok_or_else(|| err("PRelu with no slope"))?;
                    weight_elem(slope)?
                }
                _ => 0,
            };
            let m = tensors
                .get(inputs[1] as usize)
                .dims()
                .map(|d| d.get(0) as u32)
                .unwrap_or(0);
            let w = weight_elem(inputs[1])?;
            let b = weight_elem(inputs[2])?;
            let res = attrs.opt_int("res").map(id_of).transpose()?;
            let shift = attrs.opt_int("shift").map(id_of).transpose()?;
            let id = builder.conv_raw_fused(
                id_of(inputs[0])?,
                w,
                b,
                act_weight,
                m,
                act,
                (kernel[0] as u32, kernel[1] as u32),
                (stride[0] as u32, stride[1] as u32),
                (dilation[0] as u32, dilation[1] as u32),
                (pads[0] as u32, pads[1] as u32, pads[2] as u32, pads[3] as u32),
                groups,
                pad_edge,
                res,
                shift,
            );
            id
        }
        fb::Op::ConvTranspose => {
            if inputs.len() != 3 {
                return Err(err("ConvTranspose wants [x, w, b]"));
            }
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let pads = attrs.ints("pads")?;
            if pads.len() != 4 {
                return Err(err("ConvTranspose pads are not [t, l, b, r]"));
            }
            let act_code = attrs.int("activation")?;
            let act = act_from_code(act_code).map_err(|e| err(&e))?;
            let act_weight = match act {
                Act::PRelu(_) => {
                    let slope = attrs.opt_int("slope").ok_or_else(|| err("PRelu with no slope"))?;
                    weight_elem(slope)?
                }
                _ => 0,
            };
            let m = tensors
                .get(inputs[1] as usize)
                .dims()
                .map(|d| if d.len() > 1 { d.get(1) as u32 } else { 0 })
                .unwrap_or(0);
            let w = weight_elem(inputs[1])?;
            let b = weight_elem(inputs[2])?;
            builder.conv_transpose_raw(
                id_of(inputs[0])?,
                w,
                b,
                act_weight,
                m,
                act,
                (kernel[0] as u32, kernel[1] as u32),
                (stride[0] as u32, stride[1] as u32),
                (pads[0] as u32, pads[1] as u32, pads[2] as u32, pads[3] as u32),
            )
        }
        fb::Op::MatMul => {
            // Quantised convolution: `[a, w, s, b]`. 1x1 projections take
            // the tiled/GEMV kernels; strided, padded, or grouped kernels
            // take the untiled one — `conv_int8_raw_fused` routes by shape
            // exactly as the hand-written pass does, so the loader passes
            // the file geometry through rather than re-deciding it.
            if inputs.len() != 4 {
                return Err(err("MatMul wants [a, w, s, b]"));
            }
            let act = act_from_code(attrs.int("activation")?).map_err(|e| err(&e))?;
            let quant = match attrs.int("quant")? {
                1 => crate::nets::Quant::I8,
                2 => crate::nets::Quant::I4,
                q => return Err(err(&format!("MatMul quant {q} is not 1 (int8) or 2 (int4)"))),
            };
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let dilation = attrs.ints("dilation")?;
            let pads = attrs.ints("pads")?;
            if kernel.len() != 2 || stride.len() != 2 || dilation.len() != 2 || pads.len() != 4 {
                return Err(err("MatMul geometry attrs are not kernel/stride/dilation/pads"));
            }
            let groups = attrs.int("groups").unwrap_or(1) as u32;
            let m = tensors
                .get(inputs[1] as usize)
                .dims()
                .map(|d| d.get(0) as u32)
                .unwrap_or(0);
            let in_c = tensors
                .get(inputs[1] as usize)
                .dims()
                .map(|d| if d.len() > 1 { d.get(1) as u32 } else { 0 })
                .unwrap_or(0);
            let kh = kernel[0] as u32;
            let kw = kernel[1] as u32;
            let w = weight_word(inputs[1], &[m, in_c, kh, kw])?;
            let s = weight_elem(inputs[2])?;
            let b = weight_elem(inputs[3])?;
            let res = attrs.opt_int("res").map(id_of).transpose()?;
            let shift = attrs.opt_int("shift").map(id_of).transpose()?;
            builder.conv_int8_raw_fused(
                id_of(inputs[0])?,
                w,
                s,
                b,
                m,
                act,
                (kh, kw),
                (stride[0] as u32, stride[1] as u32),
                (dilation[0] as u32, dilation[1] as u32),
                (pads[0] as u32, pads[1] as u32, pads[2] as u32, pads[3] as u32),
                groups,
                quant,
                res,
                shift,
            )
        }
        fb::Op::Add => {
            if inputs.len() != 2 {
                return Err(err("Add wants [a, b]"));
            }
            builder.add(id_of(inputs[0])?, id_of(inputs[1])?)
        }
        fb::Op::Mul => {
            if inputs.len() != 2 {
                return Err(err("Mul wants [a, b]"));
            }
            builder.mul(id_of(inputs[0])?, id_of(inputs[1])?)
        }
        fb::Op::AddBroadcast => {
            if inputs.len() != 2 {
                return Err(err("AddBroadcast wants [a, b]"));
            }
            builder.add_channel(id_of(inputs[0])?, id_of(inputs[1])?)
        }
        fb::Op::MulBroadcast => {
            if inputs.len() != 2 {
                return Err(err("MulBroadcast wants [a, b]"));
            }
            builder.mul_channel(id_of(inputs[0])?, id_of(inputs[1])?)
        }
        fb::Op::MaxPool => {
            if inputs.len() != 1 {
                return Err(err("MaxPool wants [x]"));
            }
            // The only max pooling the nets record is 2x2 stride 2 (the
            // builder refuses odd extents); replay through it so the same
            // refusal guards the file.
            builder.max_pool_2x2(id_of(inputs[0])?)
        }
        fb::Op::AvgPool => {
            if inputs.len() != 1 {
                return Err(err("AvgPool wants [x]"));
            }
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            builder.avg_pool(
                id_of(inputs[0])?,
                (kernel[0] as u32, kernel[1] as u32),
                (stride[0] as u32, stride[1] as u32),
            )
        }
        fb::Op::Resize => {
            if inputs.len() != 1 {
                return Err(err("Resize wants [x]"));
            }
            let mode = attrs.int("mode")?;
            let dims = attrs.ints("dims")?;
            if dims.len() != 3 {
                return Err(err("Resize dims are not [c, h, w]"));
            }
            // Bilinear is the `half_pixel` shader; nearest is asymmetric.
            // Mode 0/1 mirrors the emitter; anything else is a corrupt file.
            match mode {
                0 => builder.resize_to(id_of(inputs[0])?, dims[1] as u32, dims[2] as u32),
                1 => builder.resize_nearest_to(id_of(inputs[0])?, dims[1] as u32, dims[2] as u32),
                _ => return Err(err(&format!("Resize mode {mode} is not 0 or 1"))),
            }
        }
        fb::Op::GlobalAvgPool => {
            if inputs.len() != 1 {
                return Err(err("GlobalAvgPool wants [x]"));
            }
            builder.global_avg_pool(id_of(inputs[0])?)
        }
        fb::Op::Concat => {
            if inputs.is_empty() {
                return Err(err("Concat wants parts"));
            }
            // A single part is a reshape (`Builder::reshaped`): replay the
            // relabel against the stored output shape rather than deriving a
            // channel sum from the part.
            if inputs.len() == 1 {
                let dims = tensors
                    .get(outputs[0] as usize)
                    .dims()
                    .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect::<Vec<u32>>())
                    .unwrap_or_default();
                if dims.len() != 3 {
                    return Err(err("reshape Concat output is not [c, h, w]"));
                }
                builder.reshaped(
                    id_of(inputs[0])?,
                    Shape::new(dims[0], dims[1], dims[2]),
                )
            } else {
                let axis = attrs.int("axis")?;
                let parts: Vec<Id> = inputs
                    .iter()
                    .map(|t| id_of(*t))
                    .collect::<Result<Vec<Id>, String>>()?;
                match axis {
                    0 => builder.concat(&parts),
                    2 => builder.concat_positions(&parts),
                    _ => return Err(err(&format!("Concat axis {axis} is not 0 or 2"))),
                }
            }
        }
        fb::Op::Affine => {
            if inputs.len() != 1 {
                return Err(err("Affine wants [x]"));
            }
            let scale = attrs.float("scale")?;
            let shift = attrs.float("shift")?;
            builder.affine(id_of(inputs[0])?, scale, shift)
        }
        fb::Op::Constant => {
            if inputs.len() != 1 {
                return Err(err("Constant wants [weight]"));
            }
            let w = weight_elem(inputs[0])?;
            let dims = tensors
                .get(outputs[0] as usize)
                .dims()
                .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect::<Vec<u32>>())
                .unwrap_or_default();
            if dims.len() != 3 {
                return Err(err("Constant output is not [c, h, w]"));
            }
            builder.constant_raw(w, Shape::new(dims[0], dims[1], dims[2]))
        }
        fb::Op::RmsNorm => {
            if inputs.len() != 2 {
                return Err(err("RmsNorm wants [x, gamma]"));
            }
            let epsilon = attrs.float("epsilon")?;
            let groups = attrs.int("groups")? as u32;
            let g = weight_elem(inputs[1])?;
            builder.rms_norm_raw(id_of(inputs[0])?, g, epsilon, groups)
        }
        fb::Op::LayerNorm => {
            if inputs.len() != 3 {
                return Err(err("LayerNorm wants [x, gamma, beta]"));
            }
            let epsilon = attrs.float("epsilon")?;
            let g = weight_elem(inputs[1])?;
            let be = weight_elem(inputs[2])?;
            builder.layer_norm_raw(id_of(inputs[0])?, g, be, epsilon)
        }
        fb::Op::Attention => {
            // Phases 0/1 scores/apply share builders with the relative
            // phases 2/3; split by `phase`.
            let phase = attrs.opt_int("phase").unwrap_or(0);
            let heads = attrs.int("heads")? as u32;
            if phase == 0 {
                if inputs.len() != 2 {
                    return Err(err("Attention scores wants [q, k]"));
                }
                builder.attn_scores(id_of(inputs[0])?, id_of(inputs[1])?, heads)
            } else if phase == 1 {
                if inputs.len() != 2 {
                    return Err(err("Attention apply wants [probs, v]"));
                }
                builder.attn_apply(id_of(inputs[0])?, id_of(inputs[1])?, heads)
            } else if phase == 2 {
                if inputs.len() != 3 {
                    return Err(err("relative Attention scores wants [q, k, table]"));
                }
                let scale = attrs.float("scale")?;
                let offsets = attrs.int("rel_offsets")? as u32;
                let t = weight_elem(inputs[2])?;
                builder.attn_scores_relative_raw(
                    id_of(inputs[0])?,
                    id_of(inputs[1])?,
                    heads,
                    scale,
                    t,
                    offsets,
                )
            } else if phase == 3 {
                if inputs.len() != 3 {
                    return Err(err("relative Attention apply wants [probs, v, table]"));
                }
                let offsets = attrs.int("rel_offsets")? as u32;
                let t = weight_elem(inputs[2])?;
                builder.attn_apply_relative_raw(
                    id_of(inputs[0])?,
                    id_of(inputs[1])?,
                    heads,
                    t,
                    offsets,
                )
            } else if phase == 4 {
                if inputs.len() != 2 {
                    return Err(err("cached Attention scores wants [q, cache]"));
                }
                let scale = attrs.float("scale")?;
                let kv_heads = attrs.opt_int("kv_heads").unwrap_or(heads as i32) as u32;
                let dynamic = attrs.opt_bool("dynamic");
                let sliding = attrs.opt_bool("sliding");
                // `id_of` before `persistent_of`: the closure borrows `ids`
                // immutably, and the borrow must be dead before the mutable
                // cache allocation on this path.
                let q = id_of(inputs[0])?;
                let cache = persistent_of(builder, tensors, ids, inputs[1], node_index)?;
                builder.attn_scores_cached_raw(q, cache, heads, kv_heads, scale, dynamic, sliding)
            } else if phase == 5 {
                if inputs.len() != 2 {
                    return Err(err("cached Attention apply wants [probs, cache]"));
                }
                let kv_heads = attrs.opt_int("kv_heads").unwrap_or(heads as i32) as u32;
                let dynamic = attrs.opt_bool("dynamic");
                let sliding = attrs.opt_bool("sliding");
                let probs = id_of(inputs[0])?;
                let cache = persistent_of(builder, tensors, ids, inputs[1], node_index)?;
                builder.attn_apply_cached_raw(probs, cache, heads, kv_heads, dynamic, sliding)
            } else {
                return Err(err(&format!("Attention phase {phase} is not 0..=5")));
            }
        }
        fb::Op::CacheWrite => {
            if inputs.len() != 2 || outputs.len() != 1 {
                return Err(err("CacheWrite wants [row, cache] -> [cache]"));
            }
            if outputs[0] != inputs[1] {
                return Err(err("CacheWrite output is not its cache input"));
            }
            let row = id_of(inputs[0])?;
            let cache =
                persistent_of(builder, tensors, ids, inputs[1], node_index)?;
            builder.cache_write(row, cache);
            // The cache Id is the output: record it so downstream nodes and
            // the graph-output mapping resolve. (The builder holds no new
            // tensor — the cache persists.)
            ids.insert(outputs[0], cache);
            return Ok(());
        }
        fb::Op::Embedding => {
            if inputs.len() != 2 {
                return Err(err("Embedding wants [ids, table]"));
            }
            let rows = attrs.int("rows")? as u32;
            // Rank 2 `[rows, channels]`, possibly padded to rank 3 by the
            // emitter's uniform rank-3 weight rows.
            let table_dims = tensors
                .get(inputs[1] as usize)
                .dims()
                .map(|d| (0..d.len()).map(|i| d.get(i) as u32).collect::<Vec<u32>>())
                .unwrap_or_default();
            if table_dims.len() < 2 || table_dims[0] != rows {
                return Err(err("embedding table is not [rows, channels]"));
            }
            let t = weight_elem(inputs[1])?;
            builder.embed_raw(id_of(inputs[0])?, t, rows, table_dims[1])
        }
        fb::Op::Softmax => {
            if inputs.len() != 1 {
                return Err(err("Softmax wants [x]"));
            }
            // Full, causal, and prefix share one file op; the mode, window,
            // and sliding attrs pick the pipeline. A sliding full softmax is
            // still full (sliding only narrows causal and prefix bounds).
            let mode = attrs.int("mode")?;
            let window = attrs.opt_int("window").unwrap_or(0);
            let sliding = attrs.opt_bool("sliding");
            match (mode, window, sliding) {
                (0, _, false) => builder.softmax(id_of(inputs[0])?),
                (1, 0, false) => builder.softmax_causal(id_of(inputs[0])?),
                (1, _, _) => builder.softmax_causal_windowed(id_of(inputs[0])?, window as u32),
                (2, _, _) => builder.softmax_prefix(id_of(inputs[0])?, sliding),
                _ => return Err(err(&format!("Softmax mode {mode} is not 0, 1, or 2"))),
            }
        }
        fb::Op::RotaryEmbedding => {
            if inputs.len() != 2 {
                return Err(err("RotaryEmbedding wants [x, angles]"));
            }
            let heads = attrs.int("heads")? as u32;
            builder.rotary(id_of(inputs[0])?, id_of(inputs[1])?, heads)
        }
        fb::Op::View => {
            // Slice-channels: `[x]` with `offset` + `dims`. The loader
            // replays the slice against the recorded length.
            if inputs.len() != 1 {
                return Err(err("View wants [x]"));
            }
            let offset = attrs.int("offset")? as u32;
            let dims = attrs.ints("dims")?;
            if dims.len() != 3 {
                return Err(err("View dims are not [c, h, w]"));
            }
            // `slice_channels(input, start, count)`: count is the view's
            // channel extent; the offset names its start.
            builder.slice_channels(id_of(inputs[0])?, offset, dims[0] as u32)
        }
        _ => return Err(err("op is not expressible in a v2 graph yet")),
    };
    ids.insert(outputs[0], out);
    Ok(())
}
