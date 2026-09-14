#[cfg(test)]
mod tests {
    use super::*;
    use crate::schema::traffic_extra::tests::{write_graph, EdgeSpec, GraphFixture};

    /// A four-arm crossroads: node 0 in the middle, nodes 1..4 due north, east, south and west of
    /// it, every arm two-way and residential. Roughly 100 m out on each side, which is a real
    /// intersection's scale and comfortably longer than [`SETBACK_M`].
    ///
    /// Built with the traffic layer's own byte-for-byte v6 writer, so the reader here is exercised
    /// against the same layout the contract specifies rather than a mock.
    fn crossroads(lanes: &[(u32, Vec<u16>)]) -> GraphFixture {
        crossroads_at(350_000_000, 12_000, lanes)
    }

    /// The same crossroads at an arbitrary latitude. `lon_step_e7` is chosen by the caller so the
    /// east and west arms stay roughly 100 m out as `cos φ` shrinks a degree of longitude.
    fn crossroads_at(
        lat_e7: i32,
        lon_step_e7: i32,
        lanes: &[(u32, Vec<u16>)],
    ) -> GraphFixture {
        let coords: [(i32, i32); 5] = [
            (lat_e7, -1_200_000_000),             // 0: the junction
            (lat_e7 + 9_000, -1_200_000_000),     // 1: north, ~100 m
            (lat_e7, -1_200_000_000 + lon_step_e7), // 2: east
            (lat_e7 - 9_000, -1_200_000_000),     // 3: south
            (lat_e7, -1_200_000_000 - lon_step_e7), // 4: west
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        write_graph(
            "junction",
            &coords,
            &[
                // Edges 0..3 leave the junction; edges 4..7 return to it. Grouped by source, which
                // is what makes `nodes.bin`'s edge_ptr a CSR row pointer.
                edge(0, 1),
                edge(0, 2),
                edge(0, 3),
                edge(0, 4),
                edge(1, 0),
                edge(2, 0),
                edge(3, 0),
                edge(4, 0),
            ],
            lanes,
        )
    }

    fn stream(fixture: &GraphFixture) -> (u64, Vec<Vec<(f64, f64)>>) {
        let spill = fixture.dir.join("features.tmp");
        let mut sink = Sink::create(&spill).expect("sink");
        let emitted =
            stream_junctions(&fixture.dir, &Conventions::default(), &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");
        let mut reader = store.reader().expect("reader");
        let mut lines = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            assert_eq!(feature.class.layer, LAYER_JUNCTION);
            assert_eq!(feature.class.min_zoom, MIN_ZOOM);
            assert!(!feature.class.area, "a connector is a line, not a ring");
            match &feature.geometry {
                Geometry::Lines(parts) => {
                    assert_eq!(parts.len(), 1, "one connector is one part");
                    lines.push(parts[0].clone());
                }
                other => panic!("a connector came back as {other:?}"),
            }
        }
        let _ = std::fs::remove_file(&spill);
        (emitted, lines)
    }

    /// **A connector must start and end on the asphalt the renderer actually paints.**
    ///
    /// Two defects met here and neither could be fixed alone. The ends disagreed — the approach was
    /// widened to the junction's exit count while the exit kept `lanes.bin`'s, so [`exit_lane`]
    /// returned lane 0 for every movement and all `d - 1` connectors into an arm terminated on one
    /// shared point. And the width itself was fabricated: `legal.len()` counts exits, not lanes.
    /// Mirroring the fabricated width would have made the two ends agree and put *both* of them off
    /// the road, which is why this pins position rather than spread.
    ///
    /// An untagged two-way road is one lane each way — the carriageway renderer's own default — so
    /// the kerb is one lane width from the centreline and every connector end belongs at half of
    /// one, on the driver's side. Under the fabricated count the outermost lane sat at 2.5 widths
    /// against that kerb at 1.0, so this fails on the starts; mirror it and it fails on the ends
    /// too.
    ///
    /// Distinct endpoints are deliberately *not* asserted. With one real lane per direction the
    /// movements out of an arm share it, so `d` distinct endpoints at a degree-`d` junction is the
    /// correct answer; separating them would mean drawing lanes that are not there.
    ///
    /// **This test assumes the carriageway is one lane each way, and that assumption is no longer
    /// universally true.** It holds here because the fixture is untagged and [`effective_lanes`]
    /// and the renderer then agree on 1. [`crate::lanefill`] now fills the lane *count* of a
    /// junction stub from the road it interrupts, so at a divided arterial the carriageway is
    /// painted several lanes wide while this layer, which reads `turn:lanes` masks that `lanefill`
    /// deliberately does not write, still lays connectors out one lane per direction. The two
    /// disagree there and this test cannot see it: connectors hug the centreline of a wide
    /// intersection rather than running off it, so nothing here fails. Closing that needs the
    /// filled count carried to the directed edge this layer sees, which is not yet possible.
    #[test]
    fn a_connector_starts_and_ends_on_the_painted_carriageway() {
        let (emitted, lines) = stream(&crossroads(&[]));
        assert_eq!(emitted, 12, "four approaches x three exits, unchanged");

        // The fixture's arms run due north, east, south and west, so of a point's two displacements
        // from the node the larger is the along-road setback and the smaller is the lateral offset.
        // This inverts `offset_degrees`, which reads longitude at the node's own latitude.
        let lane = lane_width_ground_m(35.0);
        let lateral = |(lon, lat): (f64, f64)| {
            let east = (lon + 120.0) * METRES_PER_DEGREE * 35.0f64.to_radians().cos();
            let north = (lat - 35.0) * METRES_PER_DEGREE;
            east.abs().min(north.abs())
        };
        for line in &lines {
            for (end, p) in [("start", line[0]), ("end", *line.last().expect("points"))] {
                let off = lateral(p);
                assert!(
                    off < lane,
                    "a connector's {end} sits {off:.2} m from the centreline, past the kerb at \
                     {lane:.2} m. An untagged two-way road is one lane each way, so this connector \
                     is drawn clean off the road it is meant to join — a lane count has been \
                     fabricated from something that is not a lane count.",
                );
                assert!(
                    (off - lane * 0.5).abs() < 0.05,
                    "a connector's {end} belongs in the middle of the arm's single lane, at \
                     {:.2} m from the centreline, not {off:.2} m",
                    lane * 0.5,
                );
            }
        }

        // Both ends reading the same count is what lets a straight-through movement keep its lane
        // and therefore its offset, so it is genuinely straight and samples as two points. One per
        // approach. With the ends disagreeing the two offsets differed and all twelve came out bent.
        assert_eq!(
            lines.iter().filter(|l| l.len() == 2).count(),
            4,
            "a through movement should leave and arrive in the same lane, so it draws straight",
        );
    }

    /// The single source both ends read, and the default it falls back to.
    ///
    /// The fallback is not a guess. It is the carriageway renderer's `lane_count 0 -> oneway ? 1 : 2`
    /// read in this module's per-direction units, where both branches come to one lane, so a
    /// connector is laid out across exactly the asphalt that gets painted.
    #[test]
    fn an_arms_lane_count_is_its_tagged_one_or_the_renderers_own_default() {
        let arm_with = |masks: Option<Vec<u16>>, two_way: bool| Arm {
            neighbour: 1,
            heading: 0.0,
            length_m: 100.0,
            two_way,
            masks,
        };
        assert_eq!(
            effective_lanes(&arm_with(Some(vec![LANE_LEFT, LANE_THROUGH]), true)),
            2,
            "`turn:lanes` is the only real lane count reachable from the routing graph",
        );
        assert_eq!(
            effective_lanes(&arm_with(None, true)),
            1,
            "a two-way road is two lanes across both directions, so one each way",
        );
        assert_eq!(
            effective_lanes(&arm_with(None, false)),
            1,
            "and a one-way road is one lane, which is the renderer's other branch",
        );
    }

    /// **A tiler-side `min_zoom` past the archive's deepest zoom deletes a layer in silence.**
    ///
    /// The tiler writes a feature into tiles from its `min_zoom` down. Nothing above `--max-zoom`
    /// is ever built, so a layer gated deeper than that is computed in full, costs its whole run
    /// time, and then lands in no tile at all — while the build exits zero and prints the count it
    /// generated. Junction shipped at 16 against a 14-deep archive and the only symptom was a
    /// layer missing from the archive's own summary.
    ///
    /// Pinned against [`crate::DEFAULT_MAX_ZOOM`] itself, so the bound is a compile-time
    /// dependency on the build's own default rather than a copy of it, and for both layers read
    /// out of the routing graph, because they share the failure.
    #[test]
    fn a_graph_derived_layer_is_tiled_no_deeper_than_the_archive_is_built() {
        let max_zoom = crate::DEFAULT_MAX_ZOOM;
        let layers = [("junction", MIN_ZOOM), ("traffic", crate::schema::traffic::MIN_ZOOM)];
        for (layer, min_zoom) in layers {
            assert!(
                min_zoom <= max_zoom,
                "`{layer}` is tiled from z{min_zoom}, but archives are only built to z{max_zoom}, \
                 so no tile that could hold it is ever written: every {layer} feature will be \
                 computed and then discarded, and the layer will be silently absent from every \
                 archive while the build still reports success. This constant is the TILING gate \
                 and must be at most z{max_zoom}; the zoom the layer is DRAWN at is the style's \
                 `minzoom` in basemap.flat.json and is set there, independently.",
            );
        }
    }

    /// The angle sign convention the whole layer rests on: positive is a right turn.
    ///
    /// Wrong here and every lane pairing is mirrored, which is the one failure that would look
    /// plausible on a screenshot — connectors that curve smoothly into the wrong road.
    #[test]
    fn a_turn_angle_is_positive_to_the_right() {
        // Heading north, leaving east.
        assert_eq!(classify_turn(normalise_degrees(90.0 - 0.0)), Turn::Right);
        // Heading south, leaving east: east is on the driver's left.
        assert_eq!(classify_turn(normalise_degrees(90.0 - 180.0)), Turn::Left);
        // The wrap is what makes that true rather than a 270-degree right.
        assert_eq!(normalise_degrees(90.0 - 180.0), -90.0);
        assert_eq!(normalise_degrees(350.0), -10.0);
        assert_eq!(normalise_degrees(-350.0), 10.0);
        assert_eq!(classify_turn(0.0), Turn::Through);
        assert_eq!(classify_turn(-15.0), Turn::Through);
        assert_eq!(classify_turn(30.0), Turn::SlightRight);
        assert_eq!(classify_turn(-30.0), Turn::SlightLeft);
        assert_eq!(classify_turn(150.0), Turn::SharpRight);
        assert_eq!(classify_turn(-150.0), Turn::SharpLeft);
        assert_eq!(classify_turn(179.0), Turn::Reverse);
    }

    /// Headings come from `atan2` over a geometry step, because the graph stores none.
    #[test]
    fn a_heading_is_computed_from_the_geometry_and_corrected_for_latitude() {
        let origin = (350_000_000, -1_200_000_000);
        assert!((bearing_degrees(origin, (350_009_000, -1_200_000_000)) - 0.0).abs() < 0.01, "north");
        assert!((bearing_degrees(origin, (349_991_000, -1_200_000_000)) - 180.0).abs() < 0.01, "south");
        assert!((bearing_degrees(origin, (350_000_000, -1_199_988_000)) - 90.0).abs() < 0.01, "east");
        assert!((bearing_degrees(origin, (350_000_000, -1_200_012_000)) + 90.0).abs() < 0.01, "west");
        // Without the cos(lat) correction a due-northeast step at latitude 35 comes out at 45
        // degrees only by accident of the coordinate deltas; with it, equal *ground* distances do.
        let north_m = 1000.0;
        let east_m = 1000.0;
        let (lon, lat) = offset_degrees(origin, east_m, north_m);
        let to = ((lat * 1e7) as i32, (lon * 1e7) as i32);
        assert!((bearing_degrees(origin, to) - 45.0).abs() < 0.05, "{}", bearing_degrees(origin, to));
    }

    /// A node nothing forks at emits nothing. The traffic layer's own square fixture is four nodes
    /// of degree two or less, so it is exactly this case.
    #[test]
    fn a_chain_of_degree_two_nodes_produces_no_connectors() {
        let coords: [(i32, i32); 3] = [
            (350_000_000, -1_200_000_000),
            (350_000_000, -1_199_990_000),
            (350_000_000, -1_199_980_000),
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        let fixture = write_graph(
            "junction_chain",
            &coords,
            &[edge(0, 1), edge(1, 0), edge(1, 2), edge(2, 1)],
            &[],
        );
        let (emitted, lines) = stream(&fixture);
        assert_eq!(emitted, 0, "node 1 has two neighbours, so nothing turns there");
        assert!(lines.is_empty());
    }

    /// The inferred path, which is what the overwhelming majority of roads take.
    ///
    /// Four approaches, three legal exits each (everything but the U-turn back the way you came),
    /// one inferred lane per exit: twelve connectors. The four outer nodes are degree one and
    /// contribute nothing, which is what pins the count to node 0 alone.
    #[test]
    fn a_crossroads_with_no_tagged_lanes_infers_one_connector_per_legal_movement() {
        let fixture = crossroads(&[]);
        let (emitted, lines) = stream(&fixture);
        assert_eq!(emitted, 12, "four approaches x three exits");
        assert_eq!(lines.len(), 12);
        for line in &lines {
            assert!(line.len() >= 2, "a connector needs at least two points");
            assert!(line.len() <= 14, "the sampler is bounded");
            for (lon, lat) in line {
                assert!(lon.is_finite() && lat.is_finite());
                // Every connector stays inside the intersection box, which is two setbacks across.
                assert!((lat - 35.0).abs() < 0.001, "{lat} ran outside the junction");
                assert!((lon + 120.0).abs() < 0.001, "{lon} ran outside the junction");
            }
        }
        // A turning connector is a sampled curve; a straight-through one need not be.
        assert!(
            lines.iter().any(|l| l.len() > 4),
            "no connector was sampled as a curve, so the bezier is not being walked",
        );
    }

    /// The data path: a tagged `turn:lanes` on one approach replaces the inference for that
    /// approach only, and a shared `through;right` lane emits both of its movements.
    #[test]
    fn a_tagged_turn_lanes_approach_pairs_each_lane_with_its_own_exit() {
        // Edge 4 is node1 -> node0, the approach from the north, arriving heading south. Its lanes,
        // left to right: a left-turn lane, a through lane, and a shared through/right lane.
        let masks = vec![
            LANE_LEFT,
            LANE_THROUGH,
            LANE_THROUGH | LANE_RIGHT,
        ];
        let fixture = crossroads(&[(4u32, masks)]);
        let (emitted, _) = stream(&fixture);
        // The tagged approach: one left, two throughs, one right = 4. The other three approaches
        // are untagged and infer three each = 9.
        assert_eq!(emitted, 13, "the tagged approach adds a fourth connector for its shared lane");
    }

    /// Spreading lanes across exits, both ways round. A dual left turn is two ribbons into one
    /// road; a single lane onto a two-lane exit fans into both.
    #[test]
    fn lanes_and_exits_are_distributed_so_neither_side_is_left_unfed() {
        assert_eq!(distribute(&[0, 1], &[7]), vec![(0, 7), (1, 7)], "a dual left turn");
        assert_eq!(distribute(&[3], &[5, 8]), vec![(3, 5), (3, 8)], "one lane fanning");
        assert_eq!(distribute(&[0, 1], &[4, 9]), vec![(0, 4), (1, 9)], "one to one");
        assert!(distribute(&[], &[1]).is_empty());
        assert!(distribute(&[1], &[]).is_empty());
    }

    /// A tagged indication no exit's own bucket matches still connects, to the closest exit by
    /// angle — a threshold disagreement is far likelier than a lane that leads nowhere.
    #[test]
    fn a_tagged_turn_with_no_matching_exit_falls_back_to_the_closest_one() {
        // Exits at a sharp right and straight on; nothing the classifier calls a plain right.
        let legal = vec![(0usize, 150.0, Turn::SharpRight), (1usize, 5.0, Turn::Through)];
        assert_eq!(exits_for(Turn::Right, &legal), vec![0], "90 is nearer 150 than 5");
        assert_eq!(exits_for(Turn::Through, &legal), vec![1], "an exact bucket wins");
        assert!(exits_for(Turn::Left, &[]).is_empty());
    }

    /// Lane offsets, which are what stop every connector from stacking on the road's centreline.
    #[test]
    fn a_lane_sits_on_its_own_side_of_the_centreline() {
        let w = LANE_WIDTH_MERCATOR_M;
        // A one-way, two lanes: centred on the road, half a lane either side.
        assert!((lane_offset_m(0, 2, false, false, w) + w / 2.0).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, false, false, w) - w / 2.0).abs() < 1e-9);
        // Two-way, right-hand traffic: the whole carriageway sits right of the centreline, so both
        // lanes are strictly positive and the inner one is half a lane out.
        assert!((lane_offset_m(0, 2, true, false, w) - w * 0.5).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, true, false, w) - w * 1.5).abs() < 1e-9);
        // Left-hand traffic mirrors it, and only it: lane order within the carriageway does not
        // flip, because `lanes.bin` stores masks left-to-right in the direction of travel.
        assert!((lane_offset_m(0, 2, true, true, w) + w * 1.5).abs() < 1e-9);
        assert!((lane_offset_m(1, 2, true, true, w) + w * 0.5).abs() < 1e-9);
        // A one-way is the same either side of the world: there is no opposing carriageway to sit
        // beside.
        assert_eq!(lane_offset_m(0, 3, false, true, w), lane_offset_m(0, 3, false, false, w));
    }

    /// **The latitude invariant, and the whole reason the offset is not baked in ground metres.**
    ///
    /// The renderer's lane width is a screen-space quantity, and Web Mercator carries a `1 / cos φ`
    /// stretch, so a connector offset by a fixed *ground* distance drifts off the carriageway as
    /// you go north — by 2.3x at 60°N, which is more than two lane widths. Offsetting by a fixed
    /// *projected* distance is what makes it land, at every latitude and with no wire change.
    ///
    /// Measured end to end through the real encoder rather than on the helper, and asserted from
    /// both sides: the projected spread must match, and the ground spread must not — otherwise the
    /// test would still pass if the correction were quietly dropped.
    #[test]
    fn a_connectors_lateral_offset_is_the_same_projected_size_at_every_latitude() {
        // The correction itself: a lane's ground width shrinks toward the poles exactly as fast as
        // Mercator stretches it.
        assert!((lane_width_ground_m(0.0) - LANE_WIDTH_MERCATOR_M).abs() < 1e-9);
        assert!(
            (lane_width_ground_m(60.0) - LANE_WIDTH_MERCATOR_M * 0.5).abs() < 1e-6,
            "cos 60 is a half",
        );

        // The first three connectors are the north approach's, in `incoming.of(0)` order. They
        // share a setback and a heading, so the spread between their start points is purely the
        // lane offset.
        let spread = |lines: &[Vec<(f64, f64)>], project: &dyn Fn((f64, f64)) -> (f64, f64)| {
            let starts: Vec<(f64, f64)> = lines[..3].iter().map(|l| project(l[0])).collect();
            let mut worst: f64 = 0.0;
            for a in &starts {
                for b in &starts {
                    worst = worst.max(((a.0 - b.0).powi(2) + (a.1 - b.1).powi(2)).sqrt());
                }
            }
            worst
        };

        // 0.0009 deg of latitude is ~100 m anywhere; a degree of longitude shrinks with cos, so the
        // east/west arms need a wider step at 60 to stay the same distance out.
        //
        // The north approach is tagged with three lanes, because an untagged arm is one lane each
        // way and one lane has no spread to measure. `turn:lanes` is the only real lane count this
        // module can reach, so it is also the only fixture in which several connectors leave one
        // arm at several different offsets — which is exactly what this test needs to compare.
        let tagged = || (4u32, vec![LANE_LEFT, LANE_THROUGH, LANE_RIGHT]);
        let (_, at_equator) = stream(&crossroads_at(0, 9_000, &[tagged()]));
        let (_, at_sixty) = stream(&crossroads_at(600_000_000, 18_000, &[tagged()]));
        assert_eq!(at_equator.len(), 12);
        assert_eq!(at_sixty.len(), 12);

        // In projected units — what the renderer actually draws — the two must agree. z20 is an
        // arbitrary scale; Mercator is self-similar, so any zoom gives the same ratio.
        let mercator = |(lon, lat): (f64, f64)| tile_build::geom::project(lon, lat, 20);
        let projected_equator = spread(&at_equator, &mercator);
        let projected_sixty = spread(&at_sixty, &mercator);
        assert!(projected_equator > 0.0, "the lanes did not separate at all");
        assert!(
            (projected_sixty / projected_equator - 1.0).abs() < 0.02,
            "a lane must be the same projected width everywhere: {projected_equator} at the \
             equator vs {projected_sixty} at 60N",
        );

        // And in ground units they must differ by cos 60, which is what proves the correction is
        // being applied rather than the assertion above being vacuous. A degree of longitude is
        // `cos φ` shorter on the ground, and the north approach's offset is purely east-west.
        let ground = |(lon, lat): (f64, f64)| {
            (lon * METRES_PER_DEGREE * lat.to_radians().cos(), lat * METRES_PER_DEGREE)
        };
        let ground_ratio = spread(&at_sixty, &ground) / spread(&at_equator, &ground);
        assert!(
            (ground_ratio - 0.5).abs() < 0.02,
            "a lane's ground width should halve by 60N, not stay put: ratio {ground_ratio}",
        );
    }

    /// Which lane of the exit a connector lands in.
    ///
    /// The single-lane cases pass `rank` 0 of `siblings` 1, which is what a movement only one
    /// approach lane makes looks like, and reduce to "the exit's leftmost" or "its rightmost".
    #[test]
    fn a_turn_lands_in_the_exit_lane_its_direction_implies() {
        assert_eq!(exit_lane(Turn::Left, 0, 3, 2, 0, 1), 0, "a left turn takes the exit's left lane");
        assert_eq!(exit_lane(Turn::SharpLeft, 2, 3, 4, 0, 1), 0);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 3, 0, 1), 2, "a right turn takes the rightmost");
        assert_eq!(exit_lane(Turn::Through, 0, 2, 2, 0, 1), 0, "a through movement holds its place");
        assert_eq!(exit_lane(Turn::Through, 1, 2, 2, 0, 1), 1);
        // Narrowing: the outermost lane cannot land past the exit's last.
        assert_eq!(exit_lane(Turn::Through, 3, 4, 2, 0, 1), 1);
        // A zero-lane exit cannot underflow.
        assert_eq!(exit_lane(Turn::Right, 0, 1, 0, 0, 1), 0);
        // Several lanes making one movement rank across the exit rather than stacking on one lane;
        // `a_movement_shared_by_several_lanes_fills_the_exit_from_its_own_side` owns that rule.
        // What is pinned here is only that the rank cannot run off the far side of the road.
        assert_eq!(exit_lane(Turn::Left, 2, 3, 2, 2, 3), 1);
        assert_eq!(exit_lane(Turn::Right, 0, 3, 2, 0, 3), 0);
        assert_eq!(exit_lane(Turn::Left, 1, 3, 1, 1, 2), 0);
        assert_eq!(exit_lane(Turn::Right, 1, 3, 1, 0, 2), 0);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 1, 1, 2), 0);
    }

    /// A movement several approach lanes share stays several ribbons wide, which is the whole