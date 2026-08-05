//! Landmark triangulation.
//!
//! Turns matched observations of the same scene point across two or more camera poses
//! into a 3D position. Distances between anchors are ultimately distances between
//! triangulated landmarks, so the depth quality here sets the accuracy ceiling of the
//! whole app.

use crate::epipolar::NPt;
use vision_core::linalg::{DMatrix, Matrix3, Vector3};

/// A camera pose as world-to-camera: `x_cam = r * x_world + t`.
#[derive(Clone, Debug)]
pub struct CameraPose {
    pub r: Matrix3<f64>,
    pub t: Vector3<f64>,
}

impl CameraPose {
    pub fn identity() -> Self {
        CameraPose { r: Matrix3::identity(), t: Vector3::zeros() }
    }

    /// Camera centre in world coordinates: `C = -Rᵀ t`.
    pub fn center(&self) -> Vector3<f64> {
        let rt = self.r.transpose();
        -(rt * self.t.clone())
    }
}

/// Direct linear triangulation from N >= 2 views.
///
/// Each view contributes two rows constraining the homogeneous world point; the
/// solution is the smallest right singular vector.
pub fn triangulate(views: &[(CameraPose, NPt)]) -> Option<Vector3<f64>> {
    if views.len() < 2 {
        return None;
    }
    let mut m = DMatrix::<f64>::zeros(2 * views.len(), 4);
    for (i, (pose, pt)) in views.iter().enumerate() {
        let (x, y) = *pt;
        for c in 0..3 {
            m[(2 * i, c)] = x * pose.r[(2, c)] - pose.r[(0, c)];
            m[(2 * i + 1, c)] = y * pose.r[(2, c)] - pose.r[(1, c)];
        }
        m[(2 * i, 3)] = x * pose.t[2] - pose.t[0];
        m[(2 * i + 1, 3)] = y * pose.t[2] - pose.t[1];
    }
    let vt = m.svd(false, true).v_t?;
    let h = vt.row(3);
    if h[3].abs() < 1e-12 {
        return None;
    }
    Some(Vector3::new(h[0] / h[3], h[1] / h[3], h[2] / h[3]))
}

/// Reprojection error of a world point in one view, in calibrated units.
pub fn reprojection_error(pose: &CameraPose, world: &Vector3<f64>, observed: NPt) -> f64 {
    let c = &pose.r * world.clone() + pose.t.clone();
    if c[2].abs() < 1e-9 {
        return f64::MAX;
    }
    let dx = c[0] / c[2] - observed.0;
    let dy = c[1] / c[2] - observed.1;
    (dx * dx + dy * dy).sqrt()
}

/// True when the point sits in front of every camera that saw it.
pub fn is_in_front_of_all(views: &[(CameraPose, NPt)], world: &Vector3<f64>) -> bool {
    views.iter().all(|(pose, _)| {
        let c = &pose.r * world.clone() + pose.t.clone();
        c[2] > 0.0
    })
}

/// Angle in radians between the rays from two camera centres to a point.
///
/// Small parallax means depth is poorly constrained: a landmark seen from almost the
/// same viewpoint twice has essentially unbounded depth uncertainty, so callers should
/// drop anything below roughly one degree rather than trust its position.
pub fn parallax_angle(a: &CameraPose, b: &CameraPose, world: &Vector3<f64>) -> f64 {
    let ca = a.center();
    let cb = b.center();
    let ra = world.clone() - ca;
    let rb = world.clone() - cb;
    let na = ra.norm();
    let nb = rb.norm();
    if na < 1e-12 || nb < 1e-12 {
        return 0.0;
    }
    let cos = (ra.dot(&rb) / (na * nb)).clamp(-1.0, 1.0);
    cos.acos()
}

/// Triangulate and accept only if the result is geometrically trustworthy.
pub fn triangulate_checked(
    views: &[(CameraPose, NPt)],
    max_reproj: f64,
    min_parallax_rad: f64,
) -> Option<Vector3<f64>> {
    let p = triangulate(views)?;
    if !is_in_front_of_all(views, &p) {
        return None;
    }
    for (pose, obs) in views {
        if reprojection_error(pose, &p, *obs) > max_reproj {
            return None;
        }
    }
    // Widest baseline available decides whether depth is observable at all.
    let mut best = 0.0f64;
    for i in 0..views.len() {
        for j in (i + 1)..views.len() {
            best = best.max(parallax_angle(&views[i].0, &views[j].0, &p));
        }
    }
    if best < min_parallax_rad {
        return None;
    }
    Some(p)
}

#[cfg(test)]
mod tests {
    use super::*;
    use vision_core::camera::rodrigues_to_mat;

    fn project(pose: &CameraPose, p: Vector3<f64>) -> NPt {
        let c = &pose.r * p + pose.t.clone();
        (c[0] / c[2], c[1] / c[2])
    }

    fn two_views(baseline: f64) -> (CameraPose, CameraPose) {
        let a = CameraPose::identity();
        let b = CameraPose {
            r: Matrix3::identity(),
            t: Vector3::new(-baseline, 0.0, 0.0),
        };
        (a, b)
    }

    #[test]
    fn recovers_a_known_point() {
        let (a, b) = two_views(0.2);
        let truth = Vector3::new(0.3, -0.1, 4.0);
        let views = vec![(a.clone(), project(&a, truth)), (b.clone(), project(&b, truth))];
        let got = triangulate(&views).expect("triangulate");
        assert!((got - truth).norm() < 1e-6, "got {:?}", (got[0], got[1], got[2]));
    }

    #[test]
    fn recovers_a_point_from_three_views() {
        let a = CameraPose::identity();
        let b = CameraPose { r: Matrix3::identity(), t: Vector3::new(-0.2, 0.0, 0.0) };
        let c = CameraPose {
            r: rodrigues_to_mat(Vector3::new(0.0, 0.05, 0.0)),
            t: Vector3::new(-0.4, 0.02, 0.0),
        };
        let truth = Vector3::new(-0.5, 0.25, 3.0);
        let views = vec![
            (a.clone(), project(&a, truth)),
            (b.clone(), project(&b, truth)),
            (c.clone(), project(&c, truth)),
        ];
        let got = triangulate(&views).expect("triangulate");
        assert!((got - truth).norm() < 1e-6);
    }

    #[test]
    fn camera_center_is_the_negated_rotated_translation() {
        let pose = CameraPose {
            r: rodrigues_to_mat(Vector3::new(0.0, 0.3, 0.0)),
            t: Vector3::new(-0.5, 0.0, 0.1),
        };
        let c = pose.center();
        // Projecting the centre back through the pose should land at the origin.
        let back = &pose.r * c + pose.t.clone();
        assert!(back.norm() < 1e-9);
    }

    #[test]
    fn zero_baseline_gives_no_parallax_and_is_rejected() {
        let a = CameraPose::identity();
        let b = CameraPose::identity();
        let truth = Vector3::new(0.0, 0.0, 3.0);
        let views = vec![(a.clone(), project(&a, truth)), (b.clone(), project(&b, truth))];
        assert!(
            triangulate_checked(&views, 1e-3, 0.01).is_none(),
            "a zero baseline must be rejected"
        );
    }

    #[test]
    fn points_behind_the_camera_are_rejected() {
        let (a, b) = two_views(0.2);
        let behind = Vector3::new(0.0, 0.0, -3.0);
        let views = vec![(a.clone(), project(&a, behind)), (b.clone(), project(&b, behind))];
        assert!(triangulate_checked(&views, 1e-3, 0.001).is_none());
    }

    #[test]
    fn parallax_grows_with_baseline() {
        let truth = Vector3::new(0.0, 0.0, 4.0);
        let (a, near) = two_views(0.05);
        let (_, far) = two_views(0.5);
        let small = parallax_angle(&a, &near, &truth);
        let large = parallax_angle(&a, &far, &truth);
        assert!(large > small * 5.0, "small {small} large {large}");
    }

    #[test]
    fn reprojection_error_is_zero_for_a_consistent_point() {
        let (a, _) = two_views(0.2);
        let truth = Vector3::new(0.1, 0.2, 2.5);
        assert!(reprojection_error(&a, &truth, project(&a, truth)) < 1e-12);
    }
}
