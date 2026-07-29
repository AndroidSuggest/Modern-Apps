//! Port of `StylizeData.kt`: FindEdges (Sobel) and Emboss, `rayon` removed (was 6 crates)
//! Single-function usage – now stdlib via chunk loops.

use crate::pixel::*;

#[inline]
fn luminance_at(pixels: &[i32], w: i32, h: i32, x: i32, y: i32) -> i32 {
    let sx = clamp_i(x, 0, w - 1);
    let sy = clamp_i(y, 0, h - 1);
    let p = pixels[(sy * w + sx) as usize];
    (r(p) * 299 + g(p) * 587 + b(p) * 114) / 1000
}

pub fn stylize(pixels: &[i32], w: usize, h: usize, mode: i32) -> Vec<i32> {
    let mut output = vec![0i32; w * h];
    let wi = w as i32;
    let hi = h as i32;
    match mode {
        1 => {
            // FindEdges
            for y in 0..hi {
                for x in 0..wi {
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
                    output[(y * wi + x) as usize] = pack(av, v, v, v);
                }
            }
        }
        2 => {
            let kernel: [i32; 9] = [-2, -1, 0, -1, 1, 1, 0, 1, 2];
            for y in 0..hi {
                for x in 0..wi {
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
                    output[(y * wi + x) as usize] = pack(av, v, v, v);
                }
            }
        }
        _ => { output.copy_from_slice(pixels); }
    }
    output
}
