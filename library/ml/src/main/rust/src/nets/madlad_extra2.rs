use super::madlad::{
    DECODER, DECODER_LAYERS, DECODER_NORM, D_MODEL, ENCODER, EPSILON, HEAD_LM,
    HEAD_SPLITS, HEADS, INNER, MAX_DECODE_POSITIONS, TABLE_DEC, TABLE_ENC, TENSORS,
};
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::madlad::{Layers, cross_attention, feed_forward, name_host_tensors, point, split_classes};

/// One decoder step.
///
/// # Inputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0 | `[1024, 1, 1]` | the current token, after [`embed_positions`] |
/// | 1 | `[1024, 1, src_len]` | the encoder output, channel-major as the encoder produced it |
/// | 2 | `[16, 1, MAX]` | the step's self-attention bias row, host-computed (see below) |
///
/// # Outputs, in declaration order
///
/// | | shape | |
/// | :--- | :--- | :--- |
/// | 0..4 | `[64000, 1, 1]` | the four `lm_head` logits splits, concatenated by the host |
///
/// The K and V rows are no longer outputs: they are written straight into the device-side
/// cache by the plan, and the host never sees them again.
///
/// # The bias input
///
/// A decode step is one query at absolute position `step` against keys `0..=step`, so its
/// bias row is `[HEADS, 1, step + 1]` of live values. The plan is recorded once, so the
/// input is `[HEADS, 1, MAX_DECODE_POSITIONS]` wide and the step uploads the live prefix
/// followed by zeros: `softmax_prefix` normalises only the leading `prefix + 1` entries,
/// and the stale tail never enters the maximum or the sum — the same bound the cached
/// score map already reads.
///
/// # Why the cross-attention K and V are recomputed
///
/// As NLLB's: they depend only on the encoder output, so computing them once and caching
/// them would save thirty-two 1024x2048 projections per step. They are not cached here
/// because that would need a third pass and a second kind of persistence for a saving the
/// logits projection dwarfs: the head is ~262 million multiply-accumulates per split-set
/// against the whole decoder's ~1.6 billion. Worth revisiting only after `Kind::ConvVecInt8`
/// makes the head cheap.
pub(crate) fn decode_step(weights: &dyn WeightSource, src_len: u32) -> Result<Plan, String> {
    if src_len == 0 {
        return Err("a decode step with no source to attend over".into());
    }

    let l = &mut Layers { next: DECODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The decoder plus the logits head, which this pass computes in the same plan. The input
    // table, the whole encoder and both bias tables belong elsewhere: the tables are read
    // on the host, never by a shader, so the range ends at TABLE_ENC — ending it at
    // TABLE_DEC would leave the encoder bias table in no-man's-land (named by neither
    // pass's complement check).
    name_host_tensors(b, &[HEAD_LM..ENCODER, DECODER..TABLE_ENC]);

    let mut x = b.input(Shape::new(D_MODEL, 1, 1));
    let encoded = b.input(Shape::new(D_MODEL, 1, src_len));
    let step_bias = b.input(Shape::new(HEADS, 1, MAX_DECODE_POSITIONS));
    // Every layer's cache pair, held on the device across steps rather than handed in and out.
    //
    // Sized for the longest decode the loop will run, not for this step, which is what lets one
    // recording serve every token: the plan no longer mentions the step number, so
    // `Reshaped::at` stops matching a new key and re-recording on each one. How much of each
    // cache is live comes from `StepParams::prefix` at submit time.
    let caches: Vec<(Id, Id)> = (0..DECODER_LAYERS)
        .map(|_| {
            let k = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, INNER));
            let v = b.persistent(Shape::new(MAX_DECODE_POSITIONS, 1, INNER));
            (k, v)
        })
        .collect();

    for &(cache_k, cache_v) in &caches {
        // Self-attention, pre-norm, against the cache this step is about to extend.
        let normed = b.rms_norm(x, l.take(), EPSILON);
        let q = point(b, l, normed, INNER, Act::None);
        let k_new = point(b, l, normed, INNER, Act::None);
        let v_new = point(b, l, normed, INNER, Act::None);
        // A projection writes `[inner, 1, 1]`; a cache position is `[1, 1, inner]`. The same
        // bytes, so this is a relabelling and the write below is one contiguous copy.
        let k_row = b.reshaped(k_new, Shape::new(1, 1, INNER));
        let v_row = b.reshaped(v_new, Shape::new(1, 1, INNER));
        // Written into the cache at the row the step names, replacing the concatenation that
        // used to copy the entire prefix on every layer of every token.
        b.cache_write(k_row, cache_k);
        b.cache_write(v_row, cache_v);
        // Prescaled dynamic: T5 applies no 1/sqrt(head_dim), and the key count comes from
        // the step rather than the shape.
        let scores = b.attn_scores_cached_prescaled(q, cache_k, HEADS, HEADS, false);
        let biased = b.add(scores, step_bias);
        let probs = b.softmax_prefix(biased, true);
        let mixed = b.attn_apply_cached_dynamic(probs, cache_v, HEADS);
        let projected = point(b, l, mixed, D_MODEL, Act::None);
        x = b.add(x, projected);

        // Cross-attention over the encoder output, which is channel-major and a different length,
        // so it uses the ordinary pair rather than the cached one. No bias: cross-attention
        // never carries a relative term.
        let normed = b.rms_norm(x, l.take(), EPSILON);
        x = cross_attention(b, l, x, normed, encoded);

        x = feed_forward(b, l, x);
    }
    if l.next != DECODER_NORM {
        return Err(format!("the decoder claims {} tensors, not {DECODER_NORM}", l.next));
    }
    let state = b.rms_norm(x, l.take(), EPSILON);
    if l.next != TABLE_ENC {
        return Err(format!("the decoder norm ends at {}, not {TABLE_ENC}", l.next));
    }

    // The untied logits head, in the same plan: a separate pass would mean a second `rebuild`
    // and a round trip through the host for a `[1024]` vector. Four even splits of 64,000.
    let head = &mut Layers { next: HEAD_LM };
    let outputs: Vec<Id> = (0..HEAD_SPLITS)
        .map(|split| point(b, head, state, split_classes(split), Act::None))
        .collect();
    if head.next != ENCODER {
        return Err(format!("the head claims {} tensors, not {ENCODER}", head.next));
    }
    builder.finish(&outputs)
}
