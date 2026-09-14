//! Shape and layout inference over a verified MAML v2 graph (spec 7.2 + 9.2).
//!
//! Re-derives every computed tensor's static shape and layout from the graph
//! inputs and node attributes, and requires the result to equal the stored
//! row. A mismatch is a load error, not a re-record trigger. Along the way
//! this checks the semantic properties structural verification cannot:
//! single-writer SSA, acyclicity (topological order), every weight tensor
//! read-or-host, graph outputs traceable to inputs, and the `graph_digest`
//! recompute.

use std::collections::{HashMap, HashSet};

use sha2::{Digest, Sha256};

use crate::maml2::fb;
use crate::maml2::verify::Verified;

/// An inferred tensor: static dims plus the layout the producer writes.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Inferred {
    /// Static shape, rank 1..=4, all values > 0.
    pub dims: Vec<i32>,
    /// The layout the producer writes.
    pub layout: fb::Layout,
}

/// The inference result for one graph.
#[derive(Debug)]
pub struct InferredGraph {
    /// Graph index in `Model.graphs`.
    pub graph: usize,
    /// Inferred shape per tensor index (weights: stored dims echoed).
    pub shapes: HashMap<i32, Inferred>,
    /// Recomputed digest over the canonical op/edge/attr sequence.
    pub graph_digest: [u8; 32],
}

/// Attribute accessors over the generated union readers.
struct Attrs<'a> {
    attrs: flatbuffers::Vector<'a, flatbuffers::ForwardsUOffset<fb::Attribute<'a>>>,
}

impl<'a> Attrs<'a> {
    fn get(&self, name: &str) -> Option<fb::Attribute<'a>> {
        (0..self.attrs.len()).map(|i| self.attrs.get(i)).find(|a| a.name() == Some(name))
    }

    fn int(&self, name: &str) -> Result<i32, String> {
        let attr = self.get(name).ok_or_else(|| format!("missing attribute {name}"))?;
        attr.value_as_attr_int().map(|v| v.value()).ok_or_else(|| format!("attribute {name} is not an int"))
    }

    fn ints(&self, name: &str) -> Result<Vec<i32>, String> {
        let attr = self.get(name).ok_or_else(|| format!("missing attribute {name}"))?;
        attr.value_as_attr_ints()
            .and_then(|v| v.value().map(|vec| (0..vec.len()).map(|i| vec.get(i)).collect()))
            .ok_or_else(|| format!("attribute {name} is not an int vector"))
    }

    fn float(&self, name: &str) -> Result<f32, String> {
        let attr = self.get(name).ok_or_else(|| format!("missing attribute {name}"))?;
        attr.value_as_attr_float().map(|v| v.value()).ok_or_else(|| format!("attribute {name} is not a float"))
    }

    fn bool(&self, name: &str) -> Result<bool, String> {
        let attr = self.get(name).ok_or_else(|| format!("missing attribute {name}"))?;
        attr.value_as_attr_bool().map(|v| v.value()).ok_or_else(|| format!("attribute {name} is not a bool"))
    }

    fn opt_int(&self, name: &str) -> Result<Option<i32>, String> {
        match self.get(name) {
            None => Ok(None),
            Some(attr) => attr
                .value_as_attr_int()
                .map(|v| Some(v.value()))
                .ok_or_else(|| format!("attribute {name} is not an int")),
        }
    }

    /// Float accessor, for ops beyond the Phase 1 sampler subset (LayerNorm
    /// epsilon, Attention scale). Unused until coverage grows past the
    /// sampler's integer-attribute ops.
    #[allow(dead_code)]
    fn float_attr(&self, name: &str) -> Result<f32, String> {
        self.float(name)
    }

    /// Bool accessor, for ops beyond the Phase 1 sampler subset (Conv
    /// pad_edge). Unused until coverage grows past the sampler's
    /// integer-attribute ops.
    #[allow(dead_code)]
    fn bool_attr(&self, name: &str) -> Result<bool, String> {
        self.bool(name)
    }
}

/// Run shape/layout inference over every graph in the verified model.
///
/// Returns one [`InferredGraph`] per graph, in model order. Also enforces
/// the model-wide digest gate (spec section 9.2): the per-graph digests
/// folded in model order must equal the stored `graph_digest`, or the file
/// is rejected — a topology no emitter vouched for never reaches lowering.
pub fn infer(verified: &Verified<'_>) -> Result<Vec<InferredGraph>, String> {
    infer_with(verified, None)
}

/// Inference with live input shapes for one entry graph (a rebuild).
///
/// `overrides` names the entry graph and its live input dims, in graph-input
/// order. Marked axes (dim_params) accept any live length `<= max`;
/// unmarked axes — and every weight, host, and state row — must equal the
/// stored dims exactly, so a bridge passing frames where chars belong fails
/// loudly. Computed tensors skip the stored-equality check on this path
/// (they derive from live dims); everything else — SSA, topology, digest —
/// is checked identically, and the digest is shape-independent, so a
/// topology the emitter never vouched for still fails the gate.
///
/// Fixed-shape nets never call this; they use [`infer`].
pub fn infer_shaped(
    verified: &Verified<'_>,
    entry: usize,
    live: &[[i32; 3]],
) -> Result<Vec<InferredGraph>, String> {
    infer_with(verified, Some((entry, live)))
}

fn infer_with(
    verified: &Verified<'_>,
    overrides: Option<(usize, &[[i32; 3]])>,
) -> Result<Vec<InferredGraph>, String> {
    let model = verified.model;
    let tensors = model.tensors().ok_or("a model with no tensors")?;
    let graphs = model.graphs().ok_or("a model with no graphs")?;
    let inferred: Vec<InferredGraph> = (0..graphs.len())
        .map(|g| {
            let live = match overrides {
                Some((entry, dims)) if entry == g => Some(dims),
                _ => None,
            };
            infer_graph(&tensors, &graphs.get(g), g, live)
        })
        .collect::<Result<_, _>>()?;
    let mut hasher = Sha256::new();
    for graph in &inferred {
        hasher.update(graph.graph_digest);
    }
    let recomputed: [u8; 32] = hasher.finalize().into();
    let stored: Vec<u8> =
        model.graph_digest().map(|d| (0..d.len()).map(|i| d.get(i)).collect()).unwrap_or_default();
    if stored.as_slice() != recomputed {
        return Err("the model digest does not match the recomputed graph digests".into());
    }
    Ok(inferred)
}

/// Stored dims of one tensor row.
fn stored_dims(tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>, index: i32) -> Result<Vec<i32>, String> {
    let tensor = tensors.get(index as usize);
    tensor
        .dims()
        .map(|dims| (0..dims.len()).map(|i| dims.get(i)).collect())
        .ok_or_else(|| format!("tensor {index} has no dims"))
}

/// Stored layout of one tensor row.
fn stored_layout(
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    index: i32,
) -> fb::Layout {
    tensors.get(index as usize).layout()
}

/// Dim params of one tensor row as `(axis, symbol, max)`.
fn dim_params_of(
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    index: i32,
) -> Vec<(u32, String, i32)> {
    tensors
        .get(index as usize)
        .dim_params()
        .map(|params| {
            (0..params.len())
                .map(|i| {
                    let p = params.get(i);
                    (p.axis(), p.symbol().unwrap_or("?").to_string(), p.max())
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Infer one graph: propagate shapes, check SSA + acyclicity + digest.
///
/// `live` overrides the graph inputs' dims (a rebuild): `None` seeds from
/// the stored rows and checks every computed row against them; `Some` seeds
/// from the live dims (validated against dim_params below) and skips the
/// stored check for computed tensors, whose shapes legitimately differ.
fn infer_graph(
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    graph: &fb::Graph<'_>,
    graph_index: usize,
    live: Option<&[[i32; 3]]>,
) -> Result<InferredGraph, String> {
    let name = graph.name().unwrap_or("?");
    let nodes = graph.nodes().ok_or_else(|| format!("graph {graph_index} ({name}) has no nodes"))?;
    let mut shapes: HashMap<i32, Inferred> = HashMap::new();
    let mut writers: HashMap<i32, usize> = HashMap::new();
    // Graph inputs are written by the caller: seed from stored rows, or from
    // the live dims on a rebuild. Live dims are checked axis by axis: marked
    // (dim_param) axes accept `<= max`, unmarked axes must equal stored.
    if let Some(inputs) = graph.inputs() {
        if let Some(live) = live {
            if live.len() != inputs.len() {
                return Err(format!(
                    "graph {graph_index} ({name}): {} live inputs for {} graph inputs",
                    live.len(),
                    inputs.len()
                ));
            }
            for i in 0..inputs.len() {
                let t = inputs.get(i);
                let stored = stored_dims(tensors, t)?;
                let dims = [live[i][0], live[i][1], live[i][2]];
                let params = dim_params_of(tensors, t);
                for axis in 0..3 {
                    let marked = params.iter().find(|(a, _, _)| *a as usize == axis);
                    match marked {
                        Some((_, _, max)) => {
                            if dims[axis] <= 0 || dims[axis] > *max {
                                return Err(format!(
                                    "graph {graph_index} ({name}): live dim {axis} of input {t} is {}, outside (0, {max}]",
                                    dims[axis], max = max
                                ));
                            }
                        }
                        None => {
                            if stored.get(axis) != Some(&dims[axis]) {
                                return Err(format!(
                                    "graph {graph_index} ({name}): live dim {axis} of input {t} is {}, stored {} (axis not marked variable)",
                                    dims[axis],
                                    stored.get(axis).copied().unwrap_or(-1)
                                ));
                            }
                        }
                    }
                }
                shapes.insert(
                    t,
                    Inferred { dims: dims.to_vec(), layout: stored_layout(tensors, t) },
                );
            }
        } else {
            for i in 0..inputs.len() {
                let t = inputs.get(i);
                shapes.insert(t, Inferred { dims: stored_dims(tensors, t)?, layout: stored_layout(tensors, t) });
            }
        }
    } else if live.is_some() {
        return Err(format!("graph {graph_index} ({name}) has no inputs to override"));
    }
    // Weight tensors (buffer >= 0) and host tensors (no buffer, PLACEMENT host)
    // are file facts: seed from stored rows so node inference can read them.
    // State tensors (KV caches, PINNED) seed the same way: capacity, not
    // data — the loader allocates per session and the plan addresses the
    // recorded maxima.
    for t in 0..tensors.len() as i32 {
        if shapes.contains_key(&t) {
            continue;
        }
        let tensor = tensors.get(t as usize);
        if tensor.buffer() != -1
            || tensor.placement() == fb::Placement::HOST
            || tensor.state() != fb::StateKind::NONE
        {
            shapes.insert(t, Inferred { dims: stored_dims(tensors, t)?, layout: tensor.layout() });
        }
    }

    let mut hasher = Sha256::new();
    // The graph index prefixes the digest (spec 9.2): the emitter hashes
    // each graph's sequence under its index, so a graph reordered past the
    // gate hashes differently. Must match `emit::digest_nodes`' caller.
    hasher.update((graph_index as u32).to_le_bytes());
    // Defined tensors: inputs + file tensors. Anything else must be written
    // by a topologically earlier node (acyclicity by construction order).
    let mut defined: HashSet<i32> = shapes.keys().copied().collect();

    for n in 0..nodes.len() {
        let node = nodes.get(n);
        let inputs: Vec<i32> = node.inputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect()).unwrap_or_default();
        let outputs: Vec<i32> = node.outputs().map(|v| (0..v.len()).map(|i| v.get(i)).collect()).unwrap_or_default();
        // Every input must already be defined: the file order IS topological.
        for input in &inputs {
            if !defined.contains(input) {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} ({:?}) reads tensor {input} with no writer above it",
                    node.op()
                ));
            }
        }
        // CacheWrite appends to a state tensor rather than defining one: its
        // output must be an already-defined state tensor (one writer per
        // graph, persisting across submits). Digest and continue — no shape
        // is derived, and the single-writer rule below does not apply.
        if node.op() == fb::Op::CacheWrite {
            if inputs.len() != 2 || outputs.len() != 1 {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} (CacheWrite) wants [row, cache] -> [cache]"
                ));
            }
            if outputs[0] != inputs[1] {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} (CacheWrite) writes tensor {} but reads cache {}",
                    outputs[0], inputs[1]
                ));
            }
            let attrs = node.attrs().map(|v| Attrs { attrs: v });
            if shapes.get(&outputs[0]).is_none() {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} (CacheWrite) targets undefined tensor {}",
                    outputs[0]
                ));
            }
            // The target must be a state tensor: appending to a computed
            // tensor would fork SSA with no writer to blame.
            if tensors.get(outputs[0] as usize).state() == fb::StateKind::NONE {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} (CacheWrite) targets non-state tensor {}",
                    outputs[0]
                ));
            }
            digest_node(&mut hasher, &node, attrs.as_ref())?;
            continue;
        }
        // Single-writer SSA: no output may already be defined.
        for output in &outputs {
            if defined.contains(output) {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} ({:?}) rewrites tensor {output} (writers: {:?})",
                    node.op(),
                    writers.get(output)
                ));
            }
        }
        let attrs = node.attrs().map(|v| Attrs { attrs: v });
        let inferred = infer_node(node.op(), &inputs, attrs.as_ref(), &shapes, n, name, graph_index)?;
        // Every inferred output must equal the stored row (spec 7.2) —
        // except on a live rebuild, where computed shapes legitimately
        // differ from the recorded maxima. Weights, host, and state rows
        // still check: their dims never vary. Layouts always check.
        let live_path = live.is_some();
        for (output, want) in outputs.iter().zip(inferred.iter()) {
            let is_file_row = {
                let tensor = tensors.get(*output as usize);
                tensor.buffer() != -1
                    || tensor.placement() == fb::Placement::HOST
                    || tensor.state() != fb::StateKind::NONE
            };
            if !live_path || is_file_row {
                let stored = stored_dims(tensors, *output)?;
                if stored != want.dims {
                    return Err(format!(
                        "graph {graph_index} ({name}): node {n} ({:?}) infers tensor {output} as {:?}, stored {:?}",
                        node.op(),
                        want.dims,
                        stored
                    ));
                }
            }
            let stored_layout = stored_layout(tensors, *output);
            if stored_layout != want.layout {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} ({:?}) infers tensor {output} layout {:?}, stored {:?}",
                    node.op(),
                    want.layout,
                    stored_layout
                ));
            }
            shapes.insert(*output, want.clone());
            writers.insert(*output, n);
            defined.insert(*output);
        }
        // Digest over the canonical op/edge/attr sequence (spec 9.2): the
        // same order the emitter hashes (op byte, inputs, outputs, per-attr
        // name + value bytes).
        digest_node(&mut hasher, &node, attrs.as_ref())?;
    }

    // Every graph output must be defined (traceable to inputs).
    if let Some(outputs) = graph.outputs() {
        for o in 0..outputs.len() {
            let t = outputs.get(o);
            if !defined.contains(&t) {
                return Err(format!("graph {graph_index} ({name}): output tensor {t} is never written"));
            }
        }
    }

    let graph_digest: [u8; 32] = hasher.finalize().into();
    Ok(InferredGraph { graph: graph_index, shapes, graph_digest })
}

/// Infer a node's output shapes from its inputs and attributes.
fn infer_node(
    op: fb::Op,
    inputs: &[i32],
    attrs: Option<&Attrs<'_>>,
    shapes: &HashMap<i32, Inferred>,
    node: usize,
    graph: &str,
    graph_index: usize,
) -> Result<Vec<Inferred>, String> {
    let err = |what: &str| format!("graph {graph_index} ({graph}): node {node} ({op:?}): {what}");
    let shape_of = |t: i32| -> Result<&Inferred, String> {
        shapes.get(&t).ok_or_else(|| err(&format!("tensor {t} has no shape")))
    };
    let attrs = attrs.ok_or_else(|| err("no attributes"))?;
    // Computed tensors default to NCHW during the migration: every kernel
    // the loader can dispatch today addresses NCHW, so the arena is NCHW
    // and the file declares it. (Spec 3.4's CHANNEL_BLOCKED_4 default
    // describes the end state, when blocked twins + transpose boundaries
    // land and files are re-emitted.) View keeps its source layout
    // (zero-copy reinterpret).
    let computed_layout = fb::Layout::NCHW;
    match op {
        fb::Op::Conv => {
            if inputs.len() != 3 {
                return Err(err("Conv wants [x, w, b]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            let w = shape_of(inputs[1])?.dims.clone();
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let dilation = attrs.ints("dilation")?;
            // Four values `[t, l, b, r]` (the node retains `(t, l)`; the
            // emitter solves `(b, r)` from the recorded shapes). Totals
            // drive the output size; `(t, l)` drive the taps.
            let pads = attrs.ints("pads")?;
            if pads.len() != 4 {
                return Err(err("Conv pads are not [t, l, b, r]"));
            }
            let groups = attrs.int("groups")?;
            if x.len() != 3 || w.len() < 2 {
                return Err(err("Conv shapes are not [c, h, w] / [m, ...]"));
            }
            let kh = if kernel.len() == 2 { kernel[0] } else { w.get(2).copied().unwrap_or(1) };
            let kw = if kernel.len() == 2 { kernel[1] } else { w.get(3).copied().unwrap_or(1) };
            let sh = stride.first().copied().unwrap_or(1);
            let sw = *stride.get(1).unwrap_or(&1);
            let dh = dilation.first().copied().unwrap_or(1);
            let dw = *dilation.get(1).unwrap_or(&1);
            let m = w[0];
            let out_h = conv_out(x[1], kh, sh, dh, pads[0] + pads[2]);
            let out_w = conv_out(x[2], kw, sw, dw, pads[1] + pads[3]);
            let _ = groups;
            Ok(vec![Inferred { dims: vec![m, out_h, out_w], layout: computed_layout }])
        }
        fb::Op::MatMul => {
            // Quantised convolution in file vocabulary: 1x1 projections and
            // heads, but also strided/padded/grouped kernels (the patch
            // projection, maia's grouped expansion). Geometry rides the same
            // attrs as `Conv`; lowering routes by shape exactly as the
            // hand-written pass does.
            if inputs.len() != 4 && inputs.len() != 3 {
                return Err(err("MatMul wants [a, w, s, b] or [a, w, b]"));
            }
            let a = shape_of(inputs[0])?.dims.clone();
            let w = shape_of(inputs[1])?.dims.clone();
            if a.len() != 3 || w.len() < 2 {
                return Err(err("MatMul shapes are not [c, h, w] / [m, ...]"));
            }
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let dilation = attrs.ints("dilation")?;
            let pads = attrs.ints("pads")?;
            if kernel.len() != 2 || stride.len() != 2 || dilation.len() != 2 || pads.len() != 4 {
                return Err(err("MatMul geometry attrs are not kernel/stride/dilation/pads"));
            }
            let out_h = conv_out(a[1], kernel[0], stride[0], dilation[0], pads[0] + pads[2]);
            let out_w = conv_out(a[2], kernel[1], stride[1], dilation[1], pads[1] + pads[3]);
            Ok(vec![Inferred { dims: vec![w[0], out_h, out_w], layout: computed_layout }])
        }
        fb::Op::ConvTranspose => {
            // Learned upsample: weights `[in_c, m, kh, kw]`, output
            // `(in - 1) * stride + kh - pads`. Mirrors `deconv_out`.
            if inputs.len() != 3 {
                return Err(err("ConvTranspose wants [x, w, b]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            let w = shape_of(inputs[1])?.dims.clone();
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            let pads = attrs.ints("pads")?;
            if x.len() != 3 || w.len() < 2 {
                return Err(err("ConvTranspose shapes are not [c, h, w] / [in_c, m, ...]"));
            }
            let kh = kernel.first().copied().unwrap_or(1);
            let kw = *kernel.get(1).unwrap_or(&1);
            let sh = stride.first().copied().unwrap_or(1);
            let sw = *stride.get(1).unwrap_or(&1);
            if pads.len() != 4 {
                return Err(err("ConvTranspose pads are not [t, l, b, r]"));
            }
            let m = w.get(1).copied().unwrap_or(0);
            // Totals, mirroring `deconv_out` (which saturates rather than
            // wrapping; the recorded shapes are sane, so no bound is hit).
            let out_h = (x[1].max(1) - 1) * sh + kh - (pads[0] + pads[2]);
            let out_w = (x[2].max(1) - 1) * sw + kw - (pads[1] + pads[3]);
            Ok(vec![Inferred { dims: vec![m, out_h, out_w], layout: computed_layout }])
        }
        fb::Op::MaxPool | fb::Op::AvgPool => {
            if inputs.len() != 1 {
                return Err(err("pooling wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            let kernel = attrs.ints("kernel")?;
            let stride = attrs.ints("stride")?;
            if x.len() != 3 {
                return Err(err("pooling input is not [c, h, w]"));
            }
            let kh = kernel.first().copied().unwrap_or(1);
            let kw = *kernel.get(1).unwrap_or(&1);
            let sh = stride.first().copied().unwrap_or(1);
            let sw = *stride.get(1).unwrap_or(&1);
            // Floored and unpadded, as the builders enforce at record.
            let out_h = conv_out(x[1], kh, sh, 1, 0);
            let out_w = conv_out(x[2], kw, sw, 1, 0);
            Ok(vec![Inferred { dims: vec![x[0], out_h, out_w], layout: computed_layout }])
        }
        fb::Op::Resize => {
            if inputs.len() != 1 {
                return Err(err("Resize wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            let dims = attrs.ints("dims")?;
            if dims.len() != 3 || dims[0] != x[0] {
                return Err(err(&format!("Resize dims {dims:?} do not extend {x:?}")));
            }
            Ok(vec![Inferred { dims, layout: computed_layout }])
        }
        fb::Op::GlobalAvgPool => {
            if inputs.len() != 1 {
                return Err(err("GlobalAvgPool wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: vec![x[0], 1, 1], layout: computed_layout }])
        }
        fb::Op::Concat => {
            if inputs.is_empty() {
                return Err(err("Concat wants parts"));
            }
            // A single part is a reshape: same elements under the `dims`
            // attr's shape. The element counts must agree; the shape itself
            // is authoritative (it is what the stored row is checked
            // against by the caller).
            if inputs.len() == 1 {
                let part = shape_of(inputs[0])?.dims.clone();
                let dims = attrs.ints("dims")?;
                if dims.len() != 3 {
                    return Err(err("reshape Concat dims are not [c, h, w]"));
                }
                let part_elems: i64 = part.iter().map(|&d| d as i64).product();
                let dst_elems: i64 = dims.iter().map(|&d| d as i64).product();
                if part_elems != dst_elems {
                    return Err(err(&format!(
                        "reshape Concat of {part:?} ({part_elems} elems) to {dims:?} ({dst_elems} elems)"
                    )));
                }
                return Ok(vec![Inferred { dims, layout: computed_layout }]);
            }
            let axis = attrs.int("axis")?;
            let mut parts = Vec::with_capacity(inputs.len());
            for input in inputs {
                parts.push(shape_of(*input)?.dims.clone());
            }
            let first = parts[0].clone();
            if axis == 0 {
                // Channel concat: spatial extents agree, channels sum.
                let mut channels = 0;
                for part in &parts {
                    if part.len() != 3 || part[1] != first[1] || part[2] != first[2] {
                        return Err(err(&format!("channel concat part {part:?} mismatches {first:?}")));
                    }
                    channels += part[0];
                }
                Ok(vec![Inferred { dims: vec![channels, first[1], first[2]], layout: computed_layout }])
            } else if axis == 2 {
                // Position concat: channels and height agree, widths sum.
                let mut width = 0;
                for part in &parts {
                    if part.len() != 3 || part[0] != first[0] || part[1] != first[1] {
                        return Err(err(&format!("position concat part {part:?} mismatches {first:?}")));
                    }
                    width += part[2];
                }
                Ok(vec![Inferred { dims: vec![first[0], first[1], width], layout: computed_layout }])
            } else {
                Err(err(&format!("Concat axis {axis} is not 0 (channels) or 2 (positions)")))
            }
        }
        fb::Op::Affine => {
            if inputs.len() != 1 {
                return Err(err("Affine wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::Activate | fb::Op::Softcap => {
            if inputs.len() != 1 {
                return Err(err("activation wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::GatedSwish => {
            // `activate(gate) * up` over a fused `[gate | up]` projection:
            // channels halve.
            if inputs.len() != 1 {
                return Err(err("GatedSwish wants [fused]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            if x.is_empty() || x[0] % 2 != 0 {
                return Err(err(&format!("gated activation over {x:?}, whose channels are not a pair")));
            }
            let mut dims = x;
            dims[0] /= 2;
            Ok(vec![Inferred { dims, layout: computed_layout }])
        }
        fb::Op::MulScalar | fb::Op::Clamp => {
            if inputs.len() != 2 {
                return Err(err("scaled/clamped op wants [x, param]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::Constant => {
            // A learned tensor copied to the arena: the output shape is the
            // stored row, checked by the caller against this echo.
            if inputs.len() != 1 {
                return Err(err("Constant wants [weight]"));
            }
            let w = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: w, layout: computed_layout }])
        }
        fb::Op::Add | fb::Op::Mul => {
            if inputs.len() != 2 {
                return Err(err("binary wants [a, b]"));
            }
            let a = shape_of(inputs[0])?.dims.clone();
            let b = shape_of(inputs[1])?.dims.clone();
            if a != b {
                return Err(err(&format!("binary shape mismatch {a:?} vs {b:?}")));
            }
            Ok(vec![Inferred { dims: a, layout: computed_layout }])
        }
        fb::Op::AddBroadcast | fb::Op::MulBroadcast => {
            if inputs.len() != 2 {
                return Err(err("broadcast wants [a, b]"));
            }
            let a = shape_of(inputs[0])?.dims.clone();
            let b = shape_of(inputs[1])?.dims.clone();
            if b.len() != 3 || b[1] != 1 || b[2] != 1 || b[0] != a[0] {
                return Err(err(&format!("broadcast {b:?} is not [c, 1, 1] over {a:?}")));
            }
            Ok(vec![Inferred { dims: a, layout: computed_layout }])
        }
        fb::Op::LayerNorm | fb::Op::RmsNorm => {
            if inputs.is_empty() {
                return Err(err("norm wants [x, ...]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::Attention => {
            // Four phases share one op: 0 scores (q, k -> map), 1 apply
            // (probs, v -> mixed), 2 relative scores, 3 relative apply. The
            // `phase` attr says which; relative phases carry the learned
            // offset table as a third input plus `rel_offsets`.
            let phase = attrs.opt_int("phase")?.unwrap_or(0);
            if phase == 0 {
                if inputs.len() != 2 {
                    return Err(err("Attention scores wants [q, k]"));
                }
                let q = shape_of(inputs[0])?.dims.clone();
                let k = shape_of(inputs[1])?.dims.clone();
                let heads = attrs.int("heads")?;
                if q.len() != 3 || k.len() != 3 {
                    return Err(err("Attention shapes are not sequences"));
                }
                Ok(vec![Inferred { dims: vec![heads, q[2], k[2]], layout: computed_layout }])
            } else if phase == 1 {
                if inputs.len() != 2 {
                    return Err(err("Attention apply wants [probs, v]"));
                }
                let probs = shape_of(inputs[0])?.dims.clone();
                let v = shape_of(inputs[1])?.dims.clone();
                let heads = attrs.int("heads")?;
                if probs.len() != 3 || v.len() != 3 {
                    return Err(err("Attention shapes are not sequences"));
                }
                // Mixed output: `[d_model, 1, queries]`, where d_model is the
                // value width scaled to query heads (GQA: kv_heads <= heads).
                let kv_heads = attrs.opt_int("kv_heads")?.unwrap_or(heads);
                let head_dim = v[0] / kv_heads.max(1);
                Ok(vec![Inferred { dims: vec![heads * head_dim, 1, probs[1]], layout: computed_layout }])
            } else if phase == 2 {
                if inputs.len() != 3 {
                    return Err(err("relative Attention scores wants [q, k, table]"));
                }
                let q = shape_of(inputs[0])?.dims.clone();
                let k = shape_of(inputs[1])?.dims.clone();
                let table = shape_of(inputs[2])?.dims.clone();
                let heads = attrs.int("heads")?;
                let offsets = attrs.int("rel_offsets")?;
                if q.len() != 3 || k.len() != 3 {
                    return Err(err("Attention shapes are not sequences"));
                }
                if q[2] != k[2] {
                    return Err(err("relative offsets need one sequence, not two lengths"));
                }
                // Table is `[offsets, head_dim]` shared across heads.
                if table.len() < 2 || table[0] != offsets {
                    return Err(err(&format!(
                        "relative table {table:?} does not hold {offsets} offsets"
                    )));
                }
                Ok(vec![Inferred { dims: vec![heads, q[2], k[2]], layout: computed_layout }])
            } else if phase == 3 {
                if inputs.len() != 3 {
                    return Err(err("relative Attention apply wants [probs, v, table]"));
                }
                let probs = shape_of(inputs[0])?.dims.clone();
                let v = shape_of(inputs[1])?.dims.clone();
                let table = shape_of(inputs[2])?.dims.clone();
                let heads = attrs.int("heads")?;
                let offsets = attrs.int("rel_offsets")?;
                if probs.len() != 3 || v.len() != 3 {
                    return Err(err("Attention shapes are not sequences"));
                }
                if table.len() < 2 || table[0] != offsets {
                    return Err(err(&format!(
                        "relative table {table:?} does not hold {offsets} offsets"
                    )));
                }
                Ok(vec![Inferred { dims: vec![v[0], 1, probs[1]], layout: computed_layout }])
            } else if phase == 4 {
                // One query against a position-major K cache: `[d, 1, 1]`
                // and `[K, 1, kv_heads * head_dim]` give `[heads, 1, K]`.
                // Live lengths arrive per submit as step uniforms; the plan
                // is built at the recorded maxima.
                if inputs.len() != 2 {
                    return Err(err("cached Attention scores wants [q, cache]"));
                }
                let q = shape_of(inputs[0])?.dims.clone();
                let cache = shape_of(inputs[1])?.dims.clone();
                let heads = attrs.int("heads")?;
                let kv_heads = attrs.opt_int("kv_heads")?.unwrap_or(heads);
                if q.len() != 3 || cache.len() != 3 {
                    return Err(err("cached Attention shapes are not [d, 1, 1] / [K, 1, w]"));
                }
                if q[1] != 1 || q[2] != 1 || cache[1] != 1 {
                    return Err(err("a decode step is one query against [K, 1, w] positions"));
                }
                Ok(vec![Inferred { dims: vec![heads, 1, cache[0]], layout: computed_layout }])
            } else if phase == 5 {
                // A `[heads, 1, K]` probability row against a position-major
                // V cache gives one `[d_model, 1, 1]` query result, where
                // d_model is the query side's width (GQA: kv_heads <= heads).
                if inputs.len() != 2 {
                    return Err(err("cached Attention apply wants [probs, cache]"));
                }
                let probs = shape_of(inputs[0])?.dims.clone();
                let cache = shape_of(inputs[1])?.dims.clone();
                let heads = attrs.int("heads")?;
                let kv_heads = attrs.opt_int("kv_heads")?.unwrap_or(heads);
                if probs.len() != 3 || cache.len() != 3 {
                    return Err(err("cached Attention shapes are not [heads, 1, K] / [K, 1, w]"));
                }
                let head_dim = cache[2] / kv_heads.max(1);
                Ok(vec![Inferred { dims: vec![heads * head_dim, 1, 1], layout: computed_layout }])
            } else if phase == 6 {
                // Banded scores: `[d, 1, T]` queries and keys give
                // `[heads, T, band]`, with the per-head relative table
                // `[heads, offsets, head_dim]`.
                if inputs.len() != 3 {
                    return Err(err("banded Attention scores wants [q, k, table]"));
                }
                let q = shape_of(inputs[0])?.dims.clone();
                let k = shape_of(inputs[1])?.dims.clone();
                let table = shape_of(inputs[2])?.dims.clone();
                let heads = attrs.int("heads")?;
                let band = attrs.int("band")?;
                let offsets = attrs.int("rel_offsets")?;
                if q.len() != 3 || k.len() != 3 {
                    return Err(err("banded Attention shapes are not sequences"));
                }
                if q[2] != k[2] {
                    return Err(err("a band is a window into one sequence, not two lengths"));
                }
                if table.len() != 3 || table[0] != heads || table[1] != offsets {
                    return Err(err(&format!(
                        "banded table {table:?} is not [heads, {offsets}, head_dim]"
                    )));
                }
                Ok(vec![Inferred { dims: vec![heads, q[2], band], layout: computed_layout }])
            } else if phase == 7 {
                // Banded apply: `[heads, T, band]` probabilities against a
                // `[d, 1, T]` sequence give `[d, 1, T]`.
                if inputs.len() != 2 {
                    return Err(err("banded Attention apply wants [probs, v]"));
                }
                let probs = shape_of(inputs[0])?.dims.clone();
                let v = shape_of(inputs[1])?.dims.clone();
                let heads = attrs.int("heads")?;
                let band = attrs.int("band")?;
                if probs.len() != 3 || v.len() != 3 {
                    return Err(err("banded Attention shapes are not [heads, T, band] / [d, 1, T]"));
                }
                if probs[0] != heads || probs[2] != band || probs[1] != v[2] {
                    return Err(err(&format!(
                        "banded probs {probs:?} do not match {heads} heads, band {band}, {T} queries",
                        T = v[2]
                    )));
                }
                Ok(vec![Inferred { dims: vec![v[0], 1, v[2]], layout: computed_layout }])
            } else {
                Err(err(&format!("Attention phase {phase} is not 0..=7")))
            }
        }
        fb::Op::Embedding => {
            if inputs.len() != 2 {
                return Err(err("Embedding wants [ids, table]"));
            }
            let ids = shape_of(inputs[0])?.dims.clone();
            let table = shape_of(inputs[1])?.dims.clone();
            let rows = attrs.int("rows")?;
            if table.len() < 2 || table[0] != rows {
                return Err(err(&format!("embedding table {table:?} has {rows} rows")));
            }
            // Ids split across two lanes past fp16-exact range (v1
            // `EMBED_LANE`): `[lanes, 1, T]` with lanes 1 or 2.
            let lanes = if rows > crate::nets::EMBED_LANE as i32 { 2 } else { 1 };
            if ids.len() != 3 || ids[0] != lanes || ids[1] != 1 {
                return Err(err(&format!("embedding ids {ids:?} are not [{lanes}, 1, T]")));
            }
            Ok(vec![Inferred { dims: vec![table[1], 1, ids[2]], layout: computed_layout }])
        }
        fb::Op::Softmax => {
            if inputs.len() != 1 {
                return Err(err("Softmax wants [x]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::RotaryEmbedding => {
            if inputs.len() != 2 {
                return Err(err("RotaryEmbedding wants [x, angles]"));
            }
            let x = shape_of(inputs[0])?.dims.clone();
            Ok(vec![Inferred { dims: x, layout: computed_layout }])
        }
        fb::Op::View => {
            if inputs.len() != 1 {
                return Err(err("View wants [x]"));
            }
            let x = inputs[0];
            let src = shape_of(x)?.clone();
            let offset = attrs.int("offset")?;
            let dims = attrs.ints("dims")?;
            if dims.len() != 3 {
                return Err(err("View dims are not [c, h, w]"));
            }
            // Bounds: the view must fit inside the source allocation. Both
            // are `[c, h, w]` element counts; the offset is in elements.
            let src_elems: i64 =
                src.dims.iter().map(|&d| d as i64).product();
            let dst_elems: i64 = dims.iter().map(|&d| d as i64).product();
            if offset < 0 || offset as i64 + dst_elems > src_elems {
                return Err(err(&format!(
                    "View [{offset}..{}] escapes its source of {src_elems} elements",
                    offset as i64 + dst_elems
                )));
            }
            Ok(vec![Inferred { dims, layout: src.layout }])
        }
        _ => Err(err("op is not expressible in a v2 graph yet")),
    }
}

/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`: ONNX's
/// convolution output size. Mirrors `nets::conv_out` (mod_part10.rs) — the
/// loader shares the formula rather than duplicating a subtly different one.
fn conv_out(input: i32, kernel: i32, stride: i32, dilation: i32, pad_total: i32) -> i32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

/// Hash one node in the canonical order (spec 9.2): op byte, inputs,
/// outputs, then per-attribute name + value bytes. Must match the emitter's
/// `emit_sampler` digest exactly.
fn digest_node(
    hasher: &mut Sha256,
    node: &fb::Node<'_>,
    attrs: Option<&Attrs<'_>>,
) -> Result<(), String> {
    use sha2::Digest as _;
    hasher.update([node.op().0 as u8]);
    if let Some(inputs) = node.inputs() {
        for i in 0..inputs.len() {
            hasher.update(inputs.get(i).to_le_bytes());
        }
    }
    if let Some(outputs) = node.outputs() {
        for o in 0..outputs.len() {
            hasher.update(outputs.get(o).to_le_bytes());
        }
    }
    if let Some(attrs) = attrs {
        for i in 0..attrs.attrs.len() {
            let attr = attrs.attrs.get(i);
            if let Some(name) = attr.name() {
                hasher.update(name.as_bytes());
                match attr.value_type() {
                    fb::AttrValue::AttrInt => {
                        let v = attr
                            .value_as_attr_int()
                            .map(|v| v.value())
                            .ok_or("AttrInt with no value")?;
                        hasher.update(v.to_le_bytes());
                    }
                    fb::AttrValue::AttrInts => {
                        let v = attr
                            .value_as_attr_ints()
                            .and_then(|v| v.value())
                            .ok_or("AttrInts with no value")?;
                        for j in 0..v.len() {
                            hasher.update(v.get(j).to_le_bytes());
                        }
                    }
                    fb::AttrValue::AttrFloat => {
                        let v = attr
                            .value_as_attr_float()
                            .map(|v| v.value())
                            .ok_or("AttrFloat with no value")?;
                        hasher.update(v.to_le_bytes());
                    }
                    fb::AttrValue::AttrBool => {
                        let v = attr
                            .value_as_attr_bool()
                            .map(|v| v.value())
                            .ok_or("AttrBool with no value")?;
                        hasher.update([u8::from(v)]);
                    }
                    _ => return Err("attribute value is not a Phase 1 kind".into()),
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::maml2::{FORMAT_VERSION, OPSET_VERSION};

    /// Build a minimal one-graph model: `Add([2,1,1], [2,1,1]) -> [2,1,1]`,
    /// carrying `digest` as its stored model digest.
    fn tiny_model(digest: [u8; 32]) -> Vec<u8> {
        let mut builder = flatbuffers::FlatBufferBuilder::new();
        let mut tensors = Vec::new();
        for name in ["a", "b", "y"] {
            let name = builder.create_string(name);
            let dims = builder.create_vector(&[2, 1, 1]);
            let quant = fb::Quantization::new(fb::QuantKind::NONE, -1, -1, 0, 0);
            tensors.push(fb::Tensor::create(
                &mut builder,
                &fb::TensorArgs {
                    name: Some(name),
                    dtype: fb::DType::F16,
                    dims: Some(dims),
                    dim_params: None,
                    layout: fb::Layout::NCHW,
                    quantization: Some(&quant),
                    buffer: -1,
                    buffer_offset: 0,
                    elem_count: 2,
                    placement: fb::Placement::DEVICE,
                    state: fb::StateKind::NONE,
                },
            ));
        }
        let tensors = builder.create_vector(&tensors);
        let inputs = builder.create_vector(&[0, 1]);
        let outputs = builder.create_vector(&[2]);
        let attrs = builder.create_vector::<flatbuffers::WIPOffset<fb::Attribute<'_>>>(&[]);
        let node = fb::Node::create(
            &mut builder,
            &fb::NodeArgs {
                op: fb::Op::Add,
                inputs: Some(inputs),
                outputs: Some(outputs),
                attrs: Some(attrs),
                payload: None,
            },
        );
        let nodes = builder.create_vector(&[node]);
        let graph_inputs = builder.create_vector(&[0, 1]);
        let graph_outputs = builder.create_vector(&[2]);
        let graph_name = builder.create_string("tiny");
        let graph = fb::Graph::create(
            &mut builder,
            &fb::GraphArgs {
                name: Some(graph_name),
                tensors: None,
                nodes: Some(nodes),
                inputs: Some(graph_inputs),
                outputs: Some(graph_outputs),
            },
        );
        let graphs = builder.create_vector(&[graph]);
        let digest = builder.create_vector(&digest);
        let model = fb::Model::create(
            &mut builder,
            &fb::ModelArgs {
                version: FORMAT_VERSION,
                opset_version: OPSET_VERSION,
                runtime_min_version: None,
                converter_version: None,
                source_sha256: None,
                graph_digest: Some(digest),
                description: None,
                backends: None,
                tensors: Some(tensors),
                buffers: None,
                graphs: Some(graphs),
                entry_points: None,
                metadata: None,
            },
        );
        builder.finish(model, Some(fb::MODEL_IDENTIFIER));
        builder.finished_data().to_vec()
    }

    /// The model-wide digest gate fires: a structurally valid file carrying a
    /// wrong digest verifies but never reaches lowering.
    #[test]
    fn a_wrong_model_digest_is_rejected_at_inference() {
        let bytes = tiny_model([0xAB; 32]);
        let verified = crate::maml2::verify::verify(&bytes).expect("structure is valid");
        let err = infer(&verified).expect_err("the digest must not match");
        assert!(err.contains("digest"), "unexpected error: {err}");
    }

    /// The gate passes when the stored digest is the true fold: inference of
    /// the same topology succeeds, proving the fold both sides compute is
    /// the same one. The expected digests are hashed by hand from the spec
    /// section 9.2 sequence (graph index, op byte, inputs, outputs, no
    /// attrs) — emitter-independent.
    #[test]
    fn the_true_model_digest_passes_inference() {
        let mut graph_hasher = Sha256::new();
        graph_hasher.update(0u32.to_le_bytes());
        graph_hasher.update([fb::Op::Add.0 as u8]);
        graph_hasher.update(0i32.to_le_bytes());
        graph_hasher.update(1i32.to_le_bytes());
        graph_hasher.update(2i32.to_le_bytes());
        let graph_digest: [u8; 32] = graph_hasher.finalize().into();
        let mut model_hasher = Sha256::new();
        model_hasher.update(graph_digest);
        let model_digest: [u8; 32] = model_hasher.finalize().into();
        let bytes = tiny_model(model_digest);
        let verified = crate::maml2::verify::verify(&bytes).expect("structure is valid");
        let inferred = infer(&verified).expect("the true digest passes");
        assert_eq!(inferred.len(), 1);
        assert_eq!(inferred[0].graph_digest, graph_digest);
    }
}
