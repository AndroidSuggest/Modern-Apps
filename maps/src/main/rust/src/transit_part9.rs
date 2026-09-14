        assert_eq!(ride.to_stop, "Gamma");
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
        assert_eq!(ride.stop_count, 2);

        // The v4 sections the ingester wrote must decode into real geometry: more
        // vertices than the three stops, and a longer path than the straight line.
        assert!(
            ride.coords.len() > 6,
            "expected shape geometry, got {} coords",
            ride.coords.len()
        );
        let lons: Vec<f64> = ride.coords.chunks(2).map(|c| c[0]).collect();
        assert!(
            lons.iter().any(|&lon| lon > -122.395),
            "the eastward detour is missing: {lons:?}"
        );
        let crow = dist_m(37.700, -122.400, 37.720, -122.400);
        assert!(ride.dist_m > crow, "shape distance {} must exceed {crow}", ride.dist_m);
    }

    // --- WS-F: simulated in-service vehicle positions ---

    /// A bbox comfortably around the `one_route_pack` stops (which sit on the
    /// -122.400 meridian between 37.700 and 37.720).
    const VEH_BBOX: (f64, f64, f64, f64) = (37.69, -122.41, 37.73, -122.39);

    #[test]
    fn a_vehicle_sits_exactly_on_a_stop_at_its_scheduled_time() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 08:00: the 08:00 trip is dwelling at Alpha (stop 0).
        let vs = active_vehicles(&idx, 28_800, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "exactly the one in-service trip");
        assert!((vs[0].lat - 37.700).abs() < 1e-9, "at Alpha's latitude");
        assert!((vs[0].lon - (-122.400)).abs() < 1e-9, "at Alpha's longitude");

        // 08:10: it has reached Gamma, the terminus (stop 2), exactly on its coord.
        let vs = active_vehicles(&idx, 29_400, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "the 09:00 trip has not started yet");
        assert!((vs[0].lat - 37.720).abs() < 1e-9);
        assert!((vs[0].lon - (-122.400)).abs() < 1e-9);
    }

    #[test]
    fn a_vehicle_between_two_stops_lies_between_them() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 08:02:30: halfway between Alpha (departs 28_800) and Beta (arrives 29_100).
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1);
        let v = &vs[0];
        assert!(
            v.lat > 37.700 && v.lat < 37.710,
            "strictly between Alpha and Beta, got {}",
            v.lat
        );
        assert!((v.lat - 37.705).abs() < 1e-6, "halfway, got {}", v.lat);
        assert!((v.lon - (-122.400)).abs() < 1e-9);
        // Alpha -> Beta is due north (same longitude, rising latitude).
        assert!(v.bearing < 1.0 || v.bearing > 359.0, "heading north, got {}", v.bearing);
    }

    #[test]
    fn a_cancelled_trip_shows_no_vehicle() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // Cancel the 08:00 departure from Alpha (route 0, stop position 0).
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 0,
                cancelled: true,
            }],
        );
        // At 08:02:30 the 08:00 trip would be mid-route, but it is cancelled and
        // the 09:00 trip is not yet running.
        let vs = active_vehicles(
            &idx,
            28_950,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            m0,
            m1,
            m2,
            m3,
        );
        assert!(vs.is_empty(), "a cancelled trip yields no vehicle");
    }

    #[test]
    fn a_delayed_vehicle_is_placed_at_its_live_position() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 28_800,
                delay_secs: 300,
                cancelled: false,
            }],
        );
        // 08:05: a 5-min-late trip is where the schedule put it at 08:00 — dwelling
        // at Alpha.
        let vs = active_vehicles(
            &idx,
            29_100,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
            m0,
            m1,
            m2,
            m3,
        );
        assert_eq!(vs.len(), 1);
        assert!((vs[0].lat - 37.700).abs() < 1e-9, "held back to Alpha by the delay");
        // Schedule-only, the same instant would already have it dwelling at Beta.
        let vs2 = active_vehicles(&idx, 29_100, sched(wednesday()), m0, m1, m2, m3);
        assert!((vs2[0].lat - 37.710).abs() < 1e-9, "schedule-only is at Beta");
    }

    #[test]
    fn an_overnight_trip_is_placed_in_the_query_day_frame() {
        let mut pack = one_route_pack();
        // A single 24:30:00 trip: GTFS files it under the previous service day.
        pack.routes[0].trips = vec![Trip {
            start: 88_200,
            stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
            service: 0,
            headsign: "Owl",
        }];
        let idx = pack.index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        // 00:32:30 Thursday: the trip runs 00:30->00:38 in Thursday's frame, so it
        // is halfway between Alpha (departs 00:30) and Beta (arrives 00:35).
        let vs = active_vehicles(&idx, 1_950, sched(day), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1, "the overnight trip is in service after midnight");
        assert!(vs[0].lat > 37.700 && vs[0].lat < 37.710, "between Alpha and Beta");
    }

    #[test]
    fn a_vehicle_follows_the_gtfs_shape_between_stops() {
        let idx = shaped_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // Mid Alpha->Beta hop: the shape detours east, so the point must swing off
        // the straight -122.400 line the two stops share.
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), m0, m1, m2, m3);
        assert_eq!(vs.len(), 1);
        assert!(vs[0].lon > -122.400, "on the eastward shape detour, got {}", vs[0].lon);
    }

    #[test]
    fn a_route_outside_the_bbox_is_not_enumerated() {
        let idx = one_route_pack().index();
        // Far from the SF stops: nothing to place, and the route is culled before
        // its trips are ever scanned.
        let vs = active_vehicles(&idx, 28_950, sched(wednesday()), 40.0, -75.0, 41.0, -74.0);
        assert!(vs.is_empty(), "the route is nowhere near this bbox");
    }

    #[test]
    fn no_vehicle_before_the_first_departure_or_after_the_last_arrival() {
        let idx = one_route_pack().index();
        let (m0, m1, m2, m3) = VEH_BBOX;
        // 07:00: before the 08:00 trip departs and long before the 09:00 one.
        assert!(active_vehicles(&idx, 25_200, sched(wednesday()), m0, m1, m2, m3).is_empty());
        // 09:20: after the 09:00 trip has reached its terminus (09:10).
        assert!(active_vehicles(&idx, 33_600, sched(wednesday()), m0, m1, m2, m3).is_empty());
    }
}
