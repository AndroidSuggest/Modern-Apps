/// The convex hull of `points`, counter-clockwise, by Andrew's monotone chain.
pub(crate) fn convex_hull(points: &[(f32, f32)]) -> Vec<(f32, f32)> {
    if points.len() < 3 {
        return points.to_vec();
    }
    let mut sorted = points.to_vec();
    sorted.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.1.total_cmp(&b.1)));
    sorted.dedup();

    let cross = |o: (f32, f32), a: (f32, f32), b: (f32, f32)| {
        (a.0 - o.0) * (b.1 - o.1) - (a.1 - o.1) * (b.0 - o.0)
    };
    let mut hull: Vec<(f32, f32)> = Vec::with_capacity(sorted.len() + 1);
    for pass in 0..2 {
        let lower = hull.len();
        let iter: Box<dyn Iterator<Item = &(f32, f32)>> = if pass == 0 {
            Box::new(sorted.iter())
        } else {
            Box::new(sorted.iter().rev())
        };
        for &point in iter {
            while hull.len() >= lower + 2 {
                let (a, b) = match (hull.get(hull.len() - 2), hull.last()) {
                    (Some(&a), Some(&b)) => (a, b),
                    _ => break,
                };
                if cross(a, b, point) > 0.0 {
                    break;
                }
                let _ = hull.pop();
            }
            hull.push(point);
        }
        let _ = hull.pop();
    }
    hull
}
