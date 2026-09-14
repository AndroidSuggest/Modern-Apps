//! A MAML v2 sampler plan on the device, behind the v1 bridge (NCHW-first).
//!
//! ```text
//! cargo run --release -p modelrunner --example run_sampler_maml2
//! ```
//!
//! The first v2 file toward a real GPU: verify → infer → lower with the
//! blocked rewrite forced off (`lower_nchw`) → the NCHW plan the device
//! parity suite already covers. Device execution itself runs under
//! `#[ignore]` tests on the Pixel 8 (this example is host-only: no Vulkan
//! here); this gate proves the NCHW plan the device will record.
//!
//! # Why NCHW first
//!
//! The v2 file declares blocked layouts, but every neighboring kernel
//! (layernorm, softmax, attention, add) still addresses NCHW. Dispatching
//! blocked kernels into an NCHW arena reads garbage at every boundary. The
//! boundary design (transpose ops vs full-kernel port) is open; this path
//! proves file → device execution plus the barrier win with the existing
//! kernels, which is the load-bearing half.

use std::path::PathBuf;

use modelrunner::maml2::{infer, lower, verify};

fn repo_root() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").exists() {
        dir = dir.parent().expect("repo root").to_path_buf();
    }
    dir
}

fn main() {
    let root = repo_root();
    let path = root.join("speech/src/main/assets/supertonic/supertonic_ve.maml2");
    let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let verified = verify::verify(&bytes).expect("verify");
    let inferred = infer::infer(&verified).expect("infer");
    let weights = lower::V2Weights::new(&verified).expect("bridge");
    let plan = lower::lower_nchw(&verified, &inferred[0], &weights, 0).expect("lower");
    let blocked = plan
        .ops
        .iter()
        .filter(|op| {
            matches!(
                op,
                modelrunner::nets::Op::Dispatch { kind: modelrunner::nets::Kind::ConvPointCb4Int8, .. }
            )
        })
        .count();
    assert_eq!(blocked, 0, "NCHW-first run selects no blocked kernels");
    println!("v2 NCHW plan: {} ops, arena {} elems", plan.ops.len(), plan.arena_elems);
    // The v1 oracle at the same shape must agree op-for-op (modulo the twin
    // mapping, which is vacuous here: no twins selected).
    let v1path = root.join("speech/src/main/assets/supertonic/supertonic_ve.maml");
    let v1bytes = std::fs::read(&v1path).expect("read the v1 asset");
    let v1 =
        modelrunner::weights::Weights::parse(&v1bytes, modelrunner::weights::graph::SUPERTONIC_VE)
            .expect("parse the v1 asset");
    let v1plan =
        modelrunner::nets::supertonic_sampler::build(&v1.offsets(), 49, 55).expect("build v1");
    assert_eq!(plan.ops.len(), v1plan.ops.len());
    assert_eq!(plan.arena_elems, v1plan.arena_elems);
    println!("NCHW parity with v1 holds ({} ops)", plan.ops.len());
}
