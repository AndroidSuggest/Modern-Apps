use super::build::{build, build_toggled};
use super::mesh::{CarriagewayMesh, LayerMesh, TileMesh, ROAD_LANE_MIN_ZOOM, TRAFFIC_MIN_ZOOM};
use super::terrain::tile_ground_width_m;
use crate::style::{KindFilter, Layer, LayerKind, LayerToggles};
use crate::style::paint::Ramp;
use crate::style;
use crate::tess::{fill, ribbon, roof, stroke, terrain};
use crate::tile::select::ANCESTOR_DEPTH;
use tilecodec::mamaps::body::{Body, GEOM_LINE, GEOM_POLYGON};
use tilecodec::mamaps::dict::{LAYER_JUNCTION, LAYER_TRAFFIC};

const REAL_TILE: &[u8] = include_bytes!("../../../tests/fixtures/v5ca_z11_tile.mvt");

/// The published tile, converted to a `.mamaps` body.
///
/// The fixture is still MVT because it was lifted out of the published archive with a ranged
/// GET, and there is no published `.mamaps` archive yet. Going through `from_mvt` is what
/// Phase 4 of the plan is: the container and this module are validated on data the tiler
/// already produced and the app already drew, before any tag→kind schema work exists to be
/// wrong.
fn real() -> Body {
    let tile = tilecodec::mvt::Tile::decode(REAL_TILE).expect("the published tile decodes");
    tilecodec::mamaps::from_mvt::from_tile(&tile).expect("converts").0
}

fn mesh_for<'a>(mesh: &'a TileMesh, layers: &[Layer], id: &str) -> Option<&'a LayerMesh> {
    mesh.meshes.iter().find(|m| layers[m.layer_index].id == id)
}

#[test]
fn the_real_tile_produces_geometry_for_the_layers_it_has_data_in() {
    // The published tile carries one `earth` polygon, one `roads` LineString of
    // kind = major_road, and two `water` polygons.
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);

    assert!(mesh_for(&mesh, &layers, "earth").is_some(), "the earth polygon tessellates");
    assert!(mesh_for(&mesh, &layers, "water").is_some(), "both water polygons tessellate");
    assert!(mesh_for(&mesh, &layers, "roads-major").is_some(), "the major_road strokes");
    assert!(mesh_for(&mesh, &layers, "roads-major-casing").is_some(), "and so does its casing");

    // Layers the tile has no data for produce no mesh at all, rather than an empty
    // one that would still cost a draw.
    assert!(mesh_for(&mesh, &layers, "buildings").is_none(), "no buildings layer here");
    assert!(mesh_for(&mesh, &layers, "roads-highway").is_none(), "the road is a major_road");
    assert!(
        mesh_for(&mesh, &layers, "landuse_park:national_park").is_none(),
        "no landuse layer",
    );
}

#[test]
fn an_ancestor_carries_the_layers_it_will_stand_in_for() {
    // A tile is displayed as a stand-in ancestor up to ANCESTOR_DEPTH levels below its
    // own zoom, so tessellation has to cover that whole window. Gating on the tile's own
    // zoom instead means an ancestor holds no geometry for any layer whose `min_zoom` is
    // deeper than it, and those layers appear only once the exact-zoom tiles arrive.
    //
    // The layer is synthetic so the test states its own `min_zoom` of 9. It used to
    // borrow `roads-major`'s, which quietly tied this to the style table — and road
    // layers are gated by their width ramp now, so that number is gone.
    let layers = vec![major_road_at_min_zoom(9)];

    // z6 is within reach of z9 (6 + 4 = 10), so the road is tessellated ready for the
    // camera to descend onto it. The old tile-zoom gate dropped it here.
    let reaching = build(&real(), &layers, 6, 339, 770, false);
    assert!(
        mesh_for(&reaching, &layers, "roads-major").is_some(),
        "a z6 ancestor must carry the roads it will stand in for at z9",
    );

    // z4 is not (4 + 4 = 8 < 9), so the window stays bounded and this is not simply
    // tessellating everything at every zoom.
    let out_of_reach = build(&real(), &layers, 4, 339, 770, false);
    assert!(
        mesh_for(&out_of_reach, &layers, "roads-major").is_none(),
        "the window must stay bounded, or every tile pays for every layer",
    );
}

/// A line layer matching the published fixture's `major_road`, gated at `min_zoom`.
fn major_road_at_min_zoom(min_zoom: u8) -> Layer {
    use crate::style::paint::Ramp;
    Layer {
        id: "roads-major".to_string(),
        source_layer: "roads".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_ROADS,
        kind: crate::style::LayerKind::Line,
        kinds: vec!["major_road".to_string()],
        kind_ids: vec![crate::style::kind_id_for_test("major_road")],
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFFFFFFFF,
        dark: 0xFF000000,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(1.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(0.0),
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
        halo_dark: 0xFF000000,
        halo_width: 0.0,
        min_zoom,
        browse_min_zoom: min_zoom,
        max_zoom: 22,
        authored: "roads_major".to_string(),
    }
}

#[test]
fn every_mesh_is_well_formed() {
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    assert!(!mesh.meshes.is_empty());
    for m in &mesh.meshes {
        let id = &layers[m.layer_index].id;
        let stride = match m.kind {
            LayerKind::Fill => fill::FLOATS_PER_VERTEX,
            LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
        };
        assert_eq!(m.vertices.len() % stride, 0, "{id} vertices are whole");
        assert_eq!(m.indices.len() % 3, 0, "{id} indices come in threes");
        assert!(!m.indices.is_empty(), "{id} has triangles");

        let vertex_count = (m.vertices.len() / stride) as u32;
        for &i in &m.indices {
            assert!(i < vertex_count, "{id} index {i} is out of range");
        }
        for f in &m.vertices {
            assert!(f.is_finite(), "{id} has a non-finite vertex");
        }
    }
}

#[test]
fn tessellated_output_is_within_the_bounds_the_shaders_assume() {
    // Tight, unlike the loose -3..4 this replaced. The vertex shaders assume
    // tile-local 0..1 positions, unit-length normals, and a distance-along-line that
    // is also tile-local — `line.vert` multiplies it by the tile's pixel size to get
    // pixels for the dash pattern. A violation of any of those renders a recognisable
    // map that is badly wrong, which is exactly the failure this pins down.
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    assert!(!mesh.meshes.is_empty());

    let mut worst_pos = 0.0f32;
    let mut worst_normal = 0.0f32;
    let mut worst_distance = 0.0f32;
    for m in &mesh.meshes {
        match m.kind {
            LayerKind::Fill => {
                for chunk in m.vertices.chunks(fill::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                }
            }
            LayerKind::Line => {
                for chunk in m.vertices.chunks(stroke::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                    let length = (chunk[2] * chunk[2] + chunk[3] * chunk[3]).sqrt();
                    // A miter normal is deliberately *longer* than unit, by
                    // 1/cos(theta/2), so both segments' edges meet on it — the same
                    // trick MapLibre uses with its "special" normals of up to length
                    // 126/63 = 2. So the bound is the miter limit, not 1.
                    worst_normal = worst_normal.max(length);
                    worst_distance = worst_distance.max(chunk[6].abs());
                }
            }
            LayerKind::Symbol => {
                // Symbol quads carry (x, y, u, v): positions are tile-local like
                // fills, UVs are atlas 0..1 — checked by tess::text's own tests.
                for chunk in m.vertices.chunks(crate::tile::symbol::FLOATS_PER_VERTEX) {
                    worst_pos = worst_pos.max(chunk[0].abs()).max(chunk[1].abs());
                }
            }
        }
    }

    // Protomaps buffers tiles by a few percent, so a little overspill is expected and
    // 2.0 would not be.
    assert!(worst_pos < 1.3, "positions reach {worst_pos}, not tile-local 0..1");
    assert!(
        worst_normal <= stroke::MITER_LIMIT + 1e-3,
        "a normal is {worst_normal} long, past the miter limit, so that join is too wide",
    );
    assert!(
        worst_distance < 1.3,
        "distance-along-line reaches {worst_distance}; line.vert scales it by the tile's \
         pixel size, so it must be tile-local, not extent units",
    );
    // And every vertex must be finite: a NaN normal from a zero-length segment would
    // silently drop or explode the triangles that share it.
    for m in &mesh.meshes {
        for f in &m.vertices {
            assert!(f.is_finite(), "{} emitted a non-finite vertex", layers[m.layer_index].id);
        }
    }
}

#[test]
fn positions_are_tile_normalised() {
    // The clip transform assumes 0..1 within the tile. Clipped geometry overspills
    // the edges a little, which is why the bound is generous rather than exact.
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    for m in &mesh.meshes {
        let stride = match m.kind {
            LayerKind::Fill => fill::FLOATS_PER_VERTEX,
            LayerKind::Line => stroke::FLOATS_PER_VERTEX,
            LayerKind::Symbol => crate::tile::symbol::FLOATS_PER_VERTEX,
        };
        for chunk in m.vertices.chunks(stride) {
            assert!(chunk[0] > -3.0 && chunk[0] < 4.0, "x {} is not tile-normalised", chunk[0]);
            assert!(chunk[1] > -3.0 && chunk[1] < 4.0, "y {} is not tile-normalised", chunk[1]);
        }
    }
}

#[test]
fn a_layer_outside_its_zoom_range_is_skipped() {
    let layers = style::layers();
    let low = build(&real(), &layers, 11, 339, 770, false);
    // buildings is min_zoom 14, and roads-minor is 13; the tile's road is a
    // major_road anyway.
    assert!(mesh_for(&low, &layers, "roads-minor").is_none());
    assert!(mesh_for(&low, &layers, "earth").is_some(), "earth draws at every zoom");
}

#[test]
fn a_casing_produces_twice_the_vertices_of_a_plain_stroke() {
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    let plain = mesh_for(&mesh, &layers, "roads-major").expect("plain");
    let casing = mesh_for(&mesh, &layers, "roads-major-casing").expect("casing");
    assert_eq!(
        plain.vertices.len() * 2,
        casing.vertices.len(),
        "a casing is two bands of the same centreline",
    );
    assert_eq!(plain.indices.len() * 2, casing.indices.len());
}

#[test]
fn an_empty_tile_produces_no_meshes() {
    let layers = style::layers();
    let mesh = build(&Body::new(4096), &layers, 11, 0, 0, false);
    assert!(mesh.meshes.is_empty());
}

/// Three transit lines, two colours: the layer emits one mesh per distinct colour, in
/// first-seen feature order, and the two lines that share a colour share a mesh.
///
/// Without the split a `find` in the renderer would draw only the first mesh, so the
/// second operator's line would vanish rather than merely be miscoloured.
#[test]
fn transit_lines_split_into_one_mesh_per_colour() {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    use tilecodec::mamaps::dict;

    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_TRANSIT);
    // Blue, red, blue again — so the test also proves equal colours coalesce into one
    // mesh rather than one mesh per feature.
    for (color, y) in [(0x00_54_A5u32, 100i16), (0xE3_1E_24, 200), (0x00_54_A5, 300)] {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("rail"),
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: color,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
    }
    body.layers.push(source);

    let all = style::layers();
    let at = all.iter().position(|l| l.id == "transit-rail").expect("the transit layer");
    let Some(only) = all.get(at..=at) else { panic!("a one-layer slice") };

    // Off by default, and the gate is before any tessellation: nothing at all.
    assert!(
        build(&body, only, 14, 0, 0, false).meshes.is_empty(),
        "an optional layer that is off must tessellate nothing",
    );

    let on = LayerToggles { poi: false, transit: true, traffic: false };
    let mesh = build_toggled(&body, only, 14, 0, 0, false, on, &KindFilter::all(), 7);
    assert_eq!(mesh.generation, 7, "the mesh records the generation it was built at");
    let colours: Vec<Option<u32>> = mesh.meshes.iter().map(|m| m.color_override).collect();
    assert_eq!(
        colours,
        vec![Some(0xFF00_54A5), Some(0xFFE3_1E24)],
        "one opaque ARGB mesh per distinct colour, in first-seen order",
    );
    // The two blue lines really did share a mesh rather than each getting one.
    let (blue, red) = (&mesh.meshes[0], &mesh.meshes[1]);
    assert_eq!(blue.indices.len(), red.indices.len() * 2, "two lines against one");
    for m in &mesh.meshes {
        assert_eq!(m.kind, LayerKind::Line);
        assert!(!m.indices.is_empty());
    }
}

/// A body of straight roads, one per `(lane_count, oneway)` entry, on the `roads` layer.
fn carriageway_body(roads: &[(u8, bool)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, FLAG_IS_ONEWAY, NAME_NONE, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    for (i, &(lane_count, oneway)) in roads.iter().enumerate() {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        let y = 100 + i as i16 * 100;
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("major_road"),
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: if oneway { FLAG_IS_ONEWAY } else { 0 },
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count,
        });
    }
    body.layers.push(source);
    body
}

/// The `roads-carriageway` layer as a one-layer slice, so a test states its own layer set.
///
/// Read with lane rendering forced on: [`style::LANE_RENDERING`] is off for release, so the
/// shipped layer set carries no carriageway at all and the tests below would have nothing to
/// assert against.
fn carriageway_only() -> &'static [Layer] {
    let all = style::layers_with_lane_rendering();
    let at = all.iter().position(|l| l.carriageway).expect("the carriageway layer");
    all.get(at..=at).expect("a one-layer slice")
}

/// **The path every real archive takes today.** The tiler is still wiring the producer side, so
/// no published tile carries a carriageway table or a marking convention, and the renderer has
/// to draw a correct road from the lane count alone: the split lands on a lane boundary, the
/// centre line is white, and a one-way suppresses it entirely.
#[test]
fn a_tile_with_no_carriageway_table_still_draws_a_correct_carriageway() {
    let body = carriageway_body(&[(4, false), (3, true)]);
    assert!(body.carriageways.is_empty(), "the fixture is a v7 archive with no table");
    assert!(body.convention.is_none());

    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways.iter().map(|c| (c.lanes, c.oneway)).collect::<Vec<_>>(),
        vec![(4, false), (3, true)],
    );
    assert_eq!(mesh.carriageways[0].split, 0.0, "an even count splits down the middle");
    assert!(!mesh.yellow_centre, "no convention means white, which is most of the world");
    assert!(mesh.meshes.is_empty(), "a carriageway is not a stroked layer mesh");
}

/// **The degenerate case the flat 0.0 default hid.** `road_surface.frag` picks the boundary
/// carrying the centre line with `abs(boundaryT - centreT) < 1.0 / lanes` — half a lane, and
/// strictly less — so a split that sits exactly halfway between two boundaries matches neither.
/// On an odd lane count the middle of the road is the middle of the centre *lane*, so a 0.0
/// default left both neighbouring dividers dashed and painted the centre line down a lane.
///
/// This only ever bit the unknown-split path, which is the only path there is until the tiler
/// writes a carriageway table — so the invariant is asserted the way the shader tests it,
/// against every lane count a road can have, both hands of the road.
#[test]
fn an_unknown_split_always_lands_on_a_lane_boundary() {
    use tilecodec::mamaps::body::MarkingConvention;
    for left_hand in [false, true] {
        for lanes in 1..=12u8 {
            let mut body = carriageway_body(&[(lanes, false)]);
            body.convention = Some(MarkingConvention { left_hand, yellow_centre: false });
            let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
            let split = mesh.carriageways[0].split;
            assert!((-1.0..=1.0).contains(&split), "{lanes} lanes gave t {split}");
            // Exactly the shader's test, against the nearest boundary it would round to.
            let boundary = (((split + 1.0) / 2.0) * lanes as f32).round();
            let boundary_t = boundary / lanes as f32 * 2.0 - 1.0;
            assert!(
                (boundary_t - split).abs() < 1.0 / lanes as f32,
                "{lanes} lanes: t {split} is not within half a lane of boundary {boundary_t}, \
                 so the centre line would paint down the middle of a lane",
            );
        }
    }
}

/// The odd lane goes to the forward direction, which is the split real data carries — a
/// three-lane two-way road is tagged 2/1, not "centred" — and which side of the road that puts
/// the line on still follows the driving convention.
#[test]
fn an_odd_lane_count_gives_the_extra_lane_to_the_forward_direction() {
    use tilecodec::mamaps::body::MarkingConvention;
    let third = 1.0f32 / 3.0;
    for (left_hand, expected) in [(false, -third), (true, third)] {
        let mut body = carriageway_body(&[(3, false)]);
        body.convention = Some(MarkingConvention { left_hand, yellow_centre: false });
        let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
        assert!(
            (mesh.carriageways[0].split - expected).abs() < 1e-6,
            "left_hand {left_hand}: {} is not {expected}",
            mesh.carriageways[0].split,
        );
    }
    // And the same three lanes tagged explicitly agree with the guess, so the fallback is not
    // a second answer that real data will contradict.
    let tagged = split_for(3, tilecodec::mamaps::body::Carriageway {
        forward: 2,
        backward: 1,
        solid_dividers: 0,
    });
    assert!((tagged - -third).abs() < 1e-6, "tagged 2/1 gave {tagged}");
}

/// One road with a known carriageway row, tessellated under right-hand traffic.
fn split_for(lanes: u8, shape: tilecodec::mamaps::body::Carriageway) -> f32 {
    use tilecodec::mamaps::dict;
    let mut body = carriageway_body(&[(lanes, false)]);
    body.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    build(&body, carriageway_only(), 16, 0, 0, false).carriageways[0].split
}

/// A body of road sections laid end to end along one line, each `(lane_count, length)` in tile
/// units.
///
/// The shape the defect actually has: `coalesce`'s merge key includes the lane count, so a road
/// whose count changes mid-block arrives as two features that share an endpoint exactly.
fn abutting_body(sections: &[(u8, i16)]) -> Body {
    use tilecodec::mamaps::body::{
        Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    let mut x = 0i16;
    for &(lane_count, length) in sections {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        source.coords.extend_from_slice(&[(x, 2000), (x + length, 2000)]);
        x += length;
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("major_road"),
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
            lane_count,
        });
    }
    body.layers.push(source);
    body
}

/// One lane's width in tile-local units, standing in for the style ramp.
const TEST_LANE: f32 = 0.01;

/// How wide the carriageway is, in lanes, at the vertex nearest tile-local `x`.
///
/// Reproduces what reaches a pixel rather than reading the mesh's own fields:
/// `record_carriageways` pushes `lane width x lanes / 2` and `road_surface.vert` offsets each
/// kerb by `normal * t * halfWidth`. The roads in these fixtures run east, so the kerbs are the
/// y offsets either side of the centreline.
fn lanes_across(mesh: &CarriagewayMesh, x: f32) -> f32 {
    let floats = ribbon::FLOATS_PER_VERTEX;
    let half_width = TEST_LANE * f32::from(mesh.lanes) / 2.0;
    let points = mesh.vertices.len() / floats / 2;
    let distance = |point: usize| (mesh.vertices[point * 2 * floats] - x).abs();
    let nearest = (0..points)
        .min_by(|&a, &b| distance(a).total_cmp(&distance(b)))
        .expect("a carriageway mesh has points");
    let kerb = |vertex: usize| {
        let f = vertex * floats;
        mesh.vertices[f + 1] + mesh.vertices[f + 3] * mesh.vertices[f + 4] * half_width
    };
    (kerb(nearest * 2 + 1) - kerb(nearest * 2)).abs() / TEST_LANE
}

/// **The defect the user reported, end to end through `tessellate`.**
///
/// A four-lane section running into a two-lane one used to step: two features, two meshes, two
/// constant half-widths, and a hard shoulder-step where they met. The wide section must now
/// arrive at the shared point exactly as wide as the narrow one.
#[test]
fn a_lane_count_change_tapers_instead_of_stepping() {
    let body = abutting_body(&[(4, 1000), (2, 1000)]);
    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    let shapes: Vec<u8> = mesh.carriageways.iter().map(|c| c.lanes).collect();
    assert_eq!(shapes, vec![4, 2], "still one mesh per lane count");

    let (wide, narrow) = (&mesh.carriageways[0], &mesh.carriageways[1]);
    let node = 1000.0 / 4096.0;
    let wide_at_node = lanes_across(wide, node);
    let narrow_at_node = lanes_across(narrow, node);
    assert!(
        (wide_at_node - narrow_at_node).abs() < 1e-4,
        "the kerbs must meet: {wide_at_node} lanes against {narrow_at_node}",
    );
    assert!((narrow_at_node - 2.0).abs() < 1e-4, "and on the narrow road's width");
    // The far end is untouched, so the taper is local to the change rather than shrinking the
    // whole road.
    assert!((lanes_across(wide, 0.0) - 4.0).abs() < 1e-4, "{}", lanes_across(wide, 0.0));
}

/// The counterpart, so the test above cannot pass by tapering everything: a road that does not
/// change width must be byte-identical to what it was before the taper existed.
#[test]
fn a_road_of_constant_width_is_not_tapered_at_all() {
    let body = abutting_body(&[(4, 1000), (4, 1000)]);
    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(mesh.carriageways.len(), 1, "one lane count, one mesh");
    let road = &mesh.carriageways[0];
    for x in [0.0, 1000.0 / 4096.0, 2000.0 / 4096.0] {
        let across = lanes_across(road, x);
        assert!((across - 4.0).abs() < 1e-4, "full width at {x}: {across}");
    }
    // Every normal is still the plain unit or miter length the untapered path emits.
    let floats = ribbon::FLOATS_PER_VERTEX;
    for vertex in 0..road.vertices.len() / floats {
        let (nx, ny) = (road.vertices[vertex * floats + 2], road.vertices[vertex * floats + 3]);
        let length = (nx * nx + ny * ny).sqrt();
        assert!((length - 1.0).abs() < 1e-5, "vertex {vertex} normal is {length}");
    }
}

/// A junction connector is one lane by construction and is placed with a setback from the arms,
/// so it must never be pulled into a road's transition even if an endpoint coincides.
#[test]
fn a_lane_count_change_does_not_taper_the_connectors() {
    let body = abutting_body(&[(4, 1000), (2, 1000)]);
    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    assert!(
        mesh.carriageways.iter().all(|c| c.layer_index == 0),
        "the fixture carries no junction layer, so this pins the roads path alone",
    );
    // The taper rode in the vertices, not the mesh key, so the two sections did not multiply
    // into a mesh per part.
    assert_eq!(mesh.carriageways.len(), 2);
}

/// A road with no `lanes` tag — most of OSM — still gets a carriageway, because a zero lane
/// count would push a zero width and draw nothing at all where a road plainly is.
#[test]
fn an_untagged_road_falls_back_to_one_lane_each_way() {
    let mesh = build(&carriageway_body(&[(0, false), (0, true)]), carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways.iter().map(|c| (c.lanes, c.oneway)).collect::<Vec<_>>(),
        vec![(2, false), (1, true)],
    );
}

/// The push inputs are what splits the meshes, so roads that agree on all three share a draw
/// and roads that disagree cannot — the carriageway equivalent of the transit colour split.
#[test]
fn carriageways_split_into_one_mesh_per_distinct_road_shape() {
    // Two four-lane two-ways, a six-lane two-way, and a four-lane one-way.
    let body = carriageway_body(&[(4, false), (6, false), (4, false), (4, true)]);
    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways.iter().map(|c| (c.lanes, c.oneway)).collect::<Vec<_>>(),
        vec![(4, false), (6, false), (4, true)],
        "one mesh per distinct shape, in first-seen feature order",
    );
    // The two four-lane two-ways really did share a mesh rather than each getting one.
    assert_eq!(mesh.carriageways[0].indices.len(), mesh.carriageways[1].indices.len() * 2);
    for c in &mesh.carriageways {
        assert_eq!(c.vertices.len() % ribbon::FLOATS_PER_VERTEX, 0, "vertices are whole");
        assert_eq!(c.indices.len() % 3, 0, "indices come in threes");
        let vertex_count = (c.vertices.len() / ribbon::FLOATS_PER_VERTEX) as u32;
        assert!(c.indices.iter().all(|&i| i < vertex_count), "an index is out of range");
        assert!(c.vertices.iter().all(|f| f.is_finite()));
    }
}

/// Where the archive *does* carry a split, the centre line goes at the boundary between the
/// directions — and which side that is depends on which side the country drives on, because
/// `forward` means "toward the feature's last point" and the ribbon measures `t` from the left
/// of that same direction.
#[test]
fn the_centre_line_follows_the_split_and_the_driving_side() {
    use tilecodec::mamaps::body::{Carriageway, MarkingConvention};
    use tilecodec::mamaps::dict;
    // Three forward lanes, one backward.
    let shape = Carriageway { forward: 3, backward: 1, solid_dividers: 0 };

    let mut right = carriageway_body(&[(4, false)]);
    right.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    right.convention = Some(MarkingConvention { left_hand: false, yellow_centre: true });
    let mesh = build(&right, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways[0].split, -0.5,
        "right-hand traffic keeps the forward lanes on the +1 side, so the one backward \
         lane takes the quarter of the road nearest the -1 kerb",
    );
    assert!(mesh.yellow_centre, "the Americas paint the line between directions yellow");

    let mut left = carriageway_body(&[(4, false)]);
    left.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    left.convention = Some(MarkingConvention { left_hand: true, yellow_centre: false });
    let mesh = build(&left, carriageway_only(), 16, 0, 0, false);
    assert_eq!(mesh.carriageways[0].split, 0.5, "left-hand traffic mirrors it");
    assert!(!mesh.yellow_centre);
}

/// A road the archive lists in the table but knows nothing about degrades to the same answer as
/// a tile with no table at all, rather than dividing by zero.
#[test]
fn a_road_with_an_empty_carriageway_row_falls_back_like_an_absent_one() {
    use tilecodec::mamaps::body::Carriageway;
    assert_eq!(split_for(4, Carriageway::default()), 0.0);
    let odd = split_for(3, Carriageway::default());
    assert!((odd - -(1.0f32 / 3.0)).abs() < 1e-6, "three lanes gave {odd}");
}

/// The dense lane detail is gated to high zoom, over the same ancestor window every other
/// layer uses: a tile builds what it will stand in for and no more.
#[test]
fn the_carriageway_is_not_built_below_its_zoom_window() {
    let body = carriageway_body(&[(4, false)]);
    assert!(
        build(&body, carriageway_only(), 11, 0, 0, false).carriageways.is_empty(),
        "z11 stands in no deeper than z15, which is below the carriageway floor",
    );
    assert!(
        !build(&body, carriageway_only(), ROAD_LANE_MIN_ZOOM - ANCESTOR_DEPTH, 0, 0, false)
            .carriageways
            .is_empty(),
        "the deepest archive tile must build what it stands in for at z16",
    );
}

// --- lane connectors through junctions ---------------------------------

/// A body of straight lane connectors on the junction layer, each an already-sampled polyline
/// of three points — the shape the tiler emits, with the bezier sampled on its side.
///
/// The features deliberately carry a lane count of six and no one-way flag, neither of which a
/// connector can actually be. A connector's shape is fixed by what it *is*, so the tests below
/// prove the renderer imposes that rather than reading it off the feature.
fn junction_body(count: usize) -> Body {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(LAYER_JUNCTION);
    for i in 0..count {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 3,
            winding: WINDING_OUTER,
        });
        let y = 100 + i as i16 * 100;
        source.coords.extend_from_slice(&[(0, y), (500, y), (1000, y + 200)]);
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
            lane_count: 6,
        });
    }
    body.layers.push(source);
    body
}

/// The `junction-connector` layer as a one-layer slice, matching [`carriageway_only`].
fn connector_only() -> &'static [Layer] {
    let all = style::layers_with_lane_rendering();
    let at = all.iter().position(|l| l.id == "junction-connector").expect("the connector layer");
    all.get(at..=at).expect("a one-layer slice")
}

/// A connector goes through the carriageway path as one lane of one-way traffic, whatever the
/// feature carries.
///
/// Both halves matter to `road_surface.frag`, which reads them straight out of the push block.
/// One lane leaves no interior lane boundary, so no divider is dashed down the middle of it;
/// one-way suppresses the centre line, which is the point — a single stream of traffic has no
/// opposing direction to be separated from, and a connector painted with a centre line reads
/// as a two-way road through the junction.
///
/// Twelve of them, which is what a plain 4-arm crossroads emits (4 approaches x 3 legal exits,
/// no U-turn). They must collapse to **one** draw: every connector agrees on all three push
/// inputs by construction, so the mesh key coalesces them however many there are. That is what
/// keeps a dense tile to one extra draw call rather than one per connector, and it is the
/// property that would silently regress if a connector ever gained a per-feature shape.
#[test]
fn a_connector_is_one_lane_of_one_way_traffic_whatever_the_feature_carries() {
    let mesh = build(&junction_body(12), connector_only(), 17, 0, 0, false);
    assert_eq!(mesh.carriageways.len(), 1, "a whole crossroads is one draw, not twelve");
    let connector = &mesh.carriageways[0];
    assert_eq!(connector.lanes, 1, "one lane wide, not the six the feature claims");
    assert!(connector.oneway, "and one-way, so no centre line is painted down it");
    assert!(connector.split.abs() < 1e-6, "the split is meaningless on a one-way");
    assert!(mesh.meshes.is_empty(), "a connector is not a stroked layer mesh");

    // The ribbon vertex, so the existing pipeline and shaders draw it with no new format.
    assert_eq!(connector.vertices.len() % ribbon::FLOATS_PER_VERTEX, 0);
    assert_eq!(connector.indices.len() % 3, 0);
    let vertex_count = (connector.vertices.len() / ribbon::FLOATS_PER_VERTEX) as u32;
    assert_eq!(vertex_count, 72, "twelve connectors, three points each, two vertices a point");
    assert!(connector.indices.iter().all(|&i| i < vertex_count), "an index is out of range");
    assert!(connector.vertices.iter().all(|f| f.is_finite()));
}

/// Roads and connectors never share a draw, and the connector draws second.
///
/// They disagree on every push input, so they could not share one anyway. The order is the
/// point: a connector overlaps the road surface at the mouth of the junction, and the road
/// painting over the connector would leave the connector's edge lines cut off short of where
/// they meet the kerb.
#[test]
fn connectors_and_roads_are_separate_draws_with_the_connector_over_the_road() {
    let layers = style::layers_with_lane_rendering();
    let roads = layers.iter().position(|l| l.id == "roads-carriageway").expect("roads");
    let connectors =
        layers.iter().position(|l| l.id == "junction-connector").expect("connectors");
    assert!(roads < connectors, "layer order is draw order, and the connector goes on top");

    let mut body = carriageway_body(&[(4, false)]);
    body.layers.extend(junction_body(1).layers);
    let mesh = build(&body, layers, 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways
            .iter()
            .map(|c| (c.layer_index, c.lanes, c.oneway))
            .collect::<Vec<_>>(),
        vec![(roads, 4, false), (connectors, 1, true)],
    );
}

/// **The only path that exists today.** No archive carries a junction layer, and none will
/// until the tiler writes one, so the connector layer has to cost exactly nothing on every tile
/// there is: no mesh, no vertex, no draw, and no road drawn any differently.
///
/// Asserted as a byte-equality against the carriageway layer on its own, rather than as "no
/// connector mesh appeared". The weaker form would still pass if the connector layer had
/// quietly changed a road's lane count or split on its way past, which is the failure that
/// would actually reach a screen.
#[test]
fn a_tile_with_no_junction_layer_is_untouched_by_the_connector_layer() {
    let layers = style::layers_with_lane_rendering();
    let connectors =
        layers.iter().position(|l| l.id == "junction-connector").expect("connectors");

    // Ordinary roads at the carriageway zoom, and nothing else — a v7 archive.
    let body = carriageway_body(&[(4, false), (3, true), (0, false)]);
    assert!(body.layer(LAYER_JUNCTION).is_none(), "the fixture has no junction layer");

    let full = build(&body, layers, 16, 0, 0, false);
    let roads_only = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(full.carriageways.len(), roads_only.carriageways.len(), "an extra draw");
    for (a, b) in full.carriageways.iter().zip(&roads_only.carriageways) {
        assert_ne!(a.layer_index, connectors, "a connector mesh out of thin air");
        assert_eq!(a.lanes, b.lanes);
        assert_eq!(a.oneway, b.oneway);
        assert!((a.split - b.split).abs() < 1e-6, "split {} became {}", b.split, a.split);
        assert_eq!(a.vertices, b.vertices, "the road geometry is byte-identical");
        assert_eq!(a.indices, b.indices);
    }

    // And the published tile, which is the real thing and carries no junction layer either.
    let published = build(&real(), layers, 11, 339, 770, false);
    assert!(published.carriageways.is_empty(), "z11 is below the carriageway floor anyway");
    assert!(
        !published.meshes.iter().any(|m| m.layer_index == connectors),
        "the connector layer must not draw a stroked mesh either",
    );
}

/// **What makes the `NO_MARKINGS` sentinel safe.** The renderer signals "paint no markings on
/// this ribbon" by pushing -2.0 into `Push.line.z`, the slot that otherwise carries the
/// centre-line split. That is only sound because a real split is an across-road coordinate and
/// so cannot leave `[-1, +1]` — a road that ever pushed a value below -1.5 would silently lose
/// its lane markings.
///
/// `vulkan/` is `#[cfg(target_os = "android")]`, so the sentinel itself is not reachable from a
/// host test. This pins the half that is: the producer side, over every shape a road can take,
/// including the lopsided splits that push furthest toward a kerb.
#[test]
fn a_roads_split_can_never_reach_the_no_markings_sentinel() {
    use tilecodec::mamaps::body::{Carriageway, MarkingConvention};

    let mut worst: f32 = 0.0;
    for left_hand in [false, true] {
        for lanes in 1..=12u8 {
            // The unknown-split path, which is what every archive takes today.
            let mut body = carriageway_body(&[(lanes, false), (lanes, true)]);
            body.convention = Some(MarkingConvention { left_hand, yellow_centre: false });
            for mesh in build(&body, carriageway_only(), 16, 0, 0, false).carriageways {
                worst = worst.max(mesh.split.abs());
            }
            // And every tagged division of those lanes, including all-forward and
            // all-backward, which are the extremes that land the split on a kerb.
            for forward in 0..=lanes {
                let shape = Carriageway {
                    forward,
                    backward: lanes - forward,
                    solid_dividers: 0,
                };
                worst = worst.max(split_for(lanes, shape).abs());
            }
        }
    }
    assert!(
        worst <= 1.0,
        "a road pushed a split of {worst}, outside the +/-1 an across-road coordinate can \
         take; anything past -1.5 would be read as the no-markings sentinel",
    );
}

/// Turn arrows are produced from the archive's turn-lane table at high zoom and gated off
/// below it: a road with `turn:lanes` yields one arrow per marked lane, pointing along the
/// road, and none when the tile is too coarse.
#[test]
fn turn_arrows_come_from_the_turn_table_at_high_zoom() {
    use tilecodec::mamaps::body::{
        Feature, LaneTurns, Layer as BodyLayer, Part, LANE_LEFT, LANE_THROUGH, NAME_NONE,
        WINDING_OUTER,
    };
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_ROADS);
    // A straight eastbound road spanning the tile, ending at the east edge.
    source.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    source.coords.extend_from_slice(&[(100, 2000), (3000, 2000)]);
    source.features.push(Feature {
        kind: crate::style::kind_id_for_test("major_road"),
        kind_detail: 0,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 2,
    });
    body.layers.push(source);
    body.turn_lanes = vec![(
        dict::LAYER_ROADS,
        vec![LaneTurns { forward: vec![LANE_LEFT, LANE_THROUGH], backward: vec![] }],
    )];

    // Below the gate: no arrows built.
    assert!(build(&body, &style::layers(), 11, 0, 0, false).arrows.is_empty());

    let mesh = build(&body, &style::layers(), 16, 0, 0, false);
    assert_eq!(mesh.arrows.len(), 2, "one arrow per marked forward lane");
    assert!(mesh.arrows.iter().all(|a| a.angle.abs() < 1e-4), "eastbound heading is ~0");
    assert_eq!(mesh.arrows[0].arrow, crate::tile::arrow::TurnArrow::Left);
    assert_eq!(mesh.arrows[1].arrow, crate::tile::arrow::TurnArrow::Through);
    assert!(mesh.arrows.iter().all(|a| a.count == 2));
}

#[test]
fn transit_lines_of_one_colour_split_again_on_their_corridor_ordinal() {
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
    use tilecodec::mamaps::dict;
    let mut body = Body::new(4096);
    let mut source = BodyLayer::new(dict::LAYER_TRANSIT);
    for (ordinal, y) in [(0u8, 100i16), (1, 200), (0, 300)] {
        let parts_offset = source.parts.len() as u32;
        source.parts.push(Part {
            coord_start: source.coords.len() as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        source.coords.extend_from_slice(&[(0, y), (1000, y)]);
        source.features.push(Feature {
            kind: crate::style::kind_id_for_test("rail"),
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset,
            part_count: 1,
            transit_color: 0x00_54_A5,
            transit_ordinal: ordinal,
            transit_lanes: 2,
            transit_taper: 255,
            lane_count: 0,
        });
    }
    body.layers.push(source);
    let all = style::layers();
    let at = all.iter().position(|l| l.id == "transit-rail").expect("the transit layer");
    let Some(only) = all.get(at..=at) else { panic!("a one-layer slice") };
    let on = LayerToggles { poi: false, transit: true, traffic: false };
    let mesh = build_toggled(&body, only, 14, 0, 0, false, on, &KindFilter::all(), 0);
    assert_eq!(
        mesh.meshes.iter().map(|m| m.lane).collect::<Vec<(u8, u8, u8)>>(),
        vec![(0, 2, 255), (1, 2, 255)],
        "one mesh per ordinal, in first-seen order",
    );
    assert!(mesh.meshes.iter().all(|m| m.color_override == Some(0xFF00_54A5)));
    // The two lines on the same ordinal really did share a mesh.
    assert_eq!(mesh.meshes[0].indices.len(), mesh.meshes[1].indices.len() * 2);
}

/// The counterpart: a feature with no colour of its own stays in the layer's single
/// mesh, so the road path is untouched by the split.
#[test]
fn a_layer_whose_features_carry_no_colour_still_emits_one_mesh() {
    let layers = style::layers();
    let mesh = build(&real(), &layers, 11, 339, 770, false);
    for m in &mesh.meshes {
        assert_eq!(
            m.color_override, None,
            "`{}` gained a colour override from a road feature",
            layers[m.layer_index].id,
        );
    }
    let roads: Vec<&LayerMesh> = mesh
        .meshes
        .iter()
        .filter(|m| layers[m.layer_index].id == "roads-major")
        .collect();
    assert_eq!(roads.len(), 1, "one mesh per layer where no feature carries a colour");
}

#[test]
fn a_line_layer_also_strokes_polygon_outlines() {
    // A lake shoreline and an administrative boundary are lines over area features.
    let outline = vec![Layer {
        id: "water-edge".to_string(),
        source_layer: "water".to_string(),
        source_layer_id: tilecodec::mamaps::dict::LAYER_WATER,
        kind: LayerKind::Line,
        kinds: Vec::new(),
        kind_ids: Vec::new(),
        require_flags: 0,
        forbid_flags: 0,
        detail_ids: Vec::new(),
        forbid_details: Vec::new(),
        light: 0xFF000000,
        dark: 0xFF000000,
        opacity: Ramp::constant(1.0),
        width: Ramp::constant(1.0),
        gap_width: Ramp::constant(0.0),
        spread: Ramp::constant(0.0),
        lanes: Ramp::constant(1.0),
        carriageway: false,
        dash: (0.0, 0.0),
        text_size: Ramp::constant(1.0),
        text_size_large: None,
        rank_threshold: None,
        uppercase: false,
        medium: false,
        toggle: None,
        icon: false,
        text_offset: (0.0, 0.0),
        text_max_width: 0.0,
        variable_anchor: Vec::new(),
        halo_light: 0x00000000,
        halo_dark: 0x00000000,
        halo_width: 1.0,
        min_zoom: 0,
        browse_min_zoom: 0,
        max_zoom: 22,
        authored: "water".to_string(),
    }];
    let mesh = build(&real(), &outline, 11, 339, 770, false);
    assert_eq!(mesh.meshes.len(), 1, "the water polygons' outlines stroke");
    assert!(!mesh.meshes[0].indices.is_empty());
}
/// **The Phase 4 milestone, end to end inside this crate.** A `.mamaps` archive is built,
/// opened through a `RangeReader`, and tessellated -- so the container, the reader, the style's
/// interned ids and this module are proven together, on data the tiler already produced.
#[test]
fn a_tile_read_out_of_a_mamaps_archive_tessellates() {
    use std::cell::RefCell;
    use tilecodec::mamaps::write::{Options, StreamWriter};
    use tilecodec::mamaps::MamapsArchive;
    use tilecodec::stream::RangeReader;

    struct Memory {
        bytes: Vec<u8>,
        requests: RefCell<usize>,
    }
    impl RangeReader for Memory {
        fn read(&self, offset: u64, length: u32) -> tilecodec::proto::Result<Vec<u8>> {
            *self.requests.borrow_mut() += 1;
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    let id = tilecodec::pmtiles::tile_id(11, 339, 770);
    let options = Options { min_zoom: 0, max_zoom: 14, ..Options::default() };
    let mut writer = StreamWriter::new(options).expect("options");
    writer.append(id, &real()).expect("append");
    let bytes = writer.finish().expect("finish");

    let mut archive =
        MamapsArchive::open(Memory { bytes, requests: RefCell::new(0) }).expect("open");
    assert_eq!(*archive.reader().requests.borrow(), 1, "a cold open is one request");

    let layers = style::layers();
    let body = archive.tile(11, 339, 770).expect("read").expect("present");
    let mesh = build(&body, layers, 11, 339, 770, archive.header.rings_validated());
    // The same layers the fixture produces when tessellated directly, so nothing was lost
    // between the encoder and the reader.
    for id in ["earth", "water", "roads-major", "roads-major-casing"] {
        assert!(mesh_for(&mesh, layers, id).is_some(), "{id} should draw");
    }
    assert!(mesh_for(&mesh, layers, "buildings").is_none(), "the tile has no buildings");
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
