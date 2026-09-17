
    #[test]
    fn softmax_subtracts_the_row_maximum_rather_than_exponentiating_directly() {
        // exp overflows fp32 a little past 88, so a row containing 100 sums to infinity
        // and every probability in it becomes a NaN. Subtracting the maximum first makes
        // the largest term exp(0), which cannot overflow and also floors the denominator
        // at 1.
        let got = one(Shape::new(1, 1, 3), &[100.0, 99.0, -100.0], &[], |b, x| b.softmax(x));
        assert!(got.iter().all(|v| v.is_finite()), "{got:?}");
        let expected = 1.0 / (1.0 + (-1.0f32).exp());
        close(&got, &[expected, 1.0 - expected, 0.0]);
    }

    #[test]
    fn a_causal_softmax_gives_position_zero_a_point_distribution() {
        // The fixture that fails if the row bound is off by one in either direction. One head,
        // T 3, so three rows of three: query 0 sees key 0 alone, query 1 keys 0-1, query 2 all
        // three.
        //
        // Row 0's scores are (0, 100, 100). Its distribution must be exactly (1, 0, 0) — a bound
        // of `query + 2` would let the 100 in and give (0, 1, 0), and a bound of `query` would
        // leave an empty row and divide by zero.
        let got = one(
            Shape::new(1, 3, 3),
            &[
                0.0, 100.0, 100.0, //
                1.0, 2.0, 100.0, //
                5.0, 5.0, 5.0,
            ],
            &[],
            |b, x| b.softmax_causal(x),
        );
        let pair = (-1.0f32).exp() / (1.0 + (-1.0f32).exp());
        close(
            &got,
            &[
                1.0, 0.0, 0.0, //
                pair, 1.0 - pair, 0.0, //
                1.0 / 3.0, 1.0 / 3.0, 1.0 / 3.0,
            ],
        );
        // Every row is still a distribution, masked tail and all — which is what says the divisor
        // was the truncated sum rather than the whole row's.
        for (row, values) in got.chunks_exact(3).enumerate() {
            let total: f32 = values.iter().sum();
            assert!((total - 1.0).abs() < 2e-3, "row {row} sums to {total}");
        }
    }

    #[test]
    fn a_causal_softmax_masks_within_each_head_rather_than_across_the_map() {
        // Two heads, T 2, so rows are (head 0 query 0), (head 0 query 1), (head 1 query 0),
        // (head 1 query 1). The query index is `row % out_h`, so head 1's first row must be
        // masked exactly as head 0's was; taking the flat row index instead would leave head 1
        // unmasked entirely.
        let got = one(
            Shape::new(2, 2, 2),
            &[
                0.0, 100.0, //
                5.0, 5.0, //
                0.0, 100.0, //
                5.0, 5.0,
            ],
            &[],
            |b, x| b.softmax_causal(x),
        );
        close(&got, &[1.0, 0.0, 0.5, 0.5, 1.0, 0.0, 0.5, 0.5]);
    }

    #[test]
    fn a_position_concat_prepends_a_column_to_every_channel() {
        // TinyCLIP's class token, in miniature: a `[2, 1, 1]` token in front of a `[2, 1, 3]`
        // grid. The position axis is innermost, so this is one run per channel and not one copy —
        // a contiguous concat would give (9, 1, 2, 3, 8, 4, 5, 6) shifted by a channel.
        let given = Given::new(&[]).expect("no tensors");
        let mut builder = Builder::new(&given);
        let token = builder.input(Shape::new(2, 1, 1));
        let grid = builder.input(Shape::new(2, 1, 3));
        let joined = builder.concat_positions(&[token, grid]);
        let plan = builder.finish(&[joined]).expect("the fixture plan builds");
        // No dispatch at all: the whole op is `vkCmdCopyBuffer`, which is the reason it needs no
        // shader and no reference arm of its own.
        assert!(
            plan.ops.iter().all(|op| matches!(op, crate::nets::Op::Copy { .. })),
            "{:?}",
            plan.ops
        );
        let outputs = run_multi(&plan, given.data(), &[&[9.0, 8.0], &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]])
            .expect("the fixture plan runs");
        match outputs.as_slice() {
            [only] => close(only, &[9.0, 1.0, 2.0, 3.0, 8.0, 4.0, 5.0, 6.0]),
            other => panic!("{} outputs", other.len()),
        }
    }

    #[test]
    fn attention_applies_a_row_of_weights_across_the_keys_not_the_queries() {
        // One head, d_model 2, T 2. Weights are `[heads, T, T]` with the key innermost,
        // so query 0 mixes (0.25, 0.75) and query 1 takes key 0 alone.
        //
        // V's columns are (10, 3) and (20, 7). Reading the weight matrix transposed gives
        // 22.5 for the first output instead of 17.5, which is why the rows differ.
        let got = two(
            (Shape::new(1, 2, 2), Shape::new(2, 1, 2)),
            (&[0.25, 0.75, 1.0, 0.0], &[10.0, 20.0, 3.0, 7.0]),
            |b, probs, v| b.attn_apply(probs, v, 1),
        );
        close(&got, &[17.5, 10.0, 6.0, 3.0]);
    }

    #[test]
    fn attention_uses_the_head_only_to_pick_a_plane_of_weights() {
        // Two heads over d_model 4. Head 0's weights are the identity and head 1's swap
        // the two keys, so the expected output is V with its last two channels reversed
        // along T and the first two untouched.
        //
        // This is the claim that makes the head concatenation free: channel `c` of V is
        // channel `c` of the output, and the head index selects nothing but which plane
        // of the score map to read.
        let got = two(
            (Shape::new(2, 2, 2), Shape::new(4, 1, 2)),
            (
                &[1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0],
                &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0],
            ),
            |b, probs, v| b.attn_apply(probs, v, 2),
        );
        close(&got, &[1.0, 2.0, 3.0, 4.0, 6.0, 5.0, 8.0, 7.0]);
    }

    #[test]
    fn a_whole_attention_averages_its_values_when_every_score_is_equal() {
        // The three ops composed. Q and K are zero, so every score is zero, so every
        // softmax row is uniform at 1/T, so each output position is the mean of V over
        // the sequence — the same value at all four positions.
        //
        // That pins all three at once in a way none of them can fake: a missing scale
        // still gives zeros, but a softmax that did not normalise scales the mean by T,
        // and a weighted sum that indexed V by query rather than key returns V itself.
        let got = two(
            (Shape::new(2, 1, 4), Shape::new(2, 1, 4)),
            (
                &[0.0; 8],
                &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            ),
            |b, zeros, v| {
                let scores = b.attn_scores(zeros, zeros, 1);
                let probs = b.softmax(scores);
                b.attn_apply(probs, v, 1)
            },
        );
        close(&got, &[2.5, 2.5, 2.5, 2.5, 25.0, 25.0, 25.0, 25.0]);
    }

    #[test]
    fn a_head_count_that_does_not_divide_the_channels_fails_the_build() {
        // d_model 120 in 8 heads is the recogniser's geometry; anything that does not
        // divide would silently reinterpret the channel runs as overlapping heads.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let q = b.input(Shape::new(6, 1, 2));
        let scores = b.attn_scores(q, q, 4);
        let error = b.finish(&[scores]).expect_err("6 channels in 4 heads");
        assert!(error.contains("do not split into 4 heads"), "{error}");
    }

    #[test]
    fn a_sequence_with_a_height_above_one_is_refused() {
        // `[d_model, 1, T]` is what makes the head split a reinterpretation rather than
        // a copy. A taller tensor would be read as if the extra rows were sequence
        // positions, which is wrong and produces an output of a plausible shape.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let q = b.input(Shape::new(4, 2, 2));
        let scores = b.attn_scores(q, q, 2);
        let error = b.finish(&[scores]).expect_err("a two-row sequence");
        assert!(error.contains("height above"), "{error}");
    }

    #[test]
    fn slicing_channels_takes_a_contiguous_range_and_nothing_else() {
        // Channels 1 and 2 of four. A slice that got the element stride wrong would return
        // the right *count* of values from the wrong place.
        let values: Vec<f32> = vec![
            1.0, 2.0, 3.0, //
            10.0, 20.0, 30.0, //
            100.0, 200.0, 300.0, //
            1000.0, 2000.0, 3000.0,
        ];
        let got = one(Shape::new(4, 1, 3), &values, &[], |b, x| {
            b.slice_channels(x, 1, 2)
        });
        close(&got, &[10.0, 20.0, 30.0, 100.0, 200.0, 300.0]);
    }

    #[test]
    fn slicing_past_the_end_is_refused() {
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let x = b.input(Shape::new(4, 1, 3));
        let out = b.slice_channels(x, 3, 2);
        let error = b.finish(&[out]).expect_err("past the end");
        assert!(error.contains("channels 3..5"), "{error}");
    }

    #[test]
    fn edge_padding_replicates_the_border_instead_of_reading_zeros() {
        // Four positions, a 3-wide kernel of all ones padded by one each side. With zero padding
        // the two end outputs are short a tap; with edge padding the border value is counted
        // twice. This is the whole difference, and it only shows at the ends — which is why
        // getting it wrong is an audible click at the start and finish of an utterance and
        // nothing anywhere else.
        let values = [1.0f32, 2.0, 3.0, 4.0];
        let weights = &[(vec![1u32, 1, 1, 3], vec![1.0, 1.0, 1.0]), (vec![1], vec![0.0])];
        let zero = one(Shape::new(1, 1, 4), &values, weights, |b, x| {
            b.conv(x, 0, 1, (1, 3), (1, 1), (1, 1), (0, 1, 0, 1), 1, Act::None)
        });
        // 0+1+2, 1+2+3, 2+3+4, 3+4+0
        close(&zero, &[3.0, 6.0, 9.0, 7.0]);

        let edge = one(Shape::new(1, 1, 4), &values, weights, |b, x| {
            b.edge_padding();
            b.conv(x, 0, 1, (1, 3), (1, 1), (1, 1), (0, 1, 0, 1), 1, Act::None)
        });
        // 1+1+2, 1+2+3, 2+3+4, 3+4+4
        close(&edge, &[4.0, 6.0, 9.0, 11.0]);
        // And the interior is identical, which is what makes the failure mode so quiet.
        assert_eq!(zero[1], edge[1]);
        assert_eq!(zero[2], edge[2]);
    }

    #[test]
    fn edge_padding_survives_a_kernel_wider_than_the_input() {
        // The vocoder dilates a 7-tap kernel by up to 4, so a tap can be 12 positions outside a
        // short sequence. Clamping must saturate rather than wrap.
        let values = [5.0f32, 6.0];
        let got = one(
            Shape::new(1, 1, 2),
            &values,
            &[(vec![1u32, 1, 1, 3], vec![1.0, 1.0, 1.0]), (vec![1], vec![0.0])],
            |b, x| {
                b.edge_padding();
                b.conv(x, 0, 1, (1, 3), (1, 1), (4, 4), (0, 4, 0, 4), 1, Act::None)
            },
        );
        // Every tap lands 4 away, so both outputs read only the clamped ends: 5 + x + 6.
        close(&got, &[5.0 + 5.0 + 6.0, 5.0 + 6.0 + 6.0]);
    }

    #[test]
    fn an_elementwise_product_multiplies_position_by_position() {
        // Distinct from `mul_channel`, which broadcasts `[C, 1, 1]`. Two channels of three
        // positions, with values chosen so a broadcast would give a different answer: if this
        // took only channel 0 of `b` it would produce 2, 6, 12 in the second row.
        let got = two(
            (Shape::new(2, 1, 3), Shape::new(2, 1, 3)),
            (&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0], &[2.0, 3.0, 4.0, 5.0, 6.0, 7.0]),
            |b, a, c| b.mul(a, c),
        );
        close(&got, &[2.0, 6.0, 12.0, 20.0, 30.0, 42.0]);
    }

    #[test]
    fn an_elementwise_product_of_mismatched_shapes_is_refused() {
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let a = b.input(Shape::new(2, 1, 3));
        let c = b.tensor(Shape::new(2, 1, 4));
        let out = b.mul(a, c);
        let error = b.finish(&[out]).expect_err("mismatched shapes");
        assert!(error.contains("mul of"), "{error}");
    }

    #[test]
    fn gelu_matches_the_exact_erf_form() {
        // Pinned against the exact `0.5 x (1 + erf(x / sqrt(2)))`, which is what the export
        // computes. The tanh approximation would also pass at this tolerance - measured, the two
        // forms differ by at most 4.7e-4 against fp16's 2.0e-3 step - so this test does not
        // distinguish them, and the doc on `Act::Gelu` says why `erf` is used anyway. What it
        // does pin is that the activation is a GELU at all, symmetric about the right place, and
        // saturating to the identity and to zero at the tails.
        let xs = [-6.0f32, -2.0, -1.0, 0.0, 0.5, 1.0, 2.0, 6.0];
        // A 1x1 convolution with weight 1 and bias 0 is the identity, so this measures the
        // activation alone. It also routes through `ConvPoint`, so the tiled path carries it.
        let got = one(
            Shape::new(1, 1, xs.len() as u32),
            &xs,
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv(x, 0, 1, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu),
        );
        let want = [
            -0.0000000f32,
            -0.04550027,
            -0.15865526,
            0.0,
            0.34573123,
            0.8413447,
            1.9544997,
            6.0,
        ];
        for (i, (&g, &w)) in got.iter().zip(&want).enumerate() {
            assert!((g - w).abs() < 2e-3, "at x={}: {g} not {w}", xs[i]);
        }
    }

    #[test]
    fn an_embedding_gathers_the_row_each_id_names() {
        // Four symbols of three channels, looked up in an order that is neither ascending
        // nor a permutation — 2, 0, 3, 0 — so a lookup that ignored the ids and copied the
        // table straight through would be visible.
        let table: Vec<f32> = vec![
            10.0, 11.0, 12.0, //
            20.0, 21.0, 22.0, //
            30.0, 31.0, 32.0, //
            40.0, 41.0, 42.0,
        ];
        let got = one(
            Shape::new(1, 1, 4),
            &[2.0, 0.0, 3.0, 0.0],
            &[(vec![4, 3], table)],
            |b, ids| b.embed(ids, 0, 4, 3),
        );
        // Channel-major, so all four positions of channel 0, then channel 1, then 2.
        close(
            &got,
            &[
                30.0, 10.0, 40.0, 10.0, //
                31.0, 11.0, 41.0, 11.0, //
                32.0, 12.0, 42.0, 12.0,
            ],
        );
    }

    #[test]
    fn an_embedding_id_survives_the_round_trip_through_fp16() {
        // Ids travel in the arena as fp16 like everything else. That is exact here only
        // because fp16 holds every integer to 2048 and a phoneme table has 130 rows; the
        // highest id must come back as itself and not as its neighbour.
        let rows = 130u32;
        let table: Vec<f32> = (0..rows).map(|r| r as f32).collect();
        let ids: Vec<f32> = vec![0.0, 1.0, 64.0, 127.0, 128.0, 129.0];
        let got = one(
            Shape::new(1, 1, ids.len() as u32),
            &ids,
            &[(vec![rows, 1], table)],
            |b, x| b.embed(x, 0, rows, 1),
        );
        close(&got, &ids);
    }

    #[test]
    fn an_embedding_id_past_the_table_clamps_rather_than_reading_on() {
        // A phonemiser that emitted an unknown symbol should mispronounce a word, not
        // sample whatever weights happen to follow the table.
        let table: Vec<f32> = vec![7.0, 8.0, 9.0];
        let got = one(
            Shape::new(1, 1, 3),
            &[0.0, 2.0, 99.0],
            &[(vec![3, 1], table)],
            |b, ids| b.embed(ids, 0, 3, 1),
        );
        close(&got, &[7.0, 9.0, 9.0]);
    }

    #[test]
    fn an_embedding_over_a_multi_channel_id_tensor_is_refused() {
        // Ids are one per position. A `[2, 1, T]` input would silently embed only the first
        // channel and drop the second.
        let source = Given::new(&[(vec![4, 3], vec![0.0; 12])]).expect("the fixture lays out");
        let mut b = Builder::new(&source);
        let ids = b.input(Shape::new(2, 1, 4));
        let out = b.embed(ids, 0, 4, 3);
        let error = b.finish(&[out]).expect_err("two channels of ids");
        assert!(error.contains("one per position"), "{error}");
    }

    #[test]
    fn a_two_lane_id_reaches_a_row_fp16_cannot_name() {
        // Supertonic has 8,322 symbols, so ids past 2048 arrive as `lo + 2048 * hi`. Each
        // row here carries its own index back as that same pair, both halves exact in fp16,
        // so a lane that was dropped, swapped or scaled shows up as the wrong row.
        let rows = 8322u32;
        let mut table = Vec::with_capacity(rows as usize * 2);
        for row in 0..rows {
            table.push((row / EMBED_LANE) as f32);
            table.push((row % EMBED_LANE) as f32);
        }
        let ids = [0u32, 1, 2047, 2048, 2049, 4096, 8321];
        let got = one(
            Shape::new(2, 1, ids.len() as u32),
            &embed_lanes(&ids),
            &[(vec![rows, 2], table)],
            |b, x| b.embed(x, 0, rows, 2),
        );
        let want: Vec<f32> = ids
            .iter()
            .map(|&id| (id / EMBED_LANE) as f32)
            .chain(ids.iter().map(|&id| (id % EMBED_LANE) as f32))
            .collect();
        close(&got, &want);
    }

    #[test]
    fn a_big_table_refuses_ids_in_one_lane() {
        // The failure this guards is silent otherwise: id 4000 in a single fp16 lane comes
        // back as 4000 exactly, but 4001 comes back as 4000, so the net reads a plausible
        // wrong row instead of failing.
        let rows = EMBED_LANE + 1;
        let source = Given::new(&[(vec![rows, 1], vec![0.0; rows as usize])]).expect("the fixture lays out");
        let mut b = Builder::new(&source);
        let ids = b.input(Shape::new(1, 1, 4));
        let out = b.embed(ids, 0, rows, 1);
        let error = b.finish(&[out]).expect_err("one lane for a big table");
        assert!(error.contains("two lanes"), "{error}");
    }

    #[test]
    fn a_score_map_over_two_different_lengths_is_queries_by_keys() {
        // Three queries against five keys, one head, two channels. Cross-attention: the map is
        // `[1, 3, 5]` and not square, and the two operands are strided by their own lengths —
        // reading K with Q's stride is the failure this pins, and it would still produce a
        // tensor of the right shape.
        let queries = 3u32;
        let keys = 5u32;
        // Q channel 0 is 1 everywhere and channel 1 counts the query; K is the mirror, so
        // `Q . K = 1 * 1 + query * key` and every pair is distinguishable.
        let q: Vec<f32> = (0..queries)
            .map(|_| 1.0)
            .chain((0..queries).map(|i| i as f32))
            .collect();
        let k: Vec<f32> = (0..keys)
            .map(|_| 1.0)
            .chain((0..keys).map(|j| j as f32))
            .collect();
        let got = two(
            (Shape::new(2, 1, queries), Shape::new(2, 1, keys)),
            (&q, &k),
            |b, q, k| b.attn_scores(q, k, 1),
        );
        // One head of two channels, so the scale is `1 / sqrt(2)`.
        let scale = 1.0 / 2.0f32.sqrt();
        let want: Vec<f32> = (0..queries)
            .flat_map(|i| (0..keys).map(move |j| (1.0 + (i * j) as f32) * scale))
            .collect();
        close(&got, &want);
    }