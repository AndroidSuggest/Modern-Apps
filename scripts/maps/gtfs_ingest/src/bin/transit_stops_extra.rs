//! Tests for `transit_stops`, moved wholesale out of `transit_stops.rs` to keep
//! the bin under the file-size limit. Behaviour-identical: same helpers, same
//! assertions, only the module path changed.

use super::{fold_stop_mode, json_escape, normalize_route_type, run, trip_modes};
use gtfs_ingest::gtfs::{Csv, parse_csv};
use gtfs_ingest::manifest::FeedSpec;
use gtfs_ingest::par;
use std::collections::HashMap;
use std::path::PathBuf;

/// The mode fold driven from a parsed `stop_times` table.
///
/// The production path streams the file instead, but both go through
/// [`trip_modes`] and [`fold_stop_mode`], so this exercises the same decisions
/// without a 5.8 GB fixture on disk.
fn derive_from_csv(routes: &Csv, trips: &Csv, stop_times: &Csv) -> HashMap<String, u32> {
    let trip_type = trip_modes(routes, trips);
    let mut out = HashMap::new();
    for row in &stop_times.rows {
        fold_stop_mode(
            &mut out,
            &trip_type,
            stop_times.get(row, "trip_id"),
            stop_times.get(row, "stop_id"),
        );
    }
    out
}

#[test]
fn extended_route_types_fold_onto_the_basic_set() {
    assert_eq!(normalize_route_type(3), 3, "plain bus is unchanged");
    assert_eq!(normalize_route_type(109), 2, "suburban railway -> rail");
    assert_eq!(normalize_route_type(401), 1, "metro -> subway");
    assert_eq!(normalize_route_type(717), 3, "share taxi -> bus");
    assert_eq!(normalize_route_type(900), 0, "tram service -> tram");
    assert_eq!(normalize_route_type(1501), 3, "unknown -> bus");
}

#[test]
fn rail_outranks_bus_at_a_shared_stop() {
    let routes = parse_csv(
        "route_id,route_type\n\
         BUS,3\n\
         SUB,1\n",
    );
    let trips = parse_csv(
        "route_id,trip_id\n\
         BUS,TB\n\
         SUB,TS\n",
    );
    // The bus trip is listed first, so a naive first-wins would pick bus.
    let stop_times = parse_csv(
        "trip_id,stop_id,stop_sequence\n\
         TB,SHARED,1\n\
         TS,SHARED,1\n\
         TB,BUSONLY,2\n",
    );
    let modes = derive_from_csv(&routes, &trips, &stop_times);
    assert_eq!(modes.get("SHARED"), Some(&1), "subway wins over bus");
    assert_eq!(modes.get("BUSONLY"), Some(&3));
}

#[test]
fn an_unserved_stop_gets_no_mode() {
    let routes = parse_csv("route_id,route_type\nR,3\n");
    let trips = parse_csv("route_id,trip_id\nR,T\n");
    let stop_times = parse_csv("trip_id,stop_id,stop_sequence\nT,A,1\n");
    let modes = derive_from_csv(&routes, &trips, &stop_times);
    // A parent station or orphan row no trip calls at is absent, which is what
    // keeps it out of the layer.
    assert_eq!(modes.get("STATION"), None);
    assert_eq!(modes.get("A"), Some(&3));
}

/// Sharding feeds across threads must change nothing.
///
/// The fixture is built so the merge is genuinely order-sensitive: two feeds carry
/// the SAME physical stop and BOTH have a MOTIS prefix, so the first-wins rule
/// decides between them and only spec order can break the tie. With one prefixed
/// feed the rule converges regardless of order, and the test would pass even if
/// the merge were replayed in completion order — proving nothing.
#[test]
fn sharding_feeds_across_threads_changes_no_bytes() {
    let root = std::env::temp_dir().join(format!("gtfs_shard_{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);

    // Every feed has one shared platform — same name AND same position, so the
    // dedup key really collides — plus one stop of its own.
    let write_feed = |tag: &str| -> PathBuf {
        let dir = root.join(tag);
        std::fs::create_dir_all(&dir).unwrap();
        let w = |file: &str, body: String| std::fs::write(dir.join(file), body).unwrap();
        w("routes.txt", "route_id,route_type\nR1,3\nR2,1\n".to_string());
        w("trips.txt", "route_id,trip_id\nR1,T1\nR2,T2\n".to_string());
        w(
            "stops.txt",
            format!(
                "stop_id,stop_name,stop_lat,stop_lon\n\
                 SHARED,Shared Platform,37.7749000,-122.4194000\n\
                 OWN{tag},Own {tag},37.8{},-122.3{}\n",
                tag.len(),
                tag.len()
            ),
        );
        w(
            "stop_times.txt",
            format!(
                "trip_id,stop_id,stop_sequence,arrival_time,departure_time\n\
                 T1,SHARED,1,08:00:00,08:00:00\n\
                 T2,OWN{tag},1,09:00:00,09:00:00\n"
            ),
        );
        dir
    };

    // Seven feeds, so 2- and 3-thread batches both straddle a boundary. Feeds a
    // and b both carry a prefix, so which of them wins SHARED depends on order.
    let specs: Vec<FeedSpec> = ["a", "b", "c", "d", "e", "f", "g"]
        .iter()
        .map(|t| (t.to_string(), write_feed(t), format!("p{t}")))
        .collect();

    let run_at = |n: usize| -> Vec<u8> {
        par::set_threads(n);
        let out = root.join(format!("stops.t{n}.geojsonseq"));
        run(&out, &specs).unwrap();
        std::fs::read(&out).unwrap()
    };

    let base = run_at(1);
    assert!(!base.is_empty(), "the fixture produced no stops");
    // The shared platform survives exactly once, and it is the FIRST feed's.
    let text = String::from_utf8(base.clone()).unwrap();
    assert_eq!(
        text.matches("Shared Platform").count(),
        1,
        "the shared stop must dedup to one feature: {text}"
    );
    assert!(
        text.contains("\"pa_SHARED\""),
        "feed 'a' comes first in spec order, so its id must survive: {text}"
    );
    for n in [2, 3, 7, 32] {
        assert!(
            run_at(n) == base,
            "{n} threads perturbed the layer"
        );
    }
    par::clear_threads();
    let _ = std::fs::remove_dir_all(&root);
}

#[test]
fn json_escaping_covers_quotes_and_controls() {
    let mut out = Vec::new();
    json_escape("A\"B\\C\tD".as_bytes(), &mut out);
    assert_eq!(String::from_utf8(out).unwrap(), "A\\\"B\\\\C\\tD");
    // Non-ASCII passes through as UTF-8 rather than being \u-escaped.
    let mut utf8 = Vec::new();
    json_escape("Béziers".as_bytes(), &mut utf8);
    assert_eq!(String::from_utf8(utf8).unwrap(), "Béziers");
}
