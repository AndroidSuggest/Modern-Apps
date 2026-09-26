/// One decoder layer.
///
/// The two archetypes differ only in whether they project their own key and value, so this is one
/// function with one branch rather than two that would share every other line.
#[allow(clippy::too_many_arguments)]
fn layer(
    b: &mut Builder,
    index: usize,
    x: Id,
    per_layer_inputs: Id,
    angles: Id,
    cache_k: Id,
    cache_v: Id,
) -> Result<Id, String> {
    let dim = head_dim(index);
    let inner = ffn(index);
    let at = layer_at(index);
    let mut next = at;
    let plain = |n: &mut usize| {
        let here = *n;
        *n += 1;
        here
    };
    let proj = |n: &mut usize| {
        let here = *n;
        *n += PROJECTION_TENSORS;
        here
    };

    // Self-attention, pre-norm.
    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let (k_norm_at, k_proj, v_proj) = if owns_cache(index) {
        (Some(plain(&mut next)), Some(proj(&mut next)), Some(proj(&mut next)))
    } else {
        (None, None, None)
    };
    let o_proj = proj(&mut next);
    let post_attention = plain(&mut next);
    let pre_ff = plain(&mut next);
    let gate_proj = proj(&mut next);
    let ff1_proj = proj(&mut next);
    let down = proj(&mut next);
    let post_ff = plain(&mut next);
    let gate_at = proj(&mut next);
    let projection_at = proj(&mut next);
    let post_per_layer = plain(&mut next);
    let skip_at = plain(&mut next);
    if next != layer_at(index + 1) {
        return Err(format!("layer {index} read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, input_norm, EPSILON);
    // Live round-trips every attention activation through int8 (the Track-16..20
    // qint set): without these the device runs the attention at full precision
    // while live quantizes, and small input perturbations survive to the softmax
    // instead of being rounded away - see [`QI_SCALE`].
    let normed_q = b.quantize(normed, QI_SCALE[index]);
    let q = point(b, q_proj, normed_q, HEADS * dim);
    let q = b.quantize(q, QO_SCALE[index]);
    // Per head, against one `head_dim`-long gamma. The scale is already inside that gamma.
    let q = b.rms_norm_grouped(q, q_norm_at, EPSILON, HEADS);
    let q = b.rotary(q, angles, HEADS);

    if let (Some(k_norm_at), Some(k_proj), Some(v_proj)) = (k_norm_at, k_proj, v_proj) {
        let k = point(b, k_proj, normed_q, KV_HEADS * dim);
        let k = b.quantize(k, KO_SCALE[index]);
        let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
        let k = b.rotary(k, angles, KV_HEADS);
        let v = point(b, v_proj, normed_q, KV_HEADS * dim);
        let v = b.quantize(v, VO_SCALE[index]);
        // S10's `value_norm`: a parameter-free RMS norm (all-ones gamma) over
        // the value row before the cache write (composite op23 for layer 0,
        // one per owning layer). It is not a stored tensor, so it is easy to
        // read as absent - but skipping it stores raw V (rms ~28) where the
        // reference stores rms-normalised V (rms ~1), and every layer's
        // attention mixes the wrong values. The gamma is head-dim-wide
        // (`[256]` sliding, `[512]` full): see [`ONE_SLIDING`].
        let ones = if is_full_attention(index) { ONE_FULL } else { ONE_SLIDING };
        let v = b.rms_norm(v, ones, EPSILON);
        let k_row = b.reshaped(k, Shape::new(1, 1, KV_HEADS * dim));
        let v_row = b.reshaped(v, Shape::new(1, 1, KV_HEADS * dim));
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
    }

    // Layers 0-3 of every group of five slide; layer 4 attends the whole prefix. One
    // `window_start` in `StepParams` serves both because this flag decides, per op, whether to
    // read it - see `Push::sliding`.
    let slides = !is_full_attention(index);
    let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, KV_HEADS, slides);
    let probs = b.softmax_prefix(scores, slides);
    let mixed = b.attn_apply_cached_grouped(probs, cache_v, HEADS, KV_HEADS, slides);
    // Live round-trips the mixture through int8 at the per-layer `MM_SCALE`
    // before the O projection consumes it (S10's `attn_vec_einsum/composite`).
    // Without this the O-tail runs at full precision and diverges from live
    // past any cosine gate — see [`MM_SCALE`].
    let mixed = b.quantize(mixed, MM_SCALE[index]);
    let attended = point(b, o_proj, mixed, D_MODEL);
    // The O-input round-trip (`attn_vec_einsum/dot_general1`), same set.
    let attended = b.quantize(attended, O_SCALE[index]);
    // Post-norm on the branch, then the residual: Gemma norms the sublayer's output rather than
    // its input alone, which is why there are five norms and not three.
    let attended = b_rms(b, attended, post_attention);
    let x = b.add(x, attended);

    // Gated feed-forward, split gate + up (litertlm stores them separately).
    // The GELU folds into the gate projection's store (single consumer).
    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let gelu_gate = point_act(b, gate_proj, ff_in, inner, Act::Gelu);
    let up = point(b, ff1_proj, ff_in, inner);
    let gated = b.mul(gelu_gate, up);
    let ff = point(b, down, gated, D_MODEL);
    let ff = b_rms(b, ff, post_ff);
    let x = b.add(x, ff);

    // The per-layer input branch. Inferred from the tensor shapes and the graph's node names, not
    // read from a reference run: `gelu(x @ gate) * per_layer_input[layer]`, projected back up,
    // normed and added.
    //
    // The per-layer inputs arrive as one `[256 * 35, 1, 1]` block so this layer's slice is a
    // channel range. A `[256, 1, 35]` shape would need a slice along the width axis, which has no
    // builder and would buy nothing.
    let mine = b.slice_channels(per_layer_inputs, index as u32 * PER_LAYER, PER_LAYER);
    let gated_in = point8_act(b, gate_at, x, PER_LAYER, Act::Gelu);
    let combined = b.mul(gated_in, mine);
    let projected = point8(b, projection_at, combined, D_MODEL);
    let branch = b_rms(b, projected, post_per_layer);
    let x = b.add(x, branch);
    // The whole residual is scaled by `skip` after add2
    // (`_maybe_apply_skip_scale/mul` in the portable graph). Without it layer 0
    // leaves min -173.6/max 11.6 against an ideal -4.66/0.32, and every
    // downstream residual is dominated by the unscaled stream.
    let x = b.mul_scalar(x, skip_at);
    // No layer_scalar: litertlm carries none.
    Ok(x)
}

/// `rms_norm` with this module's epsilon, which every norm here uses.
fn b_rms(b: &mut Builder, x: Id, at: usize) -> Id {
    b.rms_norm(x, at, EPSILON)
}
