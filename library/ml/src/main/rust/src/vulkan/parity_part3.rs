#[test]
#[ignore = "needs a Vulkan device"]
fn a_clamp_bounds_a_tensor_by_a_pair_from_the_weights() {
    // The vision tower's `use_clipped_linears`. Values are spread well past both bounds so a
    // shader that read only one of them, or read them in the wrong order, cannot pass.
    let values: Vec<f32> = (0..48).map(|i| (i as f32 - 24.0) * 1.7).collect();
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[Fixture::F16(vec![2], vec![-9.5, 6.25])],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the bounds parse");
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(48, 1, 1));
    let last = builder.clamp(first, 0);
    let plan = builder.finish(&[last]).expect("the clamp fixture plan builds");
    compare("a clamp", plan, weights.data().to_vec(), &[&values]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_prefill_split_into_chunks_matches_one_chunk() {
    // Splitting a prefill must not change the answer.
    //
    // It did, and badly: `prefill_layer` attended each chunk against only its own keys, so a
    // second chunk could not see the first. The same prompt then answered differently depending
    // on where the split fell - and every result looked fluent, so nothing short of comparing
    // two splits could reveal it. Short prompts fit one chunk and passed, which is why this
    // survived a "verified" batched-prefill claim.
    //
    // Deliberately at the op level rather than through Gemma: the property belongs to the
    // attention ops, and checking it here does not need 1.3 GB of weights.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 4;
    const T: u32 = 24;
    let q = spread((HEADS * HEAD_DIM * T) as usize, 0.4);
    let k = spread((HEAD_DIM * T) as usize, 0.9);
    let v = spread((HEAD_DIM * T) as usize, 1.3);
    let whole = run_invented(
        &[
            Shape::new(HEADS * HEAD_DIM, 1, T),
            Shape::new(HEAD_DIM, 1, T),
            Shape::new(HEAD_DIM, 1, T),
        ],
        &[&q, &k, &v],
        |b, ids| {
            let scores = b.attn_scores_grouped_prescaled(ids[0], ids[1], HEADS, 1);
            let probs = b.softmax_causal(scores);
            b.attn_apply_grouped(probs, ids[2], HEADS, 1)
        },
    );
    // The first half of the queries against the first half of the keys is the same computation
    // as the first half of the whole, because attention is causal.
    const HALF: u32 = T / 2;
    let clip = |from: &[f32], channels: u32| {
        let mut out = Vec::with_capacity((channels * HALF) as usize);
        for channel in 0..channels {
            for t in 0..HALF {
                out.push(from[(channel * T + t) as usize]);
            }
        }
        out
    };
    let qh = clip(&q, HEADS * HEAD_DIM);
    let kh = clip(&k, HEAD_DIM);
    let vh = clip(&v, HEAD_DIM);
    let half = run_invented(
        &[
            Shape::new(HEADS * HEAD_DIM, 1, HALF),
            Shape::new(HEAD_DIM, 1, HALF),
            Shape::new(HEAD_DIM, 1, HALF),
        ],
        &[&qh, &kh, &vh],
        |b, ids| {
            let scores = b.attn_scores_grouped_prescaled(ids[0], ids[1], HEADS, 1);
            let probs = b.softmax_causal(scores);
            b.attn_apply_grouped(probs, ids[2], HEADS, 1)
        },
    );
    for channel in 0..HEADS * HEAD_DIM {
        for t in 0..HALF {
            let a = whole[(channel * T + t) as usize];
            let b = half[(channel * HALF + t) as usize];
            assert!(
                (a - b).abs() < 0.02,
                "query {t} channel {channel}: {a} over {T} positions, {b} over {HALF}"
            );
        }
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_batched_prefill_attention_agrees_with_the_reference() {
    // The uncached attention path at multi-query, which is what a batched prefill runs. K and V
    // are an eighth of Q's width, so a shader that indexed them by query head would read past
    // its own head and produce plausible nonsense - the same trap the cached path had.
    const HEAD_DIM: u32 = 16;
    const HEADS: u32 = 8;
    const KV_HEADS: u32 = 1;
    const T: u32 = 12;
    let q = spread((HEADS * HEAD_DIM * T) as usize, 0.4);
    let k = spread((KV_HEADS * HEAD_DIM * T) as usize, 0.9);
    let v = spread((KV_HEADS * HEAD_DIM * T) as usize, 1.3);
    agrees_invented(
        "a multi-query prefill attention",
        0,
        &[
            Shape::new(HEADS * HEAD_DIM, 1, T),
            Shape::new(KV_HEADS * HEAD_DIM, 1, T),
            Shape::new(KV_HEADS * HEAD_DIM, 1, T),
        ],
        &[&q, &k, &v],
        |b, ids| {
            let scores = b.attn_scores_grouped_prescaled(ids[0], ids[1], HEADS, KV_HEADS);
            let probs = b.softmax_causal(scores);
            b.attn_apply_grouped(probs, ids[2], HEADS, KV_HEADS)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_windowed_causal_softmax_drops_what_is_behind_the_window() {
    // The sliding half of a prefill. The window is deliberately shorter than the sequence, so a
    // shader that ignored it would keep keys it must drop - and the rows would still sum to one,
    // which is why this compares against the interpreter rather than checking a sum.
    const HEADS: u32 = 2;
    const T: u32 = 16;
    const WINDOW: u32 = 5;
    let scores = spread((HEADS * T * T) as usize, 0.6);
    agrees_invented(
        "a windowed causal softmax",
        0,
        &[Shape::new(HEADS, T, T)],
        &[&scores],
        |b, ids| b.softmax_causal_windowed(ids[0], WINDOW),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_int4_gemv_agrees_with_the_reference_on_a_wide_aligned_row() {
    // `conv_vec_int4` has two paths, and the fixture below only reaches one.
    //
    // Where `in_c` is a multiple of 32 the shader loads a whole block as one `uvec4` - four
    // times fewer memory instructions, which is the difference between 4.7 GB/s and something
    // near the 19.6 a plain read achieves. The other fixture contracts over 100 taps, not a
    // multiple of 32, so it takes the scalar fallback: it passed while the wide path was
    // indexing the buffer wrongly, and only a full Gemma run against onnxruntime caught that.
    //
    // 128 taps is four whole blocks; 64 outputs span several workgroups.
    let out_channels = 64u32;
    let in_channels = 128u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 7) % 16 - 8) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels * blocks)
        .map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32))
        .collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.7);
    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::Relu);
    let plan = builder.finish(&[last]).expect("the wide int4 gemv fixture plan builds");
    compare("a wide-load int4 gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_int4_gemv_agrees_with_the_reference() {
    // `conv_vec_int4.comp`. The shape crosses several blocks on purpose: at `I4_BLOCK` 32 a
    // 100-tap contraction is four blocks, the last of them partial, which is where an off-by-one
    // in the block index or the nibble unpack shows up.
    //
    // Values span the whole signed range so a shader that read the nibbles unsigned - a
    // plausible mistake, and a different quantisation rather than a different spelling - cannot
    // pass.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 7) % 16 - 8) as i8)
        .collect();
    assert!(kernel.iter().any(|&v| v < 0) && kernel.iter().any(|&v| v > 0));
    // A distinct scale per block per channel, each an exact power of two so the interpreter and
    // the device read one number rather than two roundings of one.
    let scales: Vec<f32> = (0..out_channels * blocks)
        .map(|i| 0.007_812_5 * (1.0 + (i % 5) as f32))
        .collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int4(first, 0, out_channels, Act::Relu);
    let plan = builder.finish(&[last]).expect("the int4 gemv fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt4, .. }
        )),
        "a single-position int4 convolution must lower to the gemv shader",
    );
    compare("an int4 gemv", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_int4_convolution_agrees_with_the_reference() {
    // `conv_point_int4.comp`, which folds the scale into the staged tile. That is only correct
    // while a 16-tap tile stays inside one 32-tap block, so the widths here are chosen to make
    // the tiling real: 40 positions is three tiles across, 100 taps is seven staging steps.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let positions = 40u32;
    let blocks = in_channels.div_ceil(32);
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 11) % 16 - 8) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels * blocks)
        .map(|i| 0.015_625 * (1.0 + (i % 3) as f32))
        .collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.4);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I4(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels, blocks], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the int4 fixture parses");
    let input = spread((in_channels * positions) as usize, 0.5);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, positions));
    let last = builder.conv_int4(first, 0, out_channels, Act::Gelu);
    let plan = builder.finish(&[last]).expect("the tiled int4 fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvPointInt4, .. }
        )),
        "a multi-position int4 convolution must lower to the tiled shader",
    );
    compare("a tiled int4 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn the_gemv_int8_shader_agrees_with_the_reference() {
    // `conv_vec_int8.comp`, which every ungrouped single-position 1x1 int8 convolution lowers to.
    // It is a decode step's whole int8 workload bar the cross-attention keys and values, and it
    // reduces across all 64 lanes through shared memory where the other two int8 shaders give each
    // invocation a whole dot product — so a wrong reduction, a wrong row stride or a missing barrier
    // is invisible in the shape and shows up only here.
    //
    // The shape makes both ragged cases fire at once: 20 output channels is not a multiple of the
    // shader's 8 rows, so the last workgroup accumulates three rows that must be discarded at the
    // store; and 100 input channels is not a multiple of the 64-lane stride, so the inner loop's
    // last pass covers 36 lanes and the other 28 must contribute zero.
    let out_channels = 20u32;
    let in_channels = 100u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    // Distinct per channel, and each an exact multiple of a power of two so the interpreter and the
    // device read one number rather than two roundings of one.
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the gemv int8 fixture plan builds");
    // The lowering is automatic, so this is also what asserts it happened.
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader: {:?}",
        plan.ops,
    );
    compare("a gemv int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_gemv_int8_convolution_past_a_segment_boundary_agrees_with_the_reference() {
    // The same shader as above, but with its three tensors pushed past the first descriptor
    // window, so `Segments::rebase` has to move a **word** offset rather than an element one.
    //
    // Nothing else here reaches that path on a device. Every other fixture is a few kilobytes,
    // so it is one window with a zero base and `rebase` returns the push untouched; the only
    // segmented net is Supertonic, which has no single-position int8 convolution. That gap is
    // why `ConvVecInt8` was missing from the word-unit arm of `rebase` while `weight_reads`
    // classified it correctly — the file offsets were right, the rebased offsets were half of
    // right, and no test looked.
    //
    // Run it both ways. Unsegmented it is an ordinary gemv; under
    // `MODELRUNNER_MAX_STORAGE_RANGE=33554432` the padding below guarantees a non-zero base, and
    // a regression reads the wrong weights and fails the comparison rather than erroring.
    // 44 MiB of fp16. The file is then windowed at an 8 MiB stride and the three tensors below
    // land in the sixth window, 4 MiB *past* its 40 MiB base — deliberately not on the boundary
    // itself, where a word-rebased and an element-rebased offset would both saturate to zero and
    // agree by accident.
    const PAD_ELEMENTS: usize = 23_068_672;
    let out_channels = 20u32;
    let in_channels = 100u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.007_812_5 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 1.9);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            // Never read. It exists only to move what follows into a later window.
            Fixture::F16(vec![PAD_ELEMENTS as u32], vec![0.0; PAD_ELEMENTS]),
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread(in_channels as usize, 0.7);

    let mut builder = Builder::new(&weights);
    // The padding is never dispatched over, which `finish` would otherwise reject.
    builder.host_tensor(0, &[PAD_ELEMENTS as u32]);
    let first = builder.input(Shape::new(in_channels, 1, 1));
    let last = builder.conv_int8(
        first,
        1,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the padded gemv int8 fixture plan builds");
    assert!(
        plan.ops.iter().any(|op| matches!(
            op,
            crate::nets::Op::Dispatch { kind: crate::nets::Kind::ConvVecInt8, .. }
        )),
        "a single-position int8 convolution must lower to the gemv shader",
    );
    // Reported rather than asserted, because the useful configuration is the forced one and a
    // plain run should still check the numbers rather than skip.
    let bytes = weights.data().len() as u64;
    let range = device().limits.max_storage_buffer_range;
    if bytes > range {
        println!("{bytes} bytes over a {range}-byte range: the kernel is in a later window");
    } else {
        println!(
            "{bytes} bytes inside a {range}-byte range: one window, so this run does not \
             exercise rebasing. Set MODELRUNNER_MAX_STORAGE_RANGE=33554432."
        );
    }
    compare("a gemv int8 convolution past a boundary", plan, weights.data().to_vec(), &[&input]);
}
