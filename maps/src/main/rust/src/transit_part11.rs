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

/// One drawable rail-line span: part of a route's shape with its lane in
/// the corridor it crosses. A route outside any corridor is one span over
/// its whole shape (`ordinal` 0, `lanes` 1, `taper` 255).
#[derive(Debug)]
pub struct RailLine {
    pub name: String,
    pub color: u32,
    pub route_type: u32,
    pub feed: String,
    /// Interleaved `[lon0, lat0, lon1, lat1, ...]`, like a `RawStep` geometry.
    pub coords: Vec<f64>,
    /// This colour's index among the corridor's distinct colours.
    pub ordinal: u8,
    /// Distinct colours the corridor carries. One outside a corridor.
    pub lanes: u8,
    /// How far into its lane this span sits, over 255.
    pub taper: u8,
}

/// Bound on spans per query: a metro bbox holds dozens of rail routes, but
/// an unbounded world-zoom enumeration would serialize thousands of
/// polylines.
pub const MAX_RAIL_LINES: usize = 1500;

/// Margin (degrees) matching the vehicle enumeration, so a line whose stops
/// sit just off-screen still draws to the viewport edge.
const RAIL_BBOX_MARGIN_DEG: f64 = 0.02;

/// A line already drawn in its colour over 95% of its length adds nothing.
const MOSTLY_DRAWN: f64 = 0.95;

/// How many distinct services may draw over one stretch of track before the
/// rest are dropped. Past the style's lane count the colours squash onto
/// shared lanes anyway; fifteen republished feeds over one railway is data
/// artefact, not fifteen services.
const MAX_SERVICES_PER_TRACK: usize = 4;

/// Whether a GTFS `route_type` draws as a rail line. Mirrors the Kotlin
/// `gtfsRouteTypeToMode` split inversely: everything except the bus family.
/// Buses have shapes too, but drawing every bus polyline would bury the rail
/// network this overlay is for.
fn is_rail_line(t: u32) -> bool {
    !matches!(t, 3 | 800 | 200..=299 | 700..=799)
}

/// Fallback line colour per rail mode, for routes naming none. A matched
/// pair with the tile path's table in `transit_shapes`: grouping and drawing
/// must agree, or indistinguishable lines take separate lanes.
fn fallback_color(route_type: u32) -> u32 {
    match route_type {
        1 | 400..=499 => 0xE4_002B,
        0 | 5 | 900 => 0xFF_D200,
        2 | 100..=199 => 0x00_57A8,
        12 => 0x9D_9D9D,
        _ => 0x66_6666,
    }
}

/// Every rail line serving the bbox, cut into corridor spans with lanes.
///
/// Shape-first: the route's fitted GTFS polyline (`route_shape_off` +
/// full-span `shape_slice`); routes the ingester could not fit fall back to
/// stop-to-stop, the same fallback the ride legs use. Corridor assignment is
/// the tile path's algorithm (`crate::corridor_group` + `spans_of`),
/// single-threaded at viewport scale: overlapping services fan into
/// parallel lanes, 95%-redrawn duplicates drop, and no track carries more
/// than four services.
pub fn rail_lines(
    idx: &TransitIndex,
    min_lat: f64,
    min_lon: f64,
    max_lat: f64,
    max_lon: f64,
) -> Vec<RailLine> {
    if idx.stop_count == 0 {
        return Vec::new();
    }
    let m = RAIL_BBOX_MARGIN_DEG;
    let near_bbox = |lat: f64, lon: f64| {
        lat >= min_lat - m && lat <= max_lat + m && lon >= min_lon - m && lon <= max_lon + m
    };
    // Collect serving routes with e7 shapes, deterministic order (colour,
    // name) so a rebuild fans identically.
    struct Serving {
        route_idx: u32,
        name: String,
        color: u32,
        route_type: u32,
        feed: String,
        points: Vec<(i32, i32)>,
    }
    let mut serving: Vec<Serving> = Vec::new();
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
        let e7: Vec<(i32, i32)> = match idx.route_shape_off(r) {
            Some(off) => {
                let fv = idx.route_stop_shape(first);
                let tv = idx.route_stop_shape(last);
                if fv == NONE || tv == NONE || tv < fv {
                    stop_to_stop_e7(idx, &route)
                } else {
                    idx.shape_slice(off, fv, tv)
                        .into_iter()
                        .map(|(lat, lon)| {
                            (lat * 1e7, lon * 1e7)
                        })
                        .map(|(lat, lon)| (lat.round() as i32, lon.round() as i32))
                        .collect()
                }
            }
            None => stop_to_stop_e7(idx, &route),
        };
        if e7.len() < 2 {
            continue;
        }
        let color =
            if route.color == 0 { fallback_color(route.route_type) } else { route.color };
        serving.push(Serving {
            route_idx: r,
            name: idx.read_str(route.name_off),
            color,
            route_type: route.route_type,
            feed: idx.feed_name_of(route.feed_idx),
            points: e7,
        });
    }
    serving.sort_by(|a, b| {
        a.color.cmp(&b.color).then(a.name.cmp(&b.name)).then(a.route_idx.cmp(&b.route_idx))
    });

    // Dedup: whole lines only. A line 95% drawn in its colour adds nothing;
    // past four services over one metre the track is fully said.
    let mut kept: Vec<usize> = Vec::with_capacity(serving.len());
    let mut by_color: std::collections::HashMap<u32, crate::corridor_spans::Covered> =
        std::collections::HashMap::new();
    let mut mode_cover: std::collections::HashMap<u32, crate::corridor_spans::Covered> =
        std::collections::HashMap::new();
    for (at, s) in serving.iter().enumerate() {
        let color_cover = by_color.entry(s.color).or_default();
        if color_cover.covered_fraction(&s.points) >= MOSTLY_DRAWN {
            continue;
        }
        let mode = mode_bucket(s.route_type);
        let cover = mode_cover.entry(mode).or_default();
        if cover.crowd_reaches(&s.points, MAX_SERVICES_PER_TRACK) {
            continue;
        }
        cover.add_tagged(&s.points, s.color);
        color_cover.add_tagged(&s.points, 0);
        kept.push(at);
    }

    // Corridor assignment over the survivors.
    let candidates: Vec<crate::corridor_group::Candidate> = kept
        .iter()
        .map(|&at| crate::corridor_group::Candidate {
            points: &serving[at].points,
            route: serving[at].route_idx,
            color: serving[at].color,
            name: &serving[at].name,
        })
        .collect();
    let named: Vec<Option<(u32, &str)>> = {
        let max_route = candidates.iter().map(|c| c.route as usize + 1).max().unwrap_or(0);
        let mut named = vec![None; max_route];
        for c in &candidates {
            named[c.route as usize].get_or_insert((c.color, c.name));
        }
        named
    };
    let (_sets, runs, corridors) = crate::corridor_group::group(&candidates, &named);

    // Cut spans per survivor. Runs/samples live in candidate order; map back
    // through `kept` for route metadata.
    let mut out = Vec::new();
    // Rebuild per-candidate samples for spans_of: probe points in block
    // order with probe-space cum. The grouping port owns sampling; replay
    // the same walk here so spans cut on identical geometry.
    for (nth, &at) in kept.iter().enumerate() {
        let s = &serving[at];
        let walked = crate::corridor_geom::resample(&s.points, crate::corridor_group::SAMPLE_M);
        if walked.len() < 2 {
            continue;
        }
        let own_cum = crate::corridor_geom::cumulative(&s.points);
        // Probe-space cum: probe k sits at k*SAMPLE_M, last at the total.
        let total = own_cum.last().copied().unwrap_or(0.0);
        let probe_pts: Vec<(i32, i32)> = walked;
        let probe_cum: Vec<f64> = (0..probe_pts.len())
            .map(|k| {
                if k + 1 == probe_pts.len() {
                    total
                } else {
                    (k as f64 * crate::corridor_group::SAMPLE_M).min(total)
                }
            })
            .collect();
        let spans = crate::corridor_spans::spans_of(
            &s.points,
            s.color,
            runs.get(nth).map(|r| r.as_slice()).unwrap_or(&[]),
            &probe_pts,
            &probe_cum,
            &own_cum,
            &corridors,
        );
        for span in spans {
            if span.points.len() < 2 {
                continue;
            }
            out.push(RailLine {
                name: s.name.clone(),
                color: s.color,
                route_type: s.route_type,
                feed: s.feed.clone(),
                coords: span
                    .points
                    .iter()
                    .flat_map(|&(lat, lon)| [f64::from(lon) * 1e-7, f64::from(lat) * 1e-7])
                    .collect(),
                ordinal: span.ordinal,
                lanes: span.lanes,
                taper: span.taper,
            });
            if out.len() >= MAX_RAIL_LINES {
                return out;
            }
        }
    }
    out
}

/// Coarse mode bucket for the crowd ceiling: distinct rail modes each get
/// their own crowd budget, so a subway trunk and a tramway sharing track do
/// not suppress each other.
fn mode_bucket(route_type: u32) -> u32 {
    match route_type {
        1 | 400..=499 => 1,
        0 | 5 | 900 => 2,
        2 | 100..=199 => 3,
        _ => 0,
    }
}

/// A route's stops joined straight, as e7 pairs, for routes with no fitted
/// shape.
fn stop_to_stop_e7(idx: &TransitIndex, route: &RouteRec) -> Vec<(i32, i32)> {
    let mut out = Vec::with_capacity(route.n_stops as usize);
    for pos in 0..route.n_stops {
        let (lat, lon) = idx.stop_ll(idx.route_stop(route.first_route_stop + pos));
        out.push(((lat * 1e7).round() as i32, (lon * 1e7).round() as i32));
    }
    out
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
