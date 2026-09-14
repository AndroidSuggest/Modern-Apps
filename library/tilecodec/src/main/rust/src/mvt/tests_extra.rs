use super::*;
use crate::proto;

// --- line encoding -----------------------------------------------------

fn line_roundtrip(lines: Vec<Vec<(i32, i32)>>) {
    let encoded = encode_lines(&lines);
    let decoded = decode_lines(&encoded).expect("our own output must decode");
    assert_eq!(decoded, lines);
}

#[test]
fn a_line_round_trips() {
    line_roundtrip(vec![vec![(0, 0), (100, 0), (100, 100)]]);
    // Negative deltas, and a doubling-back.
    line_roundtrip(vec![vec![(500, 500), (0, 0), (500, 500), (-50, 20)]]);
    // Multiple parts share one cursor, which is where an off-by-one in the
    // delta chain would show up.
    line_roundtrip(vec![
        vec![(0, 0), (10, 10)],
        vec![(4000, 4000), (4090, 4000)],
        vec![(-5, -5), (0, 0), (5, 5)],
    ]);
}

#[test]
fn the_line_command_stream_has_the_shape_the_spec_requires() {
    let g = encode_lines(&[vec![(3, 6), (8, 12), (20, 34)]]);
    // MoveTo(1), one point, LineTo(2), two points.
    assert_eq!(command_parts(g[0]), (CMD_MOVE_TO, 1));
    assert_eq!(command_parts(g[3]), (CMD_LINE_TO, 2));
    assert_eq!(g.len(), 1 + 2 + 1 + 4);
    // First point is an absolute-from-origin delta.
    assert_eq!(proto::zigzag_decode(g[1] as u64), 3);
    assert_eq!(proto::zigzag_decode(g[2] as u64), 6);
    // Second is relative to the first.
    assert_eq!(proto::zigzag_decode(g[4] as u64), 5);
    assert_eq!(proto::zigzag_decode(g[5] as u64), 6);
}

#[test]
fn a_degenerate_line_part_is_skipped_not_emitted() {
    // A lone MoveTo would encode a point inside a line layer, and LineTo(0) is
    // illegal outright.
    assert!(encode_lines(&[vec![]]).is_empty());
    assert!(encode_lines(&[vec![(1, 1)]]).is_empty());
    // The valid parts around it still come through.
    let g = encode_lines(&[vec![(1, 1)], vec![(0, 0), (5, 5)], vec![]]);
    assert_eq!(decode_lines(&g).unwrap(), vec![vec![(0, 0), (5, 5)]]);
}

#[test]
fn decode_lines_rejects_streams_that_are_not_lines() {
    // A multipoint MoveTo(3).
    assert!(decode_lines(&encode_points(&[(1, 1), (2, 2), (3, 3)])).is_none());
    // A LineTo with no preceding MoveTo.
    assert!(decode_lines(&[command(CMD_LINE_TO, 1), 2, 2]).is_none());
    // A ClosePath belongs to a polygon.
    assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_CLOSE_PATH, 1)]).is_none());
    // Truncated payload.
    assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2]).is_none());
    assert!(decode_lines(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_LINE_TO, 2), 1, 1]).is_none());
    // Empty is a valid empty geometry, not an error.
    assert_eq!(decode_lines(&[]), Some(vec![]));
}

// --- polygon encoding: orientation, closure, holes ---------------------

/// The unit square as an exterior ring, positive area.
fn ext() -> Vec<(i32, i32)> {
    vec![(0, 0), (0, 100), (100, 100), (100, 0), (0, 0)]
}

/// A smaller square inside it, given in the *same* direction as `ext` -- so the
/// encoder has to flip it.
fn hole() -> Vec<(i32, i32)> {
    vec![(20, 20), (20, 80), (80, 80), (80, 20), (20, 20)]
}

#[test]
fn the_encoder_derives_orientation_rather_than_trusting_the_input() {
    // Both rings arrive wound the same way. The clipper and the simplifier
    // upstream do not preserve orientation, so the input's winding means
    // nothing and the encoder must fix both.
    let g = encode_polygons(&[vec![ext(), hole()]]);
    let decoded = decode_polygons(&g).expect("our own output must decode");
    assert_eq!(decoded.len(), 1, "one polygon: {decoded:?}");
    assert_eq!(decoded[0].len(), 2, "exterior plus one hole");
    assert!(signed_area(&decoded[0][0]) > 0, "exterior must be positive");
    assert!(signed_area(&decoded[0][1]) < 0, "interior must be negative");

    // And the same when the input is wound the other way round.
    let mut e = ext();
    e.reverse();
    let mut h = hole();
    h.reverse();
    let decoded = decode_polygons(&encode_polygons(&[vec![e, h]])).unwrap();
    assert!(signed_area(&decoded[0][0]) > 0);
    assert!(signed_area(&decoded[0][1]) < 0);
}

#[test]
fn every_ring_is_terminated_by_close_path_and_holes_follow_their_exterior() {
    let g = encode_polygons(&[vec![ext(), hole()]]);
    let mut commands = Vec::new();
    let mut i = 0;
    while i < g.len() {
        let (cmd, count) = command_parts(g[i]);
        commands.push(cmd);
        i += 1 + if cmd == CMD_CLOSE_PATH { 0 } else { count as usize * 2 };
    }
    assert_eq!(
        commands,
        vec![
            CMD_MOVE_TO, CMD_LINE_TO, CMD_CLOSE_PATH, // exterior
            CMD_MOVE_TO, CMD_LINE_TO, CMD_CLOSE_PATH, // its hole
        ]
    );
}

#[test]
fn the_explicit_closing_vertex_is_stripped_because_close_path_implies_it() {
    // Same square, once closed and once not: the streams must be identical.
    let mut open = ext();
    open.pop();
    assert_eq!(encode_polygons(&[vec![ext()]]), encode_polygons(&[vec![open]]));
    // 4 corners: MoveTo(1) + 2 + LineTo(3) + 6 + ClosePath = 11 integers. A
    // retained closing vertex would make it LineTo(4) and 13.
    assert_eq!(encode_polygons(&[vec![ext()]]).len(), 11);
    // A ring closed several times over is still stripped to its corners.
    let mut twice = ext();
    twice.push((0, 0));
    assert_eq!(encode_polygons(&[vec![twice]]).len(), 11);
}

#[test]
fn a_polygon_round_trips_with_its_rings_closed() {
    let decoded = decode_polygons(&encode_polygons(&[vec![ext(), hole()]])).unwrap();
    for ring in &decoded[0] {
        assert_eq!(ring.first(), ring.last(), "decode re-closes every ring");
        assert!(ring.len() >= 4);
    }
    // Geometry is preserved up to orientation and rotation of the vertex list.
    assert_eq!(signed_area(&decoded[0][0]).abs(), signed_area(&ext()).abs());
    assert_eq!(signed_area(&decoded[0][1]).abs(), signed_area(&hole()).abs());
}

#[test]
fn several_polygons_are_grouped_by_orientation_on_the_way_back() {
    let far = vec![
        vec![(1000, 1000), (1000, 1100), (1100, 1100), (1100, 1000), (1000, 1000)],
        vec![(1020, 1020), (1020, 1080), (1080, 1080), (1080, 1020), (1020, 1020)],
    ];
    let g = encode_polygons(&[vec![ext(), hole()], far]);
    let decoded = decode_polygons(&g).unwrap();
    assert_eq!(decoded.len(), 2, "two polygons: {decoded:?}");
    assert_eq!(decoded[0].len(), 2);
    assert_eq!(decoded[1].len(), 2);
    for poly in &decoded {
        assert!(signed_area(&poly[0]) > 0);
        assert!(signed_area(&poly[1]) < 0);
    }
}

#[test]
fn a_zero_area_or_too_short_ring_is_dropped() {
    // Collinear, so it cannot be oriented.
    assert!(encode_polygons(&[vec![vec![(0, 0), (5, 0), (10, 0), (0, 0)]]]).is_empty());
    assert!(encode_polygons(&[vec![vec![(0, 0), (5, 5)]]]).is_empty());
    assert!(encode_polygons(&[vec![vec![]]]).is_empty());
    // A degenerate hole is dropped, the exterior kept.
    let g = encode_polygons(&[vec![ext(), vec![(5, 5), (6, 5), (7, 5), (5, 5)]]]);
    let decoded = decode_polygons(&g).unwrap();
    assert_eq!(decoded[0].len(), 1, "exterior only");
}

#[test]
fn losing_the_exterior_ring_drops_its_holes_too() {
    // A hole with nothing around it renders as solid fill in the layer's
    // colour, which is worse than the feature being absent.
    let g = encode_polygons(&[vec![vec![(0, 0), (5, 0), (10, 0), (0, 0)], hole()]]);
    assert!(g.is_empty(), "{g:?}");
    // The polygon after it is unaffected.
    let g = encode_polygons(&[
        vec![vec![(0, 0), (5, 0), (10, 0)], hole()],
        vec![ext()],
    ]);
    assert_eq!(decode_polygons(&g).unwrap().len(), 1);
}

#[test]
fn close_path_resets_the_cursor_to_the_rings_start() {
    // The spec says ClosePath returns the cursor to the ring's first vertex, so
    // the next ring's MoveTo delta is measured from there. Getting this wrong
    // puts every ring after the first in the wrong place, which the round trip
    // is the only thing that catches.
    let a = vec![(0, 0), (0, 10), (10, 10), (10, 0), (0, 0)];
    let b = vec![(500, 500), (500, 510), (510, 510), (510, 500), (500, 500)];
    let decoded = decode_polygons(&encode_polygons(&[vec![a.clone()], vec![b.clone()]])).unwrap();
    assert_eq!(decoded.len(), 2);
    let corners = |ring: &[(i32, i32)]| {
        let mut c: Vec<(i32, i32)> = ring[..ring.len() - 1].to_vec();
        c.sort_unstable();
        c
    };
    assert_eq!(corners(&decoded[0][0]), corners(&a));
    assert_eq!(corners(&decoded[1][0]), corners(&b));
}

#[test]
fn decode_polygons_rejects_malformed_streams() {
    // Opens with a hole: there is nothing to attach it to, and guessing would
    // silently turn a hole into a fill.
    let ring = [(20, 80), (80, 80), (80, 20), (20, 20)];
    assert!(signed_area(&ring) < 0, "a hole by area, not by intent");
    let mut g = vec![command(CMD_MOVE_TO, 1)];
    let (mut cx, mut cy) = (0i32, 0i32);
    push_delta(&mut g, ring[0], &mut cx, &mut cy);
    g.push(command(CMD_LINE_TO, 3));
    for p in &ring[1..] {
        push_delta(&mut g, *p, &mut cx, &mut cy);
    }
    g.push(command(CMD_CLOSE_PATH, 1));
    assert!(decode_polygons(&g).is_none(), "a leading hole is rejected");

    // Unterminated ring (no ClosePath).
    assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_LINE_TO, 2), 1, 1, 1, 1]).is_none());
    // ClosePath with too few vertices.
    assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_CLOSE_PATH, 1)]).is_none());
    // A second MoveTo before the ring is closed.
    assert!(decode_polygons(&[command(CMD_MOVE_TO, 1), 2, 2, command(CMD_MOVE_TO, 1), 2, 2]).is_none());
    assert_eq!(decode_polygons(&[]), Some(vec![]));
}

#[test]
fn the_decoders_do_not_confuse_each_others_geometry() {
    let points = encode_points(&[(1, 1), (2, 2)]);
    let lines = encode_lines(&[vec![(0, 0), (10, 10)]]);
    let polys = encode_polygons(&[vec![ext()]]);
    assert!(decode_points(&points).is_some());
    assert!(decode_points(&lines).is_none());
    assert!(decode_points(&polys).is_none());
    assert!(decode_lines(&points).is_none());
    assert!(decode_lines(&lines).is_some());
    assert!(decode_lines(&polys).is_none());
    assert!(decode_polygons(&points).is_none());
    // A line stream has no ClosePath, so it decodes as no polygons at all
    // rather than as a bogus one.
    assert_eq!(decode_polygons(&lines), None);
    assert!(decode_polygons(&polys).is_some());
}

// --- golden byte fixture ----------------------------------------------

#[test]
fn a_line_and_polygon_tile_encodes_to_exactly_these_bytes() {
    // A golden fixture, computed by hand from the wire layout in the module
    // docs. It pins the whole encode path -- field numbers, field order, packed
    // geometry, the property dictionaries -- so a change anywhere in it has to
    // be deliberate rather than incidental.
    let mut roads = Layer::new("roads");
    roads.features.push(Feature {
        id: None,
        geom_type: GeomType::LineString,
        geometry: encode_lines(&[vec![(0, 0), (2, 4)]]),
        props: vec![("kind".to_string(), Value::String("rail".into()))],
    });
    let body = Tile { layers: vec![roads] }.encode();

    // layer message:
    //   1 name  "roads"                     0a 05 72 6f 61 64 73
    //   2 feature:
    //        2 tags   [0, 0]                12 02 00 00
    //        3 type   2 (LineString)        18 02
    //        4 geom   [9, 0, 0, 10, 4, 8]   22 06 09 00 00 0a 04 08
    //   3 keys  "kind"                      1a 04 6b 69 6e 64
    //   4 values { 1: "rail" }              22 06 0a 04 72 61 69 6c
    //   5 extent 4096                       28 80 20
    //  15 version 2                         78 02
    #[rustfmt::skip]
    let layer: Vec<u8> = vec![
        0x0a, 0x05, b'r', b'o', b'a', b'd', b's',
        0x12, 0x0e,
            0x12, 0x02, 0x00, 0x00,
            0x18, 0x02,
            0x22, 0x06, 0x09, 0x00, 0x00, 0x0a, 0x04, 0x08,
        0x1a, 0x04, b'k', b'i', b'n', b'd',
        0x22, 0x06, 0x0a, 0x04, b'r', b'a', b'i', b'l',
        0x28, 0x80, 0x20,
        0x78, 0x02,
    ];
    let mut expected: Vec<u8> = vec![0x1a, layer.len() as u8];
    expected.extend_from_slice(&layer);
    assert_eq!(body, expected, "encoded {body:02x?}");

    // And it reads back as what it claims to be.
    let tile = Tile::decode(&body).unwrap();
    let f = &tile.layer("roads").unwrap().features[0];
    assert_eq!(f.geom_type, GeomType::LineString);
    assert_eq!(decode_lines(&f.geometry).unwrap(), vec![vec![(0, 0), (2, 4)]]);
    assert_eq!(f.get("kind"), Some(&Value::String("rail".into())));
}

#[test]
fn a_polygon_features_geometry_survives_a_tile_round_trip() {
    // The encoders feed Feature::geometry, which the tile codec treats as
    // opaque -- so the two halves have to agree on the stream, and this is what
    // proves they do end to end.
    let mut admin = Layer::new("admin_city");
    admin.features.push(Feature {
        id: Some(7),
        geom_type: GeomType::Polygon,
        geometry: encode_polygons(&[vec![ext(), hole()]]),
        props: vec![("name".to_string(), Value::String("Oakland".into()))],
    });
    let tile = Tile::decode(&Tile { layers: vec![admin] }.encode()).unwrap();
    let f = &tile.layer("admin_city").unwrap().features[0];
    assert_eq!(f.id, Some(7));
    assert_eq!(f.geom_type, GeomType::Polygon);
    let rings = decode_polygons(&f.geometry).unwrap();
    assert_eq!(rings.len(), 1);
    assert_eq!(rings[0].len(), 2);
    assert!(signed_area(&rings[0][0]) > 0);
    assert!(signed_area(&rings[0][1]) < 0);
}
