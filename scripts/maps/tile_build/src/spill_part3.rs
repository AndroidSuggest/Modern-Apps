    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "tb_spill_{}_{name}_{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn every_value() -> Vec<(String, Value)> {
        vec![
            ("s".to_string(), Value::String("héllo, wörld".into())),
            ("f".to_string(), Value::Float(1.5)),
            ("d".to_string(), Value::Double(-2.25)),
            ("i".to_string(), Value::Int(-7)),
            ("u".to_string(), Value::Uint(9)),
            ("si".to_string(), Value::SInt(-11)),
            ("bt".to_string(), Value::Bool(true)),
            ("bf".to_string(), Value::Bool(false)),
            ("empty".to_string(), Value::String(String::new())),
        ]
    }

    fn every_int_geometry() -> Vec<IntGeometry> {
        vec![
            IntGeometry::Points(vec![]),
            IntGeometry::Points(vec![(0, 0), (-5, 4096), (i32::MIN, i32::MAX)]),
            IntGeometry::Lines(vec![]),
            IntGeometry::Lines(vec![vec![(1, 2), (3, 4)], vec![], vec![(9, -9)]]),
            IntGeometry::Polygons(vec![]),
            IntGeometry::Polygons(vec![
                // Exterior plus a hole, which is the shape a real admin polygon has.
                vec![
                    vec![(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)],
                    vec![(2, 2), (8, 2), (8, 8), (2, 8), (2, 2)],
                ],
                vec![vec![(20, 20), (30, 20), (30, 30), (20, 20)]],
            ]),
        ]
    }

    /// Every geometry variant and every `mvt::Value` must survive the round trip. A
    /// value type that spilled wrongly would reach the encoder as a different property
    /// and nothing between here and the rendered tile would notice.
    #[test]
    fn a_record_round_trips_for_every_geometry_and_every_value() {
        let dir = tmp("roundtrip");
        for (n, geom) in every_int_geometry().into_iter().enumerate() {
            let rec = SpillRecord {
                tile_id: 5 + n as u64,
                seq: 42 + n as u64,
                extent: -1234,
                geom,
                props: every_value(),
            };
            let bytes = rec.to_bytes().unwrap();
            assert_eq!(
                bytes.len(),
                u32::from_le_bytes(bytes[0..4].try_into().unwrap()) as usize,
                "rec_len must be the whole record"
            );

            let mut set = BucketSet::new(&dir, &format!("rt{n}"), 0, 64, 1).unwrap();
            set.push(rec.tile_id, &bytes).unwrap();
            set.seal().unwrap();
            assert_eq!(set.load(0).unwrap(), vec![rec], "variant {n}");
        }
    }

    #[test]
    fn a_truncated_record_errors_rather_than_decoding() {
        let dir = tmp("truncated");
        let rec = SpillRecord {
            tile_id: 1,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Lines(vec![vec![(1, 1), (2, 2), (3, 3)]]),
            props: every_value(),
        };
        let bytes = rec.to_bytes().unwrap();

        let mut set = BucketSet::new(&dir, "t", 0, 64, 1).unwrap();
        set.push(1, &bytes).unwrap();
        set.seal().unwrap();
        let path = dir.join("t.00000.bucket");
        assert!(set.load(0).is_ok(), "the intact fixture must read");

        // Cut mid-payload: the header promises more than the file holds.
        std::fs::write(&path, &bytes[..bytes.len() - 4]).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "a short payload must error");

        // Cut mid-header, which is a truncated file rather than a legitimate end.
        std::fs::write(&path, &bytes[..REC_HEADER_BYTES - 1]).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "a short header must error");

        // A whole record short: the reader reaches the end and the tallies disagree.
        std::fs::write(&path, []).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(
            r.next().is_err(),
            "a bucket missing a record must fail the count check"
        );
    }

    #[test]
    fn a_wrong_length_or_a_dirty_reserved_tail_errors() {
        let rec = SpillRecord {
            tile_id: 1,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(1, 1)]),
            props: vec![],
        };
        let good = rec.to_bytes().unwrap();
        let mut head: [u8; REC_HEADER_BYTES] = good[..REC_HEADER_BYTES].try_into().unwrap();
        assert!(RecHeader::parse(&head).is_ok(), "the fixture must be valid");

        let mut bad = head;
        bad[0] = bad[0].wrapping_add(1);
        assert!(
            RecHeader::parse(&bad).is_err(),
            "rec_len must agree with geom_len + props_len"
        );

        head[REC_HEADER_BYTES - 1] = 1;
        assert!(
            RecHeader::parse(&head).is_err(),
            "a nonzero reserved tail must be refused, not guessed at"
        );
    }

    #[test]
    fn an_unknown_geometry_kind_or_value_type_errors() {
        assert!(GeomKind::from_tag(3).is_err());
        // Value tag 7 does not exist.
        let mut b = Vec::new();
        put_u32(&mut b, 1);
        put_u32(&mut b, 1);
        b.push(b'k');
        b.push(7);
        assert!(decode_props(&b).is_err(), "an unknown value type must error");
    }

    /// Every record must land in the bucket its `tile_id` names, and the three
    /// independent tallies -- push time, read time, and the file length -- must agree.
    #[test]
    fn every_record_lands_in_its_own_bucket_and_the_books_balance() {
        let dir = tmp("buckets");
        // z4: ids 21..85, 64 of them, into 16 buckets of 4.
        let (lo, hi) = (21u64, 85u64);
        let mut set = BucketSet::new(&dir, "z4", lo, hi, 16).unwrap();
        assert_eq!(set.len(), 16);
        assert_eq!(set.span(), 4);

        let mut expected: Vec<Vec<u64>> = vec![Vec::new(); 16];
        for (n, id) in (lo..hi).enumerate() {
            // Skewed on purpose: some ids get several records, some none at all.
            for k in 0..(n % 3) {
                let rec = SpillRecord {
                    tile_id: id,
                    seq: (n * 10 + k) as u64,
                    extent: n as i64,
                    geom: IntGeometry::Points(vec![(n as i32, k as i32)]),
                    props: vec![("n".to_string(), Value::Uint(n as u64))],
                };
                set.push(id, &rec.to_bytes().unwrap()).unwrap();
                expected[((id - lo) / 4) as usize].push(id);
            }
        }
        set.seal().unwrap();

        let mut total = 0u64;
        for (i, want) in expected.iter().enumerate() {
            let recs = set.load(i).unwrap();
            let ids: Vec<u64> = recs.iter().map(|r| r.tile_id).collect();
            assert_eq!(&ids, want, "bucket {i}");
            let (blo, bhi) = set.range_of(i);
            for r in &recs {
                assert!(r.tile_id >= blo && r.tile_id < bhi, "bucket {i} range");
            }
            total += recs.len() as u64;
        }
        assert_eq!(total, set.total_records());
        assert!(total > 0, "the fixture must actually push something");
    }

    #[test]
    fn a_tile_id_outside_the_range_is_refused() {
        let dir = tmp("range");
        let mut set = BucketSet::new(&dir, "r", 21, 85, 16).unwrap();
        let rec = SpillRecord {
            tile_id: 20,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        assert!(set.push(20, &rec.to_bytes().unwrap()).is_err(), "below the range");
        assert!(set.push(85, &rec.to_bytes().unwrap()).is_err(), "above the range");
    }

    /// A bucket claiming a record it does not cover is the failure a bad partition would
    /// produce, so the reader refuses it even though the writer could not have made it.
    #[test]
    fn a_reader_refuses_a_record_from_the_wrong_range() {
        let dir = tmp("wrongrange");
        let mut set = BucketSet::new(&dir, "w", 0, 64, 1).unwrap();
        let rec = SpillRecord {
            tile_id: 5,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        set.push(5, &rec.to_bytes().unwrap()).unwrap();
        set.seal().unwrap();

        // Rewrite the same bucket with an out-of-range id, keeping the byte count so
        // only the range check can catch it.
        let mut moved = rec.clone();
        moved.tile_id = 999;
        let bytes = moved.to_bytes().unwrap();
        assert_eq!(bytes.len(), rec.to_bytes().unwrap().len());
        std::fs::write(dir.join("w.00000.bucket"), &bytes).unwrap();
        let mut r = set.reader(0).unwrap().unwrap();
        assert!(r.next().is_err(), "an out-of-range tile id must error");
    }

    /// Draining buckets in ascending index, each internally sorted, must give globally
    /// ascending `tile_id` -- across two levels of re-partition, which is the expected
    /// path for a dense metro bucket rather than an exotic one.
    #[test]
    fn buckets_drain_in_ascending_tile_id_across_two_recursion_levels() {
        let dir = tmp("recursion");
        let (lo, hi) = (21u64, 85u64);
        let mut parent = BucketSet::new(&dir, "p", lo, hi, 4).unwrap();
        assert_eq!(parent.span(), 16);

        // Pushed in a deliberately scrambled order: the spill has no ordering
        // requirement, which is what would make a parallel bucket pass safe later.
        let order: Vec<u64> = (lo..hi).rev().collect();
        for id in &order {
            let rec = SpillRecord {
                tile_id: *id,
                seq: *id,
                extent: 0,
                geom: IntGeometry::Points(vec![(0, 0)]),
                props: vec![],
            };
            parent.push(*id, &rec.to_bytes().unwrap()).unwrap();
        }
        parent.seal().unwrap();

        let mut drained: Vec<u64> = Vec::new();
        for i in 0..parent.len() {
            let (blo, bhi) = parent.range_of(i);
            // Re-partition every parent bucket, then re-partition each child again.
            let mut child = BucketSet::new(&dir, &format!("c{i}"), blo, bhi, 4).unwrap();
            let mut recs = parent.load(i).unwrap();
            recs.sort_by_key(|r| r.tile_id);
            for r in &recs {
                child.push(r.tile_id, &r.to_bytes().unwrap()).unwrap();
            }
            child.seal().unwrap();
            for j in 0..child.len() {
                let (clo, chi) = child.range_of(j);
                let mut grand =
                    BucketSet::new(&dir, &format!("g{i}_{j}"), clo, chi, 4).unwrap();
                let mut crecs = child.load(j).unwrap();
                crecs.sort_by_key(|r| r.tile_id);
                for r in &crecs {
                    grand.push(r.tile_id, &r.to_bytes().unwrap()).unwrap();
                }
                grand.seal().unwrap();
                for k in 0..grand.len() {
                    let mut g = grand.load(k).unwrap();
                    g.sort_by_key(|r| r.tile_id);
                    drained.extend(g.iter().map(|r| r.tile_id));
                }
            }
        }
        let want: Vec<u64> = (lo..hi).collect();
        assert_eq!(drained, want, "the drain must be globally ascending");
    }

    #[test]
    fn a_bucket_count_that_is_not_a_power_of_four_is_refused() {
        let dir = tmp("pow4");
        for bad in [0usize, 2, 8, 32, 100] {
            assert!(
                BucketSet::new(&dir, "b", 0, 64, bad).is_err(),
                "{bad} buckets must be refused"
            );
        }
        for good in [1usize, 4, 16, 64, 256] {
            assert!(BucketSet::new(&dir, "b", 0, 1024, good).is_ok(), "{good} buckets");
        }
    }

    /// A shallow zoom has fewer tiles than the requested bucket count. Dividing the
    /// count down by four keeps a bucket one quadtree cell; clamping it to the tile
    /// count would not.
    #[test]
    fn a_shallow_range_reduces_the_bucket_count_by_fours() {
        let dir = tmp("shallow");
        // z1 has 4 tiles; asking for 256 buckets gets 4.
        let set = BucketSet::new(&dir, "z1", 1, 5, 256).unwrap();
        assert_eq!(set.len(), 4);
        assert_eq!(set.span(), 1);
        // z0 has one.
        let set = BucketSet::new(&dir, "z0", 0, 1, 256).unwrap();
        assert_eq!(set.len(), 1);
        assert_eq!(set.span(), 1);
    }

    #[test]
    fn a_bucket_set_removes_its_files_when_it_is_dropped() {
        let dir = tmp("cleanup");
        let path;
        {
            let mut set = BucketSet::new(&dir, "gone", 0, 64, 1).unwrap();
            let rec = SpillRecord {
                tile_id: 0,
                seq: 0,
                extent: 0,
                geom: IntGeometry::Points(vec![(0, 0)]),
                props: vec![],
            };
            set.push(0, &rec.to_bytes().unwrap()).unwrap();
            set.seal().unwrap();
            path = dir.join("gone.00000.bucket");
            assert!(path.exists(), "the bucket file must exist while the set does");
        }
        assert!(!path.exists(), "a dropped bucket set must take its files with it");
    }

    /// A bucket that receives no records in a rerun must not inherit the file a killed
    /// previous run left at the same path: `seal` would report bytes nobody pushed, and
    /// with a persistent `--spill-dir` that turns a crash into a baffling hard failure.
    #[test]
    fn a_stale_bucket_file_does_not_survive_a_new_set() {
        let dir = tmp("stale");
        let stale = dir.join("s.00001.bucket");
        std::fs::write(&stale, b"left over by a killed run").unwrap();

        let mut set = BucketSet::new(&dir, "s", 0, 64, 4).unwrap();
        assert!(!stale.exists(), "the stale file must be cleared at construction");
        let rec = SpillRecord {
            tile_id: 0,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        set.push(0, &rec.to_bytes().unwrap()).unwrap();
        set.seal().expect("the books must balance despite the stale file");
        assert!(set.load(1).unwrap().is_empty(), "bucket 1 is empty, not stale");
    }

    #[test]
    fn a_bucket_set_must_be_sealed_before_it_is_read() {
        let dir = tmp("sealed");
        let mut set = BucketSet::new(&dir, "s", 0, 64, 1).unwrap();
        assert!(set.reader(0).is_err(), "reading an unsealed set must error");
        set.seal().unwrap();
        assert!(set.reader(0).unwrap().is_none(), "an empty bucket has no reader");
        let rec = SpillRecord {
            tile_id: 0,
            seq: 0,
            extent: 0,
            geom: IntGeometry::Points(vec![(0, 0)]),
            props: vec![],
        };
        assert!(
            set.push(0, &rec.to_bytes().unwrap()).is_err(),
            "pushing to a sealed set must error"
        );
    }

    // --- the normalized file -------------------------------------------------

    /// Snap a geometry to the e7 grid, which is what [`encode_geometry`] stores.
    ///
    /// A plain decimal literal is only NEARLY on that grid: `-122.42` and
    /// `-1_224_200_000 as f64 * 1e-7` are different `f64`s, an ulp apart -- 1.4e-14
    /// degrees, seven orders of magnitude below the 1.1 cm the quantisation itself costs.
    /// So a decoded feature is compared against its snapped input rather than against the
    /// literal it was written from. The assertion stays exact and total -- every vertex,
    /// every count, every property -- and states the contract the codec actually has.
    fn snapped(g: &Geometry) -> Geometry {
        let pt = |&(x, y): &Pt| {
            (
                crate::pyramid::e7(x) as f64 * 1e-7,
                crate::pyramid::e7(y) as f64 * 1e-7,
            )
        };
        let ring = |r: &Vec<Pt>| r.iter().map(pt).collect::<Vec<Pt>>();
        match g {
            Geometry::Points(p) => Geometry::Points(ring(p)),
            Geometry::Lines(l) => Geometry::Lines(l.iter().map(ring).collect()),
            Geometry::Polygons(p) => {
                Geometry::Polygons(p.iter().map(|rs| rs.iter().map(ring).collect()).collect())
            }
        }
    }

    fn snapped_all(features: &[NormalizedFeature]) -> Vec<NormalizedFeature> {
        features
            .iter()
            .map(|f| NormalizedFeature {
                geometry: snapped(&f.geometry),
                props: f.props.clone(),
            })
            .collect()
    }

    /// **The guarantee a byte-identical archive rests on.** Every coordinate `osm_ingest`
    /// yields is an `i32` e7 multiplied by `1e-7`, and such a value must come back as the
    /// same `f64` it went in as -- not merely close. If it did not, halving the spill
    /// would have moved every OSM vertex in the build.
    ///
    /// Spanning the whole `i32` range, because the argument is about the two
    /// multiplications' relative error staying under half an e7 unit and that is weakest
    /// at the largest magnitude.
    #[test]
    fn an_e7_grid_coordinate_survives_the_round_trip_bit_exactly() {
        let dir = tmp("e7exact");
        let path = dir.join("f.bin");
        let grid: Vec<i32> = vec![
            0,
            1,
            -1,
            1_800_000_000,
            -1_800_000_000,
            900_000_000,
            -900_000_000,
            1_224_200_000,
            -1_224_200_000,
            377_700_000,
            i32::MAX,
            i32::MIN + 1,
        ];
        let points: Vec<Pt> = grid
            .iter()
            .map(|&n| (n as f64 * 1e-7, -n as f64 * 1e-7))
            .collect();
        let feature = NormalizedFeature {
            geometry: Geometry::Points(points.clone()),
            props: every_value(),
        };

        let mut w = NormalizedWriter::create(&path).unwrap();
        w.push(&feature.geometry, &feature.props).unwrap();
        w.finish().unwrap();

        let back = NormalizedReader::open(&path)
            .unwrap()
            .next()
            .unwrap()
            .expect("a feature");
        assert_eq!(back, feature, "an e7-grid coordinate must not move at all");

        // Eight bytes a vertex, not sixteen. The whole point of the encoding.
        let bytes = std::fs::metadata(&path).unwrap().len() as usize;
        let props = {
            let mut buf = Vec::new();
            encode_props(&feature.props, &mut buf).unwrap();
            buf.len()
        };
        assert_eq!(
            bytes,
            NORM_HEADER_BYTES + 4 + points.len() * 8 + props,
            "a vertex must cost eight bytes"
        );

        let _ = std::fs::remove_dir_all(&dir);
    }
