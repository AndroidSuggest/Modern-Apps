//! Shared-section wire tests: round-trips, refusals, and the lane A v8.1 suite.
//!
//! Pure moves out of the former single-file shared module; nothing here changed
//! except dedenting one level (file module instead of inline `mod tests`).

use super::pools::{parse_lane_turns_pool, push_uvarint, serialize_lane_turns_pool};
use crate::mamaps::body::ID_NONE;

use super::*;

// Fail-watch convention: each test pins one wire fact as a literal, so a
// revert of that fact quotes its failure (`left` vs `right`) rather than a
// vague mismatch. Revert → quote the failure → restore.

#[test]
fn the_header_is_32_bytes_behind_mbsh() {
    let h = SharedHeader {
        flags: 0,
        row_count: 2,
        string_count: 1,
        pool_count: 7,
        total_len: 32 + 7 * 24 + 64,
        id_run_count: 2,
    };
    let bytes = h.serialize();
    assert_eq!(bytes.len(), 32, "SharedHeader must stay 32 B");
    assert_eq!(&bytes[0..4], b"MBSH");
    assert_eq!(SharedHeader::parse(&bytes).expect("parse"), h);
    let mut bad = bytes.clone();
    bad[0] = b'X';
    assert!(SharedHeader::parse(&bad).is_err(), "bad magic is refused");
}

#[test]
fn pool_entries_are_24_bytes_with_known_kinds() {
    let e = SharedPoolDirEntry { kind: SHARED_KIND_ROWS, elem_len: 32, offset: 200, len: 64 };
    let bytes = e.serialize();
    assert_eq!(bytes.len(), 24, "dir entries must stay 24 B");
    assert_eq!(SharedPoolDirEntry::parse(&bytes).expect("parse"), e);
    let mut bad = bytes.clone();
    bad[0] = 99;
    assert!(SharedPoolDirEntry::parse(&bad).is_err(), "unknown pool kind");
}

#[test]
fn logical_rows_are_32_bytes_and_carry_attr_indices() {
    let r = SharedLogicalRow {
        logical_id: 7,
        name_ref: 1,
        kind: 45,
        kind_detail: 4,
        view_bits: 0xFFFF,
        building_idx: 0,
        carriageway_idx: 1,
        lane_turns_idx: 0,
        flags: 0,
    };
    let bytes = r.serialize();
    assert_eq!(bytes.len(), 32, "logical rows must stay 32 B");
    assert_eq!(SharedLogicalRow::parse(&bytes).expect("parse"), r);
}

#[test]
fn slim_refs_are_8_bytes() {
    let r = SharedSlimRef { logical_id: 9, view_bits: 3 };
    let bytes = r.serialize();
    assert_eq!(bytes.len(), 8);
    assert_eq!(SharedSlimRef::parse(&bytes).expect("parse"), r);
}

#[test]
fn attr_records_keep_their_body_widths() {
    assert_eq!(SHARED_BUILDING_LEN, 20, "BuildingAttrs stays 20 B");
    assert_eq!(SHARED_CARRIAGEWAY_LEN, 6, "Carriageway stays 6 B");
    let b = SharedBuildingAttrs { height: 1200, roof_shape: 1, ..Default::default() };
    assert_eq!(b.serialize().len(), 20);
    assert_eq!(SharedBuildingAttrs::parse(&b.serialize()).expect("parse"), b);
    let c = SharedCarriageway { forward: 2, backward: 2, solid_dividers: 0b010 };
    assert_eq!(c.serialize().len(), 6);
    assert_eq!(SharedCarriageway::parse(&c.serialize()).expect("parse"), c);
}

#[test]
fn name_ref_zero_is_none_and_lookup_is_one_based() {
    let pool = SharedStringPool { names: vec!["Main St".to_string()] };
    assert_eq!(pool.lookup(SHARED_NAME_NONE), None);
    assert_eq!(pool.lookup(1), Some("Main St"));
    assert_eq!(pool.lookup(2), None, "past the table");
    let (parsed, _) = SharedStringPool::parse(&pool.serialize()).expect("parse");
    assert_eq!(parsed, pool);
}

#[test]
fn id_runs_round_trip_with_none_runs() {
    // Two junctions (NO stable id) then two traffic component ids.
    let ids = vec![ID_NONE, ID_NONE, 100, 250];
    let enc = encode_id_runs(&ids).expect("encode");
    assert_eq!(decode_id_runs(&enc, ids.len()).expect("decode"), ids);
    // All-NONE (a junctions-only section) is one RLE run.
    let nones = vec![ID_NONE; 5];
    let enc = encode_id_runs(&nones).expect("encode");
    assert_eq!(enc[0], 0, "RLE marker leads an all-NONE stream");
    assert_eq!(decode_id_runs(&enc, nones.len()).expect("decode"), nones);
    // Non-monotonic ids are fine (row order is first-sighting order);
    // duplicates are refused rather than silently delta'd.
    let jumbled = vec![250, 100, 300];
    let enc = encode_id_runs(&jumbled).expect("encode");
    assert_eq!(decode_id_runs(&enc, jumbled.len()).expect("decode"), jumbled);
    assert!(encode_id_runs(&[300, 300]).is_err(), "duplicate ids");
}

#[test]
fn junction_keys_are_geometry_hashes_not_stable_ids() {
    let a = junction_key(&[(0, 0), (100, 50), (200, 0)]);
    let b = junction_key(&[(0, 0), (100, 50), (200, 1)]);
    assert_ne!(a, b, "distinct connectors hash distinctly");
    assert_eq!(a, junction_key(&[(0, 0), (100, 50), (200, 0)]), "stable for one geometry");
}

#[test]
fn low16_aliasing_component_ids_are_distinct_rows() {
    // Two component ids sharing (edge_low16, seg): the old bare-low32 fold
    // aliased them to one row and failed the build. Content keys carry the
    // full u64, so they are distinct rows by construction.
    let mut b = SharedBuilder::new();
    let key = |id: u64| SharedRowKey {
        layer: 9,
        stable_id: id,
        geom_hash: 0,
        name: None,
        kind: 0,
        kind_detail: 0,
        flags: 0,
        building: SharedBuildingAttrs::default(),
        carriageway: SharedCarriageway::default(),
        lane_turns: SharedLaneTurns::default(),
    };
    let lo = |edge: u64, seg: u64| (edge << 16) | seg;
    let a = lo(0x0001_0042, 3);
    let c = lo(0x0002_0042, 3);
    assert_eq!(a as u32, c as u32, "low32 collides by construction");
    assert_ne!(
        b.intern_row(key(a), 0, 0, 0, a),
        b.intern_row(key(c), 0, 0, 0, c),
        "aliasing edges are distinct rows",
    );
}

#[test]
fn whole_record_lane_turns_deduplicate_and_seed_empty_zero() {
    let t = SharedLaneTurns { forward: vec![1, 2], backward: vec![4] };
    let pool = serialize_lane_turns_pool(&[SharedLaneTurns::default(), t.clone()]);
    let parsed = parse_lane_turns_pool(&pool).expect("parse");
    assert!(parsed[0].is_empty(), "index 0 is empty");
    assert_eq!(parsed[1], t);
}

#[test]
fn interned_rows_are_sequential_and_repeat_sightings_share_one_row() {
    let mut b = SharedBuilder::new();
    let key = |name: &str| SharedRowKey {
        layer: 1,
        stable_id: 100,
        geom_hash: 0,
        name: Some(name.to_string()),
        kind: 45,
        kind_detail: 0,
        flags: 0,
        building: SharedBuildingAttrs::default(),
        carriageway: SharedCarriageway::default(),
        lane_turns: SharedLaneTurns::default(),
    };
    let first = b.intern_row(key("Main St"), 45, 0, 0, 100);
    assert_eq!(first, 1, "the first row is id 1");
    let repeat = b.intern_row(key("Main St"), 45, 0, 0, 100);
    assert_eq!(repeat, 1, "a repeat sighting shares the row");
    let other = b.intern_row(key("Oak Ave"), 45, 0, 0, 200);
    assert_eq!(other, 2, "different content is a different row");
    let section = b.serialize().expect("section");
    let view = SharedView::parse(&section).expect("parse");
    assert_eq!(view.header.row_count, 2, "two rows, not three");
    assert_eq!(
        view.rows.iter().map(|r| r.logical_id).collect::<Vec<_>>(),
        vec![1, 2],
        "sequential ids in first-sighting order",
    );
}

// ─── Lane A v8.1: geometry pool ────────────────────────────────
//
// Fail-watch convention (lane A v8.1): each test pins one wire fact as a
// literal, so a revert of that fact quotes its failure (`left` vs
// `right`) rather than a vague mismatch. Revert → quote → restore.

#[test]
fn canonical_verts_use_the_tile_arena_encoding() {
    // Zigzag varint deltas from the origin, exactly the walk tile arenas
    // use: (0,0) then two steps of (3,2) — zigzag 3 is 6, zigzag 2 is 4.
    let points = vec![(0i16, 0i16), (3, 2), (6, 4)];
    let enc = encode_canonical_verts(&points);
    assert_eq!(enc, vec![0, 0, 6, 4, 6, 4], "zigzag deltas from the origin");
    let (dec, used) = decode_canonical_verts(&enc, 3).expect("decode");
    assert_eq!(dec, points);
    assert_eq!(used, 6, "every byte consumed");
    assert!(decode_canonical_verts(&enc[..5], 3).is_err(), "a truncated stream");
    assert!(decode_canonical_verts(&enc, 4).is_err(), "a count past the stream");
    // A delta that walks outside i16 is corruption, not a wrap.
    let mut over = Vec::new();
    push_uvarint(&mut over, crate::proto::zigzag_encode(100_000));
    push_uvarint(&mut over, crate::proto::zigzag_encode(0));
    assert!(decode_canonical_verts(&over, 1).is_err(), "a delta past i16");
}

#[test]
fn keep_masks_encode_as_alternating_bitvec_rle() {
    let runs = encode_keep_runs(&[true, true, false, false, false, true]);
    assert_eq!(runs, vec![(1, 2), (0, 3), (1, 1)], "maximal runs, keep-first");
    assert!(encode_keep_runs(&[]).is_empty(), "an empty mask is no runs");
    // And the decoder refuses what the encoder can never write.
    let good = vec![1u8, 2, 0, 0, 0, 0, 3, 0, 0, 0, 1, 1, 0, 0, 0];
    let mut pos = 0;
    assert_eq!(
        decode_keep_runs(&good, &mut pos, 6, 3).expect("decode"),
        vec![true, true, false, false, false, true]
    );
    assert_eq!(pos, 15, "three 5-byte runs");
    let cases: &[(&str, Vec<u8>, usize, usize)] = &[
        ("a bit past 1", vec![2u8, 1, 0, 0, 0], 1, 1),
        ("a zero length", vec![1u8, 0, 0, 0, 0], 1, 1),
        ("adjacent equal bits", vec![1u8, 1, 0, 0, 0, 1, 1, 0, 0, 0], 2, 2),
        ("runs past the bit count", vec![1u8, 9, 0, 0, 0], 2, 1),
        ("runs short of the bit count", vec![1u8, 1, 0, 0, 0], 2, 1),
        ("runs for no bits", vec![1u8, 1, 0, 0, 0], 0, 1),
    ];
    for (what, bytes, bits, n) in cases {
        let mut p = 0;
        assert!(decode_keep_runs(bytes, &mut p, *bits, *n).is_err(), "{what}");
    }
    // No runs for a nonempty mask, and runs for an empty one.
    let mut p = 0;
    assert!(decode_keep_runs(&[], &mut p, 2, 0).is_err(), "a nonempty mask with no runs");
    assert!(
        decode_keep_runs(&[1u8, 1, 0, 0, 0], &mut p, 0, 1).is_err(),
        "runs for an empty mask"
    );
}

#[test]
fn the_geometry_pool_is_the_next_kind() {
    assert_eq!(SHARED_KIND_GEOMETRY, 8, "kinds 1..=7 are taken, geometry is 8");
    assert_eq!(SHARED_GEOM_NONE, 0, "index 0 is the empty entry");
}

#[test]
fn interned_geometries_share_one_index_by_content_hash() {
    let mut b = SharedBuilder::new();
    assert_eq!(b.intern_geometry(&[]), SHARED_GEOM_NONE, "empty is none, not stored");
    let a = b.intern_geometry(&[(0, 0), (10, 0), (10, 10)]);
    assert_eq!(a, 1, "index 0 is the empty entry");
    assert_eq!(
        b.intern_geometry(&[(0, 0), (10, 0), (10, 10)]),
        1,
        "a repeat sighting shares the index"
    );
    assert_eq!(
        canonical_geom_hash(&[(0, 0), (10, 0)]),
        canonical_geom_hash(&[(0, 0), (10, 0)]),
        "the hash is stable for one array"
    );
    assert_ne!(
        canonical_geom_hash(&[(0, 0), (10, 0)]),
        canonical_geom_hash(&[(0, 0), (10, 1)]),
        "distinct arrays hash distinctly"
    );
    assert_eq!(b.intern_geometry(&[(0, 0), (10, 1)]), 2, "different content, new index");
}

#[test]
fn keep_masks_are_emitted_per_row_and_zoom_and_round_trip() {
    let mut b = SharedBuilder::new();
    let g = b.intern_geometry(&[(0, 0), (10, 0), (10, 10)]);
    let first = b.emit_keep_mask(7, 12, g, &[true, true, false]);
    assert_eq!(first, 0, "the geometry's first mask");
    assert_eq!(
        b.emit_keep_mask(7, 12, g, &[true, true, false]),
        0,
        "an identical re-emit shares the mask"
    );
    assert_eq!(
        b.emit_keep_mask(7, 13, g, &[true, true, true]),
        1,
        "another zoom is another mask"
    );
    let section = b.serialize().expect("section");
    let view = SharedView::parse(&section).expect("parse");
    assert_eq!(view.header.pool_count, 8, "the geometry pool rides last");
    assert_eq!(view.geometries.len(), 2, "empty index 0 plus one geometry");
    assert!(view.geometries[0].is_empty(), "index 0 is empty");
    let geom = view.geometry(1).expect("geometry 1");
    assert_eq!(geom.points, vec![(0, 0), (10, 0), (10, 10)], "full-detail vertices");
    assert_eq!(geom.mask_for(7, 12).expect("z12").keep, vec![true, true, false]);
    assert_eq!(view.keep_mask(1, 7, 13).expect("z13").kept_count(), 3, "all kept at z13");
    assert_eq!(view.geometry(2), None, "past the pool");
    assert_eq!(view.keep_mask(1, 7, 14), None, "no mask at an unemitted zoom");
}

#[test]
fn sections_without_geometry_stay_seven_pools_and_read_empty() {
    // Pre-lane-A shape: no kind-8 pool on the wire, and the reader fills
    // in the empty entry rather than failing.
    let mut b = SharedBuilder::new();
    b.push_row(
        SharedLogicalRow {
            logical_id: 1,
            name_ref: SHARED_NAME_NONE,
            kind: 45,
            kind_detail: 0,
            view_bits: 0,
            building_idx: 0,
            carriageway_idx: 0,
            lane_turns_idx: 0,
            flags: 0,
        },
        ID_NONE,
    );
    let section = b.serialize().expect("section");
    let view = SharedView::parse(&section).expect("parse");
    assert_eq!(view.header.pool_count, 7, "no geometry interned, no kind-8 pool");
    assert_eq!(
        view.geometries,
        vec![SharedCanonicalGeom::default()],
        "a missing pool reads as empty"
    );
}

#[test]
fn a_corrupt_geometry_pool_is_refused() {
    fn pool_with_one_mask() -> Vec<u8> {
        SharedGeometryPool {
            geoms: vec![
                SharedCanonicalGeom::default(),
                SharedCanonicalGeom {
                    points: vec![(0, 0), (4, 2)],
                    masks: vec![SharedKeepMask { row: 3, zoom: 12, keep: vec![true, false] }],
                },
            ],
        }
        .serialize()
    }
    let good = pool_with_one_mask();
    let (_, used) = SharedGeometryPool::parse(&good).expect("the good pool parses");
    assert_eq!(used, good.len(), "no trailing bytes on a good pool");
    assert_eq!(good.len(), 52, "count + two entries + 4 vert bytes + mask + pad");
    // Layout: [count 0..4][geom0 4..12][geom1 12..20][verts 20..24]
    // [mask header 24..40][runs 40..50][pad 50..52].
    let cases: &[(&str, fn(&mut Vec<u8>))] = &[
        ("a truncated stream", |p| {
            p.pop();
        }),
        ("a mask past its vertices", |p| p[32] = 3),
        ("row 0", |p| p[24] = 0),
        ("a zoom past the maximum", |p| p[28] = 23),
        ("a dirty reserved byte", |p| p[29] = 1),
        ("a run bit past 1", |p| p[40] = 2),
        ("a zero-length run", |p| {
            p[41] = 0;
            p[42] = 0;
            p[43] = 0;
            p[44] = 0;
        }),
        ("non-alternating runs", |p| p[45] = 1),
        ("non-zero padding", |p| p[50] = 7),
    ];
    for (what, break_it) in cases {
        let mut bytes = good.clone();
        break_it(&mut bytes);
        assert!(SharedGeometryPool::parse(&bytes).is_err(), "{what} is refused");
    }
    // An index 0 that is not empty, and trailing garbage past the pad.
    let nonempty_zero = SharedGeometryPool {
        geoms: vec![SharedCanonicalGeom { points: vec![(1, 1)], masks: Vec::new() }],
    }
    .serialize();
    assert!(SharedGeometryPool::parse(&nonempty_zero).is_err(), "index 0 must be empty");
    let mut trailed = good.clone();
    trailed.extend_from_slice(&[9, 9, 9, 9]);
    assert!(SharedGeometryPool::parse(&trailed).is_err(), "trailing garbage");
}
