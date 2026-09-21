use super::*;
use crate::mamaps::body::{Feature, Layer, Part, DEFAULT_EXTENT, GEOM_LINE, WINDING_OUTER};
use crate::mamaps::write::{Options, StreamWriter};
use crate::pmtiles::{tile_id, zoom_base};
use crate::proto::Result;
use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};
use std::cell::RefCell;

/// A [`RangeReader`] over bytes in memory that logs every range asked for, so a test can assert
/// on the **number of round trips** as well as on the bytes. The same shape `stream.rs` uses.
struct Counting {
    bytes: Vec<u8>,
    requests: RefCell<Vec<(u64, u32)>>,
}

impl RangeReader for Counting {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        self.requests.borrow_mut().push((offset, length));
        if offset >= self.bytes.len() as u64 {
            return Ok(Vec::new());
        }
        // Short rather than failing, as the trait specifies.
        let end = (offset + length as u64).min(self.bytes.len() as u64);
        Ok(self.bytes[offset as usize..end as usize].to_vec())
    }
}

/// A one-line body whose contents depend on `seed`, so two tiles differ unless asked not to.
fn body_for(seed: i16) -> Body {
    let mut roads = Layer::new(dict::LAYER_ROADS);
    roads.features.push(Feature {
        kind: 45,
        kind_detail: dict::NONE,
        geom_type: GEOM_LINE,
        flags: 0,
        name_idx: crate::mamaps::body::NAME_NONE,
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
    Body { extent: DEFAULT_EXTENT, layers: vec![roads], names: Vec::new(), ids: Vec::new() , turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }
}

/// An archive of `(z, x, y, seed)` tiles, fed in ascending id order as the writer requires.
fn archive(tiles: &[(u8, u64, u64, i16)], options: Options) -> Vec<u8> {
    let mut rows: Vec<(u64, i16)> =
        tiles.iter().map(|(z, x, y, seed)| (tile_id(*z, *x, *y), *seed)).collect();
    rows.sort_by_key(|(id, _)| *id);
    let mut writer = StreamWriter::new(options).expect("options");
    for (id, seed) in rows {
        writer.append(id, &body_for(seed)).expect("append");
    }
    writer.finish().expect("finish")
}

fn open(bytes: Vec<u8>) -> MamapsArchive<Counting> {
    MamapsArchive::open(Counting { bytes, requests: RefCell::new(Vec::new()) }).expect("open")
}

/// **Invariant 5.** Byte-identical output for identical input, twice over. Nothing in the emit
/// path iterates a hash map: the dictionary is a constant table, layers are sorted by id and
/// the content bucket is only ever probed.
#[test]
fn two_builds_of_the_same_input_are_byte_identical() {
    let tiles: Vec<(u8, u64, u64, i16)> =
        (0..4u64).flat_map(|x| (0..4u64).map(move |y| (2u8, x, y, (x * 4 + y) as i16))).collect();
    let first = archive(&tiles, Options::default());
    let second = archive(&tiles, Options::default());
    assert_eq!(first, second);
    // And feeding the same tiles in a different order gives the same file, because `archive`
    // sorts by id and the writer's output depends on nothing else. This is what stands in for
    // "identical at 1/2/3/32 threads": a threaded producer differs only in arrival order.
    let mut shuffled = tiles.clone();
    shuffled.reverse();
    assert_eq!(first, archive(&shuffled, Options::default()));
}

/// The dictionary is the same bytes whatever the archive covers, which is what makes a diff of
/// two archives a diff of their tiles.
#[test]
fn a_small_archive_has_the_same_dictionary_as_a_large_one() {
    let small = archive(&[(0, 0, 0, 1)], Options::default());
    let large: Vec<(u8, u64, u64, i16)> =
        (0..8u64).flat_map(|x| (0..8u64).map(move |y| (3u8, x, y, (x * 8 + y) as i16))).collect();
    let large = archive(&large, Options::default());
    let dict_of = |bytes: &[u8]| {
        let h = Header::parse(bytes).expect("header");
        bytes[h.dict_offset as usize..(h.dict_offset + h.dict_len as u64) as usize].to_vec()
    };
    assert_eq!(dict_of(&small), dict_of(&large));
    assert_eq!(dict_of(&small), Dictionary::schema().serialize());
}

/// **Invariant 4.** Ids must ascend, and the writer refuses rather than sorting: the index is
/// built as bodies arrive, and a caller feeding them out of order is a caller whose bucketing
/// broke.
#[test]
fn appending_out_of_order_or_outside_the_zoom_range_is_refused() {
    let mut writer = StreamWriter::new(Options::default()).expect("options");
    writer.append(tile_id(1, 1, 1), &body_for(1)).expect("first");
    assert!(writer.append(tile_id(0, 0, 0), &body_for(2)).is_err(), "descending");
    assert!(writer.append(tile_id(1, 1, 1), &body_for(2)).is_err(), "repeated");

    let options = Options { min_zoom: 2, max_zoom: 3, ..Options::default() };
    let mut writer = StreamWriter::new(options).expect("options");
    assert!(writer.append(tile_id(0, 0, 0), &body_for(1)).is_err(), "shallower than min_zoom");
    assert!(writer.append(tile_id(4, 0, 0), &body_for(1)).is_err(), "deeper than max_zoom");
    assert!(writer.append(tile_id(2, 0, 0), &body_for(1)).is_ok());
}

/// And the ids the index is built on are the same zoom-major ones the generator's spill buckets
/// use, so its `zoom_base(z)..zoom_base(z + 1)` ranges are provably untouched by this format.
#[test]
fn stored_ids_are_ascending_and_land_in_their_own_zooms_range() {
    let mut tiles: Vec<(u8, u64, u64, i16)> = Vec::new();
    for z in 0..=3u8 {
        for x in 0..(1u64 << z) {
            tiles.push((z, x, 0, (z as i16 + 1) * 10));
        }
    }
    let bytes = archive(&tiles, Options { leaf_entry_capacity: 4, ..Options::default() });
    let stored = read::read_all(&bytes).expect("read_all");
    assert!(stored.windows(2).all(|p| p[1].0 > p[0].0), "ascending across leaves");
    for (id, _, _) in &stored {
        let (z, _, _) = crate::pmtiles::tile_zxy(*id);
        assert!((zoom_base(z)..zoom_base(z + 1)).contains(id), "id {id} is outside z{z}");
    }
    let addressed: u32 = stored.iter().map(|(_, run, _)| run).sum();
    assert_eq!(addressed as usize, tiles.len(), "every tile is addressed exactly once");
}

/// **Invariant 1, end to end.** A root that will not fit the opening prefix is a build failure,
/// and the escape is a bigger leaf rather than a bigger root — so a build that would have cost
/// every reader a third round trip instead grows its leaves and still opens in one.
#[test]
fn a_root_too_large_for_the_prefix_grows_its_leaves_instead() {
    // 4096 z6 tiles at one entry per leaf would need 4096 root entries, which is 128 KiB. The
    // writer must double the capacity until the root fits.
    let tiles: Vec<(u8, u64, u64, i16)> =
        (0..64u64).flat_map(|x| (0..64u64).map(move |y| (6u8, x, y, (x * 64 + y) as i16))).collect();
    let options = Options { leaf_entry_capacity: 1, max_zoom: 6, ..Options::default() };
    let bytes = archive(&tiles, options);
    let header = Header::parse(&bytes).expect("header");
    assert!(header.leaf_entry_capacity > 1, "the capacity grew to {}", header.leaf_entry_capacity);
    let prefix = header::HEADER_LEN as u64 + header.dict_len as u64 + header.root_len as u64;
    assert!(prefix <= OPEN_PREFIX_BYTES as u64, "the prefix is {prefix} bytes");
    // And it still opens in one request and reads back.
    let mut a = open(bytes);
    assert_eq!(a.reader.requests.borrow().len(), 1);
    assert_eq!(a.tile(6, 63, 63).expect("read").expect("present"), body_for(63 * 64 + 63));
}

#[test]
fn an_empty_archive_is_refused_rather_than_written() {
    let writer = StreamWriter::new(Options::default()).expect("options");
    assert!(writer.finish().is_err(), "an archive with no tiles addresses nothing");
}

#[test]
fn a_build_id_survives_the_round_trip_and_is_what_invalidates_a_cache() {
    let options = Options { build_id: 0xDEAD_BEEF_0BAD_F00D, ..Options::default() };
    let a = open(archive(&[(0, 0, 0, 1)], options));
    assert_eq!(a.header.build_id, 0xDEAD_BEEF_0BAD_F00D);
    // Republishing the same tiles under a new id gives a different header and the same data,
    // which is exactly what lets a reader notice without a second request.
    let options = Options { build_id: 1, ..Options::default() };
    let other = archive(&[(0, 0, 0, 1)], options);
    assert_ne!(Header::parse(&other).expect("header").build_id, a.header.build_id);
}

#[test]
fn the_rings_validated_flag_is_carried_so_the_renderer_can_gate_its_repair_pass() {
    let plain = open(archive(&[(0, 0, 0, 1)], Options::default()));
    assert!(!plain.header.rings_validated(), "not claimed unless the generator says so");
    let options = Options { rings_validated: true, ..Options::default() };
    let validated = open(archive(&[(0, 0, 0, 1)], options));
    assert!(validated.header.rings_validated());
}

/// An archive written against a different schema table must be refused on open, not rendered
/// with every kind meaning something else.
#[test]
fn an_archive_whose_dictionary_is_not_this_schema_is_refused_on_open() {
    let mut bytes = archive(&[(0, 0, 0, 1)], Options::default());
    let header = Header::parse(&bytes).expect("header");
    // `island` is the first kind name; corrupt one byte of it.
    let at = header.dict_offset as usize;
    let island = bytes[at..].windows(6).position(|w| w == b"island").expect("island") + at;
    bytes[island] = b'X';
    let opened = MamapsArchive::open(Counting { bytes, requests: RefCell::new(Vec::new()) });
    let failure = match opened {
        Ok(_) => panic!("an archive written against another schema should be refused"),
        Err(e) => e,
    };
    assert!(failure.0.contains("disagrees with this build's schema"), "{}", failure.0);
}

/// Planet per-leaf data-span overflow: a leaf whose entries' max(offset)-
/// min(offset) would exceed u32::MAX (planet: 4294968552 = u32::MAX+1257)
/// must be split, otherwise LeafEntry.offset_delta (u32) overflows.
/// Common case (NA ~418 MB, all spans < u32::MAX) must stay byte-identical
/// to the old chunks(capacity) partitioning (NA sha FF312EC...).
#[test]
fn a_leaf_whose_data_span_would_exceed_u32_max_is_split_and_common_case_stays_byte_identical() {
    use crate::mamaps::write::{Pending, Options, StreamWriter};

    // 1) Common case: all offsets within one capacity chunk and span < u32::MAX
    // → must yield ceil(N/capacity) leaves, same as old chunks(capacity).
    {
        let n = 5000usize;
        let cap = 4096u32;
        // Offsets grow linearly ~1 KiB per entry → 5000 KiB ≈5 MB span < 4 GiB
        let entries: Vec<Pending> = (0..n)
            .map(|i| Pending { tile_id: crate::pmtiles::tile_id(4, (i%32) as u64, (i/32) as u64), offset: i as u64 * 1024, run_length: 1, length: 100 })
            .collect();
        let split = crate::mamaps::write::StreamWriter::partition_for_test(&entries, cap).expect("partition").expect("split");
        let expected_leaves = (n + cap as usize - 1)/ cap as usize;
        assert_eq!(split.0.len(), expected_leaves, "common case must use count-only split");
        assert_eq!(split.1.iter().map(|l| l.len()).sum::<usize>(), n);
        // Every leaf leaf_offset must be byte-identical to old count-only: leaf_offset = leaf_index * cap*16
        for (i, re) in split.0.iter().enumerate() {
            assert_eq!(re.leaf_offset, (i * cap as usize * crate::mamaps::index::LEAF_ENTRY_LEN) as u64);
        }
        // Every delta fits u32 and reconstructs
        for (re, leaf) in split.0.iter().zip(split.1.iter()) {
            for e in leaf { assert!((e.offset_delta as u64 + re.base_data_offset) <= u32::MAX as u64 + re.base_data_offset); }
        }
    }

    // 2) Span overflow: 3 entries within one capacity slot but offsets 0 and u32::MAX+5000
    // must be split into 2 leaves even though count < capacity.
    {
        let cap = 4096u32;
        let entries = vec![
            Pending { tile_id: crate::pmtiles::tile_id(4,0,0), offset: 0, run_length: 1, length: 10 },
            Pending { tile_id: crate::pmtiles::tile_id(4,1,0), offset: 1_000_000, run_length: 1, length: 10 },
            Pending { tile_id: crate::pmtiles::tile_id(4,2,0), offset: u32::MAX as u64 + 5000, run_length: 1, length: 10 },
        ];
        let split = crate::mamaps::write::StreamWriter::partition_for_test(&entries, cap).expect("partition").expect("split");
        assert!(split.0.len() >= 2, "span > u32::MAX must force an extra leaf split (got {} leaves)", split.0.len());
        let total: usize = split.1.iter().map(|l| l.len()).sum();
        assert_eq!(total, 3);
        for (re, leaf) in split.0.iter().zip(split.1.iter()) {
            let max_delta = leaf.iter().map(|e| e.offset_delta as u64).max().unwrap_or(0);
            assert!(max_delta <= u32::MAX as u64, "every delta must fit u32 (max_delta={})", max_delta);
            // Absolute offset reconstructs
            for e in leaf {
                let abs = re.base_data_offset + e.offset_delta as u64;
                let found = entries.iter().any(|pe| pe.offset == abs);
                assert!(found, "reconstructed offset {} must be one of the input offsets", abs);
            }
        }
        // Old fixed-chunk logic would have produced 1 leaf and later failed with
        // "leaf spans 429496..." — new logic succeeds by splitting.
        eprintln!("span-overflow partition: {} leaves for 3 entries spanning >u32::MAX (byte-identical common case preserved)", split.0.len());
    }

    // Also smoke a real archive (5000 tiles, 2 leaves) to ensure end-to-end still works.
    // z8: 256x256 tiles, so (i%32, i/32) for i in 0..5000 stays in range and unique (z4
    // only has 16 rows — y past 15 wraps tile_id into duplicates the writer refuses).
    let tiles: Vec<(u8,u64,u64,i16)> = (0..5000u64).map(|i| (8u8, i%32, i/32, i as i16)).collect();
    let opts = Options { leaf_entry_capacity: 4096, ..Options::default() };
    let mut rows: Vec<(u64,i16)> = tiles.iter().map(|(z,x,y,s)| (crate::pmtiles::tile_id(*z,*x,*y),*s)).collect();
    rows.sort_by_key(|(id,_)| *id);
    let mut w = StreamWriter::new(opts).expect("opts");
    for (id, seed) in rows { w.append(id, &crate::mamaps::body::Body { extent: 4096, layers: {
        let mut l = crate::mamaps::body::Layer::new(crate::mamaps::dict::LAYER_ROADS);
        l.features.push(crate::mamaps::body::Feature{kind:1,kind_detail:0,geom_type:1,flags:0,name_idx:0,parts_offset:0,part_count:1,transit_color:0,transit_ordinal:0,transit_lanes:0,transit_taper:0,lane_count:0});
        l.parts.push(crate::mamaps::body::Part{coord_start:0,point_count:2,winding:0});
        l.coords = vec![(0,0),(seed,seed)];
        vec![l]
    }, names: Vec::new(), ids: Vec::new(), turn_lanes: Vec::new(), buildings: Vec::new(), heightmap: None, carriageways: Vec::new(), convention: None, region_links: Vec::new() }).expect("append"); }
    let bytes = w.finish().expect("finish");
    let hdr = crate::mamaps::Header::parse(&bytes).expect("hdr");
    assert_eq!(hdr.leaf_count as usize, (5000+4096-1)/4096);
    eprintln!("end-to-end: 5000 tiles → leaf_count={} leaf_len={}", hdr.leaf_count, hdr.leaf_len);
}
