#[cfg(test)]
mod tests {
    use super::*;
    use crate::testpbf;

    fn features(path: &Path) -> Vec<String> {
        std::fs::read_to_string(path)
            .unwrap()
            .lines()
            .map(str::to_string)
            .collect()
    }

    #[test]
    fn extracts_the_safety_layer_from_a_pbf() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_safety");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: None,
            },
        )
        .unwrap();

        // The fixture's four safety nodes: a speed camera, an ALPR, a stop sign
        // and traffic signals. Everything else in it belongs to other layers.
        assert_eq!(stats.features, 4);
        assert_eq!(stats.from_nodes, 4);
        assert_eq!((stats.from_ways, stats.from_relations), (0, 0));

        let lines = features(&out);
        assert_eq!(lines.len(), 4);
        let kinds: Vec<&str> = ["speed_camera", "alpr", "stop_sign", "traffic_signals"]
            .into_iter()
            .filter(|k| lines.iter().any(|l| l.contains(&format!("\"kind\":\"{k}\""))))
            .collect();
        assert_eq!(kinds, ["speed_camera", "alpr", "stop_sign", "traffic_signals"]);

        // Every line is a Point Feature with an osm_id in the node/N form.
        for l in &lines {
            assert!(l.starts_with("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\""), "{l}");
            assert!(l.contains("\"osm_id\":\"node/"), "{l}");
        }

        // The ALPR node keeps its operator and surveillance_type.
        let alpr = lines.iter().find(|l| l.contains("\"alpr\"")).unwrap();
        assert!(alpr.contains("\"operator\":\"Flock Safety\""), "{alpr}");
        assert!(alpr.contains("\"surveillance_type\":\"ALPR\""), "{alpr}");

        // The cafe node has a name and tags, but is not road furniture.
        assert!(!lines.iter().any(|l| l.contains("Corner Cafe")));
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_det");
        let run = |suffix: &str| {
            let out = dir.join(format!("safety{suffix}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options {
                    layer: Layer::Safety,
                    bbox: None,
                },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));
    }

    /// The point of `--threads`: it must change only how fast the work happens.
    /// Counts that do not divide the chunk count are the interesting ones.
    #[test]
    fn the_thread_cap_changes_no_bytes() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_threads");
        let run = |n: usize| {
            crate::par::set_threads(n);
            let out = dir.join(format!("safety.t{n}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options {
                    layer: Layer::Safety,
                    bbox: None,
                },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        let serial = run(1);
        for n in [2, 3, 7, 32] {
            assert_eq!(serial, run(n), "{n} threads perturbed the output");
        }
        crate::par::clear_threads();
    }

    #[test]
    fn a_bbox_that_excludes_everything_yields_an_empty_layer() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox");
        let out = dir.join("safety.geojsonseq");
        // The fixture sits near 37N 122W; this box is over the Atlantic.
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        // The count says "4 safety features were outside the box", which is what
        // distinguishes a bad bbox from a PBF with no cameras in it.
        assert_eq!(stats.outside_bbox, 4);
        assert_eq!(features(&out).len(), 0);
    }

    #[test]
    fn a_bbox_that_includes_everything_changes_nothing() {
        let (pbf_path, dir) = testpbf::write_layers_sample("extract_bbox_all");
        let out = dir.join("safety.geojsonseq");
        let stats = build(
            &pbf_path,
            &out,
            &Options {
                layer: Layer::Safety,
                bbox: Some(BBox::parse("-123,36,-121,38").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 4);
        assert_eq!(stats.outside_bbox, 0);
    }

    // --- maxspeed ---------------------------------------------------------

    fn extract_layer(tag: &str, layer: Layer) -> (Vec<String>, Stats) {
        let (pbf_path, dir) = testpbf::write_layers_sample(tag);
        let out = dir.join(format!("{}.geojsonseq", layer.name()));
        let stats = build(&pbf_path, &out, &Options { layer, bbox: None }).unwrap();
        (features(&out), stats)
    }

    #[test]
    fn extracts_the_maxspeed_layer_with_its_raw_values() {
        let (lines, stats) = extract_layer("extract_maxspeed", Layer::Maxspeed);
        // Two of the fixture's three highway ways carry a limit; the third does not.
        assert_eq!((stats.features, stats.from_ways), (2, 2));
        assert_eq!(stats.from_nodes, 0);
        assert_eq!(lines.len(), 2);

        // The whole point of the layer: the string survives verbatim.
        let mph = lines.iter().find(|l| l.contains("way/3001")).unwrap();
        assert!(mph.contains("\"maxspeed\":\"25 mph\""), "{mph}");
        assert!(mph.contains("\"highway\":\"residential\""), "{mph}");
        assert!(mph.contains("\"name\":\"Main St\""), "{mph}");
        assert!(mph.contains("\"type\":\"LineString\""), "{mph}");

        // maxspeed=none is kept as "none", and beats the directional tag.
        let none = lines.iter().find(|l| l.contains("way/3002")).unwrap();
        assert!(none.contains("\"maxspeed\":\"none\""), "{none}");
        assert!(!none.contains("30 mph"), "the direction-agnostic tag wins: {none}");

        // The way with no limit is absent.
        assert!(!lines.iter().any(|l| l.contains("way/3003")));

        // Geometry came from resolved node coordinates, not from nowhere.
        assert!(mph.contains("-122.43"), "{mph}");
    }

    // --- roads ------------------------------------------------------------

    #[test]
    fn extracts_the_roads_layer_with_lanes_speed_and_width() {
        let (lines, stats) = extract_layer("extract_roads", Layer::Roads);
        // All three of the fixture's highway ways, including the one with no
        // speed limit: `roads` is every road, not only the ones with a limit.
        assert_eq!((stats.features, stats.from_ways), (3, 3));
        assert_eq!(lines.len(), 3);

        // The fully-attributed motorway.
        let m = lines.iter().find(|l| l.contains("way/3002")).unwrap();
        assert!(m.contains("\"class\":1"), "motorway is class 1: {m}");
        assert!(m.contains("\"lanes\":3"), "{m}");
        // through|through|right as the graph's own LANE_* masks, left to right.
        assert!(
            m.contains(&format!(
                "\"turn_lanes_forward\":\"{}|{}|{}\"",
                crate::tags::LANE_THROUGH,
                crate::tags::LANE_THROUGH,
                crate::tags::LANE_RIGHT
            )),
            "{m}"
        );
        assert!(m.contains("\"oneway\":1"), "{m}");
        assert!(m.contains("\"width\":12.00"), "{m}");
        assert!(m.contains("\"bridge\":1"), "{m}");
        assert!(m.contains("\"layer\":1"), "{m}");
        // `maxspeed=none` keeps its string and gets no number: it is not 0 km/h.
        assert!(m.contains("\"maxspeed\":\"none\""), "{m}");
        assert!(!m.contains("maxspeed_kmh"), "{m}");

        // A posted mph limit arrives as both forms.
        let r = lines.iter().find(|l| l.contains("way/3001")).unwrap();
        assert!(r.contains("\"class\":7"), "residential is class 7: {r}");
        assert!(r.contains("\"maxspeed\":\"25 mph\""), "{r}");
        assert!(r.contains("\"maxspeed_kmh\":40"), "{r}");
        assert!(r.contains("\"type\":\"LineString\""), "{r}");
        // Geometry came from resolved node coordinates.
        assert!(r.contains("-122.43"), "{r}");

        // And the service road with nothing on it is class + osm_id only.
        let s = lines.iter().find(|l| l.contains("way/3003")).unwrap();
        assert!(s.contains("\"properties\":{\"class\":8,\"osm_id\":\"way/3003\"}"), "{s}");

        // The railway ways and the platform are not roads.
        assert!(!lines.iter().any(|l| l.contains("way/5001")));
    }

    #[test]
    fn roads_is_deterministic_and_bbox_filtered() {
        let (pbf_path, dir) = testpbf::write_layers_sample("det_roads");
        let run = |suffix: &str| {
            let out = dir.join(format!("roads{suffix}.geojsonseq"));
            build(&pbf_path, &out, &Options { layer: Layer::Roads, bbox: None }).unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));

        let stats = build(
            &pbf_path,
            &dir.join("empty.geojsonseq"),
            &Options {
                layer: Layer::Roads,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        assert_eq!(stats.outside_bbox, 3);
    }

    // --- transit_lines ----------------------------------------------------

    #[test]
    fn extracts_the_transit_lines_layer_from_ways_and_relations() {
        let (lines, stats) = extract_layer("extract_transit", Layer::TransitLines);
        // Two railway ways plus one route relation. The platform way and the bus
        // route are both dropped.
        assert_eq!(stats.features, 3);
        assert_eq!((stats.from_ways, stats.from_relations), (2, 1));
        assert_eq!(lines.len(), 3);

        let subway = lines.iter().find(|l| l.contains("way/5001")).unwrap();
        assert!(subway.contains("\"kind\":\"subway\""), "{subway}");
        assert!(subway.contains("\"name\":\"Market St Subway\""), "{subway}");
        assert!(subway.contains("\"type\":\"LineString\""), "{subway}");

        // narrow_gauge folds into rail.
        let ng = lines.iter().find(|l| l.contains("way/5002")).unwrap();
        assert!(ng.contains("\"kind\":\"rail\""), "{ng}");

        assert!(!lines.iter().any(|l| l.contains("way/5003")), "no platform");
        assert!(!lines.iter().any(|l| l.contains("relation/9002")), "no bus route");
    }

    #[test]
    fn a_route_relation_becomes_an_unstitched_multilinestring() {
        let (lines, _) = extract_layer("extract_transit_rel", Layer::TransitLines);
        let route = lines.iter().find(|l| l.contains("relation/9001")).unwrap();
        assert!(route.contains("\"type\":\"MultiLineString\""), "{route}");
        assert!(route.contains("\"kind\":\"subway\""), "{route}");
        // The tags GDAL used to bury in an `other_tags` HSTORE are now just tags.
        assert!(route.contains("\"name\":\"Red Line\""), "{route}");
        assert!(route.contains("\"ref\":\"Red\""), "{route}");
        assert!(route.contains("\"colour\":\"#DA291C\""), "{route}");

        // Two member ways, so two parts: `[[[...]],[[...]]]` is three opening
        // brackets before the first coordinate pair.
        let at = route.find("\"coordinates\":").unwrap();
        assert!(route[at..].starts_with("\"coordinates\":[[["), "{route}");
        // Counting the part separators: two parts means one `]],[[`.
        assert_eq!(route.matches("]],[[").count(), 1, "two parts: {route}");
    }

    #[test]
    fn a_routes_platform_members_are_not_drawn_as_track() {
        // The fixture's route relation carries a third member: a *closed*
        // `railway=platform` way, `role=platform`. Assembling geometry from every
        // member drew a box around the station.
        let (lines, stats) = extract_layer("extract_transit_platform", Layer::TransitLines);
        let route = lines.iter().find(|l| l.contains("relation/9001")).unwrap();
        assert_eq!(route.matches("]],[[").count(), 1, "the two path members only: {route}");
        // The platform's corners sit at 122.404-122.405W, nowhere near the path.
        assert!(!route.contains("-122.4050000"), "platform outline drawn: {route}");
        assert!(!route.contains("-122.4040000"), "platform outline drawn: {route}");
        // And it is not a feature in its own right either, roled or not.
        assert!(!lines.iter().any(|l| l.contains("way/5004")));
        assert_eq!((stats.from_ways, stats.from_relations), (2, 1));
    }

    #[test]
    fn the_line_layers_are_deterministic_and_bbox_filtered() {
        for layer in [Layer::Maxspeed, Layer::TransitLines] {
            let (pbf_path, dir) = testpbf::write_layers_sample(&format!("det_{}", layer.name()));
            let run = |suffix: &str| {
                let out = dir.join(format!("{}{suffix}.geojsonseq", layer.name()));
                build(&pbf_path, &out, &Options { layer, bbox: None }).unwrap();
                std::fs::read(out).unwrap()
            };
            assert_eq!(run("a"), run("b"), "{} is not deterministic", layer.name());

            // The fixture's ways sit near 37.79N 122.42W; this box is Atlantic.
            let out = dir.join("empty.geojsonseq");
            let stats = build(
                &pbf_path,
                &out,
                &Options {
                    layer,
                    bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
                },
            )
            .unwrap();
            assert_eq!(stats.features, 0, "{}", layer.name());
            assert!(stats.outside_bbox > 0, "{}", layer.name());

            // And a box that contains them changes nothing.
            let out = dir.join("all.geojsonseq");
            let all = build(
                &pbf_path,
                &out,
                &Options {
                    layer,
                    bbox: Some(BBox::parse("-123,36,-121,38").unwrap()),
                },
            )
            .unwrap();
            assert_eq!(all.outside_bbox, 0, "{}", layer.name());
            assert!(all.features > 0, "{}", layer.name());
        }
    }

    #[test]
    fn a_transit_lines_layer_with_no_relations_is_reported() {
        // Ways-only is the shape a GDAL-less legacy build produces: the right
        // feature count, and no `colour` anywhere, so every line renders grey.
        let ways_only = Stats { features: 900, from_ways: 900, ..Default::default() };
        assert!(ways_only.missing_half(Layer::TransitLines).is_some());
        // Both halves present, or nothing at all, are both fine.
        let both = Stats {
            features: 900,
            from_ways: 800,
            from_relations: 100,
            ..Default::default()
        };
        assert_eq!(both.missing_half(Layer::TransitLines), None);
        assert_eq!(Stats::default().missing_half(Layer::TransitLines), None);
        // And the check is specific to the two-source layer.
        assert_eq!(ways_only.missing_half(Layer::Maxspeed), None);
    }

    // --- admin_city -------------------------------------------------------

    #[test]
    fn assembles_an_admin_city_boundary_from_its_member_ways() {
        let (lines, stats) = extract_layer("extract_admin", Layer::AdminCity);
        // Only the admin_level=8 relation. The county at level 6 is dropped.
        assert_eq!(stats.features, 1, "{lines:?}");
        assert_eq!((stats.from_relations, stats.from_ways), (1, 0));

        let f = &lines[0];
        assert!(f.contains("\"admin_level\":8"), "a number, not a string: {f}");
        assert!(f.contains("\"name\":\"Oakland\""), "{f}");
        assert!(f.contains("\"osm_id\":\"relation/9101\""), "{f}");
        // No name:en on the fixture, and the city level has no fallback to `name`.
        assert!(!f.contains("name_en"), "{f}");
        assert!(!lines.iter().any(|l| l.contains("Alameda County")));
    }

    #[test]
    fn an_admin_outer_ring_is_stitched_and_its_hole_is_kept() {
        let (lines, _) = extract_layer("extract_admin_rings", Layer::AdminCity);
        let f = &lines[0];
        // One outer ring plus one hole: a Polygon, not a MultiPolygon.
        assert!(f.contains("\"type\":\"Polygon\""), "{f}");
        // Two rings means one `]],[[` separator between them.
        assert_eq!(f.matches("]],[[").count(), 1, "exterior plus one hole: {f}");

        // The exterior came from two ways -- one roled `outer`, one unroled, and one
        // of them traversed backwards -- so all four corners must be present and the
        // ring must close on the corner it started from.
        let at = f.find("\"coordinates\":[[").unwrap() + "\"coordinates\":[".len();
        let end = at + f[at..].find("]],").unwrap() + 1;
        let outer = &f[at..end];
        for corner in [
            "[-122.4000000,37.8000000]",
            "[-122.3600000,37.8000000]",
            "[-122.3600000,37.8400000]",
            "[-122.4000000,37.8400000]",
        ] {
            assert!(outer.contains(corner), "missing outer corner {corner}: {outer}");
        }
        assert_eq!(outer.matches("[-122.4000000,37.8000000]").count(), 2, "closed: {outer}");

        // The hole is the second ring, and is inside the first.
        let hole = &f[end..];
        assert!(hole.contains("[-122.3900000,37.8100000]"), "{hole}");
    }

    #[test]
    fn admin_city_is_deterministic_and_bbox_filtered() {
        let (pbf_path, dir) = testpbf::write_layers_sample("det_admin");
        let run = |suffix: &str| {
            let out = dir.join(format!("admin{suffix}.geojsonseq"));
            build(
                &pbf_path,
                &out,
                &Options { layer: Layer::AdminCity, bbox: None },
            )
            .unwrap();
            std::fs::read(out).unwrap()
        };
        assert_eq!(run("a"), run("b"));

        let stats = build(
            &pbf_path,
            &dir.join("empty.geojsonseq"),
            &Options {
                layer: Layer::AdminCity,
                bbox: Some(BBox::parse("-30,20,-20,30").unwrap()),
            },
        )
        .unwrap();
        assert_eq!(stats.features, 0);
        assert_eq!(stats.outside_bbox, 1);
    }

    #[test]
    fn args_need_a_layer_and_an_out() {
        let ok = parse_args(&[
            "in.pbf".into(),
            "--layer".into(),
            "safety".into(),
            "--out".into(),
            "s.geojsonseq".into(),