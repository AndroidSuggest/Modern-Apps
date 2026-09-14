                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            },
        ];
        let shared = Settings { shared_table: true, ..settings(14, 14) };
        let (bytes, _) = build(&spilled(&features), &shared).expect("shared build");
        let (header, _, _) = tilecodec::mamaps::read::open_prefix(&bytes).expect("prefix");
        let Some((off, len)) = header.shared_location() else {
            panic!("a --shared-table build carries a shared section");
        };
        let view = tilecodec::mamaps::shared::SharedView::parse(
            &bytes[off as usize..(off + len) as usize],
        )
        .expect("parse the shared section");
        // Two traffic rows (one segment each), two slim refs, no geometry:
        // per-edge-per-tile bases do not dedup, so the drain no longer
        // interns them (the pool stays byte-identical to before lane A).
        assert_eq!(view.header.row_count, 2, "one row per segment: {view:?}");
        assert_eq!(view.slim_refs.len(), 2, "one slim ref per sighting");
        assert_eq!(
            view.geometries.len(),
            1,
            "only the empty entry: no geometry interned: {view:?}"
        );
        // Each row's segment survives the round trip through resolve.
        let mut segs = std::collections::BTreeSet::new();
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        for (_, _, body) in &entries {
            let body = if body.len() >= 4 && body[0..3] == *b"MBD" && body[3] == 8 {
                tilecodec::mamaps::read::resolve_body(
                    &view,
                    &tilecodec::mamaps::read::SlimBody::parse(body).expect("slim parse"),
                )
                .expect("resolve")
            } else {
                Body::parse(body).expect("parse")
            };
            let Some(layer) = body.layer(LAYER_TRAFFIC) else { continue };
            for index in 0..layer.features.len() {
                let id = body
                    .feature_id(LAYER_TRAFFIC, index)
                    .expect("the traffic layer must carry an id table");
                let (edge, seg) = unpack_component_id(id);
                assert_eq!(edge, 7, "a component_id must unpack to its source edge");
                segs.insert(seg);
                // Full bodies until lane B lands: the traffic layer is all
                // line features with their own parts.
                assert_eq!(layer.features[index].geom_type, GEOM_LINE);
            }
        }
        assert_eq!(segs.len(), 2, "both segments keep their component ids");
        // No keep-masks: the drain interns rows only, and the stitch codec
        // still pins its own contract in `layercodec.rs` (`resolve_groups_by
        // _edge_in_sighting_order`, `applying_a_mask_returns_the_segments_
        // vertices`). Geometry lives in the per-tile arenas, as v8.0 specifies.
    }

    /// **Lane C: slim-or-full stays full through the tiler.** Buildings and
    /// junction decode exactly as v7 with `--shared-table` on — the dispatch
    /// says [`crate::layercodec::BodyMode::Slim`] now that lane B's
    /// [`crate::layercodec::MIXED_BODIES`] flipped, but the tiler keeps a
    /// layer full unless **every** feature has a shared intent (junction
    /// connectors and unattributed buildings have no shared row to slim to),
    /// and the bodies prove it.
    ///
    /// Fail-watch: force slim on a layer with unattributed features and the
    /// resolve-equals-v7 assertion below quotes the mismatch; restore after.
    #[test]
    fn shared_buildings_and_junction_stay_full_v7() {
        use crate::layercodec::{body_mode, BodyMode};
        use crate::schema::junction::junction_class;
        use tilecodec::mamaps::dict::{LAYER_BUILDINGS, LAYER_JUNCTION};
        assert_eq!(body_mode(LAYER_BUILDINGS, true), BodyMode::Slim);
        assert_eq!(body_mode(LAYER_JUNCTION, true), BodyMode::Slim);
        let mut features = vec![Feature {
            class: Class::area(LAYER_BUILDINGS, crate::schema::kind("building"), 14),
            geometry: square(-120.005, 35.005, 0.002),
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
        features.push(Feature {
            class: junction_class(),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.9996, 35.0004)]]),
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
        });
        let shared = Settings { shared_table: true, ..settings(14, 14) };
        let (bytes, _) = build(&spilled(&features), &shared).expect("shared build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut saw_building = false;
        let mut saw_junction = false;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            if let Some(layer) = body.layer(LAYER_BUILDINGS) {
                saw_building = saw_building || !layer.features.is_empty();
                for feature in &layer.features {
                    assert_eq!(feature.geom_type, GEOM_POLYGON, "buildings stay full polygons");
                }
            }
            if let Some(layer) = body.layer(LAYER_JUNCTION) {
                saw_junction = saw_junction || !layer.features.is_empty();
                for feature in &layer.features {
                    assert_eq!(feature.geom_type, GEOM_LINE, "junction stays full lines");
                }
            }
        }
        assert!(saw_building, "the building survives a shared build");
        assert!(saw_junction, "the connector survives a shared build");
    }

    /// **Lane C: the widest tile-layer check covers all eleven content layers.**
    /// A tile per layer is built through the real encode path and
    /// [`widest_layers`] reports each one — so a codec that dropped a layer's
    /// accounting would fail here rather than at the 65,535-feature cap on
    /// device.
    ///
    /// Fail-watch: delete a layer's `fetch_max` sample in `encode_batch` and
    /// this quotes `left: 0, right: 1` on that layer's widest count; restore
    /// after quoting.
    #[test]
    fn widest_tile_layer_reports_all_eleven_layers() {
        use tilecodec::mamaps::dict::LAYERS;
        // Eleven content layers: earth..junction minus transit, whose geometry
        // comes from a GTFS export the fixture path does not carry.
        let layers: [(u8, Class, Geometry); 11] = [
            (
                dict::LAYER_EARTH,
                Class::area(dict::LAYER_EARTH, crate::schema::kind("earth"), 0),
                square(-120.0, 35.0, 0.5),
            ),
            (
                dict::LAYER_WATER,
                Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), 0),
                square(-120.0, 35.0, 0.4),
            ),
            (
                dict::LAYER_LANDCOVER,
                Class::area(dict::LAYER_LANDCOVER, crate::schema::kind("forest"), 0),
                square(-120.0, 35.0, 0.3),
            ),
            (
                dict::LAYER_LANDUSE,
                Class::area(dict::LAYER_LANDUSE, crate::schema::kind("park"), 0),
                square(-120.0, 35.0, 0.25),
            ),
            (
                dict::LAYER_ROADS,
                Class::line(dict::LAYER_ROADS, crate::schema::kind("minor_road"), 0),
                Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.0, 36.0)]]),
            ),
            (
                dict::LAYER_BOUNDARIES,
                Class::line(dict::LAYER_BOUNDARIES, crate::schema::kind("country"), 0),
                Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.0, 36.0)]]),
            ),
            (
                dict::LAYER_BUILDINGS,
                Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
                square(-120.005, 35.005, 0.002),
            ),
            (
                dict::LAYER_PLACES,
                Class::line(dict::LAYER_PLACES, crate::schema::kind("locality"), 0),
                Geometry::Points(vec![(-120.0, 35.0)]),
            ),
            (
                dict::LAYER_POI,
                Class::line(dict::LAYER_POI, crate::schema::kind("cafe"), 0),
                Geometry::Points(vec![(-120.0, 35.0)]),
            ),
            (
                dict::LAYER_TRAFFIC,
                crate::schema::traffic::traffic_class(),
                Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.9996, 35.0004)]]),
            ),
            (
                dict::LAYER_JUNCTION,
                crate::schema::junction::junction_class(),
                Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.9996, 35.0004)]]),
            ),
        ];
        assert_eq!(LAYERS.len(), 12, "twelve layers in the dictionary, eleven tiled here");
        let mut seen = std::collections::BTreeSet::new();
        for (layer_id, class, geometry) in layers {
            let feature = Feature {
                class,
                geometry,
                name: None,
                id: if crate::extract::layer_tracks_ids(layer_id) {
                    match layer_id {
                        dict::LAYER_PLACES => {
                            crate::extract::tagged_id(7, crate::extract::ELEMENT_NODE)
                        }
                        dict::LAYER_POI => {
                            crate::extract::tagged_id(9, crate::extract::ELEMENT_NODE)
                        }
                        dict::LAYER_TRAFFIC => {
                            crate::schema::traffic::pack_component_id(7, 0)
                        }
                        _ => tilecodec::mamaps::body::ID_NONE,
                    }
                } else {
                    tilecodec::mamaps::body::ID_NONE
                },
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
            let (bytes, _) = build(&spilled(&[feature]), &settings(14, 14)).expect("build");
            let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
            let mut count = 0usize;
            for (_, _, body) in &entries {
                let body = Body::parse(body).expect("parse");
                count += body.layer(layer_id).map(|l| l.features.len()).unwrap_or(0);
            }
            assert!(count >= 1, "layer {layer_id} tiles at least one feature");
            seen.insert(layer_id);
        }
        // And the running maximum saw every one of them through the real path.
        // `>= 1`: the static is process-global, so an earlier test's wider
        // tile may have already raised a layer's maximum.
        let widest: std::collections::BTreeMap<u8, u64> =
            widest_layers().into_iter().collect();
        for layer_id in seen {
            assert!(
                widest.get(&layer_id).copied().unwrap_or(0) >= 1,
                "layer {layer_id} reported through widest_layers",
            );
        }
    }

    /// A measurement, not an assertion: build the same z14 region with and without a dense grid of
    /// traffic segments and print the compressed archive delta, so a real per-segment cost can be
    /// extrapolated to a region build without the California inputs to hand. Run explicitly:
    /// `cargo test traffic_layer_size_delta -- --ignored --nocapture`.
    #[test]
    #[ignore = "measurement; run with --ignored --nocapture"]
    fn traffic_layer_size_delta() {
        use crate::schema::traffic::{pack_component_id, traffic_class};
        let n = 120i32; // 120x120 = 14,400 cells
        let coords = |c: i32, r: i32| {
            let x = -120.0 + c as f64 * 0.0016;
            let y = 35.0 + r as f64 * 0.0016;
            vec![vec![(x, y), (x + 0.0014, y)]]
        };
        let road = |c: i32, r: i32| Feature {
            class: Class::line(dict::LAYER_ROADS, crate::schema::kind("minor_road"), 12),
            geometry: Geometry::Lines(coords(c, r)),
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
        let traffic = |c: i32, r: i32, i: u64| Feature {
            class: traffic_class(),
            geometry: Geometry::Lines(coords(c, r)),
            name: None,
            id: pack_component_id(i, 0),
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

        let mut base = Vec::new();
        for r in 0..n {
            for c in 0..n {
                base.push(road(c, r));
            }
        }
        let mut both = Vec::new();
        for r in 0..n {
            for c in 0..n {
                both.push(road(c, r));
            }
        }
        let mut i = 0u64;
        for r in 0..n {
            for c in 0..n {
                both.push(traffic(c, r, i));
                i += 1;
            }
        }

        let without = build(&spilled(&base), &settings(12, 14)).expect("without").0;
        let with = build(&spilled(&both), &settings(12, 14)).expect("with").0;
        let segs = (n * n) as usize;
        let delta = with.len() as i64 - without.len() as i64;
        println!(
            "traffic size delta: {} segment(s) added {} compressed byte(s) ({:.1} B/segment); \
             archive {} -> {} bytes (+{:.1}%)",
            segs,
            delta,
            delta as f64 / segs as f64,
            without.len(),
            with.len(),
            delta as f64 / without.len() as f64 * 100.0,
        );
    }
    /// polygon per tile, so the id is the only thing that says those pieces are one region. This
    /// pins both halves: the id survives into `boundaries`, and it is the *same* id in every tile
    /// the region touches. Without the second half a mask can only punch out one tile.
    #[test]
    fn a_region_area_carries_one_id_across_every_tile_it_touches() {
        let osm = crate::extract::tagged_id(396_487, crate::extract::ELEMENT_RELATION);
        // Wide enough at z14 (a tile is ~0.022 deg) to be cut into several pieces.
        let region = Feature {
            class: Class::area(
                dict::LAYER_BOUNDARIES,
                crate::schema::kind("region_area"),
                0,
            ),
            geometry: square(-120.0, 35.0, 0.1),
            name: None,
            id: osm,
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
        let border = Feature {
            class: Class::line(
                dict::LAYER_BOUNDARIES,
                crate::schema::kind("region"),
                0,
            ),
            geometry: Geometry::Lines(vec![vec![
                (-120.05, 34.95),
                (-119.95, 34.95),
                (-119.95, 35.05),
            ]]),
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
        let (bytes, _) = build(&spilled(&[region, border]), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut tiles_with_the_region = 0;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_BOUNDARIES) else { continue };
            let mut here = false;
            for index in 0..layer.features.len() {
                match body.feature_id(dict::LAYER_BOUNDARIES, index) {
                    // The region's shape. Every piece must name the same relation, or the pieces
                    // cannot be gathered back into one region.
                    Some(id) if id == osm => here = true,
                    // A border line, which is coalesced and so has no id worth keeping.
                    Some(id) => assert_eq!(
                        id,
                        tilecodec::mamaps::body::ID_NONE,
                        "only the region shape may carry an id",
                    ),
                    None => panic!("the boundaries layer should have an id table"),
                }
            }
            if here {
                tiles_with_the_region += 1;
            }
        }
        assert!(
            tiles_with_the_region > 1,
            "the region should be clipped across several tiles, got {tiles_with_the_region}",
        );
    }

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