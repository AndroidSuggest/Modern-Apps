//! Celestial coordinate transforms. Pure math ported 1:1 from
//! `CoordinateTransforms.kt` (`batchRaDecToAltAz`) and the `normalizePi` /
//! `normalize2Pi` helpers from `TimeEngine.kt`. Kept dependency-free so it runs
//! under host `cargo test`.

use std::f64::consts::PI;

/// Kotlin `Double.normalizePi()`: wrap to (-PI, PI].
#[inline]
fn normalize_pi(x: f64) -> f64 {
    let mut v = (x + PI) % (2.0 * PI);
    if v < 0.0 {
        v += 2.0 * PI;
    }
    v - PI
}

/// Kotlin `Double.normalize2Pi()`: wrap to [0, 2PI).
#[inline]
fn normalize_2pi(x: f64) -> f64 {
    let mut v = x % (2.0 * PI);
    if v < 0.0 {
        v += 2.0 * PI;
    }
    v
}

/// Batch RaDec -> AltAz. `radec` is interleaved `[ra0, dec0, ra1, dec1, ...]`
/// in radians; returns interleaved `[az0, alt0, az1, alt1, ...]` in radians.
/// Mirrors `CoordinateTransforms.batchRaDecToAltAz` exactly, including hoisting
/// sin/cos of the latitude out of the loop and the `abs(cosAlt) < 1e-10` guard.
pub fn batch_radec_to_altaz(radec: &[f64], lst_rad: f64, lat_rad: f64) -> Vec<f64> {
    let sin_lat = lat_rad.sin();
    let cos_lat = lat_rad.cos();
    let n = radec.len() / 2;
    let mut out = Vec::with_capacity(n * 2);
    for i in 0..n {
        let ra = radec[2 * i];
        let dec = radec[2 * i + 1];
        let ha = normalize_pi(lst_rad - ra);
        let sin_dec = dec.sin();
        let cos_dec = dec.cos();
        let sin_alt = sin_dec * sin_lat + cos_dec * cos_lat * ha.cos();
        let alt = sin_alt.clamp(-1.0, 1.0).asin();
        let cos_alt = alt.cos();
        let az = if cos_alt.abs() < 1e-10 {
            0.0
        } else {
            let cos_az = (sin_dec - sin_alt * sin_lat) / (cos_alt * cos_lat);
            let sin_az = -cos_dec * ha.sin() / cos_alt;
            normalize_2pi(sin_az.atan2(cos_az))
        };
        out.push(az);
        out.push(alt);
    }
    out
}
