/// Pass 2: refs of every relation member way. Moved whole from `extract`.
fn load_member_ways(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    relations: &[Relation],
) -> Result<HashMap<i64, Vec<i64>>> {
    // --- pass 2: the refs of every relation member way ------------------------------------
    //
    // **Every** member, not just the ones pass 1 did not classify. A relation reaches its members by
    // id, in the order it lists them, which is the one random access in this stage and the one thing
    // a sequential spill file cannot serve. So the members — and only the members — stay resident,
    // and that is what lets the other several million classified ways go to disk. California has
    // 63 156 relations, so this table is small next to the one it replaces.
    let wanted: Vec<i64> = {
        let mut wanted: Vec<i64> = relations
            .iter()
            .flat_map(|r| r.members.iter().map(|(id, _)| *id))
            .collect();
        wanted.sort_unstable();
        wanted.dedup();
        wanted
    };
    let mut members: HashMap<i64, Vec<i64>> = HashMap::new();
    if !wanted.is_empty() {
        let (chunks, _) = pbf::run_pass(
            input,
            &blobs,
            Some(&blob_kinds),
            KIND_WAYS,
            "Pass 2: member ways",
            Vec::<(i64, Vec<i64>)>::new,
            |state, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_WAYS, &mut kinds, &mut |el| {
                    if let Element::Way(way) = el {
                        if wanted.binary_search(&way.id).is_ok() {
                            state.push((way.id, way.refs.to_vec()));
                        }
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
        )?;
        for chunk in chunks {
            members.extend(chunk);
        }
    }

    Ok(members)
}

/// Pass 3: id index plus resolved coordinates. Moved whole from `extract`.
fn build_resolved_table(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    ways_path: &Path,
    members: &HashMap<i64, Vec<i64>>,
    way_refs: u64,
    way_max_ref: i64,
    stats: &mut Stats,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    // --- pass 3: the coordinates those two asked for --------------------------------------
    //
    // The classified ways are read off disk instead of walked in memory. Order does not matter to
    // either path below, so this is a plain streaming pass over the spill.
    let member_refs: usize = members.values().map(|refs| refs.len()).sum();
    let refs_total = way_refs as usize + member_refs;
    let max_ref = members
        .values()
        .flat_map(|refs| refs.iter().copied())
        .fold(way_max_ref, i64::max);
    let table = if refs_total <= REFS_IN_MEMORY {
        collect_needed_in_memory(&ways_path, &members, refs_total, &mark)?
    } else {
        collect_needed_by_bitset(&ways_path, &members, max_ref, &mark)?
    };
    mark("id index built, refs freed");
    stats.nodes_needed = table.len() as u64;
    let table = resolve_nodes(input, &blobs, &blob_kinds, "Pass 3: nodes", table)?;
    mark("coordinates resolved");
    // The split, when asked for. Note what it does and does not separate: the first number is the
    // protobuf decode of each block *plus* the id lookups inside it, because bracketing the lookups
    // alone would need a clock read per node and there are billions. The second is the write, which
    // is the half that is serialised behind `run_pass_sink`'s lock.
    let (scan, write) = osm_ingest::nodeloc::resolve_seconds();
    if scan + write > 0.0 {
        println!(
            "  [stage A] node pass CPU: decode+lookup {scan:.1}s  write {write:.1}s (serialised)",
        );
    }

    Ok(table)
}

/// Lane inheritance over the ways spill. Moved whole from `extract`.
fn inherit_lane_counts(
    spill_path: &Path,
    ways_path: &Path,
    table: &NodeLocations,
) -> Result<Vec<(i64, u8)>> {
    // --- lane inheritance -------------------------------------------------------------------
    //
    // A junction stub OSM left untagged takes a lane count from the road it interrupts, instead of
    // the renderer's flat `oneway ? 1 : 2`. See [`crate::lanefill`] for the conditions and the
    // measurements behind each of them. It runs here rather than in pass 1 because the conditions
    // are geometric — a length and a turn angle — and coordinates are only resolved above.
    let inherited_lanes = {
        let mut collector = crate::lanefill::Collector::create(spill_path)?;
        let mut reader = WayReader::open(&ways_path)?;
        let mut refs: Vec<i64> = Vec::new();
        while let Some((id, class, name, lane_count, _, _, _, _)) = reader.next(&mut refs)? {
            // Roads, and not slip roads. A ramp leaves a junction carrying its parent's name on
            // very nearly its parent's heading, so it would inherit the mainline's width onto a
            // single-lane ramp; `corridor` leaves links out of a corridor for the same reason.
            if class.layer != tilecodec::mamaps::dict::LAYER_ROADS
                || class.flags & tilecodec::mamaps::body::FLAG_IS_LINK != 0
            {
                continue;
            }
            let Some(name) = name.as_deref() else {
                continue;
            };
            let line = table.line(&refs);
            collector.push(&crate::lanefill::Segment {
                id,
                name,
                lanes: lane_count,
                oneway: class.flags & tilecodec::mamaps::body::FLAG_IS_ONEWAY != 0,
                nodes: &refs,
                line: &line,
            })?;
        }
        collector.finish()?
    };

    Ok(inherited_lanes)
}

/// Node pass: label nodes straight into the sink. Moved whole from `extract`.
fn spill_node_labels(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    select: &Select,
    layers: Layers,
    sink: &mut Sink,
    stats: &mut Stats,
    mark: &dyn Fn(&str),
) -> Result<()> {
    // --- node pass: labels ----------------------------------------------------------------
    //
    // Nodes carry no refs to resolve — their coordinates are inline — so they classify and spill
    // in one pass, straight into the sink: a label is one point and one name, and holding tens of
    // millions of them for a later loop would be a second materialise for no reason. Ways and
    // relations below only *add* to the sink, so spilling nodes first changes no order the tiler
    // reads (it groups by layer id).
    //
    // Only label layers are consulted here: a node is never a road, a lake or a building, and
    // running the full schema over 2 B nodes would pay the tag scan for nothing.
    if layers.places || layers.poi {
        pbf::run_pass_sink(
            input,
            &blobs,
            Some(&blob_kinds),
            KIND_NODES,
            "Pass 4: nodes",
            Vec::<(Class, f64, f64, Option<String>, u64)>::new,
            |state, block| {
                let mut kinds = 0u8;
                visit_block(block, KIND_NODES, &mut kinds, &mut |el| {
                    if let Element::Node(node) = el {
                        if !select.matches(|k| node.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&node.tags, false, layers) {
                            if is_label(class.layer) {
                                let name = schema::display_name(&node.tags, class.layer);
                                state.push((
                                    class,
                                    node.lon_e7 as f64 * 1e-7,
                                    node.lat_e7 as f64 * 1e-7,
                                    name,
                                    tagged_id(node.id, ELEMENT_NODE),
                                ));
                            }
                        }
                    }
                    Ok(())
                })?;
                Ok(kinds)
            },
            |hits| {
                for (class, lon, lat, name, id) in hits {
                    sink.push_named(
                        &class,
                        &Geometry::Points(vec![(lon, lat)]),
                        name.as_deref(),
                        id,
                    )?;
                    stats.features += 1;
                    stats.nodes_classified += 1;
                }
                Ok(())
            },
        )?;
        mark("nodes labelled");
    }

    Ok(())
}

/// Materialise classified ways off disk, in id order. Moved whole from `extract`.
fn materialise_ways(
    ways_path: &Path,
    promoted: &[(i64, u8)],
    inherited_lanes: &[(i64, u8)],
    table: &NodeLocations,
    sink: &mut Sink,
    stats: &mut Stats,
) -> Result<()> {
    let mut reader = WayReader::open(&ways_path)?;
    let mut refs: Vec<i64> = Vec::new();
    // Silent until now, and it is not a short step: on a north-america extract this loop ran 623
    // seconds on one thread with nothing on stdout, which is indistinguishable from a hang.
    let mut bar = Progress::new(
        "Materialise: ways".to_string(),
        stats.ways_classified as usize,
        "way(s)",
        true,
    );
    // Batched, because the expensive part of a way is embarrassingly parallel and the cheap part
    // cannot be. `table.line` is a coordinate lookup per node -- an id search plus a random read of a
    // mapped file -- and `way_geometry` closes and winds rings; neither touches shared mutable state,
    // so a batch of them goes as wide as the pool. The sink then takes the results **in order**,
    // which is what keeps the archive byte-identical: the spill's feature order is the archive's.
    //
    // 64 Ki ways at ~10 nodes each is a few tens of MB of geometry in flight, against a build that
    // peaks near 7 GB.
    const MATERIALISE_BATCH: usize = 64 * 1024;
    #[allow(clippy::type_complexity)]
    let mut batch: Vec<(
        Class,
        Option<String>,
        Vec<i64>,
        u64,
        u8,
        Vec<u16>,
        Vec<u16>,
        Carriageway,
        Option<BuildingAttrs>,
    )> = Vec::with_capacity(MATERIALISE_BATCH);
    let mut built: Vec<Option<Geometry<(f64, f64)>>> = Vec::with_capacity(MATERIALISE_BATCH);
    loop {
        let more = reader.next(&mut refs)?;
        if let Some((id, class, name, lane_count, turn_fwd, turn_bwd, carriageway, building)) =
            more.as_ref()
        {
            let mut class = *class;
            // The corridor's zoom, where it is shallower than this way's own.
            if let Ok(at) = promoted.binary_search_by_key(id, |(id, _)| *id) {
                class.min_zoom = promoted[at].1;
            }
            // A neighbour's lane count, where OSM tagged none on this way. Only ever consulted
            // for a way that has none of its own, so a tag is never overridden.
            let lane_count = if *lane_count == 0 {
                inherited_lanes
                    .binary_search_by_key(id, |(id, _)| *id)
                    .map_or(0, |at| inherited_lanes[at].1)
            } else {
                *lane_count
            };
            // Only a label way carries its id onward. A road or a building is merged with its
            // neighbours by `coalesce`, which leaves the survivor's id arbitrary, and the id
            // table exists for `poi` and `places` alone.
            let id = if is_label(class.layer) {
                tagged_id(*id, ELEMENT_WAY)
            } else {
                tilecodec::mamaps::body::ID_NONE
            };
            batch.push((
                class,
                name.clone(),
                std::mem::take(&mut refs),
                id,
                lane_count,
                turn_fwd.clone(),
                turn_bwd.clone(),
                *carriageway,
                *building,
            ));
        }
        // Flushed when full, and once more at the end with whatever is left.
        if batch.len() >= MATERIALISE_BATCH || (more.is_none() && !batch.is_empty()) {
            built.clear();
            par::install(|| {
                batch
                    .par_iter()
                    .map(|(class, _, refs, _, _, _, _, _, _)| {
                        let line = table.line(refs);
                        // A label layer's ways are centroided to points: a town mapped as an area
                        // is still one label, not a loop. Everything else keeps its geometry.
                        if is_label(class.layer) {
                            centroid(&line).map(|point| Geometry::Points(vec![point]))
                        } else {
                            way_geometry(&line, class.area)
                        }
                    })
                    .collect_into_vec(&mut built);
            });
            for (
                (class, name, _, id, lane_count, turn_fwd, turn_bwd, carriageway, building),
                geometry,
            ) in batch.iter().zip(built.drain(..))
            {
                match geometry {
                    Some(geometry) => {
                        // A building carries its S3DB attributes; a road with lane data carries
                        // those; everything else — and a plain road — goes the plain, named way.
                        if let Some(b) = building {
                            sink.push_building(class, &geometry, name.as_deref(), *b)?;
                        } else if *lane_count > 0
                            || !turn_fwd.is_empty()
                            || !turn_bwd.is_empty()
                            || !carriageway.is_empty()
                        {
                            sink.push_road(
                                class,
                                &geometry,
                                name.as_deref(),
                                *lane_count,
                                turn_fwd,
                                turn_bwd,
                                *carriageway,
                            )?;
                        } else {
                            sink.push_named(class, &geometry, name.as_deref(), *id)?;
                        }
                        stats.features += 1;
                    }
                    None => stats.geometry_failed += 1,
                }
                bar.tick("way(s)");
            }
            batch.clear();
        }
        if more.is_none() {
            break;
        }
    }
    bar.finish("way(s)");
    // Nothing reads the ways spill after this: the relations below reach their members through
    // `members`, which is why that table is kept at all.
    drop(reader);
    let _ = std::fs::remove_file(&ways_path);

    Ok(())
}