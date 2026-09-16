/// One relation's geometry, built off the shared tables with nothing mutable touched, so a batch of
/// them fans out across the pool exactly as [`materialise_ways`] does. The sink and the conventions
/// grid are fed from these **in relation order** in the serial drain, which is what keeps the
/// archive byte-identical: the drain order is the spill order is the archive's.
enum BuiltRelation {
    /// A label relation centroided to a point, `None` when it had no locatable members.
    Label(Option<(f64, f64)>),
    /// A border relation's member lines, already filtered to those with two or more points.
    Border(Vec<Vec<(f64, f64)>>),
    /// An area relation's stitched rings and the ring stats that stitching produced. The stats are
    /// per-relation here and summed into [`Stats::rings`] serially — they are four additive counts,
    /// so the sum is the same in any order.
    Area { polygons: Vec<rings::Polygon>, rings: RingStats },
}

/// Build one relation's geometry. Reads only the shared `members` map and the resolved `table`,
/// both immutable, so it is safe to call across the pool. See [`BuiltRelation`].
fn build_relation(
    relation: &Relation,
    members: &HashMap<i64, Vec<i64>>,
    table: &NodeLocations,
) -> BuiltRelation {
    // A `places` relation (a country, a region) is labelled at its centroid: one point, not a
    // stitched shape. The border itself lives in `boundaries`; this is the name.
    if is_label(relation.class.layer) {
        let line: Vec<(f64, f64)> = relation
            .members
            .iter()
            .filter_map(|(id, _)| members.get(id))
            .flat_map(|refs| table.line(refs))
            .collect();
        return BuiltRelation::Label(centroid(&line));
    }
    // A boundary relation's members are the border. Each is emitted as its own line rather than
    // stitched: the renderer strokes them, and a gap between two member ways is invisible in a
    // stroke while a failed stitch would drop the whole border.
    if !relation.area {
        let lines: Vec<Vec<(f64, f64)>> = relation
            .members
            .iter()
            .filter_map(|(id, _)| members.get(id))
            .map(|refs| table.line(refs))
            .filter(|line| line.len() >= 2)
            .collect();
        return BuiltRelation::Border(lines);
    }
    let member_ways: Vec<MemberWay> = relation
        .members
        .iter()
        .filter_map(|(id, inner)| {
            Some(MemberWay { refs: members.get(id)?.clone(), outer: !inner })
        })
        .collect();
    let mut rings = RingStats::default();
    let polygons = rings::assemble(&member_ways, |id| locate(table, id), &mut rings);
    BuiltRelation::Area { polygons, rings }
}

/// Materialise relations; returns the driving-side conventions.
fn materialise_relations(
    relations: &[Relation],
    members: &HashMap<i64, Vec<i64>>,
    table: &NodeLocations,
    sink: &mut Sink,
    stats: &mut Stats,
) -> Result<schema::boundaries::Conventions> {
    let mut bar = Progress::new(
        "Materialise: relations".to_string(),
        relations.len(),
        "relation(s)",
        true,
    );
    // Which driving side and centre-line colour applies where, accumulated as the country shapes
    // go past.
    let mut conventions = schema::boundaries::Conventions::default();
    // Batched, the [`materialise_ways`] pattern: `rings::assemble`, `centroid` and `table.line` are
    // the heavy per-relation work, are independent, and touch no shared mutable state, so a batch of
    // them goes as wide as the pool. The sink and the conventions grid then take the results **in
    // relation order**, which keeps the archive byte-identical. `conventions.add` in particular has
    // precedence rules (an interior claim outranks a border one), so it must stay serial and in
    // order.
    const MATERIALISE_RELATIONS_BATCH: usize = 4096;
    let mut built: Vec<BuiltRelation> = Vec::with_capacity(MATERIALISE_RELATIONS_BATCH);
    for batch in relations.chunks(MATERIALISE_RELATIONS_BATCH) {
        built.clear();
        par::install(|| {
            batch
                .par_iter()
                .map(|relation| build_relation(relation, members, table))
                .collect_into_vec(&mut built);
        });
        for (relation, geometry) in batch.iter().zip(built.drain(..)) {
            match geometry {
                BuiltRelation::Label(Some(point)) => {
                    sink.push_named(
                        &relation.class,
                        &Geometry::Points(vec![point]),
                        relation.name.as_deref(),
                        tagged_id(relation.id, ELEMENT_RELATION),
                    )?;
                    stats.features += 1;
                }
                BuiltRelation::Label(None) => stats.geometry_failed += 1,
                BuiltRelation::Border(lines) => {
                    if lines.is_empty() {
                        stats.geometry_failed += 1;
                    } else {
                        sink.push(&relation.class, &Geometry::Lines(lines))?;
                        stats.features += 1;
                    }
                }
                BuiltRelation::Area { polygons, rings } => {
                    // Accumulated whether or not the stitch produced anything, exactly as the
                    // in-place `&mut stats.rings` did: a failed stitch still counted its unclosed
                    // and orphan rings.
                    stats.rings.outer_rings += rings.outer_rings;
                    stats.rings.inner_rings += rings.inner_rings;
                    stats.rings.unclosed += rings.unclosed;
                    stats.rings.orphan_inner += rings.orphan_inner;
                    if polygons.is_empty() {
                        stats.geometry_failed += 1;
                    } else {
                        // The id is what lets the mask gather a region's tile-clipped pieces back
                        // together.
                        let id = if tracks_ids(&relation.class) {
                            tagged_id(relation.id, ELEMENT_RELATION)
                        } else {
                            tilecodec::mamaps::body::ID_NONE
                        };
                        // A multipolygon building carries its S3DB attributes into the building side
                        // table, just as a building way does.
                        if let Some(building) = relation.building {
                            sink.push_building(
                                &relation.class,
                                &Geometry::Polygons(polygons),
                                None,
                                building,
                            )?;
                        } else {
                            // The country's own shape is also what says how its roads are marked.
                            // Stamped before the shape is moved into the sink, from the assembled
                            // rings, so the grid and the border line agree about where it is.
                            if let Some(iso) = &relation.iso {
                                conventions.add(iso, &polygons);
                            }
                            sink.push_named(
                                &relation.class,
                                &Geometry::Polygons(polygons),
                                None,
                                id,
                            )?;
                        }
                        stats.features += 1;
                    }
                }
            }
            bar.tick("relation(s)");
        }
    }
    bar.finish("relation(s)");

    Ok(conventions)
}

/// Coastline, transit, graph, then finish the store. Moved whole from `extract`.
fn append_external_and_finish(
    coastline: &Path,
    transit_routes: &Path,
    graph: &Path,
    mut sink: Sink,
    stats: &mut Stats,
    conventions: schema::boundaries::Conventions,
    spill_path: &Path,
    mark: &dyn Fn(&str),
) -> Result<Store> {
    // The mainland, last, because clipping it needs the extract's own bounding box and that is
    // only known once every OSM feature has been through the sink. Order in the file does not
    // matter: the tiler groups by layer id, so `earth` is the first layer of every body whenever it
    // was written.
    match sink.bbox_degrees() {
        Some(bbox) => {
            println!(
                "reading land polygons within {:.3},{:.3} .. {:.3},{:.3}",
                bbox.0, bbox.1, bbox.2, bbox.3,
            );
            stats.land_polygons = schema::earth::stream_prepared(coastline, bbox, &mut sink)?;
            stats.features += stats.land_polygons;
        }
        // Nothing to clip against. Land alone would be an archive of one layer, and the caller
        // almost certainly pointed at the wrong extract.
        None => return err("the extract produced no features to place land against".to_string()),
    }
    // The `transit` layer, for the same reason and against the same bounding box: its geometry is
    // GTFS rather than OSM (see [`schema::transit`]), so it is read here rather than classified in
    // any of the passes above.
    match sink.bbox_degrees() {
        Some(bbox) => {
            println!(
                "reading transit routes within {:.3},{:.3} .. {:.3},{:.3}",
                bbox.0, bbox.1, bbox.2, bbox.3,
            );
            stats.transit_routes = schema::transit::stream_routes(transit_routes, bbox, &mut sink)?;
            stats.features += stats.transit_routes;
        }
        None => {
            return err("the extract produced no features to place transit routes against"
                .to_string())
        }
    }
    // The `traffic` layer, last of the non-OSM sources: its geometry is the v6 routing graph
    // (see [`schema::traffic`]), read straight off disk rather than classified from the `.pbf`.
    // Not clipped to the bbox here — the graph is already the built region, and the tiler clips
    // each component segment per tile like any other line.
    println!("reading the v6 routing graph at {} for the traffic layer", graph.display());
    stats.traffic_segments = schema::traffic::stream_graph(graph, &mut sink)?;
    stats.features += stats.traffic_segments;
    println!("  {} drivable component segment(s)", stats.traffic_segments);
    // The `junction` layer rides the same graph: a lane connector is built from the junction
    // node's own incident edges, so there is nothing to read that the traffic pass did not
    // already need. `conventions` decides which side of a road the direction of travel sits
    // on, and is borrowed here because it is moved into the store below.
    println!("building lane connectors from the v6 routing graph for the junction layer");
    stats.junction_connectors = schema::junction::stream_junctions(graph, &conventions, &mut sink)?;
    stats.features += stats.junction_connectors;
    println!("  {} lane connector(s)", stats.junction_connectors);
    // `sink.finish` itself is a flush, not a loop: every feature was serialised and its coordinates
    // e7-quantised in the `push` that spilled it, back in `materialise_ways`/`materialise_relations`
    // (both parallel, both under a "Materialise:" bar) and in the streams above. The spill's byte
    // order is the archive's, so that write stays serial by construction — there is nothing here to
    // parallelise. The bar makes the finalise step visible rather than a silent tail of the ~25 s
    // this phase costs; `stats.features` is the count the sink is closing over.
    let bar = Progress::new(
        "Finalise: features".to_string(),
        stats.features as usize,
        "feature(s)",
        true,
    );
    let store = sink.finish(spill_path)?;
    bar.finish("feature(s)");
    let store = store.with_conventions(conventions);
    // The one phase that had no mark after it, and it turned out to be the largest single item in the
    // build outside tiling: ~25 s of a 136 s California run. It is 170 M coordinate lookups through
    // the mapped node table plus 3.3 GB of spill written, all on one thread.
    mark("materialised and spilled");

    Ok(store)
}

/// Node refs, duplicates included, above which the bitset replaces the sorted vector.
///
/// 256 M refs is a 2 GB `Vec<i64>` plus a sort of 256 M elements, and it is roughly a us-west
/// extract. Below it the vector is smaller than the bitset and this stays exactly as it always was;
/// above it the vector is the largest thing in stage A and the bitset is a constant.
const REFS_IN_MEMORY: usize = 256 << 20;

/// The node id a bitset will not be sized past.
///
/// OSM assigns node ids monotonically and is near 13 G, so this is roughly 4x today's high-water
/// mark and 2.6 GB of bitset. It is a bound on the *data*, and a synthetic or non-OSM input with one
/// sparse enormous id would otherwise ask for a bitset the size of the id, so it is an error rather
/// than a clamp: a clamp would silently drop the nodes above it.
const MAX_NODE_ID: i64 = 48 << 30;

/// The ids pass 3 must resolve, collected the way stage A always did.
///
/// One exactly-sized vector, then [`NodeLocations::new`] sorts and dedups it in place. Sized rather
/// than grown into because a `Vec` that doubles its way to 200 M ids holds the old and the new
/// allocation at once across the last realloc -- 1.1 GB plus 2.1 GB, for a length both earlier passes
/// already know.
fn collect_needed_in_memory(
    ways_path: &Path,
    members: &HashMap<i64, Vec<i64>>,
    refs_total: usize,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    let mut needed: Vec<i64> = Vec::with_capacity(refs_total);
    {
        let mut reader = WayReader::open(ways_path)?;
        let mut refs: Vec<i64> = Vec::new();
        while reader.next(&mut refs)?.is_some() {
            needed.extend_from_slice(&refs);
        }
    }
    // A member way that pass 1 also classified already contributed its refs above; `members` adds
    // the ones — the great majority, since a multipolygon's rings usually carry no tags of their
    // own — that nothing else asked for.
    for refs in members.values() {
        needed.extend_from_slice(refs);
    }
    mark("refs collected");
    NodeLocations::new(needed)
}

/// The same ids, through a bitset over the node id space.
///
/// The vector above is `way_refs + member_refs` entries of eight bytes **with duplicates**, then a
/// sort of all of them. On a north-america extract that is ~19 GB; on a planet one it projects to
/// ~96 GB and a sort of 12 G elements, which is the second of the two things that stopped a planet
/// build before anything else got the chance to.
///
/// A bit per node id costs `max_id / 8` and **does not scale with the extract**: OSM node ids are
/// assigned monotonically and top out near 13 G, so this is at most ~1.7 GB for any extract, forever.
/// Streaming the refs into it and then walking it low to high yields sorted unique ids directly --
/// which removes the vector *and* the sort in one pass, with no extra I/O.
///
/// Not an external merge sort of the ref stream. That is the general answer and needs no assumption
/// about the ids, and it costs ~96 GB written and ~96 GB read at planet scale to compute what this
/// gets in one pass. The bound that makes the bitset safe -- monotonic, bounded ids -- is a property
/// of OSM's data rather than a hope about it, and [`MAX_NODE_ID`] is where that property is enforced.
fn collect_needed_by_bitset(
    ways_path: &Path,
    members: &HashMap<i64, Vec<i64>>,
    max_ref: i64,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    let mut bits = NeededBits::new(max_ref)?;
    {
        let mut reader = WayReader::open(ways_path)?;
        let mut refs: Vec<i64> = Vec::new();
        while reader.next(&mut refs)?.is_some() {
            for &id in &refs {
                bits.set(id)?;
            }
        }
    }
    for refs in members.values() {
        for &id in refs {
            bits.set(id)?;
        }
    }
    mark("refs collected");
    // Ascending and unique by construction, which is `from_sorted`'s whole precondition.
    NodeLocations::from_sorted(bits.ids(), bits.len())
}

/// One bit per node id in `0..=max_id`.
struct NeededBits {
    words: Vec<u64>,
    /// `max_id + 1`. Kept because the last word is padded and a ref landing in that padding is out
    /// of range, not merely addressable.
    bits: usize,
    count: usize,
}

impl NeededBits {
    fn new(max_id: i64) -> Result<NeededBits> {
        if max_id > MAX_NODE_ID {
            return err(format!(
                "node id {max_id} is past the {MAX_NODE_ID} this build will size a bitset for; \
                 OSM's own ids are near 13 billion, so this input is not an OSM extract"
            ));
        }
        // `max_id` is a maximum, not a count, so the space is one larger.
        let bits = max_id.max(0) as usize + 1;
        Ok(NeededBits { words: vec![0u64; bits.div_ceil(64)], bits, count: 0 })
    }

    fn set(&mut self, id: i64) -> Result<()> {
        // A negative ref is not a node this planet has. Refused rather than skipped: it means the
        // spill or the PBF is not what it claims, and skipping would show as a way with a gap in it.
        if id < 0 {
            return err(format!("node ref {id} is negative"));
        }
        let at = id as usize;
        if at >= self.bits {
            return err(format!(
                "node ref {id} is past the {} the bitset was sized for",
                self.bits - 1
            ));
        }
        let word = &mut self.words[at / 64];
        let bit = 1u64 << (at % 64);
        if *word & bit == 0 {
            *word |= bit;
            self.count += 1;
        }
        Ok(())
    }

    /// How many distinct ids were set. The `len` [`NodeLocations::from_sorted`] sizes from.
    fn len(&self) -> usize {
        self.count
    }

    /// Every set id, ascending. `trailing_zeros` on a word at a time, so an empty stretch of the id
    /// space -- which is most of it, since a needed set is a subset of a subset -- costs one compare
    /// per 64 ids rather than one per id.
    fn ids(&self) -> impl Iterator<Item = i64> + '_ {
        self.words.iter().enumerate().flat_map(|(w, word)| {
            let mut rest = *word;
            std::iter::from_fn(move || {
                if rest == 0 {
                    return None;
                }
                let bit = rest.trailing_zeros() as usize;
                rest &= rest - 1;
                Some((w * 64 + bit) as i64)
            })
        })
    }
}
