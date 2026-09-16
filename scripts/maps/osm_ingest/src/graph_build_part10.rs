
    // ---- stop reconnection by component size ------------------------------

    /// Three road networks and a stop on each, plus a stop attached to nothing.
    ///
    /// This is the planet case stated at the smallest scale that can state it.
    /// Continents are not road-connected, so a planet's largest component is
    /// Eurasia-plus-Africa at 57.7% and the old "reconnect everything outside the
    /// largest component" rule tried to drag every American, Australian, Japanese,
    /// British and Indonesian stop across an ocean — failing 1,173,347 times. The
    /// rule is component *size*, so a stop already on a real road network is left
    /// alone whichever network that is.
    #[test]
    fn a_stop_on_any_routable_component_is_left_alone() {
        const A: u32 = 1200; // the largest component
        const B: u32 = 1000; // exactly MIN_ROUTABLE_COMPONENT, so still routable
        const C: u32 = 999; // one node short of it, so not
        let total = A + B + C + 1;

        // A is far west; B and C are adjacent in both index and space, so the
        // widening window around a stop in C reaches B before anything else.
        let mut coords: Vec<geom::Pt> = Vec::with_capacity(total as usize);
        for i in 0..A {
            coords.push((370_000_000 + i as i32 * 1000, -1_220_000_000));
        }
        for i in 0..B {
            coords.push((400_000_000 + i as i32 * 1000, -740_000_000));
        }
        for i in 0..C {
            coords.push((410_000_000 + i as i32 * 1000, -740_000_000));
        }
        // The stop attached to nothing sits beside B's far end.
        coords.push((400_999_500, -740_000_000));

        let mut edges: Vec<(u32, u32)> = Vec::new();
        let chain = |base: u32, len: u32, edges: &mut Vec<(u32, u32)>| {
            for i in 1..len {
                edges.push((base + i - 1, base + i));
                edges.push((base + i, base + i - 1));
            }
        };
        chain(0, A, &mut edges);
        chain(A, B, &mut edges);
        chain(A + B, C, &mut edges);
        let csr = csr_of(total, &edges);

        let mut stops = Bitset::new(u64::from(total));
        for s in [A / 2, A + B / 2, A + B + C / 2, A + B + C] {
            stops.set(u64::from(s));
        }

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, u64::from(A));
        assert_eq!(
            rec.already_connected, 2,
            "the stops in A and in B are both already on a routable component"
        );
        assert_eq!(rec.unreachable, 0);
        // The stop in C and the stop attached to nothing, both connectors both ways.
        assert_eq!(rec.synth.len(), 4);
        for source in [A + B + C / 2, A + B + C] {
            let target = rec
                .synth
                .iter()
                .find(|s| s.source == source)
                .expect("every isolated stop gets a connector")
                .target;
            assert!(
                target < A + B,
                "stop {source} was connected to node {target}, which is in C"
            );
            assert_eq!(target, A + B - 1, "and to the nearest such node");
        }
        // Both directions of each connector are present, which is what makes the
        // stop reachable rather than merely reaching.
        for s in &rec.synth {
            assert!(
                rec.synth
                    .iter()
                    .any(|t| t.source == s.target && t.target == s.source),
                "connector {} -> {} has no return",
                s.source,
                s.target
            );
        }
    }

    #[test]
    fn the_routable_threshold_falls_back_to_the_largest_component() {
        // A graph whose largest component is under MIN_ROUTABLE_COMPONENT still has
        // a road network — it is just a small one. Requiring 1000 nodes there would
        // qualify nothing and reconnect nothing, so the threshold is capped at the
        // largest component that exists. Two nodes of road and a stop off to one
        // side: the stop must still find the road.
        let coords: Vec<geom::Pt> = vec![
            (370_000_000, -1_220_000_000),
            (370_010_000, -1_220_000_000),
            (380_000_000, -1_220_000_000),
        ];
        let csr = csr_of(3, &[(0, 1), (1, 0)]);
        let mut stops = Bitset::new(3);
        stops.set(2);

        let rec = reconnect_isolated_stops(&coords, &stops, &csr);
        assert_eq!(rec.lcc_size, 2);
        assert_eq!(rec.already_connected, 0);
        assert_eq!(rec.unreachable, 0);
        assert_eq!(rec.synth.len(), 2);
        assert_eq!(rec.synth[0].source, 1, "the nearest node of the only road");
        assert_eq!(rec.synth[0].target, 2);
    }

    #[test]
    fn args_default_the_output_directory() {
        let (input, out, opts) = parse_args(&["cal.osm.pbf".into()]).unwrap();
        assert_eq!(input, PathBuf::from("cal.osm.pbf"));
        assert_eq!(out, PathBuf::from("map_data"));
        assert!(
            opts.within_way_chains,
            "the streaming within-way path is the CLI default"
        );
        let (_, out, _) = parse_args(&["cal.osm.pbf".into(), "--out".into(), "d".into()]).unwrap();
        assert_eq!(out, PathBuf::from("d"));
        let (_, _, opts) =
            parse_args(&["cal.osm.pbf".into(), "--reference-collapse".into()]).unwrap();
        assert!(
            !opts.within_way_chains,
            "--reference-collapse opts back into the reference path"
        );
        assert_eq!(opts.round_count(), 1, "one round is the default");
        assert!(opts.spill_dir.is_none(), "the spill defaults to the output dir");
        let (_, _, opts) = parse_args(&["cal.osm.pbf".into(), "--rounds".into(), "8".into()]).unwrap();
        assert_eq!(opts.round_count(), 8);
        let (_, _, opts) =
            parse_args(&["c.pbf".into(), "--spill-dir".into(), "/fast".into()]).unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-dir".into()]).is_err());
        let (_, _, opts) = parse_args(&[
            "c.pbf".into(),
            "--spill-dir".into(),
            "/roomy".into(),
            "--spill-pts-dir".into(),
            "/fast".into(),
        ])
        .unwrap();
        assert_eq!(opts.spill_dir, Some(PathBuf::from("/roomy")));
        assert_eq!(opts.spill_pts_dir, Some(PathBuf::from("/fast")));
        assert!(parse_args(&["a".into(), "--spill-pts-dir".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "0".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into(), "many".into()]).is_err());
        assert!(parse_args(&["a".into(), "--rounds".into()]).is_err());
        let (_, _, opts) = parse_args(&["c.pbf".into()]).unwrap();
        assert!(opts.threads.is_none(), "the pool defaults to the box");
        let (_, _, opts) =
            parse_args(&["c.pbf".into(), "--threads".into(), "4".into()]).unwrap();
        assert_eq!(opts.threads, Some(4));
        assert!(parse_args(&["a".into(), "--threads".into(), "0".into()]).is_err());
        assert!(parse_args(&["a".into(), "--threads".into()]).is_err());
        assert!(parse_args(&[]).is_err());
        assert!(parse_args(&["a".into(), "b".into()]).is_err());
        assert!(parse_args(&["--wat".into()]).is_err());
    }
