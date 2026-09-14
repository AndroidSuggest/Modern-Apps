    use super::*;
    use crate::mvt::Tile;
    use crate::pmtiles::Archive;

    fn line(coords: &[(f64, f64)], props: Vec<(&str, Value)>) -> Feature {
        Feature {
            geometry: Geometry::Lines(vec![coords.to_vec()]),
            props: props.into_iter().map(|(k, v)| (k.to_string(), v)).collect(),
        }
    }

    fn square(min_lon: f64, min_lat: f64, size: f64, name: &str) -> Feature {
        Feature {
            geometry: Geometry::Polygons(vec![vec![vec![
                (min_lon, min_lat),
                (min_lon + size, min_lat),
                (min_lon + size, min_lat + size),
                (min_lon, min_lat + size),
                (min_lon, min_lat),
            ]]]),
            props: vec![("name".to_string(), Value::String(name.into()))],
        }
    }

    fn tile_at(archive: &Archive, z: u8, lon: f64, lat: f64) -> Option<Tile> {
        let (fx, fy) = geom::project(lon, lat, z);
        archive
            .tile(z, fx.floor() as u64, fy.floor() as u64)
            .unwrap()
            .map(|b| Tile::decode(&b).unwrap())
    }

    #[test]
    fn a_line_archive_round_trips_through_the_reader() {
        let features = vec![line(
            &[(-122.42, 37.77), (-122.40, 37.79), (-122.38, 37.78)],
            vec![("maxspeed", Value::String("25 mph".into()))],
        )];
        let (bytes, report) = build_archive(&features, &Options::new("maxspeed", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (10, 12));
        assert!(String::from_utf8_lossy(&a.metadata).contains("maxspeed"));
        assert_eq!(report.len(), 3);

        for z in 10..=12u8 {
            let tile = tile_at(&a, z, -122.42, 37.77).unwrap_or_else(|| panic!("a tile at z{z}"));
            let l = tile.layer("maxspeed").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(l.features[0].geom_type, GeomType::LineString);
            assert_eq!(
                l.features[0].get("maxspeed"),
                Some(&Value::String("25 mph".into()))
            );
            // And the geometry decodes as a line.
            assert!(mvt::decode_lines(&l.features[0].geometry).is_some());
        }
    }

    #[test]
    fn a_polygon_archive_keeps_its_winding_and_its_hole() {
        let with_hole = Feature {
            geometry: Geometry::Polygons(vec![vec![
                vec![(-122.5, 37.7), (-122.3, 37.7), (-122.3, 37.9), (-122.5, 37.9), (-122.5, 37.7)],
                vec![(-122.45, 37.75), (-122.35, 37.75), (-122.35, 37.85), (-122.45, 37.85), (-122.45, 37.75)],
            ]]),
            props: vec![("name".to_string(), Value::String("Oakland".into()))],
        };
        let (bytes, _) = build_archive(&[with_hole], &Options::new("admin_city", 9, 10)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 10, -122.4, 37.8).expect("a tile");
        let f = &tile.layer("admin_city").unwrap().features[0];
        assert_eq!(f.geom_type, GeomType::Polygon);
        let rings = mvt::decode_polygons(&f.geometry).expect("polygon geometry");
        assert_eq!(rings.len(), 1);
        assert_eq!(rings[0].len(), 2, "exterior plus its hole: {rings:?}");
        assert!(mvt::signed_area(&rings[0][0]) > 0, "exterior positive");
        assert!(mvt::signed_area(&rings[0][1]) < 0, "interior negative");
    }

    /// A z6 tile spans `360/64 = 5.625` degrees of longitude, so `-123.75` is
    /// exactly the edge between tile x 9 and tile x 10. Everything below is built
    /// around that so a fixture can be made to straddle a real tile boundary
    /// without having to invert the projection.
    const Z6_TILE_EDGE_LON: f64 = -123.75;

    /// A polygon whose exterior **and** hole both cross [`Z6_TILE_EDGE_LON`], with
    /// enough sub-tolerance jitter along every edge that filtering has something to
    /// remove on both sides of the boundary.
    ///
    /// One z6 extent unit is `5.625 / 4096` degrees, about `0.00137`, so the jitter
    /// below is a third of a unit: under the 1-unit tolerance, and therefore exactly
    /// the detail a coarse zoom is supposed to drop.
    fn straddling_polygon() -> Feature {
        let jitter = |i: usize| (i % 5) as f64 * 0.0005;
        let ring = |west: f64, east: f64, south: f64, north: f64| {
            let mut r: Vec<(f64, f64)> = Vec::new();
            let (w, h) = (east - west, north - south);
            for i in 0..=200 {
                let t = i as f64 / 200.0;
                r.push((west + w * t, south + jitter(i)));
            }
            for i in 0..=20 {
                let t = i as f64 / 20.0;
                r.push((east - jitter(i), south + h * t));
            }
            for i in 0..=200 {
                let t = i as f64 / 200.0;
                r.push((east - w * t, north - jitter(i)));
            }
            for i in 0..=20 {
                let t = i as f64 / 20.0;
                r.push((west + jitter(i), north - h * t));
            }
            r.push(r[0]);
            r
        };
        let e = Z6_TILE_EDGE_LON;
        Feature {
            geometry: Geometry::Polygons(vec![vec![
                ring(e - 2.25, e + 2.75, 36.0, 40.0),
                ring(e - 1.25, e + 1.75, 37.0, 39.0),
            ]]),
            props: vec![("name".to_string(), Value::String("straddler".into()))],
        }
    }

    /// The same fixture, moved to straddle longitude 0.
    ///
    /// At z6, lon 0 projects to world x exactly 32, so this crosses the edge between
    /// tile 31 and tile 32 -- and `31 >> 5` is 0 where `32 >> 5` is 1, so those two
    /// tiles' quadtree ancestries diverge at the ROOT. That is the worst case for
    /// [`crate::subdivide`]: the geometry each tile sees has been clipped down two
    /// completely separate chains of six cells, rather than sharing a parent.
    fn root_straddling_polygon() -> Feature {
        let Feature { geometry, props } = straddling_polygon();
        let shift = -Z6_TILE_EDGE_LON;
        let Geometry::Polygons(polys) = geometry else { panic!("a polygon fixture") };
        Feature {
            geometry: Geometry::Polygons(
                polys
                    .into_iter()
                    .map(|rings| {
                        rings
                            .into_iter()
                            .map(|r| r.into_iter().map(|(lon, lat)| (lon + shift, lat)).collect())
                            .collect()
                    })
                    .collect(),
            ),
            props,
        }
    }

    /// Crossing-number point-in-ring, with a point on the ring counting as inside.
    ///
    /// Integer throughout, and `i64` for the cross products: two `i32` spans
    /// multiply to 62 bits, so containment is decided exactly rather than nearly.
    fn in_ring(ring: &[(i32, i32)], p: (i32, i32)) -> bool {
        let n = ring.len();
        let mut inside = false;
        for i in 0..n {
            let (ax, ay) = ring[i];
            let (bx, by) = ring[(i + 1) % n];
            let cross = (bx as i64 - ax as i64) * (p.1 as i64 - ay as i64)
                - (by as i64 - ay as i64) * (p.0 as i64 - ax as i64);
            if cross == 0
                && p.0 >= ax.min(bx)
                && p.0 <= ax.max(bx)
                && p.1 >= ay.min(by)
                && p.1 <= ay.max(by)
            {
                return true;
            }
            if (ay > p.1) != (by > p.1) {
                let d = by as i64 - ay as i64;
                let u = d * (p.0 as i64 - ax as i64);
                let t = (p.1 as i64 - ay as i64) * (bx as i64 - ax as i64);
                if (d > 0) == (u < t) {
                    inside = !inside;
                }
            }
        }
        inside
    }

    /// The invariant the whole annotate-then-filter design exists to hold: a hole
    /// that crossed a tile boundary is still inside its exterior once the tile has
    /// been built.
    ///
    /// A hole straddling its exterior is not something earcut can express, so the
    /// renderer has to drop it (`library/map/src/main/rust/src/tess/fill.rs`) and the
    /// island or lake fills in solid. Simplifying each clipped ring on its own is
    /// what used to produce them, so this asserts on the built archive rather than
    /// on any one stage.
    #[test]
    fn a_hole_crossing_a_tile_boundary_stays_inside_its_exterior() {
        // maxzoom above the zoom under test, so z6 is genuinely being thinned:
        // `tolerance_for` spares only the deepest zoom.
        let (bytes, _) =
            build_archive(&[straddling_polygon()], &Options::new("land", 6, 7)).unwrap();
        let a = Archive::parse(&bytes).unwrap();

        let mut checked = 0;
        for (id, raw) in a.iter_tiles().unwrap() {
            let (z, _, _) = pmtiles::tile_zxy(id);
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let f = &tile.layer("land").unwrap().features[0];
            for rings in mvt::decode_polygons(&f.geometry).expect("polygon geometry") {
                let (exterior, holes) = rings.split_first().expect("an exterior");
                for hole in holes {
                    for &p in hole {
                        assert!(
                            in_ring(exterior, p),
                            "z{z} tile {id}: hole vertex {p:?} escaped its exterior"
                        );
                    }
                    checked += 1;
                }
            }
        }
        assert!(checked > 0, "the fixture produced no holes to check");
    }

    /// Two tiles that overlap must agree, vertex for vertex, on the geometry inside
    /// the overlap. They clip the same annotated source and threshold it the same
    /// way, so the only thing that can make them disagree is thinning a clipped ring
    /// against its own endpoints -- which is what a seam at a tile join looks like.
    ///
    /// The comparison is over the band strictly inside BOTH buffered rects: the
    /// crossings each tile's own clip introduced sit exactly on the edge of that
    /// band and belong to one tile only.
    ///
    /// Held **exact**, and that is a claim worth stating since [`crate::subdivide`]
    /// arrived. A leaf's geometry is now clipped down a chain of ancestor cells, and
    /// two adjacent leaves can have different chains -- so it would have been
    /// reasonable to expect the shared band to need a shape comparison rather than a
    /// vertex one. It does not, because an ancestor boundary line either coincides
    /// with the leaf's own line on that side or lies outside the leaf entirely, so no
    /// ancestor ever puts a vertex *inside* a leaf that the leaf's own clip would not
    /// have put there. `subdivide`'s tests measure that directly; this measures the
    /// consequence at the seam, which is the thing a reader actually cares about.
    #[test]
    fn two_adjacent_tiles_agree_on_the_geometry_they_share() {
        assert_adjacent_tiles_agree(straddling_polygon(), Z6_TILE_EDGE_LON);
    }

    /// The same seam, at the one edge where the two tiles share no quadtree ancestor
    /// at all: tile 31 and tile 32 at z6 diverge at the root, so each side's geometry
    /// has been clipped down six cells that have nothing in common but the world.
    ///
    /// If a descent could open a seam, it would open it here.
    #[test]
    fn two_tiles_under_different_quadtree_roots_still_agree_on_their_seam() {
        assert_adjacent_tiles_agree(root_straddling_polygon(), 0.0);
    }

    /// Build one feature at z6 and compare the two tiles either side of `edge_lon`
    /// over the band their buffers share.
    fn assert_adjacent_tiles_agree(feature: Feature, edge_lon: f64) {
        // maxzoom above the zoom under test, so z6 is genuinely being thinned:
        // `tolerance_for` spares only the deepest zoom.
        let (bytes, _) = build_archive(&[feature], &Options::new("land", 6, 7)).unwrap();
        let a = Archive::parse(&bytes).unwrap();

        let extent = DEFAULT_EXTENT;
        let buffer = geom::buffer_for(extent);
        let (edge_tile_x, _) = geom::project(edge_lon, 0.0, 6);
        let edge = edge_tile_x * extent as f64;
        let east_tx = edge_tile_x as u64;
        // The straddling polygon covers lat 36..40, which at z6 is one tile row.
        let ty = geom::project(0.0, 38.0, 6).1.floor() as u64;

        // Every vertex of the feature, in WORLD coordinates, that lies strictly
        // inside both tiles' buffered rects.
        let shared = |tx: u64| -> Vec<(i64, i64)> {
            let tile = a
                .tile(6, tx, ty)
                .unwrap()
                .map(|b| Tile::decode(&b).unwrap())
                .unwrap_or_else(|| panic!("a tile at z6/{tx}/{ty}"));
            let f = &tile.layer("land").unwrap().features[0];
            let ox = tx as i64 * extent as i64;
            let mut out: Vec<(i64, i64)> = mvt::decode_polygons(&f.geometry)
                .expect("polygon geometry")
                .into_iter()
                .flatten()
                .flatten()
                .map(|(x, y)| (x as i64 + ox, y as i64))
                .filter(|&(x, _)| ((x as f64) - edge).abs() < buffer)
                .collect();
            out.sort_unstable();
            out
        };

        let west = shared(east_tx - 1);
        let east = shared(east_tx);
        assert!(!west.is_empty(), "the fixture reaches neither side of the edge");
        assert_eq!(west, east, "the two tiles disagree inside their overlap");
    }

    #[test]
    fn a_feature_spanning_several_tiles_appears_in_each_of_them() {
        // A line across most of California at z6 lands in more than one tile.
        let features = vec![line(&[(-124.0, 40.0), (-116.0, 33.0)], vec![])];
        let (bytes, report) = build_archive(&features, &Options::new("l", 6, 6)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(report[0].tiles > 1, "{:?}", report[0]);
        assert!(a.header.addressed_tiles > 1);
        // Every tile it reaches actually carries geometry.
        for (_, raw) in a.iter_tiles().unwrap() {
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let l = tile.layer("l").unwrap();
            assert_eq!(l.features.len(), 1);
            assert!(!l.features[0].geometry.is_empty());
        }
    }

    /// The failure mode of walking segments instead of filling the bounding box is
    /// UNDER-inclusion: a tile the line crosses but which no segment box happens to
    /// name would leave a hole in the middle of a drawn line.
    ///
    /// A long diagonal at z10 is the sharpest test of that. Every column of tiles
    /// between its two ends must hold at least one tile -- a missing column is a
    /// visible break in the line.
    #[test]
    fn a_long_diagonal_leaves_no_gap_in_the_tiles_it_covers() {
        let (west, east) = ((-124.0, 42.0), (-114.0, 33.0));
        let features = vec![line(&[west, east], vec![])];
        let (bytes, report) = build_archive(&features, &Options::new("l", 10, 10)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert!(report[0].tiles > 50, "a real spread of tiles: {:?}", report[0]);

        let (wx, wy) = geom::project(west.0, west.1, 10);
        let (ex, ey) = geom::project(east.0, east.1, 10);
        let (x0, x1) = (wx.floor() as u64, ex.floor() as u64);
        let (ylo, yhi) = (wy.floor() as u64, ey.floor() as u64);

        // Probe every column the line spans; each must carry at least one tile
        // somewhere in the y band the line occupies.
        for x in x0..=x1 {
            let mut found = false;
            for y in ylo..=yhi {
                if a.tile(10, x, y).unwrap().is_some() {
                    found = true;
                    break;
                }
            }
            assert!(found, "column x={x} has no tile: the line has a gap there");
        }

        // And every archived tile really carries geometry, so none is a stray.
        for (_, raw) in a.iter_tiles().unwrap() {
            let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
            let l = tile.layer("l").unwrap();
            assert!(!l.features[0].geometry.is_empty());
        }
    }

    #[test]
    fn tiles_a_feature_does_not_reach_are_not_created() {
        let features = vec![line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])];
        let (bytes, _) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        // A small line at z12 covers a handful of tiles, not the whole grid.
        assert!(a.header.addressed_tiles < 10, "{}", a.header.addressed_tiles);
        assert!(tile_at(&a, 12, -74.0, 40.7).is_none(), "nothing in New York");
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let features = vec![
            line(&[(-122.42, 37.77), (-122.40, 37.79)], vec![("a", Value::Uint(1))]),
            square(-122.5, 37.7, 0.1, "A"),
        ];
        let opts = Options::new("l", 10, 12);
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b, "determinism is a regression surface here");
        assert_eq!(ra, rb);
    }

    // --- the drop policy ---------------------------------------------------

    #[test]
    fn nothing_is_dropped_when_the_tile_fits() {
        let features: Vec<Feature> = (0..50)
            .map(|i| {
                let d = i as f64 * 0.0001;
                line(&[(-122.42 + d, 37.77), (-122.41 + d, 37.78)], vec![])
            })
            .collect();
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].over_budget, 0);
        assert!(report[0].kept >= 50);
    }

    #[test]
    fn a_tight_budget_drops_the_least_important_features_first() {
        // One long line and many short ones in the same tile, with a budget only a
        // few features wide. Importance is bbox span, so the long one must survive.
        let mut features = vec![line(
            &[(-122.45, 37.75), (-122.35, 37.85)],
            vec![("id", Value::String("long".into()))],
        )];
        for i in 0..200 {
            let d = i as f64 * 0.00005;
            features.push(line(
                &[(-122.40 + d, 37.80), (-122.3999 + d, 37.8001)],
                vec![("id", Value::String(format!("short{i}")))],
            ));
        }
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0, "the budget must have bitten: {:?}", report[0]);

        let a = Archive::parse(&bytes).unwrap();
        let tile = tile_at(&a, 11, -122.40, 37.80).expect("a tile");
        let ids: Vec<String> = tile
            .layer("l")
            .unwrap()
            .features
            .iter()
            .filter_map(|f| match f.get("id") {
                Some(Value::String(s)) => Some(s.clone()),
                _ => None,
            })
            .collect();
        assert!(ids.contains(&"long".to_string()), "the long line survives: {ids:?}");
    }

    #[test]
    fn a_dropping_tile_stays_within_its_budget() {
        let features: Vec<Feature> = (0..400)
            .map(|i| {
                let d = i as f64 * 0.00002;
                line(
                    &[(-122.40 + d, 37.80), (-122.399 + d, 37.801)],
                    vec![("name", Value::String(format!("street number {i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 600;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0);
        assert!(
            report[0].largest_tile_bytes <= 600,
            "largest tile {} exceeds the 600-byte budget",
            report[0].largest_tile_bytes
        );
        // The archive still reads, which is the thing a bad drop breaks.
        let a = Archive::parse(&bytes).unwrap();
        for (_, raw) in a.iter_tiles().unwrap() {
            assert!(Tile::decode(&crate::gz::decompress(raw).unwrap()).is_ok());
        }
    }
