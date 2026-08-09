// Ported from GrapheneOS NetworkLocation
// (github.com/GrapheneOS/platform_packages_apps_NetworkLocation),
// MIT-licensed, Copyright (c) 2024-2025 GrapheneOS. See LICENSE-GrapheneOS.

//! Thin JNI marshalling layer bridging the Kotlin `NetworkLocationNative`
//! object to the host-testable [`estimate_position`](crate::estimate_position)
//! solver. This file contains no estimation math; it only converts between the
//! Kotlin `DoubleArray` contract and the solver's [`Measurement`] domain.

use crate::coordinate::Coordinate;
use crate::estimate_position;
use crate::measurement::Measurement;
use crate::position::Position;

use jni::objects::{JClass, JDoubleArray};
use jni::sys::jdoubleArray;
use jni::JNIEnv;

/// Metres per degree of latitude (WGS-84 mean). Longitude scales this by
/// `cos(lat)`.
const M_PER_DEG_LAT: f64 = 111_320.0;

/// Accuracy radii below this (metres) are clamped up, so a single beacon
/// reporting an implausibly tiny radius cannot dominate the fit.
const MIN_ACCURACY_M: f64 = 1.0;

/// Estimate the device position from interleaved beacon fixes.
///
/// `beacons` is `[lat0, lon0, acc0, lat1, lon1, acc1, ...]` (degrees, degrees,
/// metres). Returns `[lat, lon, accuracy_m]`, or `None` when there are no
/// usable beacons or the slice length is not a multiple of three.
///
/// Beacon fixes carry a self-reported position and an accuracy radius but no
/// ranging, so each is modelled as a [`Measurement`] whose beacon location is
/// the observed position (on a local east/north tangent plane in metres) with
/// per-axis `six_sigma_squared = sigma^2`, and `distance = 0` — i.e. the device
/// is a priori co-located with the beacon. The robust EM/RANSAC solver in
/// [`estimate_position`] then resolves the single position most consistent with
/// all beacons and rejects outliers.
fn estimate_lat_lon(beacons: &[f64]) -> Option<[f64; 3]> {
    if beacons.is_empty() || beacons.len() % 3 != 0 {
        return None;
    }

    // Reference the tangent plane on the first *finite* beacon; a leading NaN
    // beacon must not poison lat0/lon0 (and thus every projected coordinate).
    let reference = beacons
        .chunks_exact(3)
        .find(|b| b[0].is_finite() && b[1].is_finite() && b[2].is_finite())?;
    let lat0 = reference[0];
    let lon0 = reference[1];
    let cos_lat0 = lat0.to_radians().cos();
    // Guard the pole singularity; cos(lat) -> 0 makes the east scale explode.
    let east_scale = (M_PER_DEG_LAT * cos_lat0).max(1e-6);

    let mut measurements = Vec::with_capacity(beacons.len() / 3);
    for b in beacons.chunks_exact(3) {
        let (lat, lon, acc) = (b[0], b[1], b[2]);
        if !lat.is_finite() || !lon.is_finite() || !acc.is_finite() {
            continue;
        }
        let sigma = acc.max(MIN_ACCURACY_M);
        let six_sigma_squared = sigma * sigma;
        let east = (lon - lon0) * east_scale;
        let north = (lat - lat0) * M_PER_DEG_LAT;

        measurements.push(Measurement {
            position: Position {
                x: Coordinate::new_real(east, six_sigma_squared),
                y: Coordinate::new_real(north, six_sigma_squared),
                // Altitude is unknown for a network beacon fix: latent.
                z: Coordinate::new_fake(),
            },
            distance: 0.0,
            weight: 0.0,
        });
    }

    let estimated = estimate_position(&measurements)?;
    let position = estimated.position;

    let lat = lat0 + position.y.value / M_PER_DEG_LAT;
    let lon = lon0 + position.x.value / east_scale;
    let accuracy = (position.x.six_sigma_squared + position.y.six_sigma_squared).sqrt();

    Some([lat, lon, accuracy])
}

/// `estimatePosition(beacons)`. `beacons` is interleaved
/// `[lat0,lon0,acc0,lat1,lon1,acc1,...]` (degrees, degrees, metres); returns a
/// 3-element `[lat, lon, accuracyMeters]` array, or null when there are no
/// usable beacons / on error.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_networklocation_NetworkLocationNative_estimatePosition<
    'l,
>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    beacons: JDoubleArray<'l>,
) -> jdoubleArray {
    let len = env.get_array_length(&beacons).unwrap_or(0) as usize;
    let mut buf = vec![0f64; len];
    if env.get_double_array_region(&beacons, 0, &mut buf).is_err() {
        return std::ptr::null_mut();
    }

    let result = match estimate_lat_lon(&buf) {
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
