#[cfg(test)]
mod tests {
    use super::*;
    use crate::gtfs::parse_csv;
    use crate::reader::{
        Reader, SEC_FEED_MOTIS_PREFIX, SEC_SHAPE_COORDS, SEC_STOP_GTFS_ID,
    };

    /// 24 bytes per `stop_times.txt` row is the whole point of the streaming
    /// loader — a world corpus has billions of them, and the old `Csv` +
    /// `Option<f64>` representation cost ~400 and 40.
    #[test]
    fn a_stop_time_stays_twentyfour_bytes() {
        assert_eq!(std::mem::size_of::<StopTime>(), 24);
    }

    pub(super) fn agency(tz: &str) -> Csv {
        parse_csv(&format!("agency_id,agency_name,agency_timezone\nA,Agency,{tz}\n"))
    }

    /// `(shape_id, points as (lat, lon), optional shape_dist_traveled)`.
    pub(super) type ShapeSpec<'a> = (&'a str, Vec<(f64, f64)>, Option<Vec<f64>>);

    pub(super) fn shape_map(entries: Vec<ShapeSpec>) -> HashMap<String, Shape> {
        entries
            .into_iter()
            .map(|(id, pts, dist)| {
                (
                    id.to_string(),
                    Shape {
                        lat_e7: pts.iter().map(|&(la, _)| (la * 1e7) as i32).collect(),
                        lon_e7: pts.iter().map(|&(_, lo)| (lo * 1e7) as i32).collect(),
                        dist,
                    },
                )
            })
            .collect()
    }

    /// Every route's vertex indices must be non-decreasing and inside its blob,
    /// or the device's `shape[vertex(board)..=vertex(alight)]` slice is garbage.
    pub(super) fn assert_shape_invariants(r: &Reader) {
        for route in 0..r.route_count() {
            let rec = r.route(route);
            let (n_stops, first) = (rec.n_stops, rec.first_route_stop);
            let vertices: Vec<u32> =
                (0..n_stops).map(|p| r.route_stop_shape(first + p)).collect();
            match r.route_shape_off(route) {
                None => assert!(
                    vertices.iter().all(|&v| v == NONE),
                    "route {route} has no shape but carries vertices {vertices:?}"
                ),
                Some(off) => {
                    let n = r.shape_points(off).len() as u32;
                    assert!(
                        vertices.windows(2).all(|w| w[1] >= w[0]),
                        "route {route} vertices not monotone: {vertices:?}"
                    );
                    assert!(
                        vertices.iter().all(|&v| v < n),
                        "route {route} vertices {vertices:?} exceed {n} points"
                    );
                }
            }
        }
    }

    fn feed_a() -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.70,-122.40,A1\n\
             S2,Beta,37.71,-122.41,B2\n\
             S3,Gamma,37.72,-122.42,C3\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,0000FF\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign\n\
             R1,WK,T1,Downtown\n\
             R1,WK,T2,Downtown\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             T1,S1,1,08:00:00,08:00:00\n\
             T1,S2,2,08:05:00,08:06:00\n\
             T1,S3,3,08:10:00,08:10:00\n\
             T2,S1,1,09:00:00,09:00:00\n\
             T2,S2,2,09:05:00,09:06:00\n\
             T2,S3,3,09:10:00,09:10:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    fn feed_b() -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             P1,East1,37.80,-122.27,E1\n\
             P2,East2,37.81,-122.26,E2\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             RB,51,Line 51,3,\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign\n\
             RB,WK,TB1,Loop\n",
        );
        let stop_times = parse_csv(
            "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
             TB1,P1,1,07:00:00,07:00:00\n\
             TB1,P2,2,07:20:00,07:20:00\n",
        );
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    #[test]
    fn merges_two_feeds_and_roundtrips_profiles() {
        let (as_, ar, at, ast, ac) = feed_a();
        let (bs, br, bt, bst, bc) = feed_b();
        let aa = agency("America/Los_Angeles");
        let ba = agency("America/New_York");
        // Two rows for the same (service, date) plus an unsorted extra date, to
        // exercise the sort + last-row-wins collapse.
        let acd = parse_csv(
            "service_id,date,exception_type\n\
             WK,20240704,1\n\
             WK,20240101,2\n\
             WK,20240704,2\n",
        );
        let feeds = vec![
            FeedInput {
                name: "sfmuni".to_string(),
                motis_prefix: "us-ca-SFMTA".to_string(),
                stops: &as_,
                routes: &ar,
                trips: &at,
                stop_times: &ast,
                calendar: Some(&ac),
                calendar_dates: Some(&acd),
                agency: Some(&aa),
                shapes: None,
            },
            FeedInput {
                name: "actransit".to_string(),
                // Left empty on purpose: a feed whose MOTIS prefix the build does
                // not know must still produce a valid pack.
                motis_prefix: String::new(),
                stops: &bs,
                routes: &br,
                trips: &bt,
                stop_times: &bst,
                calendar: Some(&bc),
                calendar_dates: None,
                agency: Some(&ba),
                shapes: None,
            },
        ];
        let (blob, stats) = build_index("world", &feeds).expect("build");
        let r = Reader::new(blob).expect("read back the pack");

        // Header sanity. `Reader::new` has already checked the magic.
        assert_eq!(r.version(), VERSION);
        assert_eq!(r.section_count(), SECTION_COUNT as u32);
        assert_eq!(r.stop_count(), 5, "stop_count"); // 3 + 2
        assert_eq!(r.route_count(), 2, "route_count");
        assert_eq!(r.trip_count(), 3, "trip_count"); // 2 + 1
        assert_eq!(r.feed_count(), 2, "feed_count");
        // Feed A's two trips share one shape; feed B has its own -> 2 profiles.
        assert_eq!(r.profile_count(), 2, "profile_count");
        assert_eq!(stats.profiles, 2);

        // Feeds table namespacing.
        assert_eq!(r.feed_name(0), "sfmuni");
        assert_eq!(r.feed_name(1), "actransit");
        // v3: per-feed IANA timezone from agency.txt.
        assert_eq!(r.feed_tz(0), "America/Los_Angeles");
        assert_eq!(r.feed_tz(1), "America/New_York");
        // v5: the Transitous prefix and raw stop_ids that compose a MOTIS stop id.
        assert_eq!(r.feed_motis_prefix(0), "us-ca-SFMTA");
        assert_eq!(r.feed_motis_prefix(1), "", "a feed with no known prefix writes NONE");
        // Feed A's stops come first, so 0..3 are its stop_ids. These are the raw
        // `stop_id` column, NOT `stop_code` ("A1"), which STOPS.code_off holds.
        assert_eq!(r.stop_gtfs_id(0), "S1");
        assert_eq!(r.stop_gtfs_id(1), "S2");
        assert_eq!(r.stop_gtfs_id(2), "S3");
        assert_ne!(
            r.sec_bytes(SEC_FEED_MOTIS_PREFIX).len(),
            0,
            "FEED_MOTIS_PREFIX must be populated so the manifest proves v5 landed"
        );
        assert_eq!(r.sec_bytes(SEC_STOP_GTFS_ID).len(), 5 * 4, "STOP_GTFS_ID is u32[stop_count]");

        // Route 0 = feed A's N-Judah. Decode its trips + profile.
        let rec0 = r.route(0);
        assert_eq!(rec0.feed_idx, 0, "route0 feed_idx");
        assert_eq!(rec0.n_stops, 3, "route0 n_stops");
        assert_eq!(r.read_str(rec0.name_off), "N");
        let trips = r.route_trips(&rec0);
        assert_eq!(trips.len(), 2);
        // Sorted by start_time: 08:00 then 09:00.
        assert_eq!(trips[0].start_time, 28800);
        assert_eq!(trips[1].start_time, 32400);
        // Both reference the same profile id (dedup).
        assert_eq!(trips[0].profile_id, trips[1].profile_id);

        // v6's strided table must be the same trips as the varint stream, for every
        // route. Two views of one thing, written in the same loop, so a disagreement is
        // a writer bug the device reader would silently inherit.
        for route_idx in 0..r.route_count() {
            let rec = r.route(route_idx);
            let varint = r.route_trips(&rec);
            let strided = r.route_trips_strided(route_idx);
            assert_eq!(
                varint.len(),
                strided.len(),
                "route {route_idx} trip count differs between the two views"
            );
            for (a, b) in varint.iter().zip(strided.iter()) {
                assert_eq!(a.start_time, b.start_time, "route {route_idx} start_time");
                assert_eq!(a.profile_id, b.profile_id, "route {route_idx} profile_id");
                assert_eq!(a.service_idx, b.service_idx, "route {route_idx} service_idx");
                assert_eq!(a.headsign_off, b.headsign_off, "route {route_idx} headsign_off");
            }
        }

        // Reconstruct absolute arr/dep for trip T2 (start 09:00) and compare.
        let prof = r.profile(trips[1].profile_id);
        let start = trips[1].start_time as i64;
        let abs: Vec<(i64, i64)> =
            prof.iter().map(|&(a, d)| (start + a, start + d)).collect();
        assert_eq!(abs[0], (32400, 32400)); // S1 09:00/09:00
        assert_eq!(abs[1], (32700, 32760)); // S2 09:05/09:06
        assert_eq!(abs[2], (33000, 33000)); // S3 09:10/09:10

        // Route 1 belongs to feed B.
        let rec1 = r.route(1);
        assert_eq!(rec1.feed_idx, 1, "route1 feed_idx");
        assert_eq!(rec1.n_stops, 2, "route1 n_stops");

        // Route stops of route 0 point at the first three (feed A) stops.
        for pos in 0..rec0.n_stops {
            let s = r.route_stop(rec0.first_route_stop + pos);
            assert!(s < 3, "route0 stop {s} should be a feed-A stop");
        }

        // Grid nearest lookups.
        assert_eq!(r.nearest(37.72, -122.42), Some(2), "Gamma");
        assert_eq!(r.nearest(37.80, -122.27), Some(3), "feed B P1");

        // v3: STOP_ROUTE_POS is parallel to STOP_ROUTES and gives the stop's
        // position in the pattern, so pos matches a ROUTE_STOPS lookup.
        for stop in 0..r.stop_count() {
            for (route, pos) in r.stop_routes(stop) {
                let rec = r.route(route);
                assert!(pos < rec.n_stops, "stop {stop} pos {pos} out of route {route}");
                assert_eq!(
                    r.route_stop(rec.first_route_stop + pos),
                    stop,
                    "stop {stop} route {route}"
                );
            }
        }

        // v3: exceptions are date-sorted per service and collapsed to one row
        // per (service, date), with the last CSV row winning.
        let svc = r.route_trips(&rec0)[0].service_idx;
        assert_eq!(
            r.service_exceptions(svc),
            vec![(svc, 20240101, 0), (svc, 20240704, 0)],
            "sorted, deduped, last-row-wins"
        );
        // Feed B contributes no exceptions, so its services have empty ranges.
        let svc_b = r.route_trips(&rec1)[0].service_idx;
        assert!(r.service_exceptions(svc_b).is_empty());

        // v4: no shapes.txt anywhere, so every route falls back to stop-to-stop.
        assert!(r.route_shape_off(0).is_none());
        assert!(r.route_shape_off(1).is_none());
        assert_eq!(r.sec_bytes(SEC_SHAPE_COORDS).len(), 0, "SHAPE_COORDS is empty");
        assert_shape_invariants(&r);
    }

    // --- v4 shape ingest -----------------------------------------------------

    /// One feed, three collinear stops, one route, `shape_id` on both trips.
    /// `stop_times_extra` is appended verbatim so a test can add
    /// `shape_dist_traveled`.
    pub(super) fn shaped_feed(shape_dist: bool) -> (Csv, Csv, Csv, Csv, Csv) {
        let stops = parse_csv(
            "stop_id,stop_name,stop_lat,stop_lon,stop_code\n\
             S1,Alpha,37.700,-122.400,A1\n\
             S2,Beta,37.710,-122.400,B2\n\
             S3,Gamma,37.720,-122.400,C3\n",
        );
        let routes = parse_csv(
            "route_id,route_short_name,route_long_name,route_type,route_color\n\
             R1,N,Judah,0,0000FF\n",
        );
        let trips = parse_csv(
            "route_id,service_id,trip_id,trip_headsign,shape_id\n\
             R1,WK,T1,Downtown,SH1\n\
             R1,WK,T2,Downtown,SH1\n",
        );
        let stop_times = if shape_dist {
            parse_csv(
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time,shape_dist_traveled\n\
                 T1,S1,1,08:00:00,08:00:00,0.0\n\
                 T1,S2,2,08:05:00,08:06:00,1.3\n\
                 T1,S3,3,08:10:00,08:10:00,2.6\n\
                 T2,S1,1,09:00:00,09:00:00,0.0\n\
                 T2,S2,2,09:05:00,09:06:00,1.3\n\
                 T2,S3,3,09:10:00,09:10:00,2.6\n",
            )
        } else {
            parse_csv(
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
                 T1,S1,1,08:00:00,08:00:00\n\
                 T1,S2,2,08:05:00,08:06:00\n\
                 T1,S3,3,08:10:00,08:10:00\n\
                 T2,S1,1,09:00:00,09:00:00\n\
                 T2,S2,2,09:05:00,09:06:00\n\
                 T2,S3,3,09:10:00,09:10:00\n",
            )
        };
        let calendar = parse_csv(
            "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n\
             WK,1,1,1,1,1,0,0,20240101,20241231\n",
        );
        (stops, routes, trips, stop_times, calendar)
    }

    /// The route's shape, detouring east between each pair of stops.
    fn detour_shape(dist: bool) -> HashMap<String, Shape> {
        shape_map(vec![(
            "SH1",
            vec![
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            // Kilometres, deliberately not metres: units are feed-defined.
            dist.then(|| vec![0.0, 0.65, 1.3, 1.95, 2.6]),
        )])
    }

    pub(super) fn one_feed<'a>(
        t: &'a (Csv, Csv, Csv, Csv, Csv),
        ag: &'a Csv,
        shapes: Option<&'a HashMap<String, Shape>>,
    ) -> Vec<FeedInput<'a>> {
        vec![FeedInput {
            name: "sfmuni".to_string(),
            motis_prefix: "us-ca-SFMTA".to_string(),
            stops: &t.0,
            routes: &t.1,
            trips: &t.2,
            stop_times: &t.3,
            calendar: Some(&t.4),
            calendar_dates: None,
            agency: Some(ag),
            shapes,
        }]
    }

    #[test]
    fn projects_stops_onto_a_shape_without_shape_dist_traveled() {
        let t = shaped_feed(false);
        let ag = agency("America/Los_Angeles");
        let sh = detour_shape(false);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        assert_eq!(stats.dropped_shape_routes, 0);
        assert_eq!(stats.multi_shape_routes, 0);
        assert_shape_invariants(&r);

        let off = r.route_shape_off(0).expect("route 0 is shaped");
        let pts = r.shape_points(off);
        let rec = r.route(0);
        let vertices: Vec<u32> =
            (0..rec.n_stops).map(|p| r.route_stop_shape(rec.first_route_stop + p)).collect();
        // Trimmed to the boarded extent: first stop is vertex 0, last is the end.
        assert_eq!(vertices[0], 0);
        assert_eq!(*vertices.last().unwrap() as usize, pts.len() - 1);
        // Each stop's vertex is the stop's own projection, so a boarded span
        // starts exactly on the stop.
        for (p, &v) in vertices.iter().enumerate() {
            let stop = r.route_stop(rec.first_route_stop + p as u32);
            let (slat, slon) = r.stop_ll(stop);
            let (vlat, vlon) = pts[v as usize];
            assert!(
                (vlat as f64 * 1e-7 - slat).abs() < 1e-5
                    && (vlon as f64 * 1e-7 - slon).abs() < 1e-5,
                "vertex {v} at {vlat},{vlon} is not stop {stop} at {slat},{slon}"
            );
        }
        // The detour survives simplification, so the drawn ride is not straight.
        assert!(
            pts.iter().any(|&(_, lon)| lon > -1_223_950_000),
            "the eastward detour was simplified away: {pts:?}"
        );
    }

    #[test]
    fn shape_dist_traveled_on_both_files_is_used() {
        let t = shaped_feed(true);
        let ag = agency("America/Los_Angeles");
        let sh = detour_shape(true);
        let (blob, stats) =
            build_index("world", &one_feed(&t, &ag, Some(&sh))).expect("build");
        let r = Reader::new(blob).expect("read back the pack");
        assert_eq!(stats.shaped_routes, 1);
        assert_shape_invariants(&r);
        let rec = r.route(0);
        let vertices: Vec<u32> =
            (0..rec.n_stops).map(|p| r.route_stop_shape(rec.first_route_stop + p)).collect();
        assert!(vertices.windows(2).all(|w| w[1] > w[0]), "distinct stops, distinct vertices");
    }
}
