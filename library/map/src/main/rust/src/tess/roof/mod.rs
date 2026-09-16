//! Extrudes a building footprint into a 3D mesh: walls plus a roof cap.
//!
//! WS-A (OSM Simple 3D Buildings). Render-time only — the archive carries no triangles by
//! design, only per-feature [`BuildingAttrs`](tilecodec::mamaps::body::BuildingAttrs) in a side
//! table (heights, a roof-shape enum, colours). This module turns one footprint plus its attrs
//! into the vertex/index pair the building pipeline draws.
//!
//! # The 3D building vertex
//!
//! Seven floats — unlike the position-only fill vertex — because buildings are the one layer
//! that needs per-vertex depth, a shading normal, and its own colour:
//!
//! | offset | floats | meaning                                                        |
//! |--------|--------|----------------------------------------------------------------|
//! | 0..12  | 3      | tile-local `(u, v, height)`; `u,v` in 0..1, `height` tile-norm  |
//! | 12..24 | 3      | face normal in the same tile-local space, unit length          |
//! | 24..28 | 1      | ARGB colour packed as `R8G8B8A8_UNORM` bytes `[r, g, b, a]`     |
//!
//! `height` is **tile-normalised** — a fraction of the tile's ground width, the same unit `u`/`v`
//! are — so the mesh is zoom-independent like every other tile mesh. The building vertex shader
//! multiplies it by the tile's world-px span (a per-frame push scalar) to recover the real world-px
//! height the WS0 perspective matrix expects in its `z` input. At pitch 0 that matrix ignores `z`
//! for `x`/`y`, so a building drawn from directly overhead collapses to its footprint — the flat
//! map the app has always shown.
//!
//! # Roof shapes
//!
//! A flat roof (the common case, and the fallback for any shape this build does not model) is the
//! footprint itself, tessellated flat at the apex — so a courtyard building keeps the hole its
//! walls surround. The five pitched shapes (gabled, hipped, pyramidal, skillion, dome) are built
//! over the footprint's **oriented bounding box**, aligned to the roof direction: exact for the
//! rectangular buildings that dominate OSM, and a clean approximation for the rest, where the walls
//! still follow the true footprint.
//!
//! # Flat per-face shading
//!
//! Each triangle carries its own face normal on all three vertices (walls and roof faces are not
//! shared, so the buffer is deliberately un-indexed-sharing). The fragment shader lights that
//! constant normal against one fixed direction, so every face reads as a single flat tone with no
//! shadows — the S3DB look, and cheap.

mod cap;
mod geom;
mod obb;
mod walls;

#[cfg(test)]
mod tests;

use self::cap::emit_roof;
use self::walls::emit_walls;

/// Floats per building vertex: `x, y, z, nx, ny, nz, colour`.
pub const FLOATS_PER_VERTEX: usize = 7;

/// Pack an `0xAARRGGBB` colour into the `R8G8B8A8_UNORM` byte order the vertex attribute reads:
/// the four little-endian bytes of the returned `u32` are `[r, g, b, a]`, so an `f32::from_bits`
/// round-trips it into the vertex buffer unchanged (the bytes are never read as a float).
pub fn pack_argb(argb: u32) -> u32 {
    let a = (argb >> 24) & 0xFF;
    let r = (argb >> 16) & 0xFF;
    let g = (argb >> 8) & 0xFF;
    let b = argb & 0xFF;
    r | (g << 8) | (b << 16) | (a << 24)
}

/// Extrude one footprint into walls plus a roof cap, appending to `out_v`/`out_i`.
///
/// `rings` are the footprint's rings as the decoder returns them — exterior first, then holes,
/// each closed — in tile integer coordinates over `extent`. `validated` is the archive's
/// rings-normalised flag, forwarded to the fill tessellator that builds a flat roof cap.
///
/// Heights are tile-normalised (see the module docs): `base` is where the walls start
/// (`min_height`), `wall_top` where they meet the eaves (`height - roof_height`), and `apex` the
/// roof's own top (`height`). A flat roof has `wall_top == apex`.
///
/// `ground` is the tile-normalised ground offset at a tile-local `(u, v)`: every emitted `z`
/// is its structural height *plus* `ground(u, v)`, so walls keep their height while following
/// the slope and the roof drapes over the hill (a skillion's rise, a gable's ridge, a dome's
/// crown all ride parallel to the ground beneath). A flat roof samples the ground at each of
/// its tessellated vertices and follows the slope too — the alternative, a level lid at one
/// corner's height, would clip through the uphill ground. The closure comes from
/// [`crate::tile::geometry`] sampling the tile's heightmap; where the tile carries none it
/// returns 0.0, and the mesh is bit-identical to the un-lifted one.
///
/// `roof_dir_rad` is the roof direction in radians and `roof_orientation` selects which axis the
/// ridge runs along. An unrecognised `roof_shape` falls back to flat, never panics.
#[allow(clippy::too_many_arguments)]
pub fn extrude(
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    base: f32,
    wall_top: f32,
    apex: f32,
    roof_shape: u8,
    roof_dir_rad: f32,
    roof_orientation: u8,
    wall_colour: u32,
    roof_colour: u32,
    ground: &dyn Fn(f32, f32) -> f32,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    // Open, tile-local rings (0..1), the closing duplicate dropped, for the walls and the roof
    // orientation box. Degenerate rings are skipped so a stray part cannot emit a wall.
    let scale = 1.0 / extent as f32;
    let local: Vec<Vec<(f32, f32)>> = rings
        .iter()
        .map(|ring| {
            let mut n = ring.len();
            while n >= 2 && ring[0] == ring[n - 1] {
                n -= 1;
            }
            ring[..n].iter().map(|&(x, y)| (x as f32 * scale, y as f32 * scale)).collect()
        })
        .filter(|r: &Vec<(f32, f32)>| r.len() >= 3)
        .collect();
    if local.is_empty() {
        return;
    }

    let wall_top = wall_top.max(base);
    let apex = apex.max(wall_top);
    let wall_rgba = pack_argb(wall_colour);
    let roof_rgba = pack_argb(roof_colour);

    emit_walls(&local, base, wall_top, wall_rgba, ground, out_v, out_i);
    emit_roof(rings, extent, validated, &local[0], wall_top, apex, roof_shape, roof_dir_rad, roof_orientation, roof_rgba, ground, out_v, out_i);
}
