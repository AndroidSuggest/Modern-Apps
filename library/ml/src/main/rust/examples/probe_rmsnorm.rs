//! Single-norm microbenchmark: why is device RmsNorm 80x the sibling small ops?
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example probe_rmsnorm
//! ```
//!
//! Phase 3b′ probe (read-only): one RmsNorm at decode shapes, sweeping channels,
//! groups, and count, best-of-5 wall plus per-op device times when
//! `MODELRUNNER_TIMESTAMPS=1` is set. Synthetic `Builder` plan + `Net::infer_raw`
//! over a kilobytes-large dummy blob — no model file, no whole-file upload, zero
//! OOM risk by construction.
//!
//! # The question this answers
//!
//! The per-op timestamp table puts 241 RmsNorm dispatches at ~830 µs mean each
//! (~200 ms a step) against Mul at 8.5 µs, Rotary at 11.5 µs, SoftmaxPrefix at
//! 14 µs. A norm over ≤1536 elements moves ~6 KB; at 830 µs that is ~7 MB/s
//! effective — so it is not fetch-bound. Candidate mechanisms and their
//! predictions:
//!
//! * single-workgroup launch/drain cost → flat vs channels, per-op cost drops
//!   (or holds) when tiled, rises linearly with count;
//! * serial-reduction ALU → linear in channels;
//! * driver pathologies → cliffs at specific shapes.
//!
//! The sweep below is shaped to discriminate exactly these three. No fix is
//! designed until the numbers speak — the one-paragraph mechanism statement
//! this prints is the gate for all Phase 3b′ fix work.
//!
//! # Geometries (every RmsNorm the transformer actually runs)
//!
//! * plain 1536 (input / pre_ff / final norms, groups = 1);
//! * plain 256 (K norm: KV_HEADS = 1, so grouped-over-1; v norms);
//! * grouped 2048 = 8 heads × 256 (local Q norm, groups = 8);
//! * grouped 4096 = 8 heads × 512 (global Q norm, groups = 8);
//! * grouped 256 with groups = 8 (cross-cut: same channels as the K norm,
//!   grouped like Q — isolates the grouping effect from the width effect).

use modelrunner::nets::{Builder, Shape, WeightSource};
use modelrunner::vulkan::{context, run::Net};
use modelrunner::weights::{Blob, Tensor};

/// Channels × groups × chained-count to sweep: decode widths, the natural
/// grouping for each width, the 256-wide cross-cut, and 1 vs 35-chained (one
/// layer's worth of sequential norms in a single plan).
const SWEEP: [(u32, u32, usize); 10] = [
    (256, 1, 1),
    (512, 1, 1),
    (1536, 1, 1),
    (2048, 8, 1),
    (4096, 8, 1),
    // Grouping cross-cut at fixed width.
    (256, 8, 1),
    (2048, 1, 1),
    // Tiled: one layer's sequential norms in one plan.
    (1536, 1, 35),
    (2048, 8, 35),
    (256, 1, 35),
];

/// A weight source for the norm probe: every tensor at offset zero.
///
/// Mirrors the `ProbeSource` in `bridge_part7.rs` (which examples cannot use:
/// it is private to the Android-gated bridge). They alias, which would be
/// nonsense for inference and is right here — the probe asks how fast a norm
/// runs, not what it computes.
struct NormSource;

impl WeightSource for NormSource {
    fn shaped(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn shaped_words(&self, _index: usize, _dims: &[u32]) -> Result<u32, String> {
        Ok(0)
    }
    fn count(&self) -> usize {
        1
    }
}

/// A kilobytes-large blob of identical bytes with a one-tensor table beside it.
///
/// The gamma the norms read is whatever these bytes decode to as fp16 — the
/// values do not matter for timing (no NaN: the input feed is nonzero and the
/// norm divides by the input's own magnitude, never by gamma).
struct NormFile {
    bytes: Vec<u8>,
    table: Vec<Tensor>,
}

impl Blob for NormFile {
    fn data_len(&self) -> u64 {
        self.bytes.len() as u64
    }
    fn tensors(&self) -> &[Tensor] {
        &self.table
    }
    fn read_at(&self, offset: u64, into: &mut [u8]) -> Result<(), String> {
        let from = offset as usize;
        let span = self.bytes.get(from..from + into.len()).ok_or_else(|| {
            format!("a read of {} at {offset} past {}", into.len(), self.bytes.len())
        })?;
        into.copy_from_slice(span);
        Ok(())
    }
}

fn main() {
    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    let stamps = modelrunner::knobs::is_set("timestamps");
    println!("channels groups count  best-us  per-op-us (best of 5)");
    for (channels, groups, count) in SWEEP {
        match probe(&context, channels, groups, count, stamps) {
            Ok((best, per_op)) => match per_op {
                Some(op) => println!("{channels:>8} {groups:>6} {count:>5}  {best:>7.1}  {op:>9.1}"),
                None => println!("{channels:>8} {groups:>6} {count:>5}  {best:>7.1}  (set MODELRUNNER_TIMESTAMPS=1 for per-op)"),
            },
            Err(why) => println!("{channels:>8} {groups:>6} {count:>5}  failed: {why}"),
        }
    }
}

/// Time `count` chained RmsNorms over `channels` inputs in `groups` groups.
///
/// Returns best-of-5 wall µs plus, when `stamps` is set, the timestamped
/// per-op mean for the last iteration. Chained (each op reads the previous
/// output) so the dispatches serialize exactly as decode's interleaved norms do.
fn probe(
    context: &std::sync::Arc<context::Context>,
    channels: u32,
    groups: u32,
    count: usize,
    stamps: bool,
) -> Result<(f64, Option<f64>), String> {
    let source = NormSource;
    let mut builder = Builder::new(&source);
    let input = builder.input(Shape::new(channels, 1, 1));
    let mut out = input;
    for _ in 0..count {
        out = builder.rms_norm_grouped(out, 0, modelrunner::nets::gemma4::EPSILON, groups);
    }
    let plan = builder.finish(&[out])?;
    if plan.ops.len() != count {
        return Err(format!("{} chained norms built {} ops", count, plan.ops.len()));
    }
    // Gamma needs per_group fp16 entries; 8 KB covers the widest point.
    let bytes = vec![0x11u8; 8192];
    let table = vec![Tensor {
        rank: 1,
        dims: [4096, 0, 0, 0],
        offset: 0,
        len: 4096,
        dtype: modelrunner::weights::Dtype::F16,
    }];
    let file = NormFile { bytes, table };
    let mut net = Net::new(std::sync::Arc::clone(context), plan, &file, modelrunner::preprocess::RESCALE_ONLY)?;
    let feed = vec![0.5f32; channels as usize];
    net.infer_raw(&feed)?;
    let mut best = f64::MAX;
    for _ in 0..5 {
        let started = std::time::Instant::now();
        net.infer_raw(&feed)?;
        best = best.min(started.elapsed().as_secs_f64());
    }
    let per_op = if stamps {
        let report = net.op_times()?;
        let total: f64 = report.iter().map(|(_, us, _)| us).sum();
        Some(total / report.len().max(1) as f64)
    } else {
        None
    };
    Ok((best * 1e6, per_op))
}
