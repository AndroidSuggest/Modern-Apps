/// Dense-node group for an arbitrary node list. Ids must ascend, as they do in a
/// real PBF.
fn dense_group(nodes: &[FixtureNode]) -> Vec<u8> {
    let mut keys_vals: Vec<u32> = Vec::new();
    for (_, _, _, tags) in nodes {
        for (k, v) in tags {
            keys_vals.push(*k);
            keys_vals.push(*v);
        }
        keys_vals.push(ST_EMPTY);
    }
    let mut out = Vec::new();
    packed_delta(1, &nodes.iter().map(|n| n.0).collect::<Vec<_>>(), &mut out);
    packed_delta(8, &nodes.iter().map(|n| n.1 as i64).collect::<Vec<_>>(), &mut out);
    packed_delta(9, &nodes.iter().map(|n| n.2 as i64).collect::<Vec<_>>(), &mut out);
    packed_u32(10, &keys_vals, &mut out);
    out
}

/// The `PrimitiveBlock` for the vector-layer fixture. All nodes sit in San
/// Francisco, around 37.77N 122.41W, so a plausible `--bbox` includes them and an
/// Atlantic one does not.
pub fn layers_block() -> Vec<u8> {
    let mut st = StringTable::new();

    let k_highway = st.id("highway");
    let k_man_made = st.id("man_made");
    let k_surveillance_type = st.id("surveillance:type");
    let k_operator = st.id("operator");
    let k_direction = st.id("direction");
    let k_amenity = st.id("amenity");
    let k_name = st.id("name");
    let k_maxspeed = st.id("maxspeed");
    let k_maxspeed_forward = st.id("maxspeed:forward");
    let k_railway = st.id("railway");
    let k_type = st.id("type");
    let k_route = st.id("route");
    let k_ref = st.id("ref");
    let k_colour = st.id("colour");
    let k_boundary = st.id("boundary");
    let k_admin_level = st.id("admin_level");
    // roads: the attributes the `roads` layer carries beyond geometry and class.
    let k_lanes = st.id("lanes");
    let k_turn_lanes_forward = st.id("turn:lanes:forward");
    let k_oneway = st.id("oneway");
    let k_width = st.id("width");
    let k_bridge = st.id("bridge");
    let k_layer = st.id("layer");

    let v_speed_camera = st.id("speed_camera");
    let v_surveillance = st.id("surveillance");
    let v_alpr = st.id("ALPR");
    let v_flock = st.id("Flock Safety");
    let v_forward = st.id("forward");
    let v_stop = st.id("stop");
    let v_traffic_signals = st.id("traffic_signals");
    let v_cafe = st.id("cafe");
    let v_corner_cafe = st.id("Corner Cafe");
    let v_residential = st.id("residential");
    let v_motorway = st.id("motorway");
    let v_service = st.id("service");
    let v_25mph = st.id("25 mph");
    let v_none = st.id("none");
    let v_30mph = st.id("30 mph");
    let v_main_st = st.id("Main St");
    let v_subway = st.id("subway");
    let v_narrow_gauge = st.id("narrow_gauge");
    let v_platform = st.id("platform");
    let v_market_st = st.id("Market St Subway");
    let v_route = st.id("route");
    let v_bus = st.id("bus");
    let v_red_line = st.id("Red Line");
    let v_red = st.id("Red");
    let v_da291c = st.id("#DA291C");
    let v_boundary = st.id("boundary");
    let v_administrative = st.id("administrative");
    let v_eight = st.id("8");
    let v_six = st.id("6");
    let v_oakland = st.id("Oakland");
    let v_alameda = st.id("Alameda County");
    let v_three = st.id("3");
    let v_through_through_right = st.id("through|through|right");
    let v_yes = st.id("yes");
    let v_12m = st.id("12 m");
    let v_one = st.id("1");
    let role_outer = st.id("outer");
    let role_inner = st.id("inner");
    let role_platform = st.id("platform");
    // An empty member role is string-table index 0, per the PBF spec.
    let role_empty = ST_EMPTY;

    // Ids ascend, as they do in a real PBF.
    let nodes: Vec<FixtureNode> = vec![
        (
            CAMERA_NODE_ID,
            377_749_000,
            -1_224_194_000,
            vec![(k_highway, v_speed_camera), (k_direction, v_forward)],
        ),
        (
            ALPR_NODE_ID,
            377_770_000,
            -1_224_170_000,
            vec![
                (k_man_made, v_surveillance),
                (k_surveillance_type, v_alpr),
                (k_operator, v_flock),
            ],
        ),
        (
            STOP_SIGN_NODE_ID,
            377_800_000,
            -1_224_150_000,
            vec![(k_highway, v_stop)],
        ),
        (
            SIGNALS_NODE_ID,
            377_810_000,
            -1_224_140_000,
            vec![(k_highway, v_traffic_signals)],
        ),
        (
            LAYERS_CAFE_NODE_ID,
            377_820_000,
            -1_224_130_000,
            vec![(k_amenity, v_cafe), (k_name, v_corner_cafe)],
        ),
        // Untagged nodes the ways are built from, laid out along 37.79N.
        (WAY_NODE_IDS[0], 377_900_000, -1_224_300_000, vec![]),
        (WAY_NODE_IDS[1], 377_900_000, -1_224_200_000, vec![]),
        (WAY_NODE_IDS[2], 377_900_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[3], 377_910_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[4], 377_920_000, -1_224_100_000, vec![]),
        (WAY_NODE_IDS[5], 377_930_000, -1_224_100_000, vec![]),
    ];
    let mut nodes = nodes;
    for (id, lat, lon) in ADMIN_NODES.into_iter().chain(ROUTE_PLATFORM_NODES) {
        nodes.push((id, lat, lon, vec![]));
    }
    let dense = dense_group(&nodes);

    let ways = [
        // maxspeed: a raw "25 mph", with a highway and a name to carry.
        way(
            MAXSPEED_WAY_ID,
            &[
                (k_highway, v_residential),
                (k_maxspeed, v_25mph),
                (k_name, v_main_st),
            ],
            &WAY_NODE_IDS[..3],
        ),
        // maxspeed=none, plus a directional tag that must NOT win over it. Also the
        // `roads` layer's fully-attributed way: a oneway bridge with lanes, turn
        // lanes, a width and a layer.
        way(
            MAXSPEED_NONE_WAY_ID,
            &[
                (k_highway, v_motorway),
                (k_maxspeed, v_none),
                (k_maxspeed_forward, v_30mph),
                (k_lanes, v_three),
                (k_turn_lanes_forward, v_through_through_right),
                (k_oneway, v_yes),
                (k_width, v_12m),
                (k_bridge, v_yes),
                (k_layer, v_one),
            ],
            &WAY_NODE_IDS[2..5],
        ),
        // No speed limit at all.
        way(NO_MAXSPEED_WAY_ID, &[(k_highway, v_service)], &WAY_NODE_IDS[..2]),
        // transit_lines: a subway way with a name, and a member of the route below.
        way(
            RAILWAY_WAY_ID,
            &[(k_railway, v_subway), (k_name, v_market_st)],
            &WAY_NODE_IDS[..3],
        ),
        // narrow_gauge folds into rail; also the route's second member.
        way(
            NARROW_GAUGE_WAY_ID,
            &[(k_railway, v_narrow_gauge)],
            &WAY_NODE_IDS[3..6],
        ),
        // A platform is not a line.
        way(PLATFORM_WAY_ID, &[(k_railway, v_platform)], &WAY_NODE_IDS[..2]),
        // And a closed one, which the route relation below claims as `role=platform`.
        way(
            ROUTE_PLATFORM_WAY_ID,
            &[(k_railway, v_platform)],
            &[2201, 2202, 2203, 2204, 2201],
        ),
        // admin_city: the outer ring split across two ways, so the assembler has to
        // stitch them, and one of them is reversed so it has to flip one too.
        way(ADMIN_OUTER_WAY_A, &[], &[2101, 2102, 2103]),
        way(ADMIN_OUTER_WAY_B, &[], &[2101, 2104, 2103]),
        // The hole, closed on its own.
        way(ADMIN_INNER_WAY, &[], &[2111, 2112, 2113, 2114, 2111]),
    ];

    let rels = [
        // A route relation over two member ways, so its geometry is a genuine
        // MultiLineString rather than a single part.
        relation(
            ROUTE_RELATION_ID,
            &[
                (k_type, v_route),
                (k_route, v_subway),
                (k_name, v_red_line),
                (k_ref, v_red),
                (k_colour, v_da291c),
            ],
            &[
                (RAILWAY_WAY_ID, role_empty, 1),
                (NARROW_GAUGE_WAY_ID, role_empty, 1),
                (ROUTE_PLATFORM_WAY_ID, role_platform, 1),
            ],
        ),
        // A bus route: this layer is the rail-like modes only.
        relation(
            BUS_RELATION_ID,
            &[(k_type, v_route), (k_route, v_bus)],
            &[(RAILWAY_WAY_ID, role_empty, 1)],
        ),
        // An admin_level=8 boundary. One member is unroled, which the OSM boundary
        // convention says is outer -- the case that matters most, since most real
        // members are unroled.
        relation(
            ADMIN_CITY_RELATION_ID,
            &[
                (k_type, v_boundary),
                (k_boundary, v_administrative),
                (k_admin_level, v_eight),
                (k_name, v_oakland),
            ],
            &[
                (ADMIN_OUTER_WAY_A, role_outer, 1),
                (ADMIN_OUTER_WAY_B, role_empty, 1),
                (ADMIN_INNER_WAY, role_inner, 1),
            ],
        ),
        // A county: same shape, wrong level.
        relation(
            ADMIN_COUNTY_RELATION_ID,
            &[
                (k_type, v_boundary),
                (k_boundary, v_administrative),
                (k_admin_level, v_six),
                (k_name, v_alameda),
            ],
            &[(ADMIN_OUTER_WAY_A, role_outer, 1), (ADMIN_OUTER_WAY_B, role_empty, 1)],
        ),
    ];

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for w in &ways {
        bytes_field(3, w, &mut way_group);
    }
    let mut rel_group = Vec::new();
    for r in &rels {
        bytes_field(4, r, &mut rel_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    bytes_field(2, &rel_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    block
}

/// Write the vector-layer fixture to a unique temp directory.
pub fn write_layers_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &pbf_from_block(&layers_block()))
}

// ---- the dangling-reference fixture --------------------------------------
//
// A THIRD fixture, for one hole: a routable way referencing a node the file never
// defines. Real extracts are full of these — every way clipped at an extract's
// boundary keeps refs to nodes outside it — and the road graph's whole handling
// of them is "the pair fails to resolve, so skip it", which nothing tested.
//
// It matters more than it looks. The node-id bitset is marked from way refs, so a
// dangling ref *is* marked, and any scheme that treats the marked set as the node
// address space hands it a slot. A slot that never receives coordinates reads as
// (0, 0) — null island — which corrupts distances, spatial keys and polylines
// while leaving every count plausible.

/// A node id no element in this fixture defines, referenced from the middle of a
/// routable way.
pub const DANGLING_REF_ID: i64 = 999;
pub const DANGLING_WAY_ID: i64 = 103;

/// `(id, lat_e7, lon_e7)` of the nodes this fixture does define.
pub const DANGLING_NODES: [(i64, i32, i32); 3] = [
    (1, 370_000_000, -1_220_000_000),
    (2, 370_010_000, -1_220_000_000),
    (3, 370_020_000, -1_220_000_000),
];

/// One routable way over `[1, 2, 999, 3]`, so the dangling ref sits between two
/// present nodes and breaks two consecutive pairs rather than one.
fn dangling_block() -> Vec<u8> {
    let mut st = StringTable::new();
    let k_hw = st.id("highway");
    let k_name = st.id("name");
    let v_residential = st.id("residential");
    let v_gap = st.id("Gap St");

    let nodes: Vec<FixtureNode> = DANGLING_NODES
        .iter()
        .map(|(id, lat, lon)| (*id, *lat, *lon, Vec::new()))
        .collect();
    let dense = dense_group(&nodes);
    let ways = [way(
        DANGLING_WAY_ID,
        &[(k_hw, v_residential), (k_name, v_gap)],
        &[1, 2, DANGLING_REF_ID, 3],
    )];

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for w in &ways {
        bytes_field(3, w, &mut way_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    block
}

/// Write the dangling-reference fixture to a unique temp directory.
pub fn write_dangling_sample(tag: &str) -> (std::path::PathBuf, std::path::PathBuf) {
    write_pbf(tag, &pbf_from_block(&dangling_block()))
}

// ---- an arbitrary graph shape --------------------------------------------
//
// The three fixtures above each say something specific and the tests assert exact
// counts against them, so growing one to cover a new topology makes every test
// that reads it slightly less about what it was testing. Chain cutting is all
// topology, though, and needs many shapes: a ring, a self-touching way, two ways
// meeting end-to-end, a repeated ref. This builds a PBF from a shape stated
// directly in the test.

/// A PBF of untagged nodes plus `highway=residential` ways over them.
///
/// Deliberately minimal: no names, no lanes, no speed limits and no oneways, so
/// every way agrees with every other on attributes and the *only* thing deciding
/// where chains are cut is the graph's shape.
pub fn write_shape_sample(
    tag: &str,
    nodes: &[(i64, i32, i32)],
    ways: &[(i64, &[i64])],
) -> (std::path::PathBuf, std::path::PathBuf) {
    let mut st = StringTable::new();
    let k_hw = st.id("highway");
    let v_residential = st.id("residential");

    let fixture: Vec<FixtureNode> = nodes
        .iter()
        .map(|(id, lat, lon)| (*id, *lat, *lon, Vec::new()))
        .collect();
    let dense = dense_group(&fixture);

    let mut node_group = Vec::new();
    bytes_field(2, &dense, &mut node_group);
    let mut way_group = Vec::new();
    for (id, refs) in ways {
        bytes_field(3, &way(*id, &[(k_hw, v_residential)], refs), &mut way_group);
    }

    let mut block = Vec::new();
    bytes_field(1, &st.encode(), &mut block);
    bytes_field(2, &node_group, &mut block);
    bytes_field(2, &way_group, &mut block);
    varint_field(17, 100, &mut block); // granularity
    write_pbf(tag, &pbf_from_block(&block))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stored_deflate_round_trips_through_miniz() {
        for payload in [
            b"".to_vec(),
            b"hello".to_vec(),
            (0..200_000u32).map(|i| i as u8).collect::<Vec<u8>>(),
        ] {
            let z = zlib_store(&payload);
            let back = miniz_oxide::inflate::decompress_to_vec_zlib(&z).unwrap();
            assert_eq!(back, payload, "len {}", payload.len());
        }
    }

    #[test]
    fn adler32_matches_the_reference_value() {
        // The canonical zlib test vector.
        assert_eq!(adler32(b"Wikipedia"), 0x11E6_0398);
        assert_eq!(adler32(b""), 1);
    }

    #[test]
    fn a_corrupt_adler_trailer_is_rejected() {
        let mut z = zlib_store(b"payload");
        let last = z.len() - 1;
        z[last] ^= 0xFF;
        assert!(miniz_oxide::inflate::decompress_to_vec_zlib(&z).is_err());
    }
}
