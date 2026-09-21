/// Read `input` and spill every feature the schema classifies to `spill_path`.
///
/// Features go to disk rather than into a `Vec`, because holding them was 4.9 GB of a measured
/// 10.03 GB California peak and nothing reads them until the tiler does. Classified ways go to a
/// scratch file beside it for the same reason. See [`crate::store`].
///
/// `region` filters the **built** layers (`roads`, `poi`, `buildings`) to the
/// region's bbox. Every other layer is always included regardless. `None`
/// (world) disables the filter entirely.
pub fn extract(
    input: &Path,
    layers: Layers,
    coastline: &Path,
    transit_routes: &Path,
    graph: &Path,
    spill_path: &Path,
    region: Option<osm_ingest::bbox::BBox>,
) -> Result<(Store, Stats, HashMap<u64, u64>)> {
    // Stage A's boundaries are printed with their elapsed time so an external RSS sampler can say
    // which of them the peak belongs to. Three candidates sit within seconds of each other -- the ref
    // vector, the id index built beside it, and the node pass's per-chunk accumulators -- and
    // guessing between them has already cost more than printing them does.
    let started = std::time::Instant::now();
    let mark = |what: &str| {
        println!("  [stage A] {what} at {:.1}s", started.elapsed().as_secs_f64());
    };
    let blobs = pbf::scan_blobs(input)?;
    let select = Select::parse(&schema::filters())?;
    let mut stats = Stats::default();
    // Scratch for this function alone: written in pass 1, read twice below, and removed as soon as
    // the last read is done. Beside the feature spill, so a build directed at a writable output
    // directory needs nothing else to be writable.
    let ways_path = spill_path.with_extension("ways.tmp");

    // A blob-kinds mask cached beside the routing graph -- `road_graph`'s pass 1 wrote it over this
    // same `.pbf` -- lets pass 1 skip node blobs outright rather than inflating them to learn they
    // hold no ways or relations. Guarded by blob count, file length and mtime, so a missing or stale
    // sidecar simply falls back to a full scan. Byte-neutral either way: the mask `run_pass1` returns
    // carries the skipped blobs' kinds, so it is a complete description of the file regardless.
    //
    // The sidecar also proves the graph was built over THIS pbf: when the
    // region filter is on, a world graph beside a california tile build (or
    // vice versa) would silently mismatch layers. A missing sidecar here only
    // warns — the blob mask itself is optional — but a region build still
    // validates the graph's own coverage below in `append_external_and_finish`.
    let graph_kinds = pbf::load_blob_kinds(graph, input, &blobs);
    if graph_kinds.is_some() {
        mark("blob kinds loaded from graph sidecar");
    }
    if let Some(b) = region.as_ref() {
        println!(
            "region filter: keeping roads/pois/buildings touching {:.3},{:.3} .. {:.3},{:.3}",
            b.min_lon, b.min_lat, b.max_lon, b.max_lat,
        );
    }

    let pass1 = run_pass1(input, &blobs, graph_kinds.as_deref(), &select, layers, &ways_path, &mut stats, &mark)?;
    let members = load_member_ways(input, &blobs, &pass1.blob_kinds, &pass1.relations)?;
    // Created before the fused node pass below, which spills `places`/`poi` label nodes straight
    // into it. This sits where pass 4's `Sink::create` used to, moved earlier so those labels reach
    // the sink in the same order -- chunk order, ahead of every way and relation -- they did when
    // classification was its own pass after the coordinate resolve. Nothing else touches
    // `spill_path` until then; the lane-fill temps hang off `with_extension`.
    // Spills stage in anonymous pagefile memory (no `.tmp` files): see `anon`.
    let mut sink = Sink::create(spill_path)?;
    // The ways spill, shared by refcount with every reader below (collectors,
    // lanefill scan, materialise).
    let ways_anon = pass1.ways_anon.clone();
    let table = build_resolved_table(
        input,
        &blobs,
        &pass1.blob_kinds,
        &ways_path,
        ways_anon.clone(),
        &members,
        pass1.way_refs,
        pass1.way_max_ref,
        &select,
        layers,
        region.as_ref(),
        &mut sink,
        &mut stats,
        &mark,
    )?;
    let inherited_lanes = inherit_lane_counts(
        spill_path,
        &ways_path,
        ways_anon.clone(),
        &table,
        stats.ways_classified as usize,
    )?;
    stats.lanes_inherited = inherited_lanes.len() as u64;
    mark("lane counts inherited");

    // --- materialise ----------------------------------------------------------------------
    //
    // Ways in **id order**, which is the order the spill file is already in. The label nodes were
    // already spilled into `sink` during the fused node pass above, so this only *adds* ways and
    // relations after them -- the order the tiler groups by layer id from.
    mark("materialising");
    materialise_ways(
        &ways_path,
        ways_anon.clone(),
        &pass1.promoted,
        &inherited_lanes,
        &table,
        region.as_ref(),
        &mut sink,
        &mut stats,
    )?;
    let conventions = materialise_relations(
        &pass1.relations,
        &members,
        &table,
        region.as_ref(),
        &mut sink,
        &mut stats,
    )?;
    let store = append_external_and_finish(
        coastline,
        transit_routes,
        graph,
        sink,
        &mut stats,
        conventions,
        spill_path,
        &mark,
    )?;
    // The place-label -> boundary link, tagged into the id space the archive uses (a place carries
    // its node id tagged as a node; the mask keys on the boundary's relation id tagged as a
    // relation). The tiler stamps each `places` label with its linked id from this.
    let region_links: HashMap<u64, u64> = pass1
        .region_label_nodes
        .iter()
        .map(|(&node, &rel)| (tagged_id(node, ELEMENT_NODE), tagged_id(rel, ELEMENT_RELATION)))
        .collect();
    Ok((store, stats, region_links))
}

/// What pass 1 hands the later phases: relations stay resident, corridors
/// collapse to (way id, zoom) overrides, and the ways spill stages in
/// anonymous memory, shared by refcount with every later reader.
struct Pass1Out {
    relations: Vec<Relation>,
    promoted: Vec<(i64, u8)>,
    blob_kinds: Vec<u8>,
    way_refs: u64,
    way_max_ref: i64,
    /// The sealed ways spill, shared by refcount with every later reader.
    ways_anon: std::sync::Arc<tile_build::anon::AnonStore>,
    /// `admin_centre`/`label` node member -> its boundary relation id, both raw OSM ids, for every
    /// relation that carries a region shape. The authoritative half of the place->boundary link:
    /// OSM points a boundary relation at its own label node directly, so no geometry test is needed.
    /// `label` wins over `admin_centre` when a relation lists both, since `label` is the place node
    /// the map actually renders.
    region_label_nodes: HashMap<i64, i64>,
}

/// Pass 1 (ways + relations) plus the corridor promotion. Moved whole from
/// `extract` so part files can cut at fn boundaries; behavior identical.
///
/// `blob_kinds_in` is an optional pre-computed mask (the graph sidecar) that lets this pass skip
/// node blobs; `None` means scan the whole file. Either way the mask it returns is complete.
#[allow(clippy::too_many_arguments)]
fn run_pass1(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds_in: Option<&[u8]>,
    select: &Select,
    layers: Layers,
    ways_path: &Path,
    stats: &mut Stats,
    mark: &dyn Fn(&str),
) -> Result<Pass1Out> {
    // --- pass 1: ways and relations -------------------------------------------------------
    //
    // `run_pass_sink` rather than `run_pass`, because `run_pass`'s contract is to hand back every
    // chunk at once and these chunks hold the node refs of every classified way — the 2.7 GB this
    // spill exists to be rid of. Here a chunk is appended to the file and freed while its
    // neighbours are still decoding.
    //
    // `blob_kinds` comes back from this pass and lets the later ones skip whole blobs, which on a
    // planet extract is most of the file.
    let mut ways = WaySink::create_anon(&ways_path)?;
    let mut relations: Vec<Relation> = Vec::new();
    // `admin_centre`/`label` node member -> boundary relation id (raw), gathered as region shapes
    // are classified below. See [`Pass1Out::region_label_nodes`].
    let mut region_label_nodes: HashMap<i64, i64> = HashMap::new();
    // Road ways carrying a `ref`, for the corridor pass below. A small minority of ways,
    // and the only thing pass 1 keeps in memory besides the relations.
    let mut corridors: Vec<crate::corridor::Segment> = Vec::new();
    let blob_kinds = pbf::run_pass_sink(
        input,
        &blobs,
        blob_kinds_in,
        KIND_WAYS | KIND_RELATIONS,
        "Pass 1: ways and relations",
        || {
            (
                Vec::<(i64, Way)>::new(),
                Vec::<Relation>::new(),
                Vec::<crate::corridor::Segment>::new(),
                Vec::<(i64, i64)>::new(),
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
                            if let Some(shape) =
                                schema::boundaries::region_area(&relation.tags, false)
                            {
                                // The boundary points at its own label/centre node directly: record
                                // that node -> this relation so the place label it becomes can carry
                                // the id. `label` overwrites `admin_centre` (the label node is the
                                // one that renders; admin_centre is the capital, a different node).
                                for m in relation.members.iter() {
                                    if m.kind == MEMBER_NODE && m.role == b"admin_centre" {
                                        state.3.push((m.id, relation.id));
                                    }
                                }
                                for m in relation.members.iter() {
                                    if m.kind == MEMBER_NODE && m.role == b"label" {
                                        state.3.push((m.id, relation.id));
                                    }
                                }
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
        |(chunk_ways, chunk_relations, chunk_corridors, chunk_label_nodes)| {
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
            // `label` was pushed after `admin_centre`, so inserting in order lets the label win.
            for (node_id, rel_id) in chunk_label_nodes {
                region_label_nodes.insert(node_id, rel_id);
            }
            Ok(())
        },
    )?;
    let (counts, store) = ways.finish_anon()?;
    stats.ways_classified = counts.ways;
    let (way_refs, way_max_ref, ways_anon) =
        (counts.refs, counts.max_ref, std::sync::Arc::new(store));
    stats.relations_classified = relations.len() as u64;

    // A numbered road changes class along its length, so deciding `min_zoom` per way chops
    // a corridor into stubs at the zooms where only its motorway parts survive. Promote
    // each connected same-`ref` run to the shallowest zoom any of its ways asks for, then
    // drop the segments: only the (way id, zoom) overrides are needed from here.
    let promoted = crate::corridor::promote(&corridors);
    stats.corridor_promotions = promoted.len() as u64;
    drop(corridors);
    mark("corridors promoted");

    Ok(Pass1Out {
        relations,
        promoted,
        blob_kinds,
        way_refs,
        way_max_ref,
        ways_anon,
        region_label_nodes,
    })
}