//! Turns a road centreline into the carriageway surface lane markings are painted on.
//!
//! # Why a ribbon is not just a wide stroke
//!
//! A stroke ([`super::stroke`]) only has to be a band of one colour, so a fragment
//! never needs to know where in that band it landed. A marking does: whether a
//! fragment is inside the centre line, inside a lane divider, or on bare tarmac is
//! entirely a question of how far across the road it is. So every vertex here carries
//! `t`, an across-road coordinate that is exactly `-1` on the left kerb and `+1` on the
//! right and interpolates linearly between them. Each marking in the style then reduces
//! to a comparison against `t`, and one carriageway mesh serves all of them.
//!
//! # Width still lives in the shader
//!
//! Normalising `t` is what makes that possible. A carriageway's half-width is a screen
//! measurement that changes continuously with zoom; baking it into these vertices would
//! re-tessellate every road in the resident set on every zoom step, while panning, on
//! the critical path. It stays a push constant — exactly as `Stroke::half_px` does for
//! the line path — and the vertex shader offsets by
//!
//! ```text
//! position + normal * t * half_width
//! ```
//!
//! Because `t` is a fraction of the road rather than a distance across it, a marking's
//! place on the carriageway is the same number at every zoom. The geometry is a
//! function of the tile alone.
//!
//! # A taper is a ratio, and a ratio is not a width
//!
//! Where a road's lane count changes, two abutting sections are two features with two
//! lane counts, so the renderer draws them as two meshes at two half-widths and the
//! carriageway steps. Removing that step means the width has to vary *along* the road,
//! which reads like the invariant above forbids it. It does not, and the distinction is
//! the whole of [`Taper`]: what the invariant rules out is a **screen measurement** in a
//! vertex, because that is what a zoom step changes. A dimensionless ratio — this
//! vertex carries a road four fifths as wide as the push constant says — is not a screen
//! measurement, and a zoom step does not touch it.
//!
//! It needs no new attribute either, because the normal is already not a unit vector:
//! [`joins::join_normal`] lengthens it by the miter factor, and the vertex shader multiplies by
//! it without ever assuming a length. So a taper is that same normal scaled a second
//! time, and the vertex stays seven floats, the push constant stays a push constant, and
//! `road_surface.vert` reads z from the new trailing float.
//!
//! Both sides of a transition scale toward a width derived from the *node* rather than
//! from either section (see [`crate::tile::taper`]), so they cannot arrive at it
//! disagreeing. The step is not detected and corrected; it is unrepresentable.
//!
//! What this does **not** move is the lane markings: `lanes` and the centre-line `t` are
//! still per-mesh push constants, so through a taper the shader squeezes the section's
//! own lane count into a narrowing road rather than wedging one lane out, and the centre
//! line converges on the narrow section's without exactly meeting it. Both would need a
//! seventh float — still dimensionless, still invariant-safe — and neither is the step
//! the eye actually catches.
//!
//! # Joins
//!
//! Miter joins and butt caps, computed and clamped exactly as the stroke path does, and
//! sharing its [`crate::tess::stroke::MITER_LIMIT`]. A carriageway, its casing and its
//! markings are all drawn from one centreline and have to agree along their edges, so
//! they cannot disagree about where a join is. Lengthening the normal through a miter
//! is also what holds `t = ±1` on the kerb around a bend — with a unit normal the road
//! narrows into the turn and every divider slides sideways with it.

mod joins;
mod taper;

#[cfg(test)]
mod tests;

use self::joins::{dedupe, join_normal, segment_length};
use self::taper::taper_widths;

/// Floats per vertex: `x, y, nx, ny, t, distance, z`.
///
/// `z` is the draped ground height in the same tile-local unit as `x`/`y` — `0.0`
/// straight out of the tessellator, filled in by the drape pass at build time where
/// the tile carries a heightmap. Trailing so every existing field index is unchanged.
/// The tilted clip matrix reads it; at pitch 0 it is ignored for x/y.
pub const FLOATS_PER_VERTEX: usize = 7;

/// How much narrower the carriageway is at each end of a part than along its middle.
///
/// `start` and `end` are ratios in `(0, 1]` against this part's *own* width: `1.0` is
/// full width and `0.5` is half of it, because the section this end runs into carries
/// half the lanes. `run` is how far back from that end the ramp reaches, in the same
/// tile-local `0..1` units the emitted positions use.
///
/// A ratio and a ground length, never a pixel: see the module docs on why that is what
/// lets the width stay a push constant.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Taper {
    start: f32,
    end: f32,
    run: f32,
}

impl Taper {
    /// A part that abuts nothing of a different width — the whole of the junction
    /// connector path, and the overwhelming majority of roads.
    pub const NONE: Taper = Taper {
        start: 1.0,
        end: 1.0,
        run: 0.0,
    };

    /// Clamped on the way in, so a ratio outside `(0, 1]` — which would invert the road
    /// or collapse it to nothing — cannot reach the tessellator to be checked for later.
    ///
    /// A taper that ramps to full width at both ends *is* [`NONE`](Taper::NONE) and is
    /// returned as it, so "this part does not taper" has one spelling rather than one per
    /// run length it happened to be offered.
    pub fn new(start: f32, end: f32, run: f32) -> Taper {
        let (start, end) = (ratio(start), ratio(end));
        if start >= 1.0 && end >= 1.0 {
            return Taper::NONE;
        }
        Taper {
            start,
            end,
            run: if run > 0.0 { run } else { 0.0 },
        }
    }

    /// Whether this taper would change any vertex. A `run` of zero cannot ramp.
    fn is_flat(self) -> bool {
        self.run <= 0.0
    }
}

/// A width ratio, held strictly positive so a taper can never produce a zero-width or
/// inside-out carriageway. The floor is one 255th because the narrowest a real ratio can
/// be is one lane against the 255 a `u8` lane count admits.
fn ratio(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(1.0 / 255.0, 1.0)
    } else {
        1.0
    }
}

/// Tessellate one carriageway part at a uniform width.
///
/// `coords` is flat `[x0, y0, ...]` in tile coordinates; `extent` is the layer's
/// extent, so positions come out in 0..1 and the renderer needs no per-layer scale.
/// Indices are relative to the first vertex already in `vertices`.
pub fn ribbon(coords: &[i32], extent: u32, vertices: &mut Vec<f32>, indices: &mut Vec<u32>) {
    ribbon_tapered(coords, extent, Taper::NONE, vertices, indices);
}

/// As [`ribbon`], but ramping to a neighbouring section's width at either end.
///
/// The ramp is applied to the join normal, which the vertex shader already treats as a
/// length rather than a direction, so nothing about the vertex format or the push
/// constants changes. A vertex is inserted where each ramp begins: without one, the
/// ramp would run from whatever vertex the simplifier happened to leave, and a straight
/// half-kilometre of road would taper over its whole length.
pub fn ribbon_tapered(
    coords: &[i32],
    extent: u32,
    taper: Taper,
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
) {
    let mut points = dedupe(coords);
    if points.len() / 2 < 2 {
        return;
    }
    // `taper.run` is in the same 0..1 units as the output; the points are still in
    // extent units, so the ramp has to be measured in those.
    let widths = taper_widths(&mut points, taper, taper.run * extent as f32);
    let n = points.len() / 2;
    let scale = 1.0 / extent as f32;

    let base = (vertices.len() / FLOATS_PER_VERTEX) as u32;
    let mut distance = 0.0f32;

    for i in 0..n {
        let (nx, ny) = join_normal(&points, n, i);
        // Empty for an untapered part, which is nearly all of them, so the common path
        // pays one bounds check and multiplies by nothing.
        let width = widths.get(i).copied().unwrap_or(1.0);

        if i > 0 {
            // Scaled with the positions: the dash and marking-repeat patterns are
            // measured in pixels, and `line.vert` gets there by multiplying by the
            // tile's pixel span. Leaving this in extent units would make the pattern
            // period depend on the layer that happened to supply the geometry.
            distance += segment_length(&points, i - 1, i) * scale;
        }

        let px = points[i * 2] as f32 * scale;
        let py = points[i * 2 + 1] as f32 * scale;
        // Tile y grows downward, so the perpendicular `(-dy, dx)` points to the right
        // of travel — the `-1` vertex is the left kerb, as a driver would name it.
        vertices.extend_from_slice(&[px, py, nx * width, ny * width, -1.0, distance, 0.0]);
        vertices.extend_from_slice(&[px, py, nx * width, ny * width, 1.0, distance, 0.0]);
    }

    // Two triangles per segment, wound as the stroke path winds them so face culling
    // could be switched on later without the roads disappearing.
    for i in 0..(n - 1) {
        let a = base + (i as u32) * 2;
        indices.extend_from_slice(&[a, a + 1, a + 2, a + 1, a + 3, a + 2]);
    }
}
