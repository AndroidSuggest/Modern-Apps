use crate::*;

/// Nesting depth allowed for expanding a Type 3 CharProc.
///
/// This shares the interpreter's single `depth` counter with form XObjects, so it
/// must match the `depth < 10` guard in the `Do` arm ([`MAX_GROUP_DEPTH`]) rather
/// than [`MAX_PATTERN_RECURSION`]. It was gated on the latter (4), which is the
/// SOFT-MASK expansion cap and is deliberately low for reasons `interpret.rs`
/// documents — an unmetered mask branches unbounded. Borrowing it here meant two
/// subsystems capped the same counter at different depths: an annotation appearance
/// enters at depth 1, so just three nested form XObjects inside one exhausted the
/// Type 3 budget and every glyph of the run vanished, unsearchable as well as
/// unpainted, while a plain form at the same depth carried on to 10.
const MAX_TYPE3_DEPTH: u32 = MAX_GROUP_DEPTH;

/// Everything on `Prim::Text` is derived from unvalidated file input (`Tf`, `Tz`,
/// `/Widths`, the CTM), and a non-finite value can survive even validated operands:
/// `f64 as f32` saturates to infinity above ~3.4e38. It cannot be left to the
/// consumer, because Kotlin's `coerceIn`/`coerceAtLeast` are comparisons and every
/// comparison against NaN is false — it passes straight through the clamp into
/// `Paint`, and the glyph silently disappears rather than being visibly wrong.
fn finite_or_zero(v: f32) -> f32 {
    if v.is_finite() {
        v
    } else {
        0.0
    }
}

pub(crate) fn cubic_bezier(
    p0: (f64, f64),
    p1: (f64, f64),
    p2: (f64, f64),
    p3: (f64, f64),
    t: f64,
) -> (f64, f64) {
    let u = 1.0 - t;
    let w0 = u * u * u;
    let w1 = 3.0 * u * u * t;
    let w2 = 3.0 * u * t * t;
    let w3 = t * t * t;
    (
        w0 * p0.0 + w1 * p1.0 + w2 * p2.0 + w3 * p3.0,
        w0 * p0.1 + w1 * p1.1 + w2 * p2.1 + w3 * p3.1,
    )
}

// old emit_fill removed - replaced by alpha-aware version


pub(crate) fn emit_stroke(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], gs: &GraphicsState) {
    // Device-space scale via CTM
    let ctm = &gs.ctm;
    let sx = (ctm[0] * ctm[0] + ctm[1] * ctm[1]).sqrt();
    let sy = (ctm[2] * ctm[2] + ctm[3] * ctm[3]).sqrt();
    let scale = (sx + sy) / 2.0;
    let width = (gs.line_width * scale) as f32;
    // Single-element dash is valid (odd -> duplicate) per PDF spec — fix #19
    let mut dash: Vec<f32> = gs.dash.iter().map(|d| (d * scale) as f32).filter(|d| *d >= 0.0).collect();
    if dash.len() == 1 && dash[0] > 0.0 {
        dash.push(dash[0]);
    } else if dash.len() % 2 == 1 && dash.len() > 1 {
        let cl = dash.clone();
        dash.extend(cl);
    }
    let dash = if dash.len() >= 2 && dash.iter().sum::<f32>() > 0.0 { dash } else { Vec::new() };
    let dash_phase = (gs.dash_phase * scale) as f32;
    let argb = apply_alpha_to_argb(gs.stroke, gs.alpha_stroke);
    // Overprint is a device-colorant control (PDF 8.6.7) and has no meaning on an
    // additive RGB compositor: there are no separations to leave unmarked. The
    // old Multiply approximation actively broke pages, since `white MULTIPLY dst
    // == dst` turned white knockout rectangles into no-ops and let content that
    // was meant to be covered show through.
    let blend = gs.blend_mode;
    // §8.5.3.2: "If a subpath is degenerate (consists of a single-point closed
    // subpath or of two or more points at the same coordinates), S shall paint it
    // only if round line caps have been specified, producing a filled circle
    // centred at the single point. If butt or projecting square line caps have
    // been specified, S shall paint nothing." A single-point subpath (`x y m S`,
    // or `x y m h S` once `h` drops the duplicate) never reached the renderer at
    // all, so the dot idiom used for stippled leader lines and map symbols
    // vanished. The circle is emitted directly rather than left to the renderer's
    // stroker, because what the spec asks for here is a fill, not a stroke.
    let dot_radius = (width as f64 / 2.0).max(0.05);
    for sp in subpaths {
        // One primitive PER SUBPATH, so the caller's single pre-call cap check
        // cannot bound this loop: a path carrying MAX_SUBPATHS subpaths overshot
        // MAX_PRIMITIVES by up to that many. `emit_fill` has no equivalent problem
        // because it emits one Fill holding every contour. Reported by `r5-state`,
        // whose doc on the constant states it is enforced at every emitting push.
        if prims.len() >= MAX_PRIMITIVES {
            break;
        }
        if sp.is_empty() { continue; }
        let (fx, fy) = sp[0];
        if sp.iter().all(|&(x, y)| (x - fx).abs() < 1e-9 && (y - fy).abs() < 1e-9) {
            if gs.line_cap == 1 {
                const DOT_SEGMENTS: usize = 16;
                let circle: Vec<(f32, f32)> = (0..DOT_SEGMENTS)
                    .map(|i| {
                        let a = i as f64 / DOT_SEGMENTS as f64 * std::f64::consts::TAU;
                        ((fx + dot_radius * a.cos()) as f32, (fy + dot_radius * a.sin()) as f32)
                    })
                    .collect();
                prims.push(Prim::Fill { argb, even_odd: false, contours: vec![circle], blend });
            }
            continue;
        }
        if sp.len() >= 2 {
            prims.push(Prim::Stroke {
                argb,
                width: width.max(0.1),
                dash: dash.clone(),
                dash_phase,
                cap: gs.line_cap,
                join: gs.line_join,
                miter: gs.miter_limit as f32,
                pts: sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect(),
                blend,
            });
        }
    }
}

pub(crate) fn emit_fill(prims: &mut Vec<Prim>, subpaths: &[Vec<(f64, f64)>], argb: u32, even_odd: bool, alpha_fill: f64, blend: BlendMode) {
    let argb = apply_alpha_to_argb(argb, alpha_fill);
    // All subpaths of the path form ONE fill region so interior contours (glyph
    // counters / holes) are cut out by the winding rule, instead of being filled
    // in as separate solid polygons.
    let contours: Vec<Vec<(f32, f32)>> = subpaths
        .iter()
        .filter(|sp| sp.len() >= 3)
        .map(|sp| sp.iter().map(|&(x, y)| (x as f32, y as f32)).collect())
        .collect();
    if !contours.is_empty() {
        prims.push(Prim::Fill { argb, even_odd, contours, blend });
    }
}

/// Show `bytes` with no ambient resource dictionary — see [`show_string_in`],
/// which is what the interpreter calls.
///
/// `#[cfg(test)]` because the only remaining callers are test modules (here,
/// `golden_tests.rs` and `tests.rs`). It is kept rather than folded into them so
/// those files, which belong to other owners, did not have to change when
/// §9.6.5's Type 3 resource fallback added the `ambient_resources` parameter.
#[cfg(test)]
pub(crate) fn show_string(
    doc: &Document,
    prims: &mut Vec<Prim>,
    gs: &GraphicsState,
    fonts: &HashMap<Vec<u8>, FontInfo>,
    text_matrix: &Mat,
    bytes: &[u8],
    depth: u32,
) -> f64 {
    show_string_in(doc, prims, gs, fonts, text_matrix, bytes, depth, None)
}

include!("draw_part1.rs");
include!("draw_part2.rs");
include!("draw_part3.rs");
include!("draw_part4.rs");