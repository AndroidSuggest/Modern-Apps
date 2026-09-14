    // The plan **executed** on a real device, not merely recorded.
    //
    // Ignored, and needs both `GEMMA4_AUDIO_MAML` and a Vulkan device. Run as:
    // 
    //  ```text
    //  GEMMA4_AUDIO_MAML=.../gemma4_audio.maml \
    //    cargo test -p modelrunner --lib -- --include-ignored the_plan_runs_on_a_device
    //  ```
    // 
    //  Recording a plan proves the shapes and the tensor bindings agree. It does not prove the
    //  arena fits, that every op kind has a pipeline, that the dispatch sizes are legal, or that
    //  descriptor limits hold at 750 tokens over a 49.8 MiB arena and 695 ops. Those only fail
    //  when something submits.
    // 
    //  # This says the tower RUNS. It does not say the tower is CORRECT.
    // 
    //  Those are two different claims and the difference is the whole reason this test is cheap.
    //  `examples/check_gemma4_vision_parity.rs` runs the vision tower on a GPU **and compares it
    //  to the reference**, and its result is a number: 0.999123. This runs the audio tower on a
    //  GPU and checks the output is alive, and its result is that nothing crashed. Both are "end
    //  to end on device"; only one of them is evidence of correctness. A systematically wrong
    //  tower passes everything below.
    // 
    //  Numerical parity is a separate task and a separate harness. Do not read a pass here as
    //  standing in for it.
    // 
    //  # Three assertions, and each covers a hole the previous two leave
    // 
    //  * **finite** - NaN is non-zero, so a tower emitting NaN everywhere satisfies the liveness
    //    count below and looks exactly like a healthy one.
    //  * **non-zero** - a plan whose shaders never ran reads back an untouched arena, which is
    //    zeros, and every shape assertion still passes. The input is a ramp rather than silence
    //    for the same reason: a zero input cannot tell a working pass from a dead one.
    //  * **a function of its input** - a pass that ignored its input and emitted a fixed pattern
    //    of biases would be finite, entirely non-zero, and identical at every length. That is
    //    not hypothetical: the peak here is 16.453 at both 250 and 750 tokens, which looks like
    //    exactly that failure and is in fact the ramp's period repeating.
    #[test]
    #[ignore = "needs GEMMA4_AUDIO_MAML and a Vulkan device"]
    fn the_plan_runs_on_a_device() {
        let Ok(path) = std::env::var("GEMMA4_AUDIO_MAML") else {
            // Visible under --nocapture, because a silent skip reports as a pass in 0.00s and
            // that is indistinguishable from a device run in any summary line.
            println!("SKIPPED the_plan_runs_on_a_device: GEMMA4_AUDIO_MAML is not set");
            return;
        };
        let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("{path}: {e}"));
        let weights = crate::weights::Weights::parse(&bytes, crate::weights::graph::GEMMA4_AUDIO)
            .expect("a gemma4_audio .maml");
        let context = crate::vulkan::context::shared().expect("this host has no usable Vulkan device");

        // The hard case first: the 30-second cap, which is where the arena and the descriptor
        // limits are largest. A short clip would exercise neither.
        for frames in [crate::logmel::frame_count(MAX_SAMPLES) as u32, 999] {
            let seq = tokens(frames);
            let plan = build(&weights, Mode::Clip { frames }).expect("the clip pass");
            let ops = plan.ops.len();
            let arena = plan.arena_elems;
            let mut net =
                crate::vulkan::run::Net::new(context.clone(), plan, &weights, crate::preprocess::RESCALE_ONLY)
                    .unwrap_or_else(|e| panic!("{frames} frames records into a command buffer: {e}"));

            // A ramp rather than zeros: a silent input cannot distinguish a working pass from
            // one whose shaders wrote nothing, since both give zeros out.
            let mel: Vec<f32> = (0..frames as usize * MELS as usize)
                .map(|i| ((i % 257) as f32 / 257.0 - 0.5) * 4.0)
                .collect();
            let input = prepare(&mel, frames).expect("the spectrogram lays out");
            let out = net
                .infer_raw_many(&[&input])
                .unwrap_or_else(|e| panic!("{frames} frames submits and reads back: {e}"));

            assert_eq!(out.len(), 1, "Clip has one output");
            let got = &out[0];
            assert_eq!(got.len(), (OUT_DIM * seq) as usize, "[{OUT_DIM}, 1, {seq}]");
            assert!(got.iter().all(|v| v.is_finite()), "{frames} frames produced a NaN or an inf");
            let nonzero = got.iter().filter(|v| **v != 0.0).count();
            assert!(
                nonzero * 20 > got.len(),
                "{frames} frames: only {nonzero} of {} outputs are non-zero, which is what an \
                 arena nothing wrote to looks like",
                got.len()
            );
            let peak = got.iter().fold(0.0_f32, |m, v| m.max(v.abs()));
            println!(
                "gemma4_audio ran at {frames} frames ({seq} tokens): {ops} ops, \
                 {:.1} MiB arena, {nonzero}/{} non-zero, peak {peak:.3}",
                arena as f64 * 2.0 / (1024.0 * 1024.0),
                got.len()
            );

            // THE OUTPUT MUST BE A FUNCTION OF THE INPUT. Liveness is not enough: a pass that
            // ignored its input and emitted a fixed pattern of biases would be finite, entirely
            // non-zero, and identical at every length - which is exactly what the equal peaks
            // above look like. They are equal because the ramp has period 257, so both lengths
            // contain the same local windows and a 12-wide causal tower sees the same extremes.
            // That is the benign explanation, and this is the check that distinguishes it from
            // the malign one rather than leaving it as an argument.
            let other: Vec<f32> = (0..frames as usize * MELS as usize)
                .map(|i| ((i % 149) as f32 / 149.0 - 0.5) * 7.0)
                .collect();
            let second = net
                .infer_raw_many(&[&prepare(&other, frames).expect("the second spectrogram")])
                .unwrap_or_else(|e| panic!("{frames} frames submits a second input: {e}"));
            let differing =
                got.iter().zip(&second[0]).filter(|(a, b)| (*a - *b).abs() > 1e-4).count();
            assert!(
                differing * 2 > got.len(),
                "{frames} frames: only {differing} of {} outputs moved when the input changed, \
                 so the pass is not reading it",
                got.len()
            );
            assert!(second[0].iter().all(|v| v.is_finite()), "the second input produced a NaN");
        }
    }
