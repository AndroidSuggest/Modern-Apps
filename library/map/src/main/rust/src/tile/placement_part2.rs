#[cfg(test)]
mod tests {
    use super::*;

    fn cand(id: u64, rank: u8, rect: (f32, f32, f32, f32)) -> Candidate {
        Candidate { id, rank, pop: 0, rect, alternate: None }
    }

    /// Just the ids, for the tests that predate variable anchors.
    fn ids(placed: Vec<Placed>) -> Vec<u64> {
        placed.into_iter().map(|(id, _)| id).collect()
    }

    #[test]
    fn a_country_beats_a_colliding_city() {
        let cs = vec![
            cand(1, 2, (0.0, 0.0, 100.0, 20.0)),
            cand(0, 0, (10.0, 0.0, 60.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![0]);
    }

    #[test]
    fn non_overlapping_labels_all_draw() {
        let cs = vec![
            cand(0, 0, (0.0, 0.0, 50.0, 20.0)),
            cand(1, 2, (60.0, 0.0, 160.0, 20.0)),
            cand(2, 3, (0.0, 30.0, 80.0, 50.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![0, 1, 2]);
    }

    #[test]
    fn ties_break_deterministically_by_size_then_id() {
        let cs = vec![
            cand(5, 2, (0.0, 0.0, 40.0, 20.0)),
            cand(3, 2, (0.0, 0.0, 40.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![3]);
    }

    #[test]
    fn edge_touching_boxes_do_not_collide() {
        let cs = vec![cand(0, 0, (0.0, 0.0, 50.0, 20.0)), cand(1, 0, (50.0, 0.0, 100.0, 20.0))];
        assert_eq!(ids(place(&cs)), vec![0, 1]);
    }

    #[test]
    fn empty_in_empty_out() {
        assert!(place(&[]).is_empty());
    }

    #[test]
    fn a_big_city_beats_a_town_at_the_same_collision() {
        // Same rank, overlapping boxes: population weight decides, so the
        // important place survives the cull.
        let town =
            Candidate { id: 1, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let city =
            Candidate { id: 0, rank: 2, pop: 3, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[town, city])), vec![0]);
    }

    #[test]
    fn an_unknown_rank_sinks_below_a_subplace() {
        let known =
            Candidate { id: 0, rank: 3, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        let unknown =
            Candidate { id: 1, rank: 255, pop: 3, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[unknown, known])), vec![0]);
    }

    #[test]
    fn candidate_ids_are_stable_and_distinct_per_label() {
        // Same inputs, same id across calls (no per-frame shimmer from the
        // tie-break); neighbouring labels never collide.
        let a = candidate_id(6, 10, 24, 33, 4);
        assert_eq!(a, candidate_id(6, 10, 24, 33, 4));
        assert_ne!(a, candidate_id(6, 10, 24, 33, 5));
        assert_ne!(a, candidate_id(6, 10, 25, 33, 4));
        assert_ne!(a, candidate_id(6, 10, 24, 34, 4));
    }

    #[test]
    fn a_screen_rect_centres_on_the_anchor_at_the_labels_size() {
        // A full-viewport tile maps 0..1 to -1..1, so the centre anchor lands
        // mid-screen and the box spans the advance at the frame's text size.
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        // Half an em of advance, expressed against UP_EM so the fixture stays a
        // half em if the bundled font's units change: 7px wide at 14px text.
        let half_em = crate::tile::glyph::UP_EM as f32 / 2.0;
        let (x0, y0, x1, y1) = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 0.0)
            .expect("a pitch-0 anchor always projects");
        assert!((x0 - 124.5).abs() < 1e-3, "{x0}");
        assert!((x1 - 131.5).abs() < 1e-3, "{x1}");
        assert!((y0 - 121.0).abs() < 1e-3, "{y0}");
        assert!((y1 - 135.0).abs() < 1e-3, "{y1}");
    }

    #[test]
    fn padding_inflates_the_box_symmetrically() {
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        let half_em = crate::tile::glyph::UP_EM as f32 / 2.0;
        let plain = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 0.0)
            .expect("a pitch-0 anchor always projects");
        let padded = screen_rect((0.5, 0.5), tile_clip, (256, 256), 14.0, half_em, 6.0)
            .expect("a pitch-0 anchor always projects");
        assert!((padded.0 - (plain.0 - 6.0)).abs() < 1e-3);
        assert!((padded.1 - (plain.1 - 6.0)).abs() < 1e-3);
        assert!((padded.2 - (plain.2 + 6.0)).abs() < 1e-3);
        assert!((padded.3 - (plain.3 + 6.0)).abs() < 1e-3);
    }

    #[test]
    fn rank_zero_never_collides() {
        // Countries always draw, even stacked on each other: there are few of
        // them and every one matters. Acceptance follows rank order (0 first).
        let cs = vec![
            cand(1, 0, (0.0, 0.0, 100.0, 20.0)),
            cand(0, 0, (10.0, 0.0, 60.0, 20.0)),
        ];
        assert_eq!(ids(place(&cs)), vec![1, 0]);
    }

    /// Task-9 gating: low zoom keeps only high-pop localities, so z6 holds the
    /// major-city set; higher zooms relax to towns, then everything. The numbers are
    /// reference `population_rank` values, so 12 is the 500k bucket.
    #[test]
    fn locality_gating_thins_by_ui_zoom() {
        assert_eq!(locality_min_pop(6.0), 12, "z6: the 500k-plus cities only");
        assert_eq!(locality_min_pop(6.9), 12);
        assert_eq!(locality_min_pop(7.0), 1, "z7: anywhere with a population joins");
        assert_eq!(locality_min_pop(9.9), 1);
        assert_eq!(locality_min_pop(10.0), 0, "z10: everything shaped draws");
        assert_eq!(locality_min_pop(14.0), 0);
    }

    /// Task-17 pick contract: box intersection is inclusive on edges (a tap
    /// exactly on a label edge still hits) and order-preserving (placement
    /// order = topmost first). The native `pick_labels` filters the same way;
    /// this pins the semantics host-side.
    #[test]
    fn box_hit_is_inclusive_and_order_preserving() {
        fn hits(rect: (f32, f32, f32, f32), q: (f32, f32, f32, f32)) -> bool {
            rect.0 <= q.2 && rect.2 >= q.0 && rect.1 <= q.3 && rect.3 >= q.1
        }
        // Edge touch counts.
        assert!(hits((0.0, 0.0, 10.0, 10.0), (10.0, 10.0, 20.0, 20.0)));
        assert!(hits((0.0, 0.0, 10.0, 10.0), (5.0, 5.0, 5.0, 5.0)));
        // Clean miss does not.
        assert!(!hits((0.0, 0.0, 10.0, 10.0), (10.1, 10.1, 20.0, 20.0)));
        assert!(!hits((0.0, 0.0, 10.0, 10.0), (-20.0, -20.0, -0.1, -0.1)));
    }

    // --- variable anchors ---------------------------------------------------

    /// The point of the second box: a POI whose label collides on one side flips to the
    /// other rather than dropping out, and the flip is reported so the renderer draws it
    /// where it was actually placed.
    #[test]
    fn a_blocked_label_flips_to_its_other_anchor() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            // The left anchor lands on the blocker; the right one is clear.
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((200.0, 0.0, 300.0, 20.0)),
        };
        assert_eq!(place(&[blocker, poi]), vec![(0, false), (1, true)]);
    }

    /// The alternate is a fallback, not a preference: a label whose first box is free stays
    /// there, or every POI would drift to its second anchor for no reason.
    #[test]
    fn a_clear_label_keeps_its_first_anchor() {
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (0.0, 0.0, 50.0, 20.0),
            alternate: Some((200.0, 0.0, 250.0, 20.0)),
        };
        assert_eq!(place(&[poi]), vec![(1, false)]);
    }

    /// Both boxes blocked is a dropped label, not a label drawn over something.
    #[test]
    fn a_label_blocked_at_both_anchors_is_dropped() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 300.0, 20.0), alternate: None };
        let poi = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((160.0, 0.0, 260.0, 20.0)),
        };
        assert_eq!(place(&[blocker, poi]), vec![(0, false)]);
    }

    /// The **accepted** box joins the occupied set, not the primary one: a label that
    /// flipped has to block whatever it flipped onto, or two labels stack there.
    #[test]
    fn the_accepted_box_is_what_later_labels_collide_with() {
        let blocker =
            Candidate { id: 0, rank: 2, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let flipper = Candidate {
            id: 1,
            rank: 4,
            pop: 0,
            rect: (50.0, 0.0, 150.0, 20.0),
            alternate: Some((200.0, 0.0, 300.0, 20.0)),
        };
        // Sits where the flipper landed, so it must lose to it.
        let later =
            Candidate { id: 2, rank: 4, pop: 0, rect: (250.0, 0.0, 350.0, 20.0), alternate: None };
        assert_eq!(place(&[blocker, flipper, later]), vec![(0, false), (1, true)]);
    }

    /// A POI sorts below every place label. Before the POI ids reached `rank_for_layer`
    /// they fell through to 255 and lost to *everything*, which at z17 — where places are
    /// sparse — would have looked almost right.
    #[test]
    fn a_poi_loses_to_a_subplace_and_beats_an_unknown() {
        let subplace =
            Candidate { id: 0, rank: 3, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        let poi =
            Candidate { id: 1, rank: 4, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[poi, subplace])), vec![0]);

        let poi =
            Candidate { id: 1, rank: 4, pop: 0, rect: (10.0, 0.0, 60.0, 20.0), alternate: None };
        let unknown =
            Candidate { id: 2, rank: 255, pop: 0, rect: (0.0, 0.0, 100.0, 20.0), alternate: None };
        assert_eq!(ids(place(&[unknown, poi])), vec![1]);
    }

    // --- the anchored box ---------------------------------------------------

    /// A full-viewport tile maps 0..1 to -1..1, so a centre anchor lands mid-screen.
    fn full_viewport() -> [f32; 16] {
        [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ]
    }

    /// The box has to cover the icon **and** the offset text, or a label pushed clear of
    /// its own icon leaves that icon unguarded for the next label to sit on.
    #[test]
    fn a_poi_box_covers_both_the_icon_and_the_offset_text() {
        let em = crate::tile::glyph::UP_EM as f32;
        let inputs = BoxInputs {
            text_px: 10.0,
            advance: em * 4.0,
            line_count: 1,
            offset_em: (1.1, 0.0),
            icon_px: Some((19.0, 19.0)),
            pad_px: 0.0,
        };
        // Text runs 11px..51px right of the point; the icon spans -9.5..+9.5.
        let left =
            anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs, Anchor::Left)
                .expect("a pitch-0 anchor always projects");
        assert!((left.0 - (128.0 - 9.5)).abs() < 1e-3, "left edge {} is not the icon's", left.0);
        assert!((left.2 - (128.0 + 51.0)).abs() < 1e-3, "right edge {} is not the text's", left.2);
        // Flipping the anchor mirrors it exactly, because both parts are symmetric.
        let right =
            anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs, Anchor::Right)
                .expect("a pitch-0 anchor always projects");
        assert!((right.0 - (128.0 - 51.0)).abs() < 1e-3, "{}", right.0);
        assert!((right.2 - (128.0 + 9.5)).abs() < 1e-3, "{}", right.2);
        // The icon is taller than one 10px line, so it sets the height either way.
        for rect in [left, right] {
            assert!((rect.1 - (128.0 - 9.5)).abs() < 1e-3);
            assert!((rect.3 - (128.0 + 9.5)).abs() < 1e-3);
        }
    }

    /// A wrapped label is taller by a line height per extra line — 2.2 em for two lines,
    /// not 2.4: the first line contributes its own height and each further one the leading.
    #[test]
    fn extra_lines_make_the_box_taller_by_one_line_height_each() {
        let em = crate::tile::glyph::UP_EM as f32;
        let inputs = |lines: usize| BoxInputs {
            text_px: 10.0,
            advance: em * 4.0,
            line_count: lines,
            offset_em: (0.0, 0.0),
            icon_px: None,
            pad_px: 0.0,
        };
        let height = |lines: usize| {
            let r = anchored_rect((0.5, 0.5), full_viewport(), (256, 256), &inputs(lines), Anchor::Center)
                .expect("a pitch-0 anchor always projects");
            r.3 - r.1
        };
        assert!((height(1) - 10.0).abs() < 1e-3, "{}", height(1));
        assert!((height(2) - 22.0).abs() < 1e-3, "{}", height(2));
        assert!((height(3) - 34.0).abs() < 1e-3, "{}", height(3));
        assert!((height(0) - height(1)).abs() < 1e-6, "a zero line count is one line");
    }

    // --- oriented / segmented collision (curved labels) ---------------------

    /// An OBB centred at `(cx, cy)`, `half` on each side, rotated `deg` degrees.
    fn obb(cx: f32, cy: f32, half: f32, deg: f32) -> Obb {
        let r = deg.to_radians();
        Obb { cx, cy, hx: half, hy: half, cos: r.cos(), sin: r.sin() }
    }

    #[test]
    fn axis_aligned_overlap_agrees_with_the_rect_test() {
        // An OBB with sin == 0 is a plain AABB, so `obb_overlap` must match `overlaps` on the
        // same boxes — including the flush-edge case counting as separated.
        let a = Obb::from_rect((0.0, 0.0, 10.0, 10.0));
        let b = Obb::from_rect((5.0, 5.0, 15.0, 15.0));
        assert!(obb_overlap(&a, &b));
        let flush = Obb::from_rect((10.0, 0.0, 20.0, 10.0));
        assert!(!obb_overlap(&a, &flush), "edge-touching boxes do not collide");
        let apart = Obb::from_rect((10.1, 0.0, 20.0, 10.0));
        assert!(!obb_overlap(&a, &apart));
    }

    #[test]
    fn rotation_can_separate_or_join_two_boxes() {
        // Two unit boxes whose centres are 1.3 apart on x: axis-aligned they miss (each reaches
        // 0.5), but rotate one 45 degrees and its corner reaches ~0.707 along x, so they touch.
        let a = obb(0.0, 0.0, 0.5, 0.0);
        let straight = obb(1.3, 0.0, 0.5, 0.0);
        assert!(!obb_overlap(&a, &straight), "axis-aligned they are clear");
        let turned = obb(1.3, 0.0, 0.5, 45.0);
        let turned_a = obb(0.0, 0.0, 0.5, 45.0);
        assert!(obb_overlap(&turned_a, &turned), "rotated corners now reach across the gap");
    }

    /// **The task's collision guarantee.** A curved street label and a point label placed in one
    /// pass collide with each other: the higher-priority point label (lower rank) takes the
    /// space, and the curved label — whose glyph boxes overlap it — is dropped.
    #[test]
    fn a_curved_label_collides_with_an_overlapping_point_label() {
        let point = SegmentedCandidate {
            id: 0,
            rank: 2,
            pop: 0,
            boxes: vec![Obb::from_rect((100.0, 100.0, 160.0, 120.0))],
            alternate: None,
        };
        let curved = SegmentedCandidate {
            id: 1,
            rank: 5,
            pop: 0,
            // A row of rotated glyph boxes; the first sits on the point label's box.
            boxes: vec![obb(130.0, 110.0, 9.0, 20.0), obb(300.0, 300.0, 9.0, 0.0)],
            alternate: None,
        };
        assert_eq!(place_segmented(&[point, curved]), vec![(0, false)], "the point label wins");
    }

    #[test]
    fn a_curved_label_clear_of_every_point_draws_alongside_them() {
        // The counterpart: when its glyph boxes miss every placed point box, the curved label is
        // accepted too — collision culls only what actually overlaps.
        let point = SegmentedCandidate {
            id: 0,
            rank: 2,
            pop: 0,
            boxes: vec![Obb::from_rect((0.0, 0.0, 50.0, 20.0))],
            alternate: None,
        };
        let curved = SegmentedCandidate {
            id: 1,
            rank: 5,
            pop: 0,
            boxes: vec![obb(300.0, 300.0, 9.0, 30.0), obb(320.0, 305.0, 9.0, 35.0)],
            alternate: None,
        };
        assert_eq!(place_segmented(&[point, curved]), vec![(0, false), (1, false)]);
    }

    #[test]
    fn a_curved_label_flips_to_its_alternate_footprint() {
        // The variable-anchor fallback generalises to box sets: a blocked primary footprint tries
        // the alternate before dropping, and reports the flip.
        let blocker = SegmentedCandidate {
            id: 0,
            rank: 2,
            pop: 0,
            boxes: vec![Obb::from_rect((0.0, 0.0, 100.0, 40.0))],
            alternate: None,
        };
        let curved = SegmentedCandidate {
            id: 1,
            rank: 5,
            pop: 0,
            boxes: vec![obb(50.0, 20.0, 9.0, 15.0)],           // on the blocker
            alternate: Some(vec![obb(300.0, 300.0, 9.0, 15.0)]), // clear
        };
        assert_eq!(place_segmented(&[blocker, curved]), vec![(0, false), (1, true)]);
    }

    #[test]
    fn curved_boxes_project_and_orient_from_the_layout() {
        use crate::tess::text::{CurvedGlyph, ShapedGlyph};
        // A full-viewport tile maps 0..1 to -1..1 (y down), so a glyph at (0.5, 0.5) with a
        // 45-degree tile-local tangent lands mid-screen with a box oriented down-right.
        let tile_clip = [
            2.0, 0.0, 0.0, 0.0, //
            0.0, 2.0, 0.0, 0.0, //
            0.0, 0.0, 1.0, 0.0, //
            -1.0, -1.0, 0.0, 1.0,
        ];
        let g = ShapedGlyph {
            pen_x: 0.0,
            ch: 'M',
            advance: crate::tile::glyph::UP_EM as f32, // one em of advance
            bearing_x: 0.0,
            top: 0.0,
            w: 0.0,
            h: 0.0,
        };
        let inv = 1.0 / 2f32.sqrt();
        let placed = [CurvedGlyph { glyph: g, pen: (0.5, 0.5), tangent: (inv, inv) }];
        let boxes = curved_boxes(&placed, tile_clip, (200, 200), 20.0, 0.0);
        assert_eq!(boxes.len(), 1);
        let b = boxes[0];
        // The 45-degree tangent survives to screen (square viewport, uniform scale).
        assert!((b.cos - inv).abs() < 1e-3 && (b.sin - inv).abs() < 1e-3, "{} {}", b.cos, b.sin);
        // Half-width is half the advance in px: one em at 20px text is 20px, so hx ~ 10.
        assert!((b.hx - 10.0).abs() < 1e-3, "hx {}", b.hx);
        // Centre is half an advance down-right of the pen's screen point (100, 100).
        assert!((b.cx - (100.0 + inv * 10.0)).abs() < 1e-2, "cx {}", b.cx);
        assert!((b.cy - (100.0 + inv * 10.0)).abs() < 1e-2, "cy {}", b.cy);
    }
