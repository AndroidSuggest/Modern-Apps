// Layer 0's forward pass with its tail branches exposed (split out of
// `gemma4_part2.rs` so each file stays under the 500-line Rust limit).
//
// The exact op sequence of `layer` at `index` 0 - same tensor indices, same
// order, so the KV cache it fills matches a decode step bit for bit - but
// returning `[attn_branch, mlp_branch, ple_branch, out]` instead of just the
// output, for `Mode::TraceTail`:
//
// * `attn_branch`: post-`post_attention_norm`, pre-residual
// * `mlp_branch`: post-`post_ffw_norm`, pre-residual
// * `ple_branch`: post-`post_per_layer_input_norm`, pre-residual
// * `out`: post-`skip`, the layer output
//
// Layer 0 is sliding with its own cache (`dim` 256, `inner` 6144); the owned-cache
// branch is the only one taken. Kept as a separate function rather than threading
// intermediates through `layer` so the verified decode path is untouched.
#[allow(clippy::too_many_arguments)]
fn layer_tail(
    b: &mut Builder,
    x: Id,
    per_layer_inputs: Id,
    angles: Id,
    cache_k: Id,
    cache_v: Id,
) -> Result<[Id; 4], String> {
    let dim = head_dim(0);
    let inner = ffn(0);
    let at = layer_at(0);
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

    // Same tensor order as `layer` at index 0 (owning, sliding).
    let input_norm = plain(&mut next);
    let q_norm_at = plain(&mut next);
    let q_proj = proj(&mut next);
    let k_norm_at = plain(&mut next);
    let k_proj = proj(&mut next);
    let v_proj = proj(&mut next);
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
    if next != layer_at(1) {
        return Err(format!("layer 0 tail read {} tensors", next - at));
    }

    let normed = b.rms_norm(x, input_norm, EPSILON);
    // The live int8 round-trips, as in [`layer`]: the tail bisect compares this
    // run against the round-tripped numpy oracle path.
    let normed_q = b.quantize(normed, QI_SCALE[0]);
    let q = point(b, q_proj, normed_q, HEADS * dim);
    let q = b.quantize(q, QO_SCALE[0]);
    let q = b.rms_norm_grouped(q, q_norm_at, EPSILON, HEADS);
    let q = b.rotary(q, angles, HEADS);

    let k = point(b, k_proj, normed_q, KV_HEADS * dim);
    let k = b.quantize(k, KO_SCALE[0]);
    let k = b.rms_norm_grouped(k, k_norm_at, EPSILON, KV_HEADS);
    let k = b.rotary(k, angles, KV_HEADS);
    let v = point(b, v_proj, normed_q, KV_HEADS * dim);
    let v = b.quantize(v, VO_SCALE[0]);
    // S10's `value_norm`, as in [`layer`]: parameter-free RMS norm over the value
    // row before the cache write.
    let v = b.rms_norm(v, ONE_SLIDING, EPSILON);
    let k_row = b.reshaped(k, Shape::new(1, 1, KV_HEADS * dim));
    let v_row = b.reshaped(v, Shape::new(1, 1, KV_HEADS * dim));
    b.cache_write(k_row, cache_k);
    b.cache_write(v_row, cache_v);

    let slides = !is_full_attention(0);
    let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, KV_HEADS, slides);
    let probs = b.softmax_prefix(scores, slides);
    let mixed = b.attn_apply_cached_grouped(probs, cache_v, HEADS, KV_HEADS, slides);
    // The live int8 round-trip, as in [`layer`]: the tail bisect compares this
    // run against the round-tripped numpy oracle path.
    let mixed = b.quantize(mixed, MM_SCALE[0]);
    let attended = point(b, o_proj, mixed, D_MODEL);
    let attended = b.quantize(attended, O_SCALE[0]);
    let attn_branch = b_rms(b, attended, post_attention);
    let x = b.add(x, attn_branch);

    let ff_in = b.rms_norm(x, pre_ff, EPSILON);
    let gelu_gate = point_act(b, gate_proj, ff_in, inner, Act::Gelu);
    let up = point(b, ff1_proj, ff_in, inner);
    let gated = b.mul(gelu_gate, up);
    let ff = point(b, down, gated, D_MODEL);
    let mlp_branch = b_rms(b, ff, post_ff);
    let x = b.add(x, mlp_branch);

    let mine = b.slice_channels(per_layer_inputs, 0, PER_LAYER);
    let gated_in = point8_act(b, gate_at, x, PER_LAYER, Act::Gelu);
    let combined = b.mul(gated_in, mine);
    let projected = point8(b, projection_at, combined, D_MODEL);
    let ple_branch = b_rms(b, projected, post_per_layer);
    let x = b.add(x, ple_branch);
    // The whole residual is scaled by `skip` after add2, as in [`layer`].
    let out = b.mul_scalar(x, skip_at);
    Ok([attn_branch, mlp_branch, ple_branch, out])
}
