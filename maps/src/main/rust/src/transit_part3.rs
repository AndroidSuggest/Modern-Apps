/// Plan an earliest-arrival transit journey between two WGS84 points departing
/// at `dep_secs` seconds since midnight. All times are in the feed's local frame
/// (see [`QueryDay`]). When `sched` carries an overlay, cancelled trips are
/// skipped and delayed ones are planned at their live times. Returns the ordered
/// legs, or `None` if unreachable.
pub fn plan(
    idx: &TransitIndex,
    from_lat: f64,
    from_lon: f64,
    to_lat: f64,
    to_lon: f64,
    dep_secs: u32,
    sched: Schedule,
) -> Option<Vec<TransitLeg>> {
    let n = idx.stop_count as usize;
    if n == 0 {
        return None;
    }

    let inf = u32::MAX;
    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();

    // --- Egress: precompute walk time from nearby stops to the destination. ---
    // Before access, so the very first access stop already prunes against a
    // walk-only journey when the two ranges overlap.
    let egress: Vec<(u32, u32)> = idx
        .stops_in_radius(to_lat, to_lon, ACCESS_RADIUS_M)
        .into_iter()
        .map(|(s, d)| (s, (d / WALK_SPEED_M_S).ceil() as u32))
        .collect();
    if egress.is_empty() {
        return None;
    }
    let mut reach = Arrivals::new(&egress);

    // --- Access: walk from origin to nearby stops (grid-restricted). ---
    let access = idx.stops_in_radius(from_lat, from_lon, ACCESS_RADIUS_M);
    if access.is_empty() {
        return None;
    }
    let mut seeds: Vec<u32> = Vec::new();
    for (s, d) in access {
        let t = dep_secs + (d / WALK_SPEED_M_S).ceil() as u32;
        if reach.improve(s, t, Reached::Origin) {
            seeds.push(s);
        }
    }

    // Round 0 queue = access stops + their footpath transfers.
    let mut queue: Vec<u32> = seeds.clone();
    relax_transfers(idx, &mut reach, &seeds, &mut queue);

    // --- RAPTOR rounds ---
    for _round in 0..MAX_ROUNDS {
        if queue.is_empty() {
            break;
        }
        // Routes to scan, and the earliest marked stop-position on each.
        let mut route_earliest: HashMap<u32, u32> = HashMap::new();
        for &s in &queue {
            let (rs, re) = idx.stop_routes_range(s);
            for i in rs..re {
                let r = idx.stop_route(i);
                let pos = idx.stop_route_pos(i);
                route_earliest
                    .entry(r)
                    .and_modify(|e| {
                        if pos < *e {
                            *e = pos;
                        }
                    })
                    .or_insert(pos);
            }
        }

        let mut improved: Vec<u32> = Vec::new();
        for (&r, &start_pos) in &route_earliest {
            let route = idx.route(r);
            let trips = idx.trips_for(r, &route);
            if trips.is_empty() {
                continue;
            }
            let mut cur_trip: Option<Boarding> = None;
            let mut board_stop: u32 = 0;
            for pos in start_pos..route.n_stops {
                let stop = idx.route_stop(route.first_route_stop + pos);

                // If riding, relax arrival at this stop. A delayed vehicle
                // arrives late everywhere downstream, so the boarding shift
                // carries through the rest of the leg.
                if let Some(b) = cur_trip {
                    let Some(td) = trips.get(b.trip) else { continue };
                    let arr_rel = {
                        let p = get_profile(&mut prof_cache, idx, td.profile_id);
                        p.arr_rel.get(pos as usize).copied()
                    };
                    if let Some(arr_rel) = arr_rel {
                        let arr = delayed(
                            abs_time_on(td.start_time, arr_rel, b.prev_day),
                            b.delay_secs,
                        );
                        let how = Reached::Transit { route: r, board_stop, boarding: b };
                        if reach.improve(stop, arr, how) {
                            improved.push(stop);
                        }
                    }
                }

                // Can we (re)board an earlier trip here given our arrival? Skipped
                // once this stop is too late to beat the destination, which is what
                // keeps `earliest_trip` off the routes that cannot matter.
                let ready = reach.at(stop);
                if ready != inf && reach.useful(ready) {
                    if let Some(cand) =
                        earliest_trip(idx, &mut prof_cache, r, &trips, pos, ready, sched)
                    {
                        let board_here = match cur_trip {
                            None => true,
                            Some(cur) => {
                                let new = trips.get(cand.trip);
                                let old = trips.get(cur.trip);
                                match (new, old) {
                                    (Some(new), Some(old)) => {
                                        let new_dep =
                                            trip_dep(idx, &mut prof_cache, &new, pos, cand);
                                        let cur_dep =
                                            trip_dep(idx, &mut prof_cache, &old, pos, cur);
                                        new_dep < cur_dep
                                    }
                                    _ => false,
                                }
                            }
                        };
                        if board_here {
                            cur_trip = Some(cand);
                            board_stop = stop;
                        }
                    }
                }
            }
        }

        // Next queue = freshly improved stops + their footpath transfers.
        let mut next: Vec<u32> = improved.clone();
        relax_transfers(idx, &mut reach, &improved, &mut next);
        queue = next;
        if improved.is_empty() {
            break;
        }
    }

    // --- Pick the best destination stop (arrival + egress walk). ---
    let mut best_final = inf;
    let mut best_stop = u32::MAX;
    let mut best_walk = 0u32;
    for &(s, w) in &egress {
        if reach.at(s) == inf {
            continue;
        }
        let total = reach.at(s).saturating_add(w);
        if total < best_final {
            best_final = total;
            best_stop = s;
            best_walk = w;
        }
    }
    if best_stop == u32::MAX {
        return None;
    }

    // --- Reconstruct legs by backtracking labels. ---
    let mut legs: Vec<TransitLeg> = Vec::new();
    let mut cur = best_stop;
    let mut guard = 0;
    let origin_stop;
    loop {
        guard += 1;
        if guard > MAX_ROUNDS * 4 + 16 {
            // A cycle in the labels would otherwise yield a silently truncated
            // journey; bail so the caller falls back to the online planner.
            return None;
        }
        match reach.how(cur) {
            Reached::Origin => {
                origin_stop = cur;
                break;
            }
            Reached::Walk { from_stop } => {
                legs.push(make_walk_leg(
                    idx,
                    from_stop,
                    cur,
                    reach.at(from_stop),
                    reach.at(cur),
                ));
                cur = from_stop;
            }
            Reached::Transit { route, board_stop, boarding } => {
                legs.push(make_transit_leg(
                    idx,
                    &mut prof_cache,
                    route,
                    boarding,
                    board_stop,
                    cur,
                ));
                cur = board_stop;
            }
        }
    }
    legs.reverse();

    // Prepend origin access walk and append destination egress walk.
    let first_stop = origin_stop;
    let (flat, flon) = idx.stop_ll(first_stop);
    let access_d = dist_m(from_lat, from_lon, flat, flon);
    if access_d > 1.0 {
        legs.insert(
            0,
            TransitLeg {
                kind: LegKind::Walk,
                name: "Walk".to_string(),
                feed: String::new(),
                from_stop: String::new(),
                to_stop: idx.stop_label(first_stop),
                headsign: String::new(),
                route_color: 0,
                dep_secs,
                arr_secs: reach
                    .at(first_stop)
                    .min(dep_secs + (access_d / WALK_SPEED_M_S).ceil() as u32),
                stop_count: 0,
                dist_m: access_d,
                coords: vec![from_lon, from_lat, flon, flat],
                // Walk legs carry no realtime, so they need no MOTIS ids.
                board_stop_motis_id: String::new(),
                alight_stop_motis_id: String::new(),
            },
        );
    }
    let (elat, elon) = idx.stop_ll(best_stop);
    let egress_d = dist_m(to_lat, to_lon, elat, elon);
    if egress_d > 1.0 {
        legs.push(TransitLeg {
            kind: LegKind::Walk,
            name: "Walk".to_string(),
            feed: String::new(),
            from_stop: idx.stop_label(best_stop),
            to_stop: String::new(),
            headsign: String::new(),
            route_color: 0,
            dep_secs: reach.at(best_stop),
            arr_secs: reach.at(best_stop).saturating_add(best_walk),
            stop_count: 0,
            dist_m: egress_d,
            coords: vec![elon, elat, to_lon, to_lat],
            board_stop_motis_id: String::new(),
            alight_stop_motis_id: String::new(),
        });
    }

    if legs.is_empty() {
        return None;
    }
    Some(insert_wait_legs(legs, dep_secs))
}

/// Minimum gap before we surface an explicit WAIT leg, so a 30-second dwell
/// doesn't become its own step.
const MIN_WAIT_SECS: u32 = 60;

/// Insert an explicit WAIT leg before each ride the traveller has to wait for.
/// RAPTOR already knows both times; without this the wait is invisible and the
/// journey's step durations don't add up to its total. `dep_secs` is when the
/// traveller is ready, which is what the first leg waits from.
fn insert_wait_legs(legs: Vec<TransitLeg>, dep_secs: u32) -> Vec<TransitLeg> {
    let mut out: Vec<TransitLeg> = Vec::with_capacity(legs.len());
    for leg in legs {
        let ready = out.last().map_or(dep_secs, |prev| prev.arr_secs);
        if leg.kind == LegKind::Ride && leg.dep_secs >= ready + MIN_WAIT_SECS {
            out.push(TransitLeg {
                kind: LegKind::Wait,
                name: leg.name.clone(),
                feed: String::new(),
                from_stop: leg.from_stop.clone(),
                to_stop: leg.from_stop.clone(),
                headsign: leg.headsign.clone(),
                route_color: leg.route_color,
                dep_secs: ready,
                arr_secs: leg.dep_secs,
                stop_count: 0,
                dist_m: 0.0,
                coords: Vec::new(),
                // A wait happens at the following ride's board stop, whose id that
                // ride already carries; duplicating it here would double the
                // overlay's fetch for one stop.
                board_stop_motis_id: String::new(),
                alight_stop_motis_id: String::new(),
            });
        }
        out.push(leg);
    }
    out
}

/// A single upcoming departure from a stop (offline board).
pub struct StopDeparture {
    pub route_name: String,
    pub headsign: String,
    pub feed: String,
    pub stop_code: String,
    pub route_color: u32,
    pub route_type: u32,
    /// Scheduled departure in seconds since the query day's midnight.
    pub dep_secs: u32,
    /// Realtime shift from the overlay; 0 when there is no live data.
    pub delay_secs: i32,
    /// True when realtime says this trip is cancelled.
    pub cancelled: bool,
    /// Whether the overlay had live data for this departure at all.
    pub real_time: bool,
}

/// A simulated in-service vehicle: a point interpolated along a trip's shape at
/// the query instant. Positions are computed on-device from the pack schedule +
/// shape + realtime [`DelayOverlay`]; there is no live GPS feed.
pub struct Vehicle {
    /// WGS84 longitude, degrees.
    pub lon: f64,
    /// WGS84 latitude, degrees.
    pub lat: f64,
    /// Heading in degrees, 0 = north, clockwise, along the direction of travel.
    pub bearing: f64,
    /// GTFS `route_color` packed as 0xRRGGBB, or 0 when the feed omits it.
    pub route_color: u32,
    /// GTFS `route_type`, so the caller can pick a bus/tram/train icon.
    pub route_type: u32,
    /// Identity stable across recomputes for the same trip within a pack, so the
    /// renderer can animate one sprite between 1 Hz recomputes rather than
    /// spawning a new one. Packs `(route_idx, trip_index, prev_day)`.
    pub id: i64,
}

/// Build an offline departure board for the stop(s) nearest to `(lat,lon)`.
///
/// Gathers every route serving the nearest stop (and its co-located platforms
/// within [`STATION_RADIUS_M`]) via the grid and returns upcoming scheduled
/// departures at or after `dep_secs` (seconds since local midnight) on the query
/// service day, sorted by time and capped at `max`. Empty when no stop is near
/// or nothing departs — the caller then keeps whatever the online board returned.
pub fn stop_departures(
    idx: &TransitIndex,
    lat: f64,
    lon: f64,
    dep_secs: u32,
    sched: Schedule,
    max: usize,
) -> Vec<StopDeparture> {
    const STATION_RADIUS_M: f64 = 150.0;
    const NEAREST_MAX_M: f64 = 400.0;

    if idx.stop_count == 0 {
        return Vec::new();
    }
    // Nearest stop to the tapped point (matched by lat/lon — there is no
    // MOTIS<->baked stop id join), via the grid.
    let (nearest, _) = match idx.nearest_stop(lat, lon, NEAREST_MAX_M) {
        Some(v) => v,
        None => return Vec::new(),
    };
    let (blat, blon) = idx.stop_ll(nearest);

    let mut prof_cache: HashMap<u32, ProfileDec> = HashMap::new();
    let mut out: Vec<StopDeparture> = Vec::new();
    for (s, _) in idx.stops_in_radius(blat, blon, STATION_RADIUS_M) {
        let code = idx.read_str(idx.stop(s).code_off);
        let (rs, re) = idx.stop_routes_range(s);
        for i in rs..re {
            let r = idx.stop_route(i);
            let route = idx.route(r);
            // Position of this stop within the route's ordered stop list.
            let pos = idx.stop_route_pos(i);
            // Skip if it's the terminus (no onward departure).
            if pos + 1 >= route.n_stops {
                continue;
            }
            let rname = idx.read_str(route.name_off);
            let feed = idx.feed_name_of(route.feed_idx);
            let trips = idx.trips_for(r, &route);
            for ti in 0..trips.len() {
                let Some(td) = trips.get(ti) else { break };
                let td = &td;
                // Sweep the previous service day too, so a 00:30 query still
                // finds a trip GTFS stored as 24:30:00 the day before.
                for prev_day in [true, false] {
                    if !sched.runs(idx, td.service_idx, prev_day) {
                        continue;
                    }
                    let Some(scheduled) = trip_sched_dep(idx, &mut prof_cache, td, pos, prev_day)
                    else {
                        continue;
                    };
                    let adj = sched.adjustment(r, pos, scheduled);
                    // Compare against the *live* time so a delayed trip that has
                    // not left yet stays on the board.
                    if delayed(scheduled, adj.map_or(0, |a| a.delay_secs)) < dep_secs {
                        continue;
                    }
                    out.push(StopDeparture {
                        route_name: rname.clone(),
                        headsign: idx.read_str(td.headsign_off),
                        feed: feed.clone(),
                        stop_code: code.clone(),
                        route_color: route.color,
                        route_type: route.route_type,
                        dep_secs: scheduled,
                        delay_secs: adj.map_or(0, |a| a.delay_secs),
                        cancelled: adj.is_some_and(|a| a.cancelled),
                        real_time: adj.is_some(),
                    });
                }
            }
        }
    }
    out.sort_by_key(|d| delayed(d.dep_secs, d.delay_secs));
    out.truncate(max);
    out
}

/// Departure time (query-day secs) of `trip` at stop-position `pos`, including
/// the realtime shift the traveller boarded with.
fn trip_dep(
    idx: &TransitIndex,
    cache: &mut HashMap<u32, ProfileDec>,
    trip: &TripDec,
    pos: u32,
    boarding: Boarding,
) -> u32 {
    let dep_rel = {
        let p = get_profile(cache, idx, trip.profile_id);
        p.dep_rel.get(pos as usize).copied().unwrap_or(0)
    };
    delayed(abs_time_on(trip.start_time, dep_rel, boarding.prev_day), boarding.delay_secs)
}
