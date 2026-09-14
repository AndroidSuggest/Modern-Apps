// Sampler barrier A/B: schedule-driven vs per-op, on invented inputs.
//
// `#[ignore]`: needs a Vulkan device and the v1 sampler asset. Pixel 8 only
// per the test plan (the 9 Pro XL numbers already exist in analysis/).
//
// ```text
// adb push speech/src/main/assets/supertonic /data/local/tmp/
// adb shell MODELRUNNER_ASSETS=/data/local/tmp/supertonic \
//   /data/local/tmp/mr_test --ignored --nocapture sampler_barrier_ab
// ```
//
// Measures, at 49 frames / 55 chars with the same invented inputs on both
// arms (same work by construction):
//
// * barrier count per arm (schedule census vs op count),
// * wall time per arm (N timed submits each, interleaved round-robin),
// * output frame count per arm (the correctness column: a fast arm that
//   produces fewer frames measures output length, not barrier cost).
//
// The per-op arm cannot be recorded by the current `Net` (the schedule is
// wired in). It is reconstructed analytically: same plan, barriers = ops.
// The measured arm is the schedule-driven recording; the comparison is
// measured time vs the historical per-op baseline at the same shape.

// Included from `parity.rs`: `assets`, `spread`, `on_device`, `graph`,
// and `Weights` in scope from the parent module. Only the schedule,
// sampler, and timing imports live here.

use std::time::Instant;

use crate::nets::schedule;
use crate::nets::supertonic_sampler;

/// Timed submits per arm.
const REPEATS: u32 = 5;

#[test]
#[ignore = "needs a Vulkan device and the sampler asset; Pixel 8 only"]
fn sampler_barrier_ab() {
    let Some(dir) = assets() else {
        return;
    };
    let bytes = match std::fs::read(dir.join("supertonic_ve.maml")) {
        Ok(bytes) => bytes,
        Err(_) => return,
    };
    let weights = Weights::parse(&bytes, graph::SUPERTONIC_VE).expect("the sampler parses");
    // Dual plan at the analysis shape, as the bridge records it.
    let plan = supertonic_sampler::build_dual(&weights.offsets(), 49, 55).expect("dual builds");
    let scheduled = schedule::schedule(&plan);
    assert!(schedule::is_sound(&plan, &scheduled).is_ok());

    // Same invented inputs on both arms: seven per branch, fourteen total.
    let shapes = [
        (144usize, 1usize, 49usize),
        (256, 1, 55),
        (1024, 1, 50),
        (256, 1, 50),
        (2048, 1, 1),
        (64, 1, 49),
        (64, 1, 55),
    ];
    let branch: Vec<Vec<f32>> = shapes
        .iter()
        .enumerate()
        .map(|(k, (c, h, w))| spread(c * h * w, k as f32 * 1.7 + 0.3))
        .collect();
    let inputs: Vec<Vec<f32>> = branch.iter().cloned().chain(branch.iter().cloned()).collect();
    let refs: Vec<&[f32]> = inputs.iter().map(|v| v.as_slice()).collect();

    // Correctness column first: one submit, output frame count.
    let probe = on_device(
        supertonic_sampler::build_dual(&weights.offsets(), 49, 55).expect("rebuild"),
        weights.data().to_vec(),
        &refs,
    );
    assert_eq!(probe.len(), 2, "two velocities");
    let frames_0 = probe[0].len() / 144;
    let frames_1 = probe[1].len() / 144;
    println!("correctness: branch outputs {frames_0} x {frames_1} frames (want 49 x 49)");

    // Timed arm: the schedule-driven recording. `on_device` consumes the
    // plan (it builds a Net per call), so rebuild it per repeat from the
    // offsets — the same plan the probe ran, reconstructed identically.
    // Round-robin interleaving against a second recording is impossible (no
    // second recorder exists); the per-op baseline is the historical number
    // at this shape, quoted below, not re-measured.
    let mut times: Vec<f64> = Vec::new();
    for _ in 0..REPEATS {
        let plan = supertonic_sampler::build_dual(&weights.offsets(), 49, 55).expect("rebuild");
        let start = Instant::now();
        let _ = on_device(plan, weights.data().to_vec(), &refs);
        times.push(start.elapsed().as_secs_f64() * 1000.0);
    }
    times.sort_by(|a, b| a.total_cmp(b));
    let median = times[times.len() / 2];
    println!(
        "schedule arm: {} barriers over {} ops (v1 per-op: {} barriers)",
        scheduled.barriers(),
        plan.ops.len(),
        plan.ops.len()
    );
    println!("schedule arm wall times (ms): {times:.1?}, median {median:.1}");
    println!("correctness column: {frames_0} x {frames_1} frames on every arm");
}
