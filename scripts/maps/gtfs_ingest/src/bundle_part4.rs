#[cfg(test)]
mod tests {
    use super::*;

    /// A straight line of `n` points, `metres` apart, running north from `(lat, lon)`.
    fn north(lat: f64, lon: f64, metres: f64, n: usize) -> Vec<(i32, i32)> {
        let step = metres / 111_320.0 * 1e7;
        (0..n).map(|i| (((lat * 1e7) + i as f64 * step) as i32, (lon * 1e7) as i32)).collect()
    }

    /// The same line shifted `metres` east: a second track in one corridor.
    fn shifted(line: &[(i32, i32)], metres: f64) -> Vec<(i32, i32)> {
        let cos_lat = (line[0].0 as f64 * 1e-7).to_radians().cos();
        let d = (metres / (111_320.0 * cos_lat) * 1e7) as i32;
        line.iter().map(|&(lat, lon)| (lat, lon + d)).collect()
    }

    /// One line offered to [`assign`]: route, colour, name and geometry.
    type Fixture = (u32, u32, &'static str, Vec<(i32, i32)>);

    fn candidates(lines: &[Fixture]) -> Vec<Candidate<'_>> {
        lines
            .iter()
            .map(|(route, color, name, points)| Candidate {
                points,
                route: *route,
                color: *color,
                name,
            })
            .collect()
    }

    /// The lane inputs each candidate ends up with: the `(ordinal, count)` of the span
    /// furthest into its lane, which is the one the tapers lead into.
    fn lanes(spans: &[Vec<Span>]) -> Vec<(u8, u8)> {
        spans
            .iter()
            .map(|s| {
                s.iter()
                    .max_by_key(|span| (span.lanes, span.taper))
                    .map(|span| (span.ordinal, span.lanes))
                    .unwrap_or((0, 1))
            })
            .collect()
    }

    #[test]
    fn a_route_running_alone_is_a_corridor_of_one() {
        let line = north(37.7, -122.4, 1000.0, 20);
        let lines = [(0u32, 0x00_54_A5u32, "N", line.clone())];
        let spans = assign(&candidates(&lines));
        assert_eq!(spans, vec![vec![Span { points: line, ordinal: 0, lanes: 1, taper: 255 }]]);
    }

    /// The corridor's colours get consecutive ordinals from zero and every member reports the
    /// same count, which is all the renderer needs to centre the fan on the track.
    #[test]
    fn two_routes_on_one_track_take_the_two_ordinals_of_a_corridor_of_two() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let lines = [(0u32, 0x00_00_FFu32, "A", a), (1, 0xFF_00_00, "B", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 2), (1, 2)]);
    }

    /// A corridor of three is three ordinals of three, laid out across it: the west track
    /// takes lane zero, because lane zero is the left of the reference's direction of travel
    /// and this one runs north.
    #[test]
    fn three_routes_on_one_track_take_three_ordinals() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let (b, c) = (shifted(&a, 8.0), shifted(&a, -8.0));
        let lines =
            [(0u32, 0x00_00_11u32, "A", a), (1, 0x00_00_22, "B", b), (2, 0x00_00_33, "C", c)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(1, 3), (2, 3), (0, 3)], "west to east");
    }

    /// THE defect this pass exists to fix: the members of a corridor draw the *same*
    /// polyline at their own offsets, so they are exactly parallel and evenly spaced rather
    /// than each being its own survey pushed sideways.
    #[test]
    fn the_members_of_a_corridor_all_draw_the_reference_geometry() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let lines = [(0u32, 0x00_00_11u32, "A", a.clone()), (1, 0x00_00_22, "B", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(spans[0].len(), 1, "one corridor, one span");
        assert_eq!(spans[1].len(), 1);
        assert_eq!(spans[0][0].points, a, "the lower colour is the reference");
        assert_eq!(spans[1][0].points, a, "and its corridor-mate draws it too");
    }

    /// The tie-break under the geometric order: two routes published on one identical survey
    /// have the same offset everywhere, so there is no ground truth to recover and the order
    /// falls to the colour and then the name — never the input order, or a feed reordering
    /// its routes would move every line on the map.
    #[test]
    fn coincident_surveys_fall_back_to_colour_then_name() {
        let track = north(37.7, -122.4, 1000.0, 20);
        let lines = [(0u32, 0xFF_00_00u32, "Z", track.clone()), (1, 0x00_00_FF, "A", track)];
        let spans = assign(&candidates(&lines));
        // Route 1's colour is lower, so it takes the first ordinal despite being second in.
        assert_eq!(lanes(&spans), vec![(1, 2), (0, 2)]);
    }

    /// The direction bucket is the whole reason a junction is not a corridor.
    #[test]
    fn two_tracks_crossing_at_a_junction_do_not_share_a_corridor() {
        let north_south = north(37.7, -122.4, 1000.0, 20);
        // Due east through the middle of it.
        let mid = north_south[10];
        let cos_lat = (mid.0 as f64 * 1e-7).to_radians().cos();
        let step = (1000.0 / (111_320.0 * cos_lat) * 1e7) as i32;
        let east_west: Vec<(i32, i32)> =
            (0..20).map(|i| (mid.0, mid.1 + (i - 10) * step)).collect();
        let lines = [(0u32, 0x11u32, "NS", north_south), (1, 0x22, "EW", east_west)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)]);
    }

    /// The renderer's perpendicular is the polyline's own left-hand normal, so the side a
    /// lane lands on depends on the direction the geometry is stored in. Every member of a
    /// corridor stores the reference in the reference's own **canonical** direction, so
    /// neither the direction a feed happened to publish a member in nor which member won the
    /// reference tie-break can reach the output.
    #[test]
    fn the_direction_an_input_is_stored_in_does_not_reach_the_output() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b = shifted(&a, 8.0);
        let flip = |line: &[(i32, i32)]| -> Vec<(i32, i32)> {
            line.iter().rev().copied().collect()
        };

        let forward = [(0u32, 0x11u32, "A", a.clone()), (1, 0x22, "B", b.clone())];
        let member = [(0u32, 0x11u32, "A", a.clone()), (1, 0x22, "B", flip(&b))];
        assert_eq!(assign(&candidates(&forward)), assign(&candidates(&member)));
        // And the reference itself, which is what the canonical fold is for: without it the
        // whole fan mirrors and B lands on the far side of A.
        let reference = [(0u32, 0x11u32, "A", flip(&a)), (1, 0x22, "B", b)];
        assert_eq!(assign(&candidates(&forward)), assign(&candidates(&reference)));
    }

    /// The same, for a fixture that actually eases. An ease piece is cut from the member's
    /// own survey and inherits whatever direction the feed stored that in, and `stroke::band`
    /// takes its normal from the polyline's own direction — so a piece left the wrong way
    /// round puts the member on the far side of the fan across the ease and nowhere else.
    #[test]
    fn an_ease_against_the_references_direction_does_not_mirror() {
        let forward = a_shallow_merge();
        let mut flipped = a_shallow_merge();
        flipped[1].3.reverse();
        let reference = corridor_of(&forward[0].3);

        assert_eq!(lanes(&assign(&candidates(&forward))), lanes(&assign(&candidates(&flipped))));
        for lines in [forward, flipped] {
            let spans = assign(&candidates(&lines));
            assert_eq!(
                spans[1].iter().filter(|s| s.taper < 255).count(),
                TAPER_STEPS,
                "the fixture has to actually ease, or this proves nothing",
            );
            for candidate in &spans {
                for span in candidate.iter().filter(|s| s.lanes > 1) {
                    let head = distance_along(&reference, span.points[0]);
                    let tail =
                        distance_along(&reference, span.points[span.points.len() - 1]);
                    assert!(head <= tail, "a piece stored against the reference: {span:?}");
                }
            }
        }
    }

    /// The junction the merging fixtures meet at.
    const JUNCTION: (f64, f64) = (37.70, -122.40);

    /// A line approaching [`JUNCTION`] from `(east, north)` metres away and then running 4 km
    /// north out of it, the whole thing shifted `side` metres east so several of them are one
    /// corridor over the northbound stretch and separate groups on the way in.
    fn joins_from(from: (f64, f64), side: f64) -> Vec<(i32, i32)> {
        let cos_lat = JUNCTION.0.to_radians().cos();
        let east = |m: f64| m / (111_320.0 * cos_lat) * 1e7;
        let up = |m: f64| m / 111_320.0 * 1e7;
        let (jy, jx) = (JUNCTION.0 * 1e7, JUNCTION.1 * 1e7 + east(side));
        let steps = 20;
        let mut line: Vec<(i32, i32)> = (0..steps)
            .map(|i| {
                let t = 1.0 - i as f64 / steps as f64;
                ((jy + up(from.1 * t)) as i32, (jx + east(from.0 * t)) as i32)
            })
            .collect();
        line.extend((0..=40).map(|i| ((jy + up(i as f64 * 100.0)) as i32, jx as i32)));
        line
    }

    /// A line arriving from the south-west.
    const FROM_WEST: (f64, f64) = (-1400.0, -1400.0);
    /// A line arriving from the south-east.
    const FROM_EAST: (f64, f64) = (1400.0, -1400.0);

    /// THE defect this pass exists to fix. Two routes that merge and then run north together
    /// keep the sides they arrived on: the one that came from the west stays west, whatever
    /// its colour, because lane zero is the left of the reference's direction of travel.
    #[test]
    fn two_routes_merging_keep_the_sides_they_arrived_on() {
        let lines = [
            (0u32, 0x00_00_11u32, "E", joins_from(FROM_EAST, 8.0)),
            (1, 0x00_00_22, "W", joins_from(FROM_WEST, 0.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(1, 2), (0, 2)], "the western arrival takes lane 0");
    }

    /// Two pairs that each share a corridor and then all four share one: each pair stays
    /// contiguous in the merged order and keeps its internal order, rather than the two
    /// groups interleaving by colour value. The colours here run the other way to the
    /// geometry, so ordering by colour would interleave them.
    #[test]
    fn two_groups_joining_keep_their_blocks() {
        let lines = [
            (0u32, 0x00_00_33u32, "P1", joins_from(FROM_WEST, 0.0)),
            (1, 0x00_00_44, "P2", joins_from(FROM_WEST, 8.0)),
            (2, 0x00_00_11, "Q1", joins_from(FROM_EAST, 16.0)),
            (3, 0x00_00_22, "Q2", joins_from(FROM_EAST, 24.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 4), (1, 4), (2, 4), (3, 4)]);
    }

    /// A lone line joining a group lands on the outside of it, on the side it joined from,
    /// rather than in the middle of a group it was never part of.
    #[test]
    fn a_lone_line_joining_a_group_lands_on_the_outside_of_it() {
        let lines = [
            (0u32, 0x00_00_22u32, "P1", joins_from(FROM_WEST, 0.0)),
            (1, 0x00_00_33, "P2", joins_from(FROM_WEST, 8.0)),
            (2, 0x00_00_11, "Lone", joins_from(FROM_EAST, 16.0)),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 3), (1, 3), (2, 3)], "and not between the pair");
    }

    /// Two lines of one route — a trunk and a branch — are one member of the corridor and
    /// take one lane, because the lane is per route and not per polyline.
    #[test]
    fn two_lines_of_one_route_share_its_lane() {
        let trunk = north(37.7, -122.4, 1000.0, 20);
        let branch = north(37.7, -122.4, 1000.0, 10);
        let other = shifted(&trunk, 8.0);
        let lines = [
            (0u32, 0x11u32, "A", trunk.clone()),
            (0, 0x11, "A", branch),
            (1, 0x22, "B", other),
        ];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 2), (0, 2), (1, 2)], "one route, one lane");
        // And the reference is the *longer* of the two, or the trunk would be clipped to
        // the branch that runs half of it.
        assert_eq!(spans[2][0].points, trunk);
    }

    /// Two services of one colour — a feed publishing each direction as its own route —
    /// take one lane, so they coincide exactly rather than drawing as two lines nothing
    /// distinguishes.
    #[test]
    fn two_routes_of_one_colour_take_one_lane_and_coincide() {
        let a = north(37.7, -122.4, 1000.0, 20);
        let b: Vec<(i32, i32)> = shifted(&a, 8.0).iter().rev().copied().collect();
        let lines = [(0u32, 0x11u32, "Yellow-N", a), (1, 0x11, "Yellow-S", b)];
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)], "one colour is one lane");
        assert_eq!(spans[0], spans[1], "so the two directions are the same line");
    }

    /// The lane is per corridor, not per route: a service that shares one track here and a
    /// different one there takes its place in each. Carrying one lane for a whole route is
    /// what left BART's five services on a sparse uneven subset across the bay.
    #[test]
    fn a_route_crossing_two_corridors_takes_a_lane_in_each() {
        // The main line runs 6 km north. One route joins it for the first 2 km and another
        // for the last 2 km, with 2 km to itself in between.
        let main = north(37.70, -122.40, 100.0, 61);
        let first = shifted(&north(37.70, -122.40, 100.0, 21), 8.0);
        let last = shifted(&north(37.70 + 4000.0 / 111_320.0, -122.40, 100.0, 21), -8.0);
        let lines = [
            (0u32, 0x00_00_10u32, "Main", main),
            (1, 0x00_00_20, "First", first),
            (2, 0x00_00_05, "Last", last),
        ];
        let spans = assign(&candidates(&lines));
        let places: Vec<(u8, u8)> = spans[0].iter().map(|s| (s.ordinal, s.lanes)).collect();
        // West of the first corridor's partner and east of the second's, so it takes the
        // west lane of one and the east lane of the other.
        assert!(places.contains(&(0, 2)), "the first corridor's lower ordinal: {places:?}");
        assert!(places.contains(&(1, 2)), "the second corridor's higher ordinal: {places:?}");
        assert!(places.contains(&(0, 1)), "and its own track in between: {places:?}");
        assert_eq!(lanes(&spans[1..2]), vec![(1, 2)], "the first corridor's higher ordinal");
        assert_eq!(lanes(&spans[2..3]), vec![(0, 2)], "the second corridor's lower ordinal");
    }

    /// Nothing here caps the count: the style bounds how many lanes are drawn, so a corridor
    /// of five reports five and every member gets its own ordinal.
    #[test]
    fn every_colour_of_a_corridor_gets_its_own_ordinal() {
        let a = north(37.7, -122.4, 1000.0, 20);
        // Within `CORRIDOR_M` end to end, or the outer two would be in corridors of their
        // own rather than in one of five.
        let lines: Vec<Fixture> = (0..5)
            .map(|i| {
                let colour = 0x10u32 + i;
                let points = shifted(&a, (i as f64 - 2.0) * 6.0);
                (i, colour, "X", points)
            })
            .collect();
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 5), (1, 5), (2, 5), (3, 5), (4, 5)]);
    }

    /// THE case the corridor rule exists for: a radial network. Three services share a short
    /// central subway and then run a long way alone, and the lane has to be taken where it
    /// does some work rather than where the route spends its length.
    #[test]
    fn a_shared_trunk_gives_a_lane_and_the_run_alone_does_not() {
        let lines = trunk_then_branches(2000.0, 3);
        let spans = assign(&candidates(&lines));
        // C leaves the trunk westward and A eastward, so they take the outer lanes and B,
        // which carries straight on, takes the middle.
        assert_eq!(lanes(&spans), vec![(2, 3), (1, 3), (0, 3)], "the shared trunk decides");
        for candidate in &spans {
            assert_eq!(
                candidate.last().expect("a span").lanes,
                1,
                "and the long run alone stays on its own alignment",
            );
        }
    }

    /// The guard on that rule: two alignments that touch briefly leaving a station are not a
    /// corridor, and fanning a route out on that evidence would move it off its own casing
    /// for nothing.
    #[test]
    fn a_run_shorter_than_the_minimum_does_not_become_a_corridor() {
        let lines = trunk_then_branches(100.0, 2);
        let spans = assign(&candidates(&lines));
        assert_eq!(lanes(&spans), vec![(0, 1), (0, 1)]);
        for candidate in &spans {
            assert_eq!(candidate.len(), 1, "and the route is not cut up over it");
        }
    }

    /// A route stepping sideways by nine Dp at a corridor mouth reads as a break in the
    /// line, so it eases in over a hundred metres or more instead.
    #[test]
    fn a_route_tapers_into_its_lane_at_the_end_of_a_corridor() {
        let lines = trunk_then_branches(2000.0, 4);
        let spans = assign(&candidates(&lines));
        let pieces: Vec<(u8, u8, u8)> =
            spans[0].iter().map(|s| (s.ordinal, s.lanes, s.taper)).collect();
        assert_eq!(pieces[0], (3, 4, 255), "the east lane of four, fully in lane");
        assert_eq!(
            pieces.last().expect("a span").1,
            1,
            "and its own track at the far end",
        );
        let tapers: Vec<u8> = pieces.iter().filter(|p| p.1 == 4).map(|p| p.2).collect();
        assert!(
            tapers.windows(2).all(|w| w[0] >= w[1]),
            "the taper only ever eases out of the lane: {tapers:?}",
        );
        assert!(pieces.len() >= 2 + TAPER_STEPS, "with a step per taper piece: {pieces:?}");
        // The ramp is equal-stepped the whole way, including the jump between the last ease
        // piece and the full lane of the body — there is no gap at either end of it.
        let step = 255.0 / (TAPER_STEPS + 1) as f64;
        for pair in tapers.windows(2) {
            let jump = f64::from(pair[0]) - f64::from(pair[1]);
            assert!((jump - step).abs() <= 1.0, "an uneven jump in the ease: {tapers:?}");
        }
        assert_eq!(
            *tapers.last().expect("a taper"),
            taper_fraction(1),
            "and the far end of the ease is one step off the alignment: {tapers:?}",
        );
    }

    /// A line in a lane on both sides of a run boundary steps from one lane to the other. It
    /// has no alignment of its own to ease onto there, and tapering to nothing and back is
    /// what collapsed every member of both corridors onto the centreline at the seam.
    #[test]
    fn two_abutting_corridors_do_not_taper_between_them() {
        let spans = assign(&candidates(&back_to_back_corridors()));
        for span in &spans[0] {
            assert_eq!(
                span.taper, 255,
                "the through route dips out of its lane at the seam: {span:?}",
            );
        }
    }

    /// A member hands over to the reference where the two surveys are close enough that the
    /// swap cannot be seen, not where they first count as one corridor. Two lines closing at
    /// a shallow angle are one corridor thirty metres apart, and handing over there puts the
    /// whole thirty metres into one sideways step at the mouth.
    #[test]
    fn a_shallow_approach_draws_its_own_track_until_it_is_close() {
        let lines = a_shallow_merge();
        let spans = assign(&candidates(&lines));
        let reference = corridor_of(&lines[0].3);
        let body = spans[1]
            .iter()
            .position(|s| s.lanes > 1 && s.taper == 255)
            .expect("a corridor body");
        let ease_from =
            spans[1].iter().position(|s| s.lanes > 1).expect("a piece in the corridor");
        assert!(ease_from < body, "the joining line eases in before it reaches the reference");
        assert!(
            nearest(&reference, spans[1][ease_from].points[0]).0 > SNAP_M,
            "the ease starts on the member's own track, well off the reference",
        );
        // Adaptive: the ease stretches to reach the handover rather than being the fixed
        // hundred metres a member that is already on the reference gets.
        let eased: f64 = spans[1][..body]
            .iter()
            .filter(|span| span.lanes > 1)
            .flat_map(|span| span.points.windows(2))
            .map(|pair| distance_m(pair[0], pair[1]))
            .sum();
        assert!(eased > TAPER_M, "an ease of only {eased} m for a kilometre of convergence");
        // The weld carries the body's first point onto the end of the ease, so the last step
        // of the ease *is* the handover.
        let ease = &spans[1][body - 1].points;
        let step = distance_m(ease[ease.len() - 2], ease[ease.len() - 1]);
        assert!(step <= SNAP_M, "a handover step of {step} m");
    }

    /// The pieces of one route meet: the span before a corridor is carried across to the
    /// point on the reference the corridor span begins at, and so is the ease across to the
    /// body it leads into.
    #[test]
    fn the_spans_of_one_route_share_their_boundary_vertex() {
        for lines in
            [trunk_then_branches(2000.0, 4), back_to_back_corridors(), a_shallow_merge()]
        {
            let spans = assign(&candidates(&lines));
            for candidate in &spans {
                for pair in candidate.windows(2) {
                    assert_eq!(
                        pair[0].points.last(),
                        pair[1].points.first(),
                        "a gap between two spans of one route",
                    );