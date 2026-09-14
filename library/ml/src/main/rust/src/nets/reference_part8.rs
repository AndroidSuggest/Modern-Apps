        // A relative offset is `key - query`, so it is only meaningful within one sequence.
        // Cross-attention with a position table would index a band that does not exist.
        let source = Given::new(&[(vec![3, 2], vec![0.0; 6])]).expect("the fixture lays out");
        let mut b = Builder::new(&source);
        let q = b.input(Shape::new(2, 1, 3));
        let k = b.input(Shape::new(2, 1, 5));
        let out = b.attn_scores_relative(q, k, 1, 0, 3);
        let error = b.finish(&[out]).expect_err("a relative map across two sequences");
        assert!(error.contains("same sequence"), "{error}");
    }

    #[test]
    fn a_constant_reaches_the_arena_from_the_weights_file() {
        // The one op that reads no arena. Its output has to be usable as an operand, so this
        // adds it to an input rather than reading it straight back.
        let table: Vec<f32> = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
        let got = one(
            Shape::new(2, 1, 3),
            &[10.0, 20.0, 30.0, 40.0, 50.0, 60.0],
            &[(vec![2, 1, 3], table)],
            |b, x| {
                let loaded = b.constant(0, Shape::new(2, 1, 3));
                b.add(x, loaded)
            },
        );
        close(&got, &[11.0, 22.0, 33.0, 44.0, 55.0, 66.0]);
    }

    #[test]
    fn a_per_channel_shift_broadcasts_over_the_plane() {
        // Two channels of three positions, shifted by 10 and -1. The mirror of the excite
        // multiply, and used for the sampler's timestep conditioning.
        let got = two(
            (Shape::new(2, 1, 3), Shape::new(2, 1, 1)),
            (&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0], &[10.0, -1.0]),
            |b, x, shift| b.add_channel(x, shift),
        );
        close(&got, &[11.0, 12.0, 13.0, 3.0, 4.0, 5.0]);
    }

    #[test]
    fn a_per_channel_shift_refuses_a_full_sequence() {
        // A `[2, 1, 3]` second operand would be a plain `Add`. Taking it here would read only
        // its first column and silently drop the rest.
        let source = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&source);
        let x = b.input(Shape::new(2, 1, 3));
        let shift = b.input(Shape::new(2, 1, 3));
        let out = b.add_channel(x, shift);
        let error = b.finish(&[out]).expect_err("a per-position shift");
        assert!(error.contains("not Cx1x1"), "{error}");
    }

    #[test]
    fn rotary_rotates_the_halves_of_each_head() {
        // One head of four channels over two positions, so `half` is 2. At position 0 the angle
        // is 0 and the rotation is the identity; at position 1 it is 90 degrees on both
        // frequencies, which sends `(a, b)` to `(-b, a)` and is unmistakable.
        let quarter = std::f32::consts::FRAC_PI_2;
        let x: Vec<f32> = vec![
            1.0, 5.0, // channel 0
            2.0, 6.0, // channel 1
            3.0, 7.0, // channel 2, the partner of 0
            4.0, 8.0, // channel 3, the partner of 1
        ];
        // `[head_dim, 1, W]`: two cosines then two sines.
        let angles: Vec<f32> = vec![
            1.0, quarter.cos(), //
            1.0, quarter.cos(), //
            0.0, quarter.sin(), //
            0.0, quarter.sin(),
        ];
        let got = two(
            (Shape::new(4, 1, 2), Shape::new(4, 1, 2)),
            (&x, &angles),
            |b, x, a| b.rotary(x, a, 1),
        );
        close(
            &got,
            &[
                1.0, -7.0, // 1*1 - 3*0 ; 5*0 - 7*1
                2.0, -8.0, //
                3.0, 5.0, // 3*1 + 1*0 ; 7*0 + 5*1
                4.0, 6.0,
            ],
        );
    }

    #[test]
    fn rotary_uses_one_angle_table_for_every_head() {
        // Two heads of two channels, one position, a 90-degree turn. Both heads must rotate,
        // and each must pair with its own partner rather than with the next head's channel —
        // a `half` computed on the channel count instead of the head width would pair channel
        // 0 with channel 2, which is head 1.
        let got = two(
            (Shape::new(4, 1, 1), Shape::new(2, 1, 1)),
            (&[1.0, 2.0, 3.0, 4.0], &[0.0, 1.0]),
            |b, x, a| b.rotary(x, a, 2),
        );
        // Head 0 is (1, 2) -> (-2, 1); head 1 is (3, 4) -> (-4, 3).
        close(&got, &[-2.0, 1.0, -4.0, 3.0]);
    }

    #[test]
    fn rotary_refuses_an_odd_head() {
        // It rotates 2-planes, so a head with no middle cannot be split.
        let source = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&source);
        let x = b.input(Shape::new(3, 1, 2));
        let angles = b.input(Shape::new(3, 1, 2));
        let out = b.rotary(x, angles, 1);
        let error = b.finish(&[out]).expect_err("an odd head");
        assert!(error.contains("2-planes"), "{error}");
    }

    #[test]
    fn rotary_refuses_an_angle_table_of_the_wrong_width() {
        // The table is `[head_dim, 1, T]`, cosines then sines. A `[head_dim / 2, 1, T]` one —
        // the natural mistake, since there are only `head_dim / 2` frequencies — would read
        // sines out of the tensor that follows it.
        let source = Given::new(&[]).expect("no tensors");
        let mut b = Builder::new(&source);
        let x = b.input(Shape::new(4, 1, 2));
        let angles = b.input(Shape::new(2, 1, 2));
        let out = b.rotary(x, angles, 1);
        let error = b.finish(&[out]).expect_err("half a table");
        assert!(error.contains("cosines then sines"), "{error}");
    }

    #[test]
    fn a_relative_score_map_adds_a_banded_term_from_its_table() {
        // Q = K = 1 everywhere over four positions, two channels, one head. The content
        // term is then `scale * head_dim` for every pair, and the relative term adds
        // `scale * sum_d table[j - i + window][d]` inside the band and nothing outside it.
        //
        // A table of three offsets whose rows sum to -10, 0 and +10, so the sign of the
        // displacement is visible in the answer: a transposed `j - i` would mirror it.
        let table: Vec<f32> = vec![-5.0, -5.0, 0.0, 0.0, 5.0, 5.0];
        let got = two_weighted(
            (Shape::new(2, 1, 4), Shape::new(2, 1, 4)),
            (&[1.0; 8], &[1.0; 8]),
            &[(vec![3, 2], table)],
            |b, q, k| b.attn_scores_relative(q, k, 1, 0, 3),
        );
        // scale = 1/sqrt(2); content is 2 * scale for every pair.
        let scale = 1.0 / 2.0f32.sqrt();
        let content = 2.0 * scale;
        let mut want = vec![0.0f32; 16];
        for query in 0..4i64 {
            for key in 0..4i64 {
                let offset = key - query;
                let relative = match offset {
                    -1 => -10.0,
                    0 => 0.0,
                    1 => 10.0,
                    _ => 0.0,
                };
                want[(query * 4 + key) as usize] = content + relative * scale;
            }
        }
        close(&got, &want);
    }

    #[test]
    fn a_relative_score_map_leaves_the_band_alone_outside_the_window() {
        // The whole reason this is nine taps and not a `[heads, T, 2T-1]` product and skew:
        // beyond the window the export's skewed tensor is exactly zero. Verified against
        // onnxruntime on a relative-attention encoder, and pinned here.
        let table: Vec<f32> = vec![7.0, 7.0, 0.0, 0.0, 7.0, 7.0];
        let got = two_weighted(
            (Shape::new(2, 1, 5), Shape::new(2, 1, 5)),
            (&[1.0; 10], &[1.0; 10]),
            &[(vec![3, 2], table)],
            |b, q, k| b.attn_scores_relative(q, k, 1, 0, 3),
        );
        let scale = 1.0 / 2.0f32.sqrt();
        let content = 2.0 * scale;
        for query in 0..5usize {
            for key in 0..5usize {
                let value = got[query * 5 + key];
                let far = (key as i64 - query as i64).abs() > 1;
                if far {
                    assert!(
                        (value - content).abs() < 1e-3,
                        "({query},{key}) is outside the band but moved: {value} vs {content}"
                    );
                }
            }
        }
    }

    #[test]
    fn a_relative_weighted_sum_reads_the_diagonal_of_its_probabilities() {
        // One head, two channels, three positions. Probabilities are the identity, so the
        // content term picks V[.,i] and the relative term adds `table[window][d]` — the
        // centre row — because the only non-zero probability is at `key == query`.
        let probs: Vec<f32> = vec![
            1.0, 0.0, 0.0, //
            0.0, 1.0, 0.0, //
            0.0, 0.0, 1.0,
        ];
        let values: Vec<f32> = vec![1.0, 2.0, 3.0, 10.0, 20.0, 30.0];
        let table: Vec<f32> = vec![100.0, 200.0, 1.0, 2.0, 300.0, 400.0];
        let got = two_weighted(
            (Shape::new(1, 3, 3), Shape::new(2, 1, 3)),
            (&probs, &values),
            &[(vec![3, 2], table)],
            |b, p, v| b.attn_apply_relative(p, v, 1, 0, 3),
        );
        // Channel 0 gets table[1][0] = 1, channel 1 gets table[1][1] = 2.
        close(&got, &[2.0, 3.0, 4.0, 12.0, 22.0, 32.0]);
    }

    #[test]
    fn a_relative_table_with_an_even_number_of_offsets_is_refused() {
        // `2 * window + 1` is always odd, so an even count has no centre and the
        // displacement it indexed would be ambiguous.
        let source = Given::new(&[(vec![4, 2], vec![0.0; 8])]).expect("the fixture lays out");
        let mut b = Builder::new(&source);
        let q = b.input(Shape::new(2, 1, 4));
        let k = b.input(Shape::new(2, 1, 4));
        let out = b.attn_scores_relative(q, k, 1, 0, 4);
        let error = b.finish(&[out]).expect_err("an even offset count");
        assert!(error.contains("no centre"), "{error}");
    }

    #[test]
    fn relu_clamps_at_zero_without_touching_positives() {
        let got = one(
            Shape::new(1, 1, 2),
            &[-3.0, 4.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Relu),
        );
        close(&got, &[0.0, 4.0]);
    }

    #[test]
    fn hard_swish_matches_onnx_at_its_default_alpha_and_beta() {
        // `x * clamp(x/6 + 0.5, 0, 1)`: saturated off at -3, linear at 0 and 1,
        // saturated on at 3 and above.
        let got = one(
            Shape::new(1, 1, 5),
            &[-4.0, -3.0, 0.0, 1.0, 3.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::HardSwish),
        );
        close(&got, &[0.0, 0.0, 0.0, 1.0 * (1.0 / 6.0 + 0.5), 3.0]);
    }

    #[test]
    fn sigmoid_is_the_logistic_function_and_bounds_the_mask_to_zero_one() {
        let got = one(
            Shape::new(1, 1, 3),
            &[-8.0, 0.0, 8.0],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::Sigmoid),
        );
        close(&got, &[1.0 / (1.0 + 8f32.exp()), 0.5, 1.0 / (1.0 + (-8f32).exp())]);
    }

    #[test]
    fn a_stored_value_is_rounded_to_fp16_not_kept_in_fp32() {
        // The arena is fp16 on the device, so the reference must not be more accurate
        // than the thing it is a reference for. 1 + 2^-11 is exactly halfway to the
        // next fp16 and must round to 1.
        let got = one(
            Shape::new(1, 1, 1),
            &[1.0 + 2f32.powi(-11)],
            &[(vec![1, 1, 1, 1], vec![1.0]), (vec![1], vec![0.0])],
            |b, x| b.conv_same(x, 0, 1, 1, 1, Act::None),
        );
        assert_eq!(got, vec![1.0]);
    }

    #[test]
    fn a_mismatched_input_length_is_refused_rather_than_padded() {
        let given = Given::new(&[]).expect("no tensors");
        let mut builder = Builder::new(&given);
        let input = builder.input(Shape::new(1, 2, 2));
        let out = builder.resize_to(input, 2, 2);
        let plan = builder.finish(&[out]).expect("builds");
        let error = run(&plan, given.data(), &[1.0, 2.0]).expect_err("too few values");
        assert!(error.contains("2 input values"), "{error}");
    }

    /// A net with every op in it, at a size a debug build can run in milliseconds.
    ///
    /// This is what keeps the interpreter itself honest on every `cargo test`: the two
    /// real nets below are hundreds of times larger and have to be opted into.
    fn miniature(weights: &dyn WeightSource) -> Result<Plan, String> {
        let mut b = Builder::new(weights);
        let first = b.input(Shape::new(3, 8, 8));
        let x = b.conv(first, 0, 8, (3, 3), (2, 2), (1, 1), (0, 0, 1, 1), 1, Act::HardSwish);
        let pooled = b.global_avg_pool(x);
        let gate = b.conv_same(pooled, 2, 8, 1, 1, Act::Sigmoid);
        let gated = b.mul_channel(x, gate);
        let deep = b.max_pool_2x2(gated);
        let deep = b.conv_same(deep, 4, 8, 3, 1, Act::Relu);
        let up = b.resize_like(deep, gated);
        let merged = b.add(gated, up);
        let joined = b.concat(&[merged, up]);
        let out = b.conv_transpose(joined, 6, 1, (2, 2), (2, 2), (0, 0, 0, 0), Act::Sigmoid);
        b.finish(&[out])
    }

    #[test]
    fn the_miniature_net_runs_every_op_and_stays_in_range() {
        let source = Invented::new(8);
        let plan = miniature(&source).expect("the miniature net builds");
        let data = source.into_data();
        let input: Vec<f32> = (0..plan.input().expect("one input").shape.len())
            .map(|i| (i as f32 * 0.37).sin())
            .collect();
        let got = run(&plan, &data, &input).expect("the miniature net runs");

        assert_eq!(got.len(), plan.output().expect("one output").shape.len() as usize);
        // A sigmoid output, so every value is a probability. A NaN or an fp16 overflow
        // anywhere upstream lands outside this.
        for (i, &value) in got.iter().enumerate() {
            assert!((0.0..=1.0).contains(&value), "element {i} is {value}");
        }
        // Not a constant: a plan that dropped its spatial ops would still be in range.
        let first = got.first().copied().unwrap_or_default();
        assert!(got.iter().any(|&v| v != first), "the output is uniform");
    }

    #[test]
    fn the_miniature_net_is_deterministic() {
        let source = Invented::new(8);
        let plan = miniature(&source).expect("builds");
        let data = source.into_data();
        let input = vec![0.25; plan.input().expect("one input").shape.len() as usize];
        assert_eq!(
            run(&plan, &data, &input).expect("first run"),
            run(&plan, &data, &input).expect("second run")
        );
    }

    #[test]
    fn invented_weights_are_reproducible_per_tensor() {
        // Each tensor's values come from its own index, so the blob does not depend on
        // the order a forward pass happens to ask for them.
        let first = Invented::new(4);
        let second = Invented::new(4);
        for source in [&first, &second] {
            assert!(source.shaped(0, &[2, 2, 1, 1]).is_ok());
            assert!(source.shaped(1, &[2]).is_ok());
            assert!(source.shaped(2, &[2, 2, 1, 1]).is_ok());
            assert!(source.shaped(3, &[2]).is_ok());
        }
        assert_eq!(first.into_data(), second.into_data());
    }

    #[test]
    fn invented_kernels_are_scaled_by_their_fan_in() {
        // Without this a 119-layer net either saturates fp16 or decays to zero, and an
        // end-to-end run says nothing.
        let source = Invented::new(1);
        assert!(source.shaped(0, &[4, 16, 3, 3]).is_ok());
        let data = source.into_data();
        let bound = 1.0 / (16.0f32 * 3.0 * 3.0).sqrt();
        for pair in data.chunks_exact(2) {
            let value = match pair {
                [low, high] => f16_to_f32(u16::from_le_bytes([*low, *high])),
                _ => unreachable!(),
            };
            assert!(value.abs() <= bound, "{value} exceeds {bound}");
        }
    }

    #[test]
    fn the_ppocr_rec_net_runs_end_to_end_and_decodes() {
        // The whole recognition pass, at width 16 rather than the 320 it runs at on
        // device: `T` is 2 instead of 40, which still exercises all 56 layers, both
        // attentions, all five layer norms, both squeeze-excites and the pool. Invented
        // weights, so no asset is needed.
        //
        // Width 16 rather than 8 on purpose. At `T` 1 a softmax over one key is 1.0
        // whatever the scores were, so the attention would run without its indexing being
        // observable at all. Two costs about a second more in a debug build and makes the
        // query and key axes distinguishable.
        let source = Invented::new(ppocr_rec::TENSORS);
        let plan = ppocr_rec::build(&source, 16).expect("ppocr_rec builds at width 16");
        let data = source.into_data();
        let shape = plan.input().expect("one input").shape;
        let logits = run(&plan, &data, &blob(shape)).expect("ppocr_rec runs");

        let steps = 16 / 8;
        assert_eq!(logits.len(), ppocr_rec::LOGITS as usize * steps);
        // Raw logits, so the only thing to assert about the values themselves is that fp16
        // did not saturate anywhere down 56 layers — an infinity or a NaN here is what a
        // misindexed weight or an aliased arena produces.
        for (i, &value) in logits.iter().enumerate() {
            assert!(value.is_finite(), "logit {i} is {value}");
        }
        let low = logits.iter().fold(f32::MAX, |a, &b| a.min(b));
        let high = logits.iter().fold(f32::MIN, |a, &b| a.max(b));
        println!("ppocr_rec at 48x16: logits in {low:.4}..{high:.4}");

        // And it feeds the decode. The text is meaningless — the weights are invented —
        // but the confidence must be a probability, which is the end-to-end check that the
        // class-major layout the net writes is the one `ctc::decode` reads.
        let mut keys = String::new();
        for index in 0..crate::post::ctc::DICTIONARY_ENTRIES {
            keys.push((b'a' + (index % 26) as u8) as char);
            keys.push('\n');
        }
        let dictionary = crate::post::ctc::Dictionary::parse(&keys).expect("parses");
        let decoded = crate::post::ctc::decode(&logits, steps, &dictionary).expect("decodes");
        println!("ppocr_rec: {:?} at {:.4}", decoded.text, decoded.confidence);
        assert!(
            (0.0..=1.0).contains(&decoded.confidence),
            "confidence {}",
            decoded.confidence
        );
    }

    /// Run a shipped net on an input from disk and write its output back, for
    /// `scripts/ml/onnx_parity.py` to compare against onnxruntime.
    ///
    /// Ignored, and a no-op without `PARITY_DIR`: it exists to be driven by that script,
    /// which needs the export's ONNX and an `onnxruntime` install that CI does not have.
    /// See the script's header for what the comparison is worth and what it has caught.
    #[test]
    #[ignore = "driven by scripts/ml/onnx_parity.py"]
    fn dump_reference_output() {
        let Ok(dir) = std::env::var("PARITY_DIR") else {
            return;
        };
        let dir = std::path::PathBuf::from(dir);
        let graph = std::env::var("PARITY_GRAPH").expect("PARITY_GRAPH");
        let width: u32 = std::env::var("PARITY_WIDTH")
            .expect("PARITY_WIDTH")
            .parse()
            .expect("a width");
        let raw = std::fs::read(dir.join("input.f32")).expect("the input");
        let input: Vec<f32> = raw
            .chunks_exact(4)
            .map(|c| f32::from_le_bytes([c[0], c[1], c[2], c[3]]))
            .collect();

        // A voice is a runtime download rather than a bundled asset, so the vocoder's
        // `.maml` is given by path instead of being looked up in the tree.
        if graph == "supertonic_voc" {
            let path = std::env::var("PARITY_MAML").expect("PARITY_MAML");
            let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
            let weights =
                crate::weights::Weights::parse(&bytes, crate::weights::graph::SUPERTONIC_VOC)
                    .expect("the vocoder asset parses");