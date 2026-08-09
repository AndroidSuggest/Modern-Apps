//! Device-position estimation from a set of beacon fixes.
//!
//! Each beacon (a cell tower or WiFi AP whose coordinates the Apple `gs-loc`
//! proxy returned, then cached locally) is a self-reported position with an
//! accuracy radius in metres. The device is somewhere among them; we want a
//! single lat/lon plus an accuracy estimate.
//!
//! Treating each beacon as a noisy observation of the device position with
//! Gaussian error `sigma_i` (its accuracy radius), the maximum-likelihood /
//! least-squares estimate that minimises `sum_i (1/sigma_i^2) * |p - b_i|^2`
//! is the inverse-variance-weighted centroid `p* = (sum w_i b_i) / (sum w_i)`
//! with `w_i = 1/sigma_i^2`. So the "weighted centroid" and the "least-squares
//! fit" are the same closed-form solution here — no iteration required.
//!
//! Math is done on a local east/north tangent plane (equirectangular
//! projection about the first beacon) so the averaging is metric and does not
//! misbehave with raw degrees; beacons in one fix are always within a few km.

/// Metres per degree of latitude (WGS-84 mean). Longitude scales this by
/// `cos(lat)`.
const M_PER_DEG_LAT: f64 = 111_320.0;

/// Accuracy radii below this (metres) are clamped up, so a single beacon
/// reporting an implausibly tiny radius cannot completely dominate the fit or
/// blow up `1/sigma^2`.
const MIN_ACCURACY_M: f64 = 1.0;

/// Estimate the device position from beacon fixes.
///
/// `beacons` is interleaved `[lat0, lon0, acc0, lat1, lon1, acc1, ...]` where
/// lat/lon are degrees and `acc` is the beacon's accuracy radius in metres.
///
/// Returns `[lat, lon, accuracy_m]`. `accuracy_m` is the inverse-variance
/// standard error of the estimate, floored by the weighted RMS spread of the
/// beacons about the estimate so that mutually disagreeing beacons widen (never
/// falsely shrink) the reported accuracy.
///
/// Returns `None` if there are no beacons or the slice length is not a
/// multiple of three.
pub fn estimate_position(beacons: &[f64]) -> Option<[f64; 3]> {
    if beacons.is_empty() || beacons.len() % 3 != 0 {
        return None;
    }

    // Reference the tangent plane on the first *finite* beacon; a leading NaN
    // beacon must not poison lat0/lon0 (and thus every projected coordinate).
    let reference = beacons.chunks_exact(3).find(|b| {
        b[0].is_finite() && b[1].is_finite() && b[2].is_finite()
    })?;
    let lat0 = reference[0];
    let lon0 = reference[1];
    let cos_lat0 = lat0.to_radians().cos();
    // Guard the pole singularity; cos(lat) -> 0 makes the east scale explode.
    let east_scale = (M_PER_DEG_LAT * cos_lat0).max(1e-6);

    let mut sum_w = 0.0f64;
    let mut sum_we = 0.0f64;
    let mut sum_wn = 0.0f64;

    // Reduce to a local tangent plane in metres about (lat0, lon0).
    let to_en = |lat: f64, lon: f64| {
        let north = (lat - lat0) * M_PER_DEG_LAT;
        let east = (lon - lon0) * east_scale;
        (east, north)
    };

    for b in beacons.chunks_exact(3) {
        let (lat, lon, acc) = (b[0], b[1], b[2]);
        if !lat.is_finite() || !lon.is_finite() || !acc.is_finite() {
            continue;
        }
        let sigma = acc.max(MIN_ACCURACY_M);
        let w = 1.0 / (sigma * sigma);
        let (east, north) = to_en(lat, lon);
        sum_w += w;
        sum_we += w * east;
        sum_wn += w * north;
    }

    if sum_w <= 0.0 {
        return None;
    }

    let e_mean = sum_we / sum_w;
    let n_mean = sum_wn / sum_w;

    // Weighted variance of the beacons about the estimate (spread), and the
    // inverse-variance standard error of the mean. Report the larger.
    let mut sum_w_sq_dist = 0.0f64;
    for b in beacons.chunks_exact(3) {
        let (lat, lon, acc) = (b[0], b[1], b[2]);
        if !lat.is_finite() || !lon.is_finite() || !acc.is_finite() {
            continue;
        }
        let sigma = acc.max(MIN_ACCURACY_M);
        let w = 1.0 / (sigma * sigma);
        let (east, north) = to_en(lat, lon);
        let de = east - e_mean;
        let dn = north - n_mean;
        sum_w_sq_dist += w * (de * de + dn * dn);
    }
    let spread = (sum_w_sq_dist / sum_w).sqrt();
    let std_err = (1.0 / sum_w).sqrt();
    let accuracy = spread.max(std_err);

    let lat = lat0 + n_mean / M_PER_DEG_LAT;
    let lon = lon0 + e_mean / east_scale;

    Some([lat, lon, accuracy])
}

#[cfg(test)]
mod tests {
    use super::*;

    fn approx(a: f64, b: f64, tol: f64, what: &str) {
        assert!((a - b).abs() < tol, "{what}: {a} vs {b} (tol {tol})");
    }

    #[test]
    fn none_on_empty_or_ragged() {
        assert!(estimate_position(&[]).is_none());
        assert!(estimate_position(&[1.0, 2.0]).is_none());
        assert!(estimate_position(&[1.0, 2.0, 3.0, 4.0]).is_none());
    }

    #[test]
    fn single_beacon_returns_itself() {
        let out = estimate_position(&[37.4, -122.1, 25.0]).unwrap();
        approx(out[0], 37.4, 1e-9, "lat");
        approx(out[1], -122.1, 1e-9, "lon");
        // Spread is zero, so accuracy is the inverse-variance std error == sigma.
        approx(out[2], 25.0, 1e-6, "acc");
    }

    #[test]
    fn two_equal_beacons_midpoint() {
        let out = estimate_position(&[37.0, -122.0, 30.0, 37.002, -122.0, 30.0]).unwrap();
        approx(out[0], 37.001, 1e-6, "lat midpoint");
        approx(out[1], -122.0, 1e-6, "lon");
    }

    #[test]
    fn more_accurate_beacon_dominates() {
        // Beacon A far but very accurate, beacon B near but very inaccurate.
        // Result should sit much closer to A.
        let a_lat = 37.000;
        let b_lat = 37.010;
        let out = estimate_position(&[a_lat, -122.0, 5.0, b_lat, -122.0, 500.0]).unwrap();
        let d_a = (out[0] - a_lat).abs();
        let d_b = (out[0] - b_lat).abs();
        assert!(d_a < d_b, "estimate should be closer to the accurate beacon");
        // 1/25 vs 1/250000 weighting => essentially on A.
        approx(out[0], a_lat, 1e-4, "lat near A");
    }

    #[test]
    fn accuracy_shrinks_with_agreeing_beacons() {
        let one = estimate_position(&[37.0, -122.0, 40.0]).unwrap();
        let many = estimate_position(&[
            37.0, -122.0, 40.0, //
            37.0, -122.0, 40.0, //
            37.0, -122.0, 40.0, //
            37.0, -122.0, 40.0,
        ])
        .unwrap();
        // Four identical, agreeing observations halve the standard error (sqrt(4)).
        assert!(many[2] < one[2], "more agreeing beacons -> tighter accuracy");
        approx(many[2], one[2] / 2.0, 1e-3, "std error scales 1/sqrt(n)");
    }

    #[test]
    fn disagreeing_beacons_widen_accuracy() {
        // Two equally-weighted beacons ~1 km apart: the reported accuracy must
        // reflect the spread, not the (smaller) inverse-variance std error.
        let out = estimate_position(&[37.0, -122.0, 50.0, 37.009, -122.0, 50.0]).unwrap();
        assert!(out[2] > 200.0, "spread should dominate, got {}", out[2]);
    }

    #[test]
    fn skips_non_finite_beacons() {
        let out = estimate_position(&[
            f64::NAN, -122.0, 10.0, // dropped
            37.5, -122.5, 20.0, // kept
        ])
        .unwrap();
        approx(out[0], 37.5, 1e-9, "lat");
        approx(out[1], -122.5, 1e-9, "lon");
    }
}
