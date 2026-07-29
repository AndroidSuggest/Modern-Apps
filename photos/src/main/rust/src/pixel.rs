//! ARGB (0xAARRGGBB) pixel helpers. Pixels are stored as `i32` exactly as
//! Android `Bitmap.getPixels` returns them; channel math is done via `u32`
//! casts so shifts never overflow. Extraction masks with `& 0xFF`, which makes
//! Kotlin's `ushr` and `shr` behave identically, so both source conventions map
//! to the same code here.

#[inline]
pub fn a(p: i32) -> i32 {
    (((p as u32) >> 24) & 0xFF) as i32
}
#[inline]
pub fn r(p: i32) -> i32 {
    (((p as u32) >> 16) & 0xFF) as i32
}
#[inline]
pub fn g(p: i32) -> i32 {
    (((p as u32) >> 8) & 0xFF) as i32
}
#[inline]
pub fn b(p: i32) -> i32 {
    ((p as u32) & 0xFF) as i32
}

/// Pack ARGB channels (each already 0..=255) into a single ARGB int.
#[inline]
pub fn pack(a: i32, r: i32, g: i32, b: i32) -> i32 {
    (((a as u32) << 24) | ((r as u32) << 16) | ((g as u32) << 8) | (b as u32)) as i32
}

/// Kotlin `Int.coerceIn(min, max)`.
#[inline]
pub fn clamp_i(v: i32, lo: i32, hi: i32) -> i32 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}

/// Kotlin `Float.coerceIn(min, max)`.
#[inline]
pub fn clamp_f(v: f32, lo: f32, hi: f32) -> f32 {
    if v < lo {
        lo
    } else if v > hi {
        hi
    } else {
        v
    }
}
