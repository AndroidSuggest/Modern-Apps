/// Pass 2: refs of every relation member way. Moved whole from `extract`.
fn load_member_ways(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    relations: &[Relation],
) -> Result<MemberWays> {
    // --- pass 2: the refs of every relation member way ------------------------------------
    //
    // **Every** member, not just the ones pass 1 did not classify. A relation reaches its members by
    // id, in the order it lists them, which is the one random access in this stage and the one thing
    // a sequential spill file cannot serve. So the members — and only the members — stay resident,
    // and that is what lets the other several million classified ways go to disk. California has
    // 63 156 relations, so this table is small next to the one it replaces; planet has 8.4 M, so
    // it is a sorted vector rather than a hash map (see [`MemberWays`]).
    let wanted: Vec<i64> = {
        let mut wanted: Vec<i64> = relations
            .iter()
            .flat_map(|r| r.members.iter().map(|(id, _)| *id))
            .collect();
        wanted.sort_unstable();
        wanted.dedup();
        wanted
    };
    let mut members = MemberWays::empty();
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
        // Chunks arrive in file order and ways are sorted by id, so concatenation is sorted as
        // long as no way id appears in two chunks (ids are unique in the PBF). Sortedness is
        // asserted, not assumed: `MemberWays::build` sorts anyway, which is also what absorbs
        // the (impossible in practice) duplicate across chunks.
        let all: Vec<(i64, Vec<i64>)> = chunks.into_iter().flatten().collect();
        members = MemberWays::build(all);
    }

    Ok(members)
}

/// Relation member ways by id: the one random access in stage A.
///
/// A `HashMap<i64, Vec<i64>>` costs ~48 B overhead per entry plus the hasher's table slack --
/// at planet's 8.4 M member ways that is hundreds of MB beside the ref bytes themselves. This is
/// a sorted `Vec` with binary search instead: lookups are `O(log n)` rather than `O(1)`, but a
/// lookup is followed by cloning a ref vec and resolving every ref through the coordinate table,
/// so the search is noise next to the use. Built sorted once; every reader shares the shape.
pub struct MemberWays {
    entries: Vec<(i64, Vec<i64>)>,
}

impl MemberWays {
    fn empty() -> MemberWays {
        MemberWays { entries: Vec::new() }
    }

    /// Build from `(way id, refs)` pairs in any order. Sorts by id; a duplicated id keeps its
    /// first occurrence (ids are unique in the PBF, so this is defence, not logic).
    fn build(mut all: Vec<(i64, Vec<i64>)>) -> MemberWays {
        all.sort_by_key(|(id, _)| *id);
        all.dedup_by_key(|(id, _)| *id);
        MemberWays { entries: all }
    }

    /// The refs of member way `id`, or `None` if pass 2 never saw it (an extract cut it away).
    pub fn get(&self, id: i64) -> Option<&Vec<i64>> {
        self.entries
            .binary_search_by_key(&id, |(id, _)| *id)
            .ok()
            .map(|at| &self.entries[at].1)
    }

    /// Every member's refs, for the ref collectors (order irrelevant: both collectors only add).
    pub fn values(&self) -> impl Iterator<Item = &Vec<i64>> {
        self.entries.iter().map(|(_, refs)| refs)
    }

    /// How many member ways are held.
    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    #[cfg(test)]
    pub fn from_map(map: HashMap<i64, Vec<i64>>) -> MemberWays {
        MemberWays::build(map.into_iter().collect())
    }
}

#[cfg(test)]
mod member_ways_tests {
    use super::*;

    /// The whole contract: lookups answer like the map, whatever order the pairs arrived in, and
    /// a duplicated id keeps its first occurrence.
    #[test]
    fn lookups_match_the_map_regardless_of_input_order() {
        let table = MemberWays::build(vec![
            (9, vec![10_000, 9_999]),
            (1, vec![7, 8]),
            (9, vec![1, 2, 3]),
            (5, vec![]),
        ]);
        assert_eq!(table.len(), 3, "the duplicate id is absorbed");
        assert_eq!(table.get(1), Some(&vec![7, 8]));
        assert_eq!(table.get(5), Some(&vec![]), "an empty ref list is still a member");
        assert_eq!(table.get(9), Some(&vec![10_000, 9_999]), "first occurrence wins");
        assert_eq!(table.get(2), None, "not a member at all");
        assert_eq!(table.get(-1), None, "negative ids never resolve");
        assert_eq!(table.get(i64::MAX), None, "past the end");
        // `values` covers every entry exactly once, for the ref collectors.
        let mut seen: Vec<i64> = table.values().flatten().copied().collect();
        seen.sort_unstable();
        assert_eq!(seen, vec![7, 8, 9_999, 10_000]);
        assert!(MemberWays::empty().is_empty(), "empty is empty");
    }
}

/// Pass 3: id index plus resolved coordinates, with `places`/`poi` label nodes classified and
/// spilled in the same pass. Grew the sink, select and layers params when pass 4 was folded in.
///
/// `region` filters `poi` label NODES to the bbox. `places` nodes are always
/// kept. Ways and relations are filtered later at materialise time, where the
/// resolved coordinates are in hand.
#[allow(clippy::too_many_arguments)]
fn build_resolved_table(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    ways_path: &Path,
    ways_anon: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    members: &MemberWays,
    way_refs: u64,
    way_max_ref: i64,
    select: &Select,
    layers: Layers,
    region: Option<&osm_ingest::bbox::BBox>,
    sink: &mut Sink,
    stats: &mut Stats,
    mark: &dyn Fn(&str),
) -> Result<NodeLocations> {
    // --- pass 3: the coordinates those two asked for, plus the label nodes ----------------
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
        collect_needed_in_memory(&ways_path, ways_anon, &members, refs_total, &mark)?
    } else {
        collect_needed_by_bitset(&ways_path, ways_anon, &members, max_ref, &mark)?
    };
    mark("id index built, refs freed");
    stats.nodes_needed = table.len() as u64;
    // The node region is the bulk of a planet PBF, so it is inflated once: the coordinate resolve
    // and the label-node classification share this pass. `classify` runs in the block visitor across
    // the pool and is pure -- it is exactly what the old pass 4 body did per node -- and the labels
    // it returns are drained to the sink **in chunk order**, ahead of every way and relation, which
    // is the order pass 4 spilled them in and what keeps the archive byte-identical.
    //
    // Only label layers are consulted: a node is never a road, a lake or a building, so running the
    // full schema over 2 B nodes would pay the tag scan for nothing.
    //
    // The region filter drops `poi` label nodes outside the bbox here. `places`
    // nodes are always kept — a region build still names its countries. A `poi`
    // node carries its own coordinates, so no location table is needed.
    let classify = |node: &NodeView| {
        if !select.matches(|k| node.tags.get_str(k)) {
            return None;
        }
        let class = schema::classify(&node.tags, false, layers)?;
        if !is_label(class.layer) {
            return None;
        }
        if class.layer == tilecodec::mamaps::dict::LAYER_POI
            && !osm_ingest::bbox::keep_e7(region, node.lat_e7, node.lon_e7)
        {
            return None;
        }
        let name = schema::display_name(&node.tags, class.layer);
        Some((
            class,
            node.lon_e7 as f64 * 1e-7,
            node.lat_e7 as f64 * 1e-7,
            name,
            tagged_id(node.id, ELEMENT_NODE),
        ))
    };
    let drain = |hits: Vec<(Class, f64, f64, Option<String>, u64)>| -> Result<()> {
        for (class, lon, lat, name, id) in hits {
            sink.push_named(&class, &Geometry::Points(vec![(lon, lat)]), name.as_deref(), id)?;
            stats.features += 1;
            stats.nodes_classified += 1;
        }
        Ok(())
    };
    let table = resolve_nodes_with(input, &blobs, &blob_kinds, "Pass 3: nodes", table, classify, drain)?;
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

/// Open the ways spill for sequential reading, from whichever backend the budget gate chose:
/// the shared anonymous store when present, else the file at `ways_path`. One helper so the four
/// readers (two ref collectors, lanefill scan, materialise) share one backend.
fn open_ways_reader(
    ways_path: &Path,
    ways_anon: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
) -> Result<WayReader> {
    WayReader::open_either(ways_path, ways_anon)
}

/// One named, non-link road way held for parallel coordinate resolve.
///
/// Owns its refs and name: the spill cursor cannot be re-read, so a batch row carries
/// everything the serial push needs after the parallel phase hands the geometry back.
struct LaneRow {
    id: i64,
    /// The class flags: the link test already passed, but the one-way bit rides here for
    /// the segment the push builds.
    flags: u8,
    name: String,
    lanes: u8,
    refs: Vec<i64>,
}

/// The region bbox expanded for the lane join, in degrees.
///
/// A recipient is at most 50 m long (`lanefill::MAX_STUB_M`, enforced again in `push`),
/// and a donor must share an endpoint node with it — so the shared node sits within ~50 m
/// of the recipient, and any donor that can pair with a kept recipient touches the bbox
/// expanded by a small margin. 0.1° (~11 km) is two orders of magnitude past the ~100 m
/// worst case (a 50 m recipient straddling the border sharing its far endpoint), so the
/// fringe keeps every pair materialise can consult while dropping the rest of the planet.
/// `None` (world) disables the filter: every way can pair, everything resolves.
fn expanded_fringe(region: &osm_ingest::bbox::BBox) -> osm_ingest::bbox::BBox {
    const MARGIN_DEG: f64 = 0.1;
    osm_ingest::bbox::BBox {
        min_lon: (region.min_lon - MARGIN_DEG).max(-180.0),
        min_lat: (region.min_lat - MARGIN_DEG).max(-90.0),
        max_lon: (region.max_lon + MARGIN_DEG).min(180.0),
        max_lat: (region.max_lat + MARGIN_DEG).min(90.0),
    }
}

/// Whether the resolved line touches the fringe box. `None` (world) is always true.
fn fringe_touches(
    fringe: Option<&osm_ingest::bbox::BBox>,
    line: &[(f64, f64)],
) -> bool {
    let Some(fringe) = fringe else {
        return true;
    };
    line.iter().any(|&(lon, lat)| fringe.contains(lon, lat))
}

#[cfg(test)]
mod fringe_tests {
    use super::*;

    fn california() -> osm_ingest::bbox::BBox {
        osm_ingest::bbox::BBox {
            min_lon: -124.5,
            min_lat: 32.5,
            max_lon: -114.0,
            max_lat: 42.0,
        }
    }

    #[test]
    fn the_fringe_is_the_bbox_expanded_a_hair_in_every_direction() {
        let fringe = expanded_fringe(&california());
        assert!((fringe.min_lon - (-124.6)).abs() < 1e-9);
        assert!((fringe.min_lat - 32.4).abs() < 1e-9);
        assert!((fringe.max_lon - (-113.9)).abs() < 1e-9);
        assert!((fringe.max_lat - 42.1).abs() < 1e-9);
    }

    #[test]
    fn the_fringe_clamps_at_the_world_edges() {
        let world = osm_ingest::bbox::BBox {
            min_lon: -180.0,
            min_lat: -90.0,
            max_lon: 180.0,
            max_lat: 90.0,
        };
        assert_eq!(expanded_fringe(&world), world, "clamped, not wrapped");
    }

    #[test]
    fn world_keeps_everything_and_the_fringe_keeps_only_what_touches() {
        let fringe = expanded_fringe(&california());
        let inside = vec![(-122.4, 37.8)];
        let straddling = vec![(-124.55, 39.0)];
        let far = vec![(-100.0, 40.0)];
        assert!(fringe_touches(None, &far), "world disables the filter");
        assert!(fringe_touches(Some(&fringe), &inside));
        assert!(
            fringe_touches(Some(&fringe), &straddling),
            "a border-straddling recipient is within the margin",
        );
        assert!(!fringe_touches(Some(&fringe), &far), "Nevada never pairs with California");
        assert!(
            !fringe_touches(Some(&fringe), &[]),
            "a way with no resolved geometry joins nothing",
        );
    }

    /// The soundness case: a ≤50 m recipient straddling the border and its donor's
    /// endpoint at their shared node — both within the margin, so the pair survives.
    #[test]
    fn a_border_pair_survives_the_fringe() {
        let fringe = expanded_fringe(&california());
        // Recipient crossing the western edge; shared node 20 m outside.
        let recipient = vec![(-124.52, 39.0), (-124.4999, 39.0)];
        // Donor running outward from the shared node.
        let donor = vec![(-124.52, 39.0), (-124.53, 39.001)];
        assert!(fringe_touches(Some(&fringe), &recipient));
        assert!(fringe_touches(Some(&fringe), &donor));
    }
}

/// Lane inheritance over the ways spill. Moved whole from `extract`.
///
/// The scan is batched: the expensive per-way work (coordinate lookup through the node
/// table — a rank-bitset search plus a mapped-file read per node) runs on the pool, while
/// the collector push stays serial in spill order. `table.line` is shared-read-only (the
/// materialise pass already calls it from `par_iter`), and the collector's output order
/// never reaches the archive — `finish` sorts every partition — so the bytes are identical.
/// What this buys: the scan is ~1.1 B sequential ways on a planet input, each paying ~10
/// node lookups; serially that is tens of minutes of mapped-file random reads.
///
/// `region` limits the join to the ways that can pair with a materialised way (see
/// [`fringe_touches`]): on a `--region` build over a larger input, the ways outside the
/// fringe can never share an endpoint node with a kept recipient, so resolving them would
/// be pure waste. `None` (world) disables the filter entirely.
fn inherit_lane_counts(
    spill_path: &Path,
    ways_path: &Path,
    ways_anon: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    table: &NodeLocations,
    ways_classified: usize,
    region: Option<&osm_ingest::bbox::BBox>,
) -> Result<Vec<(i64, u8)>> {
    // --- lane inheritance -------------------------------------------------------------------
    //
    // A junction stub OSM left untagged takes a lane count from the road it interrupts, instead of
    // the renderer's flat `oneway ? 1 : 2`. See [`crate::lanefill`] for the conditions and the
    // measurements behind each of them. It runs here rather than in pass 1 because the conditions
    // are geometric — a length and a turn angle — and coordinates are only resolved above.
    let fringe = region.map(expanded_fringe);
    let inherited_lanes = {
        let mut collector = crate::lanefill::Collector::create(spill_path)?;
        let mut reader = open_ways_reader(ways_path, &ways_anon)?;
        let mut refs: Vec<i64> = Vec::new();
        let mut bar = Progress::new(
            "Lane inheritance: scan".to_string(),
            ways_classified,
            "way(s)",
            true,
        );
        // One batch of spill rows at a time: 64 Ki ways at ~10 nodes each is a few tens of
        // MB of geometry in flight, the same budget the materialise pass uses.
        const LANE_BATCH: usize = 64 * 1024;
        let mut batch: Vec<LaneRow> = Vec::with_capacity(LANE_BATCH);
        let mut lines: Vec<Vec<(f64, f64)>> = Vec::with_capacity(LANE_BATCH);
        let mut exhausted = false;
        loop {
            batch.clear();
            // Serial fill: the spill is a sequential varint stream — one cursor, no seeking.
            // The bar ticks here, per way read, so it still spans the whole spill exactly as
            // before — the batching below must not move the tick or the count drifts.
            while batch.len() < LANE_BATCH {
                let Some((id, class, name, lane_count, _, _, _, _)) = reader.next(&mut refs)?
                else {
                    exhausted = true;
                    break;
                };
                bar.tick("way(s)");
                // Roads, and not slip roads. A ramp leaves a junction carrying its parent's name on
                // very nearly its parent's heading, so it would inherit the mainline's width onto a
                // single-lane ramp; `corridor` leaves links out of a corridor for the same reason.
                if class.layer != tilecodec::mamaps::dict::LAYER_ROADS
                    || class.flags & tilecodec::mamaps::body::FLAG_IS_LINK != 0
                {
                    continue;
                }
                let Some(name) = name else { continue };
                batch.push(LaneRow {
                    id,
                    flags: class.flags,
                    name,
                    lanes: lane_count,
                    refs: std::mem::take(&mut refs),
                });
            }
            if batch.is_empty() {
                debug_assert!(exhausted, "an empty batch means the spill is done");
                break;
            }
            // Parallel resolve: the dominant cost, shared-read-only.
            lines.clear();
            par::install(|| {
                batch
                    .par_iter()
                    .map(|row| table.line(&row.refs))
                    .collect_into_vec(&mut lines)
            });
            // Serial push, in spill order: the collector's file order never reaches the
            // archive (`finish` sorts), but keeping it ordered keeps the temp bytes — and
            // any future debugging of them — deterministic.
            for (row, line) in batch.iter().zip(lines.drain(..)) {
                // A region build only joins the fringe: a recipient is at most 50 m long, so
                // a donor sharing an endpoint node with a kept recipient always touches the
                // expanded box (see `expanded_fringe`). Anything else can pair only with
                // ways materialise drops — work with no reader.
                if !fringe_touches(fringe.as_ref(), &line) {
                    continue;
                }
                let oneway =
                    row.flags & tilecodec::mamaps::body::FLAG_IS_ONEWAY != 0;
                collector.push(&crate::lanefill::Segment {
                    id: row.id,
                    name: &row.name,
                    lanes: row.lanes,
                    oneway,
                    nodes: &row.refs,
                    line: &line,
                })?;
            }
            if exhausted {
                break;
            }
        }
        bar.finish("way(s)");
        collector.finish()?
    };

    Ok(inherited_lanes)
}

/// Materialise classified ways off disk, in id order. Moved whole from `extract`.
///
/// `region` filters the built layers (`roads`, `poi`, `buildings`): a way in
/// one of those layers whose resolved line touches no bbox point is dropped.
/// Every other layer is always kept. Kept whole, never clipped — the tiler
/// clips per tile later.
fn materialise_ways(
    ways_path: &Path,
    ways_anon: &Option<std::sync::Arc<tile_build::anon::AnonStore>>,
    promoted: &[(i64, u8)],
    inherited_lanes: &[(i64, u8)],
    table: &NodeLocations,
    region: Option<&osm_ingest::bbox::BBox>,
    sink: &mut Sink,
    stats: &mut Stats,
) -> Result<()> {
    let mut reader = open_ways_reader(ways_path, ways_anon)?;
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
        // Moved out of `more` by value below, so named ways cost no `String` clone and
        // turn-mask vectors move rather than copy: over ~1 B ways the allocator traffic
        // of cloning is minutes, and the batch owns every field anyway.
        let done = more.is_none();
        if let Some((id, class, name, lane_count, turn_fwd, turn_bwd, carriageway, building)) =
            more
        {
            let mut class = class;
            // The corridor's zoom, where it is shallower than this way's own. Corridors
            // are built from numbered roads alone, so a non-road way can never have an
            // entry — searching for one is ~17 wasted compares per boundary, building
            // and waterway on a region build.
            if class.layer == tilecodec::mamaps::dict::LAYER_ROADS {
                if let Ok(at) = promoted.binary_search_by_key(&id, |(id, _)| *id) {
                    class.min_zoom = promoted[at].1;
                }
            }
            // A neighbour's lane count, where OSM tagged none on this way. Only ever consulted
            // for a way that has none of its own, so a tag is never overridden.
            let lane_count = if lane_count == 0 {
                inherited_lanes
                    .binary_search_by_key(&id, |(id, _)| *id)
                    .map_or(0, |at| inherited_lanes[at].1)
            } else {
                lane_count
            };
            // Only a label way carries its id onward. A road or a building is merged with its
            // neighbours by `coalesce`, which leaves the survivor's id arbitrary, and the id
            // table exists for `poi` and `places` alone.
            let id = if is_label(class.layer) {
                tagged_id(id, ELEMENT_WAY)
            } else {
                tilecodec::mamaps::body::ID_NONE
            };
            batch.push((
                class,
                name,
                std::mem::take(&mut refs),
                id,
                lane_count,
                turn_fwd,
                turn_bwd,
                carriageway,
                building,
            ));
        }
        // Flushed when full, and once more at the end with whatever is left.
        if batch.len() >= MATERIALISE_BATCH || (done && !batch.is_empty()) {
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
                        // Region filter: roads, pois and buildings outside the
                        // bbox are dropped. The geometry is resolved by now, so
                        // this is a direct touches-box test on the line the
                        // way produced (a centroided point for labels).
                        if is_region_filtered(class.layer)
                            && !geometry_touches(&geometry, region)
                        {
                            bar.tick("way(s)");
                            continue;
                        }
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
        if done {
            break;
        }
    }
    bar.finish("way(s)");
    // Nothing reads the ways spill after this: the relations below reach their members through
    // `members`, which is why that table is kept at all. The file backend's `.tmp` is removed;
    // the anon backend's segments unmap with the last `Arc`.
    drop(reader);
    let _ = std::fs::remove_file(&ways_path);

    Ok(())
}
