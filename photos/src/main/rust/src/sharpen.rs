//! Port of `SharpenData.kt`: unsharp mask (Gaussian blur + per-channel
//! sharpening). The blur is byte-identical to `BlurData.gaussianBlur`, so it is
//! reused from [`crate::blur::gaussian_blur`].

use crate::blur::gaussian_blur;
use crate::pixel::*;

/// Port of `sharpenChannel`.
#[inline]
fn sharpen_channel(orig: i32, blurred: i32, strength: f32, threshold: i32) -> i32 {
    let diff = orig - blurred;
    if diff.abs() > threshold {
        clamp_i((orig as f32 + strength * diff as f32) as i32, 0, 255)
    } else {
        orig
    }
}

/// Kotlin `Float.roundToInt()` — rounds half toward positive infinity
/// (`floor(x + 0.5)`), unlike Rust's `f32::round` which rounds half away from 0.
#[inline]
fn round_to_int(x: f32) -> i32 {
    (x + 0.5f32).floor() as i32
}

/// Port of `UnsharpMask.applyToBitmap`.
pub fn unsharp(pixels: &[i32], w: usize, h: usize, amount: f32, radius: f32, threshold: i32) -> Vec<i32> {
    let strength = amount / 100f32;
    let int_radius = round_to_int(radius).max(1);
    let blurred = gaussian_blur(pixels, w, h, int_radius);
    let mut output = vec![0i32; w * h];
    for i in 0..pixels.len() {
        let p = pixels[i];
        let bp = blurred[i];
        let av = a(p);
        let orig_r = r(p);
        let orig_g = g(p);
        let orig_b = b(p);
        let blur_r = r(bp);
        let blur_g = g(bp);
        let blur_b = b(bp);
        let rr = sharpen_channel(orig_r, blur_r, strength, threshold);
        let gg = sharpen_channel(orig_g, blur_g, strength, threshold);
        let bb = sharpen_channel(orig_b, blur_b, strength, threshold);
        output[i] = pack(av, rr, gg, bb);
    }
    output
}
