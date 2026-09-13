//! Where one road meets opposing traffic: the across-road split both paint and arrows share.
use crate::style::Layer;
use tilecodec::mamaps::body::Body;

/// The across-road coordinate the opposing streams of one road feature meet at, in -1..=1.
///
/// The ribbon measures `t` from the left of the geometry's own direction, and the archive's
/// `forward` lanes are the ones running toward the feature's last point — the same direction. So
/// under right-hand traffic the forward lanes take the +1 side and the backward lanes the -1 side,
/// and under left-hand traffic they swap. Expressed as a fraction of the split's own total rather
/// than of [`tilecodec::mamaps::body::Feature::lane_count`], so a table that disagrees with the
/// lane tag still puts the line in the right *place*.
///
/// # It has to land on a lane boundary, not merely near one
///
/// `road_surface.frag` decides which boundary carries the centre line with
/// `abs(boundaryT - centreT) < 1.0 / lanes` — half a lane, and **strictly** less. So a value that
/// sits exactly halfway between two boundaries matches neither: the dividers either side both stay
/// dashed and the centre-line band paints down the middle of a lane. A flat 0.0 does exactly that
/// on any odd lane count, where the middle of the road *is* the middle of the centre lane.
///
/// So the unknown-split case does not push 0.0; it assumes the split a road with an odd lane count
/// actually carries — the extra lane going to the forward direction — and runs it through the same
/// arithmetic as a known one. On an even count that still comes out at 0.0, and it is what real
/// data gives anyway: a three-lane two-way road is tagged 2/1, not "centred".
///
/// # Its counterpart is the arrow fan
///
/// That no-table assumption is made a second time, independently, in [`arrow_meshes`](super::arrows::arrow_meshes) — which fans
/// each direction's arrows over the very boundary this line is painted on. **The two are
/// counterparts and have to be changed together.** If one starts assuming a different split and
/// the other does not, the arrows detach from the centre line on exactly the archives that carry
/// no table, which is the case with no third source to catch the disagreement.
pub(crate) fn split_t(tile: &Body, layer: &Layer, feature_index: usize, lanes: u8, left_hand: bool) -> f32 {
    let (forward, backward) = known_split(tile, layer.source_layer_id, feature_index)
        .unwrap_or_else(|| {
            let lanes = lanes.max(1) as u16;
            (lanes - lanes / 2, lanes / 2)
        });
    let near_kerb = if left_hand { forward } else { backward };
    2.0 * near_kerb as f32 / (forward + backward) as f32 - 1.0
}

/// The road's `(forward, backward)` lane split as the archive records it, or `None` where it does
/// not — no carriageway table, or an all-zero entry.
///
/// The single read of that table. [`split_t`] puts the centre line at the boundary it implies and
/// [`arrow_meshes`](super::arrows::arrow_meshes) fans each direction's arrows over its own side of that same boundary; reading
/// it once means the marking and the arrows cannot disagree about where the road divides. Where it
/// is absent the two synthesise a split separately instead, and those syntheses are counterparts
/// that have to be kept in step — see the note on [`split_t`].
pub(crate) fn known_split(tile: &Body, layer_id: u8, feature_index: usize) -> Option<(u16, u16)> {
    tile.feature_carriageway(layer_id, feature_index)
        .map(|shape| (shape.forward as u16, shape.backward as u16))
        .filter(|(forward, backward)| forward + backward > 0)
}
