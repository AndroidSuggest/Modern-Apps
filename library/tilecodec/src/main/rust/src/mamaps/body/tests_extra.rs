use super::*;
use crate::mamaps::dict;

/// A tile with one polygon carrying a hole and one road, which between them exercise every
/// field the format has.
fn sample() -> Body {
    let mut water = Layer::new(dict::LAYER_LANDTYPE);
    water.features.push(Feature {
        kind: 4,
        kind_detail: dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 2,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    water.parts.push(Part { coord_start: 0, point_count: 4, winding: WINDING_OUTER });
    water.parts.push(Part { coord_start: 4, point_count: 4, winding: WINDING_HOLE });
    water.coords = vec![
        (0, 0),
        (4096, 0),
        (4096, 4096),
        (0, 4096),
        (1000, 1000),
        (1000, 2000),
        (2000, 2000),
        (2000, 1000),
    ];

    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: 4,
        geom_type: GEOM_LINE,
        flags: FLAG_IS_BRIDGE | FLAG_IS_LINK,
        name_idx: NAME_NONE,
        parts_offset: 0,
        part_count: 1,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 0,
        transit_taper: 0,
        lane_count: 0,
    });
    roads.parts.push(Part { coord_start: 0, point_count: 3, winding: WINDING_OUTER });
    roads.coords = vec![(-64, 10), (2048, 2048), (4160, 4000)];

    Body { extent: DEFAULT_EXTENT, layers: vec![roads, water], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }
}

/// The turn-lane table rides beside the id and name tables, dense-parallel to a layer's
/// features: a road with `turn:lanes` carries its per-lane masks (forward and backward) and
/// one without carries an empty record, both surviving the round trip. This is the wire half
/// of the per-lane turn arrows.
#[test]
fn the_turn_lane_table_round_trips_dense_parallel_to_features() {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    for i in 0..3u32 {
        roads.features.push(Feature {
            kind: 45,
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
            lane_count: if i == 1 { 3 } else { 0 },
        });
        roads.parts.push(Part { coord_start: i * 2, point_count: 2, winding: WINDING_OUTER });
        roads.coords.extend_from_slice(&[(0, i as i16), (10, i as i16)]);
    }
    // Feature 1 is a three-lane road with turn arrows; the others carry none.
    let turns = vec![
        LaneTurns::default(),
        LaneTurns { forward: vec![1, 2, 6], backward: vec![4] },
        LaneTurns::default(),
    ];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: vec![(dict::LAYER_ROADS, turns)],
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
    let one = parsed.feature_turns(dict::LAYER_ROADS, 1).expect("a record");
    assert_eq!(one.forward, vec![1, 2, 6]);
    assert_eq!(one.backward, vec![4]);
    assert!(parsed.feature_turns(dict::LAYER_ROADS, 0).expect("a record").is_empty());
    assert_eq!(parsed.feature_turns(dict::LAYER_ROADS, 3), None, "past the table");
}

/// A turn-lane vector that is not exactly as long as its layer's features would misattribute
/// every lane past the mismatch, so it is refused on the way out just as the id table is.
#[test]
fn a_turn_lane_table_that_does_not_match_its_layer_is_refused() {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
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
    roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    roads.coords = vec![(0, 0), (10, 0)];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        // Two records for one feature.
        turn_lanes: vec![(dict::LAYER_ROADS, vec![LaneTurns::default(), LaneTurns::default()])],
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    assert!(serialize(&body).is_err(), "a turn table longer than its layer's features");
}

/// An id table with no name table beside it still parses. This is the case the v2 layout
/// could not express: it found the name table by "are there bytes left", which an id table
/// alone would have been mistaken for.
#[test]
fn an_id_table_parses_without_a_name_table() {
    let mut poi = Layer::new(dict::LAYER_POI);
    poi.features.push(Feature {
        kind: 1,
        kind_detail: dict::NONE,
        geom_type: GEOM_POINT,
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
    poi.parts.push(Part { coord_start: 0, point_count: 1, winding: WINDING_OUTER });
    poi.coords = vec![(1, 2)];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![poi],
        names: Vec::new(),
        ids: vec![(dict::LAYER_POI, vec![7])],
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    let parsed = Body::parse(&serialize(&body).expect("serialize")).expect("parse");
    assert_eq!(parsed, body);
    assert!(parsed.names.is_empty());
}

/// An id vector that is not exactly as long as its layer's features would misattribute every
/// id past the mismatch, so it is refused on the way in and on the way out.
#[test]
fn an_id_table_that_does_not_match_its_layer_is_refused() {
    let body = |ids| Body {
        extent: DEFAULT_EXTENT,
        layers: vec![Layer::new(dict::LAYER_LANDTYPE)],
        names: Vec::new(),
        ids,
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    assert!(
        serialize(&body(vec![(dict::LAYER_LANDTYPE, vec![1])])).is_err(),
        "one id for a layer with no features"
    );
    assert!(
        serialize(&body(vec![(dict::LAYER_POI, Vec::new())])).is_err(),
        "an entry for a layer the body does not carry"
    );
    assert!(
        serialize(&body(vec![(dict::LAYER_LANDTYPE, Vec::new()), (dict::LAYER_LANDTYPE, Vec::new())]))
            .is_err(),
        "two entries for one layer"
    );
}

/// A place label's baked boundary link round-trips through the region-link table, and a vector
/// that does not match its layer's feature count is refused exactly as the id table is.
#[test]
fn the_region_link_table_round_trips_and_is_validated() {
    let mut layer = Layer::new(dict::LAYER_PLACES);
    for i in 0..2u32 {
        layer.parts.push(Part {
            coord_start: i,
            point_count: 1,
            winding: WINDING_OUTER,
        });
        layer.coords.push((i as i16 * 10, i as i16 * 10));
        layer.features.push(Feature {
            kind: 1,
            kind_detail: 0,
            geom_type: GEOM_POINT,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: i,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
    }
    // First label links to boundary relation 42 (tagged as a relation: (42 << 2) | 3); second
    // links to nothing (REGION_NONE).
    let linked = (42u64 << 2) | 3;
    let body = |links| Body {
        extent: DEFAULT_EXTENT,
        layers: vec![layer.clone()],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
        region_links: links,
    };
    let good = body(vec![(dict::LAYER_PLACES, vec![linked, 0])]);
    let parsed = Body::parse(&serialize(&good).expect("serialize")).expect("parse");
    assert_eq!(parsed, good);
    assert_eq!(parsed.region_link(dict::LAYER_PLACES, 0), Some(linked));
    assert_eq!(parsed.region_link(dict::LAYER_PLACES, 1), Some(0));
    assert_eq!(parsed.region_link(dict::LAYER_ROADS, 0), None, "layer with no table");
    assert!(
        serialize(&body(vec![(dict::LAYER_PLACES, vec![linked])])).is_err(),
        "one link for a layer with two features"
    );
    assert!(
        serialize(&body(vec![(dict::LAYER_POI, vec![])])).is_err(),
        "an entry for a layer the body does not carry"
    );
}

/// The lane inputs ride in bytes the v2 record already reserved, so they round-trip with no
/// version bump. The last byte of the record stays reserved zero.
#[test]
fn the_transit_lane_inputs_round_trip_through_the_v2_record() {
    let mut layer = Layer::new(dict::LAYER_TRANSIT);
    for (i, (ordinal, lanes, taper)) in [(0u8, 3u8, 255u8), (1, 3, 128), (2, 3, 0)]
        .iter()
        .enumerate()
    {
        layer.parts.push(Part {
            coord_start: (i * 2) as u32,
            point_count: 2,
            winding: WINDING_OUTER,
        });
        layer.coords.extend_from_slice(&[(0, i as i16), (10, i as i16)]);
        layer.features.push(Feature {
            kind: 1,
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: i as u32,
            part_count: 1,
            transit_color: 0x00_54_A5,
            transit_ordinal: *ordinal,
            transit_lanes: *lanes,
            transit_taper: *taper,
            lane_count: 0,
        });
    }
    let mut body = Body::new(DEFAULT_EXTENT);
    body.layers.push(layer);
    let bytes = serialize(&body).expect("serialize");
    let parsed = Body::parse(&bytes).expect("parse");
    let read = parsed.layer(dict::LAYER_TRANSIT).expect("transit");
    assert_eq!(
        read.features
            .iter()
            .map(|f| (f.transit_ordinal, f.transit_lanes, f.transit_taper))
            .collect::<Vec<(u8, u8, u8)>>(),
        vec![(0, 3, 255), (1, 3, 128), (2, 3, 0)],
    );
    assert!(read.features.iter().all(|f| f.transit_color == 0x00_54_A5));
    // One layer, so the payload — and its feature records — start right after the index.
    let records_at = BODY_HEADER_LEN + LAYER_INDEX_LEN;
    for i in 0..3 {
        assert_eq!(
            bytes[records_at + i * FEATURE_RECORD_LEN + 23],
            0,
            "byte 23 of a v2 record is still reserved",
        );
    }
}

#[test]
fn a_feature_or_part_pointing_outside_its_own_tables_is_refused() {
    let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
    let cases: &[(&str, usize, u32)] = &[
        ("a part table past the payload", at + 8, 9_999),
        ("a feature with no geometry", at + 12, 0),
        ("more parts than the payload holds", at + 12, 9_999),
    ];
    for (what, offset, value) in cases {
        let mut bytes = serialize(&sample()).expect("serialize");
        bytes[*offset..*offset + 4].copy_from_slice(&value.to_le_bytes());
        assert!(Body::parse(&bytes).is_err(), "{what} should be refused");
    }
}

/// A truncated arena must fail rather than yield a short point list that silently draws a
/// different shape.
#[test]
fn an_arena_that_ends_early_is_refused() {
    let good = serialize(&sample()).expect("serialize");
    for cut in 1..6 {
        let mut bytes = good[..good.len() - cut].to_vec();
        let len = bytes.len() as u32;
        bytes[4..8].copy_from_slice(&len.to_le_bytes());
        assert!(Body::parse(&bytes).is_err(), "an arena {cut} bytes short");
    }
}

#[test]
fn a_winding_that_is_neither_outer_nor_hole_is_refused() {
    let mut bytes = serialize(&sample()).expect("serialize");
    // Water's part table follows its single 24-byte v2 feature record.
    let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN) + FEATURE_RECORD_LEN;
    bytes[at + 8..at + 10].copy_from_slice(&7u16.to_le_bytes());
    assert!(Body::parse(&bytes).is_err());
}

#[test]
fn an_unknown_feature_flag_is_refused() {
    let mut bytes = serialize(&sample()).expect("serialize");
    let at = align4(BODY_HEADER_LEN + 2 * LAYER_INDEX_LEN);
    bytes[at + 5] = 0x80;
    assert!(Body::parse(&bytes).is_err());
}

/// Byte-for-byte the same input gives byte-for-byte the same body, because nothing in the
/// encoder iterates a hash map. Invariant 5 of the plan's list, at the body level.
#[test]
fn serialising_the_same_body_twice_gives_the_same_bytes() {
    assert_eq!(serialize(&sample()).expect("a"), serialize(&sample()).expect("b"));
    // And assembling the layers in the other order does too, because they are sorted.
    let mut reordered = sample();
    reordered.layers.reverse();
    assert_eq!(serialize(&sample()).expect("a"), serialize(&reordered).expect("b"));
}

#[test]
fn a_layer_with_more_than_65535_features_round_trips_via_extended_encoding() {
    let mut layer = Layer::new(10);
    let n = 70000usize;
    for i in 0..n {
        layer.features.push(Feature {
            kind: 1,
            kind_detail: 0,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: i as u32,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        layer.parts.push(Part { coord_start: (i * 2) as u32, point_count: 2, winding: WINDING_OUTER });
        layer.coords.push((0, 0));
        layer.coords.push((1, 1));
    }
    let body = Body { extent: DEFAULT_EXTENT, layers: vec![layer], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() };
    let bytes = serialize(&body).expect("extended tile must serialize");
    assert_eq!(bytes[11], BODY_FLAG_EXTENDED_COUNTS, "extended flag set");
    let parsed = Body::parse(&bytes).expect("must parse extended");
    assert_eq!(parsed.layers[0].features.len(), n);
    assert_eq!(parsed.layers[0].parts.len(), n);
    // Non-extended path stays byte-identical for common case.
    let small_body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![{
        let mut l = Layer::new(10);
        l.features.push(Feature { kind: 1, kind_detail: 0, geom_type: GEOM_LINE, flags: 0, name_idx: NAME_NONE, parts_offset: 0, part_count: 1, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0 });
        l.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        l.coords.extend_from_slice(&[(0, 0), (1, 1)]);
        l
        }],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    let small_bytes = serialize(&small_body).expect("small tile");
    assert_eq!(small_bytes[11], 0, "common path no flag, byte-identical");
    assert_eq!(small_bytes[12..16], [0, 0, 0, 0], "reserved still zero for common path");
}

/// The S3DB building table rides beside the name and id tables, dense-parallel to the
/// `buildings` layer's features: a tagged building carries its height, roof and colours, a
/// plain one a default record, and both survive the round trip. This is the wire half of the
/// 3D building extrusion.
#[test]
fn the_building_table_round_trips_dense_parallel_to_features() {
    let mut buildings = Layer::new(dict::LAYER_BUILDINGS);
    for i in 0..3u32 {
        buildings.features.push(Feature {
            kind: 51,
            kind_detail: 0,
            geom_type: GEOM_POLYGON,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: i,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        buildings.parts.push(Part {
            coord_start: i * 4,
            point_count: 4,
            winding: WINDING_OUTER,
        });
        let b = i as i16;
        buildings.coords.extend_from_slice(&[(0, b), (10, b), (10, b + 10), (0, b + 10)]);
    }
    // A tall gabled tower with colours, a floating glass part, and a plain default building.
    let attrs = vec![
        BuildingAttrs {
            height: 1200,
            min_height: 0,
            roof_height: 300,
            roof_shape: ROOF_GABLED,
            roof_direction: 64,
            roof_orientation: ROOF_ORIENT_ACROSS,
            building_colour: 0xFF_C8_A0_78,
            roof_colour: 0xFF_80_20_20,
        },
        BuildingAttrs {
            height: 400,
            min_height: 250,
            roof_height: 0,
            roof_shape: ROOF_FLAT,
            roof_direction: 0,
            roof_orientation: ROOF_ORIENT_ALONG,
            building_colour: 0,
            roof_colour: 0,
        },
        BuildingAttrs::default(),
    ];
    let body = Body {
        extent: DEFAULT_EXTENT,
        layers: vec![buildings],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: vec![(dict::LAYER_BUILDINGS, attrs.clone())],
        heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new(),
    };
    let bytes = serialize(&body).expect("serialize");
    // The body carries the reader's own version byte, so an older reader rejects it cleanly.
    // Asserted against the constant rather than a literal: this test is about the building
    // table, and pinning the number here made every later format bump fail here first.
    assert_eq!(bytes[3], crate::mamaps::header::FORMAT_VERSION, "the body's format version");
    assert_ne!(bytes[11] & BODY_FLAG_BUILDING_TABLE, 0, "the building flag is set");
    let parsed = Body::parse(&bytes).expect("parse");
    assert_eq!(parsed, body);
    assert_eq!(parsed.building_attrs(dict::LAYER_BUILDINGS, 0), Some(attrs[0]));
    assert_eq!(parsed.building_attrs(dict::LAYER_BUILDINGS, 1), Some(attrs[1]));
    assert_eq!(
        parsed.building_attrs(dict::LAYER_BUILDINGS, 2),
        Some(BuildingAttrs::default()),
    );
    assert_eq!(parsed.building_attrs(dict::LAYER_BUILDINGS, 3), None, "past the table");
    assert_eq!(parsed.building_attrs(dict::LAYER_ROADS, 0), None, "a layer with no table");
}
