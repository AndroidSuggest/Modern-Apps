/// Collect one tile's shared rows from its fused layers.
///
/// Runs after coalescing, stage C and side-table fusion, so the features (and the parallel
/// `ids`/`turn_lanes`/`buildings`/`carriageways` vectors) are the ones the body carries. One
/// intent per shared feature, in layer/feature order; the drain turns first sightings into rows
/// and every sighting into a slim ref.
///
/// Traffic sightings additionally resolve their lane C segment-delta codec
/// attachments (see [`crate::layercodec::resolve_traffic_codecs`]): one entry
/// per traffic intent, in order, consumed by [`drain_shared_rows`].
fn shared_intents_for_tile(
    layers: &[ChunkEntry],
    names: &[String],
    ids: &[(u8, Vec<u64>)],
    turn_lanes: &[(u8, Vec<tilecodec::mamaps::body::LaneTurns>)],
    buildings: &[(u8, Vec<BuildingAttrs>)],
    carriageways: &[(u8, Vec<tilecodec::mamaps::body::Carriageway>)],
) -> Vec<SharedRowIntent> {
    use tilecodec::mamaps::body::NAME_NONE;
    use tilecodec::mamaps::dict::{LAYER_BUILDINGS, LAYER_JUNCTION, LAYER_ROADS, LAYER_TRAFFIC};
    // Per-layer traffic positions within `intents`, so the delta resolution
    // below can zip attachments back in sighting order.
    let mut traffic_slots: Vec<usize> = Vec::new();
    let mut traffic_sightings: Vec<(u64, Vec<(i16, i16)>)> = Vec::new();
    let mut intents = Vec::new();
    for entry in layers {
        let layer_id = entry.layer.layer_id;
        if !matches!(layer_id, LAYER_ROADS | LAYER_BUILDINGS | LAYER_TRAFFIC | LAYER_JUNCTION) {
            continue;
        }
        for (index, feature) in entry.layer.features.iter().enumerate() {
            let stable_id =
                fused_row(ids, layer_id, index).copied().unwrap_or(tilecodec::mamaps::body::ID_NONE);
            // Junction identity IS the geometry: every part's tile-local points, concatenated.
            // Traffic needs the same tile-local points for its segment-delta
            // codec; every other layer keys by id and never reads points.
            let points: Vec<(i16, i16)> = if layer_id == LAYER_JUNCTION
                || layer_id == LAYER_TRAFFIC
            {
                entry
                    .layer
                    .parts_of(feature)
                    .iter()
                    .flat_map(|part| entry.layer.points(part))
                    .copied()
                    .collect()
            } else {
                Vec::new()
            };
            let name = if feature.name_idx == NAME_NONE {
                None
            } else {
                names.get(feature.name_idx as usize - 1).cloned()
            };
            let flags = if feature.flags & tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC != 0 {
                tilecodec::mamaps::shared::SHARED_FLAG_DETAIL_NUMERIC
            } else {
                0
            };
            let building =
                fused_row(buildings, layer_id, index).copied().unwrap_or_default();
            let carriageway =
                fused_row(carriageways, layer_id, index).copied().unwrap_or_default();
            let lane_turns =
                fused_row(turn_lanes, layer_id, index).cloned().unwrap_or_default();
            let Some(key) = shared_row_key(
                layer_id,
                stable_id,
                // The shared row key never reads traffic geometry: traffic
                // keys by component id, and the base polyline rides lane A's
                // geometry pool instead.
                if layer_id == LAYER_TRAFFIC {
                    &[]
                } else {
                    &points
                },
                name,
                feature.kind,
                feature.kind_detail,
                flags,
                building,
                carriageway,
                lane_turns,
            ) else {
                continue;
            };
            if layer_id == LAYER_TRAFFIC {
                // Stitched in the second pass below, where the whole tile's
                // traffic sightings are in scope.
                traffic_slots.push(intents.len());
                traffic_sightings.push((stable_id, points));
            }
            intents.push(SharedRowIntent {
                key,
                flags,
                stable_id,
                traffic: None,
                layer_id,
                feature_index: index,
            });
        }
    }
    // Per-lane-C: resolve one edge's stitched base plus each segment's mask.
    // `None` per sighting (a gap, a degenerate shape) keeps the plain row.
    if !traffic_slots.is_empty() {
        let resolved = crate::layercodec::resolve_traffic_codecs(&traffic_sightings);
        for (slot, codec) in traffic_slots.into_iter().zip(resolved) {
            if let Some(codec) = &codec {
                debug_assert_eq!(
                    codec.mask.len(),
                    codec.base.len(),
                    "a lane C mask always covers its base: emit_keep_mask panics otherwise",
                );
            }
            intents[slot].traffic = codec;
        }
    }
    intents
}

/// Re-emit slim-mode layers as a v8 mixed body, replacing v7 bytes.
///
/// Runs in `build`'s serial loop after [`drain_shared_rows`] assigns this
/// tile's logical ids: `logical_ids[i]` is the id for `intents[i]`, and each
/// intent carries its (layer, feature) position, so slim-mode layers zip
/// features onto ids and emit 16 B instances plus the layer's own parts and
/// arena through [`serialize_mixed_body`]. Layers stay full unless **every**
/// feature has an intent (junction connectors and unattributed buildings have
/// no shared row to slim to), every feature shares one geometry type (slim
/// layers are homogeneous — resolve derives the type from the layer id), and
/// only the numeric-detail flag travels (tunnel/bridge/link/oneway, lane
/// counts and transit styling resolve as zero until rows grow fields).
/// `encoded=None` (an emptied tile) passes through untouched; without slim
/// layers the v7 bytes pass through untouched.
#[allow(clippy::type_complexity)]
fn emit_mixed_body(
    logical_ids: &[u32],
    intents: &[SharedRowIntent],
    inputs: &SlimEmitInputs,
    encoded: Option<(Vec<u8>, usize)>,
    deflate: &mut tilecodec::gz::Compressor,
    scratch: &mut tilecodec::mamaps::body::Scratch,
) -> Result<Option<(Vec<u8>, usize)>> {
    use tilecodec::mamaps::body::{MixedLayer, GEOM_LINE, GEOM_POINT, GEOM_POLYGON};
    use tilecodec::mamaps::dict::*;
    let Some((_, _)) = encoded.as_ref() else { return Ok(encoded) };
    // (layer, feature) -> logical id, from intent positions.
    let mut id_of: std::collections::HashMap<(u8, usize), u32> = std::collections::HashMap::new();
    for (intent, &logical_id) in intents.iter().zip(logical_ids.iter()) {
        // First sighting wins: repeat sightings share the row and the id.
        id_of.entry((intent.layer_id, intent.feature_index)).or_insert(logical_id);
    }
    let mut layers: Vec<(u8, MixedLayer<'_>)> = Vec::new();
    let mut any_slim = false;
    for entry in &inputs.layers {
        let layer_id = entry.layer.layer_id;
        // Slim candidates: high-K shared layers only. K≈1 layers (junction,
        // earth, water) and everything unattributed stay full v7 untouched.
        let candidate = matches!(layer_id, LAYER_TRAFFIC | LAYER_BUILDINGS | LAYER_ROADS);
        let geom = match layer_id {
            LAYER_EARTH | LAYER_WATER | LAYER_LANDCOVER | LAYER_LANDUSE | LAYER_BUILDINGS => {
                GEOM_POLYGON
            }
            LAYER_ROADS | LAYER_BOUNDARIES | LAYER_TRANSIT | LAYER_TRAFFIC | LAYER_JUNCTION => {
                GEOM_LINE
            }
            LAYER_PLACES | LAYER_POI => GEOM_POINT,
            _ => 0,
        };
        let homogeneous =
            entry.layer.features.iter().all(|f| f.geom_type == geom) && geom != 0;
        let unstyled = entry.layer.features.iter().all(|f| {
            f.flags & !(tilecodec::mamaps::body::FLAG_DETAIL_NUMERIC) == 0
                && f.lane_count == 0
                && f.transit_color == 0
                && f.transit_ordinal == 0
                && f.transit_lanes == 0
                && f.transit_taper == 0
                && f.name_idx == tilecodec::mamaps::body::NAME_NONE
        });
        let mut refs: Vec<(u32, u32, u32, u32)> = Vec::new();
        let slim = candidate
            && homogeneous
            && unstyled
            && !entry.layer.features.is_empty()
            && (0..entry.layer.features.len()).all(|index| {
                match id_of.get(&(layer_id, index)) {
                    Some(&logical_id) => {
                        let feature = &entry.layer.features[index];
                        refs.push((
                            logical_id,
                            SHARED_VIEW_BITS,
                            feature.parts_offset,
                            feature.part_count,
                        ));
                        true
                    }
                    None => false,
                }
            });
        if slim {
            any_slim = true;
            layers.push((
                layer_id,
                MixedLayer::Slim {
                    refs,
                    parts: entry.layer.parts.clone(),
                    coords: entry.layer.coords.clone(),
                },
            ));
        } else {
            layers.push((layer_id, MixedLayer::Full(&entry.layer)));
        }
    }
    if !any_slim {
        return Ok(encoded);
    }
    let mixed = tilecodec::mamaps::body::serialize_mixed_body(
        tilecodec::mamaps::body::DEFAULT_EXTENT,
        &layers,
        inputs.convention,
        inputs.heightmap.as_ref(),
        scratch,
    )?;
    let raw_len = mixed.len();
    let stored = tilecodec::mamaps::write::compress_body_with(deflate, mixed);
    Ok(Some((stored, raw_len)))
}

/// Push one tile's intents into the shared builder, in order, returning one
/// logical id per intent in order.
///
/// The builder interns by full content key (sequential build-local id,
/// first-sighting order); every sighting pushes a slim ref, so the slim pool
/// stays tile-major in tile-id order. Equal keys mean equal rows, so two
/// sightings of one key share one row and different content is a different
/// row: a misjoin is unrepresentable, and the old "two features, one id" build
/// failure is gone with the fold that caused it.
///
/// The returned ids line up with `intents` positionally, so the caller zips
/// them back onto (layer, feature) positions for slim emission.
///
/// Lane C traffic codec attachments ride the intents but emit nothing here:
/// per-edge-per-tile bases do not dedup (tile-local clips differ, exactly
/// like junctions), so interning them costs ~2 GB of pool for geometry the
/// resolve path never reads — arenas stay per-tile. The geometry pool
/// machinery (types, parse, `intern_geometry`, `emit_keep_mask`) stays for a
/// future cross-zoom design where one canonical edge serves all tiles; the
/// `traffic` field stays so the stitch tests keep pinning the codec.
/// `zoom` is the tile's own zoom, unused for now.
fn drain_shared_rows(
    builder: &mut tilecodec::mamaps::shared::SharedBuilder,
    intents: Vec<SharedRowIntent>,
    zoom: u8,
) -> Result<Vec<u32>> {
    use tilecodec::mamaps::shared::SharedSlimRef;
    let _ = zoom;
    let mut ids = Vec::with_capacity(intents.len());
    for intent in intents {
        let (kind, kind_detail) = (intent.key.kind, intent.key.kind_detail);
        let logical_id =
            builder.intern_row(intent.key, kind, kind_detail, SHARED_VIEW_BITS, intent.stable_id);
        builder.push_slim_ref(SharedSlimRef {
            logical_id,
            view_bits: SHARED_VIEW_BITS,
        });
        ids.push(logical_id);
    }
    Ok(ids)
}

/// Derive the sea for one tile as the tile rectangle with land cut out of it.
///
/// **Currently disabled** — [`Settings::ocean`] is hard-off. Kept because the analysis is worth
/// more than the code, and whoever tries this next should read it first.
///
/// # Two attempts, two structural failures
///
/// The sea has no geometry in OpenStreetMap: water is defined by the absence of land. So it can
/// only be derived, and both derivations available here break on real coastline.
///
/// **Subtracting land with the polygon clipper.** [`crate::clip`] uses Sutherland-Hodgman, which
/// returns a *self-touching* ring when a concave polygon clips into disjoint pieces, joined by a
/// zero-area sliver along the tile boundary. A coastline in a tile is exactly that shape, and a
/// sweep-line boolean requires simple polygons. The result was a visibly shredded coast.
///
/// **Land rings as holes in a rectangle**, which is what this code does. No boolean is involved
/// and `crate::rings` accepts the result, but the tessellator does not: one tile's sea is a single
/// polygon with hundreds of holes — 64,091 points at z0 on a North America build — and the land
/// rings *share edges*, because the OSMCoastline product is split into abutting pieces.
/// `library/map`'s `tess/fill.rs` says earcut cannot triangulate self-touching rings reliably, and
/// it does not: land filled as sea, differently at every zoom, so panning between zooms made the
/// same coastline flip between land and water.
///
/// # What would actually work
///
/// Union the land pieces into one ring per landmass before using them as holes, so no two holes
/// touch. That needs a boolean whose input is *not* Sutherland-Hodgman output — i.e. union the
/// land at ingest, before clipping, not per tile. `tile_build::boolean` is verified for difference
/// and intersection and could do it, but it is a different pipeline stage and its own piece of
/// work.
///
/// Until then the style does not paint marine areas green, which removes the symptom without
/// inventing geometry.
fn add_ocean(layers: &mut Vec<ChunkEntry>) {
    if !OCEAN.load(Ordering::Relaxed) {
        return;
    }
    // Land's *exterior* rings only. A land polygon's own holes are inland water, and repeating
    // them here would cut them out of the sea as well, painting a lake in the land colour.
    let land: Vec<Vec<(i32, i32)>> = match layers
        .iter()
        .find(|entry| entry.layer.layer_id == tilecodec::mamaps::dict::LAYER_EARTH)
    {
        None => Vec::new(),
        Some(entry) => entry
            .layer
            .features
            .iter()
            .filter(|feature| feature.geom_type == GEOM_POLYGON)
            .filter_map(|feature| entry.layer.parts_of(feature).first().copied())
            .map(|part| {
                entry
                    .layer
                    .points(&part)
                    .iter()
                    .map(|&(x, y)| (x as i32, y as i32))
                    .collect::<Vec<_>>()
            })
            .filter(|ring: &Vec<(i32, i32)>| ring.len() >= 3)
            .collect(),
    };

    // Clear of the land's clip edge, so a coastal ring is strictly inside and survives `rings`.
    let edge = (geom::buffer_for(EXTENT) + 2.0).round() as i32;
    let far = EXTENT as i32 + edge;
    let rect = vec![(-edge, -edge), (far, -edge), (far, far), (-edge, far)];

    // Append to the existing `water` entry if the tile has one, so a lake and the sea share a
    // layer. An open-ocean tile has no water entry at all, so create one — keeping the ascending
    // `layer_id` order that the body index is written in, which `Vec::insert` at the sorted
    // position gives and `push` would not.
    let water = tilecodec::mamaps::dict::LAYER_WATER;
    let at = match layers.binary_search_by_key(&water, |entry| entry.layer.layer_id) {
        Ok(at) => at,
        Err(at) => {
            layers.insert(at, ChunkEntry::new(water));
            at
        }
    };
    let layer = &mut layers[at].layer;
    let existing = layer.features.len();
    let parts_offset = layer.parts.len() as u32;
    push_part(layer, &rect, WINDING_OUTER);
    for ring in &land {
        push_part(layer, ring, WINDING_HOLE);
    }
    layer.features.push(BodyFeature {
        kind: crate::schema::kind("ocean"),
        kind_detail: tilecodec::mamaps::dict::NONE,
        geom_type: GEOM_POLYGON,
        flags: 0,
        name_idx: tilecodec::mamaps::body::NAME_NONE,
        parts_offset,
        part_count: 1 + land.len() as u32,
        transit_color: 0,
        transit_ordinal: 0,
        transit_lanes: 1,
        transit_taper: u8::MAX,
        lane_count: 0,
    });
    // Spliced to the front.
    // Spliced to the front. Within a layer the renderer draws in feature order, and the sea belongs
    // under everything else in `water` — a lake on an island sits on top of the sea, not the other
    // way round. Going first also keeps this layer's features in store order after the synthesised
    // one, which `feature_order_within_a_tile_layer_is_the_store_order` exists to defend.
    // `parts_offset` indexes the parts arena, which reordering does not touch.
    layer.features[..existing + 1].rotate_right(1);
    // Deliberately not touching `entry.ids`: only `places` and `poi` carry an id side table, and
    // the sea has no OSM element to name anyway.
}
