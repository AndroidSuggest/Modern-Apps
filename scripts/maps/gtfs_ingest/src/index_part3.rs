            }
            let shape_off = match &fitted {
                None => NONE,
                Some(f) => self.shape_blobs.intern(&shapes::encode(&f.points))?,
            };
            append_u32(&mut self.sec_route_shape_idx, shape_off);

            let first_route_stop = (self.sec_route_stops.len() / 4) as u32;
            for (pos, &s) in rr.stop_pattern.iter().enumerate() {
                debug_assert!(
                    s >= stop_first,
                    "route {ridx} references stop {s} from an earlier feed; per-feed \
                     finalization depends on patterns staying inside their own feed"
                );
                append_u32(&mut self.sec_route_stops, s);
                let vertex = fitted
                    .as_ref()
                    .and_then(|f| f.stop_vertices.get(pos).copied())
                    .unwrap_or(NONE);
                append_u32(&mut self.sec_route_stop_shape, vertex);
                stop_routes.push((s, ridx, pos as u32));
            }
            let n_stops = rr.stop_pattern.len() as u32;

            // Sort this route's trips by first-stop departure, then varint-pack.
            let mut trip_order: Vec<usize> = rr.trips.clone();
            trip_order.sort_by_key(|&ti| built_trips[ti].start_time);
            // RouteRec.trips_off is a u32 byte offset into ROUTE_TRIPS.
            let trips_off = u32::try_from(self.sec_route_trips.len()).map_err(|_| {
                "ROUTE_TRIPS exceeded 4 GiB, which a u32 RouteRec.trips_off cannot address"
                    .to_string()
            })?;
            let mut prev_start: u32 = 0;
            // The CSR prefix: this route's first trip in the strided table.
            append_u32(&mut self.sec_route_trip_off, self.trip_total as u32);
            for &ti in &trip_order {
                let bt = &built_trips[ti];
                let profile_id = self.profiles.intern(&bt.stoptimes)?;
                let start_delta = bt.start_time.saturating_sub(prev_start);
                write_uvarint(&mut self.sec_route_trips, start_delta as u64);
                write_uvarint(&mut self.sec_route_trips, profile_id as u64);
                write_uvarint(&mut self.sec_route_trips, bt.service_idx as u64);
                write_uvarint(&mut self.sec_route_trips, bt.headsign_off as u64);
                append_u32(&mut self.sec_route_trip_recs, bt.start_time);
                append_u32(&mut self.sec_route_trip_recs, profile_id);
                append_u32(&mut self.sec_route_trip_recs, bt.service_idx);
                append_u32(&mut self.sec_route_trip_recs, bt.headsign_off);
                prev_start = bt.start_time;
                self.trip_total += 1;
            }

            append_u32(&mut self.sec_routes, rr.name_off);
            append_u32(&mut self.sec_routes, rr.color);
            append_u32(&mut self.sec_routes, rr.route_type);
            append_u32(&mut self.sec_routes, rr.feed_idx);
            append_u32(&mut self.sec_routes, n_stops);
            append_u32(&mut self.sec_routes, first_route_stop);
            append_u32(&mut self.sec_routes, trip_order.len() as u32);
            append_u32(&mut self.sec_routes, trips_off);
        }
        self.route_count += raptor_routes.len();

        // Stable, so within a stop the routes stay in ascending route order and
        // the surviving position is the pattern's first occurrence of that stop —
        // matching the reader's `break`-on-match scan that STOP_ROUTE_POS
        // replaces.
        stop_routes.sort_by_key(|&(s, _, _)| s);
        stop_routes.dedup_by(|a, b| a.0 == b.0 && a.1 == b.1);
        let mut k = 0usize;
        for s in stop_first..self.stop_lat.len() as u32 {
            append_u32(&mut self.sec_stop_routes_idx, self.stop_routes_total);
            while let Some(&(stop, route, pos)) = stop_routes.get(k) {
                if stop != s {
                    break;
                }
                append_u32(&mut self.sec_stop_routes, route);
                append_u32(&mut self.sec_stop_route_pos, pos);
                self.stop_routes_total += 1;
                k += 1;
            }
        }
        debug_assert_eq!(k, stop_routes.len(), "a stop→route entry fell outside this feed");
        Ok(())
    }

    /// Serialize the sections that need every feed, and write the pack to `out`.
    pub fn finish_to(self, out: &mut impl Write) -> Result<BuildStats, String> {
        let IndexBuilder {
            pool,
            pack_name_off,
            stop_lat,
            stop_lon,
            min_lat,
            min_lon,
            max_lat,
            max_lon,
            service_key_to_idx: _,
            svc_mask,
            svc_start,
            svc_end,
            exceptions,
            feed_name_offs,
            feed_tz_offs,
            feed_motis_prefix_offs,
            sec_stops,
            sec_stop_gtfs_id,
            sec_routes,
            sec_route_stops,
            sec_route_trips,
            sec_route_trip_recs,
            mut sec_route_trip_off,
            mut sec_route_shape_idx,
            sec_route_stop_shape,
            sec_stop_routes,
            mut sec_stop_routes_idx,
            sec_stop_route_pos,
            profiles,
            shape_blobs,
            stop_routes_total,
            route_count,
            trip_total,
            shaped_routes,
            multi_shape_routes,
            dropped_shape_routes,
            dropped_stops_bad_coord,
        } = self;
        let stop_count = stop_lat.len();
        if stop_count == 0 {
            return Err("no usable stops in any feed".to_string());
        }

        // Everything that depends on one feed alone is already serialized; what
        // is left needs the whole merged stop set.

        // Terminating bound the reader validates shape offsets against.
        append_u32(&mut sec_route_shape_idx, shape_blobs.bytes.len() as u32);
        // Terminating total, so the last stop's STOP_ROUTES range closes.
        append_u32(&mut sec_stop_routes_idx, stop_routes_total);

        // Profiles index (byte offset per id, plus terminating length).
        let mut sec_profiles_idx = Vec::new();
        for &off in &profiles.offsets {
            append_u32(&mut sec_profiles_idx, off);
        }
        append_u32(&mut sec_profiles_idx, profiles.bytes.len() as u32);
        let profile_count = profiles.offsets.len();

        // A wrapped STRINGS offset would name the wrong stop rather than fail, so
        // this is checked before anything is written.
        if pool.overflowed {
            return Err(
                "STRINGS exceeded the 4 GiB a u32 string offset can address; the pack cannot \
                 represent this many feeds"
                    .to_string(),
            );
        }

        // --- Footpath transfers via a coarse spatial grid (cross-feed for free) ---
        // A flat `(cell, stop)` vector sorted once, not a `HashMap` of buckets: on a
        // world pack the map is millions of cells each holding a tiny `Vec`, whose
        // headers and allocations cost far more than the stop ids in them. Sorting
        // by `(cell, stop)` keeps each cell's stops ascending, which is the order the
        // old bucket pushes produced and which the distance sort below tie-breaks on.
        let xcell = |lat_e7: i32, lon_e7: i32| -> (i32, i32) {
            (
                (lat_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
                (lon_e7 as f64 * 1e-7 / CELL_DEG).floor() as i32,
            )
        };
        let mut xgrid: Vec<((i32, i32), u32)> = (0..stop_count)
            .map(|i| (xcell(stop_lat[i], stop_lon[i]), i as u32))
            .collect();
        xgrid.sort_unstable();
        let xbucket = |key: (i32, i32)| -> &[((i32, i32), u32)] {
            let lo = xgrid.partition_point(|&(k, _)| k < key);
            let hi = xgrid.partition_point(|&(k, _)| k <= key);
            &xgrid[lo..hi]
        };
        let mut transfer_total = 0usize;
        let mut sec_transfers = Vec::new();
        let mut sec_transfers_idx = Vec::new();
        let mut tacc: u32 = 0;
        for i in 0..stop_count {
            append_u32(&mut sec_transfers_idx, tacc);
            let (cx, cy) = xcell(stop_lat[i], stop_lon[i]);
            let mut cand: Vec<(u32, f64)> = Vec::new();
            for dx in -1..=1 {
                for dy in -1..=1 {
                    for &(_, j) in xbucket((cx + dx, cy + dy)) {
                        if j as usize == i {
                            continue;
                        }
                        let d =
                            dist_m(stop_lat[i], stop_lon[i], stop_lat[j as usize], stop_lon[j as usize]);
                        if d <= MAX_TRANSFER_M {
                            cand.push((j, d));
                        }
                    }
                }
            }
            // Stable, so equidistant candidates keep the dx/dy/stop order above and
            // `truncate` drops a deterministic tail.
            cand.sort_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(std::cmp::Ordering::Equal));
            cand.truncate(MAX_TRANSFERS_PER_STOP);
            for (j, d) in cand {
                let secs = (d / WALK_SPEED_M_S).ceil() as u32;
                append_u32(&mut sec_transfers, j);
                append_u32(&mut sec_transfers, secs);
                tacc += 1;
                transfer_total += 1;
            }
        }
        append_u32(&mut sec_transfers_idx, tacc);

        // --- Services + exceptions ---
        let mut sec_services = Vec::new();
        for i in 0..svc_mask.len() {
            sec_services.push(svc_mask[i]);
            sec_services.extend_from_slice(&[0u8, 0, 0]);
            append_u32(&mut sec_services, svc_start[i]);
            append_u32(&mut sec_services, svc_end[i]);
        }
        // --- Exceptions: sorted by (service_idx, date), one row per pair, with a
        // CSR index so the device does a range lookup + binary search instead of a
        // full scan per trip. `add_feed` already stable-sorted each feed's slice
        // and a feed's services are a contiguous index range, so the whole array
        // is sorted; for duplicate (service, date) rows the last in CSV order
        // wins — matching the old reader, which scanned every exception without
        // breaking out. ---
        debug_assert!(
            exceptions.windows(2).all(|w| (w[0].0, w[0].1) <= (w[1].0, w[1].1)),
            "per-feed exception slices did not concatenate into a sorted array"
        );
        let mut exc_unique: Vec<(u32, u32, u32)> = Vec::with_capacity(exceptions.len());
        for e in exceptions {
            match exc_unique.last_mut() {
                Some(last) if last.0 == e.0 && last.1 == e.1 => *last = e,
                _ => exc_unique.push(e),
            }
        }
        let mut sec_exceptions = Vec::new();
        let mut sec_exceptions_idx = Vec::new();
        let mut eacc: u32 = 0;
        let mut ei = 0usize;
        for s in 0..svc_mask.len() as u32 {
            append_u32(&mut sec_exceptions_idx, eacc);
            while let Some(&(sidx, date, added)) = exc_unique.get(ei) {
                if sidx != s {
                    break;
                }
                append_u32(&mut sec_exceptions, sidx);
                append_u32(&mut sec_exceptions, date);
                append_u32(&mut sec_exceptions, added);
                eacc += 1;
                ei += 1;
            }
        }
        append_u32(&mut sec_exceptions_idx, eacc);

        // --- Spatial grid (sparse CSR keyed by cell id) ---
        let grid_lat0 = min_lat;
        let grid_lon0 = min_lon;
        let grid_cell_e7 = (GRID_CELL_DEG * 1e7) as u32;
        let cell_col = |lon_e7: i32| -> i64 {
            ((lon_e7 as i64 - grid_lon0 as i64) / grid_cell_e7 as i64).max(0)
        };
        let cell_row = |lat_e7: i32| -> i64 {
            ((lat_e7 as i64 - grid_lat0 as i64) / grid_cell_e7 as i64).max(0)
        };
        let grid_cols = (cell_col(max_lon) + 1) as u32;
        let grid_rows = (cell_row(max_lat) + 1) as u32;
        // Sorted by `(cell_id, stop)`, which is exactly the two orderings the device
        // requires: GRID_CELL_IDS ascending, because the reader binary-searches it,
        // and each cell's GRID_STOPS run stop-ascending.
        let mut grid: Vec<(u32, u32)> = (0..stop_count)
            .map(|i| {
                let row = cell_row(stop_lat[i]) as u32;
                let col = cell_col(stop_lon[i]) as u32;
                (row * grid_cols + col, i as u32)
            })
            .collect();
        grid.sort_unstable();
        let mut sec_grid_cell_ids = Vec::new();
        let mut sec_grid_cell_off = Vec::new();
        let mut sec_grid_stops = Vec::with_capacity(stop_count * 4);
        let mut grid_cell_count = 0usize;
        for (k, &(cid, stop)) in grid.iter().enumerate() {
            if k == 0 || cid != grid[k - 1].0 {
                append_u32(&mut sec_grid_cell_ids, cid);
                append_u32(&mut sec_grid_cell_off, k as u32);
                grid_cell_count += 1;
            }
            append_u32(&mut sec_grid_stops, stop);
        }
        append_u32(&mut sec_grid_cell_off, grid.len() as u32);

        // --- Feeds ---
        let mut sec_feeds = Vec::with_capacity(feed_name_offs.len() * 4);
        for &off in &feed_name_offs {
            append_u32(&mut sec_feeds, off);
        }
        let mut sec_feed_tz = Vec::with_capacity(feed_tz_offs.len() * 4);
        for &off in &feed_tz_offs {
            append_u32(&mut sec_feed_tz, off);
        }
        let mut sec_feed_motis_prefix = Vec::with_capacity(feed_motis_prefix_offs.len() * 4);
        for &off in &feed_motis_prefix_offs {
            append_u32(&mut sec_feed_motis_prefix, off);
        }

        // --- Assemble file: header + directory + aligned sections ---
        let section_names: [&'static str; SECTION_COUNT] = [
            "STRINGS",
            "STOPS",
            "ROUTES",
            "ROUTE_STOPS",
            "ROUTE_TRIPS",
            "PROFILES",
            "PROFILES_IDX",
            "STOP_ROUTES",
            "STOP_ROUTES_IDX",
            "TRANSFERS",
            "TRANSFERS_IDX",
            "SERVICES",
            "EXCEPTIONS",
            "GRID_CELL_IDS",
            "GRID_CELL_OFF",
            "GRID_STOPS",
            "FEEDS",
            "FEED_TZ",
            "EXCEPTIONS_IDX",
            "STOP_ROUTE_POS",
            "SHAPE_COORDS",
            "ROUTE_SHAPE_IDX",
            "ROUTE_STOP_SHAPE",
            "FEED_MOTIS_PREFIX",
            "STOP_GTFS_ID",
            "ROUTE_TRIP_RECS",
            "ROUTE_TRIP_OFF",
        ];
        // Close the CSR prefix: one past the last route's trips, i.e. the total. Done
        // here because it is only known once every feed's routes have been written.
        append_u32(&mut sec_route_trip_off, trip_total as u32);
        let sections: [&[u8]; SECTION_COUNT] = [
            &pool.bytes,
            &sec_stops,
            &sec_routes,
            &sec_route_stops,
            &sec_route_trips,
            &profiles.bytes,
            &sec_profiles_idx,
            &sec_stop_routes,
            &sec_stop_routes_idx,
            &sec_transfers,
            &sec_transfers_idx,
            &sec_services,
            &sec_exceptions,
            &sec_grid_cell_ids,
            &sec_grid_cell_off,
            &sec_grid_stops,
            &sec_feeds,
            &sec_feed_tz,
            &sec_exceptions_idx,
            &sec_stop_route_pos,
            &shape_blobs.bytes,
            &sec_route_shape_idx,
            &sec_route_stop_shape,
            &sec_feed_motis_prefix,
            &sec_stop_gtfs_id,
            &sec_route_trip_recs,
            &sec_route_trip_off,
        ];

        let dir_len = SECTION_COUNT * 16;
        let mut data_off = HEADER_LEN + dir_len;
        let align = |o: usize| (o + 7) & !7;
        for s in sections.iter() {
            data_off = align(data_off) + s.len();
        }
        let total = data_off;

        let mut header: Vec<u8> = Vec::with_capacity(HEADER_LEN);
        append_u32(&mut header, MAGIC);
        append_u32(&mut header, VERSION);
        append_u32(&mut header, SECTION_COUNT as u32);
        append_u32(&mut header, stop_count as u32);
        append_u32(&mut header, route_count as u32);
        append_u32(&mut header, trip_total as u32);
        append_u32(&mut header, svc_mask.len() as u32);
        append_u32(&mut header, profile_count as u32);
        append_u32(&mut header, feed_name_offs.len() as u32);
        append_u32(&mut header, grid_cell_count as u32);
        append_u32(&mut header, pack_name_off);
        append_i32(&mut header, min_lat);
        append_i32(&mut header, min_lon);
        append_i32(&mut header, max_lat);
        append_i32(&mut header, max_lon);
        append_i32(&mut header, grid_lat0);
        append_i32(&mut header, grid_lon0);
        append_u32(&mut header, grid_cell_e7);
        append_u32(&mut header, grid_cols);
        append_u32(&mut header, grid_rows);
        debug_assert_eq!(header.len(), HEADER_LEN);

        let written =
            write_pack(out, &header, &sections).map_err(|e| format!("cannot write the pack: {e}"))?;
        debug_assert_eq!(written, total);

        let section_sizes: Vec<(&'static str, usize)> =
            section_names.iter().zip(sections.iter()).map(|(&n, s)| (n, s.len())).collect();

        Ok(BuildStats {
            stops: stop_count,
            routes: route_count,
            trips: trip_total,
            profiles: profile_count,
            feeds: feed_name_offs.len(),
            transfers: transfer_total,
            min_lat_e7: min_lat,
            min_lon_e7: min_lon,
            max_lat_e7: max_lat,
            max_lon_e7: max_lon,
            size_bytes: written,
            section_sizes,
            shaped_routes,
            multi_shape_routes,
            dropped_shape_routes,
            dropped_stops_bad_coord,
        })
    }

}
