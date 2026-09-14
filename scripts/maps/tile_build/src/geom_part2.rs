#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn projection_anchors_are_right() {
        // z0: the whole world is one tile, and 0,0 sits at its centre.
        let (x, y) = project(0.0, 0.0, 0);
        assert!((x - 0.5).abs() < 1e-9, "lon 0 -> x 0.5, got {x}");
        assert!((y - 0.5).abs() < 1e-9, "lat 0 -> y 0.5, got {y}");
        let (x, _) = project(-180.0, 0.0, 0);
        assert!(x.abs() < 1e-9);
        let (_, y) = project(0.0, 85.051_128_78, 0);
        assert!(y.abs() < 1e-6, "Mercator top -> y 0, got {y}");
        // A pole must clamp rather than diverge.
        let (_, y) = project(0.0, 90.0, 4);
        assert!(y.is_finite(), "lat 90 must clamp, got {y}");
        // San Francisco at z11 is a well-known tile.
        let (fx, fy) = project(-122.4194, 37.7749, 11);
        assert_eq!((fx.floor() as u64, fy.floor() as u64), (327, 791));
    }

    #[test]
    fn scaling_puts_a_point_inside_its_own_tile() {
        let extent = DEFAULT_EXTENT;
        let (wx, wy) = project_scaled(-122.4194, 37.7749, 11, extent);
        let (tx, ty) = (327u64, 791u64);
        let local = (wx - tx as f64 * extent as f64, wy - ty as f64 * extent as f64);
        assert!(
            (0.0..=extent as f64).contains(&local.0) && (0.0..=extent as f64).contains(&local.1),
            "{local:?} must be inside [0,{extent}]"
        );
    }

    #[test]
    fn buffer_scales_with_the_extent() {
        assert_eq!(buffer_for(DEFAULT_EXTENT), DEFAULT_BUFFER);
        assert_eq!(buffer_for(8192), DEFAULT_BUFFER * 2.0);
        assert_eq!(buffer_for(2048), DEFAULT_BUFFER / 2.0);
    }

    #[test]
    fn bounds_cover_every_part_and_ignore_non_finite() {
        let g = Geometry::Lines(vec![
            vec![(0.0, 0.0), (10.0, 5.0)],
            vec![(-3.0, 20.0), (4.0, 1.0)],
        ]);
        assert_eq!(
            bounds(&g),
            Some(Rect { min_x: -3.0, min_y: 0.0, max_x: 10.0, max_y: 20.0 })
        );
        assert_eq!(bounds(&Geometry::<Pt>::Points(vec![])), None);

        // A NaN vertex is skipped rather than poisoning the whole box.
        let g = Geometry::Points(vec![(1.0, 2.0), (f64::NAN, 0.0), (3.0, 4.0)]);
        assert_eq!(
            bounds(&g),
            Some(Rect { min_x: 1.0, min_y: 2.0, max_x: 3.0, max_y: 4.0 })
        );
        assert_eq!(bounds(&Geometry::Points(vec![(f64::NAN, f64::NAN)])), None);
    }

    #[test]
    fn a_tile_range_covers_exactly_the_tiles_touched() {
        let e = 4096u32;
        // Entirely inside tile (1,1).
        let b = Rect { min_x: 1.5 * e as f64, min_y: 1.5 * e as f64, max_x: 1.6 * e as f64, max_y: 1.6 * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 1, y1: 1 })
        );
        // Straddling into (2,2).
        let b = Rect { min_x: 1.5 * e as f64, min_y: 1.5 * e as f64, max_x: 2.5 * e as f64, max_y: 2.5 * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 2, y1: 2 })
        );
    }

    #[test]
    fn the_pad_pulls_in_the_neighbouring_tile() {
        let e = 4096u32;
        // Two units inside tile 1's left edge. With no pad it touches only tile 1;
        // with a 5-unit pad it also reaches into tile 0's buffer, which is the
        // whole reason the buffer exists.
        let x = e as f64 + 2.0;
        let b = Rect { min_x: x, min_y: x, max_x: x, max_y: x };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 1, y0: 1, x1: 1, y1: 1 })
        );
        assert_eq!(
            tile_range(&b, 4, e, 5.0),
            Some(TileRange { x0: 0, y0: 0, x1: 1, y1: 1 })
        );
    }

    #[test]
    fn a_tile_range_clamps_to_the_grid_and_rejects_the_wholly_outside() {
        let e = 4096u32;
        let n = 1u64 << 4;
        // Overhanging both ends is clamped, not wrapped.
        let b = Rect { min_x: -100.0, min_y: -100.0, max_x: (n as f64 + 3.0) * e as f64, max_y: (n as f64 + 3.0) * e as f64 };
        assert_eq!(
            tile_range(&b, 4, e, 0.0),
            Some(TileRange { x0: 0, y0: 0, x1: n - 1, y1: n - 1 })
        );
        // Wholly off the grid.
        let b = Rect { min_x: -10.0 * e as f64, min_y: 0.0, max_x: -9.0 * e as f64, max_y: e as f64 };
        assert_eq!(tile_range(&b, 4, e, 0.0), None);
        // NaN in, None out.
        let b = Rect { min_x: f64::NAN, min_y: 0.0, max_x: 1.0, max_y: 1.0 };
        assert_eq!(tile_range(&b, 4, e, 0.0), None);
    }

    /// The whole point of `tiles_touched`: a long diagonal must not claim the empty
    /// corners of its bounding box. This is the case that made `transit_lines` at
    /// planet scale take hours.
    #[test]
    fn a_diagonal_line_skips_the_corners_of_its_bounding_box() {
        let extent = 4096u32;
        let e = extent as f64;
        // A staircase across a 40 x 40 tile square, one vertex per tile step, so the
        // line genuinely passes through ~40 tiles out of 1600.
        let pts: Vec<Pt> = (0..=40).map(|i| (i as f64 * e, i as f64 * e)).collect();
        let g = Geometry::Lines(vec![pts]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);

        let bbox = tile_range(&bounds(&g).unwrap(), 8, extent, 0.0).unwrap();
        assert_eq!(bbox.len(), 41 * 41, "the box really is that big");
        // Every vertex sits exactly on a tile corner here, which is the worst case:
        // each segment's box spans 2x2 tiles rather than 1. Even so the walk is an
        // order of magnitude below the box, and that ratio is what grows with zoom.
        assert!(
            (touched.len() as u64) * 10 < bbox.len(),
            "walked {} tiles, more than a tenth of the box's {}",
            touched.len(),
            bbox.len()
        );

        // Every tile on the diagonal is present...
        for i in 0..=40u64 {
            assert!(touched.contains(&(i, i)), "tile ({i},{i}) is on the line");
        }
        // ...and the far corners, which the line never approaches, are not.
        assert!(!touched.contains(&(0, 40)), "bottom-left corner is empty");
        assert!(!touched.contains(&(40, 0)), "top-right corner is empty");
    }

    /// Safety property: it may over-include, never under-include. Everything it lists
    /// is inside the feature's bounding box, and every point ON the line lands in a
    /// tile it listed.
    ///
    /// Sampling the line rather than asserting each segment's whole box, because
    /// bisection deliberately produces a tighter set than those boxes — that is the
    /// optimisation. What must not change is that no part of the line is unreachable.
    #[test]
    fn tiles_touched_covers_every_point_on_the_line_and_nothing_outside_the_box() {
        let extent = 4096u32;
        let e = extent as f64;
        let pts = vec![
            (0.5 * e, 0.5 * e),
            (3.5 * e, 1.5 * e),
            (2.0 * e, 6.0 * e),
        ];
        let g = Geometry::Lines(vec![pts.clone()]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);

        let bbox = tile_range(&bounds(&g).unwrap(), 8, extent, 0.0).unwrap();
        let inside: Vec<(u64, u64)> = bbox.iter().collect();
        for t in &touched {
            assert!(inside.contains(t), "{t:?} is outside the bounding box");
        }

        // Walk each segment finely; every sample's tile must have been listed.
        for seg in pts.windows(2) {
            let steps = 2000;
            for i in 0..=steps {
                let t = i as f64 / steps as f64;
                let x = seg[0].0 + (seg[1].0 - seg[0].0) * t;
                let y = seg[0].1 + (seg[1].1 - seg[0].1) * t;
                let tile = ((x / e).floor() as u64, (y / e).floor() as u64);
                assert!(
                    touched.contains(&tile),
                    "point ({x}, {y}) is in tile {tile:?}, which was not listed"
                );
            }
        }
    }

    /// Bisection is what makes the result independent of vertex density: a 4-vertex
    /// line across a continent must walk about as many tiles as a 400-vertex one along
    /// the same path, rather than filling its segment boxes.
    #[test]
    fn a_sparse_line_walks_as_tightly_as_a_dense_one() {
        let extent = 4096u32;
        let e = extent as f64;
        let end = 40.0;

        let sparse = Geometry::Lines(vec![vec![(0.0, 0.0), (end * e, end * e)]]);
        let dense: Vec<Pt> = (0..=400)
            .map(|i| {
                let t = i as f64 / 400.0;
                (end * e * t, end * e * t)
            })
            .collect();
        let dense = Geometry::Lines(vec![dense]);

        let (mut a, mut b) = (Vec::new(), Vec::new());
        tiles_touched(&sparse, 8, extent, 0.0, &mut a);
        tiles_touched(&dense, 8, extent, 0.0, &mut b);

        let bbox = tile_range(&bounds(&sparse).unwrap(), 8, extent, 0.0).unwrap();
        assert!(
            (a.len() as u64) * 10 < bbox.len(),
            "sparse walked {} of the box's {}",
            a.len(),
            bbox.len()
        );
        // Within a small factor of each other, not orders apart.
        assert!(
            a.len() < b.len() * 3 && b.len() < a.len() * 3,
            "sparse {} vs dense {} should be comparable",
            a.len(),
            b.len()
        );
    }

    /// A polygon covers its interior, so its footprint stays the whole ring box --
    /// per-segment boxes would drop every tile strictly inside it.
    #[test]
    fn a_polygon_still_claims_its_interior_tiles() {
        let extent = 4096u32;
        let e = extent as f64;
        let ring = vec![
            (0.5 * e, 0.5 * e),
            (5.5 * e, 0.5 * e),
            (5.5 * e, 5.5 * e),
            (0.5 * e, 5.5 * e),
            (0.5 * e, 0.5 * e),
        ];
        let g = Geometry::Polygons(vec![vec![ring]]);

        let mut touched = Vec::new();
        tiles_touched(&g, 8, extent, 0.0, &mut touched);

        // (3,3) is strictly inside and touches no edge.
        assert!(touched.contains(&(3, 3)), "interior tile must be filled");
        assert_eq!(touched.len(), 36, "the whole 6x6 footprint");
    }

    /// Adjacent segments share tiles; a duplicate would encode the geometry twice.
    #[test]
    fn tiles_touched_deduplicates_and_sorts() {
        let extent = 4096u32;
        let e = extent as f64;
        // Many short segments all inside one tile.
        let pts: Vec<Pt> = (0..20).map(|i| (0.1 * e + i as f64, 0.1 * e)).collect();
        let mut touched = Vec::new();
        tiles_touched(&Geometry::Lines(vec![pts]), 8, extent, 0.0, &mut touched);
        assert_eq!(touched, vec![(0, 0)]);

        let mut sorted = touched.clone();
        sorted.sort_unstable();
        assert_eq!(touched, sorted, "output is sorted for a deterministic pass");
    }

    #[test]
    fn tile_range_iterates_row_major() {
        let r = TileRange { x0: 2, y0: 5, x1: 3, y1: 6 };
        assert_eq!(r.len(), 4);
        assert_eq!(
            r.iter().collect::<Vec<_>>(),
            vec![(2, 5), (3, 5), (2, 6), (3, 6)]
        );
    }

    #[test]
    fn a_tile_rect_is_the_tile_plus_its_buffer() {
        let r = tile_rect(2, 3, 4096, 5.0);
        assert_eq!(
            r,
            Rect { min_x: 8192.0 - 5.0, min_y: 12288.0 - 5.0, max_x: 12288.0 + 5.0, max_y: 16384.0 + 5.0 }
        );
        assert!(r.contains((8192.0, 12288.0)));
        assert!(r.contains((8192.0 - 4.0, 12288.0 - 4.0)), "inside the buffer");
        assert!(!r.contains((8192.0 - 6.0, 12288.0)), "beyond the buffer");
    }

    #[test]
    fn to_tile_translates_then_rounds() {
        // A vertex at world (4096.4, 8191.6) is (0.4, 4095.6) inside tile (1,1),
        // which rounds to (0, 4096).
        let g = Geometry::Lines(vec![vec![(4096.4, 8191.6), (5000.0, 9000.0)]]);
        let IntGeometry::Lines(lines) = to_tile(&g, 1, 1, 4096) else {
            panic!("lines in, lines out")
        };
        assert_eq!(lines[0][0], (0, 4096));
        assert_eq!(lines[0][1], (904, 4904));
    }

    #[test]
    fn quantisation_drops_the_duplicates_it_creates() {
        // Three vertices within a rounding unit of each other collapse to one.
        let g = Geometry::Lines(vec![vec![
            (10.1, 10.1),
            (10.2, 10.3),
            (9.9, 10.0),
            (20.0, 20.0),
        ]]);
        let IntGeometry::Lines(lines) = quantize(&g) else { panic!() };
        assert_eq!(lines[0], vec![(10, 10), (20, 20)]);

        // Non-adjacent repeats are kept: a line that doubles back is a real shape.
        let g = Geometry::Lines(vec![vec![(0.0, 0.0), (5.0, 0.0), (0.0, 0.0)]]);
        let IntGeometry::Lines(lines) = quantize(&g) else { panic!() };
        assert_eq!(lines[0], vec![(0, 0), (5, 0), (0, 0)]);
    }

    #[test]
    fn quantisation_keeps_a_rings_closing_vertex() {
        // ClosePath is applied by the encoder, not here, so the ring stays closed
        // through simplification -- which needs the closure to measure against.
        let ring = vec![(0.0, 0.0), (4.0, 0.0), (4.0, 4.0), (0.0, 4.0), (0.0, 0.0)];
        let IntGeometry::Polygons(polys) = quantize(&Geometry::Polygons(vec![vec![ring]])) else {
            panic!()
        };
        assert_eq!(polys[0][0].len(), 5);
        assert_eq!(polys[0][0].first(), polys[0][0].last());
    }

    #[test]
    fn a_non_finite_vertex_saturates_rather_than_wrapping() {
        // `f64::NAN as i32` is 0 in Rust and `1e300 as i32` is the saturated bound,
        // but both are silent. Keeping the saturation explicit means an out-of-range
        // vertex stays obviously out of range instead of landing somewhere
        // plausible; a NaN has no meaningful bound, so it becomes the origin.
        let g = Geometry::Points(vec![(f64::NAN, 1e300), (-1e300, 5.0)]);
        let IntGeometry::Points(pts) = quantize(&g) else { panic!() };
        assert_eq!(pts, vec![(0, i32::MAX), (i32::MIN, 5)]);
    }

    #[test]
    fn emptiness_is_judged_per_geometry_kind() {
        assert!(IntGeometry::Points(vec![]).is_empty());
        assert!(!IntGeometry::Points(vec![(0, 0)]).is_empty());
        assert!(IntGeometry::Lines(vec![vec![(0, 0)]]).is_empty());
        assert!(!IntGeometry::Lines(vec![vec![(0, 0), (1, 1)]]).is_empty());
        // Three distinct vertices are the least that can enclose an area.
        assert!(IntGeometry::Polygons(vec![vec![vec![(0, 0), (1, 0)]]]).is_empty());
        assert!(!IntGeometry::Polygons(vec![vec![vec![(0, 0), (1, 0), (1, 1)]]]).is_empty());
    }

    #[test]
    fn rects_intersect_symmetrically_and_touch_counts() {
        let a = Rect { min_x: 0.0, min_y: 0.0, max_x: 10.0, max_y: 10.0 };
        let b = Rect { min_x: 10.0, min_y: 10.0, max_x: 20.0, max_y: 20.0 };
        let c = Rect { min_x: 11.0, min_y: 0.0, max_x: 20.0, max_y: 10.0 };
        assert!(a.intersects(&b) && b.intersects(&a), "touching corners overlap");
        assert!(!a.intersects(&c) && !c.intersects(&a));
    }
}
