use super::slim::{BodyLayer, SlimBody, SlimInstance};
use crate::mamaps::body::{Body, BuildingAttrs, Carriageway, Feature, GEOM_LINE, GEOM_POINT, GEOM_POLYGON, ID_NONE, LaneTurns, Layer, NAME_NONE, Part, FLAG_DETAIL_NUMERIC};
use crate::mamaps::shared::{SHARED_FLAG_DETAIL_NUMERIC, SharedLogicalRow, SharedView};
use crate::proto::{Error, Result, err};

pub(crate) fn resolve_body(shared: &SharedView, slim: &SlimBody) -> Result<Body> {
    let mut names: Vec<String> = Vec::new();
    let mut layers = Vec::with_capacity(slim.layers.len());
    let mut ids: Vec<(u8, Vec<u64>)> = Vec::new();
    let mut turn_lanes: Vec<(u8, Vec<LaneTurns>)> = Vec::new();
    let mut buildings: Vec<(u8, Vec<BuildingAttrs>)> = Vec::new();
    let mut carriageways: Vec<(u8, Vec<Carriageway>)> = Vec::new();
    for body_layer in &slim.layers {
        // Full v7 layers pass through with their features, parts and arena verbatim.
        let slim_layer = match body_layer {
            BodyLayer::Full(full) => {
                for feature in &full.features {
                    if feature.name_idx != NAME_NONE {
                        return err(format!(
                            "a full layer {} in a slim body names string {}, but a slim body carries no name table",
                            full.layer_id,
                            feature.name_idx,
                        ));
                    }
                }
                layers.push(full.clone());
                continue;
            }
            BodyLayer::Slim(slim) => slim,
        };
        let mut features = Vec::with_capacity(slim_layer.instances.len());
        let mut parts: Vec<Part> = Vec::new();
        let mut coords: Vec<(i16, i16)> = Vec::new();
        let mut layer_ids: Vec<u64> = Vec::new();
        let mut layer_turns: Vec<LaneTurns> = Vec::new();
        let mut layer_buildings: Vec<BuildingAttrs> = Vec::new();
        let mut layer_carriageways: Vec<Carriageway> = Vec::new();
        for instance in &slim_layer.instances {
            // One binary search serves both the row and its parallel stable
            // id (`SharedView::ids` runs alongside `rows`); a miss is a
            // corrupt ref, refused rather than read as a neighbour's feature.
            let row_idx = shared
                .rows
                .binary_search_by_key(&instance.logical_id, |r| r.logical_id)
                .map_err(|_| {
                    Error(format!(
                        "a .mamaps slim instance names logical row {} of {}",
                        instance.logical_id,
                        shared.rows.len(),
                    ))
                })?;
            let row = &shared.rows[row_idx];
            // `view_bits` ride the ref for the renderer's clip/simplification
            // epoch and are not geometry: resolve passes them through
            // unchecked (a stale renderer refuses them; this reader assembles
            // the parts the instance names).
            let _ = instance.view_bits;
            // The 16-byte instance carries no styling copy — everything shared
            // lives in the row — so the check below pins join integrity: the
            // row found must be the row named, or a mis-pointed ref would
            // silently restyle a feature.
            check_row_matches_instance(row, instance)?;
            let name_idx = match shared.row_name(row) {
                None => NAME_NONE,
                Some(name) => {
                    let pos = match names.iter().position(|n| n == name) {
                        Some(i) => i + 1,
                        None => {
                            names.push(name.to_string());
                            names.len()
                        }
                    };
                    u16::try_from(pos).map_err(|_| {
                        Error(
                            "a .mamaps tile names more than 65535 distinct strings".to_string(),
                        )
                    })?
                }
            };
            let end = (instance.part_offset as usize)
                .checked_add(instance.part_count as usize)
                .filter(|end| *end <= slim_layer.parts.len())
                .ok_or_else(|| {
                    Error(
                        "a .mamaps slim instance indexes parts its layer does not carry"
                            .to_string(),
                    )
                })?;
            let parts_offset = parts.len() as u32;
            for part in &slim_layer.parts[instance.part_offset as usize..end] {
                let coord_end = (part.coord_start as usize)
                    .checked_add(part.point_count as usize)
                    .filter(|end| *end <= slim_layer.coords.len())
                    .ok_or_else(|| {
                        Error(
                            "a .mamaps slim part indexes points its layer does not carry"
                                .to_string(),
                        )
                    })?;
                coords.extend_from_slice(
                    &slim_layer.coords[part.coord_start as usize..coord_end],
                );
                parts.push(Part {
                    coord_start: coords.len() as u32 - part.point_count,
                    point_count: part.point_count,
                    winding: part.winding,
                });
            }
            features.push(Feature {
                kind: row.kind,
                kind_detail: row.kind_detail,
                // Slim layers are homogeneous — one geometry per layer, the way the tiler emits
                // them — so the type is a property of the layer, not the instance. A
                // heterogeneous layer stays full v7.
                geom_type: slim_layer_geom(slim_layer.layer_id)?,
                // Only the numeric-detail bit travels in the row today; tunnel/bridge/link/oneway,
                // lane counts and transit styling resolve as zero until the row grows fields for
                // them — which is why lane D keeps styled layers full in the meantime.
                flags: shared_row_flags(row.flags),
                name_idx,
                parts_offset,
                part_count: instance.part_count,
                transit_color: 0,
                transit_ordinal: 0,
                transit_lanes: 0,
                transit_taper: 0,
                lane_count: 0,
            });
            resolve_attrs(
                shared,
                row,
                row_idx,
                &mut layer_ids,
                &mut layer_turns,
                &mut layer_buildings,
                &mut layer_carriageways,
            )?;
        }
        let layer_id = slim_layer.layer_id;
        if layer_ids.iter().any(|id| *id != ID_NONE) {
            ids.push((layer_id, layer_ids));
        }
        if layer_turns.iter().any(|t| !t.is_empty()) {
            turn_lanes.push((layer_id, layer_turns));
        }
        if layer_buildings.iter().any(|a| *a != BuildingAttrs::default()) {
            buildings.push((layer_id, layer_buildings));
        }
        if layer_carriageways.iter().any(|c| !c.is_empty()) {
            carriageways.push((layer_id, layer_carriageways));
        }
        layers.push(Layer { layer_id, features, parts, coords });
    }
    Ok(Body {
        extent: slim.extent,
        layers,
        names,
        ids,
        turn_lanes,
        buildings,
        heightmap: slim.heightmap.clone(),
        carriageways,
        convention: slim.convention,
    })
}

/// The row and the ref must agree on the join.
///
/// The 16-byte instance carries no styling copy — kind, flags, lane count and transit all live in
/// the row — so there are no instance-inline fields left to compare. What remains is join
/// integrity: the row the lookup found must be the row the ref names, or a mis-pointed ref would
/// silently restyle a feature. (Via the binary-search lookup this always holds; the check pins the
/// invariant for whatever lookup comes next.)
pub(crate) fn check_row_matches_instance(row: &SharedLogicalRow, instance: &SlimInstance) -> Result<()> {
    if row.logical_id != instance.logical_id {
        return err(format!(
            "a .mamaps slim instance names logical row {} but resolved to row {}",
            instance.logical_id, row.logical_id,
        ));
    }
    Ok(())
}

/// A slim layer's geometry type.
///
/// Slim layers are homogeneous — one geometry per layer, the way the tiler emits them — so the
/// type is a property of the layer id, not the instance. A heterogeneous layer stays full v7,
/// where every feature keeps its inline type.
pub(crate) fn slim_layer_geom(layer_id: u8) -> Result<u8> {
    use crate::mamaps::dict::*;
    Ok(match layer_id {
        LAYER_EARTH | LAYER_WATER | LAYER_LANDCOVER | LAYER_LANDUSE | LAYER_BUILDINGS => {
            GEOM_POLYGON
        }
        LAYER_ROADS | LAYER_BOUNDARIES | LAYER_TRANSIT | LAYER_TRAFFIC | LAYER_JUNCTION => {
            GEOM_LINE
        }
        LAYER_PLACES | LAYER_POI => GEOM_POINT,
        other => return err(format!("a .mamaps slim layer has unknown layer id {other}")),
    })
}

/// A shared row's flags as v7 feature flags: today only the numeric-detail bit travels (the row's
/// bit 0 is the feature's [`FLAG_DETAIL_NUMERIC`]).
pub(crate) fn shared_row_flags(row_flags: u32) -> u8 {
    if row_flags & SHARED_FLAG_DETAIL_NUMERIC != 0 {
        FLAG_DETAIL_NUMERIC
    } else {
        0
    }
}

/// One row's attribute indices into the shared pools, plus its stable id.
///
/// Each index is 0 for the pool's default (lane A's reader treats a missing
/// pool as all-defaults, so an empty pool with index 0 reads as default
/// without special-casing); anything above a present pool's length is
/// corruption. The stable id comes from the row's own slot in the parallel
/// id vector — `ID_NONE` for junction-style rows with no stable identity —
/// so every junction still costs its slot rather than misaligning the rest.
#[allow(clippy::too_many_arguments)]
pub(crate) fn resolve_attrs(
    shared: &SharedView,
    row: &SharedLogicalRow,
    row_idx: usize,
    ids: &mut Vec<u64>,
    turn_lanes: &mut Vec<LaneTurns>,
    buildings: &mut Vec<BuildingAttrs>,
    carriageways: &mut Vec<Carriageway>,
) -> Result<()> {
    // `None` is the pool's default (index 0 reads as default even against an
    // empty pool, so a section that carries no pool at all needs no
    // special-casing); anything above a present pool's length is corruption.
    let idx = |pool_len: usize, idx: u32, what: &str| -> Result<Option<usize>> {
        if idx == 0 {
            return Ok(None);
        }
        let i = idx as usize;
        if i >= pool_len {
            return err(format!(
                "shared row {} names {what} {idx} of {pool_len}",
                row.logical_id,
            ));
        }
        Ok(Some(i))
    };
    buildings.push(
        match idx(shared.buildings.len(), row.building_idx, "building")? {
            None => BuildingAttrs::default(),
            Some(i) => shared.buildings[i].to_body(),
        },
    );
    carriageways.push(
        match idx(shared.carriageways.len(), row.carriageway_idx, "carriageway")? {
            None => Carriageway::default(),
            Some(i) => shared.carriageways[i].to_body(),
        },
    );
    turn_lanes.push(
        match idx(shared.lane_turns.len(), row.lane_turns_idx, "lane-turns")? {
            None => LaneTurns::default(),
            Some(i) => shared.lane_turns[i].to_body(),
        },
    );
    ids.push(if shared.ids.is_empty() {
        ID_NONE
    } else {
        *shared.ids.get(row_idx).ok_or_else(|| {
            Error(format!(
                "a shared id pool has {} id(s) for {} row(s)",
                shared.ids.len(),
                shared.rows.len(),
            ))
        })?
    });
    Ok(())
}
