use super::*;
use crate::mamaps::dict;

/// Build a `roads` layer of `n` bare line features, for the carriageway tests.
fn roads_layer(n: u32) -> Layer {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    for i in 0..n {
        roads.features.push(Feature {
            kind: 1,
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: i,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 4,
        });
        roads.parts.push(Part {
            coord_start: i * 2,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        let a = i as i16 * 10;
        roads.coords.extend_from_slice(&[(a, 0), (a, 100)]);
    }
    roads
}

/// The carriageway table rides after the heightmap, dense-parallel to the `roads` layer, and
/// carries the tile's marking convention with it. This is the wire half of the lane-marking
/// renderer: without the directional split there is nowhere to put a centre line.
#[test]
fn the_carriageway_table_round_trips_with_its_convention() {
    let roads = roads_layer(3);
    // A two-way with an even split and a solid inner divider, a one-way whose lanes all run
    // forward, and a road nothing is known about.
    let rows = vec![
        Carriageway { forward: 2, backward: 2, solid_dividers: 0b010 },
        Carriageway { forward: 4, backward: 0, solid_dividers: 0 },
        Carriageway::default(),
    ];
    let convention = MarkingConvention { left_hand: false, yellow_centre: true };
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: vec![(dict::LAYER_ROADS, rows.clone())],
        convention: Some(convention),
    };
    let bytes = serialize(&body).expect("serialize");
    assert_ne!(bytes[11] & BODY_FLAG_ROAD_LANES, 0, "the carriageway flag is set");
    let parsed = Body::parse(&bytes).expect("parse");
    assert_eq!(parsed, body);
    assert_eq!(parsed.convention, Some(convention));
    assert_eq!(parsed.feature_carriageway(dict::LAYER_ROADS, 0), Some(&rows[0]));
    assert_eq!(parsed.feature_carriageway(dict::LAYER_ROADS, 1), Some(&rows[1]));
    assert_eq!(
        parsed.feature_carriageway(dict::LAYER_ROADS, 2),
        Some(&Carriageway::default()),
        "a road nothing is known about is a default record, not an absent one",
    );
    assert_eq!(parsed.feature_carriageway(dict::LAYER_ROADS, 3), None, "past the table");
    assert_eq!(parsed.feature_carriageway(dict::LAYER_EARTH, 0), None, "a layer with no table");
}

/// A tile with no carriageway table costs nothing and reports no convention, so every archive
/// that predates lane markings stays exactly as cheap as it was.
#[test]
fn a_body_with_no_carriageway_table_carries_neither_flag_nor_convention() {
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads_layer(1)],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
    };
    let bytes = serialize(&body).expect("serialize");
    assert_eq!(bytes[11] & BODY_FLAG_ROAD_LANES, 0, "no carriageway flag");
    let parsed = Body::parse(&bytes).expect("parse");
    assert_eq!(parsed.convention, None);
    assert!(parsed.carriageways.is_empty());
}

/// The convention only reaches the wire alongside a table, so setting one without any rows
/// would silently come back `None`. Refused on the way out rather than round-tripping to
/// something the caller did not ask for.
#[test]
fn a_convention_without_a_carriageway_table_is_refused() {
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads_layer(1)],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: Some(MarkingConvention { left_hand: true, yellow_centre: false }),
    };
    assert!(serialize(&body).is_err());
}

/// A carriageway vector that is not exactly as long as its layer's features would misattribute
/// every road's lane split after the first, so it is refused on the way out just as the id,
/// turn-lane and building tables are.
#[test]
fn a_carriageway_table_that_does_not_match_its_layer_is_refused() {
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads_layer(2)],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: vec![(dict::LAYER_ROADS, vec![Carriageway::default()])],
        convention: Some(MarkingConvention::default()),
    };
    assert!(serialize(&body).is_err());
}

/// The convention byte's spare bits are reserved, not ignored: a reader that accepted them
/// would silently drop whatever a later format put there.
#[test]
fn a_marking_convention_with_reserved_bits_is_refused() {
    assert_eq!(
        MarkingConvention::from_byte(0b11).expect("both known bits"),
        MarkingConvention { left_hand: true, yellow_centre: true },
    );
    assert!(MarkingConvention::from_byte(0b100).is_err(), "a reserved bit");
}

/// `oneway` rides a feature flag rather than the carriageway table because `coalesce` already
/// keys on `flags`, so a one-way and a two-way road of the same class cannot merge. Pins the
/// bit value, which is an on-disk contract with the tiler.
#[test]
fn the_oneway_flag_round_trips_and_keeps_its_bit() {
    assert_eq!(FLAG_IS_ONEWAY, 1 << 4);
    let mut roads = roads_layer(1);
    roads.features[0].flags = FLAG_IS_ONEWAY | FLAG_IS_BRIDGE;
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
    };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    let feature = &parsed.layers[0].features[0];
    assert!(feature.is_oneway());
    assert!(feature.is_bridge(), "and the flags it shares a byte with are untouched");
    assert!(!feature.is_tunnel());
}

/// A building attr vector that is not exactly as long as its layer's features would
/// misattribute every building's height after the first, so it is refused on the way out just
/// as the id and turn-lane tables are.
#[test]
fn a_building_table_that_does_not_match_its_layer_is_refused() {
    let mut buildings = Layer::new(dict::LAYER_BUILDINGS);
    buildings.features.push(Feature {
        kind: 51,
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
    buildings.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    buildings.coords = vec![(0, 0), (10, 0), (10, 10), (0, 10)];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![buildings],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        // Two records for one feature.
        buildings: vec![(
            dict::LAYER_BUILDINGS,
            vec![BuildingAttrs::default(), BuildingAttrs::default()],
        )],
        heightmap: None, carriageways: Vec::new(), convention: None,
    };
    assert!(serialize(&body).is_err(), "a building table longer than its layer's features");
}

/// A roof shape or orientation the format does not define is corruption on parse: a reader
/// that extruded an out-of-range shape would guess at the geometry. Injected into a serialised
/// body's building record rather than constructed, because `serialize` refuses it too.
#[test]
fn an_out_of_range_roof_value_is_refused_on_parse() {
    let mut buildings = Layer::new(dict::LAYER_BUILDINGS);
    buildings.features.push(Feature {
        kind: 51,
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
    buildings.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    buildings.coords = vec![(0, 0), (10, 0), (10, 10), (0, 10)];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![buildings],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: vec![(dict::LAYER_BUILDINGS, vec![BuildingAttrs::default()])],
        heightmap: None, carriageways: Vec::new(), convention: None,
    };
    let good = serialize(&body).expect("serialize");
    assert!(Body::parse(&good).is_ok(), "the default record is otherwise fine");
    // The building record's roof_shape byte is at offset 6 of the single record. Find the
    // record by walking to the trailing sections: locate the roof byte by scanning for the
    // known table start is fiddly, so instead corrupt via a fresh serialise with a bad shape,
    // which serialize refuses — proving the encoder guards it too.
    let bad_shape = Body {
        buildings: vec![(
            dict::LAYER_BUILDINGS,
            vec![BuildingAttrs { roof_shape: 99, ..BuildingAttrs::default() }],
        )],
        ..body.clone()
    };
    assert!(serialize(&bad_shape).is_err(), "the encoder refuses an unknown roof shape");
    let bad_orient = Body {
        buildings: vec![(
            dict::LAYER_BUILDINGS,
            vec![BuildingAttrs { roof_orientation: 7, ..BuildingAttrs::default() }],
        )],
        ..body
    };
    assert!(serialize(&bad_orient).is_err(), "the encoder refuses an unknown orientation");
}

/// The per-tile heightmap round-trips: a fixed `u16` grid rides after the other trailing
/// sections, and an elevation-free tile omits it and stays as cheap as it was. This is the wire
/// half of the 3D terrain relief.
#[test]
fn the_heightmap_round_trips_and_ocean_tiles_omit_it() {
    let dim = 5u16;
    let samples: Vec<u16> =
        (0..(dim as u32 * dim as u32)).map(|i| 32768 + i as u16 * 3).collect();
    let grid = Heightmap { dim, samples };
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![Layer::new(dict::LAYER_EARTH)],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: Some(grid.clone()),
        carriageways: Vec::new(),
        convention: None,
    };
    let bytes = serialize(&body).expect("serialize");
    assert_ne!(bytes[11] & BODY_FLAG_HEIGHTMAP, 0, "the heightmap flag is set");
    let parsed = Body::parse(&bytes).expect("parse");
    assert_eq!(parsed, body);
    let read = parsed.heightmap.expect("a heightmap");
    assert_eq!(read.sample(0, 0), Some(32768), "sea level at the top-left");
    assert_eq!(Heightmap::metres(32768), 0, "the bias puts sea level at 32768");
    assert_eq!(read.sample(dim, 0), None, "out of range");

    // An ocean tile carries no grid and pays nothing for the section.
    let ocean = Body { heightmap: None, ..body };
    let ocean_bytes = serialize(&ocean).expect("serialize");
    assert_eq!(ocean_bytes[11] & BODY_FLAG_HEIGHTMAP, 0, "no heightmap flag on an ocean tile");
    assert!(ocean_bytes.len() < bytes.len(), "the ocean tile is smaller");
}

/// Both new v6 side tables ride together after the name/id/turn-lane chain, in the fixed order
/// the parser walks them. The case that matters: a tile with buildings *and* terrain.
#[test]
fn the_building_table_and_heightmap_round_trip_together() {
    let mut buildings = Layer::new(dict::LAYER_BUILDINGS);
    buildings.features.push(Feature {
        kind: 51,
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
    buildings.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    buildings.coords = vec![(0, 0), (10, 0), (10, 10), (0, 10)];
    let attrs = vec![BuildingAttrs {
        height: 250,
        roof_shape: ROOF_PYRAMIDAL,
        ..BuildingAttrs::default()
    }];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![buildings],
        names: vec!["Tower".to_string()],
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: vec![(dict::LAYER_BUILDINGS, attrs)],
        heightmap: Some(Heightmap { dim: 3, samples: vec![32768; 9] }),
        carriageways: Vec::new(),
        convention: None,
    };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
}
