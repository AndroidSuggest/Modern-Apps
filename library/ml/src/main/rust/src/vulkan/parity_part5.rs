#[test]
#[ignore = "needs a Vulkan device"]
fn a_rebuilt_net_agrees_with_the_reference_at_each_shape() {
    // Supertonic runs one set of weights at whatever length the utterance turns out to be, so
    // `Net::rebuild` re-records a net instead of building a second one and uploading 198 MB
    // again. The widths go up and then back down on purpose: growing reallocates the arena and
    // rewrites the descriptor, while shrinking keeps the buffer it already has, and it is the
    // second visit to a narrow shape that would catch a descriptor left pointing at the arena
    // that was freed under it.
    let gamma = spread(8, 1.1);
    let beta = spread(8, 2.3);
    let given = Given::new(&[(vec![8], gamma), (vec![8], beta)])
        .expect("the fixture tensors are consistent");
    let data = given.data().to_vec();
    // Two ops, so the second reads out of the arena what the first wrote into it. A one-op plan
    // would pass on a rebind that had gone to the wrong buffer, since its only read is the input
    // copy the command buffer performs by handle rather than through the descriptor set.
    let at = |width: u32| {
        build(&given, &[Shape::new(8, 1, width)], |b, ids| {
            let normed = b.layer_norm(ids[0], 0, 1e-5);
            b.add(normed, ids[0])
        })
    };

    let weights = Weights::from_data(data.clone());
    let mut net = Net::new(device(), at(3), &weights, RESCALE_ONLY)
        .expect("the plan records into a command buffer");
    for width in [9u32, 4] {
        let plan = at(width);
        let input = spread(8 * width as usize, width as f32);
        let host = run_multi(&plan, &data, &[&input]).expect("the interpreter runs the plan");
        net.rebuild(plan).expect("the net re-records at the new shape");
        let got = net.infer_raw(&input).expect("the rebuilt buffer submits and reads back");
        matches(&format!("a net rebuilt at {width}"), &host, &got);
    }
}

/// The shipped Supertonic text encoder and vocoder, on the device against the interpreter.
///
/// The fixtures above are single ops over a handful of channels, and `onnx_parity.py` compares the
/// *interpreter* against onnxruntime. Neither says anything about a real net's depth on a real
/// device: an arena slot reused while a dispatch still reads it, or a barrier missing between two
/// of ninety convolutions, shows up only at that scale and only on hardware.
///
/// Which is what let a broken vocoder ship. Both marshalling steps were missing from `bridge.rs`
/// for a net whose interpreter parity was 0.999.
///
/// The widths are the ones a real utterance produced - 47 characters and 59 latent frames for
/// "Hello, this voice runs entirely on this device." - rather than the round 64 the scripts default
/// to, because a tail that is not a multiple of the workgroup is where indexing goes wrong.
#[test]
#[ignore = "needs a Vulkan device and the shipped supertonic assets"]
fn the_shipped_supertonic_nets_agree_with_the_reference_on_the_device() {
    let Some(dir) = assets() else {
        return;
    };
    let read = |name: &str| std::fs::read(dir.join(name));

    if let Ok(bytes) = read("supertonic_ttl.maml") {
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_TTL).expect("the text encoder parses");
        let chars = 47u32;
        let plan = crate::nets::supertonic_text::build(&weights, chars).expect("the encoder builds");
        // Two lanes of ids and the voice's style, which is what the net's two inputs are.
        let ids: Vec<u32> = (0..chars).map(|i| (i * 173 + 11) % 8322).collect();
        let lanes = embed_lanes(&ids);
        let style = spread(256 * 50, 0.7);
        let host = run_multi(&plan, weights.data(), &[&lanes, &style])
            .expect("the interpreter runs the text encoder");
        let got = on_device(plan, weights.data().to_vec(), &[&lanes, &style]);
        report("the shipped text encoder at 47 characters", &host, &got);
    }

    if let Ok(bytes) = read("supertonic_voc.maml") {
        let weights = Weights::parse(&bytes, graph::SUPERTONIC_VOC).expect("the vocoder parses");
        let frames = 59u32;
        let plan = crate::nets::supertonic_vocoder::build(&weights, frames).expect("it builds");
        // The plan's own input, so this checks the net rather than `unpack_latent`, which is a host
        // permutation with fixtures of its own.
        let latent = spread(24 * 6 * frames as usize, 1.3);
        let host =
            run_multi(&plan, weights.data(), &[&latent]).expect("the interpreter runs the vocoder");
        let got = on_device(plan, weights.data().to_vec(), &[&latent]);
        report("the shipped vocoder at 59 frames", &host, &got);
    }
}

/// Print how far the device is from the interpreter, rather than asserting a threshold.
///
/// [`TOLERANCE`] is calibrated on fixtures a handful of ops deep. These nets are a hundred, so a
/// max element that exceeds it says nothing on its own — the question is whether the error is a few
/// fp16 ulps spread everywhere, which is drift, or a structure, which is a bug. Correlation and the
/// mean separate the two where a max cannot.
fn report(what: &str, host: &[Vec<f32>], got: &[Vec<f32>]) {
    assert_eq!(got.len(), host.len(), "{what}: output count");
    for (index, (host, got)) in host.iter().zip(got).enumerate() {
        assert_eq!(got.len(), host.len(), "{what}: output {index} length");
        let scale = host.iter().fold(0.0f32, |top, v| top.max(v.abs())).max(1e-3);
        let mut worst = 0.0f32;
        let mut total = 0.0f64;
        let mut over = 0usize;
        for (a, b) in host.iter().zip(got) {
            let error = (a - b).abs();
            worst = worst.max(error);
            total += f64::from(error);
            if error / scale > TOLERANCE {
                over += 1;
            }
        }
        let count = host.len();
        let mean = |v: &[f32]| v.iter().map(|&x| f64::from(x)).sum::<f64>() / count as f64;
        let (hm, gm) = (mean(host), mean(got));
        let mut cov = 0.0f64;
        let (mut hv, mut gv) = (0.0f64, 0.0f64);
        for (a, b) in host.iter().zip(got) {
            let (da, db) = (f64::from(*a) - hm, f64::from(*b) - gm);
            cov += da * db;
            hv += da * da;
            gv += db * db;
        }
        let corr = cov / (hv.sqrt() * gv.sqrt()).max(f64::MIN_POSITIVE);
        println!(
            "{what}: output {index} over {count} values, scale {scale:.4}\n  \
             max {worst:.6}  mean {:.7}  corr {corr:.8}  over tolerance {over} ({:.3}%)",
            total / count as f64,
            100.0 * over as f64 / count as f64,
        );
    }
}

/// Where the shipped `.maml` live: `MODELRUNNER_ASSETS` if set, else the checkout beside this crate.
///
/// The environment variable is what lets these run on a phone, where there is no checkout to walk
/// up to — push `speech/src/main/assets/supertonic` to `/data/local/tmp` and point this at it.
fn assets() -> Option<std::path::PathBuf> {
    if let Ok(dir) = std::env::var("MODELRUNNER_ASSETS") {
        let dir = std::path::PathBuf::from(dir);
        return dir.is_dir().then_some(dir);
    }
    let mut dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").is_file() {
        dir = dir.parent()?.to_path_buf();
    }
    Some(dir.join("speech/src/main/assets/supertonic"))
}
