#[cfg(test)]
mod tests {
    use super::super::tests::{assert_no_aliasing, Shapes};
    use super::super::{Kind, Op};
    use super::*;

    /// `<|startoftext|>`, three pieces and `<|endoftext|>`.
    const LEN: u32 = 5;

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
        // The numbers `maml_convert.py --graph tinyclip --print-layers` reports. A disagreement
        // here is a plan that reads one layer's weights as another's.
        assert_eq!(LAYER_TENSORS, 22);
        assert_eq!((PATCH_CONV, CLASS_TOKEN, IMAGE_POSITIONS, PRE_NORM), (0, 3, 4, 5));
        assert_eq!((VISION, POST_NORM, VISUAL_PROJECTION), (7, 227, 229));
        assert_eq!((TOKENS, TEXT_POSITIONS, TEXT), (232, 234, 235));
        assert_eq!((FINAL_NORM, TEXT_PROJECTION), (301, 303));
        assert_eq!(TENSORS, 306);
        assert_eq!(INT8_CONVS, 81);
        assert_eq!((GRID, VISION_POSITIONS), (14, 197));
    }

    #[test]
    fn the_parameter_total_matches_the_export() {
        // What the file holds, from the layout `every_tensor_shape_is_stated_the_same_way_twice`
        // pins, against the export plus exactly the two things quantising adds.
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();

        // One fp16 scale per output channel of each of the 81 int8 convolutions, plus one per row
        // of the token embedding.
        let layer_scales = u64::from(4 * WIDTH + FFN + WIDTH);
        let scales = u64::from(WIDTH)
            + (VISION_LAYERS + TEXT_LAYERS) as u64 * layer_scales
            + 2 * u64::from(PROJECTION)
            + u64::from(VOCAB);
        assert_eq!(scales, 80_640);
        // And a zero bias for the patch embedding and each projection head, none of which has one.
        let synthesised = u64::from(WIDTH) + 2 * u64::from(PROJECTION);

        assert_eq!(total, 23_446_016 + scales + synthesised);
    }

    #[test]
    fn the_file_is_the_size_the_asset_is() {
        // 23,734,912 bytes, which `maml_convert.py` prints and the checked-in asset is. Everything
        // but the 82 int8 tensors is fp16, and they are 99.5% of the elements — which is why the
        // file is barely over the parameter count in bytes.
        let int8 = u64::from(WIDTH) * 3 * u64::from(PATCH) * u64::from(PATCH)
            + (VISION_LAYERS + TEXT_LAYERS) as u64
                * u64::from(WIDTH)
                * u64::from(4 * WIDTH + 2 * FFN)
            + 2 * u64::from(PROJECTION) * u64::from(WIDTH)
            + u64::from(VOCAB) * u64::from(WIDTH);
        assert_eq!(int8, 23_330_816);
        let total: u64 = (0..TENSORS)
            .map(|index| dims_of(index).iter().map(|&d| u64::from(d)).product::<u64>())
            .sum();
        let file = int8 + (total - int8) * 2;
        // Plus the 64-byte header, a 32-byte table entry each, and up to 15 bytes of padding per
        // tensor. The asset is 23,734,912.
        let overhead = 64 + TENSORS as u64 * 32;
        assert!(
            (file + overhead..file + overhead + TENSORS as u64 * 16).contains(&23_734_912),
            "{file} + {overhead} against 23,734,912"
        );
    }

    /// The tensor range each pass reads on the device. Everything else it names.
    ///
    /// Stated here rather than returned by [`build`] because it is the thing under test: a pass
    /// that read the wrong range would name the right one and still be wrong.
    fn read_by(mode: Mode) -> std::ops::Range<usize> {
        match mode {
            Mode::Image => PATCH_CONV..TOKENS,
            Mode::Text { .. } => TEXT..TENSORS,
        }
    }

    #[test]
    fn the_passes_cover_the_file_and_every_one_of_them_builds() {
        // `Builder::finish` only checks that a tensor is read *or* named, so this is what stops
        // naming being used to hide a layer the device never touches. Together the two passes and
        // the host gather must account for every index.
        let mut covered = std::collections::BTreeSet::new();
        for mode in [Mode::Image, Mode::Text { len: 1 }, Mode::Text { len: LEN }] {
            plan(mode);
            covered.extend(read_by(mode));
        }
        // The three the host reads and no shader does: the token embedding, its scale, and the
        // text position table. `embed_positions` is the only reader of all three.
        covered.extend(TOKENS..TEXT);
        assert_eq!(covered.into_iter().collect::<Vec<_>>(), (0..TENSORS).collect::<Vec<_>>());
    }

    #[test]
    fn the_vision_tower_is_ten_pre_norm_layers_over_a_patch_grid() {
        let plan = plan(Mode::Image);
        let counts = counts(&plan);
        // Six int8 convolutions per layer, plus the patch embedding and the projection head.
        assert_eq!(counts.get("ConvInt8"), Some(&(VISION_LAYERS * 6 + 2)), "{counts:?}");
        // Three norms per layer would be post-norm. Pre-norm is two, plus `pre_layrnorm` and
        // `post_layernorm`.
        assert_eq!(counts.get("LayerNorm"), Some(&(VISION_LAYERS * 2 + 2)), "{counts:?}");
        assert_eq!(counts.get("AttnScores"), Some(&VISION_LAYERS), "{counts:?}");
        assert_eq!(counts.get("AttnApply"), Some(&VISION_LAYERS), "{counts:?}");
        // Not causal: an image's patches all see each other.
        assert_eq!(counts.get("Softmax"), Some(&VISION_LAYERS), "{counts:?}");
        assert_eq!(counts.get("SoftmaxCausal"), None, "{counts:?}");
        // Two residuals per layer, plus the position table — minus the ten whose
        // skip side is already written when the producing convolution runs, which
        // fold into its store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&11), "{counts:?}");
        assert_eq!(counts.get("Constant"), Some(&2), "{counts:?}");
        assert_eq!(counts.len(), 7, "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_text_tower_is_three_causal_layers() {
        let plan = plan(Mode::Text { len: LEN });
        let counts = counts(&plan);
        assert_eq!(counts.get("ConvInt8"), Some(&(TEXT_LAYERS * 6 + 1)), "{counts:?}");
        assert_eq!(counts.get("LayerNorm"), Some(&(TEXT_LAYERS * 2 + 1)), "{counts:?}");
        // Every softmax is causal, and there is no plain one anywhere in this pass. A single plain
        // one would let one layer read the future, which is fluent and wrong.
        assert_eq!(counts.get("SoftmaxCausal"), Some(&TEXT_LAYERS), "{counts:?}");
        assert_eq!(counts.get("Softmax"), None, "{counts:?}");
        // Two residuals per layer, half of which fold into their producing
        // convolution's store. See `Builder::add`.
        assert_eq!(counts.get("Add"), Some(&3), "{counts:?}");
        // No class token and no device-side position table: the host built the input.
        assert_eq!(counts.get("Constant"), None, "{counts:?}");
        assert_eq!(counts.len(), 6, "{counts:?}");
        assert_no_aliasing(&plan);
    }

    #[test]
    fn the_patch_embedding_is_a_stride_sixteen_convolution_over_the_whole_image() {
        // The one convolution here that is not `1 x 1`. A wrong stride gives the right rank and the
        // wrong grid, and nothing downstream checks the sequence length against 197.
        let plan = plan(Mode::Image);
        let patch = plan
            .ops
            .iter()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::ConvInt8, push, .. } if push.in_c == 3 => Some(*push),
                _ => None,
            })
            .expect("the patch embedding");
        assert_eq!((push_kernel(&patch), push_stride(&patch)), ((PATCH, PATCH), (PATCH, PATCH)));
        assert_eq!((patch.pad_t, patch.pad_l), (0, 0), "{patch:?}");
        assert_eq!((patch.in_h, patch.in_w), (IMAGE_SIZE, IMAGE_SIZE), "{patch:?}");
        assert_eq!((patch.out_c, patch.out_h, patch.out_w), (WIDTH, GRID, GRID), "{patch:?}");
    }

    fn push_kernel(push: &super::super::Push) -> (u32, u32) {
        (push.kh, push.kw)
    }

    fn push_stride(push: &super::super::Push) -> (u32, u32) {
        (push.stride_h, push.stride_w)
    }

    #[test]
    fn the_class_token_is_prepended_and_the_positions_are_added_after() {
        // 197 = 1 + 14 * 14, and the class token is position **0**. Appending it instead would put
        // the pooled vector at the end and shift every position embedding by one — a change no
        // shape catches.
        let plan = plan(Mode::Image);
        let copies: Vec<(u32, u32, u32)> = plan
            .ops
            .iter()
            .filter_map(|op| match *op {
                Op::Copy { src, dst, elems } => Some((src, dst, elems)),
                _ => None,
            })
            .collect();
        // The reshape is one copy of the whole grid; the position concat is one run per channel per
        // part, so `WIDTH` of one element and `WIDTH` of 196.
        let single: Vec<_> = copies.iter().filter(|(_, _, elems)| *elems == 1).collect();
        let runs: Vec<_> = copies.iter().filter(|(_, _, elems)| *elems == GRID * GRID).collect();
        let whole: Vec<_> =
            copies.iter().filter(|(_, _, elems)| *elems == WIDTH * GRID * GRID).collect();
        assert_eq!(single.len(), WIDTH as usize, "{copies:?}");
        assert_eq!(runs.len(), WIDTH as usize, "{copies:?}");
        // And the reshape, which is one contiguous move of the whole grid because
        // `[WIDTH, GRID, GRID]` and `[WIDTH, 1, GRID * GRID]` are the same bytes.
        assert_eq!(whole.len(), 1, "{copies:?}");
        assert_eq!(copies.len(), 2 * WIDTH as usize + 1, "{copies:?}");
        // The class token's copies land at stride 197 starting at the sequence's base, and the
        // patch runs land one column later.
        let base = single.iter().map(|(_, dst, _)| *dst).min().expect("a class copy");
        for (index, (_, dst, _)) in single.iter().enumerate() {
            assert_eq!(*dst, base + index as u32 * VISION_POSITIONS, "{copies:?}");
        }
        for (index, (_, dst, _)) in runs.iter().enumerate() {
            assert_eq!(*dst, base + 1 + index as u32 * VISION_POSITIONS, "{copies:?}");
        }

        // And the position table is added to the 197-long sequence, not the 196-long grid.
        let added = plan
            .ops
            .iter()
            .find_map(|op| match op {
                Op::Dispatch { kind: Kind::Add, push, .. } if push.out_w == VISION_POSITIONS => {
                    Some(*push)
                }
                _ => None,
            })
            .expect("the position add");
        assert_eq!(added.out_c, WIDTH, "{added:?}");
    }

    #[test]
    fn both_towers_project_every_position_so_the_host_can_pool_one() {
        // The vision tower's embedding is column 0 and the text tower's the last column, and
        // neither is contiguous — so the projection runs over the whole sequence and the host picks.
        // Swapping which column is read is the mistake this documents; nothing here can catch it,
        // which is why both output widths are pinned.
        let image = plan(Mode::Image);
        assert_eq!(image.input().unwrap().shape, Shape::new(3, IMAGE_SIZE, IMAGE_SIZE));
        assert_eq!(image.output().unwrap().shape, Shape::new(PROJECTION, 1, VISION_POSITIONS));

        let text = plan(Mode::Text { len: LEN });
        assert_eq!(text.input().unwrap().shape, Shape::new(WIDTH, 1, LEN));
        assert_eq!(text.output().unwrap().shape, Shape::new(PROJECTION, 1, LEN));
    }

    #[test]
    fn the_text_score_maps_are_square_so_the_causal_mask_means_something() {
        // A causal mask is a statement about one sequence attending to itself, so the map has to be
        // `[heads, T, T]`. `Builder::softmax_causal` refuses anything else; this is what says the
        // tower never asks it to.
        let plan = plan(Mode::Text { len: LEN });
        for op in &plan.ops {
            if let Op::Dispatch { kind: Kind::SoftmaxCausal, push, .. } = op {
                assert_eq!((push.out_c, push.out_h, push.out_w), (HEADS, LEN, LEN), "{push:?}");
            }
        }
    }

    #[test]
    fn a_single_token_query_still_builds() {
        // `len = eot + 1`, and the shortest possible query is `<|startoftext|><|endoftext|>` at
        // `eot = 1`. A length of 1 is what a caller passing `eot` rather than `eot + 1` on that
        // query would produce, so it has to build rather than panic — the causal softmax's row-0
        // distribution is `[1]`, which is exactly the fixture in `nets::reference`.
        let plan = plan(Mode::Text { len: 1 });
        assert_eq!(plan.output().unwrap().shape, Shape::new(PROJECTION, 1, 1));
        assert_no_aliasing(&plan);
    }

    #[test]
    fn a_pass_over_nothing_or_past_the_table_is_refused() {
        let source = Shapes::new(TENSORS);
        let error = build(&source, Mode::Text { len: 0 }).expect_err("no tokens");
        assert!(error.contains("no tokens"), "{error}");
        let source = Shapes::new(TENSORS);
        let error =
            build(&source, Mode::Text { len: CONTEXT + 1 }).expect_err("too long");
        assert!(error.contains("positions the model has"), "{error}");
    }

    #[test]
    fn every_tensor_shape_is_stated_the_same_way_twice() {
        // `dims_of` restates the table `maml_convert.collect_tinyclip` writes, and `Layers` walks
        // it. This checks the two agree for every index, which is what makes `host_tensor`'s shape
        // check meaningful rather than circular.
        let projection = |out: u32, inputs: u32| {
            vec![vec![out, inputs, 1, 1], vec![out], vec![out]]
        };
        let norm = || vec![vec![WIDTH], vec![WIDTH]];
        let layer = || {
            let mut out = norm();
            for _ in 0..4 {
                out.extend(projection(WIDTH, WIDTH));
            }
            out.extend(norm());
            out.extend(projection(FFN, WIDTH));
            out.extend(projection(WIDTH, FFN));
            out
        };

        let mut expected: Vec<Vec<u32>> = Vec::new();
        expected.extend(projection(WIDTH, 3));
        // The patch kernel is the one that is not `1 x 1`, so its dims are restated by hand.
        expected[0] = vec![WIDTH, 3, PATCH, PATCH];
        expected.push(vec![WIDTH, 1, 1]);
        expected.push(vec![WIDTH, 1, VISION_POSITIONS]);
        expected.extend(norm());
        for _ in 0..VISION_LAYERS {
            expected.extend(layer());
        }
        expected.extend(norm());
        expected.extend(projection(PROJECTION, WIDTH));
        // The token embedding is a pair: kernel and per-row scale, and no bias.
        expected.push(vec![VOCAB, WIDTH, 1, 1]);
        expected.push(vec![VOCAB]);
        expected.push(vec![CONTEXT, WIDTH]);
        for _ in 0..TEXT_LAYERS {
            expected.extend(layer());
        }
        expected.extend(norm());
        expected.extend(projection(PROJECTION, WIDTH));

        assert_eq!(expected.len(), TENSORS);
        for (index, want) in expected.iter().enumerate() {
            assert_eq!(&dims_of(index), want, "tensor {index}");
        }
    }
}
