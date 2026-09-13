use crate::tess::stroke::MITER_LIMIT;

/// The normal at point `i`: unit at the ends, a clamped miter in between.
pub(crate) fn join_normal(points: &[i32], n: usize, i: usize) -> (f32, f32) {
    let before = if i > 0 { Some(direction(points, i - 1, i)) } else { None };
    let after = if i < n - 1 { Some(direction(points, i, i + 1)) } else { None };

    match (before, after) {
        (None, Some(a)) => (-a.1, a.0),
        (Some(b), None) => (-b.1, b.0),
        (Some(b), Some(a)) => {
            // The bisector, lengthened by 1/cos(theta/2) so both segments' kerbs meet
            // exactly on it.
            let (n1x, n1y) = (-b.1, b.0);
            let (n2x, n2y) = (-a.1, a.0);
            let mut mx = n1x + n2x;
            let mut my = n1y + n2y;
            let len = (mx * mx + my * my).sqrt();
            if len < 1e-6 {
                // A perfect reversal: the bisector is undefined, so fall back to the
                // incoming normal rather than emitting NaN.
                (n1x, n1y)
            } else {
                mx /= len;
                my /= len;
                let cos_half = mx * n1x + my * n1y;
                let miter = if cos_half > 1e-3 { 1.0 / cos_half } else { MITER_LIMIT };
                let clamped = if miter > MITER_LIMIT { MITER_LIMIT } else { miter };
                (mx * clamped, my * clamped)
            }
        }
        (None, None) => unreachable!("a part with fewer than two points was filtered out"),
    }
}

/// Unit direction from point `i` to point `j`.
fn direction(points: &[i32], i: usize, j: usize) -> (f32, f32) {
    let dx = (points[j * 2] - points[i * 2]) as f32;
    let dy = (points[j * 2 + 1] - points[i * 2 + 1]) as f32;
    let len = (dx * dx + dy * dy).sqrt();
    (dx / len, dy / len)
}

pub(crate) fn segment_length(points: &[i32], i: usize, j: usize) -> f32 {
    let dx = (points[j * 2] - points[i * 2]) as f32;
    let dy = (points[j * 2 + 1] - points[i * 2 + 1]) as f32;
    (dx * dx + dy * dy).sqrt()
}

/// Drop consecutive duplicate vertices.
///
/// Simplified tile geometry contains them, and a zero-length segment has no direction
/// — which would put a NaN in the normal and take the whole carriageway off screen,
/// not just that segment.
pub(crate) fn dedupe(coords: &[i32]) -> Vec<i32> {
    let mut out: Vec<i32> = Vec::with_capacity(coords.len());
    let mut i = 0;
    while i + 1 < coords.len() {
        let (x, y) = (coords[i], coords[i + 1]);
        let len = out.len();
        if len == 0 || out[len - 2] != x || out[len - 1] != y {
            out.push(x);
            out.push(y);
        }
        i += 2;
    }
    out
}
