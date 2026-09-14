/// The spans of one continuous polyline, in the order they join.
pub(crate) type Run = Vec<crate::coalesce::Span>;

#[cfg(test)]
mod tests {
    use crate::coalesce::{coalesce_lines, coalesce_lines_with_ids, points_of, Stats};
    use tilecodec::mamaps::body::{Carriageway, Feature, Layer, Part, GEOM_LINE, GEOM_POLYGON, WINDING_OUTER};

    /// One fixture feature: `(kind, geom_type, [part, ...])`, each part a list of points.
    type Fixture = (u16, u8, Vec<Vec<(i16, i16)>>);

    /// Build a layer from a list of fixture features.
    fn layer_of(features: &[Fixture]) -> Layer {
        let mut layer = Layer::new(4);
        for (kind, geom_type, parts) in features {
            let parts_offset = layer.parts.len() as u32;
            for points in parts {
                layer.parts.push(Part {
                    coord_start: layer.coords.len() as u32,
                    point_count: points.len() as u32,
                    winding: WINDING_OUTER,
                });
                layer.coords.extend_from_slice(points);
            }
            layer.features.push(Feature {
                kind: *kind,
                kind_detail: 0,
                geom_type: *geom_type,
                flags: 0,
                name_idx: tilecodec::mamaps::body::NAME_NONE,
                parts_offset,
                part_count: parts.len() as u32,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            });
        }
        layer
    }

    /// Every part's points, so a test can compare geometry without caring how it is grouped.
    fn all_parts(layer: &Layer) -> Vec<Vec<(i16, i16)>> {
        layer
            .parts
            .iter()
            .map(|p| points_of(&layer.coords, *p).to_vec())
            .collect()
    }

    /// The arena must be tiled exactly by the parts table, in order, or the encoder rejects the
    /// body. Asserted on every result below, because it is the invariant easiest to break here.
    fn assert_arena_is_tiled(layer: &Layer) {
        let mut at = 0u32;
        for part in &layer.parts {
            assert_eq!(part.coord_start, at, "parts must tile the arena with no gaps");
            at += part.point_count;
        }
        assert_eq!(at as usize, layer.coords.len(), "the arena must end where the parts do");
        for feature in &layer.features {
            let end = feature.parts_offset as usize + feature.part_count as usize;
            assert!(end <= layer.parts.len(), "a feature indexes past the parts table");
            assert!(feature.part_count > 0, "a feature with no parts draws nothing");
        }
    }

    #[test]
    fn features_of_one_class_become_one_feature() {
        // Four disjoint fragments of the same class: one feature, four parts, same geometry.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 1)]]),
            (7, GEOM_LINE, vec![vec![(10, 10), (11, 11)]]),
            (7, GEOM_LINE, vec![vec![(20, 20), (21, 21)]]),
            (7, GEOM_LINE, vec![vec![(30, 30), (31, 31)]]),
        ]);
        let before = all_parts(&layer);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(layer.features[0].part_count, 4);
        assert_eq!(all_parts(&layer), before, "nothing disjoint may be joined");
        assert_eq!(stats, Stats { features_before: 4, features_after: 1, parts_before: 4, parts_after: 4 });
    }

    #[test]
    fn different_classes_stay_apart() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 1)]]),
            (9, GEOM_LINE, vec![vec![(1, 1), (2, 2)]]),
            (7, GEOM_LINE, vec![vec![(5, 5), (6, 6)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two classes, two features");
        assert_eq!(layer.features[0].kind, 7, "the first class keeps the first position");
        assert_eq!(layer.features[1].kind, 9);
        // Class 9 touches class 7's endpoint but must not be spliced onto it.
        assert_eq!(layer.features[1].part_count, 1);
    }

    #[test]
    fn fragments_that_continue_each_other_are_spliced() {
        // Three pieces of one road, given out of order, plus a fourth that is elsewhere.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(2, 0), (3, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
            (7, GEOM_LINE, vec![vec![(50, 50), (51, 50)]]),
        ]);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(stats.parts_before, 4);
        assert_eq!(stats.parts_after, 2, "three joined into one, plus the far piece");
        let parts = all_parts(&layer);
        assert!(
            parts.contains(&vec![(0, 0), (1, 0), (2, 0), (3, 0)]),
            "the joint point appears once, not twice: {parts:?}"
        );
        assert!(parts.contains(&vec![(50, 50), (51, 50)]));
    }

    #[test]
    fn a_chain_starts_at_its_head_rather_than_its_middle() {
        // If a chain were grown from the middle piece the result would be two runs, not one.
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(2, 0), (3, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.parts.len(), 1, "one road, one part");
        assert_eq!(all_parts(&layer)[0], vec![(0, 0), (1, 0), (2, 0), (3, 0)]);
    }

    /// A ring road: every piece is continued by another, so none is a head. Without the second pass
    /// over the leftovers this would emit nothing and the road would vanish.
    #[test]
    fn a_closed_loop_survives_having_no_head() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (10, 0)]]),
            (7, GEOM_LINE, vec![vec![(10, 0), (10, 10)]]),
            (7, GEOM_LINE, vec![vec![(10, 10), (0, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.parts.len(), 1);
        assert_eq!(
            all_parts(&layer)[0],
            vec![(0, 0), (10, 0), (10, 10), (0, 0)],
            "the loop closes on the point it started at"
        );
    }

    #[test]
    fn polygons_are_untouched_and_keep_their_place() {
        let square = vec![(0, 0), (4, 0), (4, 4), (0, 4), (0, 0)];
        let hole = vec![(1, 1), (2, 1), (2, 2), (1, 2), (1, 1)];
        let mut layer = layer_of(&[
            (3, GEOM_POLYGON, vec![square.clone(), hole.clone()]),
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2);
        assert_eq!(layer.features[0].geom_type, GEOM_POLYGON, "the polygon keeps its position");
        assert_eq!(layer.features[0].part_count, 2, "exterior and hole both survive");
        let parts = all_parts(&layer);
        assert_eq!(parts[0], square);
        assert_eq!(parts[1], hole);
        assert_eq!(parts[2], vec![(0, 0), (1, 0), (2, 0)], "the lines still merged");
    }

    #[test]
    fn a_layer_with_nothing_to_merge_is_left_alone() {
        for features in [
            vec![],
            vec![(7u16, GEOM_LINE, vec![vec![(0i16, 0i16), (1, 1)]])],
            vec![(3, GEOM_POLYGON, vec![vec![(0, 0), (4, 0), (4, 4), (0, 0)]])],
        ] {
            let mut layer = layer_of(&features);
            let before = layer.clone();
            let stats = coalesce_lines(&mut layer);
            assert_eq!(layer, before, "a layer with fewer than two lines must not be rebuilt");
            assert_eq!(stats.features_before, stats.features_after);
            assert_eq!(stats.parts_before, stats.parts_after);
        }
    }

    /// Two transit lines of one mode crossing a tile: same `kind`, different operator colour. They
    /// must stay apart, or the merged feature wears whichever colour happened to come first.
    #[test]
    fn transit_colour_separates_otherwise_identical_features() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        layer.features[0].transit_color = 0x00_54_A5;
        layer.features[1].transit_color = 0xE3_1E_24;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two colours, two features");
        assert_eq!(layer.features[0].transit_color, 0x00_54_A5);
        assert_eq!(layer.features[1].transit_color, 0xE3_1E_24);
        // They touch end to end, so without the colour in the key they would also be spliced.
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0)], vec![(1, 0), (2, 0)]]);
    }

    /// The counterpart: equal colours still merge, so the key is not simply always-distinct.
    #[test]
    fn equal_transit_colours_still_merge() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        layer.features[0].transit_color = 0x00_54_A5;
        layer.features[1].transit_color = 0x00_54_A5;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0), (2, 0)]]);
    }

    /// Two routes of one colour on different corridor ordinals draw as two parallel lines.
    /// Merging them would put both on whichever ordinal came first, which is the whole defect the
    /// ordinal exists to fix.
    #[test]
    fn a_transit_ordinal_separates_two_features_of_one_colour() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        for feature in &mut layer.features {
            feature.transit_color = 0x00_54_A5;
            feature.transit_lanes = 2;
            feature.transit_taper = 255;
        }
        layer.features[0].transit_ordinal = 0;
        layer.features[1].transit_ordinal = 1;
        coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two ordinals, two features");
        assert_eq!(layer.features[0].transit_ordinal, 0);
        assert_eq!(layer.features[1].transit_ordinal, 1);
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0)], vec![(1, 0), (2, 0)]]);
    }

    /// **The one part of the merge key that is not on the feature.** Two four-lane roads of the
    /// same class that divide 3/1 and 1/3 are drawn as different surfaces with the centre line in
    /// different places, and the split lives in a side table rather than on the feature — so
    /// without it in the key they would touch end to end, splice, and both come out on whichever
    /// division happened to come first.
    #[test]
    fn a_differing_directional_split_keeps_two_roads_of_one_class_apart() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        for feature in &mut layer.features {
            feature.lane_count = 4;
        }
        let mut carriageways = vec![
            Carriageway { forward: 3, backward: 1, solid_dividers: 0 },
            Carriageway { forward: 1, backward: 3, solid_dividers: 0 },
        ];
        coalesce_lines_with_ids(&mut layer, None, None, None, Some(&mut carriageways));
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2, "two splits, two features");
        assert_eq!(
            all_parts(&layer),
            vec![vec![(0, 0), (1, 0)], vec![(1, 0), (2, 0)]],
            "they touch end to end, so without the split in the key they would be spliced",
        );
        // And the side table still describes the features it is parallel to, in order.
        assert_eq!(
            carriageways,
            vec![
                Carriageway { forward: 3, backward: 1, solid_dividers: 0 },
                Carriageway { forward: 1, backward: 3, solid_dividers: 0 },
            ],
        );
    }

    /// The counterpart, so the key is not simply always-distinct: roads that agree on their split
    /// still merge, and the survivor's entry is the split both of them had.
    #[test]
    fn an_equal_directional_split_still_merges() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        for feature in &mut layer.features {
            feature.lane_count = 4;
        }
        let split = Carriageway { forward: 3, backward: 1, solid_dividers: 0b10 };
        let mut carriageways = vec![split, split];
        coalesce_lines_with_ids(&mut layer, None, None, None, Some(&mut carriageways));
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 1);
        assert_eq!(all_parts(&layer), vec![vec![(0, 0), (1, 0), (2, 0)]]);
        assert_eq!(carriageways, vec![split], "the side table shrinks with the features");
    }

    /// Solid dividers are part of the split, so a road where a lane change is prohibited does not
    /// merge with one where it is allowed — the difference is a solid line against a dashed one.
    #[test]
    fn a_solid_divider_separates_two_otherwise_identical_carriageways() {
        let mut layer = layer_of(&[
            (7, GEOM_LINE, vec![vec![(0, 0), (1, 0)]]),
            (7, GEOM_LINE, vec![vec![(1, 0), (2, 0)]]),
        ]);
        let mut carriageways = vec![
            Carriageway { forward: 2, backward: 2, solid_dividers: 0 },
            Carriageway { forward: 2, backward: 2, solid_dividers: 0b1 },
        ];
        coalesce_lines_with_ids(&mut layer, None, None, None, Some(&mut carriageways));
        assert_arena_is_tiled(&layer);
        assert_eq!(layer.features.len(), 2);
    }

    /// The point of the whole module, in the shape the real data has: thousands of two-point
    /// fragments of one class laid end to end.
    #[test]
    fn a_fragmented_road_collapses_to_one_part() {
        let features: Vec<Fixture> = (0..2000)
            .map(|i| (7u16, GEOM_LINE, vec![vec![(i as i16, 0), (i as i16 + 1, 0)]]))
            .collect();
        let mut layer = layer_of(&features);
        let stats = coalesce_lines(&mut layer);
        assert_arena_is_tiled(&layer);
        assert_eq!(stats.features_before, 2000);
        assert_eq!(stats.features_after, 1);
        assert_eq!(stats.parts_before, 2000);
        assert_eq!(stats.parts_after, 1);
        assert_eq!(layer.coords.len(), 2001, "2000 segments share 1999 joints");
    }
}
