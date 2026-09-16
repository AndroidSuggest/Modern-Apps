use crate::camera::Camera;

use super::coverage::visible;

use super::tile_id::TileId;

/// How many levels of descendant to *keep* when they are already resident.
///
/// The mirror of [`ANCESTOR_DEPTH`], and the reason zooming out no longer blanks the map. An
/// ancestor stands in while a finer tile loads; nothing stood in while a *coarser* one loaded,
/// because the tiles on screen were the new tile's descendants and were evicted the moment the
/// camera moved. The map then drew nothing for as long as the fetch took.
///
/// Two rather than four: a descendant set grows as 4^depth where an ancestor set is linear, and
/// two levels already covers a 4x zoom-out — more than one pinch produces. This is a bound on GPU
/// memory, not a lookahead.
pub const DESCENDANT_DEPTH: u8 = 2;

/// Whether a resident tile is worth keeping as a stand-in for a visible one that has not arrived.
///
/// Deliberately a predicate over what is *already resident*, rather than something
/// [`resident_set`] could enumerate: naming every descendant means 4^[`DESCENDANT_DEPTH`] keys per
/// visible tile, most of which were never fetched, and the keep list is asserted to stay
/// proportional to the viewport.
pub fn stands_in_for_visible(key: u64, visible: &[TileId], depth: u8) -> bool {
    let tile = TileId::from_key(key);
    visible.iter().any(|v| tile.descends_from(v, depth))
}

/// How many levels of ancestor to *keep* when they are already resident.
///
/// Four covers a 16x zoom jump, which is more than a pinch produces in one gesture.
pub const ANCESTOR_DEPTH: u8 = 4;

/// The tiles worth **keeping resident**: the visible ones, plus any ancestor of a visible
/// tile.
///
/// This is deliberately *not* the fetch list. An ancestor is a fallback for a tile that has
/// not arrived yet, so it is only useful if we **already have it** — fetching one costs a
/// round trip to draw a blurrier version of a tile that is being fetched anyway. Doing that
/// tripled the network for a screenful (24 fetches instead of 12) and, because ancestors sort
/// first, spent all that latency *before* requesting the tiles the user is actually looking
/// at. MapLibre renders the parent it happens to have cached; it does not go and fetch one.
///
/// Nor is it the whole keep list. Descendants are the other half of the fallback — see
/// [`stands_in_for_visible`] — and cannot be named here without enumerating tiles that were
/// never fetched.
///
/// Ancestors are returned **before** the tiles they stand in for. The renderer sorts by zoom
/// before drawing, so this is for the residency cap rather than for draw order: coarser tiles
/// are the cheaper, more widely useful stand-ins and should be the last thing evicted.
pub fn resident_set(camera: &Camera, min_zoom: u8, max_zoom: u8) -> Vec<TileId> {
    let exact = visible(camera, min_zoom, max_zoom);
    if exact.is_empty() {
        return exact;
    }
    let mut out: Vec<TileId> = Vec::with_capacity(exact.len() * 2);
    let mut seen = std::collections::HashSet::new();

    // Coarsest first, so the draw order is ancestors under descendants.
    for level in (1..=ANCESTOR_DEPTH).rev() {
        for tile in &exact {
            if tile.z < min_zoom + level {
                continue;
            }
            let ancestor = TileId {
                z: tile.z - level,
                x: tile.x >> level,
                y: tile.y >> level,
            };
            if seen.insert(ancestor.key()) {
                out.push(ancestor);
            }
        }
    }
    for tile in exact {
        if seen.insert(tile.key()) {
            out.push(tile);
        }
    }
    out
}
