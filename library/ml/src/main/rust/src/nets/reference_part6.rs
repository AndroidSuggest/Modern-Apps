
    #[test]
    fn a_nearest_resize_upsamples_both_axes_together() {
        // A 2x2 doubled to 4x4. Each source pixel becomes a 2x2 block, so a row/column
        // transposition in the index arithmetic changes the answer.
        let got = one(Shape::new(1, 2, 2), &[1.0, 2.0, 3.0, 4.0], &[], |b, x| {
            let like = b.resize_to(x, 4, 4);
            b.resize_nearest_like(x, like)
        });
        close(
            &got,
            &[
                1.0, 1.0, 2.0, 2.0, //
                1.0, 1.0, 2.0, 2.0, //
                3.0, 3.0, 4.0, 4.0, //
                3.0, 3.0, 4.0, 4.0,
            ],
        );
    }

    #[test]
    fn a_plan_can_declare_more_than_one_input_and_output() {
        // SCRFD needs nine outputs and Supertonic's sampler seven inputs, so the
        // plan's bindings are lists. This checks both ends: two inputs land at distinct
        // arena offsets, and two outputs come back in the order `finish` was given.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let first = b.input(Shape::new(1, 1, 2));
        let second = b.input(Shape::new(1, 1, 2));
        let sum = b.add(first, second);
        let doubled = b.add(sum, sum);
        let plan = b.finish(&[doubled, sum]).expect("builds");

        assert_eq!(plan.inputs.len(), 2);
        assert_ne!(
            plan.inputs.first().map(|b| b.at),
            plan.inputs.get(1).map(|b| b.at),
            "the two inputs share an offset"
        );
        let got = run_multi(&plan, given.data(), &[&[1.0, 2.0], &[10.0, 20.0]])
            .expect("the two-input plan runs");
        assert_eq!(got, vec![vec![22.0, 44.0], vec![11.0, 22.0]]);
    }

    #[test]
    fn a_single_output_helper_refuses_a_multi_output_plan() {
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let first = b.input(Shape::new(1, 1, 1));
        let same = b.add(first, first);
        let plan = b.finish(&[same, first]).expect("builds");
        let error = run(&plan, given.data(), &[1.0]).expect_err("two outputs");
        assert!(error.contains("2 outputs"), "{error}");
    }

    #[test]
    fn an_affine_scales_and_shifts_every_element_by_the_same_scalars() {
        let got = one(Shape::new(1, 1, 4), &[-2.0, 0.0, 1.0, 4.0], &[], |b, x| {
            b.affine(x, 0.5, 3.0)
        });
        close(&got, &[2.0, 3.0, 3.5, 5.0]);
    }

    #[test]
    fn an_affine_carries_its_scalars_through_the_push_block_as_bits() {
        // The two parameters are `f32` bits in a `u32` field so `Push` stays all-`u32`.
        // A value with a non-trivial mantissa catches a reinterpretation that happens to
        // work for small integers.
        let got = one(Shape::new(1, 1, 2), &[1.0, 2.0], &[], |b, x| {
            b.affine(x, 0.3, -0.7)
        });
        close(&got, &[0.3 - 0.7, 0.6 - 0.7]);
    }

    #[test]
    fn clip01_clamps_to_the_unit_interval() {
        // A normalised HardSigmoid: `ppocr_fold.py` folds alpha and beta into the
        // convolution, so what reaches the shader is the bare clamp.
        let got = one(
            Shape::new(1, 1, 5),
            &[-4.0, -0.5, 0.25, 1.0, 9.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Clip01),
        );
        close(&got, &[0.0, 0.0, 0.25, 1.0, 1.0]);
    }

    #[test]
    fn a_folded_hard_sigmoid_matches_the_onnx_definition() {
        // The fold's claim: `clamp(alpha * (w*x + b) + beta, 0, 1)` equals
        // `clamp(w'*x + b', 0, 1)` with `w' = alpha*w` and `b' = alpha*b + beta`. Checked
        // here against ONNX's formula computed directly, at both alphas PP-OCRv5 uses.
        for alpha in [0.2f32, 1.0 / 6.0] {
            let (w, bias) = (2.0f32, -0.5f32);
            let inputs = [-3.0f32, -0.4, 0.0, 0.9, 5.0];
            let got = one(
                Shape::new(1, 1, 5),
                &inputs,
                &[
                    (vec![1, 1, 1, 1], vec![alpha * w]),
                    (vec![1], vec![alpha * bias + 0.5]),
                ],
                |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Clip01),
            );
            let want: Vec<f32> = inputs
                .iter()
                .map(|&x| (alpha * (w * x + bias) + 0.5).clamp(0.0, 1.0))
                .collect();
            close(&got, &want);
        }
    }

    #[test]
    fn swish_is_x_times_sigmoid_x_and_not_hard_swish() {
        // The recogniser uses both, so the piecewise approximation must not be
        // substituted here. They differ most around |x| = 1..3.
        let inputs = [-3.0f32, -1.0, 0.0, 1.0, 3.0];
        let got = one(
            Shape::new(1, 1, 5),
            &inputs,
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Swish),
        );
        let want: Vec<f32> = inputs.iter().map(|&x| x / (1.0 + (-x).exp())).collect();
        close(&got, &want);
        // And it really is a different function from HardSwish at x = 1.
        let hard = 1.0 * (1.0 / 6.0 + 0.5);
        assert!((got[3] - hard).abs() > 0.05, "swish {} vs hardswish {hard}", got[3]);
    }

    #[test]
    fn layer_norm_standardises_a_column_of_channels() {
        // Four channels at one position: mean 2.5, biased variance 1.25.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[(vec![4], vec![1.0; 4]), (vec![4], vec![0.0; 4])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        let sd = 1.25f32.sqrt();
        close(&got, &[-1.5 / sd, -0.5 / sd, 0.5 / sd, 1.5 / sd]);
    }

    #[test]
    fn layer_norm_applies_its_affine_per_channel() {
        // A gamma and beta that differ per channel, so a shader broadcasting one value
        // over the column would be visible.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[
                (vec![4], vec![1.0, 2.0, 3.0, 4.0]),
                (vec![4], vec![10.0, 20.0, 30.0, 40.0]),
            ],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        let sd = 1.25f32.sqrt();
        let normalised = [-1.5 / sd, -0.5 / sd, 0.5 / sd, 1.5 / sd];
        let want: Vec<f32> = (0..4)
            .map(|i| normalised[i] * (i as f32 + 1.0) + (i as f32 + 1.0) * 10.0)
            .collect();
        close(&got, &want);
    }

    #[test]
    fn layer_norm_treats_each_position_independently() {
        // Two positions with very different scales. Both standardise to the same pair, so
        // a reduction that spanned the whole tensor instead of one column would not.
        //
        // Layout is `[c, 1, T]` channel-major, so this is columns (1, 2) and (3, 10).
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 3.0, 2.0, 10.0],
            &[(vec![2], vec![1.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        // Two channels always standardise to -1 and +1 regardless of their spread.
        close(&got, &[-1.0, -1.0, 1.0, 1.0]);
    }

    #[test]
    fn layer_norm_epsilon_comes_from_the_push_block() {
        // The recogniser uses 1e-5 for four of its five and 1e-6 for the last, so the
        // value has to travel per op. A constant column makes it the only thing that
        // stops a division by zero.
        let got = one(
            Shape::new(2, 1, 1),
            &[5.0, 5.0],
            &[(vec![2], vec![1.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.layer_norm(x, 0, 1e-5),
        );
        // Zero variance, so both come out at beta rather than as NaN.
        close(&got, &[0.0, 0.0]);
        assert!(got.iter().all(|v| v.is_finite()), "{got:?}");
    }

    #[test]
    fn rms_norm_divides_by_the_root_mean_square_without_centring() {
        // Four channels at one position. Mean 2.5 is deliberately non-zero: a layer norm
        // would subtract it, and this must not.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[(vec![4], vec![1.0; 4])],
            |b, x| b.rms_norm(x, 0, 1e-5),
        );
        let rms = ((1.0 + 4.0 + 9.0 + 16.0) / 4.0f32).sqrt();
        close(&got, &[1.0 / rms, 2.0 / rms, 3.0 / rms, 4.0 / rms]);
    }

    #[test]
    fn rms_norm_applies_its_gain_per_channel_and_has_no_beta() {
        // A gamma that differs per channel. The second tensor is never read, so a shader
        // that reached for a beta the way layer norm does would pick up whatever follows.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[(vec![4], vec![1.0, 2.0, 3.0, 4.0])],
            |b, x| b.rms_norm(x, 0, 1e-5),
        );
        let rms = ((1.0 + 4.0 + 9.0 + 16.0) / 4.0f32).sqrt();
        let want: Vec<f32> = (0..4).map(|i| (i as f32 + 1.0) / rms * (i as f32 + 1.0)).collect();
        close(&got, &want);
    }

    #[test]
    fn rms_norm_treats_each_position_independently() {
        // Layout is `[c, 1, T]` channel-major, so this is columns (1, 2) and (3, 6). Both
        // have the same ratio, so both normalise identically — a reduction spanning the
        // whole tensor would not.
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 3.0, 2.0, 6.0],
            &[(vec![2], vec![1.0, 1.0])],
            |b, x| b.rms_norm(x, 0, 1e-5),
        );
        let rms = ((1.0 + 4.0) / 2.0f32).sqrt();
        close(&got, &[1.0 / rms, 3.0 / (3.0 * rms), 2.0 / rms, 6.0 / (3.0 * rms)]);
    }

    #[test]
    fn rms_norm_epsilon_keeps_an_all_zero_column_finite() {
        let got = one(
            Shape::new(2, 1, 1),
            &[0.0, 0.0],
            &[(vec![2], vec![1.0, 1.0])],
            |b, x| b.rms_norm(x, 0, 1e-5),
        );
        close(&got, &[0.0, 0.0]);
        assert!(got.iter().all(|v| v.is_finite()), "{got:?}");
    }

    #[test]
    fn attention_scores_contract_over_channels_and_keep_the_key_axis_last() {
        // d_model 2, one head, T 2, so `scale` is 1/sqrt(2). Q and K are `[c, 1, T]`
        // channel-major, so Q's columns are (1,3) and (2,4) and K's are (5,7) and (6,8).
        //
        // Distinct operands on purpose: Q.K^T with Q == K is symmetric, and a symmetric
        // fixture passes with the query and key axes transposed. Here S[0][1] is 30 and
        // S[1][0] is 38.
        let got = two(
            (Shape::new(2, 1, 2), Shape::new(2, 1, 2)),
            (&[1.0, 2.0, 3.0, 4.0], &[5.0, 6.0, 7.0, 8.0]),
            |b, q, k| b.attn_scores(q, k, 1),
        );
        let scale = 1.0 / 2f32.sqrt();
        close(&got, &[26.0 * scale, 30.0 * scale, 38.0 * scale, 44.0 * scale]);
    }

    #[test]
    fn attention_scores_never_mix_two_heads() {
        // d_model 4 in two heads, so head 0 owns channels 0-1 and head 1 channels 2-3.
        // The second head's values are a decade larger, so any leak across the boundary
        // moves head 0's scores by about a hundredfold rather than subtly.
        //
        // Q == K here, which is fine: what is under test is the channel range each head
        // reads, and the axis convention is pinned by the fixture above.
        let sequence = [1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0];
        let got = two(
            (Shape::new(4, 1, 2), Shape::new(4, 1, 2)),
            (&sequence, &sequence),
            |b, q, k| b.attn_scores(q, k, 2),
        );
        // head_dim is 2, so the scale is still 1/sqrt(2).
        let scale = 1.0 / 2f32.sqrt();
        close(
            &got,
            &[
                10.0 * scale, 14.0 * scale, 14.0 * scale, 20.0 * scale, //
                1000.0 * scale, 1400.0 * scale, 1400.0 * scale, 2000.0 * scale,
            ],
        );
    }

    #[test]
    fn a_cached_score_map_reads_a_position_as_a_contiguous_run() {
        // The same numbers as `attention_scores_contract_over_channels_and_keep_the_key_axis_last`,
        // laid out the other way round, so the two fixtures pin the layout difference and nothing
        // else. d_model 2, one head, two keys.
        //
        // There, K was `[2, 1, 2]` channel-major with columns (5,7) and (6,8). Here the cache is
        // `[2, 1, 2]` position-major, so key 0 is the run (5,6) and key 1 is (7,8) — the same
        // vectors, contiguous. Q is one position, (1,2).
        //
        // S[0] = 1*5 + 2*6 = 17, S[1] = 1*7 + 2*8 = 23. A shader that read the cache
        // channel-major would get 1*5 + 2*7 = 19 and 1*6 + 2*8 = 22 instead, which is why the
        // fixture is deliberately not symmetric in the two axes.
        let got = two(
            (Shape::new(2, 1, 1), Shape::new(2, 1, 2)),
            (&[1.0, 2.0], &[5.0, 6.0, 7.0, 8.0]),
            |b, q, cache| b.attn_scores_cached(q, cache, 1),
        );
        let scale = 1.0 / 2f32.sqrt();
        close(&got, &[17.0 * scale, 23.0 * scale]);
    }

    #[test]
    fn a_cached_score_map_never_mixes_two_heads() {
        // d_model 4 in two heads, so head 0 owns channels 0-1 of each position and head 1 owns
        // 2-3. The second head's values are a decade larger, so a leak across the boundary moves
        // head 0's scores about a hundredfold rather than subtly.
        //
        // Q is (1,2,10,20). The cache holds two positions of four: (1,2,10,20) and (3,4,30,40).
        //   head 0, key 0: 1*1 + 2*2  = 5      head 0, key 1: 1*3 + 2*4   = 11
        //   head 1, key 0: 10*10 + 20*20 = 500 head 1, key 1: 10*30 + 20*40 = 1100
        let got = two(
            (Shape::new(4, 1, 1), Shape::new(2, 1, 4)),
            (&[1.0, 2.0, 10.0, 20.0], &[1.0, 2.0, 10.0, 20.0, 3.0, 4.0, 30.0, 40.0]),
            |b, q, cache| b.attn_scores_cached(q, cache, 2),
        );
        // head_dim is 2, so the scale is 1/sqrt(2).
        let scale = 1.0 / 2f32.sqrt();
        close(&got, &[5.0 * scale, 11.0 * scale, 500.0 * scale, 1100.0 * scale]);
    }

    #[test]
    fn a_cached_attention_output_is_channel_major_again() {
        // probs `[1, 1, 2]` over two keys, cache `[2, 1, 3]` position-major: key 0 is (1,2,3) and
        // key 1 is (4,5,6). At weights 0.25 and 0.75 the output is
        // (0.25*1 + 0.75*4, 0.25*2 + 0.75*5, 0.25*3 + 0.75*6) = (3.25, 4.25, 5.25).
        //
        // The output is `[3, 1, 1]`, so the cache layout does not escape this op — which is the
        // property that lets the next projection be an ordinary `conv_point_int8`.
        let got = two(
            (Shape::new(1, 1, 2), Shape::new(2, 1, 3)),
            (&[0.25, 0.75], &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]),
            |b, probs, cache| b.attn_apply_cached(probs, cache, 1),
        );
        close(&got, &[3.25, 4.25, 5.25]);
    }

    #[test]
    fn a_cached_attention_uses_the_head_only_to_pick_a_row_of_weights() {
        // Two heads over d_model 4, two keys. Head 0's distribution is (1, 0) and head 1's is
        // (0, 1), so head 0's channels come entirely from key 0 and head 1's from key 1.
        //
        // Cache key 0 is (1,2,3,4) and key 1 is (5,6,7,8), so the output is (1,2,7,8). A shader
        // that indexed the probability rows the other way round would give (5,6,3,4).
        let got = two(
            (Shape::new(2, 1, 2), Shape::new(2, 1, 4)),
            (&[1.0, 0.0, 0.0, 1.0], &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]),
            |b, probs, cache| b.attn_apply_cached(probs, cache, 2),
        );
        close(&got, &[1.0, 2.0, 7.0, 8.0]);
    }

    #[test]
    fn a_cached_attention_averages_its_cache_when_every_score_is_equal() {
        // The end-to-end shape of a decode step's self-attention: one query, a two-position cache,
        // scores that are all equal because Q is zero, so softmax is uniform and the output is the
        // mean of the cached values. Anything wrong in the chaining shows up as something other
        // than the midpoint.
        let cache = [1.0f32, 2.0, 5.0, 10.0];
        let got = two(
            (Shape::new(2, 1, 1), Shape::new(2, 1, 2)),
            (&[0.0, 0.0], &cache),
            |b, q, cache| {
                let scores = b.attn_scores_cached(q, cache, 1);
                let probs = b.softmax(scores);
                b.attn_apply_cached(probs, cache, 1)
            },
        );
        close(&got, &[3.0, 6.0]);
    }

    #[test]
    fn a_reshape_is_one_copy_and_the_same_elements() {
        // A projection writes `[d_model, 1, 1]` and a cache position is `[1, 1, d_model]`. Those
        // are the same bytes, and this is the relabelling that lets the two meet.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 2.0, 3.0, 4.0],
            &[],
            |b, x| b.reshaped(x, Shape::new(1, 1, 4)),
        );
        close(&got, &[1.0, 2.0, 3.0, 4.0]);
    }

    #[test]
    fn attention_scale_is_the_inverse_root_of_the_head_dimension() {
        // Four channels in four heads is head_dim 1, where the scale is exactly 1, so
        // this fixture is the raw product and isolates the scale from the contraction.
        // At one head the same tensors would be divided by 2 instead.
        let got = two(
            (Shape::new(4, 1, 1), Shape::new(4, 1, 1)),
            (&[1.0, 2.0, 3.0, 4.0], &[5.0, 6.0, 7.0, 8.0]),
            |b, q, k| b.attn_scores(q, k, 4),
        );
        close(&got, &[5.0, 12.0, 21.0, 32.0]);
    }

    #[test]
    fn softmax_normalises_each_row_of_the_last_axis_on_its_own() {
        // `[2, 2, 2]` is four rows of two, the shape a score map has. Rows are (1,2),
        // (5,5), (0,100) and (-3,-3): a plain pair, two ties at different offsets, and
        // one row whose spread would overflow.
        let got = one(
            Shape::new(2, 2, 2),
            &[1.0, 2.0, 5.0, 5.0, 0.0, 100.0, -3.0, -3.0],
            &[],
            |b, x| b.softmax(x),
        );
        let pair = (-1.0f32).exp() / (1.0 + (-1.0f32).exp());
        close(
            &got,
            &[
                pair, 1.0 - pair, //
                0.5, 0.5, //
                0.0, 1.0, //
                0.5, 0.5,
            ],
        );
        // Every row is a distribution. Two equal values summing to 1 is what a shader
        // that forgot to divide would also produce for rows 2 and 4, so the sum is
        // checked for all of them.
        for (row, pair) in got.chunks_exact(2).enumerate() {
            let total: f32 = pair.iter().sum();
            assert!((total - 1.0).abs() < 2e-3, "row {row} sums to {total}");
        }
    }