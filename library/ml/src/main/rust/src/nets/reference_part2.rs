                    for ky in 0..p.kh {
                        let iy = oy * p.stride_h + ky;
                        if iy >= p.in_h {
                            continue;
                        }
                        for kx in 0..p.kw {
                            let ix = ox * p.stride_w + kx;
                            if ix >= p.in_w {
                                continue;
                            }
                            total += self.load(p.in0, plane + iy * p.in_w + ix)?;
                        }
                    }
                    self.store(p.out, nchw(p, oc, oy, ox), total / window as f32)?;
                }
            }
        }
        Ok(())
    }

    /// Bilinear resize under the `half_pixel` convention, `src = (dst + 0.5) * in / out - 0.5`.
    ///
    /// The same formula [`crate::preprocess`] uses for the input resize. That
    /// agreement is load-bearing: U^2-Netp's decoder upsamples and then adds an
    /// encoder skip five times, so a half-pixel disagreement between the two paths
    /// would misregister every one of those residuals.
    fn resize(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let (y0, y1, ty) = bracket(oy, p.in_h, p.out_h)?;
                for ox in 0..p.out_w {
                    let (x0, x1, tx) = bracket(ox, p.in_w, p.out_w)?;
                    let row0 = plane + y0 * p.in_w;
                    let row1 = plane + y1 * p.in_w;
                    let top = lerp(self.load(p.in0, row0 + x0)?, self.load(p.in0, row0 + x1)?, tx);
                    let bottom =
                        lerp(self.load(p.in0, row1 + x0)?, self.load(p.in0, row1 + x1)?, tx);
                    self.store(p.out, nchw(p, oc, oy, ox), lerp(top, bottom, ty))?;
                }
            }
        }
        Ok(())
    }

    /// Nearest-neighbour resize, ONNX `asymmetric` coordinates with `floor` rounding:
    /// `src = floor(dst * in / out)`, which in integers is an exact division.
    ///
    /// SCRFD's feature pyramid, and deliberately a different function from
    /// [`Reference::resize`] rather than a mode flag — the two agree nowhere.
    fn resize_nearest(&mut self, p: &Push) -> Result<(), String> {
        if p.out_h == 0 || p.out_w == 0 || p.in_h == 0 || p.in_w == 0 {
            return Err("a resize of a zero extent".into());
        }
        for oc in 0..p.out_c {
            let plane = oc * p.in_h * p.in_w;
            for oy in 0..p.out_h {
                let iy = (oy * p.in_h / p.out_h).min(p.in_h - 1);
                for ox in 0..p.out_w {
                    let ix = (ox * p.in_w / p.out_w).min(p.in_w - 1);
                    let value = self.load(p.in0, plane + iy * p.in_w + ix)?;
                    self.store(p.out, nchw(p, oc, oy, ox), value)?;
                }
            }
        }
        Ok(())
    }

    /// Mean over H and W, to `C x 1 x 1`. ONNX `ReduceMean` over axes `[2, 3]`.
    fn global_avg_pool(&mut self, p: &Push) -> Result<(), String> {
        let elements = p.in_h * p.in_w;
        if elements == 0 {
            return Err("a global average pool over nothing".into());
        }
        for channel in 0..p.out_c {
            let plane = channel * elements;
            let mut total = 0.0;
            for i in 0..elements {
                total += self.load(p.in0, plane + i)?;
            }
            self.store(p.out, channel, total / elements as f32)?;
        }
        Ok(())
    }

    /// `out[o][p] = act(sum_i W[o][i] * in[i][p] + bias[o])`, a `1 x 1` convolution.
    ///
    /// The same arithmetic as [`Self::conv`] at kernel `1 x 1`, written separately because the
    /// device shader tiles it and carries a **tile** count in `push.count` rather than an
    /// element count. Every `1 x 1` in every net module now routes here, so the existing
    /// fixtures cover it.
    fn conv_point(&mut self, p: &Push) -> Result<(), String> {
        let positions = p.out_h * p.out_w;
        for channel in 0..p.out_c {
            let bias = self.weight(p.bias, channel)?;
            let shift = self.fused_shift(p, channel)?;
            for position in 0..positions {
                let mut total = 0.0f32;
                for input in 0..p.in_c {
                    total += self.weight(p.weight, channel * p.in_c + input)?
                        * self.load(p.in0, input * positions + position)?;
                }
                // `Builder::conv` refuses to route a PRelu here, so the slope is never read.
                let index = channel * positions + position;
                let folded =
                    activate(total + bias, p.act, 0.0) + self.fused_res(p, index)? + shift;
                self.store(p.out, index, folded)?;
            }
        }
        Ok(())
    }

    /// `out[c][t] = table[id(t)][c]`, with the id read out of the arena as fp16, in one lane
    /// or in two (`lo + 2048 * hi`) for a table past `EMBED_LANE` rows.
    fn embed(&mut self, p: &Push) -> Result<(), String> {
        let rows = p.in_w;
        if rows == 0 {
            return Err("an embedding table with no rows".into());
        }
        for position in 0..p.out_w {
            let mut raw = self.load(p.in0, position)?;
            if p.in_c == 2 {
                raw += 2048.0 * self.load(p.in0, p.out_w + position)?;
            }
            // Round rather than truncate, and clamp: an unknown symbol should mispronounce
            // a word rather than read past the table.
            let id = ((raw + 0.5).max(0.0) as u32).min(rows - 1);
            for channel in 0..p.out_c {
                let value = self.weight(p.weight, id * p.out_c + channel)?;
                self.store(p.out, nchw(p, channel, 0, position), value)?;
            }
        }
        Ok(())
    }

    /// `out[i] = weights[i]`, a learned tensor copied into the arena. See [`Kind::Constant`].
    fn constant(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let value = self.weight(p.weight, i)?;
            self.store(p.out, i, value)?;
        }
        Ok(())
    }

    /// `out[c][y][x] = a[c][y][x] + b[c]`, a per-channel shift.
    fn add_broadcast(&mut self, p: &Push) -> Result<(), String> {
        for oc in 0..p.out_c {
            let shift = self.load(p.in1, oc)?;
            for oy in 0..p.out_h {
                for ox in 0..p.out_w {
                    let at = nchw(p, oc, oy, ox);
                    let value = self.load(p.in0, at)?;
                    self.store(p.out, at, value + shift)?;
                }
            }
        }
        Ok(())
    }

    /// Rotary position embedding, the half-split convention. See [`Kind::Rotary`].
    fn rotary(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = p.in_c;
        // Independent rotary blocks inside one head; 1 is ordinary RoPE. See `Push::rope_axes`.
        let axes = p.rope_axes.max(1);
        if head_dim == 0 || !head_dim.is_multiple_of(axes) {
            return Err(format!("a rotary head of {head_dim} channels in {axes} blocks"));
        }
        let block = head_dim / axes;
        if block == 0 || !block.is_multiple_of(2) {
            return Err(format!("a rotary block of {block} channels"));
        }
        let half = block / 2;
        for channel in 0..p.out_c {
            let within = channel % head_dim;
            let sub = within / block;
            let local = within % block;
            let frequency = local % half;
            let lower = local < half;
            let partner = if lower { channel + half } else { channel - half };
            let base = sub * block;
            for position in 0..p.out_w {
                let angle_cos = self.load(p.in1, (base + frequency) * p.out_w + position)?;
                let angle_sin =
                    self.load(p.in1, (base + half + frequency) * p.out_w + position)?;
                let self_value = self.load(p.in0, channel * p.out_w + position)?;
                let other = self.load(p.in0, partner * p.out_w + position)?;
                let rotated = if lower { -other } else { other } * angle_sin;
                self.store(p.out, channel * p.out_w + position, self_value * angle_cos + rotated)?;
            }
        }
        Ok(())
    }

    /// `out = in * scale + shift`, both scalars carried as raw bits in the push block.
    fn affine(&mut self, p: &Push) -> Result<(), String> {
        let scale = f32::from_bits(p.param0_bits);
        let shift = f32::from_bits(p.param1_bits);
        for i in 0..p.count {
            let value = self.load(p.in0, i)?;
            self.store(p.out, i, value * scale + shift)?;
        }
        Ok(())
    }

    /// Layer normalisation over the channel axis, per spatial position.
    ///
    /// Biased variance, dividing by `C` rather than `C - 1`, which is what every
    /// framework does at inference. `count` is the spatial extent rather than the element
    /// count, because one invocation handles a whole column of channels.
    fn layer_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("a layer norm over zero channels".into());
        }
        let stride = p.in_h * p.in_w;
        let epsilon = f32::from_bits(p.param1_bits);
        for position in 0..p.count {
            let mut total = 0.0;
            for c in 0..p.in_c {
                total += self.load(p.in0, c * stride + position)?;
            }
            let mean = total / p.in_c as f32;

            let mut variance = 0.0;
            for c in 0..p.in_c {
                let centred = self.load(p.in0, c * stride + position)? - mean;
                variance += centred * centred;
            }
            let inverse = 1.0 / (variance / p.in_c as f32 + epsilon).sqrt();

            for c in 0..p.in_c {
                let at = c * stride + position;
                let centred = self.load(p.in0, at)? - mean;
                let gamma = self.weight(p.weight, c)?;
                let beta = self.weight(p.bias, c)?;
                self.store(p.out, at, centred * inverse * gamma + beta)?;
            }
        }
        Ok(())
    }

    /// Root-mean-square normalisation over the channel axis, per spatial position.
    ///
    /// [`Reference::layer_norm`] without the mean and without beta, and so one pass over
    /// the column rather than two. Epsilon is inside the square root, matching
    /// `torch.nn.RMSNorm`.
    fn rms_norm(&mut self, p: &Push) -> Result<(), String> {
        if p.in_c == 0 {
            return Err("an rms norm over zero channels".into());
        }
        let stride = p.in_h * p.in_w;
        let groups = p.group.max(1);
        if !p.in_c.is_multiple_of(groups) {
            return Err(format!("{} channels do not split into {groups} groups", p.in_c));
        }
        let per_group = p.in_c / groups;
        let epsilon = f32::from_bits(p.param1_bits);
        // `count` is `groups * stride`, so this walks one group at one position at a time.
        for index in 0..p.count {
            let group = if stride == 0 { 0 } else { index / stride };
            let position = if stride == 0 { 0 } else { index % stride };
            let base = group * per_group;
            let mut squares = 0.0;
            for c in 0..per_group {
                let value = self.load(p.in0, (base + c) * stride + position)?;
                squares += value * value;
            }
            let inverse = 1.0 / (squares / per_group as f32 + epsilon).sqrt();

            for c in 0..per_group {
                let at = (base + c) * stride + position;
                let value = self.load(p.in0, at)?;
                // Gamma is indexed within the group, so every group shares one table.
                let gamma = self.weight(p.weight, c)?;
                self.store(p.out, at, value * inverse * gamma)?;
            }
        }
        Ok(())
    }

    /// Elementwise sum of two equal shapes.
    fn add(&mut self, p: &Push) -> Result<(), String> {
        for i in 0..p.count {
            let sum = self.load(p.in0, i)? + self.load(p.in1, i)?;
            self.store(p.out, i, sum)?;
        }
        Ok(())
    }

    /// A folded residual addend, or 0.0 when the op stores its accumulator unfolded.
    ///
    /// Mirrors the `p.res` branch of the convolution shaders: `activate(acc + bias)` plus
    /// the same-indexed element of a same-shaped tensor. The single fp16 rounding happens
    /// in the caller's `store`, exactly as the single `float16_t()` in the shader — the
    /// unfolded pair would round twice, so this is not spelled as `add` after the fact.
    fn fused_res(&self, p: &Push, index: u32) -> Result<f32, String> {
        if p.res == super::NO_FUSE {
            Ok(0.0)
        } else {
            self.load(p.res, index)
        }
    }

    /// A folded per-channel shift, or 0.0 when the op stores unfolded.
    ///
    /// Mirrors the `p.shift` branch: Supertonic's timestep conditioning adds one value per
    /// output channel, broadcast over every position of that channel.
    fn fused_shift(&self, p: &Push, channel: u32) -> Result<f32, String> {
        if p.shift == super::NO_FUSE {
            Ok(0.0)
        } else {
            self.load(p.shift, channel)
        }
    }

    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`.
    ///
    /// Q and K are `[d_model, 1, T]`; the output is `[heads, T, T]`. A head is a run of
    /// `in_c / group` consecutive channels, which is what makes the split free — there is
    /// no transpose anywhere in this, by construction.
    fn attn_scores(&mut self, p: &Push) -> Result<(), String> {
        let head_dim = heads(p, p.in_c)?;
        // Q and K carry their own lengths, which are the score map's height and width. For
        // self-attention those are equal.
        let (query_stride, key_stride) = (p.out_h, p.out_w);
        let scale = f32::from_bits(p.param0_bits);
        for head in 0..p.group {
            let query_base = head * head_dim * query_stride;
            // K is `kv_heads` wide, so several query heads share a key head. The identity when
            // the two counts match, which is every net that is not Gemma's.
            let key_base = Self::kv_head_of(p, head) * head_dim * key_stride;
            for query in 0..p.out_h {
                for key in 0..p.out_w {
                    let mut total = 0.0;
                    for d in 0..head_dim {
                        total += self.load(p.in0, query_base + d * query_stride + query)?
                            * self.load(p.in1, key_base + d * key_stride + key)?;
                    }
                    self.store(p.out, nchw(p, head, query, key), total * scale)?;
                }
            }
        }
        Ok(())
    }

    /// Softmax over the last axis, `count` contiguous rows of `out_w`.
    ///
    /// The row maximum is subtracted first, as `softmax.comp` does. That is not a
    /// refinement: without it a row containing a large score exponentiates to infinity in
    /// fp32 and every probability in it becomes a NaN.
    ///
    /// `causal` truncates each row at the diagonal, as `softmax_causal.comp` does: rows are
    /// head-major with `out_h` queries per head, so query `row % out_h` reads `query + 1` keys and
    /// the rest of its row is stored as zero.
    ///
    /// [`SoftmaxMode::Prefix`] instead normalises the leading `prefix + 1` entries and **leaves
    /// the rest of the row alone**, matching `softmax_prefix.comp`. Zeroing the tail would be
    /// wrong there rather than merely wasteful: the tail belongs to positions this step does not
    /// attend over but a later one will, and the corresponding apply stops at the same bound.
    fn softmax(&mut self, p: &Push, mode: SoftmaxMode) -> Result<(), String> {
        if p.out_w == 0 {
            return Err("a softmax over an empty axis".into());
        }
        if mode == SoftmaxMode::Causal && p.out_h == 0 {
            return Err("a causal softmax over no queries".into());
        }
        for row in 0..p.count {
            let at = row * p.out_w;
            let keys = match mode {
                // At query 0 this is 1, so the distribution is exactly `[1, 0, ...]` and the
                // denominator can never be empty.
                SoftmaxMode::Causal => ((row % p.out_h.max(1)) + 1).min(p.out_w),
                SoftmaxMode::Prefix => {
                    let (first, last) = self.attended(p, p.out_w);
                    let mut peak = -65504.0f32;
                    for i in first..=last {
                        peak = peak.max(self.load(p.in0, at + i)?);
                    }
                    let mut total = 0.0;
                    for i in first..=last {
                        total += (self.load(p.in0, at + i)? - peak).exp();
                    }
                    let inverse = 1.0 / total;
                    for i in first..=last {
                        let value = (self.load(p.in0, at + i)? - peak).exp() * inverse;
                        self.store(p.out, at + i, value)?;
                    }
                    continue;
                }
                SoftmaxMode::Full => p.out_w,
            };
            // A sliding layer's causal row also drops everything more than `p.kh - 1` behind
            // the query. Zero is no window, which is the plain causal mask.
            let query = row % p.out_h.max(1);
            let first = if mode == SoftmaxMode::Causal && p.kh != 0 {
                (query + 1).saturating_sub(p.kh)
            } else {
                0
            };
            let mut peak = -65504.0f32;
            for i in first..keys {
                peak = peak.max(self.load(p.in0, at + i)?);
            }
            let mut total = 0.0;
            for i in first..keys {
                total += (self.load(p.in0, at + i)? - peak).exp();
            }
            // The peak's own term is `exp(0)`, so this is at least 1.
            let inverse = 1.0 / total;
            for i in 0..first {
                self.store(p.out, at + i, 0.0)?;
            }
            for i in first..keys {
                let value = (self.load(p.in0, at + i)? - peak).exp() * inverse;
                self.store(p.out, at + i, value)?;
            }
            if mode == SoftmaxMode::Causal {
                // Written rather than left alone: `attn_apply` multiplies the whole row, and the
                // arena is reused, so a stale masked value would contribute something
                // unpredictable.
                for i in keys..p.out_w {
                    self.store(p.out, at + i, 0.0)?;
                }
            }
        }
        Ok(())
    }

    /// Keys an op attends over, as an inclusive `[first, last]` range.
    ///
    /// The host mirror of `attn_first` / `attn_last` in `shaders/common.glsl`.
    /// The inclusive key range an attention op may read. Mirrors `attn_first` / `attn_last`.
    ///
    /// A dynamic op that does **not** slide attends the whole prefix: it shares a submit with
    /// sliding layers, so `window_start` is set for them and it must ignore it.
    fn attended(&self, p: &Push, stride: u32) -> (u32, u32) {
        if p.dyn_keys != 0 {
            let first = if p.sliding != 0 { self.window_start } else { 0 };
            (first, self.prefix.min(stride.saturating_sub(1)))
        } else {
            (0, stride.saturating_sub(1))
        }
    }

    /// The KV head a query head reads from. Mirrors `kv_head_of` in `shaders/common.glsl`.
    fn kv_head_of(p: &Push, head: u32) -> u32 {
        let kv = if p.kv_heads == 0 { p.group } else { p.kv_heads };
        head / (p.group / kv.max(1)).max(1)
    }

    /// Channels one cache position occupies: `kv_heads * head_dim`.