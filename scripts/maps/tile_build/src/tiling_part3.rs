
        assert_eq!(
            meta,
            "{\"vector_layers\":[{\"id\":\"ma_pois\",\"minzoom\":12,\"maxzoom\":16}]}",
            "listed once, at the rebuilt layer's zoom range"
        );
    }

    /// A tile only one input holds must come out as that producer's exact bytes.
    ///
    /// Worth pinning because the win is invisible from the output alone: a re-deflate
    /// would still produce a valid archive, just slowly. Comparing the raw stored
    /// bytes is the only way to tell the passthrough is actually happening.
    #[test]
    fn a_sole_owner_tile_is_copied_through_without_recompressing() {
        let mut a = Builder::new();
        a.min_zoom = 11;
        a.max_zoom = 11;
        a.add_tile(11, 1, 1, &one_point_tile("safety"));
        let a_bytes = a.build().unwrap();

        let mut b = Builder::new();
        b.min_zoom = 11;
        b.max_zoom = 11;
        // A different tile, so neither input shares one with the other.
        b.add_tile(11, 9, 9, &one_point_tile("ma_pois"));
        let b_bytes = b.build().unwrap();

        let (aa, ba) = (
            Archive::parse(&a_bytes).unwrap(),
            Archive::parse(&b_bytes).unwrap(),
        );
        let m = Archive::parse(&merge_archives(&[&aa, &ba]).unwrap()).unwrap();

        assert_eq!(
            m.tile_raw(11, 1, 1).unwrap().map(<[u8]>::to_vec),
            aa.tile_raw(11, 1, 1).unwrap().map(<[u8]>::to_vec),
            "the first input's tile is byte-identical to its stored bytes",
        );
        assert_eq!(
            m.tile_raw(11, 9, 9).unwrap().map(<[u8]>::to_vec),
            ba.tile_raw(11, 9, 9).unwrap().map(<[u8]>::to_vec),
            "and so is the second's",
        );
    }

    /// The passthrough must not apply where the tile actually needs merging.
    #[test]
    fn a_shared_tile_is_still_merged_rather_than_passed_through() {
        let mut base = Builder::new();
        base.min_zoom = 11;
        base.max_zoom = 11;
        base.add_tile(11, 5, 5, &one_point_tile("water"));
        let base_bytes = base.build().unwrap();

        let mut ov = Builder::new();
        ov.min_zoom = 11;
        ov.max_zoom = 11;
        ov.add_tile(11, 5, 5, &one_point_tile("transit_stops"));
        let ov_bytes = ov.build().unwrap();

        let (ba, oa) = (
            Archive::parse(&base_bytes).unwrap(),
            Archive::parse(&ov_bytes).unwrap(),
        );
        let m = Archive::parse(&merge_archives(&[&ba, &oa]).unwrap()).unwrap();

        let t = Tile::decode(&m.tile(11, 5, 5).unwrap().unwrap()).unwrap();
        let mut names = t.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["transit_stops", "water"], "both layers present");
    }

    /// The streaming join must produce EXACTLY what the in-memory one does.
    ///
    /// This is the test the rewrite lives or dies by. `merge_archives` is covered by
    /// every test above, so pinning the two byte for byte inherits all of it — the
    /// header, the deduplication, the run coalescing, the directory split and the tile
    /// bodies — without restating any of it.
    /// The join's half of the threading gate.
    ///
    /// Driven through [`ArchiveFile`] rather than `&Archive`, because the file reader is
    /// the one whose concurrency is new: several workers pull bodies from one handle at
    /// explicit offsets, and a seek-based reader would silently interleave and hand back
    /// the wrong bytes.
    #[test]
    fn the_thread_count_changes_no_joined_bytes() {
        let dir = std::env::temp_dir().join(format!("tb_join_threads_{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        // Enough tiles that a batch boundary falls inside the join, and both layers over
        // the same box so most tiles have two sources and take the re-encode path rather
        // than the byte copy.
        let mk = |tag: &str, a: f64, b: f64| -> Vec<Point> {
            (0..300)
                .map(|i| {
                    let f = i as f64;
                    pt(
                        -122.5 + (f * a) % 0.8,
                        37.3 + (f * b) % 0.6,
                        &format!("{tag}{i}"),
                    )
                })
                .collect()
        };
        let a = build_point_archive("safety", &mk("w", 0.0041, 0.0053), 10, 13).unwrap();
        let b = build_point_archive("ma_pois", &mk("e", 0.0037, 0.0061), 10, 13).unwrap();
        std::fs::write(dir.join("a.pmtiles"), &a).unwrap();
        std::fs::write(dir.join("b.pmtiles"), &b).unwrap();

        let run = |n: usize| {
            par::set_threads(n);
            let mut inputs = [
                ArchiveFile::open(dir.join("a.pmtiles")).unwrap(),
                ArchiveFile::open(dir.join("b.pmtiles")).unwrap(),
            ];
            let out = dir.join(format!("joined.t{n}.pmtiles"));
            merge_archives_to(&mut inputs, &out, dir.join(format!("scratch.t{n}")), false).unwrap();
            std::fs::read(&out).unwrap()
        };

        let base = run(1);
        // Against the in-memory oracle too, so this pins the parallel join to the
        // reference implementation rather than only to itself.
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());
        assert!(
            base == merge_archives(&[&aa, &ba]).unwrap(),
            "the one-thread streaming join already differs from the in-memory one"
        );
        for n in [2, 3, 32] {
            assert!(run(n) == base, "{n} threads perturbed the joined archive");
        }
        par::clear_threads();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_streaming_join_is_byte_identical_to_the_in_memory_one() {
        let dir = std::env::temp_dir().join(format!("tb_stream_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // Overlapping and disjoint tiles, several zooms, and a repeated body so the
        // deduplication and run coalescing paths are both exercised.
        let a = build_point_archive(
            "safety",
            &[pt(-122.42, 37.77, "a"), pt(-74.0, 40.7, "b")],
            10,
            12,
        )
        .unwrap();
        let b = build_point_archive(
            "ma_pois",
            &[pt(-122.42, 37.77, "c"), pt(2.35, 48.85, "d")],
            11,
            13,
        )
        .unwrap();
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let in_memory = merge_archives(&[&aa, &ba]).unwrap();

        let out = dir.join("streamed.pmtiles");
        merge_archives_to(&mut [&aa, &ba], &out, dir.join("scratch.bin"), false).unwrap();
        let streamed = std::fs::read(&out).unwrap();

        assert_eq!(
            streamed.len(),
            in_memory.len(),
            "streamed {} bytes, in-memory {}",
            streamed.len(),
            in_memory.len()
        );
        assert!(streamed == in_memory, "the two archives differ byte for byte");

        // The scratch file must not survive a successful run.
        assert!(
            !dir.join("scratch.bin").exists(),
            "the scratch file was left behind"
        );

        // And the same join driven from FILES rather than resident archives, which is
        // the only form a planet merge can take.
        let a_path = dir.join("a.pmtiles");
        let b_path = dir.join("b.pmtiles");
        std::fs::write(&a_path, &a).unwrap();
        std::fs::write(&b_path, &b).unwrap();
        let from_files = dir.join("from_files.pmtiles");
        let mut files = vec![
            ArchiveFile::open(&a_path).unwrap(),
            ArchiveFile::open(&b_path).unwrap(),
        ];
        merge_archives_to(&mut files, &from_files, dir.join("scratch2.bin"), false).unwrap();
        assert!(
            std::fs::read(&from_files).unwrap() == in_memory,
            "merging through ArchiveFile must produce the same bytes as merging in memory"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// `ArchiveFile` and `Archive` must be interchangeable, including across the
    /// two-level directory layout: a leaf-spilling archive is where a file-backed
    /// reader's own bookkeeping could diverge, since it inflates one leaf at a time
    /// into a reused buffer.
    #[test]
    fn the_file_reader_agrees_with_the_in_memory_one() {
        let dir = std::env::temp_dir().join(format!("tb_afile_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();

        // Enough distinct tiles to force leaf directories, plus a repeated body so a
        // run is present too.
        let mut b = Builder::new();
        b.min_zoom = 8;
        b.max_zoom = 8;
        b.metadata = br#"{"name":"leafy"}"#.to_vec();
        let base = pmtiles::tile_id(8, 0, 0);
        for k in 0..20_000u64 {
            let body = if (5000..5004).contains(&k) {
                "shared".to_string()
            } else {
                format!("tile {k}")
            };
            b.add_tile_raw(base + k, crate::gz::compress(body.as_bytes()));
        }
        let bytes = b.build().unwrap();
        let path = dir.join("leafy.pmtiles");
        std::fs::write(&path, &bytes).unwrap();

        let mem = Archive::parse(&bytes).unwrap();
        let mut file = ArchiveFile::open(&path).unwrap();
        assert!(mem.header.leaf_length > 0, "the fixture must have spilled");
        assert_eq!(file.metadata, mem.metadata);
        assert_eq!(file.header.addressed_tiles, mem.header.addressed_tiles);
        assert_eq!(file.header.tile_entries, mem.header.tile_entries);

        // The same entries, in the same order.
        let mut from_mem: Vec<pmtiles::Entry> = Vec::new();
        mem.visit_entries(&mut |e| {
            from_mem.push(*e);
            Ok(())
        })
        .unwrap();
        let mut from_file: Vec<pmtiles::Entry> = Vec::new();
        file.visit_entries(&mut |e| {
            from_file.push(*e);
            Ok(())
        })
        .unwrap();
        assert_eq!(from_file, from_mem, "entry walks must agree");
        assert!(
            from_mem.iter().any(|e| e.run_length > 1),
            "the fixture must include a coalesced run"
        );

        // And the same bodies.
        let mut buf = Vec::new();
        for e in &from_mem {
            file.body_into(e.offset, e.length, &mut buf).unwrap();
            assert_eq!(
                buf.as_slice(),
                mem.body_at(e.offset, e.length).unwrap(),
                "body for tile {}",
                e.tile_id
            );
        }

        // An out-of-range offset must error rather than index, on both readers.
        let past = mem.header.tile_data_length + 1;
        assert!(mem.body_at(past, 16).is_err(), "in-memory reader must refuse");
        assert!(
            file.body_into(past, 16, &mut buf).is_err(),
            "file reader must refuse the same offset"
        );

        // The header-only read sees the same archive.
        let h = ArchiveFile::read_header(&path).unwrap();
        assert_eq!(h.addressed_tiles, mem.header.addressed_tiles);
        assert_eq!((h.min_zoom, h.max_zoom), (8, 8));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Ids must ascend for run coalescing and for `clustered` to be honest, so the
    /// writer refuses rather than quietly emitting an archive readers will mis-seek.
    #[test]
    fn the_streaming_writer_rejects_out_of_order_ids() {
        let dir = std::env::temp_dir().join(format!("tb_order_{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let mut b = pmtiles::StreamBuilder::new(dir.join("s.bin")).unwrap();
        b.add_tile_raw(10, b"x").unwrap();
        assert!(b.add_tile_raw(9, b"y").is_err(), "9 after 10 must fail");
        assert!(b.add_tile_raw(10, b"y").is_err(), "a repeat must fail too");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
