//! Live-traffic component segments for one tile, one mesh per id.
use super::convert::flatten;
use super::mesh::{TrafficMesh, TRAFFIC_MIN_ZOOM};
use super::terrain::drape_vertices;
use crate::style::LayerToggles;
use crate::tess::stroke;
use crate::tile::select::ANCESTOR_DEPTH;
use tilecodec::mamaps::body::{Body, GEOM_LINE};
use tilecodec::mamaps::dict::LAYER_TRAFFIC;

/// Tessellate this tile's live-traffic component segments, one mesh per feature.
///
/// Gated on the traffic toggle so leaving it off costs nothing — the same tessellation-time
/// gate the optional style layers use, so toggling traffic re-tessellates the resident set
/// once (through the generation counter) and then costs nothing per frame.
///
/// Each feature of the traffic layer is one component segment carrying its `component_id` in
/// the layer's id side-table ([`Body::feature_id`]). Every segment becomes its own
/// [`TrafficMesh`](super::mesh::TrafficMesh): colour is keyed by id and resolved at draw, so a per-feature mesh is what
/// lets a new speed reading recolour without re-tessellating. There is no per-colour sub-mesh
/// split (as transit does) because the colour is not known here — only the id is.
///
/// A feature with no id (`None`, meaning the layer carries no id table, or [`ID_NONE`]) is
/// skipped: without a stable id nothing could ever colour it, so drawing it would only ever
/// paint the neutral no-data look over a road that is already drawn by the basemap.
pub(crate) fn traffic_meshes(
    tile: &Body,
    extent: u32,
    z: u8,
    toggles: LayerToggles,
    ground_width_m: f64,
) -> Vec<TrafficMesh> {
    if !toggles.traffic {
        return Vec::new();
    }
    // A tile stands in for up to ANCESTOR_DEPTH levels below it, so build the overlay whenever
    // any of those levels reaches the traffic floor — matching how the layer loop widens its
    // zoom window. A tile too coarse for traffic simply carries no traffic layer anyway.
    if z.saturating_add(ANCESTOR_DEPTH) < TRAFFIC_MIN_ZOOM {
        return Vec::new();
    }
    let Some(source) = tile.layer(LAYER_TRAFFIC) else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for (feature_index, feature) in source.features.iter().enumerate() {
        if feature.geom_type != GEOM_LINE {
            continue;
        }
        let Some(id) = tile.feature_id(LAYER_TRAFFIC, feature_index) else {
            continue;
        };
        if id == tilecodec::mamaps::body::ID_NONE {
            continue;
        }
        let mut vertices = Vec::new();
        let mut indices = Vec::new();
        for part in source.parts_of(feature) {
            let flat = flatten(source.points(part));
            // Never gapped: a traffic segment is one solid band, not a road casing.
            stroke::stroke(&flat, extent, false, &mut vertices, &mut indices);
        }
        if indices.is_empty() {
            continue;
        }
        // Drape the segment onto the relief where the tile carries a heightmap, so the
        // traffic overlay follows the same ground its road does; without one the
        // tessellator's z=0 stands and output is unchanged.
        if tile.heightmap.is_some() {
            drape_vertices(
                &mut vertices,
                stroke::FLOATS_PER_VERTEX,
                7,
                &tile.heightmap,
                ground_width_m,
            );
        }
        out.push(TrafficMesh {
            id,
            vertices,
            indices,
        });
    }
    out
}
