
/// `channels / group`, the head dimension, refusing the split the builder should have
/// caught.
fn heads(p: &Push, channels: u32) -> Result<u32, String> {
    if p.group == 0 || !channels.is_multiple_of(p.group) {
        return Err(format!("{channels} channels do not split into {} heads", p.group));
    }
    let head_dim = channels / p.group;
    if head_dim == 0 {
        return Err(format!("{} heads for {channels} channels", p.group));
    }
    Ok(head_dim)
}

/// `(in_c / group, out_c / group)`, refusing the divisions the builder should have
/// caught.
fn groups(p: &Push) -> Result<(u32, u32), String> {
    if p.group == 0 {
        return Err("a convolution in zero groups".into());
    }
    if !p.in_c.is_multiple_of(p.group) || !p.out_c.is_multiple_of(p.group) {
        return Err(format!(
            "{} in / {} out channels do not split into {} groups",
            p.in_c, p.out_c, p.group
        ));
    }
    let out_per_group = p.out_c / p.group;
    if out_per_group == 0 {
        return Err(format!("{} groups for {} output channels", p.group, p.out_c));
    }
    Ok((p.in_c / p.group, out_per_group))
}

/// The input coordinate a kernel tap reads, or `None` when it falls in the padding.
fn tap(positioned: u32, pad: u32, extent: u32) -> Option<u32> {
    let coordinate = positioned.checked_sub(pad)?;
    (coordinate < extent).then_some(coordinate)
}

/// As [`tap`], but replicating the border instead of falling off it.
///
/// Always lands, which is the point: an edge-padded convolution has no skipped taps. See
/// [`super::Push::pad_edge`].
fn tap_edge(positioned: u32, pad: u32, extent: u32) -> u32 {
    let last = extent.saturating_sub(1);
    match positioned.checked_sub(pad) {
        Some(coordinate) => coordinate.min(last),
        None => 0,
    }
}

/// The input coordinate feeding a transposed convolution's output, or `None` when this
/// tap does not land on one: `(padded - k)` must be non-negative and a whole number of
/// strides.
fn source(padded: u32, k: u32, stride: u32, extent: u32) -> Option<u32> {
    let numerator = padded.checked_sub(k)?;
    if stride == 0 || !numerator.is_multiple_of(stride) {
        return None;
    }
    let coordinate = numerator / stride;
    (coordinate < extent).then_some(coordinate)
}

/// The two source indices bracketing `dst` and the weight of the second, at
/// `half_pixel`. Coordinates outside the source clamp to the edge.
fn bracket(dst: u32, in_extent: u32, out_extent: u32) -> Result<(u32, u32, f32), String> {
    if in_extent == 0 || out_extent == 0 {
        return Err("a resize of a zero extent".into());
    }
    let source =
        ((dst as f32 + 0.5) * in_extent as f32 / out_extent as f32 - 0.5).max(0.0);
    let base = source.floor();
    let low = (base as u32).min(in_extent - 1);
    Ok((low, (low + 1).min(in_extent - 1), source - base))
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// A flat output index, in the NCHW order every shader's `unpack` assumes.
fn nchw(p: &Push, c: u32, y: u32, x: u32) -> u32 {
    (c * p.out_h + y) * p.out_w + x
}

/// Lay tensors out the way `.maml` does: 16-byte aligned, fp16, in index order.
fn pack(tensors: &[(Vec<u32>, Vec<f32>)]) -> Result<(Vec<u32>, Vec<u8>), String> {
    let mut offsets = Vec::with_capacity(tensors.len());
    let mut data: Vec<u8> = Vec::new();
    for (dims, values) in tensors {
        while !data.len().is_multiple_of(16) {
            data.push(0);
        }
        let declared: u64 = dims.iter().map(|&d| d as u64).product();
        if declared != values.len() as u64 {
            return Err(format!("{dims:?} declares {declared} values, got {}", values.len()));
        }
        offsets.push((data.len() / 2) as u32);
        for &value in values {
            data.extend_from_slice(&f32_to_f16(value).to_le_bytes());
        }
    }
    Ok((offsets, data))
}

/// A [`WeightSource`] over tensors given explicitly, for the per-op fixtures.
///
/// Unlike [`super::tests::Shapes`], which hands back the tensor index as a stand-in
/// offset, this lays the tensors out for real — 16-byte aligned fp16 in index order,
/// exactly as `maml_convert.py` writes them — so a plan built against it can actually
/// be run.
pub struct Given {
    offsets: Vec<u32>,
    lengths: Vec<u64>,
    data: Vec<u8>,
}

impl Given {
    /// Lay out `(dims, values)` pairs in `.maml` index order.
    pub fn new(tensors: &[(Vec<u32>, Vec<f32>)]) -> Result<Given, String> {
        let (offsets, data) = pack(tensors)?;
        let lengths = tensors.iter().map(|(_, v)| v.len() as u64).collect();
        Ok(Given { offsets, lengths, data })
    }

    /// The blob to hand [`run`].
    pub fn data(&self) -> &[u8] {
        &self.data
    }
}

impl WeightSource for Given {
    /// Int8 is not exercised by the host fixtures: the reference interpreter reads its
    /// weights as `f32`, so there is nothing for a byte view to be a view *of*. The int8
    /// path is checked against the export by `scripts/ml/onnx_parity.py` instead.
    fn shaped_words(&self, index: usize, _dims: &[u32]) -> Result<u32, String> {
        Err(format!("tensor {index}: this fixture holds no int8"))
    }

    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let declared: u64 = dims.iter().map(|&d| d as u64).product();
        let length = *self
            .lengths
            .get(index)
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.lengths.len()))?;
        if declared != length {
            return Err(format!(
                "tensor {index} holds {length} values, the forward pass wants {dims:?}"
            ));
        }
        self.offsets
            .get(index)
            .copied()
            .ok_or_else(|| format!("tensor {index} has no offset"))
    }

    fn count(&self) -> usize {
        self.lengths.len()
    }
}

/// A [`WeightSource`] that invents weights for whatever shapes it is asked for, so a
/// whole net can be run without its asset.
///
/// Values are deterministic and scaled by `1 / sqrt(fan_in)`, which matters more than
/// it looks: uniform random weights through 119 layers either saturate fp16 or decay to
/// zero, and either way an end-to-end run proves nothing. Kaiming-style scaling keeps
/// activations near unit variance all the way down, so "the output is finite and in
/// range" becomes a real statement about the plan.
///
/// Biases are zero. A bias is one value per output channel and contributes no
/// indexing that the weights do not already cover.
pub struct Invented {
    count: usize,
    state: RefCell<Laid>,
}

/// What [`Invented`] has handed out so far.
struct Laid {
    /// Element offset per tensor index, once asked for.
    offsets: Vec<Option<u32>>,
    data: Vec<u8>,
}

impl Invented {
    /// A source that expects to be asked for exactly `count` tensors.
    pub fn new(count: usize) -> Invented {
        Invented {
            count,
            state: RefCell::new(Laid { offsets: vec![None; count], data: Vec::new() }),
        }
    }

    /// The blob to hand [`run`], after the net has been built against this.
    pub fn into_data(self) -> Vec<u8> {
        self.state.into_inner().data
    }
}

impl WeightSource for Invented {
    /// As [`Given`]: int8 has nothing to mean here, since this fixture invents `f32`.
    fn shaped_words(&self, index: usize, _dims: &[u32]) -> Result<u32, String> {
        Err(format!("tensor {index}: this fixture invents fp32, not int8"))
    }

    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let mut state = self.state.borrow_mut();
        if let Some(Some(offset)) = state.offsets.get(index).copied() {
            return Ok(offset);
        }
        let elements: u64 = dims.iter().map(|&d| d as u64).product();
        // A rank-1 tensor is a bias; anything else is a kernel `[m, k, kh, kw]` whose
        // fan-in is everything but the first dimension.
        let fan_in: u64 = dims.iter().skip(1).map(|&d| d as u64).product();
        let scale = if dims.len() == 1 { 0.0 } else { 1.0 / (fan_in.max(1) as f32).sqrt() };

        while !state.data.len().is_multiple_of(16) {
            state.data.push(0);
        }
        let offset = (state.data.len() / 2) as u32;
        let mut random = seed(index);
        for _ in 0..elements {
            let value = uniform(&mut random) * scale;
            state.data.extend_from_slice(&f32_to_f16(value).to_le_bytes());
        }
        *state
            .offsets
            .get_mut(index)
            .ok_or_else(|| format!("tensor {index} of {}: out of range", self.count))? =
            Some(offset);
        Ok(offset)
    }

    fn count(&self) -> usize {
        self.count
    }
}

/// A non-zero xorshift seed derived from a tensor index, so each tensor's values are
/// reproducible on their own rather than dependent on the order tensors were asked for.
fn seed(index: usize) -> u32 {
    (index as u32).wrapping_mul(2_654_435_761).wrapping_add(0x9e37_79b9) | 1
}

/// xorshift32, mapped to `-1..1`.
fn uniform(state: &mut u32) -> f32 {
    *state ^= *state << 13;
    *state ^= *state >> 17;
    *state ^= *state << 5;
    // The top 24 bits, so the quotient is exact in fp32 and lands in `0..1`.
    (*state >> 8) as f32 / (1u32 << 24) as f32 * 2.0 - 1.0
}
