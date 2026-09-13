//! Point-format conversions between the archive and the tessellators.

/// `[(i16, i16)]` to the `[(i32, i32)]` the fill tessellator takes.
pub(crate) fn widen(points: &[(i16, i16)]) -> Vec<(i32, i32)> {
    points.iter().map(|&(x, y)| (x as i32, y as i32)).collect()
}

/// `[(i16, i16)]` to the flat `[x, y, ...]` the stroke tessellator takes.
pub(crate) fn flatten(points: &[(i16, i16)]) -> Vec<i32> {
    let mut out = Vec::with_capacity(points.len() * 2);
    for &(x, y) in points {
        out.push(x as i32);
        out.push(y as i32);
    }
    out
}
