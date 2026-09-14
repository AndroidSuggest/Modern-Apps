//! Screen-space text labels from SDF glyphs: ASCII shaping plus quad emission.
//!
//! M1 covers _place_ labels only (country/region/locality/neighbourhood): point
//! features from the v2 `places` layer whose display name is the body's name-table
//! string. No shaping engine, no BiDi, no complex scripts — `name:en` coalesced by
//! the tiler is Latin in the overwhelming NA case, and CJK/others are a stated M5.
//!
//! # Data flow
//!
//! [`shape`] turns a string into one [`ShapedGlyph`] per codepoint: pen advance in
//! _font units_ (1/upem em — ab_glyph unscaled metrics), so shaping is zoom- and
//! size-independent. [`shape_wrapped`] is the same thing broken into lines.
//! [`emit`] turns shaped lines into two triangles per glyph in tile-local 0..1, at the
//! label's anchor point, scaled by the per-frame pixel size the caller passes.
//! Tessellation is therefore a pure function of (tile, string); the camera zoom only
//! changes the scale factor, exactly like a road width.
//!
//! [`ATLAS`] (in `tile::glyph`) maps (weight, char) to UV rect + font-unit metrics,
//! shared by both functions.

use crate::style::Anchor;
use crate::tile::glyph::{GlyphAtlas, Weight, UP_EM};

/// Floats per vertex: `x, y, u, v, ax, ay` — the quad position and atlas UV, plus the label's
/// **ground anchor** in tile-local 0..1.
///
/// The anchor rides on every vertex so the billboard vertex shader (`symbol_billboard.vert`) can,
/// under tilt, project the anchor through the perspective matrix and hang the glyph off it at a
/// constant screen offset — keeping point labels upright and pinned to the ground. At pitch 0 the
/// anchor is ignored and the position is drawn as-is, so the flat map is byte-identical. A curved
/// (line) label writes each glyph's own position as its anchor, which collapses the billboard to a
/// plain on-ground projection — curved labels stay map-aligned, as they must.
pub const FLOATS_PER_VERTEX: usize = 6;

/// One codepoint after shaping: pen position plus atlas lookup.
///
/// `bearing_x`, `top`, `w` and `h` are copied straight from [`crate::tile::glyph::GlyphMetrics`]
/// and describe the **quad** — the glyph bitmap grown by the SDF spread — not the ink,
/// so they pair exactly with the atlas UV rect. `advance` is the untouched font metric.
#[derive(Clone, Copy, Debug)]
pub struct ShapedGlyph {
    /// Pen x at this glyph's origin, in font units (1/upem em).
    pub pen_x: f32,
    /// The character.
    pub ch: char,
    /// Advance including kerning with the previous glyph, in font units.
    pub advance: f32,
    /// Quad's left edge relative to the pen, in font units.
    pub bearing_x: f32,
    /// Quad's top above the baseline, in font units, y-up.
    pub top: f32,
    /// Quad size in font units.
    pub w: f32,
    pub h: f32,
}

/// Cap height as a fraction of the em for the bundled Noto Sans (1462/2048).
///
/// What centres on a label's anchor. Centring the full em box instead sits the text
/// visibly low, because a place name's descender space is usually empty.
pub const CAP_HEIGHT_EM: f32 = 0.714;

/// Baseline-to-baseline distance as a fraction of the em, for a wrapped label.
///
/// MapLibre's `text-line-height` default, and the one this renderer uses: the reference
/// `pois` layer does not override it, and a wrapped POI label that does not match it
/// diverges more visibly with each extra line.
pub const LINE_HEIGHT_EM: f32 = 1.2;

/// One line of a shaped label: glyphs with pen positions restarting at zero.
#[derive(Clone, Debug)]
pub struct ShapedLine {
    pub glyphs: Vec<ShapedGlyph>,
    /// Total advance of this line, in font units.
    pub advance: f32,
}

/// Shape `text` with `atlas`'s metrics: pen advances plus kerning, no line breaking.
///
/// `uppercase` applies the authored `text-transform` **here**, before any metric is read,
/// which is the only place it can correctly go. Applying it at emission instead drew the
/// uppercase glyph into a quad sized and positioned from the lowercase one: every letter
/// that was lowercase in the source came out at x-height dimensions on the wrong baseline,
/// with an advance too narrow for the glyph in it.
///
/// Unknown codepoints are skipped (a tofu box is worse than a gap at M1; CJK is M5).
/// Returns the shaped glyphs and the total advance in font units.
pub fn shape(
    atlas: &GlyphAtlas,
    weight: Weight,
    text: &str,
    uppercase: bool,
) -> (Vec<ShapedGlyph>, f32) {
    let mut out = Vec::new();
    let mut pen_x = 0.0f32;
    let mut previous: Option<char> = None;
    for ch in text.chars() {
        let ch = if uppercase { ch.to_ascii_uppercase() } else { ch };
        let Some(metrics) = atlas.metrics(weight, ch) else { continue };
        if let Some(prev) = previous {
            pen_x += atlas.kern(weight, prev, ch);
        }
        out.push(ShapedGlyph {
            pen_x,
            ch,
            advance: metrics.advance,
            bearing_x: metrics.bearing_x,
            top: metrics.top,
            w: metrics.w,
            h: metrics.h,
        });
        pen_x += metrics.advance;
        previous = Some(ch);
    }
    (out, pen_x)
}

/// Shape `text` into lines, breaking so no line runs much past `max_width_em` ems.
///
/// [`shape`] is the single-line special case, and `max_width_em <= 0.0` returns exactly
/// that: one line holding the whole run. Place labels take that path, which is why they
/// are unaffected by any of this.
///
/// # Why not greedy wrapping
///
/// The obvious algorithm — fill a line until the next word overflows, then break — is a
/// *different* algorithm from MapLibre's, not an approximation of it. MapLibre minimises
/// total raggedness over all break sets by backtracking, so it will happily break a line
/// early to keep the following ones even. On a two-line label the two usually agree; on
/// three they visibly do not, and matching the reference is the whole point of this layer.
///
/// So this is a transcription of `determineLineBreaks`, including the parts that look like
/// bugs and are not:
///
/// * The target width counts whitespace, while the running cursor that positions the
///   breaks does not. That asymmetry is in the reference and changing it moves breaks.
/// * A tie in badness prefers the **later** break (`<=`, not `<`).
/// * The final line's badness is halved when short and doubled when long, which is what
///   makes a ragged-right block prefer a short last line.
pub fn shape_wrapped(
    atlas: &GlyphAtlas,
    weight: Weight,
    text: &str,
    uppercase: bool,
    max_width_em: f32,
) -> Vec<ShapedLine> {
    let (glyphs, advance) = shape(atlas, weight, text, uppercase);
    if glyphs.is_empty() {
        return Vec::new();
    }
    if max_width_em <= 0.0 {
        return vec![ShapedLine { glyphs, advance }];
    }
    let breaks = line_breaks(&glyphs, max_width_em * UP_EM as f32);
    let mut lines = Vec::with_capacity(breaks.len() + 1);
    let mut start = 0usize;
    for end in breaks.iter().copied().chain(std::iter::once(glyphs.len())) {
        let Some(run) = glyphs.get(start..end) else { continue };
        start = end;
        // Trimmed like MapLibre's `TaggedString.trim()`: the space a line broke at would
        // otherwise widen the line it left behind and indent the one it starts.
        let trimmed: Vec<&ShapedGlyph> = {
            let lead = run.iter().position(|g| !is_whitespace(g.ch));
            let Some(lead) = lead else { continue };
            let tail = run.iter().rposition(|g| !is_whitespace(g.ch)).unwrap_or(lead);
            run.get(lead..=tail).unwrap_or_default().iter().collect()
        };
        // Pen positions restart per line, so `emit` can place each line independently.
        let mut pen_x = 0.0f32;
        let mut out = Vec::with_capacity(trimmed.len());
        for g in trimmed {
            out.push(ShapedGlyph { pen_x, ..*g });
            pen_x += g.advance;
        }
        if !out.is_empty() {
            lines.push(ShapedLine { glyphs: out, advance: pen_x });
        }
    }
    if lines.is_empty() {
        return vec![ShapedLine { glyphs, advance }];
    }
    lines
}

/// MapLibre's `whitespace` set.
fn is_whitespace(ch: char) -> bool {
    matches!(ch, '\t' | '\n' | '\u{0b}' | '\u{0c}' | '\r' | ' ')
}

/// MapLibre's `breakable` set: where a line may be cut.
fn is_breakable(ch: char) -> bool {
    matches!(
        ch,
        '\n' | ' '
            | '&'
            | '('
            | ')'
            | '+'
            | '-'
            | '/'
            | '\u{ad}'
            | '\u{b7}'
            | '\u{200b}'
            | '\u{2010}'
            | '\u{2013}'
            | '\u{2027}'
    )
}

/// MapLibre's `calculatePenalty`, minus the ideographic arm (the atlas is ASCII).
///
/// A newline is a forced break, expressed as a penalty so large that no raggedness can
/// outweigh it, rather than as a special case in the search.
fn break_penalty(ch: char, next: char) -> f32 {
    let mut penalty = 0.0f32;
    if ch == '\n' {
        penalty -= 10_000.0;
    }
    if ch == '(' || ch == '\u{ff08}' {
        penalty += 50.0;
    }
    if next == ')' || next == '\u{ff09}' {
        penalty += 50.0;
    }
    penalty
}

/// MapLibre's `calculateBadness`: squared deviation from the target, plus the penalty.
fn badness(line_width: f32, target: f32, penalty: f32, last: bool) -> f32 {
    let raggedness = (line_width - target) * (line_width - target);
    if last {
        // A final line shorter than average reads as a normal ragged edge; a long one
        // reads as a wrapping failure, so it is punished four times as hard.
        return if line_width < target { raggedness / 2.0 } else { raggedness * 2.0 };
    }
    // `abs(p) * p` keeps a negative penalty (the forced newline) negative after squaring.
    raggedness + penalty.abs() * penalty
}

/// One candidate break in the search: where it is, and the best total cost of reaching it.
struct Candidate {
    /// Glyph index the next line starts at.
    index: usize,
    /// Cursor position when this break was considered, in font units.
    x: f32,
    /// Index into the candidate list of the break this one follows, if any.
    prior: Option<usize>,
    badness: f32,
}

/// MapLibre's `evaluateBreak`: the cheapest way to reach this break from any earlier one.
fn evaluate(
    index: usize,
    x: f32,
    target: f32,
    prior: &[Candidate],
    penalty: f32,
    last: bool,
) -> Candidate {
    let mut best_prior = None;
    let mut best = badness(x, target, penalty, last);
    for (at, earlier) in prior.iter().enumerate() {
        let cost = badness(x - earlier.x, target, penalty, last) + earlier.badness;
        // `<=`, matching the reference: on a tie the later break wins.
        if cost <= best {
            best_prior = Some(at);
            best = cost;
        }
    }
    Candidate { index, x, prior: best_prior, badness: best }
}

/// The glyph indices each line after the first starts at.
fn line_breaks(glyphs: &[ShapedGlyph], max_width: f32) -> Vec<usize> {
    // The target is the run split into as few equal lines as will fit, not `max_width`
    // itself: wrapping "Golden Gate Park" at 8 ems should give two balanced lines rather
    // than one full line and one word.
    let total: f32 = glyphs.iter().map(|g| g.advance).sum();
    let line_count = (total / max_width).ceil().max(1.0);
    let target = total / line_count;

    let mut candidates: Vec<Candidate> = Vec::new();
    let mut x = 0.0f32;
    for (i, g) in glyphs.iter().enumerate() {
        // Whitespace advances the pen but **not** the break cursor. Straight from the
        // reference; it makes a break measure the ink either side of it rather than the
        // gap, and removing it shifts break points.
        if !is_whitespace(g.ch) {
            x += g.advance;
        }
        let Some(next) = glyphs.get(i + 1) else { continue };
        if is_breakable(g.ch) {
            let penalty = break_penalty(g.ch, next.ch);
            let candidate = evaluate(i + 1, x, target, &candidates, penalty, false);
            candidates.push(candidate);
        }
    }
    let end = evaluate(glyphs.len(), x, target, &candidates, 0.0, true);

    let mut out = Vec::new();
    let mut cursor = end.prior;
    while let Some(at) = cursor {
        let Some(candidate) = candidates.get(at) else { break };
        out.push(candidate.index);
        cursor = candidate.prior;
    }
    out.reverse();
    out
}

/// Emit two triangles per glyph of a shaped block into `vertices`/`indices`.
///
/// `point` is the label's anchor in tile-local 0..1 and `anchor` says where the block sits
/// relative to it: `Center` straddles it, `Left` puts the block's left edge on it (so the
/// text runs right), `Right` its right edge. `offset_em` is `text-offset` in ems, pushing
/// the block further along that direction. `tile_span_px` is this tile's screen size in px
/// (so `text_px / tile_span_px` converts px to tile-local); `text_px` is the frame's label
/// size in screen px from the layer's `text_size` ramp. The authored `text-transform` was
/// already applied by [`shape`].
///
/// Justification follows the anchor, which is MapLibre's `text-justify: auto`: a
/// left-anchored block is left-justified, so its wrapped lines line up down the side
/// nearest the point.
///
/// A single line at `Center` with a zero offset reduces to exactly the arithmetic this
/// used to do, in the same order — which is what keeps every `places-*` label
/// byte-identical.
///
/// Each quad covers the glyph's ink **plus the SDF spread**, matching the atlas rect
/// the UV addresses — the two are derived from one placement in [`crate::tile::glyph`]
/// and must stay that way, or the ink scales independently of its advance.
///
/// Indices are relative to the first vertex already in `vertices`.
#[allow(clippy::too_many_arguments)]
pub fn emit(
    atlas: &GlyphAtlas,
    weight: Weight,
    lines: &[ShapedLine],
    point: (f32, f32),
    anchor: Anchor,
    offset_em: (f32, f32),
    text_px: f32,
    tile_span_px: f32,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let px_per_font_unit = text_px / UP_EM as f32 / tile_span_px;
    // One em in tile-local units. Derived through `px_per_font_unit` rather than from
    // `text_px` directly so the single-line centred path below stays bit-for-bit what it
    // was.
    let em = UP_EM as f32 * px_per_font_unit;
    // MapLibre takes the offset's magnitude and picks its sign from the anchor, and gives
    // a horizontal anchor no vertical component at all. `offset_em.1` is therefore unused
    // here — every anchor this renderer supports is horizontal.
    let offset_x = offset_em.0.abs() * em;
    let _ = offset_em.1;
    // The stack of baselines is centred where a single line's would sit, so one line is
    // unmoved and each extra line splits the difference either side of the anchor.
    let block_shift = (lines.len().saturating_sub(1)) as f32 * 0.5 * LINE_HEIGHT_EM * em;
    let first_baseline =
        point.1 + 0.5 * CAP_HEIGHT_EM * UP_EM as f32 * px_per_font_unit - block_shift;
    let widest = lines.iter().fold(0.0f32, |wide, line| wide.max(line.advance));

    for (index, line) in lines.iter().enumerate() {
        let origin_x = match anchor {
            // Every line centred on the point, which for one line is the old expression
            // in the old order.
            Anchor::Center => point.0 - line.advance * 0.5 * px_per_font_unit,
            // Left-justified: every line starts at the block's left edge, whatever the
            // widest line is.
            Anchor::Left => point.0 + offset_x,
            // Right-justified: every line ends at the block's right edge.
            Anchor::Right => {
                point.0 - offset_x - widest * px_per_font_unit
                    + (widest - line.advance) * px_per_font_unit
            }
        };
        let baseline_y = first_baseline + index as f32 * LINE_HEIGHT_EM * em;
        for g in &line.glyphs {
            let Some(uv) = atlas.uv(weight, g.ch) else { continue };
            let x0 = origin_x + (g.pen_x + g.bearing_x) * px_per_font_unit;
            let x1 = x0 + g.w * px_per_font_unit;
            // `top` is y-up above the baseline; tile space is y-down.
            let y0 = baseline_y - g.top * px_per_font_unit;
            let y1 = y0 + g.h * px_per_font_unit;
            let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
            vertices.extend_from_slice(&[x0, y0, uv.u0, uv.v0, point.0, point.1]);
            vertices.extend_from_slice(&[x1, y0, uv.u1, uv.v0, point.0, point.1]);
            vertices.extend_from_slice(&[x1, y1, uv.u1, uv.v1, point.0, point.1]);
            vertices.extend_from_slice(&[x0, y1, uv.u0, uv.v1, point.0, point.1]);
            indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
        }
    }
}

/// Rotate already-emitted quads about `pivot`, so they draw upright through a rotated
/// clip matrix.
///
/// `vertices` is the `[x, y, u, v]` run this label just appended, `pivot` its tile-local
/// anchor, and `rotation` the `(cos, sin)` of the camera's bearing. The clip matrix turns
/// a tile-local offset `d` into a screen offset `R·d`; pre-multiplying by `Rᵀ` here makes
/// that come out screen-aligned again, which is the whole trick.
///
/// **Labels stay upright under a heading-up camera**, which is what car navigation wants
/// and what MapLibre does with the default `text-rotation-alignment: viewport`. Letting
/// them turn with the map puts every street name upside down whenever the driver is
/// heading south. It also keeps [`crate::tile::placement`] honest for free: its collision
/// boxes are axis-aligned in screen space, which is exactly the box an upright label
/// occupies and is *not* the box a rotated one would.
///
/// Tile-local space is scaled uniformly to the screen (`span` Dp on both axes, see
/// [`crate::camera::Camera::world_quad_to_clip`]), so a rotation here is a rotation there
/// — no shear to correct for.
pub fn upright(vertices: &mut [f32], pivot: (f32, f32), rotation: (f32, f32)) {
    upright_stride(vertices, pivot, rotation, FLOATS_PER_VERTEX);
}

/// [`upright`] for a vertex layout of `stride` floats whose first two are the position.
///
/// The point-label text path uses [`FLOATS_PER_VERTEX`]; the POI-icon path keeps its own
/// 4-float `x, y, u, v` quad (it stays on the on-ground sprite pipeline, not the billboard one),
/// so it rotates through here with `stride == 4`. Only the leading `x, y` of each vertex moves;
/// any trailing fields (uv, anchor) are copied through untouched.
pub fn upright_stride(vertices: &mut [f32], pivot: (f32, f32), rotation: (f32, f32), stride: usize) {
    let (cos, sin) = rotation;
    if sin == 0.0 && cos == 1.0 {
        return;
    }
    for vertex in vertices.chunks_exact_mut(stride) {
        let dx = vertex[0] - pivot.0;
        let dy = vertex[1] - pivot.1;
        vertex[0] = pivot.0 + cos * dx - sin * dy;
        vertex[1] = pivot.1 + sin * dx + cos * dy;
    }
}

include!("text_part1.rs");
include!("text_part2.rs");
include!("text_part3.rs");
include!("text_part4.rs");