//! Edge snapping, A* search, and the on-edge direct path.
use crate::geometry::*;
use crate::graph::*;
use crate::state::{RadixHeap, RoutingScratchpad};
use super::steps::{edge_point_elevations, proj_elevation};

pub struct SnappedEdge {
    pub node_a: u32,
    pub node_b: u32,
    pub proj_lat: i32,
    pub proj_lon: i32,
    pub dist_a_mm: u32,
    pub dist_b_mm: u32,
    pub type_: u8,
    pub speed_limit: u8,
    pub name_offset: u32,
    pub edge_idx: u64,
    pub segment_idx: u32,
}

impl SnappedEdge {
    fn empty(proj_lat: i32, proj_lon: i32) -> SnappedEdge {
        SnappedEdge {
            node_a: 0xFFFF_FFFF,
            node_b: 0xFFFF_FFFF,
            proj_lat,
            proj_lon,
            dist_a_mm: 0,
            dist_b_mm: 0,
            type_: 0,
            speed_limit: 0,
            name_offset: 0xFFFF_FFFF,
            edge_idx: INVALID_EDGE,
            segment_idx: 0,
        }
    }
}

pub struct RoutingContext {
    pub start: SnappedEdge,
    pub end: SnappedEdge,
    pub target_node: u32,
    pub iterations: i32,
    /// A route that stays on the edge it started on, when both ends snapped to
    /// the same road. See [`direct_path`].
    pub direct: Option<Direct>,
}

/// A route from the start projection to the end projection that never leaves the
/// edge they both snapped to.
///
/// Without this, a trip along one road is forced out to a junction and back,
/// because the A* search can only start and finish at nodes: it is seeded at the
/// start edge's two endpoints and stops at one of the end edge's two endpoints,
/// and on a single edge those are the *same* two nodes. The search therefore
/// "succeeds" immediately at a shared endpoint and the reconstruction walks from
/// the start projection back to that endpoint and forward again.
///
/// Uncompacted, an edge was one pair of adjacent OSM vertices — a few metres —
/// so the detour was invisible. Once degree-2 chains collapse, an edge is a whole
/// road between junctions, and a 20 m walk became a 2 km round trip. Measured on
/// the SLO fixture, 63 of 80 short probes were affected, the worst going from 0 m
/// to 2 km.
pub struct Direct {
    /// Start projection to end projection along the road, inclusive.
    pub coords: Vec<LatLon>,
    /// Ground elevation in metres parallel to [`Direct::coords`].
    pub elevations: Vec<f64>,
    pub dist_mm: u32,
    pub time_10ms: u32,
    pub name_offset: u32,
    /// Road class with any flag bits already masked off.
    pub type_: u8,
    pub speed_limit: u8,
    /// The directed edge actually travelled, for traffic lookup.
    pub edge_idx: u64,
}

/// Snap a WGS84 point to the nearest routable edge for `mode`.
pub fn find_nearest_edge(g: &Graph, lat: f64, lon: f64, mode: i32) -> SnappedEdge {
    let target_spatial = Graph::latlng_to_spatial(lat, lon);
    let p_lat = (lat * 1e7) as i32;
    let p_lon = (lon * 1e7) as i32;
    let mut best = SnappedEdge::empty(p_lat, p_lon);

    if g.node_count == 0 {
        return best;
    }

    let mut min_snap_dist: u32 = 0xFFFF_FFFF;

    // Binary search the globally Morton-sorted node array for the node just
    // below the target code.
    let mut low: u32 = 0;
    let mut high: u32 = g.node_count - 1;
    let mut local_center: u32 = 0;
    while low <= high {
        let mid = low + (high - low) / 2;
        if Graph::node_spatial_id(&g.node(mid)) < target_spatial {
            local_center = mid;
            low = mid + 1;
        } else {
            if mid == 0 {
                break;
            }
            high = mid - 1;
        }
    }

    let mut coords = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];

    const WINDOW: i32 = 800;
    let lo = std::cmp::max(0, local_center as i32 - WINDOW);
    let hi = std::cmp::min(g.node_count as i32 - 1, local_center as i32 + WINDOW);
    for i in lo..=hi {
        let u_global = i as u32;
        let node_u = g.node(u_global);
        let (e_start, e_ptr) = g.edge_range(u_global);
        for j in e_start..e_ptr {
            let e = g.edge(u_global, j);
            if !is_mode_allowed(e.type_, mode) {
                continue;
            }
            if e.target >= g.node_count {
                continue;
            }

            if let Some((count, is_reversed)) = g.get_edge_coordinates_from(u_global, j, &mut coords)
            {
                if count >= 2 {
                    let num_pts = count;
                    let mut current_dist_from_start_mm: u32 = 0;
                    for p in 0..num_pts - 1 {
                        let p1 = get_pt_at(&coords, count, is_reversed, p);
                        let p2 = get_pt_at(&coords, count, is_reversed, p + 1);

                        let proj =
                            get_projection(g, p_lat, p_lon, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                        if proj.dist_mm < min_snap_dist {
                            min_snap_dist = proj.dist_mm;
                            best.node_a = u_global;
                            best.node_b = e.target;
                            best.proj_lat = proj.lat_e7;
                            best.proj_lon = proj.lon_e7;
                            best.type_ = e.type_;
                            best.speed_limit = e.speed_limit;
                            best.name_offset = g.edge_name_offset(j).unwrap_or(NO_NAME);
                            best.edge_idx = j;
                            best.segment_idx = p;

                            let dist_to_proj_seg_mm =
                                fast_dist_mm(g, p1.lat_e7, p1.lon_e7, proj.lat_e7, proj.lon_e7);
                            best.dist_a_mm = current_dist_from_start_mm + dist_to_proj_seg_mm;
                            best.dist_b_mm = e.dist_mm.saturating_sub(best.dist_a_mm);
                        }
                        current_dist_from_start_mm +=
                            fast_dist_mm(g, p1.lat_e7, p1.lon_e7, p2.lat_e7, p2.lon_e7);
                    }
                    continue;
                }
            }

            // No geometry: snap to the straight node-to-node segment.
            let node_v = g.node(e.target);
            let p = get_projection(
                g, p_lat, p_lon, node_u.lat_e7, node_u.lon_e7, node_v.lat_e7, node_v.lon_e7,
            );
            if p.dist_mm < min_snap_dist {
                min_snap_dist = p.dist_mm;
                best.node_a = u_global;
                best.node_b = e.target;
                best.proj_lat = p.lat_e7;
                best.proj_lon = p.lon_e7;
                best.dist_a_mm = fast_dist_mm(g, p.lat_e7, p.lon_e7, node_u.lat_e7, node_u.lon_e7);
                best.dist_b_mm = fast_dist_mm(g, p.lat_e7, p.lon_e7, node_v.lat_e7, node_v.lon_e7);
                best.type_ = e.type_;
                best.speed_limit = e.speed_limit;
                best.name_offset = g.edge_name_offset(j).unwrap_or(NO_NAME);
                best.edge_idx = j;
                best.segment_idx = 0;
            }
        }
    }
    best
}

/// Snap endpoints, seed the open set. Returns `None` if snapping fails.
#[allow(clippy::too_many_arguments)]
pub fn prepare_routing(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    s_lat: f64,
    s_lon: f64,
    e_lat: f64,
    e_lon: f64,
    mode: i32,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) -> Option<RoutingContext> {
    scratch.reset();
    heap.clear();

    let start = find_nearest_edge(g, s_lat, s_lon, mode);
    let end = find_nearest_edge(g, e_lat, e_lon, mode);
    if start.node_a == 0xFFFF_FFFF || end.node_a == 0xFFFF_FFFF {
        return None;
    }

    if mode == DRIVING {
        ensure_traffic(start.proj_lat, start.proj_lon);
    }

    let push = |node: u32, travel_dist_mm: u32, scratch: &mut RoutingScratchpad, heap: &mut RadixHeap| {
        let t_actual = get_edge_time_10ms(
            g, traffic, INVALID_EDGE, travel_dist_mm, start.type_, start.speed_limit, mode,
        );
        {
            let entry = scratch.get_entry(node);
            entry.g_fwd = t_actual;
            entry.g_bwd = t_actual;
        }
        let n_data = g.get_node(node);
        let h = heuristic_time_10ms(g, n_data.lat_e7, n_data.lon_e7, end.proj_lat, end.proj_lon, mode);
        heap.push(t_actual.wrapping_add(h), node);
    };
    push(start.node_a, start.dist_a_mm, scratch, heap);
    push(start.node_b, start.dist_b_mm, scratch, heap);

    let direct = direct_path(g, traffic, &start, &end, mode);

    Some(RoutingContext {
        start,
        end,
        target_node: 0xFFFF_FFFF,
        iterations: 0,
        direct,
    })
}

/// Polyline of `edge_idx` in source-to-target order, falling back to the straight
/// chord when the edge stores no geometry.
fn edge_polyline(g: &Graph, edge_idx: u64, source: u32, target: u32) -> Vec<LatLon> {
    let mut buf = [LatLon { lat_e7: 0, lon_e7: 0 }; 256];
    if let Some((count, is_reversed)) = g.get_edge_coordinates_from(source, edge_idx, &mut buf) {
        if count >= 2 {
            return (0..count).map(|p| get_pt_at(&buf, count, is_reversed, p)).collect();
        }
    }
    let a = g.get_node(source);
    let b = g.get_node(target);
    vec![
        LatLon { lat_e7: a.lat_e7, lon_e7: a.lon_e7 },
        LatLon { lat_e7: b.lat_e7, lon_e7: b.lon_e7 },
    ]
}

/// The other direction of the same road: the *only* edge from `target` back to
/// `source`, or `None` when there is none or more than one.
///
/// Uniqueness is required, not incidental. `graph.rs` resolves
/// [`REVERSE_GEOMETRY_FLAG`] by taking the first such edge, and the generator's
/// `twin_is_unique` refuses to set the flag unless exactly one exists — so
/// accepting the first match here would let a *parallel but different* road
/// between the same two junctions pass as the twin, and its name, type and speed
/// limit would then be attached to a polyline belonging to the other road. The
/// synthetic transit-stop connectors make that shape real: the graph fixture has
/// two edges from one node to another, a collapsed street and a one-way service
/// road.
///
/// A self-loop (an anchorless ring collapsed to one node) would otherwise match
/// itself and so claim that its own reverse direction exists.
fn twin_edge(g: &Graph, source: u32, target: u32) -> Option<u64> {
    if target >= g.node_count || source == target {
        return None;
    }
    let (s, e) = g.edge_range(target);
    let mut found = None;
    for k in s..e {
        if g.edge_targets(k, target, source) {
            if found.is_some() {
                return None;
            }
            found = Some(k);
        }
    }
    found
}

/// Where a point sits along `poly`: `(segment index, distance from the start)`.
/// The point is expected to already lie on the polyline, so the nearest segment
/// is unambiguous.
fn locate_on(g: &Graph, poly: &[LatLon], lat_e7: i32, lon_e7: i32) -> (usize, u32) {
    let mut best = (0usize, 0u32);
    let mut best_off = u32::MAX;
    let mut acc: u32 = 0;
    for i in 0..poly.len() - 1 {
        let (a, b) = (poly[i], poly[i + 1]);
        let p = get_projection(g, lat_e7, lon_e7, a.lat_e7, a.lon_e7, b.lat_e7, b.lon_e7);
        if p.dist_mm < best_off {
            best_off = p.dist_mm;
            best = (i, acc.saturating_add(fast_dist_mm(g, a.lat_e7, a.lon_e7, p.lat_e7, p.lon_e7)));
        }
        acc = acc.saturating_add(fast_dist_mm(g, a.lat_e7, a.lon_e7, b.lat_e7, b.lon_e7));
    }
    best
}

/// The stretch of `poly` between two located points, endpoints included. Always
/// at least two points: when both project onto the same segment the result is
/// just the two projections, which for coincident ends is a valid zero-length
/// route.
fn sub_polyline(poly: &[LatLon], from: (usize, LatLon), to: (usize, LatLon)) -> Vec<LatLon> {
    let mut out = vec![from.1];
    for p in poly.iter().take(to.0 + 1).skip(from.0 + 1) {
        out.push(*p);
    }
    out.push(to.1);
    out
}

/// Build the on-edge route when both ends snapped to the same road, or `None`
/// when they did not or when travelling it in the required direction is not
/// allowed.
fn direct_path(
    g: &Graph,
    traffic: &TrafficSpeeds,
    start: &SnappedEdge,
    end: &SnappedEdge,
    mode: i32,
) -> Option<Direct> {
    if start.edge_idx == INVALID_EDGE || end.edge_idx == INVALID_EDGE {
        return None;
    }
    let twin = twin_edge(g, start.node_a, start.node_b);
    // Same directed edge, or the one unambiguous other direction of one road.
    // Anything else — including a second, parallel road between the same pair of
    // nodes — is left to the search, because its polyline is a different road.
    if end.edge_idx != start.edge_idx && Some(end.edge_idx) != twin {
        return None;
    }

    let poly = edge_polyline(g, start.edge_idx, start.node_a, start.node_b);
    if poly.len() < 2 {
        return None;
    }
    let s_pt = LatLon { lat_e7: start.proj_lat, lon_e7: start.proj_lon };
    let e_pt = LatLon { lat_e7: end.proj_lat, lon_e7: end.proj_lon };
    let s_at = locate_on(g, &poly, s_pt.lat_e7, s_pt.lon_e7);
    let e_at = locate_on(g, &poly, e_pt.lat_e7, e_pt.lon_e7);

    // Going backwards along the polyline means driving the twin, which only
    // exists when the road is not one-way. The twin leaves the *other* end, so the
    // source has to be chosen with the edge, not recovered afterwards.
    let forward = s_at <= e_at;
    let (edge_idx, edge_source) = if forward {
        (start.edge_idx, start.node_a)
    } else {
        (twin?, start.node_b)
    };
    let e = g.edge(edge_source, edge_idx);
    if !is_mode_allowed(e.type_, mode) {
        return None;
    }

    let coords = if forward {
        sub_polyline(&poly, (s_at.0, s_pt), (e_at.0, e_pt))
    } else {
        let mut c = sub_polyline(&poly, (e_at.0, e_pt), (s_at.0, s_pt));
        c.reverse();
        c
    };

    let mut dist_mm: u32 = 0;
    for w in coords.windows(2) {
        dist_mm = dist_mm
            .saturating_add(fast_dist_mm(g, w[0].lat_e7, w[0].lon_e7, w[1].lat_e7, w[1].lon_e7));
    }
    let type_ = e.type_ & ROAD_TYPE_MASK;
    // Elevation ramps from the start projection to the end projection: each is itself an
    // interpolation of the edge's two node elevations at its own distance along the road, and the
    // interior coords ride the cumulative-distance ramp between them.
    let elev_s = proj_elevation(g, start.node_a, start.node_b, start.dist_a_mm, start.dist_b_mm);
    let elev_e = proj_elevation(g, end.node_a, end.node_b, end.dist_a_mm, end.dist_b_mm);
    let elevations = edge_point_elevations(g, &coords, elev_s, elev_e);
    Some(Direct {
        time_10ms: get_edge_time_10ms(g, traffic, edge_idx, dist_mm, type_, e.speed_limit, mode),
        coords,
        elevations,
        dist_mm,
        name_offset: g.edge_name_offset(edge_idx).unwrap_or(NO_NAME),
        type_,
        speed_limit: e.speed_limit,
        edge_idx,
    })
}

/// A* main loop. Fills `ctx.target_node` on success.
pub fn perform_search_loop(
    g: &Graph,
    traffic: &TrafficSpeeds,
    ensure_traffic: &mut dyn FnMut(i32, i32),
    mode: i32,
    ctx: &mut RoutingContext,
    scratch: &mut RoutingScratchpad,
    heap: &mut RadixHeap,
) {
    // Cap is a safety net only. The 36M-node CA graph needs ~6.4M expansions for
    // the longest routes (SF->LA), so the old 1M cap aborted them ("no route").
    // Reaching one end of the destination's edge is not the same as arriving.
    // The search can only stop at nodes, so it stops at `end.node_a` or
    // `end.node_b` and `reconstruct_path` then walks the leftover stub to the
    // projection. Stopping at whichever anchor is reached *first* ignores how
    // long that stub is, and after compaction the two anchors are a whole road
    // apart rather than a few metres, so the wrong choice costs the length of
    // the street. Score both completions and keep the better one.
    let mut best_total = u32::MAX;
    while !heap.empty() && ctx.iterations < 25_000_000 {
        ctx.iterations += 1;
        let u = heap.pop();

        let u_cost = scratch.get_entry(u).g_fwd;
        if u >= g.node_count {
            continue;
        }
        let n_u = g.node(u);

        // `f` is a lower bound on any route through `u`, and the heap pops in
        // non-decreasing `f` order, so once it cannot beat a completed route
        // nothing left can.
        let f_u = u_cost.saturating_add(heuristic_time_10ms(
            g, n_u.lat_e7, n_u.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode,
        ));
        if f_u >= best_total {
            break;
        }

        if u == ctx.end.node_a || u == ctx.end.node_b {
            let stub = if u == ctx.end.node_a {
                ctx.end.dist_a_mm
            } else {
                ctx.end.dist_b_mm
            };
            let total = u_cost.saturating_add(get_edge_time_10ms(
                g, traffic, INVALID_EDGE, stub, ctx.end.type_, ctx.end.speed_limit, mode,
            ));
            if total < best_total {
                best_total = total;
                ctx.target_node = u;
            }
            // Keep expanding: this anchor may also be on the way to the other.
        }

        if mode == DRIVING {
            ensure_traffic(n_u.lat_e7, n_u.lon_e7);
        }
        let (s, e_ptr) = g.edge_range(u);

        for i in s..e_ptr {
            let edge = g.edge(u, i);
            if !is_mode_allowed(edge.type_, mode) {
                continue;
            }

            let travel_time =
                get_edge_time_10ms(g, traffic, i, edge.dist_mm, edge.type_, edge.speed_limit, mode);

            let v = edge.target;
            let new_g = u_cost.wrapping_add(travel_time);
            let update = {
                let entry_v = scratch.get_entry(v);
                if new_g < entry_v.g_fwd {
                    entry_v.g_fwd = new_g;
                    entry_v.g_bwd = new_g;
                    entry_v.p_fwd = u;
                    true
                } else {
                    false
                }
            };
            if update {
                let n_v = g.get_node(v);
                let h = heuristic_time_10ms(
                    g, n_v.lat_e7, n_v.lon_e7, ctx.end.proj_lat, ctx.end.proj_lon, mode,
                );
                heap.push(new_g.wrapping_add(h), v);
            }
        }
    }
}
