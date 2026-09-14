/// Scheduled (timetable) departure of `trip` at `pos` in the query day's frame,
/// ignoring realtime. `None` when `prev_day` is set but the trip does not
/// actually run into the query day — i.e. its GTFS time is below `24:00:00`, so
/// it belongs wholly to yesterday and must not be considered at all.
fn trip_sched_dep(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trip: &TripDec,
    pos: u32,
    prev_day: bool,
) -> Option<u32> {
    let dep_rel = {
        let p = get_profile(cache, idx, trip.profile_id);
        p.dep_rel.get(pos as usize).copied().unwrap_or(0)
    };
    let t = trip.start_time as i64 + dep_rel as i64
        - if prev_day { SECS_PER_DAY as i64 } else { 0 };
    if t < 0 {
        return None;
    }
    Some(t as u32)
}

/// Relax one-hop footpath transfers from each `seed` stop, appending any stop
/// whose best arrival improves into `out`.
fn relax_transfers(
    idx: &TransitIndex,
    reach: &mut Arrivals,
    seeds: &[u32],
    out: &mut Vec<u32>,
) {
    for &s in seeds {
        let base_t = reach.at(s);
        if base_t == u32::MAX {
            continue;
        }
        let (ts, te) = idx.transfers_range(s);
        for i in ts..te {
            let tr = idx.transfer(i);
            let arr = base_t.saturating_add(tr.secs);
            if reach.improve(tr.to_stop, arr, Reached::Walk { from_stop: s }) {
                out.push(tr.to_stop);
            }
        }
    }
}

/// Earliest trip on this route departing stop-position `pos` no earlier than
/// `ready`, whose service runs on `day` and which realtime hasn't cancelled.
/// Times are in the query day's frame and include any realtime delay.
fn earliest_trip(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    route_idx: u32,
    trips: &Trips,
    pos: u32,
    ready: u32,
    sched: Schedule,
) -> Option<Boarding> {
    let mut best: Option<Boarding> = None;
    let mut best_dep = u32::MAX;
    // Trips are written start-time sorted and every profile offset is >= 0, so
    // `dep >= start_time - max_early`. Once start_time passes that bound, no
    // later trip on this day can improve on `best_dep`. Exact, not a heuristic:
    // with no realtime the slack is 0, and realtime can only pull a departure
    // earlier by `max_early_secs`.
    let slack = sched.max_early_secs();
    // Previous-day trips first: theirs are the earliest times in this frame.
    for prev_day in [true, false] {
        // A previous-day trip departs at `start_time + rel - SECS_PER_DAY` in this
        // frame, so its `start_time` has to clear the bound by a whole day. The
        // day belongs in the bound rather than disabling the break: exempting the
        // pass made it an unconditional scan of every trip on the route, each with
        // a `service_runs` binary search.
        let day_shift = if prev_day { SECS_PER_DAY } else { 0 };
        for i in 0..trips.len() {
            let Some(td) = trips.get(i) else { break };
            if best.is_some()
                && td.start_time >= best_dep.saturating_add(slack).saturating_add(day_shift)
            {
                break;
            }
            if !sched.runs(idx, td.service_idx, prev_day) {
                continue;
            }
            // A previous-day trip only counts if it actually runs into the query
            // day (a GTFS time at or past 24:00:00).
            let Some(scheduled) = trip_sched_dep(idx, cache, &td, pos, prev_day) else {
                continue;
            };
            let adj = sched.adjustment(route_idx, pos, scheduled);
            if adj.is_some_and(|a| a.cancelled) {
                continue;
            }
            let delay_secs = adj.map_or(0, |a| a.delay_secs);
            let dep = delayed(scheduled, delay_secs);
            if dep >= ready && dep < best_dep {
                best_dep = dep;
                best = Some(Boarding { trip: i, prev_day, delay_secs });
            }
        }
    }
    best
}

fn make_transit_leg(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    route_idx: u32,
    boarding: Boarding,
    board_stop: u32,
    alight_stop: u32,
) -> TransitLeg {
    let route = idx.route(route_idx);
    let trips = idx.trips_for(route_idx, &route);
    let td = trips.get(boarding.trip).unwrap_or(TripDec {
        start_time: 0,
        profile_id: 0,
        service_idx: 0,
        headsign_off: NONE,
    });
    // Find board/alight positions along the route. This keeps the linear scan
    // (and its last-match semantics) rather than using STOP_ROUTE_POS, which
    // records the *first* occurrence — for a route that visits a stop twice the
    // two differ, and this runs once per leg, not in the RAPTOR hot loop.
    let mut board_pos = 0u32;
    let mut alight_pos = 0u32;
    for pos in 0..route.n_stops {
        let s = idx.route_stop(route.first_route_stop + pos);
        if s == board_stop {
            board_pos = pos;
        }
        if s == alight_stop {
            alight_pos = pos;
        }
    }
    let (dep, arr) = {
        let p = get_profile(cache, idx, td.profile_id);
        let dep = abs_time_on(
            td.start_time,
            p.dep_rel.get(board_pos as usize).copied().unwrap_or(0),
            boarding.prev_day,
        );
        let arr = abs_time_on(
            td.start_time,
            p.arr_rel.get(alight_pos as usize).copied().unwrap_or(0),
            boarding.prev_day,
        );
        (delayed(dep, boarding.delay_secs), delayed(arr, boarding.delay_secs))
    };

    let mut coords = Vec::new();
    let mut dist = 0.0;
    let mut count = 0i32;
    if alight_pos >= board_pos {
        count = (alight_pos - board_pos + 1) as i32;
        // Prefer the feed's own `shapes.txt` geometry (v4 packs): it is the path
        // the vehicle actually takes, and it makes `dist_m` truthful — a line
        // through the stops under-reports every ride. A v3 pack, or a route the
        // ingester could not attach a shape to, falls back to one vertex per stop.
        let shaped = idx.route_shape_off(route_idx).and_then(|off| {
            let from = idx.route_stop_shape(route.first_route_stop + board_pos);
            let to = idx.route_stop_shape(route.first_route_stop + alight_pos);
            if from == NONE || to == NONE {
                return None;
            }
            let pts = idx.shape_slice(off, from, to);
            if pts.len() < 2 {
                None
            } else {
                Some(pts)
            }
        });
        let points = shaped.unwrap_or_else(|| {
            (board_pos..=alight_pos)
                .map(|pos| idx.stop_ll(idx.route_stop(route.first_route_stop + pos)))
                .collect()
        });
        let mut prev: Option<(f64, f64)> = None;
        for (lat, lon) in points {
            if let Some((plat, plon)) = prev {
                dist += dist_m(plat, plon, lat, lon);
            }
            prev = Some((lat, lon));
            coords.push(lon);
            coords.push(lat);
        }
    }

    TransitLeg {
        kind: LegKind::Ride,
        name: idx.read_str(route.name_off),
        feed: idx.feed_name_of(route.feed_idx),
        from_stop: idx.stop_label(board_stop),
        to_stop: idx.stop_label(alight_stop),
        headsign: idx.read_str(td.headsign_off),
        route_color: route.color,
        dep_secs: dep,
        arr_secs: arr,
        stop_count: (count - 1).max(0),
        dist_m: dist,
        coords,
        board_stop_motis_id: idx.motis_stop_id(board_stop).unwrap_or_default(),
        alight_stop_motis_id: idx.motis_stop_id(alight_stop).unwrap_or_default(),
    }
}

fn make_walk_leg(
    idx: &TransitIndex,
    from_stop: u32,
    to_stop: u32,
    dep_secs: u32,
    arr_secs: u32,
) -> TransitLeg {
    let (flat, flon) = idx.stop_ll(from_stop);
    let (tlat, tlon) = idx.stop_ll(to_stop);
    TransitLeg {
        kind: LegKind::Walk,
        name: "Walk".to_string(),
        feed: String::new(),
        from_stop: idx.stop_label(from_stop),
        to_stop: idx.stop_label(to_stop),
        headsign: String::new(),
        route_color: 0,
        dep_secs,
        arr_secs,
        stop_count: 0,
        dist_m: dist_m(flat, flon, tlat, tlon),
        coords: vec![flon, flat, tlon, tlat],
        board_stop_motis_id: String::new(),
        alight_stop_motis_id: String::new(),
    }
}

/// Initial compass bearing from `a` to `b`, degrees in `[0, 360)`, 0 = north,
/// increasing clockwise — the heading a vehicle travelling `a`→`b` faces.
fn bearing_deg(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (lat1, lon1) = (a.0.to_radians(), a.1.to_radians());
    let (lat2, lon2) = (b.0.to_radians(), b.1.to_radians());
    let dlon = lon2 - lon1;
    let y = dlon.sin() * lat2.cos();
    let x = lat1.cos() * lat2.sin() - lat1.sin() * lat2.cos() * dlon.cos();
    let deg = y.atan2(x).to_degrees();
    (deg + 360.0) % 360.0
}

/// Point at fraction `frac` of the cumulative ground length of `points` (a
/// `(lat, lon)` polyline in travel order), with the bearing of the segment it
/// lands on. `frac` is clamped to `[0, 1]`; the endpoints return the endpoint
/// coordinates exactly, which is what keeps a vehicle sitting on its stop.
fn interp_along(points: &[(f64, f64)], frac: f64) -> (f64, f64, f64) {
    if points.is_empty() {
        return (0.0, 0.0, 0.0);
    }
    if points.len() == 1 {
        return (points[0].0, points[0].1, 0.0);
    }
    let frac = frac.clamp(0.0, 1.0);
    let mut seglen: Vec<f64> = Vec::with_capacity(points.len() - 1);
    let mut total = 0.0;
    for w in points.windows(2) {
        let d = dist_m(w[0].0, w[0].1, w[1].0, w[1].1);
        seglen.push(d);
        total += d;
    }
    // A zero-length polyline (all vertices coincident) has no direction; place at
    // the first vertex and take the first segment's (degenerate) bearing.
    if total <= 0.0 {
        return (points[0].0, points[0].1, bearing_deg(points[0], points[1]));
    }
    let target = frac * total;
    let mut acc = 0.0;
    for (i, &d) in seglen.iter().enumerate() {
        if acc + d >= target || i == seglen.len() - 1 {
            let local = if d <= 0.0 { 0.0 } else { ((target - acc) / d).clamp(0.0, 1.0) };
            let (alat, alon) = points[i];
            let (blat, blon) = points[i + 1];
            let lat = alat + (blat - alat) * local;
            let lon = alon + (blon - alon) * local;
            return (lat, lon, bearing_deg(points[i], points[i + 1]));
        }
        acc += d;
    }
    let last = points[points.len() - 1];
    (last.0, last.1, bearing_deg(points[points.len() - 2], last))
}

/// The vehicle path between stop-positions `from`..=`to` of a route, in travel
/// order as `(lat, lon)`. Prefers the feed's `shapes.txt` geometry (the path the
/// vehicle actually takes); falls back to a straight line between the two stops
/// on a v3 pack or a route the ingester attached no shape to. Mirrors the shape
/// handling in [`make_transit_leg`].
fn segment_points(
    idx: &TransitIndex,
    route_idx: u32,
    route: &RouteRec,
    from: u32,
    to: u32,
) -> Vec<(f64, f64)> {
    let shaped = idx.route_shape_off(route_idx).and_then(|off| {
        let fv = idx.route_stop_shape(route.first_route_stop + from);
        let tv = idx.route_stop_shape(route.first_route_stop + to);
        if fv == NONE || tv == NONE || tv < fv {
            return None;
        }
        let pts = idx.shape_slice(off, fv, tv);
        if pts.len() < 2 {
            None
        } else {
            Some(pts)
        }
    });
    shaped.unwrap_or_else(|| {
        vec![
            idx.stop_ll(idx.route_stop(route.first_route_stop + from)),
            idx.stop_ll(idx.route_stop(route.first_route_stop + to)),
        ]
    })
}

/// Bound on how many vehicles a single query returns, so a dense metro area at a
/// wide zoom can't make the ~1 Hz recompute unbounded. The visible bbox already
/// caps it in practice; this is a backstop.
const MAX_VEHICLES: usize = 4000;
/// Margin (degrees) by which a route's stops may fall outside the query bbox and
/// still have the route enumerated, so a vehicle currently between an off-screen
/// and an on-screen stop is not missed.
const VEHICLE_BBOX_MARGIN_DEG: f64 = 0.02;
