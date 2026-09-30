//! Runs Gemma 4's decode step on the GPU and compares its logits with onnxruntime's.
//!
//! ```text
//! cargo run --offline --release -p modelrunner --example check_gemma4_parity -- \
//!     gemma4_text.maml gemma4_embed.maml logits_golden.json
//! ```
//!
//! # This is the check nothing else substitutes for
//!
//! Every other test in this port checks *layout* - that 999 tensors are declared in the order the
//! converter writes them, that a scale has the rank its precision implies, that one op agrees
//! with a CPU oracle. All of that can pass while the model is wrong, because a transposed read
//! agrees with itself and a doubled scale is still a number.
//!
//! Thirty-five layers in sequence against real weights is the only thing that catches an error of
//! *composition*: a residual added before its norm instead of after, a rotary applied to the key
//! but not the query, a cache written at the wrong position. Those produce plausible logits.
//!
//! # What agreement to expect
//!
//! The reference is fp16 weights in fp32 arithmetic; this is int4 weights with a per-block scale.
//! The logits will not match to the bit and should not be expected to. What must match is the
//! **argmax** and the broad ordering of the top few - a model that is right differs from the
//! reference by quantisation noise, and a model that is wrong differs by rearranging the ranking.
use std::path::PathBuf;
use std::sync::Arc;

use modelrunner::nets::gemma4;
use modelrunner::vulkan::context;
use modelrunner::vulkan::reshape::Reshaped;
use modelrunner::vulkan::run::StepParams;
use modelrunner::weights::{Streamed, graph};

/// The cache these examples record against: the top tier, so a long prompt fits.
const TIER: u32 = gemma4::MAX_CONTEXT;

/// Open both `.maml` files as [`Streamed`] instead of `std::fs::read` + [`Weights::parse`].
///
/// The old path held 1.09 GB + 2.39 GB resident and then copied each data section again
/// inside `parse`, which is ~7 GB of peak host RSS against the ~2.4 GB a 7 GB Pixel 8 has
/// free — the OOM-reboot this harness caused twice. `Streamed::open` reads only the
/// ~64 KB header/table prefix; the data section stays in the file and is pulled through
/// `Net`'s 8 MB chunked staging upload plus row-at-a-time host gathers, so peak host RSS
/// is tens of MB. Identical bytes reach the device (`read_at` over the same section).
fn open_streamed(path: &PathBuf, expect_graph: u32) -> Result<Streamed, String> {
    let file = std::fs::File::open(path)
        .map_err(|e| format!("cannot open {}: {e}", path.display()))?;
    let len = file
        .metadata()
        .map_err(|e| format!("cannot size {}: {e}", path.display()))?
        .len();
    Streamed::open(file, 0, len, expect_graph)
}

fn main() {
    let mut args = std::env::args().skip(1);
    let (Some(text), Some(embed), Some(golden)) =
        (args.next().map(PathBuf::from), args.next().map(PathBuf::from), args.next())
    else {
        println!("usage: check_gemma4_parity <text.maml> <embed.maml> <golden.json> [head.maml]");
        return;
    };
    let weights = match open_streamed(&text, graph::GEMMA4_TEXT) {
        Ok(w) => w,
        Err(why) => return println!("the text model does not stream: {why}"),
    };
    let embed_weights = match open_streamed(&embed, graph::GEMMA4_EMBED) {
        Ok(w) => w,
        Err(why) => return println!("the embedding does not stream: {why}"),
    };
    // Optional standalone head file (4th arg): the 48 int8 triples alone
    // (~402 MB upload instead of the whole EMBED file). Absent on old flows
    // — the EMBED-resident chunks (or host head) cover those.
    let head_path: Option<PathBuf> = std::env::args().nth(6).map(PathBuf::from);
    let head_weights: Option<Streamed> = match &head_path {
        Some(path) => match open_streamed(path, graph::GEMMA4_HEAD) {
            Ok(w) => Some(w),
            Err(why) => return println!("the head file does not stream: {why}"),
        },
        None => None,
    };
    let golden = match std::fs::read_to_string(&golden) {
        Ok(text) => text,
        Err(why) => return println!("cannot read the golden: {why}"),
    };
    let (tokens, want_ids, want_logits) = parse_golden(&golden);
    if tokens.is_empty() {
        return println!("the golden holds no tokens");
    }
    println!("tokens {tokens:?}");

    let context = match context::shared() {
        Ok(context) => context,
        Err(why) => return println!("no Vulkan device: {why}"),
    };
    // `--time <repeats>`: decode-speed mode (no parity report). Runs the same
    // streaming decode loop repeatedly over the golden's tokens and prints
    // per-step ms: warmup vs steady-state separates upload-one-time from
    // per-token cost. Crash-safe by construction: identical streaming I/O to
    // the parity path (no whole-file reads), ~1.5 GB peak upload with the
    // head file (TEXT 1.1 + head 0.4) or the 2.4 GB legacy flow — never the
    // 4.7 GB TEXT+whole-EMBED upload that OOM-rebooted the P8 twice.
    if let Some(repeats) = std::env::args()
        .position(|a| a == "--time")
        .and_then(|i| std::env::args().nth(i + 1))
        .and_then(|n| n.parse::<usize>().ok())
    {
        return time_decode(&context, &weights, &embed_weights, head_weights.as_ref(), &tokens, repeats);
    }
    match run(&context, &weights, &embed_weights, head_weights.as_ref(), &tokens) {
        Ok(logits) => report(&logits, &want_ids, &want_logits),
        Err(why) => println!("the decode failed: {why}"),
    }

    // Bisect: where do the two first disagree? Only useful when the logits already failed, but
    // cheap enough to always print.
    let trace_path = std::env::args().nth(4);
    let Some(trace_path) = trace_path else { return };
    let Ok(trace) = std::fs::read_to_string(&trace_path) else {
        return println!("cannot read {trace_path}");
    };
    println!();
    println!("bisect against the reference's intermediates");
    for (label, layers) in [("ple_combined", 0usize), ("l0_out", 1), ("l1_out", 2)] {
        let Some(want) = field(&trace, label) else {
            println!("  {label:<14} not in the trace file");
            continue;
        };
        match trace_run(&context, &weights, &embed_weights, &tokens, layers) {
            Ok((hidden, per_layer)) => {
                let got: &[f32] = if label == "ple_combined" { &per_layer } else { &hidden };
                println!("  {label:<14} {}", compare(got, &want));
            }
            Err(why) => println!("  {label:<14} failed: {why}"),
        }
    }

    // L0 tail bisect: the four branch vectors against the numpy-fp oracle, in
    // tail order. The first label where the device drops below 0.99 while numpy
    // holds is the divergence point (see `Mode::TraceTail`).
    let tail_arg = std::env::args().nth(5);
    let Some(tail_path) = tail_arg else { return };
    let Ok(tail) = std::fs::read_to_string(&tail_path) else {
        return println!("cannot read {tail_path}");
    };
    println!();
    println!("L0 tail against the numpy oracle");
    match tail_run(&context, &weights, &embed_weights, &tokens) {
        Ok(branches) => {
            for (label, got) in
                ["attn_branch", "mlp_branch", "ple_branch", "l0_out"].into_iter().zip(branches.iter())
            {
                match field(&tail, label) {
                    Some(want) => println!("  {label:<14} {}", compare(got, &want)),
                    None => println!("  {label:<14} not in the tail file"),
                }
            }
        }
        Err(why) => println!("  the tail trace failed: {why}"),
    }
}

/// Cosine and relative RMS between a device tensor and the reference's.
fn compare(got: &[f32], want: &[f32]) -> String {
    if got.len() != want.len() {
        return format!("length {} against {}", got.len(), want.len());
    }
    let dot: f64 = got.iter().zip(want).map(|(a, b)| f64::from(*a) * f64::from(*b)).sum();
    let na: f64 = got.iter().map(|a| f64::from(*a) * f64::from(*a)).sum::<f64>().sqrt();
    let nb: f64 = want.iter().map(|b| f64::from(*b) * f64::from(*b)).sum::<f64>().sqrt();
    let cosine = dot / (na * nb).max(1e-30);
    let verdict = if cosine > 0.99 { "ok" } else { "WRONG" };
    format!("cosine {cosine:.6}  rms {:.4} against {:.4}  {verdict}", na / got.len() as f64, nb / want.len() as f64)
}

/// One named array from the trace file.
fn field(text: &str, key: &str) -> Option<Vec<f32>> {
    let at = text.find(&format!("\"{key}\""))?;
    let rest = &text[at..];
    let open = rest.find('[')?;
    let close = rest[open..].find(']')?;
    Some(rest[open + 1..open + close].split(',').filter_map(|p| p.trim().parse().ok()).collect())
}

/// [`run`] stopping after `layers` layers, returning `(hidden, per_layer)`.
fn trace_run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    tokens: &[u32],
    layers: usize,
) -> Result<(Vec<f32>, Vec<f32>), String> {
    let mode = gemma4::Mode::Trace { layers }.at(TIER);
    let mut net = Reshaped::streamed(
        Arc::clone(context),
        weights.offsets(),
        weights,
        mode,
        |offsets, mode| gemma4::build(offsets, mode),
    )?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut last = (Vec::new(), Vec::new());
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        let (hidden, per_layer) = gemma4::gather(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
        let at = net.at(mode)?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        let mut it = out.into_iter();
        last = (it.next().unwrap_or_default(), it.next().unwrap_or_default());
    }
    Ok(last)
}

/// Layer 0's four tail branches at the last step, in tail order
/// (`attn_branch`, `mlp_branch`, `ple_branch`, `l0_out`).
///
/// Same OOM-safe shape as [`trace_run`]: the KV cache fills one step at a time and only
/// the last step's four 1536-float branches are kept.
fn tail_run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    tokens: &[u32],
) -> Result<[Vec<f32>; 4], String> {
    let mode = gemma4::Mode::TraceTail.at(TIER);
    let mut net = Reshaped::streamed(
        Arc::clone(context),
        weights.offsets(),
        weights,
        mode,
        |offsets, mode| gemma4::build(offsets, mode),
    )?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut last = [Vec::new(), Vec::new(), Vec::new(), Vec::new()];
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        let (hidden, per_layer) = gemma4::gather(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;
        let at = net.at(mode)?;
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden, &per_layer, &angles_local, &angles_global])?;
        if out.len() != 4 {
            return Err(format!("a tail step returned {} tensors, not the four branches", out.len()));
        }
        let mut it = out.into_iter();
        last = [
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
            it.next().unwrap_or_default(),
        ];
    }
    Ok(last)
}

/// Decode-speed probe: repeat the streaming decode loop and print per-step ms.
///
/// Builds the `DecodeStep` net ONCE (upload-one-time), then runs the loop
/// `repeats` times over `tokens`, timing every step: the first pass includes
/// upload + graph setup, later passes are pure steady-state decode. Prints
/// per-step ms per pass plus the steady-state mean (everything after pass 0)
/// as ms/token and tokens/s. Never computes logits — no head upload, no
/// readback — so this isolates transformer decode from head cost.
fn time_decode(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    _head: Option<&Streamed>,
    tokens: &[u32],
    repeats: usize,
) {
    use std::time::Instant;
    let mut net = match Reshaped::streamed(
        Arc::clone(context),
        weights.offsets(),
        weights,
        gemma4::Mode::DecodeStep.at(TIER),
        |offsets, mode| gemma4::build(offsets, mode),
    ) {
        Ok(net) => net,
        Err(why) => return println!("the net does not build: {why}"),
    };
    let reader = embed.reader();
    let rotary = weights.reader();
    let repeats = repeats.max(1);
    // Per-op device times, read once from the last step's submit when the
    // timestamps knob is set. Hoisted: a property lookup per step would be
    // noise in what is being timed.
    let stamps = modelrunner::knobs::is_set("timestamps");
    let mut op_report: Option<Vec<(String, f64, f64)>> = None;
    let mut steady: Vec<f64> = Vec::new();
    // Host-side split: gather+rotary file reads vs device infer per step.
    // Accumulated over steady-state passes only, reported once.
    let mut host_ms = 0.0;
    let mut infer_ms = 0.0;
    let mut host_steps = 0usize;
    for pass in 0..repeats {
        let mut step_ms: Vec<f64> = Vec::with_capacity(tokens.len());
        for (step, &token) in tokens.iter().enumerate() {
            let tick = Instant::now();
            let position = match u32::try_from(step) {
                Ok(p) => p,
                Err(_) => return println!("a step past u32"),
            };
            let host_tick = Instant::now();
            let Ok((hidden_in, per_layer)) = gemma4::gather(&reader, token) else {
                return println!("gather failed at step {step}");
            };
            let Ok(angles_local) =
                rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)
            else {
                return println!("rotary local failed at step {step}");
            };
            let Ok(angles_global) =
                rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)
            else {
                return println!("rotary global failed at step {step}");
            };
            let host_done = host_tick.elapsed().as_secs_f64() * 1000.0;
            let at = match net.at(gemma4::Mode::DecodeStep.at(TIER)) {
                Ok(at) => at,
                Err(why) => return println!("the net does not bind at step {step}: {why}"),
            };
            if at
                .set_params(StepParams {
                    prefix: position,
                    window_start: position.saturating_sub(gemma4::WINDOW - 1),
                })
                .is_err()
            {
                return println!("params failed at step {step}");
            }
            let infer_tick = Instant::now();
            match at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global]) {
                Ok(_) => {}
                Err(why) => return println!("step {step} failed: {why}"),
            }
            if pass > 0 {
                host_ms += host_done;
                infer_ms += infer_tick.elapsed().as_secs_f64() * 1000.0;
                host_steps += 1;
            }
            // The query pool holds this submit's timestamps once the fence
            // waited above returns; the last step's is the steady-state plan.
            if stamps && pass + 1 == repeats && step + 1 == tokens.len() {
                match at.op_times() {
                    Ok(report) => op_report = Some(report),
                    Err(why) => println!("timestamps unreadable: {why}"),
                }
            }
            step_ms.push(tick.elapsed().as_secs_f64() * 1000.0);
        }
        let mean = step_ms.iter().sum::<f64>() / step_ms.len().max(1) as f64;
        println!("pass {pass}: {} steps, mean {mean:.1} ms/step", step_ms.len());
        for (i, ms) in step_ms.iter().enumerate() {
            println!("  step {i}: {ms:.1} ms");
        }
        if pass > 0 {
            steady.extend(step_ms);
        }
    }
    if !steady.is_empty() {
        let mean = steady.iter().sum::<f64>() / steady.len() as f64;
        println!("steady-state: {mean:.1} ms/token = {:.2} tok/s", 1000.0 / mean.max(1e-9));
    }
    if host_steps > 0 {
        println!(
            "host split per step: gather+rotary {:.1} ms, infer {:.1} ms ({} steps)",
            host_ms / host_steps as f64,
            infer_ms / host_steps as f64,
            host_steps
        );
    }
    // Per-op device times, hottest first, then aggregated by Kind. The table
    // that splits a step into kernel execution: it answers whether GEMV ALU,
    // occupancy-shaped small dispatches, or barriers own the 574 ms.
    if let Some(report) = op_report {
        println!();
        println!("{:>6} {:>10} {:>10}  {}", "step", "us", "gap-us", "kind");
        let mut per_kind: Vec<(String, usize, f64, f64)> = Vec::new();
        let mut total = 0.0;
        let mut gaps = 0.0;
        for (label, us, gap) in &report {
            println!("{label:>6} {us:>10.1} {gap:>10.1}");
            total += us;
            gaps += gap;
            let kind = label.split(':').nth(1).unwrap_or("?").trim().to_string();
            match per_kind.iter_mut().find(|(k, _, _, _)| *k == kind) {
                Some((_, n, t, g)) => {
                    *n += 1;
                    *t += us;
                    *g += gap;
                }
                None => per_kind.push((kind, 1, *us, *gap)),
            }
        }
        per_kind.sort_by(|a, b| b.2.partial_cmp(&a.2).unwrap_or(std::cmp::Ordering::Equal));
        println!();
        println!("{:>6} {:>10} {:>10} {:>10}  {}", "count", "total-ms", "mean-us", "gap-ms", "kind");
        for (kind, n, us, gap) in &per_kind {
            println!(
                "{n:>6} {:>10.1} {:>10.1} {:>10.1}  {kind}",
                us / 1000.0,
                us / *n as f64,
                gap / 1000.0
            );
        }
        println!();
        println!("timestamped device total: {:.1} ms against the step mean above", total / 1000.0);
        println!("barrier/bind gaps total: {:.1} ms", gaps / 1000.0);
        println!("(copies and host work are not timestamped)");
    }
}

/// Feed every token in order and return the last step's logits.
///
/// The device returns the normed hidden state; the logits are
/// `hidden @ H^T` over the raw-scale head table (see
/// `gemma4::embed::HEAD_TABLE`), softcapped, computed on the host exactly as
/// the bridge does it. So a parity pass here covers the gather, the whole
/// transformer, AND the tied-head path the app actually uses.
fn run(
    context: &Arc<context::Context>,
    weights: &Streamed,
    embed: &Streamed,
    head: Option<&Streamed>,
    tokens: &[u32],
) -> Result<Vec<f32>, String> {
    let mut net = Reshaped::streamed(
        Arc::clone(context),
        weights.offsets(),
        weights,
        gemma4::Mode::DecodeStep.at(TIER),
        |offsets, mode| gemma4::build(offsets, mode),
    )?;
    let reader = embed.reader();
    let rotary = weights.reader();
    let mut hidden = Vec::new();
    for (step, &token) in tokens.iter().enumerate() {
        let position = u32::try_from(step).map_err(|_| "a step past u32")?;
        if position >= gemma4::MAX_CONTEXT {
            return Err(format!("position {position} is past MAX_CONTEXT"));
        }
        let (hidden_in, per_layer) = gemma4::gather(&reader, token)?;
        let angles_local = rotary_row(&rotary, gemma4::ROTARY_LOCAL, gemma4::HEAD_DIM, position)?;
        let angles_global =
            rotary_row(&rotary, gemma4::ROTARY_GLOBAL, gemma4::GLOBAL_HEAD_DIM, position)?;

        let at = net.at(gemma4::Mode::DecodeStep.at(TIER))?;
        // The sliding layers attend `[position - WINDOW + 1, position]`; the full ones want the
        // whole prefix. One `window_start` serves both only while the prefix is inside the
        // window, which it is for a golden this short - a longer one needs the per-layer window
        // this runtime does not carry yet.
        at.set_params(StepParams {
            prefix: position,
            window_start: position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[&hidden_in, &per_layer, &angles_local, &angles_global])?;
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not the hidden state", out.len()));
        }
        hidden = out.into_iter().next().unwrap_or_default();
    }
    if hidden.len() != gemma4::D_MODEL as usize {
        return Err(format!("hidden state of {} values, not {}", hidden.len(), gemma4::D_MODEL));
    }
    Ok(gpu_or_host_logits(context, embed, head, &reader, &hidden)?)
}

/// Logits for `hidden`: the standalone head file first, then the GPU head
/// in EMBED, then the host tied head.
///
/// The standalone head file (4th CLI arg, ~402 MB upload instead of the
/// whole EMBED file) is preferred whenever present — same numerics as the
/// EMBED-resident int8 chunks, one-ninth the upload bytes. Falls back to the
/// EMBED-resident chunks (`hidden @ chunks`), then the host tied head
/// (`hidden @ HEAD_TABLE^T` in `HEAD_SPLITS` quarters) on files without
/// chunks, so this example keeps working against pre-head-split converts.
/// All paths must agree: the parity gate below compares against the golden
/// either way.
fn gpu_or_host_logits(
    context: &Arc<context::Context>,
    embed: &Streamed,
    head: Option<&Streamed>,
    reader: &modelrunner::weights::Reader<'_>,
    hidden: &[f32],
) -> Result<Vec<f32>, String> {
    use modelrunner::nets::gemma4_head;
    if let Some(head_file) = head {
        if head_file.offsets().len() == gemma4_head::HEAD_FILE_TENSORS {
            let mut net = Reshaped::streamed(
                Arc::clone(context),
                head_file.offsets(),
                head_file,
                (),
                |offsets, ()| gemma4_head::build_plan_standalone(offsets, false),
            )?;
            let at = net.at(())?;
            let out = at.infer_raw_many(&[hidden])?;
            if out.len() == gemma4_head::HEAD_CHUNKS
                && out.iter().all(|s| s.len() == gemma4_head::CLASSES_PER_CHUNK as usize)
            {
                let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
                for split in &out {
                    for &value in split {
                        logits.push(gemma4::LOGIT_CAP * (value / gemma4::LOGIT_CAP).tanh());
                    }
                }
                return Ok(logits);
            }
            // Wrong shape: fall through to the EMBED-resident chunks.
        }
    }
    if embed.offsets().len() >= gemma4_head::TENSORS_WITH_HEAD {
        let mut head = Reshaped::streamed(
            Arc::clone(context),
            embed.offsets(),
            embed,
            (),
            |offsets, ()| gemma4_head::build_plan(offsets),
        )?;
        let at = head.at(())?;
        let out = at.infer_raw_many(&[hidden])?;
        if out.len() == gemma4_head::HEAD_CHUNKS
            && out.iter().all(|s| s.len() == gemma4_head::CLASSES_PER_CHUNK as usize)
        {
            let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
            for split in &out {
                for &value in split {
                    logits.push(gemma4::LOGIT_CAP * (value / gemma4::LOGIT_CAP).tanh());
                }
            }
            return Ok(logits);
        }
        // Wrong shape: fall through to the host head rather than failing.
    }
    // Tied head on the host, in vocabulary splits (see `gemma4::HEAD_SPLITS`).
    // Reads the raw-scale head table (`gemma4::embed::HEAD_TABLE`), not the
    // working table - no gain division (see `gemma4::EMBED_GAIN`).
    let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
    for split in 0..gemma4::HEAD_SPLITS {
        let start = (split as u32) * gemma4::CLASSES_PER_SPLIT;
        let block = reader.fp16_rows(
            gemma4::embed::HEAD_TABLE,
            &[gemma4::VOCAB, gemma4::D_MODEL],
            start,
            gemma4::CLASSES_PER_SPLIT,
        )?;
        for row in block.chunks_exact(gemma4::D_MODEL as usize) {
            let dot: f32 = row.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum();
            logits.push(gemma4::LOGIT_CAP * (dot / gemma4::LOGIT_CAP).tanh());
        }
    }
    if logits.len() != gemma4::VOCAB as usize {
        return Err(format!("{} logits, not {}", logits.len(), gemma4::VOCAB));
    }
    Ok(logits)
}

/// One position's angles from a rotary table, which is plain fp16.
///
/// A single-row `fp16_rows` read (512 B local, 1 KB global) rather than the
/// whole `[MAX_CONTEXT, width]` table: the old `fp16` call held the full
/// 8/16 MB blob as ~16/33 MB of fp32 transient per step, twice a step.
fn rotary_row(
    reader: &modelrunner::weights::Reader<'_>,
    index: usize,
    width: u32,
    position: u32,
) -> Result<Vec<f32>, String> {
    if position >= gemma4::MAX_CONTEXT {
        return Err(format!("position {position} is past the rotary table"));
    }
    reader.fp16_rows(index, &[gemma4::MAX_CONTEXT, width], position, 1)
}

/// Print the comparison, and say plainly whether it passed.
fn report(got: &[f32], want_ids: &[u32], want_logits: &[f32]) {
    let mut order: Vec<u32> = (0..got.len() as u32).collect();
    order.sort_by(|&a, &b| got[b as usize].total_cmp(&got[a as usize]));
    let top: Vec<u32> = order.iter().copied().take(10).collect();

    println!();
    println!("  reference top10 {want_ids:?}");
    println!("  device    top10 {top:?}");
    println!();
    println!("  {:>8}  {:>12}  {:>12}", "id", "reference", "device");
    for (rank, &id) in want_ids.iter().enumerate().take(10) {
        println!(
            "  {id:>8}  {:>12.4}  {:>12.4}",
            want_logits.get(rank).copied().unwrap_or(f32::NAN),
            got.get(id as usize).copied().unwrap_or(f32::NAN),
        );
    }
    println!();
    let argmax_ok = top.first() == want_ids.first();
    let overlap = top.iter().filter(|id| want_ids.contains(id)).count();
    println!("  argmax {}", if argmax_ok { "MATCHES" } else { "DIFFERS" });
    println!("  {overlap} of the reference's top 10 are in the device's top 10");
    if argmax_ok && overlap >= 8 {
        println!();
        println!("PASS: the forward pass agrees with onnxruntime within quantisation noise");
    } else {
        println!();
        println!("FAIL: this is a composition error, not quantisation - the ranking moved");
    }
}

/// `{"tokens": [...], "top_ids": [...], "top_logits": [...]}`.
fn parse_golden(text: &str) -> (Vec<u32>, Vec<u32>, Vec<f32>) {
    let numbers = |key: &str| -> String {
        let Some(at) = text.find(key) else { return String::new() };
        let rest = &text[at + key.len()..];
        let Some(open) = rest.find('[') else { return String::new() };
        let Some(close) = rest[open..].find(']') else { return String::new() };
        rest[open + 1..open + close].to_string()
    };
    let ints = |s: String| -> Vec<u32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    let floats = |s: String| -> Vec<f32> {
        s.split(',').filter_map(|p| p.trim().parse().ok()).collect()
    };
    (
        ints(numbers("\"tokens\"")),
        ints(numbers("\"top_ids\"")),
        floats(numbers("\"top_logits\"")),
    )
}
