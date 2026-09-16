use super::*;

    /// A `dim x dim` heightmap from a metres-above-sea closure, applying the +32768 bias the format
    /// stores.
    fn heightmap(dim: u16, metres: impl Fn(u16, u16) -> i32) -> Heightmap {
        let mut samples = Vec::with_capacity((dim as usize).pow(2));
        for row in 0..dim {
            for col in 0..dim {
                samples.push((metres(col, row) + 32768) as u16);
            }
        }
        Heightmap { dim, samples }
    }

    /// Read back one vertex as `(position, normal)`.
    fn vertex(v: &[f32], i: usize) -> ([f32; 3], [f32; 3]) {
        let at = i * FLOATS_PER_VERTEX;
        (
            [v[at], v[at + 1], v[at + 2]],
            [v[at + 3], v[at + 4], v[at + 5]],
        )
    }

    #[test]
    fn a_flat_heightmap_makes_a_flat_grid_pointing_up() {
        // Sea level everywhere: every vertex sits at z 0 with a straight-up normal, so at pitch 0
        // it is indistinguishable from the old flat ground.
        let hm = heightmap(5, |_, _| 0);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        let grid = 25;
        let skirt = 4 * (5 - 1);
        assert_eq!(
            v.len() / FLOATS_PER_VERTEX,
            grid + skirt,
            "one vertex per sample plus one skirt vertex per edge sample",
        );
        assert_eq!(
            i.len(),
            4 * 4 * 6 + skirt * 6,
            "two triangles per cell over a 4x4 cell grid plus one wall quad per edge segment",
        );
        for k in 0..grid {
            let (p, n) = vertex(&v, k);
            assert!(p[2].abs() < 1e-6, "flat ground sits at z 0, got {}", p[2]);
            assert!(n[0].abs() < 1e-6 && n[1].abs() < 1e-6 && (n[2] - 1.0).abs() < 1e-6);
        }
    }

    #[test]
    fn a_synthetic_hill_displaces_the_ground() {
        // A single high sample in the centre must lift that vertex to metres / ground_width, the
        // same tile-normalised unit buildings use, and its neighbours must tilt their normals off
        // vertical toward it.
        let dim = 5u16;
        let peak_m = 300; // 300 m
        let ground = 1200.0; // 1200 m across the tile, so the peak is 0.25 tile-norm high.
        let centre = dim / 2;
        let hm = heightmap(dim, |c, r| {
            if c == centre && r == centre {
                peak_m
            } else {
                0
            }
        });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);

        let (peak, peak_n) = vertex(&v, (centre as usize) * dim as usize + centre as usize);
        assert!(
            (peak[2] - peak_m as f32 / ground as f32).abs() < 1e-6,
            "the hill rises to metres/ground_width: {} vs {}",
            peak[2],
            peak_m as f32 / ground as f32,
        );
        assert!(
            peak_n[2] > 0.99,
            "the very top is locally flat, so its normal stays near vertical"
        );

        // A sample one step west of the peak sits on the slope, so its normal leans off vertical.
        let (_, slope_n) = vertex(&v, (centre as usize) * dim as usize + centre as usize - 1);
        assert!(
            slope_n[0].abs() > 1e-3 || slope_n[1].abs() > 1e-3,
            "a slope vertex must tilt its normal, got {slope_n:?}",
        );
        assert!(slope_n[2] < 0.9999, "and lean it away from straight up");

        // The displaced grid still reads as the tile footprint from overhead: every x/y in 0..1.
        for k in 0..v.len() / FLOATS_PER_VERTEX {
            let (p, _) = vertex(&v, k);
            assert!(
                (0.0..=1.0).contains(&p[0]) && (0.0..=1.0).contains(&p[1]),
                "{p:?} left the tile"
            );
        }
    }

    #[test]
    fn a_below_sea_sample_dips_below_zero() {
        // The bias lets terrain go below sea level (the Dead Sea, Death Valley); a -100 m sample
        // must come out negative rather than clamped.
        let hm = heightmap(3, |c, r| if c == 1 && r == 1 { -100 } else { 0 });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        let (mid, _) = vertex(&v, 4);
        assert!(
            mid[2] < 0.0,
            "a below-sea sample dips below z 0, got {}",
            mid[2]
        );
    }

    #[test]
    fn a_degenerate_heightmap_emits_nothing() {
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(
            &Heightmap {
                dim: 1,
                samples: vec![32768],
            },
            1000.0,
            &mut v,
            &mut i,
        );
        assert!(
            v.is_empty() && i.is_empty(),
            "a 1x1 grid has no cell to tessellate"
        );
    }

    #[test]
    fn every_index_is_in_range_and_triangles_are_whole() {
        let hm = heightmap(9, |c, r| (c as i32 * 7 + r as i32 * 3) % 50);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 800.0, &mut v, &mut i);
        let count = (v.len() / FLOATS_PER_VERTEX) as u32;
        assert_eq!(i.len() % 3, 0);
        for &idx in &i {
            assert!(idx < count, "index {idx} past the {count} vertices");
        }
        for f in &v {
            assert!(f.is_finite(), "a non-finite terrain vertex");
        }
    }

    #[test]
    fn tessellation_appends_rather_than_replaces() {
        // Two tiles' grids can share a buffer, so a second call must rebase its indices past the
        // first's vertices.
        let hm = heightmap(3, |_, _| 0);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        let first_vertices = (v.len() / FLOATS_PER_VERTEX) as u32;
        let first_indices = i.len();
        tessellate(&hm, 1000.0, &mut v, &mut i);
        assert!(
            i[first_indices..].iter().all(|&idx| idx >= first_vertices),
            "the second grid must rebase onto its own vertices",
        );
    }

    /// Smoothing over a planar ramp must not tilt the normals: a blur of a plane is the same
    /// plane, so every normal matches the analytic slope normal exactly.
    #[test]
    fn a_planar_field_keeps_identical_normals() {
        // z = 2c + 3r metres over a 7x7 grid: interior central differences recover the slope
        // exactly, blurred or not.
        let hm = heightmap(7, |c, r| 2 * c as i32 + 3 * r as i32);
        let ground = 1000.0;
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // dz/du = 2 m per texel = 2 * dim-1 steps over the tile, normalised by ground width.
        let step = 1.0 / 6.0;
        let ex = -2.0 / ground as f32 / step;
        let ey = -3.0 / ground as f32 / step;
        let len = (ex * ex + ey * ey + 1.0).sqrt();
        let want = [ex / len, ey / len, 1.0 / len];
        let grid = 7 * 7;
        for k in 0..grid {
            let (p, n) = vertex(&v, k);
            // Positions stay on the raw plane.
            let col = k % 7;
            let row = k / 7;
            let z = (2 * col as i32 + 3 * row as i32) as f32 / ground as f32;
            assert!(
                (p[2] - z).abs() < 1e-6,
                "position {k} must stay exact, got {}",
                p[2]
            );
            for a in 0..3 {
                assert!(
                    (n[a] - want[a]).abs() < 1e-5,
                    "normal {k} must match the analytic slope normal, got {n:?} want {want:?}",
                );
            }
        }
    }

    /// The point of the blur: checkerboard DEM noise must tilt interior normals far less than
    /// the raw central differences would.
    #[test]
    fn a_noisy_field_gets_softer_normals() {
        // ±40 m checkerboard over a 9x9 grid: raw central differences see a ±80 m swing per
        // texel step; the 3×3 blur averages each interior texel with its opposite-phase
        // neighbours, which must shrink the off-vertical tilt.
        let dim = 9usize;
        let amp = 40i32;
        let hm = heightmap(dim as u16, |c, r| if (c + r) % 2 == 0 { amp } else { -amp });
        let ground = 1000.0;
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // Raw tilt the unblurred differences would produce, at an interior vertex: dz/du over
        // one step is 2*amp metres, normalised.
        let step = 1.0 / (dim as f32 - 1.0);
        let raw_tilt = 2.0 * amp as f32 / ground as f32 / step;
        // Interior vertex one step in from the corner: its kernel mixes blurred interior with
        // the verbatim border, so the tilt shrinks but stays nonzero — the softening case,
        // rather than a fully-interior vertex whose symmetric kernel would erase it outright.
        let (_, n) = vertex(&v, dim + 1);
        let tilt = (n[0] * n[0] + n[1] * n[1]).sqrt();
        let raw_off_vertical = raw_tilt / (raw_tilt * raw_tilt + 1.0).sqrt();
        assert!(
            tilt < 0.5 * raw_off_vertical,
            "blurred tilt {tilt} must be well under the raw {raw_off_vertical}",
        );
        assert!(n[2] > 0.5, "the normal must stay mostly upright, got {n:?}");
    }

    /// The border ring passes through the blur untouched — edge differences read the raw
    /// samples, so the neighbour tile (which stores its own copy of the shared edge) derives
    /// the same slopes and the seam is invisible by construction.
    #[test]
    fn border_texels_are_bit_identical_pre_post_blur() {
        let dim = 7usize;
        let hm = heightmap(dim as u16, |c, r| {
            (c as i32 * 13 + r as i32 * 29 + 7) % 61 - 30
        });
        let ground = 800.0;
        let factor = 1.0 / ground;
        let mut raw = Vec::with_capacity(dim * dim);
        for row in 0..dim {
            for col in 0..dim {
                raw.push(
                    (Heightmap::metres(hm.sample(col as u16, row as u16).unwrap()) as f64 * factor)
                        as f32,
                );
            }
        }
        let blurred = smooth_heights(&raw, dim);
        for k in 0..raw.len() {
            let col = k % dim;
            let row = k / dim;
            let on_border = col == 0 || row == 0 || col == dim - 1 || row == dim - 1;
            if on_border {
                assert_eq!(
                    blurred[k].to_bits(),
                    raw[k].to_bits(),
                    "border texel ({col}, {row}) must pass through the blur untouched",
                );
            }
        }
        // And at least one interior texel must actually change, or the blur is a no-op.
        assert!(
            (1..dim - 1).any(|row| (1..dim - 1)
                .any(|col| blurred[row * dim + col].to_bits() != raw[row * dim + col].to_bits())),
            "the blur must move at least one interior texel",
        );
    }

    /// Positions and indices are the blur's non-goals: the smoothed field must never leak into
    /// either. A spiky field is the sharpest probe — raw spikes stay spiky in z, indices stay
    /// the untouched grid triangulation.
    #[test]
    fn positions_and_indices_are_untouched_by_smoothing() {
        let dim = 5u16;
        let peak_m = 300;
        let ground = 1200.0;
        let centre = dim / 2;
        let hm = heightmap(dim, |c, r| {
            if c == centre && r == centre {
                peak_m
            } else {
                0
            }
        });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        // The peak vertex keeps its exact raw height — smoothing the position would sink it.
        let (peak, _) = vertex(&v, centre as usize * dim as usize + centre as usize);
        assert_eq!(
            peak[2].to_bits(),
            (peak_m as f32 / ground as f32).to_bits(),
            "the peak height must be the exact raw sample",
        );
        // The untouched grid indices: two triangles per cell, the same winding as before.
        let dim32 = dim as u32;
        let mut want = Vec::new();
        for row in 0..dim - 1 {
            for col in 0..dim - 1 {
                let a = row as u32 * dim32 + col as u32;
                want.extend_from_slice(&[a, a + 1, a + dim32 + 1, a, a + dim32 + 1, a + dim32]);
            }
        }
        assert_eq!(
            &i[..want.len()],
            &want[..],
            "grid indices must stay the plain grid triangulation under the skirt",
        );
    }

    /// The skirt half of the buffers: one skirt vertex per edge sample, hanging SKIRT_DEPTH
    /// below its edge vertex with `(u, v)` and the normal copied bit-for-bit.
    #[test]
    fn skirt_vertices_hang_below_their_edge_vertices() {
        // A sloped field, so the drop shows against nonzero edge heights and tilted normals.
        let dim = 5usize;
        let ground = 1000.0;
        let hm = heightmap(dim as u16, |c, r| (c as i32 * 11 + r as i32 * 17) % 43 - 21);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        let grid = dim * dim;
        let ring = edge_ring(dim);
        assert_eq!(
            v.len() / FLOATS_PER_VERTEX,
            grid + ring.len(),
            "one skirt vertex per edge sample",
        );
        for (k, &edge) in ring.iter().enumerate() {
            let (top_p, top_n) = vertex(&v, edge);
            let (skirt_p, skirt_n) = vertex(&v, grid + k);
            assert_eq!(
                skirt_p[0].to_bits(),
                top_p[0].to_bits(),
                "skirt {k} shares its edge vertex's u",
            );
            assert_eq!(
                skirt_p[1].to_bits(),
                top_p[1].to_bits(),
                "skirt {k} shares its edge vertex's v",
            );
            assert_eq!(
                skirt_p[2].to_bits(),
                (top_p[2] - SKIRT_DEPTH).to_bits(),
                "skirt {k} hangs SKIRT_DEPTH below the edge",
            );
            for a in 0..3 {
                assert_eq!(
                    skirt_n[a].to_bits(),
                    top_n[a].to_bits(),
                    "skirt {k} keeps its edge vertex's normal",
                );
            }
        }
    }

    /// The grid half is untouched by the skirt: every grid position recomputed from the raw
    /// samples must match bit-for-bit, so the top edge the walls hang from stays exact.
    #[test]
    fn grid_positions_are_bit_identical_under_the_skirt() {
        let dim = 6usize;
        let ground = 800.0;
        let factor = 1.0 / ground;
        let hm = heightmap(dim as u16, |c, r| {
            (c as i32 * 13 + r as i32 * 29 + 7) % 61 - 30
        });
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, ground, &mut v, &mut i);
        let step = 1.0 / (dim as f32 - 1.0);
        for k in 0..dim * dim {
            let col = k % dim;
            let row = k / dim;
            let stored = hm.sample(col as u16, row as u16).unwrap();
            let (p, _) = vertex(&v, k);
            assert_eq!(
                p[0].to_bits(),
                (col as f32 * step).to_bits(),
                "grid {k} keeps its exact u",
            );
            assert_eq!(
                p[1].to_bits(),
                (row as f32 * step).to_bits(),
                "grid {k} keeps its exact v",
            );
            assert_eq!(
                p[2].to_bits(),
                ((Heightmap::metres(stored) as f64 * factor) as f32).to_bits(),
                "grid {k} keeps its exact raw height",
            );
        }
    }

    /// Past the grid prefix, the indices walk the edge ring hanging one wall quad per segment —
    /// and the buffer as a whole stays triple-whole with every index in range.
    #[test]
    fn skirt_indices_close_every_wall_quad() {
        let dim = 5usize;
        let hm = heightmap(dim as u16, |c, r| (c as i32 * 7 + r as i32 * 3) % 50);
        let mut v = Vec::new();
        let mut i = Vec::new();
        tessellate(&hm, 800.0, &mut v, &mut i);
        let grid = dim * dim;
        let ring = edge_ring(dim);
        let grid_indices = (dim - 1) * (dim - 1) * 6;
        assert_eq!(
            i.len(),
            grid_indices + ring.len() * 6,
            "one wall quad per edge segment past the grid prefix",
        );
        assert_eq!(i.len() % 3, 0, "indices come in threes including the skirt");
        let count = (v.len() / FLOATS_PER_VERTEX) as u32;
        for &idx in &i {
            assert!(idx < count, "index {idx} past the {count} vertices");
        }
        for f in &v {
            assert!(f.is_finite(), "a non-finite terrain vertex");
        }
        let n = ring.len() as u32;
        for (k, _) in ring.iter().enumerate() {
            let k = k as u32;
            let t0 = ring[k as usize] as u32;
            let t1 = ring[((k + 1) % n) as usize] as u32;
            let s0 = grid as u32 + k;
            let s1 = grid as u32 + (k + 1) % n;
            let at = grid_indices + k as usize * 6;
            assert_eq!(
                &i[at..at + 6],
                &[t0, t1, s1, t0, s1, s0],
                "wall quad {k} hangs top0/top1/skirt1/skirt0",
            );
        }
    }
