use crate::style::Layer;
use crate::tess::text;
use crate::tile::geometry::ShapedLabel;
use crate::tile::glyph::{fonts_staged, Weight};
use crate::tile::sprite::Sprite;
use tilecodec::mamaps::body::{Body, Feature};

/// Shape one place label into a [`ShapedLabel`] candidate. `weight` follows the
/// layer's `medium` flag (country and big-city labels); the authored
/// `text-transform` is applied by [`text::shape`], so the shaped glyphs already
/// carry the codepoints that will be drawn and the metrics that go with them.
/// Returns `None` for empty shapes (no atlas, no point, unshapable string) - the
/// renderer skips those silently.
pub fn shape_label(
    layer: &Layer,
    tile: &Body,
    feature: &Feature,
    name: &str,
    extent: u32,
    layer_index: usize,
    feature_index: usize,
) -> Option<ShapedLabel> {
    if !fonts_staged() {
        return None;
    }
    let atlas = crate::tile::glyph::atlas();
    let weight = if layer.medium {
        Weight::Medium
    } else {
        Weight::Regular
    };
    // `text_max_width` is zero on every place layer, which is the single-line path and
    // therefore byte-identical to what `shape` alone used to produce.
    let lines = text::shape_wrapped(atlas, weight, name, layer.uppercase, layer.text_max_width);
    if lines.is_empty() {
        return None;
    }
    let total_advance = lines
        .iter()
        .fold(0.0f32, |wide, line| wide.max(line.advance));
    let anchor = tile_point(tile, feature, extent)?;
    Some(ShapedLabel {
        layer_index,
        anchor,
        // Task-17 pick: the display name rides with the label so the JNI
        // pickLabels path can return it without re-reading the tile body.
        name: name.to_string(),
        lines,
        total_advance,
        weight,
        rank: rank_for_layer(&layer.id),
        // Only the POI layers ask for an icon, and a kind the sheet has no picture for
        // (`townhall`) simply draws label-only — which is what MapLibre does with a
        // missing `icon-image`.
        sprite: if layer.icon {
            sprite_for(feature.kind)
        } else {
            None
        },
        // Places carry the tiler's 0–3 population rank as a NUMERIC detail
        // (see schema/places.rs); anything else is unranked.
        pop: if feature.flags & tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC != 0 {
            feature.kind_detail
        } else {
            0
        },
        // The feature's own kind, so a pick can say `cafe` where the layer only knows it
        // draws one of `poi-food`'s four.
        kind: feature.kind,
        // `feature_index` is the position in the *body* layer's feature vector, which is what
        // the id table is parallel to — not the position among the features this style layer
        // admitted. `ID_NONE` covers both "this layer has no id table" and "no OSM element".
        feature_id: tile
            .feature_id(layer.source_layer_id, feature_index)
            .unwrap_or(tilecodec::mamaps::body::ID_NONE),
        // A point label anchors one block; it does not follow a line.
        centreline: None,
    })
}

/// Shape one road/river line label into a curved [`ShapedLabel`] candidate.
///
/// The line half of [`shape_label`]: the name shapes into a single line (no wrapping — a curved
/// label is one run laid along the road), and the whole feature `centreline` in tile-local 0..1
/// rides on the label so the renderer can walk it per frame at the frame's text size (see
/// [`crate::tess::text::emit_curved`]). The [`anchor`](ShapedLabel::anchor) is the polyline's
/// midpoint, used only by the point-label collision/pick fallbacks until the segmented placer is
/// wired; the curved footprint proper comes from the per-glyph tangents.
///
/// Returns `None` when fonts are not staged, the name is unshapable, or the centreline has fewer
/// than two points — the renderer skips those silently, exactly as it does an empty point shape.
pub fn shape_line_label(
    layer: &Layer,
    tile: &Body,
    feature: &Feature,
    name: &str,
    layer_index: usize,
    feature_index: usize,
    centreline: Vec<(f32, f32)>,
) -> Option<ShapedLabel> {
    if !fonts_staged() {
        return None;
    }
    if centreline.len() < 2 {
        return None;
    }
    let atlas = crate::tile::glyph::atlas();
    let weight = if layer.medium {
        Weight::Medium
    } else {
        Weight::Regular
    };
    // A curved label is a single run — never wrapped — so `text_max_width` is ignored here.
    let lines = text::shape_wrapped(atlas, weight, name, layer.uppercase, 0.0);
    if lines.is_empty() {
        return None;
    }
    let total_advance = lines
        .iter()
        .fold(0.0f32, |wide, line| wide.max(line.advance));
    let anchor = polyline_midpoint(&centreline);
    Some(ShapedLabel {
        layer_index,
        anchor,
        name: name.to_string(),
        lines,
        total_advance,
        weight,
        rank: rank_for_layer(&layer.id),
        // A line label carries no population rank and no icon.
        pop: 0,
        sprite: None,
        kind: feature.kind,
        feature_id: tile
            .feature_id(layer.source_layer_id, feature_index)
            .unwrap_or(tilecodec::mamaps::body::ID_NONE),
        centreline: Some(centreline),
    })
}

/// The point half-way along a tile-local polyline by arc length — the curved label's nominal
/// anchor. Falls back to the first point on a degenerate (zero-length) line.
fn polyline_midpoint(pts: &[(f32, f32)]) -> (f32, f32) {
    let mut total = 0.0f32;
    for w in pts.windows(2) {
        total += ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
    }
    if total <= 0.0 {
        return *pts.first().unwrap_or(&(0.0, 0.0));
    }
    let half = total * 0.5;
    let mut acc = 0.0f32;
    for w in pts.windows(2) {
        let seg = ((w[1].0 - w[0].0).powi(2) + (w[1].1 - w[0].1).powi(2)).sqrt();
        if seg <= 0.0 {
            continue;
        }
        if acc + seg >= half {
            let t = (half - acc) / seg;
            return (
                w[0].0 + (w[1].0 - w[0].0) * t,
                w[0].1 + (w[1].1 - w[0].1) * t,
            );
        }
        acc += seg;
    }
    *pts.last().unwrap_or(&(0.0, 0.0))
}

/// Placement rank from the symbol layer id: country first, POI last.
/// Unknown ids sink (255) rather than winning collisions they were never
/// meant to enter.
pub(crate) fn rank_for_layer(id: &str) -> u8 {
    match id {
        "places-country" => 0,
        "places-region" => 1,
        "places-locality" => 2,
        "places-subplace" => 3,
        // One rank for all six POI colour groups: the split exists to give each group its
        // own `text-color` and nothing else, so a cafe must not beat a park at a collision
        // merely because its colour was authored later in the file. Without this they fall
        // through to `u8::MAX` and lose every collision to every place label — which at
        // z17, where places are sparse, would look almost right and be wrong.
        "poi-outdoor" | "poi-transport" | "poi-civic" | "poi-shop" | "poi-food" | "poi-culture" => {
            4
        }
        // Line labels below the point labels: a road or river name yields to a place or POI at a
        // collision, matching MapLibre's default `symbol-z-order`. Major roads above minor above
        // rivers, so a highway name wins over a side street and both over the waterway they cross.
        // The ids are the render-side symbol layers the build carries road/water names for (WS-E).
        "roads-label-major" => 5,
        "roads-label-minor" => 6,
        "waterway-label" => 7,
        _ => u8::MAX,
    }
}

/// The sprite a feature's `kind` names, if the sheet carries one.
///
/// The reference's `icon-image` is
/// `["match", ["get", "kind"], "station", "train_station", ["get", "kind"]]` — one rename,
/// and otherwise the kind itself. That whole expression is these three lines, which is why
/// the style carries a boolean rather than a per-layer icon name.
pub(crate) fn sprite_for(kind: u16) -> Option<Sprite> {
    use tilecodec::mamaps::dict;
    // `kind` is a 1-based id into the interned table; 0 is `dict::NONE`.
    let name = dict::KINDS.get(usize::from(kind).checked_sub(1)?)?;
    let name = if *name == "station" {
        "train_station"
    } else {
        *name
    };
    crate::tile::sprite::atlas().get(name)
}

/// The feature's point in tile-local 0..1. Places are single-point features; the
/// first point of the first part is the anchor.
fn tile_point(tile: &Body, feature: &Feature, extent: u32) -> Option<(f32, f32)> {
    let source = tile.layer(feature_source_layer(tile, feature))?;
    let parts = source.parts_of(feature);
    let first = parts.first()?;
    let (x, y) = *source.points(first).first()?;
    Some((x as f32 / extent as f32, y as f32 / extent as f32))
}

/// The layer id of the layer containing `feature`. The caller already resolved it
/// to call `matches_feature`; re-finding by scan keeps this module from threading
/// the id through. Places live in exactly one layer per tile.
fn feature_source_layer(tile: &Body, feature: &Feature) -> u8 {
    use tilecodec::mamaps::dict;
    for layer in &tile.layers {
        let start = layer.features.as_ptr() as usize;
        let end = start + layer.features.len() * std::mem::size_of::<Feature>();
        let addr = feature as *const Feature as usize;
        if addr >= start && addr < end {
            return layer.layer_id;
        }
        let _ = dict::LAYER_PLACES;
    }
    dict::LAYER_PLACES
}
