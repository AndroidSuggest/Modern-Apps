//! Per-lane turn arrows for one tile, from the turn-lane table.
use super::mesh::ROAD_LANE_MIN_ZOOM;
use super::split::known_split;
use crate::tile::arrow::{self, ArrowInstance};
use crate::tile::select::ANCESTOR_DEPTH;
use crate::tile::taper;
use tilecodec::mamaps::body::{Body, GEOM_LINE};
use tilecodec::mamaps::dict::LAYER_ROADS;

/// The per-lane turn arrows for this tile, from the archive's turn-lane side table.
///
/// One arrow per marked lane, at each turn-tagged road's junction end (and start, for backward
/// lanes). Gated to [`ROAD_LANE_MIN_ZOOM`] like the carriageway, over the same
/// [`ANCESTOR_DEPTH`] window the layer loop uses — a tile stands in for the levels below it, and
/// the archive stops at z14 while arrows are drawn from z16, so a z14 tile must build arrows it
/// will not draw itself.
/// Empty on any tile with no `turn:lanes` (no turn-lane table), which is nearly all.
///
/// The lane split and the driving convention go in alongside the geometry because a direction's
/// lanes occupy one *half* of the carriageway: without them the fan is centred on the road and
/// every arrow on a two-way sits in the oncoming lanes. `left_hand` is threaded in from the caller
/// rather than read again here, so the arrows and the carriageway split cannot disagree about which
/// side the forward lanes are on, and the split itself comes from [`known_split`](super::split::known_split) — the same read
/// [`split_t`](super::split::split_t) places the centre line from — for the same reason.
pub(crate) fn arrow_meshes(tile: &Body, z: u8, left_hand: bool) -> Vec<ArrowInstance> {
    if z.saturating_add(ANCESTOR_DEPTH) < ROAD_LANE_MIN_ZOOM {
        return Vec::new();
    }
    let Some(source) = tile.layer(LAYER_ROADS) else { return Vec::new() };
    let mut out = Vec::new();
    for (index, feature) in source.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE {
            continue;
        }
        let Some(turns) = tile.feature_turns(LAYER_ROADS, index) else { continue };
        if turns.is_empty() {
            continue;
        }
        // The feature's whole centreline in tile-local 0..1 (its parts joined in order; a
        // coalesced road is usually one part). Normalised here so the renderer places arrows with
        // the tile's `tileToClip` alone, needing no extent — arrows sit at the ends, so the
        // concatenation is what puts forward at the junction and backward at the start.
        let extent = tile.extent.max(1) as f32;
        let mut line: Vec<(f32, f32)> = Vec::new();
        for part in source.parts_of(feature) {
            for &(x, y) in source.points(part) {
                line.push((x as f32 / extent, y as f32 / extent));
            }
        }
        // How the road divides, from the same table the centre line is placed from, so an arrow
        // and the marking beside it cannot disagree about which lanes belong to which direction.
        // Absent that, this synthesises a split and so does `split_t`: the two are counterparts
        // and must be changed together, or the arrows come away from the centre line on precisely
        // the archives that carry no table. They agree on every two-way road. They differ only for
        // a one-way — all lanes forward here, an even division there — and that is harmless solely
        // because a one-way carriageway draws no centre line for the split to be wrong about.
        // Not read off the mask lists, which describe only the lanes that carry a turn indication.
        let oneway = feature.is_oneway();
        let lanes = taper::carriageway_lanes(feature.lane_count, oneway);
        let lanes_each_way = known_split(tile, LAYER_ROADS, index).unwrap_or_else(|| {
            let lanes = u16::from(lanes);
            if oneway {
                (lanes, 0)
            } else {
                (lanes - lanes / 2, lanes / 2)
            }
        });
        let lanes_each_way =
            (lanes_each_way.0.min(u8::MAX.into()) as u8, lanes_each_way.1.min(u8::MAX.into()) as u8);
        out.extend(arrow::place_arrows(&line, turns, lanes_each_way, left_hand));
    }
    out
}
