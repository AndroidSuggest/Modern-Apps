//! Offline routing engine + live-traffic MVT tile encoder for the Maps app,
//! exposed to Kotlin (`com.vayunmathur.maps.util.OfflineRouter`) via JNI.
//!
//! Rust port of the former CMake/C++ `libofflinerouter` (`native-lib.cpp` +
//! `scratchpad.h` + `radix_heap.h`). The whole-world graph is mmap'd once and
//! shared read-only behind an `Arc`; routing state is serialized behind a mutex;
//! traffic speeds and the per-square segment cache live in their own locks,
//! mirroring the C++ globals but made explicit.

// `graph`, `geometry`, `routing` and `state` are `pub` so the host-side
// route differential (`examples/route_diff.rs`) can drive the real engine
// instead of reimplementing it. Nothing outside this crate links the cdylib,
// so the wider surface costs nothing on device.
pub mod geometry;
pub mod graph;
mod jni;
mod mvt;
pub mod routing;
pub mod state;
mod transit;

#[cfg(test)]
mod tests {
    use crate::jni::routes::{
        graph, snap_walk_legs, walk_snap_endpoints, walk_snap_plausible, WALK_SNAP_MAX_M,
    };
    use crate::transit;

    fn walk_leg(dist_m: f64, coords: Vec<f64>) -> transit::TransitLeg {
        transit::TransitLeg {
            kind: transit::LegKind::Walk,
            name: "Walk".to_string(),
            feed: String::new(),
            from_stop: String::new(),
            to_stop: String::new(),
            headsign: String::new(),
            route_color: 0,
            dep_secs: 28_800,
            arr_secs: 29_100,
            stop_count: 0,
            dist_m,
            coords,
            board_stop_motis_id: String::new(),
            alight_stop_motis_id: String::new(),
        }
    }

    #[test]
    fn walk_snapping_reads_its_endpoints_off_the_straight_line() {
        let leg = walk_leg(300.0, vec![-122.400, 37.700, -122.400, 37.703]);
        assert_eq!(
            walk_snap_endpoints(&leg),
            Some((37.700, -122.400, 37.703, -122.400)),
            "coords are [lon, lat] pairs; endpoints come back as (lat, lon)"
        );
    }

    #[test]
    fn walk_snapping_skips_a_leg_beyond_the_distance_cap() {
        let leg = walk_leg(
            WALK_SNAP_MAX_M + 1.0,
            vec![-122.400, 37.700, -122.400, 37.730],
        );
        assert!(walk_snap_endpoints(&leg).is_none());
    }

    #[test]
    fn walk_snapping_skips_non_walk_legs_and_degenerate_geometry() {
        let mut ride = walk_leg(300.0, vec![-122.400, 37.700, -122.400, 37.703]);
        ride.kind = transit::LegKind::Ride;
        assert!(walk_snap_endpoints(&ride).is_none(), "a ride leg keeps its shape geometry");
        let stub = walk_leg(300.0, vec![-122.400, 37.700]);
        assert!(walk_snap_endpoints(&stub).is_none(), "a single point has no line to route");
    }

    #[test]
    fn a_road_path_far_longer_than_the_straight_line_is_rejected() {
        assert!(walk_snap_plausible(100.0, 240.0));
        assert!(!walk_snap_plausible(100.0, 260.0));
        // A zero-length line has no ratio to test against.
        assert!(walk_snap_plausible(0.0, 500.0));
    }

    #[test]
    fn walk_snapping_is_a_no_op_without_a_graph() {
        // No `init` has run in this test binary, so GRAPH is None and every leg
        // must come back exactly as RAPTOR drew it.
        assert!(graph().is_none(), "no graph is loaded in unit tests");
        let coords = vec![-122.400, 37.700, -122.400, 37.703];
        let mut legs = vec![walk_leg(300.0, coords.clone())];
        snap_walk_legs(&mut legs);
        assert_eq!(legs[0].coords, coords);
        assert_eq!(legs[0].dist_m, 300.0);
    }
}
