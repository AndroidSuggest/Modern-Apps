fn polyline_length_m(line: &[(f64, f64)]) -> f64 {
    line.windows(2)
        .map(|p| {
            let north = (p[1].1 - p[0].1) * METRES_PER_DEGREE;
            let east = (p[1].0 - p[0].0) * METRES_PER_DEGREE * p[0].1.to_radians().cos();
            (north * north + east * east).sqrt()
        })
        .sum()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Broadway × Rollins Road, Burlingame — the junction this module was written for.
    const JUNCTION: (f64, f64) = (-122.3622, 37.5889);

    fn offset(east_m: f64, north_m: f64) -> (f64, f64) {
        (
            JUNCTION.0 + east_m / (METRES_PER_DEGREE * JUNCTION.1.to_radians().cos()),
            JUNCTION.1 + north_m / METRES_PER_DEGREE,
        )
    }

    /// A straight way between two points on the local tangent plane, in metres east/north of the
    /// junction. `nodes` are given so tests can decide what touches what.
    struct Straight {
        nodes: [i64; 2],
        line: [(f64, f64); 2],
    }

    fn straight(from_node: i64, from: (f64, f64), to_node: i64, to: (f64, f64)) -> Straight {
        Straight {
            nodes: [from_node, to_node],
            line: [offset(from.0, from.1), offset(to.0, to.1)],
        }
    }

    fn segment<'a>(
        id: i64,
        name: &'a str,
        lanes: u8,
        oneway: bool,
        s: &'a Straight,
    ) -> Segment<'a> {
        Segment { id, name, lanes, oneway, nodes: &s.nodes, line: &s.line }
    }

    fn scratch(name: &str) -> PathBuf {
        std::env::temp_dir().join(format!("mamaps_lanefill_{}_{name}", std::process::id()))
    }

    /// Push every segment and resolve. Takes the segments as a closure so the borrow of the
    /// `Straight`s outlives nothing awkward.
    fn run(name: &str, push: impl FnOnce(&mut Collector) -> Result<()>) -> Vec<(i64, u8)> {
        let mut collector = Collector::create(&scratch(name)).expect("partitions");
        push(&mut collector).expect("push");
        collector.finish().expect("resolve")
    }

    /// THE case this module exists for, to the real geometry: Rollins Road runs east-west across
    /// Broadway's two carriageways, is tagged 6 lanes on one side and 4 on the other, and the
    /// nineteen metres between the carriageways carries no `lanes` tag at all. Way 417329335.
    #[test]
    fn the_stub_between_two_carriageways_takes_the_narrower_neighbours_lane_count() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (9.6, 0.0));
        let east = straight(3, (9.6, 0.0), 4, (120.0, 0.0));
        let out = run("reported", |c| {
            c.push(&segment(417_329_340, "Rollins Road", 6, false, &west))?;
            c.push(&segment(417_329_335, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(504_486_258, "Rollins Road", 4, false, &east))
        });
        assert_eq!(
            out,
            vec![(417_329_335, 4)],
            "the stub inherits 4 - the minimum of its 6 and 4 neighbours - against a default of 2",
        );
    }

    /// The one condition that is not a heuristic. A way that states its own lane count keeps it,
    /// whatever its neighbours say, so this pass can never contradict a survey.
    #[test]
    fn a_way_that_carries_a_lanes_tag_is_never_touched() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let middle = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("tagged", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            // Tagged 3, between neighbours tagged 6. Its own tag wins.
            c.push(&segment(11, "Rollins Road", 3, false, &middle))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "a tagged way is not a recipient: {out:?}");
    }

    /// A donor at one end is a road that happens to end here, not a road this way interrupts.
    /// Without both ends, every untagged street leaving a tagged one would widen.
    #[test]
    fn one_donor_is_not_enough() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let out = run("oneend", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))
        });
        assert!(out.is_empty(), "no donor at the far end: {out:?}");
    }

    /// Collinearity alone is not the same road. A road changing its name at a crossroads is
    /// ordinary, and inheriting across the change would take a width from a different street.
    #[test]
    fn a_collinear_neighbour_with_another_name_is_a_different_road() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("names", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Carolan Avenue", 6, false, &east))
        });
        assert!(out.is_empty(), "the east arm is a different street: {out:?}");
    }

    /// The name alone is not the same road either: a street turning a right angle at a junction is
    /// a different carriageway, and one of the two is very often the wider.
    #[test]
    fn a_same_named_neighbour_round_a_hard_corner_is_a_different_carriageway() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        // Same name, touching the stub's east end, but leaving due north.
        let north = straight(3, (10.0, 0.0), 4, (10.0, 120.0));
        let out = run("corner", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 6, false, &north))
        });
        assert!(out.is_empty(), "a 90-degree turn is not a continuation: {out:?}");
    }

    /// `lanes` counts both directions, so a two-way six inherited onto a one-way stub would paint
    /// six lanes running one way where the truth is about three. A doubling, not a rounding.
    #[test]
    fn a_one_way_stub_does_not_inherit_from_a_two_way_road() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("oneway", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, true, &stub))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "the donors are two-way and the stub is not: {out:?}");
    }

    /// The bound is what separates "the pavement inside a junction" from "a road". Without it,
    /// hundreds of metres of genuinely untagged road silently take a neighbour's width.
    #[test]
    fn a_road_too_long_to_be_a_junction_stub_does_not_inherit() {
        let west = straight(1, (-400.0, 0.0), 2, (-150.0, 0.0));
        let long = straight(2, (-150.0, 0.0), 3, (150.0, 0.0));
        let east = straight(3, (150.0, 0.0), 4, (400.0, 0.0));
        assert!(
            polyline_length_m(&long.line) > MAX_STUB_M,
            "the fixture has to be longer than the bound to test it",
        );
        let out = run("toolong", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &long))?;
            c.push(&segment(12, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "300 m is a road, not a junction stub: {out:?}");
    }

    /// A donor must carry a real tag, so a width travels exactly one way and stops. Two untagged
    /// stubs in a row leaves each with a tagged donor at one end only, and neither inherits --
    /// which is what makes the blast radius a property of the rule rather than a limit to police.
    #[test]
    fn an_inherited_count_never_becomes_a_donor() {
        let west = straight(1, (-120.0, 0.0), 2, (-30.0, 0.0));
        let first = straight(2, (-30.0, 0.0), 3, (0.0, 0.0));
        let second = straight(3, (0.0, 0.0), 4, (30.0, 0.0));
        let east = straight(4, (30.0, 0.0), 5, (120.0, 0.0));
        let out = run("chain", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &first))?;
            c.push(&segment(12, "Rollins Road", 0, false, &second))?;
            c.push(&segment(13, "Rollins Road", 6, false, &east))
        });
        assert!(out.is_empty(), "neither stub has a tagged donor at both ends: {out:?}");
    }

    /// Where the answer is the answer the default would have given, say nothing. Keeps the lookup
    /// table to the ways that actually move, the way `corridor::promote` does.
    #[test]
    fn a_stub_that_would_inherit_its_own_default_is_left_out() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let out = run("noop", |c| {
            c.push(&segment(10, "Rollins Road", 2, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 2, false, &east))
        });
        assert!(out.is_empty(), "two lanes is what the default already draws: {out:?}");
    }

    /// The archive has to be byte-identical however the build is run, so the resolution cannot
    /// depend on the order ways arrived in -- which, with the records split across partitions and
    /// sorted inside them, is the property worth pinning rather than assuming.
    #[test]
    fn the_result_does_not_depend_on_the_order_ways_were_offered() {
        let west = straight(1, (-120.0, 0.0), 2, (-10.0, 0.0));
        let stub = straight(2, (-10.0, 0.0), 3, (10.0, 0.0));
        let east = straight(3, (10.0, 0.0), 4, (120.0, 0.0));
        let forward = run("order_a", |c| {
            c.push(&segment(10, "Rollins Road", 6, false, &west))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(12, "Rollins Road", 5, false, &east))
        });
        let backward = run("order_b", |c| {
            c.push(&segment(12, "Rollins Road", 5, false, &east))?;
            c.push(&segment(11, "Rollins Road", 0, false, &stub))?;
            c.push(&segment(10, "Rollins Road", 6, false, &west))
        });
        assert_eq!(forward, backward);
        assert_eq!(forward, vec![(11, 5)], "the minimum of 6 and 5");
    }

    /// A closed way has one endpoint rather than two, so "a donor at both ends" is not a question
    /// that can be asked of it, and a loop is not a junction stub in any case.
    #[test]
    fn a_closed_way_is_not_a_stub() {
        let ring_nodes = [7i64, 7];
        let ring_line = [offset(0.0, 0.0), offset(0.0, 0.0)];
        let arm = straight(7, (0.0, 0.0), 8, (120.0, 0.0));
        let out = run("closed", |c| {
            c.push(&Segment {
                id: 11,
                name: "Rollins Road",
                lanes: 0,
                oneway: false,
                nodes: &ring_nodes,
                line: &ring_line,
            })?;
            c.push(&segment(10, "Rollins Road", 6, false, &arm))
        });
        assert!(out.is_empty(), "{out:?}");
    }

    /// A way whose extract cut some of its nodes away has coordinates that no longer line up with
    /// its refs, so its endpoints are not where they claim to be.
    #[test]
    fn a_way_whose_geometry_is_short_of_its_refs_is_skipped() {
        let mut collector = Collector::create(&scratch("cut")).expect("partitions");
        let nodes = [1i64, 2, 3];
        let line = [offset(-10.0, 0.0), offset(10.0, 0.0)];
        collector
            .push(&Segment {
                id: 11,
                name: "Rollins Road",
                lanes: 0,
                oneway: false,
                nodes: &nodes,
                line: &line,
            })
            .expect("push");
        assert!(collector.finish().expect("resolve").is_empty());
    }

    /// Both bearings point away from the node they share, so a straight-through road scores zero
    /// and the measure has to wrap correctly at north.
    #[test]
    fn deviation_is_zero_for_a_straight_continuation_and_wraps_at_north() {
        assert!(deviation(90.0, -90.0).abs() < 1e-9, "east meeting west runs straight through");
        assert!(deviation(10.0, -170.0).abs() < 1e-9, "and so does this, across the wrap");
        assert!((deviation(0.0, 0.0) - 180.0).abs() < 1e-9, "a doubling back is the far extreme");
        assert!((deviation(0.0, 90.0) - 90.0).abs() < 1e-9, "a right angle is 90 from straight");
        // Symmetric, because neither way of the pair is privileged.
        assert!((deviation(35.0, -120.0) - deviation(-120.0, 35.0)).abs() < 1e-9);
    }

    /// Peak memory is one partition, so an uneven spread is the thing that breaks. The hazard is
    /// not a run of consecutive ids -- those spread evenly under a plain modulo -- but a *strided*
    /// one, which a modulo folds onto a single partition. Node ids are handed out by editors and
    /// import scripts, so a stride sharing a factor with [`PARTITIONS`] is not exotic.
    #[test]
    fn a_strided_run_of_node_ids_still_spreads_across_the_partitions() {
        let mut hit = vec![0u32; PARTITIONS];
        for k in 0..100_000i64 {
            hit[partition_of(1_000_000_000 + k * PARTITIONS as i64)] += 1;
        }
        assert_eq!(
            hit.iter().filter(|n| **n > 0).count(),
            PARTITIONS,
            "a plain modulo would have put all 100,000 in one partition",
        );
        let (low, high) = (*hit.iter().min().unwrap(), *hit.iter().max().unwrap());
        // 100,000 over 256 averages 390. Within a factor of two of each other is far tighter than
        // a fold-onto-one failure and loose enough not to pin the hash's exact output.
        assert!(high < low * 2, "the spread is even: {low}..{high}");
    }
}
