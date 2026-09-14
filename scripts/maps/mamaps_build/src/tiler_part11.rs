#[cfg(test)]
mod tests_part11 {
    use super::*;
    use super::tests::*;
    use crate::schema::Class;
    use tilecodec::mamaps::dict;
    /// PLANET z14 overflow reproduction: one tile-layer with >65535 bodies through the REAL
    /// encode+append+read path. This is the ONLY path north-america never exercised
    /// (NA max 38,239). Dense cities at z14 (Jakarta etc.) cross 65535 and hit the
    /// extended-count branch. Tests boundaries 65534/65535/65536 and a large 70k case,
    /// plus that the common path (<65535) stays byte-identical (flag 0, reserved 0).
    #[test]
    fn a_tile_layer_with_more_than_65535_buildings_round_trips_through_the_full_tiler() {
        let _guard = budget();
        par::set_threads(1);
        // All features in one tiny patch so they fall into the same z14 tile(s).
        // Use slightly distinct geometries so dedup does not collapse them.
        // At z14 tile width is ~0.022 deg. Buildings have size 0.0003 deg; with
        // jitter 0.00002 deg the whole cluster occupies <0.004 deg — well inside
        // one tile interior (plus buffer), so at z14 it collapses to ONE tile.
        // Previous jitter 0.00008 deg made 0.008 deg spread -> clipped across 2 tiles.
        for n in [65534usize, 65535, 65536, 70000] {
            let mut features = Vec::with_capacity(n);
            for i in 0..n {
                let jitter_x = (i % 200) as f64 * 0.00001;
                let jitter_y = (i / 200) as f64 * 0.00001;
                // Center at -122.005, 37.005 — well inside interior of tile 14/2628/6338 area
                let lon = -122.005 + jitter_x;
                let lat = 37.005 + jitter_y;
                // Small square that stays inside tile even with simplification at z14
                features.push(Feature {
                    class: Class::area(dict::LAYER_BUILDINGS, crate::schema::kind("building"), 14),
                    geometry: square(lon, lat, 0.0003),
                    name: None, id: tilecodec::mamaps::body::ID_NONE, transit_color: 0, transit_ordinal: 0, transit_lanes: 0, transit_taper: 0, lane_count: 0,
                                    turn_fwd: Vec::new(),
                    turn_bwd: Vec::new(),
                    building: None,
                    carriageway: tilecodec::mamaps::body::Carriageway::default(),
                });
            }
            let store = spilled(&features);
            let (bytes, _stats) = build(&store, &settings(14, 14))
                .unwrap_or_else(|e| panic!("build failed for n={n}: {e:?}"));
            let entries = tilecodec::mamaps::read::read_all(&bytes)
                .unwrap_or_else(|e| panic!("read_all failed for n={n}: {e:?}"));
            let mut max_layer_len = 0usize;
            let mut total = 0usize;
            let mut any_extended = false;
            for (_, _, body_bytes) in &entries {
                let body = Body::parse(body_bytes)
                    .unwrap_or_else(|e| panic!("Body::parse failed for n={n}: {e:?}"));
                // Check body flag for extended
                if body_bytes.len() >= 12 && body_bytes[11] == tilecodec::mamaps::body::BODY_FLAG_EXTENDED_COUNTS {
                    any_extended = true;
                }
                if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                    max_layer_len = max_layer_len.max(layer.features.len());
                    total += layer.features.len();
                    // Also verify Body::raw_len prefix matches
                    assert_eq!(Body::raw_len(body_bytes).expect("raw_len") as usize, body_bytes.len());
                }
            }
            // n buildings may split across a few tiles (grid), so max may be <n but for
            // this tight patch it should be close. For 70k we expect >65535 in one tile.
            eprintln!("n={n} -> tiles {} max_buildings {max_layer_len} total {total} extended={any_extended}", entries.len());
            if n >= 65536 {
                assert!(max_layer_len > 65535, "n={n} expected a layer >65535 but widest was {max_layer_len}");
                assert!(any_extended, "n={n} should have used extended encoding");
            } else {
                // For 65534/65535 we expect NOT extended (common path byte-identical)
                // However due to tiling across tiles, individual tile may be <n; just verify no panic and parse ok.
                // If the body's n is <=65535 it should NOT have the flag unless another tile triggered it;
                // the body-level flag is per-body, so bodies with <=65535 features stay flag 0.
                // We don't assert global flag here to avoid false positive when n=65535 splits across 2 tiles.
            }
            // For small case 100, verify common path produces flag 0 on all bodies
            if n <= 65535 {
                for (_, _, body_bytes) in &entries {
                    // Only check bodies that actually overflow; small bodies should remain flag 0
                    let body = Body::parse(body_bytes).expect("parse");
                    if let Some(layer) = body.layer(dict::LAYER_BUILDINGS) {
                        if layer.features.len() <= 65535 {
                            assert_eq!(body_bytes[11], 0, "common path must be flag 0 for n={n} layer {}", layer.features.len());
                            assert_eq!(&body_bytes[12..16], &[0,0,0,0], "reserved must be 0 for n={n}");
                        }
                    }
                }
            }
        }
        release_threads();
    }
}
