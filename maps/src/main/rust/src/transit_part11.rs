// Rail-line shapes + trip itineraries, read straight from the on-device pack.
//
// The tile layer the renderer draws (`transit`, kind `rail`) needs an archive
// rebuild to populate, but the timetable pack already carries everything a
// lines overlay and a vehicle-details sheet need: full GTFS-shape polylines
// per route (sections 20-22), per-route colour/type/name, and the factored
// trip/profile tables a stop-by-stop itinerary reconstructs from. No format
// change — this only reads through the same helpers the planner, the board
// and the vehicle simulation already use.
//
// Included into `transit.rs`, so `TransitIndex`'s private helpers (`route`,
// `trips_for`, `route_shape_off`, `shape_slice`, `stop_ll`, `stop_label`,
// `motis_stop_id`, `read_str`, `feed_name_of`, `get_profile`, `service_runs`
// via `Schedule::runs`, `delayed`, `abs_time_on`) and the `HashMap` import
// are all in scope without qualification, exactly as in `transit_part10.rs`.

/// One drawable rail line: the route's full shape with its agency colour.
pub struct RailLine {
    pub name: String,
    pub color: u32,
    pub route_type: u32,
    pub feed: String,
    /// Interleaved `[lon0, lat0, lon1, lat1, ...]`, like a `RawStep` geometry.
    pub coords: Vec<f64>,
}

/// Bound on lines per query: a metro bbox holds dozens of rail routes, but an
/// unbounded world-zoom enumeration would serialize thousands of polylines.
pub const MAX_RAIL_LINES: usize = 1500;

/// Margin (degrees) matching the vehicle enumeration, so a line whose stops
/// sit just off-screen still draws to the viewport edge.
const RAIL_BBOX_MARGIN_DEG: f64 = 0.02;

/// Whether a GTFS `route_type` draws as a rail line. Mirrors the Kotlin
/// `gtfsRouteTypeToMode` split inversely: everything except the bus family.
/// Buses have shapes too, but drawing every bus polyline would bury the rail
/// network this overlay is for.
fn is_rail_line(t: u32) -> bool {
    !matches!(t, 3 | 800 | 200..=299 | 700..=799)
}

/// Every rail line serving the bbox, with agency colours.
///
/// Shape-first: the route's fitted GTFS polyline (`route_shape_off` +
/// full-span `shape_slice`); routes the ingester could not fit fall back to
/// stop-to-stop, the same fallback the ride legs use. Direction variants of
/// one GTFS route are distinct RAPTOR routes and both enumerate — the caller
/// dedups by name when it wants one line per named route.
pub fn rail_lines(
    idx: &TransitIndex,
    min_lat: f64,
    min_lon: f64,
    max_lat: f64,
    max_lon: f64,
) -> Vec<RailLine> {
    let mut out = Vec::new();
    if idx.stop_count == 0 {
        return out;
    }
    let m = RAIL_BBOX_MARGIN_DEG;
    let near_bbox = |lat: f64, lon: f64| {
        lat >= min_lat - m && lat <= max_lat + m && lon >= min_lon - m && lon <= max_lon + m
    };
    for r in 0..idx.route_count {
        let route = idx.route(r);
        if route.n_stops < 2 || !is_rail_line(route.route_type) {
            continue;
        }
        let mut serves_bbox = false;
        for pos in 0..route.n_stops {
            let (lat, lon) = idx.stop_ll(idx.route_stop(route.first_route_stop + pos));
            if near_bbox(lat, lon) {
                serves_bbox = true;
                break;
            }
        }
        if !serves_bbox {
            continue;
        }
        let first = route.first_route_stop;
        let last = first + route.n_stops - 1;
        let coords: Vec<f64> = match idx.route_shape_off(r) {
            Some(off) => {
                let fv = idx.route_stop_shape(first);
                let tv = idx.route_stop_shape(last);
                if fv == NONE || tv == NONE || tv < fv {
                    stop_to_stop(idx, &route)
                } else {
                    idx.shape_slice(off, fv, tv)
                        .into_iter()
                        .flat_map(|(lat, lon)| [lon, lat])
                        .collect()
                }
            }
            None => stop_to_stop(idx, &route),
        };
        if coords.len() < 4 {
            continue;
        }
        out.push(RailLine {
            name: idx.read_str(route.name_off),
            color: route.color,
            route_type: route.route_type,
            feed: idx.feed_name_of(route.feed_idx),
            coords,
        });
        if out.len() >= MAX_RAIL_LINES {
            break;
        }
    }
    out
}

/// A route's stops joined straight, for routes with no fitted shape.
fn stop_to_stop(idx: &TransitIndex, route: &RouteRec) -> Vec<f64> {
    let mut coords = Vec::with_capacity(route.n_stops as usize * 2);
    for pos in 0..route.n_stops {
        let (lat, lon) = idx.stop_ll(idx.route_stop(route.first_route_stop + pos));
        coords.push(lon);
        coords.push(lat);
    }
    coords
}

/// One stop on a trip's itinerary, with query-day-frame times.
pub struct TripStop {
    pub name: String,
    pub lat: f64,
    pub lon: f64,
    /// Seconds since feed-local midnight, realtime-adjusted when the overlay
    /// covers this trip. Same frame as departure-board `depSecs`.
    pub arr_secs: u32,
    pub dep_secs: u32,
    /// Baked MOTIS id when the pack carries one (v5+ with a known feed
    /// prefix); empty otherwise.
    pub motis_id: String,
}

/// A trip's full run: what the vehicle-details sheet lists.
pub struct TripItinerary {
    pub route_name: String,
    pub headsign: String,
    pub color: u32,
    pub route_type: u32,
    pub feed: String,
    pub cancelled: bool,
    pub stops: Vec<TripStop>,
}

/// Decode a vehicle id back to `(route_idx, trip_index, prev_day)`. Inverse of
/// the packing in [`active_vehicles`](crate::transit::active_vehicles):
/// `id = (r << 32) | (ti << 1) | prev_day`.
fn decode_vehicle_id(id: i64) -> (u32, usize, bool) {
    (
        (id >> 32) as u32,
        ((id >> 1) & 0x7FFF_FFFF) as usize,
        (id & 1) != 0,
    )
}

/// The full stop-by-stop itinerary of the trip a vehicle id names.
///
/// Reuses the vehicle simulation's own math (profile rel-times,
/// previous-day frame shift, first-match trip-wide delay, cancellation), so
/// the sheet agrees with where the sprite is. Returns `None` when the id
/// does not resolve (stale sprite from a previous pack) or the trip does not
/// run on the query service day. A cancelled trip still returns its schedule
/// with `cancelled` set — the sheet shows it as cancelled rather than blank.
pub fn trip_itinerary(
    idx: &TransitIndex,
    vehicle_id: i64,
    schedule: Schedule,
) -> Option<TripItinerary> {
    let (r, ti, prev_day) = decode_vehicle_id(vehicle_id);
    if r >= idx.route_count {
        return None;
    }
    let route = idx.route(r);
    let trips = idx.trips_for(r, &route);
    let td = trips.get(ti)?;
    if !schedule.runs(idx, td.service_idx, prev_day) {
        return None;
    }
    let mut prof_cache = HashMap::new();
    let p = get_profile(&mut prof_cache, idx, td.profile_id);
    let n = (route.n_stops as usize).min(p.arr_rel.len()).min(p.dep_rel.len());
    if n < 2 {
        return None;
    }
    let day_shift = if prev_day { SECS_PER_DAY as i64 } else { 0 };
    // Same trip-wide realtime resolution as the vehicle simulation: the first
    // overlay match applies to the whole run; any cancellation marks it.
    let mut cancelled = false;
    let mut delay_secs = 0i32;
    let mut found_delay = false;
    for pos in 0..n {
        let sched_dep = td.start_time as i64 + p.dep_rel[pos] as i64 - day_shift;
        if sched_dep < 0 {
            continue;
        }
        if let Some(adj) = schedule.adjustment(r, pos as u32, sched_dep as u32) {
            if adj.cancelled {
                cancelled = true;
            }
            if !found_delay {
                delay_secs = adj.delay_secs;
                found_delay = true;
            }
        }
    }
    let mut stops = Vec::with_capacity(n);
    for pos in 0..n {
        let stop_idx = idx.route_stop(route.first_route_stop + pos as u32);
        let (lat, lon) = idx.stop_ll(stop_idx);
        stops.push(TripStop {
            name: idx.stop_label(stop_idx),
            lat,
            lon,
            arr_secs: delayed(
                abs_time_on(td.start_time, p.arr_rel[pos], prev_day),
                delay_secs,
            ),
            dep_secs: delayed(
                abs_time_on(td.start_time, p.dep_rel[pos], prev_day),
                delay_secs,
            ),
            motis_id: idx.motis_stop_id(stop_idx).unwrap_or_default(),
        });
    }
    Some(TripItinerary {
        route_name: idx.read_str(route.name_off),
        headsign: idx.read_str(td.headsign_off),
        color: route.color,
        route_type: route.route_type,
        feed: idx.feed_name_of(route.feed_idx),
        cancelled,
        stops,
    })
}
