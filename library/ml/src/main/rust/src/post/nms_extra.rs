use super::nms::Face;

/// Sort by score and drop any proposal overlapping a better one by more than `threshold`.
///
/// Greedy and quadratic in the number of proposals, which after a 0.5 score threshold is
/// a handful even on a group photo.
pub fn suppress(faces: &mut Vec<Face>, threshold: f32) {
    // Stable, unlike the reference's quicksort — see the module docs. `total_cmp` rather
    // than `partial_cmp`: a NaN score would otherwise make the ordering inconsistent and
    // the sort's behaviour unspecified.
    faces.sort_by(|a, b| b.score.total_cmp(&a.score));

    let mut kept: Vec<Face> = Vec::new();
    for face in faces.iter() {
        let overlaps = kept.iter().any(|other| {
            let intersection = face.intersection(other);
            let union = face.area() + other.area() - intersection;
            // Strictly greater, and a zero union cannot suppress: two degenerate boxes
            // would otherwise divide by zero and compare as NaN, which is `false` here
            // but only by accident.
            union > 0.0 && intersection / union > threshold
        });
        if !overlaps {
            kept.push(*face);
        }
    }
    *faces = kept;
}
