) -> Result<(Store, Stats)> {
    // Stage A's boundaries are printed with their elapsed time so an external RSS sampler can say
    // which of them the peak belongs to. Three candidates sit within seconds of each other -- the ref
    // vector, the id index built beside it, and the node pass's per-chunk accumulators -- and
    // guessing between them has already cost more than printing them does.
    let started = std::time::Instant::now();
    let mark = |what: &str| {
        println!("  [stage A] {what} at {:.1}s", started.elapsed().as_secs_f64());
    };
    let blobs = pbf::scan_blobs(input)?;
    let select = Select::parse(&schema::filters(layers))?;
    let mut stats = Stats::default();
    // Scratch for this function alone: written in pass 1, read twice below, and removed as soon as
    // the last read is done. Beside the feature spill, so a build directed at a writable output
    // directory needs nothing else to be writable.
    let ways_path = spill_path.with_extension("ways.tmp");

    // --- pass 1: ways and relations -------------------------------------------------------
    //
    // `run_pass_sink` rather than `run_pass`, because `run_pass`'s contract is to hand back every
    // chunk at once and these chunks hold the node refs of every classified way — the 2.7 GB this
    // spill exists to be rid of. Here a chunk is appended to the file and freed while its
    // neighbours are still decoding.
    //
    // `blob_kinds` comes back from this pass and lets the later ones skip whole blobs, which on a
    // planet extract is most of the file.
    let mut ways = WaySink::create(&ways_path)?;
    let mut relations: Vec<Relation> = Vec::new();
    // Road ways carrying a `ref`, for the corridor pass below. A small minority of ways,
    // and the only thing pass 1 keeps in memory besides the relations.
    let mut corridors: Vec<crate::corridor::Segment> = Vec::new();
    let blob_kinds = pbf::run_pass_sink(
        input,
        &blobs,
        None,
        KIND_WAYS | KIND_RELATIONS,
        "Pass 1: ways and relations",
        || {
            (
                Vec::<(i64, Way)>::new(),
                Vec::<Relation>::new(),
                Vec::<crate::corridor::Segment>::new(),
            )
        },
        |state, block| {
            let mut kinds = 0u8;
            visit_block(block, KIND_WAYS | KIND_RELATIONS, &mut kinds, &mut |el| {
                match el {
                    Element::Way(way) => {
                        if !select.matches(|k| way.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&way.tags, true, layers) {
                            // A numbered road's `min_zoom` is decided per corridor, not per
                            // way, so that a route does not vanish where its class changes.
                            // Slip roads are left out: they carry the parent's `ref` and
                            // would be promoted into junction stubs at world zoom.
                            if class.layer == tilecodec::mamaps::dict::LAYER_ROADS
                                && class.flags & tilecodec::mamaps::body::FLAG_IS_LINK == 0
                                && way.refs.len() >= 2
                            {
                                if let Some(route) = crate::corridor::route_key(&way.tags) {
                                    state.2.push(crate::corridor::Segment {
                                        way_id: way.id,
                                        route,
                                        first_node: way.refs[0],
                                        last_node: way.refs[way.refs.len() - 1],
                                        min_zoom: class.min_zoom,
                                    });
                                }
                            }
                            let name = schema::display_name(&way.tags, class.layer);
                            // A road's carriageway lane count, per-lane turn masks and directional
                            // split, baked so the renderer can draw the lanes individually, place
                            // turn arrows and put the centre line where the traffic divides. Zero,
                            // empty and default for every other layer.
                            let is_road =
                                class.layer == tilecodec::mamaps::dict::LAYER_ROADS;
                            let lane_count = if is_road {
                                schema::roads::lane_count(&way.tags)
                            } else {
                                0
                            };
                            let (turn_fwd, turn_bwd) = if is_road {
                                schema::roads::turn_masks(&way.tags)
                            } else {
                                (Vec::new(), Vec::new())
                            };
                            let carriageway = if is_road {
                                schema::roads::carriageway(&way.tags)
                            } else {
                                Carriageway::default()
                            };
                            // A building's S3DB attributes, parsed once here beside its class.
                            // Zero-cost for the overwhelming majority of ways, which are not
                            // buildings.
                            let building = if class.layer == LAYER_BUILDINGS {
                                Some(schema::buildings::attrs(&way.tags))
                            } else {
                                None
                            };
                            state.0.push((
                                way.id,
                                Way {
                                    class,
                                    refs: way.refs.to_vec(),
                                    name,
                                    lane_count,
                                    turn_fwd,
                                    turn_bwd,
                                    carriageway,
                                    building,
                                },
                            ));
                        }
                    }
                    Element::Relation(relation) => {
                        // Two relation types carry a shape. Anything else — a site, a
                        // public_transport — does not.
                        if !matches!(
                            relation.tags.get_str("type"),
                            Some("multipolygon" | "boundary")
                        ) {
                            return Ok(());
                        }
                        if !select.matches(|k| relation.tags.get_str(k)) {
                            return Ok(());
                        }
                        if let Some(class) = schema::classify(&relation.tags, false, layers) {
                            let members: Vec<_> = relation
                                .members
                                .iter()
                                .filter(|m| m.kind == MEMBER_WAY)
                                .map(|m| (m.id, m.role == b"inner"))
                                .collect();
                            // **The schema decides**, not the relation's `type` tag. Both an
                            // administrative border and a protected area are `type=boundary`, and
                            // one is a line while the other is a shape — which is exactly the
                            // distinction `Class::area` exists to make.
                            let area = class.area;
                            let name = schema::display_name(&relation.tags, class.layer);
                            // A multipolygon building (a footprint with a courtyard) carries S3DB
                            // attributes just as a building way does.
                            let building = if class.layer == LAYER_BUILDINGS {
                                Some(schema::buildings::attrs(&relation.tags))
                            } else {
                                None
                            };
                            // A country's code, kept so the region shape below can stamp its
                            // driving side and centre-line colour onto the tiles it covers.
                            let iso = schema::boundaries::country_code(&relation.tags)
                                .map(str::to_string);
                            // An administrative relation yields *two* features: the border, which
                            // is a line and is what the basemap draws, and the region's shape,
                            // which nothing draws and the region mask reads. They cannot be one
                            // feature — a clipped polygon grows tile-edge segments and the border
                            // layer strokes them into a grid. See `schema::boundaries`.
                            if layers.boundaries {
                                if let Some(shape) =
                                    schema::boundaries::region_area(&relation.tags, false)
                                {
                                    state.1.push(Relation {
                                        class: shape,
                                        members: members.clone(),
                                        area: true,
                                        name: name.clone(),
                                        id: relation.id,
                                        building: None,
                                        iso: iso.clone(),
                                    });
                                }
                            }
                            state.1.push(Relation {
                                class,
                                members,
                                area,
                                name,
                                id: relation.id,
                                building,
                                iso: None,
                            });
                        }
                    }
                    Element::Node(_) => {}
                }
                Ok(())
            })?;
            Ok(kinds)
        },
        |(chunk_ways, chunk_relations, chunk_corridors)| {
            // Chunks arrive in file order and a PBF's ways are sorted by id, so appending here
            // leaves the file in ascending id order. `WaySink::push` refuses an id that does not
            // advance rather than letting an unsorted file reorder the archive silently.
            for (id, way) in chunk_ways {
                ways.push(
                    id,
                    &way.class,
                    &way.refs,
                    way.name.as_deref(),
                    way.lane_count,
                    &way.turn_fwd,
                    &way.turn_bwd,
                    way.carriageway,
                    way.building,
                )?;
            }
            relations.extend(chunk_relations);
            corridors.extend(chunk_corridors);
            Ok(())
        },
    )?;
    let WayCounts { ways: ways_classified, refs: way_refs, max_ref: way_max_ref } = ways.finish()?;
    stats.ways_classified = ways_classified;
    stats.relations_classified = relations.len() as u64;

    // A numbered road changes class along its length, so deciding `min_zoom` per way chops
    // a corridor into stubs at the zooms where only its motorway parts survive. Promote
    // each connected same-`ref` run to the shallowest zoom any of its ways asks for, then
    // drop the segments: only the (way id, zoom) overrides are needed from here.
    let promoted = crate::corridor::promote(&corridors);
    stats.corridor_promotions = promoted.len() as u64;
    drop(corridors);
    mark("corridors promoted");

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
    stats.lanes_inherited = inherited_lanes.len() as u64;
    mark("lane counts inherited");

    // --- materialise ----------------------------------------------------------------------
    //
    // Ways in **id order**, which is the order the spill file is already in. It used to be a sort of
    // a `HashMap`'s keys, for the same reason: the output has to be reproducible and hash order is
    // not. The one place in this pipeline where that was a real risk, and now it is a property of
    // the file rather than a step that could be forgotten.
    mark("materialising");
    let mut sink = Sink::create(spill_path)?;

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