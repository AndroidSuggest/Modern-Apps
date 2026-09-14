/// Fail-watched: the v7 legacy path through the new code. A hand-built
/// v7 archive opens in one request, `tile()` and `tile_resolved()` agree
/// byte for byte, the request counts are the legacy 1/2/1, and no shared
/// request is ever made.
#[test]
fn v7_tiles_read_byte_identical_through_the_new_code() {
    let (bytes, body) = v7_archive();
    let mut a = MamapsArchive::open(Memory { bytes, requests: RefCell::new(Vec::new()) })
        .expect("open");
    assert_eq!(
        a.reader.requests.borrow().as_slice(),
        &[(0, OPEN_PREFIX_BYTES)],
        "a cold open is one request"
    );
    assert!(!a.has_shared(), "a v7 archive carries no shared section");
    assert!(a.shared_table().expect("shared").is_none(), "and none is fetched");
    assert_eq!(a.reader.requests.borrow().len(), 1, "asking changed nothing");

    let legacy = a.tile(0, 0, 0).expect("read").expect("present");
    assert_eq!(legacy, body);
    assert_eq!(a.reader.requests.borrow().len(), 1 + 2, "a cold tile is a leaf plus a body");
    let resolved = a.tile_resolved(0, 0, 0).expect("read").expect("present");
    assert_eq!(resolved, legacy, "resolved == legacy, byte for byte");
    assert_eq!(resolved, body);
    assert_eq!(a.reader.requests.borrow().len(), 1 + 2 + 1, "warm leaf: only the body");
    assert!(a.tile(1, 0, 0).expect("read").is_none(), "past max_zoom stays None");
    assert!(a.tile_resolved(1, 0, 0).expect("read").is_none(), "on both paths");
}

/// Fail-watched: a cached shared table costs no requests. Injected here
/// (the header hook lands with lane B); the fetch path itself is pinned
/// by `shared_table_parses_and_caches` in lane B's harness.
#[test]
fn a_cached_shared_table_resolves_without_requests() {
    let (bytes, _) = v7_archive();
    let mut a = MamapsArchive::open(Memory { bytes, requests: RefCell::new(Vec::new()) })
        .expect("open");
    a.shared = Some(SharedView::parse(&shared_section()).expect("shared"));
    assert!(a.shared_table().expect("shared").is_some());
    assert_eq!(a.reader.requests.borrow().len(), 1, "no fetch for a cached table");
    let slim = SlimBody::parse(&slim_body(0)).expect("slim");
    let resolved = resolve_body(a.shared_table().expect("shared").expect("present"), &slim)
        .expect("resolve");
    assert_eq!(resolved.layers.len(), 1);
    assert_eq!(resolved.layers[0].features.len(), 2);
    assert_eq!(a.reader.requests.borrow().len(), 1, "resolve is pure: still no requests");
}

/// Fail-watched: slim refs resolve to the bodies they were built from.
/// Row 7 is the named road (carriageway 2/2, lane turns, stable id);
/// row 9 is the unnamed default road. Geometry comes from the per-tile
/// arena, kind and the numeric bit from the row, names/attrs/ids from the
/// pools; the layer lends the geometry type.
#[test]
fn slim_refs_resolve_logical_id_to_row_to_pools() {
    let shared = SharedView::parse(&shared_section()).expect("shared");
    assert_eq!(shared.rows.len(), 2);
    assert_eq!(shared.row(7).expect("row 7").name_ref, 1);
    assert_eq!(shared.row_name(shared.row(7).expect("row")), Some("Oak Ave"));
    let slim = SlimBody::parse(&slim_body(0)).expect("slim");
    assert_eq!(slim.extent, crate::mamaps::body::DEFAULT_EXTENT);
    assert!(matches!(slim.layers[0], BodyLayer::Slim(_)), "roads go slim");
    let body = resolve_body(&shared, &slim).expect("resolve");

    let layer = body.layer(dict::LAYER_ROADS).expect("roads");
    assert_eq!(layer.features.len(), 2);
    assert_eq!(layer.features[0].kind, 45);
    assert_eq!(layer.features[0].geom_type, GEOM_LINE, "the layer lends the type");
    assert_eq!(layer.features[0].flags, 0, "row flags are clear");
    assert_eq!(layer.features[0].name(&body), Some("Oak Ave"));
    assert_eq!(layer.features[1].name(&body), None);
    assert_eq!(body.names, vec!["Oak Ave".to_string()], "first-use order, deduped");
    // Geometry is per-tile: two parts, four points, starts rebased.
    assert_eq!(layer.parts.len(), 2);
    assert_eq!(layer.coords, vec![(0, 0), (10, 0), (5, 5), (15, 5)]);
    assert_eq!(layer.points(&layer.parts[1])[0], (5, 5), "deltas restart per part");
    // Attrs: row 7's carriageway and lane turns, row 9's defaults.
    let cars = &body.carriageways;
    assert_eq!(cars.len(), 1, "non-default carriageways are kept");
    assert_eq!(cars[0].0, dict::LAYER_ROADS);
    assert_eq!(
        cars[0].1[0],
        Carriageway { forward: 2, backward: 2, solid_dividers: 0b010 },
    );
    assert_eq!(cars[0].1[1], Carriageway::default());
    let turns = &body.turn_lanes;
    assert_eq!(turns.len(), 1);
    assert_eq!(turns[0].1[0].forward, vec![1u16, 2u16]);
    assert_eq!(turns[0].1[0].backward, vec![4u16]);
    assert!(turns[0].1[1].is_empty());
    assert!(body.buildings.is_empty(), "all-default building attrs are omitted");
    // Ids: row 7's stable id rides its own slot; the junction-style row
    // keeps ID_NONE without misaligning the rest.
    assert_eq!(body.ids, vec![(dict::LAYER_ROADS, vec![12_345_678_901, ID_NONE])]);
    assert_eq!(body.feature_id(dict::LAYER_ROADS, 0), Some(12_345_678_901));
    assert_eq!(body.feature_id(dict::LAYER_ROADS, 1), Some(ID_NONE));
    assert!(body.convention.is_none() && body.heightmap.is_none(), "no flags, no sections");
}

/// Fail-watched: `view_bits` are renderer epoch metadata, not geometry —
/// a ref carrying them resolves to the same feature.
#[test]
fn view_bits_do_not_change_the_resolved_feature() {
    let shared = SharedView::parse(&shared_section()).expect("shared");
    let mut bytes = slim_body(0);
    bytes[PAYLOAD_AT + 4..PAYLOAD_AT + 8].copy_from_slice(&0xDEAD_BEEFu32.to_le_bytes());
    let slim = SlimBody::parse(&bytes).expect("slim");
    match &slim.layers[0] {
        BodyLayer::Slim(slim_layer) => {
            assert_eq!(slim_layer.instances[0].view_bits, 0xDEAD_BEEF);
        }
        BodyLayer::Full(_) => panic!("roads go slim"),
    }
    let body = resolve_body(&shared, &slim).expect("resolve");
    assert_eq!(body.layer(dict::LAYER_ROADS).expect("roads").features.len(), 2);
    assert_eq!(
        body.layer(dict::LAYER_ROADS).expect("roads").features[0].name(&body),
        Some("Oak Ave"),
    );
}

/// Fail-watched: the trailing sections ride their flags. The convention
/// byte follows the payloads, then the heightmap grid.
#[test]
fn convention_and_heightmap_trail_their_flags() {
    let shared = SharedView::parse(&shared_section()).expect("shared");
    let slim = SlimBody::parse(&slim_body(BODY_FLAG_ROAD_LANES | BODY_FLAG_HEIGHTMAP))
        .expect("slim");
    assert_eq!(slim.convention, Some(MarkingConvention::default()));
    let grid = slim.heightmap.clone().expect("heightmap");
    assert_eq!((grid.dim, grid.samples.len()), (2, 4));
    let body = resolve_body(&shared, &slim).expect("resolve");
    assert_eq!(body.convention, Some(MarkingConvention::default()));
    assert_eq!(body.heightmap, Some(grid));
}

/// Fail-watched: corrupt slim bodies fail here, never past a slice end.
#[test]
fn a_corrupt_slim_body_is_refused() {
    let good = slim_body(0);
    assert!(SlimBody::parse(&good[..SLIM_HEADER_LEN - 1]).is_err(), "shorter than the header");
    let cases: &[(&str, fn(&mut Vec<u8>))] = &[
        ("bad magic", |b| b[0] = b'X'),
        ("a v7 version byte", |b| b[3] = 7),
        ("a zero extent", |b| b[8..10].copy_from_slice(&0u16.to_le_bytes())),
        ("an unknown flag", |b| b[11] = 0x80),
        ("a dirty reserved word", |b| b[12] = 1),
        ("a layer count past the index", |b| b[10] = 200),
        ("a payload outside the body", |b| {
            b[20..24].copy_from_slice(&999_999u32.to_le_bytes())
        }),
        ("an encoding that is neither full nor slim", |b| b[SLIM_HEADER_LEN + 1] = 2),
        ("trailing garbage with no flags", |b| b.push(0xFF)),
        ("an instance with no geometry", |b| {
            b[PAYLOAD_AT + 12..PAYLOAD_AT + 16].copy_from_slice(&0u32.to_le_bytes())
        }),
        ("an instance past its part table", |b| {
            b[PAYLOAD_AT + 8..PAYLOAD_AT + 12].copy_from_slice(&99u32.to_le_bytes())
        }),
        ("a bad part winding", |b| {
            let at = PAYLOAD_AT + 2 * SLIM_INSTANCE_LEN;
            b[at + 8..at + 10].copy_from_slice(&7u16.to_le_bytes())
        }),
    ];
    for (what, break_it) in cases {
        let mut bytes = good.clone();
        break_it(&mut bytes);
        // Length-declared bodies must restate their length after mutation.
        let len = bytes.len() as u32;
        if bytes.len() >= 8 {
            bytes[4..8].copy_from_slice(&len.to_le_bytes());
        }
        assert!(SlimBody::parse(&bytes).is_err(), "{what} should be refused");
    }
}

/// Fail-watched: corrupt refs fail at resolve, naming the row — and the join is the check.
/// The 16-byte instance carries no styling copy, so `check_row_matches_instance` pins
/// row-vs-row agreement: the row found must be the row named. (Via the binary-search lookup
/// this always holds; the unit pins it for whatever lookup comes next.)
#[test]
fn a_ref_to_no_row_or_a_misjoined_row_is_refused_at_resolve() {
    let shared = SharedView::parse(&shared_section()).expect("shared");
    let mut slim = SlimBody::parse(&slim_body(0)).expect("slim");
    match &mut slim.layers[0] {
        BodyLayer::Slim(slim_layer) => slim_layer.instances[0].logical_id = 404,
        BodyLayer::Full(_) => panic!("roads go slim"),
    }
    let failure = resolve_body(&shared, &slim).expect_err("no such row");
    assert!(failure.0.contains("404"), "{}", failure.0);

    // The join check itself: agreement passes, a misjoin names both sides.
    let row = shared.row(7).expect("row 7");
    let good = SlimInstance { logical_id: 7, view_bits: 0, part_offset: 0, part_count: 1 };
    assert!(super::check_row_matches_instance(row, &good).is_ok());
    let bad = SlimInstance { logical_id: 404, ..good };
    let failure = super::check_row_matches_instance(row, &bad).expect_err("misjoin");
    assert!(failure.0.contains("404"), "{}", failure.0);

    // An attribute index past its pool.
    let mut shared = shared.clone();
    shared.rows[0].carriageway_idx = 99;
    let slim = SlimBody::parse(&slim_body(0)).expect("slim");
    let failure = resolve_body(&shared, &slim).expect_err("past the pool");
    assert!(failure.0.contains("99"), "{}", failure.0);
}

/// Fail-watched: the 16-byte instance wire. A revert to 32 bytes quotes
/// `left: 32, right: 16` here rather than misparsing tiles.
#[test]
fn slim_instances_are_16_bytes_fully_packed() {
    assert_eq!(SLIM_INSTANCE_LEN, 16, "SharedSlimRef 8B + part_offset + part_count");
    // One known instance, byte for byte: logical 7, epoch 0xDEADBEEF, parts 3..5.
    let bytes = crate::mamaps::body::encode_slim_instance(7, 0xDEAD_BEEF, 3, 2);
    assert_eq!(
        bytes,
        [7u8, 0, 0, 0, 0xEF, 0xBE, 0xAD, 0xDE, 3, 0, 0, 0, 2, 0, 0, 0],
        "logical_id LE, view_bits LE, part_offset LE, part_count LE",
    );
    let instance = SlimInstance::parse(&bytes).expect("parse");
    assert_eq!(
        instance,
        SlimInstance { logical_id: 7, view_bits: 0xDEAD_BEEF, part_offset: 3, part_count: 2 },
    );
    assert!(SlimInstance::parse(&bytes[..15]).is_err(), "15 bytes is not an instance");
    // No reserved bytes anymore: every byte is defined, so a zeroed record fails only for
    // having no geometry — never for a dirty spare byte.
    assert!(SlimInstance::parse(&[0u8; 16]).is_err(), "part_count 0");
}

/// Fail-watched: one body, two encodings. Roads go slim (16-byte refs resolving through the
/// shared table); water stays full v7 bytes and passes through untouched — the K≈1 escape
/// hatch for unattributed layers.
#[test]
fn a_mixed_body_resolves_slim_layers_and_passes_full_ones_through() {
    let shared = SharedView::parse(&shared_section()).expect("shared");
    let slim = SlimBody::parse(&mixed_body()).expect("mixed");
    assert_eq!(slim.layers.len(), 2);
    assert!(matches!(slim.layers[0], BodyLayer::Full(_)), "water stays full");
    assert!(matches!(slim.layers[1], BodyLayer::Slim(_)), "roads go slim");
    let body = resolve_body(&shared, &slim).expect("resolve");
    assert_eq!(body.layers.len(), 2);
    // Water verbatim: the polygon, its inline fields, its arena.
    let water = body.layer(dict::LAYER_WATER).expect("water");
    assert_eq!(water.features.len(), 1);
    assert_eq!(water.features[0].geom_type, GEOM_POLYGON);
    assert_eq!(water.coords, vec![(0, 0), (100, 0), (100, 100), (0, 100)]);
    // Roads resolved: kind from the row, names/attrs/ids from the pools.
    let roads = body.layer(dict::LAYER_ROADS).expect("roads");
    assert_eq!(roads.features.len(), 2);
    assert_eq!(roads.features[0].geom_type, GEOM_LINE);
    assert_eq!(roads.features[0].name(&body), Some("Oak Ave"));
    assert_eq!(body.feature_id(dict::LAYER_ROADS, 0), Some(12_345_678_901));
    assert_eq!(body.names, vec!["Oak Ave".to_string()], "slim names only");
}

/// Fail-watched: the 32-byte wire is gone, and the index byte decides. A 32-byte-era record
/// behind the slim bit carries its "part table" mid-record and an arena that never ends where
/// the payload does; behind the full bit (what the old all-zero index byte now means) its
/// first "feature" has geometry type 0 and is refused as v7 always was.
#[test]
fn a_32_byte_record_is_refused_behind_either_encoding_byte() {
    // One 32-byte-era record: ref(7,0) + ranges + the layer/geom/flags/transit tail, then the
    // part table and arena the old writer appended.
    let mut old = Vec::new();
    old.extend_from_slice(&SharedSlimRef { logical_id: 7, view_bits: 0 }.serialize());
    old.extend_from_slice(&0u32.to_le_bytes());
    old.extend_from_slice(&1u32.to_le_bytes());
    old.push(dict::LAYER_ROADS);
    old.extend_from_slice(&[0u8, 0u8, 0u8]);
    old.push(GEOM_LINE);
    old.push(0);
    old.push(0);
    old.push(0);
    old.extend_from_slice(&0u32.to_le_bytes());
    old.extend_from_slice(&[0u8, 0u8, 0u8, 0u8]);
    assert_eq!(old.len(), 32, "one 32-byte-era record");
    slim_part(&mut old, 0, 2);
    while old.len() % 4 != 0 {
        old.push(0);
    }
    for d in [0u64, 0, 20, 0] {
        push_uvarint(&mut old, d);
    }
    assert!(super::parse_slim_layer(dict::LAYER_ROADS, 1, &old).is_err(), "32B behind slim");
    assert!(
        crate::mamaps::body::parse_layer(dict::LAYER_ROADS, 1, &old).is_err(),
        "32B behind full",
    );
}

/// Fail-watched: the lane-B emitter round-trips through the lane-B parser. Lane D (writer)
/// assembles slim payloads through `assemble_slim_payload`; this pins that what it emits is
/// what `parse_slim_layer` reads, including the part-range and contiguity refusals.
#[test]
fn the_slim_emitter_round_trips_through_the_slim_parser() {
    let refs = [(7u32, 0u32, 0u32, 1u32), (9, 0, 1, 1)];
    let parts = [
        Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER },
        Part { coord_start: 2, point_count: 2, winding: WINDING_OUTER },
    ];
    let coords = [(0i16, 0i16), (10, 0), (5, 5), (15, 5)];
    let payload =
        crate::mamaps::body::assemble_slim_payload(&refs, &parts, &coords).expect("assemble");
    let layer = super::parse_slim_layer(dict::LAYER_ROADS, 2, &payload).expect("parse");
    assert_eq!(layer.layer_id, dict::LAYER_ROADS);
    assert_eq!(
        layer.instances,
        vec![
            SlimInstance { logical_id: 7, view_bits: 0, part_offset: 0, part_count: 1 },
            SlimInstance { logical_id: 9, view_bits: 0, part_offset: 1, part_count: 1 },
        ],
    );
    assert_eq!(layer.coords, vec![(0, 0), (10, 0), (5, 5), (15, 5)]);
    // And the emitter refuses what the parser would misresolve.
    assert!(
        crate::mamaps::body::assemble_slim_payload(&[(7, 0, 0, 0)], &parts, &coords).is_err(),
        "an instance with no geometry",
    );
    assert!(
        crate::mamaps::body::assemble_slim_payload(&[(7, 0, 0, 9)], &parts, &coords).is_err(),
        "a part range past the table",
    );
    let gapped = [Part { coord_start: 1, point_count: 2, winding: WINDING_OUTER }];
    assert!(
        crate::mamaps::body::assemble_slim_payload(&refs[..1], &gapped, &coords).is_err(),
        "parts that do not tile the arena",
    );
}

/// Fail-watched: a slim payload is exactly its instances, parts and arena. Anything past the
/// arena is corruption, refused here rather than silently ignored.
#[test]
fn bytes_past_the_arena_are_refused() {
    let mut payload = Vec::new();
    slim_instance(&mut payload, 7, 0, 0, 1);
    slim_part(&mut payload, 0, 2);
    while payload.len() % 4 != 0 {
        payload.push(0);
    }
    for d in [0u64, 0, 20, 0] {
        push_uvarint(&mut payload, d);
    }
    assert!(super::parse_slim_layer(dict::LAYER_ROADS, 1, &payload).is_ok(), "exact parses");
    payload.push(0);
    assert!(
        super::parse_slim_layer(dict::LAYER_ROADS, 1, &payload).is_err(),
        "one byte past the arena",
    );
}
