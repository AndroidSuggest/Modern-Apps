//! Tests for [`super::roads`], kept beside the schema rules they pin.
#[cfg(test)]
mod tests {
    use super::super::roads::{carriageway, classify, lane_count, turn_masks};
    use super::super::Class;
    use tilecodec::mamaps::body::{
        Carriageway, FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY, FLAG_IS_TUNNEL,
    };
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        classify(pairs)
    }

    fn names(class: &Class) -> (&'static str, &'static str) {
        (
            dict::KINDS[class.kind as usize - 1],
            dict::DETAILS[class.kind_detail as usize - 1],
        )
    }

    #[test]
    fn a_road_carries_a_coarse_kind_and_its_exact_osm_class() {
        let motorway = classify_tags(&[("highway", "motorway")]).expect("motorway");
        assert_eq!(names(&motorway), ("highway", "motorway"));
        assert_eq!(motorway.layer, dict::LAYER_ROADS);
        assert!(!motorway.area, "a road is a line");

        // Four OSM classes collapse to one drawn colour, and each keeps its own detail.
        for (value, detail) in
            [("trunk", "trunk"), ("primary", "primary"), ("secondary", "secondary"), ("tertiary", "tertiary")]
        {
            let class = classify_tags(&[("highway", value)]).expect(value);
            assert_eq!(names(&class), ("major_road", detail));
        }
    }

    /// **The fix this layer was redone for.** Without these three bits a bridge is tarmac laid over
    /// a river and a tunnel is a road on the surface.
    #[test]
    fn a_bridge_a_tunnel_and_a_slip_road_are_all_flagged() {
        let bridge = classify_tags(&[("highway", "primary"), ("bridge", "yes")]).expect("bridge");
        assert_eq!(bridge.flags, FLAG_IS_BRIDGE);
        let tunnel = classify_tags(&[("highway", "primary"), ("tunnel", "yes")]).expect("tunnel");
        assert_eq!(tunnel.flags, FLAG_IS_TUNNEL);
        // A slip road is spelled in the class, not in a tag of its own.
        let link = classify_tags(&[("highway", "motorway_link")]).expect("link");
        assert_eq!(link.flags, FLAG_IS_LINK);
        assert_eq!(names(&link), ("highway", "motorway_link"));
        // And they combine: a flyover slip road is both.
        let both = classify_tags(&[("highway", "motorway_link"), ("bridge", "viaduct")])
            .expect("both");
        assert_eq!(both.flags, FLAG_IS_LINK | FLAG_IS_BRIDGE);
        // Only `no` is not a bridge. `bridge=viaduct` and `bridge=boardwalk` are.
        let flat = classify_tags(&[("highway", "primary"), ("bridge", "no")]).expect("flat");
        assert_eq!(flat.flags, 0);
    }

    #[test]
    fn a_covered_way_counts_as_a_tunnel() {
        // `covered=yes` is how an arcade or a building passage is tagged when it is not a tunnel
        // proper, and it draws the same way.
        let covered = classify_tags(&[("highway", "footway"), ("covered", "yes")]).expect("covered");
        assert_eq!(covered.flags, FLAG_IS_TUNNEL);
    }

    /// The most consequential column in this crate. A motorway carries a continent; a service road
    /// is every driveway in the world and there are more of them than of everything else combined.
    #[test]
    fn the_minimum_zooms_run_from_continent_to_street() {
        let at = |value: &str| classify_tags(&[("highway", value)]).expect(value).min_zoom;
        assert_eq!(at("motorway"), 3, "a motorway is a continental feature");
        assert!(at("trunk") < at("primary"));
        assert!(at("primary") < at("secondary"));
        assert!(at("secondary") < at("tertiary"));
        assert!(at("tertiary") < at("residential"));
        assert!(at("residential") < at("service"));
        assert_eq!(at("service"), 14, "a driveway is street-level at best");
        // A slip road is not carried shallower than the road it joins, which would draw a
        // disembodied stub.
        assert!(at("motorway_link") > at("motorway"));
        assert!(at("primary_link") > at("primary"));
    }

    #[test]
    fn every_railway_draws_as_one_kind_with_its_own_detail() {
        for (value, expected) in
            [("rail", "rail"), ("subway", "subway"), ("tram", "tram"), ("narrow_gauge", "rail")]
        {
            let class = classify_tags(&[("railway", value)]).expect(value);
            assert_eq!(names(&class), ("rail", expected));
        }
        // A rail line is a country-scale feature; a tram is not.
        let rail = classify_tags(&[("railway", "rail")]).expect("rail");
        let tram = classify_tags(&[("railway", "tram")]).expect("tram");
        assert!(rail.min_zoom < tram.min_zoom);
    }

    #[test]
    fn a_runway_a_pier_and_a_ferry_are_carried_in_this_layer() {
        let runway = classify_tags(&[("aeroway", "runway")]).expect("runway");
        assert_eq!(names(&runway), ("other", "runway"));
        let pier = classify_tags(&[("man_made", "pier")]).expect("pier");
        assert_eq!(names(&pier), ("path", "pier"), "a pier is walkable");
        let ferry = classify_tags(&[("route", "ferry")]).expect("ferry");
        assert_eq!(dict::KINDS[ferry.kind as usize - 1], "ferry");
    }

    /// `highway` is asked before `railway`, which matters at a level crossing: a way tagged both is
    /// the road, because that is what carries traffic.
    #[test]
    fn highway_is_asked_before_railway() {
        let crossing = classify_tags(&[("railway", "rail"), ("highway", "residential")])
            .expect("crossing");
        assert_eq!(names(&crossing).0, "minor_road");
    }

    /// The carriageway lane count baked for the renderer's parallel-lane draw: the OSM `lanes`
    /// total, zero when absent, and capped so a mistagged count cannot become an absurd fan.
    #[test]
    fn the_lane_count_is_the_capped_osm_lanes_total() {
        assert_eq!(lane_count(&[("highway", "primary"), ("lanes", "4")][..]), 4);
        assert_eq!(lane_count(&[("highway", "residential")][..]), 0, "no tag, no lanes");
        assert_eq!(lane_count(&[("highway", "primary"), ("lanes", "0")][..]), 0);
        // A byte, and never past the shared MAX_LANES the router and pmtiles layer use.
        assert_eq!(
            lane_count(&[("highway", "motorway"), ("lanes", "999999999")][..]),
            osm_ingest::tags::MAX_LANES as u8,
        );
    }

    /// The per-lane turn masks reuse the routing graph's derivation, so the arrows drawn over a
    /// road agree with how it is routed: a oneway's plain `turn:lanes` is forward, a two-way's is
    /// neither, and the masks are the graph's `LANE_*` bits left to right.
    #[test]
    fn the_turn_masks_match_the_routing_graphs_derivation() {
        use osm_ingest::tags::{LANE_LEFT, LANE_RIGHT, LANE_THROUGH};
        // A oneway carries its plain `turn:lanes` as forward, none backward.
        let oneway: &[(&str, &str)] =
            &[("highway", "primary"), ("oneway", "yes"), ("turn:lanes", "left|through|through;right")];
        let (fwd, bwd) = turn_masks(oneway);
        assert_eq!(fwd, vec![LANE_LEFT, LANE_THROUGH, LANE_THROUGH | LANE_RIGHT]);
        assert!(bwd.is_empty());
        // Explicit forward/backward split on a two-way street.
        let split: &[(&str, &str)] = &[
            ("highway", "secondary"),
            ("turn:lanes:forward", "through|right"),
            ("turn:lanes:backward", "left"),
        ];
        let (fwd, bwd) = turn_masks(split);
        assert_eq!(fwd, vec![LANE_THROUGH, LANE_RIGHT]);
        assert_eq!(bwd, vec![LANE_LEFT]);
        // A road with no turn tags carries nothing either way.
        let (fwd, bwd) = turn_masks(&[("highway", "residential")][..]);
        assert!(fwd.is_empty() && bwd.is_empty());
    }

    /// A one-way is a flag rather than side-table data: it is what decides whether the carriageway
    /// has a centre line at all, and `coalesce` keys on flags — so a one-way and a two-way of the
    /// same class cannot merge into one feature wearing whichever direction came first.
    #[test]
    fn a_oneway_is_flagged_and_only_oneway_yes_counts() {
        let oneway = classify_tags(&[("highway", "primary"), ("oneway", "yes")]).expect("oneway");
        assert_eq!(oneway.flags, FLAG_IS_ONEWAY);
        // The same test the routing graph applies: `-1` is a direction rather than a flag, and
        // neither models it.
        for value in ["no", "-1", "reversible", "alternating", ""] {
            let class = classify_tags(&[("highway", "primary"), ("oneway", value)]).expect(value);
            assert_eq!(class.flags, 0, "oneway={value} is not a one-way here");
        }
        // And it combines with the other three.
        let ramp = classify_tags(&[("highway", "motorway_link"), ("oneway", "yes"), ("bridge", "yes")])
            .expect("ramp");
        assert_eq!(ramp.flags, FLAG_IS_LINK | FLAG_IS_BRIDGE | FLAG_IS_ONEWAY);
    }

    /// The directional split the surface renderer places a centre line from. A one-way puts every
    /// lane forward; a two-way needs one side tagged and infers the other from the total.
    #[test]
    fn the_carriageway_divides_the_lane_total_between_the_directions() {
        let split = |pairs: &[(&str, &str)]| carriageway(pairs);
        assert_eq!(
            split(&[("highway", "motorway"), ("oneway", "yes"), ("lanes", "3")]),
            Carriageway { forward: 3, backward: 0, solid_dividers: 0 },
            "every lane of a one-way runs forward",
        );
        assert_eq!(
            split(&[("highway", "primary"), ("lanes", "4"), ("lanes:backward", "1")]),
            Carriageway { forward: 3, backward: 1, solid_dividers: 0 },
            "one side tagged implies the other",
        );
        assert_eq!(
            split(&[
                ("highway", "primary"),
                ("lanes", "5"),
                ("lanes:forward", "3"),
                ("lanes:backward", "2"),
            ]),
            Carriageway { forward: 3, backward: 2, solid_dividers: 0 },
        );
    }

    /// **An absent split stays absent.** 2/2 for a road tagged only `lanes=4` would be a guess
    /// dressed as a survey, and the renderer already draws an unknown split down the middle. It is
    /// also what lets a tile of untagged residential streets carry no carriageway table at all.
    #[test]
    fn a_split_that_was_never_surveyed_or_does_not_add_up_is_left_unknown() {
        let split = |pairs: &[(&str, &str)]| carriageway(pairs);
        assert_eq!(
            split(&[("highway", "primary"), ("lanes", "4")]),
            Carriageway::default(),
            "a bare total says nothing about the division",
        );
        assert_eq!(split(&[("highway", "residential")][..]), Carriageway::default());
        // Mistagged: three forward and three backward of four lanes is a shape nothing can draw.
        assert_eq!(
            split(&[
                ("highway", "primary"),
                ("lanes", "4"),
                ("lanes:forward", "3"),
                ("lanes:backward", "3"),
            ]),
            Carriageway::default(),
        );
    }

    /// `change:lanes` marks the gaps a lane change is prohibited across, one bit per interior
    /// divider from the leftmost. Either lane can forbid the crossing.
    #[test]
    fn a_prohibited_lane_change_becomes_a_solid_divider() {
        let dividers = |pairs: &[(&str, &str)]| carriageway(pairs).solid_dividers;
        // Four lanes, three interior dividers. `not_right` on lane 0 makes divider 0 solid, `no`
        // on lane 2 makes dividers 1 and 2 solid.
        assert_eq!(
            dividers(&[
                ("highway", "primary"),
                ("lanes", "4"),
                ("change:lanes", "not_right|yes|no|yes"),
            ]),
            0b111,
        );
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "2"), ("change:lanes", "yes|yes")]),
            0,
            "a road nothing is prohibited on has no solid dividers",
        );
        // A list that does not describe this road's lanes describes some other road's, and half of
        // it applied here would draw solid lines down the wrong gaps.
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "4"), ("change:lanes", "no|no")]),
            0,
        );
        // The dividers are a property of the total, so they survive an unusable split.
        assert_eq!(
            dividers(&[("highway", "primary"), ("lanes", "3"), ("change:lanes", "yes|no|yes")]),
            0b11,
        );
    }

    #[test]
    fn an_unrecognised_value_is_not_a_road() {
        for tags in [
            vec![("highway", "bus_stop")],
            vec![("highway", "street_lamp")],
            vec![("railway", "abandoned")],
            vec![("aeroway", "gate")],
            vec![("man_made", "tower")],
            vec![("route", "bicycle")],
            vec![("building", "yes")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a road");
        }
    }
}
