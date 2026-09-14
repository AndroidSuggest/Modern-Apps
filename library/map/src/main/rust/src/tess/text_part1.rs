/// One glyph positioned along a curved baseline: the source glyph, where its pen origin sits
/// in tile-local 0..1, and the unit tangent of the baseline there.
///
/// The single source of truth shared by [`emit_curved`] (which builds the quads) and the
/// placer (which builds the oriented collision boxes) so the box a label occupies is the box
/// the GPU draws — the curved analogue of what [`crate::tile::placement::anchored_rect`] does
/// for a point label.
#[derive(Clone, Copy, Debug)]
pub struct CurvedGlyph {
    /// The source glyph: char, atlas metrics and advance, all in font units.
    pub glyph: ShapedGlyph,
    /// Pen origin on the baseline, tile-local 0..1.
    pub pen: (f32, f32),
    /// Unit tangent `(cos, sin)` of the baseline at the pen, tile-local. The glyph is rotated
    /// to this, so the run curves with the road/river.
    pub tangent: (f32, f32),
}

/// The arc length of a tile-local polyline.
fn polyline_length(pts: &[(f32, f32)]) -> f32 {
    let mut sum = 0.0;
    for w in pts.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        sum += (dx * dx + dy * dy).sqrt();
    }
    sum
}

/// The sharpest turn a run may be laid across, at a single vertex.
///
/// MapLibre's `symbol-max-angle` default. A corner sharper than this cannot carry legible
/// glyphs, and — more importantly here — it is what a *discontinuity* looks like: a line
/// feature's parts are joined end to end before they reach this module, so a multi-part road
/// arrives as one polyline with a phantom connector segment bridging the gap. That connector
/// inflates the arc length (so an over-long name passes the fit test) and points nowhere near
/// the road (so the glyphs that land on it fly off at their own angle). Both of the reported
/// symptoms — a name that stops mid-word, and letters kinked away from the line — are that.
const MAX_TURN_RAD: f32 = std::f32::consts::FRAC_PI_4;

/// The longest stretch of `pts` containing no turn sharper than [`MAX_TURN_RAD`].
///
/// A label is laid along this rather than the whole polyline, which is the cheap version of
/// "put the run where it fits": no placement search, just the single best-behaved stretch.
fn longest_smooth_run(pts: &[(f32, f32)]) -> &[(f32, f32)] {
    if pts.len() < 3 {
        return pts;
    }
    let cos_max = MAX_TURN_RAD.cos();
    let direction = |a: (f32, f32), b: (f32, f32)| {
        let (dx, dy) = (b.0 - a.0, b.1 - a.1);
        let len = (dx * dx + dy * dy).sqrt();
        (len > 0.0).then_some((dx / len, dy / len))
    };
    let (mut best_start, mut best_end) = (0usize, pts.len());
    let mut best_len = -1.0f32;
    let mut start = 0usize;
    let mut previous: Option<(f32, f32)> = None;
    for i in 0..pts.len() - 1 {
        // A repeated vertex has no direction of its own; it bridges its neighbours rather than
        // ending the run, exactly as `sample_polyline` skips it.
        let Some(d) = direction(pts[i], pts[i + 1]) else { continue };
        if let Some(p) = previous {
            if p.0 * d.0 + p.1 * d.1 < cos_max {
                let len = polyline_length(&pts[start..=i]);
                if len > best_len {
                    best_len = len;
                    (best_start, best_end) = (start, i + 1);
                }
                // The corner vertex belongs to both runs: it is the end of one and the start
                // of the next, so no arc length is lost between them.
                start = i;
            }
        }
        previous = Some(d);
    }
    if polyline_length(&pts[start..]) > best_len {
        (best_start, best_end) = (start, pts.len());
    }
    &pts[best_start..best_end]
}

/// The point and unit tangent at arc length `d` along `pts`, clamped to the ends. Degenerate
/// (zero-length) segments are skipped so a repeated vertex cannot produce a NaN tangent.
fn sample_polyline(pts: &[(f32, f32)], d: f32) -> ((f32, f32), (f32, f32)) {
    let d = d.max(0.0);
    let mut acc = 0.0f32;
    for w in pts.windows(2) {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        let seg = (dx * dx + dy * dy).sqrt();
        if seg <= 0.0 {
            continue;
        }
        if acc + seg >= d {
            let t = ((d - acc) / seg).clamp(0.0, 1.0);
            return ((w[0].0 + dx * t, w[0].1 + dy * t), (dx / seg, dy / seg));
        }
        acc += seg;
    }
    // Past the end (or an all-degenerate line): the last vertex, with the last real tangent.
    let last = *pts.last().unwrap_or(&(0.0, 0.0));
    for w in pts.windows(2).rev() {
        let dx = w[1].0 - w[0].0;
        let dy = w[1].1 - w[0].1;
        let seg = (dx * dx + dy * dy).sqrt();
        if seg > 0.0 {
            return (last, (dx / seg, dy / seg));
        }
    }
    (last, (1.0, 0.0))
}

/// Lay a single shaped line's glyphs along a tile-local `centreline`, centred on the line's
/// length.
///
/// `px_per_font_unit` converts a font-unit advance into the tile-local unit the centreline is
/// measured in (`text_px / UP_EM / tile_span_px`), so the along-line spacing tracks the frame's
/// text size exactly as [`emit`]'s does. Returns one [`CurvedGlyph`] per glyph, or an empty vec
/// when the run is longer than the centreline — it does not fit and the label should not be
/// placed at all, which is what stops a long name spilling off a short road. A half-drawn street
/// name is worse than none: the reader cannot tell it is incomplete.
///
/// The run is measured and laid along the [longest smooth stretch](longest_smooth_run) of the
/// centreline, not the whole of it, so a corner or a join between two parts of the feature
/// neither lends its length to the fit test nor carries any glyph.
pub fn layout_along_line(
    line: &ShapedLine,
    centreline: &[(f32, f32)],
    px_per_font_unit: f32,
) -> Vec<CurvedGlyph> {
    if centreline.len() < 2 || line.glyphs.is_empty() || px_per_font_unit <= 0.0 {
        return Vec::new();
    }
    let smooth = longest_smooth_run(centreline);
    if smooth.len() < 2 {
        return Vec::new();
    }
    let total_len = polyline_length(smooth);
    let run_len = line.advance * px_per_font_unit;
    if run_len <= 0.0 || run_len > total_len {
        return Vec::new();
    }
    // Read the line in whichever direction keeps it upright: a polyline whose net heading points
    // leftwards would otherwise draw every glyph upside down. Reverse the *walk*, not the glyph
    // order, so the run still spells left-to-right on screen.
    let net_dx = smooth[smooth.len() - 1].0 - smooth[0].0;
    let reversed: Vec<(f32, f32)>;
    let path: &[(f32, f32)] = if net_dx < 0.0 {
        reversed = smooth.iter().rev().copied().collect();
        &reversed
    } else {
        smooth
    };
    let start = (total_len - run_len) * 0.5;
    // One em either side: the window each glyph's heading is averaged over. Wide enough that a
    // vertex is inside it for a few consecutive glyphs (so a bend is shared between them rather
    // than taken in one step) and that the direction noise of extent-quantised coordinates
    // averages out, narrow enough that the run still tracks a real curve.
    let half_window = UP_EM as f32 * px_per_font_unit;
    let mut out = Vec::with_capacity(line.glyphs.len());
    for g in &line.glyphs {
        let d = start + g.pen_x * px_per_font_unit;
        let (pen, segment_tangent) = sample_polyline(path, d);
        // The chord across that window, not the tangent of the segment the pen happens to sit
        // on. The segment tangent is a step function of `d`: every glyph on one segment shares
        // an angle, the angle jumps at each vertex, and on a finely-digitised road it jitters
        // with the coordinate quantisation — which is the faceted, individually-rotated look.
        // A chord between two points of a continuous polyline turns continuously.
        let (behind, _) = sample_polyline(path, d - half_window);
        let (ahead, _) = sample_polyline(path, d + half_window);
        let (dx, dy) = (ahead.0 - behind.0, ahead.1 - behind.1);
        let chord = (dx * dx + dy * dy).sqrt();
        let tangent = if chord > 1e-7 { (dx / chord, dy / chord) } else { segment_tangent };
        out.push(CurvedGlyph { glyph: *g, pen, tangent });
    }
    out
}

/// Emit two triangles per glyph of a single shaped line laid along a tile-local `centreline`.
///
/// The baseline follows the polyline and every glyph is rotated to its local tangent, so a
/// street or river name curves with its line. This is the counterpart of [`emit`] for a
/// **line** label, and it differs in two deliberate ways:
///
/// * There is no anchor, offset or justification — the run is centred on the polyline's length
///   by [`layout_along_line`].
/// * The caller must **not** pass the result through [`upright`]. A curved label is map-aligned
///   (MapLibre's `text-rotation-alignment: map`): its orientation is the geometry's tangent, not
///   the camera's bearing, so counter-rotating it would fight the curve it is meant to follow.
///
/// Vertical placement mirrors [`emit`]: the baseline sits half a cap-height below the centreline
/// (screen-down), so the cap box straddles the line. Each quad still covers the glyph ink plus
/// the SDF spread and addresses the same atlas cell, so the fragment shader is unchanged.
pub fn emit_curved(
    atlas: &GlyphAtlas,
    weight: Weight,
    line: &ShapedLine,
    centreline: &[(f32, f32)],
    text_px: f32,
    tile_span_px: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    if tile_span_px <= 0.0 {
        return;
    }
    let px_per_font_unit = text_px / UP_EM as f32 / tile_span_px;
    let placed = layout_along_line(line, centreline, px_per_font_unit);
    // The baseline is half a cap-height below the centreline, so the cap box centres on the line.
    let cap_half = 0.5 * CAP_HEIGHT_EM * UP_EM as f32 * px_per_font_unit;
    for cg in &placed {
        let Some(uv) = atlas.uv(weight, cg.glyph.ch) else { continue };
        let (cos, sin) = cg.tangent;
        // "Up" (toward glyph tops) in y-down tile space is the tangent rotated by -90 degrees.
        let up = (sin, -cos);
        let g = cg.glyph;
        // Local coordinates from the pen origin: `a` along the baseline, `u` up (font units → tile
        // local via px_per_font_unit).
        let a0 = g.bearing_x * px_per_font_unit;
        let a1 = a0 + g.w * px_per_font_unit;
        let u_top = -cap_half + g.top * px_per_font_unit;
        let u_bot = u_top - g.h * px_per_font_unit;
        let corner = |a: f32, u: f32| {
            (cg.pen.0 + cos * a + up.0 * u, cg.pen.1 + sin * a + up.1 * u)
        };
        let (x0, y0) = corner(a0, u_top);
        let (x1, y1) = corner(a1, u_top);
        let (x2, y2) = corner(a1, u_bot);
        let (x3, y3) = corner(a0, u_bot);
        let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
        // A curved label is map-aligned: each glyph vertex is its own anchor, so the billboard
        // shader collapses to a plain on-ground projection (offset zero) and the run foreshortens
        // with the ground under tilt instead of standing up.
        vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0, x0, y0]);
        vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v0, x1, y1]);
        vertices.extend_from_slice(&[x2, y2, uv.u1, uv.v1, x2, y2]);
        vertices.extend_from_slice(&[x3, y3, uv.u0, uv.v1, x3, y3]);
        indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
    }
}
