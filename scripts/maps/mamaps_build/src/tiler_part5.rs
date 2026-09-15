fn encode_batch(
    batch: Vec<(u64, Vec<ChunkEntry>)>,
    dem: &crate::dem::Dem,
    conventions: &crate::schema::boundaries::Conventions,
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
                        return Ok((id, None, rings, lines));
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
                    let body = Body {
                        extent: EXTENT as u16,
                        layers: layers.into_iter().map(|entry| entry.layer).collect(),
                        names,
                        ids,
                        turn_lanes,
                        buildings,
                        // The DEM heightmap is produced by a separate ingest stage keyed by tile,
                        // not from OSM geometry. Sample its grid onto this tile's z/x/y; a tile
                        // with no DEM under it (ocean, off-coverage) gets None and stays 16-byte.
                        heightmap: dem.heightmap_for(z, x, y),
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
                    Ok((id, Some((stored, raw_len)), rings, lines))
                },
            )
            .collect()
    })
}
