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
/// Returns one [`InferredGraph`] per graph, in model order.
pub fn infer(verified: &Verified<'_>) -> Result<Vec<InferredGraph>, String> {
    let model = verified.model;
    let tensors = model.tensors().ok_or("a model with no tensors")?;
    let graphs = model.graphs().ok_or("a model with no graphs")?;
    (0..graphs.len()).map(|g| infer_graph(&tensors, &graphs.get(g), g)).collect()
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

/// Infer one graph: propagate shapes, check SSA + acyclicity + digest.
fn infer_graph(
    tensors: &flatbuffers::Vector<'_, flatbuffers::ForwardsUOffset<fb::Tensor<'_>>>,
    graph: &fb::Graph<'_>,
    graph_index: usize,
) -> Result<InferredGraph, String> {
    let name = graph.name().unwrap_or("?");
    let nodes = graph.nodes().ok_or_else(|| format!("graph {graph_index} ({name}) has no nodes"))?;
    let mut shapes: HashMap<i32, Inferred> = HashMap::new();
    let mut writers: HashMap<i32, usize> = HashMap::new();
    // Graph inputs are written by the caller: seed from stored rows.
    if let Some(inputs) = graph.inputs() {
        for i in 0..inputs.len() {
            let t = inputs.get(i);
            shapes.insert(t, Inferred { dims: stored_dims(tensors, t)?, layout: stored_layout(tensors, t) });
        }
    }
    // Weight tensors (buffer >= 0) and host tensors (no buffer, PLACEMENT host)
    // are file facts: seed from stored rows so node inference can read them.
    for t in 0..tensors.len() as i32 {
        if shapes.contains_key(&t) {
            continue;
        }
        let tensor = tensors.get(t as usize);
        if tensor.buffer() != -1 || tensor.placement() == fb::Placement::HOST {
            shapes.insert(t, Inferred { dims: stored_dims(tensors, t)?, layout: tensor.layout() });
        }
    }

    let mut hasher = Sha256::new();
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
        // Every inferred output must equal the stored row (spec 7.2).
        for (output, want) in outputs.iter().zip(inferred.iter()) {
            let stored = stored_dims(tensors, *output)?;
            if stored != want.dims {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} ({:?}) infers tensor {output} as {:?}, stored {:?}",
                    node.op(),
                    want.dims,
                    stored
                ));
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
            let pads = attrs.ints("pads")?;
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
            let pt = pads.first().copied().unwrap_or(0);
            let pl = *pads.get(1).unwrap_or(&0);
            let m = w[0];
            let out_h = conv_out(x[1], kh, sh, dh, pt * 2);
            let out_w = conv_out(x[2], kw, sw, dw, pl * 2);
            let _ = groups;
            Ok(vec![Inferred { dims: vec![m, out_h, out_w], layout: computed_layout }])
        }
        fb::Op::MatMul => {
            if inputs.len() != 4 && inputs.len() != 3 {
                return Err(err("MatMul wants [a, w, s, b] or [a, w, b]"));
            }
            let a = shape_of(inputs[0])?.dims.clone();
            let w = shape_of(inputs[1])?.dims.clone();
            if a.len() != 3 || w.len() < 2 {
                return Err(err("MatMul shapes are not [c, h, w] / [m, ...]"));
            }
            // Pointwise: `[m, 1, positions]`.
            Ok(vec![Inferred { dims: vec![w[0], a[1], a[2]], layout: computed_layout }])
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
            // Two phases share one op: phase 0 scores (q, k -> map), phase 1
            // apply (probs, v -> mixed). The `phase` attr says which.
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
            } else {
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
            }
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
        _ => Err(err("op is not a Phase 1 sampler op")),
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
