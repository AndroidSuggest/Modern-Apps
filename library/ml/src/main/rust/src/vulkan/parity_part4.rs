#[test]
#[ignore = "needs a Vulkan device"]
fn the_gemv_and_tiled_int8_shaders_agree_with_each_other() {
    // The same weights over one position and over 16. `Builder` sends the first to
    // `conv_vec_int8.comp` and the second to `conv_point_int8.comp`, and the wide run's position 0
    // must equal the narrow one — the strongest statement available that adding the lowering
    // changed no numbers, and the one a device can make that the interpreter cannot, since the
    // interpreter serves both from the same `conv_int8`.
    let out_channels = 24u32;
    let in_channels = 64u32;
    let width = 16u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 53) % 241 - 120) as i8)
        .collect();
    let scales: Vec<f32> = (0..out_channels).map(|c| 0.015_625 * (1.0 + c as f32)).collect();
    let biases: Vec<f32> = spread(out_channels as usize, 0.3);
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 1], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");

    let narrow = spread(in_channels as usize, 0.4);
    // Channel-major, so position 0 of each channel is every `width`th element.
    let mut wide = vec![0.0f32; (in_channels * width) as usize];
    for channel in 0..in_channels as usize {
        wide[channel * width as usize] = narrow[channel];
    }

    let run = |shape: Shape, input: &[f32]| -> Vec<f32> {
        let mut builder = Builder::new(&weights);
        let first = builder.input(shape);
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
        let plan = builder.finish(&[last]).expect("the comparison plan builds");
        let mut out = on_device(plan, weights.data().to_vec(), &[input]);
        out.pop().expect("one output")
    };
    let one = run(Shape::new(in_channels, 1, 1), &narrow);
    let many = run(Shape::new(in_channels, 1, width), &wide);
    for channel in 0..out_channels as usize {
        let gemv = one[channel];
        let tiled = many[channel * width as usize];
        // Both accumulate in fp32 and store fp16, but in a different order, so the last fp16 place
        // can differ — the same tolerance `matches` applies.
        let tolerance = 1e-3 * gemv.abs().max(1.0);
        assert!(
            (gemv - tiled).abs() <= tolerance,
            "channel {channel}: gemv {gemv} against tiled {tiled}",
        );
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn rotary_agrees_with_the_reference() {
    // The half-split convention: `out[j] = x[j] cos - x[j + half] sin`, within each head. A
    // shader that paired adjacent channels instead — the other common convention — gets the
    // same shape and the same magnitude, so only the values catch it.
    let x = spread(8 * 4, 0.0);
    let angles = spread(4 * 4, 1.3);
    agrees(
        "rotary",
        &[Shape::new(8, 1, 4), Shape::new(4, 1, 4)],
        &[&x, &angles],
        &[],
        |b, ids| b.rotary(ids[0], ids[1], 2),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_folded_constant_agrees_with_the_reference() {
    // The only op that reads nothing from the arena, so it is also the only one whose output
    // offset the shader cannot cross-check against an input it just read.
    let x = spread(4 * 3, 0.4);
    let folded = spread(4 * 3, 2.1);
    agrees(
        "a folded constant",
        &[Shape::new(4, 1, 3)],
        &[&x],
        &[(vec![4, 1, 3], folded)],
        |b, ids| {
            let constant = b.constant(0, Shape::new(4, 1, 3));
            b.add(ids[0], constant)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_per_channel_shift_agrees_with_the_reference() {
    // `a + b` where b is C x 1 x 1. The mirror of mul_channel, and the one thing Supertonic's
    // four timestep conditioning layers need.
    let x = spread(4 * 3, 0.2);
    let shift = spread(4, 3.0);
    agrees(
        "a per-channel shift",
        &[Shape::new(4, 1, 3), Shape::new(4, 1, 1)],
        &[&x, &shift],
        &[],
        |b, ids| b.add_channel(ids[0], ids[1]),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn attention_agrees_with_the_reference() {
    // Deliberately not self-attention: `Q . K^T` is symmetric when Q == K, so a fixture built
    // from one tensor passes with the operands swapped and with the query and key axes
    // transposed. Four queries against five keys makes the score map rectangular, which pins
    // the orientation as well as the arithmetic.
    let q = spread(8 * 4, 0.0);
    let k = spread(8 * 5, 1.7);
    let v = spread(8 * 5, 2.9);
    agrees(
        "attention",
        &[Shape::new(8, 1, 4), Shape::new(8, 1, 5), Shape::new(8, 1, 5)],
        &[&q, &k, &v],
        &[],
        |b, ids| {
            let scores = b.attn_scores(ids[0], ids[1], 2);
            let probs = b.softmax(scores);
            b.attn_apply(probs, ids[2], 2)
        },
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn layer_norm_over_channels_agrees_with_the_reference() {
    // The reduction is strided here, not contiguous: these sequences are [d_model, 1, T], so
    // normalising over channels reads a stride apart. A shader that reduced over W instead
    // still writes plausible unit-variance numbers.
    let x = spread(8 * 5, 0.6);
    let gamma = spread(8, 1.1);
    let beta = spread(8, 2.3);
    agrees(
        "layer norm",
        &[Shape::new(8, 1, 5)],
        &[&x],
        &[(vec![8], gamma), (vec![8], beta)],
        |b, ids| b.layer_norm(ids[0], 0, 1e-5),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_two_lane_embedding_agrees_with_the_reference() {
    // Past 2048 rows an id does not survive a single fp16 lane, so it arrives as lo + 2048 * hi.
    // A shader that read only the low lane returns a real row of the table — the wrong one.
    let rows = 3000u32;
    let channels = 4u32;
    let ids = [7u32, 2048, 2999];
    let lanes = embed_lanes(&ids);
    let table = spread((rows * channels) as usize, 0.9);
    agrees(
        "a two-lane embedding",
        &[Shape::new(2, 1, ids.len() as u32)],
        &[&lanes],
        &[(vec![rows, channels], table)],
        |b, ids| b.embed(ids[0], 0, rows, channels),
    );
}

#[test]
#[ignore = "needs a Vulkan device"]
fn an_int8_convolution_agrees_with_the_reference() {
    // The only op whose weights are not fp16, so the only one where the shader and the interpreter
    // address the weights buffer differently: 32-bit words unpacked to bytes on one side, a byte
    // index into the undecoded blob on the other. Four output channels with four *different*
    // scales, because a shader that read `scale[0]` for every channel — which is what this op did
    // before the export turned out to be quantised per column — still returns the right shape and
    // three wrong channels.
    //
    // Deliberately a `1 x 3` rather than a `1 x 1`: an ungrouped `1 x 1` now lowers to
    // `Kind::ConvPointInt8` instead, so a pointwise fixture here would leave `conv_int8.comp`
    // untested on the device. The spatial kernel also puts its zero padding under test, which is
    // the one thing the tiled path has none of.
    let out_channels = 4u32;
    let in_channels = 3u32;
    let width = 5u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels * 3) as i32)
        .map(|i| ((i * 7) % 61 - 30) as i8)
        .collect();
    let scales: Vec<f32> = vec![0.25, 0.5, 0.0625, 1.0];
    let biases: Vec<f32> = vec![0.5, -0.25, 1.0, 0.0];
    let blob = write_mixed(
        graph::SUPERTONIC_VE,
        &[
            Fixture::I8(vec![out_channels, in_channels, 1, 3], kernel),
            Fixture::F16(vec![out_channels], scales),
            Fixture::F16(vec![out_channels], biases),
        ],
    );
    let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let input = spread((in_channels * width) as usize, 0.3);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, width));
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 3),
        (1, 1),
        (1, 1),
        (0, 1, 0, 1),
        1,
        Act::Relu,
    );
    let plan = builder.finish(&[last]).expect("the int8 fixture plan builds");
    compare("an int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_tiled_int8_convolution_agrees_with_the_reference() {
    // `conv_point_int8.comp`, which every ungrouped 1x1 int8 convolution lowers to and which
    // Supertonic's sampler is 92% made of by parameter count.
    //
    // The shape is chosen so nothing about the tiling is exercised only at its happy path: 20
    // output channels and 21 positions are each more than one 16-wide tile and neither divides it,
    // so the four tiles include partial ones in both axes, and 24 input channels make the
    // accumulation loop take two staging steps of which the second is half out of range. A shader
    // that dropped the zero-fill on an out-of-range staging slot, or that mismatched its two
    // barriers, is wrong only on a fixture with all three of those properties.
    let out_channels = 20u32;
    let in_channels = 24u32;
    let width = 21u32;
    let kernel: Vec<i8> = (0..(out_channels * in_channels) as i32)
        .map(|i| ((i * 37) % 251 - 125) as i8)
        .collect();
    // Distinct per channel, as in the untiled fixture, and every one an exact multiple of a power
    // of two so the interpreter and the device read one number rather than two roundings of one.
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
    let input = spread((in_channels * width) as usize, 0.7);

    let mut builder = Builder::new(&weights);
    let first = builder.input(Shape::new(in_channels, 1, width));
    // Gelu because that is what follows these convolutions in the sampler, and because it is the
    // activation whose input range the dequantisation scale decides.
    let last = builder.conv_int8(
        first,
        0,
        out_channels,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::Gelu,
    );
    let plan = builder.finish(&[last]).expect("the tiled int8 fixture plan builds");
    compare("a tiled int8 convolution", plan, weights.data().to_vec(), &[&input]);
}

#[test]
#[ignore = "needs a Vulkan device"]
fn report_the_tiled_int8_convolution_against_the_tiled_fp16_one() {
    // The measurement Phase 2 of the int8 plan is gated on, printed by
    // `cargo test -- --ignored --nocapture`. `conv_point_int8.comp` exists so that quantising
    // Supertonic's sampler does not move 92% of its parameters onto the untiled `conv_int8.comp`,
    // which was measured at 15.2 GFLOP/s against a device peak of order 1000. That is only worth
    // having if the int8 tiled path is close to the fp16 tiled one, because the alternative to
    // quantising is bundling 198 MB of fp16 rather than 105 MB of int8 — a size decision, not a
    // speed one, so a large slowdown here means take the size.
    //
    // Eight chained `512 -> 512` projections over 64 positions: the sampler's shape, deep enough
    // that the shader rather than the submit-and-read-back dominates.
    const CHANNELS: u32 = 512;
    const POSITIONS: u32 = 64;
    const LAYERS: usize = 8;
    const RUNS: usize = 20;

    // The two nets compute the same numbers, not merely the same shapes: the int8 weights are
    // exact small integers and the scale is an exact power of two, so dequantising is lossless
    // here. That is what lets the timings be compared against each other *and* the outputs
    // compared for equality, which is the only thing standing between a fast shader and a shader
    // that quietly wrote nothing.
    //
    // The divisor is what keeps eight chained layers inside fp16. A `512`-wide dot product of
    // weights spread over -3..3 has a gain of about `2 * sqrt(512) / DIVISOR`, so at 16 the chain
    // grows by ~2.8 a layer and reaches infinity by the eighth; at 64 it shrinks, which fp16
    // tolerates far better than it tolerates overflow.
    const DIVISOR: f32 = 64.0;
    let taps = (CHANNELS * CHANNELS) as usize;
    let int8_kernel: Vec<i8> = (0..taps).map(|i| (i % 7) as i8 - 3).collect();
    let fp16_kernel: Vec<f32> = int8_kernel.iter().map(|&w| f32::from(w) / DIVISOR).collect();
    let scales = vec![1.0 / DIVISOR; CHANNELS as usize];
    let biases = vec![0.0f32; CHANNELS as usize];

    let mut fp16_tensors = Vec::new();
    let mut int8_tensors = Vec::new();
    for _ in 0..LAYERS {
        let dims = vec![CHANNELS, CHANNELS, 1, 1];
        fp16_tensors.push(Fixture::F16(dims.clone(), fp16_kernel.clone()));
        fp16_tensors.push(Fixture::F16(vec![CHANNELS], biases.clone()));
        int8_tensors.push(Fixture::I8(dims, int8_kernel.clone()));
        int8_tensors.push(Fixture::F16(vec![CHANNELS], scales.clone()));
        int8_tensors.push(Fixture::F16(vec![CHANNELS], biases.clone()));
    }

    let input = spread((CHANNELS * POSITIONS) as usize, 0.5);
    let mut timings = Vec::new();
    let mut outputs = Vec::new();
    for (what, tensors, per_layer) in
        [("fp16", fp16_tensors, 2usize), ("int8", int8_tensors, 3usize)]
    {
        let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
        let weights = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the blob parses");
        let mut builder = Builder::new(&weights);
        let mut x = builder.input(Shape::new(CHANNELS, 1, POSITIONS));
        for layer in 0..LAYERS {
            let at = layer * per_layer;
            x = if per_layer == 2 {
                builder.conv(x, at, CHANNELS, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, Act::Gelu)
            } else {
                builder.conv_int8(
                    x,
                    at,
                    CHANNELS,
                    (1, 1),
                    (1, 1),
                    (1, 1),
                    (0, 0, 0, 0),
                    1,
                    Act::Gelu,
                )
            };
        }
        let plan = builder.finish(&[x]).expect("the benchmark plan builds");
        let mut net = Net::new(device(), plan, &weights, RESCALE_ONLY)
            .expect("the plan records into a command buffer");
        // One discarded run: the first submit pays for pipeline warm-up and for faulting the
        // weights into device memory, neither of which a steady-state utterance pays per step.
        let first = net.infer_raw(&input).expect("the benchmark submits and reads back");
        let start = std::time::Instant::now();
        for _ in 0..RUNS {
            net.infer_raw(&input).expect("the benchmark submits and reads back");
        }
        let each = start.elapsed().as_secs_f64() / RUNS as f64;
        // Two operations per multiply-accumulate, which is how the 15.2 GFLOP/s this shader
        // exists to escape was counted.
        let flops = 2.0 * f64::from(CHANNELS) * f64::from(CHANNELS) * f64::from(POSITIONS)
            * LAYERS as f64;
        println!("{what}: {:.3} ms per pass, {:.1} GFLOP/s", each * 1e3, flops / each / 1e9);
        timings.push(each);
        outputs.push(first);
    }

    match (timings.as_slice(), outputs.as_slice()) {
        ([fp16, int8], [from_fp16, from_int8]) => {
            println!("int8 / fp16 = {:.2}x", int8 / fp16);
            matches("the two benchmark nets", from_fp16, from_int8);
        }
        _ => panic!("two nets were timed"),
    }
}

#[test]
#[ignore = "needs a Vulkan device"]
fn a_net_uploaded_from_a_file_agrees_with_one_uploaded_from_memory() {
    // The bundled path end to end: the same plan, the same weights, uploaded once from a `Vec<u8>`
    // and once by streaming a file in chunks. The two must produce identical output, because a
    // wrong `dst_offset` on any chunk but the first gives a net whose early layers are right and
    // whose later ones read whatever the buffer was allocated with — plausible-looking audio, not
    // an error.
    //
    // The blob is deliberately larger than the chunk size the upload uses, so more than one copy is
    // issued. A single-chunk fixture exercises none of the arithmetic that can be wrong.
    const CHANNELS: u32 = 1024;
    const LAYERS: usize = 6;
    let taps = (CHANNELS * CHANNELS) as usize;
    let kernel: Vec<f32> = (0..taps).map(|i| ((i % 11) as f32 - 5.0) / 1024.0).collect();
    let bias = vec![0.0f32; CHANNELS as usize];
    let mut tensors = Vec::new();
    for _ in 0..LAYERS {
        tensors.push(Fixture::F16(vec![CHANNELS, CHANNELS, 1, 1], kernel.clone()));
        tensors.push(Fixture::F16(vec![CHANNELS], bias.clone()));
    }
    let blob = write_mixed(graph::SUPERTONIC_VE, &tensors);
    assert!(
        blob.len() as u64 > Net::CHUNK_BYTES,
        "{} bytes fits in one {}-byte chunk, so nothing is being tested",
        blob.len(),
        Net::CHUNK_BYTES,
    );

    let input = spread((CHANNELS * 8) as usize, 0.25);
    let plan_for = |source: &dyn WeightSource| -> Plan {
        let mut builder = Builder::new(source);
        let mut x = builder.input(Shape::new(CHANNELS, 1, 8));
        for layer in 0..LAYERS {
            x = builder.conv_same(x, layer * 2, CHANNELS, 1, 1, Act::Relu);
        }
        builder.finish(&[x]).expect("the upload fixture plan builds")
    };

    let parsed = Weights::parse(&blob, graph::SUPERTONIC_VE).expect("the fixture blob parses");
    let mut from_memory = Net::new(device(), plan_for(&parsed), &parsed, RESCALE_ONLY)
        .expect("the in-memory plan records");
    let want = from_memory.infer_raw(&input).expect("the in-memory net runs");

    // A leading pad, so the file's data section does not start where the file does — which is what
    // an asset inside an APK looks like, and the one thing an offset-free reader gets wrong.
    let path = std::env::temp_dir().join("modelrunner-upload-parity.maml");
    let mut staged = vec![0x5Au8; 8192];
    staged.extend_from_slice(&blob);
    std::fs::write(&path, &staged).expect("the fixture file writes");
    let file = std::fs::File::open(&path).expect("the fixture file reopens");
    let streamed = Streamed::open(file, 8192, blob.len() as u64, graph::SUPERTONIC_VE)
        .expect("the fixture file streams");
    let mut from_file = Net::new(device(), plan_for(&parsed), &streamed, RESCALE_ONLY)
        .expect("the streamed plan records");
    let got = from_file.infer_raw(&input).expect("the streamed net runs");

    matches("a net uploaded from a file", &want, &got);
}
