//! Emit a MAML v2 file for a single-graph net from its committed v1 asset.
//!
//! ```text
//! cargo run --release -p modelrunner --example emit_net_maml2 selfie
//! ```
//!
//! Like `emit_sampler_maml2` but net-generic: records through the net's own
//! `record` entry point, serialises with `emit_sampler`'s shared core, and
//! writes the `.maml2` beside the v1 asset. Nets with fixed shapes take no
//! further arguments; nets with dims take them positionally (see `usage`).
//!
//! Each net's section is the per-net gate: the v1 asset path, graph id,
//! record call, entry roles, and description. Adding a net is adding a
//! section — the verify/infer/lower/bit-exact gates below are shared.

use std::path::PathBuf;

use modelrunner::maml2::{self, emit};
use modelrunner::nets::Recorded;
use modelrunner::weights::{self, Tensor as WeightTensor};

fn repo_root() -> PathBuf {
    let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    while !dir.join("settings.gradle.kts").exists() {
        dir = dir.parent().expect("repo root").to_path_buf();
    }
    dir
}

struct NetSpec {
    /// CLI name.
    name: &'static str,
    /// v1 asset relative to the repo root.
    asset: &'static str,
    /// v1 graph id.
    graph: u32,
    /// Entry roles, binding positionally to the graph inputs.
    roles: Vec<&'static str>,
    /// Human description embedded in the file.
    description: &'static str,
}

fn usage() -> ! {
    eprintln!("usage: emit_net_maml2 <net> [dims...]");
    eprintln!("  single-graph: selfie u2netp scrfd mobilefacenet ppocr_det ppocr_rec maia");
    eprintln!("    vocoder duration text");
    eprintln!("  multi-graph: tinyclip whisper gemma_text");
    eprintln!("  scrfd, ppocr_det take HEIGHT WIDTH; ppocr_rec takes WIDTH;");
    eprintln!("  vocoder takes FRAMES; duration, text take CHARS; tinyclip takes LEN;");
    eprintln!("  gemma_text takes CONTEXT_TIER TOKENS");
    std::process::exit(1)
}

/// Read a v1 asset, parse it, and split its table — the common prefix of
/// every net section below.
fn load_v1(
    root: &PathBuf,
    asset: &str,
    graph: u32,
) -> (Vec<u8>, Vec<WeightTensor>, modelrunner::weights::Offsets, [u8; 32]) {
    let bytes = std::fs::read(root.join(asset)).expect("read the v1 asset");
    let weights = weights::Weights::parse(&bytes, graph).expect("parse v1");
    let table = weights.offsets();
    let tensors: Vec<WeightTensor> =
        (0..table.len()).map(|i| table.tensor(i).expect("tensor")).collect();
    let data = weights.data().to_vec();
    let sha = weights.source_sha256;
    (data, tensors, table, sha)
}

fn main() {
    let net = std::env::args().nth(1).unwrap_or_else(|| -> String { usage() });
    let root = repo_root();
    let (spec, recorded, table, data, source_sha256) = match net.as_str() {
        "selfie" => {
            let asset = "camera/src/main/assets/selfie_segmentation.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::SELFIE);
            let recorded = modelrunner::nets::selfie::record(&offsets).expect("record");
            (
                NetSpec {
                    name: "selfie",
                    asset,
                    graph: weights::graph::SELFIE,
                    roles: vec!["pixels"],
                    description: "selfie-segmentation fp16 nchw (v2 pilot)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "u2netp" => {
            let asset = "photos/src/main/assets/u2netp.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::U2NETP);
            let recorded = modelrunner::nets::u2netp::record(&offsets).expect("record");
            (
                NetSpec {
                    name: "u2netp",
                    asset,
                    graph: weights::graph::U2NETP,
                    roles: vec!["pixels"],
                    description: "u2netp fp16 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "scrfd" => {
            let (h, w) = dims2("scrfd", 640, 640);
            let asset = "photos/src/main/assets/scrfd_500m.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::SCRFD);
            let recorded =
                modelrunner::nets::scrfd::record(&offsets, h, w).expect("record");
            (
                NetSpec {
                    name: "scrfd",
                    asset,
                    graph: weights::graph::SCRFD,
                    roles: vec!["pixels"],
                    description: "scrfd-500m fp16 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "mobilefacenet" => {
            let asset = "photos/src/main/assets/w600k_mbf.maml";
            let (data, tensors, offsets, sha) =
                load_v1(&root, asset, weights::graph::MOBILEFACENET);
            let recorded = modelrunner::nets::mobilefacenet::record(&offsets).expect("record");
            (
                NetSpec {
                    name: "mobilefacenet",
                    asset,
                    graph: weights::graph::MOBILEFACENET,
                    roles: vec!["pixels"],
                    description: "mobilefacenet fp16 prelu nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "ppocr_det" => {
            let (h, w) = dims2("ppocr_det", 960, 960);
            let asset = "library/ocr/src/main/assets/ppocr_det.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::PPOCR_DET);
            let recorded =
                modelrunner::nets::ppocr_det::record(&offsets, h, w).expect("record");
            (
                NetSpec {
                    name: "ppocr_det",
                    asset,
                    graph: weights::graph::PPOCR_DET,
                    roles: vec!["pixels"],
                    description: "ppocr-det fp16 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "ppocr_rec" => {
            let width: u32 = std::env::args()
                .nth(2)
                .unwrap_or_else(|| "320".to_string())
                .parse()
                .unwrap_or_else(|_| usage());
            let asset = "library/ocr/src/main/assets/ppocr_rec.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::PPOCR_REC);
            let recorded =
                modelrunner::nets::ppocr_rec_extra::record(&offsets, width).expect("record");
            (
                NetSpec {
                    name: "ppocr_rec",
                    asset,
                    graph: weights::graph::PPOCR_REC,
                    roles: vec!["crop"],
                    description: "ppocr-rec fp16 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "maia" => {
            let asset = "games/chess/src/main/assets/maia3-5m.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::MAIA);
            let recorded = modelrunner::nets::maia::record(&offsets).expect("record");
            (
                NetSpec {
                    name: "maia",
                    asset,
                    graph: weights::graph::MAIA,
                    roles: vec!["tokens"],
                    description: "maia3 int8 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "vocoder" => {
            let frames: u32 = dim1(49);
            let asset = "speech/src/main/assets/supertonic/supertonic_voc.maml";
            let (data, tensors, offsets, sha) =
                load_v1(&root, asset, weights::graph::SUPERTONIC_VOC);
            let recorded =
                modelrunner::nets::supertonic_vocoder::record(&offsets, frames).expect("record");
            (
                NetSpec {
                    name: "vocoder",
                    asset,
                    graph: weights::graph::SUPERTONIC_VOC,
                    roles: vec!["latent"],
                    description: "supertonic-vocoder fp16/int8 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "duration" => {
            let chars: u32 = dim1(55);
            let asset = "speech/src/main/assets/supertonic/supertonic_dp.maml";
            let (data, tensors, offsets, sha) =
                load_v1(&root, asset, weights::graph::SUPERTONIC_DP);
            let recorded =
                modelrunner::nets::supertonic_duration::record(&offsets, chars).expect("record");
            (
                NetSpec {
                    name: "duration",
                    asset,
                    graph: weights::graph::SUPERTONIC_DP,
                    roles: vec!["char_ids", "style"],
                    description: "supertonic-duration fp16/int8 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "text" => {
            let chars: u32 = dim1(55);
            let asset = "speech/src/main/assets/supertonic/supertonic_ttl.maml";
            let (data, tensors, offsets, sha) =
                load_v1(&root, asset, weights::graph::SUPERTONIC_TTL);
            let recorded =
                modelrunner::nets::supertonic_text::record(&offsets, chars).expect("record");
            (
                NetSpec {
                    name: "text",
                    asset,
                    graph: weights::graph::SUPERTONIC_TTL,
                    roles: vec!["char_ids", "style"],
                    description: "supertonic-text-encoder fp16 nchw (v2)",
                },
                recorded,
                tensors,
                data,
                sha,
            )
        }
        "tinyclip" => {
            let len: u32 = dim1(16);
            let asset = "photos/src/main/assets/clip/tinyclip.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::TINYCLIP);
            let image = modelrunner::nets::tinyclip::record(
                &offsets,
                modelrunner::nets::tinyclip::Mode::Image,
            )
            .expect("record image");
            let text = modelrunner::nets::tinyclip::record(
                &offsets,
                modelrunner::nets::tinyclip::Mode::Text { len },
            )
            .expect("record text");
            let emitted = emit::emit_graphs(
                &tensors,
                &data,
                "tinyclip int8 nchw (v2)",
                "maml2 emit_net_maml2",
                sha,
                &[
                    emit::GraphSpec {
                        recorded: &image,
                        graph_name: "image",
                        entry_name: "image",
                        roles: &["pixels"],
                    },
                    emit::GraphSpec {
                        recorded: &text,
                        graph_name: "text",
                        entry_name: "text",
                        roles: &["embedded"],
                    },
                ],
            )
            .expect("emission succeeds");
            let out_path = root.join(asset.replace(".maml", ".maml2"));
            std::fs::write(&out_path, &emitted.bytes).expect("write the v2 file");
            println!("tinyclip v2: {} nodes over 2 graphs", emitted.op_inventory.iter().map(|(_, n)| n).sum::<usize>());
            println!("graph_digest: {}", hex(&emitted.graph_digest));
            println!("wrote {}", out_path.display());
            return;
        }
        "whisper" => {
            let asset = "speech/src/main/assets/whisper-base/whisper_base.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::WHISPER);
            let encode = modelrunner::nets::whisper::record(
                &offsets,
                modelrunner::nets::whisper::Mode::Encode,
            )
            .expect("record encode");
            let decode = modelrunner::nets::whisper::record(
                &offsets,
                modelrunner::nets::whisper::Mode::DecodeStep,
            )
            .expect("record decode");
            // 13 decode inputs: the token plus per-layer cross K/V pairs.
            let mut decode_roles: Vec<String> = vec!["token".to_string()];
            for layer in 0..modelrunner::nets::whisper::DECODER_LAYERS {
                decode_roles.push(format!("cross_k{layer}"));
                decode_roles.push(format!("cross_v{layer}"));
            }
            let decode_role_refs: Vec<&str> =
                decode_roles.iter().map(|s| s.as_str()).collect();
            let emitted = emit::emit_graphs(
                &tensors,
                &data,
                "whisper-base int8 nchw (v2)",
                "maml2 emit_net_maml2",
                sha,
                &[
                    emit::GraphSpec {
                        recorded: &encode,
                        graph_name: "encode",
                        entry_name: "encode",
                        roles: &["mel"],
                    },
                    emit::GraphSpec {
                        recorded: &decode,
                        graph_name: "decode_step",
                        entry_name: "decode_step",
                        roles: &decode_role_refs,
                    },
                ],
            )
            .expect("emission succeeds");
            let out_path = root.join(asset.replace(".maml", ".maml2"));
            std::fs::write(&out_path, &emitted.bytes).expect("write the v2 file");
            println!("whisper v2: {} nodes over 2 graphs", emitted.op_inventory.iter().map(|(_, n)| n).sum::<usize>());
            println!("graph_digest: {}", hex(&emitted.graph_digest));
            println!("wrote {}", out_path.display());
            return;
        }
        "gemma_text" => {
            use modelrunner::nets::gemma4::{Mode, Pass};
            let context: u32 = std::env::args()
                .nth(2)
                .map(|s| s.parse().unwrap_or_else(|_| usage()))
                .unwrap_or(1024);
            let tokens: u32 = std::env::args()
                .nth(3)
                .map(|s| s.parse().unwrap_or_else(|_| usage()))
                .unwrap_or(4);
            let asset = "analysis/models/gemma4_text.maml";
            let (data, tensors, offsets, sha) = load_v1(&root, asset, weights::graph::GEMMA4_TEXT);
            let decode = modelrunner::nets::gemma4::record(
                &offsets,
                Pass { mode: Mode::DecodeStep, context },
            )
            .expect("record decode");
            let prefill = modelrunner::nets::gemma4::record(
                &offsets,
                Pass { mode: Mode::Prefill { tokens }, context },
            )
            .expect("record prefill");
            let roles = ["token_embed", "per_layer", "angles_sliding", "angles_full"];
            let emitted = emit::emit_graphs(
                &tensors,
                &data,
                "gemma4-text int4 nchw (v2)",
                "maml2 emit_net_maml2",
                sha,
                &[
                    emit::GraphSpec {
                        recorded: &decode,
                        graph_name: "decode_step",
                        entry_name: "decode_step",
                        roles: &roles,
                    },
                    emit::GraphSpec {
                        recorded: &prefill,
                        graph_name: "prefill",
                        entry_name: "prefill",
                        roles: &roles,
                    },
                ],
            )
            .expect("emission succeeds");
            let out_path = root.join(asset.replace(".maml", ".maml2"));
            std::fs::write(&out_path, &emitted.bytes).expect("write the v2 file");
            println!("gemma_text v2: {} nodes over 2 graphs", emitted.op_inventory.iter().map(|(_, n)| n).sum::<usize>());
            println!("graph_digest: {}", hex(&emitted.graph_digest));
            println!("wrote {}", out_path.display());
            return;
        }
        _ => usage(),
    };
    let _ = spec.graph;
    emit_and_check(&root, &spec, &recorded, &table, &data, source_sha256);
}

/// One positional dim with a default, for the singly-shaped nets.
fn dim1(default: u32) -> u32 {
    std::env::args()
        .nth(2)
        .map(|s| s.parse().unwrap_or_else(|_| usage()))
        .unwrap_or(default)
}

/// Two positional dims (HEIGHT WIDTH) with defaults, for the shaped nets.
fn dims2(_net: &str, default_h: u32, default_w: u32) -> (u32, u32) {
    let h: u32 = std::env::args()
        .nth(2)
        .map(|s| s.parse().unwrap_or_else(|_| usage()))
        .unwrap_or(default_h);
    let w: u32 = std::env::args()
        .nth(3)
        .map(|s| s.parse().unwrap_or_else(|_| usage()))
        .unwrap_or(default_w);
    (h, w)
}

fn emit_and_check(
    root: &PathBuf,
    spec: &NetSpec,
    recorded: &Recorded,
    table: &[WeightTensor],
    data: &[u8],
    source_sha256: [u8; 32],
) {
    let emitted = emit::emit_graph(
        recorded,
        table,
        data,
        spec.description,
        "maml2 emit_net_maml2",
        source_sha256,
        spec.name,
        "encode",
        &spec.roles,
    )
    .expect("emission succeeds");

    assert!(maml2::fb::model_buffer_has_identifier(&emitted.bytes));
    let model = maml2::fb::root_as_model(&emitted.bytes).expect("the emitted file parses");
    assert_eq!(model.version(), maml2::FORMAT_VERSION);
    let nodes = model.graphs().and_then(|g| g.get(0).nodes()).map(|n| n.len()).unwrap_or(0);
    assert_eq!(nodes, recorded.nodes.len(), "one v2 node per recorded node");
    // Roles bind positionally: the entry must name every graph input.
    assert_eq!(spec.roles.len(), recorded.inputs.len(), "roles cover the inputs");

    let out_path = root.join(spec.asset.replace(".maml", ".maml2"));
    std::fs::write(&out_path, &emitted.bytes).expect("write the v2 file");
    println!("{} v2: {} tensors, {nodes} nodes", spec.name, emitted.op_inventory.iter().map(|(_, n)| n).sum::<usize>());
    println!("graph_digest: {}", hex(&emitted.graph_digest));
    println!("wrote {}", out_path.display());
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect::<Vec<_>>().join("")
}
