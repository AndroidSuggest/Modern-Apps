/// Lowest-numbered node on the cycle containing segment `si`, used to break an
/// anchorless ring deterministically.
fn cycle_anchor(si: usize, segs: &[Seg], inc: &Incidence) -> u32 {
    let mut best = segs[si].u.min(segs[si].v);
    let mut prev_seg = si as u32;
    let start = segs[si].u;
    let mut cur = segs[si].v;
    while cur != start {
        best = best.min(cur);
        let next = match inc.at(cur).find(|s| *s != prev_seg) {
            Some(s) => s,
            None => break,
        };
        cur = segs[next as usize].other(cur);
        prev_seg = next;
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A straight line of nodes 0.001 degrees apart, well inside the i16 delta
    /// range, so nothing in these tests is interpolated.
    fn pt(n: u32) -> geom::Pt {
        (370_000_000 + (n as i32) * 10_000, -1_220_000_000)
    }

    fn seg(u: u32, v: u32) -> Seg {
        Seg {
            u,
            v,
            dist_mm: 1_000,
            name_offset: 7,
            type_: 5,
            speed_limit: 50,
            oneway: false,
            fwd_lane_off: u32::MAX,
            fwd_lane_count: 0,
            bwd_lane_off: u32::MAX,
            bwd_lane_count: 0,
        }
    }

    fn run(n: u32, segs: &[Seg]) -> Compacted {
        compact(n, segs, &[], pt, |_| false)
    }

    #[test]
    fn a_straight_run_becomes_one_chain() {
        // 0 - 1 - 2 - 3 - 4, all interior except the ends.
        let segs: Vec<Seg> = (0..4).map(|i| seg(i, i + 1)).collect();
        let c = run(5, &segs);
        assert_eq!(c.kept, 2);
        assert_eq!(c.new_id[0], 0);
        assert_eq!(c.new_id[4], 1);
        assert_eq!(c.new_id[2], REMOVED);
        assert_eq!(c.chains.len(), 1);
        assert_eq!(c.chains[0].pts(&c.pts), &[0, 1, 2, 3, 4]);
        assert_eq!(c.chains[0].dist_mm, 4_000);
        assert!(!c.chains[0].oneway);
    }

    #[test]
    fn a_junction_stops_a_chain() {
        // 0 - 1 - 2 - 3 with a spur 2 - 4: node 2 has degree 3.
        let mut segs: Vec<Seg> = (0..3).map(|i| seg(i, i + 1)).collect();
        segs.push(seg(2, 4));
        let c = run(5, &segs);
        // 1 collapses, 2 survives as a junction.
        assert_eq!(c.kept, 4);
        assert_eq!(c.new_id[1], REMOVED);
        assert_ne!(c.new_id[2], REMOVED);
        let mut shapes: Vec<Vec<u32>> = c.chains.iter().map(|ch| ch.pts(&c.pts).to_vec()).collect();
        shapes.sort();
        assert_eq!(shapes, vec![vec![0, 1, 2], vec![2, 3], vec![2, 4]]);
    }

    #[test]
    fn differing_attributes_block_the_collapse() {
        for (label, mutate) in [
            ("type", (|s: &mut Seg| s.type_ = 9) as fn(&mut Seg)),
            ("speed", |s: &mut Seg| s.speed_limit = 30),
            ("name", |s: &mut Seg| s.name_offset = 99),
            ("oneway", |s: &mut Seg| s.oneway = true),
        ] {
            let mut segs = vec![seg(0, 1), seg(1, 2)];
            mutate(&mut segs[1]);
            let c = run(3, &segs);
            assert_eq!(c.kept, 3, "{label} should have kept node 1");
            assert_eq!(c.chains.len(), 2, "{label}");
        }
    }

    #[test]
    fn a_stop_node_is_never_collapsed() {
        let segs = vec![seg(0, 1), seg(1, 2)];
        let c = compact(3, &segs, &[], pt, |n| n == 1);
        assert_eq!(c.kept, 3);
        assert_eq!(c.chains.len(), 2);
    }

    #[test]
    fn one_ways_collapse_only_when_traffic_flows_through() {
        let mut through = vec![seg(0, 1), seg(1, 2)];
        for s in &mut through {
            s.oneway = true;
        }
        let c = run(3, &through);
        assert_eq!(c.kept, 2, "0->1->2 should collapse");
        assert!(c.chains[0].oneway);

        // Both pointing at node 1 makes it a sink, not a pass-through.
        let mut sink = vec![seg(0, 1), seg(2, 1)];
        for s in &mut sink {
            s.oneway = true;
        }
        let c = run(3, &sink);
        assert_eq!(c.kept, 3);
    }

    #[test]
    fn a_one_way_chain_keeps_its_direction_whichever_end_is_walked() {
        // Traffic flows 2 -> 1 -> 0, but the walk starts at node 0 because
        // anchors are visited in id order. The emitted chain must still run
        // 2 -> 0, or the road would be reversed on the map.
        let mut segs = vec![seg(1, 0), seg(2, 1)];
        for s in &mut segs {
            s.oneway = true;
        }
        let c = run(3, &segs);
        assert_eq!(c.kept, 2);
        assert_eq!(c.chains.len(), 1);
        assert_eq!(c.chains[0].pts(&c.pts), &[2, 1, 0]);

        // A single one-way segment between two junctions is the same rule with
        // no interior node to hide behind.
        let mut lone = vec![seg(1, 0), seg(1, 2), seg(2, 3)];
        lone[0].oneway = true;
        let c = run(4, &lone);
        let oneway: Vec<&Chain> = c.chains.iter().filter(|ch| ch.oneway).collect();
        assert_eq!(oneway.len(), 1);
        assert_eq!(oneway[0].pts(&c.pts), &[1, 0]);
    }

    #[test]
    fn a_one_way_chain_carries_the_right_lane_masks_after_reorientation() {
        // Forward lanes belong to the direction the way was drawn in. Walking
        // the chain backwards must not hand them to the wrong end.
        let pool = vec![0x4u16, 0x2, 0x4, 0x2];
        let mut segs = vec![seg(1, 0), seg(2, 1)];
        for s in &mut segs {
            s.oneway = true;
        }
        segs[0].fwd_lane_off = 0;
        segs[0].fwd_lane_count = 2;
        segs[1].fwd_lane_off = 2;
        segs[1].fwd_lane_count = 2;
        let c = compact(3, &segs, &pool, pt, |_| false);
        assert_eq!(c.chains.len(), 1);
        let ch = &c.chains[0];
        assert_eq!(ch.pts(&c.pts), &[2, 1, 0]);
        // The chain now starts at node 2, whose outgoing segment is segs[1].
        assert_eq!((ch.fwd_lane_off, ch.fwd_lane_count), (2, 2));
    }

    #[test]
    fn lane_masks_are_compared_by_content_not_offset() {
        // The same masks interned at two different pool offsets must still
        // collapse; genuinely different masks must not.
        let pool = vec![0x2u16, 0x4, 0x2, 0x4, 0x8, 0x8];
        let mut same = vec![seg(0, 1), seg(1, 2)];
        same[0].fwd_lane_off = 0;
        same[0].fwd_lane_count = 2;
        same[1].fwd_lane_off = 2;
        same[1].fwd_lane_count = 2;
        let c = compact(3, &same, &pool, pt, |_| false);
        assert_eq!(c.kept, 2, "identical masks at different offsets");
        assert_eq!(c.chains[0].fwd_lane_count, 2);

        let mut differ = vec![seg(0, 1), seg(1, 2)];
        differ[0].fwd_lane_off = 0;
        differ[0].fwd_lane_count = 2;
        differ[1].fwd_lane_off = 4;
        differ[1].fwd_lane_count = 2;
        let c = compact(3, &differ, &pool, pt, |_| false);
        assert_eq!(c.kept, 3, "different masks must block the collapse");
    }

    #[test]
    fn forward_and_backward_lanes_are_kept_apart() {
        // Turn lanes on the forward direction only, consistent along the chain:
        // this is the common case and must still collapse.
        let pool = vec![0x4u16, 0x2, 0x4, 0x2];
        let mut segs = vec![seg(0, 1), seg(1, 2)];
        segs[0].fwd_lane_off = 0;
        segs[0].fwd_lane_count = 2;
        segs[1].fwd_lane_off = 2;
        segs[1].fwd_lane_count = 2;
        let c = compact(3, &segs, &pool, pt, |_| false);
        assert_eq!(c.kept, 2);
        assert_eq!(c.chains[0].fwd_lane_count, 2);
        assert_eq!(c.chains[0].bwd_lane_count, 0);
    }

    #[test]
    fn parallel_segments_between_the_same_pair_are_left_alone() {
        // Two ways from 0 to 1 make node 0 and node 1 degree 2, but their
        // neighbour sets are {1} and {0}: not a chain.
        let segs = vec![seg(0, 1), seg(0, 1)];
        let c = run(2, &segs);
        assert_eq!(c.kept, 2);
        assert_eq!(c.chains.len(), 2);
    }

    #[test]
    fn an_anchorless_ring_keeps_one_node() {
        // 0 - 1 - 2 - 3 - 0, every node degree 2 and eligible.
        let segs = vec![seg(0, 1), seg(1, 2), seg(2, 3), seg(3, 0)];
        let c = run(4, &segs);
        assert_eq!(c.kept, 1, "the ring must keep exactly one node");
        assert_eq!(c.new_id[0], 0);
        assert_eq!(c.chains.len(), 1);
        let pts = c.chains[0].pts(&c.pts);
        assert_eq!(pts[0], 0);
        assert_eq!(pts[pts.len() - 1], 0, "the chain closes on its anchor");
        assert_eq!(pts.len(), 5);
        assert_eq!(c.chains[0].dist_mm, 4_000);
    }

    #[test]
    fn a_ring_hanging_off_a_junction_closes_on_it() {
        // 0 - 1 (spur), then the ring 1 - 2 - 3 - 1.
        let segs = vec![seg(0, 1), seg(1, 2), seg(2, 3), seg(3, 1)];
        let c = run(4, &segs);
        // Node 1 has degree 3, so it anchors both the spur and the ring.
        assert_eq!(c.kept, 2);
        let mut shapes: Vec<Vec<u32>> = c.chains.iter().map(|ch| ch.pts(&c.pts).to_vec()).collect();
        shapes.sort();
        assert_eq!(shapes, vec![vec![0, 1], vec![1, 2, 3, 1]]);
    }

    #[test]
    fn every_segment_is_consumed_exactly_once() {
        // A shape with a junction, a spur, a ring and a dead end.
        let segs = vec![
            seg(0, 1),
            seg(1, 2),
            seg(2, 3),
            seg(3, 4),
            seg(4, 2),
            seg(1, 5),
            seg(5, 6),
        ];
        let c = run(7, &segs);
        let total: usize = c.chains.iter().map(|ch| ch.pts_len as usize - 1).sum();
        assert_eq!(total, segs.len(), "chains must cover every segment once");
        let dist: u64 = c.chains.iter().map(|ch| u64::from(ch.dist_mm)).sum();
        assert_eq!(dist, 7_000);
    }

    #[test]
    fn a_chain_too_long_to_encode_is_split() {
        // Nodes 0.02 degrees apart: 200_000 e7 units, about 7 encoded points per
        // segment, so ~36 segments fill one edge's 256-point budget.
        let far = |n: u32| (370_000_000 + (n as i32) * 200_000, -1_220_000_000);
        let segs: Vec<Seg> = (0..80).map(|i| seg(i, i + 1)).collect();
        let c = compact(81, &segs, &[], far, |_| false);
        assert!(c.splits >= 2, "expected splits, got {}", c.splits);
        assert!(c.chains.len() >= 3);
        // Every chain must be encodable, and the runs must tile the path.
        let mut covered = 0usize;
        for ch in &c.chains {
            let pts: Vec<geom::Pt> = ch.pts(&c.pts).iter().map(|n| far(*n)).collect();
            assert!(
                pts.len() == 2 || geom::fits(&pts),
                "chain of {} points does not fit",
                pts.len()
            );
            covered += ch.pts_len as usize - 1;
            // A split boundary must be a surviving node.
            assert_ne!(c.new_id[ch.pts(&c.pts)[0] as usize], REMOVED);
            assert_ne!(c.new_id[*ch.pts(&c.pts).last().unwrap() as usize], REMOVED);
        }
        assert_eq!(covered, segs.len());
        let dist: u64 = c.chains.iter().map(|ch| u64::from(ch.dist_mm)).sum();
        assert_eq!(dist, 80_000, "splitting must not lose distance");
    }

    #[test]
    fn a_single_unencodable_segment_survives_as_its_own_chain() {
        // 30 degrees apart: one segment needs ~9155 encoded points, far past the
        // ceiling, so it must be emitted alone and left without geometry.
        let far = |n: u32| (100_000_000 + (n as i32) * 300_000_000, 0);
        let segs = vec![seg(0, 1), seg(1, 2)];
        let c = compact(3, &segs, &[], far, |_| false);
        let total: usize = c.chains.iter().map(|ch| ch.pts_len as usize - 1).sum();
        assert_eq!(total, 2);
        for ch in &c.chains {
            assert_eq!(ch.pts_len, 2, "each run must be a bare chord");
        }
    }

    #[test]
    fn a_degree_zero_node_is_dropped_unless_it_carries_a_stop() {
        // Node 2 has no incident segment: a way the region filter removed, dead
        // weight with no edge ever addressing it.
        let segs = vec![seg(0, 1)];
        let c = run(3, &segs);
        assert_eq!(c.kept, 2);
        assert_eq!(c.new_id[2], REMOVED);
        assert_eq!(c.chains.len(), 1);
        // An isolated transit stop is the exception: the reconnect pass
        // addresses stop nodes directly.
        let c = compact(3, &segs, &[], pt, |n| n == 2);
        assert_eq!(c.kept, 3);
        assert_eq!(c.new_id[2], 2);
        assert_eq!(c.chains.len(), 1);
    }
}
