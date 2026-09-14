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

    /// **Lane E keying.** Shared logicals are keyed by full content per layer:
    /// traffic by `component_id`, junctions by geometry hash (no stable id),
    /// roads/buildings by OSM way id when stage A plumbs one (it does not yet,
    /// so those return `None` and are skipped). Anything else is not a shared
    /// logical.
    #[test]
    fn shared_logicals_are_keyed_by_content_per_layer() {
        use crate::schema::traffic::pack_component_id;
        use tilecodec::mamaps::dict::{
            LAYER_BUILDINGS, LAYER_JUNCTION, LAYER_ROADS, LAYER_TRAFFIC, LAYER_WATER,
        };
        use tilecodec::mamaps::shared::junction_key;
        let key = |layer: u8, id: u64, points: &[(i16, i16)]| {
            shared_row_key(
                layer,
                id,
                points,
                None,
                0,
                0,
                0,
                BuildingAttrs::default(),
                tilecodec::mamaps::body::Carriageway::default(),
                tilecodec::mamaps::body::LaneTurns::default(),
            )
        };
        // Traffic: the full component id rides the key, so low16-aliasing
        // edges are distinct rows by construction.
        let a = key(LAYER_TRAFFIC, pack_component_id(7, 3), &[]);
        let aliased = key(LAYER_TRAFFIC, pack_component_id(7 + 65536, 3), &[]);
        assert!(a.is_some(), "traffic keys through the component id");
        assert_eq!(
            aliased.clone().map(|k| k.stable_id),
            Some(pack_component_id(7 + 65536, 3)),
            "the full id rides the key, not a fold",
        );
        assert_ne!(aliased, a, "aliasing edges are distinct rows");
        // Junctions: the geometry hash, stable for one shape and distinct for
        // another — and independent of whatever stable id rides along.
        let points = [(0i16, 0i16), (100, 50), (200, 0)];
        let keyed = key(LAYER_JUNCTION, 999, &points);
        assert_eq!(
            keyed.clone().map(|k| k.geom_hash),
            Some(junction_key(&points)),
            "junctions key by geometry",
        );
        assert_eq!(
            key(LAYER_JUNCTION, 1000, &points).map(|k| k.geom_hash),
            keyed.clone().map(|k| k.geom_hash),
            "same geometry hashes the same whatever id rides along",
        );
        assert_ne!(
            key(LAYER_JUNCTION, 999, &[(0i16, 0i16), (100, 50), (200, 1)]),
            keyed,
            "distinct connectors hash distinctly",
        );
        // Roads and buildings: way 12345 keys whole; ID_NONE is skipped.
        let way = crate::extract::tagged_id(12345, crate::extract::ELEMENT_WAY);
        assert_eq!(way & 0b11, crate::extract::ELEMENT_WAY);
        assert!(key(LAYER_ROADS, way, &[]).is_some());
        assert!(key(LAYER_BUILDINGS, way, &[]).is_some());
        assert_eq!(
            key(LAYER_ROADS, tilecodec::mamaps::body::ID_NONE, &[]),
            None,
            "no identity, no row",
        );
        assert_eq!(key(LAYER_BUILDINGS, tilecodec::mamaps::body::ID_NONE, &[]), None);
        // Nothing else is a shared logical, id or no id.
        assert_eq!(key(LAYER_WATER, way, &[]), None);
        assert_eq!(
            key(LAYER_TRAFFIC, tilecodec::mamaps::body::ID_NONE, &[]),
            None,
        );
    }

    /// **Lane E drain.** The first sighting of a content key interns its row
    /// (sequential id, first-sighting order), every sighting pushes a slim
    /// ref, and the same content twice shares one row. Round-trips through the
    /// real section encoding, independent of the writer's finish wiring.
    #[test]
    fn the_shared_drain_interns_once_refs_per_tile_and_shares_repeats() {
        use tilecodec::mamaps::shared::{
            SharedBuilder, SharedBuildingAttrs, SharedCarriageway, SharedLaneTurns, SharedRowKey,
            SharedView,
        };
        let key = |name: &str, stable_id: u64| SharedRowKey {
            layer: 1,
            stable_id,
            geom_hash: 0,
            name: Some(name.to_string()),
            kind: 45,
            kind_detail: 0,
            flags: 0,
            building: SharedBuildingAttrs::default(),
            carriageway: SharedCarriageway::default(),
            lane_turns: SharedLaneTurns::default(),
        };
        let intent = |key: SharedRowKey, stable_id: u64| SharedRowIntent {
            key,
            flags: 0,
            stable_id,
            traffic: None,
            layer_id: 1,
            feature_index: 0,
        };
        // Two tiles, one shared road each, plus a second road on the second:
        // three sightings, two rows, four slim refs.
        let mut builder = SharedBuilder::new();
        drain_shared_rows(
            &mut builder,
            vec![
                intent(key("Market Street", 100), 100),
                intent(key("Oak Ave", 200), 200),
            ],
            14,
        )
        .expect("first tile");
        drain_shared_rows(
            &mut builder,
            vec![
                intent(key("Market Street", 100), 100),
                intent(key("Elm St", 300), 300),
            ],
            14,
        )
        .expect("second tile repeats one row");
        // The same content twice: shared, not refused.
        let view =
            SharedView::parse(&builder.serialize().expect("section")).expect("parse");
        // The shared-section 4-tuple the dump prints: rows, strings, pools, id runs.
        assert_eq!(view.header.row_count, 3, "one row per distinct content: {view:?}");
        assert_eq!(view.header.string_count, 3, "three distinct names interned");
        assert_eq!(view.header.pool_count, 7, "every pool is written");
        assert_eq!(view.header.id_run_count, 3, "one stable id per row");
        assert_eq!(view.slim_refs.len(), 4, "one slim ref per tile-feature");
        assert_eq!(
            view.rows.iter().map(|r| r.logical_id).collect::<Vec<_>>(),
            vec![1, 2, 3],
            "sequential ids in first-sighting order",
        );
    }

    /// **Lane E flag.** `--shared-table` on changes what is interned, never what is drawn: a
    /// shared build parses, keeps the traffic id and every junction connector (the opt-out guards
    /// stay green), and is byte-identical run to run (intents are per-tile pure, the drain is
    /// tile-id ordered).
    ///
    /// NOTE (lane-A follow-up): this fixture carries a single traffic segment on purpose.
    /// `SharedBuilder` sorts rows by logical id and `encode_id_runs` then requires stable ids
    /// strictly increasing in that row order — but the splitmix traffic fold does not preserve row
    /// order across segments, so any multi-segment traffic fixture refuses with "shared ids must
    /// be strictly increasing in row order". That is a builder-contract issue (the fold fixed
    /// low32 collisions but invalidated the sortedness assumption), not a keying issue: the full
    /// u64s are distinct and correct, only their fold order is unsorted. Junction NONE-rows
    /// interleave freely, so the multi-row builder path is still exercised here; the multi-segment
    /// traffic case waits on lane-A relaxing the enforcement (zigzag already encodes signed
    /// deltas — only the `delta > 0` check assumes sortedness).
    #[test]
    fn a_shared_build_parses_and_is_deterministic() {
        use crate::schema::junction::junction_class;
        use crate::schema::traffic::{pack_component_id, traffic_class, unpack_component_id};
        let mut features = vec![Feature {
            class: traffic_class(),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.9996, 35.0004)]]),
            name: None,
            id: pack_component_id(7, 0),
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
            turn_fwd: Vec::new(),
            turn_bwd: Vec::new(),
            building: None,
            carriageway: tilecodec::mamaps::body::Carriageway::default(),
        }];
        // Two connectors meeting end to start: the shape a join would splice.
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
        features.push(connector(-120.0, -119.9996));
        features.push(connector(-119.9996, -119.9992));
        // A road and a building with no stable identity: skipped rows, never an error.
        features.push(Feature {
            class: Class::line(dict::LAYER_ROADS, crate::schema::kind("minor_road"), 12),
            geometry: Geometry::Lines(vec![vec![(-120.0, 35.0), (-119.99, 35.001)]]),
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
        });
        let shared = Settings { shared_table: true, ..settings(14, 14) };
        let first = build(&spilled(&features), &shared).expect("shared build").0;
        let second = build(&spilled(&features), &shared).expect("shared build").0;
        assert_eq!(first, second, "a shared build is deterministic");
        // Bodies are mixed v8 (traffic goes slim): resolve through the shared
        // section and compare against the v7 semantics below.
        let (header, _, _) = tilecodec::mamaps::read::open_prefix(&first).expect("prefix");
        let Some((off, len)) = header.shared_location() else {
            panic!("a --shared-table build carries a shared section");
        };
        let view = tilecodec::mamaps::shared::SharedView::parse(
            &first[off as usize..(off + len) as usize],
        )
        .expect("parse the shared section");
        let entries = tilecodec::mamaps::read::read_all(&first).expect("read");
        let mut segs = std::collections::BTreeSet::new();
        let mut connectors = 0usize;
        for (_, _, body) in &entries {
            let body = if body.len() >= 4 && body[0..3] == *b"MBD" && body[3] == 8 {
                tilecodec::mamaps::read::resolve_body(
                    &view,
                    &tilecodec::mamaps::read::SlimBody::parse(body).expect("slim parse"),
                )
                .expect("resolve")
            } else {
                Body::parse(body).expect("parse")
            };
            if let Some(layer) = body.layer(dict::LAYER_TRAFFIC) {
                for index in 0..layer.features.len() {
                    let id = body
                        .feature_id(dict::LAYER_TRAFFIC, index)
                        .expect("the traffic layer must carry an id table");
                    let (edge, seg) = unpack_component_id(id);
                    assert_eq!(edge, 7);
                    segs.insert(seg);
                }
            }
            if let Some(layer) = body.layer(dict::LAYER_JUNCTION) {
                connectors += layer.features.len();
            }
        }
        assert_eq!(segs.len(), 1, "the traffic segment keeps its component id");
        assert_eq!(connectors, 2, "coalescing must not chain the two connectors");
    }
}
