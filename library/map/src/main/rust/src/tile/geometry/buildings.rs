//! Extrude building footprints into the tile-local 3D building mesh.
//!
//! Flat 2D layers (fills, strokes) deliberately stay at z=0 over relief: draping every road
//! and boundary over the DEM would cost a heightmap sample per vertex for geometry the pitch-0
//! map draws identically, so only buildings — the one layer with real height — close the datum
//! gap against the terrain. Route and puck overlays are unaffected: they draw above the
//! basemap in their own passes, not from this mesh.
use super::mesh::DEFAULT_BUILDING_HEIGHT_M;
use super::terrain::sample_ground_metres;
use crate::tess::roof;
use tilecodec::mamaps::body::Body;
use tilecodec::mamaps::dict::LAYER_BUILDINGS;

/// Extrude one `buildings` feature into walls and a roof, appending to the tile's building mesh.
///
/// The metric heights in the side table are normalised against `ground_width_m` — the tile's own
/// ground width — so the extruded height rides in the same tile-local unit the footprint does and
/// A feature with no attrs (or a layer with no building table) reads back a default
/// [`BuildingAttrs`], which extrudes as a flat box at [`DEFAULT_BUILDING_HEIGHT_M`].
///
/// The walls and roof ride the tile's heightmap where it has one: every emitted `z` is its
/// structural height plus the ground offset sampled under that vertex, so a building on a
/// hillside sits on the slope instead of sinking into it or floating over the valley. The
/// offset is tile-normalised with the same `factor` as the heights (metres over this tile's
/// `ground_width_m`), and an overzoomed ancestor reuses the shared width unchanged — the two
/// stay in the same unit by construction. With no heightmap the offset is 0.0 everywhere and
/// the mesh is bit-identical to the un-lifted one; below-sea ground stays negative, so a
/// building in a depression sits below z=0 rather than clamped to it.
///
/// A wall or roof the archive gives no colour keeps **0** — a transparent black that no real colour
/// can collide with, since the side table already spells "absent" that way. `building.frag` reads
/// the zero alpha as "use the palette's building colour", which arrives as a push constant. The
/// style colour is deliberately *not* baked in here: vertex colour is fixed at tessellation time
/// and the palette is not reachable from this path, so baking it would mean re-tessellating every
/// building in the resident set on a light/dark switch.
#[allow(clippy::too_many_arguments)]
pub(crate) fn extrude_building(
    tile: &Body,
    feature_index: usize,
    rings: &[Vec<(i32, i32)>],
    extent: u32,
    validated: bool,
    ground_width_m: f64,
    out_v: &mut Vec<f32>,
    out_i: &mut Vec<u32>,
) {
    let attrs = tile.building_attrs(LAYER_BUILDINGS, feature_index).unwrap_or_default();
    // Tile-normalised height per metre; a degenerate (polar) tile with zero width flattens rather
    // than dividing by zero.
    let factor = if ground_width_m > 0.0 { 1.0 / ground_width_m } else { 0.0 };
    // Decimetres on the wire, metres here. An absent height (0) extrudes to the default so an
    // untagged building is still a box.
    let height_m =
        if attrs.height != 0 { attrs.height as f64 / 10.0 } else { DEFAULT_BUILDING_HEIGHT_M };
    let min_m = attrs.min_height as f64 / 10.0;
    // The roof lives inside the total height, so it can never be taller than the building.
    let roof_m = (attrs.roof_height as f64 / 10.0).min(height_m);
    let base = (min_m * factor) as f32;
    let apex = (height_m * factor) as f32;
    let wall_top = ((height_m - roof_m) * factor) as f32;
    // `roof_direction` is quantised over a full turn: `v * 360 / 256` degrees, i.e. `v / 256` of a
    // turn in radians.
    let roof_dir = attrs.roof_direction as f64 / 256.0 * std::f64::consts::TAU;
    let wall_colour = attrs.building_colour;
    let roof_colour =
        if attrs.roof_colour != 0 { attrs.roof_colour } else { attrs.building_colour };
    // The ground under each footprint corner, tile-normalised with the same factor as the
    // heights. Sampled per vertex inside the extruder so walls follow the slope; `None` is a
    // flat 0.0, which keeps the pitch-0 output byte-identical where the tile has no DEM.
    let heightmap = &tile.heightmap;
    let ground = |u: f32, v: f32| -> f32 {
        sample_ground_metres(heightmap, u, v).map_or(0.0, |m| (m as f64 * factor) as f32)
    };
    roof::extrude(
        rings,
        extent,
        validated,
        base,
        wall_top,
        apex,
        attrs.roof_shape,
        roof_dir as f32,
        attrs.roof_orientation,
        wall_colour,
        roof_colour,
        &ground,
        out_v,
        out_i,
    );
}
