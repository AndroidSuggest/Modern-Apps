//! Emit a MAML v2 sampler file from the committed v1 asset.
//!
//! ```text
//! cargo run --release -p modelrunner --example emit_sampler_maml2
//! ```
//!
//! # Why an example and not a test
//!
//! The emitter needs the real `supertonic_ve.maml` weights (tens of MB) to
//! repack, and `cargo test` must stay runnable with no assets. This follows
//! the `report_barriers` precedent: read the committed `.maml` files, build
//! plans on the host with no Vulkan, write the v2 file beside them.
//!
//! # What it proves (Phase 1 gate)
//!
//! * The sampler records [`Recorded`] through its own `record` entry point.
//! * [`emit_sampler`] serialises that recording to `MAM2` FlatBuffers.
//! * The output re-parses with the file identifier, the node count matches
//!   the recording, and the digest recomputes.
//! * Output: `speech/src/main/assets/supertonic/supertonic_ve.maml2`.

use std::path::{Path, PathBuf};

use modelrunner::maml2::{self, emit};
use modelrunner::nets::supertonic_sampler;
use modelrunner::weights::{graph, Weights};

/// Frames and characters to record at. Must be a shape the net actually runs:
/// the analysis utterance is 49 frames / 55 chars.
const FRAMES: u32 = 49;
const CHARS: u32 = 55;

fn repo_root() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").exists() {
        dir = dir.parent().expect("repo root").to_path_buf();
    }
    dir
}

fn main() {
    let root = repo_root();
    let path = root.join("speech/src/main/assets/supertonic/supertonic_ve.maml");
    let bytes =
        std::fs::read(&path).unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let weights = Weights::parse(&bytes, graph::SUPERTONIC_VE).expect("the v1 sampler asset parses");
    let table = weights.offsets();
    let tensors: Vec<modelrunner::weights::Tensor> =
        (0..table.len()).map(|i| table.tensor(i).expect("tensor")).collect();

    let recorded =
        supertonic_sampler::record(&table, FRAMES, CHARS).expect("the sampler records");

    let emitted = emit::emit_sampler(
        &recorded,
        &tensors,
        weights.data(),
        "supertonic-sampler ve fp16/int8 nchw (v2 pilot)",
        "maml2 emit_sampler_maml2",
        weights.source_sha256,
    )
    .expect("emission succeeds");

    // Verify: identifier, root parse, counts, digest.
    assert!(maml2::fb::model_buffer_has_identifier(&emitted.bytes));
    let model = maml2::fb::root_as_model(&emitted.bytes).expect("the emitted file parses");
    assert_eq!(model.version(), maml2::FORMAT_VERSION);
    assert_eq!(model.opset_version(), maml2::OPSET_VERSION);
    let tensors_out = model.tensors().map(|t| t.len()).unwrap_or(0);
    let graphs = model.graphs().map(|g| g.len()).unwrap_or(0);
    assert_eq!(graphs, 1, "one sampler graph");
    let nodes = model.graphs().and_then(|g| g.get(0).nodes()).map(|n| n.len()).unwrap_or(0);
    assert_eq!(nodes, recorded.nodes.len(), "one v2 node per recorded node");
    let digest =
        model.graph_digest().map(|d| d.iter().collect::<Vec<u8>>()).unwrap_or_default();
    assert_eq!(&digest[..], &emitted.graph_digest[..], "digest round-trips");

    let out_path = root.join("speech/src/main/assets/supertonic/supertonic_ve.maml2");
    write_out(&out_path, &emitted.bytes);

    println!("sampler v2: {tensors_out} tensors, {nodes} nodes");
    println!("op inventory:");
    let mut inventory = emitted.op_inventory;
    inventory.sort_by_key(|(_, n)| std::cmp::Reverse(*n));
    for (op, count) in &inventory {
        println!("  {op:?}: {count}");
    }
    println!("graph_digest: {}", hex(&emitted.graph_digest));
    println!("wrote {}", out_path.display());
}

fn write_out(path: &Path, bytes: &[u8]) {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).expect("asset dir");
    }
    std::fs::write(path, bytes).expect("write the v2 file");
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join("")
}
