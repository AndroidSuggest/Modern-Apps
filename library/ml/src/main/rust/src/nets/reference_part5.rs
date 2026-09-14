
    /// Build a plan whose only ops come from `record`, run it, and return the output.
    ///
    /// Going through the real [`Builder`] rather than hand-writing a [`Push`] is the
    /// point: these fixtures check the resolved plan, so the shape propagation, the
    /// group split and the arena offsets are all under test too.
    fn one(
        input_shape: Shape,
        input: &[f32],
        tensors: &[(Vec<u32>, Vec<f32>)],
        record: impl FnOnce(&mut Builder, Id) -> Id,
    ) -> Vec<f32> {
        let given = Given::new(tensors).expect("the fixture tensors are consistent");
        let mut builder = Builder::new(&given);
        let first = builder.input(input_shape);
        let last = record(&mut builder, first);
        let plan = builder.finish(&[last]).expect("the fixture plan builds");
        run(&plan, given.data(), input).expect("the fixture plan runs")
    }

    #[test]
    fn an_int8_convolution_dequantises_per_output_channel() {
        // Two output channels over three input channels, 1x1 — the shape every SMaLL-100 linear
        // reduces to. The two channels get *different* scales, which is the whole point: a
        // per-tensor scale would give channel 1 four times its correct magnitude here and still
        // produce a plausible-looking tensor of the right shape.
        //
        // Values chosen to be exact in fp16 so the expectation can be written out longhand and any
        // disagreement is arithmetic rather than rounding.
        let kernel: Vec<i8> = vec![1, 2, 3, 4, -5, 6];
        let scales = vec![0.25f32, 1.0];
        let biases = vec![1.0f32, -1.0];
        let blob = crate::weights::write_mixed(
            crate::weights::graph::SUPERTONIC_VE,
            &[
                crate::weights::Fixture::I8(vec![2, 3, 1, 1], kernel.clone()),
                crate::weights::Fixture::F16(vec![2], scales.clone()),
                crate::weights::Fixture::F16(vec![2], biases.clone()),
            ],
        );
        let weights = crate::weights::Weights::parse(&blob, crate::weights::graph::SUPERTONIC_VE)
            .expect("the fixture blob parses");

        // Three channels, two positions: [c0: 1, 2] [c1: 4, 8] [c2: 0.5, -1]
        let input = vec![1.0f32, 2.0, 4.0, 8.0, 0.5, -1.0];
        let mut builder = Builder::new(&weights);
        let first = builder.input(Shape::new(3, 1, 2));
        let last = builder.conv_int8(
            first,
            0,
            2,
            (1, 1),
            (1, 1),
            (1, 1),
            (0, 0, 0, 0),
            1,
            Act::None,
        );
        let plan = builder.finish(&[last]).expect("the int8 fixture plan builds");
        let got = run(&plan, weights.data(), &input).expect("the int8 fixture plan runs");

        let mut want = Vec::new();
        for oc in 0..2usize {
            for x in 0..2usize {
                let mut acc = 0.0f32;
                for ic in 0..3usize {
                    acc += f32::from(kernel[oc * 3 + ic]) * input[ic * 2 + x];
                }
                want.push(acc * scales[oc] + biases[oc]);
            }
        }
        assert_eq!(got, want, "int8 conv: got {got:?}, want {want:?}");
    }

    /// [`one`], for the ops whose two operands are different shapes.
    ///
    /// Attention cannot be checked with one tensor used twice: `Q . K^T` is symmetric
    /// when `Q == K`, so a fixture built that way passes with the two operands swapped
    /// and with the query and key axes transposed.
    fn two(
        shapes: (Shape, Shape),
        inputs: (&[f32], &[f32]),
        record: impl FnOnce(&mut Builder, Id, Id) -> Id,
    ) -> Vec<f32> {
        let given = Given::new(&[]).expect("no tensors");
        let mut builder = Builder::new(&given);
        let first = builder.input(shapes.0);
        let second = builder.input(shapes.1);
        let last = record(&mut builder, first, second);
        let plan = builder.finish(&[last]).expect("the fixture plan builds");
        super::super::tests::assert_no_aliasing(&plan);
        let outputs =
            run_multi(&plan, given.data(), &[inputs.0, inputs.1]).expect("the fixture plan runs");
        match <[Vec<f32>; 1]>::try_from(outputs) {
            Ok([only]) => only,
            Err(other) => panic!("{} outputs", other.len()),
        }
    }

    /// [`two`], with weight tensors and independent shapes, for the relative attention.
    fn two_weighted(
        shapes: (Shape, Shape),
        inputs: (&[f32], &[f32]),
        tensors: &[(Vec<u32>, Vec<f32>)],
        record: impl FnOnce(&mut Builder, Id, Id) -> Id,
    ) -> Vec<f32> {
        let given = Given::new(tensors).expect("the fixture tensors lay out");
        let mut builder = Builder::new(&given);
        let first = builder.input(shapes.0);
        let second = builder.input(shapes.1);
        let last = record(&mut builder, first, second);
        let plan = builder.finish(&[last]).expect("the fixture plan builds");
        super::super::tests::assert_no_aliasing(&plan);
        let outputs =
            run_multi(&plan, given.data(), &[inputs.0, inputs.1]).expect("the fixture plan runs");
        match <[Vec<f32>; 1]>::try_from(outputs) {
            Ok([only]) => only,
            Err(other) => panic!("{} outputs", other.len()),
        }
    }

    fn close(got: &[f32], want: &[f32]) {
        assert_eq!(got.len(), want.len(), "{got:?} vs {want:?}");
        for (i, (&g, &w)) in got.iter().zip(want).enumerate() {
            // fp16 carries ~3 decimal digits, and these fixtures are small integers
            // and halves, so the tolerance only has to absorb the store.
            let tolerance = w.abs() * 1e-3 + 1e-3;
            assert!((g - w).abs() <= tolerance, "element {i}: {got:?} vs {want:?}");
        }
    }

    #[test]
    fn erf_matches_its_known_values() {
        // A&S 7.1.26, good to 1.5e-7, pinned at values with published digits — a wrong
        // coefficient would still look like a sigmoid. `Act::Gelu` is the exact erf form rather
        // than the tanh approximation, and `shaders/conv.comp` carries the same series, so this
        // is what keeps the interpreter and the device agreeing.
        for (x, want) in [
            (0.0f32, 0.0f32),
            (0.5, 0.5204999),
            (1.0, 0.8427008),
            (2.0, 0.9953223),
            (-1.0, -0.8427008),
            (3.0, 0.9999779),
        ] {
            let got = super::super::erf(x);
            assert!((got - want).abs() < 2e-6, "erf({x}) = {got} not {want}");
        }
    }

    #[test]
    fn a_dense_convolution_matches_a_hand_summed_window() {
        // 2x2 of ones over 1..9, bias 0.5. Each output is the sum of its window.
        let got = one(
            Shape::new(1, 3, 3),
            &[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0],
            &[(vec![1, 1, 2, 2], vec![1.0; 4]), (vec![1], vec![0.5])],
            |b, x| b.conv(x, 0, 1, (2, 2), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::None),
        );
        close(&got, &[12.5, 16.5, 24.5, 28.5]);
    }

    #[test]
    fn asymmetric_padding_is_applied_only_where_onnx_puts_it() {
        // The selfie net's stem shape: 3x3 stride 2, `pads = [0, 0, 1, 1]`. Nothing
        // above or left, one row and column below and right. Padding symmetrically
        // gives the same 2x2 output and different numbers in every cell, which is
        // exactly the error no mask inspection would reveal.
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(
            Shape::new(1, 4, 4),
            &input,
            &[(vec![1, 1, 3, 3], vec![1.0; 9]), (vec![1], vec![0.0])],
            |b, x| b.conv(x, 0, 1, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::None),
        );
        // Windows anchored at (0,0) and (0,2) / (2,0) and (2,2), clipped at the far edge.
        close(&got, &[54.0, 45.0, 72.0, 54.0]);
    }

    #[test]
    fn a_dilated_convolution_skips_the_taps_it_should() {
        // U^2-Netp's shape: 3x3, dilation 2, pad 2, which holds the extent. At the
        // centre the taps land on rows and columns 0, 2, 4 of a 5x5; at the corner two
        // of the three fall in the padding.
        let input: Vec<f32> = (1..=25).map(|v| v as f32).collect();
        let got = one(
            Shape::new(1, 5, 5),
            &input,
            &[(vec![1, 1, 3, 3], vec![1.0; 9]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 3, 2, Act::None),
        );
        assert_eq!(got.len(), 25);
        // Centre: 1+3+5 + 11+13+15 + 21+23+25.
        close(&got[12..13], &[117.0]);
        // Corner: rows 0 and 2, columns 0 and 2 only.
        close(&got[0..1], &[28.0]);
    }

    #[test]
    fn a_depthwise_convolution_never_mixes_channels() {
        // group == in_c == out_c, so each output channel sees exactly one input
        // channel. A weight table read as if it were dense would fold both together.
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 2.0, 10.0, 20.0],
            &[(vec![2, 1, 1, 1], vec![3.0, 5.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::None),
        );
        close(&got, &[3.0, 6.0, 50.0, 100.0]);
    }

    #[test]
    fn a_grouped_convolution_reads_only_its_own_slice() {
        // Four channels in two groups of two, each input channel a distinct decade so
        // any leak across the group boundary is visible in the sum.
        let got = one(
            Shape::new(4, 1, 1),
            &[1.0, 10.0, 100.0, 1000.0],
            &[(vec![4, 2, 1, 1], vec![1.0; 8]), (vec![4], vec![0.0; 4])],
            |b, x| b.conv(x, 0, 4, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::None),
        );
        close(&got, &[11.0, 11.0, 1100.0, 1100.0]);
    }

    #[test]
    fn a_transposed_convolution_reads_input_channels_outermost() {
        // The layout inversion. Weights are `[in_c, out_c, kh, kw]`, so with two input
        // channels the first four values belong to input channel 0. Reading them as
        // `Conv` does — `[out_c, in_c, kh, kw]` — gives 7 here instead of 21, and an
        // output of the right shape either way.
        let got = one(
            Shape::new(2, 1, 1),
            &[1.0, 2.0],
            &[
                (vec![2, 1, 2, 2], vec![1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0]),
                (vec![1], vec![0.0]),
            ],
            |b, x| b.conv_transpose(x, 0, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::None),
        );
        close(&got, &[21.0, 42.0, 63.0, 84.0]);
    }

    #[test]
    fn max_pooling_takes_the_largest_of_each_window() {
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 4, 4), &input, &[], |b, x| b.max_pool_2x2(x));
        close(&got, &[6.0, 8.0, 14.0, 16.0]);
    }

    #[test]
    fn average_pooling_takes_the_mean_of_each_window() {
        // The same 4x4 as the max-pool fixture above, so the two are directly
        // comparable: 6 against 3.5 for the first window.
        let input: Vec<f32> = (1..=16).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 4, 4), &input, &[], |b, x| {
            b.avg_pool(x, (2, 2), (2, 2))
        });
        close(&got, &[3.5, 5.5, 11.5, 13.5]);
    }

    #[test]
    fn an_asymmetric_pool_window_is_not_transposed() {
        // The recogniser's shape: kernel and stride both (3, 2) on a 3-row map, which
        // collapses the height to one and halves the width. This is the step that turns
        // a feature map into a `[d_model, 1, T]` sequence.
        //
        // Rows are (1,2,3,4), (5,6,7,8), (9,10,11,12), so the left window holds
        // 1,2,5,6,9,10 and the right one 3,4,7,8,11,12. A kernel read as (2, 3) instead
        // would pool 1,2,3,5,6,7 and answer 4 for the first column.
        let input: Vec<f32> = (1..=12).map(|v| v as f32).collect();
        let got = one(Shape::new(1, 3, 4), &input, &[], |b, x| {
            b.avg_pool(x, (3, 2), (3, 2))
        });
        close(&got, &[33.0 / 6.0, 45.0 / 6.0]);
    }

    #[test]
    fn average_pooling_keeps_channels_apart() {
        // Two channels a decade apart, so a pool whose plane stride was wrong folds them
        // together visibly rather than shifting the answer slightly.
        let got = one(
            Shape::new(2, 1, 4),
            &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            &[],
            |b, x| b.avg_pool(x, (1, 2), (1, 2)),
        );
        close(&got, &[1.5, 3.5, 15.0, 35.0]);
    }

    #[test]
    fn a_pool_window_that_does_not_tile_its_input_is_refused() {
        // 5 wide with a 2-wide window at stride 2 drops the last column, and the shader
        // divides by the window size regardless — so the choice is between a silently
        // dropped column and a silently rescaled edge. Neither is acceptable, so the
        // build fails instead.
        let given = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&given);
        let x = b.input(Shape::new(1, 2, 5));
        let pooled = b.avg_pool(x, (2, 2), (2, 2));
        let error = b.finish(&[pooled]).expect_err("a 5-wide input");
        assert!(error.contains("does not tile 5 along w"), "{error}");
    }

    #[test]
    fn a_resize_puts_its_samples_at_half_pixel_centres() {
        // Two columns to four. The samples land at source x = -0.25, 0.25, 0.75, 1.25,
        // which clamp at both ends. `align_corners` would give 0, 4/3, 8/3, 4 instead.
        let got = one(Shape::new(1, 1, 2), &[0.0, 4.0], &[], |b, x| b.resize_to(x, 1, 4));
        close(&got, &[0.0, 1.0, 3.0, 4.0]);
    }

    #[test]
    fn a_resize_down_to_one_pixel_averages_all_four_taps() {
        // Half-pixel puts the single sample at the centre of the 2x2, so all four taps
        // weigh equally.
        let got = one(Shape::new(1, 2, 2), &[0.0, 4.0, 4.0, 0.0], &[], |b, x| {
            b.resize_to(x, 1, 1)
        });
        close(&got, &[2.0]);
    }

    #[test]
    fn global_average_pooling_reduces_each_channel_on_its_own() {
        let got = one(
            Shape::new(2, 2, 2),
            &[1.0, 2.0, 3.0, 4.0, 10.0, 20.0, 30.0, 40.0],
            &[],
            |b, x| b.global_avg_pool(x),
        );
        close(&got, &[2.5, 25.0]);
    }

    #[test]
    fn a_channel_broadcast_multiply_indexes_the_gate_by_channel_alone() {
        let got = one(
            Shape::new(2, 1, 2),
            &[1.0, 2.0, 4.0, 8.0],
            &[(vec![2, 2, 1, 1], vec![1.0, 0.0, 0.0, 1.0]), (vec![2], vec![0.0, 0.0])],
            |b, x| {
                // A `C x 1 x 1` gate, made by pooling and then a 1x1 that passes each
                // channel through unchanged, so the expected values stay hand-computable:
                // the gate is (1.5, 6.0).
                let pooled = b.global_avg_pool(x);
                let gate = b.conv_same(pooled, 0, 2, 1, 1, Act::None);
                b.mul_channel(x, gate)
            },
        );
        close(&got, &[1.5, 3.0, 24.0, 48.0]);
    }

    #[test]
    fn an_elementwise_add_pairs_the_two_operands_positionally() {
        let got = one(Shape::new(1, 1, 3), &[1.0, 2.0, 3.0], &[], |b, x| {
            let doubled = b.add(x, x);
            b.add(doubled, x)
        });
        close(&got, &[3.0, 6.0, 9.0]);
    }

    #[test]
    fn a_concatenation_lands_the_parts_end_to_end_in_channel_order() {
        // Concat is lowered to copies rather than a shader, so this is the check that
        // the destination offsets stack correctly.
        let got = one(Shape::new(1, 1, 2), &[1.0, 2.0], &[], |b, x| {
            let resized = b.resize_to(x, 1, 2);
            b.concat(&[x, resized, x])
        });
        close(&got, &[1.0, 2.0, 1.0, 2.0, 1.0, 2.0]);
    }

    #[test]
    fn the_activation_codes_agree_with_the_ones_the_builder_emits() {
        // `activate` here and in `common.glsl` both switch on a bare `u32`, so a
        // reordering of `Act` would silently repoint every activation in both.
        assert_eq!(Act::None.code(), act::NONE);
        assert_eq!(Act::Relu.code(), act::RELU);
        assert_eq!(Act::HardSwish.code(), act::HARDSWISH);
        assert_eq!(Act::Sigmoid.code(), act::SIGMOID);
        assert_eq!(Act::PRelu(0).code(), act::PRELU);
        assert_eq!(Act::Clip01.code(), act::CLIP01);
        assert_eq!(Act::Swish.code(), act::SWISH);
        assert_eq!(Act::Gelu.code(), act::GELU);
    }

    #[test]
    fn prelu_scales_only_the_negative_side_and_per_channel() {
        // Two channels with different slopes, so a PReLU that read one slope for the
        // whole tensor — or indexed it by element instead of by channel — is visible.
        // Slopes 0.25 and 4.0; inputs -2 and 3 in each channel.
        let got = one(
            Shape::new(2, 1, 2),
            &[-2.0, 3.0, -2.0, 3.0],
            &[
                (vec![2, 1, 1, 1], vec![1.0, 1.0]),
                (vec![2], vec![0.0, 0.0]),
                (vec![2, 1, 1], vec![0.25, 4.0]),
            ],
            |b, x| {
                b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::PRelu(2))
            },
        );
        close(&got, &[-0.5, 3.0, -8.0, 3.0]);
    }

    #[test]
    fn prelu_at_slope_one_is_the_identity_and_at_zero_is_relu() {
        // The two degenerate slopes, which pin the sign convention: a shader that
        // scaled the positive side instead would pass the identity case and fail this.
        let got = one(
            Shape::new(2, 1, 2),
            &[-4.0, 4.0, -4.0, 4.0],
            &[
                (vec![2, 1, 1, 1], vec![1.0, 1.0]),
                (vec![2], vec![0.0, 0.0]),
                (vec![2, 1, 1], vec![1.0, 0.0]),
            ],
            |b, x| {
                b.conv(x, 0, 2, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 2, Act::PRelu(2))
            },
        );
        close(&got, &[-4.0, 4.0, 0.0, 4.0]);
    }

    #[test]
    fn a_nearest_resize_repeats_each_source_pixel_rather_than_blending() {
        // Two columns to four, `asymmetric` + `floor`: src = floor(dst * 2 / 4) = dst/2,
        // so 0, 0, 1, 1. The bilinear kernel gives 0, 1, 3, 4 for the same input, which
        // is what makes this the discriminating fixture.
        let got = one(Shape::new(1, 1, 2), &[0.0, 4.0], &[], |b, x| {
            let like = b.resize_to(x, 1, 4);
            b.resize_nearest_like(x, like)
        });
        close(&got, &[0.0, 0.0, 4.0, 4.0]);
    }