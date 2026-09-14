    fn kv_stride(p: &Push, head_dim: u32) -> u32 {
        let kv = if p.kv_heads == 0 { p.group } else { p.kv_heads };
        kv * head_dim
    }

    /// Clamp to a range held in the weights. See `shaders/clamp.comp`.
    fn clamp(&mut self, p: &Push) -> Result<(), String> {
        let low = self.weight(p.act_weight, 0)?;
        let high = self.weight(p.act_weight, 1)?;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, value.clamp(low, high))?;
        }
        Ok(())
    }

    /// Multiply by a scalar held in the weights. See `shaders/mul_scalar.comp`.
    fn mul_scalar(&mut self, p: &Push) -> Result<(), String> {
        let scale = self.weight(p.act_weight, 0)?;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, value * scale)?;
        }
        Ok(())
    }

    /// An activation on its own. See `shaders/activate.comp`.
    /// `activate(gate) * up`, the fused form. See `shaders/gated_activate.comp`.
    fn gated_activate(&mut self, p: &Push) -> Result<(), String> {
        let spatial = (p.out_h * p.out_w).max(1);
        for index in 0..p.count {
            let gate = self.load(p.in0, index)?;
            // `in_c` is the whole gate half, so this is the same element in the up half.
            let up = self.load(p.in0 + p.in_c, index)?;
            let slope = self.weight(p.act_weight, index / spatial).unwrap_or(0.0);
            self.store(p.out, index, activate(gate, p.act, slope) * up)?;
        }
        Ok(())
    }
    fn activate(&mut self, p: &Push) -> Result<(), String> {
        let spatial = (p.out_h * p.out_w).max(1);
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            // The free function, not this method. `PRelu` would need a slope per channel and is
            // not offered here - see `Kind::Activate`.
            let slope = self.weight(p.act_weight, index / spatial).unwrap_or(0.0);
            self.store(p.out, index, activate(value, p.act, slope))?;
        }
        Ok(())
    }

    /// `tanh(x / cap) * cap`. See `shaders/softcap.comp`.
    fn softcap(&mut self, p: &Push) -> Result<(), String> {
        let cap = f32::from_bits(p.param0_bits);
        if !(cap > 0.0) {
            return Err(format!("a softcap of {cap}"));
        }
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, index, (value / cap).tanh() * cap)?;
        }
        Ok(())
    }

    /// Copy one position into a KV cache at the step's prefix. See `shaders/cache_write.comp`.
    ///
    /// The one op whose destination depends on the step rather than the recording, which is why
    /// the interpreter has to know the prefix at all.
    fn cache_write(&mut self, p: &Push) -> Result<(), String> {
        // Mirrors the transpose in `shaders/cache_write.comp`. See there for why it is here.
        let positions = p.group.max(1);
        if positions > 1 {
            for index in 0..p.count {
                let channel = index / positions;
                let position = index % positions;
                if self.prefix + position >= p.in_h {
                    continue;
                }
                let value = self.load(p.in0, index)?;
                self.store(p.out, (self.prefix + position) * p.in_c + channel, value)?;
            }
            return Ok(());
        }
        // Past the end writes nothing, matching the shader. The caller stops before this; a
        // silent drop is a better failure than a store outside the cache.
        if self.prefix >= p.in_h {
            return Ok(());
        }
        let base = self.prefix * p.in_c;
        for index in 0..p.count {
            let value = self.load(p.in0, index)?;
            self.store(p.out, base + index, value)?;
        }
        Ok(())
    }

    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`.
    ///
    /// `S` is `[heads, T, T]` at `in0`; `V` and the output are `[d_model, 1, T]`. The
    /// head index picks a plane of `S` and nothing else — channel `c` of `V` is channel
    /// `c` of the output — which is why concatenating the heads afterwards is not an op.
    fn attn_apply(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        // `out_w` is the query count and `in_w` the key count; equal for self-attention.
        let (queries, keys) = (p.out_w, p.in_w);
        for channel in 0..p.out_c {
            let head = channel / head_dim;
            let row = head * queries * keys;
            // V's channel for this query head, shared under multi-query attention.
            let value = Self::kv_head_of(p, head) * head_dim + (channel % head_dim);
            for query in 0..queries {
                let mut total = 0.0;
                for key in 0..keys {
                    total += self.load(p.in0, row + query * keys + key)?
                        * self.load(p.in1, value * keys + key)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// [`Self::attn_scores`] for one query against a **position-major** K cache.
    ///
    /// The only difference from [`Self::attn_scores`] is the cache's indexing: a key is a run of
    /// `in_c` elements rather than a channel every `key_stride` apart, which is what makes
    /// appending a position one contiguous copy. `out_w` is the key count, and there is exactly one
    /// query, so the score map is `[heads, 1, keys]` and `softmax` normalises it unchanged.
    fn attn_scores_cached(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let scale = f32::from_bits(p.param0_bits);
        let (first, last) = self.attended(p, p.out_w);
        let stride = Self::kv_stride(p, head_dim);
        for head in 0..p.group {
            // Q is one position, so its channels are consecutive.
            let query_base = head * head_dim;
            let kv_base = Self::kv_head_of(p, head) * head_dim;
            for key in first..=last {
                let key_base = key * stride + kv_base;
                let mut total = 0.0;
                for d in 0..head_dim {
                    total +=
                        self.load(p.in0, query_base + d)? * self.load(p.in1, key_base + d)?;
                }
                self.store(p.out, nchw(p, head, 0, key), total * scale)?;
            }
        }
        Ok(())
    }

    /// [`Self::attn_apply`] for one query against a **position-major** V cache.
    ///
    /// The output is `[d_model, 1, 1]`, back in the channel-major layout the next projection
    /// reads, so the cache layout is confined to the operand that is a cache. `in_w` is the key
    /// count, which in this layout is the cache's channel count.
    fn attn_apply_cached(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let stride = p.in_w;
        let (first, last) = self.attended(p, stride);
        let vstride = Self::kv_stride(p, head_dim);
        for channel in 0..p.out_c {
            let head = channel / head_dim;
            // One query, so a head's row of probabilities starts a full stride apart even when
            // this step attends over fewer keys than the row can hold.
            let row = head * stride;
            let value = Self::kv_head_of(p, head) * head_dim + (channel % head_dim);
            let mut total = 0.0;
            for key in first..=last {
                total += self.load(p.in0, row + key)?
                    * self.load(p.in1, value + key * vstride)?;
            }
            self.store(p.out, nchw(p, channel, 0, 0), total)?;
        }
        Ok(())
    }

    /// [`Self::attn_scores`] plus a relative-position term: nine taps of a learned
    /// table indexed by `key - query`, in place of the export's product-and-skew.
    ///
    /// The scale multiplies both terms, because the export divides the query by
    /// `sqrt(head_dim)` before either product.
    fn attn_scores_relative(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let stride = p.in_h * p.in_w;
        let scale = f32::from_bits(p.param0_bits);
        let window = (p.kw.max(1) - 1) as i64 / 2;
        for head in 0..p.group {
            let base = head * head_dim * stride;
            for query in 0..p.out_h {
                for key in 0..p.out_w {
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        let channel = base + d * stride;
                        total += self.load(p.in0, channel + query)?
                            * self.load(p.in1, channel + key)?;
                    }
                    let offset = key as i64 - query as i64;
                    if offset.abs() <= window {
                        let row = (offset + window) as u32 * head_dim;
                        for d in 0..head_dim {
                            total += self.load(p.in0, base + d * stride + query)?
                                * self.weight(p.weight, row + d)?;
                        }
                    }
                    self.store(p.out, nchw(p, head, query, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// [`Self::attn_apply`] plus the value-side relative term. Taps outside the sequence
    /// are skipped rather than clamped, which would double-count a neighbour at the ends.
    fn attn_apply_relative(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let keys = p.out_w;
        let window = (p.kw.max(1) - 1) as i64 / 2;
        for channel in 0..p.out_c {
            let row = (channel / head_dim) * keys * keys;
            let depth = channel % head_dim;
            for query in 0..keys {
                let mut total = 0.0;
                for key in 0..keys {
                    total += self.load(p.in0, row + query * keys + key)?
                        * self.load(p.in1, channel * keys + key)?;
                }
                for offset in -window..=window {
                    let key = query as i64 + offset;
                    if key < 0 || key >= keys as i64 {
                        continue;
                    }
                    let entry = (offset + window) as u32 * head_dim + depth;
                    total += self.load(p.in0, row + query * keys + key as u32)?
                        * self.weight(p.weight, entry)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// [`Kind::AttnScoresBanded`]. The guard mirrors `nets::gemma4_audio::band_key` and this
    /// arm calls it rather than restating it — four rearrangements of that condition have been
    /// proposed and been wrong, so the oracle and the shader share one definition on purpose.
    fn attn_scores_banded(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        let stride = p.in_h * p.in_w;
        let scale = f32::from_bits(p.param0_bits);
        let cap = f32::from_bits(p.param1_bits);
        if cap <= 0.0 {
            return Err(format!("a banded score map with a logit cap of {cap}"));
        }
        let band = p.kh;
        for head in 0..p.group {
            let base = head * head_dim * stride;
            for query in 0..p.out_h {
                for column in 0..band {
                    let slot = nchw(p, head, query, column);
                    // `None` gates the LOAD, not just the stored value. Parameterised by the op's
                    // own band, which the shader reads from `Push::kh` - calling the
                    // `ATTEND_SPAN` form here would agree only at band 12.
                    let Some(key) = crate::nets::gemma4_audio::band_key_in(band, query, column)
                    else {
                        self.store(p.out, slot, crate::nets::gemma4_audio::MASK_FILL)?;
                        continue;
                    };
                    if key >= p.in_w {
                        return Err(format!(
                            "banded score at query {query} column {column} reads key {key} of \
                             {} - right context is zero, so this cannot happen",
                            p.in_w
                        ));
                    }
                    // Column `j` reads relative offset `j + 1`; see `gemma4_audio::rel_column`.
                    let row = (head * p.kw + column + 1) * head_dim;
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        let channel = base + d * stride;
                        let q = self.load(p.in0, channel + query)?;
                        total += q * self.load(p.in1, channel + key)?;
                        total += q * self.weight(p.weight, row + d)?;
                    }
                    total *= scale;
                    self.store(p.out, slot, (total / cap).tanh() * cap)?;
                }
            }
        }
        Ok(())
    }

    /// [`Kind::AttnApplyBanded`]. Columns before the sequence are skipped rather than read,
    /// which is exact because the softmax has already given them zero weight.
    fn attn_apply_banded(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.out_c)?;
        let keys = p.out_w;
        let band = p.kh;
        for channel in 0..p.out_c {
            let row = (channel / head_dim) * keys * band;
            for query in 0..keys {
                let mut total = 0.0;
                for column in 0..band {
                    let Some(key) = crate::nets::gemma4_audio::band_key_in(band, query, column)
                    else {
                        continue;
                    };
                    total += self.load(p.in0, row + query * band + column)?
                        * self.load(p.in1, channel * keys + key)?;
                }
                self.store(p.out, nchw(p, channel, 0, query), total)?;
            }
        }
        Ok(())
    }

    /// `out = a * b`, elementwise over two equal shapes.
    fn mul(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let product = self.load(p.in0, i)? * self.load(p.in1, i)?;
            self.store(p.out, i, product)?;
        }
        Ok(())
    }

    /// `out[c][y][x] = a[c][y][x] * b[c]`, the excite half of a squeeze-excite block.
    fn mul_broadcast(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let gate = self.load(p.in1, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let index = nchw(p, oc, oy, ox);
                    let value = self.load(p.in0, index)?;
                    self.store(p.out, index, value * gate)?;
                }
            }
        }
        Ok(())
    }
}

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
