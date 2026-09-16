//! The DEM-displaced ground grid for one tile.
use super::mesh::{EARTH_CIRCUMFERENCE_M, TerrainMesh};
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
    let Some(heightmap) = &tile.heightmap else { return TerrainMesh::default() };
    let mut vertices = Vec::new();
    let mut indices = Vec::new();
    terrain::tessellate(heightmap, ground_width_m, &mut vertices, &mut indices);
    TerrainMesh { vertices, indices }
}

/// Bilinearly sample a tile's ground elevation at tile-local `(u, v)` in 0..1, in metres
/// above sea level.
///
/// Reads [`Heightmap::metres`] (the stored sample minus the 32768 bias) at the four grid
/// samples around the point and blends them, so a building corner between DEM posts sits on
/// the slope between them rather than stepping to the nearest post. `u`/`v` are clamped to
/// `[0, 1]` and the grid edges replicate outward — the border terrain verts sit exactly on
/// the edge samples, so a building touching the tile edge meets the same ground its half of
/// the tile edge shows. Returns `None` when the tile carries no heightmap, or the map is a
/// degenerate (`dim < 2`) grid with no cell to interpolate inside.
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
        hm.sample(col as u16, row as u16).map_or(0.0, |s| Heightmap::metres(s) as f32)
    };
    let top = at(x0, y0) * (1.0 - fx) + at(x0 + 1, y0) * fx;
    let bottom = at(x0, y0 + 1) * (1.0 - fx) + at(x0 + 1, y0 + 1) * fx;
    Some(top * (1.0 - fy) + bottom * fy)
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
