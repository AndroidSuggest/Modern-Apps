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

    let mut bar = Progress::new(
        "Materialise: relations".to_string(),
        relations.len(),
        "relation(s)",
        true,
    );
    // Which driving side and centre-line colour applies where, accumulated as the country shapes
    // go past. Empty on a build with `boundaries` switched off, because nothing classifies an
    // administrative relation then and there is no country geometry to resolve against — such a
    // build's tiles carry no convention and the renderer falls back to right-hand and white.
    let mut conventions = schema::boundaries::Conventions::default();
    for relation in &relations {
        bar.tick("relation(s)");
        // A `places` relation (a country, a region) is labelled at its centroid: one point, not
        // a stitched shape. The border itself lives in `boundaries`; this is the name.
        if is_label(relation.class.layer) {
            let line: Vec<(f64, f64)> = relation
                .members
                .iter()
                .filter_map(|(id, _)| members.get(id))
                .flat_map(|refs| table.line(refs))
                .collect();
            match centroid(&line) {
                Some(point) => {
                    sink.push_named(
                        &relation.class,
                        &Geometry::Points(vec![point]),
                        relation.name.as_deref(),
                        tagged_id(relation.id, ELEMENT_RELATION),
                    )?;
                    stats.features += 1;
                }
                None => stats.geometry_failed += 1,
            }
            continue;
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
            if lines.is_empty() {
                stats.geometry_failed += 1;
                continue;
            }
            sink.push(&relation.class, &Geometry::Lines(lines))?;
            stats.features += 1;
            continue;
        }
        let member_ways: Vec<MemberWay> = relation
            .members
            .iter()
            .filter_map(|(id, inner)| {
                Some(MemberWay { refs: members.get(id)?.clone(), outer: !inner })
            })
            .collect();
        let polygons = rings::assemble(&member_ways, |id| locate(&table, id), &mut stats.rings);
        if polygons.is_empty() {
            stats.geometry_failed += 1;
            continue;
        }
        // The id is what lets the mask gather a region's tile-clipped pieces back together.
        let id = if tracks_ids(&relation.class) {
            tagged_id(relation.id, ELEMENT_RELATION)
        } else {
            tilecodec::mamaps::body::ID_NONE
        };
        // A multipolygon building carries its S3DB attributes into the building side table, just
        // as a building way does.
        if let Some(building) = relation.building {
            sink.push_building(&relation.class, &Geometry::Polygons(polygons), None, building)?;
        } else {
            // The country's own shape is also what says how its roads are marked. Stamped before
            // the shape is moved into the sink, from the assembled rings rather than from a
            // separate geometry, so the grid and the border line agree about where the country is.
            if let Some(iso) = &relation.iso {
                conventions.add(iso, &polygons);
            }
            sink.push_named(&relation.class, &Geometry::Polygons(polygons), None, id)?;
        }
        stats.features += 1;
    }
    bar.finish("relation(s)");

    // The mainland, last, because clipping it needs the extract's own bounding box and that is
    // only known once every OSM feature has been through the sink. Order in the file does not
    // matter: the tiler groups by layer id, so `earth` is the first layer of every body whenever it
    // was written.
    if let Some(path) = coastline {
        match sink.bbox_degrees() {
            Some(bbox) => {
                println!(
                    "reading land polygons within {:.3},{:.3} .. {:.3},{:.3}",
                    bbox.0, bbox.1, bbox.2, bbox.3,
                );
                stats.land_polygons = schema::earth::stream_prepared(path, bbox, &mut sink)?;
                stats.features += stats.land_polygons;
            }
            // Nothing to clip against. Land alone would be an archive of one layer, and the caller
            // almost certainly pointed at the wrong extract.
            None => return err("the extract produced no features to place land against".to_string()),
        }
    }
    // The `transit` layer, for the same reason and against the same bounding box: its geometry is
    // GTFS rather than OSM (see [`schema::transit`]), so it is read here rather than classified in
    // any of the passes above.
    if let Some(path) = transit_routes {
        match sink.bbox_degrees() {
            Some(bbox) => {
                println!(
                    "reading transit routes within {:.3},{:.3} .. {:.3},{:.3}",
                    bbox.0, bbox.1, bbox.2, bbox.3,
                );
                stats.transit_routes = schema::transit::stream_routes(path, bbox, &mut sink)?;
                stats.features += stats.transit_routes;
            }
            None => {
                return err("the extract produced no features to place transit routes against"
                    .to_string())
            }
        }
    }
    // The `traffic` layer, last of the non-OSM sources: its geometry is the v6 routing graph
    // (see [`schema::traffic`]), read straight off disk rather than classified from the `.pbf`.
    // Not clipped to the bbox here — the graph is already the built region, and the tiler clips
    // each component segment per tile like any other line.
    if let Some(dir) = graph {
        println!("reading the v6 routing graph at {} for the traffic layer", dir.display());
        stats.traffic_segments = schema::traffic::stream_graph(dir, &mut sink)?;
        stats.features += stats.traffic_segments;
        println!("  {} drivable component segment(s)", stats.traffic_segments);
        // The `junction` layer rides the same graph: a lane connector is built from the junction
        // node's own incident edges, so there is nothing to read that the traffic pass did not
        // already need. `conventions` decides which side of a road the direction of travel sits
        // on, and is borrowed here because it is moved into the store below.
        stats.junction_connectors = schema::junction::stream_junctions(dir, &conventions, &mut sink)?;
        stats.features += stats.junction_connectors;
        println!("  {} lane connector(s)", stats.junction_connectors);
    }
    let store = sink.finish(spill_path)?;
    let store = store.with_conventions(conventions);
    // The one phase that had no mark after it, and it turned out to be the largest single item in the
    // build outside tiling: ~25 s of a 136 s California run. It is 170 M coordinate lookups through
    // the mapped node table plus 3.3 GB of spill written, all on one thread.
    mark("materialised and spilled");
    Ok((store, stats))
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

/// A node's location in lon/lat, which is the order [`rings::assemble`] and GeoJSON both want.
///
/// [`NodeLocations::get`] returns lat/lon, matching the PBF's own field order.
fn locate(table: &NodeLocations, id: i64) -> Option<(f64, f64)> {
    let (lat_e7, lon_e7) = table.get(id)?;
    Some((lon_e7 as f64 * 1e-7, lat_e7 as f64 * 1e-7))
}

/// Is this a label layer (`places`/`poi`)? Labels are points with names: ways mapped as areas
/// are centroided to one, relations likewise, and nodes spill directly.
pub(crate) fn is_label(layer: u8) -> bool {
    use tilecodec::mamaps::dict::{LAYER_PLACES, LAYER_POI};
    layer == LAYER_PLACES || layer == LAYER_POI
}

/// Does this layer have an id side table at all?
///
/// Layer-level, and separate from [`tracks_ids`], because the table is indexed by feature
/// position: every feature in such a layer needs an entry, `ID_NONE` included, or the table stops
/// lining up with the features it describes.
pub(crate) fn layer_tracks_ids(layer: u8) -> bool {
    is_label(layer)
        || layer == tilecodec::mamaps::dict::LAYER_BOUNDARIES
        || layer == tilecodec::mamaps::dict::LAYER_TRAFFIC
}

/// May this feature carry a non-zero id?
///
/// Wider than [`is_label`], and deliberately a separate predicate: `is_label` also means
/// "centroid this to a point", which a region's shape must not be. `boundaries` needs ids for
/// a different reason — a region is stored as one clipped polygon per tile, so without an id
/// there is nothing to say which pieces are the same region, and the mask can only punch out
/// the piece under the finger.
///
/// Keyed on the whole class rather than the layer because `boundaries` holds both kinds of
/// geometry: the region's shape, which is an area and keeps its id, and the border, which is a
/// line and must not. `coalesce` merges adjacent border lines, and the survivor's id would be
/// whichever member happened to come first.
pub(crate) fn tracks_ids(class: &crate::schema::Class) -> bool {
    use tilecodec::mamaps::dict::{LAYER_BOUNDARIES, LAYER_TRAFFIC};
    is_label(class.layer)
        || (class.layer == LAYER_BOUNDARIES && class.area)
        || class.layer == LAYER_TRAFFIC
}
