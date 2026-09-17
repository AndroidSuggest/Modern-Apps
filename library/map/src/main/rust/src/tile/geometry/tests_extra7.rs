use super::build::build;
use super::mesh::{LayerMesh, TileMesh};
use super::terrain::tile_ground_width_m;
use crate::style;
use crate::style::Layer;
use crate::tess::terrain;
use tilecodec::mamaps::body::{
    Body, Feature, Layer as BodyLayer, Part, GEOM_POLYGON, NAME_NONE, WINDING_OUTER,
};
use tilecodec::mamaps::dict;

/// A representative v7 body: one `earth` polygon and one `water` polygon —
/// the same layers the old MVT fixture carried, built directly as a body so
/// the tests no longer depend on the MVT→body converter.
/// (Duplicated from `tests_extra3`: sibling `#[cfg(test)]` modules cannot
/// see each other's private items.)
fn real() -> Body {
    let mut body = Body::new(4096);
    let mut earth = BodyLayer::new(dict::LAYER_EARTH);
    earth.features.push(Feature {
        kind: 1,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    earth.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    earth.coords = vec![(0, 0), (4096, 0), (4096, 4096), (0, 4096)];
    body.layers.push(earth);
    let mut water = BodyLayer::new(dict::LAYER_WATER);
    water.features.push(Feature {
        kind: 4,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    water.parts.push(Part {
        coord_start: 0,
        point_count: 4,
        winding: WINDING_OUTER,
    });
    water.coords = vec![(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)];
    body.layers.push(water);
    body
}

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

// --- 3D terrain relief (WS-G) ------------------------------------------

/// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the format
/// stores.
fn heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> tilecodec::mamaps::body::Heightmap {
    let mut samples = Vec::with_capacity((dim as usize).pow(2));
    for row in 0..dim {
        for col in 0..dim {
            samples.push((metres(col, row) + 32768) as u16);
        }
    }
    tilecodec::mamaps::body::Heightmap { dim, samples }
}

#[test]
fn a_heightmap_tile_builds_terrain_and_keeps_the_flat_earth_fill() {
    // A tile carrying a heightmap draws its ground as the displaced terrain grid, and its
    // flat `earth` fill is kept as backstop: the terrain draws first depth-tested and the
    // flat fill paints over its cracks via depth-off layer order, so gaps show earth
    // colour rather than the water-blue clear colour. Water still tessellates as before.
    let layers = style::layers();
    let mut body = real();
    body.heightmap = Some(heightmap(9, |c, r| (c as i32 + r as i32) * 20));
    let mesh = build(&body, &layers, 11, 339, 770, false);

    assert!(
        !mesh.terrain.indices.is_empty(),
        "the heightmap tile builds a terrain grid"
    );
    assert_eq!(
        mesh.terrain.indices.len() % 3,
        0,
        "terrain indices come in threes"
    );
    assert_eq!(
        mesh.terrain.vertices.len() % terrain::FLOATS_PER_VERTEX,
        0,
        "terrain vertices are whole",
    );
    assert!(
        mesh_for(&mesh, &layers, "earth").is_some(),
        "the flat earth fill stays as backstop under the terrain grid",
    );
    assert!(
        mesh_for(&mesh, &layers, "water").is_some(),
        "water still draws flat over terrain"
    );
}

#[test]
fn a_tile_without_a_heightmap_stays_flat() {
    // The no-DEM case (ocean, off-coverage): no terrain grid, and the flat earth fill remains
    // exactly as it always was.
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    assert!(
        mesh.terrain.indices.is_empty(),
        "a tile with no heightmap builds no terrain"
    );
    assert!(
        mesh_for(&mesh, &layers, "earth").is_some(),
        "and keeps its flat earth fill"
    );
}

#[test]
fn terrain_height_is_normalised_from_the_dem() {
    // The displaced z is metres / the tile's ground width — the same tile-local unit buildings
    // use — so the grid is zoom-independent and a hill of a known height lands where expected.
    let layers = style::layers();
    let (z, y) = (11u8, 770u32);
    let peak_m = 500;
    let mut body = real();
    // Flat except one central sample, so the peak vertex is unambiguous.
    body.heightmap = Some(heightmap(
        5,
        |c, r| if c == 2 && r == 2 { peak_m } else { 0 },
    ));
    let mesh = build(&body, &layers, z, 339, y, false);

    let ground = tile_ground_width_m(z, y);
    let max_z = mesh
        .terrain
        .vertices
        .chunks(terrain::FLOATS_PER_VERTEX)
        .map(|c| c[2])
        .fold(f32::MIN, f32::max);
    assert!(
        (max_z - peak_m as f32 / ground as f32).abs() < 1e-4,
        "the peak rises to metres/ground_width: {} vs {}",
        max_z,
        peak_m as f32 / ground as f32,
    );
}
