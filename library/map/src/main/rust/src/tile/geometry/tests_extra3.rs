use super::build::{build, build_toggled};
use super::mesh::{LayerMesh, TileMesh, TRAFFIC_MIN_ZOOM};
use super::terrain::tile_ground_width_m;
use crate::style;
use crate::style::paint::Ramp;
use crate::style::{KindFilter, Layer, LayerKind, LayerToggles};
use crate::tess::{roof, terrain};
use tilecodec::mamaps::body::{Body, Feature, GEOM_LINE, GEOM_POLYGON, Layer as BodyLayer, NAME_NONE, Part, WINDING_OUTER};
use tilecodec::mamaps::dict;
use tilecodec::mamaps::dict::LAYER_TRAFFIC;

/// A representative v7 body: one `earth` polygon and one `water` polygon —
/// the same layers the old MVT fixture carried, built directly as a body so
/// the tests no longer depend on the MVT→body converter.
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
    earth.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
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
    water.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    water.coords = vec![(500, 3000), (1100, 3000), (1100, 3400), (500, 3400)];
    body.layers.push(water);
    body
}

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

// --- the live-traffic overlay (WS3) ------------------------------------

/// A body carrying `count` traffic component segments, ids from `ids` (one per feature,
/// `None` for a feature the id table attributes to nothing → [`ID_NONE`]). When `id_table`
/// is false the layer carries no id table at all, which is the other "no id" case.
fn traffic_body(ids: &[Option<u64>], id_table: bool) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, NAME_NONE, WINDING_OUTER,
    };
    let mut source = BodyLayer::new(LAYER_TRAFFIC);
    for (i, _) in ids.iter().enumerate() {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        let y = 100 + i as i16 * 10;
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: 0,
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
    }
    let table = if id_table {
        let vec: Vec<u64> = ids
            .iter()
            .map(|o| o.unwrap_or(tilecodec::mamaps::body::ID_NONE))
            .collect();
        vec![(LAYER_TRAFFIC, vec)]
    } else {
        Vec::new()
    };
    Body { extent: DEFAULT_EXTENT, layers: vec![source], names: Vec::new(), ids: table, turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None }
}

fn traffic_on() -> LayerToggles {
    LayerToggles { poi: false, transit: false, traffic: true }
}

/// One mesh per component segment, each carrying the segment's `component_id` from the id
/// side-table, in feature order. This is what lets the renderer key its pushed colour
/// table by id.
#[test]
fn traffic_is_one_mesh_per_component_carrying_its_id() {
    let ids = [Some(0x1234_0000_u64 | 5), Some(0x1234_0000 | 6)];
    let body = traffic_body(&ids, true);
    let mesh = build_toggled(&body, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert_eq!(
        mesh.traffic.iter().map(|t| t.id).collect::<Vec<_>>(),
        vec![0x1234_0000 | 5, 0x1234_0000 | 6],
        "one mesh per segment, id from the side-table, in feature order",
    );
    assert!(mesh.traffic.iter().all(|t| !t.indices.is_empty()), "every segment tessellates");
}

/// The overlay is gated at tessellation like the other optional layers: off means nothing
/// is built, so leaving it off costs nothing per frame.
#[test]
fn traffic_is_gated_off_unless_the_toggle_is_on() {
    let body = traffic_body(&[Some(1), Some(2)], true);
    let off = build(&body, &[], 14, 0, 0, false);
    assert!(off.traffic.is_empty(), "traffic off tessellates no segments");
    let on = build_toggled(&body, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert_eq!(on.traffic.len(), 2, "traffic on tessellates the segments");
}

/// Component lines are dense, so they are only built at or below the traffic floor even
/// when the toggle is on — the render-side half of WS2's archive zoom gate.
#[test]
fn traffic_is_gated_below_its_min_zoom() {
    let body = traffic_body(&[Some(1)], true);
    // deepest = z + ANCESTOR_DEPTH(4); at z0 that is 4, well below the floor.
    let coarse = build_toggled(&body, &[], 0, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert!(coarse.traffic.is_empty(), "a coarse tile builds no traffic");
    let deep = build_toggled(
        &body,
        &[],
        TRAFFIC_MIN_ZOOM,
        0,
        0,
        false,
        traffic_on(),
        &KindFilter::all(),
        1,
    );
    assert_eq!(deep.traffic.len(), 1, "a tile at the floor builds it");
}

/// A segment with no stable id — either the layer carries no id table, or its entry is
/// [`ID_NONE`] — is skipped: nothing could ever colour it, so drawing it would only repaint
/// a road the basemap already drew.
#[test]
fn a_traffic_segment_with_no_id_is_skipped() {
    let none_in_table = traffic_body(&[Some(7), None, Some(9)], true);
    let mesh =
        build_toggled(&none_in_table, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert_eq!(
        mesh.traffic.iter().map(|t| t.id).collect::<Vec<_>>(),
        vec![7, 9],
        "the ID_NONE segment is dropped, the others keep their ids",
    );

    let no_table = traffic_body(&[Some(7), Some(9)], false);
    let mesh =
        build_toggled(&no_table, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert!(mesh.traffic.is_empty(), "a layer with no id table colours nothing, so draws nothing");
}

/// Colour is never an input to traffic tessellation — the builder takes no colour at all —
/// so a new speed reading (which only replaces the renderer's id→colour table) can never
/// re-tessellate. Two builds of the same body produce byte-identical geometry, which is the
/// property the "recolour without re-tessellation" guarantee rests on: the geometry a
/// recolour would have to change simply does not depend on anything a recolour touches.
#[test]
fn recolouring_cannot_retessellate_because_colour_is_not_a_tessellation_input() {
    let body = traffic_body(&[Some(11), Some(22)], true);
    let a = build_toggled(&body, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    let b = build_toggled(&body, &[], 14, 0, 0, false, traffic_on(), &KindFilter::all(), 1);
    assert_eq!(a.traffic.len(), b.traffic.len());
    for (x, y) in a.traffic.iter().zip(&b.traffic) {
        assert_eq!(x.id, y.id);
        assert_eq!(x.vertices, y.vertices, "geometry is deterministic and colour-independent");
        assert_eq!(x.indices, y.indices);
    }
}

// --- 3D buildings (WS-A) -----------------------------------------------

/// A `buildings` body with one square footprint and the given attrs. `attrs` of `None` gives a
/// layer with no building side table at all — the "no attrs" case the reader handles.
fn building_body(attrs: Option<tilecodec::mamaps::body::BuildingAttrs>) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, NAME_NONE, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut source = BodyLayer::new(dict::LAYER_BUILDINGS);
    source.parts.push(Part { coord_start: 0, point_count: 5, winding: WINDING_OUTER });
    // A closed square footprint, roughly a quarter of the tile.
    source.coords.extend_from_slice(&[(0, 0), (1000, 0), (1000, 1000), (0, 1000), (0, 0)]);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("building"),
        kind_detail: 0,
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
    let buildings = match attrs {
        Some(a) => vec![(dict::LAYER_BUILDINGS, vec![a])],
        None => Vec::new(),
    };
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![source],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings,
        heightmap: None, carriageways: Vec::new(), convention: None,
    }
}

/// The height of the tallest building vertex, in tile-normalised units.
fn max_building_z(mesh: &TileMesh) -> f32 {
    mesh.buildings
        .vertices
        .chunks(roof::FLOATS_PER_VERTEX)
        .map(|c| c[2])
        .fold(0.0f32, f32::max)
}

#[test]
fn a_building_extrudes_into_a_3d_mesh_not_a_flat_fill() {
    use tilecodec::mamaps::body::BuildingAttrs;
    // A 30 m box (300 dm) at a mid-latitude tile (y = 8192 is the equator at z14).
    let body = building_body(Some(BuildingAttrs { height: 300, ..Default::default() }));
    let layers = style::layers();
    let mesh = build(&body, &layers, 14, 0, 8192, false);

    assert!(!mesh.buildings.indices.is_empty(), "the building must extrude");
    assert_eq!(mesh.buildings.indices.len() % 3, 0, "indices come in threes");
    assert_eq!(mesh.buildings.vertices.len() % roof::FLOATS_PER_VERTEX, 0, "vertices are whole");
    // Buildings draw in their own depth pass, so they must NOT also appear as a flat fill mesh.
    assert!(
        mesh_for(&mesh, &layers, "buildings").is_none(),
        "a building must not double up as a flat fill",
    );

    let zs: Vec<f32> =
        mesh.buildings.vertices.chunks(roof::FLOATS_PER_VERTEX).map(|c| c[2]).collect();
    assert!(zs.iter().any(|&z| z.abs() < 1e-6), "walls must start at the base");
    assert!(zs.iter().any(|&z| z > 0.0), "the box must extrude upward");
}

#[test]
fn a_taller_building_reaches_higher() {
    use tilecodec::mamaps::body::BuildingAttrs;
    let layers = style::layers();
    let short = build(
        &building_body(Some(BuildingAttrs { height: 200, ..Default::default() })),
        &layers,
        14,
        0,
        8192,
        false,
    );
    let tall = build(
        &building_body(Some(BuildingAttrs { height: 600, ..Default::default() })),
        &layers,
        14,
        0,
        8192,
        false,
    );
    // Triple the metric height, so the tile-normalised apex is ~3x — the heights really do come
    // from the side table rather than a constant.
    assert!(
        max_building_z(&tall) > max_building_z(&short) * 2.5,
        "a 3x taller building must extrude far higher: {} vs {}",
        max_building_z(&tall),
        max_building_z(&short),
    );
}

#[test]
fn a_building_with_no_side_table_still_extrudes_a_default_box() {
    // A layer with no building table reads back default attrs, which extrude at the default
    // height rather than nothing — an unattributed building is still a building.
    let mesh = build(&building_body(None), &style::layers(), 14, 0, 8192, false);
    assert!(!mesh.buildings.indices.is_empty(), "a default building still extrudes");
    assert!(max_building_z(&mesh) > 0.0, "the default box has a real height");
}

#[test]
fn buildings_are_not_extruded_far_below_their_zoom() {
    use tilecodec::mamaps::body::BuildingAttrs;
    // A coarse tile outside the z14 ancestor window carries no buildings at all, the same gate
    // every zoomed-in layer uses.
    let coarse = build(
        &building_body(Some(BuildingAttrs { height: 300, ..Default::default() })),
        &style::layers(),
        5,
        0,
        8192,
        false,
    );
    assert!(coarse.buildings.indices.is_empty(), "a z5 tile is far below the buildings zoom");
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
fn a_heightmap_tile_builds_terrain_and_drops_the_flat_earth_fill() {
    // A tile carrying a heightmap draws its ground as the displaced terrain grid, and its flat
    // `earth` fill is suppressed so the two do not double up — while the other flat layers
    // (water) still tessellate as before.
    let layers = style::layers();
    let mut body = real();
    body.heightmap = Some(heightmap(9, |c, r| (c as i32 + r as i32) * 20));
    let mesh = build(&body, &layers, 11, 339, 770, false);

    assert!(!mesh.terrain.indices.is_empty(), "the heightmap tile builds a terrain grid");
    assert_eq!(mesh.terrain.indices.len() % 3, 0, "terrain indices come in threes");
    assert_eq!(
        mesh.terrain.vertices.len() % terrain::FLOATS_PER_VERTEX,
        0,
        "terrain vertices are whole",
    );
    assert!(
        mesh_for(&mesh, &layers, "earth").is_none(),
        "the flat earth fill is replaced by the terrain grid",
    );
    assert!(mesh_for(&mesh, &layers, "water").is_some(), "water still draws flat over terrain");
}

#[test]
fn a_tile_without_a_heightmap_stays_flat() {
    // The no-DEM case (ocean, off-coverage): no terrain grid, and the flat earth fill remains
    // exactly as it always was.
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    assert!(mesh.terrain.indices.is_empty(), "a tile with no heightmap builds no terrain");
    assert!(mesh_for(&mesh, &layers, "earth").is_some(), "and keeps its flat earth fill");
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
    body.heightmap = Some(heightmap(5, |c, r| if c == 2 && r == 2 { peak_m } else { 0 }));
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

// --- curved labels along roads / rivers (WS-E) -------------------------

/// A symbol layer over the `roads` source with no kind filter, so it labels any named road
/// line — the render-side shape of the `roads-label` style layer the build carries names for.
fn roads_label_layer() -> Layer {
    Layer {
        id: "roads-label".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: LayerKind::Symbol,
        kinds: Vec::new(),
        kind_ids: Vec::new(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFF3B3B3B,
        dark: 0xFFEDEDED,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(0.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(12.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0xFFFFFFFF,
        halo_dark: 0xFF0D1B2A,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "roads_label".to_string(),
    }
}

/// A body with one named road line running along `pts` (extent units).
fn named_road_body(name: &str, pts: &[(i16, i16)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, DEFAULT_EXTENT, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    source.parts.push(Part {
        coord_start: 0,
        point_count: pts.len() as u32,
        winding: WINDING_OUTER,
    });
    source.coords.extend_from_slice(pts);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: 1,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    Body {
        extent: DEFAULT_EXTENT,
        layers: vec![source],
        names: vec![name.to_string()],
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None,
    }
}

#[test]
fn a_named_road_line_shapes_a_curved_label_along_its_centreline() {
    if !crate::tile::glyph::fonts_staged() {
        eprintln!("SKIP: staged TTFs are not fonts");
        return;
    }
    // A straight eastbound road spanning the tile.
    let body = named_road_body("Market Street", &[(200, 2048), (3800, 2048)]);
    let layers = vec![roads_label_layer()];
    let mesh = build(&body, &layers, 14, 0, 8192, false);

    assert_eq!(mesh.labels.len(), 1, "the named road shapes exactly one label");
    let label = &mesh.labels[0];
    assert_eq!(label.name, "Market Street");
    assert_eq!(label.layer_index, 0);
    let centreline = label.centreline.as_ref().expect("a road label is curved, not point");
    assert_eq!(centreline.len(), 2, "the whole feature centreline rides on the label");
    // Normalised into tile-local 0..1 from extent units, along the tile's mid-line.
    assert!(centreline.iter().all(|&(x, y)| (0.0..=1.0).contains(&x) && (y - 0.5).abs() < 1e-3));
    assert!(centreline[1].0 > centreline[0].0, "eastbound: x increases along the line");
}

#[test]
fn an_unnamed_road_line_shapes_no_label() {
    // Without a name there is nothing to lay along the line, so no curved label is produced —
    // the same silent skip the point path makes for an unnamed place.
    use tilecodec::mamaps::body::NAME_NONE;
    let mut body = named_road_body("ignored", &[(200, 2048), (3800, 2048)]);
    body.layers[0].features[0].name_idx = NAME_NONE;
    let mesh = build(&body, &vec![roads_label_layer()], 14, 0, 8192, false);
    assert!(mesh.labels.is_empty(), "an unnamed road line shapes nothing");
}
