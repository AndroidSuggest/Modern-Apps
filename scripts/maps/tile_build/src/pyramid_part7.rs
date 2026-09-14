
    #[test]
    fn two_streaming_runs_are_byte_identical() {
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let a = assert_identical("det_a", &features, &opts, &StreamLimits::default());
        let b = assert_identical("det_b", &features, &opts, &StreamLimits::default());
        assert_eq!(a, b, "determinism is a regression surface here");
    }

    #[test]
    fn the_streaming_producer_refuses_the_same_bad_options() {
        let scratch = Scratch::new("badopts");
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        let limits = StreamLimits::default();
        for opts in [
            Options::new("l", 12, 10),
            Options { extent: 0, ..Options::new("l", 10, 12) },
        ] {
            let mut src = SliceSource::new(&features);
            assert!(
                build_archive_to(
                    scratch.0.join("out.pmtiles"),
                    &scratch.0,
                    &opts,
                    &mut src,
                    &limits
                )
                .is_err(),
                "bad options must be refused by both producers"
            );
        }
    }

    /// `--buckets 1` cannot narrow a range, so an over-budget bucket must fail fast
    /// rather than copy itself to disk once per level up to the depth cap.
    #[test]
    fn a_single_bucket_partition_fails_fast_instead_of_recursing() {
        let scratch = Scratch::new("onebucket");
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 10);
        let limits = StreamLimits {
            buckets: 1,
            bucket_budget_bytes: 1,
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
        .expect_err("a one-bucket partition cannot split, so it must error");
        assert!(
            e.to_string().contains("cannot be split further"),
            "the error must say why: {e}"
        );
    }

    /// `zoom_base(max_zoom + 1)` overflows above z30, which only the streaming path
    /// computes. A message beats a panic.
    #[test]
    fn a_zoom_beyond_the_tile_id_range_is_refused() {
        let scratch = Scratch::new("deepzoom");
        let features = vec![line(&[(0.0, 0.0), (1.0, 1.0)], vec![])];
        // Refused before any zoom runs, so this costs nothing: a z30 bucket pass over a
        // one-degree line would touch millions of tiles.
        let opts = Options::new("l", 31, 31);
        let mut src = SliceSource::new(&features);
        let e = build_archive_to(
            scratch.0.join("out.pmtiles"),
            &scratch.0,
            &opts,
            &mut src,
            &StreamLimits::default(),
        )
        .expect_err("z31 must be refused");
        assert!(e.to_string().contains("above 30"), "{e}");
    }

    /// The same normalized file forced through the sequential path, so a test can pin the
    /// chunked reader against it. Everything delegates; `chunks` is left at its `None`
    /// default, which is the whole point.
    struct SerialOnly(NormalizedSource);

    impl FeatureSource for SerialOnly {
        fn rewind(&mut self) -> Result<()> {
            self.0.rewind()
        }
        fn next(&mut self) -> Result<Option<Feature>> {
            self.0.next()
        }
        fn len(&self) -> u64 {
            self.0.len()
        }
        fn bounds(&self) -> Option<geom::Rect> {
            self.0.bounds()
        }
        fn geom_kind(&self) -> Option<GeomKind> {
            self.0.geom_kind()
        }
    }

    /// The chunked reader is the production read path, so it must produce exactly what the
    /// sequential one does, at every thread count.
    ///
    /// `tight_fixture` is 201 features against `NORM_CHUNK_FEATURES` of 64, so the index
    /// spans four chunks and ends mid-chunk — the shape that catches an off-by-one in the
    /// chunk-to-`seq` mapping. The tight budget matters more: it makes the drop policy
    /// run, so a perturbed `seq` changes which features SURVIVE rather than only which
    /// bucket they land in. Without it a wrong `seq` would still sort out and the test
    /// would pass while the mapping was broken.
    ///
    /// Checked by mutation: dropping the chunk base (`first = 0`, so `seq` collides
    /// across chunks) fails this. Adding a constant to it does NOT, and should not —
    /// `seq` is only ever a tie-break, so a uniform shift preserves every comparison.
    #[test]
    fn the_chunked_reader_matches_the_sequential_one() {
        let features = tight_fixture();
        let mut opts = Options::new("l", 11, 11);
        opts.max_tile_bytes = 400;
        let limits = StreamLimits::default();

        let held = Scratch::new("chunked_src");
        let norm = held.0.join("features.bin");
        let mut w = crate::spill::NormalizedWriter::create(&norm).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();

        // The fixture has to span several chunks and end mid-chunk, or this degenerates
        // into the single-chunk case and asserts nothing about the mapping.
        assert!(
            summary.chunk_count() > 1,
            "fixture must span several chunks, got {}",
            summary.chunk_count()
        );
        assert_ne!(
            summary.count % crate::spill::NORM_CHUNK_FEATURES,
            0,
            "fixture must end mid-chunk to exercise the partial tail"
        );

        let serial = {
            let scratch = Scratch::new("chunked_serial");
            let out = scratch.0.join("out.pmtiles");
            let mut src = SerialOnly(NormalizedSource::open(&norm, summary.clone()).unwrap());
            assert!(src.chunks().is_none(), "this must take the serial path");
            let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
            (std::fs::read(&out).unwrap(), report)
        };

        for n in [1, 2, 3, 32] {
            par::set_threads(n);
            let scratch = Scratch::new(&format!("chunked_{n}"));
            let out = scratch.0.join("out.pmtiles");
            let mut src = NormalizedSource::open(&norm, summary.clone()).unwrap();
            assert!(src.chunks().is_some(), "this must take the chunked path");
            let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
            assert_eq!(
                report, serial.1,
                "{n} threads: the chunked reader changed the report"
            );
            assert!(
                std::fs::read(&out).unwrap() == serial.0,
                "{n} threads: the chunked reader perturbed the archive"
            );
        }
        par::clear_threads();
    }

    /// The production source is a file, and it must yield exactly what the slice-backed
    /// one does -- otherwise the byte-identity tests above are testing a path nothing
    /// ships.
    #[test]
    fn the_normalized_source_produces_the_same_archive_as_the_slice_one() {
        let scratch = Scratch::new("normsrc");
        let features = mixed_fixture();
        let opts = Options::new("l", 10, 12);
        let limits = StreamLimits::default();

        let (want, want_report) = build_archive(&features, &opts).unwrap();

        let norm = scratch.0.join("features.bin");
        let mut w = crate::spill::NormalizedWriter::create(&norm).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.count, features.len() as u64);

        let out = scratch.0.join("out.pmtiles");
        let mut src = NormalizedSource::open(&norm, summary).unwrap();
        let report = build_archive_to(&out, &scratch.0, &opts, &mut src, &limits).unwrap();
        assert_eq!(report, want_report);
        assert!(
            std::fs::read(&out).unwrap() == want,
            "the file-backed source must produce the same bytes"
        );
    }
