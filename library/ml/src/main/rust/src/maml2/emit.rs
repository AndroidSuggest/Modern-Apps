//! Emit a MAML v2 file from a recorded [`Recorded`] forward pass.
//!
//! The transpiler half of Phase 1: the sampler's hand-written Rust pass builds
//! through [`Builder`] as it always has, and this module serialises the
//! resulting [`Recorded`] (fused nodes, shapes, bindings, file-tensor read
//! flags) into the FlatBuffers `MAM2` container from `schema/maml2.fbs`.
//!
//! The graph is correct by construction — it is the same `Recorded` that
//! `Builder::finish` would have emitted a [`Plan`] from, after the same
//! fusion fold and the same every-tensor rule. The converter's job is not to
//! re-derive topology but to supply the weight bytes (repacked to the
//! kernel-chosen layout) that the emitted tensor table names.

use std::collections::HashMap;

use sha2::{Digest, Sha256};

use crate::maml2::fb;
use crate::nets::{Act, Node, Quant, Recorded, Shape, SoftmaxMode};
use crate::weights::{Dtype, Tensor as WeightTensor, I4_BLOCK};

/// Quantization of one weight tensor, resolved from the [`Recorded`] graph.
///
/// The scale lives in its own file tensor (the companion-tensor convention),
/// so resolving is a lookup of which file index the nodes read as a scale.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum WeightQuant {
    /// Plain fp16.
    None,
    /// Int8 with a per-output-channel scale at this file index.
    Int8 {
        /// File tensor holding the fp16 scales.
        scale: u32,
    },
    /// Int4 with a per-block scale at this file index.
    Int4 {
        /// File tensor holding the fp16 scales, `[out, ceil(taps / 32)]`.
        scale: u32,
    },
}

/// Kernels are stored verbatim (NCHW), never repacked.
///
/// The channel-blocked twins (`Kind::ConvPointCb4Int8` and its successors)
/// read their kernel NCHW — only the activations are blocked — so the file
/// holds v1-order bytes and one stored layout serves every execution
/// layout. Repacking kernels would silently fork the host interpreter
/// (NCHW) from the device (blocked) with both sides self-consistent;
/// storing one layout keeps that class of bug inexpressible. (The pilot
/// stored blocked kernels, which made NCHW-kernel runs read permuted
/// weights on both sides and agree anyway; the repack pair was deleted
/// with that lesson.)

/// The v2 tensor rows for one v1 weight tensor, in emission order.
///
/// Most tensors emit one row. An int8/int4 kernel emits two: the repacked
/// kernel first, then its fp16 scale (repacked as a `[out, blocks]`
/// channel-blocked plane so the scale read is contiguous too).
struct EmittedTensor {
    /// Debug name, e.g. `w12.kernel.cb4`.
    name: String,
    /// Logical dims (unpadded).
    dims: Vec<i32>,
    /// v2 dtype.
    dtype: fb::DType,
    /// v2 layout.
    layout: fb::Layout,
    /// Payload bytes in v2 (repacked) order.
    bytes: Vec<u8>,
    /// Logical element count (unpadded).
    elems: u64,
    /// Quantization descriptor. Scales reference the emitted scale tensor by
    /// its position in the emission order, filled in on a second pass.
    quant: WeightQuant,
}

/// Weight payload of one v1 tensor, read from the data section.
enum WeightPayload {
    /// fp16 words, logical length `len`.
    F16(Vec<u16>),
    /// int8 bytes, logical length `len`.
    I8(Vec<u8>),
    /// Packed nibbles, `len.div_ceil(2)` bytes. Phase 1 refuses these (see
    /// `emit_weight_tensor`); the variant exists so the reader stays total.
    I4(Vec<u8>),
}

/// Read one v1 tensor's payload from the data section.
fn read_weight_payload(
    table: &[WeightTensor],
    data: &[u8],
    index: usize,
) -> Result<(WeightTensor, WeightPayload), String> {
    let tensor = table
        .get(index)
        .copied()
        .ok_or_else(|| format!("tensor {index} of {}: out of range", table.len()))?;
    let bytes = Dtype::bytes(tensor.dtype, u64::from(tensor.len)) as usize;
    let start = tensor.offset as usize;
    let end = start.checked_add(bytes).ok_or_else(|| format!("tensor {index} overflows"))?;
    let slice = data.get(start..end).ok_or_else(|| {
        format!("tensor {index} spans {start}..{end} of a {}-byte data section", data.len())
    })?;
    let payload = match tensor.dtype {
        Dtype::F16 => {
            let mut words = Vec::with_capacity(tensor.len as usize);
            for pair in slice.chunks_exact(2) {
                words.push(u16::from_le_bytes([pair[0], pair[1]]));
            }
            WeightPayload::F16(words)
        }
        Dtype::I8 => WeightPayload::I8(slice.to_vec()),
        Dtype::I4 => WeightPayload::I4(slice.to_vec()),
    };
    Ok((tensor, payload))
}

/// Tensor dims as a `[c, h, w]` shape with rank 1..4 mapped onto trailing axes.
///
/// v1 tensors are rank 1..4 with `dims[4]`; v2 dims are the significant
/// entries in order. A rank-2 `[m, n]` weight keeps `[m, n]`; the repack
/// interprets the leading axis as channels.
fn logical_dims(tensor: &WeightTensor) -> Vec<i32> {
    tensor.dims[..tensor.rank as usize].iter().map(|&d| d as i32).collect()
}

/// Emit one v1 weight tensor's v2 rows.
///
/// `quant` says how the graph reads this tensor: as a plain weight
/// ([`WeightQuant::None`]) or as a quantised kernel with its scale beside it.
/// Scale tensors are emitted by the kernel's emission, not separately — the
/// caller skips file indices consumed as scales.
#[allow(clippy::too_many_arguments)]
fn emit_weight_tensor(
    table: &[WeightTensor],
    data: &[u8],
    index: usize,
    quant: WeightQuant,
    rank_hint: Option<Vec<i32>>,
    out: &mut Vec<EmittedTensor>,
) -> Result<(), String> {
    let (tensor, payload) = read_weight_payload(table, data, index)?;
    let mut dims = rank_hint.unwrap_or_else(|| logical_dims(&tensor));
    // A `[c, 1, 1]` shorthand (per-channel vectors stored rank-3) stays
    // `[c, 1, 1]`; a `[n]` vector stays `[n, 1, 1]`. Padding is descriptive
    // only: stored bytes are verbatim either way.
    while dims.len() < 3 {
        dims.push(1);
    }
    match (payload, quant) {
        (WeightPayload::F16(words), WeightQuant::None) => {
            // Verbatim NCHW: every kernel reads fp16 weights NCHW, so the
            // file holds v1-order bytes (see the module note on kernels).
            let bytes: Vec<u8> = words.iter().flat_map(|w| w.to_le_bytes()).collect();
            let elems = words.len() as u64;
            out.push(EmittedTensor {
                name: format!("w{index}"),
                dims,
                dtype: fb::DType::F16,
                layout: fb::Layout::NCHW,
                bytes,
                elems,
                quant: WeightQuant::None,
            });
        }
        (WeightPayload::F16(_), WeightQuant::Int8 { .. }) => {
            return Err(format!("tensor {index} is fp16 but read as a quantised kernel"));
        }
        (WeightPayload::I8(bytes), WeightQuant::Int8 { .. }) => {
            // Verbatim NCHW: the int8 twins read `channel * in_c + tap`
            // exactly as the NCHW kernel does — only activations block.
            out.push(EmittedTensor {
                name: format!("w{index}.kernel"),
                dims,
                dtype: fb::DType::I8,
                layout: fb::Layout::NCHW,
                bytes: bytes.clone(),
                elems: bytes.len() as u64,
                quant,
            });
            // The scale follows as its own tensor: `[out]` fp16, contiguous.
            let (scale_tensor, scale_payload) = read_weight_payload(table, data, index + 1)?;
            let scale_dims = logical_dims(&scale_tensor);
            let WeightPayload::F16(scale_words) = scale_payload else {
                return Err(format!("tensor {} is not the fp16 scale of tensor {index}", index + 1));
            };
            let scale_bytes: Vec<u8> =
                scale_words.iter().flat_map(|w| w.to_le_bytes()).collect();
            out.push(EmittedTensor {
                name: format!("w{index}.scale"),
                dims: scale_dims,
                dtype: fb::DType::F16,
                layout: fb::Layout::NCHW,
                bytes: scale_bytes,
                elems: scale_words.len() as u64,
                quant: WeightQuant::None,
            });
        }
        (WeightPayload::I8(_), WeightQuant::None) => {
            return Err(format!("tensor {index} is int8 but read as a plain weight"));
        }
        (WeightPayload::I4(bytes), _) => {
            return Err(format!(
                "tensor {index}: int4 repack is not implemented in Phase 1 ({} bytes)",
                bytes.len()
            ));
        }
        (WeightPayload::I8(_), WeightQuant::Int4 { .. })
        | (WeightPayload::F16(_), WeightQuant::Int4 { .. }) => {
            return Err(format!("tensor {index}: mismatched quant kind"));
        }
    }
    Ok(())
}

/// Which file indices the [`Recorded`] nodes consume as quantised kernels,
/// mapped to their scale's file index.
///
/// Int8/int4 kernels carry resolved *offsets* in the nodes: `weight` is a
/// word index (`Tensor::word_offset`), while `scale`/`bias`/`gamma` are fp16
/// element indices (`Tensor::elem_offset`). The emitter inverts offsets back
/// to file indices through the table. An fp16 tensor's word and element
/// offsets differ (offset/4 vs offset/2), so the inversion is exact: a kernel
/// offset names the quantised tensor, a scale offset names the fp16 scale.
/// Two tensors could share an offset only if one has zero length, which the
/// v1 parser rejects.
fn quant_kernels(
    nodes: &[Node],
    table: &[WeightTensor],
    data: &[u8],
) -> Result<HashMap<usize, WeightQuant>, String> {
    let _ = data;
    let mut kernels = HashMap::new();
    let mut word_to_file: HashMap<u32, usize> = HashMap::new();
    let mut elem_to_file: HashMap<u32, usize> = HashMap::new();
    for (index, tensor) in table.iter().enumerate() {
        if tensor.dtype.is_quantised() {
            word_to_file.insert(tensor.word_offset(), index);
        } else {
            elem_to_file.insert(tensor.elem_offset(), index);
        }
    }
    for node in nodes {
        if let Node::ConvInt8 { weight, scale, quant, .. } = node {
            let kernel = word_to_file.get(weight).copied().ok_or_else(|| {
                format!("no quantised file tensor at word offset {weight}")
            })?;
            let scale_index = elem_to_file.get(scale).copied().ok_or_else(|| {
                format!("no fp16 file tensor at element offset {scale}")
            })?;
            let kind = match quant {
                Quant::I8 => WeightQuant::Int8 { scale: scale_index as u32 },
                Quant::I4 => WeightQuant::Int4 { scale: scale_index as u32 },
            };
            if let Some(previous) = kernels.insert(kernel, kind) {
                if previous != kind {
                    return Err(format!("tensor {kernel} read with two quant kinds"));
                }
            }
        }
    }
    Ok(kernels)
}

/// File indices consumed as scales (values of [`quant_kernels`]), so the
/// tensor walk can skip them: they emit beside their kernel.
fn scale_indices(kernels: &HashMap<usize, WeightQuant>) -> Vec<usize> {
    let mut scales: Vec<usize> = kernels
        .values()
        .filter_map(|kind| match *kind {
            WeightQuant::Int8 { scale } | WeightQuant::Int4 { scale } => Some(scale as usize),
            WeightQuant::None => None,
        })
        .collect();
    scales.sort_unstable();
    scales.dedup();
    scales
}

/// Activation code for the v2 `activation` attribute.
///
/// Matches `Act::code()` on the runtime side: 0 none, 1 relu, 2 hardswish,
/// 3 sigmoid, 5 clip01, 6 swish, 8 gelu. PRelu carries its slope tensor and
/// is encoded as `4 + slope file index << 8`; the Phase 1 sampler never
/// emits one (no PRelu in the net), so encountering it is an error.
fn act_code(act: Act) -> Result<i32, String> {
    match act {
        Act::None => Ok(0),
        Act::Relu => Ok(1),
        Act::HardSwish => Ok(2),
        Act::Sigmoid => Ok(3),
        Act::PRelu(_) => Err("PRelu is not expressible in the Phase 1 sampler graph".into()),
        Act::Clip01 => Ok(5),
        Act::Swish => Ok(6),
        Act::Gelu => Ok(8),
    }
}

/// Softmax mode code for the v2 `mode` attribute: 0 full, 1 causal, 2 prefix.
fn softmax_mode_code(mode: SoftmaxMode) -> i32 {
    match mode {
        SoftmaxMode::Full => 0,
        SoftmaxMode::Causal => 1,
        SoftmaxMode::Prefix => 2,
    }
}

/// One emitted v2 node: op, tensor refs, and attributes.
pub struct EmittedNode {
    /// The catalog op.
    pub op: fb::Op,
    /// Input tensor indices.
    pub inputs: Vec<i32>,
    /// Output tensor indices.
    pub outputs: Vec<i32>,
    /// Typed attributes in emission order (digest order).
    pub attrs: Vec<(String, AttrValue)>,
}

/// Attribute values the emitter writes.
pub enum AttrValue {
    /// A single integer.
    Int(i32),
    /// An integer vector.
    Ints(Vec<i32>),
    /// A float.
    Float(f32),
    /// A boolean.
    Bool(bool),
}

/// Map a resolved weight offset to its v2 tensor index.
///
/// `id_map.weights` is keyed by v1 file index (assigned in emission order).
/// Nodes carry resolved offsets, so invert through two maps: word offsets
/// for quantised kernels, element offsets for fp16 weights. Built once per
/// emission from the v1 table.
struct WeightLookup {
    word_to_v2: HashMap<u32, i32>,
    elem_to_v2: HashMap<u32, i32>,
}

impl WeightLookup {
    fn new(table: &[WeightTensor], weights: &HashMap<usize, i32>) -> Result<WeightLookup, String> {
        let mut word_to_v2 = HashMap::new();
        let mut elem_to_v2 = HashMap::new();
        for (file_index, v2) in weights {
            let tensor = table.get(*file_index).copied().ok_or_else(|| {
                format!("tensor {file_index} of {}: out of range", table.len())
            })?;
            if tensor.dtype.is_quantised() {
                word_to_v2.insert(tensor.word_offset(), *v2);
            } else {
                elem_to_v2.insert(tensor.elem_offset(), *v2);
            }
        }
        Ok(WeightLookup { word_to_v2, elem_to_v2 })
    }

    /// Look up a quantised kernel by its word offset.
    fn kernel(&self, offset: u32) -> Result<i32, String> {
        self.word_to_v2.get(&offset).copied().ok_or_else(|| {
            format!("conv_int8 weight offset {offset} is not a quantised file tensor")
        })
    }

    /// Look up an fp16 weight (bias, scale, gamma, beta) by element offset.
    fn fp16(&self, what: &str, offset: u32) -> Result<i32, String> {
        self.elem_to_v2.get(&offset).copied().ok_or_else(|| {
            format!("{what} offset {offset} is not an fp16 file tensor")
        })
    }
}

/// Map a [`Recorded`] tensor id to its v2 tensor index.
///
/// `tensor_ids` assigns v2 indices: graph inputs first (declaration order),
/// then computed tensors in first-use order, then host tensors. Weight file
/// indices map through `weight_ids`.
struct IdMap {
    /// [`Recorded`] tensor id (`Id.0`) → v2 tensor index.
    computed: HashMap<usize, i32>,
    /// v1 file tensor index → v2 tensor index (weights only).
    weights: HashMap<usize, i32>,
    /// v2 tensor count so far.
    next: i32,
}

impl IdMap {
    fn new() -> IdMap {
        IdMap { computed: HashMap::new(), weights: HashMap::new(), next: 0 }
    }

    fn alloc(&mut self) -> i32 {
        let index = self.next;
        self.next += 1;
        index
    }
}

/// Emit the v2 nodes for one [`Recorded`] graph.
///
/// `shapes` gives every tensor id's `[c, h, w]`; `id_map` is pre-seeded with
/// the graph inputs and weight tensors, and gains the computed tensors in
/// first-use order. `emit_tensors` (built alongside) receives the computed
/// tensor rows in the same order.
#[allow(clippy::too_many_arguments)]
fn emit_nodes(
    recorded: &Recorded,
    id_map: &mut IdMap,
    weights: &WeightLookup,
    emit_tensors: &mut Vec<EmittedTensor>,
    seen: &mut HashMap<usize, i32>,
) -> Result<Vec<EmittedNode>, String> {
    let mut nodes = Vec::with_capacity(recorded.nodes.len());
    // Tensor id → v2 index for computed tensors, allocating rows on first use.
    let mut computed = |id: usize, map: &mut IdMap, tensors: &mut Vec<EmittedTensor>| -> i32 {
        if let Some(&index) = map.computed.get(&id) {
            return index;
        }
        let index = map.alloc();
        map.computed.insert(id, index);
        seen.insert(id, index);
        let shape = recorded.shapes.get(id).copied().unwrap_or(Shape::new(0, 0, 0));
        tensors.push(EmittedTensor {
            name: format!("t{id}"),
            dims: vec![shape.c as i32, shape.h as i32, shape.w as i32],
            dtype: fb::DType::F16,
            // NCHW during the migration: the recording ran NCHW kernels, so
            // the arena the loader rebuilds is NCHW too, and the file
            // declares it. Re-emitted blocked when twins land.
            layout: fb::Layout::NCHW,
            bytes: Vec::new(),
            elems: shape.len() as u64,
            quant: WeightQuant::None,
        });
        index
    };
    for node in &recorded.nodes {
        match *node {
            Node::Conv {
                input,
                out,
                weight,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                transpose,
                pad_edge,
                res,
                shift,
                ..
            } => {
                if transpose {
                    return Err("transposed convolution is not in the Phase 1 sampler".into());
                }
                let a = computed(input.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                let w = weights.fp16("conv weight", weight)?;
                let b = weights.fp16("conv bias", bias)?;
                let mut attrs = vec![
                    ("kernel".into(), AttrValue::Ints(vec![kernel.0 as i32, kernel.1 as i32])),
                    ("stride".into(), AttrValue::Ints(vec![stride.0 as i32, stride.1 as i32])),
                    (
                        "dilation".into(),
                        AttrValue::Ints(vec![dilation.0 as i32, dilation.1 as i32]),
                    ),
                    ("pads".into(), AttrValue::Ints(vec![pad.0 as i32, pad.1 as i32])),
                    ("groups".into(), AttrValue::Int(group as i32)),
                    ("pad_edge".into(), AttrValue::Bool(pad_edge)),
                    ("activation".into(), AttrValue::Int(act_code(act)?)),
                ];
                if let Some(res) = res {
                    attrs.push((
                        "res".into(),
                        AttrValue::Int(computed(res.0, id_map, emit_tensors)),
                    ));
                }
                if let Some(shift) = shift {
                    attrs.push((
                        "shift".into(),
                        AttrValue::Int(computed(shift.0, id_map, emit_tensors)),
                    ));
                }
                nodes.push(EmittedNode {
                    op: fb::Op::Conv,
                    inputs: vec![a, w, b],
                    outputs: vec![y],
                    attrs,
                });
            }
            Node::ConvInt8 {
                input,
                out,
                weight,
                scale,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                quant,
                res,
                shift,
            } => {
                let a = computed(input.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                let w = weights.kernel(weight)?;
                let s = weights.fp16("conv_int8 scale", scale)?;
                let b = weights.fp16("conv_int8 bias", bias)?;
                let mut attrs = vec![
                    ("kernel".into(), AttrValue::Ints(vec![kernel.0 as i32, kernel.1 as i32])),
                    ("stride".into(), AttrValue::Ints(vec![stride.0 as i32, stride.1 as i32])),
                    (
                        "dilation".into(),
                        AttrValue::Ints(vec![dilation.0 as i32, dilation.1 as i32]),
                    ),
                    ("pads".into(), AttrValue::Ints(vec![pad.0 as i32, pad.1 as i32])),
                    ("groups".into(), AttrValue::Int(group as i32)),
                    ("activation".into(), AttrValue::Int(act_code(act)?)),
                    (
                        "quant".into(),
                        AttrValue::Int(match quant {
                            Quant::I8 => 1,
                            Quant::I4 => 2,
                        }),
                    ),
                ];
                if let Some(res) = res {
                    attrs.push((
                        "res".into(),
                        AttrValue::Int(computed(res.0, id_map, emit_tensors)),
                    ));
                }
                if let Some(shift) = shift {
                    attrs.push((
                        "shift".into(),
                        AttrValue::Int(computed(shift.0, id_map, emit_tensors)),
                    ));
                }
                nodes.push(EmittedNode {
                    // v2 stores quantised convolutions as MatMul when 1x1
                    // (the loader lowers by shape); the kernel dims say so.
                    op: if kernel == (1, 1) { fb::Op::MatMul } else { fb::Op::Conv },
                    inputs: vec![a, w, s, b],
                    outputs: vec![y],
                    attrs,
                });
            }
            Node::Binary { kind, a, b, out } => {
                let x = computed(a.0, id_map, emit_tensors);
                let y_in = computed(b.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                let op = match kind {
                    crate::nets::Kind::Add => fb::Op::Add,
                    crate::nets::Kind::Mul => fb::Op::Mul,
                    crate::nets::Kind::MulBroadcast => fb::Op::MulBroadcast,
                    crate::nets::Kind::AddBroadcast => fb::Op::AddBroadcast,
                    other => return Err(format!("{other:?} is not a Phase 1 sampler op")),
                };
                nodes.push(EmittedNode { op, inputs: vec![x, y_in], outputs: vec![y], attrs: vec![] });
            }
            Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                let x = computed(input.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                let g = weights.fp16("layer_norm gamma", gamma)?;
                let be = weights.fp16("layer_norm beta", beta)?;
                nodes.push(EmittedNode {
                    op: fb::Op::LayerNorm,
                    inputs: vec![x, g, be],
                    outputs: vec![y],
                    attrs: vec![("epsilon".into(), AttrValue::Float(epsilon))],
                });
            }
            Node::AttnScores { q, k, out, heads, kv_heads, scale } => {
                let x = computed(q.0, id_map, emit_tensors);
                let y_in = computed(k.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                nodes.push(EmittedNode {
                    op: fb::Op::Attention,
                    inputs: vec![x, y_in],
                    outputs: vec![y],
                    attrs: vec![
                        ("heads".into(), AttrValue::Int(heads as i32)),
                        ("kv_heads".into(), AttrValue::Int(kv_heads as i32)),
                        ("scale".into(), AttrValue::Float(scale)),
                        ("phase".into(), AttrValue::Int(0)),
                    ],
                });
            }
            Node::AttnApply { probs, v, out, heads, kv_heads } => {
                let p = computed(probs.0, id_map, emit_tensors);
                let vv = computed(v.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                nodes.push(EmittedNode {
                    op: fb::Op::Attention,
                    inputs: vec![p, vv],
                    outputs: vec![y],
                    attrs: vec![
                        ("heads".into(), AttrValue::Int(heads as i32)),
                        ("kv_heads".into(), AttrValue::Int(kv_heads as i32)),
                        ("phase".into(), AttrValue::Int(1)),
                    ],
                });
            }
            Node::Softmax { input, out, mode, .. } => {
                let x = computed(input.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                nodes.push(EmittedNode {
                    op: fb::Op::Softmax,
                    inputs: vec![x],
                    outputs: vec![y],
                    attrs: vec![("mode".into(), AttrValue::Int(softmax_mode_code(mode)))],
                });
            }
            Node::Rotary { input, angles, out, heads, axes } => {
                let x = computed(input.0, id_map, emit_tensors);
                let a = computed(angles.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                nodes.push(EmittedNode {
                    op: fb::Op::RotaryEmbedding,
                    inputs: vec![x, a],
                    outputs: vec![y],
                    attrs: vec![
                        ("heads".into(), AttrValue::Int(heads as i32)),
                        ("axes".into(), AttrValue::Int(axes as i32)),
                    ],
                });
            }
            Node::SliceChannels { input, out, start } => {
                let x = computed(input.0, id_map, emit_tensors);
                let y = computed(out.0, id_map, emit_tensors);
                let shape = recorded.shapes.get(out.0).copied().unwrap_or(Shape::new(0, 0, 0));
                nodes.push(EmittedNode {
                    op: fb::Op::View,
                    inputs: vec![x],
                    outputs: vec![y],
                    attrs: vec![
                        ("offset".into(), AttrValue::Int(start as i32)),
                        (
                            "dims".into(),
                            AttrValue::Ints(vec![
                                shape.c as i32,
                                shape.h as i32,
                                shape.w as i32,
                            ]),
                        ),
                    ],
                });
            }
            ref other => {
                return Err(format!("{other:?} is not a Phase 1 sampler op"));
            }
        }
    }
    Ok(nodes)
}

/// Hash emitted nodes in the canonical order (spec 9.2): op byte, inputs,
/// outputs, then per-attribute name + value bytes. [`infer::digest_graph`]
/// must hash the same sequence from the file, including the caller's graph
/// index prefix.
pub fn digest_nodes(hasher: &mut Sha256, nodes: &[EmittedNode]) {
    for node in nodes {
        hasher.update([node.op.0 as u8]);
        for input in &node.inputs {
            hasher.update(input.to_le_bytes());
        }
        for output in &node.outputs {
            hasher.update(output.to_le_bytes());
        }
        for (name, attr) in &node.attrs {
            hasher.update(name.as_bytes());
            match attr {
                AttrValue::Int(v) => hasher.update(v.to_le_bytes()),
                AttrValue::Ints(vs) => {
                    for v in vs {
                        hasher.update(v.to_le_bytes());
                    }
                }
                AttrValue::Float(v) => hasher.update(v.to_le_bytes()),
                AttrValue::Bool(v) => hasher.update([u8::from(*v)]),
            }
        }
    }
}

/// A finished v2 model: the FlatBuffers bytes plus the checks the emitter ran.
pub struct Emitted {
    /// The `MAM2` file bytes.
    pub bytes: Vec<u8>,
    /// SHA-256 over the canonical op/edge/attr sequence (spec section 9.2).
    pub graph_digest: [u8; 32],
    /// `(op, count)` inventory of the emitted graph, for the report.
    pub op_inventory: Vec<(fb::Op, usize)>,
}

/// Emit a MAML v2 model from one recorded sampler branch.
///
/// `recorded` is the [`Builder::record`] output at a fixed `(frames, chars)`
/// shape; `table` + `data` are the v1 file's tensor table and data section,
/// whose weight bytes are repacked into the v2 buffers. `description` names
/// the model for humans; `converter_version` and `source_sha256` trace
/// provenance.
///
/// The entry point is `encode` over the graph `sampler`: Phase 1 proves the
/// single-branch loop, and the dual-branch plan is two submits of it.
pub fn emit_sampler(
    recorded: &Recorded,
    table: &[WeightTensor],
    data: &[u8],
    description: &str,
    converter_version: &str,
    source_sha256: [u8; 32],
) -> Result<Emitted, String> {
    // 1. Which file tensors are quantised kernels, and which are their scales.
    let kernels = quant_kernels(&recorded.nodes, table, data)?;
    let scales = scale_indices(&kernels);
    // File tensors named by node offsets (kernels, biases, scales, gammas):
    // collect every resolved offset the nodes carry, inverted to file indices.
    // A tensor marked read but named by no node is host-side data (the v1
    // `host_tensor` contract: read on the CPU, never bound). A tensor neither
    // read nor named cannot reach here — `Builder::record` fails it first.
    let mut node_refs: HashMap<usize, ()> = HashMap::new();
    {
        let mut word_to_file: HashMap<u32, usize> = HashMap::new();
        let mut elem_to_file: HashMap<u32, usize> = HashMap::new();
        for (index, tensor) in table.iter().enumerate() {
            if tensor.dtype.is_quantised() {
                word_to_file.insert(tensor.word_offset(), index);
            } else {
                elem_to_file.insert(tensor.elem_offset(), index);
            }
        }
        // Node weight fields are offsets; invert each to its file tensor.
        // (Duplicated with `quant_kernels`' maps; factored here because the
        // host decision needs the full ref set, not just kernels.)
        let mark = |offset: u32,
                    quantised: bool,
                    word_to_file: &HashMap<u32, usize>,
                    elem_to_file: &HashMap<u32, usize>|
         -> Option<usize> {
            if quantised {
                word_to_file.get(&offset).copied()
            } else {
                elem_to_file.get(&offset).copied()
            }
        };
        for node in &recorded.nodes {
            match node {
                Node::Conv { weight, bias, act_weight, .. } => {
                    // act_weight is 0 unless PRelu (absent in Phase 1).
                    let _ = act_weight;
                    // The fp16 kernel/bias resolve through elem offsets; find
                    // their file tensors by matching offset to table.
                    for (index, tensor) in table.iter().enumerate() {
                        if !tensor.dtype.is_quantised()
                            && (tensor.elem_offset() == *weight
                                || tensor.elem_offset() == *bias)
                        {
                            node_refs.insert(index, ());
                        }
                    }
                }
                Node::ConvInt8 { weight, scale, bias, .. } => {
                    for file in [
                        mark(*weight, true, &word_to_file, &elem_to_file),
                        mark(*scale, false, &word_to_file, &elem_to_file),
                        mark(*bias, false, &word_to_file, &elem_to_file),
                    ]
                    .into_iter()
                    .flatten()
                    {
                        node_refs.insert(file, ());
                    }
                }
                Node::LayerNorm { gamma, beta, .. } => {
                    for file in [
                        mark(*gamma, false, &word_to_file, &elem_to_file),
                        mark(*beta, false, &word_to_file, &elem_to_file),
                    ]
                    .into_iter()
                    .flatten()
                    {
                        node_refs.insert(file, ());
                    }
                }
                Node::RmsNorm { gamma, .. } => {
                    if let Some(file) = mark(*gamma, false, &word_to_file, &elem_to_file) {
                        node_refs.insert(file, ());
                    }
                }
                Node::AttnScoresRelative { table: relative, .. }
                | Node::AttnApplyRelative { table: relative, .. }
                | Node::AttnScoresBanded { table: relative, .. } => {
                    if let Some(file) = mark(*relative, false, &word_to_file, &elem_to_file) {
                        node_refs.insert(file, ());
                    }
                }
                Node::Constant { weight, .. }
                | Node::Embed { table: weight, .. }
                | Node::MulScalar { scale: weight, .. }
                | Node::Clamp { bounds: weight, .. } => {
                    if let Some(file) = mark(*weight, false, &word_to_file, &elem_to_file) {
                        node_refs.insert(file, ());
                    }
                }
                _ => {}
            }
        }
        // Scales ride beside their kernel: mark them referenced so the host
        // check below does not misclassify them (they are skipped in emission
        // order, not emitted as host rows).
        for scale in &scales {
            node_refs.insert(*scale, ());
        }
    }

    // 2. Emit the weight tensors in file order (skipping scales: they ride
    //    beside their kernel), assigning v2 tensor indices.
    let mut id_map = IdMap::new();
    let mut emitted: Vec<EmittedTensor> = Vec::new();
    // v1 file index → position in `emitted` (kernels occupy two rows).
    let mut weight_rows: HashMap<usize, usize> = HashMap::new();
    for index in 0..table.len() {
        if scales.contains(&index) {
            continue;
        }
        // Host tensors are read on the CPU, never bound: they still occupy a
        // v2 tensor row (placement HOST, no buffer) so the graph can name them.
        // Named by no node but marked read = the v1 `host_tensor` contract.
        let is_host = !node_refs.contains_key(&index)
            && recorded.read.get(index).copied().unwrap_or(false);
        if is_host {
            let tensor = table.get(index).copied().ok_or_else(|| {
                format!("tensor {index} of {}: out of range", table.len())
            })?;
            let v2 = id_map.alloc();
            id_map.weights.insert(index, v2);
            weight_rows.insert(index, emitted.len());
            emitted.push(EmittedTensor {
                name: format!("w{index}.host"),
                dims: logical_dims(&tensor),
                dtype: fb::DType::F16,
                layout: fb::Layout::NCHW,
                bytes: Vec::new(),
                elems: u64::from(tensor.len),
                quant: WeightQuant::None,
            });
            continue;
        }
        let quant = kernels.get(&index).copied().unwrap_or(WeightQuant::None);
        let before = emitted.len();
        emit_weight_tensor(table, data, index, quant, None, &mut emitted)?;
        let v2 = id_map.alloc();
        // A quantised kernel occupies two rows; the v2 index names the kernel.
        if matches!(quant, WeightQuant::Int8 { .. } | WeightQuant::Int4 { .. }) {
            let scale_v2 = id_map.alloc();
            id_map.weights.insert(index + 1, scale_v2);
        }
        id_map.weights.insert(index, v2);
        weight_rows.insert(index, before);
        let _ = weight_rows;
    }
    // The v2 weight map is keyed by file index throughout; nodes look up
    // through the offset→v2 inversion below. Scales were allocated right
    // after their kernel above, in file order.
    let _ = weight_rows;
    let weights = WeightLookup::new(table, &id_map.weights)?;

    // 3. Graph inputs: declaration order = binding order.
    let mut input_ids = Vec::with_capacity(recorded.inputs.len());
    for input in &recorded.inputs {
        let v2 = id_map.alloc();
        id_map.computed.insert(input.0, v2);
        input_ids.push(v2);
    }
    let mut emit_tensors: Vec<EmittedTensor> = Vec::new();
    for input in &recorded.inputs {
        let shape = recorded.shapes.get(input.0).copied().unwrap_or(Shape::new(0, 0, 0));
        emit_tensors.push(EmittedTensor {
            name: format!("in{}", input.0),
            dims: vec![shape.c as i32, shape.h as i32, shape.w as i32],
            dtype: fb::DType::F16,
            layout: fb::Layout::NCHW,
            bytes: Vec::new(),
            elems: shape.len() as u64,
            quant: WeightQuant::None,
        });
    }
    // 4. Nodes + computed tensors.
    // `emit_nodes` allocates computed tensors in first-use order and records
    // tensor-id → v2-index in `seen`.
    let mut seen: HashMap<usize, i32> = HashMap::new();
    let nodes = emit_nodes(recorded, &mut id_map, &weights, &mut emit_tensors, &mut seen)?;

    // 5. Outputs: the v2 index of each recorded output tensor id. Exact —
    // no shape matching, no last-writer heuristics.
    let plan_outputs: Vec<i32> = recorded
        .outputs
        .iter()
        .map(|id| {
            seen.get(&id.index()).copied().ok_or_else(|| {
                format!("output tensor {} was never emitted", id.index())
            })
        })
        .collect::<Result<Vec<i32>, String>>()?;

    // 6. Canonical digest over the graph-indexed op/edge/attr sequences
    // (spec section 9.2): the graph index first, then the nodes in the same
    // order `infer` replays. Model-wide so a second graph (prefill +
    // decode, twin towers) cannot smuggle an unaudited topology past the
    // gate; single-graph files hash index 0, then fold once like every
    // model with one graph.
    let mut hasher = Sha256::new();
    hasher.update(0u32.to_le_bytes());
    digest_nodes(&mut hasher, &nodes);
    let graph0: [u8; 32] = hasher.finalize().into();
    let mut model_hasher = Sha256::new();
    model_hasher.update(graph0);
    let graph_digest: [u8; 32] = model_hasher.finalize().into();

    // 7. Assemble the FlatBuffers model.
    let mut builder = flatbuffers::FlatBufferBuilder::new();
    // Buffers: one per emitted weight payload, 16-aligned; computed tensors
    // reference no buffer.
    let buffer_offsets: Vec<flatbuffers::WIPOffset<fb::Buffer<'_>>> = Vec::new();
    let _ = buffer_offsets;
    // NOTE: assembly continues below; tensor/buffer vectors need the payloads
    // built first. The full builder sequence is in `assemble`.
    let assembled = assemble(
        &mut builder,
        &emitted,
        &emit_tensors,
        &nodes,
        &input_ids,
        &plan_outputs,
        recorded,
        description,
        converter_version,
        source_sha256,
        graph_digest,
    )?;
    let _ = assembled;

    let mut op_inventory: Vec<(fb::Op, usize)> = Vec::new();
    for node in &nodes {
        match op_inventory.iter_mut().find(|(op, _)| *op == node.op) {
            Some((_, n)) => *n += 1,
            None => op_inventory.push((node.op, 1)),
        }
    }
    op_inventory.sort_by_key(|(_, n)| std::cmp::Reverse(*n));

    Ok(Emitted { bytes: builder.finished_data().to_vec(), graph_digest, op_inventory })
}

/// Build the FlatBuffers vectors and finish the model.
#[allow(clippy::too_many_arguments)]
fn assemble(
    builder: &mut flatbuffers::FlatBufferBuilder<'_>,
    emitted: &[EmittedTensor],
    computed: &[EmittedTensor],
    nodes: &[EmittedNode],
    input_ids: &[i32],
    outputs: &[i32],
    recorded: &Recorded,
    description: &str,
    converter_version: &str,
    source_sha256: [u8; 32],
    graph_digest: [u8; 32],
) -> Result<(), String> {
    // Weight payloads become Buffers; each emitted weight tensor points at
    // its buffer. Computed tensors (buffer -1) and host tensors (no bytes)
    // point at nothing.
    // Map emitted-tensor position → buffer index.
    let mut buffer_of: Vec<Option<usize>> = Vec::with_capacity(emitted.len());
    let mut buffers: Vec<flatbuffers::WIPOffset<fb::Buffer<'_>>> = Vec::new();
    for tensor in emitted.iter() {
        if tensor.bytes.is_empty() {
            buffer_of.push(None);
            continue;
        }
        let data = builder.create_vector(&tensor.bytes);
        buffers.push(fb::Buffer::create(builder, &fb::BufferArgs { data: Some(data) }));
        buffer_of.push(Some(buffers.len() - 1));
    }
    let buffers = builder.create_vector(&buffers);

    // Quantization structs are inline; build the per-tensor Tensor tables.
    // Scale refs: the kernel's `quant` names the v1 scale file index, which
    // the weight map already turned into a v2 tensor index.
    let _ = recorded;
    let mut tensors: Vec<flatbuffers::WIPOffset<fb::Tensor<'_>>> = Vec::new();
    // v2 tensor index → position in `tensors`, for scale refs. Weights were
    // allocated in emission order starting at 0; inputs and computed follow.
    // Reconstruct: emitted weights occupy 0..emitted.len().
    for (position, tensor) in emitted.iter().enumerate() {
        let name = builder.create_string(&tensor.name);
        let dims = builder.create_vector(&tensor.dims);
        let (quant_kind, scale_v2, quant_dim, block) = match tensor.quant {
            WeightQuant::None => (fb::QuantKind::NONE, -1, 0, 0),
            WeightQuant::Int8 { .. } => {
                // The scale row follows the kernel row in emission order.
                (fb::QuantKind::PER_CHANNEL, (position + 1) as i32, 0, 0)
            }
            WeightQuant::Int4 { .. } => {
                (fb::QuantKind::PER_CHANNEL, (position + 1) as i32, 0, I4_BLOCK as i32)
            }
        };
        let quant = fb::Quantization::new(
            quant_kind,
            if matches!(tensor.quant, WeightQuant::None) {
                -1
            } else {
                // Position in emission order == v2 index for the weight block
                // (weights start at 0 and each occupies its rows in order).
                scale_v2
            },
            -1,
            quant_dim,
            block,
        );
        let (buffer, buffer_offset) = match buffer_of[position] {
            Some(_) => (position as i32, 0u64),
            None => (-1, 0u64),
        };
        let placement =
            if tensor.bytes.is_empty() { fb::Placement::HOST } else { fb::Placement::DEVICE };
        tensors.push(fb::Tensor::create(
            builder,
            &fb::TensorArgs {
                name: Some(name),
                dtype: tensor.dtype,
                dims: Some(dims),
                dim_params: None,
                layout: tensor.layout,
                quantization: Some(&quant),
                buffer,
                buffer_offset,
                elem_count: tensor.elems,
                placement,
                state: fb::StateKind::NONE,
            },
        ));
    }
    // Computed + input tensors: dtype F16, CB4, no buffer, no quant.
    // Their v2 indices continue after the weights; node refs already assume
    // that order (inputs allocated after weights, computed in first-use
    // order matching `emit_tensors` push order).
    for tensor in computed.iter() {
        let name = builder.create_string(&tensor.name);
        let dims = builder.create_vector(&tensor.dims);
        let quant = fb::Quantization::new(fb::QuantKind::NONE, -1, -1, 0, 0);
        tensors.push(fb::Tensor::create(
            builder,
            &fb::TensorArgs {
                name: Some(name),
                dtype: tensor.dtype,
                dims: Some(dims),
                dim_params: None,
                layout: tensor.layout,
                quantization: Some(&quant),
                buffer: -1,
                buffer_offset: 0,
                elem_count: tensor.elems,
                placement: fb::Placement::DEVICE,
                state: fb::StateKind::NONE,
            },
        ));
    }
    let tensors = builder.create_vector(&tensors);

    // Nodes with typed attributes.
    let mut node_offsets = Vec::with_capacity(nodes.len());
    for node in nodes {
        let inputs = builder.create_vector(&node.inputs);
        let outputs = builder.create_vector(&node.outputs);
        let mut attr_offsets = Vec::with_capacity(node.attrs.len());
        for (name, attr) in &node.attrs {
            let name = builder.create_string(name);
            let (value_type, value) = match attr {
                AttrValue::Int(v) => {
                    let t = fb::AttrInt::create(builder, &fb::AttrIntArgs { value: *v });
                    (fb::AttrValue::AttrInt, t.as_union_value())
                }
                AttrValue::Ints(vs) => {
                    let v = builder.create_vector(vs);
                    let t = fb::AttrInts::create(builder, &fb::AttrIntsArgs { value: Some(v) });
                    (fb::AttrValue::AttrInts, t.as_union_value())
                }
                AttrValue::Float(v) => {
                    let t = fb::AttrFloat::create(builder, &fb::AttrFloatArgs { value: *v });
                    (fb::AttrValue::AttrFloat, t.as_union_value())
                }
                AttrValue::Bool(v) => {
                    let t = fb::AttrBool::create(builder, &fb::AttrBoolArgs { value: *v });
                    (fb::AttrValue::AttrBool, t.as_union_value())
                }
            };
            attr_offsets.push(fb::Attribute::create(
                builder,
                &fb::AttributeArgs { name: Some(name), value_type, value: Some(value) },
            ));
        }
        let attrs = builder.create_vector(&attr_offsets);
        node_offsets.push(fb::Node::create(
            builder,
            &fb::NodeArgs {
                op: node.op,
                inputs: Some(inputs),
                outputs: Some(outputs),
                attrs: Some(attrs),
                payload: None,
            },
        ));
    }
    let nodes = builder.create_vector(&node_offsets);
    // The graph's tensor scope is every v2 tensor.
    let tensor_count = emitted.len() + computed.len();
    let scope: Vec<i32> = (0..tensor_count as i32).collect();
    let scope = builder.create_vector(&scope);
    let graph_inputs = builder.create_vector(input_ids);
    let graph_outputs = builder.create_vector(outputs);
    let graph_name = builder.create_string("sampler");
    let graph = fb::Graph::create(
        builder,
        &fb::GraphArgs {
            name: Some(graph_name),
            tensors: Some(scope),
            nodes: Some(nodes),
            inputs: Some(graph_inputs),
            outputs: Some(graph_outputs),
        },
    );
    let graphs = builder.create_vector(&[graph]);

    // Entry point: `encode` over graph 0. Roles bind positionally to the
    // graph inputs (the sampler's seven, in `build` order).
    let entry_name = builder.create_string("encode");
    let roles: Vec<flatbuffers::WIPOffset<&str>> = [
        "noisy_latent",
        "text",
        "style_keys",
        "style_values",
        "shifts",
        "query_angles",
        "key_angles",
    ]
    .iter()
    .map(|role| builder.create_string(role))
    .collect();
    let roles = builder.create_vector(&roles);
    let entry_point = fb::EntryPoint::create(
        builder,
        &fb::EntryPointArgs {
            name: Some(entry_name),
            graph: 0,
            inputs: Some(roles),
            step_uniforms: None,
        },
    );
    let entry_points = builder.create_vector(&[entry_point]);

    let description = builder.create_string(description);
    let converter_version = builder.create_string(converter_version);
    let runtime_min_version = builder.create_string("2.0.0");
    let source_sha256 = builder.create_vector(&source_sha256);
    let graph_digest = builder.create_vector(&graph_digest);
    let model = fb::Model::create(
        builder,
        &fb::ModelArgs {
            version: super::FORMAT_VERSION,
            opset_version: super::OPSET_VERSION,
            runtime_min_version: Some(runtime_min_version),
            converter_version: Some(converter_version),
            source_sha256: Some(source_sha256),
            graph_digest: Some(graph_digest),
            description: Some(description),
            backends: None,
            tensors: Some(tensors),
            buffers: Some(buffers),
            graphs: Some(graphs),
            entry_points: Some(entry_points),
            metadata: None,
        },
    );
    builder.finish(model, Some(fb::MODEL_IDENTIFIER));
    Ok(())
}
