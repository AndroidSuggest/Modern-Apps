//! PDF Function evaluator (PDF 1.7 §7.10).
//!
//! Supports all four function types:
//! - Type 0: sampled functions (multilinear interpolation over an N-D sample grid)
//! - Type 2: exponential interpolation (`C0 + t^N·(C1-C0)`)
//! - Type 3: stitching (piecewise sub-functions selected by `/Bounds`)
//! - Type 4: PostScript calculator (a bounded stack-machine over the operator subset)
//!
//! Plus [`PdfFunction::Array`], an array of single-output functions that together
//! produce one output tuple (used where a shading `/Function` is an array).
//!
//! This is the shared color-transform foundation for `color.rs`
//! (Separation/DeviceN tint transforms) and `shading.rs`/`images.rs`
//! (axial/radial gradient color functions).

use crate::*;

/// Guards for the Type 4 PostScript calculator.
const MAX_PS_TOKENS: usize = 100_000;
const MAX_PS_STACK: usize = 1000;
const MAX_PS_STEPS: usize = 5_000_000;
const MAX_PS_DEPTH: u32 = 64;
/// Cap on total sampled-function bytes we will hold / interpolate.
const MAX_SAMPLED_BYTES: usize = 64 * 1024 * 1024;
/// Cap on a sampled function's input arity. `eval_sampled` interpolates over
/// `2^m` grid corners per evaluation and runs once per pixel for a shading, so an
/// unbounded `m` taken from `/Size` is a hang. Real-world m is 1 or 2 (7.10.2).
const MAX_SAMPLED_INPUTS: usize = 8;
/// Cap on a function's output arity, so a bogus `/Range` cannot make every
/// evaluation allocate an absurd vector.
const MAX_FN_OUTPUTS: usize = 32;
/// Nesting and total-node budgets for function PARSING.
///
/// Type 3 (`/Functions`) and the array-of-functions form both recurse through
/// `PdfFunction::parse`, and nothing in 7.10 stops `5 0 R` naming a Type 3 whose
/// `/Functions` array is `[5 0 R]`. That recursed forever: a Rust stack overflow is
/// not a panic, so `catch_unwind` cannot contain it and the whole process dies.
///
/// A depth cap alone is not sufficient, because the recursion BRANCHES — a Type 3
/// holding 100 sub-functions, each holding 100, is 100^depth nodes long before it
/// ever reaches the depth limit. The shared node budget is what actually bounds the
/// work; the depth cap bounds the stack. Real functions nest one or two levels
/// (7.10.4 NOTE: a Type 3's sub-functions "shall not" themselves be Type 3).
const MAX_FN_DEPTH: u32 = 8;
const MAX_FN_NODES: usize = 4096;

#[derive(Clone)]
pub(crate) enum PdfFunction {
    Sampled {
        domain: Vec<[f64; 2]>,
        range: Vec<[f64; 2]>,
        size: Vec<usize>,
        bps: u32,
        encode: Vec<[f64; 2]>,
        decode: Vec<[f64; 2]>,
        samples: Vec<u8>,
        n_in: usize,
        n_out: usize,
    },
    Exponential {
        domain: [f64; 2],
        /// 7.10.1 Table 38 makes `/Range` optional for types 2 and 3, but when it IS
        /// present outputs shall be clipped to it — a Type 2 whose `/Domain` extends
        /// past 1 evaluates `C0 + t^N*(C1-C0)` outside the `C0..C1` interval, so the
        /// entry is not redundant.
        range: Vec<[f64; 2]>,
        c0: Vec<f64>,
        c1: Vec<f64>,
        n: f64,
    },
    Stitching {
        domain: [f64; 2],
        range: Vec<[f64; 2]>,
        functions: Vec<PdfFunction>,
        bounds: Vec<f64>,
        encode: Vec<[f64; 2]>,
    },
    PostScript {
        domain: Vec<[f64; 2]>,
        range: Vec<[f64; 2]>,
        program: Vec<PsToken>,
    },
    /// An array of functions, each contributing (usually one) output component.
    Array(Vec<PdfFunction>),
}

#[derive(Clone)]
pub(crate) enum PsToken {
    Num(f64),
    Op(PsOp),
    Proc(Vec<PsToken>),
}

#[derive(Clone, Copy, PartialEq)]
pub(crate) enum PsOp {
    Abs, Add, Atan, Ceiling, Cos, Cvi, Cvr, Div, Exp, Floor, Idiv, Ln, Log,
    Mod, Mul, Neg, Round, Sin, Sqrt, Sub, Truncate,
    And, Bitshift, Eq, False, Ge, Gt, Le, Lt, Ne, Not, Or, True, Xor,
    If, Ifelse,
    Copy, Dup, Exch, Index, Pop, Roll,
}

fn read_pairs(obj: Option<&Object>) -> Vec<[f64; 2]> {
    let arr = match obj {
        Some(Object::Array(a)) => a,
        _ => return Vec::new(),
    };
    arr.chunks(2)
        .filter_map(|c| {
            if c.len() == 2 {
                Some([num(&c[0])?, num(&c[1])?])
            } else {
                None
            }
        })
        .collect()
}

fn read_floats(obj: Option<&Object>) -> Vec<f64> {
    match obj {
        Some(Object::Array(a)) => a.iter().filter_map(num).collect(),
        _ => Vec::new(),
    }
}

/// Clip `v` to the interval described by the pair `[a, b]` taken from a `/Domain`
/// or `/Range` entry (7.10.1: inputs are clipped to Domain, outputs to Range).
///
/// This exists instead of `f64::clamp` for two reasons, both reachable from a file:
///
/// * `f64::clamp` PANICS when its low bound exceeds its high bound, and nothing in
///   Table 38 stops a generator writing `[1 0]`. A reversed pair therefore aborted
///   the render of the whole page rather than clipping one component.
/// * `f64::clamp` PROPAGATES NaN. A NaN component survives every downstream
///   conversion and lands as an arbitrary (usually black) colour, so it has to be
///   removed at the boundary rather than clamped — the same reasoning the
///   `Exponential` arm already applies to `t^N`.
///
/// A non-finite bound is ignored rather than honoured, so `f64::max`/`min`'s
/// NaN-skipping behaviour is what makes the degenerate cases fall out.
fn clip(v: f64, a: f64, b: f64) -> f64 {
    if !v.is_finite() {
        let lo = a.min(b);
        return if lo.is_finite() { lo } else { 0.0 };
    }
    v.max(a.min(b)).min(a.max(b))
}

include!("functions_part1.rs");
include!("functions_part2.rs");
include!("functions_part3.rs");
include!("functions_part4.rs");