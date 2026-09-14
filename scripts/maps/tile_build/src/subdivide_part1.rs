#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::{Pt, SigPt};
    use crate::mvt::DEFAULT_EXTENT;

    const EXTENT: u32 = DEFAULT_EXTENT;
    const BUFFER: f64 = geom::DEFAULT_BUFFER;

    /// A world coordinate `t` tiles along an axis.
    fn w(t: f64) -> f64 {
        t * EXTENT as f64
    }

    fn sig(pts: &[Pt]) -> Vec<SigPt> {
        pts.iter().map(|&(x, y)| SigPt::new(x, y)).collect()
    }

    fn line(pts: &[Pt]) -> Geometry<SigPt> {
        let mut g = Geometry::Lines(vec![sig(pts)]);
        crate::simplify::annotate(&mut g);
        g
    }

    fn polygon(rings: &[&[Pt]]) -> Geometry<SigPt> {
        let mut g = Geometry::Polygons(vec![rings.iter().map(|r| sig(r)).collect()]);
        crate::simplify::annotate(&mut g);
        g
    }

    /// A closed axis-aligned ring, in tile units.
    fn square(min: f64, max: f64) -> Vec<Pt> {
        vec![
            (w(min), w(min)),
            (w(max), w(min)),
            (w(max), w(max)),
            (w(min), w(max)),
            (w(min), w(min)),
        ]
    }

    /// A comb: `teeth` prongs pointing east off a spine on the west.
    ///
    /// The shape Sutherland-Hodgman is worst at. A clip that cuts the prongs off from
    /// the spine leaves several disjoint pieces, which S-H returns as ONE
    /// self-touching ring joined by zero-width slivers along the clip boundary. A
    /// two-prong U reaches that case once; a comb reaches it many times over, with
    /// slivers stacked on the same boundary line -- and clipping *that* again is the
    /// thing the descent does and a single clip never did.
    fn comb(teeth: usize, min: f64, max: f64) -> Geometry<SigPt> {
        let span = max - min;
        let spine = min + span * 0.15;
        let mut r: Vec<Pt> = vec![(w(min), w(min))];
        for i in 0..teeth {
            // Two bands per tooth: out along the top of the tooth, back along the
            // bottom of the gap above it.
            let y0 = min + span * (i as f64 * 2.0 + 0.4) / (teeth as f64 * 2.0);
            let y1 = min + span * (i as f64 * 2.0 + 1.4) / (teeth as f64 * 2.0);
            r.push((w(spine), w(y0)));
            r.push((w(max), w(y0)));
            r.push((w(max), w(y1)));
            r.push((w(spine), w(y1)));
        }
        r.push((w(spine), w(max)));
        r.push((w(min), w(max)));
        r.push((w(min), w(min)));
        let mut g = Geometry::Polygons(vec![vec![sig(&r)]]);
        crate::simplify::annotate(&mut g);
        g
    }

    /// Collect the descent's answer per tile.
    fn descend(g: &Geometry<SigPt>, z: u8) -> Vec<((u64, u64), Geometry<SigPt>)> {
        let mut out = Vec::new();
        subdivide(g, z, EXTENT, BUFFER, &mut |tx, ty, clipped| {
            out.push(((tx, ty), clipped.clone()))
        });
        out
    }

    /// What the old loop produced: `tiles_touched`, then a clip of the whole
    /// geometry against each tile, skipping the ones that came back empty.
    fn direct(g: &Geometry<SigPt>, z: u8) -> Vec<((u64, u64), Geometry<SigPt>)> {
        let mut touched = Vec::new();
        geom::tiles_touched(g, z, EXTENT, BUFFER, &mut touched);
        let mut out = Vec::new();
        for (tx, ty) in touched {
            let clipped = clip_geometry(g, &geom::tile_rect(tx, ty, EXTENT, BUFFER));
            if is_empty(&clipped) {
                continue;
            }
            out.push(((tx, ty), clipped));
        }
        out
    }

    fn vertices(g: &Geometry<SigPt>) -> usize {
        match g {
            Geometry::Points(p) => p.len(),
            Geometry::Lines(l) => l.iter().map(Vec::len).sum(),
            Geometry::Polygons(p) => p.iter().flatten().map(Vec::len).sum(),
        }
    }

    /// How far apart two world coordinates may be and still be the same vertex.
    ///
    /// One world unit is 1/4096 of a tile and [`geom::quantize`] rounds to the nearest
    /// one, so anything below half a unit is invisible in the output. This is seven
    /// orders of magnitude tighter than that: the drift the descent introduces is
    /// last-bit drift from interpolating a crossing off an already-interpolated
    /// vertex, not a shape moving.
    const SLOP: f64 = 1e-6;

    fn near(a: SigPt, b: SigPt) -> bool {
        (a.x - b.x).abs() <= SLOP
            && (a.y - b.y).abs() <= SLOP
            // Equality first, so two unremovable vertices match: `inf - inf` is `NaN`.
            && (a.sig == b.sig || (a.sig - b.sig).abs() <= SLOP)
    }

    /// Whether two rings are the same ring, allowing for a different starting vertex
    /// and for Sutherland-Hodgman's zero-area bookkeeping.
    ///
    /// Two things are forgiven, and both are things the algorithm does to itself:
    ///
    /// * **Rotation.** S-H's output order follows its input's, and clipping in stages
    ///   changes the intermediate order -- so the descent's rings start at a different
    ///   vertex. A rotation is the same polygon: same edges, same winding, same area,
    ///   and MVT re-states the start vertex as a `MoveTo` either way.
    /// * **Collinear spurs.** Clipping a concave ring leaves degenerate runs along the
    ///   clip boundary, and how many vertices a run costs depends on how many passes
    ///   built it. Measured over the corpus, the direct clip carries MORE of them than
    ///   the descent does, so this forgives the descent nothing it needs.
    ///
    /// What is not forgiven is a vertex that carries shape. Reducing both rings to
    /// their corners and then demanding a rotation is what makes that distinction, and
    /// the signed-area check beside it is the independent witness.
    fn same_ring(a: &[SigPt], b: &[SigPt]) -> bool {
        let (a, b) = (corners(a), corners(b));
        let n = a.len();
        n == b.len() && (n == 0 || (0..n).any(|k| (0..n).all(|i| near(a[i], b[(i + k) % n]))))
    }

    /// A ring's corners: opened, and with every vertex that turns no corner removed.
    ///
    /// Removing one vertex can leave its neighbours collinear with each other, so this
    /// runs to a fixed point rather than in one pass.
    fn corners(ring: &[SigPt]) -> Vec<SigPt> {
        let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
        let mut out: Vec<SigPt> = ring[..ring.len() - usize::from(closed)].to_vec();
        loop {
            let n = out.len();
            if n < 3 {
                return out;
            }
            let Some(at) = (0..n).find(|&i| straight(out[(i + n - 1) % n], out[i], out[(i + 1) % n]))
            else {
                return out;
            };
            out.remove(at);
        }
    }

    /// Whether `b` sits on the line through `a` and `c`, turning no corner.
    ///
    /// The cross product scaled by the two edge lengths, so this is a bound on the
    /// sine of the turn rather than on an area -- an area threshold would call a long
    /// gentle bend straight and a short sharp one bent.
    fn straight(a: SigPt, b: SigPt, c: SigPt) -> bool {
        let ((ax, ay), (bx, by), (cx, cy)) = (a.xy(), b.xy(), c.xy());
        let (ux, uy) = (bx - ax, by - ay);
        let (vx, vy) = (cx - bx, cy - by);
        let scale = (ux * ux + uy * uy).sqrt() * (vx * vx + vy * vy).sqrt();
        (ux * vy - uy * vx).abs() <= SLOP * (scale + 1.0)
    }

    fn signed_area(ring: &[SigPt]) -> f64 {
        let n = ring.len();
        let mut a = 0.0;
        for i in 0..n {
            let (x1, y1) = ring[i].xy();
            let (x2, y2) = ring[(i + 1) % n].xy();
            a += x1 * y2 - x2 * y1;
        }
        a / 2.0
    }

    fn length(line: &[SigPt]) -> f64 {
        line.windows(2)
            .map(|w| {
                let ((x1, y1), (x2, y2)) = (w[0].xy(), w[1].xy());
                ((x2 - x1).powi(2) + (y2 - y1).powi(2)).sqrt()
            })
            .sum()
    }

    /// Whether two clips of the same feature into the same tile are the same shape.
    ///
    /// `None` when they are; otherwise why not. Rings are compared up to rotation and
    /// last-bit drift, everything else exactly: part counts, ring counts, vertex
    /// counts, and -- as an independent witness that a rotation really is a rotation
    /// -- signed area per ring and total length per line.
    fn same_shape(a: &Geometry<SigPt>, b: &Geometry<SigPt>) -> Option<String> {
        match (a, b) {
            (Geometry::Points(x), Geometry::Points(y)) => (x != y).then(|| "points differ".into()),
            (Geometry::Lines(x), Geometry::Lines(y)) => {
                if x.len() != y.len() {
                    return Some(format!("{} line parts vs {}", x.len(), y.len()));
                }
                for (i, (p, q)) in x.iter().zip(y).enumerate() {
                    if p.len() != q.len() {
                        return Some(format!("part {i}: {} vertices vs {}", p.len(), q.len()));
                    }
                    if !p.iter().zip(q).all(|(u, v)| near(*u, *v)) {
                        return Some(format!("part {i}: vertices moved"));
                    }
                    let (lp, lq) = (length(p), length(q));
                    if (lp - lq).abs() > SLOP * (1.0 + lq.abs()) {
                        return Some(format!("part {i}: length {lp} vs {lq}"));
                    }
                }
                None
            }
            (Geometry::Polygons(x), Geometry::Polygons(y)) => {
                if x.len() != y.len() {
                    return Some(format!("{} polygons vs {}", x.len(), y.len()));
                }
                for (i, (rp, rq)) in x.iter().zip(y).enumerate() {
                    if rp.len() != rq.len() {
                        return Some(format!("polygon {i}: {} rings vs {}", rp.len(), rq.len()));
                    }
                    for (j, (p, q)) in rp.iter().zip(rq).enumerate() {
                        if !same_ring(p, q) {
                            return Some(format!(
                                "polygon {i} ring {j}: not a rotation ({} corners vs {})",
                                corners(p).len(),
                                corners(q).len()
                            ));
                        }
                        let (ap, aq) = (signed_area(p), signed_area(q));
                        if (ap - aq).abs() > SLOP * (1.0 + aq.abs()) {
                            return Some(format!("polygon {i} ring {j}: area {ap} vs {aq}"));
                        }
                    }
                }
                None
            }
            _ => Some("geometry kind changed".into()),
        }
    }

    /// What one shape's descent cost against a direct clip, over every tile it
    /// reaches.
    struct Divergence {
        tiles: usize,
        /// Tiles one produced and the other did not, either way round.
        missing: Vec<(u64, u64)>,
        /// Tiles whose shapes are not the same shape, with the reason.
        wrong: Vec<((u64, u64), String)>,
        /// Tiles whose bytes differ although the shape does not -- a ring rotation, or
        /// a crossing that moved by its last bit. Measured, not asserted away.
        rotated: usize,
        descent_vertices: usize,
        direct_vertices: usize,
    }

    fn compare(g: &Geometry<SigPt>, z: u8) -> Divergence {
        let mine = descend(g, z);
        let mut want: std::collections::HashMap<(u64, u64), Geometry<SigPt>> =
            direct(g, z).into_iter().collect();
        let mut d = Divergence {
            tiles: 0,
            missing: Vec::new(),
            wrong: Vec::new(),
            rotated: 0,
            descent_vertices: 0,
            direct_vertices: 0,
        };
        for (tile, got) in &mine {
            d.tiles += 1;
            d.descent_vertices += vertices(got);
            // A tile the descent claims and the direct clip never reached is
            // over-inclusion; a tile it lost is a seam. Both are `missing`.
            let Some(expect) = want.remove(tile) else {
                d.missing.push(*tile);
                continue;
            };
            d.direct_vertices += vertices(&expect);
            match same_shape(got, &expect) {
                Some(why) => d.wrong.push((*tile, why)),
                None if *got != expect => d.rotated += 1,
                None => {}
            }
        }
        for (tile, expect) in want {
            d.tiles += 1;
            d.direct_vertices += vertices(&expect);
            d.missing.push(tile);
        }
        d.missing.sort_unstable();
        d
    }

    /// A deterministic corpus of shapes that cross a tile grid, each with the zoom to
    /// tile it at. Named so a failure says which shape broke.
    fn corpus() -> Vec<(&'static str, Geometry<SigPt>, u8)> {
        let mut out: Vec<(&'static str, Geometry<SigPt>, u8)> = vec![
            // A comb: many prongs, so one clip leaves Sutherland-Hodgman joining
            // several disjoint pieces into one self-touching ring. Clipping that
            // result again is the case a two-prong U does not reach.
            ("a comb of 9 teeth", comb(9, 0.5, 6.5), 4),
            ("a comb of 25 teeth", comb(25, 0.5, 6.5), 4),
            ("a comb on exact tile lines", comb(8, 1.0, 5.0), 4),
            ("a comb over many z7 tiles", comb(40, 2.5, 40.5), 7),
            // --- the no-recursion case: one tile, so the leaf clip is the only clip ---
            ("a ring inside one tile", polygon(&[&square(0.2, 0.8)]), 4),
            (
                "a line inside one tile",
                line(&[(w(0.2), w(0.2)), (w(0.5), w(0.7)), (w(0.8), w(0.3))]),
                4,
            ),
            // --- polygons spanning many tiles, which is the case this exists for ---
            ("a ring over 6x6 tiles", polygon(&[&square(0.5, 6.5)]), 4),
            ("a ring on exact tile corners", polygon(&[&square(1.0, 5.0)]), 4),
            (
                "a ring with a hole crossing tiles",
                polygon(&[&square(0.5, 6.5), &square(2.25, 4.75)]),
                4,
            ),
            (
                "a hole sharing a tile edge with its exterior",
                polygon(&[&square(1.0, 5.0), &square(2.0, 4.0)]),
                4,
            ),
            // A U straddling tile boundaries: Sutherland-Hodgman returns one ring with
            // a zero-area sliver here, which is the structure most at risk from
            // composing clips.
            (
                "a concave U over several tiles",
                polygon(&[&[
                    (w(0.5), w(0.5)),
                    (w(5.5), w(0.5)),
                    (w(5.5), w(2.5)),
                    (w(2.5), w(2.5)),
                    (w(2.5), w(4.5)),
                    (w(5.5), w(4.5)),
                    (w(5.5), w(6.5)),
                    (w(0.5), w(6.5)),
                    (w(0.5), w(0.5)),
                ]]),
                4,
            ),
        ];

        // A star, so edges cross tile lines at angles that are not nice fractions.
        let mut star: Vec<Pt> = (0..24)
            .map(|i| {
                let a = i as f64 * std::f64::consts::TAU / 24.0;
                let r = if i % 2 == 0 { 3.3 } else { 1.7 };
                (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
            })
            .collect();
        star.push(star[0]);
        out.push(("a 12-point star", polygon(&[&star]), 4));

        // --- lines, which the plan calls the fragile case -------------------
        out.push((
            "a long diagonal",
            line(&[(w(0.3), w(0.3)), (w(7.7), w(6.1))]),
            4,
        ));
        out.push((
            "a diagonal through exact tile corners",
            line(&[(w(0.0), w(0.0)), (w(8.0), w(8.0))]),
            4,
        ));
        out.push((
            "a staircase, one vertex per tile corner",
            line(&(0..=8).map(|i| (w(i as f64), w(i as f64))).collect::<Vec<_>>()),
            4,
        ));
        // Leaves and re-enters repeatedly, so the weld is exercised at every level.
        out.push((
            "a zigzag crossing one tile line many times",
            line(
                &(0..40)
                    .map(|i| {
                        let t = i as f64 / 39.0;
                        (w(2.0 + 0.6 * ((i % 2) as f64 * 2.0 - 1.0)), w(0.5 + 6.0 * t))
                    })
                    .collect::<Vec<_>>(),
            ),
            4,
        ));
        // A vertex sitting exactly on a tile line, which is where an inclusive
        // `inside` test and an interpolated crossing can disagree.
        out.push((
            "a line with a vertex exactly on a tile line",
            line(&[(w(0.5), w(1.5)), (w(2.0), w(2.0)), (w(3.5), w(1.5))]),
            4,
        ));
        // A spiral, for edges at many angles and lengths at once.
        out.push((
            "a spiral over the grid",
            line(
                &(0..200)
                    .map(|i| {
                        let t = i as f64 / 199.0;
                        let a = t * std::f64::consts::TAU * 3.0;
                        let r = 0.2 + 3.5 * t;
                        (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
                    })
                    .collect::<Vec<_>>(),
            ),
            4,
        ));

        // --- pseudo-random walks, deterministically seeded ------------------
        let mut seed = 0x2545_F491_4F6C_DD1Du64;
        let mut next = move || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            (seed >> 11) as f64 / (1u64 << 53) as f64
        };
        for _ in 0..8 {
            let pts: Vec<Pt> = (0..60)
                .map(|_| (w(next() * 8.0), w(next() * 8.0)))
                .collect();
            out.push(("a random walk", line(&pts), 4));
        }
        for _ in 0..8 {
            // A convex-ish blob: random radii around a circle, so the ring does not
            // self-intersect but its edges still cross tile lines arbitrarily.
            let mut ring: Vec<Pt> = (0..20)
                .map(|i| {
                    let a = i as f64 * std::f64::consts::TAU / 20.0;
                    let r = 1.0 + 2.5 * next();
                    (w(4.0 + r * a.cos()), w(4.0 + r * a.sin()))
                })
                .collect();
            ring.push(ring[0]);
            out.push(("a random blob", polygon(&[&ring]), 4));
        }

        // --- deeper zooms, so the descent runs more levels ------------------
        out.push(("a ring over many z7 tiles", polygon(&[&square(3.5, 60.5)]), 7));
        out.push((
            "a long diagonal over many z7 tiles",
            line(&[(w(1.3), w(2.3)), (w(90.7), w(70.1))]),