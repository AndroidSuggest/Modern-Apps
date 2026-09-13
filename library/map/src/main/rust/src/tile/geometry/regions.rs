//! Region shapes for the selection mask, one mesh per region feature.
use super::convert::widen;
use super::mesh::RegionMesh;
use crate::tess::fill;
use tilecodec::mamaps::body::{Body, GEOM_POLYGON};

/// Tessellate this tile's `region_area` shapes, one mesh per feature.
///
/// Driven off the archive rather than the style: the mask is not a style layer, and giving it one
/// would mean the boundary line layer strokes these polygons' tile-edge segments into a grid
/// across the map — which is exactly what made an earlier attempt at region areas unusable.
pub(crate) fn region_meshes(tile: &Body, extent: u32, rings_validated: bool) -> Vec<RegionMesh> {
    let Some(kind) = crate::style::kind_id("region_area") else { return Vec::new() };
    let Some(source) = tile.layer(tilecodec::mamaps::dict::LAYER_BOUNDARIES) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for (feature_index, feature) in source.features.iter().enumerate() {
        if feature.kind != kind || feature.geom_type != GEOM_POLYGON {
            continue;
        }
        // No id means nothing could gather this piece together with the region's other tiles,
        // so it would mask one tile and leave the rest bright. Better to draw no mask at all.
        let Some(id) =
            tile.feature_id(tilecodec::mamaps::dict::LAYER_BOUNDARIES, feature_index)
        else {
            continue;
        };
        if id == tilecodec::mamaps::body::ID_NONE {
            continue;
        }
        let rings: Vec<Vec<(i32, i32)>> =
            source.parts_of(feature).iter().map(|part| widen(source.points(part))).collect();
        let mut vertices = Vec::new();
        let mut indices = Vec::new();
        fill::tessellate(&rings, extent, rings_validated, &mut vertices, &mut indices);
        if indices.is_empty() {
            continue;
        }
        // Exteriors only. `parts_of` yields the exterior first and its holes after, and stage C
        // has already made that ordering true of every polygon in the archive.
        let scale = 1.0 / extent as f32;
        let outer: Vec<Vec<(f32, f32)>> = source
            .parts_of(feature)
            .iter()
            .filter(|part| part.winding != tilecodec::mamaps::body::WINDING_HOLE)
            .map(|part| {
                source
                    .points(part)
                    .iter()
                    .map(|&(x, y)| (x as f32 * scale, y as f32 * scale))
                    .collect()
            })
            .collect();
        let area = outer.iter().map(|ring| ring_area(ring).abs()).sum();
        out.push(RegionMesh { id, vertices, indices, rings: outer, area, level: feature.kind_detail });
    }
    out
}

/// Twice the signed area of a closed ring, by the shoelace formula.
///
/// The factor of two is left in: this is only ever compared against other rings measured the
/// same way, so halving every term would change nothing.
fn ring_area(ring: &[(f32, f32)]) -> f32 {
    let mut sum = 0.0;
    for window in ring.windows(2) {
        sum += window[0].0 * window[1].1 - window[1].0 * window[0].1;
    }
    sum
}
