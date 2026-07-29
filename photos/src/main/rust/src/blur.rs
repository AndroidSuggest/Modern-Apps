//! Port of `BlurData.kt`: separable Gaussian blur, motion blur, radial/spin
//! blur, the masked lens-blur composite ([`blur_params`]) and the destructive
//! full-image filter blurs ([`filter_blur`]).

use crate::pixel::*;
use rayon::prelude::*;

/// Matches `generateGaussianKernel` / `unsharpGaussianKernel` (identical math).
pub fn gaussian_kernel(radius: i32) -> Vec<f32> {
    let size = (radius * 2 + 1) as usize;
    let mut kernel = vec![0f32; size];
    let sigma = radius as f32 / 3f32;
    let mut sum = 0f32;
    for i in 0..size {
        let x = (i as i32 - radius) as f32;
        kernel[i] = (-(x * x) / (2f32 * sigma * sigma)).exp();
        sum += kernel[i];
    }
    for i in 0..size {
        kernel[i] /= sum;
    }
    kernel
}

/// Port of `gaussianBlur` (also reused by the unsharp mask, whose blur is
/// byte-identical). Two separable passes (horizontal then vertical).
pub fn gaussian_blur(pixels: &[i32], w: usize, h: usize, radius: i32) -> Vec<i32> {
    if radius <= 0 {
        return pixels.to_vec();
    }
    let kernel = gaussian_kernel(radius);
    let r = radius as isize;
    let mut temp = vec![0i32; w * h];
    // Horizontal pass.
    temp.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for x in 0..w {
            let (mut rr, mut gg, mut bb, mut aa) = (0f32, 0f32, 0f32, 0f32);
            for k in -r..=r {
                let sx = clamp_i(x as i32 + k as i32, 0, w as i32 - 1) as usize;
                let px = pixels[y * w + sx];
                let weight = kernel[(k + r) as usize];
                aa += a(px) as f32 * weight;
                rr += r_ch(px) as f32 * weight;
                gg += g(px) as f32 * weight;
                bb += b(px) as f32 * weight;
            }
            row[x] = pack(
                clamp_i(aa as i32, 0, 255),
                clamp_i(rr as i32, 0, 255),
                clamp_i(gg as i32, 0, 255),
                clamp_i(bb as i32, 0, 255),
            );
        }
    });
    let mut output = vec![0i32; w * h];
    // Vertical pass.
    output.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for x in 0..w {
            let (mut rr, mut gg, mut bb, mut aa) = (0f32, 0f32, 0f32, 0f32);
            for k in -r..=r {
                let sy = clamp_i(y as i32 + k as i32, 0, h as i32 - 1) as usize;
                let px = temp[sy * w + x];
                let weight = kernel[(k + r) as usize];
                aa += a(px) as f32 * weight;
                rr += r_ch(px) as f32 * weight;
                gg += g(px) as f32 * weight;
                bb += b(px) as f32 * weight;
            }
            row[x] = pack(
                clamp_i(aa as i32, 0, 255),
                clamp_i(rr as i32, 0, 255),
                clamp_i(gg as i32, 0, 255),
                clamp_i(bb as i32, 0, 255),
            );
        }
    });
    output
}

// `r` is taken by the radius param name above; alias the red channel accessor.
#[inline]
fn r_ch(p: i32) -> i32 {
    r(p)
}

/// Port of `motionBlur`.
fn motion_blur(src: &[i32], w: usize, h: usize, length: i32, angle: f32) -> Vec<i32> {
    let mut out = vec![0i32; w * h];
    let rad = (angle as f64).to_radians();
    let dx = rad.cos() as f32;
    let dy = rad.sin() as f32;
    let half = length / 2;
    out.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for x in 0..w {
            let (mut aa, mut rr, mut gg, mut bb, mut n) = (0f32, 0f32, 0f32, 0f32, 0f32);
            for k in -half..=half {
                let sx = clamp_i((x as f32 + dx * k as f32) as i32, 0, w as i32 - 1) as usize;
                let sy = clamp_i((y as f32 + dy * k as f32) as i32, 0, h as i32 - 1) as usize;
                let p = src[sy * w + sx];
                aa += a(p) as f32;
                rr += r(p) as f32;
                gg += g(p) as f32;
                bb += b(p) as f32;
                n += 1f32;
            }
            row[x] = pack(
                (aa / n) as i32,
                (rr / n) as i32,
                (gg / n) as i32,
                (bb / n) as i32,
            );
        }
    });
    out
}

/// Port of `radialBlur` (zoom when `spin == false`, rotational when `true`).
fn radial_blur(
    src: &[i32],
    w: usize,
    h: usize,
    amount: f32,
    center_x: f32,
    center_y: f32,
    spin: bool,
) -> Vec<i32> {
    let mut out = vec![0i32; w * h];
    let cx = center_x * w as f32;
    let cy = center_y * h as f32;
    let samples = clamp_i((amount * 0.2f32) as i32, 3, 30);
    let strength = amount / 100f32;
    out.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for x in 0..w {
            let (mut aa, mut rr, mut gg, mut bb) = (0f32, 0f32, 0f32, 0f32);
            let odx = x as f32 - cx;
            let ody = y as f32 - cy;
            for s in 0..samples {
                let t = s as f32 / samples as f32;
                let sx: f32;
                let sy: f32;
                if spin {
                    let ang = -strength * 0.3f32 * t;
                    let cos_a = (ang as f64).cos() as f32;
                    let sin_a = (ang as f64).sin() as f32;
                    sx = cx + (odx * cos_a - ody * sin_a);
                    sy = cy + (odx * sin_a + ody * cos_a);
                } else {
                    let scale = 1f32 - strength * t;
                    sx = cx + odx * scale;
                    sy = cy + ody * scale;
                }
                let ix = clamp_i(sx as i32, 0, w as i32 - 1) as usize;
                let iy = clamp_i(sy as i32, 0, h as i32 - 1) as usize;
                let p = src[iy * w + ix];
                aa += a(p) as f32;
                rr += r(p) as f32;
                gg += g(p) as f32;
                bb += b(p) as f32;
            }
            let n = samples as f32;
            row[x] = pack(
                (aa / n) as i32,
                (rr / n) as i32,
                (gg / n) as i32,
                (bb / n) as i32,
            );
        }
    });
    out
}

// BlurMode: 0 = Radial, 1 = Linear, 2 = Lens.
fn generate_mask(
    mode: i32,
    center_x: f32,
    center_y: f32,
    radius: f32,
    feather: f32,
    angle: f32,
    w: usize,
    h: usize,
) -> Vec<f32> {
    let mut mask = vec![0f32; w * h];
    let max_wh = w.max(h) as f32;
    match mode {
        0 => {
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let r = radius * max_wh;
            let feather_dist = feather * max_wh;
            for y in 0..h {
                for x in 0..w {
                    let dx = x as f32 - cx;
                    let dy = y as f32 - cy;
                    let dist = ((dx * dx + dy * dy) as f64).sqrt() as f32;
                    mask[y * w + x] = if dist < r {
                        0f32
                    } else {
                        clamp_f((dist - r) / feather_dist.max(1f32), 0f32, 1f32)
                    };
                }
            }
        }
        1 => {
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let r = radius * max_wh;
            let feather_dist = feather * max_wh;
            let rad = (angle as f64).to_radians();
            let nx = -(rad.sin() as f32);
            let ny = rad.cos() as f32;
            for y in 0..h {
                for x in 0..w {
                    let dist = ((x as f32 - cx) * nx + (y as f32 - cy) * ny).abs();
                    mask[y * w + x] = if dist < r {
                        0f32
                    } else {
                        clamp_f((dist - r) / feather_dist.max(1f32), 0f32, 1f32)
                    };
                }
            }
        }
        _ => {
            // Lens (2)
            let cx = center_x * w as f32;
            let cy = center_y * h as f32;
            let r = radius * max_wh;
            for y in 0..h {
                for x in 0..w {
                    let dx = x as f32 - cx;
                    let dy = y as f32 - cy;
                    let dist = ((dx * dx + dy * dy) as f64).sqrt() as f32;
                    mask[y * w + x] = if dist > r { 1f32 } else { 0f32 };
                }
            }
        }
    }
    mask
}

/// Port of `BlurParams.applyBlurToBitmap` (masked lens/radial/linear blur).
pub fn blur_params(
    pixels: &[i32],
    w: usize,
    h: usize,
    mode: i32,
    center_x: f32,
    center_y: f32,
    radius: f32,
    intensity: f32,
    feather: f32,
    angle: f32,
) -> Vec<i32> {
    let blur_radius = clamp_i((intensity * 0.5f32) as i32, 1, 25);
    let blurred = gaussian_blur(pixels, w, h, blur_radius);
    let mask = generate_mask(mode, center_x, center_y, radius, feather, angle, w, h);
    let mut output = vec![0i32; w * h];
    for i in 0..pixels.len() {
        let m = mask[i];
        let orig_a = a(pixels[i]);
        let orig_r = r(pixels[i]);
        let orig_g = g(pixels[i]);
        let orig_b = b(pixels[i]);
        let blur_a = a(blurred[i]);
        let blur_r = r(blurred[i]);
        let blur_g = g(blurred[i]);
        let blur_b = b(blurred[i]);
        let aa = clamp_i((orig_a as f32 + (blur_a - orig_a) as f32 * m) as i32, 0, 255);
        let rr = clamp_i((orig_r as f32 + (blur_r - orig_r) as f32 * m) as i32, 0, 255);
        let gg = clamp_i((orig_g as f32 + (blur_g - orig_g) as f32 * m) as i32, 0, 255);
        let bb = clamp_i((orig_b as f32 + (blur_b - orig_b) as f32 * m) as i32, 0, 255);
        output[i] = pack(aa, rr, gg, bb);
    }
    output
}

/// Port of `FilterBlur.applyToBitmap`.
/// FilterBlurMode: 0 = Gaussian, 1 = Motion, 2 = Radial, 3 = Spin.
pub fn filter_blur(
    pixels: &[i32],
    w: usize,
    h: usize,
    mode: i32,
    amount: f32,
    angle: f32,
    center_x: f32,
    center_y: f32,
) -> Vec<i32> {
    match mode {
        0 => gaussian_blur(pixels, w, h, clamp_i((amount * 0.5f32) as i32, 1, 60)),
        1 => motion_blur(pixels, w, h, clamp_i(amount as i32, 1, 100), angle),
        2 => radial_blur(pixels, w, h, amount, center_x, center_y, false),
        _ => radial_blur(pixels, w, h, amount, center_x, center_y, true),
    }
}
