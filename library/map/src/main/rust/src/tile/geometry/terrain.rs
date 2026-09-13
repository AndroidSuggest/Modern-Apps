//! The DEM-displaced ground grid for one tile.
use super::mesh::{EARTH_CIRCUMFERENCE_M, TerrainMesh};
use crate::tess::terrain;
use tilecodec::mamaps::body::Body;

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

/// Metres of ground the tile spans east–west at its centre latitude — the horizontal unit the
/// building heights are normalised against, so a metre up reads the same on screen as a metre
/// across. Web Mercator, matching the projection the archive was cut with.
pub(crate) fn tile_ground_width_m(z: u8, y: u32) -> f64 {
    let scale = 2f64.powi(z as i32);
    let n = std::f64::consts::PI - 2.0 * std::f64::consts::PI * (y as f64 + 0.5) / scale;
    let lat = n.sinh().atan();
    EARTH_CIRCUMFERENCE_M * lat.cos() / scale
}
