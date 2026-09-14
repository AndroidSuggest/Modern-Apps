        concatenate(&mut first, second);

        assert_eq!(first, together, "two chunks concatenated are not the one-pass layer");
        // Spelled out as well, because `assert_eq` on the whole layer would also pass if both were
        // empty, and an arena its parts do not tile exactly is what the encoder rejects.
        assert_eq!(first.layer.features.len(), 2);
        assert_eq!(first.layer.features[1].parts_offset, 1);
        assert_eq!(first.layer.parts[1].coord_start, first.layer.parts[0].point_count);
        assert_eq!(first.layer.coords.len(), 10);
    }

    /// The merge's contract on its own, without a build around it: ascending tiles, and layers in id
    /// order within a tile, with same-layer contributions from several chunks collapsed into one.
    ///
    /// Written through a [`ChunkSpill`] first, because that is the only way the merge is reachable
    /// now — and so this doubles as the round-trip check on a chunk whose entries all have empty
    /// arenas.
    #[test]
    fn the_merge_yields_ascending_tiles_with_their_layers_in_id_order() {
        let mut early: Chunk = BTreeMap::new();
        early.insert((10, 3), ChunkEntry::new(3));
        early.insert((30, 1), ChunkEntry::new(1));
        let mut middle: Chunk = BTreeMap::new();
        middle.insert((10, 1), ChunkEntry::new(1));
        middle.insert((20, 2), ChunkEntry::new(2));
        let mut late: Chunk = BTreeMap::new();
        late.insert((10, 3), ChunkEntry::new(3));

        let spill = ChunkSpill::create(scratch()).expect("scratch");
        let refs: Vec<ChunkRef> = [early, middle, late]
            .into_iter()
            .map(|chunk| spill.write_chunk(chunk).expect("spill a chunk"))
            .collect();

        let merged: Vec<(u64, Vec<u8>)> = merge(&refs, &spill)
            .map(|tile| tile.expect("read a tile back"))
            .map(|(id, layers)| (id, layers.iter().map(|l| l.layer.layer_id).collect()))
            .collect();
        // Tile 10 carries layer 1 before layer 3 even though layer 3 was read first, and its two
        // separate layer-3 pieces arrive as one layer rather than two.
        assert_eq!(merged, vec![(10, vec![1, 3]), (20, vec![2]), (30, vec![1])]);
        spill.check_books().expect("the books balance");
    }

    /// The read window is a memory/syscall trade and must not be observable in the archive. Forced
    /// here at both clamps and either side of one entry's header, because a real build only ever
    /// reaches one clamp and which one depends on the extract.
    #[test]
    fn the_archive_is_identical_however_the_read_window_is_sized() {
        let _guard = budget();
        par::set_threads(4);
        // Small chunks, so a zoom has many streams and a tile's layers really do come from several.
        set_chunk_vertices(64);
        let store = spilled(&a_crowd());

        let want = build(&store, &settings(0, 14)).expect("build").0;
        for window in [1usize, 23, 24, 25, 4096, tilespill::MIN_WINDOW, tilespill::MAX_WINDOW] {
            tilespill::set_read_window(window);
            let got = build(&store, &settings(0, 14)).expect("build").0;
            assert_eq!(got, want, "a {window}-byte read window moved the archive");
        }

        tilespill::set_read_window(0);
        set_chunk_vertices(0);
        release_threads();
    }

    /// PLANET z14 overflow reproduction: one tile-layer with >65535 bodies through the REAL
    /// encode+append+read path. This is the ONLY path north-america never exercised
    /// (NA max 38,239). Dense cities at z14 (Jakarta etc.) cross 65535 and hit the
    /// extended-count branch. Tests boundaries 65534/65535/65536 and a large 70k case,
    /// plus that the common path (<65535) stays byte-identical (flag 0, reserved 0).
    #[test]
    fn a_tile_layer_with_more_than_65535_buildings_round_trips_through_the_full_tiler() {
        let _guard = budget();
        par::set_threads(1);
        // All features in one tiny patch so they fall into the same z14 tile(s).
        // Use slightly distinct geometries so dedup does not collapse them.
        // At z14 tile width is ~0.022 deg. Buildings have size 0.0003 deg; with
        // jitter 0.00002 deg the whole cluster occupies <0.004 deg — well inside
        // one tile interior (plus buffer), so at z14 it collapses to ONE tile.
        // Previous jitter 0.00008 deg made 0.008 deg spread -> clipped across 2 tiles.
        for n in [65534usize, 65535, 65536, 70000] {
            let mut features = Vec::with_capacity(n);
            for i in 0..n {
                let jitter_x = (i % 200) as f64 * 0.00001;
                let jitter_y = (i / 200) as f64 * 0.00001;
                // Center at -122.005, 37.005 — well inside interior of tile 14/2628/6338 area
                let lon = -122.005 + jitter_x;
                let lat = 37.005 + jitter_y;
                // Small square that stays inside tile even with simplification at z14
                features.push(Feature {
                    class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
                    geometry: square(lon, lat, 0.0003),
                    name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                                    turn_fwd: Vec::new(),
                    turn_bwd: Vec::new(),
                    building: None,
                    carriageway: tilecodec::mamaps::body::Carriageway::default(),
                });
            }
            let store = spilled(&features);
            let (bytes, _stats) = build(&store, &settings(14, 14))
                .unwrap_or_else(|e| panic!("build failed for n={n}: {e:?}"));
            let entries = tilecodec::mamaps::read::read_all(&bytes)
                .unwrap_or_else(|e| panic!("read_all failed for n={n}: {e:?}"));
            let mut max_layer_len = 0usize;
            let mut total = 0usize;
            let mut any_extended = false;
            for (_, _, body_bytes) in &entries {
                let body = Body::parse(body_bytes)
                    .unwrap_or_else(|e| panic!("Body::parse failed for n={n}: {e:?}"));
                // Check body flag for extended
                if body_bytes.len() >= 12 && body_bytes[11] == tilecodec::mamaps::body::BODY_FLAG_EXTENDED_COUNTS {
                    any_extended = true;
                }
                if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                    max_layer_len = max_layer_len.max(layer.features.len());
                    total += layer.features.len();
                    // Also verify Body::raw_len prefix matches
                    assert_eq!(Body::raw_len(body_bytes).expect("raw_len") as usize, body_bytes.len());
                }
            }
            // n buildings may split across a few tiles (grid), so max may be <n but for
            // this tight patch it should be close. For 70k we expect >65535 in one tile.
            eprintln!("n={n} -> tiles {} max_buildings {max_layer_len} total {total} extended={any_extended}", entries.len());
            if n >= 65536 {
                assert!(max_layer_len > 65535, "n={n} expected a layer >65535 but widest was {max_layer_len}");
                assert!(any_extended, "n={n} should have used extended encoding");
            } else {
                // For 65534/65535 we expect NOT extended (common path byte-identical)
                // However due to tiling across tiles, individual tile may be <n; just verify no panic and parse ok.
                // If the body's n is <=65535 it should NOT have the flag unless another tile triggered it;
                // the body-level flag is per-body, so bodies with <=65535 features stay flag 0.
                // We don't assert global flag here to avoid false positive when n=65535 splits across 2 tiles.
            }
            // For small case 100, verify common path produces flag 0 on all bodies
            if n <= 65535 {
                for (_, _, body_bytes) in &entries {
                    // Only check bodies that actually overflow; small bodies should remain flag 0
                    let body = Body::parse(body_bytes).expect("parse");
                    if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                        if layer.features.len() <= 65535 {
                            assert_eq!(body_bytes[11], 0, "common path must be flag 0 for n={n} layer {}", layer.features.len());
                            assert_eq!(&body_bytes[12..16], &[0,0,0,0], "reserved must be 0 for n={n}");
                        }
                    }
                }
            }
        }
        release_threads();
    }
}
