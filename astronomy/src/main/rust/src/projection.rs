//! Stereographic sky projection. Pure math ported 1:1 from
//! `StereographicProjection.kt` (constructor precomputes, `projectCore`,
//! `angularDist`, and the FOV cull in `project`). No Compose / dependency-free
//! so it runs under host `cargo test`.

use std::f64::consts::PI;

#[inline]
fn to_radians(deg: f64) -> f64 {
    deg * PI / 180.0
}

/// Precomputed projector, mirroring the fields the Kotlin
/// `StereographicProjection` constructor derives from a `ViewState`.
pub struct Projector {
    sin_center_alt: f64,
    cos_center_alt: f64,
    center_az: f64,
    scale: f64,
    half_fov: f64,
    screen_w: f64,
    screen_h: f64,
    rotation_rad: f64,
}

impl Projector {
    pub fn new(
        center_az_rad: f64,
        center_alt_rad: f64,
        fov_deg: f64,
        screen_w: f64,
        screen_h: f64,
        rotation_rad: f64,
    ) -> Projector {
        let sin_center_alt = center_alt_rad.sin();
        let cos_center_alt = center_alt_rad.cos();
        // ViewState.fovRad = toRadians(fovDeg), then clamped 1..150 deg.
        let fov_rad = to_radians(fov_deg).clamp(to_radians(1.0), to_radians(150.0));
        let scale = {
            let rho_edge = 2.0 * (fov_rad / 2.0 / 2.0).tan();
            if rho_edge < 1e-9 {
                1.0
            } else {
                (screen_w * 0.5) / rho_edge
            }
        };
        let half_fov = {
            let w = screen_w;
            let h = screen_h;
            let corner_rho = 0.5 * (w * w + h * h).sqrt();
            let theta_corner = 2.0 * ((corner_rho / scale) / 2.0).atan();
            (theta_corner + to_radians(2.0)).clamp(to_radians(1.0), to_radians(179.0))
        };
        Projector {
            sin_center_alt,
            cos_center_alt,
            center_az: center_az_rad,
            scale,
            half_fov,
            screen_w,
            screen_h,
            rotation_rad,
        }
    }

    /// Mirrors `StereographicProjection.angularDist` including the `+3*PI` wrap.
    fn angular_dist(&self, az_rad: f64, alt_rad: f64) -> f64 {
        let sin_alt = alt_rad.sin();
        let cos_alt = alt_rad.cos();
        let d_az = {
            let it = az_rad - self.center_az;
            ((it + 3.0 * PI) % (2.0 * PI)) - PI
        };
        let cos_theta = self.sin_center_alt * sin_alt + self.cos_center_alt * cos_alt * d_az.cos();
        cos_theta.clamp(-1.0, 1.0).acos()
    }

    /// Mirrors `StereographicProjection.projectCore` (note the `+PI` wrap here,
    /// distinct from `angularDist`'s `+3*PI`).
    fn project_core(&self, az_rad: f64, alt_rad: f64, theta: f64) -> (f32, f32) {
        if theta < 1e-9 {
            return ((self.screen_w / 2.0) as f32, (self.screen_h / 2.0) as f32);
        }
        let rho = 2.0 * (theta / 2.0).tan() * self.scale;
        let sin_alt = alt_rad.sin();
        let cos_alt = alt_rad.cos();
        let d_az = {
            let it = az_rad - self.center_az;
            ((it + PI) % (2.0 * PI)) - PI
        };
        let sin_daz = d_az.sin();
        let cos_daz = d_az.cos();
        let phi = (sin_daz * cos_alt)
            .atan2(self.cos_center_alt * sin_alt - self.sin_center_alt * cos_alt * cos_daz);
        let x = rho * phi.sin();
        let y = rho * phi.cos();
        let cos_r = self.rotation_rad.cos();
        let sin_r = self.rotation_rad.sin();
        let xr = x * cos_r - y * sin_r;
        let yr = x * sin_r + y * cos_r;
        (
            (self.screen_w / 2.0 + xr) as f32,
            (self.screen_h / 2.0 + yr) as f32,
        )
    }

    /// Mirrors `StereographicProjection.project`: returns `None` when culled.
    pub fn project(&self, az_rad: f64, alt_rad: f64) -> Option<(f32, f32)> {
        let theta = self.angular_dist(az_rad, alt_rad);
        if theta > self.half_fov {
            return None;
        }
        Some(self.project_core(az_rad, alt_rad, theta))
    }
}

/// Batch stereographic projection. `altaz` is interleaved `[az0, alt0, ...]`;
/// returns interleaved `[x0, y0, x1, y1, ...]` in screen pixels, with both
/// components set to `f32::NAN` for any point `project` culls.
pub fn batch_project(
    altaz: &[f64],
    center_az_rad: f64,
    center_alt_rad: f64,
    fov_deg: f64,
    screen_w: f64,
    screen_h: f64,
    rotation_rad: f64,
) -> Vec<f32> {
    let p = Projector::new(
        center_az_rad,
        center_alt_rad,
        fov_deg,
        screen_w,
        screen_h,
        rotation_rad,
    );
    let n = altaz.len() / 2;
    let mut out = Vec::with_capacity(n * 2);
    for i in 0..n {
        let az = altaz[2 * i];
        let alt = altaz[2 * i + 1];
        match p.project(az, alt) {
            Some((x, y)) => {
                out.push(x);
                out.push(y);
            }
            None => {
                out.push(f32::NAN);
                out.push(f32::NAN);
            }
        }
    }
    out
}
