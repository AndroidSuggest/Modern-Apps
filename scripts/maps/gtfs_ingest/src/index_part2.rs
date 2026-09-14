impl IndexBuilder {
    /// `pack_name` is stored in the string pool and surfaced to the on-device
    /// planner as a fallback.
    pub fn new(pack_name: &str) -> IndexBuilder {
        let mut pool = StringPool::new();
        let pack_name_off = pool.intern(pack_name);
        IndexBuilder {
            pool,
            pack_name_off,
            stop_lat: Vec::new(),
            stop_lon: Vec::new(),
            min_lat: i32::MAX,
            min_lon: i32::MAX,
            max_lat: i32::MIN,
            max_lon: i32::MIN,
            service_key_to_idx: HashMap::new(),
            svc_mask: Vec::new(),
            svc_start: Vec::new(),
            svc_end: Vec::new(),
            exceptions: Vec::new(),
            feed_name_offs: Vec::new(),
            feed_tz_offs: Vec::new(),
            feed_motis_prefix_offs: Vec::new(),
            sec_stops: Vec::new(),
            sec_stop_gtfs_id: Vec::new(),
            sec_routes: Vec::new(),
            sec_route_stops: Vec::new(),
            sec_route_trips: Vec::new(),
            sec_route_trip_recs: Vec::new(),
            sec_route_trip_off: Vec::new(),
            sec_route_shape_idx: Vec::new(),
            sec_route_stop_shape: Vec::new(),
            sec_stop_routes: Vec::new(),
            sec_stop_routes_idx: Vec::new(),
            sec_stop_route_pos: Vec::new(),
            profiles: ProfileTable::new(),
            shape_blobs: ShapeBlobs::new(),
            stop_routes_total: 0,
            route_count: 0,
            trip_total: 0,
            shaped_routes: 0,
            multi_shape_routes: 0,
            dropped_shape_routes: 0,
            dropped_stops_bad_coord: 0,
        }
    }

    /// Register a feed-namespaced service id, returning its global index.
    fn ensure_service(&mut self, key: &str) -> u32 {
        if let Some(&i) = self.service_key_to_idx.get(key) {
            return i;
        }
        let i = self.svc_mask.len() as u32;
        self.service_key_to_idx.insert(key.to_string(), i);
        self.svc_mask.push(0);
        self.svc_start.push(0);
        self.svc_end.push(99_999_999);
        i
    }

    /// Ingest and finalize one feed whose tables are all already parsed.
    pub fn add_feed(&mut self, feed: &FeedInput) -> Result<(), String> {
        self.add(&feed.into())
    }

    /// Ingest and finalize one feed, streaming its `stop_times.txt` off disk.
    pub fn add_feed_dir(&mut self, feed: &FeedDir) -> Result<(), String> {
        self.add(&feed.into())
    }

    /// Bytes currently held in the sections, for progress reporting: this is
    /// what the finished pack will be, so it is the number to watch.
    pub fn section_bytes(&self) -> usize {
        self.pool.bytes.len()
            + self.profiles.bytes.len()
            + self.shape_blobs.bytes.len()
            + self.sec_stops.len()
            + self.sec_stop_gtfs_id.len()
            + self.sec_routes.len()
            + self.sec_route_stops.len()
            + self.sec_route_trips.len()
            + self.sec_route_trip_recs.len()
            + self.sec_route_trip_off.len()
            + self.sec_route_shape_idx.len()
            + self.sec_route_stop_shape.len()
            + self.sec_stop_routes.len()
            + self.sec_stop_routes_idx.len()
            + self.sec_stop_route_pos.len()
    }

    /// `(stops, routes, trips)` accumulated so far.
    pub fn counts(&self) -> (usize, usize, usize) {
        (self.stop_lat.len(), self.route_count, self.trip_total)
    }

    fn add(&mut self, feed: &FeedView) -> Result<(), String> {
        let feed_idx = self.feed_name_offs.len() as u32;
        let feed_stop_start = self.stop_lat.len() as u32;
        let feed_exc_start = self.exceptions.len();
        self.feed_name_offs.push(self.pool.intern(feed.name));
        // Per-feed IANA timezone. Dedup in the pool means one shared
        // "America/Los_Angeles" no matter how many feeds use it.
        let tz = feed
            .agency
            .and_then(|a| a.rows.first().map(|row| a.get(row, "agency_timezone").trim()))
            .unwrap_or("");
        self.feed_tz_offs.push(self.pool.intern(tz));
        // Interned once per feed, so every stop's MOTIS id shares this entry.
        self.feed_motis_prefix_offs.push(self.pool.intern(feed.motis_prefix.trim()));

        // --- Stops (this feed) -> global indices ---
        let mut stop_id_to_idx: HashMap<String, u32> = HashMap::new();
        for row in &feed.stops.rows {
            let id = feed.stops.get(row, "stop_id");
            if id.is_empty() {
                continue;
            }
            // Out-of-range coordinates are dropped, not clamped: see
            // `gtfs::parse_lat_lon` for what one bad row does to the bbox.
            let Some((lat, lon)) =
                gtfs::parse_lat_lon(feed.stops.get(row, "stop_lat"), feed.stops.get(row, "stop_lon"))
            else {
                self.dropped_stops_bad_coord += 1;
                continue;
            };
            let lat_e7 = (lat * 1e7) as i32;
            let lon_e7 = (lon * 1e7) as i32;
            let name = feed.stops.get(row, "stop_name");
            let code = feed.stops.get(row, "stop_code");
            let idx = self.stop_lat.len() as u32;
            stop_id_to_idx.insert(id.to_string(), idx);
            self.stop_lat.push(lat_e7);
            self.stop_lon.push(lon_e7);
            // StopRec goes straight into the section: keeping the name/code/id
            // offsets in parallel `Vec<u32>`s only to flatten them at the end was
            // a second copy of STOPS plus a third of STOP_GTFS_ID.
            append_i32(&mut self.sec_stops, lat_e7);
            append_i32(&mut self.sec_stops, lon_e7);
            let name_off = self.pool.intern(name);
            append_u32(&mut self.sec_stops, name_off);
            let code_off = self.pool.intern(if code.is_empty() { id } else { code });
            append_u32(&mut self.sec_stops, code_off);
            // Interned explicitly: `code_off` above falls back to `stop_id` only
            // when `stop_code` is blank, so it cannot stand in for the real id.
            let gtfs_off = self.pool.intern(id);
            append_u32(&mut self.sec_stop_gtfs_id, gtfs_off);
            self.min_lat = self.min_lat.min(lat_e7);
            self.min_lon = self.min_lon.min(lon_e7);
            self.max_lat = self.max_lat.max(lat_e7);
            self.max_lon = self.max_lon.max(lon_e7);
        }

        // --- Route metadata (this feed) ---
        let mut route_meta: HashMap<String, RouteMeta> = HashMap::new();
        for row in &feed.routes.rows {
            let id = feed.routes.get(row, "route_id");
            if id.is_empty() {
                continue;
            }
            let short = feed.routes.get(row, "route_short_name");
            let long = feed.routes.get(row, "route_long_name");
            let name = if !short.is_empty() { short } else { long };
            let color =
                u32::from_str_radix(feed.routes.get(row, "route_color").trim(), 16).unwrap_or(0);
            let rtype: u32 = feed.routes.get(row, "route_type").trim().parse().unwrap_or(3);
            let name_off = self.pool.intern(name);
            route_meta
                .insert(id.to_string(), RouteMeta { name_off, color, route_type: rtype });
        }

        // --- Trips (this feed) ---
        let mut trip_meta: HashMap<String, TripMeta> = HashMap::new();
        for row in &feed.trips.rows {
            let id = feed.trips.get(row, "trip_id");
            if id.is_empty() {
                continue;
            }
            let headsign_off = self.pool.intern(feed.trips.get(row, "trip_headsign"));
            trip_meta.insert(
                id.to_string(),
                TripMeta {
                    route_id: feed.trips.get(row, "route_id").to_string(),
                    service_id: feed.trips.get(row, "service_id").to_string(),
                    headsign_off,
                    shape_id: feed.trips.get(row, "shape_id").trim().to_string(),
                },
            );
        }

        // --- stop_times grouped by trip (this feed) ---
        let mut trip_stoptimes: HashMap<String, Vec<StopTime>> = HashMap::new();
        let mut collect = |row: gtfs::StopTimeRow| {
            let stop_idx = match stop_id_to_idx.get(row.stop_id) {
                Some(&i) => i,
                None => return,
            };
            trip_stoptimes.entry(row.trip_id.to_string()).or_default().push(StopTime {
                seq: row.seq,
                stop_idx,
                arr: row.arr,
                dep: row.dep,
                dist: row.dist,
            });
        };
        match feed.stop_times {
            StopTimesSource::Table(csv) => {
                for row in &csv.rows {
                    let trip_id = csv.get(row, "trip_id");
                    if trip_id.is_empty() {
                        continue;
                    }
                    let Some((arr, dep)) = gtfs::stop_time_pair(
                        csv.get(row, "arrival_time"),
                        csv.get(row, "departure_time"),
                    ) else {
                        continue;
                    };
                    collect(gtfs::StopTimeRow {
                        trip_id,
                        stop_id: csv.get(row, "stop_id"),
                        seq: csv.get(row, "stop_sequence").trim().parse().unwrap_or(0),
                        arr,
                        dep,
                        dist: csv
                            .get(row, "shape_dist_traveled")
                            .trim()
                            .parse()
                            .unwrap_or(f64::NAN),
                    });
                }
            }
            StopTimesSource::Dir(dir) => {
                gtfs::stream_stop_times(dir, collect).ok_or_else(|| {
                    format!(
                        "feed '{}' ({}) missing required GTFS file: stop_times.txt",
                        feed.name,
                        dir.display()
                    )
                })?;
            }
        }

        // --- Services (calendar.txt) namespaced by feed ---
        let feed_key = |id: &str| format!("{}|{}", feed.name, id);
        if let Some(cal) = feed.calendar {
            let days = [
                "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            ];
            for row in &cal.rows {
                let id = cal.get(row, "service_id");
                if id.is_empty() {
                    continue;
                }
                let key = feed_key(id);
                if self.service_key_to_idx.contains_key(&key) {
                    continue;
                }
                let mut mask = 0u8;
                for (b, d) in days.iter().enumerate() {
                    if cal.get(row, d).trim() == "1" {
                        mask |= 1 << b;
                    }
                }
                let idx = self.svc_mask.len() as u32;
                self.service_key_to_idx.insert(key, idx);
                self.svc_mask.push(mask);
                self.svc_start.push(parse_gtfs_date(cal.get(row, "start_date")).unwrap_or(0));
                self.svc_end
                    .push(parse_gtfs_date(cal.get(row, "end_date")).unwrap_or(99_999_999));
            }
        }

        // --- Exceptions (calendar_dates.txt) ---
        if let Some(cd) = feed.calendar_dates {
            for row in &cd.rows {
                let sid = cd.get(row, "service_id");
                if sid.is_empty() {
                    continue;
                }
                let date = match parse_gtfs_date(cd.get(row, "date")) {
                    Some(d) => d,
                    None => continue,
                };
                let added = if cd.get(row, "exception_type").trim() == "1" { 1 } else { 0 };
                let sidx = self.ensure_service(&feed_key(sid));
                self.exceptions.push((sidx, date, added));
            }
        }

        // --- Group this feed's trips into RAPTOR routes ---
        // Every one of these is a feed-local: `finalize_feed` below turns them
        // into section bytes before `add_feed` returns, which is what stops them
        // accumulating across 1272 feeds.
        let mut raptor_routes: Vec<RaptorRoute> = Vec::new();
        let mut built_trips: Vec<BuiltTrip> = Vec::new();
        // Referenced `shapes.txt` polylines, interned so a trip names its shape
        // by index instead of carrying a `String`.
        let mut shapes: Vec<(&str, &Shape)> = Vec::new();
        let mut shape_id_to_idx: HashMap<&str, u32> = HashMap::new();
        // Keyed by a hash of `(route_id, stop_pattern)`, with every candidate
        // compared in full. Unlike the other dedup tables a false match here
        // would fuse two distinct patterns into one route — wrong departures
        // rather than wasted bytes — so a hash alone is not enough. Comparing
        // against the pattern the route already stores also removes the ~240-byte
        // key string this used to allocate per trip.
        let mut pattern_index: HashMap<u64, Vec<usize>> = HashMap::new();
        for (trip_id, mut sts) in trip_stoptimes {
            if sts.len() < 2 {
                continue;
            }
            sts.sort_by_key(|s| s.seq);
            let pattern: Vec<u32> = sts.iter().map(|s| s.stop_idx).collect();
            let tmeta = match trip_meta.get(&trip_id) {
                Some(m) => m,
                None => continue,
            };
            let service_idx = self.ensure_service(&feed_key(&tmeta.service_id));
            let mut hash = fnv1a(tmeta.route_id.as_bytes());
            for &s in &pattern {
                hash = fnv1a_mix(hash, &s.to_le_bytes());
            }
            let slot = pattern_index.entry(hash).or_default();
            let route_idx = match slot.iter().copied().find(|&i| {
                raptor_routes[i].route_id == tmeta.route_id
                    && raptor_routes[i].stop_pattern == pattern
            }) {
                Some(i) => i,
                None => {
                    let (name_off, color, rtype) = match route_meta.get(&tmeta.route_id) {
                        Some(m) => (m.name_off, m.color, m.route_type),
                        None => (NONE, 0, 3),
                    };
                    raptor_routes.push(RaptorRoute {
                        feed_idx,
                        route_id: tmeta.route_id.clone(),
                        name_off,
                        color,
                        route_type: rtype,
                        stop_pattern: pattern,
                        trips: Vec::new(),
                    });
                    let i = raptor_routes.len() - 1;
                    slot.push(i);
                    i
                }
            };
            let shape_key = match feed
                .shapes
                .filter(|_| !tmeta.shape_id.is_empty())
                .and_then(|m| m.get_key_value(&tmeta.shape_id))
            {
                Some((id, sh)) => *shape_id_to_idx.entry(id.as_str()).or_insert_with(|| {
                    shapes.push((id.as_str(), sh));
                    shapes.len() as u32 - 1
                }),
                None => NONE,
            };
            built_trips.push(BuiltTrip {
                service_idx,
                headsign_off: tmeta.headsign_off,
                start_time: sts.first().map(|s| s.dep).unwrap_or(0),
                stoptimes: sts.iter().map(|s| (s.arr, s.dep)).collect(),
                shape_key,
                // Present only when every stop in the pattern carries one, which
                // is what `shapes::fit` requires before it trusts the key.
                stop_dists: sts
                    .iter()
                    .map(|s| (!s.dist.is_nan()).then_some(s.dist))
                    .collect::<Option<Vec<f64>>>(),
            });
            raptor_routes[route_idx].trips.push(built_trips.len() - 1);
        }

        self.finalize_feed(&raptor_routes, &built_trips, &shapes, feed_stop_start)?;

        // A feed's services are allocated contiguously while it is being added,
        // and its exceptions only ever reference them, so sorting this feed's
        // slice leaves the whole array sorted by (service, date) once every feed
        // is in. The sort must stay **stable**: `finish_to` collapses duplicate
        // (service, date) rows by keeping the last, which is CSV order.
        self.exceptions[feed_exc_start..].sort_by_key(|&(sidx, date, _)| (sidx, date));
        Ok(())
    }

}
