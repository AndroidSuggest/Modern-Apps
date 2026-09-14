    /// An empty geometry, and one entirely off the grid, produce nothing rather than
    /// panicking on a start cell that does not exist.
    #[test]
    fn nothing_to_tile_emits_nothing() {
        let mut hit = 0usize;
        for g in [
            Geometry::<SigPt>::Lines(vec![]),
            Geometry::<SigPt>::Points(vec![]),
            Geometry::<SigPt>::Polygons(vec![]),
            Geometry::Lines(vec![sig(&[(w(-40.0), w(-40.0)), (w(-30.0), w(-30.0))])]),
            Geometry::Points(sig(&[(f64::NAN, 0.0)])),
        ] {
            subdivide(&g, 4, EXTENT, BUFFER, &mut |_, _, _| hit += 1);
        }
        assert_eq!(hit, 0);
    }

    /// Points are bucketed rather than descended, and a point inside a neighbour's
    /// buffer is in both tiles -- which is what the per-tile `contains` filter did.
    #[test]
    fn a_point_lands_in_every_tile_whose_buffer_holds_it() {
        let g = Geometry::Points(sig(&[(w(1.0) + 1.0, w(1.0) + 1.0)]));
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, got| {
            assert_eq!(vertices(got), 1);
            tiles.push((tx, ty));
        });
        assert_eq!(tiles, vec![(0, 0), (0, 1), (1, 0), (1, 1)]);
    }

    /// Points keep their input order within a tile. The drop policy's tie-break is the
    /// feature index, but a MultiPoint's own parts have no index of their own, so
    /// their order is the order they arrived in.
    #[test]
    fn points_keep_their_input_order_within_a_tile() {
        let want = sig(&[(w(0.9), w(0.1)), (w(0.1), w(0.9)), (w(0.5), w(0.5))]);
        let g = Geometry::Points(want.clone());
        subdivide(&g, 4, EXTENT, BUFFER, &mut |_, _, got| {
            assert_eq!(*got, Geometry::Points(want.clone()))
        });
    }
