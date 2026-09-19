#[cfg(test)]
mod tests2 {
    use super::*;
    use super::tests::{crossroads, stream};
    use crate::schema::traffic_extra::tests::{write_graph, EdgeSpec, GraphFixture};

    /// A movement several approach lanes share stays several ribbons wide, which is the whole
    /// reason [`exit_lane`] takes a rank at all: without it a dual left turn puts both its lanes on
    /// the exit's leftmost and draws one connector twice on top of itself.
    ///
    /// This pins the **direction each side fills from**, which the clamped cases above cannot: they
    /// all saturate, so they would still pass if left and right filled the same way. A dual left
    /// takes the exit's two leftmost lanes and a dual right its two rightmost, and the difference
    /// between those is the thing worth defending.
    #[test]
    fn a_movement_shared_by_several_lanes_fills_the_exit_from_its_own_side() {
        // Two left-turn lanes into a three-lane exit: the exit's left two, in order.
        assert_eq!(exit_lane(Turn::Left, 0, 3, 3, 0, 2), 0);
        assert_eq!(exit_lane(Turn::Left, 1, 3, 3, 1, 2), 1);
        // Two right-turn lanes into the same exit: the right two. Lanes 1 and 2, not 0 and 1 —
        // the outermost approach lane takes the outermost exit lane, and a right turn that fed
        // the exit's left lane would cross the traffic beside it.
        assert_eq!(exit_lane(Turn::Right, 1, 3, 3, 0, 2), 1);
        assert_eq!(exit_lane(Turn::Right, 2, 3, 3, 1, 2), 2);
        // The two sides genuinely disagree at the same rank, which is what makes the rule a rule
        // rather than a shared clamp.
        assert_ne!(
            exit_lane(Turn::Left, 0, 3, 3, 0, 2),
            exit_lane(Turn::Right, 1, 3, 3, 0, 2),
            "left and right must fill from opposite ends of the exit",
        );
    }

    /// `lanes.bin` is sparse, so absence is the answer for most edges and a missing file is not an
    /// error at all.
    #[test]
    fn a_sparse_lane_table_answers_only_for_the_edges_it_holds() {
        let fixture = crossroads(&[(1u32, vec![LANE_THROUGH]), (4u32, vec![LANE_LEFT, LANE_RIGHT])]);
        let table = LaneTable::load(&fixture.dir).expect("load").expect("a file was written");
        assert_eq!(table.masks(1), Some(vec![LANE_THROUGH]));
        assert_eq!(table.masks(4), Some(vec![LANE_LEFT, LANE_RIGHT]));
        assert_eq!(table.masks(0), None, "edge 0 is not in the index");
        assert_eq!(table.masks(3), None);
        assert_eq!(table.masks(99), None, "past every entry");

        let bare = crossroads(&[]);
        assert!(
            LaneTable::load(&bare.dir).expect("load").is_none(),
            "a graph built without lane data is not an error",
        );
    }

    /// The reverse index, which is the whole reason an approach can be found: `nodes.bin` is
    /// out-edges only.
    #[test]
    fn incoming_edges_are_recovered_from_a_graph_that_stores_only_outgoing_ones() {
        let fixture = crossroads(&[]);
        let graph = Graph::load(&fixture.dir).expect("graph");
        let incoming = InEdges::build(&graph).expect("reverse index");
        // Edges 4..7 all target the junction.
        assert_eq!(incoming.of(0), &[4, 5, 6, 7]);
        // Each outer node is fed by exactly the one edge leaving the junction toward it.
        assert_eq!(incoming.of(1), &[0]);
        assert_eq!(incoming.of(4), &[3]);
        // And every in-edge's source is recoverable from the same `edge_ptr` array.
        assert_eq!(source_of(&graph, 4), 1);
        assert_eq!(source_of(&graph, 7), 4);
        assert_eq!(source_of(&graph, 0), 0);
        assert_eq!(source_of(&graph, 3), 0);
    }

    /// **A divided highway's median U-turn slot, which is the case the neighbour test cannot see.**
    ///
    /// Node 1 is the approaching carriageway and node 2 the opposing one running alongside it, so a
    /// reversal at node 0 leaves toward a *different* node and survives
    /// `exit.neighbour != approach.neighbour`. The median is ~9 m, which puts the exit 5 degrees off
    /// due north and the movement at 175 degrees — inside [`classify_turn`]'s 170-degree reversal
    /// threshold, and a realistic width rather than one tuned to just clear it.
    fn divided_highway() -> GraphFixture {
        let coords: [(i32, i32); 4] = [
            (350_000_000, -1_200_000_000), // 0: the junction
            (350_009_000, -1_200_000_000), // 1: north, the carriageway traffic arrives on
            (350_009_000, -1_199_999_000), // 2: north, the opposing carriageway across the median
            (350_000_000, -1_199_988_000), // 3: east, so the node has three neighbours
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        write_graph(
            "junction_divided",
            &coords,
            &[edge(0, 2), edge(0, 3), edge(1, 0), edge(3, 0)],
            &[],
        )
    }

    /// **A reversal onto a different node is not a legal movement, and used to draw a fishhook.**
    ///
    /// `exit.neighbour != approach.neighbour` excludes the U-turn back the way you came and nothing
    /// else, so a 180-degree movement reaching a different node came through as legal. Its geometry
    /// is degenerate rather than merely ugly: with `f_out = -f_in` the four control points along
    /// the approach's forward axis are `-back`, `-0.45*back`, `-0.45*ahead`, `-ahead` — every one
    /// negative, so the whole curve stays behind the node and never enters the junction. At the
    /// usual 14 m setback its apex sits 8.2 m short of the node while it swings half a lane either
    /// side of the centreline: a flat hook doubling back along the approach.
    ///
    /// Pinned on both the count and the shape. The count alone would pass if the reversal were
    /// merely redirected somewhere, and the shape alone would pass if it were dropped for an
    /// unrelated reason.
    #[test]
    fn a_reversal_onto_a_different_node_is_not_emitted() {
        let (emitted, lines) = stream(&divided_highway());
        // Three movements reach the legality filter: from the north, the reversal onto the opposing
        // carriageway and the left onto the east arm; from the east, the right onto the opposing
        // carriageway. Only the reversal is dropped.
        assert_eq!(emitted, 2, "the reversal onto the opposing carriageway must not be emitted");
        assert_eq!(lines.len(), 2);

        // And no survivor doubles back. Both ends of the fishhook sat ~14 m up the approach; a
        // connector that genuinely crosses the junction has at most one end far up any single arm.
        // The threshold is well clear of the half-lane lateral offset a legitimate end carries.
        let north_m = |p: (f64, f64)| (p.1 - 35.0) * METRES_PER_DEGREE;
        for line in &lines {
            let (start, end) = (line[0], *line.last().expect("a connector has points"));
            assert!(
                !(north_m(start) > 5.0 && north_m(end) > 5.0),
                "a connector runs from {:.1} m to {:.1} m north of the junction, both ends up the \
                 same arm: it doubles back along the approach instead of crossing the node",
                north_m(start),
                north_m(end),
            );
        }
    }

    /// A one-way approach is the case [`InEdges`] exists for: the junction has no out-edge back
    /// toward it, so [`Graph::has_drivable_twin`] could never find it.
    #[test]
    fn a_one_way_approach_is_found_even_though_nothing_leaves_the_junction_toward_it() {
        let coords: [(i32, i32); 4] = [
            (350_000_000, -1_200_000_000), // 0: the junction
            (350_009_000, -1_200_000_000), // 1: north, feeds in one-way
            (350_000_000, -1_199_988_000), // 2: east
            (349_991_000, -1_200_000_000), // 3: south
        ];
        let edge = |source, target| EdgeSpec { source, target, type_: 7, interior: Vec::new() };
        let fixture = write_graph(
            "junction_oneway",
            &coords,
            &[
                // The junction leaves only east and south. Nothing runs back north.
                edge(0, 2),
                edge(0, 3),
                // The one-way approach, plus two-way arms so the node has three neighbours.
                edge(1, 0),
                edge(2, 0),
                edge(3, 0),
            ],
            &[],
        );
        let graph = Graph::load(&fixture.dir).expect("graph");
        assert!(
            !graph.has_drivable_twin(0, 1).expect("twin"),
            "the fixture is only meaningful if nothing leaves the junction northward",
        );
        let (emitted, _) = stream(&fixture);
        // Three approaches. From the north: east and south are both legal (2).
        // From the east: south only, since the U-turn back east is excluded and
        // nothing runs north (1). From the south: east only (1). One of the
        // four is a through movement and carries no connector.
        assert_eq!(emitted, 3, "the one-way approach contributes its two turns");
    }

    /// Left-hand traffic mirrors the connectors rather than leaving them on the wrong side.
    #[test]
    fn a_left_hand_traffic_country_gets_its_connectors_on_the_other_side() {
        let fixture = crossroads(&[]);
        let spill = fixture.dir.join("lht.tmp");

        let mut left = Conventions::default();
        // A ring around the fixture's coordinates, so its z6 cell is claimed for a left-hand
        // country. `add` takes lon/lat rings, outer first.
        left.add(
            "GB",
            &[vec![vec![
                (-121.0, 34.0),
                (-119.0, 34.0),
                (-119.0, 36.0),
                (-121.0, 36.0),
                (-121.0, 34.0),
            ]]],
        );

        let mut sink = Sink::create(&spill).expect("sink");
        stream_junctions(&fixture.dir, &left, &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");
        let mut reader = store.reader().expect("reader");
        let mut mirrored = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            if let Geometry::Lines(parts) = &feature.geometry {
                mirrored.push(parts[0].clone());
            }
        }
        let _ = std::fs::remove_file(&spill);

        let (_, right) = stream(&fixture);
        assert_eq!(mirrored.len(), right.len(), "the same movements exist either side of the world");
        assert_ne!(mirrored, right, "left-hand traffic must not draw the right-hand connectors");
    }
}
