#[cfg(test)]
mod tests_part8 {
    use super::*;
    use super::tests::*;
    use crate::schema::Class;
    use tilecodec::mamaps::dict;
    /// **The road/river name path, end to end.** A street's name survives the spill, the merge and
    /// coalescing, and comes back interned in the tile's name table on the line feature — which is
    #[test]
    fn a_road_name_reaches_the_archive() {
        let road = Feature {
            class: Class::line(dict::LAYER_ROADS, crate::schema::kind("major_road"), 12),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.98, 35.002)]]),
            name: Some("Market Street".to_string()),
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let river = Feature {
            class: Class::line(dict::LAYER_WATER, crate::schema::kind("river"), 12),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.97, 35.004)]]),
            name: Some("Los Gatos Creek".to_string()),
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (bytes, _) = build(&spilled(&[road, river]), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let (mut saw_road, mut saw_river) = (false, false);
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            if let Some(layer) = body.layer(dict::LAYER_ROADS) {
                for f in &layer.features {
                    if f.name(&body) == Some("Market Street") {
                        saw_road = true;
                    }
                }
            }
            if let Some(layer) = body.layer(dict::LAYER_WATER) {
                for f in &layer.features {
                    if f.name(&body) == Some("Los Gatos Creek") {
                        saw_river = true;
                    }
                }
            }
        }
        assert!(saw_road, "the road's name should reach the archive");
        assert!(saw_river, "the river's name should reach the archive");
    }

    #[test]
    fn a_roads_turn_masks_reach_the_archive() {
        use osm_ingest::tags::{LANE_LEFT, LANE_RIGHT, LANE_THROUGH};
        let (fwd, _) = crate::schema::roads::turn_masks(
            &[("highway", "primary"), ("oneway", "yes"), ("lanes", "3"),
              ("turn:lanes", "left|through|through;right")][..],
        );
        let road = Feature {
            class: Class::line(dict::LAYER_ROADS, crate::schema::kind("major_road"), 12),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.99, 35.001)]]),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 3,
            turn_fwd: fwd,
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let (bytes, _) = build(&spilled(&[road]), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut seen = false;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_ROADS) else { continue };
            for index in 0..layer.features.len() {
                let Some(turns) = body.feature_turns(dict::LAYER_ROADS, index) else { continue };
                if turns.is_empty() {
                    continue;
                }
                assert_eq!(
                    turns.forward,
                    vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH | LANE_RIGHT],
                    "the road's forward turn masks survive the archive",
                );
                assert!(turns.backward.is_empty(), "a oneway carries no backward masks");
                seen = true;
            }
        }
        assert!(seen, "at least one archived road tile carries the turn masks");
    }

    /// **The junction layer's coalesce opt-out, through the full tiler.** Every
    /// connector shares one class (no kind, no detail), so coalescing would
    /// chain unrelated movements — a left turn and the through beside it — into
    /// one polyline and draw a ribbon between them. This pushes two touching
    /// connectors and asserts each survives as its own feature, mirroring
    /// `a_traffic_layer_keeps_one_id_per_segment_through_the_tiler` for the
    /// traffic layer's neighbouring opt-out.
    #[test]
    fn a_junction_layer_keeps_one_connector_per_feature_through_the_tiler() {
        use crate::schema::junction::junction_class;
        // Two connectors meeting end to start at the junction mouth: exactly
        // the shape a join would splice, and the shape a left turn beside a
        // through movement takes.
        let connector = |x0: f64, x1: f64| Feature {
            class: junction_class(),
            geometry: Geometry::Lines(vec![vec![(x0, 35.0), (x1, 35.0004)]]),
            name: None,
            id: tilecodec::mamaps::body::ID_NONE,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        };
        let features =
            vec![connector(-120.0, -119.9996), connector(-119.9996, -119.9992)];
        let (bytes, _) = build(&spilled(&features), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut total = 0usize;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_JUNCTION) else { continue };
            for feature in &layer.features {
                assert_eq!(feature.geom_type, GEOM_LINE);
                total += 1;
            }
        }
        assert_eq!(
            total, 2,
            "coalescing chained the two connectors into one feature: \
             delete the LAYER_JUNCTION opt-out in encode_batch and this fails",
        );
    }

    /// **The traffic layer's one-id-per-segment invariant, through the full tiler.** Traffic
    /// features share a class (no kind, no detail), so if the layer were coalesced like every
    /// other line layer they would collapse into one feature and every segment's `component_id`
    /// but the first would be lost. This pushes several distinct segments of one edge and asserts
    /// each survives as its own feature with its own id, decoded back out of the archive.
    #[test]
    fn a_traffic_layer_keeps_one_id_per_segment_through_the_tiler() {
        use crate::schema::traffic::{pack_component_id, traffic_class, unpack_component_id};
        let mut features = Vec::new();
        for seg in 0..8u32 {
            // Spaced tightly so they land in the same z14 tile(s); each its own 2-point line.
            let x = -120.0 + seg as f64 * 0.0005;
            features.push(Feature {
                class: traffic_class(),
                geometry: Geometry::Lines(vec![vec![(x, 35.0), (x + 0.0004, 35.0004)]]),
                name: None,
                id: pack_component_id(7, seg),
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                            turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                building: None,
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
            });
        }
        let (bytes, _) = build(&spilled(&features), &settings(14, 14)).expect("build");
        let entries = tilecodec::mamaps::read::read_all(&bytes).expect("read");
        let mut seen: std::collections::BTreeSet<u32> = std::collections::BTreeSet::new();
        let mut total = 0usize;
        for (_, _, body) in &entries {
            let body = Body::parse(body).expect("parse");
            let Some(layer) = body.layer(dict::LAYER_TRAFFIC) else { continue };
            for index in 0..layer.features.len() {
                assert_eq!(layer.features[index].geom_type, GEOM_LINE);
                let id = body
                    .feature_id(dict::LAYER_TRAFFIC, index)
                    .expect("the traffic layer must carry an id table");
                let (edge, seg) = unpack_component_id(id);
                assert_eq!(edge, 7, "a component_id must unpack to its source edge");
                assert!(seg < 8, "seg_index {seg} is past what was pushed");
                seen.insert(seg);
                total += 1;
            }
        }
        assert_eq!(seen.len(), 8, "coalescing collapsed the per-segment ids");
        assert!(total >= 8, "feature count {total} is short of the segments pushed");
    }

}
