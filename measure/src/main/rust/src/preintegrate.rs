//! IMU preintegration between keyframes.
//!
//! Integrates gyroscope and accelerometer samples into a single relative motion
//! summary `(ΔR, Δv, Δp)` covering the interval between two keyframes, expressed in
//! the *body frame of the first keyframe*.
//!
//! Gravity is **not** removed here. The accelerometer measures specific force
//! `f = Rᵀ(a − g)`, and that is integrated as-is, because the device's attitude
//! relative to gravity is not yet known at this stage. The alignment step carries
//! explicit `g` terms that cancel the gravity contribution — see `align`, whose
//! equations assume exactly this convention. Removing gravity here would double-count
//! it there.

use vision_core::camera::rodrigues_to_mat;
use vision_core::linalg::{Matrix3, Vector3};

/// Standard gravity. The alignment step solves for the gravity *direction* but
/// constrains its magnitude to this.
pub const GRAVITY: f64 = 9.80665;

#[derive(Clone, Copy, Debug)]
pub struct ImuSample {
    /// Nanoseconds on the same clock as camera frame timestamps. Android reports
    /// `SENSOR_INFO_TIMESTAMP_SOURCE = REALTIME` on supported devices, which is what
    /// makes this comparison valid at all.
    pub t_ns: i64,
    /// Angular velocity, rad/s, body frame.
    pub gyro: Vector3<f64>,
    /// Specific force, m/s², body frame. Includes the reaction to gravity.
    pub accel: Vector3<f64>,
}

/// Relative motion accumulated across an interval.
///
/// Integrated from raw specific force, so the gravity reaction is still present in
/// [`delta_v`] and [`delta_p`]. Consumers must subtract it explicitly.
#[derive(Clone, Debug)]
pub struct Preintegrated {
    pub dt: f64,
    pub delta_r: Matrix3<f64>,
    pub delta_v: Vector3<f64>,
    pub delta_p: Vector3<f64>,
    pub sample_count: usize,
}

impl Preintegrated {
    pub fn identity() -> Self {
        Preintegrated {
            dt: 0.0,
            delta_r: Matrix3::identity(),
            delta_v: Vector3::zeros(),
            delta_p: Vector3::zeros(),
            sample_count: 0,
        }
    }
}

/// Preintegrate `samples` with the given biases removed.
///
/// Uses midpoint integration: at 400 Hz the difference from a higher-order scheme is
/// far below the accelerometer's own noise floor, and midpoint avoids the systematic
/// drift that plain forward Euler accumulates over a multi-second initialisation.
pub fn preintegrate(
    samples: &[ImuSample],
    gyro_bias: Vector3<f64>,
    accel_bias: Vector3<f64>,
) -> Preintegrated {
    let mut out = Preintegrated::identity();
    if samples.len() < 2 {
        return out;
    }

    let mut r = Matrix3::identity();
    let mut v = Vector3::zeros();
    let mut p = Vector3::zeros();

    for w in samples.windows(2) {
        let (s0, s1) = (w[0], w[1]);
        let dt = (s1.t_ns - s0.t_ns) as f64 * 1e-9;
        // Reject non-monotonic or absurd gaps; a dropped batch should not corrupt
        // the whole interval.
        if !(dt > 0.0 && dt < 0.1) {
            continue;
        }

        let g0 = s0.gyro - gyro_bias;
        let g1 = s1.gyro - gyro_bias;
        let a0 = s0.accel - accel_bias;
        let a1 = s1.accel - accel_bias;

        // Midpoint angular increment.
        let w_mid = (g0 + g1) * 0.5;
        let dr = rodrigues_to_mat(w_mid * dt);
        let r_next = r * dr;

        // Midpoint specific force, rotated into the reference (first) body frame.
        let a_ref = (&r * a0 + &r_next * a1) * 0.5;

        p += v * dt + a_ref * (0.5 * dt * dt);
        v += a_ref * dt;
        r = r_next;

        out.dt += dt;
        out.sample_count += 1;
    }

    out.delta_r = r;
    out.delta_v = v;
    out.delta_p = p;
    out
}

/// Estimate the gyroscope bias from an interval the device was stationary for.
///
/// A stationary gyro reads pure bias, so the mean is the estimate. Callers must
/// confirm stillness first — [`is_stationary`] exists for that.
pub fn estimate_gyro_bias(samples: &[ImuSample]) -> Vector3<f64> {
    if samples.is_empty() {
        return Vector3::zeros();
    }
    let mut sum = Vector3::zeros();
    for s in samples {
        sum += s.gyro;
    }
    sum / samples.len() as f64
}

/// True when the samples look like a device at rest: little rotation, and specific
/// force close to 1 g in magnitude.
pub fn is_stationary(samples: &[ImuSample], gyro_tol: f64, accel_tol: f64) -> bool {
    if samples.len() < 2 {
        return false;
    }
    for s in samples {
        if s.gyro.norm() > gyro_tol {
            return false;
        }
        if (s.accel.norm() - GRAVITY).abs() > accel_tol {
            return false;
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn constant_motion(n: usize, dt: f64, accel: Vector3<f64>, gyro: Vector3<f64>) -> Vec<ImuSample> {
        (0..n)
            .map(|i| ImuSample {
                t_ns: (i as f64 * dt * 1e9) as i64,
                gyro,
                accel,
            })
            .collect()
    }

    #[test]
    fn constant_acceleration_integrates_to_the_kinematic_answer() {
        // 1 s of 2 m/s² with no rotation: v = 2, p = 1.
        let dt = 1.0 / 400.0;
        let s = constant_motion(401, dt, Vector3::new(2.0, 0.0, 0.0), Vector3::zeros());
        let pre = preintegrate(&s, Vector3::zeros(), Vector3::zeros());

        assert!((pre.dt - 1.0).abs() < 1e-6, "dt = {}", pre.dt);
        assert!((pre.delta_v[0] - 2.0).abs() < 1e-6, "v = {}", pre.delta_v[0]);
        assert!((pre.delta_p[0] - 1.0).abs() < 1e-3, "p = {}", pre.delta_p[0]);
    }

    #[test]
    fn stationary_device_accumulates_no_motion() {
        let dt = 1.0 / 400.0;
        let s = constant_motion(401, dt, Vector3::zeros(), Vector3::zeros());
        let pre = preintegrate(&s, Vector3::zeros(), Vector3::zeros());
        assert!(pre.delta_v.norm() < 1e-9);
        assert!(pre.delta_p.norm() < 1e-9);
    }

    #[test]
    fn constant_rotation_integrates_to_the_expected_angle() {
        // 1 rad/s about Z for 1 s => 1 rad.
        let dt = 1.0 / 400.0;
        let s = constant_motion(401, dt, Vector3::zeros(), Vector3::new(0.0, 0.0, 1.0));
        let pre = preintegrate(&s, Vector3::zeros(), Vector3::zeros());
        let expected = rodrigues_to_mat(Vector3::new(0.0, 0.0, 1.0));
        for i in 0..3 {
            for j in 0..3 {
                assert!(
                    (pre.delta_r[(i, j)] - expected[(i, j)]).abs() < 1e-4,
                    "R[{i}][{j}] drifted"
                );
            }
        }
    }

    #[test]
    fn bias_is_removed_before_integration() {
        let dt = 1.0 / 400.0;
        let bias = Vector3::new(0.5, -0.2, 0.1);
        let s = constant_motion(401, dt, bias, Vector3::zeros());
        let pre = preintegrate(&s, Vector3::zeros(), bias);
        assert!(pre.delta_v.norm() < 1e-9, "bias should cancel exactly");
    }

    #[test]
    fn gyro_bias_estimate_recovers_a_constant_offset() {
        let dt = 1.0 / 400.0;
        let bias = Vector3::new(0.01, -0.02, 0.003);
        let s = constant_motion(200, dt, Vector3::new(0.0, 0.0, GRAVITY), bias);
        let est = estimate_gyro_bias(&s);
        assert!((est - bias).norm() < 1e-9);
    }

    #[test]
    fn stationary_detection_rejects_motion() {
        let dt = 1.0 / 400.0;
        let still = constant_motion(50, dt, Vector3::new(0.0, 0.0, GRAVITY), Vector3::zeros());
        assert!(is_stationary(&still, 0.05, 0.3));

        let moving = constant_motion(50, dt, Vector3::new(3.0, 0.0, GRAVITY), Vector3::zeros());
        assert!(!is_stationary(&moving, 0.05, 0.3));
    }

    #[test]
    fn irregular_timestamps_do_not_corrupt_the_interval() {
        // A dropped batch shows up as a large gap; it must be skipped, not integrated.
        let mut s = constant_motion(100, 1.0 / 400.0, Vector3::new(1.0, 0.0, 0.0), Vector3::zeros());
        s.push(ImuSample {
            t_ns: s.last().unwrap().t_ns + 5_000_000_000,
            gyro: Vector3::zeros(),
            accel: Vector3::new(1.0, 0.0, 0.0),
        });
        let pre = preintegrate(&s, Vector3::zeros(), Vector3::zeros());
        assert!(pre.dt < 0.5, "the 5 s gap should have been rejected, dt = {}", pre.dt);
    }
}
