                out.extend_from_slice(s);
            }
            out
        }

        fn index(&self) -> TransitIndex {
            TransitIndex::from_bytes(self.build_with_version(VERSION)).expect("index loads")
        }
    }

    /// Weekdays Mon-Fri, all of 2024.
    fn weekdays() -> Service {
        Service { mask: 0b0011_1111 & 0b0001_1111, start: 20_240_101, end: 20_241_231 }
    }

    /// Three stops ~1 km apart on one route, with an 08:00 and an 09:00 trip.
    fn one_route_pack() -> Pack {
        Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "Alpha", code: "A1", gtfs_id: "901201" },
                Stop { lat: 37.710, lon: -122.400, name: "Beta", code: "B2", gtfs_id: "901202" },
                Stop { lat: 37.720, lon: -122.400, name: "Gamma", code: "C3", gtfs_id: "901203" },
            ],
            routes: vec![Route {
                name: "N",
                color: 0x0000FF,
                route_type: 0,
                feed: 0,
                pattern: vec![0, 1, 2],
                trips: vec![
                    Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (29_100, 29_160), (29_400, 29_400)],
                        service: 0,
                        headsign: "Downtown",
                    },
                    Trip {
                        start: 32_400,
                        stoptimes: vec![(32_400, 32_400), (32_700, 32_760), (33_000, 33_000)],
                        service: 0,
                        headsign: "Downtown",
                    },
                ],
                shape: None,
            }],
            services: vec![weekdays()],
            exceptions: Vec::new(),
            feeds: vec![("sfmuni", "America/Los_Angeles", "us-ca-SFMTA")],
        }
    }

    /// [`one_route_pack`] plus a `shapes.txt` polyline that detours east between
    /// each pair of stops, so shaped geometry is distinguishable from a straight
    /// line through the stops by both vertex count and length.
    fn shaped_route_pack() -> Pack {
        let mut pack = one_route_pack();
        pack.routes[0].shape = Some(Shape {
            points: vec![
                (37.700, -122.400),
                (37.705, -122.390),
                (37.710, -122.400),
                (37.715, -122.390),
                (37.720, -122.400),
            ],
            stop_vertices: vec![0, 2, 4],
        });
        pack
    }

    /// 2024-01-03 was a Wednesday.
    fn wednesday() -> QueryDay {
        QueryDay {
            weekday: 2,
            date: 20_240_103,
            prev_weekday: 1,
            prev_date: 20_240_102,
        }
    }

    fn sched(day: QueryDay) -> Schedule<'static> {
        Schedule { day, overlay: None }
    }

    #[test]
    fn plans_a_ride_between_two_stops() {
        let pack = one_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");

        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.name, "N");
        assert_eq!(ride.headsign, "Downtown");
        assert_eq!(ride.route_color, 0x0000FF);
        assert_eq!(ride.feed, "sfmuni");
        // Stop *names*, not codes — the UI presents these as names.
        assert_eq!(ride.from_stop, "Alpha");
        assert_eq!(ride.to_stop, "Gamma");
        // Ready at 25000 < 28800, so it takes the 08:00 trip.
        assert_eq!(ride.dep_secs, 28_800);
        assert_eq!(ride.arr_secs, 29_400);
        assert_eq!(ride.stop_count, 2);
    }

    #[test]
    fn emits_a_wait_leg_before_a_ride() {
        let pack = one_route_pack();
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let wait = legs.iter().find(|l| l.kind == LegKind::Wait).expect("a wait leg");
        // No access-walk leg is emitted here (the origin is on the stop), so the
        // wait covers the whole idle period from the query time.
        assert_eq!(wait.dep_secs, 25_000);
        assert_eq!(wait.arr_secs, 28_800);
        assert_eq!(wait.name, "N");
    }

    #[test]
    fn a_sub_minute_wait_is_not_its_own_step() {
        let mut pack = one_route_pack();
        // Departs ~30 s after the traveller is ready: real, but not worth a step.
        pack.routes[0].trips = vec![Trip {
            start: 25_030,
            stoptimes: vec![(25_030, 25_030), (25_330, 25_330), (25_630, 25_630)],
            service: 0,
            headsign: "Downtown",
        }];
        let idx = pack.index();
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday()))
            .expect("a journey exists");
        assert!(legs.iter().any(|l| l.kind == LegKind::Ride));
        assert!(
            !legs.iter().any(|l| l.kind == LegKind::Wait),
            "a sub-MIN_WAIT_SECS wait must not become its own step"
        );
    }

    #[test]
    fn walk_legs_carry_their_timings() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Offset from the stops so there is a real access and egress walk.
        let legs = plan(&idx, 37.6994, -122.4004, 37.7206, -122.4004, 25_000, sched(wednesday()))
            .expect("a journey exists");
        let walks: Vec<&TransitLeg> = legs.iter().filter(|l| l.kind == LegKind::Walk).collect();
        assert_eq!(walks.len(), 2, "an access and an egress walk");
        for leg in walks {
            assert!(leg.arr_secs > leg.dep_secs, "walk leg has no timing");
            assert!(leg.dep_secs > 0, "walk leg departs at 0");
        }
    }

    #[test]
    fn finds_a_trip_stored_past_midnight() {
        let mut pack = one_route_pack();
        // A single 24:30:00 trip: GTFS files it under the previous service day.
        pack.routes[0].trips = vec![Trip {
            start: 88_200,
            stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
            service: 0,
            headsign: "Owl",
        }];
        let idx = pack.index();

        // 00:20 on Thursday: the trip belongs to Wednesday's service day and runs
        // at 00:30 in Thursday's frame.
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 1_200, sched(day))
            .expect("the 24:30 trip is reachable at 00:20");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 1_800, "shifted into the query day's frame");
        assert_eq!(ride.arr_secs, 2_400);
    }

    #[test]
    fn a_calendar_dates_removal_cancels_the_day() {
        let mut pack = one_route_pack();
        // exception_type 2 (removed) for the query date.
        pack.exceptions = vec![(0, 20_240_103, 0)];
        let idx = pack.index();
        assert!(
            plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(wednesday())).is_none(),
            "service removed for this date, so no journey"
        );
        // The neighbouring date is unaffected — the CSR range is date-keyed.
        let thursday = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(thursday)).is_some());
    }

    #[test]
    fn a_calendar_dates_addition_runs_off_schedule() {
        let mut pack = one_route_pack();
        // Saturday is not in the weekday mask, but an exception adds it.
        pack.exceptions = vec![(0, 20_240_106, 1)];
        let idx = pack.index();
        let saturday = QueryDay {
            weekday: 5,
            date: 20_240_106,
            prev_weekday: 4,
            prev_date: 20_240_105,
        };
        assert!(plan(&idx, 37.700, -122.400, 37.720, -122.400, 25_000, sched(saturday)).is_some());
    }

    #[test]
    fn the_overlay_skips_a_cancelled_trip() {
        let pack = one_route_pack();
        let idx = pack.index();
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
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("the 09:00 trip is still available");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 32_400, "fell through to the 09:00 trip");
    }

    #[test]
    fn the_overlay_delays_a_trip() {
        let pack = one_route_pack();
        let idx = pack.index();
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
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        // The delay propagates to every downstream time on that trip.
        assert_eq!(ride.dep_secs, 29_100);
        assert_eq!(ride.arr_secs, 29_700);
    }

    #[test]
    fn an_unmatched_overlay_entry_leaves_the_schedule_alone() {
        let pack = one_route_pack();
        let idx = pack.index();
        // Right stop, wrong route name -> must not be applied.
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "38R".to_string(),
                sched_secs: 28_800,
                delay_secs: 600,
                cancelled: false,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            25_000,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 28_800);
    }

    #[test]
    fn an_early_running_trip_still_wins_despite_the_scan_break() {
        // Regression: the scan breaks once start_time passes best_dep, which is
        // only sound if realtime can't pull a departure *earlier*. A vehicle
        // running early has a negative delay, so the bound must be widened.
        let mut pack = one_route_pack();
        pack.routes[0].trips.push(Trip {
            start: 36_000,
            stoptimes: vec![(36_000, 36_000), (36_300, 36_360), (36_600, 36_600)],
            service: 0,
            headsign: "Downtown",
        });
        let idx = pack.index();
        // The 10:00 trip is running 80 min early, so it actually leaves at 08:40 —
        // after the 08:00 trip has gone, but before the scheduled 09:00 one.
        let overlay = DelayOverlay::build(
            &idx,
            &[DelayEntry {
                lat: 37.700,
                lon: -122.400,
                route_name: "N".to_string(),
                sched_secs: 36_000,
                delay_secs: -4_800,
                cancelled: false,
            }],
        );
        let legs = plan(
            &idx,
            37.700,
            -122.400,
            37.720,
            -122.400,
            30_600,
            Schedule { day: wednesday(), overlay: Some(&overlay) },
        )
        .expect("a journey exists");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(
            ride.dep_secs, 31_200,
            "the early-running 10:00 trip departs at 08:40, beating the scheduled 09:00"
        );
    }

    #[test]
    fn an_overnight_trip_survives_the_previous_day_scan_break() {
        // The previous-day pass used to be exempt from the break, making it an
        // unconditional scan of the route. It now breaks on a day-shifted bound, so
        // the earliest overnight trip must still win with later ones present.
        let mut pack = one_route_pack();
        pack.routes[0].trips = vec![
            Trip {
                start: 88_200, // 24:30 -> 00:30 in the query day
                stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 91_800, // 25:30 -> 01:30
                stoptimes: vec![(91_800, 91_800), (92_100, 92_100), (92_400, 92_400)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 95_400, // 26:30 -> 02:30
                stoptimes: vec![(95_400, 95_400), (95_700, 95_700), (96_000, 96_000)],
                service: 0,
                headsign: "Owl",
            },
        ];
        let idx = pack.index();
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 1_200, sched(day))
            .expect("the 24:30 trip is reachable at 00:20");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 1_800, "the first overnight trip, not a later one");
    }

    #[test]
    fn a_later_overnight_trip_is_found_once_the_first_has_gone() {
        // The break only applies once a candidate exists, so a query that misses
        // the earliest overnight trip must keep scanning to the next one rather
        // than stopping at the day-shifted bound.
        let mut pack = one_route_pack();
        pack.routes[0].trips = vec![
            Trip {
                start: 88_200, // departs 00:30, already gone at 01:00
                stoptimes: vec![(88_200, 88_200), (88_500, 88_500), (88_800, 88_800)],
                service: 0,
                headsign: "Owl",
            },
            Trip {
                start: 91_800, // departs 01:30
                stoptimes: vec![(91_800, 91_800), (92_100, 92_100), (92_400, 92_400)],
                service: 0,
                headsign: "Owl",
            },
        ];
        let idx = pack.index();
        let day = QueryDay {
            weekday: 3,
            date: 20_240_104,
            prev_weekday: 2,
            prev_date: 20_240_103,
        };
        let legs = plan(&idx, 37.700, -122.400, 37.720, -122.400, 3_600, sched(day))
            .expect("the 25:30 trip is still catchable at 01:00");
        let ride = legs.iter().find(|l| l.kind == LegKind::Ride).expect("a ride leg");
        assert_eq!(ride.dep_secs, 5_400, "the 25:30 trip, shifted into the query day");
    }

    /// Two routes reaching the destination: a direct slow one found in round 1, and
    /// a two-leg faster one that only completes in round 2. Target pruning cuts the
    /// search against the best destination arrival so far, so this pins that it
    /// still admits a genuine later improvement instead of settling for the direct
    /// route.
    #[test]
    fn target_pruning_still_admits_a_faster_journey_found_in_a_later_round() {
        let pack = Pack {
            stops: vec![
                Stop { lat: 37.700, lon: -122.400, name: "Alpha", code: "A1", gtfs_id: "901201" },
                Stop { lat: 37.710, lon: -122.400, name: "Beta", code: "B2", gtfs_id: "901202" },
                Stop { lat: 37.720, lon: -122.400, name: "Gamma", code: "C3", gtfs_id: "901203" },
            ],
            routes: vec![
                Route {
                    name: "Slow",
                    color: 0x0000FF,
                    route_type: 0,
                    feed: 0,
                    pattern: vec![0, 2],
                    trips: vec![Trip {
                        start: 28_800,
                        stoptimes: vec![(28_800, 28_800), (36_000, 36_000)],
                        service: 0,
                        headsign: "Direct",
                    }],
                    shape: None,
                },
                Route {