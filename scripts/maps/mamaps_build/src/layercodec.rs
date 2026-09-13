//! Lane C: per-layer codecs, behind `--shared-table`.
//!
//! One dispatch table over the twelve layer ids, plus the one codec this lane
//! owns end to end (traffic segment-delta). Everything here is a pure function
//! of tile-local inputs: the tiler calls in, the shared builder (lane A pools)
//! and the body writer stay exactly as they are.
//!
//! # The three codec kinds
//!
//! * `traffic` — **segment-delta**: one shared base polyline per edge,
//!   interned once in lane A's geometry pool, plus one keep-mask per segment
//!   row selecting its vertices. The packed `component_id` rides the id-runs
//!   pool as a varint (tiny against the body's fixed 8 B per id).
//! * `buildings`, `junction` — **slim-or-full**: the body carries a 16 B slim
//!   ref or a full feature, per lane B's mixed-body flag. Lane B has not
//!   landed, so [`MIXED_BODIES`] is false and every body is full; the decision
//!   point is [`body_mode`], read in the encode path so the flip compiles
//!   where it will branch.
//! * Everything else (`earth`, `water`, and all remaining layers) —
//!   **passthrough**: the v7 generalisation path, untouched. It is already
//!   optimal and this lane changes nothing about it.
//!
//! # What this lane does not touch
//!
//! * The traffic/junction coalesce opt-outs in `encode_batch`: segment
//!   identity is what the codec keys on, so merging would destroy its input.
//! * `header` / `read` / `write` / `store`, and everything under `library/`:
//!   codec bytes ride the shared section's existing pools (rows, id runs,
//!   lane A's kind-8 geometry), which the writer already serialises.
//! * Bodies: until lane B lands, every tile carries full v7 features and a
//!   `--shared-table` build differs from v7 only by the appended section.
//!
//! # Tile-locality
//!
//! A base polyline is stitched from the segments of one edge *as clipped into
//! this tile*. Segments of the same edge in another tile stitch a different
//! base and intern a different geometry — exactly like junctions, whose
//! tile-local clips hash differently per tile: no cross-tile dedup is
//! claimed. An edge whose tile-local segments do not chain (a gap where the
//! clip dropped a piece) falls back to plain shared rows for the whole edge
//! in this tile, rather than to a base that would misalign a mask.

use crate::schema::traffic::unpack_component_id;

/// How one layer's tile features are carried when `--shared-table` is on.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LayerCodec {
    /// The v7 path, byte for byte: no intents beyond what lane E already
    /// emits (and for most layers, none at all).
    PassthroughV7,
    /// Shared base polyline per edge plus per-segment keep-masks.
    TrafficDelta,
    /// A 16 B slim ref or a full feature, per [`MIXED_BODIES`].
    SlimOrFull,
}

/// Which codec carries `layer` (a `dict::LAYER_*` id).
///
/// Twelve arms, one per entry of [`tilecodec::mamaps::dict::LAYERS`]: an
/// appended layer is a compile error here rather than a silent passthrough,
/// because `match` has no wildcard.
pub fn codec_for(layer: u8) -> LayerCodec {
    use tilecodec::mamaps::dict::{
        LAYER_BOUNDARIES, LAYER_BUILDINGS, LAYER_EARTH, LAYER_JUNCTION, LAYER_LANDCOVER,
        LAYER_LANDUSE, LAYER_PLACES, LAYER_POI, LAYER_ROADS, LAYER_TRAFFIC, LAYER_TRANSIT,
        LAYER_WATER,
    };
    match layer {
        LAYER_EARTH => LayerCodec::PassthroughV7,
        LAYER_WATER => LayerCodec::PassthroughV7,
        LAYER_LANDCOVER => LayerCodec::PassthroughV7,
        LAYER_LANDUSE => LayerCodec::PassthroughV7,
        LAYER_ROADS => LayerCodec::PassthroughV7,
        LAYER_BOUNDARIES => LayerCodec::PassthroughV7,
        LAYER_BUILDINGS => LayerCodec::SlimOrFull,
        LAYER_PLACES => LayerCodec::PassthroughV7,
        LAYER_POI => LayerCodec::PassthroughV7,
        LAYER_TRANSIT => LayerCodec::PassthroughV7,
        LAYER_TRAFFIC => LayerCodec::TrafficDelta,
        LAYER_JUNCTION => LayerCodec::SlimOrFull,
        // No wildcard: a thirteenth layer must add its arm above, and the
        // `every_layer_has_a_codec` test pins the twelve. This arm is the
        // compiler's exhaustiveness receipt, unreachable while `LAYERS` has
        // twelve entries.
        12..=u8::MAX => panic!("layer {layer} has no lane C codec: add its arm to codec_for"),
    }
}

/// Whether one tile feature rides its body slim or full.
///
/// Full until lane B's mixed-body wire lands; the flag lives here (not in
/// the tiler) so the flip is one const and every call site already reads it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BodyMode {
    Full,
    Slim,
}

/// Lane B's mixed-body switch. **True: the 16 B slim wire landed** (lane B's
/// `encode_slim_instance`/`assemble_slim_payload` in `body.rs` plus the mixed
/// parse + resolve in `read.rs`), so slim emission writes bytes a reader
/// resolves.
pub const MIXED_BODIES: bool = true;

/// Slim or full for one feature of `layer`, given the build's `--shared-table`
/// flag. Only `buildings`/`junction` can ever answer slim, only with the flag
/// on, and only once [`MIXED_BODIES`] flips: every other combination is full,
///
/// which is what keeps a v7 build (flag off) all-full by construction.
pub fn body_mode(layer: u8, shared_table: bool) -> BodyMode {
    match codec_for(layer) {
        LayerCodec::SlimOrFull if shared_table && MIXED_BODIES => BodyMode::Slim,
        _ => BodyMode::Full,
    }
}

/// One traffic segment's share of its edge's codec: the stitched base (to
/// intern once per edge per tile) and the mask selecting this segment's
/// vertices within it (to emit per row per zoom).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TrafficCodec {
    pub base: Vec<(i16, i16)>,
    pub mask: Vec<bool>,
}

/// This segment's edge and position within it, from its packed component id.
pub fn traffic_edge_seg(stable_id: u64) -> (u64, u32) {
    unpack_component_id(stable_id)
}

/// Stitch one edge's tile-local segments into its base polyline.
///
/// `segments` is `(seg_index, first_point, last_point)` per segment, in any
/// order. Sorted by index, each segment must start where the previous ended;
/// anything else — a duplicate index, a gap, an empty input — is `None`, and
/// the caller falls back to plain shared rows for the edge.
pub fn stitch_traffic_base(
    segments: &[(u32, (i16, i16), (i16, i16))],
) -> Option<Vec<(i16, i16)>> {
    if segments.is_empty() {
        return None;
    }
    let mut ordered = segments.to_vec();
    ordered.sort_by_key(|(seg, _, _)| *seg);
    if ordered.windows(2).any(|w| w[0].0 >= w[1].0) {
        return None;
    }
    let mut base = Vec::with_capacity(ordered.len() + 1);
    base.push(ordered[0].1);
    for (_, first, last) in &ordered {
        if *first != *base.last().expect("a non-empty base has a last point") {
            return None;
        }
        base.push(*last);
    }
    Some(base)
}

/// The keep-mask selecting `seg`'s vertices within `base`.
///
/// The first window of `base` equalling the whole segment wins; no window is
/// `None`, and the caller falls back to plain rows. Windows (not index
/// arithmetic) because a tile clip can drop an edge's middle, so a segment's
/// position in the base is looked up, never assumed.
pub fn mask_for_segment(
    base: &[(i16, i16)],
    seg: &[(i16, i16)],
) -> Option<Vec<bool>> {
    if seg.is_empty() || seg.len() > base.len() {
        return None;
    }
    let at = base.windows(seg.len()).position(|w| w == seg)?;
    let mut mask = vec![false; base.len()];
    for i in at..at + seg.len() {
        mask[i] = true;
    }
    Some(mask)
}

/// Apply a keep-mask: the decode side, and what the tests round-trip
/// through. A mask the encoder refused to produce decodes to nothing.
pub fn apply_mask(base: &[(i16, i16)], mask: &[bool]) -> Vec<(i16, i16)> {
    base.iter().zip(mask.iter()).filter(|(_, keep)| **keep).map(|(pt, _)| *pt).collect()
}

/// Resolve one tile's traffic codec attachments, in sighting order.
///
/// `sightings` is `(stable_id, tile-local points)` per traffic intent, in the
/// order the drain will intern them. Returns one entry per sighting: `Some`
/// when the sighting's edge stitched cleanly and the sighting's points were
/// found in the base, `None` (plain shared row, no geometry) otherwise. Order
/// is preserved exactly, so the caller zips the result back onto its intents
/// and slim refs stay tile-major.
pub fn resolve_traffic_codecs(
    sightings: &[(u64, Vec<(i16, i16)>)],
) -> Vec<Option<TrafficCodec>> {
    use tilecodec::mamaps::body::ID_NONE;
    let mut out: Vec<Option<TrafficCodec>> = sightings.iter().map(|_| None).collect();
    // Edges in first-sighting order (a `BTreeMap` would sort by edge id,
    // which is equally deterministic; sighting order keeps the base stitch
    // adjacent to the intents it serves).
    let mut edges: Vec<(u64, Vec<usize>)> = Vec::new();
    for (at, (stable_id, _)) in sightings.iter().enumerate() {
        if *stable_id == ID_NONE {
            continue;
        }
        let (edge, _) = traffic_edge_seg(*stable_id);
        match edges.iter_mut().find(|(e, _)| *e == edge) {
            Some((_, members)) => members.push(at),
            None => edges.push((edge, vec![at])),
        }
    }
    for (_, members) in &edges {
        let mut ends: Vec<(u32, (i16, i16), (i16, i16))> = Vec::with_capacity(members.len());
        let mut whole = true;
        for at in members {
            let (stable_id, points) = &sightings[*at];
            let (_, seg) = traffic_edge_seg(*stable_id);
            let (Some(first), Some(last)) = (points.first(), points.last()) else {
                whole = false;
                break;
            };
            ends.push((seg, *first, *last));
        }
        let Some(base) = whole.then(|| stitch_traffic_base(&ends)).flatten() else {
            continue;
        };
        for at in members {
            let mask = mask_for_segment(&base, &sightings[*at].1);
            out[*at] = mask.map(|mask| TrafficCodec { base: base.clone(), mask });
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Fail-watch convention (this file): each test pins one wire fact as a
    /// literal, so a revert quotes its failure (`left` vs `right`) rather
    /// than a vague mismatch. Revert → quote the failure → restore.

    /// The dispatch table, all twelve layers as literals: traffic alone
    /// deltas, buildings/junction go slim-or-full, everything else rides v7.
    #[test]
    fn every_layer_has_a_codec_and_only_traffic_deltas() {
        use tilecodec::mamaps::dict::*;
        assert_eq!(LAYERS.len(), 12, "a thirteenth layer must add its arm here");
        let cases: [(u8, LayerCodec); 12] = [
            (LAYER_EARTH, LayerCodec::PassthroughV7),
            (LAYER_WATER, LayerCodec::PassthroughV7),
            (LAYER_LANDCOVER, LayerCodec::PassthroughV7),
            (LAYER_LANDUSE, LayerCodec::PassthroughV7),
            (LAYER_ROADS, LayerCodec::PassthroughV7),
            (LAYER_BOUNDARIES, LayerCodec::PassthroughV7),
            (LAYER_BUILDINGS, LayerCodec::SlimOrFull),
            (LAYER_PLACES, LayerCodec::PassthroughV7),
            (LAYER_POI, LayerCodec::PassthroughV7),
            (LAYER_TRANSIT, LayerCodec::PassthroughV7),
            (LAYER_TRAFFIC, LayerCodec::TrafficDelta),
            (LAYER_JUNCTION, LayerCodec::SlimOrFull),
        ];
        for (layer, want) in cases {
            assert_eq!(codec_for(layer), want, "layer {layer}");
        }
    }

    /// Without `--shared-table` every layer of every build is full: v7 by
    /// construction, whatever lane B later flips.
    #[test]
    fn a_v7_build_is_all_full_bodies() {
        for layer in 0..tilecodec::mamaps::dict::LAYERS.len() as u8 {
            assert_eq!(body_mode(layer, false), BodyMode::Full, "layer {layer}");
        }
    }

    /// With the flag on, buildings and junction answer slim: the wire landed
    /// (lane B's `encode_slim_instance`/`assemble_slim_payload` + mixed parse
    /// in `read.rs`), so the dispatch means it. The tiler still keeps a layer
    /// full unless every feature has a shared row (see
    /// `shared_buildings_and_junction_stay_full_v7`); v7 builds stay all-full
    /// by construction.
    #[test]
    fn a_shared_build_stays_full_until_lane_b_lands() {
        use tilecodec::mamaps::dict::*;
        assert!(MIXED_BODIES, "lane B landed: this test now pins slim dispatch");
        for layer in [LAYER_BUILDINGS, LAYER_JUNCTION] {
            assert_eq!(body_mode(layer, true), BodyMode::Slim, "layer {layer}");
        }
        // And the passthrough layers never consult the flag at all.
        for layer in [LAYER_EARTH, LAYER_WATER, LAYER_TRAFFIC, LAYER_ROADS] {
            assert_eq!(body_mode(layer, true), BodyMode::Full, "layer {layer}");
        }
    }

    /// Three chained segments stitch to one four-vertex base, whatever order
    /// they arrive in.
    #[test]
    fn contiguous_segments_stitch_into_one_base() {
        let segs = [(1u32, (10i16, 0i16), (20, 0)), (0, (0, 0), (10, 0)), (2, (20, 0), (30, 0))];
        assert_eq!(
            stitch_traffic_base(&segs),
            Some(vec![(0i16, 0i16), (10, 0), (20, 0), (30, 0)]),
        );
    }

    /// A gap, a duplicate index and an empty edge all refuse the stitch: the
    /// tiler falls back to plain shared rows rather than a misaligned base.
    #[test]
    fn a_gap_in_an_edge_falls_back_to_plain_rows() {
        // Missing seg 1: seg 2 starts where nothing ended.
        assert_eq!(
            stitch_traffic_base(&[(0u32, (0i16, 0i16), (10, 0)), (2, (20, 0), (30, 0))]),
            None,
        );
        // Two sightings of seg 0 (a duplicated feature, not a chain).
        assert_eq!(
            stitch_traffic_base(&[(0u32, (0i16, 0i16), (10, 0)), (0, (0, 0), (10, 0))]),
            None,
        );
        let empty: [(u32, (i16, i16), (i16, i16)); 0] = [];
        assert_eq!(stitch_traffic_base(&empty), None);
    }

    /// Each mask selects exactly its segment's vertices, and a foreign shape
    /// selects nothing.
    #[test]
    fn masks_select_exactly_their_segment() {
        let base = vec![(0i16, 0i16), (10, 0), (20, 0), (30, 0)];
        assert_eq!(
            mask_for_segment(&base, &[(10i16, 0i16), (20, 0)]),
            Some(vec![false, true, true, false]),
        );
        assert_eq!(mask_for_segment(&base, &[(99i16, 99i16), (100, 99)]), None);
        assert_eq!(mask_for_segment(&base, &[]), None);
    }

    /// Masks round-trip: applying each segment's mask to the base returns the
    /// segment's own vertices, which is the decode contract.
    #[test]
    fn applying_a_mask_returns_the_segments_vertices() {
        let base = vec![(0i16, 0i16), (10, 5), (20, 0)];
        for seg in [[(0i16, 0i16), (10, 5)], [(10, 5), (20, 0)]] {
            let mask = mask_for_segment(&base, &seg).expect("a base window");
            assert_eq!(apply_mask(&base, &mask), seg);
        }
    }

    /// The id side of the codec: eight packed component ids ride the id-runs
    /// pool as varints in a fraction of the body's 64 fixed bytes, and decode
    /// back exactly.
    #[test]
    fn component_ids_ride_varint_id_runs_compactly() {
        use crate::schema::traffic::pack_component_id;
        use tilecodec::mamaps::shared::{decode_id_runs, encode_id_runs};
        let ids: Vec<u64> = (0..8u32).map(|seg| pack_component_id(7, seg)).collect();
        let wire = encode_id_runs(&ids).expect("eight small deltas encode");
        assert!(
            wire.len() < 8 * 8,
            "id runs are {} bytes against 64 fixed: {wire:?}",
            wire.len(),
        );
        assert_eq!(decode_id_runs(&wire, ids.len()).expect("decode"), ids);
    }

    /// Two interleaved edges resolve independently, in sighting order: each
    /// sighting keeps its position and carries its own edge's base.
    #[test]
    fn resolve_groups_by_edge_in_sighting_order() {
        use crate::schema::traffic::pack_component_id;
        let sightings = vec![
            (pack_component_id(7, 0), vec![(0i16, 0i16), (10, 0)]),
            (pack_component_id(9, 0), vec![(50i16, 50i16), (60, 50)]),
            (pack_component_id(7, 1), vec![(10i16, 0i16), (20, 0)]),
            (pack_component_id(9, 1), vec![(60i16, 50i16), (70, 50)]),
        ];
        let resolved = resolve_traffic_codecs(&sightings);
        assert_eq!(resolved.len(), 4, "one entry per sighting, in order");
        for (at, codec) in resolved.iter().enumerate() {
            let codec = codec.as_ref().expect("both edges stitch");
            assert_eq!(apply_mask(&codec.base, &codec.mask), sightings[at].1);
        }
        assert_eq!(
            resolved[0].as_ref().expect("edge 7").base,
            vec![(0i16, 0i16), (10, 0), (20, 0)],
        );
        assert_eq!(
            resolved[1].as_ref().expect("edge 9").base,
            vec![(50i16, 50i16), (60, 50), (70, 50)],
        );
    }

    /// Degenerate sightings resolve to nothing: no stable id, no points, or
    /// an edge that cannot stitch all poison only their own entries.
    #[test]
    fn degenerate_sightings_resolve_to_none() {
        use crate::schema::traffic::pack_component_id;
        use tilecodec::mamaps::body::ID_NONE;
        let sightings = vec![
            (ID_NONE, vec![(0i16, 0i16), (10, 0)]),
            (pack_component_id(7, 0), vec![]),
            (pack_component_id(8, 0), vec![(0i16, 0i16), (10, 0)]),
            (pack_component_id(8, 2), vec![(20i16, 0i16), (30, 0)]),
        ];
        let resolved = resolve_traffic_codecs(&sightings);
        assert_eq!(resolved, vec![None, None, None, None]);
    }
}
