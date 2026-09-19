/// Append one feature's tile-local geometry to a layer. Returns `(features, points)` added.
///
/// A polygon becomes **one feature per ring group**, so a feature's parts are exactly one exterior
/// and its holes — which is what the tessellator wants and what makes "one outer per feature" an
/// invariant worth stating.
///
/// A point becomes **one feature per point**, each with its own part and its own name: labels are
/// independent features that happen to share a tile, not a multi-point geometry, because the
/// renderer draws (and collides) them one at a time.
fn push(entry: &mut ChunkEntry, feature: &Feature, geometry: &IntGeometry) -> (u64, u64) {
    let layer = &mut entry.layer;
    let class = &feature.class;
    // `places`, `poi` and `boundaries` have an id table. Pushed in every branch rather than only
    // in the point one so the two vectors cannot drift apart - the table is indexed by feature
    // position, so a feature with no id of its own still needs its `ID_NONE` entry.
    let track_ids = crate::extract::layer_tracks_ids(class.layer);
    // The `buildings` layer carries an S3DB attribute side table, dense-parallel to its features
    // exactly as the id table is — so every building feature pushes an entry (a default one when
    // the building had no S3DB tags), in every branch, so the two vectors cannot drift.
    let track_buildings = class.layer == tilecodec::mamaps::dict::LAYER_BUILDINGS;
    // `roads` and `landtype` line features carry a display name for curved labels; it is
    // interned into the tile's name table like a point label's, at no format cost.
    let name_line_layer = matches!(
        class.layer,
        tilecodec::mamaps::dict::LAYER_ROADS | tilecodec::mamaps::dict::LAYER_LANDTYPE
    );
    let mut added = (0u64, 0u64);
    match geometry {
        IntGeometry::Polygons(polygons) => {
            // Below a few square pixels a polygon is a speck rather than detail, and there are
            // millions of them. Measured **after** clipping, on the shape that would actually be
            // drawn, so a large park clipped to a sliver of one tile is kept where it is big and
            // dropped where it is not.
            let floor = crate::schema::landtype::min_area_units(class.min_area_px, EXTENT);
            for rings in polygons {
                // A ring needs three distinct points plus the closing one; anything less bounds no
                // area. Filtered *before* anything is appended, because a part and its points have
                // to be committed together — the encoder requires the parts to tile the arena
                // exactly, so half-appending a group and rolling it back would leave orphaned
                // coordinates.
                let keep: Vec<(usize, &Vec<(i32, i32)>)> =
                    rings.iter().enumerate().filter(|(_, ring)| ring.len() >= 4).collect();
                // If the exterior did not survive, what is left is a hole with nothing to be a hole
                // in, which would paint as the inverse of the shape.
                if !keep.first().is_some_and(|(index, _)| *index == 0) {
                    continue;
                }
                if floor > 0.0 && ring_area(keep[0].1) < floor {
                    continue;
                }
                let parts_offset = layer.parts.len() as u32;
                for (index, ring) in &keep {
                    added.1 += push_part(
                        layer,
                        ring,
                        if *index == 0 { WINDING_OUTER } else { WINDING_HOLE },
                    );
                }
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_POLYGON,
                    flags: class.flags,
                    name_idx: tilecodec::mamaps::body::NAME_NONE,
                    parts_offset,
                    part_count: keep.len() as u32,
                    transit_color: feature.transit_color,
                    transit_ordinal: feature.transit_ordinal,
                    transit_lanes: feature.transit_lanes,
                    transit_taper: feature.transit_taper,
                    lane_count: feature.lane_count,
                });
                if track_ids {
                    entry.ids.push(feature.id);
                }
                if track_buildings {
                    entry.buildings.push(feature.building.unwrap_or_default());
                }
                added.0 += 1;
            }
        }
        IntGeometry::Lines(lines) => {
            let parts_offset = layer.parts.len() as u32;
            for line in lines {
                if line.len() < 2 {
                    continue;
                }
                added.1 += push_part(layer, line, WINDING_OUTER);
            }
            let part_count = layer.parts.len() as u32 - parts_offset;
            if part_count > 0 {
                // A road or river's name, interned for a curved label; NAME_NONE otherwise.
                let name_idx = if name_line_layer {
                    intern_name(&mut entry.names, feature.name.as_deref())
                } else {
                    tilecodec::mamaps::body::NAME_NONE
                };
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_LINE,
                    flags: class.flags,
                    name_idx,
                    parts_offset,
                    part_count,
                    transit_color: feature.transit_color,
                    transit_ordinal: feature.transit_ordinal,
                    transit_lanes: feature.transit_lanes,
                    transit_taper: feature.transit_taper,
                    lane_count: feature.lane_count,
                });
                if track_ids {
                    entry.ids.push(feature.id);
                }
                if track_buildings {
                    entry.buildings.push(feature.building.unwrap_or_default());
                }
                // Roads carry a per-feature turn-lane record, dense-parallel to the layer's
                // features like the id table is for its layers. Pushed here — the one branch a road
                // ever reaches (a road is a line) — inside the `part_count > 0` guard so it stays
                // aligned with the feature that was actually added.
                if class.layer == tilecodec::mamaps::dict::LAYER_ROADS {
                    entry.turn_lanes.push(tilecodec::mamaps::body::LaneTurns {
                        forward: feature.turn_fwd.clone(),
                        backward: feature.turn_bwd.clone(),
                    });
                    entry.carriageways.push(feature.carriageway);
                }
                added.0 += 1;
            }
        }
        IntGeometry::Points(points) => {
            // One feature per point, each naming its own label. Points carry no simplification
            // survivors to filter: a label is either in the tile or it is not.
            for point in points {
                // Interned before the layer is borrowed: the name table lives on the entry, the
                // geometry on the layer, and the two borrows must not overlap.
                let name_idx = intern_name(&mut entry.names, feature.name.as_deref());
                let parts_offset = layer.parts.len() as u32;
                added.1 += push_part(layer, &[*point], WINDING_OUTER);
                layer.features.push(BodyFeature {
                    kind: class.kind,
                    kind_detail: class.kind_detail,
                    geom_type: GEOM_POINT,
                    flags: class.flags,
                    name_idx,
                    parts_offset,
                    part_count: 1,
                    transit_color: feature.transit_color,
                    transit_ordinal: feature.transit_ordinal,
                    transit_lanes: feature.transit_lanes,
                    transit_taper: feature.transit_taper,
                    lane_count: feature.lane_count,
                });
                if track_ids {
                    entry.ids.push(feature.id);
                }
                if track_buildings {
                    entry.buildings.push(feature.building.unwrap_or_default());
                }
                added.0 += 1;
            }
        }
    }
    added
}

/// Append one path's points to a layer's arena, clamped to what an `i16` holds.
///
/// The clip buffer keeps a coordinate within a few percent of the extent, so `i16` has eight times
/// the headroom needed and this never bites. Clamping rather than asserting because a single stray
/// vertex should not fail a whole build.
fn push_part(layer: &mut BodyLayer, points: &[(i32, i32)], winding: u16) -> u64 {
    let coord_start = layer.coords.len() as u32;
    for &(x, y) in points {
        layer.coords.push((
            x.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
            y.clamp(i16::MIN as i32, i16::MAX as i32) as i16,
        ));
    }
    layer.parts.push(Part { coord_start, point_count: points.len() as u32, winding });
    points.len() as u64
}

/// A ring's absolute area, by the shoelace formula.
///
/// `i64` throughout: a 4096-unit tile's coordinates cross-multiply to about 2^24 per term, and a
/// long ring accumulates thousands of them, which overflows an `i32` and would silently report a
/// huge shape as a tiny one.
fn ring_area(ring: &[(i32, i32)]) -> f64 {
    let mut twice = 0i64;
    for pair in ring.windows(2) {
        let ((x0, y0), (x1, y1)) = (pair[0], pair[1]);
        twice += x0 as i64 * y1 as i64 - x1 as i64 * y0 as i64;
    }
    (twice.abs() as f64) / 2.0
}

fn is_empty(g: &Geometry<SigPt>) -> bool {
    match g {
        Geometry::Points(points) => points.is_empty(),
        Geometry::Lines(lines) => lines.iter().all(|l| l.len() < 2),
        Geometry::Polygons(polygons) => polygons.iter().all(|rings| {
            rings.first().map(|ring| ring.len() < 4).unwrap_or(true)
        }),
    }
}



/// A build that produced nothing is a build whose schema matched nothing, which is worth failing on
/// rather than publishing an empty archive.
pub fn check_not_empty(stats: &[ZoomStats]) -> Result<()> {
    if stats.iter().all(|s| s.tiles == 0) {
        return err("the build produced no tiles: nothing in the input matched the schema");
    }
    Ok(())
}
