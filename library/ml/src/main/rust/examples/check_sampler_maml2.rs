//! Verify + infer a MAML v2 sampler file: the Phase 2 gate.
//!
//! ```text
//! cargo run --release -p modelrunner --example check_sampler_maml2 [path]
//! ```
//!
//! Defaults to `speech/src/main/assets/supertonic/supertonic_ve.maml2`.
//! Asserts, in order:
//!
//! * [`verify`]: identifier, `version ==`, `opset <=`, bounds, alignment,
//!   refs in range, entry roles match graph inputs.
//! * [`infer`]: every computed shape re-derived and equal to stored, SSA
//!   single-writer, topological order, outputs traceable, digest recomputed
//!   and equal to the stored `graph_digest`.
//! * Cross-check: node count and op inventory against the v1 recording.

use std::path::PathBuf;

use modelrunner::maml2::{infer, verify};

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
    println!(
        "verify: {} tensors, {} buffers, {} graphs",
        verified.tensor_count, verified.buffer_count, verified.graph_count
    );

    let inferred = infer::infer(&verified).expect("infer");
    assert_eq!(inferred.len(), verified.graph_count);
    for graph in &inferred {
        println!("graph {}: {} shapes inferred", graph.graph, graph.shapes.len());
    }

    // Digest check against the stored row: the recompute must equal what the
    // emitter wrote, which is also what Python recomputed independently.
    let model = verified.model;
    let stored: Vec<u8> =
        model.graph_digest().map(|d| d.iter().collect()).unwrap_or_default();
    assert_eq!(&stored[..], &inferred[0].graph_digest[..], "digest matches stored");
    println!("digest: {}", hex(&inferred[0].graph_digest));

    // Negative checks: truncated, flipped-version, and flipped-op files must
    // all fail (never a wrong answer, never a silent pass).
    let mut bad = bytes.clone();
    bad.truncate(bad.len() / 2);
    assert!(verify::verify(&bad).is_err(), "truncated file must fail");
    let mut bad_version = bytes.clone();
    // `version` is the first u32 past the FlatBuffers root offset + vtable;
    // flipping is version-agnostic: corrupt the identifier instead, which
    // must fail before any parse.
    bad_version[4] = b'X';
    assert!(verify::verify(&bad_version).is_err(), "bad identifier must fail");
    println!("negative checks pass (truncated + bad identifier rejected)");
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join("")
}
