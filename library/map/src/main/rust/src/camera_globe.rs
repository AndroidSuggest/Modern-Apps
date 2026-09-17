//! Globe camera math: the orthographic-sphere matrices behind the globe path.
//!
//! Split from `camera_extra.rs` (file-length limit). The flat derivations there
//! are untouched: `globe == false` never reaches here.
use crate::camera::Camera;

impl Camera {
    /// Orthographic-sphere tile matrix: bends tile (z, x, y) onto the globe.
    ///
    /// The tile's own (u, v) grid is evaluated per-vertex in the `*_globe` shaders
    /// (not baked here — a 4x4 cannot bend), so this matrix carries two things the
    /// flat path does not: the globe's screen scale in the linear 2x2 (r in Dp),
    /// centred on the viewport, and the tile's normalised-world origin + span in
    /// the third row (m[2], m[6], m[10]), which the globe shaders read to
    /// reconstruct the lon/lat per vertex.
    pub fn globe_tile_to_clip(&self, z: u8, x: u32, y: u32) -> [f32; 16] {
        let r = crate::camera::globe_radius(self.zoom);
        let kx = 2.0 / self.width_dp as f64;
        let ky = 2.0 / self.height_dp as f64;
        // Tile origin/span in *normalised world* (0..1 across the planet): stable
        // across zooms and compact enough for an f32 push row.
        let n = 2f64.powi(z as i32);
        let ox = x as f64 / n;
        let oy = y as f64 / n;
        let span = 1.0 / n;
        [
            (kx * r) as f32,
            0.0,
            ox as f32,
            0.0, //
            0.0,
            (ky * r) as f32,
            oy as f32,
            0.0, //
            0.0,
            0.0,
            span as f32,
            0.0, //
            0.0,
            0.0,
            0.0,
            1.0,
        ]
    }

    /// Screen-space centre of a lon/lat on the globe (Dp from viewport top-left),
    /// or `None` when it is on the far side. For markers, the puck and picking —
    /// the globe counterpart of [`screen_quad_to_clip`](Self::screen_quad_to_clip).
    pub fn globe_anchor_to_screen(&self, lon: f64, lat: f64) -> Option<(f64, f64)> {
        let (x, y, z) = crate::camera::globe_point(self.center_lon, self.center_lat, lon, lat);
        if z < 0.0 {
            return None;
        }
        let r = crate::camera::globe_radius(self.zoom);
        Some((
            self.width_dp as f64 / 2.0 + x * r,
            self.height_dp as f64 / 2.0 - y * r,
        ))
    }
}
