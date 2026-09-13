use super::*;
use crate::mamaps::body;
use crate::mamaps::dict;
use crate::pmtiles::tile_id;
use crate::proto::Result;
use crate::stream::RangeReader;
use std::cell::RefCell;

use crate::mamaps::index::{ROOT_ENTRY_LEN, LEAF_ENTRY_LEN};
use crate::mamaps::body::LAYER_INDEX_LEN;
use crate::mamaps::shared::{
    SharedBuildingAttrs, SharedCarriageway, SharedHeader, SharedPoolDirEntry,
    SharedSlimRef, SharedStringPool, SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN,
    SHARED_HEADER_LEN, SHARED_KIND_BUILDINGS, SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_ID_RUNS,
    SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_STRINGS, SHARED_POOL_ENTRY_LEN,
    SHARED_ROW_LEN, SHARED_NAME_NONE,
};

/// Where the slim payload starts: header plus one index entry, already
/// 4-aligned. The corruption tests below mutate payload bytes by absolute
/// offset, so this — not a literal — anchors them.
const PAYLOAD_AT: usize = SLIM_HEADER_LEN + LAYER_INDEX_LEN;

// Fail-watch convention (lane C): each test pins one wire or behaviour
// fact as a literal, so a revert of that fact quotes its failure (`left`
// vs `right`) rather than a vague mismatch. Revert → quote → restore.
// These tests were written without a rebuild (lane orders); they run with
// `cargo test -p tilecodec` once the lane's turn in the build queue comes.

/// A [`RangeReader`] over bytes in memory that logs every range asked for,
/// so a test can assert on the **number of round trips** as well as the
/// bytes. The same shape `mod.rs`'s `Counting` uses, local so this lane
/// touches only `read.rs`.
struct Memory {
    bytes: Vec<u8>,
    requests: RefCell<Vec<(u64, u32)>>,
}

impl RangeReader for Memory {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        self.requests.borrow_mut().push((offset, length));
        if offset >= self.bytes.len() as u64 {
            return Ok(Vec::new());
        }
        let end = (offset + length as u64).min(self.bytes.len() as u64);
        Ok(self.bytes[offset as usize..end as usize].to_vec())
    }
}

/// A one-line v7 body whose contents depend on `seed`.
fn body_for(seed: i16) -> Body {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
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
    roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
    roads.coords = vec![(0, 0), (seed, seed)];
    Body {
        extent: crate::mamaps::body::DEFAULT_EXTENT,
        layers: vec![roads],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
    }
}

/// A minimal one-tile v7 archive, hand-built (no `write` feature needed):
/// `[header 128][dict][root 1×32][leaf 1×16][body]`. Everything is
/// addressed through header offsets, like the real writer emits.
fn v7_archive() -> (Vec<u8>, Body) {
    let body = body_for(100);
    let raw = crate::mamaps::body::serialize(&body).expect("serialize");
    let dict = Dictionary::schema().serialize();
    let mut header = Header {
        flags: 0,
        compression: COMPRESSION_NONE,
        layer_count: Dictionary::schema().layers.len() as u8,
        min_zoom: 0,
        max_zoom: 0,
        build_id: 0x0123_4567_89AB_CDEF,
        file_len: 0,
        dict_offset: HEADER_LEN as u64,
        dict_len: dict.len() as u32,
        leaf_entry_capacity: 4096,
        root_offset: 0,
        root_len: ROOT_ENTRY_LEN as u32,
        leaf_count: 1,
        leaf_offset: 0,
        leaf_len: LEAF_ENTRY_LEN as u64,
        data_offset: 0,
        data_len: raw.len() as u64,
        tiles_addressed: 1,
        bodies_written: 1,
        min_lon_e7: 0,
        min_lat_e7: 0,
        max_lon_e7: 0,
        max_lat_e7: 0,
        // A v7 archive: no shared section, so the header stays 128 bytes
        // with version byte 7 and lane B's tail fields zeroed.
        shared_offset: 0,
        shared_len: 0,
        shared_flags: 0,
        shared_pools: 0,
    };
    header.root_offset = header.dict_offset + header.dict_len as u64;
    header.leaf_offset = header.root_offset + header.root_len as u64;
    header.data_offset = header.leaf_offset + header.leaf_len;
    header.file_len = header.data_offset + header.data_len;
    let mut root = Vec::new();
    root.extend_from_slice(&tile_id(0, 0, 0).to_le_bytes());
    root.extend_from_slice(&0u64.to_le_bytes());
    root.extend_from_slice(&0u64.to_le_bytes());
    root.extend_from_slice(&1u32.to_le_bytes());
    root.extend_from_slice(&0u32.to_le_bytes());
    let mut leaf = Vec::new();
    leaf.extend_from_slice(&0u32.to_le_bytes());
    leaf.extend_from_slice(&1u32.to_le_bytes());
    leaf.extend_from_slice(&0u32.to_le_bytes());
    leaf.extend_from_slice(&(raw.len() as u32).to_le_bytes());
    let mut bytes = header.serialize();
    bytes.extend_from_slice(&dict);
    bytes.extend_from_slice(&root);
    bytes.extend_from_slice(&leaf);
    bytes.extend_from_slice(&raw);
    assert_eq!(bytes.len() as u64, header.file_len);
    (bytes, body)
}

/// One uvarint, appended — the same wire `proto::Writer::uvarint` writes.
fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

/// A whole shared section for the resolve tests: two strings, two rows
/// (a named road with a carriageway + lane turns, an unnamed default
/// road), attr pools seeded with their index-0 defaults, and id runs for
/// two stable ids. Built with lane A's own serializers so the test pins
/// the shared contract, not a parallel encoding.
fn shared_section() -> Vec<u8> {
    use crate::mamaps::shared::encode_id_runs;
    let strings = SharedStringPool { names: vec!["Oak Ave".to_string(), "Main St".to_string()] }.serialize();
    let rows = [
        SharedLogicalRow {
            logical_id: 7,
            name_ref: 1,
            kind: 45,
            kind_detail: dict::NONE,
            view_bits: 0,
            building_idx: 0,
            carriageway_idx: 1,
            lane_turns_idx: 1,
            flags: 0,
        },
        SharedLogicalRow {
            logical_id: 9,
            name_ref: SHARED_NAME_NONE,
            kind: 45,
            kind_detail: dict::NONE,
            view_bits: 0,
            building_idx: 0,
            carriageway_idx: 0,
            lane_turns_idx: 0,
            flags: 0,
        },
    ];
    let mut rows_pool = Vec::new();
    for r in &rows {
        rows_pool.extend_from_slice(&r.serialize());
    }
    let buildings = {
        let mut out = Vec::new();
        out.extend_from_slice(&1u32.to_le_bytes());
        out.extend_from_slice(&SharedBuildingAttrs::default().serialize());
        out
    };
    let carriageways = {
        let mut out = Vec::new();
        out.extend_from_slice(&2u32.to_le_bytes());
        out.extend_from_slice(&SharedCarriageway::default().serialize());
        out.extend_from_slice(
            &SharedCarriageway { forward: 2, backward: 2, solid_dividers: 0b010 }.serialize(),
        );
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    };
    let lane_turns = {
        let mut out = Vec::new();
        out.extend_from_slice(&2u32.to_le_bytes());
        out.extend_from_slice(&[0u8, 0u8]);
        out.extend_from_slice(&[2u8, 1u8]);
        for m in [1u16, 2u16, 4u16] {
            out.extend_from_slice(&m.to_le_bytes());
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    };
    let mut id_pool = encode_id_runs(&[12_345_678_901, ID_NONE]).expect("encode");
    while id_pool.len() % 4 != 0 {
        id_pool.push(0);
    }
    let pools: Vec<(u8, u32, Vec<u8>)> = vec![
        (SHARED_KIND_STRINGS, 0, strings),
        (SHARED_KIND_ROWS, SHARED_ROW_LEN as u32, rows_pool),
        (SHARED_KIND_BUILDINGS, SHARED_BUILDING_LEN as u32, buildings),
        (SHARED_KIND_CARRIAGEWAYS, SHARED_CARRIAGEWAY_LEN as u32, carriageways),
        (SHARED_KIND_LANE_TURNS, 0, lane_turns),
        (SHARED_KIND_ID_RUNS, 0, id_pool),
    ];
    let mut offset = (SHARED_HEADER_LEN + pools.len() * SHARED_POOL_ENTRY_LEN) as u64;
    let mut dir = Vec::new();
    for (kind, elem_len, bytes) in &pools {
        dir.push(SharedPoolDirEntry { kind: *kind, elem_len: *elem_len, offset, len: bytes.len() as u64 });
        offset += bytes.len() as u64;
    }
    let header = SharedHeader {
        flags: 0,
        row_count: 2,
        string_count: 2,
        pool_count: pools.len() as u32,
        total_len: offset as u32,
        id_run_count: 2,
    };
    let mut out = header.serialize();
    for e in &dir {
        out.extend_from_slice(&e.serialize());
    }
    for (_, _, bytes) in &pools {
        out.extend_from_slice(bytes);
    }
    assert_eq!(out.len() as u32, header.total_len);
    out
}

/// Append one 12-byte layer index entry with its encoding byte (0 = full v7, 1 = slim).
fn slim_index(out: &mut Vec<u8>, layer_id: u8, encoding: u8, count: u16, offset: u32, len: u32) {
    out.push(layer_id);
    out.push(encoding);
    out.extend_from_slice(&count.to_le_bytes());
    out.extend_from_slice(&offset.to_le_bytes());
    out.extend_from_slice(&len.to_le_bytes());
}

/// Append one 16-byte slim instance record, through the real emitter so the
/// fixtures pin emit and parse together rather than a parallel encoding.
fn slim_instance(
    out: &mut Vec<u8>,
    logical_id: u32,
    view_bits: u32,
    part_offset: u32,
    part_count: u32,
) {
    out.extend_from_slice(&crate::mamaps::body::encode_slim_instance(
        logical_id,
        view_bits,
        part_offset,
        part_count,
    ));
}

/// Append one 12-byte part entry.
fn slim_part(out: &mut Vec<u8>, coord_start: u32, point_count: u32) {
    out.extend_from_slice(&coord_start.to_le_bytes());
    out.extend_from_slice(&point_count.to_le_bytes());
    out.extend_from_slice(&WINDING_OUTER.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes());
}

/// A slim body resolving against [`shared_section`]: one roads layer with
/// two 16-byte instances (row 7 with parts 0..1, row 9 with parts 1..2), a
/// part table and a four-point arena. `flags` selects the trailing sections.
fn slim_body(flags: u8) -> Vec<u8> {
    let mut payload = Vec::new();
    slim_instance(&mut payload, 7, 0, 0, 1);
    slim_instance(&mut payload, 9, 0, 1, 1);
    slim_part(&mut payload, 0, 2);
    slim_part(&mut payload, 2, 2);
    while payload.len() % 4 != 0 {
        payload.push(0);
    }
    // Deltas restart at each part: part 0 runs (0,0)->(10,0),
    // part 1 runs (5,5)->(15,5). Zigzagged: 0,0,20,0 then 10,10,20,0.
    for d in [0u64, 0, 20, 0, 10, 10, 20, 0] {
        push_uvarint(&mut payload, d);
    }
    let index_end = align4(SLIM_HEADER_LEN + LAYER_INDEX_LEN);
    let mut out = Vec::new();
    out.extend_from_slice(b"MBD");
    out.push(V8_FORMAT_VERSION);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&crate::mamaps::body::DEFAULT_EXTENT.to_le_bytes());
    out.push(1);
    out.push(flags);
    out.extend_from_slice(&0u32.to_le_bytes());
    slim_index(
        &mut out,
        dict::LAYER_ROADS,
        SLIM_INDEX_SLIM,
        2,
        index_end as u32,
        payload.len() as u32,
    );
    while out.len() % 4 != 0 {
        out.push(0);
    }
    assert_eq!(out.len(), index_end);
    out.extend_from_slice(&payload);
    if flags & BODY_FLAG_ROAD_LANES != 0 {
        out.push(MarkingConvention::default().to_byte());
        while out.len() % 4 != 0 {
            out.push(0);
        }
    }
    if flags & BODY_FLAG_HEIGHTMAP != 0 {
        out.extend_from_slice(&2u16.to_le_bytes());
        for _ in 0..4 {
            out.extend_from_slice(&32768u16.to_le_bytes());
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
    }
    let raw_len = out.len() as u32;
    out[4..8].copy_from_slice(&raw_len.to_le_bytes());
    out
}

/// One full-v7 water layer's payload bytes: built by the real v7 encoder so the mixed-body
/// test pins compatibility, not a parallel encoding. One square polygon, unnamed.
fn full_water_payload() -> Vec<u8> {
    let mut water = Layer::new(dict::LAYER_WATER);
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
    water.coords = vec![(0, 0), (100, 0), (100, 100), (0, 100)];
    let body = Body {
        extent: crate::mamaps::body::DEFAULT_EXTENT,
        layers: vec![water],
        names: Vec::new(),
        ids: Vec::new(),
        turn_lanes: Vec::new(),
        buildings: Vec::new(),
        heightmap: None,
        carriageways: Vec::new(),
        convention: None,
    };
    let bytes = crate::mamaps::body::serialize(&body).expect("serialize");
    // One layer: its index entry sits right after the header.
    let at = crate::mamaps::body::BODY_HEADER_LEN;
    let offset =
        u32::from_le_bytes([bytes[at + 4], bytes[at + 5], bytes[at + 6], bytes[at + 7]])
            as usize;
    let len =
        u32::from_le_bytes([bytes[at + 8], bytes[at + 9], bytes[at + 10], bytes[at + 11]])
            as usize;
    bytes[offset..offset + len].to_vec()
}

/// A mixed v8 body: water full v7 (index byte 0, the real encoder's bytes) plus roads slim
/// (two 16-byte instances resolving against [`shared_section`]). Layers ascend by id.
fn mixed_body() -> Vec<u8> {
    let mut slim_payload = Vec::new();
    slim_instance(&mut slim_payload, 7, 0, 0, 1);
    slim_instance(&mut slim_payload, 9, 0, 1, 1);
    slim_part(&mut slim_payload, 0, 2);
    slim_part(&mut slim_payload, 2, 2);
    while slim_payload.len() % 4 != 0 {
        slim_payload.push(0);
    }
    for d in [0u64, 0, 20, 0, 10, 10, 20, 0] {
        push_uvarint(&mut slim_payload, d);
    }
    let full = full_water_payload();
    // Payloads in index order from the aligned index end, the way the v7 writer lays them out.
    let index_end = align4(SLIM_HEADER_LEN + 2 * LAYER_INDEX_LEN);
    let water_at = index_end;
    let roads_at = water_at + full.len();
    let mut out = Vec::new();
    out.extend_from_slice(b"MBD");
    out.push(V8_FORMAT_VERSION);
    out.extend_from_slice(&0u32.to_le_bytes());
    out.extend_from_slice(&crate::mamaps::body::DEFAULT_EXTENT.to_le_bytes());
    out.push(2);
    out.push(0);
    out.extend_from_slice(&0u32.to_le_bytes());
    slim_index(
        &mut out,
        dict::LAYER_WATER,
        SLIM_INDEX_FULL,
        1,
        water_at as u32,
        full.len() as u32,
    );
    slim_index(
        &mut out,
        dict::LAYER_ROADS,
        SLIM_INDEX_SLIM,
        2,
        roads_at as u32,
        slim_payload.len() as u32,
    );
    while out.len() % 4 != 0 {
        out.push(0);
    }
    assert_eq!(out.len(), index_end);
    out.extend_from_slice(&full);
    out.extend_from_slice(&slim_payload);
    let raw_len = out.len() as u32;
    out[4..8].copy_from_slice(&raw_len.to_le_bytes());
    out
}

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
