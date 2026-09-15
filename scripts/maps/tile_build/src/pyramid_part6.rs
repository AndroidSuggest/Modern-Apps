
    #[test]
    fn one_feature_too_big_for_the_budget_is_kept_and_reported() {
        // An empty tile is a hole in the map; an oversized one is merely slow.
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let mut opts = Options::new("l", 14, 14);
        opts.max_tile_bytes = 50;
        let (bytes, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].over_budget > 0, "{:?}", report[0]);
        assert!(report[0].kept > 0, "the tile must not be empty");
        let a = Archive::parse(&bytes).unwrap();
        assert!(a.header.addressed_tiles > 0);
    }

    #[test]
    fn the_drop_policy_is_deterministic_under_a_tight_budget() {
        let features: Vec<Feature> = (0..300)
            .map(|i| {
                let d = i as f64 * 0.00003;
                line(
                    &[(-122.40 + d, 37.80), (-122.3995 + d, 37.8005)],
                    vec![("name", Value::String(format!("f{i}")))],
                )
            })
            .collect();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 700;
        let (a, ra) = build_archive(&features, &opts).unwrap();
        let (b, rb) = build_archive(&features, &opts).unwrap();
        assert_eq!(a, b);
        assert_eq!(ra, rb);
        assert!(ra[0].dropped > 0, "the test is only meaningful if it dropped");
    }

    #[test]
    fn simplification_thins_the_shallow_zooms_and_spares_the_deepest() {
        // A wiggly line: at maxzoom the tolerance is zero, so every vertex the clip
        // left must still be there; below it, fewer.
        let coords: Vec<(f64, f64)> = (0..300)
            .map(|i| (-122.45 + i as f64 * 0.0002, 37.80 + (i % 2) as f64 * 0.00005))
            .collect();
        let (bytes, _) = build_archive(&[line(&coords, vec![])], &Options::new("l", 8, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        let vertices_at = |z: u8| -> usize {
            let mut n = 0;
            for (id, raw) in a.iter_tiles().unwrap() {
                if pmtiles::tile_zxy(id).0 != z {
                    continue;
                }
                let tile = Tile::decode(&crate::gz::decompress(raw).unwrap()).unwrap();
                for f in &tile.layer("l").unwrap().features {
                    n += mvt::decode_lines(&f.geometry)
                        .map(|ls| ls.iter().map(|l| l.len()).sum::<usize>())
                        .unwrap_or(0);
                }
            }
            n
        };
        let deep = vertices_at(12);
        let shallow = vertices_at(8);
        assert!(deep > 0 && shallow > 0);
        assert!(
            shallow < deep,
            "z8 kept {shallow} vertices and z12 kept {deep}; simplification did nothing"
        );
    }

    #[test]
    fn features_that_clip_away_entirely_are_not_counted_as_drops() {
        // Two features far apart: each tile sees one of them, and the other's
        // absence is a clip, not a budget decision.
        let features = vec![
            line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![]),
            line(&[(-74.01, 40.71), (-74.00, 40.72)], vec![]),
        ];
        let (_, report) = build_archive(&features, &Options::new("l", 12, 12)).unwrap();
        assert_eq!(report[0].dropped, 0, "{:?}", report[0]);
        assert_eq!(report[0].kept, report[0].placed);
    }

    #[test]
    fn an_empty_input_produces_an_empty_but_valid_archive() {
        let (bytes, report) = build_archive(&[], &Options::new("l", 10, 12)).unwrap();
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 0);
        assert!(report.iter().all(|s| s.tiles == 0));
    }

    #[test]
    fn bad_options_are_refused() {
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        let mut opts = Options::new("l", 12, 10);
        assert!(build_archive(&features, &opts).is_err(), "minzoom above maxzoom");
        opts = Options::new("l", 10, 12);
        opts.extent = 0;
        assert!(build_archive(&features, &opts).is_err(), "extent 0");
    }

    #[test]
    fn the_report_prints_one_row_per_zoom() {
        let (_, report) = build_archive(
            &[line(&[(-122.42, 37.77), (-122.41, 37.78)], vec![])],
            &Options::new("l", 10, 11),
        )
        .unwrap();
        let mut out = Vec::new();
        print_report(&report, &mut out).unwrap();
        let text = String::from_utf8(out).unwrap();
        assert!(text.contains("z10"), "{text}");
        assert!(text.contains("z11"), "{text}");
    }

    // --- the streaming producer --------------------------------------------
    //
    // `build_archive` is covered by every test above, so pinning the streaming producer
    // against it byte for byte inherits all of it -- the drop policy, the importance
    // order, the simplification, the header, the deduplication, the run coalescing and
    // without restating any of it.

    struct Scratch(std::path::PathBuf);

    impl Scratch {
        fn new(name: &str) -> Scratch {
            let d = std::env::temp_dir().join(format!(
                "tb_pyr_{}_{name}_{:?}",
                std::process::id(),
                std::thread::current().id()
            ));
            let _ = std::fs::remove_dir_all(&d);
            std::fs::create_dir_all(&d).unwrap();
            Scratch(d)
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Drive both producers from one `Vec<Feature>` and return the streamed bytes.
    ///
    /// Asserts the archive bytes AND the `ZoomStats` are equal, and that nothing was
    /// left behind in the scratch directory -- a stranded per-zoom bucket set would make
    /// peak disk the SUM of the zooms rather than the largest one.
    fn assert_identical(
        name: &str,
        features: &[Feature],
        opts: &Options,
        limits: &StreamLimits,
    ) -> Vec<u8> {
        let scratch = Scratch::new(name);
        let (want_bytes, want_report) = build_archive(features, opts).unwrap();

        let out = scratch.0.join("streamed.pmtiles");
        let mut src = SliceSource::new(features);
        let got_report = build_archive_to(&out, &scratch.0, opts, &mut src, limits).unwrap();
        let got_bytes = std::fs::read(&out).unwrap();

        assert_eq!(got_report, want_report, "{name}: the per-zoom reports differ");
        assert_eq!(
            got_bytes.len(),
            want_bytes.len(),
            "{name}: streamed {} bytes, in-memory {}",
            got_bytes.len(),
            want_bytes.len()
        );
        assert!(
            got_bytes == want_bytes,
            "{name}: the two archives differ byte for byte"
        );

        let leftovers: Vec<String> = std::fs::read_dir(&scratch.0)
            .unwrap()
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n != "streamed.pmtiles")
            .collect();
        assert!(
            leftovers.is_empty(),
            "{name}: the spill was left behind: {leftovers:?}"
        );
        assert!(
            !out.with_extension("pmtiles.tiledata").exists(),
            "{name}: the writer's scratch was left behind"
        );
        got_bytes
    }

    /// A fixture covering every branch the two producers could disagree on: a line
    /// spanning many tiles, a polygon with a hole, and two features with identical
    /// geometry so the `seq` tie-break in the importance order has to decide between
    /// them.
    fn mixed_fixture() -> Vec<Feature> {
        let mut features = vec![
            // Long enough at z11-12 to land in many tiles, which is what makes the
            // bucket partition non-trivial.
            line(&[(-124.0, 42.0), (-114.0, 33.0)], vec![("id", Value::Uint(0))]),
            square(-122.5, 37.7, 0.4, "A"),
        ];
        // A polygon with a hole.
        features.push(Feature {
            geometry: Geometry::Polygons(vec![vec![
                vec![
                    (-122.5, 37.7),
                    (-122.3, 37.7),
                    (-122.3, 37.9),
                    (-122.5, 37.9),
                    (-122.5, 37.7),
                ],
                vec![
                    (-122.45, 37.75),
                    (-122.35, 37.75),
                    (-122.35, 37.85),
                    (-122.45, 37.85),
                    (-122.45, 37.75),
                ],
            ]]),
            props: vec![("name".to_string(), Value::String("holey".into()))],
        });
        // Two identical geometries: tied on `extent_of`, so only `seq` orders them.
        for k in 0..2 {
            features.push(line(
                &[(-122.42, 37.77), (-122.40, 37.79)],
                vec![("tied", Value::Uint(k))],
            ));
        }
        features
    }

    #[test]
    fn the_streaming_producer_is_byte_identical_to_the_in_memory_one() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        assert_identical("mixed", &features, &opts, &StreamLimits::default());
    }

    /// The drop policy is where the two could diverge without the output looking wrong,
    /// so it gets a budget tight enough to bite.
    #[test]
    fn the_streaming_producer_matches_under_a_tight_tile_budget() {
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
        let (_, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].dropped > 0, "the fixture must drop: {:?}", report[0]);
        assert_identical("tight", &features, &opts, &StreamLimits::default());
    }

    /// The `over_budget` path: one feature that does not fit is kept anyway, and both
    /// producers must keep the same one and report it the same way.
    #[test]
    fn the_streaming_producer_matches_when_a_single_feature_is_over_budget() {
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let mut opts = Options::new("l", 14, 14);
        opts.max_tile_bytes = 50;
        let (_, report) = build_archive(&features, &opts).unwrap();
        assert!(report[0].over_budget > 0, "the fixture must be over budget");
        assert_identical("overbudget", &features, &opts, &StreamLimits::default());
    }

    /// Re-partitioning a dense bucket is the expected path at planet scale, not an
    /// exotic one, so it gets its own byte-identity check rather than being trusted.
    ///
    /// Four buckets per level and a budget far below one zoom's spill forces recursion
    /// all the way down to single-tile ranges; the archive must not move by a byte.
    #[test]
    fn re_partitioning_does_not_perturb_a_byte() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let limits = StreamLimits {
            buckets: 4,
            // Well under one zoom's total spill and comfortably above one tile's, which
            // is what makes this a recursion test rather than an error test.
            bucket_budget_bytes: 2_000,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        assert_identical("recursion", &features, &opts, &limits);
    }

    /// The fixture whose drop policy actually bites: one long feature plus 200 short
    /// ones, at a budget that cannot hold them all.
    fn tight_fixture() -> Vec<Feature> {
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
        features
    }

    /// The gate for threading the tilers: the thread count must change only how fast
    /// they run.
    ///
    /// Both producers, at 1, 2, 3 and 32 threads, against the serial result. Three is
    /// there on purpose — counts that divide neither the bucket count (256, or 4 in the
    /// recursion case) nor the batch length are the ones that catch an off-by-one in a
    /// chunked fold, and a count above the core count catches an ordering bug that a
    /// saturated pool would hide.
    ///
    /// Run over three cases, because they are three different code paths: the flat
    /// partition, the recursing one, and a budget tight enough that the drop policy
    /// runs — the last being the only one where a perturbed candidate order changes
    /// which features survive rather than merely where they land.
    #[test]
    fn the_thread_count_changes_no_bytes() {
        let recursing = StreamLimits {
            buckets: 4,
            bucket_budget_bytes: 2_000,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        let mut tight_opts = Options::new("l", 11, 11);
        tight_opts.max_tile_bytes = 400;

        let cases: Vec<(&str, Vec<Feature>, Options, StreamLimits)> = vec![
            (
                "flat",
                mixed_fixture(),
                Options::new("l", 10, 12),
                StreamLimits::default(),
            ),
            (
                "recursing",
                mixed_fixture(),
                Options::new("l", 10, 12),
                recursing,
            ),
            (
                "tight",
                tight_fixture(),
                tight_opts,
                StreamLimits::default(),
            ),
        ];

        for (tag, features, opts, limits) in cases {
            let run = |n: usize| {
                par::set_threads(n);
                let scratch = Scratch::new(&format!("threads_{tag}_{n}"));
                let (mem, mem_report) = build_archive(&features, &opts).unwrap();
                let out = scratch.0.join("streamed.pmtiles");
                let mut src = SliceSource::new(&features);
                let stream_report =
                    build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
                (mem, mem_report, std::fs::read(&out).unwrap(), stream_report)
            };

            let (base_mem, base_mem_report, base_stream, base_stream_report) = run(1);
            // The property the whole crate rests on, restated at one thread so a
            // failure below cannot be blamed on the producers disagreeing.
            assert!(base_mem == base_stream, "{tag}: the producers disagree serially");

            for n in [2, 3, 32] {
                let (mem, mem_report, stream, stream_report) = run(n);
                assert_eq!(
                    mem_report, base_mem_report,
                    "{tag}: {n} threads changed the in-memory report"
                );
                assert_eq!(
                    stream_report, base_stream_report,
                    "{tag}: {n} threads changed the streamed report"
                );
                assert!(
                    mem == base_mem,
                    "{tag}: {n} threads perturbed the in-memory archive"
                );
                assert!(
                    stream == base_stream,
                    "{tag}: {n} threads perturbed the streamed archive"
                );
            }
        }
        par::clear_threads();
    }

    /// An unsplittable group over budget errors, naming the tile, rather than being
    /// loaded and killed by the OOM reaper. Capping candidates per tile instead would
    /// change which features survive, which is an output change.
    #[test]
    fn an_unsplittable_over_budget_tile_group_is_named_in_the_error() {
        let scratch = Scratch::new("unsplittable");
        let long: Vec<(f64, f64)> = (0..4000)
            .map(|i| (-122.42 + (i % 97) as f64 * 0.0001, 37.77 + (i % 89) as f64 * 0.0001))
            .collect();
        let features = vec![line(&long, vec![("name", Value::String("wiggly".into()))])];
        let opts = Options::new("l", 14, 14);
        let limits = StreamLimits {
            buckets: 4,
            bucket_budget_bytes: 64,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        };
        let mut src = SliceSource::new(&features);
        let e = build_archive_to(
            scratch.0.join("out.pmtiles"),
            &scratch.0,
            &opts,
            &mut src,
            &limits,
        )
        .expect_err("an unsplittable over-budget group must error");
        let msg = e.to_string();
        assert!(msg.contains("z14/"), "the error must name the tile: {msg}");
        assert!(msg.contains("spill record(s)"), "and the record count: {msg}");
        assert!(
            msg.contains("--bucket-budget-bytes"),
            "and what to do about it: {msg}"
        );
    }

    /// An empty input is what catches the `Builder`/`StreamBuilder` default-bounds
    /// divergence: nothing overwrites the latitude fields, and they differ by three
    /// units between the two writers.
    #[test]
    fn an_empty_input_streams_to_an_empty_but_valid_archive() {
        let opts = Options::new("l", 10, 12);
        let bytes = assert_identical("empty", &[], &opts, &StreamLimits::default());
        let a = Archive::parse(&bytes).unwrap();
        assert_eq!(a.header.addressed_tiles, 0);
        assert_eq!(a.header.min_lat_e7, -850_511_290, "Builder's default, not StreamBuilder's");
        assert_eq!(a.header.max_lat_e7, 850_511_290);
    }
