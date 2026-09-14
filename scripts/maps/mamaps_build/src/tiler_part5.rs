fn encode_batch(
    batch: Vec<(u64, Vec<ChunkEntry>)>,
    dem: Option<&crate::dem::Dem>,
    conventions: &crate::schema::boundaries::Conventions,
    shared: bool,
) -> Result<Vec<Encoded>> {
    let min_len = par::min_task_len(batch.len());
    let on = timing();
    par::install(|| {
        batch
            .into_par_iter()
            .with_min_len(min_len)
            // One DEFLATE state and one serialisation buffer per worker, not one per tile.
            // `compress_to_vec` builds a 65,712-byte `CompressorOxide` every call, and at a tile
            // each across 64 cores that construction swamps the compression it exists to do -- see
            // `mamaps::write::compress_body_with`. The `Scratch` is there for the same reason on
            // the serialisation side: its allocations, not its encoding, were what made
            // serialisation cost 20.9 s of CPU at 16 threads and 1120 s at 64.
            .map_init(
                || (tilecodec::gz::Compressor::new(), tilecodec::mamaps::body::Scratch::default()),
                |(deflate, scratch), (id, mut layers)| {
                    // **Coalesce first.** An OSM way is an editing unit, not a rendering one, so a
                    // road arrives as hundreds of two-point fragments each carrying its own 28 bytes
                    // of header -- see `crate::coalesce`. Merging them before stage C is also what
                    // keeps stage C off tens of thousands of features it would only copy through.
                    let mut lines = crate::coalesce::Stats::default();
                    for entry in &mut layers {
                        // The traffic layer is one feature per component segment, each carrying a
                        // distinct component_id in the id table. Coalescing merges a class's lines
                        // into one feature, which would collapse every segment's id onto whichever
                        // came first — so it is skipped, keeping the layer one-to-one with its ids.
                        //
                        // The junction layer is skipped for the neighbouring reason: it carries no
                        // ids, but each feature is one lane's connector through an intersection and
                        // they all share a class, so coalescing would chain unrelated connectors —
                        // a left turn and the through movement beside it — into one polyline and
                        // draw a ribbon between them.
                        if entry.layer.layer_id == tilecodec::mamaps::dict::LAYER_TRAFFIC
                            || entry.layer.layer_id == tilecodec::mamaps::dict::LAYER_JUNCTION
                        {
                            continue;
                        }
                        lines.add(crate::coalesce::coalesce_lines_with_ids(
                            &mut entry.layer,
                            Some(&mut entry.ids),
                            Some(&mut entry.turn_lanes),
                            Some(&mut entry.buildings),
                            Some(&mut entry.carriageways),
                        ));
                    }
                    // **The sea.** There is no `natural=ocean` in OpenStreetMap — water is defined
                    // by the absence of land — so the only way to have ocean geometry is to
                    // subtract the land from the tile. Before this, the sea was the renderer's
                    // background colour, which meant nothing was ever drawn *over* it: marine
                    // protected areas, which are real `landuse` polygons hundreds of kilometres
                    // across, painted green across open water with nothing to repaint them.
                    //
                    // Here rather than in a schema rule because it is the one feature that is a
                    // property of the tile rather than of any OSM element, and this is the first
                    // point at which the tile's land is known: clipped, coalesced, and in
                    // tile-local coordinates.
                    add_ocean(&mut layers);
                    // **Stage C**, per tile: winding normalised, hole containment resolved,
                    // degenerate rings dropped. Once, here, in `f64` with no frame budget, instead
                    // of every frame on device in `i32` under one. This is what makes
                    // `FLAG_RINGS_VALIDATED` true.
                    let mut rings = crate::rings::Stats::default();
                    timed(on, &STAGE_C_NANOS, || {
                        for entry in &mut layers {
                            rings.add(crate::rings::normalise_with_ids(
                                &mut entry.layer,
                                Some(&mut entry.ids),
                                Some(&mut entry.turn_lanes),
                                Some(&mut entry.buildings),
                            ));
                        }
                        // A layer that ended up empty - every feature in it fell below the minimum
                        // area after clipping, or lost its exterior to stage C - costs bytes in the
                        // archive and a draw call on device for nothing.
                        layers.retain(|entry| !entry.layer.features.is_empty());
                    });
                    if layers.is_empty() {
                        return Ok((id, None, rings, lines, Vec::new(), SlimEmitInputs::default()));
                    }
                    // What the `u16` feature index in the body format has to hold. Sampled here
                    // because this is the shape that reaches the encoder: after coalescing merged
                    // the line fragments and after stage C dropped what it drops.
                    for entry in &layers {
                        WIDEST_LAYER[entry.layer.layer_id as usize]
                            .fetch_max(entry.layer.features.len() as u64, Ordering::Relaxed);
                    }
                    // Fuse the layers' name tables into the body's one, in layer order, remapping
                    // every feature's index as it goes. First-use order is deterministic (merge
                    // order within a layer, layer order across them), so the same tile always
                    // carries the same table.
                    let mut names: Vec<String> = Vec::new();
                    for entry in &mut layers {
                        for feature in &mut entry.layer.features {
                            if feature.name_idx
                                != tilecodec::mamaps::body::NAME_NONE
                            {
                                let name = entry.names[feature.name_idx as usize - 1].clone();
                                match names.iter().position(|n| *n == name) {
                                    Some(at) => feature.name_idx = at as u16 + 1,
                                    None => {
                                        feature.name_idx = names.len() as u16 + 1;
                                        names.push(name);
                                    }
                                }
                            }
                        }
                    }
                    // Fuse the layers' id tables into the body's one. Unlike names these are
                    // values rather than indices, so nothing is remapped and nothing is
                    // deduplicated — the table is simply the surviving `places`/`poi` layers'
                    // vectors, in layer order, which `Merged` already delivers ascending.
                    //
                    // Checked against the feature count rather than assumed. `coalesce_lines`
                    // rebuilds a layer's features and `rings::normalise` retains over them, and
                    // both now rewrite the id table alongside. That is easy to get wrong and
                    // silent when it is - every id after the first casualty would describe the
                    // wrong feature - so this fails the build instead.
                    let mut ids: Vec<(u8, Vec<u64>)> = Vec::new();
                    for entry in &mut layers {
                        if entry.ids.is_empty() {
                            continue;
                        }
                        if entry.ids.len() != entry.layer.features.len() {
                            return err(format!(
                                "layer {} has {} id(s) for {} feature(s) after coalesce and \
                                 stage C",
                                entry.layer.layer_id,
                                entry.ids.len(),
                                entry.layer.features.len(),
                            ));
                        }
                        ids.push((entry.layer.layer_id, std::mem::take(&mut entry.ids)));
                    }
                    // The turn-lane table, built the same way as the id table and checked against
                    // the feature count for the same reason: coalesce and stage C rebuild a layer's
                    // features and rewrite this alongside, and a drift would misattribute every
                    // lane after the first. Only emitted when some road actually has turn masks, so
                    // a tile with none carries no table at all.
                    let mut turn_lanes: Vec<(u8, Vec<tilecodec::mamaps::body::LaneTurns>)> =
                        Vec::new();
                    for entry in &mut layers {
                        if entry.turn_lanes.is_empty() {
                            continue;
                        }
                        if entry.turn_lanes.len() != entry.layer.features.len() {
                            return err(format!(
                                "layer {} has {} turn record(s) for {} feature(s) after coalesce \
                                 and stage C",
                                entry.layer.layer_id,
                                entry.turn_lanes.len(),
                                entry.layer.features.len(),
                            ));
                        }
                        if entry.turn_lanes.iter().all(|t| t.is_empty()) {
                            entry.turn_lanes.clear();
                            continue;
                        }
                        turn_lanes
                            .push((entry.layer.layer_id, std::mem::take(&mut entry.turn_lanes)));
                    }
                    // The building side table, built the same way and checked against the feature
                    // count for the same reason. A tile whose buildings all carried default attrs
                    // (no S3DB tags) emits no table at all — the renderer falls back to its own
                    // default height and colour — so the table is paid for only where 3D data
                    // exists.
                    let mut buildings: Vec<(u8, Vec<BuildingAttrs>)> = Vec::new();
                    for entry in &mut layers {
                        if entry.buildings.is_empty() {
                            continue;
                        }
                        if entry.buildings.len() != entry.layer.features.len() {
                            return err(format!(
                                "layer {} has {} building record(s) for {} feature(s) after \
                                 coalesce and stage C",
                                entry.layer.layer_id,
                                entry.buildings.len(),
                                entry.layer.features.len(),
                            ));
                        }
                        if entry.buildings.iter().all(|b| *b == BuildingAttrs::default()) {
                            entry.buildings.clear();
                            continue;
                        }
                        buildings
                            .push((entry.layer.layer_id, std::mem::take(&mut entry.buildings)));
                    }
                    // The carriageway side table, built like the two above and checked against the
                    // feature count for the same reason.
                    //
                    // A tile where no road's split was ever surveyed usually emits no table at all,
                    // so it costs nothing for lane data that does not exist. The exception is a
                    // tile whose country is not the default one: the convention only reaches the
                    // wire alongside a table, and a tile of untagged residential streets in Tokyo
                    // still has to say that traffic keeps left. So an all-default table is kept
                    // exactly when there is a non-default convention to carry with it.
                    let (z, x, y) = tilecodec::pmtiles::tile_zxy(id);
                    let convention = conventions.at_tile(z, x, y);
                    let mut carriageways: Vec<(u8, Vec<tilecodec::mamaps::body::Carriageway>)> =
                        Vec::new();
                    for entry in &mut layers {
                        if entry.carriageways.is_empty() {
                            continue;
                        }
                        if entry.carriageways.len() != entry.layer.features.len() {
                            return err(format!(
                                "layer {} has {} carriageway record(s) for {} feature(s) after \
                                 coalesce and stage C",
                                entry.layer.layer_id,
                                entry.carriageways.len(),
                                entry.layer.features.len(),
                            ));
                        }
                        if convention == tilecodec::mamaps::body::MarkingConvention::default()
                            && entry.carriageways.iter().all(|c| c.is_empty())
                        {
                            entry.carriageways.clear();
                            continue;
                        }
                        carriageways
                            .push((entry.layer.layer_id, std::mem::take(&mut entry.carriageways)));
                    }
                    let convention = (!carriageways.is_empty()).then_some(convention);
                    // Shared intents are per-tile pure — safe to build on the worker — while the
                    // drain stays serial in `build`. Skipped entirely when the flag is off, so a
                    // v7 build pays nothing for the table it did not ask for.
                    let shared_intents = if shared {
                        shared_intents_for_tile(
                            &layers,
                            &names,
                            &ids,
                            &turn_lanes,
                            &buildings,
                            &carriageways,
                        )
                    } else {
                        Vec::new()
                    };
                    // Slim emission inputs ride alongside for the serial loop: with
                    // the flag on it drains this tile's intents (assigning
                    // logical ids) and re-emits slim-mode layers through
                    // `emit_mixed_body`, replacing the v7 bytes below. Without
                    // the flag everything stays empty and the v7 bytes append
                    // untouched.
                    let slim_inputs = if shared {
                        SlimEmitInputs {
                            layers: layers
                                .iter()
                                .map(|entry| ChunkEntry {
                                    layer: BodyLayer {
                                        layer_id: entry.layer.layer_id,
                                        features: entry.layer.features.clone(),
                                        parts: entry.layer.parts.clone(),
                                        coords: entry.layer.coords.clone(),
                                    },
                                    names: entry.names.clone(),
                                    ids: entry.ids.clone(),
                                    turn_lanes: entry.turn_lanes.clone(),
                                    buildings: entry.buildings.clone(),
                                    carriageways: entry.carriageways.clone(),
                                })
                                .collect(),
                            names: names.clone(),
                            ids: ids.clone(),
                            turn_lanes: turn_lanes.clone(),
                            buildings: buildings.clone(),
                            carriageways: carriageways.clone(),
                            heightmap: dem.and_then(|d| d.heightmap_for(z, x, y)),
                            convention,
                            intents: Vec::new(),
                        }
                    } else {
                        SlimEmitInputs::default()
                    };
                    // The intents ride the emit inputs too: the serial loop
                    // drains a clone for ids while emission zips the original
                    // onto (layer, feature) positions.
                    let mut slim_inputs = slim_inputs;
                    slim_inputs.intents = shared_intents.clone();
                    let body = Body {
                        extent: EXTENT as u16,
                        layers: layers.into_iter().map(|entry| entry.layer).collect(),
                        names,
                        ids,
                        turn_lanes,
                        buildings,
                        // The DEM heightmap is produced by a separate ingest stage keyed by tile,
                        // not from OSM geometry. When a dataset was given, sample its grid onto this
                        // tile's z/x/y; a tile with no DEM under it (ocean, off-coverage) gets None
                        // and stays 16-byte. Without a dataset every tile stays None.
                        heightmap: dem.and_then(|d| d.heightmap_for(z, x, y)),
                        carriageways,
                        convention,
                    };
                    let encoded = timed(on, &SERIALIZE_NANOS, || {
                        tilecodec::mamaps::body::serialize_into(&body, scratch)
                    })?;
                    let raw_len = encoded.len();
                    let stored = timed(on, &DEFLATE_NANOS, || {
                        tilecodec::mamaps::write::compress_body_with(deflate, encoded)
                    });
                    Ok((id, Some((stored, raw_len)), rings, lines, shared_intents, slim_inputs))
                },
            )
            .collect()
    })
}
