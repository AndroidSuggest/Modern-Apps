//! Malformed-input robustness harness.
//!
//! Rounds 1 and 2 closed a long list of panic paths, unbounded allocations and
//! potential hangs, but every one of those fixes was verified by *reading* the
//! code. This module verifies them by *execution*: it builds valid documents,
//! systematically corrupts their bytes, and drives the real public entry points
//! over every mutant.
//!
//! The invariant under test, per §7.5.1 ("a conforming reader shall be able to
//! process a damaged file"), is:
//!
//!   For ANY byte string, every entry point must either produce a sane result or
//!   a clean error, within a finite time budget. It must never panic, never hang
//!   and never allocate without bound.
//!
//! A panic is not a cosmetic failure here: the JNI layer wraps each entry point
//! in `catch_unwind` (jni_bindings.rs), so a panic surfaces to the user as a
//! blank page or a dead document — the reported "crashexample" /
//! "crasheshalfway" / "doesntopenexample" symptom classes.
//!
//! Everything is deterministic. The corpus is constructed in code, the mutation
//! schedule is fixed, and the one place randomness is used (bit-flip position
//! selection) is a seeded LCG whose seed is printed on failure, so any finding
//! reproduces exactly.

use crate::*;
use lopdf::content::{Content, Operation};
use lopdf::{dictionary, Object, Stream};
use std::time::{Duration, Instant};

// ---------------------------------------------------------------------------
// Budget — self-calibrating against a measured in-process reference
// ---------------------------------------------------------------------------

// Budgets here are expressed as a MULTIPLE of a reference page rendered in this
// same process, per `bench`'s methodology note, rather than as a hardcoded
// millisecond figure. A hardcoded figure is wrong in one build mode or the other:
// `bench` measured this crate's debug/release ratio at 12.5x (7.874 ms vs
// 0.628 ms for an identical reference page), and machine-to-machine spread is on
// top of that. A relative budget self-calibrates across debug/release, CI vs
// laptop, and CPU speed.
//
// On the MULTIPLIER: `bench`'s figure is 50x per PAGE with a flat per-document
// backstop — never 50x per document, which they confirmed explicitly. The budgets
// here are per-DOCUMENT ("open + count + render <=4 pages + index"), so a tight
// multiple would be the wrong instrument: `bench` measured *legitimate,
// well-formed* pages from 0.011 ms to 622 ms in release against a 0.628 ms
// reference — a ~56000x span among entirely valid documents. The multipliers below
// are therefore deliberately enormous; they exist to catch "never returns", not
// "slower than typical". `bench`'s own closing advice was to err loose, because a
// flaky timeout trains people to ignore the suite. For calibration: the slowest of
// my 768 mutants ran at 1.2x reference, and the two real hangs overshot by >20x
// and >2000x, so the loose multipliers cost nothing in detection.
//
// Each budget also has an absolute floor, so that on a very fast machine the
// calibrated value cannot collapse to something razor-thin, and the effective
// values on this box stay what they were when the findings below were measured.
// The reference itself has a CEILING (see `REFERENCE_CEILING`) so that a
// contaminated calibration cannot silently widen every budget.

/// Reference multiple for a whole mutant ("open + count + render <=4 pages +
/// index"). ~1000x a reference page.
const MUTANT_BUDGET_X: u32 = 1_200;
const MUTANT_BUDGET_FLOOR: Duration = Duration::from_secs(10);

/// Same order of work as a mutant; a single adversarial construct.
const CONSTRUCT_BUDGET_X: u32 = 1_200;
const CONSTRUCT_BUDGET_FLOOR: Duration = Duration::from_secs(10);

/// For cases that are LEGITIMATELY heavy by construction — operator floods and
/// decompression bombs. These do real, bounded work proportional to a cap
/// (`MAX_PRIMITIVES` = 300k, `MAX_DECODED_BYTES` = 256 MB), and `bench` measured
/// a valid 400k-rect page at 622 ms in release, which is ~7.8 s in debug. A
/// tight budget here would be a false positive on correct behaviour.
const FLOOD_BUDGET_X: u32 = 25_000;
const FLOOD_BUDGET_FLOOR: Duration = Duration::from_secs(180);

/// Absolute ceiling on the calibrated reference.
///
/// This closes a real hole `bench` identified in the self-calibrating design: if
/// calibration happens to run during a load spike (concurrent agents now, a busy
/// CI box later) the reference inflates, EVERY budget scales up with it, and that
/// run silently loses its ability to detect a slow case. A ceiling means a
/// contaminated calibration degrades to the fixed floors instead of to infinity —
/// the suite can lose precision but never lose sensitivity without saying so.
///
/// 60 ms is ~5x the honest debug figure measured on this box (11.9 ms) and ~8x
/// `bench`'s (7.874 ms), so it cannot clip a legitimately slower machine, but it
/// does clip a spike.
const REFERENCE_CEILING: Duration = Duration::from_millis(60);

/// Time one render of a reference page, once per process.
///
/// Deliberately shaped like `bench`'s reference workload (60 text runs + 200
/// filled rects) so that the figure printed by this module is directly
/// comparable to the numbers in their measurements.
///
/// Takes the MINIMUM of the samples, not the median. Contention can only ever
/// make a render look slower, never faster, so the minimum is the least
/// contaminated estimate of what this machine actually costs — and unlike a
/// median it is robust to a *sustained* spike across the whole calibration
/// window, which is precisely the case `bench` raised.
fn reference_render() -> Duration {
    static REF: OnceLock<Duration> = OnceLock::new();
    *REF.get_or_init(|| {
        let mut content = Vec::new();
        for i in 0..200u32 {
            content.extend_from_slice(
                format!("{} {} 0.5 0.2 0.9 rg 4 6 re f ", i % 590, (i * 3) % 780).as_bytes(),
            );
        }
        content.extend_from_slice(b"BT /F1 11 Tf ");
        for i in 0..60u32 {
            content.extend_from_slice(
                format!("1 0 0 1 40 {} Tm (reference workload line) Tj ", 760 - i * 12).as_bytes(),
            );
        }
        content.extend_from_slice(b"ET");
        let pdf = raw_one_page(
            "/Resources << /Font << /F1 5 0 R >> >>",
            &content,
            vec![(
                5,
                b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>".to_vec(),
            )],
        );
        let doc = load_document_lenient(&pdf).expect("reference page must load");
        let page_id = *doc.get_pages().values().next().unwrap();
        // Warm the font cache the way a real session would, then sample.
        let _ = interpret_page(&doc, page_id);
        let mut samples: Vec<Duration> = (0..9)
            .map(|_| {
                let t0 = Instant::now();
                let page = interpret_page(&doc, page_id).expect("reference must interpret");
                let _ = crate::wire::serialize(&page);
                t0.elapsed()
            })
            .collect();
        samples.sort();
        let min = samples[0];
        let median = samples[samples.len() / 2];
        let max = samples[samples.len() - 1];
        let chosen = min.min(REFERENCE_CEILING);
        // Print all three so a contaminated calibration is visible in the log
        // rather than silently widening every budget.
        eprintln!(
            "[robustness] reference page (60 text runs + 200 rects), 9 samples: \
             min {min:?} / median {median:?} / max {max:?} -> using min, \
             clamped to {chosen:?} (ceiling {REFERENCE_CEILING:?})"
        );
        if max > min * 4 {
            eprintln!(
                "[robustness] WARNING: reference spread is {:.1}x across samples \
                 (min {min:?}, max {max:?}). The box is contended, so timing-based \
                 results from this run are less trustworthy than usual. Budgets are \
                 anchored on the minimum, so sensitivity is preserved.",
                max.as_secs_f64() / min.as_secs_f64().max(f64::MIN_POSITIVE)
            );
        }
        if min > REFERENCE_CEILING {
            eprintln!(
                "[robustness] WARNING: even the fastest reference sample ({min:?}) \
                 exceeds REFERENCE_CEILING ({REFERENCE_CEILING:?}). Budgets are \
                 clamped, so they are now effectively the absolute floors. Either \
                 this machine is much slower than the ones this was calibrated on, \
                 or the renderer regressed badly."
            );
        }
        chosen
    })
}

/// Effective budget: `multiple` x the measured reference, never below `floor`.
/// `FUZZ_BUDGET_SECS` overrides both, for diagnosing whether a failure is
/// "unbounded" or merely "slow".
fn scaled_budget(multiple: u32, floor: Duration) -> Duration {
    if let Some(s) = std::env::var("FUZZ_BUDGET_SECS").ok().and_then(|s| s.parse::<u64>().ok()) {
        return Duration::from_secs(s);
    }
    (reference_render() * multiple).max(floor)
}

fn mutant_budget() -> Duration {
    scaled_budget(MUTANT_BUDGET_X, MUTANT_BUDGET_FLOOR)
}

fn construct_budget() -> Duration {
    scaled_budget(CONSTRUCT_BUDGET_X, CONSTRUCT_BUDGET_FLOOR)
}

fn flood_budget() -> Duration {
    scaled_budget(FLOOD_BUDGET_X, FLOOD_BUDGET_FLOOR)
}

/// Worker-thread stack for guarded work.
///
/// Deliberately generous: an overflow at 8 MiB means genuinely unbounded
/// recursion rather than a tight-stack artifact, and a stack overflow aborts the
/// whole process (it is a guard-page fault, not an unwind) so it cannot be
/// reported per-test. `bisect_nesting_depth` with `FUZZ_NEST_STACK` is the
/// tighter, Android-realistic probe.
const WORKER_STACK: usize = 8 * 1024 * 1024;

/// Fixed seed for bit-flip position selection. Printed on every failure.
const FUZZ_SEED: u64 = 0x5EED_1234_ABCD_0001;

// ---------------------------------------------------------------------------
// Guarded execution: catches panics, enforces the time budget
// ---------------------------------------------------------------------------

#[derive(Debug)]
enum Verdict {
    Completed(Duration),
    /// Panic message, and how long it took to get there.
    Panicked(String, Duration),
    /// Exceeded the budget. The worker thread is deliberately leaked — there is
    /// no way to cancel Rust computation, and the test binary exits regardless.
    TimedOut,
}

impl Verdict {
    fn is_failure(&self) -> bool {
        !matches!(self, Verdict::Completed(_))
    }
}

fn panic_text(e: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = e.downcast_ref::<&str>() {
        (*s).to_string()
    } else if let Some(s) = e.downcast_ref::<String>() {
        s.clone()
    } else {
        "<non-string panic payload>".to_string()
    }
}

/// Run `f` on a worker thread, capturing a panic and enforcing `budget`.
///
/// The panic is captured rather than allowed to propagate so that one bad mutant
/// does not stop the sweep: the caller collects every finding and reports them
/// together, which is far more useful than the first one.
fn guarded(label: &str, budget: Duration, f: impl FnOnce() + Send + 'static) -> Verdict {
    guarded_on_stack(label, budget, WORKER_STACK, f)
}

fn guarded_on_stack(
    label: &str,
    budget: Duration,
    stack: usize,
    f: impl FnOnce() + Send + 'static,
) -> Verdict {
    let (tx, rx) = std::sync::mpsc::channel();
    let handle = std::thread::Builder::new()
        .name(format!("fuzz:{label}"))
        .stack_size(stack)
        .spawn(move || {
            let t0 = Instant::now();
            let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(f));
            let dt = t0.elapsed();
            let _ = tx.send(match r {
                Ok(()) => Verdict::Completed(dt),
                Err(e) => Verdict::Panicked(panic_text(e), dt),
            });
        })
        .expect("failed to spawn guard thread");

    match rx.recv_timeout(budget) {
        Ok(v) => {
            let _ = handle.join();
            v
        }
        Err(_) => Verdict::TimedOut,
    }
}

/// Guarded run of a single adversarial construct. Fails the test loudly, naming
/// the hazard, if the construct panics or blows the budget.
fn assert_construct_survives(hazard: &str, bytes: Vec<u8>) -> Duration {
    let label = hazard.to_string();
    let b = construct_budget();
    match guarded(hazard, b, move || exercise(&bytes)) {
        Verdict::Completed(dt) => dt,
        Verdict::Panicked(msg, dt) => panic!(
            "ROBUSTNESS FAILURE (panic) — hazard: {label}\n  \
             panicked after {dt:?} with: {msg}\n  \
             A malformed PDF must degrade gracefully, never panic; a panic here \
             blanks or kills the whole document at the JNI boundary."
        ),
        Verdict::TimedOut => panic!(
            "ROBUSTNESS FAILURE (hang) — hazard: {label}\n  \
             exceeded the {b:?} budget. This is the \
             \"slowdownexample\" class: unbounded work on hostile input."
        ),
    }
}

// ---------------------------------------------------------------------------
// Deterministic RNG (same shape as content.rs's arbitrary_bytes_never_panic)
// ---------------------------------------------------------------------------

struct Lcg(u64);

impl Lcg {
    fn new(seed: u64) -> Self {
        Lcg(seed)
    }
    fn next_u64(&mut self) -> u64 {
        // Numerical Recipes LCG constants; deterministic across platforms.
        self.0 = self.0.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        self.0
    }
    fn below(&mut self, n: usize) -> usize {
        if n == 0 {
            0
        } else {
            (self.next_u64() % n as u64) as usize
        }
    }
}

// ---------------------------------------------------------------------------
// The entry points under test
// ---------------------------------------------------------------------------

/// How far each mutant actually got. Without this the sweep could look thorough
/// while every mutant was rejected at the front door, so the report would be
/// dishonest about coverage.
#[derive(Default)]
struct Reach {
    offered: std::sync::atomic::AtomicUsize,
    loaded: std::sync::atomic::AtomicUsize,
    had_pages: std::sync::atomic::AtomicUsize,
    interpreted: std::sync::atomic::AtomicUsize,
    prims_emitted: std::sync::atomic::AtomicUsize,
}

impl Reach {
    fn bump(c: &std::sync::atomic::AtomicUsize) {
        c.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
    fn get(c: &std::sync::atomic::AtomicUsize) -> usize {
        c.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// Drive every byte-consuming entry point the app exposes, in the order the app
/// itself uses them. Any panic or hang inside this is a bug in the renderer.
///
/// Deliberately does NOT go through the global registry (`open_document` /
/// `render_page`): the registry is an 8-entry process-global LRU
/// (registry.rs:17), so bulk use of it would evict other tests' documents and
/// make results order-dependent. `registry_open_render_close_survives_mutants`
/// covers that path separately with a small, fixed number of documents.
fn exercise_counting(bytes: &[u8], reach: &Reach) {
    Reach::bump(&reach.offered);

    // 1. Password probe — reads the trailer and /Encrypt of untrusted bytes.
    let _ = pdf_password_state(bytes);

    // 2. Load, including the custom xref-rebuild recovery path that only runs
    //    for files lopdf rejects — i.e. exactly the fuzz-interesting branch.
    let doc = match load_document_lenient(bytes) {
        Some(d) => d,
        None => return, // a clean rejection is a correct outcome
    };
    Reach::bump(&reach.loaded);

    // 3. Page enumeration.
    let pages = doc.get_pages();
    if !pages.is_empty() {
        Reach::bump(&reach.had_pages);
    }

    // 4. Render each page: content tokenize -> interpret -> annotations ->
    //    wire serialize. Capped at 4 pages so a mutant that multiplies the page
    //    tree cannot dominate the sweep's runtime.
    for (_, page_id) in pages.iter().take(4) {
        if let Ok(page) = interpret_page(&doc, *page_id) {
            Reach::bump(&reach.interpreted);
            if !page.prims.is_empty() {
                Reach::bump(&reach.prims_emitted);
            }
            let buf = crate::wire::serialize(&page);
            // The wire buffer must always be structurally sound, even for a
            // page interpreted from garbage: Kotlin reads the header
            // unconditionally, so a short buffer is an out-of-bounds read there.
            assert!(
                buf.len() >= 20,
                "wire buffer shorter than its own 20-byte header ({} bytes)",
                buf.len()
            );
            let magic = u32::from_le_bytes(buf[0..4].try_into().unwrap());
            assert_eq!(magic, 0x5044_4657, "wire magic corrupted");
            let w = f32::from_le_bytes(buf[8..12].try_into().unwrap());
            let h = f32::from_le_bytes(buf[12..16].try_into().unwrap());
            assert!(
                w.is_finite() && h.is_finite(),
                "page size must be finite, got {w}x{h} — a NaN/Inf page size \
                 propagates into the Canvas transform on the Kotlin side"
            );
        }
    }

    // 5. The second, independent path through the interpreter (text_only).
    let _ = build_index(&doc);
}

fn exercise(bytes: &[u8]) {
    exercise_counting(bytes, &Reach::default());
}

// ---------------------------------------------------------------------------
// Valid seed corpus, built in code
// ---------------------------------------------------------------------------

fn flate(data: &[u8]) -> Vec<u8> {
    use flate2::write::ZlibEncoder;
    use std::io::Write;
    let mut e = ZlibEncoder::new(Vec::new(), flate2::Compression::default());
    e.write_all(data).unwrap();
    e.finish().unwrap()
}

include!("robustness_tests_part1.rs");
include!("robustness_tests_part2.rs");
include!("robustness_tests_part3.rs");
include!("robustness_tests_part4.rs");
include!("robustness_tests_part5.rs");
include!("robustness_tests_part6.rs");
include!("robustness_tests_part7.rs");