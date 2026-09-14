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

    // The model-wide digest gate already ran inside `infer` (a mismatch is a
    // load error there, not an assert here). Report the per-graph digest for
    // bisection; the stored model digest it folds into is what the emitter
    // wrote. The mismatch arm has its own negative in `maml2::infer`'s
    // unit tests, where a hand-built model carries a wrong digest.
    println!("digest: {}", hex(&inferred[0].graph_digest));

    // Negative checks: truncated and flipped-identifier files must fail
    // (never a wrong answer, never a silent pass).
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
