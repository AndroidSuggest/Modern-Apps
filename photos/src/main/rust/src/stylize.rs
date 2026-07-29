//! Port of `StylizeData.kt`: FindEdges (Sobel on luminance) and Emboss.
//! mode: 0 = None, 1 = FindEdges, 2 = Emboss.

use crate::pixel::*;
use rayon::prelude::*;

/// Port of `luminanceAt` (integer BT.601-ish luma with edge clamping).
#[inline]
fn luminance_at(pixels: &[i32], w: i32, h: i32, x: i32, y: i32) -> i32 {
    let sx = clamp_i(x, 0, w - 1);
    let sy = clamp_i(y, 0, h - 1);
    let p = pixels[(sy * w + sx) as usize];
    let r = r(p);
    let g = g(p);
    let b = b(p);
    (r * 299 + g * 587 + b * 114) / 1000
}

pub fn stylize(pixels: &[i32], w: usize, h: usize, mode: i32) -> Vec<i32> {
    let mut output = vec![0i32; w * h];
    let wi = w as i32;
    let hi = h as i32;
    match mode {
        1 => {
            // FindEdges
            output.par_chunks_mut(w).enumerate().for_each(|(yy, row)| {
                let y = yy as i32;
                for xx in 0..w {
                    let x = xx as i32;
                    let tl = luminance_at(pixels, wi, hi, x - 1, y - 1);
                    let tc = luminance_at(pixels, wi, hi, x, y - 1);
                    let tr = luminance_at(pixels, wi, hi, x + 1, y - 1);
                    let ml = luminance_at(pixels, wi, hi, x - 1, y);
                    let mr = luminance_at(pixels, wi, hi, x + 1, y);
                    let bl = luminance_at(pixels, wi, hi, x - 1, y + 1);
                    let bc = luminance_at(pixels, wi, hi, x, y + 1);
                    let br = luminance_at(pixels, wi, hi, x + 1, y + 1);

                    let gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl);
                    let gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr);
                    let mag = clamp_i(((gx * gx + gy * gy) as f64).sqrt() as i32, 0, 255);
                    let v = 255 - mag;

                    let av = a(pixels[(y * wi + x) as usize]);
                    row[xx] = pack(av, v, v, v);
                }
            });
        }
        2 => {
            // Emboss
            let kernel: [i32; 9] = [-2, -1, 0, -1, 1, 1, 0, 1, 2];
            output.par_chunks_mut(w).enumerate().for_each(|(yy, row)| {
                let y = yy as i32;
                for xx in 0..w {
                    let x = xx as i32;
                    let mut sum = 0i32;
                    let mut ki = 0usize;
                    for dy in -1..=1 {
                        for dx in -1..=1 {
                            sum += luminance_at(pixels, wi, hi, x + dx, y + dy) * kernel[ki];
                            ki += 1;
                        }
                    }
                    let v = clamp_i(sum + 128, 0, 255);
                    let av = a(pixels[(y * wi + x) as usize]);
                    row[xx] = pack(av, v, v, v);
                }
            });
        }
        _ => {
            // None (0) or unknown: identity copy.
            output.copy_from_slice(pixels);
        }
    }
    output
}
