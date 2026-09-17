#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    fn plan(mode: Mode) -> Plan {
        let source = Shapes::new(TENSORS);
        build(&source, mode).unwrap_or_else(|e| panic!("{mode:?}: {e}"))
    }

    fn counts(plan: &Plan) -> std::collections::BTreeMap<String, usize> {
        let mut counts = std::collections::BTreeMap::new();
        for op in &plan.ops {
            if let Op::Dispatch { kind, .. } = op {
                *counts.entry(super::super::tests::name_of(*kind)).or_insert(0usize) += 1;
            }
        }
        counts
    }

    #[test]
    fn the_layout_matches_the_converter() {
        // The numbers `maml_convert.py --graph whisper --print-layers` reports. A disagreement here
        // is a plan that reads one layer's weights as another's.
        assert_eq!((ENCODER_LAYER_TENSORS, DECODER_LAYER_TENSORS), (22, 36));
        assert_eq!((CONV1, CONV2, ENC_POSITIONS, ENCODER), (0, 3, 6, 7));
        assert_eq!((ENC_NORM, HEAD, DEC_POSITIONS, DECODER), (139, 141, 144, 145));
        assert_eq!((DEC_NORM, TENSORS), (361, 363));
        assert_eq!(INT8_CONVS, 99);
        assert_eq!(SOURCE_POSITIONS, 1500);
        // Cross-attention K sits 19 tensors into a decoder layer, and V three after it.
        assert_eq!(cross_kv(0), DECODER + 19);
        assert_eq!(cross_kv(1), DECODER + DECODER_LAYER_TENSORS + 19);
    }

    #[test]
    fn the_parameter_total_matches_the_checkpoint() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the checkpoint plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 99 int8 convolutions.
        let layer_scales = u64::from(4 * D_MODEL + FFN + D_MODEL);
        let scales = 2 * u64::from(D_MODEL)
            + ENCODER_LAYERS as u64 * layer_scales
            + DECODER_LAYERS as u64 * (layer_scales + u64::from(4 * D_MODEL))
            + u64::from(VOCAB);
        assert_eq!(scales, 120_473);
        // And a zero bias for the tied head and for every `k_proj`, none of which has one.
        let keys = ENCODER_LAYERS as u64 + DECODER_LAYERS as u64 * 2;
        let synthesised = u64::from(VOCAB) + keys * u64::from(D_MODEL);
        assert_eq!(synthesised, 51_865 + 9216);

        assert_eq!(total, 72_593_920 + scales + synthesised);
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the two passes and
        // the host gather must account for every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [Mode::Encode, Mode::DecodeStep] {
            plan(mode);
            covered.extend(device_tensors(mode));
        }
        // The one the host reads and no shader does: the decoder position table. The tied embedding
        // is host-read too, but the decode step also binds it as the logits kernel.
        covered.insert(DEC_POSITIONS);
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_two_passes_partition_the_cross_attention_projections() {
        // The one place the passes interleave: `Mode::Encode` reads each decoder layer's cross K and
        // V, and `Mode::DecodeStep` reads everything else in that layer. An overlap would mean one
        // pass computing what the other already did; a gap would leave a tensor unread by both, which
        // `the_passes_cover_the_file...` catches.
        let encode: std::collections::BTreeSet<usize> =
            device_tensors(Mode::Encode).into_iter().collect();
        let decode: std::collections::BTreeSet<usize> =
            device_tensors(Mode::DecodeStep).into_iter().collect();
        let both: Vec<usize> = encode.intersection(&decode).copied().collect();
        assert!(both.is_empty(), "read by both passes: {both:?}");
        for layer in 0..DECODER_LAYERS {
            for index in cross_kv(layer)..cross_kv(layer) + 6 {
                assert!(encode.contains(&index), "layer {layer} tensor {index}");
                assert!(!decode.contains(&index), "layer {layer} tensor {index}");
            }
            // And the cross-attention query and output projections belong to the decode step.
            assert!(decode.contains(&(cross_kv(layer) - 3)), "layer {layer} cross q");
            assert!(decode.contains(&(cross_kv(layer) + 6)), "layer {layer} cross out");
        }
    }

    #[test]
    fn the_conv_stem_halves_the_frame_count() {
        // 3000 mel frames to 1500 encoder positions, which is `conv2`'s stride 2 and nothing else.
        // A wrong stride gives the right rank and the wrong length, and nothing downstream checks it.
        let plan = plan(Mode::Encode);
        let convs: Vec<super::super::Push> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind: Kind::ConvInt8, push, .. } if push.kw == CONV_KERNEL => {
                    Some(*push)
                }
                _ => None,
            })
            .collect();
        assert_eq!(convs.len(), 2, "{convs:?}");
        let (first, second) = (convs[0], convs[1]);
        assert_eq!((first.in_c, first.in_w), (MELS, MEL_FRAMES), "{first:?}");
        assert_eq!((first.stride_h, first.stride_w), (1, 1), "{first:?}");
        // Same-padded, so stride 1 holds the length.
        assert_eq!((first.out_c, first.out_h, first.out_w), (D_MODEL, 1, MEL_FRAMES), "{first:?}");
        assert_eq!((second.stride_h, second.stride_w), (1, 2), "{second:?}");
        assert_eq!(
            (second.out_c, second.out_h, second.out_w),
            (D_MODEL, 1, SOURCE_POSITIONS),
            "{second:?}"
        );
        // Both `same`-padded on the width only: a `1 x 3` kernel over a height-1 sequence.
        for push in [first, second] {
            assert_eq!((push.pad_t, push.pad_l, push.kh), (0, 1, 1), "{push:?}");
        }
    }

    #[test]
    fn the_encoder_is_six_pre_norm_layers_over_fifteen_hundred_positions() {
        let plan = plan(Mode::Encode);
        let counts = counts(&plan);
        // Six int8 convolutions per layer, the two conv-stem ones, and the twelve cross-attention
        // key and value projections this pass computes for the decoder.
        assert_eq!(
            counts.get("ConvInt8"),
            Some(&(ENCODER_LAYERS * 6 + 2 + DECODER_LAYERS * 2)),
            "{counts:?}"
        );
        // Three norms per layer would be post-norm. Pre-norm is two, plus one at the end.
        assert_eq!(counts.get("LayerNorm"), Some(&(ENCODER_LAYERS * 2 + 1)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), Some(&ENCODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&ENCODER_LAYERS), "{counts:?}");
        // Not causal: an audio window's positions all see each other.
        assert_eq!(counts.get("SoftmaxCausal"), None, "{counts:?}");
        // Two residuals per layer, plus the position table — minus the six whose
        // skip side is already written when the producing convolution runs, which
        // fold into its store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&7), "{counts:?}");
        assert_eq!(counts.get("Constant"), Some(&1), "{counts:?}");
        assert_eq!(counts.len(), 7, "{counts:?}");

        assert_eq!(plan.input().unwrap().shape, Shape::new(MELS, 1, MEL_FRAMES));
        // The hidden states, then twelve caches in layer order — all the same shape.
        assert_eq!(plan.outputs.len(), 1 + DECODER_LAYERS * 2);
        for binding in &plan.outputs {
            assert_eq!(binding.shape, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
        }
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_encoder_score_maps_are_square_at_fifteen_hundred() {
        // Self-attention over one window, so queries and keys are the same positions. This is also
        // the shape the arena is dominated by: `[8, 1500, 1500]` is 36 MB of fp16.
        let plan = plan(Mode::Encode);
        let mut seen = 0;
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::AttnScores, push, .. } = op {
                assert_eq!(
                    (push.group, push.out_h, push.out_w),
                    (HEADS, SOURCE_POSITIONS, SOURCE_POSITIONS),
                    "{push:?}"
                );
                seen += 1;
            }
        }
        assert_eq!(seen, ENCODER_LAYERS);
    }

    #[test]
    fn report_the_encoder_arena() {
        // Not an assertion so much as the number the port's one open question needs, printed by
        // `cargo test -- --nocapture`. The arena is bound whole with `range(arena_size)`, and
        // `maxStorageBufferRange`'s guaranteed minimum is 128 MiB — so if this approaches that,
        // `vulkan::segment` is the machinery to reuse. Only a device reports the real limit.
        let plan = plan(Mode::Encode);
        let bytes = u64::from(plan.arena_elems) * 2;
        println!(
            "whisper encoder arena: {} KiB ({:.1} MiB) for {} ops",
            bytes / 1024,
            bytes as f64 / (1 << 20) as f64,
            plan.ops.len()
        );
        // One score map and its softmax are 36 MB each, so anything under that is a plan that is not
        // doing the work; anything over 128 MiB cannot be bound on a device at the guaranteed floor.
        let map = u64::from(HEADS) * u64::from(SOURCE_POSITIONS) * u64::from(SOURCE_POSITIONS) * 2;
        assert!(bytes > map, "{bytes} bytes is under one score map's {map}");
        assert!(bytes < 128 << 20, "{bytes} bytes is past the guaranteed binding range");
    }

    #[test]
    fn a_decode_step_is_six_layers_of_two_attentions() {
        let plan = plan(Mode::DecodeStep);
        let counts = counts(&plan);
        // Eight int8 convolutions per layer — the four self-attention projections, the
        // cross-attention's query and output, and the two feed-forwards — plus the tied head. The
        // cross-attention's key and value belong to `Mode::Encode`.
        assert_eq!(counts.get("ConvInt8"), Some(&(DECODER_LAYERS * 8 + 1)), "{counts:?}");
        // Three norms per layer, pre-norm, plus the trailing one.
        assert_eq!(counts.get("LayerNorm"), Some(&(DECODER_LAYERS * 3 + 1)), "{counts:?}");
        // Self-attention is cached and single-query; the cross-attention is not.
        assert_eq!(counts.get("AttnScoresCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApplyCached"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&DECODER_LAYERS), "{counts:?}");
        // The self-attention softmax is prefix-bounded; the cross-attention's is not.
        assert_eq!(counts.get("Softmax"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxPrefix"), Some(&DECODER_LAYERS), "{counts:?}");
        assert_eq!(counts.get("CacheWrite"), Some(&(DECODER_LAYERS * 2)), "{counts:?}");
        // Three residuals per layer — half of which fold into their producing
        // convolution's store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&9), "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_takes_the_token_and_the_cross_caches_only() {
        let plan = plan(Mode::DecodeStep);
        // The token and twelve cross caches. The self-attention caches are on the device now, so
        // they are neither uploaded nor read back - which was 18.4 MB per step.
        assert_eq!(plan.inputs.len(), 1 + DECODER_LAYERS * 2);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        for binding in &plan.inputs[1..] {
            // Channel-major, because a single-query cross-attention reads a sequence directly.
            assert_eq!(binding.shape, Shape::new(D_MODEL, 1, SOURCE_POSITIONS));
        }
        // The logits, and nothing else.
        assert_eq!(plan.outputs.len(), 1);
        assert_eq!(plan.outputs[0].shape, Shape::new(VOCAB, 1, 1));
    }

    #[test]
    fn the_decode_plan_does_not_depend_on_the_step() {
        // One key for every token, so `Reshaped::at` matches after the first step and the plan is
        // never re-recorded. The caches keep their arena offsets for the same reason.
        let first = plan(Mode::DecodeStep);
        let again = plan(Mode::DecodeStep);
        assert_eq!(first.ops, again.ops);
        assert_eq!(first.arena_elems, again.arena_elems);
    }

    #[test]
    fn the_self_caches_are_built_for_the_longest_transcript() {
        let plan = plan(Mode::DecodeStep);
        let mut writes = 0;
        for op in &plan.ops {
            match op {
                Op::Dispatch { kind: Kind::CacheWrite, push, .. } => {
                    writes += 1;
                    assert_eq!(push.in_h, MAX_POSITIONS, "{push:?}");
                    assert_eq!(push.count, D_MODEL, "{push:?}");
                }
                // Self-attention: the map is as wide as the cache, bounded by the step.
                Op::Dispatch { kind: Kind::AttnScoresCached, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, MAX_POSITIONS), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "{push:?}");
                }
                Op::Dispatch { kind: Kind::AttnApplyCached, push, .. } => {
                    assert_eq!((push.in_w, push.out_c), (MAX_POSITIONS, D_MODEL), "{push:?}");
                    assert_ne!(push.dyn_keys, 0, "{push:?}");
                }
                // Cross-attention: one query over all 1500 encoder positions, fixed.
                Op::Dispatch { kind: Kind::AttnScores, push, .. } => {
                    assert_eq!((push.out_h, push.out_w), (1, SOURCE_POSITIONS), "{push:?}");
                    assert_eq!(push.dyn_keys, 0, "{push:?}");
                }
                _ => {}
            }
        }
        assert_eq!(writes, DECODER_LAYERS * 2, "a K and a V per layer");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_decode_step_is_single_position_so_its_int8_work_is_a_gemv() {
        // Why `Kind::ConvVecInt8` exists, and why it matters more here than in SMaLL-100: *every*
        // int8 convolution in a whisper decode step is one position, including the 26.6 MB head,
        // because the cross-attention's multi-position key and value projections moved to the
        // encoder pass.
        let plan = plan(Mode::DecodeStep);
        let int8: Vec<(Kind, super::super::Push)> = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { kind, push, .. }
                    if super::super::tests::name_of(*kind) == "ConvInt8" =>
                {
                    Some((*kind, *push))
                }
                _ => None,
            })
            .collect();
        assert_eq!(int8.len(), DECODER_LAYERS * 8 + 1);
        for (kind, push) in &int8 {
            assert_eq!(*kind, Kind::ConvVecInt8, "{push:?}");
            assert_eq!(push.out_h * push.out_w, 1, "{push:?}");
        }
    }

    #[test]
    fn the_head_is_one_binding_under_the_guaranteed_range() {
        // Why whisper needs no class split where SMaLL-100 does: 51,865 x 512 int8 is 25.3 MiB
        // against a guaranteed 128 MiB, and even fp16 would fit.
        let whole = u64::from(VOCAB) * u64::from(D_MODEL);
        assert!(whole < 32 << 20, "the head is {whole} bytes");
        let plan = plan(Mode::DecodeStep);
        let heads: Vec<&Op> = plan
            .ops
            .iter()
            .filter(|op| matches!(op, Op::Dispatch { push, .. } if push.out_c == VOCAB))
            .collect();
        assert_eq!(heads.len(), 1, "{heads:?}");
    }

    #[test]
    fn the_position_table_bounds_the_cache() {
        // The decode plan is recorded once at `MAX_POSITIONS`, so a step past the end is no longer
        // a build error - `cache_write.comp` refuses the write instead. The host is what must
        // stop, so this pins the two numbers that let it: the cache is exactly as long as the
        // position table the embedding can index.
        let plan = plan(Mode::DecodeStep);
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::CacheWrite, push, .. } = op {
                assert_eq!(push.in_h, MAX_POSITIONS, "{push:?}");
            }
        }
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_whisper` writes, and `Layers` walks it.
        // This checks the two agree for every index, which is what makes `host_tensor`'s shape check
        // meaningful rather than circular.
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![D_MODEL], vec![D_MODEL]];
        let layer = |cross: bool| {
            let mut out = norm();
            for _ in 0..4 {
                out.extend(projection(D_MODEL, D_MODEL));
            }
            if cross {
                out.extend(norm());
                for _ in 0..4 {
                    out.extend(projection(D_MODEL, D_MODEL));
                }
            }
            out.extend(norm());
            out.extend(projection(FFN, D_MODEL));
            out.extend(projection(D_MODEL, FFN));
            out
        };

        let mut expected: Vec<Vec<u32>> = vec![
            vec![D_MODEL, MELS, 1, CONV_KERNEL],
            vec![D_MODEL],
            vec![D_MODEL],
            vec![D_MODEL, D_MODEL, 1, CONV_KERNEL],
            vec![D_MODEL],
            vec![D_MODEL],
            vec![D_MODEL, 1, SOURCE_POSITIONS],
        ];
        for _ in 0..ENCODER_LAYERS {
            expected.extend(layer(false));
        }
        expected.extend(norm());
        expected.extend(projection(VOCAB, D_MODEL));
        expected.push(vec![MAX_POSITIONS, D_MODEL]);
        for _ in 0..DECODER_LAYERS {
            expected.extend(layer(true));
        }
        expected.extend(norm());

        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
}
