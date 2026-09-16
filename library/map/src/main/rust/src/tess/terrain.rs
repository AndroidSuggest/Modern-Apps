//! Tessellates a tile's ground into a DEM-displaced relief grid (WS-G, 3D terrain).
//!
//! Render-time only, like [`super::roof`]: the archive carries a per-tile `u16` heightmap in a
//! side table (metres above sea level, biased by 32768 — see
//! [`Heightmap`](tilecodec::mamaps::body::Heightmap)), never triangles. This module turns one
//! tile's heightmap into a regular grid of triangles whose per-vertex `z` rises and falls with the
//! terrain, so under camera tilt the ground shows real relief and occludes what sits behind a hill.
//!
//! # The terrain vertex
//!
//! Six floats — position with a height, plus a surface normal. Unlike a building it needs no
//! per-vertex colour: the whole ground is one style colour (the `earth` layer's), pushed per draw,
//! and the relief is conveyed by shading that colour against the normal.
//!
//! | offset | floats | meaning                                                        |
//! |--------|--------|----------------------------------------------------------------|
//! | 0..12  | 3      | tile-local `(u, v, height)`; `u,v` in 0..1, `height` tile-norm  |
//! | 12..24 | 3      | surface normal in the same tile-local space, unit length       |
//!
//! `height` is **tile-normalised** — metres divided by the tile's own ground width, the same unit
//! `u`/`v` are — so the grid is zoom-independent like every other tile mesh, exactly as
//! [`super::roof`] normalises building heights. The terrain vertex shader multiplies it by the
//! tile's world-px span (a per-frame push scalar) to recover the world-px height the WS0
//! perspective matrix expects in its `z` input. At pitch 0 that matrix ignores `z` for `x`/`y`, so
//! the grid collapses to the flat footprint of the tile — the flat map the app has always shown.
//!
//! # The grid
//!
//! One vertex per heightmap sample (`dim * dim`), so the DEM is used at its own resolution with no
//! resampling, and `(dim - 1)^2` quads between them. Normals come from central differences of the
//! neighbouring heights (a standard heightfield normal), so a slope reads darker than a flat and
//! the relief is legible without any shadow pass. The differences read a 3×3-smoothed copy of the
//! height field — interior texels only, the border ring verbatim — so facet noise softens while
//! the two tiles sharing an edge smooth from the same edge samples and agree exactly. Positions
//! always carry the raw heights untouched. A tile whose samples are all equal (flat DEM, or
//! filled sea level) produces a flat sheet with every normal pointing straight up — indistinguishable
//! from the old flat ground once shading is disabled at pitch 0.

use tilecodec::mamaps::body::Heightmap;

/// Floats per terrain vertex: `x, y, z, nx, ny, nz`.
pub const FLOATS_PER_VERTEX: usize = 6;

/// Smooth the height field for normals only: a 3×3 box blur over interior texels, with the
/// border ring copied verbatim.
///
/// The neighbour tile across a shared edge smooths from the same edge samples (it stores its own
/// copy of them, and neither side's blur kernel reaches past its own border), so the two shared
/// edge slopes — and hence the two edge normals — agree bit-for-bit: zero seam risk by
/// construction. Kept on `f32` tile-normalised heights so the test can compare pre/post blur
/// exactly, and so positions — which must stay exact — never pass through it.
fn smooth_heights(raw: &[f32], dim: usize) -> Vec<f32> {
    let mut out = raw.to_vec();
    if dim < 3 {
        // No interior texel exists (a dim-2 grid is all border), so there is nothing to blur.
        return out;
    }
    for row in 1..dim - 1 {
        for col in 1..dim - 1 {
            let mut sum = 0.0f32;
            for dr in -1..=1 {
                for dc in -1..=1 {
                    sum += raw[((row as isize + dr) as usize) * dim + (col as isize + dc) as usize];
                }
            }
            out[row * dim + col] = sum / 9.0;
        }
    }
    out
}

/// Tessellate one tile's heightmap into a relief grid, appending to `out_v`/`out_i`.
///
/// `ground_width_m` is the tile's own east–west ground width in metres (see
/// [`crate::tile::geometry`]), the horizontal unit the heights are normalised against so a metre up
/// reads the same on screen as a metre across. A `dim` below 2 (or a zero ground width) makes no
/// surface and emits nothing.
///
/// The output is indexed and shares vertices between adjacent quads: unlike the flat-shaded
/// building mesh, terrain normals are per-vertex (Gouraud across the grid), so sharing is both
/// correct and cheaper.
pub fn tessellate(hm: &Heightmap, ground_width_m: f64, out_v: &mut Vec<f32>, out_i: &mut Vec<u32>) {
    let dim = hm.dim as usize;
    if dim < 2 {
        return;
    }
    // Tile-normalised height per metre; a degenerate (polar) tile with zero width flattens rather
    // than dividing by zero, matching how buildings handle the same edge.
    let factor = if ground_width_m > 0.0 { 1.0 / ground_width_m } else { 0.0 };
    // A sample's height in tile-normalised units. Out-of-range reads back sea level (the bias),
    // which the grid never asks for but keeps the closure total.
    let height = |col: usize, row: usize| -> f32 {
        let stored = hm.sample(col as u16, row as u16).unwrap_or(32768);
        (Heightmap::metres(stored) as f64 * factor) as f32
    };

    let step = 1.0 / (dim as f32 - 1.0);
    let base = (out_v.len() / FLOATS_PER_VERTEX) as u32;
    // The blurred field behind the normals: central differences below read from this, so
    // per-sample DEM noise softens, while positions keep the raw `height(col, row)`. The
    // border ring is verbatim by construction, so the shared-edge slopes — and the edge
    // normals — agree bit-for-bit with the neighbour tile's.
    let smooth = {
        let mut raw = Vec::with_capacity(dim * dim);
        for row in 0..dim {
            for col in 0..dim {
                raw.push(height(col, row));
            }
        }
        smooth_heights(&raw, dim)
    };
    let shed = |col: usize, row: usize| -> f32 { smooth[row * dim + col] };
    for row in 0..dim {
        for col in 0..dim {
            let u = col as f32 * step;
            let v = row as f32 * step;
            let z = height(col, row);

            // Central differences over the *smoothed* field, clamped at the tile edge. The spacing
            // is the tile-normalised grid step, so the slope is in the same units as `z`, which
            // keeps the normal honest against the height the vertex actually carries.
            let cl = col.saturating_sub(1);
            let cr = (col + 1).min(dim - 1);
            let ru = row.saturating_sub(1);
            let rd = (row + 1).min(dim - 1);
            let dzdu = (shed(cr, row) - shed(cl, row)) / ((cr - cl) as f32 * step);
            let dzdv = (shed(col, rd) - shed(col, ru)) / ((rd - ru) as f32 * step);
            // The upward normal of a heightfield z = f(u, v) is (-dz/du, -dz/dv, 1), normalised.
            let mut n = [-dzdu, -dzdv, 1.0];
            let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
            if len > f32::EPSILON {
                n = [n[0] / len, n[1] / len, n[2] / len];
            } else {
                n = [0.0, 0.0, 1.0];
            }
            out_v.extend_from_slice(&[u, v, z, n[0], n[1], n[2]]);
        }
    }

    let dim32 = dim as u32;
    for row in 0..dim - 1 {
        for col in 0..dim - 1 {
            let a = base + row as u32 * dim32 + col as u32;
            let b = a + 1;
            let c = a + dim32;
            let d = c + 1;
            // Two triangles per cell, consistently wound; culling is off in the pipeline so the
            // winding only ever decides nothing visible, but it is kept regular for readability.
            out_i.extend_from_slice(&[a, b, d, a, d, c]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the format
    /// stores.
    fn heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> Heightmap {
        let mut samples = Vec::with_capacity((dim as usize).pow(2));
        for row in 0..dim {
            for col in 0..dim {
                samples.push((metres(col, row) + 32768) as u16);
            }
        }
        Heightmap { dim, samples }
    }

    /// Read back one vertex as `(position, normal)`.
    fn vertex(v: &[f32], i: usize) -> ([f32; 3], [f32; 3]) {
        let at = i * FLOATS_PER_VERTEX;
        ([v[at], v[at + 1], v[at + 2]], [v[at + 3], v[at + 4], v[at + 5]])
    }

    #[test]
    fn a_flat_heightmap_makes_a_flat_grid_pointing_up() {
        // Sea level everywhere: every vertex sits at z 0 with a straight-up normal, so at pitch 0
        // it is indistinguishable from the old flat ground.
        let hm = heightmap(5, |_, _| 0);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        assert_eq!(v.len() / FLOATS_PER_VERTEX, 25, "one vertex per sample");
        assert_eq!(i.len(), 4 * 4 * 6, "two triangles per cell over a 4x4 cell grid");
        for k in 0..v.len() / FLOATS_PER_VERTEX {
            let (p, n) = vertex(&v, k);
            assert!(p[2].abs() < 1e-6, "flat ground sits at z 0, got {}", p[2]);
            assert!(n[0].abs() < 1e-6 && n[1].abs() < 1e-6 && (n[2] - 1.0).abs() < 1e-6);
        }
    }

    #[test]
    fn a_synthetic_hill_displaces_the_ground() {
        // A single high sample in the centre must lift that vertex to metres / ground_width, the
        // same tile-normalised unit buildings use, and its neighbours must tilt their normals off
        // vertical toward it.
        let dim = 5u16;
        let peak_m = 300; // 300 m
        let ground = 1200.0; // 1200 m across the tile, so the peak is 0.25 tile-norm high.
        let centre = dim / 2;
        let hm = heightmap(dim, |c, r| if c == centre && r == centre { peak_m } else { 0 });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);

        let (peak, peak_n) = vertex(&v, (centre as usize) * dim as usize + centre as usize);
        assert!(
            (peak[2] - peak_m as f32 / ground as f32).abs() < 1e-6,
            "the hill rises to metres/ground_width: {} vs {}",
            peak[2],
            peak_m as f32 / ground as f32,
        );
        assert!(peak_n[2] > 0.99, "the very top is locally flat, so its normal stays near vertical");

        // A sample one step west of the peak sits on the slope, so its normal leans off vertical.
        let (_, slope_n) = vertex(&v, (centre as usize) * dim as usize + centre as usize - 1);
        assert!(
            slope_n[0].abs() > 1e-3 || slope_n[1].abs() > 1e-3,
            "a slope vertex must tilt its normal, got {slope_n:?}",
        );
        assert!(slope_n[2] < 0.9999, "and lean it away from straight up");

        // The displaced grid still reads as the tile footprint from overhead: every x/y in 0..1.
        for k in 0..v.len() / FLOATS_PER_VERTEX {
            let (p, _) = vertex(&v, k);
            assert!((0.0..=1.0).contains(&p[0]) && (0.0..=1.0).contains(&p[1]), "{p:?} left the tile");
        }
    }

    #[test]
    fn a_below_sea_sample_dips_below_zero() {
        // The bias lets terrain go below sea level (the Dead Sea, Death Valley); a -100 m sample
        // must come out negative rather than clamped.
        let hm = heightmap(3, |c, r| if c == 1 && r == 1 { -100 } else { 0 });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        let (mid, _) = vertex(&v, 4);
        assert!(mid[2] < 0.0, "a below-sea sample dips below z 0, got {}", mid[2]);
    }

    #[test]
    fn a_degenerate_heightmap_emits_nothing() {
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&Heightmap { dim: 1, samples: vec![32768] }, 1000.0, &mut v, &mut i);
        assert!(v.is_empty() && i.is_empty(), "a 1x1 grid has no cell to tessellate");
    }

    #[test]
    fn every_index_is_in_range_and_triangles_are_whole() {
        let hm = heightmap(9, |c, r| (c as i32 * 7 + r as i32 * 3) % 50);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 800.0, &mut v, &mut i);
        let count = (v.len() / FLOATS_PER_VERTEX) as u32;
        assert_eq!(i.len() % 3, 0);
        for &idx in &i {
            assert!(idx < count, "index {idx} past the {count} vertices");
        }
        for f in &v {
            assert!(f.is_finite(), "a non-finite terrain vertex");
        }
    }

    #[test]
    fn tessellation_appends_rather_than_replaces() {
        // Two tiles' grids can share a buffer, so a second call must rebase its indices past the
        // first's vertices.
        let hm = heightmap(3, |_, _| 0);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        let first_vertices = (v.len() / FLOATS_PER_VERTEX) as u32;
        let first_indices = i.len();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        assert!(
            i[first_indices..].iter().all(|&idx| idx >= first_vertices),
            "the second grid must rebase onto its own vertices",
        );
    }

    /// Smoothing over a planar ramp must not tilt the normals: a blur of a plane is the same
    /// plane, so every normal matches the analytic slope normal exactly.
    #[test]
    fn a_planar_field_keeps_identical_normals() {
        // z = 2c + 3r metres over a 7x7 grid: interior central differences recover the slope
        // exactly, blurred or not.
        let hm = heightmap(7, |c, r| 2 * c as i32 + 3 * r as i32);
        let ground = 1000.0;
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // dz/du = 2 m per texel = 2 * dim-1 steps over the tile, normalised by ground width.
        let step = 1.0 / 6.0;
        let ex = -2.0 / ground as f32 / step;
        let ey = -3.0 / ground as f32 / step;
        let len = (ex * ex + ey * ey + 1.0).sqrt();
        let want = [ex / len, ey / len, 1.0 / len];
        for k in 0..v.len() / FLOATS_PER_VERTEX {
            let (p, n) = vertex(&v, k);
            // Positions stay on the raw plane.
            let col = k % 7;
            let row = k / 7;
            let z = (2 * col as i32 + 3 * row as i32) as f32 / ground as f32;
            assert!((p[2] - z).abs() < 1e-6, "position {k} must stay exact, got {}", p[2]);
            for a in 0..3 {
                assert!(
                    (n[a] - want[a]).abs() < 1e-5,
                    "normal {k} must match the analytic slope normal, got {n:?} want {want:?}",
                );
            }
        }
    }

    /// The point of the blur: checkerboard DEM noise must tilt interior normals far less than
    /// the raw central differences would.
    #[test]
    fn a_noisy_field_gets_softer_normals() {
        // ±40 m checkerboard over a 9x9 grid: raw central differences see a ±80 m swing per
        // texel step; the 3×3 blur averages each interior texel with its opposite-phase
        // neighbours, which must shrink the off-vertical tilt.
        let dim = 9usize;
        let amp = 40i32;
        let hm = heightmap(dim as u16, |c, r| if (c + r) % 2 == 0 { amp } else { -amp });
        let ground = 1000.0;
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // Raw tilt the unblurred differences would produce, at an interior vertex: dz/du over
        // one step is 2*amp metres, normalised.
        let step = 1.0 / (dim as f32 - 1.0);
        let raw_tilt = 2.0 * amp as f32 / ground as f32 / step;
        // Interior vertex one step in from the corner: its kernel mixes blurred interior with
        // the verbatim border, so the tilt shrinks but stays nonzero — the softening case,
        // rather than a fully-interior vertex whose symmetric kernel would erase it outright.
        let (_, n) = vertex(&v, dim + 1);
        let tilt = (n[0] * n[0] + n[1] * n[1]).sqrt();
        let raw_off_vertical = raw_tilt / (raw_tilt * raw_tilt + 1.0).sqrt();
        assert!(
            tilt < 0.5 * raw_off_vertical,
            "blurred tilt {tilt} must be well under the raw {raw_off_vertical}",
        );
        assert!(n[2] > 0.5, "the normal must stay mostly upright, got {n:?}");
    }

    /// The border ring passes through the blur untouched — edge differences read the raw
    /// samples, so the neighbour tile (which stores its own copy of the shared edge) derives
    /// the same slopes and the seam is invisible by construction.
    #[test]
    fn border_texels_are_bit_identical_pre_post_blur() {
        let dim = 7usize;
        let hm = heightmap(dim as u16, |c, r| (c as i32 * 13 + r as i32 * 29 + 7) % 61 - 30);
        let ground = 800.0;
        let factor = 1.0 / ground;
        let mut raw = Vec::with_capacity(dim * dim);
        for row in 0..dim {
            for col in 0..dim {
                raw.push(
                    (Heightmap::metres(hm.sample(col as u16, row as u16).unwrap()) as f64 * factor)
                        as f32,
                );
            }
        }
        let blurred = smooth_heights(&raw, dim);
        for k in 0..raw.len() {
            let col = k % dim;
            let row = k / dim;
            let on_border = col == 0 || row == 0 || col == dim - 1 || row == dim - 1;
            if on_border {
                assert_eq!(
                    blurred[k].to_bits(),
                    raw[k].to_bits(),
                    "border texel ({col}, {row}) must pass through the blur untouched",
                );
            }
        }
        // And at least one interior texel must actually change, or the blur is a no-op.
        assert!(
            (1..dim - 1).any(|row| (1..dim - 1)
                .any(|col| blurred[row * dim + col].to_bits() != raw[row * dim + col].to_bits())),
            "the blur must move at least one interior texel",
        );
    }

    /// Positions and indices are the blur's non-goals: the smoothed field must never leak into
    /// either. A spiky field is the sharpest probe — raw spikes stay spiky in z, indices stay
    /// the untouched grid triangulation.
    #[test]
    fn positions_and_indices_are_untouched_by_smoothing() {
        let dim = 5u16;
        let peak_m = 300;
        let ground = 1200.0;
        let centre = dim / 2;
        let hm = heightmap(dim, |c, r| if c == centre && r == centre { peak_m } else { 0 });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // The peak vertex keeps its exact raw height — smoothing the position would sink it.
        let (peak, _) = vertex(&v, centre as usize * dim as usize + centre as usize);
        assert_eq!(
            peak[2].to_bits(),
            (peak_m as f32 / ground as f32).to_bits(),
            "the peak height must be the exact raw sample",
        );
        // The untouched grid indices: two triangles per cell, the same winding as before.
        let dim32 = dim as u32;
        let mut want = Vec::new();
        for row in 0..dim - 1 {
            for col in 0..dim - 1 {
                let a = row as u32 * dim32 + col as u32;
                want.extend_from_slice(&[a, a + 1, a + dim32 + 1, a, a + dim32 + 1, a + dim32]);
            }
        }
        assert_eq!(i, want, "indices must stay the plain grid triangulation");
    }
}
