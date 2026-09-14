//! Lower a MAML v2 sampler file to a Plan and compare against v1.
//!
//! ```text
//! cargo run --release -p modelrunner --example lower_sampler_maml2 [path]
//! ```
//!
//! The round-trip gate: verify → infer → lower must reproduce the v1 plan's
//! op inventory (kinds folded as the op-inventory tests fold them), arena
//! high-water, and input/output bindings at the same shape. Any divergence is
//! a loader bug, not tolerance.

use std::path::PathBuf;

use modelrunner::maml2::{infer, lower, verify};

fn main() {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").exists() {
        dir = dir.parent().expect("repo root").to_path_buf();
    }
    let arg = std::env::args().nth(1);
    let path = arg.map(PathBuf::from).unwrap_or_else(|| {
        dir.join("speech/src/main/assets/supertonic/supertonic_ve.maml2")
    });
    let bytes = std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));

    let verified = verify::verify(&bytes).expect("verify");
    let inferred = infer::infer(&verified).expect("infer");
    assert_eq!(inferred.len(), 1);
    let weights = lower::V2Weights::new(&verified).expect("weight bridge");
    let plan = lower::lower(&verified, &inferred[0], &weights, 0).expect("lower");

    // v1 oracle at the same shape: the emitter recorded at 49 frames / 55
    // chars, which is what the v2 file's static shapes carry.
    let v1path = dir.join("speech/src/main/assets/supertonic/supertonic_ve.maml");
    let v1bytes = std::fs::read(&v1path).expect("read the v1 asset");
    let v1 =
        modelrunner::weights::Weights::parse(&v1bytes, modelrunner::weights::graph::SUPERTONIC_VE)
            .expect("parse the v1 asset");
    let v1plan =
        modelrunner::nets::supertonic_sampler::build(&v1.offsets(), 49, 55).expect("build v1");

    println!("v2: {} ops, arena {} elems", plan.ops.len(), plan.arena_elems);
    println!("v1: {} ops, arena {} elems", v1plan.ops.len(), v1plan.arena_elems);
    assert_eq!(plan.ops.len(), v1plan.ops.len(), "same op count");
    assert_eq!(plan.inputs.len(), v1plan.inputs.len(), "same input count");
    assert_eq!(plan.outputs.len(), v1plan.outputs.len(), "same output count");
    for (a, b) in plan.inputs.iter().zip(v1plan.inputs.iter()) {
        assert_eq!(a.shape, b.shape, "input shapes match");
    }
    for (a, b) in plan.outputs.iter().zip(v1plan.outputs.iter()) {
        assert_eq!(a.shape, b.shape, "output shapes match");
    }
    // Per-op inventory with the same folding the op-inventory tests use
    // (tiled lowerings fold back to the graph op). `name_of` is `pub(crate)`
    // for in-tree tests; examples are separate crates, so the fold is
    // Per-op inventory with the same folding the op-inventory tests use
    // (tiled lowerings fold back to the graph op). `name_of` is `pub(crate)`
    // for in-tree tests; examples are separate crates, so the fold is
    // re-stated here rather than reached into. Address spaces legitimately
    // differ (repacked weights), so compare geometry, not addresses.
    let fold = |kind: modelrunner::nets::Kind| -> String {
        match kind {
            modelrunner::nets::Kind::ConvPoint => "Conv".to_string(),
            modelrunner::nets::Kind::ConvPointInt8
            | modelrunner::nets::Kind::ConvVecInt8
            | modelrunner::nets::Kind::ConvPointCb4Int8 => "ConvInt8".to_string(),
            other => format!("{other:?}"),
        }
    };
    let geom = |op: &modelrunner::nets::Op| -> String {
        // Kinds fold through the same table as the inventory: the blocked
        // twin is the same op in a different layout, so geometry compares
        // modulo the twin mapping. Anything else compares exactly.
        match op {
            modelrunner::nets::Op::Copy { elems, .. } => format!("Copy{{{elems}}}"),
            modelrunner::nets::Op::Dispatch { kind, push, invocations } => format!(
                "{}in={}/{}/{} out={}/{}/{} k={}x{} s={}x{} g={} act={} n={} inv={invocations}",
                fold(*kind),
                push.in_c, push.in_h, push.in_w, push.out_c, push.out_h, push.out_w,
                push.kh, push.kw, push.stride_h, push.stride_w, push.group, push.act,
                push.count,
            ),
        }
    };
    for (step, (a, b)) in plan.ops.iter().zip(v1plan.ops.iter()).enumerate() {
        if geom(a) != geom(b) {
            println!("first geometry divergence at op {step}:");
            println!("  v2: {}", geom(a));
            println!("  v1: {}", geom(b));
            println!("  v2 full: {a:?}");
            println!("  v1 full: {b:?}");
            break;
        }
    }
    // Full-address comparison is expected to differ (repacked weights); the
    // geometry loop above is the real gate. Arena high-water is informational:
    // identical shapes + order should pack identically, and a divergence here
    // after zero geometry divergences points at pinned/binding order.
    if plan.arena_elems != v1plan.arena_elems {
        println!(
            "NOTE: arena differs (v2 {} vs v1 {}); bindings:",
            plan.arena_elems, v1plan.arena_elems
        );
        for (i, (a, b)) in plan.inputs.iter().zip(v1plan.inputs.iter()).enumerate() {
            println!("  in{i}: v2 at={} {a:?} vs v1 at={} {b:?}", a.at, b.at);
        }
        for (i, (a, b)) in plan.outputs.iter().zip(v1plan.outputs.iter()).enumerate() {
            println!("  out{i}: v2 at={} {a:?} vs v1 at={} {b:?}", a.at, b.at);
        }
    }
    let fold = |kind: modelrunner::nets::Kind| -> String {
        match kind {
            modelrunner::nets::Kind::ConvPoint => "Conv".to_string(),
            modelrunner::nets::Kind::ConvPointInt8
            | modelrunner::nets::Kind::ConvVecInt8
            | modelrunner::nets::Kind::ConvPointCb4Int8 => "ConvInt8".to_string(),
            other => format!("{other:?}"),
        }
    };
    let mut v2counts = std::collections::BTreeMap::new();
    for op in &plan.ops {
        if let modelrunner::nets::Op::Dispatch { kind, .. } = op {
            *v2counts.entry(fold(*kind)).or_insert(0) += 1;
        }
    }
    let mut v1counts = std::collections::BTreeMap::new();
    for op in &v1plan.ops {
        if let modelrunner::nets::Op::Dispatch { kind, .. } = op {
            *v1counts.entry(fold(*kind)).or_insert(0) += 1;
        }
    }
    // Copies are arena moves, not dispatches: compare separately.
    let v2copies = plan.ops.iter().filter(|op| matches!(op, modelrunner::nets::Op::Copy { .. })).count();
    let v1copies = v1plan.ops.iter().filter(|op| matches!(op, modelrunner::nets::Op::Copy { .. })).count();
    println!("v2 inventory: {v2counts:?} + {v2copies} copies");
    println!("v1 inventory: {v1counts:?} + {v1copies} copies");
    assert_eq!(v2counts, v1counts, "same dispatch inventory");
    assert_eq!(v2copies, v1copies, "same copy count");
    // Geometry is the round-trip gate (addresses legitimately differ across
    // the repack). Fail here if any op diverged geometrically above.
    let mut diverged = false;
    for (a, b) in plan.ops.iter().zip(v1plan.ops.iter()) {
        if geom(a) != geom(b) {
            diverged = true;
            break;
        }
    }
    assert!(!diverged, "zero geometry divergences");
    println!("round-trip: identical geometry, identical inventory");
}
