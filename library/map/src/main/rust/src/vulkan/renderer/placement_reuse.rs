//! Placement reuse across camera motion, plus the collision-box inputs.
//!
//! File-length split from the renderer root (see `mod.rs`) and from
//! [`placement`](super::placement) (see `placement.rs`): the drift-reuse
//! machinery that lets small zoom/bearing/pitch changes reuse the cached
//! accept-set, the [`PlacementKey`] universe comparison behind it, and the
//! per-label collision-box inputs the candidate loop reads. A real submodule
//! (not `include!`), so imports are explicit below.
use super::PlacementKey;
use crate::camera::Camera;
use crate::style::Layer;
use crate::tile::geometry;
use std::time::Instant;

/// The reuse window once the camera is zooming fast (see [`Renderer::place_symbols`](super::Renderer::place_symbols)).
///
/// Past ~1 zoom level per second the collision answer goes stale faster than the pan window
/// allows: re-place promptly instead so labels do not visibly lag the zoom.
const PLACE_REUSE_FAST_ZOOM_MS: u128 = 80;

/// The zoom speed past which the reuse window shrinks to [`PLACE_REUSE_FAST_ZOOM_MS`],
/// in zoom levels per second.
const FAST_ZOOM_PER_SEC: f64 = 1.0;

/// Drift thresholds for reusing the accept-set without an exact key match.
///
/// A camera that differs from the cached one only in centre/zoom/bearing/pitch, each within
/// these, reuses the cached accept-set within the reuse window: the projection moved a
/// little but the candidate universe (tiles, layers, filter, extent, sizes) is bit-identical,
/// so the collision answer is overwhelmingly likely unchanged and boxes still re-project per
/// frame in `refresh_placed`. Any drift past these re-places.
const DRIFT_MAX_DZOOM: f64 = 0.05;
/// Degrees.
const DRIFT_MAX_DBEARING: f64 = 2.0;
/// Degrees.
const DRIFT_MAX_DPITCH: f64 = 2.0;

/// Whether `key` differs from `cached` only by camera drift within the reuse thresholds.
///
/// Centre may move arbitrarily (pan); zoom/bearing/pitch each have a small budget. The
/// universe (tiles, stamps, filter, extent, sizes, layers) must be bit-identical — checked
/// by the caller via [`PlacementKey::same_universe`].
pub(super) fn within_drift(cached: &PlacementKey, key: &PlacementKey) -> bool {
    let zoom = f64::from_bits(key.zoom);
    let cached_zoom = f64::from_bits(cached.zoom);
    if (zoom - cached_zoom).abs() >= DRIFT_MAX_DZOOM {
        return false;
    }
    let bearing = f64::from_bits(key.bearing);
    let cached_bearing = f64::from_bits(cached.bearing);
    if (bearing - cached_bearing).abs() >= DRIFT_MAX_DBEARING {
        return false;
    }
    let pitch = f64::from_bits(key.pitch);
    let cached_pitch = f64::from_bits(cached.pitch);
    if (pitch - cached_pitch).abs() >= DRIFT_MAX_DPITCH {
        return false;
    }
    true
}

/// The reuse window for a drift-reuse hit: the full reuse window, shrunk to
/// [`PLACE_REUSE_FAST_ZOOM_MS`] while zooming fast.
///
/// Zoom velocity comes from the cached key's zoom versus this frame's over the cache age —
/// both already in hand, no extra state. A zero/negative age (same-instant re-entry) takes
/// the fast path only if the zoom actually jumped; otherwise the full window.
pub(super) fn drift_reuse_ms(
    cached: &PlacementKey,
    key: &PlacementKey,
    at: &Instant,
    full_window_ms: u128,
) -> u128 {
    let zoom = f64::from_bits(key.zoom);
    let cached_zoom = f64::from_bits(cached.zoom);
    let dz = (zoom - cached_zoom).abs();
    if dz <= 0.0 {
        return full_window_ms;
    }
    let age_secs = at.elapsed().as_secs_f64();
    if age_secs > 0.0 && dz / age_secs > FAST_ZOOM_PER_SEC {
        PLACE_REUSE_FAST_ZOOM_MS
    } else {
        full_window_ms
    }
}

impl PlacementKey {
    /// Bit-identity of everything but the camera pose: tiles and their stamps, filter, extent,
    /// sizes, and layer count. A drift-reuse candidate must match all of these; only
    /// centre/zoom/bearing/pitch may differ (within their thresholds — see `within_drift`).
    /// Any universe change is a hard re-place.
    pub(super) fn same_universe(&self, other: &PlacementKey) -> bool {
        self.width_dp == other.width_dp
            && self.height_dp == other.height_dp
            && self.density == other.density
            && self.extent == other.extent
            && self.filter == other.filter
            && self.tiles == other.tiles
            && self.layers == other.layers
    }
}

/// Everything one label's collision box depends on besides its anchor.
///
/// Built once per label and reused for each candidate anchor, so the two boxes a POI is
/// tried at can only differ in where they sit — not in how big they are.
pub(super) fn box_inputs(
    layer: &Layer,
    label: &geometry::ShapedLabel,
    camera: &Camera,
) -> crate::tile::placement::BoxInputs {
    box_inputs_with_arms(layer, label, camera, layer.text_size_arms(camera.zoom))
}

/// [`box_inputs`], but with the style's text-size arms already resolved for this
/// (layer, zoom) — see [`Layer::text_size_arms`]. The per-label call resolves two
/// style ramps per label; the per-layer caller resolves them once and answers each
/// label with a float compare. Bit-identical values either way.
pub(super) fn box_inputs_with_arms(
    layer: &Layer,
    label: &geometry::ShapedLabel,
    camera: &Camera,
    arms: (f32, Option<f32>, Option<f32>),
) -> crate::tile::placement::BoxInputs {
    crate::tile::placement::BoxInputs {
        text_px: Layer::text_size_with_arms(label.pop, arms.0, arms.1, arms.2) * camera.density,
        advance: label.total_advance,
        line_count: label.lines.len(),
        offset_em: layer.text_offset,
        // Dp from the sheet, device px here — the same conversion `emit_icon` makes.
        icon_px: label
            .sprite
            .map(|s| (s.width_dp * camera.density, s.height_dp * camera.density)),
        pad_px: collision_padding_px(camera.zoom),
    }
}

/// Collision padding in device px around every label box, by camera zoom.
///
/// MapLibre pads every label (icon + text padding, growing at low zoom via
/// the icon-padding ramp), which is what holds z6 to ~10 cities while z14
/// stays dense. Without it tight advance boxes let hundreds of villages
/// survive at z6. 24px at z6 and below culls hamlets against towns; 4px at
/// z14+ keeps street labels tight. Linear between.
fn collision_padding_px(zoom: f64) -> f32 {
    if zoom <= 6.0 {
        24.0
    } else if zoom >= 14.0 {
        4.0
    } else {
        (24.0 - (zoom - 6.0) * (20.0 / 8.0)) as f32
    }
}
