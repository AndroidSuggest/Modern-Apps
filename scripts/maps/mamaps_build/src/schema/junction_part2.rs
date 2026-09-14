/// Spread `lanes` across `exits`, both already in left→right order, as `(lane, exit)` pairs.
///
/// Whichever side is larger drives the pairing, so every lane gets a connector and every exit gets
/// fed. Two left-turn lanes onto one road give two connectors into the same exit, which is right —
/// a dual left turn is two ribbons. One lane onto a two-lane exit gives two connectors out of the
/// same lane, which is also right: the lane fans.
fn distribute(lanes: &[usize], exits: &[usize]) -> Vec<(usize, usize)> {
    if lanes.is_empty() || exits.is_empty() {
        return Vec::new();
    }
    if lanes.len() >= exits.len() {
        lanes
            .iter()
            .enumerate()
            .map(|(i, &lane)| (lane, exits[i * exits.len() / lanes.len()]))
            .collect()
    } else {
        exits
            .iter()
            .enumerate()
            .map(|(j, &exit)| (lanes[j * lanes.len() / exits.len()], exit))
            .collect()
    }
}

/// Read the v6 graph at `dir` and push one line feature per lane connector into `sink`. Returns the
/// number of connectors emitted.
///
/// `conventions` decides which side of a road's centreline the direction of travel sits on, so a
/// left-hand-traffic country's connectors are not mirrored. It is resolved from each junction's own
/// coordinate at [`super::boundaries::COARSE_ZOOM`], which is the granularity the grid holds.
///
/// Not clipped to a bounding box, for the same reason [`crate::schema::traffic::stream_graph`] is
/// not: the graph is already the built region and the tiler clips each connector per tile.
pub fn stream_junctions(dir: &Path, conventions: &Conventions, sink: &mut Sink) -> Result<u64> {
    let graph = Graph::load(dir)?;
    let lanes = LaneTable::load(dir)?;
    let incoming = InEdges::build(&graph)?;
    let class = junction_class();
    let mut emitted = 0u64;

    for n in 0..graph.node_count {
        let node = graph.node(n);
        let in_arms: Vec<Arm> = incoming
            .of(n)
            .iter()
            .filter_map(|&idx| {
                arm(&graph, lanes.as_ref(), idx, source_of(&graph, idx) as u32, n as u32, false)
            })
            .collect();
        let out_arms: Vec<Arm> = (graph.edge_ptr(n)..graph.edge_ptr(n + 1))
            .filter(|&idx| is_drivable(graph.edge_type(idx)))
            .filter_map(|idx| {
                let target = graph.edge_target(idx, n as u32).ok()?;
                arm(&graph, lanes.as_ref(), idx, n as u32, target, true)
            })
            .collect();
        if in_arms.is_empty() || out_arms.len() < 2 {
            continue;
        }
        // A junction is a node three or more distinct roads meet at. Degree-2 chains are collapsed
        // by the generator, so the survivors below this bar are dead ends, attribute breaks and
        // transit stops — none of which anything turns at.
        let degree: BTreeSet<u32> =
            in_arms.iter().chain(&out_arms).map(|a| a.neighbour).collect();
        if degree.len() < 3 {
            continue;
        }

        let (tx, ty) = tile_build::geom::project(
            f64::from(node.1) * 1e-7,
            f64::from(node.0) * 1e-7,
            super::boundaries::COARSE_ZOOM,
        );
        let left_hand = conventions
            .at_tile(
                super::boundaries::COARSE_ZOOM,
                tx.max(0.0) as u64,
                ty.max(0.0) as u64,
            )
            .left_hand;

        for approach in &in_arms {
            // Every exit but the one back the way you came. The graph carries no turn restrictions,
            // so this is the whole legality model: inference, and generous.
            let mut legal: Vec<(usize, f64, Turn)> = out_arms
                .iter()
                .enumerate()
                .filter(|(_, exit)| exit.neighbour != approach.neighbour)
                .map(|(i, exit)| {
                    let delta = normalise_degrees(exit.heading - approach.heading);
                    (i, delta, classify_turn(delta))
                })
                // A reversal is not a movement this layer draws, and the neighbour test above does
                // not catch it. On a divided highway the median slot reverses onto the *opposing
                // carriageway*, which is a different node, so it survives that filter and arrives
                // here as a legal 180-degree movement. The curve it produces never enters the
                // junction at all: with `f_out = -f_in` all four bezier control points sit behind
                // the node, so it draws as a flat hook doubling back along the approach.
                .filter(|(_, _, turn)| *turn != Turn::Reverse)
                .collect();
            if legal.is_empty() {
                continue;
            }
            // Left→right as the driver sees them, which is the order lane masks are stored in.
            legal.sort_by(|a, b| a.1.total_cmp(&b.1));

            // Both ends read the same function of their own arm, so neither can drift from the
            // other and neither depends on who is looking. No count is derived from the junction's
            // shape: `legal.len()` counts exits, not lanes, and using it as a width fanned the
            // endpoints across phantom lanes and off the carriageway.
            let approach_lanes = effective_lanes(approach);

            // `turn:lanes` where it exists, the arm's own lane count where it does not.
            let mut pairs: Vec<(usize, usize)> = match &approach.masks {
                Some(masks) => {
                    let mut pairs = Vec::new();
                    for turn in Turn::ALL {
                        let bit = turn.bit();
                        let claimants: Vec<usize> = masks
                            .iter()
                            .enumerate()
                            .filter(|(_, m)| {
                                // A lane whose only marking is "none" is treated as a through lane,
                                // which is what an unmarked lane at a junction means in practice.
                                **m & bit != 0
                                    || (turn == Turn::Through
                                        && (**m == 0 || **m == LANE_NONE))
                            })
                            .map(|(k, _)| k)
                            .collect();
                        if claimants.is_empty() {
                            continue;
                        }
                        pairs.extend(distribute(&claimants, &exits_for(turn, &legal)));
                    }
                    pairs
                }
                // Untagged, which is the overwhelming majority of roads. The arm's real lanes are
                // spread over its legal exits — and since an untagged arm has exactly one, every
                // movement leaves from lane 0 and the connectors share the arm's mouth. Assigning
                // a lane *index* per exit here was the other half of the fabricated width: it put
                // the third movement 1.5 lane widths out on a road with one lane.
                None => {
                    let lanes: Vec<usize> = (0..approach_lanes).collect();
                    let exits: Vec<usize> = legal.iter().map(|(i, _, _)| *i).collect();
                    distribute(&lanes, &exits)
                }
            };

            // Ordered so a movement's lanes rank left→right, and deduplicated because two tagged
            // indications can fall back to the same exit and would otherwise draw one connector
            // twice on top of itself.
            pairs.sort_unstable();
            pairs.dedup();

            for &(in_lane, exit_index) in &pairs {
                let exit = &out_arms[exit_index];
                let Some((_, _, turn)) = legal.iter().find(|(i, _, _)| *i == exit_index) else {
                    continue;
                };
                let out_lanes = effective_lanes(exit);
                // This lane's place among the lanes making the same movement into the same exit.
                let siblings: Vec<usize> =
                    pairs.iter().filter(|(_, e)| *e == exit_index).map(|&(l, _)| l).collect();
                let rank = siblings.iter().position(|&l| l == in_lane).unwrap_or(0);
                let out_lane =
                    exit_lane(*turn, in_lane, approach_lanes, out_lanes, rank, siblings.len());
                let Some(points) = connector(
                    node,
                    approach,
                    in_lane,
                    approach_lanes,
                    exit,
                    out_lane,
                    out_lanes,
                    *turn,
                    left_hand,
                ) else {
                    continue;
                };
                sink.push(&class, &Geometry::Lines(vec![points]))?;
                emitted += 1;
            }
        }
    }
    Ok(emitted)
}
