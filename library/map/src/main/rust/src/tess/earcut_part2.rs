/// The reference's own winding measure, kept in its own convention so
/// [`linked_list`]'s `clockwise` flag means what it does upstream.
fn signed_area(coords: &[i32], start: usize, end: usize) -> i64 {
    let mut sum = 0i64;
    let mut j = end - 2;
    let mut i = start;
    while i < end {
        sum += (coords[j] - coords[i]) as i64 * (coords[i + 1] + coords[j + 1]) as i64;
        j = i;
        i += 2;
    }
    sum
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Total unsigned area of the emitted triangles.
    fn covered_area(coords: &[i32], triangles: &[u32]) -> f64 {
        let mut total = 0.0;
        for t in triangles.chunks_exact(3) {
            let (a, b, c) = (t[0] as usize, t[1] as usize, t[2] as usize);
            let cross = (coords[b * 2] - coords[a * 2]) as i64
                * (coords[c * 2 + 1] - coords[a * 2 + 1]) as i64
                - (coords[c * 2] - coords[a * 2]) as i64
                    * (coords[b * 2 + 1] - coords[a * 2 + 1]) as i64;
            total += (cross as f64).abs() / 2.0;
        }
        total
    }

    fn shoelace(coords: &[i32]) -> f64 {
        let n = coords.len() / 2;
        let mut sum = 0i64;
        for i in 0..n {
            let j = (i + 1) % n;
            sum += coords[i * 2] as i64 * coords[j * 2 + 1] as i64
                - coords[j * 2] as i64 * coords[i * 2 + 1] as i64;
        }
        sum as f64
    }

    fn assert_no_degenerate(coords: &[i32], triangles: &[u32]) {
        for t in triangles.chunks_exact(3) {
            let (a, b, c) = (t[0] as usize, t[1] as usize, t[2] as usize);
            let cross = (coords[b * 2] - coords[a * 2]) as i64
                * (coords[c * 2 + 1] - coords[a * 2 + 1]) as i64
                - (coords[c * 2] - coords[a * 2]) as i64
                    * (coords[b * 2 + 1] - coords[a * 2 + 1]) as i64;
            assert!(cross != 0, "triangle {a}/{b}/{c} is degenerate");
        }
    }

    /// A monotone staircase: provably simple, with a reflex vertex per step.
    fn staircase(steps: i32, step: i32) -> Vec<i32> {
        let mut out = vec![0, 0, steps * step, 0];
        for i in (1..=steps).rev() {
            let y = (steps - i + 1) * step;
            out.extend_from_slice(&[i * step, y, (i - 1) * step, y]);
        }
        out
    }

    #[test]
    fn a_square_becomes_two_triangles_covering_its_area() {
        let square = [0, 0, 100, 0, 100, 100, 0, 100];
        let triangles = triangulate(&square, &[]);
        assert_eq!(triangles.len() / 3, 2);
        assert!((covered_area(&square, &triangles) - 10_000.0).abs() < 1e-9);
    }

    #[test]
    fn winding_order_does_not_matter() {
        // Rings arrive from a clipper and a simplifier, neither of which preserves
        // orientation, so both windings have to triangulate.
        let cw = [0, 0, 100, 0, 100, 100, 0, 100];
        let ccw = [0, 100, 100, 100, 100, 0, 0, 0];
        assert!((covered_area(&cw, &triangulate(&cw, &[])) - 10_000.0).abs() < 1e-9);
        assert!((covered_area(&ccw, &triangulate(&ccw, &[])) - 10_000.0).abs() < 1e-9);
    }

    #[test]
    fn a_concave_polygon_is_covered_exactly_once() {
        // An L: a naive fan from vertex 0 would cover area outside it, which is what
        // ear clipping exists to avoid.
        let shape = [0, 0, 100, 0, 100, 40, 40, 40, 40, 100, 0, 100];
        let triangles = triangulate(&shape, &[]);
        assert_eq!(triangles.len() / 3, 4);
        assert!((covered_area(&shape, &triangles) - (100.0 * 40.0 + 40.0 * 60.0)).abs() < 1e-9);
        assert_no_degenerate(&shape, &triangles);
    }

    #[test]
    fn a_hole_is_excluded_from_the_covered_area() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        assert!((covered_area(&coords, &triangles) - (10_000.0 - 400.0)).abs() < 1e-9);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn several_holes_are_all_excluded() {
        let mut coords = vec![0, 0, 300, 0, 300, 300, 0, 300];
        coords.extend_from_slice(&[20, 20, 60, 20, 60, 60, 20, 60]);
        coords.extend_from_slice(&[120, 120, 160, 120, 160, 160, 120, 160]);
        coords.extend_from_slice(&[220, 220, 260, 220, 260, 260, 220, 260]);
        let triangles = triangulate(&coords, &[4, 8, 12]);
        assert!((covered_area(&coords, &triangles) - (90_000.0 - 3.0 * 1600.0)).abs() < 1e-9);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn a_hole_wound_the_same_way_as_its_exterior_is_still_a_hole() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        assert!((covered_area(&coords, &triangles) - (10_000.0 - 400.0)).abs() < 1e-9);
    }

    #[test]
    fn degenerate_input_yields_no_triangles_rather_than_panicking() {
        assert!(triangulate(&[], &[]).is_empty());
        assert!(triangulate(&[0, 0], &[]).is_empty());
        assert!(triangulate(&[0, 0, 10, 10], &[]).is_empty());
        // Collinear, so it encloses nothing.
        assert!(triangulate(&[0, 0, 5, 0, 10, 0], &[]).is_empty());
        // A repeated vertex.
        assert!(triangulate(&[0, 0, 0, 0, 0, 0], &[]).is_empty());
    }

    #[test]
    fn a_ring_large_enough_to_use_the_z_order_index_still_triangulates_exactly() {
        // Above 80 vertices this switches to the hashed ear test — a different code
        // path, and the one every coastline tile takes. This exercises that path but does
        // not guard it: a convex ring has an ear at every vertex, so it comes out right
        // even if the hash returns nothing useful. See
        // `a_hashed_ring_rejects_ears_that_contain_a_hole_vertex`.
        let n = 400;
        let mut coords = vec![0i32; n * 2];
        for i in 0..n {
            let angle = 2.0 * std::f64::consts::PI * i as f64 / n as f64;
            coords[i * 2] = (2048.0 + 1500.0 * angle.cos()) as i32;
            coords[i * 2 + 1] = (2048.0 + 1500.0 * angle.sin()) as i32;
        }
        let triangles = triangulate(&coords, &[]);
        let expected = shoelace(&coords).abs() / 2.0;
        assert!(
            (covered_area(&coords, &triangles) - expected).abs() < 1.0,
            "covered {} vs {expected}",
            covered_area(&coords, &triangles),
        );
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn a_hashed_ring_rejects_ears_that_contain_a_hole_vertex() {
        // The guard on [`z_order`]'s scaling, which nothing else here provides. A convex
        // ring cannot be that guard, and neither can a monotone one: both have an ear
        // available at every step, so the hashed lookup can miss every candidate point and
        // the triangulation still comes out exact by luck. Noticing that the Z codes have
        // stopped tracking position takes a shape where the lookup *must* find an interior
        // point in order to reject a bad ear — so, holes, and enough vertices to cross the
        // threshold that turns the hashed path on at all.
        let side: i32 = 1000;
        let holes_per_axis: i32 = 6;
        let step = side / (holes_per_axis + 1);
        let hole = step / 3;
        let mut coords = vec![0, 0, side, 0, side, side, 0, side];
        let mut hole_starts = Vec::new();
        for gy in 1..=holes_per_axis {
            for gx in 1..=holes_per_axis {
                let (x, y) = (gx * step, gy * step);
                hole_starts.push(coords.len() / 2);
                coords.extend_from_slice(&[x, y, x + hole, y, x + hole, y + hole, x, y + hole]);
            }
        }
        assert!(coords.len() > 80 * 2, "must be past the hashed-path threshold");

        let triangles = triangulate(&coords, &hole_starts);
        let expected = (side * side - holes_per_axis * holes_per_axis * hole * hole) as f64;
        assert!(
            (covered_area(&coords, &triangles) - expected).abs() < 1e-6,
            "covered {} vs {expected}",
            covered_area(&coords, &triangles),
        );
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn a_staircase_relentlessly_concave_is_covered_exactly() {
        let coords = staircase(40, 25);
        let triangles = triangulate(&coords, &[]);
        let expected = shoelace(&coords).abs() / 2.0;
        assert!((covered_area(&coords, &triangles) - expected).abs() < 1e-6);
        assert_no_degenerate(&coords, &triangles);
    }

    #[test]
    fn triangulation_is_deterministic() {
        // Integer predicates mean no epsilon and no device-dependent rounding, so the
        // same ring must give identical output every time — which is what makes a
        // golden-image comparison in CI meaningful.
        let coords = staircase(20, 30);
        let first = triangulate(&coords, &[]);
        for _ in 0..3 {
            assert_eq!(first, triangulate(&coords, &[]));
        }
    }

    #[test]
    fn every_index_addresses_a_real_vertex() {
        let mut coords = vec![0, 0, 100, 0, 100, 100, 0, 100];
        coords.extend_from_slice(&[40, 40, 60, 40, 60, 60, 40, 60]);
        let triangles = triangulate(&coords, &[4]);
        let vertex_count = coords.len() / 2;
        assert!(!triangles.is_empty());
        assert_eq!(triangles.len() % 3, 0, "indices come in threes");
        for &i in &triangles {
            assert!((i as usize) < vertex_count, "index {i} outside the vertex list");
        }
    }

    #[test]
    fn the_midpoint_test_meets_a_slanted_edge_at_its_true_crossing() {
        // The ray from the diagonal's midpoint meets `(0,0)-(1000,100)` at x=500, far
        // from either endpoint's own x. An edge that shallow is what separates a
        // correctly scaled crossing test from one whose offset term is off by a factor
        // — on axis-aligned test shapes the offset is zero and any scaling passes.
        let pts = [(0, 0), (1000, 100), (800, 100), (-200, 500)];
        let mut ring = Ring::new(pts.len());
        let mut last = NIL;
        for (k, &(x, y)) in pts.iter().enumerate() {
            last = ring.insert(k * 2, x, y, last);
        }
        let v3 = last;
        let (v0, v2) = (ring.next(v3), ring.prev(v3));
        // The midpoint of v0-v2 is (400, 50), inside the quad: the ray to +x crosses
        // v0-v1 once.
        assert!(middle_inside(&ring, v0, v2));
    }
}
