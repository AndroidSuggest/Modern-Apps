            7,
        ));

        // --- off the edge of the grid --------------------------------------
        out.push(("a ring hanging off the grid", polygon(&[&square(-2.0, 3.0)]), 4));
        out.push((
            "a line hanging off the grid",
            line(&[(w(-3.0), w(-1.0)), (w(4.0), w(5.0))]),
            4,
        ));

        // --- points --------------------------------------------------------
        let mut pts = Geometry::Points(sig(&[
            (w(0.5), w(0.5)),
            (w(1.0), w(1.0)),
            (w(2.9999), w(3.0001)),
            (w(5.5), w(2.5)),
        ]));
        crate::simplify::annotate(&mut pts);
        out.push(("a handful of points", pts, 4));

        out
    }

    /// **The gate.** The descent's answer for a tile must be the same shape as
    /// clipping the source straight to that tile -- same tiles, same parts, same
    /// rings, same vertices, same areas, same lengths.
    ///
    /// It is not the same BYTES, and the reason is not the one the plan expected.
    /// The worry was that clipping to an ancestor cell inserts vertices on that
    /// ancestor's boundary which nothing downstream removes. That does not happen: a
    /// child cell is a subset of its parent and both are grown by the *same* buffer,
    /// so a parent boundary line either coincides with the child's line on that side
    /// -- when the child sits on that border -- or lies strictly outside the child,
    /// where the next clip removes it. Coincident lines produce coincident crossings.
    /// So no ancestor vertex survives that the leaf would not have invented itself,
    /// and [`the_descent_invents_no_vertices`] measures that directly.
    ///
    /// What differs instead is [`Divergence::rotated`]: ring rotation, and last-bit
    /// drift on interpolated line crossings. Neither moves a shape, and both are
    /// below the quantisation grid or invisible to it. A collinear-reduction pass
    /// would not address either, so there is none.
    #[test]
    fn the_descent_is_the_same_shape_as_a_direct_clip() {
        let mut bad = Vec::new();
        for (name, g, z) in corpus() {
            let d = compare(&g, z);
            assert!(d.tiles > 0, "{name}: nothing was tiled at all");
            if !d.missing.is_empty() {
                bad.push(format!(
                    "{name} at z{z}: {} of {} tiles are in one and not the other: {:?}",
                    d.missing.len(),
                    d.tiles,
                    &d.missing[..d.missing.len().min(6)]
                ));
            }
            if !d.wrong.is_empty() {
                bad.push(format!(
                    "{name} at z{z}: {} of {} tiles changed shape: {:?}",
                    d.wrong.len(),
                    d.tiles,
                    &d.wrong[..d.wrong.len().min(3)]
                ));
            }
        }
        assert!(bad.is_empty(), "{}", bad.join("\n"));
    }

    /// The archive-growth question, measured. Boundary vertices on internal cell
    /// edges would cost bytes at every zoom, and that was the main argument for a
    /// collinear pass. The descent emits no more vertices than a direct clip -- fewer,
    /// in fact, because a concave ring's zero-area sliver comes out shorter when the
    /// clip happens in stages.
    #[test]
    fn the_descent_invents_no_vertices() {
        let (mut mine, mut theirs) = (0usize, 0usize);
        for (_, g, z) in corpus() {
            let d = compare(&g, z);
            mine += d.descent_vertices;
            theirs += d.direct_vertices;
        }
        assert!(theirs > 0);
        assert!(
            mine <= theirs,
            "the descent would grow the archive: {mine} vertices vs {theirs}"
        );
    }

    /// A ring of `n` vertices around `(cx, cy)`, radius varying with `wobble`.
    ///
    /// Deliberately not star-convex when `wobble` is large: a convex-ish blob never
    /// makes Sutherland-Hodgman produce the self-touching output that composing a clip
    /// has to survive.
    fn wobbly_ring(cx: f64, cy: f64, radius: f64, n: usize, wobble: &mut impl FnMut() -> f64) -> Vec<Pt> {
        let mut r: Vec<Pt> = (0..n)
            .map(|i| {
                let a = i as f64 * std::f64::consts::TAU / n as f64;
                let d = radius * (0.25 + 1.5 * wobble());
                (w(cx + d * a.cos()), w(cy + d * a.sin()))
            })
            .collect();
        r.push(r[0]);
        r
    }

    /// **A hole must not vanish.** Found by `test/diff_mamaps.py` on a real us-west z13
    /// archive: one `landcover` feature came back with 13 rings where a direct clip gave
    /// 14, and the missing one was a hole carrying a quarter of the feature's net area.
    /// A hole that disappears renders as a lake filled in solid.
    ///
    /// The corpus above missed it because every hole in it is an axis-aligned square. A
    /// hole only triggers this when it is concave enough that clipping it to an ancestor
    /// cell leaves a self-touching ring, and positioned so that the next clip down has
    /// to cut that ring again.
    ///
    /// Ring COUNT and NET signed area, not vertex lists: what matters is that the hole is
    /// still there and still subtracts the same ground.
    #[test]
    fn a_hole_survives_the_descent_that_a_direct_clip_keeps() {
        let mut seed = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };

        let mut checked = 0usize;
        let mut bad: Vec<String> = Vec::new();

        // A comb-shaped hole is the sharpest case, and the reason is the gap in the
        // descent's soundness argument. Composing clips is exact for a CONVEX ring:
        // `clip(clip(g,P),L) == clip(g,L)` because both are set intersections. For a
        // concave ring Sutherland-Hodgman does not return the intersection as a simple
        // polygon -- it returns one SELF-TOUCHING ring joined by zero-width slivers --
        // and re-clipping that is not covered by the argument. A comb straddling a cell
        // boundary produces exactly that, many times over.
        let comb_hole = {
            let mut r: Vec<Pt> = vec![(w(2.0), w(4.0))];
            for i in 0..11 {
                let y0 = 4.0 + i as f64 * 0.7;
                let y1 = y0 + 0.35;
                r.push((w(12.5), w(y0)));
                r.push((w(12.5), w(y1)));
                r.push((w(2.0), w(y1)));
                r.push((w(2.0), w(y0 + 0.7)));
            }
            r.push((w(2.0), w(12.0)));
            r.push((w(2.0), w(4.0)));
            r
        };
        let ext = square(0.5, 15.5);
        let g = polygon(&[&ext, &comb_hole]);
        for z in [4u8, 5, 6] {
            let mine: std::collections::HashMap<_, _> = descend(&g, z).into_iter().collect();
            for (tile, expect) in direct(&g, z) {
                let Some(got) = mine.get(&tile) else {
                    bad.push(format!("comb hole z{z} tile {tile:?}: the descent lost the tile"));
                    continue;
                };
                let (Geometry::Polygons(a), Geometry::Polygons(b)) = (got, &expect) else { continue };
                if a.len() != b.len() {
                    bad.push(format!("comb hole z{z} tile {tile:?}: {} polygons vs {}", a.len(), b.len()));
                    continue;
                }
                for (i, (ra, rb)) in a.iter().zip(b).enumerate() {
                    checked += 1;
                    if ra.len() != rb.len() {
                        bad.push(format!(
                            "comb hole z{z} tile {tile:?} polygon {i}: {} rings vs {}",
                            ra.len(),
                            rb.len()
                        ));
                        continue;
                    }
                    let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                        rings.iter().map(|r| signed_area(r)).sum()
                    };
                    let (na, nb) = (net(ra), net(rb));
                    if (na - nb).abs() > SLOP * (1.0 + nb.abs()) {
                        bad.push(format!(
                            "comb hole z{z} tile {tile:?} polygon {i}: net area {na} vs {nb}"
                        ));
                    }
                }
            }
        }

        for case in 0..120 {
            // A big wobbly exterior with two wobbly holes inside it, at a zoom deep
            // enough that the descent runs several levels.
            let ext = wobbly_ring(8.0, 8.0, 5.0, 40, &mut next);
            let holes: Vec<Vec<Pt>> = (0..2)
                .map(|k| {
                    let (ox, oy) = (6.0 + 4.0 * k as f64, 6.0 + 3.0 * next());
                    wobbly_ring(ox, oy, 1.6, 24, &mut next)
                })
                .collect();
            let mut rings: Vec<&[Pt]> = vec![&ext];
            rings.extend(holes.iter().map(|h| h.as_slice()));
            let g = polygon(&rings);

            let mine: std::collections::HashMap<_, _> = descend(&g, 5).into_iter().collect();
            for (tile, expect) in direct(&g, 5) {
                let Some(got) = mine.get(&tile) else {
                    bad.push(format!("case {case} tile {tile:?}: the descent lost the tile"));
                    continue;
                };
                let (Geometry::Polygons(a), Geometry::Polygons(b)) = (got, &expect) else {
                    continue;
                };
                for (i, (ra, rb)) in a.iter().zip(b).enumerate() {
                    checked += 1;
                    if ra.len() != rb.len() {
                        bad.push(format!(
                            "case {case} tile {tile:?} polygon {i}: {} rings vs {}",
                            ra.len(),
                            rb.len()
                        ));
                        continue;
                    }
                    let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                        rings.iter().map(|r| signed_area(r)).sum()
                    };
                    let (na, nb) = (net(ra), net(rb));
                    if (na - nb).abs() > SLOP * (1.0 + nb.abs()) {
                        bad.push(format!(
                            "case {case} tile {tile:?} polygon {i}: net area {na} vs {nb}"
                        ));
                    }
                }
            }
        }
        assert!(checked > 0, "the fixture produced no polygons to check");
        assert!(bad.is_empty(), "{} of {checked}:\n{}", bad.len(), bad[..bad.len().min(8)].join("\n"));
    }

    /// **The composition property, tested directly.** Nothing in [`crate::clip`] asserts
    /// `clip(clip(g, A), B) == clip(g, B)` for `B` inside `A`, and the whole descent is
    /// that identity applied `z - level` times. This searches for a counterexample over
    /// random rings, random holes and random nested rect pairs.
    ///
    /// Ring COUNT and NET signed area, because those are what a failure costs: a lost
    /// ring is a lost hole, and a hole that vanishes renders as a lake filled in solid.
    #[test]
    fn clipping_twice_is_clipping_once_for_a_nested_rect() {
        let mut seed = 0x1234_5678_9ABC_DEF1u64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };

        let mut bad: Vec<String> = Vec::new();
        let mut checked = 0usize;
        for case in 0..4000 {
            // A wobbly exterior, and half the time a wobbly hole inside it. Wobble is
            // wide enough that the rings are genuinely concave, which is the only case
            // composition is in doubt for.
            let ext = wobbly_ring(4.0, 4.0, 3.0, 6 + (next() * 24.0) as usize, &mut next);
            let mut rings: Vec<Vec<Pt>> = vec![ext];
            if next() < 0.5 {
                rings.push(wobbly_ring(
                    3.5 + next(),
                    3.5 + next(),
                    0.6 + next(),
                    6 + (next() * 18.0) as usize,
                    &mut next,
                ));
            }
            let refs: Vec<&[Pt]> = rings.iter().map(|r| r.as_slice()).collect();
            let g = polygon(&refs);

            // Outer rect A, and inner rect B strictly inside it -- the relationship a
            // parent cell and a descendant cell always have.
            let (ax0, ay0) = (next() * 3.0, next() * 3.0);
            let (aw, ah) = (1.0 + next() * 6.0, 1.0 + next() * 6.0);
            let a = Rect {
                min_x: w(ax0),
                min_y: w(ay0),
                max_x: w(ax0 + aw),
                max_y: w(ay0 + ah),
            };
            let (bx0, by0) = (ax0 + next() * aw * 0.5, ay0 + next() * ah * 0.5);
            let b = Rect {
                min_x: w(bx0),
                min_y: w(by0),
                max_x: w(bx0 + next() * (ax0 + aw - bx0)),
                max_y: w(by0 + next() * (ay0 + ah - by0)),
            };

            let once = clip_geometry(&g, &b);
            let twice = clip_geometry(&clip_geometry(&g, &a), &b);
            checked += 1;

            let (Geometry::Polygons(p1), Geometry::Polygons(p2)) = (&once, &twice) else {
                continue;
            };
            if p1.len() != p2.len() {
                bad.push(format!("case {case}: {} polygons once vs {} twice", p1.len(), p2.len()));
                continue;
            }
            for (i, (r1, r2)) in p1.iter().zip(p2).enumerate() {
                if r1.len() != r2.len() {
                    bad.push(format!(
                        "case {case} polygon {i}: {} rings once vs {} twice (A {a:?} B {b:?})",
                        r1.len(),
                        r2.len()
                    ));
                    continue;
                }
                let net = |rings: &Vec<Vec<SigPt>>| -> f64 {
                    rings.iter().map(|r| signed_area(r)).sum()
                };
                let (n1, n2) = (net(r1), net(r2));
                if (n1 - n2).abs() > SLOP * (1.0 + n1.abs()) {
                    bad.push(format!(
                        "case {case} polygon {i}: net area {n1} once vs {n2} twice"
                    ));
                }
            }
        }
        assert!(checked > 0);
        assert!(
            bad.is_empty(),
            "{} of {checked} nested clips disagree:\n{}",
            bad.len(),
            bad[..bad.len().min(6)].join("\n")
        );
    }

    /// **Byte-identity is genuinely gone.** Recorded as a test so nobody later reads
    /// the shape test above as a byte guarantee and builds on it.
    ///
    /// Three differences, in descending order of how many tiles they touch, none of
    /// them a shape:
    ///
    /// 1. **A ring's starting vertex.** The largest share by far. Sutherland-Hodgman
    ///    keeps its input's rotation and the descent's input is its parent's answer.
    /// 2. **Fewer collinear spurs.** A concave ring's zero-area run along a clip
    ///    boundary comes out shorter when the clip happens in stages, so the descent
    ///    emits fewer vertices than a direct clip, never more.
    /// 3. **Last-bit drift on a line crossing.** Liang-Barsky interpolates both
    ///    coordinates, so a crossing computed off an already-interpolated vertex can
    ///    land an ULP away -- 1e-12 world units against a quantisation grid of 1.
    ///
    /// What is NOT in that list is the thing the plan expected to dominate: surviving
    /// vertices on internal cell boundaries. [`the_descent_invents_no_vertices`] is
    /// where that is measured, and it is why there is no collinear-reduction pass.
    #[test]
    fn the_descent_is_not_byte_identical_and_this_is_where_that_is_written_down() {
        let (mut differing, mut total) = (0usize, 0usize);
        for (_, g, z) in corpus() {
            let d = compare(&g, z);
            differing += d.rotated;
            total += d.tiles;
        }
        assert!(
            differing > 0,
            "if no tile's bytes differed, byte-identity is back and this test is a lie"
        );
        assert!(differing < total, "some tiles must still match exactly");
    }

    /// A feature small enough for one tile must not recurse at all
    /// the leaf, and the leaf clip is the only clip. This is the overwhelmingly common
    /// case -- a building, a shop, a street -- and it has to stay exactly as cheap as
    /// it was.
    #[test]
    fn a_feature_inside_one_tile_is_clipped_once() {
        let g = polygon(&[&square(0.2, 0.8)]);
        let mut clips = 0usize;
        let mut tiles = Vec::new();
        subdivide(&g, 6, EXTENT, BUFFER, &mut |tx, ty, _| {
            clips += 1;
            tiles.push((tx, ty));
        });
        assert_eq!(tiles, vec![(0, 0)]);
        assert_eq!(clips, 1);
    }

    /// The start cell is chosen from the PADDED range, so a feature that only reaches
    /// into a neighbour's buffer still descends towards that neighbour. Picking the
    /// cell from the unpadded box would leave a seam at the join.
    #[test]
    fn a_feature_reaching_only_into_a_neighbours_buffer_still_lands_there() {
        // Two units inside tile 1's western edge, well within the 5-unit buffer.
        let x = w(1.0) + 2.0;
        let g = line(&[(x, w(1.2)), (x, w(1.8))]);
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, _| tiles.push((tx, ty)));
        tiles.sort_unstable();
        assert_eq!(tiles, vec![(0, 1), (1, 1)], "tile 0's buffer holds it too");
    }

    /// A leaf's cell rect must be the tile rect the old loop clipped against, to the
    /// bit -- not merely close to it. A single unit of drift would move every
    /// crossing on every tile boundary in the archive.
    #[test]
    fn a_leaf_cell_is_exactly_the_tile_rect() {
        for (tx, ty, z) in [(0u64, 0u64, 0u8), (1, 1, 4), (13, 6, 4), (827, 1391, 14)] {
            assert_eq!(
                cell_rect(z, tx, ty, z, EXTENT, BUFFER),
                geom::tile_rect(tx, ty, EXTENT, BUFFER),
                "leaf ({tx},{ty}) at z{z}"
            );
        }
    }

    /// A cell contains its four children, buffer and all. This is the monotonicity the
    /// whole descent rests on: if it failed, an ancestor clip could remove geometry a
    /// leaf clip would have kept, and features would vanish from tiles.
    #[test]
    fn a_cells_buffered_rect_contains_its_childrens() {
        let z = 5u8;
        for level in 0..z {
            for (cx, cy) in [(0u64, 0u64), (1, 0), (1, 2)] {
                let parent = cell_rect(level, cx, cy, z, EXTENT, BUFFER);
                for (dx, dy) in [(0u64, 0u64), (1, 0), (0, 1), (1, 1)] {
                    let child = cell_rect(level + 1, cx * 2 + dx, cy * 2 + dy, z, EXTENT, BUFFER);
                    assert!(
                        parent.min_x <= child.min_x
                            && parent.min_y <= child.min_y
                            && child.max_x <= parent.max_x
                            && child.max_y <= parent.max_y,
                        "level {level} cell ({cx},{cy}) does not contain child (+{dx},+{dy}): \
                         {parent:?} vs {child:?}"
                    );
                }
            }
        }
    }

    /// Emptiness prunes, so a feature must not be handed tiles it does not reach.
    #[test]
    fn tiles_a_feature_does_not_reach_are_not_visited() {
        // A diagonal across a 8x8 tile block: the far corners are empty.
        let g = line(&[(w(0.5), w(0.5)), (w(7.5), w(7.5))]);
        let mut tiles: Vec<(u64, u64)> = Vec::new();
        subdivide(&g, 4, EXTENT, BUFFER, &mut |tx, ty, _| tiles.push((tx, ty)));
        assert!(tiles.contains(&(0, 0)) && tiles.contains(&(7, 7)));
        assert!(!tiles.contains(&(0, 7)), "the line never goes there");
        assert!(!tiles.contains(&(7, 0)), "nor there");
        assert!(tiles.len() < 24, "walked {} tiles of the 64-tile box", tiles.len());
    }

    /// An empty geometry, and one entirely off the grid, produce nothing rather than
    /// panicking on a start cell that does not exist.
    #[test]