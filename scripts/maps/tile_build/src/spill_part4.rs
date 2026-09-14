

    #[test]
    fn the_normalized_file_round_trips_and_folds_its_summary() {
        let dir = tmp("normalized");
        let path = dir.join("features.bin");
        let features = vec![
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-122.42, 37.77), (-122.40, 37.79)]]),
                props: every_value(),
            },
            NormalizedFeature {
                geometry: Geometry::Lines(vec![vec![(-74.01, 40.71), (-73.99, 40.73)]]),
                props: vec![],
            },
            NormalizedFeature {
                geometry: Geometry::Points(vec![(2.35, 48.85)]),
                props: vec![("x".to_string(), Value::Bool(true))],
            },
        ];

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        w.skip();
        w.skip();
        let summary = w.finish().unwrap();

        assert_eq!(summary.count, 3);
        assert_eq!(summary.skipped, 2);
        // The FIRST feature's kind, even though a point follows.
        assert_eq!(summary.geom_kind, Some(GeomKind::Lines));
        let b = summary.bounds.expect("bounds");
        assert_eq!((b.min_x, b.max_x), (-122.42, 2.35));
        assert_eq!((b.min_y, b.max_y), (37.77, 48.85));

        let mut r = NormalizedReader::open(&path).unwrap();
        let mut back = Vec::new();
        while let Some(f) = r.next().unwrap() {
            back.push(f);
        }
        let want = snapped_all(&features);
        assert_eq!(back, want);

        // Every zoom re-reads it from the front.
        r.rewind().unwrap();
        assert_eq!(r.next().unwrap().as_ref(), Some(&want[0]));

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The chunked reader must yield exactly the sequential reader's features in exactly
    /// its order, and its chunk boundaries must land on multiples of
    /// `NORM_CHUNK_FEATURES` — that alignment is what the bucket pass relies on to
    /// reconstruct `seq` without reading the whole file.
    #[test]
    fn the_chunked_reader_yields_what_the_sequential_one_does() {
        let dir = tmp("normchunks");
        let path = dir.join("features.bin");

        // Deliberately not a multiple of the stride, so the last chunk is partial, and
        // varied enough that a record-length mistake cannot cancel out.
        let n = 2 * NORM_CHUNK_FEATURES as usize + 7;
        let features: Vec<NormalizedFeature> = (0..n)
            .map(|i| {
                let f = i as f64;
                if i % 3 == 0 {
                    NormalizedFeature {
                        geometry: Geometry::Points(vec![(f * 0.01, -f * 0.02)]),
                        props: vec![("i".to_string(), Value::Uint(i as u64))],
                    }
                } else if i % 3 == 1 {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(f * 0.01, 1.0), (f * 0.01, 2.0)]]),
                        props: every_value(),
                    }
                } else {
                    NormalizedFeature {
                        geometry: Geometry::Lines(vec![vec![(0.0, f * 0.01), (1.0, f * 0.01)]]),
                        props: vec![],
                    }
                }
            })
            .collect();

        let mut w = NormalizedWriter::create(&path).unwrap();
        for f in &features {
            w.push(&f.geometry, &f.props).unwrap();
        }
        let summary = w.finish().unwrap();
        assert_eq!(summary.count, n as u64);
        assert_eq!(summary.chunk_count(), 3, "two full chunks and a partial one");
        assert_eq!(
            *summary.chunks.last().unwrap(),
            std::fs::metadata(&path).unwrap().len(),
            "the sentinel must be the file's length"
        );

        let chunks = NormalizedChunks::open(&path, summary.chunks.clone()).unwrap();
        let mut scratch = Vec::new();
        let mut out = Vec::new();
        let mut back = Vec::new();
        for i in 0..chunks.chunk_count() {
            chunks.read_into(i, &mut scratch, &mut out).unwrap();
            // The alignment the `seq` reconstruction depends on.
            assert_eq!(
                back.len() as u64,
                i as u64 * NORM_CHUNK_FEATURES,
                "chunk {i} must start at feature {}",
                i as u64 * NORM_CHUNK_FEATURES
            );
            back.extend(out.iter().cloned());
        }
        assert_eq!(back, snapped_all(&features), "the chunked read must match the input");

        // And the sequential reader, so neither can drift from the other.
        let mut r = NormalizedReader::open(&path).unwrap();
        let mut seq = Vec::new();
        while let Some(f) = r.next().unwrap() {
            seq.push(f);
        }
        assert_eq!(back, seq, "the two readers must agree");

        assert!(
            chunks
                .read_into(chunks.chunk_count(), &mut scratch, &mut out)
                .is_err(),
            "a chunk past the end must error rather than read nothing"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// A corrupt chunk index must be reported, not silently mis-decoded: the offsets are
    /// the one part of the format the records themselves cannot validate.
    #[test]
    fn a_chunk_index_pointing_into_a_record_errors() {
        let dir = tmp("normchunkbad");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        for i in 0..NORM_CHUNK_FEATURES + 3 {
            w.push(
                &Geometry::Lines(vec![vec![(i as f64, 1.0), (i as f64, 2.0)]]),
                &every_value(),
            )
            .unwrap();
        }
        let summary = w.finish().unwrap();

        let mut scratch = Vec::new();
        let mut out = Vec::new();

        // Nudge a boundary into the middle of a record.
        let mut bad = summary.chunks.clone();
        bad[1] += 3;
        let chunks = NormalizedChunks::open(&path, bad).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err()
                || chunks.read_into(1, &mut scratch, &mut out).is_err(),
            "a misaligned boundary must error"
        );

        // A boundary that runs backwards.
        let mut backwards = summary.chunks.clone();
        backwards[1] = backwards[0];
        backwards[0] = *summary.chunks.last().unwrap();
        let chunks = NormalizedChunks::open(&path, backwards).unwrap();
        assert!(
            chunks.read_into(0, &mut scratch, &mut out).is_err(),
            "a backwards range must error"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The summary's bounds must be exactly what the in-memory path computes, or the
    /// two producers write different header bytes for the same input.
    #[test]
    fn the_summary_bounds_match_the_in_memory_fold() {
        let dir = tmp("bounds");
        let path = dir.join("f.bin");
        let geoms = vec![
            Geometry::Lines(vec![vec![(-10.0, -5.0), (3.0, 8.0)]]),
            Geometry::Points(vec![(20.0, -30.0)]),
            // No vertices at all, so it must not move the box.
            Geometry::Lines(vec![]),
        ];
        let mut w = NormalizedWriter::create(&path).unwrap();
        for g in &geoms {
            w.push(g, &[]).unwrap();
        }
        let summary = w.finish().unwrap();

        let want = geoms.iter().fold(None, crate::pyramid::fold_bounds);
        assert_eq!(summary.bounds, want);

        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn an_empty_normalized_file_reads_as_no_features() {
        let dir = tmp("empty");
        let path = dir.join("f.bin");
        let summary = NormalizedWriter::create(&path).unwrap().finish().unwrap();
        assert_eq!(summary, NormalizedSummary::default());
        assert!(NormalizedReader::open(&path).unwrap().next().unwrap().is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_truncated_normalized_record_errors() {
        let dir = tmp("normtrunc");
        let path = dir.join("f.bin");
        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(
            &Geometry::Lines(vec![vec![(1.0, 2.0), (3.0, 4.0)]]),
            &every_value(),
        )
        .unwrap();
        w.finish().unwrap();

        let bytes = std::fs::read(&path).unwrap();
        std::fs::write(&path, &bytes[..bytes.len() - 8]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short payload");
        std::fs::write(&path, &bytes[..NORM_HEADER_BYTES - 1]).unwrap();
        assert!(NormalizedReader::open(&path).unwrap().next().is_err(), "short header");
        let mut dirty = bytes.clone();
        dirty[NORM_HEADER_BYTES - 1] = 1;
        std::fs::write(&path, &dirty).unwrap();
        assert!(
            NormalizedReader::open(&path).unwrap().next().is_err(),
            "nonzero reserved tail"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_normalized_file_guard_removes_it_on_drop() {
        let dir = tmp("normguard");
        let path = dir.join("f.bin");
        {
            let guard = NormalizedFile::new(&path);
            NormalizedWriter::create(guard.path()).unwrap().finish().unwrap();
            assert!(path.exists());
        }
        assert!(!path.exists(), "the guard must remove it");
        let _ = std::fs::remove_dir_all(&dir);
    }
