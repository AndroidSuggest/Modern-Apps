use super::*;

/// Total unsigned area of the emitted triangles, in tile-normalised units.
fn covered_area(vertices: &[f32], indices: &[u32]) -> f64 {
    let mut total = 0.0;
    for t in indices.chunks_exact(3) {
        let p = |i: u32| -> (f64, f64) {
            let at = i as usize * FLOATS_PER_VERTEX;
            (vertices[at] as f64, vertices[at + 1] as f64)
        };
        let (ax, ay) = p(t[0]);
        let (bx, by) = p(t[1]);
        let (cx, cy) = p(t[2]);
        total += ((bx - ax) * (cy - ay) - (cx - ax) * (by - ay)).abs() / 2.0;
    }
    total
}

fn square(size: i32) -> Vec<(i32, i32)> {
    // Closed, as decode_polygons returns rings.
    vec![(0, 0), (size, 0), (size, size), (0, size), (0, 0)]
}

#[test]
fn empty_input_emits_nothing() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[], 4096, false, &mut v, &mut idx);
    assert!(v.is_empty() && idx.is_empty());
}

#[test]
fn a_second_polygon_indices_are_based_at_its_own_vertices() {
    let mut v = Vec::new();
    let mut idx = Vec::new();
    tessellate(&[square(100)], 100, false, &mut v, &mut idx);
    let first = idx.len();
    tessellate(&[square(100)], 100, false, &mut v, &mut idx);
    assert!(
        idx[first..].iter().all(|&i| i >= 4),
        "the second polygon rebases: {:?}",
        &idx[first..]
    );
    assert_eq!(v.len() / FLOATS_PER_VERTEX, 8);
}
/// **The gate, and the reason the repair pass is kept.** On a polygon that is already valid the
/// two paths have to agree exactly: trusting the producer is only a saving if it is not also a
/// change. Anything else means the gate is a behaviour switch rather than an optimisation.
#[test]
fn trusting_a_validated_polygon_matches_repairing_it() {
    // Valid: one exterior, one hole wound the other way, strictly inside it.
    let valid = vec![
        vec![(0, 0), (400, 0), (400, 400), (0, 400), (0, 0)],
        vec![(100, 100), (100, 200), (200, 200), (200, 100), (100, 100)],
    ];
    let mut trusted = (Vec::new(), Vec::new());
    let mut repaired = (Vec::new(), Vec::new());
    tessellate(&valid, 4096, true, &mut trusted.0, &mut trusted.1);
    tessellate(&valid, 4096, false, &mut repaired.0, &mut repaired.1);
    assert_eq!(
        trusted, repaired,
        "the gate changed the output on a valid polygon"
    );
    assert!(!trusted.1.is_empty(), "and it drew something");

    // And the hole is really cut out, rather than both paths agreeing on a solid square.
    let mut whole = (Vec::new(), Vec::new());
    tessellate(&[valid[0].clone()], 4096, true, &mut whole.0, &mut whole.1);
    assert!(
        covered_area(&trusted.0, &trusted.1) < covered_area(&whole.0, &whole.1),
        "the hole was not cut out",
    );
}
