//! Path reconstruction: direct-path shortcut, node-chain walk, start/main/end stubs.
use crate::geometry::*;
use crate::graph::*;
use crate::state::RoutingScratchpad;
use super::builder::StepBuilder;
use super::elevation::{edge_point_elevations, proj_elevation};
use super::model::{INVALID_NODE, StepData};
use super::super::search::{Direct, RoutingContext};

/// Total time of the searched route, including the stub from the last node to the
/// destination projection. [`u32::MAX`] when the search found nothing.
fn network_time_10ms(
    g: &Graph,
    traffic: &TrafficSpeeds,
    mode: i32,
    ctx: &RoutingContext,
    scratch: &mut RoutingScratchpad,
) -> u32 {
    if ctx.target_node == INVALID_NODE {
        return u32::MAX;
    }
    let reached = scratch.get_entry(ctx.target_node).g_fwd;
    // `g_fwd` stops at the node; the walk from there to the projection is the
    // part `reconstruct_path`'s end stub adds.
    let stub = if ctx.target_node == ctx.end.node_a {
        ctx.end.dist_a_mm
    } else {
        ctx.end.dist_b_mm
    };
    let stub_time = get_edge_time_10ms(
        g, traffic, INVALID_EDGE, stub, ctx.end.type_, ctx.end.speed_limit, mode,
    );
    reached.saturating_add(stub_time)
}

/// Turn a [`Direct`] route into steps, reusing the same coalescing the searched
/// path gets so the two are indistinguishable downstream.
fn direct_steps(g: &Graph, traffic: &TrafficSpeeds, mode: i32, d: &Direct) -> Vec<StepData> {
    let mut b = StepBuilder {
        g,
        traffic,
        mode,
        steps: Vec::new(),
        last_bearing: 0.0,
        pending_junction: INVALID_NODE,
        pending_prev: INVALID_NODE,
        pending_approach: INVALID_EDGE,
    };
    for (i, w) in d.coords.windows(2).enumerate() {
        let dist = fast_dist_mm(g, w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7);
        b.add_segment(
            f64::from(w[0].lat_e7) * 1e-7,
            f64::from(w[0].lon_e7) * 1e-7,
            f64::from(w[1].lat_e7) * 1e-7,
            f64::from(w[1].lon_e7) * 1e-7,
            d.name_offset,
            d.type_,
            d.speed_limit,
            dist,
            d.edge_idx,
            d.elevations[i],
            d.elevations[i + 1],
        );
    }
    b.steps
}

/// Rebuild the step list from `ctx.target_node`. Returns an empty vec if no
/// path exists.
pub fn reconstruct_path(
    g: &Graph,
    traffic: &TrafficSpeeds,
    mode: i32,
    ctx: &RoutingContext,
    scratch: &mut RoutingScratchpad,
) -> Vec<StepData> {
    // Staying on the edge both ends snapped to usually wins, but not always: a
    // C-shaped road whose two ends meet a straight one is faster left and
    // rejoined. Take whichever is quicker, and take the on-edge route outright
    // when the search found nothing at all.
    if let Some(d) = &ctx.direct {
        if d.time_10ms <= network_time_10ms(g, traffic, mode, ctx, scratch) {
            return direct_steps(g, traffic, mode, d);
        }
    }

    let mut path_nodes: Vec<u32> = Vec::new();
    let mut curr = ctx.target_node;
    let mut safety = 0u32;
    while curr != 0xFFFF_FFFF && safety < 1_000_000 {
        path_nodes.push(curr);
        curr = scratch.get_entry(curr).p_fwd;
        safety += 1;
    }
    path_nodes.reverse();
    if path_nodes.is_empty() {
        return Vec::new();
    }

    let mut b = StepBuilder {
        g,
        traffic,
        mode,
        steps: Vec::new(),
        last_bearing: 0.0,
        pending_junction: INVALID_NODE,
        pending_prev: INVALID_NODE,
        pending_approach: INVALID_EDGE,
    };

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];

    // 1. Start stub: proj_s -> path_nodes[0]
    {
        let n0 = path_nodes[0];
        let node0 = g.get_node(n0);
        let j = ctx.start.edge_idx;
        let geom = if j != INVALID_EDGE {
            g.get_edge_coordinates_from(ctx.start.node_a, j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.start.segment_idx;
            // Per-point elevation for the whole start edge (node_a -> node_b traversal order), and
            // the projection's own interpolated elevation where the route joins the edge.
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(ctx.start.node_a)),
                f64::from(g.node_elevation(ctx.start.node_b)),
            );
            let elev_proj = proj_elevation(
                g, ctx.start.node_a, ctx.start.node_b, ctx.start.dist_a_mm, ctx.start.dist_b_mm,
            );

            if n0 == ctx.start.node_a {
                let p_next = pts[seg_idx as usize];
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j,
                    elev_proj, elevs[seg_idx as usize],
                );
                let mut p = seg_idx as i32;
                while p >= 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[(p - 1) as usize];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[(p - 1) as usize],
                    );
                    p -= 1;
                }
            } else {
                let p_next = pts[seg_idx as usize + 1];
                let d1 = fast_dist_mm(
                    g, ctx.start.proj_lat, ctx.start.proj_lon, p_next.lat_e7, p_next.lon_e7,
                );
                b.add_segment(
                    ctx.start.proj_lat as f64 * 1e-7,
                    ctx.start.proj_lon as f64 * 1e-7,
                    p_next.lat_e7 as f64 * 1e-7,
                    p_next.lon_e7 as f64 * 1e-7,
                    ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d1, j,
                    elev_proj, elevs[seg_idx as usize + 1],
                );
                for p in seg_idx + 1..num_pts - 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[p as usize + 1];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[p as usize + 1],
                    );
                }
            }
        } else {
            let dist = if n0 == ctx.start.node_a {
                ctx.start.dist_a_mm
            } else {
                ctx.start.dist_b_mm
            };
            let elev_proj = proj_elevation(
                g, ctx.start.node_a, ctx.start.node_b, ctx.start.dist_a_mm, ctx.start.dist_b_mm,
            );
            b.add_segment(
                ctx.start.proj_lat as f64 * 1e-7,
                ctx.start.proj_lon as f64 * 1e-7,
                node0.lat_e7 as f64 * 1e-7,
                node0.lon_e7 as f64 * 1e-7,
                ctx.start.name_offset, ctx.start.type_, ctx.start.speed_limit, dist, INVALID_EDGE,
                elev_proj, f64::from(g.node_elevation(n0)),
            );
        }
    }

    // 2. Main path segments
    let mut prev_edge_idx = INVALID_EDGE;
    for i in 0..path_nodes.len() - 1 {
        let u = path_nodes[i];
        let v = path_nodes[i + 1];
        if u >= g.node_count {
            continue;
        }
        let node_u = g.node(u);
        let node_v = g.get_node(v);

        let (s, e_ptr) = g.edge_range(u);

        let mut best_e_idx = INVALID_EDGE;
        for k in s..e_ptr {
            if g.edge_targets(k, u, v) {
                best_e_idx = k;
                break;
            }
        }
        if best_e_idx == INVALID_EDGE {
            continue;
        }

        let e = g.edge(u, best_e_idx);
        let e_name = g.edge_name_offset(best_e_idx).unwrap_or(NO_NAME);
        let mut d = e.dist_mm;
        if d == 0 {
            d = accurate_dist_mm(node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7);
        }

        // Lane guidance is derived at the junction where this edge begins
        // (node u), excluding the node we arrived from. Set it just before the
        // edge's first segment; add_segment consumes and clears it so only the
        // maneuver segment picks up lanes. `pending_approach` is the edge the
        // driver is on reaching u (the previous main-path edge), which carries
        // the real OSM turn:lanes for the maneuver at u.
        let prev_node = if i > 0 { path_nodes[i - 1] } else { INVALID_NODE };
        b.pending_junction = u;
        b.pending_prev = prev_node;
        b.pending_approach = prev_edge_idx;
        prev_edge_idx = best_e_idx;

        if let Some((count, is_reversed)) = g
            .get_edge_coordinates_from(u, best_e_idx, &mut coords)
            .filter(|&(c, _)| c >= 2)
        {
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(u)),
                f64::from(g.node_elevation(v)),
            );
            for p in 0..count - 1 {
                let p1 = pts[p as usize];
                let p2 = pts[p as usize + 1];
                let seg_dist = fast_dist_mm(g, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                b.add_segment(
                    p1.lat_e7 as f64 * 1e-7, p1.lon_e7 as f64 * 1e-7,
                    p2.lat_e7 as f64 * 1e-7, p2.lon_e7 as f64 * 1e-7,
                    e_name, e.type_, e.speed_limit, seg_dist, best_e_idx,
                    elevs[p as usize], elevs[p as usize + 1],
                );
            }
        } else {
            b.add_segment(
                node_u.lat_e7 as f64 * 1e-7, node_u.lon_e7 as f64 * 1e-7,
                node_v.lat_e7 as f64 * 1e-7, node_v.lon_e7 as f64 * 1e-7,
                e_name, e.type_, e.speed_limit, d, best_e_idx,
                f64::from(g.node_elevation(u)), f64::from(g.node_elevation(v)),
            );
        }
    }

    // 3. End stub: path_nodes.back() -> proj_e
    {
        let nk = *path_nodes.last().unwrap();
        let nodek = g.get_node(nk);
        let j = ctx.end.edge_idx;
        let geom = if j != INVALID_EDGE {
            g.get_edge_coordinates_from(ctx.end.node_a, j, &mut coords)
        } else {
            None
        };

        if let Some((count, is_reversed)) = geom.filter(|&(c, _)| c >= 2) {
            let num_pts = count;
            let seg_idx = ctx.end.segment_idx;
            let pts: Vec<LatLon> =
                (0..count).map(|p| get_pt_at(&coords, count, is_reversed, p)).collect();
            let elevs = edge_point_elevations(
                g,
                &pts,
                f64::from(g.node_elevation(ctx.end.node_a)),
                f64::from(g.node_elevation(ctx.end.node_b)),
            );
            let elev_proj = proj_elevation(
                g, ctx.end.node_a, ctx.end.node_b, ctx.end.dist_a_mm, ctx.end.dist_b_mm,
            );

            if nk == ctx.end.node_a {
                for p in 0..seg_idx {
                    let p_from = pts[p as usize];
                    let p_to = pts[p as usize + 1];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[p as usize + 1],
                    );
                }
                let p_last = pts[seg_idx as usize];
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j,
                    elevs[seg_idx as usize], elev_proj,
                );
            } else {
                let mut p = num_pts as i32 - 1;
                while p > seg_idx as i32 + 1 {
                    let p_from = pts[p as usize];
                    let p_to = pts[(p - 1) as usize];
                    let d_seg = fast_dist_mm(g, p_from.lat_e7, p_from.lon_e7, p_to.lat_e7, p_to.lon_e7);
                    b.add_segment(
                        p_from.lat_e7 as f64 * 1e-7, p_from.lon_e7 as f64 * 1e-7,
                        p_to.lat_e7 as f64 * 1e-7, p_to.lon_e7 as f64 * 1e-7,
                        ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d_seg, j,
                        elevs[p as usize], elevs[(p - 1) as usize],
                    );
                    p -= 1;
                }
                let p_last = pts[seg_idx as usize + 1];
                let d2 = fast_dist_mm(g, p_last.lat_e7, p_last.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon);
                b.add_segment(
                    p_last.lat_e7 as f64 * 1e-7, p_last.lon_e7 as f64 * 1e-7,
                    ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                    ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, d2, j,
                    elevs[seg_idx as usize + 1], elev_proj,
                );
            }
        } else {
            let dist = if nk == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            let elev_proj = proj_elevation(
                g, ctx.end.node_a, ctx.end.node_b, ctx.end.dist_a_mm, ctx.end.dist_b_mm,
            );
            b.add_segment(
                nodek.lat_e7 as f64 * 1e-7, nodek.lon_e7 as f64 * 1e-7,
                ctx.end.proj_lat as f64 * 1e-7, ctx.end.proj_lon as f64 * 1e-7,
                ctx.end.name_offset, ctx.end.type_, ctx.end.speed_limit, dist, INVALID_EDGE,
                f64::from(g.node_elevation(nk)), elev_proj,
            );
        }
    }

    b.steps
}
