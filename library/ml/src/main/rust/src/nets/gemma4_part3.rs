#[cfg(test)]
mod tests {
    use super::*;

    /// The tier the layout tests record at. The smallest, because they check shapes and
    /// counts rather than capacity, and the smallest is the fastest to build.
    const TEST_CONTEXT: u32 = CONTEXT_TIERS[0];
    use crate::nets::tests::Shapes;
    use crate::nets::{Kind, Op};

    #[test]
    fn the_layer_types_alternate_four_sliding_to_one_full() {
        // `config.json`'s `layer_types`, transcribed: indices 4, 9, 14, 19, 24, 29, 34 are full.
        let full: Vec<usize> = (0..LAYERS).filter(|&i| is_full_attention(i)).collect();
        assert_eq!(full, vec![4, 9, 14, 19, 24, 29, 34]);
        assert_eq!(LAYERS - full.len(), 28, "the rest slide over a {WINDOW}-position window");
    }

    #[test]
    fn the_head_dimension_follows_the_attention_type() {
        // Read off the `q_proj` widths in the export: 2048 on a sliding layer, 4096 on a full one,
        // both over eight heads.
        for index in 0..LAYERS {
            let expected = if is_full_attention(index) { 512 } else { 256 };
            assert_eq!(head_dim(index), expected, "layer {index}");
            assert_eq!(HEADS * head_dim(index), if expected == 512 { 4096 } else { 2048 });
        }
    }

    #[test]
    fn only_the_first_fifteen_layers_own_a_cache() {
        // `num_kv_shared_layers = 20`, and the graph has exactly fifteen `past_key_values` inputs
        // and thirty-one outputs - one logits plus fifteen key/value pairs.
        let owners: Vec<usize> = (0..LAYERS).filter(|&i| owns_cache(i)).collect();
        assert_eq!(owners.len(), OWNS_CACHE_LAYERS);
        assert_eq!(owners.last(), Some(&14));
        assert_eq!(LAYERS - owners.len(), 20, "the shared ones");
    }

    #[test]
    fn a_shared_layer_reads_the_last_owner_of_its_own_attention_type() {
        // Traced from the export: every sliding shared layer reads layer 13, every full shared
        // layer reads layer 14. Mixing the two would attend over a cache written with the wrong
        // head dimension, which is a shape error here and silent nonsense on a device.
        for index in 0..LAYERS {
            let source = cache_source(index);
            assert!(owns_cache(source), "layer {index} reads {source}, which owns no cache");
            assert_eq!(
                is_full_attention(source),
                is_full_attention(index),
                "layer {index} reads {source}, of the other attention type"
            );
            if owns_cache(index) {
                assert_eq!(source, index);
            } else if is_full_attention(index) {
                assert_eq!(source, 14, "layer {index}");
            } else {
                assert_eq!(source, 13, "layer {index}");
            }
        }
    }

    #[test]
    fn the_shared_layers_trade_key_and_value_for_feed_forward_width() {
        // The whole shape of the model: no `k_proj`/`v_proj`/`k_norm`, and double the inner width.
        assert_eq!(ffn(0), 6144);
        assert_eq!(ffn(34), 12288);
        // Two fewer projections and two fewer norms (`k_norm` and `v_norm`): eight tensors,
        // because a quantised projection is a triple in the file.
        assert_eq!(OWNING_LAYER_TENSORS - SHARED_LAYER_TENSORS, 8);
        assert_eq!(OWNING_LAYER_TENSORS, 9 + 8 * 3);
        assert_eq!(SHARED_LAYER_TENSORS, 7 + 6 * 3);
    }

    #[test]
    fn the_decode_pass_builds_and_reads_every_tensor() {
        // The whole 35-layer forward pass against the stub source, so `Builder::finish`'s own
        // invariant does the work: it refuses a plan that leaves a tensor unread, which is what
        // catches a layer walking its span at the wrong stride.
        let source = Shapes::new(TENSORS);
        let plan = build(&source, Mode::DecodeStep.at(TEST_CONTEXT)).expect("the decode pass builds");
        assert_eq!(plan.inputs.len(), INPUTS);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.inputs[1].shape, Shape::new(PER_LAYER * LAYERS as u32, 1, 1));
        // Four splits of the vocabulary, softcapped, and nothing else: the KV caches stay on the
        // device.
        assert_eq!(plan.outputs.len(), HEAD_SPLITS);
        let classes: u32 = plan.outputs.iter().map(|b| b.shape.c).sum();
        assert_eq!(classes, VOCAB);
        crate::nets::tests::assert_no_aliasing(&plan);
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // One recording for a whole generation, as for NLLB and whisper.
        let first = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let again = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
    }

    #[test]
    fn the_attention_is_multi_query_and_never_double_scales() {
        // Two things that are invisible in the shapes. Every cached score map must take its key
        // count from the step and read one key/value head per eight query heads; and its scale
        // must be exactly one, because the export folded `1/sqrt(head_dim)` into `q_norm` and
        // deriving it again here would halve every logit.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let mut scores = 0;
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } = op {
                scores += 1;
                assert_eq!(push.group, HEADS, "{push:?}");
                assert_eq!(push.kv_heads, KV_HEADS, "{push:?}");
                assert_ne!(push.dyn_keys, 0, "{push:?}");
                assert_eq!(
                    f32::from_bits(push.param0_bits),
                    1.0,
                    "the scale is already in q_norm: {push:?}"
                );
            }
        }
        assert_eq!(scores, LAYERS, "one cached score map per layer");
        assert!(Q_NORM_CARRIES_SCALE, "if this ever becomes false the assertion above flips");
    }

    #[test]
    fn only_the_owning_layers_write_to_a_cache() {
        // Fifteen layers project a key and a value; the other twenty read one of theirs. A shared
        // layer that wrote would overwrite the position its source layer just stored.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let writes = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::CacheWrite, .. }))
            .count();
        assert_eq!(writes, OWNS_CACHE_LAYERS * 2, "a key and a value per owning layer");
    }

    #[test]
    fn every_layer_gates_its_feed_forward() {
        // Two gates a layer: the MLP's `gelu(gate) * up`, and the per-layer input's.
        //
        // They are different **kinds** because only the MLP's reads a fused `[gate | up]`
        // projection, which `Kind::GatedActivate` takes whole; the per-layer gate multiplies
        // against a slice of a separate tensor and stays an `Activate` plus a `Mul`. Counting
        // both is the point - the property is that every layer gates twice, not that it does so
        // with any particular op.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let count = |want: Kind| {
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind, .. } if *kind == want))
                .count()
        };
        assert_eq!(count(Kind::GatedActivate), LAYERS, "the MLP gate, per layer");
        assert_eq!(count(Kind::Activate), LAYERS, "the per-layer input gate, per layer");
        // The fused form must not leave its slices behind: they were the reason for it.
        //
        // 135 before fusing and 65 after - exactly the two `[gate | up]` slices a layer gone.
        // The rest are the per-layer input's slice and the head's splits, which are not this
        // op's to remove.
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Copy { .. })).count(),
            65,
            "the gated MLP's two slice copies a layer are gone"
        );
        let caps = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Softcap, .. }))
            .count();
        assert_eq!(caps, HEAD_SPLITS, "each logits split is capped");
    }

    #[test]
    fn a_prefill_plan_drops_the_logits_head() {
        // The head is evaluated for every prompt position today and the result is discarded for
        // all but the last, because `bridge.rs` checks `want_logits` after the pass and the pass
        // always runs the head. `Mode::Prefill` is the pass that does not.
        //
        // Asserted as a DIFFERENCE against `DecodeStep` rather than an absolute count, so it
        // survives the rest of the net changing under it.
        let decode = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("decode builds");
        let prefill =
            build(&Shapes::new(TENSORS), Mode::Prefill { tokens: 1 }.at(TEST_CONTEXT)).expect("prefill builds");
        assert!(
            prefill.ops.len() < decode.ops.len(),
            "prefill {} ops against decode {}",
            prefill.ops.len(),
            decode.ops.len()
        );
        // Four int4 splits, four softcaps, one final norm.
        assert_eq!(decode.ops.len() - prefill.ops.len(), 9, "the head is nine ops");
        let softcaps = |plan: &Plan| {
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::Softcap, .. }))
                .count()
        };
        assert_eq!(softcaps(&decode), HEAD_SPLITS, "a decode step caps every split");
        assert_eq!(softcaps(&prefill), 0, "a prefill caps nothing, because it computes nothing");
        // The caches are the point of a prefill, so it must still write all of them.
        let writes = |plan: &Plan| {
            plan.ops
                .iter()
                .filter(|op| matches!(op, Op::Dispatch { kind: Kind::CacheWrite, .. }))
                .count()
        };
        assert_eq!(writes(&prefill), writes(&decode), "prefill fills the same caches");
        // And the arena must not GROW on the way back to decode.
        //
        // `Reshaped::at` rebuilds whenever the mode changes, and `Net::rebuild` reallocates the
        // arena only when it grows — but reallocating is exactly what calls `rebind_arena`, which
        // drops every persistent tensor, i.e. the KV caches the prefill just spent a whole prompt
        // filling. `bridge.rs` constructs the net at `DecodeStep`, so the arena starts at decode's
        // size and the switch to prefill cannot grow it.
        //
        // That makes the safety of the whole prefill-then-decode sequence rest on prefill never
        // needing more arena than decode. It is true today because prefill drops the head's
        // logits tensors and adds nothing, but nothing enforced it until this line.
        assert!(
            prefill.arena_elems <= decode.arena_elems,
            "prefill wants {} arena elements against decode's {}: switching back to decode would \
             grow the arena, and the reallocation drops every KV cache the prefill just filled",
            prefill.arena_elems,
            decode.arena_elems
        );
    }

    #[test]
    fn a_batched_prefill_widens_every_stage_and_masks_causally() {
        // The whole prompt in one submit. What makes it worth having is that the projections
        // become GEMMs: at one position the model is bandwidth-bound reading 1.3 GB of weights
        // per token, and at T it reads the same weights once for all of them.
        //
        // What makes it dangerous is that a plan recorded for T positions which attends as
        // though it were one produces fluent, wrong text and no shape error. So this checks the
        // masking, not merely that it builds.
        for tokens in [2u32, 8, 64] {
            let plan = build(&Shapes::new(TENSORS), Mode::Prefill { tokens }.at(TEST_CONTEXT))
                .unwrap_or_else(|e| panic!("{tokens} positions: {e}"));
            assert_eq!(plan.inputs.len(), INPUTS);
            for input in &plan.inputs {
                assert_eq!(input.shape.w, tokens, "every input is widened");
            }
            let mut causal = 0;
            let mut windowed = 0;
            for op in &plan.ops {
                let Op::Dispatch { kind, push, .. } = op else { continue };
                match kind {
                    Kind::SoftmaxCausal => {
                        causal += 1;
                        if push.kh != 0 {
                            windowed += 1;
                            assert_eq!(push.kh, WINDOW);
                        }
                        assert_eq!((push.out_h, push.out_w), (tokens, tokens), "T x T scores");
                    }
                    // Every score map is multi-query, as the decode path's is.
                    Kind::AttnScores | Kind::AttnApply => {
                        assert_eq!(push.group, HEADS);
                        assert_eq!(push.kv_heads, KV_HEADS);
                    }
                    // Fifteen owning layers write a key and a value, transposing as they go.
                    Kind::CacheWrite => assert_eq!(push.group, tokens, "one write per position"),
                    _ => {}
                }
            }
            assert_eq!(causal, LAYERS, "one causal softmax per layer");
            assert_eq!(windowed, 28, "and the sliding ones carry a window");
        }
        assert!(build(&Shapes::new(TENSORS), Mode::Prefill { tokens: 0 }.at(TEST_CONTEXT)).is_err());
        let past = MAX_CONTEXT + 1;
        assert!(build(&Shapes::new(TENSORS), Mode::Prefill { tokens: past }.at(TEST_CONTEXT)).is_err());
    }

    #[test]
    fn the_caches_are_allocated_before_the_inputs() {
        // The invariant that lets two differently-shaped plans share one arena, and the reason
        // the cache declaration sits above the inputs in `build` rather than beside the layers.
        //
        // `Builder::finish` assigns arena offsets by walking its pinned list in order, and both
        // `input` and `persistent` push onto it. The inputs are the only pinned tensors that grow
        // with the sequence length, so if they are allocated first every cache behind them moves
        // when the length changes - and a prefill plan would then fill caches that the decode
        // plan does not read. Same arena, so that is not uninitialised memory but the other
        // plan's live activations: fluent, wrong, and silent.
        //
        // Checked through the emitted offsets rather than the declaration order, so it cannot
        // pass by agreeing with the source it is meant to police.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let first_cache = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => Some(push.out),
                _ => None,
            })
            .min()
            .expect("a decode step writes caches");
        let first_input = plan.inputs.iter().map(|binding| binding.at).min().expect("inputs");
        assert!(
            first_cache < first_input,
            "the caches start at {first_cache} and the inputs at {first_input}: the inputs are \
             allocated first, so every cache moves when the sequence length does"
        );
    }

    #[test]
    fn a_full_attention_layer_ignores_the_sliding_layers_window() {
        // The bug this exists to prevent, and the reason `Push::sliding` is not a step parameter.
        //
        // `StepParams` carries one `window_start`, which the host sets for the sliding layers.
        // A full-attention layer sharing that submit must attend the whole prefix anyway. If it
        // read the same field it would lose its long-range attention - the one thing it is there
        // for - silently, and only once a conversation outgrew the window, which is exactly the
        // point at which nobody is still testing.
        let plan = build(&Shapes::new(TENSORS), Mode::DecodeStep.at(TEST_CONTEXT)).expect("builds");
        let mut sliding = 0;
        let mut full = 0;
        for op in &plan.ops {
            let Op::Dispatch { kind, push, .. } = op else { continue };
            if !matches!(
                kind,
                Kind::AttnScoresCached | Kind::AttnApplyCached | Kind::SoftmaxPrefix
            ) {
                continue;
            }
            if push.dyn_keys == 0 {
                continue;
            }
            if push.sliding == 0 {
                full += 1;
            } else {
                sliding += 1;
            }
        }
        // Three attention ops a layer - scores, softmax, apply - and seven of the 35 layers are
        // full attention: 4, 9, 14, 19, 24, 29, 34.
        assert_eq!(full, 7 * 3, "every op of a full-attention layer ignores the window");
        assert_eq!(sliding, 28 * 3, "and every op of a sliding one uses it");
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The whole ordered table, with no `.maml` on disk. `Shapes` hands back the index as the
        // offset and records every request, so this asserts what `maml_convert.py` must write.
        let source = Shapes::new(TENSORS);
        for index in 0..LAYERS {
            declare_layer(&source, index).unwrap_or_else(|e| panic!("layer {index}: {e}"));
        }
        let asked = source.asked.borrow();
        // Every layer's tensors, contiguous and in order, with nothing skipped or repeated.
        let indices: Vec<usize> = asked.iter().map(|(i, _)| *i).collect();
        let expected: Vec<usize> = (LAYER0..layer_at(LAYERS)).collect();
        assert_eq!(indices, expected, "the layers must tile their span exactly");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // Spot-checks against the export's initializer table, transcribed by hand and mapped back
        // through `as_exported` so this compares in the file's own `[in, out]` convention.
        let exported = |index: usize| -> Vec<Vec<u32>> {
            let source = Shapes::new(TENSORS);
            declare_layer(&source, index).unwrap_or_else(|e| panic!("layer {index}: {e}"));
            let asked = source.asked.borrow();
            asked.iter().map(|(_, d)| as_exported(d)).collect()
        };

        let owning = exported(0);
        assert_eq!(owning.len(), OWNING_LAYER_TENSORS);
        assert!(owning.contains(&vec![1536, 2048]), "q_proj on a sliding layer: {owning:?}");
        assert!(owning.contains(&vec![2048, 1536]), "o_proj: {owning:?}");
        assert!(owning.contains(&vec![1536, 12288]), "the fused gate-and-up: {owning:?}");
        assert!(owning.contains(&vec![6144, 1536]), "down_proj: {owning:?}");

        let full = exported(4);
        assert!(full.contains(&vec![1536, 4096]), "q_proj on a full layer: {full:?}");
        assert!(full.contains(&vec![1536, 512]), "k_proj at the global head dim: {full:?}");

        let shared = exported(15);
        assert_eq!(shared.len(), SHARED_LAYER_TENSORS);
        assert!(shared.contains(&vec![1536, 24576]), "the double-wide gate-and-up: {shared:?}");
        assert!(shared.contains(&vec![12288, 1536]), "the wide down_proj: {shared:?}");
        // `[1536, 256]` is ambiguous by shape alone - it is `k_proj`, `v_proj` *and*
        // `per_layer_input_gate` - so count it rather than test for absence. An owning layer has
        // all three; a shared layer has only the gate.
        let narrow = |v: &[Vec<u32>]| v.iter().filter(|d| **d == vec![1536, 256]).count();
        assert_eq!(narrow(&owning), 3, "k_proj, v_proj and the per-layer gate: {owning:?}");
        assert_eq!(narrow(&shared), 1, "only the per-layer gate: {shared:?}");
    }

    #[test]
    fn every_projection_is_a_kernel_a_scale_and_a_bias() {
        // Gemma 4 has no biases, but this runtime's convolutions do and a quantised kernel needs
        // a scale, so the converter writes a triple. This is what pins the count the converter
        // must emit - a file with one tensor per projection would parse and then read the next
        // layer's weights as this one's bias.
        //
        // The scale's **rank** is the precision: rank 2 `(out, blocks)` for int4, rank 1 `(out)`
        // for the two int8 per-layer projections. `Builder` resolves a scale by shape, so this is
        // also what stops one being read as the other.
        let source = Shapes::new(TENSORS);
        declare_layer(&source, 0).expect("layer 0");
        let asked = source.asked.borrow();
        let kernels = asked.iter().filter(|(_, d)| d.len() == 4).count();
        assert_eq!(kernels, OWNING_PROJECTIONS, "one kernel per projection");
        let mut wide = 0;
        let mut narrow = 0;
        for (offset, (_, dims)) in asked.iter().enumerate() {
            if dims.len() != 4 {
                continue;
            }
            let out = dims[0];
            let inp = dims[1];
            let scale = asked.get(offset + 1).map(|(_, d)| d.clone());
            let bias = asked.get(offset + 2).map(|(_, d)| d.clone());
            match scale.as_deref() {
                Some([rows, blocks]) => {
                    wide += 1;
                    assert_eq!(*rows, out, "the int4 scale has a row per channel: {dims:?}");
                    assert_eq!(
                        *blocks,
                        inp.div_ceil(crate::weights::I4_BLOCK),
                        "one scale per block of taps: {dims:?}"
                    );
                }
                Some([rows]) => {
                    narrow += 1;
                    assert_eq!(*rows, out, "the int8 scale is one per channel: {dims:?}");
                }
                other => panic!("a {dims:?} kernel is followed by {other:?}"),
            }
            assert_eq!(bias, Some(vec![out]), "the bias after a {dims:?} kernel");
        }
        assert_eq!(narrow, 2, "only the two per-layer projections are int8");
        assert_eq!(wide, OWNING_PROJECTIONS - 2);
    }

    #[test]
    fn every_projection_is_declared_as_a_convolution_kernel() {
        // The runtime reads these through `conv_int8`, which takes `[out, in, kh, kw]`, while the
        // export holds `MatMul` weights as `[in, out]`. The converter transposes; this is what
        // stops the two conventions being confused, which would be a plausible-looking net that
        // multiplies by a transposed matrix.
        let source = Shapes::new(TENSORS);
        declare_layer(&source, 0).expect("layer 0");
        for (index, dims) in source.asked.borrow().iter() {
            match dims.len() {