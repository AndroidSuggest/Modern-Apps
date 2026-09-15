#[cfg(test)]
mod tests {
    use super::*;

    /// The tests hold features in memory and the tiler reads them from a file, so they spill
    /// first. Keeps a test about tiling from reading like a test about plumbing.
    pub(super) fn spilled(features: &[Feature]) -> crate::store::Store {
        crate::store::Store::of(features).expect("spill")
    }

    use crate::schema::Class;

    use tilecodec::mamaps::dict;

    pub(super) fn square(lon: f64, lat: f64, size: f64) -> Geometry {
        Geometry::Polygons(vec![vec![vec![
            (lon, lat),
            (lon + size, lat),
            (lon + size, lat + size),
            (lon, lat + size),
            (lon, lat),
        ]]])
    }

    pub(super) fn lake(lon: f64, lat: f64, size: f64, min_zoom: u8) -> Feature {
        Feature {
            class: Class::area(dict::LAYER_WATER, crate::schema::kind("lake"), min_zoom),
            geometry: square(lon, lat, size),
            name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        }
    }

    pub(super) fn shed(lon: f64, lat: f64, size: f64) -> Feature {
        Feature {
            class: Class {
                min_area_px: crate::schema::buildings::MIN_AREA_PX,
                ..Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14)
            },
            geometry: square(lon, lat, size),
            name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        }
    }

    pub(super) fn buildings_in_archive(bytes: &[u8]) -> usize {
        tilecodec::mamaps::read::read_all(bytes)
            .expect("read")
            .iter()
            .map(|(_, _, body)| {
                Body::parse(body)
                    .expect("parse")
                    .layer(dict::LAYER_BUILDINGS)
                    .map(|layer| layer.features.len())
                    .unwrap_or(0)
            })
            .sum()
    }

    pub(super) fn settings(min_zoom: u8, max_zoom: u8) -> Settings {
        // `min_zoom`/`max_zoom` are kept as arguments so call sites read unchanged;
        // the tiler builds z0-14 regardless.
        let _ = (min_zoom, max_zoom);
        Settings {
            build_id: 7,
            scratch: scratch(),
            dem: crate::dem::Dem::from_grids(14, 17, Vec::new()),
        }
    }

    /// A scratch path of this test's own. The tiler truncates and removes it per zoom, so two tests
    /// sharing one would tile each other's chunks.
    pub(super) fn scratch() -> PathBuf {
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
    pub(super) static BUDGET: Mutex<()> = Mutex::new(());

    pub(super) fn budget() -> std::sync::MutexGuard<'static, ()> {
        let guard = BUDGET.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        // Trip the once-only environment adoption here, *before* the test sets its own count. Left
        // to `build`, the first call would run it after `set_threads(1)` and could quietly put
        // `RAYON_NUM_THREADS` back — leaving a test that says "one thread" asserting nothing.
        adopt_thread_budget();
        guard
    }

    /// `par::clear_threads` is `#[cfg(test)]` *inside* `tile_build`, so it does not exist from here.
    /// Putting the box's own count back is the same thing for a test process.
    pub(super) fn release_threads() {
        par::set_threads(std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4));
    }

    /// Enough features, spread over enough tiles, that every zoom has several chunks to merge and
    /// several tiles per chunk. A single-tile fixture would pass any merge, correct or not.
    pub(super) fn a_crowd() -> Vec<Feature> {
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

    /// **The sub-pixel drop.** A footprint under one display pixel at z14 is a speck, not detail:
    /// dropped by the `min_area_px` floor, while a house-sized one on the same build survives.
    #[test]
    fn a_speck_sized_footprint_is_dropped_but_a_house_survives() {
        // A degree at z14 is ~186k extent units at this latitude, so 0.00005 deg is ~9
        // units a side (area ~85 < the 256-unit floor) and 0.0005 deg is ~93 units a side
        // (area ~8700, well above it). Both sit inside one 0.022-deg tile; one build, because
        // a speck alone would leave zero tiles and the writer refuses an empty archive.
        let speck = shed(-120.001, 35.001, 0.00005);
        let house = shed(-120.01, 35.01, 0.0005);
        let (bytes, stats) = build(&spilled(&[speck, house]), &settings(14, 14)).expect("build");
        assert_eq!(buildings_in_archive(&bytes), 1, "the house survives, the speck does not");
        assert_eq!(stats.iter().find(|s| s.zoom == 14).expect("z14").features, 1);
    }

    /// **The z14 tolerance floor.** A wobble under half an extent unit (~0.3 m) is sub-pixel: the
    /// midpoint goes on the buildings layer, while the same geometry on a non-building layer
    /// keeps every vertex at a zero tolerance.
    #[test]
    fn sub_pixel_jaggies_simplify_on_buildings_only() {
        // A 100x10-unit wall with a 0.4-unit bump: under BUILDING_TOLERANCE, over zero.
        let ring: Vec<SigPt> =
            [(0.0, 0.0), (50.0, 0.4), (100.0, 0.0), (100.0, 10.0), (0.0, 10.0), (0.0, 0.0)]
                .iter()
                .map(|&(x, y)| SigPt::new(x, y))
                .collect();
        let mut g = Geometry::Polygons(vec![vec![ring]]);
        simplify::annotate(&mut g);
        let tol = tolerance_for_layer(dict::LAYER_BUILDINGS, 14, 0.0);
        assert_eq!(tol, BUILDING_TOLERANCE);
        let Geometry::Polygons(out) = simplify::filter(&g, tol) else { panic!() };
        assert_eq!(out[0][0].len(), 5, "the 0.4-unit bump goes: {out:?}");
        // The same shape on water at a zero tolerance keeps it.
        let water_tol = tolerance_for_layer(dict::LAYER_WATER, 14, 0.0);
        assert_eq!(water_tol, 0.0, "other layers keep the policy tolerance");
        let Geometry::Polygons(kept) = simplify::filter(&g, water_tol) else { panic!() };
        assert_eq!(kept[0][0].len(), 6, "at zero tolerance nothing moves");
        // A coarser global tolerance still wins over the floor.
        assert_eq!(tolerance_for_layer(dict::LAYER_BUILDINGS, 14, 2.0), 2.0);
        // And below the buildings floor the policy stands: z13 keeps 1.0, not 0.5.
        assert_eq!(tolerance_for_layer(dict::LAYER_BUILDINGS, 13, 1.0), 1.0);
        assert_eq!(tolerance_for_layer(dict::LAYER_WATER, 13, 1.0), 1.0);
    }

    /// **The orthogonal snap.** A hand-digitised near-rectangle snaps exactly axis-aligned;
    /// anything further off stays where it was, and closure, order and count are untouched.
    #[test]
    fn near_rectangles_snap_flat_and_true_shapes_do_not_move() {
        let ring_of = |pts: &[(f64, f64)]| -> Vec<SigPt> {
            pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()
        };
        // A 100x50 rectangle with a 0.3-unit wobble on each side.
        let wonky = ring_of(&[
            (0.0, 0.2),
            (100.3, 0.0),
            (100.0, 50.1),
            (49.8, 50.0),
            (0.0, 50.0),
            (0.0, 0.2),
        ]);
        let snapped = snap_building_ring(&wonky);
        assert_eq!(snapped.len(), wonky.len(), "no vertex added or removed");
        assert_eq!(snapped.first().map(|v| v.xy()), snapped.last().map(|v| v.xy()), "still closed");
        let pts: Vec<(f64, f64)> = snapped.iter().map(|v| v.xy()).collect();
        // Long edges are exactly flat now: y constant along the bottom, x along the sides.
        assert!((pts[0].1 - pts[1].1).abs() == 0.0, "bottom edge flat: {pts:?}");
        assert!((pts[1].0 - pts[2].0).abs() == 0.0, "right edge flat: {pts:?}");
        // A genuinely diagonal edge is out of snap range and untouched.
        let diag = ring_of(&[(0.0, 0.0), (100.0, 40.0), (100.0, 100.0), (0.0, 100.0), (0.0, 0.0)]);
        assert_eq!(
            snap_building_ring(&diag).iter().map(|v| v.xy()).collect::<Vec<_>>(),
            diag.iter().map(|v| v.xy()).collect::<Vec<_>>(),
            "a 40-unit lean is not a wobble",
        );
        // Significance rides along: the snap moves coordinates, never re-measures them.
        let mut g = Geometry::Polygons(vec![vec![wonky.clone()]]);
        simplify::annotate(&mut g);
        let Geometry::Polygons(annotated) = &g else { panic!() };
        let before: Vec<f64> = annotated[0][0].iter().map(|v| v.sig).collect();
        let Geometry::Polygons(after) = snap_building_rings(&g, dict::LAYER_BUILDINGS) else {
            panic!()
        };
        let kept: Vec<f64> = after[0][0].iter().map(|v| v.sig).collect();
        assert_eq!(before, kept, "snapping must not touch significance");
    }

    /// **The end of the id path.** A `poi` node's OSM id survives classification, the spill, the
    /// tiler's chunk merge and the body encoder, and comes back out of the archive attached to
    /// the same feature. Every other layer's id table stays absent, which is the whole reason the
    /// table is a side table.
    #[test]
    fn a_poi_carries_its_osm_id_into_the_archive() {
        let osm = crate::extract::tagged_id(240_109_189, crate::extract::ELEMENT_NODE);
        let poi = Feature {
            class: Class::line(dict::LAYER_POI, crate::schema::kind("cafe"), 0),
            geometry: Geometry::Points(vec![(-120.0, 35.0)]),
            name: Some("Blue Bottle".to_string()),
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
        let features = vec![poi, lake(-120.0, 35.0, 0.01, 0)];
        let (bytes, _) = build(&spilled(&features), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut seen = false;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_POI) else { continue };
            for (index, feature) in layer.features.iter().enumerate() {
                assert_eq!(body.feature_id(dict::LAYER_POI, index), Some(osm));
                assert_eq!(feature.name(&body), Some("Blue Bottle"));
                seen = true;
            }
            assert_eq!(
                body.feature_id(dict::LAYER_WATER, 0),
                None,
                "the lake's layer carries no id table",
            );
        }
        assert!(seen, "the poi should reach at least one tile");
    }

    /// **The lane path, end to end.** A multi-lane road's `lane_count` survives classification,
    /// the way spill, the tiler's chunk merge and the body encoder, and comes back out of a v5
    /// archive on the same feature — which is what the renderer's lane fan reads. Also pins that
    /// the archive is written at the writer's own format version.
    #[test]
    fn a_multi_lane_road_carries_its_lane_count_into_the_archive() {
        let road = Feature {
            class: Class::line(dict::LAYER_ROADS, crate::schema::kind("major_road"), 12),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.99, 35.001)]]),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 4,
                    turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (bytes, _) = build(&spilled(&[road]), &settings(14, 14)).expect("build");
        // The reader's own version byte, so an older reader rejects the archive cleanly.
        assert_eq!(
            bytes[7],
            tilecodec::mamaps::header::FORMAT_VERSION,
            "the archive header carries the writer's own format version",
        );
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut seen = false;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_ROADS) else { continue };
            for feature in &layer.features {
                assert_eq!(feature.lane_count, 4, "the road's four lanes survive the archive");
                seen = true;
            }
        }
        assert!(seen, "the road should reach at least one tile");
    }

    /// **The S3DB building path, end to end.** A building's height, roof and colours survive the
    /// spill, the tiler's chunk merge and stage C, and come back out of a v6 archive on the same
    /// feature via the body's building side table — which is what the renderer reads to extrude it.
    #[test]
    fn a_buildings_s3db_attrs_reach_the_archive() {
        use tilecodec::mamaps::body::{BuildingAttrs, ROOF_GABLED, ROOF_ORIENT_ACROSS};
        let attrs = BuildingAttrs {
            height: 420,
            min_height: 30,
            roof_height: 90,
            roof_shape: ROOF_GABLED,
            roof_direction: 64,
            roof_orientation: ROOF_ORIENT_ACROSS,
            building_colour: 0xFF_C8_A0_78,
            roof_colour: 0xFF_80_20_20,
        };
        let building = Feature {
            class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
            geometry: square(-120.001, 35.001, 0.0008),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: Some(attrs),
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (bytes, _) = build(&spilled(&[building]), &settings(14, 14)).expect("build");
        assert_eq!(
            bytes[7],
            tilecodec::mamaps::header::FORMAT_VERSION,
            "the archive header carries the writer's own format version",
        );
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut seen = false;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_BUILDINGS) else { continue };
            for index in 0..layer.features.len() {
                let got = body
                    .building_attrs(dict::LAYER_BUILDINGS, index)
                    .expect("the buildings layer must carry a building table");
                assert_eq!(got, attrs, "the S3DB attributes survive the archive");
                seen = true;
            }
        }
        assert!(seen, "the building should reach at least one tile");
    }

    /// A building with no S3DB tags carries a default record, and a tile where *every* building is
    /// default carries no building table at all — the renderer falls back to its own height. This
    /// pins that the table is paid for only where 3D data exists.
    #[test]
    fn a_plain_building_tile_carries_no_building_table() {
        use tilecodec::mamaps::body::BuildingAttrs;
        let building = Feature {
            class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
            geometry: square(-120.001, 35.001, 0.0008),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: Some(BuildingAttrs::default()),
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (bytes, _) = build(&spilled(&[building]), &settings(14, 14)).expect("build");
        for (_, _, body) in &tilecodec::mamaps::read::read_all(&bytes).expect("read") {
            let body = Body::parse(body).expect("parse");
            if body.layer(dict::LAYER_BUILDINGS).is_some() {
                assert!(
                    body.buildings.is_empty(),
                    "an all-default building tile must not carry a building table",
                );
            }
        }
    }

    /// **The DEM heightmap path, end to end.** A synthetic per-tile grid handed to the tiler reaches
    /// the archive on the tile it covers and reads back through [`tilecodec::mamaps::body::Heightmap::sample`]
    /// — and a build with no DEM leaves every tile's heightmap unset, a valid v6 with no terrain
    /// section.
    #[test]
    fn a_dem_grid_reaches_the_archive_and_reads_back_via_sample() {
        use tilecodec::mamaps::body::Heightmap;
        let (z, dim) = (14u8, 17u16);
        let (tx, ty) = (2730u64, 6335u64);
        // The tile's centre, so a small building lands squarely inside it (and so in exactly the
        // tile the synthetic DEM covers).
        let n = (1u64 << z) as f64;
        let clon = (tx as f64 + 0.5) / n * 360.0 - 180.0;
        let clat = {
            let m = std::f64::consts::PI * (1.0 - 2.0 * (ty as f64 + 0.5) / n);
            m.sinh().atan().to_degrees()
        };
        let building = || Feature {
            class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
            geometry: square(clon - 0.001, clat - 0.001, 0.001),
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
        // A ramp grid: sample(col, row) = 32768 + row*10 + col, so a read-back names its cell.
        let mut grid = Vec::with_capacity(dim as usize * dim as usize);
        for row in 0..dim as usize {
            for col in 0..dim as usize {
                grid.push(32768u16 + (row * 10 + col) as u16);
            }
        }
        let dem =
            crate::dem::Dem::from_grids(z, dim, vec![(tile_id(z, tx, ty), grid.clone())]);
        let with_dem = Settings { dem, ..settings(14, 14) };
        let (bytes, _) = build(&spilled(&[building()]), &with_dem).expect("build with dem");
        assert_eq!(
            bytes[7],
            tilecodec::mamaps::header::FORMAT_VERSION,
            "the archive header carries the writer's own format version",
        );
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut saw = false;
        for (id, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            if *id == tile_id(z, tx, ty) {
                let hm = body.heightmap.expect("the covered tile carries a heightmap");
                assert_eq!(hm.dim, dim);
                assert_eq!(hm.samples, grid, "the grid reaches the archive verbatim at its own zoom");
                assert_eq!(hm.sample(0, 0), Some(32768), "sea level at the top-left");
                assert_eq!(hm.sample(4, 3), Some(32768 + 34));
                assert_eq!(Heightmap::metres(hm.sample(4, 3).unwrap()), 34);
                saw = true;
            }
        }
        assert!(saw, "the building's tile should be emitted and carry the DEM grid");

        // Without a DEM the same build is a valid v6 whose tiles carry no heightmap section.
        let (plain, _) = build(&spilled(&[building()]), &settings(14, 14)).expect("build without dem");
        for (_, _, body) in &tilecodec::mamaps::read::read_all(&plain).expect("read") {
            let body = Body::parse(body).expect("parse");
            assert!(body.heightmap.is_none(), "no DEM means no heightmap section");
        }
    }
}
