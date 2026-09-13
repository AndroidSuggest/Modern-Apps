use super::geom::{face_normal, orient, push_tri};
use super::obb::OrientedBox;
use super::super::fill;
use tilecodec::mamaps::body::{
    ROOF_DOME, ROOF_GABLED, ROOF_HIPPED, ROOF_PYRAMIDAL, ROOF_SKILLION,
};

/// The grid resolution a dome is tessellated at, per axis of the oriented box. Eight keeps the
/// dome round without drowning a dense z16 tile in triangles.
const DOME_STEPS: usize = 8;

/// The roof cap: a flat lid for a flat/unknown shape (the true footprint, holes and all), or a
/// pitched surface over the footprint's oriented box for the five modelled shapes.
#[allow(clippy::too_many_arguments)]
pub(crate) fn emit_roof(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    exterior: &[(f32, f32)],
    wall_top: f32,
    apex: f32,
    roof_shape: u8,
    roof_dir_rad: f32,
    roof_orientation: u8,
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let rise = apex - wall_top;
    let pitched =
        matches!(roof_shape, ROOF_GABLED | ROOF_HIPPED | ROOF_PYRAMIDAL | ROOF_SKILLION | ROOF_DOME);
    if !pitched || rise <= 0.0 {
        flat_cap(rings, extent, validated, apex, rgba, out_v, out_i);
        return;
    }

    let obb = OrientedBox::of(exterior, roof_dir_rad, roof_orientation);
    // The roof's own centroid, at mid-roof height, used to orient every face outward.
    let (ccx, ccy) = obb.point(0.5, 0.5);
    let centre = [ccx, ccy, (wall_top + apex) * 0.5];
    let e = wall_top;
    let a = apex;
    // `(cu, av)` in the oriented box (`cu` across the slopes, `av` along the ridge) to a 3D point.
    let p = |cu: f32, av: f32, z: f32| -> [f32; 3] {
        let (x, y) = obb.point(cu, av);
        [x, y, z]
    };

    match roof_shape {
        ROOF_SKILLION => {
            // A mono-pitch slope: the `cu == 0` edge stays at the eaves, the `cu == 1` edge rises.
            roof_quad(p(0.0, 0.0, e), p(1.0, 0.0, a), p(1.0, 1.0, a), p(0.0, 1.0, e), centre, rgba, out_v, out_i);
        }
        ROOF_GABLED => {
            let (ridge0, ridge1) = (p(0.5, 0.0, a), p(0.5, 1.0, a));
            // Two slopes down to the long edges.
            roof_quad(p(0.0, 0.0, e), ridge0, ridge1, p(0.0, 1.0, e), centre, rgba, out_v, out_i);
            roof_quad(ridge0, p(1.0, 0.0, e), p(1.0, 1.0, e), ridge1, centre, rgba, out_v, out_i);
            // The two vertical gable ends.
            roof_tri(p(0.0, 0.0, e), p(1.0, 0.0, e), ridge0, centre, rgba, out_v, out_i);
            roof_tri(p(0.0, 1.0, e), ridge1, p(1.0, 1.0, e), centre, rgba, out_v, out_i);
        }
        ROOF_HIPPED => {
            // The ridge is inset from both ends; a square building hips to a point (a pyramid).
            let t = 0.5 * (obb.cross_span / obb.along_span).min(1.0);
            let (ridge0, ridge1) = (p(0.5, t, a), p(0.5, 1.0 - t, a));
            let (e00, e10) = (p(0.0, 0.0, e), p(1.0, 0.0, e));
            let (e01, e11) = (p(0.0, 1.0, e), p(1.0, 1.0, e));
            // Two long slopes.
            roof_quad(e00, e01, ridge1, ridge0, centre, rgba, out_v, out_i);
            roof_quad(e10, ridge0, ridge1, e11, centre, rgba, out_v, out_i);
            // Two hip-end triangles.
            roof_tri(e00, ridge0, e10, centre, rgba, out_v, out_i);
            roof_tri(e01, e11, ridge1, centre, rgba, out_v, out_i);
        }
        ROOF_PYRAMIDAL => {
            let top = p(0.5, 0.5, a);
            let (c00, c10) = (p(0.0, 0.0, e), p(1.0, 0.0, e));
            let (c11, c01) = (p(1.0, 1.0, e), p(0.0, 1.0, e));
            roof_tri(c00, c10, top, centre, rgba, out_v, out_i);
            roof_tri(c10, c11, top, centre, rgba, out_v, out_i);
            roof_tri(c11, c01, top, centre, rgba, out_v, out_i);
            roof_tri(c01, c00, top, centre, rgba, out_v, out_i);
        }
        ROOF_DOME => {
            let n = DOME_STEPS as f32;
            let dome = |cu: f32, av: f32| -> [f32; 3] {
                let r2 = (2.0 * cu - 1.0).powi(2) + (2.0 * av - 1.0).powi(2);
                p(cu, av, e + rise * (1.0 - r2).max(0.0).sqrt())
            };
            for i in 0..DOME_STEPS {
                for j in 0..DOME_STEPS {
                    let (cu0, cu1) = (i as f32 / n, (i + 1) as f32 / n);
                    let (av0, av1) = (j as f32 / n, (j + 1) as f32 / n);
                    roof_quad(dome(cu0, av0), dome(cu1, av0), dome(cu1, av1), dome(cu0, av1), centre, rgba, out_v, out_i);
                }
            }
        }
        _ => unreachable!("non-pitched shapes take the flat cap above"),
    }
}

/// A flat roof lid: the footprint tessellated at the apex, every face pointing straight up.
pub(crate) fn flat_cap(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    apex: f32,
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let mut cap_xy: Vec<f32> = Vec::new();
    let mut cap_idx: Vec<u32> = Vec::new();
    fill::tessellate(rings, extent, validated, &mut cap_xy, &mut cap_idx);
    for tri in cap_idx.chunks_exact(3) {
        let mut pts = [[0.0f32; 3]; 3];
        for (corner, &vi) in tri.iter().enumerate() {
            let at = vi as usize * fill::FLOATS_PER_VERTEX;
            pts[corner] = [cap_xy[at], cap_xy[at + 1], apex];
        }
        push_tri(out_v, out_i, pts[0], pts[1], pts[2], [0.0, 0.0, 1.0], rgba);
    }
}

/// One roof triangle, its normal oriented outward from the roof centre.
#[allow(clippy::too_many_arguments)]
pub(crate) fn roof_tri(
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    centre: [f32; 3],
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let face = [(a[0] + b[0] + c[0]) / 3.0, (a[1] + b[1] + c[1]) / 3.0, (a[2] + b[2] + c[2]) / 3.0];
    let normal = orient(face_normal(a, b, c), face, centre);
    push_tri(out_v, out_i, a, b, c, normal, rgba);
}

/// One roof quad `a, b, c, d` as two triangles sharing the quad's normal.
#[allow(clippy::too_many_arguments)]
pub(crate) fn roof_quad(
    a: [f32; 3],
    b: [f32; 3],
    c: [f32; 3],
    d: [f32; 3],
    centre: [f32; 3],
    rgba: u32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    roof_tri(a, b, c, centre, rgba, out_v, out_i);
    roof_tri(a, c, d, centre, rgba, out_v, out_i);
}
