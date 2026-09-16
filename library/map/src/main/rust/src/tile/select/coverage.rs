use crate::camera::{Camera, TILE_SIZE};

use super::tile_id::TileId;

/// How far past the camera centre the tilt trapezoid may reach, in viewport heights.
///
/// Under tilt the top of the screen recedes toward the horizon — at 65° the far edge is many
/// viewport-heights away — and an uncapped trapezoid enumerates an unbounded tile strip that is
/// fogged to the background colour anyway (see `FAR_FADE`). Capping the far reach keeps the
/// visible set proportional to the viewport instead of the pitch: ground past the cap is still
/// drawn (the far tiles cover it, faded) but no *extra* tiles are fetched for it. In tile units
/// the cap bites only at high pitch + low zoom, exactly where the strip would explode.
const FAR_REACH_VIEWPORTS: f64 = 6.0;

/// The world-px axis-aligned box the viewport covers on the ground, accounting for **both**
/// bearing and tilt.
///
/// At pitch 0 this is exactly [`Camera::viewport_bounds`] — the bounding box of the rotated
/// viewport. Under tilt the top of the screen recedes toward the horizon, so the ground the
/// viewport actually covers is a trapezoid reaching far past that box; a selection derived from
/// the untilted box leaves the top of the display blank exactly where the distance the driver is
/// looking toward is. So the four screen corners are unprojected through the tilt-aware ray/plane
/// ([`Camera::screen_to_world`]) and the box is grown to hold them. The [`PITCH_MAX_DEG`] cap keeps
/// every corner below the horizon, so each resolves and the trapezoid stays finite.
fn coverage(camera: &Camera) -> (crate::camera::WorldPx, crate::camera::WorldPx) {
    let (mut min, mut max) = camera.viewport_bounds();
    if camera.pitch_deg == 0.0 {
        return (min, max);
    }
    let w = camera.width_dp as f64;
    let h = camera.height_dp as f64;
    // The top edge recedes furthest, so its two corners are the far edge of the trapezoid; the
    // bottom corners are the near edge. Their AABB bounds the whole trapezoid because a screen line
    // maps to a straight line on the ground plane.
    for &(sx, sy) in &[(0.0, 0.0), (w, 0.0), (w, h), (0.0, h)] {
        if let Some(p) = camera.screen_to_world(sx, sy) {
            min.x = min.x.min(p.x);
            min.y = min.y.min(p.y);
            max.x = max.x.max(p.x);
            max.y = max.y.max(p.y);
        }
    }
    // Far-distance cap: clamp the box to a radius around the camera centre so a near-horizon
    // view at high pitch enumerates a bounded strip instead of tiles to the horizon. The
    // `PITCH_MAX_DEG` cap keeps every corner finite; this keeps the *count* bounded.
    let centre = crate::camera::project(camera.center_lon, camera.center_lat, camera.zoom);
    let reach = FAR_REACH_VIEWPORTS * h.max(w);
    min.x = min.x.max(centre.x - reach);
    min.y = min.y.max(centre.y - reach);
    max.x = max.x.min(centre.x + reach);
    max.y = max.y.min(centre.y + reach);
    (min, max)
}

/// The tiles covering `camera`'s viewport, clamped to the archive's zoom range.
///
/// Coverage is the bounding box of the viewport **as the camera actually orients it** — bearing
/// *and* tilt — so a rotation pulls in the extra ring of tiles the rotated corners reach and a tilt
/// pulls in the trapezoid of ground the receding top of the screen covers. At bearing zero and
/// pitch zero the box is the viewport and this is what it always was.
pub fn visible(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    if camera.width_dp <= 0.0 || camera.height_dp <= 0.0 {
        return Vec::new();
    }
    let z = (camera.zoom.floor().max(0.0) as u32).clamp(min_zoom as u32, max_zoom as u32) as u8;
    let n = 1i64 << z;
    let span = camera.tile_span_dp(z);
    let (min, max) = coverage(camera);

    let min_tx = (min.x / span).floor() as i64;
    let max_tx = (max.x / span).floor() as i64;
    let min_ty = (min.y / span).floor() as i64;
    let max_ty = (max.y / span).floor() as i64;

    let mut out = Vec::new();
    for ty in min_ty..=max_ty {
        if ty < 0 || ty >= n {
            continue;
        }
        for tx in min_tx..=max_tx {
            if tx < 0 || tx >= n {
                continue;
            }
            out.push(TileId { z, x: tx as u32, y: ty as u32 });
        }
    }
    out
}

/// A rough bound on how many tiles a viewport can want, for capacity hints.
///
/// Measured off the same tilt-and-rotation coverage box [`visible`] selects from, so it stays a
/// bound rather than becoming a lie the moment the camera turns or tilts.
pub fn bound(camera: &Camera) -> usize {
    let (min, max) = coverage(camera);
    let across = (max.x - min.x) / TILE_SIZE + 2.0;
    let down = (max.y - min.y) / TILE_SIZE + 2.0;
    (across * down).ceil() as usize
}
