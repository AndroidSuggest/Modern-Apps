//! Frame-level statistics used by the gain-compensation stage.

use crate::imgbuf::Rgba;

/// Mean luma of an RGBA frame (opaque pixels only).
pub fn mean_luma(f: &Rgba) -> f32 {
    let mut sum = 0f64;
    let mut n = 0f64;
    for i in 0..f.w * f.h {
        if f.px[i * 4 + 3] == 0 {
            continue;
        }
        let r = f.px[i * 4] as f64;
        let g = f.px[i * 4 + 1] as f64;
        let b = f.px[i * 4 + 2] as f64;
        sum += 0.299 * r + 0.587 * g + 0.114 * b;
        n += 1.0;
    }
    if n > 0.0 { (sum / n) as f32 } else { 0.0 }
}
