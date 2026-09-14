impl<'a> From<&'a FeedDir<'a>> for FeedView<'a> {
    fn from(f: &'a FeedDir<'a>) -> FeedView<'a> {
        FeedView {
            name: &f.name,
            motis_prefix: &f.motis_prefix,
            stops: f.stops,
            routes: f.routes,
            trips: f.trips,
            calendar: f.calendar,
            calendar_dates: f.calendar_dates,
            agency: f.agency,
            shapes: f.shapes,
            stop_times: StopTimesSource::Dir(f.dir),
        }
    }
}

/// Summary counts reported to the caller / manifest.
pub struct BuildStats {
    pub stops: usize,
    pub routes: usize,
    pub trips: usize,
    pub profiles: usize,
    pub feeds: usize,
    pub transfers: usize,
    pub min_lat_e7: i32,
    pub min_lon_e7: i32,
    pub max_lat_e7: i32,
    pub max_lon_e7: i32,
    pub size_bytes: usize,
    /// (section name, byte length) for the size-breakdown report.
    pub section_sizes: Vec<(&'static str, usize)>,
    /// Routes that got `shapes.txt` ride geometry.
    pub shaped_routes: usize,
    /// Routes whose trips disagreed on `shape_id`; the modal one was used.
    pub multi_shape_routes: usize,
    /// Routes whose shape failed validation and fell back to stop-to-stop.
    pub dropped_shape_routes: usize,
    /// Stops dropped because `stop_lat`/`stop_lon` was missing, unparseable or
    /// outside the WGS84 ranges. A non-zero count is a data-quality signal, not
    /// a build error.
    pub dropped_stops_bad_coord: usize,
}

fn append_u32(v: &mut Vec<u8>, x: u32) {
    v.extend_from_slice(&x.to_le_bytes());
}
fn append_i32(v: &mut Vec<u8>, x: i32) {
    v.extend_from_slice(&x.to_le_bytes());
}

/// Approximate ground distance in metres (equirectangular; fine at footpath
/// range).
fn dist_m(lat1_e7: i32, lon1_e7: i32, lat2_e7: i32, lon2_e7: i32) -> f64 {
    let lat1 = lat1_e7 as f64 * 1e-7;
    let lat2 = lat2_e7 as f64 * 1e-7;
    let dlat = (lat2 - lat1) * 111_320.0;
    let mean = ((lat1 + lat2) * 0.5).to_radians();
    let dlon = (lon2_e7 as f64 * 1e-7 - lon1_e7 as f64 * 1e-7) * 111_320.0 * mean.cos();
    (dlat * dlat + dlon * dlon).sqrt()
}

/// A RAPTOR route: trips grouped by identical (feed, gtfs route, stop pattern).
struct RaptorRoute {
    feed_idx: u32,
    /// The feed's own `route_id`. Kept because grouping is by
    /// `(route_id, stop_pattern)`: two GTFS routes over the same stops are two
    /// RAPTOR routes, and `pattern_index` confirms a candidate against this.
    route_id: String,
    name_off: u32,
    color: u32,
    route_type: u32,
    stop_pattern: Vec<u32>,
    trips: Vec<usize>, // indices into `built_trips`
}

/// A trip reduced to its start time + run-time shape, ready for profile dedup.
struct BuiltTrip {
    service_idx: u32,
    headsign_off: u32,
    start_time: u32,             // departure at the first stop
    stoptimes: Vec<(u32, u32)>,  // (arr, dep) aligned to the route's stop_pattern
    /// Index into the feed's interned shape list, or [`NONE`] when the trip
    /// references no usable shape.
    shape_key: u32,
    /// `shape_dist_traveled` per pattern stop, when every stop has one.
    stop_dists: Option<Vec<f64>>,
}

/// Build the whole pack in memory from feeds whose tables are already parsed.
/// `pack_name` is stored in the string pool and surfaced to the on-device planner
/// as a fallback.
///
/// Convenience for tests and small corpora. The host tool drives
/// [`IndexBuilder`] directly instead, one feed at a time, so it never holds more
/// than one feed's tables — or a second copy of the pack.
pub fn build_index(
    pack_name: &str,
    feeds: &[FeedInput],
) -> Result<(Vec<u8>, BuildStats), String> {
    let mut builder = IndexBuilder::new(pack_name);
    for feed in feeds {
        builder.add_feed(feed)?;
    }
    let mut blob = Vec::new();
    let stats = builder.finish_to(&mut blob)?;
    Ok((blob, stats))
}

/// One GTFS route's own fields, before its trips are grouped into patterns.
struct RouteMeta {
    name_off: u32,
    color: u32,
    route_type: u32,
}

/// One GTFS trip's own fields, before its `stop_times` are attached.
struct TripMeta {
    route_id: String,
    service_id: String,
    headsign_off: u32,
    shape_id: String,
}

/// Accumulates GTFS feeds into one merged pack.
///
/// Feeds go in one at a time through [`IndexBuilder::add_feed`] (or
/// [`IndexBuilder::add_feed_dir`], which streams `stop_times.txt`), and each call
/// both ingests a feed **and finalizes it**: its routes, trips, profiles, shapes
/// and stop→route lists are serialized before the next feed is touched, so
/// nothing per-feed survives the feed boundary. That is legal because a route
/// never references a stop outside its own feed (`stop_id_to_idx` is per-feed),
/// which `add` asserts.
///
/// What crosses a boundary is only the pack itself — the section buffers, the
/// string pool, the profile and shape tables — plus `stop_lat`/`stop_lon`,
/// services and exceptions, which the transfers, both grids and the bbox need
/// once every feed is in.
pub struct IndexBuilder {
    pool: StringPool,
    /// STRINGS offset of the pack name, for the header.
    pack_name_off: u32,

    // Merged stop coordinates, in feed order; a feed's stops are one contiguous
    // run. Kept (unlike the name/code offsets, which go straight into STOPS)
    // because transfers, the transfer grid, the spatial grid and the bbox all
    // need them, and `grid_cols` is only known once the last feed is in. 8 bytes
    // a stop is ~80-160 MB on a world pack.
    stop_lat: Vec<i32>,
    stop_lon: Vec<i32>,
    min_lat: i32,
    min_lon: i32,
    max_lat: i32,
    max_lon: i32,

    // Services, namespaced by feed, plus exceptions against the global index.
    service_key_to_idx: HashMap<String, u32>,
    svc_mask: Vec<u8>,
    svc_start: Vec<u32>,
    svc_end: Vec<u32>,
    exceptions: Vec<(u32, u32, u32)>,

    feed_name_offs: Vec<u32>,
    feed_tz_offs: Vec<u32>,
    feed_motis_prefix_offs: Vec<u32>,

    // Sections, appended feed by feed.
    sec_stops: Vec<u8>,
    sec_stop_gtfs_id: Vec<u8>,
    sec_routes: Vec<u8>,
    sec_route_stops: Vec<u8>,
    sec_route_trips: Vec<u8>,
    /// v6: fixed-stride `u32 start_time, profile_id, service_idx, headsign_off` per
    /// trip, per route contiguous, so a trip is addressable by index. Written
    /// alongside ROUTE_TRIPS rather than replacing it, because the device reader's
    /// version window still accepts packs that only have the varint stream.
    sec_route_trip_recs: Vec<u8>,
    /// v6: `u32[route_count + 1]` CSR prefix into [`Self::sec_route_trip_recs`].
    sec_route_trip_off: Vec<u8>,
    sec_route_shape_idx: Vec<u8>,
    sec_route_stop_shape: Vec<u8>,
    sec_stop_routes: Vec<u8>,
    sec_stop_routes_idx: Vec<u8>,
    sec_stop_route_pos: Vec<u8>,
    profiles: ProfileTable,
    shape_blobs: ShapeBlobs,
    /// Running STOP_ROUTES entry count, i.e. the next STOP_ROUTES_IDX value.
    stop_routes_total: u32,

    route_count: usize,
    trip_total: usize,
    shaped_routes: usize,
    multi_shape_routes: usize,
    dropped_shape_routes: usize,
    dropped_stops_bad_coord: usize,
}
