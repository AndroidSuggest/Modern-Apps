//! Gravity-aligned plane fitting over the landmark cloud.
//!
//! Anchors snap to a fitted plane so that tapping "the floor" twice produces two points
//! that genuinely lie on one surface, rather than two independently noisy depths. Since
//! the alignment step gives a metric gravity vector, planes can be classified as
//! horizontal or vertical and the fit can be constrained to physically plausible
//! surfaces instead of any arbitrary orientation.

use vision_core::geometry::Lcg;
use vision_core::linalg::Vector3;

/// A plane as `n · x + d = 0` with `n` unit length.
#[derive(Clone, Debug)]
pub struct Plane {
    pub normal: Vector3<f64>,
    pub d: f64,
    pub inliers: Vec<usize>,
}

impl Plane {
    /// Signed distance from a point to the plane, in metres.
    pub fn distance(&self, p: &Vector3<f64>) -> f64 {
        self.normal.dot(p) + self.d
    }

    /// Project a point perpendicularly onto the plane.
    pub fn project(&self, p: &Vector3<f64>) -> Vector3<f64> {
        *p - self.normal * self.distance(p)
    }

    /// Intersection of the ray `origin + t·dir` with the plane, if it hits in front.
    pub fn intersect_ray(&self, origin: &Vector3<f64>, dir: &Vector3<f64>) -> Option<Vector3<f64>> {
        let denom = self.normal.dot(dir);
        if denom.abs() < 1e-9 {
            return None; // ray parallel to the plane
        }
        let t = -(self.normal.dot(origin) + self.d) / denom;
        if t <= 0.0 {
            return None; // plane is behind the camera
        }
        Some(*origin + *dir * t)
    }

    /// Angle between the plane normal and the up direction, in radians.
    pub fn tilt_from(&self, up: &Vector3<f64>) -> f64 {
        let n = up.norm();
        if n < 1e-12 {
            return 0.0;
        }
        let cos = (self.normal.dot(up) / n).clamp(-1.0, 1.0);
        cos.acos().min(std::f64::consts::PI - cos.acos())
    }
}

fn plane_through(a: &Vector3<f64>, b: &Vector3<f64>, c: &Vector3<f64>) -> Option<Plane> {
    let n = (*b - *a).cross(&(*c - *a));
    let len = n.norm();
    if len < 1e-12 {
        return None; // collinear
    }
    let normal = n / len;
    let d = -normal.dot(a);
    Some(Plane { normal, d, inliers: Vec::new() })
}

/// RANSAC plane fit.
///
/// When `up` is supplied, candidate planes whose normal is further than
/// `max_tilt_rad` from parallel or perpendicular to it are discarded. Real indoor
/// surfaces are overwhelmingly floors, walls and tabletops, so this rejects fits
/// through scattered clutter that happen to catch a few points.
pub fn fit_plane_ransac(
    points: &[Vector3<f64>],
    iters: usize,
    inlier_dist: f64,
    up: Option<Vector3<f64>>,
    max_tilt_rad: f64,
) -> Option<Plane> {
    let n = points.len();
    if n < 3 {
        return None;
    }
    let mut rng = Lcg::new(0x71A4E ^ n as u64);
    let mut best: Option<Plane> = None;

    for _ in 0..iters {
        let i = rng.next_usize(n);
        let j = rng.next_usize(n);
        let k = rng.next_usize(n);
        if i == j || j == k || i == k {
            continue;
        }
        let cand = match plane_through(&points[i], &points[j], &points[k]) {
            Some(p) => p,
            None => continue,
        };

        if let Some(ref u) = up {
            let tilt = cand.tilt_from(u);
            let horizontal = tilt < max_tilt_rad;
            let vertical = (tilt - std::f64::consts::FRAC_PI_2).abs() < max_tilt_rad;
            if !horizontal && !vertical {
                continue;
            }
        }

        let inliers: Vec<usize> = (0..n)
            .filter(|&idx| cand.distance(&points[idx]).abs() < inlier_dist)
            .collect();

        if best.as_ref().is_none_or(|b| inliers.len() > b.inliers.len()) {
            best = Some(Plane { inliers, ..cand });
        }
    }

    let best = best?;
    if best.inliers.len() < 3 {
        return None;
    }
    Some(refit_least_squares(points, &best.inliers).unwrap_or(best))
}

/// Refit a plane to all its inliers via the smallest principal direction of the
/// centred scatter matrix, which is the total-least-squares normal.
fn refit_least_squares(points: &[Vector3<f64>], inliers: &[usize]) -> Option<Plane> {
    if inliers.len() < 3 {
        return None;
    }
    let mut centroid = Vector3::zeros();
    for &i in inliers {
        centroid += points[i];
    }
    centroid /= inliers.len() as f64;

    // Symmetric 3x3 scatter matrix, accumulated as raw sums.
    let mut m = [[0.0f64; 3]; 3];
    for &i in inliers {
        let d = points[i] - centroid;
        for r in 0..3 {
            for c in 0..3 {
                m[r][c] += d[r] * d[c];
            }
        }
    }
    let mat = vision_core::linalg::Matrix3::new(
        m[0][0], m[0][1], m[0][2], m[1][0], m[1][1], m[1][2], m[2][0], m[2][1], m[2][2],
    );
    let eig = mat.symmetric_eigen();
    // Smallest eigenvalue's eigenvector is the surface normal.
    let mut min_i = 0;
    for i in 1..3 {
        if eig.eigenvalues[i] < eig.eigenvalues[min_i] {
            min_i = i;
        }
    }
    let normal = eig.eigenvectors.column(min_i);
    let len = normal.norm();
    if len < 1e-12 {
        return None;
    }
    let normal = normal / len;
    let d = -normal.dot(&centroid);
    Some(Plane { normal, d, inliers: inliers.to_vec() })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn floor_points(n: usize, height: f64, noise: f64) -> Vec<Vector3<f64>> {
        let mut seed = 99u64;
        let mut next = move || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
        };
        (0..n)
            .map(|_| Vector3::new(next() * 4.0, next() * 4.0, height + next() * noise))
            .collect()
    }

    #[test]
    fn fits_a_horizontal_plane() {
        let pts = floor_points(80, -1.5, 0.002);
        let p = fit_plane_ransac(&pts, 200, 0.02, None, 0.3).expect("plane");
        assert!(p.normal[2].abs() > 0.99, "normal should be vertical: {:?}", p.normal[2]);
        // Plane passes near z = -1.5, so distance from a point on it is ~0.
        assert!(p.distance(&Vector3::new(0.0, 0.0, -1.5)).abs() < 0.02);
        assert!(p.inliers.len() > 70);
    }

    #[test]
    fn ray_intersection_hits_the_floor() {
        let pts = floor_points(80, -1.5, 0.001);
        let p = fit_plane_ransac(&pts, 200, 0.02, None, 0.3).expect("plane");
        let origin = Vector3::zeros();
        let dir = Vector3::new(0.0, 0.3, -1.0);
        let hit = p.intersect_ray(&origin, &dir).expect("hit");
        assert!((hit[2] + 1.5).abs() < 0.05, "hit z = {}", hit[2]);
    }

    #[test]
    fn ray_pointing_away_from_the_plane_misses() {
        let pts = floor_points(60, -1.5, 0.001);
        let p = fit_plane_ransac(&pts, 200, 0.02, None, 0.3).expect("plane");
        // Looking up, away from the floor below.
        assert!(p.intersect_ray(&Vector3::zeros(), &Vector3::new(0.0, 0.0, 1.0)).is_none());
    }

    #[test]
    fn projection_lands_on_the_plane() {
        let pts = floor_points(60, -1.0, 0.001);
        let p = fit_plane_ransac(&pts, 200, 0.02, None, 0.3).expect("plane");
        let q = p.project(&Vector3::new(1.0, 2.0, 5.0));
        assert!(p.distance(&q).abs() < 1e-9);
    }

    #[test]
    fn gravity_constraint_rejects_a_slanted_fit() {
        // A cloud on a 45-degree ramp is neither floor nor wall, so with the
        // constraint active there should be no acceptable plane.
        let mut seed = 7u64;
        let mut next = move || {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1);
            ((seed >> 33) as f64 / (1u64 << 31) as f64) - 0.5
        };
        let pts: Vec<Vector3<f64>> = (0..60)
            .map(|_| {
                let x = next() * 3.0;
                let y = next() * 3.0;
                Vector3::new(x, y, x) // 45-degree slope
            })
            .collect();
        let up = Vector3::new(0.0, 0.0, 1.0);
        assert!(fit_plane_ransac(&pts, 300, 0.02, Some(up), 0.2).is_none());
    }

    #[test]
    fn too_few_points_is_none() {
        let pts = vec![Vector3::zeros(), Vector3::new(1.0, 0.0, 0.0)];
        assert!(fit_plane_ransac(&pts, 50, 0.01, None, 0.3).is_none());
    }
}
