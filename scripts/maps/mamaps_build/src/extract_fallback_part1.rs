#[cfg(test)]
mod tests {
    use super::*;

    /// A closed square ring, `min..max` in both axes.
    fn square(min: f64, max: f64) -> Polygon {
        vec![vec![(min, min), (max, min), (max, max), (min, max), (min, min)]]
    }

    fn boundary(id: u64, level: u16, polygons: Vec<Polygon>) -> Boundary {
        Boundary::new(id, level, polygons)
    }
    /// The score is area × headcount² in log space, population weighted double so a
    /// microstate's headcount can actually lift it. Measured scores: Russia 28.63,
    /// USA 29.26, France 26.57, Mongolia 24.32, Singapore 21.73, Liechtenstein
    /// 16.56, Nauru 14.82, Vatican City 10.62 — every expectation asserted.
    #[test]
    fn country_zoom_scores_area_times_headcount() {
        // z0: score ≥ 26 — vast and populous, plus populous western Europe (a z0
        // world tile holds four labels that never share a tile, so no collision).
        assert_eq!(country_zoom(289.0, 55.0, 144_000_000), 0, "Russia");
        assert_eq!(country_zoom(170.0, 40.0, 335_000_000), 0, "USA");
        assert_eq!(country_zoom(9.5, 47.0, 68_000_000), 0, "France");
        assert_eq!(country_zoom(6.2, 51.0, 84_000_000), 0, "Germany");
        // z1: 23.5–26. Switzerland (23.67) leads Mongolia (24.32)? No: 24.32 ≥
        // 23.5 → z1 as well. Both vast-and-empty vs small-and-rich meet here.
        assert_eq!(country_zoom(27.0, 46.0, 3_000_000), 1, "Mongolia");
        assert_eq!(country_zoom(0.72, 46.8, 8_800_000), 1, "Switzerland");
        // z2: 20–23.5 — tiny but millions of people.
        assert_eq!(country_zoom(0.012, 1.35, 6_000_000), 2, "Singapore");
        // z3: 14.5–20 — everything else with a pulse, on by z3.
        assert_eq!(country_zoom(0.0028, 47.1, 39_000), 3, "Liechtenstein");
        assert_eq!(country_zoom(0.00037, -0.5, 12_000), 3, "Nauru");
        // z4 backstop, never later: tiny AND near-empty.
        assert_eq!(country_zoom(0.000007, 41.9, 800), 4, "Vatican City");
        // Antarctica (11.71): vast but uncounted. The backstop is honest — no
        // headcount exists to score, and area alone must not outrank every
        // populated microstate.
        assert_eq!(country_zoom(240.0, -80.0, 0), 4, "Antarctica");
    }

    #[test]
    fn nested_shapes_link_each_place_kind_to_its_own_level() {
        // Country 0..100 (level 2), region 10..50 (level 4), locality 20..30 (level 8).
        let country = boundary(1, 2, vec![square(0.0, 100.0)]);
        let region = boundary(2, 4, vec![square(10.0, 50.0)]);
        let locality = boundary(3, 8, vec![square(20.0, 30.0)]);
        let all = [&country, &region, &locality];
        // A country label inside all three still links the country: bands isolate levels.
        assert_eq!(select_link((25.0, 25.0), Band::Country, &all), Some(1));
        assert_eq!(select_link((25.0, 25.0), Band::Region, &all), Some(2));
        assert_eq!(select_link((25.0, 25.0), Band::Locality, &all), Some(3));
        // A region label outside the region links nothing, not the country it sits in —
        // bands never fall back across levels.
        assert_eq!(select_link((5.0, 5.0), Band::Region, &all), None);
        assert_eq!(select_link((5.0, 5.0), Band::Country, &all), Some(1));
    }

    #[test]
    fn smallest_in_band_container_wins() {
        // Two localities nested: the tighter one wins.
        let big = boundary(1, 8, vec![square(20.0, 40.0)]);
        let small = boundary(2, 8, vec![square(25.0, 35.0)]);
        let both = [&big, &small];
        assert_eq!(select_link((30.0, 30.0), Band::Locality, &both), Some(2));
        // Order-independent.
        let swapped = [&small, &big];
        assert_eq!(select_link((30.0, 30.0), Band::Locality, &swapped), Some(2));
    }

    #[test]
    fn county_levels_match_nothing() {
        // Levels 5–6 are the documented gap: a place inside only a county links nowhere.
        let county = boundary(1, 6, vec![square(0.0, 100.0)]);
        let only = [&county];
        assert_eq!(select_link((50.0, 50.0), Band::Country, &only), None);
        assert_eq!(select_link((50.0, 50.0), Band::Region, &only), None);
        assert_eq!(select_link((50.0, 50.0), Band::Locality, &only), None);
        // A county boundary never steals a pick even when it is the tightest container.
        let country = boundary(2, 2, vec![square(0.0, 100.0)]);
        let mixed = [&county, &country];
        assert_eq!(select_link((50.0, 50.0), Band::Country, &mixed), Some(2));
    }

    #[test]
    fn near_tie_links_nothing() {
        // Two identical claims: areas equal, diff 0% — ambiguous.
        let first = boundary(1, 8, vec![square(0.0, 10.0)]);
        let second = boundary(2, 8, vec![square(0.0, 10.0)]);
        assert_eq!(select_link((5.0, 5.0), Band::Locality, &[&first, &second]), None);
        // Areas within 1% (10.0 vs 10.04 wide — 0.8% area diff) still ambiguous.
        let narrow = boundary(3, 8, vec![square(0.0, 10.0)]);
        let wide = boundary(4, 8, vec![square(0.0, 10.04)]);
        assert_eq!(select_link((5.0, 5.0), Band::Locality, &[&narrow, &wide]), None);
        // A clear 2x nesting is not a tie.
        let big = boundary(5, 8, vec![square(0.0, 20.0)]);
        assert_eq!(select_link((5.0, 5.0), Band::Locality, &[&narrow, &big]), Some(3));
    }

    #[test]
    fn a_point_on_a_ring_edge_links_nothing() {
        let square8 = boundary(1, 8, vec![square(0.0, 10.0)]);
        let only = [&square8];
        // On the west edge: ray-casting is a coin flip, so this is a deliberate miss.
        assert_eq!(select_link((0.0, 5.0), Band::Locality, &only), None);
        // Just inside still links.
        assert_eq!(select_link((0.5, 5.0), Band::Locality, &only), Some(1));
    }

    #[test]
    fn holes_punch_out() {
        // A locality donut: exterior 0..10 with a 4..6 hole. A place in the hole is not inside.
        let donut = boundary(1, 8, vec![vec![
            vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0), (0.0, 10.0), (0.0, 0.0)],
            vec![(4.0, 4.0), (6.0, 4.0), (6.0, 6.0), (4.0, 6.0), (4.0, 4.0)],
        ]]);
        let only = [&donut];
        assert_eq!(select_link((2.0, 2.0), Band::Locality, &only), Some(1));
        assert_eq!(select_link((5.0, 5.0), Band::Locality, &only), None);
    }

    #[test]
    fn sub_locality_places_never_link() {
        assert_eq!(band_for_place_kind("neighbourhood"), None);
        assert_eq!(band_for_place_kind("macrohood"), None);
        assert_eq!(band_for_place_kind("country"), Some(Band::Country));
        assert_eq!(band_for_place_kind("region"), Some(Band::Region));
        assert_eq!(band_for_place_kind("locality"), Some(Band::Locality));
    }

    /// End to end over a spilled store: member links win, member-less places gain links
    /// (including a way-id place the node-keyed member map could never reach), and the county
    /// gap still misses.
    #[test]
    fn member_links_win_and_member_less_places_gain_links() {
        use crate::extract::{tagged_id, Feature, ELEMENT_NODE, ELEMENT_RELATION, ELEMENT_WAY};

        fn place(id: u64, place_tag: &str, point: (f64, f64)) -> Feature {
            let class = crate::schema::places::classify(
                &[("place", place_tag), ("name", "Test")] as &[(&str, &str)],
            )
            .expect("classified");
            Feature {
                class,
                geometry: Geometry::Points(vec![point]),
                name: Some("Test".to_string()),
                id,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
                building: None,
            }
        }

        fn shape(id: u64, level: &str, ring: Polygon) -> Feature {
            let class = crate::schema::boundaries::region_area(
                &[("boundary", "administrative"), ("admin_level", level)] as &[(&str, &str)],
                false,
            )
            .expect("region shape");
            Feature {
                class,
                geometry: Geometry::Polygons(vec![ring]),
                name: None,
                id,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
                turn_fwd: Vec::new(),
                turn_bwd: Vec::new(),
                carriageway: tilecodec::mamaps::body::Carriageway::default(),
                building: None,
            }
        }

        // A city boundary (level 8) 20..30 and a county boundary (level 6) 0..100.
        let city_id = tagged_id(201, ELEMENT_RELATION);
        let county_id = tagged_id(202, ELEMENT_RELATION);
        let member_target = tagged_id(203, ELEMENT_RELATION);
        // A member-linked town: fallback would pick the city, the member must win.
        let linked_town = tagged_id(101, ELEMENT_NODE);
        // A member-less town (node) and a member-less village mapped as a way.
        let bare_town = tagged_id(102, ELEMENT_NODE);
        let way_village = tagged_id(103, ELEMENT_WAY);
        // A country label sitting where only the county covers it: must stay unlinked.
        let bare_country = tagged_id(104, ELEMENT_NODE);
        let features = vec![
            shape(city_id, "8", square(20.0, 30.0)),
            shape(county_id, "6", square(0.0, 100.0)),
            place(linked_town, "town", (25.0, 25.0)),
            place(bare_town, "town", (26.0, 26.0)),
            place(way_village, "village", (27.0, 27.0)),
            place(bare_country, "country", (50.0, 50.0)),
        ];
        let store = crate::store::Store::of(&features).expect("spilled");

        let mut links: HashMap<u64, u64> = HashMap::new();
        links.insert(linked_town, member_target);
        let stats = extend_region_links(&store, &mut links).expect("fallback");

        assert_eq!(links.get(&linked_town), Some(&member_target), "member wins over fallback");
        assert_eq!(links.get(&bare_town), Some(&city_id), "member-less town gains the city");
        assert_eq!(links.get(&way_village), Some(&city_id), "way-id place links by position");
        assert!(!links.contains_key(&bare_country), "county gap: no link for the country label");

        assert_eq!(stats.places_total, 4);
        assert_eq!(stats.member_linked, 1);
        assert_eq!(stats.fallback_linked, 2);
        assert_eq!(stats.fallback_missed, 1);
    }
}
