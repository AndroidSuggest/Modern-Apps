#[cfg(test)]
mod tests {
    /// **The guard on chunk skipping.** A zoom-filtered read must yield exactly what a full read
    /// yields after the same filter, in the same order. Otherwise skipping drops real geometry, and
    /// the symptom would be a few tiles quietly missing features at one zoom -- no error, no crash.
    #[test]
    fn skipping_chunks_yields_exactly_what_a_full_read_would() {
        let path = temp("zoomfilter");
        let mut sink = Sink::create(&path).expect("create");
        // Several chunks' worth, with min_zoom varying so chunks genuinely differ in what they hold.
        let mut expected_by_zoom: Vec<Vec<u16>> = vec![Vec::new(); 15];
        for i in 0..500u16 {
            let min_zoom = (i % 15) as u8;
            let kind = i.max(1);
            let class = Class::line(dict::LAYER_ROADS, kind, min_zoom);
            let line = vec![(-120.0 + i as f64 * 0.001, 35.0), (-119.9, 35.1)];
            sink.push(&class, &Geometry::Lines(vec![line])).expect("push");
            for z in min_zoom as usize..15 {
                expected_by_zoom[z].push(kind);
            }
        }
        let store = sink.finish(&path).expect("finish");

        for z in 0..15u8 {
            let mut got = Vec::new();
            let mut reader = store.reader_for_zoom(z).expect("reader");
            while let Some(feature) = reader.next().expect("read") {
                // Asserted, not filtered. The chunk index only skips a chunk when *every* feature
                // in it is too deep, so a kept chunk still carries features this zoom does not
                // draw -- and the reader's lanes are now what drops them. Anything arriving below
                // the floor means that filter has gone missing.
                assert!(feature.class.min_zoom <= z, "z{z} was handed a z{} feature", feature.class.min_zoom);
                got.push(feature.class.kind);
            }
            assert_eq!(got, expected_by_zoom[z as usize], "z{z}");
        }

        // The deepest zoom keeps every chunk, so it must match the sequential reader exactly.
        let mut full = Vec::new();
        let mut reader = store.reader().expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            full.push(feature.class.kind);
        }
        let mut deep = Vec::new();
        let mut reader = store.reader_for_zoom(14).expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            deep.push(feature.class.kind);
        }
        assert_eq!(deep, full, "at the deepest zoom nothing is skipped");
        let _ = std::fs::remove_file(&path);
    }

    use super::*;
    use crate::schema;
    use tilecodec::mamaps::dict;

    fn temp(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mamaps_store_{}_{name}", std::process::id()))
    }

    #[test]
    fn a_class_survives_being_packed_into_one_integer() {
        let class = Class {
            layer: dict::LAYER_ROADS,
            kind: 0xbeef,
            kind_detail: 0xcafe,
            flags: 0b1010_0101,
            area: true,
            min_zoom: 14,
            min_area_px: 8.0,
        };
        assert_eq!(unpack(pack(&class).expect("pack")), class);

        // And the zero case, which is most features.
        let plain = Class::line(dict::LAYER_WATER, 1, 0);
        assert_eq!(unpack(pack(&plain).expect("pack")), plain);
    }

    /// **Every class the schema can actually produce** has to round-trip, or a feature arrives at the
    /// tiler as something else. Cheaper to prove exhaustively than to trust the bit widths.
    #[test]
    fn every_class_the_schema_emits_round_trips() {
        let cases: &[&[(&str, &str)]] = &[
            &[("natural", "water")],
            &[("waterway", "river")],
            &[("building", "yes")],
            &[("building:part", "yes")],
            &[("highway", "motorway")],
            &[("highway", "motorway_link"), ("bridge", "yes"), ("tunnel", "yes")],
            &[("railway", "subway")],
            &[("boundary", "administrative"), ("admin_level", "2")],
            &[("boundary", "administrative"), ("admin_level", "8")],
            &[("natural", "wood")],
            &[("leisure", "park")],
            &[("leisure", "pitch")],
            &[("landuse", "residential")],
            &[("place", "island")],
            &[("natural", "cliff")],
        ];
        for tags in cases {
            let class =
                schema::classify(*tags, true, schema::Layers::all()).expect("classified");
            let bits = pack(&class).unwrap_or_else(|e| panic!("{tags:?} will not pack: {e:?}"));
            assert_eq!(unpack(bits), class, "{tags:?}");
        }
    }

    /// A threshold that cannot be represented is refused, not rounded. A minimum area quietly
    /// changed is a layer quietly wrong, and it would be invisible.
    #[test]
    fn an_unrepresentable_class_is_refused_rather_than_rounded() {
        let fractional = Class { min_area_px: 2.5, ..Class::area(0, 1, 0) };
        assert!(pack(&fractional).is_err(), "a fractional threshold");
        let huge = Class { min_area_px: 4096.0, ..Class::area(0, 1, 0) };
        assert!(pack(&huge).is_err(), "a threshold past a byte");
        let deep = Class { min_zoom: 32, ..Class::area(0, 1, 0) };
        assert!(pack(&deep).is_err(), "a zoom past five bits");
    }

    /// **The lane partition, across every lane and past the wrap.**
    ///
    /// [`ZoomReader`] deals chunks over [`prefetch_lanes`] threads and reads them back by walking
    /// the lanes in the same order, and the archive's whole feature ordering rests on those two
    /// agreeing. Nothing else here reaches that code: the other store fixtures are a few hundred
    /// features, which is fewer chunks than there are lanes, so they run on a handful of lanes with
    /// one chunk each and would pass against a partition that shuffled the file.
    ///
    /// So this writes three full rounds over every lane, sized off the constants rather than a
    /// literal so it keeps its teeth if they change. Three rather than one because the wrap is the
    /// interesting part — a reader that dealt correctly but read back assuming one chunk per lane
    /// would agree for the first round and diverge after it. Every feature carries a coordinate
    /// unique to its position, so the assertion is the file's exact order rather than a count or a
    /// checksum.
    #[test]
    fn a_zoom_reader_returns_chunks_in_file_order_across_every_lane() {
        let count = 3 * prefetch_lanes() * PREFETCH_RUN * NORM_CHUNK_FEATURES as usize;
        // Unique per feature and inside real lon/lat, so the bbox fold has nothing to complain
        // about. 997 is prime, so it shares no factor with the lane or chunk counts and no aliasing
        // can hide a swapped chunk.
        //
        // Compared as e7 integers, never as `f64`. The spill quantises to the same 1e-7 grid the
        // archive header uses, so a round trip is exact on that grid and an ULP apart off it — and
        // an ULP is not what this test is about.
        let at = |i: usize| (-120.0 + (i % 997) as f64 * 0.0001, 35.0 + (i / 997) as f64 * 0.0001);
        let grid = |(x, y): (f64, f64)| ((x * 1e7).round() as i64, (y * 1e7).round() as i64);

        let path = temp("laneorder");
        let mut sink = Sink::create(&path).expect("create");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 0);
        for i in 0..count {
            let (x, y) = at(i);
            sink.push(&class, &Geometry::Lines(vec![vec![(x, y), (x, y + 0.0001)]]))
                .expect("push");
        }
        let store = sink.finish(&path).expect("finish");
        assert_eq!(store.len(), count as u64);

        // z0 keeps every chunk, because the class above is drawn from z0 -- so this is the whole
        // file, through the prefetch, with every lane loaded.
        let mut got = Vec::with_capacity(count);
        let mut reader = store.reader_for_zoom(0).expect("reader");
        while let Some(feature) = reader.next().expect("read") {
            let Geometry::Lines(lines) = &feature.geometry else { panic!("a line went in") };
            got.push(grid(lines[0][0]));
        }
        let expected: Vec<(i64, i64)> = (0..count).map(|i| grid(at(i))).collect();
        assert_eq!(got.len(), expected.len(), "the prefetch lost or invented features");
        // Located rather than just reported: `assert_eq` on two vectors this long prints something
        // nobody can read, and which chunk went astray is the whole diagnosis.
        if let Some(i) = (0..count).find(|&i| got[i] != expected[i]) {
            let chunk = i / NORM_CHUNK_FEATURES as usize;
            panic!(
                "feature {i} (chunk {chunk}, lane {}) is {:?}, expected {:?}",
                (chunk / PREFETCH_RUN) % prefetch_lanes(),
                got[i],
                expected[i],
            );
        }
    }

    /// **The `--reuse-store` path, which nothing covered at all.** A reused run rebuilds its
    /// `Store` from the index alone, and the marking-convention grid rides in there — so a grid
    /// that did not survive the round trip would leave the reused build drawing every road
    /// right-hand and white. No error, no missing feature, no failing test: just the wrong
    /// markings across a whole country, distinguishable from a correct build only by looking at
    /// it. `Conventions`' own byte round trip is tested beside the grid; this is the wiring, which
    /// is where the offsets can drift.
    #[test]
    fn a_reused_store_index_carries_the_marking_conventions() {
        let path = temp("reuse_index");
        let mut sink = Sink::create(&path).expect("create");
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 0);
        sink.push(&road, &Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.5, 35.5)]]))
            .expect("push");
        let store = sink.finish(&path).expect("finish");

        let mut grid = crate::schema::boundaries::Conventions::default();
        grid.add(
            "JP",
            &[vec![vec![
                (130.0, 30.0),
                (145.0, 30.0),
                (145.0, 45.0),
                (130.0, 45.0),
                (130.0, 30.0),
            ]]],
        );
        let store = store.with_conventions(grid);

        // Built by hand rather than through `Provenance::of`, which would need a source `.pbf` on
        // disk to stat. Every field distinct, so a misread offset cannot land on a matching value.
        let provenance = Provenance {
            source_len: 1234,
            source_mtime: 5678,
            layers: 0b101,
            coastline: true,
            transit_routes: false,
            graph: true,
        };
        let index = store.save_index(provenance, 99).expect("save the index");
        let (reopened, features) = Store::open(&path, provenance).expect("reopen");

        assert_eq!(features, 99, "the feature count the build id derives from");
        let (x, y) = tile_build::geom::project(137.0, 37.0, 14);
        assert_eq!(
            reopened.conventions().at_tile(14, x as u64, y as u64),
            tilecodec::mamaps::body::MarkingConvention { left_hand: true, yellow_centre: true },
            "a reused store fell back to right-hand and white",
        );
        // The grid trails the chunk index, so a wrong offset for it means a wrong offset for
        // everything before it too. Asserted together rather than trusting the grid alone.
        assert_eq!(reopened.len(), store.len());
        assert_eq!(reopened.bbox(), store.bbox());

        let _ = std::fs::remove_file(&index);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn features_written_come_back_in_order_with_their_geometry() {
        let path = temp("roundtrip");
        let mut sink = Sink::create(&path).expect("create");
        let square = vec![vec![
            (-120.0, 35.0),
            (-119.0, 35.0),
            (-119.0, 36.0),
            (-120.0, 36.0),
            (-120.0, 35.0),
        ]];
        let lake = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        sink.push(&lake, &Geometry::Polygons(vec![square.clone()])).expect("push");
        sink.push(&road, &Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.5, 35.5)]]))
            .expect("push");
        let store = sink.finish(&path).expect("finish");

        assert_eq!(store.len(), 2);
        // The bbox came for free, rather than from a pass of its own over the whole file.
        assert_eq!(store.bbox(), (-1_200_000_000, 350_000_000, -1_190_000_000, 360_000_000));

        let mut reader = store.reader().expect("reader");
        let first = reader.next().expect("read").expect("a feature");
        assert_eq!(first.class, lake);
        assert!(matches!(first.geometry, Geometry::Polygons(ref p) if p[0] == square));
        let second = reader.next().expect("read").expect("a feature");
        assert_eq!(second.class, road);
        assert!(matches!(second.geometry, Geometry::Lines(_)));
        assert!(reader.next().expect("read").is_none(), "and then the end");

        // Re-readable, because the tiler reads it once per zoom.
        let mut again = store.reader().expect("reader");
        assert_eq!(again.next().expect("read").expect("a feature").class, lake);
        let _ = std::fs::remove_file(&path);
    }

    /// Ids, classes and refs all come back exactly, in the order they went in. The one thing the
    /// ways spill has to guarantee, because the archive's feature order is this file's record order.
    #[test]
    fn spilled_ways_come_back_in_the_order_and_with_the_refs_they_went_in_with() {
        let path = temp("ways_roundtrip");
        let lake = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        // Refs chosen to exercise the delta coding: a large first id, a run of neighbours, a jump
        // backwards, and a way with none at all.
        let cases: Vec<(i64, Class, Vec<i64>, u8, Vec<u16>, Vec<u16>, Carriageway)> = vec![
            (1, lake, vec![10_000_000_001, 10_000_000_002, 10_000_000_003, 9_000_000_000], 0, vec![], vec![], Carriageway::default()),
            // A four-lane road with forward and backward turn masks and a 3/1 split, all surviving
            // the spill.
            (
                2,
                road,
                vec![],
                4,
                vec![4u16, 2, 2, 34],
                vec![1, 2],
                Carriageway { forward: 3, backward: 1, solid_dividers: 0b101 },
            ),
            (i64::MAX, lake, vec![-5, 0, 5, i64::MAX, i64::MIN], 0, vec![], vec![], Carriageway::default()),
        ];
        let mut sink = WaySink::create(&path).expect("create");
        for (id, class, refs, lanes, fwd, bwd, carriageway) in &cases {
            sink.push(*id, class, refs, None, *lanes, fwd, bwd, *carriageway, None).expect("push");
        }
        let counts = sink.finish().expect("finish");
        assert_eq!(counts.ways, 3, "one record per push");
        assert_eq!(counts.refs, 9, "refs summed over every way, so `needed` can be sized once");

        let mut reader = WayReader::open(&path).expect("open");
        let mut refs: Vec<i64> = Vec::new();
        for (id, class, expected, lanes, fwd, bwd, carriageway) in &cases {
            let (got_id, got_class, got_name, got_lanes, got_fwd, got_bwd, got_cw, _got_building) =
                reader.next(&mut refs).expect("read").expect("a way");
            assert_eq!(got_id, *id);
            assert_eq!(got_class, *class);
            assert_eq!(got_name, None, "these ways are nameless");
            assert_eq!(got_lanes, *lanes, "a road's lane count survives the spill");
            assert_eq!(&got_fwd, fwd, "forward turn masks survive the spill");
            assert_eq!(&got_bwd, bwd, "backward turn masks survive the spill");
            assert_eq!(got_cw, *carriageway, "the directional split survives the spill");
            assert_eq!(&refs, expected);
        }
        assert!(reader.next(&mut refs).expect("read").is_none(), "and then the end");
        assert!(refs.is_empty(), "the caller's buffer is cleared even at the end");
        let _ = std::fs::remove_file(&path);
    }

    /// The spill's whole premise is that a PBF's ways arrive sorted, so materialisation can stream
    /// the file instead of sorting a map's keys. An input that breaks the premise has to say so:
    /// accepting it would reorder the archive, and a reordered archive is only visible as a
    /// different hash of 655 MB.
    #[test]
    fn a_way_id_that_does_not_advance_is_refused_rather_than_reordering_the_archive() {
        let path = temp("ways_unsorted");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        let mut sink = WaySink::create(&path).expect("create");
        let none = Carriageway::default();
        sink.push(100, &class, &[1, 2], None, 0, &[], &[], none, None).expect("push");
        assert!(sink.push(99, &class, &[3], None, 0, &[], &[], none, None).is_err(), "an id going backwards");
        assert!(sink.push(100, &class, &[3], None, 0, &[], &[], none, None).is_err(), "and the same id twice");
        sink.push(101, &class, &[3], None, 0, &[], &[], none, None).expect("but forwards is fine");
        let _ = std::fs::remove_file(&path);
    }

    /// A truncated spill is a corrupt file, not a short one. It was written by `WaySink` in this
    /// same process moments earlier, so a record that will not parse means the file is not ours.
    #[test]
    fn a_truncated_ways_spill_is_an_error_rather_than_a_silently_short_read() {
        let path = temp("ways_truncated");
        let class = Class::area(dict::LAYER_WATER, schema::kind("lake"), 6);
        let mut sink = WaySink::create(&path).expect("create");
        let none = Carriageway::default();
        sink.push(1, &class, &[7, 8, 9], None, 0, &[], &[], none, None).expect("push");
        sink.push(2, &class, &[11, 12, 13], Some("named"), 0, &[], &[], none, None).expect("push");
        sink.finish().expect("finish");

        let whole = std::fs::read(&path).expect("read");
        // Cut the second record short, leaving it claiming refs the file does not hold.
        std::fs::write(&path, &whole[..whole.len() - 2]).expect("truncate");
        let mut reader = WayReader::open(&path).expect("open");
        let mut refs: Vec<i64> = Vec::new();
        assert!(reader.next(&mut refs).expect("read").is_some(), "the first record survives");
        assert!(reader.next(&mut refs).is_err(), "the cut one does not");
        let _ = std::fs::remove_file(&path);
    }

    /// Delta coding is the reason the file can be read twice without the I/O mattering. Worth
    /// pinning: a regression to fixed-width would be invisible except as a slower build.
    #[test]
    fn near_consecutive_refs_cost_about_a_byte_each() {
        let path = temp("ways_size");
        let class = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 3);
        let refs: Vec<i64> = (0..1000).map(|i| 10_000_000_000 + i).collect();
        let mut sink = WaySink::create(&path).expect("create");
        sink.push(1, &class, &refs, None, 0, &[], &[], Carriageway::default(), None).expect("push");
        sink.finish().expect("finish");
        let bytes = std::fs::metadata(&path).expect("metadata").len();
        // The first ref is a full-width id; every one after it is a delta of 1, one byte.
        assert!(bytes < 1100, "{bytes} bytes for 1000 refs, against 8000 fixed-width");
        let _ = std::fs::remove_file(&path);
    }

    /// A named way round-trips its label; a truncated name errors like a truncated ref.
    #[test]
    fn a_named_way_keeps_its_name() {
        let path = temp("ways_named");
        let class = Class::line(dict::LAYER_POI, schema::kind("cafe"), 15);
        let mut sink = WaySink::create(&path).expect("create");
        let none = Carriageway::default();
        sink.push(1, &class, &[7, 8], Some("Café"), 0, &[], &[], none, None).expect("push");
        sink.push(2, &class, &[9], None, 0, &[], &[], none, None).expect("push");
        sink.finish().expect("finish");
        let mut reader = WayReader::open(&path).expect("open");
        let mut refs: Vec<i64> = Vec::new();
        let (_, _, name, _, _, _, _, _) = reader.next(&mut refs).expect("read").expect("a way");
        assert_eq!(name.as_deref(), Some("Café"), "UTF-8 survives the spill");
        let (_, _, name, _, _, _, _, _) = reader.next(&mut refs).expect("read").expect("a way");
        assert_eq!(name, None);
        let _ = std::fs::remove_file(&path);
    }

    /// **No cross-version `--reuse-store`.** A v7 index (rewritten version
    /// byte) is refused by this v8 build rather than misread: the v8 tiler
    /// keys shared rows by stable identity, and a v7 spill carries nothing to
    /// key them on.
    #[test]
    fn a_v7_store_index_is_refused_rather_than_reused() {
        let path = temp("version_gate");
        let mut sink = Sink::create(&path).expect("create");
        let road = Class::line(dict::LAYER_ROADS, schema::kind("highway"), 0);
        sink.push(&road, &Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.5, 35.5)]]))
            .expect("push");
        let store = sink.finish(&path).expect("finish");
        let provenance = Provenance {
            source_len: 1234,
            source_mtime: 5678,
            layers: 0b101,
            coastline: true,
            transit_routes: false,
            graph: true,
        };
        let index = store.save_index(provenance, 99).expect("save the index");

        // Downgrade the saved index to v7: same bytes, older version word
        // (magic is 8 B, version the 4 LE bytes after it).
        let mut raw = std::fs::read(&index).expect("read the index");
        raw[8..12].copy_from_slice(&7u32.to_le_bytes());
        std::fs::write(&index, &raw).expect("rewrite as v7");

        let failure = match Store::open(&path, provenance) {
            Ok(_) => panic!("a v7 index must be refused"),
            Err(e) => e,
        };
        assert!(
            failure.0.contains("is version 7, this build writes 8"),
            "unexpected refusal: {}",
            failure.0,
        );
        let _ = std::fs::remove_file(&index);
        let _ = std::fs::remove_file(&path);
    }
}
