use super::build::build;
use super::mesh::{CarriagewayMesh, ROAD_LANE_MIN_ZOOM};
use crate::style;
use crate::style::Layer;
use crate::tess::ribbon;
use crate::tile::select::ANCESTOR_DEPTH;
use tilecodec::mamaps::body::{Body, GEOM_LINE};

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
    let at = all
        .iter()
        .position(|l| l.carriageway)
        .expect("the carriageway layer");
    all.get(at..=at).expect("a one-layer slice")
}

/// **The path every real archive takes today.** The tiler is still wiring the producer side, so
/// no published tile carries a carriageway table or a marking convention, and the renderer has
/// to draw a correct road from the lane count alone: the split lands on a lane boundary, the
/// centre line is white, and a one-way suppresses it entirely.
#[test]
fn a_tile_with_no_carriageway_table_still_draws_a_correct_carriageway() {
    let body = carriageway_body(&[(4, false), (3, true)]);
    assert!(
        body.carriageways.is_empty(),
        "the fixture is a v7 archive with no table"
    );
    assert!(body.convention.is_none());

    let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways
            .iter()
            .map(|c| (c.lanes, c.oneway))
            .collect::<Vec<_>>(),
        vec![(4, false), (3, true)],
    );
    assert_eq!(
        mesh.carriageways[0].split, 0.0,
        "an even count splits down the middle"
    );
    assert!(
        !mesh.yellow_centre,
        "no convention means white, which is most of the world"
    );
    assert!(
        mesh.meshes.is_empty(),
        "a carriageway is not a stroked layer mesh"
    );
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
            body.convention = Some(MarkingConvention {
                left_hand,
                yellow_centre: false,
            });
            let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
            let split = mesh.carriageways[0].split;
            assert!(
                (-1.0..=1.0).contains(&split),
                "{lanes} lanes gave t {split}"
            );
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
        body.convention = Some(MarkingConvention {
            left_hand,
            yellow_centre: false,
        });
        let mesh = build(&body, carriageway_only(), 16, 0, 0, false);
        assert!(
            (mesh.carriageways[0].split - expected).abs() < 1e-6,
            "left_hand {left_hand}: {} is not {expected}",
            mesh.carriageways[0].split,
        );
    }
    // And the same three lanes tagged explicitly agree with the guess, so the fallback is not
    // a second answer that real data will contradict.
    let tagged = split_for(
        3,
        tilecodec::mamaps::body::Carriageway {
            forward: 2,
            backward: 1,
            solid_dividers: 0,
        },
    );
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
    use tilecodec::mamaps::body::{Feature, Layer as BodyLayer, Part, NAME_NONE, WINDING_OUTER};
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
        source
            .coords
            .extend_from_slice(&[(x, 2000), (x + length, 2000)]);
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
    assert!(
        (narrow_at_node - 2.0).abs() < 1e-4,
        "and on the narrow road's width"
    );
    // The far end is untouched, so the taper is local to the change rather than shrinking the
    // whole road.
    assert!(
        (lanes_across(wide, 0.0) - 4.0).abs() < 1e-4,
        "{}",
        lanes_across(wide, 0.0)
    );
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
        let (nx, ny) = (
            road.vertices[vertex * floats + 2],
            road.vertices[vertex * floats + 3],
        );
        let length = (nx * nx + ny * ny).sqrt();
        assert!(
            (length - 1.0).abs() < 1e-5,
            "vertex {vertex} normal is {length}"
        );
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
    let mesh = build(
        &carriageway_body(&[(0, false), (0, true)]),
        carriageway_only(),
        16,
        0,
        0,
        false,
    );
    assert_eq!(
        mesh.carriageways
            .iter()
            .map(|c| (c.lanes, c.oneway))
            .collect::<Vec<_>>(),
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
        mesh.carriageways
            .iter()
            .map(|c| (c.lanes, c.oneway))
            .collect::<Vec<_>>(),
        vec![(4, false), (6, false), (4, true)],
        "one mesh per distinct shape, in first-seen feature order",
    );
    // The two four-lane two-ways really did share a mesh rather than each getting one.
    assert_eq!(
        mesh.carriageways[0].indices.len(),
        mesh.carriageways[1].indices.len() * 2
    );
    for c in &mesh.carriageways {
        assert_eq!(
            c.vertices.len() % ribbon::FLOATS_PER_VERTEX,
            0,
            "vertices are whole"
        );
        assert_eq!(c.indices.len() % 3, 0, "indices come in threes");
        let vertex_count = (c.vertices.len() / ribbon::FLOATS_PER_VERTEX) as u32;
        assert!(
            c.indices.iter().all(|&i| i < vertex_count),
            "an index is out of range"
        );
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
    let shape = Carriageway {
        forward: 3,
        backward: 1,
        solid_dividers: 0,
    };

    let mut right = carriageway_body(&[(4, false)]);
    right.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    right.convention = Some(MarkingConvention {
        left_hand: false,
        yellow_centre: true,
    });
    let mesh = build(&right, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways[0].split, -0.5,
        "right-hand traffic keeps the forward lanes on the +1 side, so the one backward \
         lane takes the quarter of the road nearest the -1 kerb",
    );
    assert!(
        mesh.yellow_centre,
        "the Americas paint the line between directions yellow"
    );

    let mut left = carriageway_body(&[(4, false)]);
    left.carriageways = vec![(dict::LAYER_ROADS, vec![shape])];
    left.convention = Some(MarkingConvention {
        left_hand: true,
        yellow_centre: false,
    });
    let mesh = build(&left, carriageway_only(), 16, 0, 0, false);
    assert_eq!(
        mesh.carriageways[0].split, 0.5,
        "left-hand traffic mirrors it"
    );
    assert!(!mesh.yellow_centre);
}

/// A road the archive lists in the table but knows nothing about degrades to the same answer as
/// a tile with no table at all, rather than dividing by zero.
#[test]
fn a_road_with_an_empty_carriageway_row_falls_back_like_an_absent_one() {
    use tilecodec::mamaps::body::Carriageway;
    assert_eq!(split_for(4, Carriageway::default()), 0.0);
    let odd = split_for(3, Carriageway::default());
    assert!(
        (odd - -(1.0f32 / 3.0)).abs() < 1e-6,
        "three lanes gave {odd}"
    );
}

/// The dense lane detail is gated to high zoom, over the same ancestor window every other
/// layer uses: a tile builds what it will stand in for and no more.
#[test]
fn the_carriageway_is_not_built_below_its_zoom_window() {
    let body = carriageway_body(&[(4, false)]);
    assert!(
        build(&body, carriageway_only(), 11, 0, 0, false)
            .carriageways
            .is_empty(),
        "z11 stands in no deeper than z15, which is below the carriageway floor",
    );
    assert!(
        !build(
            &body,
            carriageway_only(),
            ROAD_LANE_MIN_ZOOM - ANCESTOR_DEPTH,
            0,
            0,
            false
        )
        .carriageways
        .is_empty(),
        "the deepest archive tile must build what it stands in for at z16",
    );
}
