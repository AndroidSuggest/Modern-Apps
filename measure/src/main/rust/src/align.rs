//! Visual-inertial alignment: recovering **metric scale** and gravity.
//!
//! This is the crux of the whole app. A monocular camera reconstructs geometry only
//! up to an unknown scale factor `s`: a small room close up and a large room far away
//! produce identical images. Measuring in metres therefore requires an external
//! reference for length, and the accelerometer is it — gravity has a known magnitude
//! and the double-integrated specific force has real units.
//!
//! Given keyframe rotations (metric, orientation is directly observable) and camera
//! positions known only up to `s`, the IMU preintegration between consecutive
//! keyframes gives, for each interval `i`:
//!
//! ```text
//! s·(p_{i+1} − p_i) = R_i·Δp_i + v_i·Δt_i + ½·g·Δt_i²
//! s·(velocity change)  ⇒  R_i·Δv_i = v_{i+1} − v_i + g·Δt_i
//! ```
//!
//! Both are **linear** in the unknowns `(s, g, v_0..v_n)`, so the whole thing is one
//! least-squares solve rather than a nonlinear optimisation. This mirrors the linear
//! initialisation stage used by VINS-Mono.
//!
//! The solve is only well-conditioned when the device genuinely translated. Under pure
//! rotation every `p_i` is identical, the `s` column becomes zero, and scale is
//! unrecoverable — which is exactly why the UI has to coach a sideways motion.

use crate::preintegrate::{Preintegrated, GRAVITY};
use vision_core::linalg::{DMatrix, DVector, Matrix3, Vector3};

/// One keyframe's visual pose. `position` carries the arbitrary visual scale;
/// `rotation` is body-to-world and is already metric.
#[derive(Clone, Debug)]
pub struct KeyframePose {
    pub rotation: Matrix3<f64>,
    pub position: Vector3<f64>,
}

#[derive(Clone, Debug)]
pub struct AlignmentResult {
    /// Multiply any visual length by this to get metres.
    pub scale: f64,
    /// Gravity in the visual world frame, magnitude ~9.81.
    pub gravity: Vector3<f64>,
    pub velocities: Vec<Vector3<f64>>,
    /// 0..1 heuristic; below ~0.3 the estimate should not be shown to the user.
    pub confidence: f64,
}

/// Solve for scale, gravity and per-keyframe velocity.
///
/// `poses[i]` and `imu[i]` must line up such that `imu[i]` covers the interval from
/// keyframe `i` to `i+1`, so `imu.len() == poses.len() - 1`.
pub fn align_visual_inertial(
    poses: &[KeyframePose],
    imu: &[Preintegrated],
) -> Option<AlignmentResult> {
    let n = poses.len();
    if n < 3 || imu.len() + 1 != n {
        return None;
    }

    // Unknowns: [v_0 (3), v_1 (3), ..., v_{n-1} (3), g (3), s (1)]
    let n_unknowns = 3 * n + 4;
    let n_rows = 6 * (n - 1);
    if n_rows < n_unknowns {
        return None;
    }

    let mut a = DMatrix::<f64>::zeros(n_rows, n_unknowns);
    let mut b = DVector::zeros(n_rows);
    let g_col = 3 * n;
    let s_col = 3 * n + 3;

    for i in 0..(n - 1) {
        let pre = &imu[i];
        let dt = pre.dt;
        if dt <= 0.0 {
            return None;
        }
        let r_i = &poses[i].rotation;
        let dp = poses[i + 1].position - poses[i].position;

        // --- Position rows ---
        // s·dp − v_i·Δt − ½·g·Δt² = R_i·Δp
        let row = 6 * i;
        for k in 0..3 {
            a[(row + k, 3 * i + k)] = -dt;
            a[(row + k, g_col + k)] = -0.5 * dt * dt;
            a[(row + k, s_col)] = dp[k];
        }
        let rdp = r_i * pre.delta_p.clone();
        for k in 0..3 {
            b[row + k] = rdp[k];
        }

        // --- Velocity rows ---
        // v_{i+1} − v_i − g·Δt = R_i·Δv
        let row = 6 * i + 3;
        for k in 0..3 {
            a[(row + k, 3 * i + k)] = -1.0;
            a[(row + k, 3 * (i + 1) + k)] = 1.0;
            a[(row + k, g_col + k)] = -dt;
        }
        let rdv = r_i * pre.delta_v.clone();
        for k in 0..3 {
            b[row + k] = rdv[k];
        }
    }

    let x = solve_least_squares(&a, &b, n_rows, n_unknowns)?;

    let scale = x[s_col];
    let gravity = Vector3::new(x[g_col], x[g_col + 1], x[g_col + 2]);

    // A negative or wildly small scale means the geometry was degenerate, most often
    // because the user only rotated the phone.
    if !(scale.is_finite() && scale > 1e-4) {
        return None;
    }

    let velocities = (0..n)
        .map(|i| Vector3::new(x[3 * i], x[3 * i + 1], x[3 * i + 2]))
        .collect();

    // Gravity magnitude is the honest self-check: nothing in the solve forces it, so
    // how close it lands to 9.81 says how much the rest can be trusted.
    let g_err = (gravity.norm() - GRAVITY).abs() / GRAVITY;
    let confidence = (1.0 - g_err * 2.0).clamp(0.0, 1.0);

    Some(AlignmentResult { scale, gravity, velocities, confidence })
}

/// Normal-equations least squares: solve `AᵀA x = Aᵀb`.
///
/// The system is small (a dozen keyframes is ~40 unknowns) and reasonably conditioned
/// once there is real translation, so the squared condition number of the normal
/// equations is acceptable here and avoids implementing a dense QR.
fn solve_least_squares(
    a: &DMatrix<f64>,
    b: &DVector<f64>,
    rows: usize,
    cols: usize,
) -> Option<DVector<f64>> {
    let mut ata = DMatrix::<f64>::zeros(cols, cols);
    let mut atb = DVector::zeros(cols);

    for i in 0..cols {
        for j in i..cols {
            let mut sum = 0.0;
            for k in 0..rows {
                sum += a[(k, i)] * a[(k, j)];
            }
            ata[(i, j)] = sum;
            ata[(j, i)] = sum;
        }
        let mut sum = 0.0;
        for k in 0..rows {
            sum += a[(k, i)] * b[k];
        }
        atb[i] = sum;
    }

    // Tikhonov nudge: keeps the solve from blowing up when a direction is barely
    // excited (e.g. almost no motion along one axis) without biasing a good solve.
    for i in 0..cols {
        ata[(i, i)] += 1e-9;
    }

    ata.lu().solve(&atb)
}

/// Refine gravity by projecting it back onto the sphere of radius `GRAVITY`.
///
/// The linear solve treats gravity's three components as free, so the result is close
/// to but not exactly 9.81 m/s². Renormalising trades a little least-squares optimality
/// for a physically valid vector.
pub fn refine_gravity(g: Vector3<f64>) -> Vector3<f64> {
    let n = g.norm();
    if n < 1e-9 {
        return Vector3::new(0.0, 0.0, -GRAVITY);
    }
    g * (GRAVITY / n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::preintegrate::{preintegrate, ImuSample};
    use vision_core::camera::rodrigues_to_mat;

    /// Simulate a device that both translates and rotates, and return the visual
    /// poses (positions divided by `true_scale`, as a monocular reconstruction would
    /// give them) alongside the IMU preintegration for each interval.
    ///
    /// Rotation matters: with a fixed attitude, a constant linear acceleration and a
    /// tilted gravity vector produce identical accelerometer readings, so gravity and
    /// acceleration cannot be separated and scale comes out wrong. Rotating the device
    /// changes how gravity projects into the body frame and breaks that ambiguity.
    /// This is why real VIO initialisation asks the user to move *and* turn the phone.
    fn simulate(
        true_scale: f64,
        duration: f64,
        n_keyframes: usize,
        rotate: bool,
    ) -> (Vec<KeyframePose>, Vec<Preintegrated>) {
        let gravity_world = Vector3::new(0.0, 0.0, -GRAVITY);
        let imu_rate = 400.0;
        let dt = 1.0 / imu_rate;

        // Sideways sinusoidal sweep: gives genuine, varying parallax.
        let amp = 0.15;
        let omega = 2.0 * std::f64::consts::PI / duration;
        let pos_at = |t: f64| Vector3::new(amp * (omega * t).sin(), 0.0, 0.0);
        let acc_at = |t: f64| Vector3::new(-amp * omega * omega * (omega * t).sin(), 0.0, 0.0);

        // Gentle yaw sweep about the body Y axis.
        let rot_rate = if rotate { 0.6 } else { 0.0 };
        let axis = Vector3::new(0.0, 1.0, 0.0);
        let rot_at = |t: f64| rodrigues_to_mat(axis * (rot_rate * t));

        let seg = duration / (n_keyframes - 1) as f64;

        let poses: Vec<KeyframePose> = (0..n_keyframes)
            .map(|k| {
                let t = k as f64 * seg;
                KeyframePose {
                    rotation: rot_at(t),
                    position: pos_at(t) / true_scale,
                }
            })
            .collect();

        let mut pres = Vec::new();
        for k in 0..(n_keyframes - 1) {
            let t0 = k as f64 * seg;
            let n = (seg * imu_rate).round() as usize + 1;
            let samples: Vec<ImuSample> = (0..n)
                .map(|i| {
                    let t = t0 + i as f64 * dt;
                    let r = rot_at(t);
                    // Accelerometer reads specific force in the body frame.
                    let f_world = acc_at(t) - gravity_world;
                    let f_body = r.transpose() * f_world;
                    ImuSample {
                        t_ns: (t * 1e9).round() as i64,
                        gyro: axis * rot_rate,
                        accel: f_body,
                    }
                })
                .collect();
            // Raw specific-force integral: the solver cancels gravity itself.
            pres.push(preintegrate(&samples, Vector3::zeros(), Vector3::zeros()));
        }
        (poses, pres)
    }

    #[test]
    fn recovers_a_known_scale_factor() {
        let true_scale = 3.0;
        let (poses, imu) = simulate(true_scale, 1.5, 10, true);
        let r = align_visual_inertial(&poses, &imu).expect("alignment");
        assert!(
            (r.scale - true_scale).abs() / true_scale < 0.10,
            "scale {} should be near {true_scale}",
            r.scale
        );
    }

    #[test]
    fn recovered_scale_is_independent_of_the_hidden_factor() {
        // Whatever arbitrary scale the visual reconstruction happened to pick, the
        // aligner should undo exactly it.
        for &s_true in &[0.5, 2.0, 7.5] {
            let (poses, imu) = simulate(s_true, 1.5, 10, true);
            let r = align_visual_inertial(&poses, &imu)
                .unwrap_or_else(|| panic!("alignment failed for scale {s_true}"));
            assert!(
                (r.scale - s_true).abs() / s_true < 0.10,
                "scale {} should be near {s_true}",
                r.scale
            );
        }
    }

    #[test]
    fn recovers_gravity_direction() {
        let (poses, imu) = simulate(2.0, 1.5, 10, true);
        let r = align_visual_inertial(&poses, &imu).expect("alignment");
        let g = refine_gravity(r.gravity);
        assert!((g.norm() - GRAVITY).abs() < 1e-6);
        assert!(
            g[2] < -GRAVITY * 0.9,
            "gravity should point down, got {:?}",
            (g[0], g[1], g[2])
        );
    }

    #[test]
    fn reports_high_confidence_for_a_well_excited_trajectory() {
        let (poses, imu) = simulate(2.0, 1.5, 10, true);
        let r = align_visual_inertial(&poses, &imu).expect("alignment");
        assert!(r.confidence > 0.8, "confidence was {}", r.confidence);
    }

    #[test]
    fn rejects_a_stationary_sequence() {
        // No translation at all: scale is unobservable and the solve must decline
        // rather than return a confident nonsense number.
        let n = 6;
        let poses: Vec<KeyframePose> = (0..n)
            .map(|_| KeyframePose {
                rotation: Matrix3::identity(),
                position: Vector3::zeros(),
            })
            .collect();
        let gravity_world = Vector3::new(0.0, 0.0, -GRAVITY);
        let imu: Vec<Preintegrated> = (0..(n - 1))
            .map(|k| {
                let samples: Vec<ImuSample> = (0..101)
                    .map(|i| ImuSample {
                        t_ns: ((k as f64 * 0.25 + i as f64 / 400.0) * 1e9) as i64,
                        gyro: Vector3::zeros(),
                        accel: -gravity_world,
                    })
                    .collect();
                preintegrate(&samples, Vector3::zeros(), Vector3::zeros())
            })
            .collect();

        let r = align_visual_inertial(&poses, &imu);
        assert!(
            r.is_none() || r.unwrap().scale < 1e-2,
            "a stationary sequence must not yield a usable scale"
        );
    }

    #[test]
    fn rejects_too_few_keyframes() {
        let (poses, imu) = simulate(2.0, 0.4, 2, true);
        assert!(align_visual_inertial(&poses, &imu).is_none());
    }

    #[test]
    fn refine_gravity_normalises_magnitude() {
        let g = refine_gravity(Vector3::new(0.0, 0.0, -5.0));
        assert!((g.norm() - GRAVITY).abs() < 1e-9);
        assert!(g[2] < 0.0);
    }
}
