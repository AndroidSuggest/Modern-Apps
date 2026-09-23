//! App-pin placement blockers: marker screen boxes as rank-0 collision candidates.
//!
//! File-length split from [`placement`](super::placement) (see `placement.rs`):
//! an `impl Renderer` method beside the pre-pass it feeds, with explicit
//! imports like the other splits carry.
use super::{Overlay, Renderer};
use crate::camera::Camera;
use crate::tile::placement;
use ash::vk;
use std::collections::HashSet;

impl Renderer {
    /// Every drawn marker's screen box as a rank-0 candidate, plus the sentinel ids for
    /// [`place_symbols`](super::placement) to strip from the accept-set.
    ///
    /// A POI that would land on a pin loses its collision instead of drawing under it. Rank 0
    /// short-circuits the placer (always accepted, first in order), which puts the pin
    /// boxes in the grid before any POI is tested — pins beat POIs, never each other, and
    /// never themselves (blockers carry no alternate). Vehicles are excluded: a simulated
    /// sprite is transient and untappable, and culling POIs for a passing bus would flicker
    /// labels every second. Markers are a handful of pins, so this is one projection each.
    ///
    /// The boxes come from `marker_screen_geom` — the same derivation the icon and label
    /// draws read — so a blocker covers exactly what the pin draws. A marker that draws
    /// nothing (missing sprite, behind the eye, far side) contributes no blocker either.
    pub(super) fn marker_blockers(
        &self,
        camera: &Camera,
        extent: vk::Extent2D,
    ) -> (Vec<placement::SegmentedCandidate>, HashSet<u64>) {
        let mut candidates: Vec<placement::SegmentedCandidate> = Vec::new();
        let mut blocker_ids: HashSet<u64> = HashSet::new();
        let globe = crate::camera::globe_active(camera);
        let density = camera.density;
        let glyph_atlas = crate::tile::glyph::atlas();
        let text_px = crate::marker::MARKER_LABEL_DP * density;
        let (vw, vh) = (extent.width as f32, extent.height as f32);
        let mut index = 0usize;
        for overlay in &self.overlays {
            let Overlay::Markers(markers, labels) = overlay else {
                continue;
            };
            for (i, marker) in markers.iter().enumerate() {
                let Some((sprite, cx, cy, ..)) =
                    super::record_markers::marker_screen_geom(camera, marker, globe)
                else {
                    continue;
                };
                // Clip centre to device px — the same mapping `project_to_screen` does.
                let sx = (cx as f32 * 0.5 + 0.5) * vw;
                let sy = (cy as f32 * 0.5 + 0.5) * vh;
                let (hw_dp, hh_dp) = crate::marker::marker_icon_half_extents(sprite);
                let (hw, hh) = (hw_dp * density, hh_dp * density);
                let (x0, mut y0, mut x1, mut y1) = (sx - hw, sy - hh, sx + hw, sy + hh);
                // The name beside the icon, when the pin carries one: the same single-line
                // left-anchored layout the label draw emits (icon edge plus gap, advance at
                // the marker text size, one em tall centred on the pin).
                let label = labels.get(i).map(String::as_str).unwrap_or("");
                if !label.is_empty() {
                    let (_, advance) = crate::tess::text::shape(
                        glyph_atlas,
                        crate::tile::glyph::Weight::Regular,
                        label,
                        false,
                    );
                    if advance > 0.0 {
                        let w = text_px * advance / crate::tile::glyph::UP_EM as f32;
                        let h = text_px;
                        let offset =
                            (hw_dp + crate::marker::MARKER_LABEL_GAP_DP) * density;
                        x1 = x1.max(sx + offset + w);
                        y0 = y0.min(sy - h * 0.5);
                        y1 = y1.max(sy + h * 0.5);
                    }
                }
                // A sentinel id outside the `candidate_id` hash space, stripped from the
                // accept-set by the caller: blockers are not tile labels, so `record_symbol`
                // and `refresh_placed` (which resolve ids through tiles) must never see them.
                let id = u64::MAX - index as u64;
                index += 1;
                blocker_ids.insert(id);
                candidates.push(placement::SegmentedCandidate {
                    id,
                    rank: 0,
                    pop: 0,
                    boxes: vec![placement::Obb::from_rect((x0, y0, x1, y1))],
                    alternate: None,
                    feature_id: tilecodec::mamaps::body::ID_NONE,
                    layer_index: usize::MAX,
                });
            }
        }
        (candidates, blocker_ids)
    }
}
