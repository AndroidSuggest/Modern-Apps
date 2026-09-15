#[cfg(test)]
mod tests {
    use super::*;
    use tilecodec::mamaps::dict;

    fn classify_tags(pairs: &[(&str, &str)]) -> Option<Class> {
        super::classify(pairs)
    }

    /// **What the style actually reads.** `boundaries_country` filters `kind_detail <= 2`, so the
    /// level has to arrive as a number that compares correctly — not as an interned id whose
    /// ordering is an accident of table position.
    #[test]
    fn the_level_is_carried_as_a_number_not_an_id() {
        for level in 1..=8u16 {
            let class =
                classify_tags(&[("boundary", "administrative"), ("admin_level", &level.to_string())])
                    .expect("a level");
            assert_eq!(class.kind_detail, level, "the field holds the level itself");
            assert_eq!(class.flags, FLAG_DETAIL_NUMERIC, "and says so");
        }
        // The comparison the style makes.
        let country = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        let city = classify_tags(&[("boundary", "administrative"), ("admin_level", "8")])
            .expect("city");
        assert!(country.kind_detail <= 2, "drawn by boundaries_country");
        assert!(city.kind_detail > 2, "drawn by the other layer");
    }

    #[test]
    fn a_level_maps_to_the_name_upstream_uses() {
        let name = |level: &str| {
            let class = classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level);
            dict::KINDS[class.kind as usize - 1]
        };
        assert_eq!(name("2"), "country");
        assert_eq!(name("4"), "region");
        assert_eq!(name("6"), "county");
        assert_eq!(name("8"), "locality");
    }

    /// A relation yields the border *and* the region's shape, as two features.
    ///
    /// They cannot be one. The border must stay a line, because clipping a polygon to a tile adds
    /// segments along the tile edge and the style's `boundaries` layer strokes polygon outlines —
    /// which drew a grid across the whole map when this was tried as a single area feature.
    #[test]
    fn a_relation_yields_a_border_line_and_a_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "8")];
        let line = classify_tags(&tags).expect("the border");
        assert!(!line.area, "the border is drawn, so it stays a line");

        let shape = super::region_area(&tags[..], false).expect("the region");
        assert!(shape.area, "the region is a shape, and nothing draws it");
        assert_eq!(shape.layer, dict::LAYER_BOUNDARIES);
        assert_eq!(
            dict::KINDS[shape.kind as usize - 1],
            "region_area",
            "a kind the boundaries layer's whitelist excludes, or it would be stroked",
        );
        assert_eq!(shape.kind_detail, line.kind_detail, "same level, so the mask can tell a city from a country");
        assert!(shape.min_zoom < line.min_zoom, "a mask is wanted where the region fits on screen");
    }

    /// A single way is one segment of a border and encloses nothing.
    #[test]
    fn a_way_has_no_region_shape() {
        let tags = [("boundary", "administrative"), ("admin_level", "2")];
        assert!(super::region_area(&tags[..], true).is_none());
        assert!(super::region_area(&tags[..], false).is_some());
    }

    /// A border is a line even when it closes. Filling it would paint over every layer inside the
    /// country — and a polygon clipped to a tile grows edges along the tile boundary, which the
    /// style's `line` layers stroke as a grid across the map. That is not theoretical: it was
    /// tried, and the tile grid was immediately visible.
    #[test]
    fn a_boundary_is_never_an_area() {
        let class = classify_tags(&[("boundary", "administrative"), ("admin_level", "2")])
            .expect("country");
        assert!(!class.area);
        assert_eq!(class.layer, dict::LAYER_BOUNDARIES);
    }

    #[test]
    fn a_country_border_is_carried_at_world_zoom_and_a_city_limit_is_not() {
        let at = |level: &str| {
            classify_tags(&[("boundary", "administrative"), ("admin_level", level)])
                .expect(level)
                .min_zoom
        };
        assert_eq!(at("2"), 0, "a country border carries a world tile");
        assert!(at("2") < at("4"));
        assert!(at("4") < at("6"));
        assert!(at("6") < at("8"));
        assert_eq!(at("8"), 9, "there are a hundred thousand city limits");
    }

    #[test]
    fn a_level_below_a_city_is_not_drawn() {
        // Level 9 and 10 are wards and neighbourhoods: real data, and a line nobody wants.
        for level in ["9", "10", "11"] {
            assert!(
                classify_tags(&[("boundary", "administrative"), ("admin_level", level)]).is_none(),
                "level {level} should not be drawn",
            );
        }
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "0")]).is_none(),
            "level 0 is not a level",
        );
    }

    #[test]
    fn a_maritime_administrative_boundary_is_not_drawn() {
        // An EEZ / territorial-water limit: `boundary=administrative` over water, tagged
        // `maritime=yes`. The reference style's boundary layers show inland/coastline admin
        // only, so the tiler drops these rather than drawing lines across open sea.
        assert!(
            classify_tags(&[
                ("boundary", "administrative"),
                ("admin_level", "2"),
                ("maritime", "yes"),
            ])
            .is_none(),
            "a maritime boundary should not be a boundary",
        );
        // And the inland equivalent still classifies.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", "2")]).is_some(),
            "an inland country border still counts",
        );
    }

    /// The convention is a property of a *country*, so nothing below level 2 carries one: a state
    /// has no driving side of its own anywhere this build covers.
    #[test]
    fn only_a_level_two_relation_with_an_iso_code_is_a_country() {
        let code = |pairs: &[(&str, &str)]| super::country_code(pairs).map(str::to_string);
        assert_eq!(
            code(&[("boundary", "administrative"), ("admin_level", "2"), ("ISO3166-1", "JP")]),
            Some("JP".to_string()),
        );
        // Whitespace around a level is common in real data, as `classify` already allows.
        assert_eq!(
            code(&[("boundary", "administrative"), ("admin_level", " 2 "), ("ISO3166-1", "GB")]),
            Some("GB".to_string()),
        );
        for tags in [
            // A state, which has no convention of its own.
            vec![("boundary", "administrative"), ("admin_level", "4"), ("ISO3166-1", "US")],
            // A country with no code to look up.
            vec![("boundary", "administrative"), ("admin_level", "2")],
            // A three-letter code is the alpha-3 field under the wrong key.
            vec![("boundary", "administrative"), ("admin_level", "2"), ("ISO3166-1", "JPN")],
            vec![("boundary", "protected_area"), ("admin_level", "2"), ("ISO3166-1", "JP")],
            vec![("ISO3166-1", "JP")],
        ] {
            assert!(super::country_code(&tags[..]).is_none(), "{tags:?} is not a country");
        }
    }

    #[test]
    fn the_convention_table_carries_the_driving_side_and_the_centre_line_colour() {
        let at = super::convention_for;
        assert_eq!(at("GB"), MarkingConvention { left_hand: true, yellow_centre: false });
        assert_eq!(at("JP"), MarkingConvention { left_hand: true, yellow_centre: true });
        assert_eq!(at("US"), MarkingConvention { left_hand: false, yellow_centre: true });
        assert_eq!(at("FR"), MarkingConvention::default(), "right-hand and white");
        // An unrecognised code draws the same as an unresolved tile, rather than differently for
        // no reason a reader could see.
        assert_eq!(at("ZZ"), MarkingConvention::default());
        assert_eq!(at("gb"), at("GB"), "OSM's own casing is not the only casing in the wild");
    }

    /// A square degree box as the relation assembler would hand one over: one polygon, one closed
    /// outer ring, lon/lat.
    fn box_over(west: f64, south: f64, east: f64, north: f64) -> Vec<Vec<Vec<(f64, f64)>>> {
        vec![vec![vec![
            (west, south),
            (east, south),
            (east, north),
            (west, north),
            (west, south),
        ]]]
    }

    /// The tile a lon/lat falls in at `z`.
    fn tile_at(lon: f64, lat: f64, z: u8) -> (u64, u64) {
        let (x, y) = tile_build::geom::project(lon, lat, z);
        (x as u64, y as u64)
    }

    /// **Resolved coarse, inherited downward.** A California build is 12.7 M z14 tiles and the
    /// answer is the same across all of them, so the country is decided once per z6 cell and every
    /// tile below reads its ancestor's.
    #[test]
    fn a_country_claims_the_coarse_tiles_it_covers_and_every_tile_below_inherits() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));

        let inside = MarkingConvention { left_hand: true, yellow_centre: true };
        let (x, y) = tile_at(137.0, 37.0, 14);
        assert_eq!(grid.at_tile(14, x, y), inside, "a z14 tile reads its z6 ancestor");
        // Its neighbour is a different z14 tile in the same coarse cell, so it must agree.
        assert_eq!(grid.at_tile(14, x + 1, y + 1), inside);
        let (cx, cy) = tile_at(137.0, 37.0, COARSE_ZOOM);
        assert_eq!(grid.at_tile(COARSE_ZOOM, cx, cy), inside, "and so does the cell itself");
    }

    /// Nothing claimed it, so it draws right-hand and white — which is most of the world by land
    /// area and the safer thing to be wrong about.
    #[test]
    fn an_unclaimed_tile_falls_back_to_right_hand_and_white() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));
        let (x, y) = tile_at(-100.0, 40.0, 14);
        assert_eq!(grid.at_tile(14, x, y), MarkingConvention::default());
        assert!(!Conventions::default().at_tile(14, 0, 0).left_hand, "and so does an empty grid");
    }

    /// A coastal or border cell is claimed by every country with a boundary vertex in it, and only
    /// one of them covers its centre. Covering the centre is the unambiguous claim, so it wins
    /// whatever order the relations arrived in.
    #[test]
    fn covering_a_cells_centre_outranks_merely_having_a_border_in_it() {
        let mut grid = Conventions::default();
        // A shape too small to cover any cell centre: it can only ever be a border claim.
        grid.add("JP", &box_over(-100.02, 40.0, -100.0, 40.02));
        // And a shape that swallows it whole.
        grid.add("US", &box_over(-110.0, 35.0, -95.0, 45.0));
        let (x, y) = tile_at(-100.01, 40.01, 14);
        assert_eq!(
            grid.at_tile(14, x, y),
            MarkingConvention { left_hand: false, yellow_centre: true },
            "the interior claim came second and still won",
        );
    }

    /// The store index has to reproduce the run that built the spill. A grid that did not survive
    /// the index would leave a build drawing every road right-hand and white, which is not
    /// a difference anything downstream could see.
    #[test]
    fn the_grid_survives_the_store_index() {
        let mut grid = Conventions::default();
        grid.add("JP", &box_over(130.0, 30.0, 145.0, 45.0));
        grid.add("GB", &box_over(-8.0, 50.0, 2.0, 59.0));
        assert!(!grid.is_empty());

        let bytes = grid.to_bytes();
        let (back, used) = Conventions::from_bytes(&bytes).expect("read the grid back");
        assert_eq!(used, bytes.len(), "the whole grid was consumed");
        for (lon, lat) in [(137.0, 37.0), (-3.0, 54.0), (-100.0, 40.0)] {
            let (x, y) = tile_at(lon, lat, 14);
            assert_eq!(back.at_tile(14, x, y), grid.at_tile(14, x, y), "at {lon},{lat}");
        }
        // Ascending by cell key, so two runs of the same build write the same index bytes.
        assert_eq!(bytes, back.to_bytes());
    }

    #[test]
    fn a_truncated_convention_grid_is_an_error_rather_than_a_short_read() {
        let mut grid = Conventions::default();
        grid.add("GB", &box_over(-8.0, 50.0, 2.0, 59.0));
        let bytes = grid.to_bytes();
        assert!(Conventions::from_bytes(&bytes[..bytes.len() - 1]).is_err(), "a cut cell");
        assert!(Conventions::from_bytes(&bytes[..2]).is_err(), "a cut count");
        // And the empty grid, which is what a build with no country relation writes.
        let (empty, used) = Conventions::from_bytes(&Conventions::default().to_bytes())
            .expect("an empty grid is readable");
        assert!(empty.is_empty());
        assert_eq!(used, 4);
    }

    #[test]
    fn only_an_administrative_boundary_with_a_readable_level_counts() {
        for tags in [
            // A protected area or a maritime boundary is a boundary and not an administrative one.
            vec![("boundary", "protected_area"), ("admin_level", "2")],
            vec![("boundary", "maritime"), ("admin_level", "2")],
            // Administrative but with no level, or an unreadable one.
            vec![("boundary", "administrative")],
            vec![("boundary", "administrative"), ("admin_level", "")],
            vec![("boundary", "administrative"), ("admin_level", "two")],
            vec![("boundary", "administrative"), ("admin_level", "4;6")],
            vec![("admin_level", "2")],
            vec![],
        ] {
            assert!(classify_tags(&tags).is_none(), "{tags:?} should not be a boundary");
        }
        // Whitespace around a level is common in real data and is not a reason to drop a border.
        assert!(
            classify_tags(&[("boundary", "administrative"), ("admin_level", " 4 ")]).is_some(),
            "a padded level still parses",
        );
    }
}
