//! Marker quad emission: one billboarded sprite's clip-space corners.
//!
//! Split from `record_extra3.rs` (file-length limit). Pure function over the
//! resolved quad centre/axes — shared by the flat billboard path and the globe
//! anchor path (axis-aligned clip axes with w == 1, same add).
/// Emit one marker's four corners into the shared sprite vertex/index buffers.
///
/// `corners` are (local_u, local_v, tex_u, tex_v); (cx, cy) the clip-space
/// centre; (xu, yu)/(xv, yv) the local Dp axes in clip space; `w` the
/// perspective divisor (1.0 on the globe path).
pub(super) fn emit_marker_corners(
    vertices: &mut Vec<f32>,
    indices: &mut Vec<u32>,
    base: u32,
    corners: [(f64, f64, f32, f32); 4],
    cx: f64,
    cy: f64,
    xu: f64,
    yu: f64,
    xv: f64,
    yv: f64,
    w: f64,
) {
    for (lu, lv, tu, tv) in corners {
        // Flat path: perspective divide by the marker's own w. Globe path:
        // axis-aligned clip axes with w == 1, so this is the same add.
        let ox = (lu * xu + lv * xv) / w;
        let oy = (lu * yu + lv * yv) / w;
        vertices.push((cx + ox) as f32);
        vertices.push((cy + oy) as f32);
        vertices.push(tu);
        vertices.push(tv);
    }
    indices.extend_from_slice(&[base, base + 1, base + 2, base, base + 2, base + 3]);
}
