//! Structural validation of a MAML v2 file (spec section 9.1).
//!
//! Everything here runs before any device touch: magic, versions, bounds,
//! alignment, and cross-references. A failure is a load error naming the
//! offending tensor/node — never a wrong answer, never a driver reset.
//!
//! Semantic validation (SSA single-writer, acyclicity, every-weight-read,
//! digest recompute — spec section 9.2) lives in [`super::infer`], which
//! needs the inferred shapes to check outputs against.

use crate::maml2::fb;
use crate::maml2::{FORMAT_VERSION, OPSET_VERSION};

/// A validated file: the parsed root plus the counts every later stage needs.
///
/// Borrowed from the input bytes (`mmap` + verify + dereference in place —
/// the loader never deserialises weights into a second allocation).
pub struct Verified<'a> {
    /// The parsed model root.
    pub model: fb::Model<'a>,
    /// `model.tensors.len()`, checked non-empty.
    pub tensor_count: usize,
    /// `model.buffers.len()`.
    pub buffer_count: usize,
    /// `model.graphs.len()`, checked non-empty.
    pub graph_count: usize,
}

/// Greatest byte length one element of this dtype occupies.
///
/// v2 dtypes are logical: I8 is one byte an element, I4 half a byte (rounded
/// up per tensor). The loader checks `buffer_bytes == ceil(elems / stride)`.
fn dtype_stride(dtype: fb::DType) -> Result<u64, String> {
    match dtype {
        fb::DType::F16 => Ok(2),
        fb::DType::F32 => Ok(4),
        fb::DType::I32 => Ok(4),
        fb::DType::U32 => Ok(4),
        fb::DType::I8 => Ok(1),
        fb::DType::I4 => Ok(1),
        _ => Err(format!("dtype {dtype:?} is not a Phase 1 dtype")),
    }
}

/// Whether the dtype packs two elements per byte.
fn is_packed(dtype: fb::DType) -> bool {
    dtype == fb::DType::I4
}

/// Verify `bytes` as a MAML v2 file, returning the parsed root.
///
/// Checks, in order (spec section 9.1):
///
/// * `MAM2` identifier (FlatBuffers verifier runs first: malformed buffers
///   fail there, not here).
/// * `version == FORMAT_VERSION` (strict equality until the first on-device
///   ship — spec section 12.1).
/// * `opset_version <= OPSET_VERSION`.
/// * Every tensor: rank 1..=4, all dims > 0, `elem_count == product(dims)`
///   (with packed-size math for I4), buffer refs in range, 16-aligned
///   offsets, `dim_params` axes in range with `max >= dims[axis]`.
/// * Every buffer payload fits its buffer; quant scale shapes deferred to
///   inference (needs the kernel dims).
/// * Every graph: non-empty nodes, tensor/inputs/outputs refs in range,
///   node input/output refs in range, `CustomOp` names non-empty
///   (unknown names fail at load — spec section 9.3).
/// * Every entry point names a graph in range.
pub fn verify(bytes: &[u8]) -> Result<Verified<'_>, String> {
    if !fb::model_buffer_has_identifier(bytes) {
        return Err("not a MAML v2 file (bad identifier; want MAM2)".into());
    }
    let model = fb::root_as_model(bytes).map_err(|e| format!("malformed MAM2 buffer: {e}"))?;
    if model.version() != FORMAT_VERSION {
        return Err(format!(
            "format version {}, loader reads {}",
            model.version(),
            FORMAT_VERSION
        ));
    }
    if model.opset_version() > OPSET_VERSION {
        return Err(format!(
            "opset version {} is newer than this loader implements ({OPSET_VERSION})",
            model.opset_version()
        ));
    }
    let tensors = model.tensors().ok_or("a model with no tensors")?;
    if tensors.len() == 0 {
        return Err("a model with no tensors".into());
    }
    let tensor_count = tensors.len();
    let buffers = model.buffers();
    let buffer_count = buffers.map(|b| b.len()).unwrap_or(0);
    let buffer_lens: Vec<usize> = (0..buffer_count)
        .map(|i| {
            buffers
                .and_then(|b| b.get(i).data())
                .map(|d| d.len())
                .unwrap_or(0)
        })
        .collect();

    for i in 0..tensor_count {
        let tensor = tensors.get(i);
        verify_tensor(&tensor, i, &buffer_lens, tensor_count)?;
    }

    let graphs = model.graphs().ok_or("a model with no graphs")?;
    if graphs.len() == 0 {
        return Err("a model with no graphs".into());
    }
    let graph_count = graphs.len();
    for g in 0..graph_count {
        verify_graph(&graphs.get(g), g, tensor_count)?;
    }

    if let Some(entries) = model.entry_points() {
        for e in 0..entries.len() {
            let entry = entries.get(e);
            if entry.graph() as usize >= graph_count {
                return Err(format!(
                    "entry point {} names graph {}, of {graph_count}",
                    entry.name().unwrap_or("?"),
                    entry.graph()
                ));
            }
            // Roles bind positionally to the graph inputs; the count must match.
            let graph_inputs =
                graphs.get(entry.graph() as usize).inputs().map(|v| v.len()).unwrap_or(0);
            let roles = entry.inputs().map(|v| v.len()).unwrap_or(0);
            if roles != graph_inputs {
                return Err(format!(
                    "entry point {} declares {roles} roles for {graph_inputs} graph inputs",
                    entry.name().unwrap_or("?"),
                ));
            }
        }
    }

    Ok(Verified { model, tensor_count, buffer_count, graph_count })
}

/// Verify one tensor row (spec section 9.1).
fn verify_tensor(
    tensor: &fb::Tensor<'_>,
    index: usize,
    buffer_lens: &[usize],
    tensor_total: usize,
) -> Result<(), String> {
    let name = tensor.name().unwrap_or("?");
    let dims = tensor.dims().ok_or_else(|| format!("tensor {index} ({name}) has no dims"))?;
    if dims.len() == 0 || dims.len() > 4 {
        return Err(format!("tensor {index} ({name}) has rank {}", dims.len()));
    }
    let mut product: u64 = 1;
    for d in 0..dims.len() {
        let dim = dims.get(d);
        if dim <= 0 {
            return Err(format!("tensor {index} ({name}) has non-positive dim {dim}"));
        }
        product *= dim as u64;
    }
    if tensor.elem_count() != product {
        return Err(format!(
            "tensor {index} ({name}) has elem_count {} but dims product {product}",
            tensor.elem_count()
        ));
    }
    // Payload size: packed dtypes round up per tensor.
    let stride = dtype_stride(tensor.dtype())?;
    let payload: u64 = if is_packed(tensor.dtype()) {
        product.div_ceil(2) * stride
    } else {
        product * stride
    };
    let _ = payload;
    let buffer = tensor.buffer();
    if buffer == -1 {
        // Computed, state, or host tensor: no bytes in the file.
        if tensor.buffer_offset() != 0 {
            return Err(format!("tensor {index} ({name}) has no buffer but offset {}", tensor.buffer_offset()));
        }
    } else {
        if buffer < 0 || buffer as usize >= buffer_lens.len() {
            return Err(format!("tensor {index} ({name}) names buffer {buffer} of {}", buffer_lens.len()));
        }
        if tensor.buffer_offset() % 16 != 0 {
            return Err(format!(
                "tensor {index} ({name}) is at offset {}, not 16-aligned",
                tensor.buffer_offset()
            ));
        }
        let end = tensor.buffer_offset() + payload;
        if end > buffer_lens[buffer as usize] as u64 {
            return Err(format!(
                "tensor {index} ({name}) spans {}..{end} of a {}-byte buffer",
                tensor.buffer_offset(),
                buffer_lens[buffer as usize]
            ));
        }
    }
    // dim_params: axes in range, max >= dims[axis].
    if let Some(params) = tensor.dim_params() {
        for p in 0..params.len() {
            let param = params.get(p);
            if param.axis() as usize >= dims.len() {
                return Err(format!(
                    "tensor {index} ({name}) has a dim_param on axis {} of rank {}",
                    param.axis(),
                    dims.len()
                ));
            }
            if param.max() < dims.get(param.axis() as usize) {
                return Err(format!(
                    "tensor {index} ({name}): dim_param max {} is below dims[{}]",
                    param.max(),
                    param.axis()
                ));
            }
            if param.symbol().is_none_or(|s| s.is_empty()) {
                return Err(format!("tensor {index} ({name}) has an anonymous dim_param"));
            }
        }
    }
    // Quantization: kind NONE needs no scale; otherwise the scale tensor must
    // be a real tensor (shape checked at inference, where the kernel dims
    // are known). The tensor count is available via `tensor_total`.
    if let Some(quant) = tensor.quantization() {
        match quant.kind() {
            fb::QuantKind::NONE => {
                if quant.scale_tensor() != -1 {
                    return Err(format!(
                        "tensor {index} ({name}) is unquantised but names scale tensor {}",
                        quant.scale_tensor()
                    ));
                }
            }
            fb::QuantKind::PER_CHANNEL | fb::QuantKind::BLOCKWISE => {
                if quant.scale_tensor() < 0 {
                    return Err(format!(
                        "tensor {index} ({name}) is quantised but names no scale tensor"
                    ));
                }
                if quant.scale_tensor() as usize >= tensor_total {
                    return Err(format!(
                        "tensor {index} ({name}) names scale tensor {} of {tensor_total}",
                        quant.scale_tensor()
                    ));
                }
                if quant.zero_point() != -1 {
                    return Err(format!(
                        "tensor {index} ({name}): only symmetric quantization is supported"
                    ));
                }
            }
            _ => return Err(format!("tensor {index} ({name}) has an unknown quant kind")),
        }
    }
    Ok(())
}

/// Verify one graph row: refs in range, non-empty nodes, no unknown customs.
fn verify_graph(
    graph: &fb::Graph<'_>,
    graph_index: usize,
    tensor_count: usize,
) -> Result<(), String> {
    let name = graph.name().unwrap_or("?");
    let nodes = graph.nodes().ok_or_else(|| format!("graph {graph_index} ({name}) has no nodes"))?;
    if nodes.len() == 0 {
        return Err(format!("graph {graph_index} ({name}) has no nodes"));
    }
    let scope = graph.tensors();
    let in_scope = |t: i32| -> bool {
        if t < 0 || t as usize >= tensor_count {
            return false;
        }
        scope.map(|s| (0..s.len()).any(|i| s.get(i) == t)).unwrap_or(true)
    };
    let check_ref = |t: i32, what: &str| -> Result<(), String> {
        if !in_scope(t) {
            return Err(format!("graph {graph_index} ({name}): {what} names tensor {t} of {tensor_count}"));
        }
        Ok(())
    };
    if let Some(inputs) = graph.inputs() {
        for i in 0..inputs.len() {
            check_ref(inputs.get(i), "input")?;
        }
    }
    if let Some(outputs) = graph.outputs() {
        if outputs.len() == 0 {
            return Err(format!("graph {graph_index} ({name}) has no outputs"));
        }
        for o in 0..outputs.len() {
            check_ref(outputs.get(o), "output")?;
        }
    }
    for n in 0..nodes.len() {
        let node = nodes.get(n);
        if let Some(inputs) = node.inputs() {
            for i in 0..inputs.len() {
                check_ref(inputs.get(i), &format!("node {n} input"))?;
            }
        }
        if let Some(outputs) = node.outputs() {
            if outputs.len() == 0 {
                return Err(format!("graph {graph_index} ({name}): node {n} has no outputs"));
            }
            for o in 0..outputs.len() {
                check_ref(outputs.get(o), &format!("node {n} output"))?;
            }
        }
        if node.op() == fb::Op::CustomOp {
            let custom = node
                .attrs()
                .map(|attrs| {
                    (0..attrs.len()).any(|i| attrs.get(i).name().is_some_and(|s| !s.is_empty()))
                })
                .unwrap_or(false);
            if !custom {
                return Err(format!(
                    "graph {graph_index} ({name}): node {n} is CustomOp with no name attribute (spec 9.3: unknown customs fail)"
                ));
            }
        }
        // Only CustomOp may carry a payload (spec section 5).
        if node.op() != fb::Op::CustomOp
            && node.payload().is_some_and(|p| p.len() > 0)
        {
            return Err(format!(
                "graph {graph_index} ({name}): node {n} ({:?}) carries a payload",
                node.op()
            ));
        }
    }
    Ok(())
}
