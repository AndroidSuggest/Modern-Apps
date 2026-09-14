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
use crate::maml2::load::Model;

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

/// The selfie net through the production v2 load path (`Model::load`), on
/// device against the interpreter.
///
/// `#[ignore]`: needs a Vulkan device and the selfie v2 asset. This is the
/// first bridge flip proven on hardware: the exact `Model::parse` +
/// `load(0)` + `Net` sequence the JNI constructor runs, not the manual
/// verify/infer/lower chain above.
#[test]
#[ignore = "needs a Vulkan device and the selfie v2 asset"]
fn v2_selfie_load_path_on_device() {
    // On-device the asset comes from MODELRUNNER_ASSETS; the checkout path
    // is the host fallback (device builds have no checkout to walk to, so a
    // missing dir skips rather than panicking on path arithmetic).
    let bytes = match std::env::var("MODELRUNNER_ASSETS") {
        Ok(dir) => match std::fs::read(std::path::PathBuf::from(dir).join("selfie_segmentation.maml2")) {
            Ok(bytes) => bytes,
            Err(_) => return,
        },
        Err(_) => {
            let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
                .ancestors()
                .nth(5)
                .map(|p| p.to_path_buf());
            let Some(root) = root else {
                return;
            };
            match std::fs::read(root.join("camera/src/main/assets/selfie_segmentation.maml2")) {
                Ok(bytes) => bytes,
                Err(_) => return,
            }
        }
    };
    let model = Model::parse(bytes).expect("the v2 file parses");
    let (plan, blob) = model.load(0).expect("entry 0 loads");
    assert_eq!(plan.inputs.len(), 1, "one selfie input");
    let input: Vec<f32> =
        (0..3 * 256 * 256).map(|i| (i as f32 * 0.7).sin() * 0.8 + 0.1).collect();
    let refs = [input.as_slice()];
    let host_weights = read_blob(&blob);
    let host = run_multi(&plan, &host_weights, &refs).expect("the interpreter runs");
    assert!(
        host.iter().flat_map(|v| v.iter()).any(|&v| v != 0.0),
        "the interpreter's selfie output is all zeros"
    );
    let got = on_device(plan, host_weights, &refs);
    matches_deep("the v2 selfie at 256x256", &host, &got);
}

/// Read a [`Blob`] fully into a vector, for the interpreter.
fn read_blob(blob: &crate::maml2::load::WeightsBlob) -> Vec<u8> {
    use crate::weights::Blob;
    let mut out = vec![0u8; blob.data_len() as usize];
    blob.read_at(0, &mut out).expect("the blob reads");
    out
}
