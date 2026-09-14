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

include!("tests_part1.rs");