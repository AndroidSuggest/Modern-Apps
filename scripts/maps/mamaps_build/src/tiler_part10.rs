                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            },
            Feature {
                class: Class::area(
                    dict::LAYER_LANDUSE,
                    crate::schema::kind("nature_reserve"),
                    0,
                ),
                geometry: square(-123.0, 35.0, 0.05),
                name: None,
                id: tilecodec::mamaps::body::ID_NONE,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            },
        ];
        let store = spilled(&features);
        let with_sea = Settings { ocean: true, ..settings(8, 8) };
        let (bytes, _) = build(&store, &with_sea).expect("build");

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let ocean = crate::schema::kind("ocean");
        let (mut all_sea, mut cut_out) = (0usize, 0usize);
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(water) = body.layer(dict::LAYER_WATER) else { continue };
            let has_land = body.layer(dict::LAYER_EARTH).is_some();
            for feature in &water.features {
                if feature.kind != ocean {
                    continue;
                }
                // The sea is drawn under everything else in its layer.
                assert_eq!(
                    water.features[0].kind, ocean,
                    "the sea must be the layer's first feature so lakes draw over it"
                );
                if has_land {
                    // The point of the whole exercise: land is a hole in the sea. One part is the
                    // rectangle alone, which would paint the tile blue over the coastline — the
                    // exact way this went wrong the first time it was built.
                    assert!(
                        feature.part_count > 1,
                        "the sea over a tile with land must have that land cut out of it, but it \
                         has {} part(s)",
                        feature.part_count
                    );
                    cut_out += 1;
                } else {
                    assert_eq!(
                        feature.part_count, 1,
                        "open water has nothing to cut out of it"
                    );
                    all_sea += 1;
                }
            }
        }
        assert!(
            all_sea > 0,
            "a tile holding only a marine protected area should be entirely sea, so the sea is \
             drawn over it"
        );
        assert!(cut_out > 0, "a tile with land should still carry the sea around it");
    }

    /// Without a coastline the rule "no land here means open water" is false, so the sea is not
    /// synthesised at all. Otherwise a build of, say, `water` alone would flood the planet.
    #[test]
    fn no_coastline_means_no_synthesised_sea() {
        let _budget = budget();
        let features = vec![Feature {
            class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0),
            geometry: square(-120.0, 35.0, 0.01),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
                    turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        }];
        let store = spilled(&features);
        let (bytes, _) = build(&store, &settings(8, 8)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let ocean = crate::schema::kind("ocean");
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(water) = body.layer(dict::LAYER_WATER) else { continue };
            assert!(
                water.features.iter().all(|f| f.kind != ocean),
                "the sea was synthesised without a coastline to justify it"
            );
        }
    }

    fn settings(min_zoom: u8, max_zoom: u8) -> Settings {
        Settings {
            min_zoom,
            max_zoom,
            simplification: DEFAULT_SIMPLIFICATION,
            build_id: 7,
            scratch: scratch(),
            // Off by default here: these fixtures carry no coastline, so "no land in this tile"
            // would flood every one of them. `ocean_fills_a_tile_with_no_land` opts in.
            ocean: false,
            dem: None,
            // Off: the shared-table tests opt in per test, so every existing test keeps asserting
            // byte-identical v7.
            shared_table: false,
        }
    }

    /// A scratch path of this test's own. The tiler truncates and removes it per zoom, so two tests
    /// sharing one would tile each other's chunks.
    fn scratch() -> PathBuf {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        std::env::temp_dir().join(format!(
            "mamaps_test_{}_{}.tilechunks",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ))
    }

    /// The thread budget and the chunk size are process-wide, and `cargo test` runs these tests in
    /// one process on several threads. The tests that set either take this lock against each other —
    /// not for safety, an `AtomicUsize` is safe, but so that a test asserting "this is what one
    /// thread produces" really is running on one thread when it says so.
    ///
    /// A poisoned lock is taken anyway: poisoning means another test panicked, and *its* failure is
    /// the one worth reading rather than a cascade of lock errors on top of it.
    static BUDGET: Mutex<()> = Mutex::new(());

    fn budget() -> std::sync::MutexGuard<'static, ()> {
        let guard = BUDGET.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        // Trip the once-only environment adoption here, *before* the test sets its own count. Left
        // to `build`, the first call would run it after `set_threads(1)` and could quietly put
        // `RAYON_NUM_THREADS` back — leaving a test that says "one thread" asserting nothing.
        adopt_thread_budget();
        guard
    }

    /// `par::clear_threads` is `#[cfg(test)]` *inside* `tile_build`, so it does not exist from here.
    /// Putting the box's own count back is the same thing for a test process.
    fn release_threads() {
        par::set_threads(std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4));
    }

    #[test]
    fn a_lake_tiles_and_reads_back_out_of_the_archive() {
        let features = vec![lake(-120.0, 35.0, 0.5, 0)];
        let (bytes, stats) = build(&spilled(&features), &settings(0, 6)).expect("build");
        check_not_empty(&stats).expect("not empty");

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read back");
        assert!(!entries.is_empty());
        // Every stored body holds the water layer and nothing else.
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let layer = body.layer(dict::LAYER_WATER).expect("water");
            assert!(!layer.features.is_empty());
            assert_eq!(layer.features[0].geom_type, GEOM_POLYGON);
            assert!(body.layer(dict::LAYER_BUILDINGS).is_none());
        }
    }

    /// A feature's `min_zoom` is what keeps a world tile from carrying every pond. Enforced here,
    /// so the archive does not hold what the style would not draw.
    #[test]
    fn a_feature_is_not_written_above_its_own_min_zoom() {
        let features = vec![lake(-120.0, 35.0, 0.01, 10)];
        let (_, stats) = build(&spilled(&features), &settings(0, 11)).expect("build");
        for s in &stats {
            if s.zoom < 10 {
                assert_eq!(s.tiles, 0, "z{} should be empty", s.zoom);
            }
        }
        assert!(stats.iter().any(|s| s.zoom >= 10 && s.tiles > 0), "and present once it is due");
    }

    /// **Invariant 4 and 5 together.** Ids ascend across the whole archive because `tile_id` is
    /// zoom-major and every emit path is a `BTreeMap`, and the output is byte-identical run to run
    /// because nothing iterates a hash map.
    #[test]
    fn the_output_is_byte_identical_and_its_ids_ascend() {
        let features = vec![
            lake(-120.0, 35.0, 0.4, 0),
            lake(-119.0, 36.0, 0.3, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.1, 35.1, 0.02),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            },
        ];
        let first = build(&spilled(&features), &settings(0, 8)).expect("first").0;
        let second = build(&spilled(&features), &settings(0, 8)).expect("second").0;
        assert_eq!(first, second, "two runs of the same input");

        let entries = tilecodec::mamaps::read::read_all(&first).expect("read");
        assert!(entries.windows(2).all(|p| p[1].0 > p[0].0), "ids ascend");
        for (id, _, _) in &entries {
            let (z, _, _) = tilecodec::pmtiles::tile_zxy(*id);
            let range = tilecodec::pmtiles::zoom_base(z)..tilecodec::pmtiles::zoom_base(z + 1);
            assert!(range.contains(id), "id {id} is outside z{z}");
        }
    }

    /// Two layers in one tile, which is the shape the format exists for: a cold tile is one range
    /// request whatever the style is drawing.
    #[test]
    fn a_tile_carrying_two_layers_holds_them_both() {
        let features = vec![
            lake(-120.0, 35.0, 0.02, 0),
            Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(-120.005, 35.005, 0.002),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            },
        ];
        let (bytes, _) = build(&spilled(&features), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let both = entries.iter().any(|(_, _, body)| {
            let body = Body::parse(body).expect("parse");
            body.layer(dict::LAYER_WATER).is_some() && body.layer(dict::LAYER_BUILDINGS).is_some()
        });
        assert!(both, "some tile should carry both");
    }

    #[test]
    fn a_build_that_matched_nothing_fails_rather_than_publishing_an_empty_archive() {
        let stats = vec![ZoomStats { zoom: 0, ..ZoomStats::default() }];
        assert!(check_not_empty(&stats).is_err());
        assert!(build(&spilled(&[]), &settings(0, 2)).is_err(), "the writer refuses an empty archive");
    }

    /// Simplification is per zoom, so a shallow tile holds fewer points for the same shape. If this
    /// ever inverts, the tolerance is being applied at the wrong end.
    #[test]
    fn a_shallow_zoom_carries_fewer_points_than_a_deep_one() {
        // A wiggly line, so there is something to simplify away.
        let points: Vec<(f64, f64)> = (0..200)
            .map(|i| (-120.0 + i as f64 * 0.001, 35.0 + (i % 3) as f64 * 0.0005))
            .collect();
        let features = vec![Feature {
            class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 0),
            geometry: Geometry::Lines(vec![points]),
            name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                    turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        }];
        let (_, stats) = build(&spilled(&features), &settings(6, 14)).expect("build");
        let at = |z: u8| stats.iter().find(|s| s.zoom == z).expect("zoom").points;
        assert!(at(6) < at(14), "z6 has {} points, z14 has {}", at(6), at(14));
    }

    /// Enough features, spread over enough tiles, that every zoom has several chunks to merge and
    /// several tiles per chunk. A single-tile fixture would pass any merge, correct or not.
    fn a_crowd() -> Vec<Feature> {
        let mut features = Vec::new();
        for i in 0..60 {
            let (row, column) = (i / 10, i % 10);
            let (lon, lat) = (-120.0 + column as f64 * 0.03, 35.0 + row as f64 * 0.03);
            features.push(lake(lon, lat, 0.02, 0));
            features.push(Feature {
                class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 0),
                geometry: square(lon + 0.004, lat + 0.004, 0.004),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            });
            // A line as well, so the merge has to rebase a `GEOM_LINE` feature's parts too, and a
            // long one so it crosses tiles rather than sitting inside one.
            features.push(Feature {
                class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 0),
                geometry: Geometry::Lines(vec![(0..40)
                    .map(|k| (lon + k as f64 * 0.002, lat + (k % 5) as f64 * 0.001))
                    .collect()]),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            });
        }
        features
    }

    /// **The property the parallel tiler exists to keep.** The archive is a function of the input,
    /// not of the thread count.
    ///
    /// One thread is not a formality: it is the configuration where the reader and the single worker
    /// share one queue, and it is the configuration a producer running on the pool would deadlock
    /// in. Three is the awkward one, where chunks outnumber threads unevenly and completion order
    /// is guaranteed not to be read order.
    #[test]
    fn the_archive_is_identical_at_every_thread_count() {
        let _budget = budget();
        let store = spilled(&a_crowd());
        // Small enough that the crowd above becomes many chunks, so the merge is doing real work at
        // every thread count rather than folding one chunk into itself.
        set_chunk_vertices(64);
        par::set_threads(1);
        let (one, first_stats) = build(&store, &settings(0, 12)).expect("one thread");
        for threads in [2usize, 3, 8, 17] {
            par::set_threads(threads);
            let (bytes, stats) = build(&store, &settings(0, 12)).expect("many threads");
            assert_eq!(bytes.len(), one.len(), "{threads} threads changed the archive length");
            assert_eq!(bytes, one, "{threads} threads changed the archive bytes");
            // The report is published beside the archive and diffed too, so it has to agree as
            // well: a counter summed in completion order would still be right, and one accumulated
            // per chunk into a shared total would not.
            for (a, b) in first_stats.iter().zip(&stats) {
                assert_eq!(
                    (a.zoom, a.tiles, a.features, a.points, a.dropped, a.bytes),
                    (b.zoom, b.tiles, b.features, b.points, b.dropped, b.bytes),
                    "{threads} threads changed the z{} counters",
                    a.zoom,
                );
                assert_eq!(a.rings, b.rings, "{threads} threads changed z{} stage C", a.zoom);
            }
        }
        set_chunk_vertices(0);
        release_threads();
    }

    /// The stronger claim, and the one that makes the thread count irrelevant rather than merely
    /// tested: concatenating a partition of the feature stream in partition order reproduces the
    /// stream, so **where** the chunk boundaries fall cannot matter either.
    ///
    /// A chunk of one vertex means one chunk per feature — every tile in the merge is then assembled
    /// from as many pieces as it has features, which is the worst case for [`concatenate`] and the
    /// one where a wrong `parts_offset` could not hide.
    #[test]
    fn the_archive_is_identical_however_the_features_are_chunked() {
        let _budget = budget();
        let store = spilled(&a_crowd());
        par::set_threads(4);
        set_chunk_vertices(1);
        let one_per_feature = build(&store, &settings(0, 12)).expect("tiny chunks").0;
        for size in [7usize, 64, 4096, 1 << 20] {
            set_chunk_vertices(size);
            let bytes = build(&store, &settings(0, 12)).expect("build").0;
            assert_eq!(bytes, one_per_feature, "a chunk of {size} vertices changed the archive");
        }
        set_chunk_vertices(0);
        release_threads();
    }

    /// Feature order **within a tile layer** is the store's order, asserted rather than assumed.
    ///
    /// This is the failure the whole module is arranged around, and it is invisible from outside: a
    /// merge that folded chunks in completion order would still put every feature in the right tile,
    /// still draw the right picture, and still write different bytes every run. So each feature
    /// carries a `kind_detail` counting up in store order, and the decoded archive has to count up
    /// too.
    #[test]
    fn feature_order_within_a_tile_layer_is_the_store_order() {
        let _budget = budget();
        // All in one small patch so they pile into the same handful of z14 tiles, each labelled with
        // its position in the store.
        let features: Vec<Feature> = (0..40u16)
            .map(|i| Feature {
                class: Class {
                    kind_detail: i,
                    ..Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0)
                },
                geometry: square(-120.0 + i as f64 * 0.00005, 35.0, 0.004),
                name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            })
            .collect();
        let store = spilled(&features);
        set_chunk_vertices(1);
        par::set_threads(8);
        let (bytes, _) = build(&store, &settings(14, 14)).expect("build");
        set_chunk_vertices(0);
        release_threads();

        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut widest = 0usize;
        for (id, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_WATER) else { continue };
            let order: Vec<u16> = layer.features.iter().map(|f| f.kind_detail).collect();
            let mut ascending = order.clone();
            ascending.sort_unstable();
            assert_eq!(order, ascending, "tile {id} holds its features out of store order");
            widest = widest.max(order.len());
        }
        // And that assertion has to have had something to bite on: a tile carrying one feature is
        // in order however badly the merge behaves.
        assert!(widest >= 20, "no tile gathered enough features to order (widest was {widest})");
    }

    /// [`concatenate`] against the thing it claims to equal: one layer built by pushing both
    /// features in one go. Not "the offsets look plausible" but "the layer is the same layer".
    #[test]
    fn concatenating_two_chunks_of_a_layer_is_one_layer() {
        let class = Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0);
        let feature = Feature { class, geometry: square(0.0, 0.0, 1.0), name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0, turn_fwd: Vec::new(), turn_bwd: Vec::new(), building: None, carriageway: tilecodec::mamaps::body::Carriageway::default() };
        // Tile-local already, so the fixture is about the arenas rather than about projection, and
        // big enough that no minimum-area floor can drop it.
        let box_at = |x: i32| {
            IntGeometry::Polygons(vec![vec![vec![
                (x, 0),
                (x + 500, 0),
                (x + 500, 500),
                (x, 500),
                (x, 0),
            ]]])
        };

        let mut together = ChunkEntry::new(dict::LAYER_WATER);
        assert_eq!(push(&mut together, &feature, &box_at(0)), (1, 5));
        push(&mut together, &feature, &box_at(1000));

        let mut first = ChunkEntry::new(dict::LAYER_WATER);
        push(&mut first, &feature, &box_at(0));
        let mut second = ChunkEntry::new(dict::LAYER_WATER);
        push(&mut second, &feature, &box_at(1000));