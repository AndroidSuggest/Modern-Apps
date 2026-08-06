//! The VIO session: the state machine that turns a stream of camera frames and IMU
//! samples into metric camera poses and a landmark cloud.
//!
//! Lifecycle:
//!
//! 1. **Initialising** — track features, accumulate keyframes with enough parallax, and
//!    keep preintegrating IMU. Nothing is measurable yet because scale is unknown.
//! 2. **Aligning** — once several keyframes with real translation exist, solve for
//!    metric scale and gravity, then rescale the whole map into metres.
//! 3. **Tracking** — continue tracking, triangulating new landmarks, and fitting a
//!    ground plane. Measurements are only valid from here on.
//!
//! Deliberately *not* a full sliding-window bundle adjuster with marginalisation. For a
//! measuring tool the user holds still-ish for a few seconds over a few metres, the
//! dominant error is scale estimation, not long-horizon drift, so effort belongs in
//! initialisation quality rather than in a large back-end.

use crate::align::{align_visual_inertial, refine_gravity, AlignmentResult, KeyframePose};
use crate::epipolar::{recover_pose, NPt};
use crate::klt::{track, Pyramid};
use crate::plane::{fit_plane_ransac, Plane};
use crate::preintegrate::{preintegrate, ImuSample, Preintegrated};
use crate::triangulate::{triangulate_checked, CameraPose};
use vision_core::features::detect_and_describe;
use vision_core::imgbuf::Gray;
use vision_core::linalg::Vector3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quality {
    Initialising = 0,
    Limited = 1,
    Good = 2,
    Lost = 3,
}

/// Pinhole intrinsics for the analysis-resolution stream.
#[derive(Clone, Copy, Debug)]
pub struct Intrinsics {
    pub fx: f64,
    pub fy: f64,
    pub cx: f64,
    pub cy: f64,
}

impl Intrinsics {
    /// Pixel -> calibrated bearing on the z = 1 plane.
    pub fn unproject(&self, x: f32, y: f32) -> NPt {
        ((x as f64 - self.cx) / self.fx, (y as f64 - self.cy) / self.fy)
    }
}

const MIN_FEATURES: usize = 40;
const TARGET_FEATURES: usize = 300;
const FAST_THRESHOLD: i32 = 18;
/// Redetect once tracking attrition drops the set below this fraction of target.
const REDETECT_FRACTION: f64 = 0.5;
/// Median pixel displacement a frame must show before it earns a keyframe.
const KEYFRAME_MIN_FLOW_PX: f32 = 12.0;
const MIN_KEYFRAMES_FOR_ALIGN: usize = 6;
const MAX_KEYFRAMES: usize = 20;
const MIN_PARALLAX_RAD: f64 = 0.0175; // ~1 degree
const MAX_REPROJ: f64 = 0.01;

struct Keyframe {
    pose: CameraPose,
    points: Vec<(f32, f32)>,
    imu: Preintegrated,
}

pub struct VioSession {
    intr: Intrinsics,
    prev_pyramid: Option<Pyramid>,
    prev_points: Vec<(f32, f32)>,
    keyframes: Vec<Keyframe>,
    pending_imu: Vec<ImuSample>,
    gyro_bias: Vector3<f64>,
    accel_bias: Vector3<f64>,

    /// Current camera pose, metric once aligned.
    pose: CameraPose,
    scale: f64,
    gravity: Vector3<f64>,
    landmarks: Vec<Vector3<f64>>,
    plane: Option<Plane>,
    quality: Quality,
    scale_confidence: f64,
    last_frame_ns: i64,
    frame_count: u64,
}

impl VioSession {
    pub fn new(intr: Intrinsics) -> Self {
        VioSession {
            intr,
            prev_pyramid: None,
            prev_points: Vec::new(),
            keyframes: Vec::new(),
            pending_imu: Vec::new(),
            gyro_bias: Vector3::zeros(),
            accel_bias: Vector3::zeros(),
            pose: CameraPose::identity(),
            scale: 1.0,
            gravity: Vector3::new(0.0, 0.0, -crate::preintegrate::GRAVITY),
            landmarks: Vec::new(),
            plane: None,
            quality: Quality::Initialising,
            scale_confidence: 0.0,
            last_frame_ns: 0,
            frame_count: 0,
        }
    }

    pub fn reset(&mut self) {
        let intr = self.intr;
        *self = VioSession::new(intr);
    }

    pub fn quality(&self) -> Quality {
        self.quality
    }
    pub fn scale_confidence(&self) -> f64 {
        self.scale_confidence
    }
    pub fn landmark_count(&self) -> usize {
        self.landmarks.len()
    }
    pub fn tracked_count(&self) -> usize {
        self.prev_points.len()
    }
    pub fn has_plane(&self) -> bool {
        self.plane.is_some()
    }
    pub fn pose(&self) -> &CameraPose {
        &self.pose
    }
    pub fn gravity(&self) -> Vector3<f64> {
        self.gravity
    }

    /// Queue IMU samples. Cheap: they are only integrated at keyframe boundaries.
    pub fn push_imu(&mut self, samples: &[ImuSample]) {
        self.pending_imu.extend_from_slice(samples);
        // Bound memory if frames stop arriving (screen off, camera stalled).
        if self.pending_imu.len() > 8000 {
            let cut = self.pending_imu.len() - 8000;
            self.pending_imu.drain(0..cut);
        }
    }

    /// Feed one camera frame. Returns the current quality.
    pub fn push_frame(&mut self, gray: &Gray, t_ns: i64) -> Quality {
        self.frame_count += 1;
        let pyramid = Pyramid::build(gray, 4);

        let prev = match self.prev_pyramid.take() {
            None => {
                // First frame: seed the feature set and the first keyframe.
                self.prev_points = detect_points(gray);
                self.prev_pyramid = Some(pyramid);
                self.last_frame_ns = t_ns;
                self.keyframes.push(Keyframe {
                    pose: CameraPose::identity(),
                    points: self.prev_points.clone(),
                    imu: Preintegrated::identity(),
                });
                return self.quality;
            }
            Some(p) => p,
        };

        let results = track(&prev, &pyramid, &self.prev_points);
        let mut kept_prev = Vec::new();
        let mut kept_now = Vec::new();
        let mut flow = Vec::new();
        for (i, r) in results.iter().enumerate() {
            if !r.ok {
                continue;
            }
            let p = self.prev_points[i];
            kept_prev.push(p);
            kept_now.push((r.x, r.y));
            flow.push(((r.x - p.0).powi(2) + (r.y - p.1).powi(2)).sqrt());
        }

        if kept_now.len() < MIN_FEATURES {
            // Attrition wiped the set out. Only call this "lost" if tracking had
            // actually been established: reporting Lost during start-up would tell the
            // user to recover something that never existed, when the honest state is
            // still "initialising".
            if matches!(self.quality, Quality::Good | Quality::Limited) {
                self.quality = Quality::Lost;
            }
            self.prev_points = detect_points(gray);
            self.prev_pyramid = Some(pyramid);
            self.last_frame_ns = t_ns;
            return self.quality;
        }

        let median_flow = median(&mut flow);
        self.prev_points = kept_now.clone();
        self.prev_pyramid = Some(pyramid);

        if self.prev_points.len() < (TARGET_FEATURES as f64 * REDETECT_FRACTION) as usize {
            // Top the set back up, keeping the survivors so continuity is preserved.
            let fresh = detect_points(gray);
            merge_points(&mut self.prev_points, &fresh, TARGET_FEATURES);
        }

        if median_flow >= KEYFRAME_MIN_FLOW_PX {
            self.add_keyframe(&kept_prev, &kept_now, t_ns);
        }

        self.last_frame_ns = t_ns;
        self.quality
    }

    fn add_keyframe(&mut self, prev_pts: &[(f32, f32)], now_pts: &[(f32, f32)], t_ns: i64) {
        let a: Vec<NPt> = prev_pts.iter().map(|p| self.intr.unproject(p.0, p.1)).collect();
        let b: Vec<NPt> = now_pts.iter().map(|p| self.intr.unproject(p.0, p.1)).collect();

        // Sampson threshold expressed in calibrated units: ~2 px at this focal length.
        let thresh = 2.0 / self.intr.fx;
        let (rel, _inliers) = match recover_pose(&a, &b, 200, thresh) {
            Some(v) => v,
            None => return,
        };

        // Chain the relative pose onto the previous keyframe.
        let last = self.keyframes.last().map(|k| k.pose.clone()).unwrap_or_else(CameraPose::identity);
        let new_pose = CameraPose {
            r: rel.r * last.r,
            // Translation is a unit direction until alignment fixes the scale.
            t: rel.r * last.t + rel.t,
        };

        let imu = preintegrate(&self.pending_imu, self.gyro_bias, self.accel_bias);
        self.pending_imu.clear();

        self.keyframes.push(Keyframe {
            pose: new_pose.clone(),
            points: now_pts.to_vec(),
            imu,
        });
        if self.keyframes.len() > MAX_KEYFRAMES {
            self.keyframes.remove(0);
        }
        self.pose = new_pose;
        let _ = t_ns;

        if self.quality == Quality::Initialising || self.quality == Quality::Lost {
            self.try_align();
        } else {
            self.rebuild_map();
        }
    }

    /// Attempt metric alignment. Only succeeds with enough well-separated keyframes.
    fn try_align(&mut self) {
        if self.keyframes.len() < MIN_KEYFRAMES_FOR_ALIGN {
            return;
        }
        let poses: Vec<KeyframePose> = self
            .keyframes
            .iter()
            .map(|k| KeyframePose {
                rotation: k.pose.r.transpose(),
                position: k.pose.center(),
            })
            .collect();
        // imu[i] covers keyframe i -> i+1, and keyframe 0 carries an empty interval.
        let imu: Vec<Preintegrated> =
            self.keyframes.iter().skip(1).map(|k| k.imu.clone()).collect();

        let AlignmentResult { scale, gravity, confidence, .. } =
            match align_visual_inertial(&poses, &imu) {
                Some(r) => r,
                None => return,
            };

        // A low-confidence solve means gravity came out the wrong magnitude, which
        // makes the scale untrustworthy too. Better to keep coaching than to show a
        // confidently wrong number.
        if confidence < 0.3 {
            return;
        }

        self.scale = scale;
        self.gravity = refine_gravity(gravity);
        self.scale_confidence = confidence;

        // Rescale every keyframe translation into metres.
        for k in self.keyframes.iter_mut() {
            k.pose.t = k.pose.t * scale;
        }
        self.pose = self.keyframes.last().unwrap().pose.clone();

        self.quality = if confidence > 0.6 { Quality::Good } else { Quality::Limited };
        self.rebuild_map();
    }

    /// Retriangulate landmarks across the keyframe window and refit the plane.
    fn rebuild_map(&mut self) {
        if self.keyframes.len() < 2 {
            return;
        }
        let first = &self.keyframes[0];
        let last = &self.keyframes[self.keyframes.len() - 1];
        let n = first.points.len().min(last.points.len());

        let mut pts = Vec::with_capacity(n);
        for i in 0..n {
            let views = vec![
                (first.pose.clone(), self.intr.unproject(first.points[i].0, first.points[i].1)),
                (last.pose.clone(), self.intr.unproject(last.points[i].0, last.points[i].1)),
            ];
            if let Some(p) = triangulate_checked(&views, MAX_REPROJ, MIN_PARALLAX_RAD) {
                pts.push(p);
            }
        }
        self.landmarks = pts;

        if self.landmarks.len() >= 12 {
            // Gravity points down, so -gravity is up.
            let up = -self.gravity;
            self.plane = fit_plane_ransac(&self.landmarks, 200, 0.03, Some(up), 0.35);
        }

        if self.quality != Quality::Initialising {
            self.quality = if self.landmarks.len() >= 30 && self.scale_confidence > 0.6 {
                Quality::Good
            } else {
                Quality::Limited
            };
        }
    }

    /// Project metric world points back to normalised screen coordinates.
    ///
    /// Lives here rather than in Kotlin because the current pose and intrinsics are
    /// both owned by the session; exposing the pose across JNI just so the UI could
    /// redo this multiplication would duplicate the convention in two places.
    ///
    /// Returns `(nx, ny, visible)` per point, where `visible` is false for points
    /// behind the camera or outside the frame.
    pub fn project_to_screen(&self, world: &[Vector3<f64>], width: f64, height: f64) -> Vec<(f32, f32, bool)> {
        world
            .iter()
            .map(|p| {
                let c = self.pose.r * *p + self.pose.t;
                if c[2] <= 1e-6 {
                    return (0.0, 0.0, false);
                }
                let px = self.intr.fx * (c[0] / c[2]) + self.intr.cx;
                let py = self.intr.fy * (c[1] / c[2]) + self.intr.cy;
                let nx = (px / width) as f32;
                let ny = (py / height) as f32;
                let visible = (0.0..=1.0).contains(&nx) && (0.0..=1.0).contains(&ny);
                (nx, ny, visible)
            })
            .collect()
    }

    /// Convert a screen tap into a metric world point.
    ///
    /// Prefers the fitted plane: tapping a floor twice should yield two points on one
    /// surface, not two independently noisy depths. Falls back to the nearest landmark
    /// along the ray when no plane is available.
    pub fn ray_to_world(&self, px: f32, py: f32) -> Option<(Vector3<f64>, bool)> {
        if self.quality == Quality::Initialising || self.quality == Quality::Lost {
            return None;
        }
        let (nx, ny) = self.intr.unproject(px, py);
        // Camera ray in world coordinates.
        let dir_cam = Vector3::new(nx, ny, 1.0);
        let rt = self.pose.r.transpose();
        let dir_world = rt * dir_cam;
        let origin = self.pose.center();

        if let Some(ref plane) = self.plane {
            if let Some(hit) = plane.intersect_ray(&origin, &dir_world) {
                return Some((hit, true));
            }
        }

        // Nearest landmark by angle to the ray.
        let dn = dir_world.norm();
        if dn < 1e-9 || self.landmarks.is_empty() {
            return None;
        }
        let unit = dir_world / dn;
        let mut best: Option<(f64, Vector3<f64>)> = None;
        for lm in &self.landmarks {
            let v = *lm - origin;
            let along = v.dot(&unit);
            if along <= 0.0 {
                continue;
            }
            let perp = (v - unit * along).norm();
            if best.as_ref().is_none_or(|(d, _)| perp < *d) {
                best = Some((perp, *lm));
            }
        }
        best.map(|(_, p)| (p, false))
    }
}

fn detect_points(gray: &Gray) -> Vec<(f32, f32)> {
    let f = detect_and_describe(gray, TARGET_FEATURES, FAST_THRESHOLD);
    f.kps.iter().map(|k| (k.x, k.y)).collect()
}

/// Add fresh detections that are not already covered, up to `target`.
fn merge_points(existing: &mut Vec<(f32, f32)>, fresh: &[(f32, f32)], target: usize) {
    const MIN_SEP2: f32 = 100.0; // 10 px
    for &f in fresh {
        if existing.len() >= target {
            break;
        }
        let too_close = existing
            .iter()
            .any(|&e| (e.0 - f.0).powi(2) + (e.1 - f.1).powi(2) < MIN_SEP2);
        if !too_close {
            existing.push(f);
        }
    }
}

fn median(v: &mut [f32]) -> f32 {
    if v.is_empty() {
        return 0.0;
    }
    v.sort_by(|a, b| a.total_cmp(b));
    v[v.len() / 2]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn intr() -> Intrinsics {
        Intrinsics { fx: 500.0, fy: 500.0, cx: 320.0, cy: 240.0 }
    }

    #[test]
    fn unproject_maps_the_principal_point_to_the_origin() {
        let i = intr();
        let (x, y) = i.unproject(320.0, 240.0);
        assert!(x.abs() < 1e-12 && y.abs() < 1e-12);
    }

    #[test]
    fn unproject_scales_by_focal_length() {
        let i = intr();
        let (x, _) = i.unproject(320.0 + 500.0, 240.0);
        assert!((x - 1.0).abs() < 1e-12);
    }

    #[test]
    fn a_new_session_is_initialising_and_unmeasurable() {
        let s = VioSession::new(intr());
        assert_eq!(s.quality(), Quality::Initialising);
        assert!(s.ray_to_world(320.0, 240.0).is_none());
        assert_eq!(s.landmark_count(), 0);
        assert!(!s.has_plane());
    }

    #[test]
    fn reset_returns_to_the_initial_state() {
        let mut s = VioSession::new(intr());
        s.scale = 3.0;
        s.quality = Quality::Good;
        s.reset();
        assert_eq!(s.quality(), Quality::Initialising);
        assert!((s.scale - 1.0).abs() < 1e-12);
    }

    #[test]
    fn imu_queue_is_bounded() {
        let mut s = VioSession::new(intr());
        let batch: Vec<ImuSample> = (0..1000)
            .map(|i| ImuSample {
                t_ns: i as i64 * 2_500_000,
                gyro: Vector3::zeros(),
                accel: Vector3::zeros(),
            })
            .collect();
        for _ in 0..20 {
            s.push_imu(&batch);
        }
        assert!(s.pending_imu.len() <= 8000, "queue grew to {}", s.pending_imu.len());
    }

    #[test]
    fn merge_points_respects_separation_and_target() {
        let mut existing = vec![(10.0f32, 10.0f32)];
        let fresh = vec![(11.0, 11.0), (100.0, 100.0), (200.0, 200.0)];
        merge_points(&mut existing, &fresh, 3);
        // (11,11) is within 10 px of (10,10) so must be skipped.
        assert_eq!(existing.len(), 3);
        assert!(existing.contains(&(100.0, 100.0)));
        assert!(!existing.contains(&(11.0, 11.0)));
    }

    #[test]
    fn median_of_even_and_odd_lengths() {
        assert!((median(&mut [3.0, 1.0, 2.0]) - 2.0).abs() < 1e-9);
        assert!((median(&mut [1.0]) - 1.0).abs() < 1e-9);
        assert!((median(&mut []) - 0.0).abs() < 1e-9);
    }
}
