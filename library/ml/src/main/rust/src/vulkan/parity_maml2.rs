// The v2 sampler on the device, through the v2 bridge.
//
// `#[ignore]`: needs a Vulkan device and the v1 + v2 sampler assets.
//
// ```text
// adb push speech/src/main/assets/supertonic /data/local/tmp/
// adb shell MODELRUNNER_ASSETS=/data/local/tmp/supertonic \
//   /data/local/tmp/mr_test --ignored --nocapture v2_sampler_on_device
// ```
//
// Runs the v2 plan (verify → infer → `lower_nchw`) on the device against
// the bridge blob, and requires it to agree with the CPU reference
// interpreter. Kernels are NCHW in the file, so the NCHW kernels both sides
// run read true values and the agreement is numeric truth, not two sides
// misreading one repack identically.
//
// This is the pilot exit's device half: file → device execution plus the
// schedule-driven recorder, on real weights, with the interpreter as oracle.
//
// Included from `parity.rs` (same file-include pattern as `parity_partN`):
// `assets`, `on_device`, `report`, `spread`, `run_multi`, `Weights`, and
// `graph` are in scope from the parent module. Only the v2 loader is
// imported here.

use crate::maml2::{infer, lower, verify};

/// The sampler's seven inputs at the recorded shape, invented values.
///
/// Same shapes the emitter recorded at (49 frames, 55 chars); values are the
/// `spread` fixture pattern (distinct, bounded, asymmetric about zero) so
/// sign/transpose errors cannot hide.
fn sampler_inputs() -> Vec<Vec<f32>> {
    let shapes = [
        (144usize, 1usize, 49usize),
        (256, 1, 55),
        (1024, 1, 50),
        (256, 1, 50),
        (2048, 1, 1),
        (64, 1, 49),
        (64, 1, 55),
    ];
    shapes
        .iter()
        .enumerate()
        .map(|(k, (c, h, w))| spread(c * h * w, k as f32 * 1.7))
        .collect()
}

#[test]
#[ignore = "needs a Vulkan device and the sampler assets"]
fn v2_sampler_on_device_agrees_with_the_reference() {
    let Some(dir) = assets() else {
        return;
    };
    let read = |name: &str| std::fs::read(dir.join(name));
    // The v1 asset must exist (it proves the pair was generated together),
    // but only its presence is checked: the v2 plan addresses the bridge's
    // payload layout, not v1 file offsets, so the v1 bytes are never read.
    if read("supertonic_ve.maml").is_err() {
        return;
    }
    // The v2 plan, lowered NCHW-first: same kernels the parity suite covers.
    let v2bytes = match read("supertonic_ve.maml2") {
        Ok(bytes) => bytes,
        Err(_) => return,
    };
    let verified = verify::verify(&v2bytes).expect("the v2 file verifies");
    let inferred = infer::infer(&verified).expect("shapes infer");
    let bridge = lower::V2Weights::new(&verified).expect("the bridge builds");
    let plan = lower::lower_nchw(&verified, &inferred[0], &bridge, 0).expect("the v2 plan lowers");
    assert_eq!(plan.inputs.len(), 7, "seven sampler inputs");

    let inputs = sampler_inputs();
    let refs: Vec<&[f32]> = inputs.iter().map(|v| v.as_slice()).collect();
    // The blob in the plan's own address space: the bridge payloads laid out
    // in tensor order at 16-aligned placements (v1's scheme), so an fp16
    // element offset and a quant word offset both land without rebasing.
    // NOT the v1 data section — v1 file offsets and bridge placements are
    // different address spaces, and running the v2 plan against the v1 blob
    // reads every weight from the wrong address (all-zeros output on both
    // host and device, which the oracle below refuses).
    let blob = bridge.blob();
    let host = run_multi(&plan, &blob, &refs).expect("the interpreter runs the v2 plan");
    // The zero oracle: `report` (unlike `matches`) prints rather than
    // asserts, so an all-zeros interpreter output would pass silently here.
    // Refuse it explicitly — invented inputs through 212 ops cannot be zero.
    assert!(
        host.iter().flat_map(|v| v.iter()).any(|&v| v != 0.0),
        "the interpreter's sampler output is all zeros; the inputs never reached it"
    );
    let got = on_device(plan, blob, &refs);
    matches("the v2 sampler (NCHW) at 49 frames, 55 chars", &host, &got);
}
