//! Astronomy native library: batch celestial coordinate transforms (RaDec ->
//! AltAz) and batch stereographic sky projection, exposed to Kotlin via JNI.
//! Ports the two per-tick / per-frame hot paths from Kotlin; the math lives in
//! dependency-free modules so it is testable on the host.

mod coords;
mod projection;

pub use coords::batch_radec_to_altaz;
pub use projection::{batch_project, Projector};

#[cfg(test)]
mod tests {
    use super::*;
    use std::f64::consts::PI;

    // Scalar reference implementation of raDecToAltAz, matching the Kotlin
    // scalar `CoordinateTransforms.raDecToAltAz`.
    fn scalar_radec_to_altaz(ra: f64, dec: f64, lst: f64, lat: f64) -> (f64, f64) {
        let normalize_pi = |x: f64| {
            let mut v = (x + PI) % (2.0 * PI);
            if v < 0.0 {
                v += 2.0 * PI;
            }
            v - PI
        };
        let normalize_2pi = |x: f64| {
            let mut v = x % (2.0 * PI);
            if v < 0.0 {
                v += 2.0 * PI;
            }
            v
        };
        let ha = normalize_pi(lst - ra);
        let sin_alt = dec.sin() * lat.sin() + dec.cos() * lat.cos() * ha.cos();
        let alt = sin_alt.clamp(-1.0, 1.0).asin();
        let cos_alt = alt.cos();
        let az = if cos_alt.abs() < 1e-10 {
            0.0
        } else {
            let cos_az = (dec.sin() - sin_alt * lat.sin()) / (cos_alt * lat.cos());
            let sin_az = -dec.cos() * ha.sin() / cos_alt;
            normalize_2pi(sin_az.atan2(cos_az))
        };
        (az, alt)
    }

    #[test]
    fn batch_altaz_matches_scalar() {
        // A few (ra, dec) pairs, one lst/lat.
        let lst = 1.234;
        let lat = 0.71; // ~40.7 deg N
        let cases = [
            (0.0, 0.0),
            (1.0, 0.5),
            (3.5, -0.3),
            (5.9, 1.2),
            (2.0, -1.0),
        ];
        let radec: Vec<f64> = cases.iter().flat_map(|&(ra, dec)| [ra, dec]).collect();
        let out = batch_radec_to_altaz(&radec, lst, lat);
        assert_eq!(out.len(), cases.len() * 2);
        for (i, &(ra, dec)) in cases.iter().enumerate() {
            let (az, alt) = scalar_radec_to_altaz(ra, dec, lst, lat);
            assert!((out[2 * i] - az).abs() < 1e-12, "az mismatch at {i}");
            assert!((out[2 * i + 1] - alt).abs() < 1e-12, "alt mismatch at {i}");
        }
    }

    #[test]
    fn batch_altaz_hand_checked_zenith() {
        // A star on the meridian (ha=0) at the same dec as latitude sits at the
        // zenith: alt = 90 deg (PI/2), az irrelevant.
        let lat = 0.5;
        let lst = 1.0;
        let ra = lst; // ha = 0
        let dec = lat;
        let out = batch_radec_to_altaz(&[ra, dec], lst, lat);
        assert!((out[1] - PI / 2.0).abs() < 1e-9, "expected zenith, got alt={}", out[1]);
    }

    #[test]
    fn project_center_maps_to_screen_center() {
        let w = 1080.0;
        let h = 1920.0;
        let center_az = 1.3;
        let center_alt = 0.4;
        // A point exactly at the view center should map to the screen center.
        let out = batch_project(&[center_az, center_alt], center_az, center_alt, 60.0, w, h, 0.0);
        assert!((out[0] as f64 - w / 2.0).abs() < 1e-3, "x not centered: {}", out[0]);
        assert!((out[1] as f64 - h / 2.0).abs() < 1e-3, "y not centered: {}", out[1]);
    }

    #[test]
    fn project_outside_fov_returns_nan() {
        let w = 1080.0;
        let h = 1920.0;
        let center_az = 0.0;
        let center_alt = 0.0;
        // Antipodal-ish point (opposite azimuth, high negative alt) is well
        // outside the cull disk of a narrow FOV -> NaN.
        let out = batch_project(&[PI, -PI / 2.0], center_az, center_alt, 30.0, w, h, 0.0);
        assert!(out[0].is_nan() && out[1].is_nan(), "expected NaN, got ({}, {})", out[0], out[1]);
    }

    #[test]
    fn project_interleaving_and_length() {
        let w = 800.0;
        let h = 600.0;
        // First point in-view (center), second point culled.
        let altaz = [0.5, 0.2, PI, -PI / 2.0];
        let out = batch_project(&altaz, 0.5, 0.2, 45.0, w, h, 0.0);
        assert_eq!(out.len(), 4);
        assert!(!out[0].is_nan() && !out[1].is_nan());
        assert!(out[2].is_nan() && out[3].is_nan());
    }
}

#[cfg(not(test))]
mod jni_bindings {
    use super::*;
    use jni::objects::{JClass, JDoubleArray};
    use jni::sys::{jdouble, jdoubleArray, jfloatArray};
    use jni::JNIEnv;

    /// `batchRaDecToAltAz(radec, lstRad, latRad)`. `radec` interleaved
    /// `[ra0,dec0,...]` radians; returns interleaved `[az0,alt0,...]` radians,
    /// or null on error.
    #[no_mangle]
    pub extern "system" fn Java_com_vayunmathur_astronomy_domain_AstronomyNative_batchRaDecToAltAz<
        'l,
    >(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        radec: JDoubleArray<'l>,
        lst_rad: jdouble,
        lat_rad: jdouble,
    ) -> jdoubleArray {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let len = env.get_array_length(&radec).unwrap_or(0) as usize;
            let mut buf = vec![0f64; len];
            if env.get_double_array_region(&radec, 0, &mut buf).is_err() {
                return None;
            }
            Some(batch_radec_to_altaz(&buf, lst_rad, lat_rad))
        }))
        .unwrap_or(None);

        let result = match result {
            Some(r) => r,
            None => return std::ptr::null_mut(),
        };
        let out = match env.new_double_array(result.len() as i32) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_double_array_region(&out, 0, &result).is_err() {
            return std::ptr::null_mut();
        }
        out.into_raw()
    }

    /// `batchProject(altaz, centerAzRad, centerAltRad, fovDeg, screenW, screenH,
    /// rotationRad)`. `altaz` interleaved `[az0,alt0,...]`; returns interleaved
    /// `[x0,y0,...]` pixels (NaN,NaN for culled points), or null on error.
    #[no_mangle]
    #[allow(clippy::too_many_arguments)]
    pub extern "system" fn Java_com_vayunmathur_astronomy_domain_AstronomyNative_batchProject<'l>(
        env: JNIEnv<'l>,
        _class: JClass<'l>,
        altaz: JDoubleArray<'l>,
        center_az_rad: jdouble,
        center_alt_rad: jdouble,
        fov_deg: jdouble,
        screen_w: jdouble,
        screen_h: jdouble,
        rotation_rad: jdouble,
    ) -> jfloatArray {
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let len = env.get_array_length(&altaz).unwrap_or(0) as usize;
            let mut buf = vec![0f64; len];
            if env.get_double_array_region(&altaz, 0, &mut buf).is_err() {
                return None;
            }
            Some(batch_project(
                &buf,
                center_az_rad,
                center_alt_rad,
                fov_deg,
                screen_w,
                screen_h,
                rotation_rad,
            ))
        }))
        .unwrap_or(None);

        let result = match result {
            Some(r) => r,
            None => return std::ptr::null_mut(),
        };
        let out = match env.new_float_array(result.len() as i32) {
            Ok(a) => a,
            Err(_) => return std::ptr::null_mut(),
        };
        if env.set_float_array_region(&out, 0, &result).is_err() {
            return std::ptr::null_mut();
        }
        out.into_raw()
    }
}
