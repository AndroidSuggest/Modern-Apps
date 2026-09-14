    use super::*;
    use crate::mvt::{Feature, Layer};

    const REAL_TILE: &[u8] = include_bytes!("../tests/fixtures/v5ca_z11_tile.mvt");

    fn pt(lon: f64, lat: f64, name: &str) -> Point {
        Point {
            lon,
            lat,
            props: vec![("name".to_string(), Value::String(name.to_string()))],
        }
    }

    #[test]
    fn a_point_lands_in_the_expected_tile() {
        // San Francisco at z11 is a well-known tile: x=327, y=791.
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!((fx.floor() as u64, fy.floor() as u64), (327, 791));
    }

    /// A minimal one-tile archive carrying `metadata` verbatim.
    fn meta_archive(metadata: &str) -> Vec<u8> {
        let mut b = Builder::new();
        b.min_zoom = 1;
        b.max_zoom = 1;
        b.metadata = metadata.as_bytes().to_vec();
        b.add_tile(1, 0, 0, &one_point_tile("x"));
        b.build().unwrap()
    }

    /// A one-tile archive with real bounds, as every layer builder produces.
    /// `bounds` is `(west, south, east, north)` in e7 degrees.
    fn bounded_archive(
        layer: &str,
        bounds: (i32, i32, i32, i32),
        min_zoom: u8,
        max_zoom: u8,
    ) -> Vec<u8> {
        let (w, s, e, n) = bounds;
        let mut b = Builder::new();
        b.min_zoom = min_zoom;
        b.max_zoom = max_zoom;
        b.center_zoom = min_zoom;
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
        b.metadata = point_metadata(layer, min_zoom, max_zoom).into_bytes();
        b.add_tile(min_zoom, 0, 0, &one_point_tile(layer));
        b.build().unwrap()
    }

    fn one_point_tile(layer: &str) -> Vec<u8> {
        let mut l = Layer::new(layer);
        l.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(1, 1)]),
            props: vec![],
        });
        Tile { layers: vec![l] }.encode()
    }

    #[test]
    fn tiling_buckets_points_and_round_trips_them() {
        let pts = vec![
            pt(-122.4194, 37.7749, "SF"),
            pt(-122.4180, 37.7760, "SF2"),
            // Far away, so it must land in a different tile.
            pt(-74.0060, 40.7128, "NYC"),
        ];
        let tiles = tile_points("transit_stops", &pts, 11, DEFAULT_EXTENT);
        assert_eq!(tiles.len(), 2, "two distinct tiles");

        let sf_id = pmtiles::tile_id(11, 327, 791);
        let (_, body) = tiles.iter().find(|(id, _)| *id == sf_id).expect("the SF tile");
        let tile = Tile::decode(body).unwrap();
        let layer = tile.layer("transit_stops").unwrap();
        assert_eq!(layer.features.len(), 2, "both SF points in one tile");
        for f in &layer.features {
            assert_eq!(f.geom_type, GeomType::Point);
            let decoded = mvt::decode_points(&f.geometry).unwrap();
            assert_eq!(decoded.len(), 1);
            let (x, y) = decoded[0];
            assert!(
                (0..=DEFAULT_EXTENT as i32).contains(&x)
                    && (0..=DEFAULT_EXTENT as i32).contains(&y),
                "({x},{y}) inside the extent grid"
            );
        }
    }

    #[test]
    fn tiling_is_deterministic() {
        let pts = vec![pt(-122.42, 37.77, "a"), pt(-122.41, 37.78, "b")];
        assert_eq!(
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
            tile_points("l", &pts, 12, DEFAULT_EXTENT),
        );
    }

    /// The point path's half of the threading gate: neither the per-tile MVT encode
    /// nor the gzip loop may depend on how many threads ran them.
    ///
    /// Enough points spread over enough tiles that a batch boundary falls inside the
    /// zoom at every count tried — a fixture of two points would pass no matter how
    /// badly the fold were ordered.
    #[test]
    fn the_thread_count_changes_no_point_bytes() {
        let pts: Vec<Point> = (0..500)
            .map(|i| {
                let f = i as f64;
                pt(
                    -122.5 + (f * 0.0037) % 0.9,
                    37.2 + (f * 0.0051) % 0.7,
                    &format!("stop{i}"),
                )
            })
            .collect();

        let run = |n: usize| {
            par::set_threads(n);
            (
                tile_points("l", &pts, 13, DEFAULT_EXTENT),
                build_point_archive("l", &pts, 11, 13).unwrap(),
            )
        };
        let (base_tiles, base_archive) = run(1);
        assert!(base_tiles.len() > 1, "the fixture must span several tiles");
        for n in [2, 3, 32] {
            let (tiles, archive) = run(n);
            assert!(tiles == base_tiles, "{n} threads perturbed tile_points");
            assert!(
                archive == base_archive,
                "{n} threads perturbed the point archive"
            );
        }
        par::clear_threads();
    }

    #[test]
    fn a_point_archive_round_trips_at_every_zoom() {
        let pts = vec![pt(-122.4194, 37.7749, "Embarcadero"), pt(-118.2437, 34.0522, "Union")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 13).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!((a.header.min_zoom, a.header.max_zoom), (11, 13));
        assert!(
            String::from_utf8_lossy(&a.metadata).contains("transit_stops"),
            "metadata names the layer"
        );
        for z in 11..=13u8 {
            let (fx, fy) = project(-122.4194, 37.7749, z);
            let body = a
                .tile(z, fx.floor() as u64, fy.floor() as u64)
                .unwrap()
                .unwrap_or_else(|| panic!("a tile at z{z}"));
            let tile = Tile::decode(&body).unwrap();
            let l = tile.layer("transit_stops").unwrap();
            assert_eq!(l.features.len(), 1, "z{z}");
            assert_eq!(
                l.features[0].get("name"),
                Some(&Value::String("Embarcadero".into()))
            );
        }
    }

    #[test]
    fn merging_unions_layers_and_preserves_the_base_geometry() {
        // A real tippecanoe tile (polygons + lines) merged with our own point
        // layer: the union must keep all four layers, and the base geometry must
        // come through byte-identically.
        let base = REAL_TILE.to_vec();
        let mut ours = Layer::new("transit_stops");
        ours.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(100, 200)]),
            props: vec![("motis_id".to_string(), Value::String("us-ca-X_1".into()))],
        });
        let overlay = Tile { layers: vec![ours] }.encode();

        let merged = merge_tiles(&[base, overlay]).unwrap();
        let tile = Tile::decode(&merged).unwrap();
        let mut names = tile.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["earth", "roads", "transit_stops", "water"]);

        let before = Tile::decode(REAL_TILE).unwrap();
        for name in ["earth", "roads", "water"] {
            let b = before.layer(name).unwrap();
            let a = tile.layer(name).unwrap();
            assert_eq!(b.features.len(), a.features.len(), "{name} feature count");
            for (bf, af) in b.features.iter().zip(a.features.iter()) {
                assert_eq!(bf.geometry, af.geometry, "{name} geometry must be untouched");
                assert_eq!(bf.geom_type, af.geom_type);
            }
        }
        assert_eq!(
            tile.layer("transit_stops").unwrap().features[0].get("motis_id"),
            Some(&Value::String("us-ca-X_1".into()))
        );
    }

    #[test]
    fn a_later_input_replaces_a_colliding_layer() {
        let mut old = Layer::new("transit_stops");
        old.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(1, 1)]),
            props: vec![("v".to_string(), Value::Uint(1))],
        });
        let mut new = Layer::new("transit_stops");
        new.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(2, 2)]),
            props: vec![("v".to_string(), Value::Uint(2))],
        });
        let merged = merge_tiles(&[
            Tile { layers: vec![old] }.encode(),
            Tile { layers: vec![new] }.encode(),
        ])
        .unwrap();
        let tile = Tile::decode(&merged).unwrap();
        assert_eq!(tile.layers.len(), 1, "not duplicated");
        let l = tile.layer("transit_stops").unwrap();
        assert_eq!(l.features.len(), 1);
        assert_eq!(l.features[0].get("v"), Some(&Value::Uint(2)), "the later one wins");
    }

    #[test]
    fn merging_archives_unions_overlapping_and_disjoint_tiles() {
        // Base: two tiles. Overlay: one overlapping, one new.
        let mut base = Builder::new();
        base.min_zoom = 11;
        base.max_zoom = 11;
        let mut l = Layer::new("water");
        l.features.push(Feature {
            id: None,
            geom_type: GeomType::Polygon,
            geometry: vec![mvt::command(mvt::CMD_MOVE_TO, 1), 0, 0],
            props: vec![],
        });
        let water = Tile { layers: vec![l] }.encode();
        base.add_tile(11, 327, 791, &water);
        base.add_tile(11, 100, 100, &water);
        let base_bytes = base.build().unwrap();

        let mut ov = Builder::new();
        ov.min_zoom = 11;
        ov.max_zoom = 11;
        let mut sl = Layer::new("transit_stops");
        sl.features.push(Feature {
            id: None,
            geom_type: GeomType::Point,
            geometry: mvt::encode_points(&[(5, 5)]),
            props: vec![],
        });
        let stops = Tile { layers: vec![sl] }.encode();
        ov.add_tile(11, 327, 791, &stops);
        ov.add_tile(11, 500, 500, &stops);
        let ov_bytes = ov.build().unwrap();

        let ba = Archive::parse(&base_bytes).unwrap();
        let oa = Archive::parse(&ov_bytes).unwrap();
        let merged = merge_archives(&[&ba, &oa]).unwrap();
        let m = Archive::parse(&merged).unwrap();

        // Overlapping tile carries both layers.
        let t = Tile::decode(&m.tile(11, 327, 791).unwrap().unwrap()).unwrap();
        let mut names = t.layer_names();
        names.sort_unstable();
        assert_eq!(names, vec!["transit_stops", "water"]);
        // Base-only tile survives.
        let t = Tile::decode(&m.tile(11, 100, 100).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["water"]);
        // Overlay-only tile survives.
        let t = Tile::decode(&m.tile(11, 500, 500).unwrap().unwrap()).unwrap();
        assert_eq!(t.layer_names(), vec!["transit_stops"]);
    }

    #[test]
    fn merging_a_single_archive_is_a_faithful_copy() {
        let pts = vec![pt(-122.4194, 37.7749, "SF")];
        let bytes = build_point_archive("transit_stops", &pts, 11, 11).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let merged = merge_archives(&[&a]).unwrap();
        let m = Archive::parse(&merged).unwrap();
        assert_eq!(m.header.addressed_tiles, a.header.addressed_tiles);
        assert_eq!(m.metadata, a.metadata, "metadata round-trips unchanged");
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!(
            m.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
            a.tile(11, fx.floor() as u64, fy.floor() as u64).unwrap(),
        );
    }

    /// The overlay-only case: no input covers the whole extent, so the merged
    /// envelope has to be the union or the narrowest input would clip the rest.
    ///
    /// The fixtures set their bounds explicitly. `build_point_archive` leaves the
    /// builder's whole-world default in place, so using it here would make every
    /// assertion pass under "take the first input" too.
    #[test]
    fn merging_unions_zoom_and_bounds_across_inputs() {
        // San Francisco, z10-16.
        let west = bounded_archive(
            "safety",
            (-1_224_200_000, 377_000_000, -1_223_800_000, 377_900_000),
            10,
            16,
        );
        // New York, z12-14 - east of, and north of, the other one.
        let east = bounded_archive(
            "ma_pois",
            (-740_200_000, 407_000_000, -739_800_000, 407_900_000),
            12,
            14,
        );
        let (wa, ea) = (Archive::parse(&west).unwrap(), Archive::parse(&east).unwrap());

        let merged = merge_archives(&[&wa, &ea]).unwrap();
        let m = Archive::parse(&merged).unwrap();

        assert_eq!((m.header.min_zoom, m.header.max_zoom), (10, 16));
        assert_eq!(m.header.min_lon_e7, -1_224_200_000, "west edge from SF");
        assert_eq!(m.header.max_lon_e7, -739_800_000, "east edge from NYC");
        assert_eq!(m.header.min_lat_e7, 377_000_000, "south edge from SF");
        assert_eq!(m.header.max_lat_e7, 407_900_000, "north edge from NYC");
        // Neither input's own centre, which is the point.
        assert_eq!(m.header.center_lon_e7, (-1_224_200_000 + -739_800_000) / 2);
        assert_eq!(m.header.center_lat_e7, (377_000_000 + 407_900_000) / 2);
        assert_eq!(m.header.center_zoom, 10, "the shallowest zoom on offer");
    }

    /// Two e7 longitudes at opposite edges of the world sum past `i32::MAX`, so the
    /// midpoint has to widen before it adds.
    #[test]
    fn the_merged_centre_survives_a_world_wide_envelope() {
        let west = bounded_archive("a", (-1_800_000_000, -850_000_000, -1_700_000_000, -840_000_000), 1, 2);
        let east = bounded_archive("b", (1_700_000_000, 840_000_000, 1_800_000_000, 850_000_000), 1, 2);
        let (wa, ea) = (Archive::parse(&west).unwrap(), Archive::parse(&east).unwrap());

        let m = Archive::parse(&merge_archives(&[&wa, &ea]).unwrap()).unwrap();
        assert_eq!(m.header.min_lon_e7, -1_800_000_000);
        assert_eq!(m.header.max_lon_e7, 1_800_000_000);
        assert_eq!(m.header.center_lon_e7, 0);
        assert_eq!(m.header.center_lat_e7, 0);
    }

    #[test]
    fn merging_unions_the_vector_layers_list() {
        let a = build_point_archive("safety", &[pt(-122.4, 37.7, "a")], 10, 16).unwrap();
        let b = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "b")], 12, 16).unwrap();
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let merged = merge_archives(&[&aa, &ba]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[\
             {\"id\":\"safety\",\"minzoom\":10,\"maxzoom\":16},\
             {\"id\":\"ma_pois\",\"minzoom\":12,\"maxzoom\":16}]}",
            "both layers advertised, in input order"
        );
    }

    /// A base archive's other metadata keys are what carry attribution, so the
    /// splice has to leave everything it does not understand alone.
    #[test]
    fn splicing_preserves_the_templates_other_keys() {
        let spliced = splice_vector_layers(
            "{\"name\":\"v5\",\"vector_layers\":[{\"id\":\"water\"}],\"attribution\":\"OSM\"}",
            "{\"id\":\"safety\"}",
        );
        assert_eq!(
            spliced,
            "{\"name\":\"v5\",\"vector_layers\":[{\"id\":\"safety\"}],\"attribution\":\"OSM\"}"
        );
    }

    #[test]
    fn splicing_grafts_a_list_onto_metadata_that_lacks_one() {
        assert_eq!(
            splice_vector_layers("{\"name\":\"v5\"}", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}],\"name\":\"v5\"}"
        );
        assert_eq!(
            splice_vector_layers("", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}]}"
        );
    }

    /// A `vector_layers` that is not an array cannot be spliced, and keeping the
    /// template would emit the key twice.
    #[test]
    fn a_non_array_vector_layers_is_replaced_rather_than_duplicated() {
        assert_eq!(
            splice_vector_layers("{\"vector_layers\":5,\"name\":\"v5\"}", "{\"id\":\"safety\"}"),
            "{\"vector_layers\":[{\"id\":\"safety\"}]}"
        );
    }

    /// The scanner walks strings rather than searching for the key, so a decoy
    /// inside a value cannot misdirect it.
    #[test]
    fn the_metadata_scanner_ignores_decoys_inside_strings() {
        let text = r#"{"note":"see \"vector_layers\": [bogus]","vector_layers":[{"id":"real"}]}"#;
        let span = vector_layers_span(text).expect("the real key");
        assert_eq!(array_elements(text, span), vec![r#"{"id":"real"}"#]);
    }

    #[test]
    fn an_id_is_read_past_a_nested_field_map() {
        let obj = "{\"fields\":{\"id\":\"decoy\"},\"id\":\"real\",\"minzoom\":3}";
        assert_eq!(object_string_field(obj, "id").as_deref(), Some("real"));
        assert_eq!(object_string_field(obj, "missing"), None);
    }

    /// Entries with no string `id` cannot be matched against, so they must be kept
    /// side by side rather than folded onto one empty key and collapsed into one.
    #[test]
    fn id_less_layer_entries_are_kept_rather_than_deduplicated() {
        let a = meta_archive("{\"vector_layers\":[{\"minzoom\":1},{\"minzoom\":2}]}");
        let b = meta_archive("{\"vector_layers\":[{\"minzoom\":3}]}");
        let (aa, ba) = (Archive::parse(&a).unwrap(), Archive::parse(&b).unwrap());

        let merged = merge_archives(&[&aa, &ba]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[{\"minzoom\":1},{\"minzoom\":2},{\"minzoom\":3}]}"
        );
    }

    #[test]
    fn a_colliding_layer_id_keeps_only_the_later_description() {
        let stale = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "a")], 12, 14).unwrap();
        let fresh = build_point_archive("ma_pois", &[pt(-122.4, 37.7, "b")], 12, 16).unwrap();
        let (sa, fa) = (Archive::parse(&stale).unwrap(), Archive::parse(&fresh).unwrap());

        let merged = merge_archives(&[&sa, &fa]).unwrap();
        let meta = String::from_utf8(Archive::parse(&merged).unwrap().metadata).unwrap();

        assert_eq!(
            meta,
            "{\"vector_layers\":[{\"id\":\"ma_pois\",\"minzoom\":12,\"maxzoom\":16}]}",
            "listed once, at the rebuilt layer's zoom range"
        );
    }

