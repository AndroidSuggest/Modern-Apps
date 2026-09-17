use super::nllb::{
    DECODER, DECODER_LAYERS, DECODER_NORM, ENCODER, EPSILON, HEAD, HEAD_SPLITS, HEADS,
    MAX_DECODE_POSITIONS, TENSORS, D_MODEL,
};
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::nllb::{Layers, feed_forward, name_host_tensors, point, split_classes};

/// One decoder step.
///
/// # Inputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1024, 1, 1]` | the current token, after [`embed_positions`] at `past = cache_len` |
/// | 1 | `[1024, 1, src_len]` | the encoder output, channel-major as the encoder produced it |
/// | 2, 3 | `[cache_len, 1, 1024]` | layer 0's self-attention K and V, position-major |
/// | 4, 5 | | layer 1's |
/// | … | | … |
///
/// The cache pair is **omitted at step 0**, where there is nothing before the current token.
///
/// # Outputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0..4 | `[~64052, 1, 1]` | the four logits splits (the last two one class short), concatenated by the host |
/// | 4, 5 | `[1, 1, 1024]` | layer 0's new self-attention K and V, ready to append |
/// | 6, 7 | | layer 1's |
/// | … | | … |
///
/// # Why the cross-attention K and V are recomputed
///
/// They depend only on the encoder output, so computing them once and caching them would save
/// twelve 1024x1024 projections per step. They are not cached here because that would need a third
/// pass and a second kind of persistence for a saving the logits projection dwarfs: the head is
/// ~262 million multiply-accumulates against the whole decoder's ~200 million. Worth revisiting
/// only after `Kind::ConvVecInt8` makes the head cheap.
pub(crate) fn decode_step(weights: &dyn WeightSource, src_len: u32) -> Result<Plan, String> {
    if src_len == 0 {
        return Err("a decode step with no source to attend over".into());
    }

    let l = &mut Layers { next: DECODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The decoder plus the tied head, which this pass computes in the same plan. The whole encoder
    // belongs to `Mode::Encode`.
    name_host_tensors(b, &[HEAD..ENCODER, DECODER..TENSORS]);

    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    let encoded = b.input(Shape::new(D_MODEL, 1, src_len));
    // Every layer's cache pair, held on the device across steps rather than handed in and out.
    //
    // Sized for the longest decode the loop will run, not for this step, which is what lets one
    // recording serve every token: the plan no longer mentions the step number, so
    // `Reshaped::at` stops matching a new key and re-recording on each one. How much of each
    // cache is live comes from `StepParams::prefix` at submit time.
    let caches: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            let v = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, D_MODEL));
            (k, v)
        })
        .collect();

    for &(cache_k, cache_v) in &caches {
        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k_new = point(b, l, normed, D_MODEL, Act::None);
        let v_new = point(b, l, normed, D_MODEL, Act::None);
        // A projection writes `[d_model, 1, 1]`; a cache position is `[1, 1, d_model]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, D_MODEL));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, D_MODEL));
        // Written into the cache at the row the step names, replacing the concatenation that
        // used to copy the entire prefix on every layer of every token.
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        let scores = b.attn_scores_cached_dynamic(q, cache_k, HEADS);
        let probs = b.softmax_prefix(scores, true);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the encoder output, which is channel-major and a different length,
        // so it uses the ordinary pair rather than the cached one.
        let normed = b.layer_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, D_MODEL, Act::None);
        let k = point(b, l, encoded, D_MODEL, Act::None);
        let v = point(b, l, encoded, D_MODEL, Act::None);
        let scores = b.attn_scores(q, k, HEADS);
        let probs = b.softmax(scores);
        let mixed = b.attn_apply(probs, v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        x = feed_forward(b, l, x);
    }
    if l.next != DECODER_NORM {
        return Err(format!("the decoder claims {} tensors, not {DECODER_NORM}", l.next));
    }
    let state = b.layer_norm(x, l.take(), EPSILON);
    if l.next != TENSORS {
        return Err(format!("the decoder norm ends at {}, not {TENSORS}", l.next));
    }

    // The tied head, in the same plan: a separate pass would mean a second `rebuild` and a round
    // trip through the host for a `[1024]` vector. The last two splits are one class short.
    let head = &mut Layers { next: HEAD };
    let outputs: Vec<Id> = (0..HEAD_SPLITS)
        .map(|split| point(b, head, state, split_classes(split), Act::None))
        .collect();
    if head.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", head.next));
    }
    // The K and V rows are no longer outputs: they are in the cache, on the device, and the host
    // never sees them again.
    builder.finish(&outputs)
}
