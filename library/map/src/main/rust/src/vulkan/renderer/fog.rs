//! Distance fog for tilted views: haze far tiles into the land.
//!
//! Split out of `mod.rs` (which owns the colour helpers this builds on) to keep every
//! renderer file under the 500-line lint. Pure functions over the camera + tile id —
//! no GPU state, trivially testable off-device.

use crate::camera::Camera;
use crate::style::{Layer, Palette};

/// The tilt distances (in viewport heights from the camera centre) the distance fog ramps over.
///
/// At pitch 0 every drawn tile is within ~1 viewport-height, so nothing ever fogs. Tilted at
/// 65° the far trapezoid reaches the 6-viewport coverage cap in `select::coverage`; the ramp
/// ends there, so the farthest drawn tiles arrive already land-coloured and the ground
/// dissolves into haze instead of ending at a hard edge. Starts past the mid-field so the
/// readable map never hazes — only the far strip does.
const FOG_NEAR_VIEWPORTS: f64 = 2.5;
const FOG_FAR_VIEWPORTS: f64 = 6.0;

/// Distance-fog mix for one tile's draws: 0 near, rising to 1 at the far coverage cap.
///
/// Measured tile-centre to camera-centre in viewport heights, so it is zoom-independent: a
/// z14 tile at 65° and a z10 tile at 65° fog by where they sit on screen, not by their span.
/// Zero at pitch 0 (the `pitch_deg == 0.0` short-circuit keeps the flat map byte-identical)
/// and zero for anything inside the near ramp.
pub(crate) fn fog_factor(camera: &Camera, z: u8, x: u32, y: u32) -> f32 {
    if camera.pitch_deg == 0.0 {
        return 0.0;
    }
    let h = camera.height_dp.max(1.0) as f64;
    let span = camera.tile_span_dp(z);
    let centre = crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
    let dx = (f64::from(x) + 0.5) * span - centre.x;
    let dy = (f64::from(y) + 0.5) * span - centre.y;
    ((dx.hypot(dy) / h - FOG_NEAR_VIEWPORTS) / (FOG_FAR_VIEWPORTS - FOG_NEAR_VIEWPORTS))
        .clamp(0.0, 1.0) as f32
}

/// Mix an ARGB colour toward the fog colour by `f`: 0 is unchanged, 1 is the fog colour.
///
/// Applied in packed space like `scale_alpha`, consistent with the renderer's existing
/// non-colour-managed math. Folds into the pushed colour, so fills *and* lines fog together
/// (unlike `morph.x`, which the line path ignores) — roads haze with the land instead of
/// staying crisp over it. Alpha mixes too: full fog is opaque haze, which is what makes the
/// far edge read as haze rather than as translucent ground.
pub(crate) fn apply_fog(argb: u32, fog: u32, f: f32) -> u32 {
    if f <= 0.0 {
        return argb;
    }
    let m = |a: u32, b: u32| {
        (a as f32 + (b as f32 - a as f32) * f)
            .round()
            .clamp(0.0, 255.0) as u32
    };
    (m(argb >> 24, fog >> 24) << 24)
        | (m((argb >> 16) & 0xFF, (fog >> 16) & 0xFF) << 16)
        | (m((argb >> 8) & 0xFF, (fog >> 8) & 0xFF) << 8)
        | m(argb & 0xFF, fog & 0xFF)
}

/// The colour distance fog mixes toward: the `earth` land colour, not the water-blue clear.
///
/// The clear colour is the sea by design (`style::background` is water: an unloaded map reads
/// as ocean with land appearing on top of it). Fogging toward it pulled distant tilted land
/// into ocean-blue, dissolving the far ground into sea. The far ground under tilt is land, so
/// the haze it dissolves into is the land's own colour — `earth.color(palette)` at the frame
/// zoom, opacity-scaled the way the terrain pass paints it, so the farthest drawn tiles arrive
/// already land-coloured.
///
/// Falls back to the background when the style names no `earth` layer, which keeps the old
/// behaviour rather than inventing a colour. That never happens with the bundled style; the
/// fallback is for a future style that drops the layer.
pub(crate) fn fog_color(layers: &[Layer], palette: Palette, zoom: f64) -> u32 {
    layers
        .iter()
        .find(|l| l.source_layer_id == tilecodec::mamaps::dict::LAYER_EARTH)
        .map(|earth| super::scale_alpha(earth.color(palette), earth.opacity_at(zoom)))
        .unwrap_or_else(|| crate::style::background(palette.variant))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn camera(pitch: f64) -> Camera {
        Camera {
            center_lon: -122.4194,
            center_lat: 37.7749,
            zoom: 14.0,
            width_dp: 411.0,
            height_dp: 891.0,
            density: 2.0,
            bearing_deg: 0.0,
            pitch_deg: pitch,
            time_seconds: 0.0,
        }
    }

    #[test]
    fn the_flat_map_never_fogs() {
        let c = camera(0.0);
        // Even a tile at the edge of the world: pitch 0 short-circuits to zero.
        assert_eq!(fog_factor(&c, 14, 0, 0), 0.0);
        assert_eq!(fog_factor(&c, 14, 16383, 16383), 0.0);
    }

    #[test]
    fn the_centre_tile_never_fogs_when_tilted() {
        // The tile under the camera centre sits at distance ~0 viewports: inside the near
        // ramp at any pitch, so the readable map never hazes.
        let c = camera(65.0);
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let span = c.tile_span_dp(14);
        let x = (centre.x / span).floor() as u32;
        let y = (centre.y / span).floor() as u32;
        assert_eq!(fog_factor(&c, 14, x, y), 0.0);
    }

    #[test]
    fn a_far_tile_fogs_fully_at_max_tilt() {
        // Six viewport-heights north of the centre: past the far ramp end, so full fog.
        let c = camera(65.0);
        let centre = crate::camera::project(c.center_lon, c.center_lat, c.zoom);
        let span = c.tile_span_dp(14);
        let far = centre.y - 6.0 * c.height_dp as f64;
        let ty = (far / span).floor().max(0.0) as u32;
        let tx = (centre.x / span).floor().max(0.0) as u32;
        assert_eq!(fog_factor(&c, 14, tx, ty), 1.0);
    }

    #[test]
    fn fog_mixes_toward_the_fog_colour() {
        assert_eq!(apply_fog(0xFF00_0000, 0xFFFF_FFFF, 0.0), 0xFF00_0000);
        assert_eq!(apply_fog(0xFF00_0000, 0xFFFF_FFFF, 1.0), 0xFFFF_FFFF);
        // Halfway red-to-white is half-grey, opaque throughout.
        assert_eq!(apply_fog(0xFFFF_0000, 0xFFFF_FFFF, 0.5), 0xFFFF_8080);
    }

    #[test]
    fn fog_aims_at_the_land_not_the_water() {
        use crate::style::Variant;
        let layers = crate::style::layers();
        let land = fog_color(layers, Palette::new(false, false), 14.0);
        assert_eq!(land, 0xFFE2DFDA, "light earth is the authored #e2dfda");
        assert_eq!(
            fog_color(layers, Palette::new(true, false), 14.0),
            0xFF24262C,
            "dark earth is the authored #24262c"
        );
        // The water-blue clear the fog used to aim at: full fog must no longer land on it.
        let water = crate::style::background(Variant::Light);
        assert_eq!(
            water, 0xFF80DEEA,
            "the clear is still the authored water-blue"
        );
        assert_ne!(land, water, "fog must not aim at the water-blue clear");
        // Full fog of a water draw is the land colour: a far tile hazes into the shore
        // haze rather than dissolving the land into sea.
        assert_eq!(apply_fog(water, land, 1.0), land);
    }
}
