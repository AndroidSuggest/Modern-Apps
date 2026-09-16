//! The DEM-displaced ground grid for one tile.
use super::mesh::{TerrainMesh, EARTH_CIRCUMFERENCE_M};
use crate::tess::terrain;
use tilecodec::mamaps::body::{Body, Heightmap};

/// The DEM-displaced ground grid for this tile, or an empty mesh when the tile carries no
/// heightmap.
///
/// Driven off the archive's per-tile heightmap rather than the style: the ground is one grid for
/// the whole tile, sampled at the DEM's own resolution, and normalised against `ground_width_m` so
/// a metre of relief reads the same on screen as a metre across — the same tile-local unit the
/// building heights use. A tile with no heightmap (open ocean, off-DEM coverage) returns an empty
/// mesh and keeps drawing its flat `earth` fill instead.
pub(crate) fn terrain_mesh(tile: &Body, ground_width_m: f64) -> TerrainMesh {
    let Some(heightmap) = &tile.heightmap else {
        return TerrainMesh::default();
    };
    let mut vertices = Vec::new();
    let mut indices = Vec::new();
    terrain::tessellate(heightmap, ground_width_m, &mut vertices, &mut indices);
    TerrainMesh { vertices, indices }
}

/// Bilinearly sample a tile's ground elevation at tile-local `(u, v)` in 0..1, in metres
/// above sea level.
///
/// Reads [`Heightmap::metres`] (the stored sample minus the 32768 bias) at the four grid
/// samples around the point and blends them along the rendered triangle plane, so a building
/// corner between DEM posts sits on the same surface the terrain grid draws rather than
/// stepping to the nearest post. `u`/`v` are clamped to `[0, 1]` and the grid edges
/// replicate outward — the border terrain verts sit exactly on the edge samples, so a
/// building touching the tile edge meets the same ground its half of the tile edge shows.
/// Returns `None` when the tile carries no heightmap, or the map is a degenerate
/// (`dim < 2`) grid with no cell to interpolate inside.
///
/// The blend follows the cell's diagonal fold exactly: each DEM cell renders as the two
/// triangles `[a, b, d]` and `[a, d, c]` (see `tess::terrain::tessellate`), and the sample
/// is the height of whichever triangle covers `(u, v)`. Plain bilinear would average the
/// four corners; on a saddle (opposite corners high, the other two low) the quad's centre
/// sags between the two triangle planes, so a bilinearly seated wall bottom would sit
/// below one fold and float above the other — buried on one side, daylighting on the
/// other. Sampling the triangle plane closes the datum gap against the surface drawn.
pub(crate) fn sample_ground_metres(heightmap: &Option<Heightmap>, u: f32, v: f32) -> Option<f32> {
    let hm = heightmap.as_ref()?;
    let dim = hm.dim as usize;
    if dim < 2 {
        return None;
    }
    let u = u.clamp(0.0, 1.0);
    let v = v.clamp(0.0, 1.0);
    // Grid position in sample units: the `dim` samples sit at 0, 1/(dim-1), ..., 1.
    let gx = u * (dim as f32 - 1.0);
    let gy = v * (dim as f32 - 1.0);
    let x0 = (gx.floor() as usize).min(dim - 2);
    let y0 = (gy.floor() as usize).min(dim - 2);
    let (fx, fy) = (gx - x0 as f32, gy - y0 as f32);
    let at = |col: usize, row: usize| -> f32 {
        hm.sample(col as u16, row as u16)
            .map_or(0.0, |s| Heightmap::metres(s) as f32)
    };
    // The cell renders as triangles `[a, b, d]` (top-left, top-right, bottom-right) and
    // `[a, d, c]` (top-left, bottom-right, bottom-left): the fold runs from `a` to `d`
    // along `fx == fy`. Sample the covering triangle's plane — an affine blend of its
    // three corners, exact against the drawn surface — rather than the bilinear average,
    // which sags between the two planes on a saddle (diagonal-fold burial).
    let (a, b, c, d) = (
        at(x0, y0),
        at(x0 + 1, y0),
        at(x0, y0 + 1),
        at(x0 + 1, y0 + 1),
    );
    if fx >= fy {
        // Triangle `[a, b, d]`: `(fx, fy) = a + (b - a) * s + (d - a) * t` with `s = fx - fy`
        // along the top edge and `t = fy` along the diagonal, so the fold endpoint `d`
        // is reached exactly at `(1, 1)`.
        Some(a + (b - a) * (fx - fy) + (d - a) * fy)
    } else {
        // Triangle `[a, d, c]`: `(fx, fy) = a + (d - a) * s + (c - a) * t` with `s = fx`
        // along the diagonal and `t = fy - fx` down the left edge, so the fold endpoint
        // `d` is reached exactly at `(1, 1)` from this side too — no seam along the fold.
        Some(a + (d - a) * fx + (c - a) * (fy - fx))
    }
}

/// Drape flat-layer vertices onto the relief: sample the tile's ground elevation under
/// each vertex's tile-local `(x, y)` and write it into the trailing `z` slot.
///
/// The tessellators emit `z = 0.0`, so this is a no-op without a heightmap — callers guard
/// with `tile.heightmap.is_some()` and skip the walk entirely, keeping no-DEM output
/// bit-identical. `z_index` is the trailing slot (2 for fill, 7 for stroke, 6 for ribbon).
/// The lift is a sub-metre tile-normalised epsilon (`0.5 m / ground_width_m`) so the
/// depth-off flat layers paint visibly above the depth-tested terrain grid under tilt
/// with no depth fight; at pitch 0 the z is ignored for x/y.
///
/// Same units as the buildings: metres over this tile's `ground_width_m`, via the same
/// `factor` normalisation `buildings.rs` uses, so a draped road meets a building's base
/// at the same height. Below-sea ground stays negative, matching the buildings.
pub(crate) fn drape_vertices(
    vertices: &mut [f32],
    floats_per_vertex: usize,
    z_index: usize,
    heightmap: &Option<Heightmap>,
    ground_width_m: f64,
) {
    let factor = if ground_width_m > 0.0 {
        1.0 / ground_width_m
    } else {
        0.0
    };
    let lift = (0.5 / ground_width_m.max(f64::MIN_POSITIVE)) as f32;
    for c in vertices.chunks_exact_mut(floats_per_vertex) {
        // Without a sample the tessellator's z stands untouched — in particular the guarded
        // callers' no-DEM output is bit-identical, and even an unguarded call moves nothing.
        if let Some(m) = sample_ground_metres(heightmap, c[0], c[1]) {
            c[z_index] = (m as f64 * factor) as f32 + lift;
        }
    }
}

/// Metres of ground the tile spans east–west at its centre latitude — the horizontal unit the
/// building heights are normalised against, so a metre up reads the same on screen as a metre
/// across. Web Mercator, matching the projection the archive was cut with.
pub(crate) fn tile_ground_width_m(z: u8, y: u32) -> f64 {
    let scale = 2f64.powi(z as i32);
    let n = std::f64::consts::PI - 2.0 * std::f64::consts::PI * (y as f64 + 0.5) / scale;
    let lat = n.sinh().atan();
    EARTH_CIRCUMFERENCE_M * lat.cos() / scale
}
