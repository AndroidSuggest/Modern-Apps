#[cfg(test)]
mod tests_part10 {
    use super::*;
    use super::tests::*;
    use crate::schema::Class;
    use tilecodec::mamaps::dict;
    /// A tile with no land is all sea, and a tile with land has that land cut out of it.
    ///
    /// The reason the sea needs geometry at all: it used to be the renderer's background colour,
    /// so nothing was ever drawn over it and marine protected areas — real `landuse` polygons,
    /// hundreds of kilometres across — painted green across open water.
    #[test]
    fn the_sea_is_the_tile_minus_the_land() {
        let _budget = budget();
        let land_at = |lon: f64, lat: f64| Feature {
            class: Class::area(dict::LAYER_EARTH, tilecodec::mamaps::dict::NONE, 0),
            geometry: square(lon, lat, 0.05),
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
        };
        // One patch of land, and a lake sitting on it so the water layer already exists. Plus a
        // marine protected area out at sea with no land under it at all — a real `landuse` polygon
        // over open water, which is the exact shape of the bug this exists to fix.
        let features = vec![
            land_at(-120.0, 35.0),
            Feature {
                class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0),
                geometry: square(-119.99, 35.01, 0.005),
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
}
