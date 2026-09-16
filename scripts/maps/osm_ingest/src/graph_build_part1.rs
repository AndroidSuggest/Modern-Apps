/// The ways-only pass: one [`Seg`] per consecutive resolvable ref pair.
///
/// Re-reading the way blobs replaces a cache of every routable way and every one
/// of its refs — 32 GB at planet scale — with the cost of inflating the way
/// blobs a second time. Ways are a small minority of a planet file's blobs and
/// `blob_kinds` lets the pass skip the rest outright, so the trade is heavily in
/// favour of re-reading.
#[derive(Default)]
struct WayPass {
    segs: Vec<Seg>,
    lanes: Vec<u16>,
    names: LocalNames,
}

#[derive(Default)]
struct Pass2 {
    /// `(dense id, lat_e7, lon_e7)`, scattered index-aligned in the sink.
    locs: Vec<(u32, i32, i32)>,
    /// `(dense id, stop code)`, as owned bytes. Interning is deferred until every
    /// way name is in the pool, because a name's offset is its position in the
    /// pool: interning a stop code early would shift every way name after it and
    /// rewrite the whole of `road_names.bin`.
    stops: Vec<(u32, Vec<u8>)>,
}

#[derive(Clone, Copy)]
struct TmpEdge {
    source: u32,
    target: u32,
    dist_mm: u32,
    name_offset: u32,
    type_: u8,
    speed_limit: u8,
    lane_off: u32,
    lane_count: u16,
    /// Chain this edge traverses, or [`NO_CHAIN`]. Only used to make the write
    /// order total; the geometry comes from the point range below.
    chain: u32,
    /// True when it runs from the chain's last point to its first, so the stored
    /// polyline has to be read backwards.
    chain_rev: bool,
    /// The chain's polyline in `chains.pts`. Carried here rather than looked up per
    /// edge: the header file is 24 GB at planet scale, so seeking back into it once
    /// per edge would be a billion cold random reads.
    pts_start: u64,
    pts_len: u32,
}

/// Build-time choices that change the shape of the build rather than its output
/// contract.
#[derive(Clone, Default)]
pub struct Options {
    /// Restrict chains to a single OSM way, which trades some collapsing for the
    /// segment array and its incidence index. See [`crate::chains`].
    pub within_way_chains: bool,
    /// How many source-partitioned rounds to write the pack in. One round is the
    /// whole graph at once; more rounds trade extra sequential passes over the
    /// chain spill for a proportionally smaller edge buffer. Zero means one.
    pub rounds: u32,
    /// Where to put the chain spill. Defaults to the output directory.
    ///
    /// Worth separating because `chains.pts` is the only file the build reads
    /// randomly — once per edge that stores a polyline — so it wants a disk whose
    /// seeks are cheap, while the output only ever needs sequential writes and can
    /// go wherever there is room. Putting the spill on a slow mount cost more wall
    /// clock on California than every other stage put together.
    pub spill_dir: Option<PathBuf>,
    /// Where to put `chains.pts` specifically, if it should not sit beside
    /// `chains.hdr`.
    ///
    /// The header is the larger file — about 24 GB against 18 GB at planet scale —
    /// but it is only ever streamed from the front, so it can live somewhere roomy
    /// and slow while the randomly-read points go somewhere fast and small.
    pub spill_pts_dir: Option<PathBuf>,
    /// Print the [`Census`] report after the build. Changes no output file.
    pub stats: bool,
    /// Cap on the parallel pool. `None` leaves it to [`crate::par::threads`].
    pub threads: Option<usize>,
    /// A `.mdem` heightmap dataset (`dem_ingest`) to bake a per-node elevation from.
    ///
    /// `None` omits `elevation.bin` entirely — the graph is still valid and the device reads every
    /// node's elevation as 0 (WS-G's route profile simply comes out flat). When set, every graph
    /// node is sampled from the DEM and its metres written to `elevation.bin`; a node off the DEM's
    /// coverage bakes as 0.
    pub dem: Option<PathBuf>,
}

impl Options {
    fn round_count(&self) -> u32 {
        self.rounds.max(1)
    }
}

pub fn build(input: &Path, out_dir: &Path) -> Result<Stats> {
    build_with(input, out_dir, Options::default())
}

pub fn build_with(input: &Path, out_dir: &Path, opts: Options) -> Result<Stats> {
    if let Some(n) = opts.threads {
        crate::par::set_threads(n);
    }
    std::fs::create_dir_all(out_dir)
        .map_err(|e| Error(format!("cannot create {}: {e}", out_dir.display())))?;

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());
    pbf::probe_compression(input, &blobs)?;

    let mut pool = NamePool::new(BufWriter::new(create(&out_dir.join("road_names.bin"))?));

    let mut mask = Bitset::new(BITSET_SIZE);
    let mut marked: u64 = 0;
    let mut max_id: u64 = 0;

    // --- Pass 1: which nodes the graph needs ---------------------------------
    // Folded through a sink rather than collected, so a chunk's ref list is
    // consumed and freed while later chunks are still decoding. Nothing about a
    // way survives this pass: the ways pass below re-reads the way blobs, which
    // is far cheaper than caching every routable way and every ref it holds.
    let blob_kinds = pbf::run_pass_sink(
        input,
        &blobs,
        None,
        KIND_NODES | KIND_WAYS,
        "Pass 1: refs + stops",
        Pass1::default,
        pass1_blob,
        |chunk| {
            for id in chunk.refs.iter().chain(chunk.stop_nodes.iter()) {
                if *id >= 0 && (*id as u64) < BITSET_SIZE {
                    if mask.set(*id as u64) {
                        marked += 1;
                    }
                    // The rank index is sized from the largest id actually
                    // marked, not from BITSET_SIZE: indexing all 20 G nominal ids
                    // would cost 313 MB to describe blocks no node reaches.
                    max_id = max_id.max(*id as u64);
                }
            }
            Ok(())
        },
    )?;
    println!("{marked} graph node(s) expected");
    // Cache the complete blob-kinds mask beside the graph so a `mamaps_build` run over the same
    // `.pbf` can skip its own full pass-1 scan. This pass inflated every blob with `None`, so the
    // mask is complete. Best-effort: a write failure must not fail the graph build, which never
    // reads the sidecar itself.
    if let Err(e) = pbf::write_blob_kinds(out_dir, input, &blob_kinds) {
        eprintln!("warning: could not write blob-kinds sidecar: {e}");
    }
    let slots = cap_u32("marked graph node", marked)?;

    // --- The rank index ------------------------------------------------------
    // From here on a node's address is the number of marked ids below its own, so
    // the node array is implicitly ordered by OSM id and needs to store neither
    // the id nor a sorted side table to look it up by.
    let counted = mask.build_rank(max_id);
    if counted != marked {
        // The mask and the running count are maintained in the same loop, so a
        // disagreement means one of the two is wrong about which ids were set,
        // and every address derived from the rank would be off by however many.
        return Err(Error(format!(
            "rank index counted {counted} marked node(s) but pass 1 set {marked}"
        )));
    }

    // --- Pass 2: coordinates and stop codes for the marked nodes -------------
    // Zero-initialised, so the slots belonging to dangling refs are never touched
    // and never fault in: an untouched page of `coords` costs no real memory.
    let mut coords: Vec<geom::Pt> = vec![(0, 0); slots as usize];
    let mut present = Bitset::new(u64::from(slots));
    let mut stop = Bitset::new(u64::from(slots));
    let mut stop_codes: Vec<(u32, Vec<u8>)> = Vec::new();
    let mut node_count: u64 = 0;
    let _ = pbf::run_pass_sink(
        input,
        &blobs,
        Some(&blob_kinds),
        KIND_NODES,
        "Pass 2: node scan",
        Pass2::default,
        |state, block| pass2_blob(state, block, &mask),
        |chunk| {
            for (dense, lat_e7, lon_e7) in chunk.locs {
                coords[dense as usize] = (lat_e7, lon_e7);
                if present.set(u64::from(dense)) {
                    node_count += 1;
                }
            }
            for (dense, code) in chunk.stops {
                stop.set(u64::from(dense));
                stop_codes.push((dense, code));
            }
            Ok(())
        },
    )?;
    println!(
        "Located {node_count} node(s); {} marked id(s) had no coordinates in the file",
        marked - node_count
    );

    // Stop codes go into the pool only after every way name is in it, in the
    // order pass 2 met them, so `road_names.bin` is unaffected by dense
    // addressing and by where the way names are interned.
    let index = NodeIndex { mask, present };

    // --- Collapse ------------------------------------------------------------
    let spill_dir = opts.spill_dir.clone().unwrap_or_else(|| out_dir.to_path_buf());
    let spill_pts_dir = opts.spill_pts_dir.clone().unwrap_or_else(|| spill_dir.clone());
    let mut collapsed = if opts.within_way_chains {
        collapse_within_ways(
            input, &spill_dir, &spill_pts_dir, &blobs, &blob_kinds, &index, &coords, &stop,
            slots, &mut pool,
        )?
    } else {
        collapse_segments(
            input, &spill_dir, &spill_pts_dir, &blobs, &blob_kinds, &index, &coords, &stop,
            slots, &mut pool,
        )?
    };

    let mut prog = crate::progress::Progress::new("Interning stop codes", stop_codes.len() as u64);
    for (_, code) in &stop_codes {
        pool.intern(code).map_err(io_err)?;
        prog.inc();
    }
    prog.finish();
    drop(stop_codes);

    // `saturating_sub` because a file with no routable ways and no stop nodes
    // marks nothing, and there is then no largest id to index up to.
    let kept_count = cap_u32(
        "surviving node",
        collapsed.kept.build_rank(u64::from(slots).saturating_sub(1)),
    )?;
    let kept = collapsed.kept;
    println!(
        "Collapsed {node_count} node(s) -> {kept_count} ({} chain(s), {} split(s) for the \
         256-point limit)",
        collapsed.chain_count, collapsed.splits
    );
    cap_u32("chain", collapsed.chain_count)?;
    let raw_edge_count = collapsed.raw_edge_count;

    // --- Morton order -------------------------------------------------------
    // Deferred to here, and applied only to the survivors, because that is the
    // only set the device ever binary-searches. Sorting before the collapse would
    // have meant carrying a 64-bit spatial key through every node that was about
    // to be collapsed away.
    println!("Sorting {kept_count} surviving node(s) by spatial key...");
    let mut keys: Vec<(u64, u32)> = Vec::with_capacity(kept_count as usize);
    let mut prog = crate::progress::Progress::new("Morton keys", u64::from(slots));
    for i in 0..slots {
        if kept.get(u64::from(i)) {
            let (lat_e7, lon_e7) = coords[i as usize];
            keys.push((spatial_from_e7(lat_e7, lon_e7), i));
        }
        prog.inc();
    }
    prog.finish();
    debug_assert_eq!(keys.len(), kept_count as usize);
    // The key is `(spatial, dense)`, and the loop above pushed `dense` ascending, so
    // this is exactly the order the previous STABLE `sort_by_key(spatial)` produced:
    // ties there fell back to insertion order, which was ascending `dense`. Spelling
    // the tie-break into the key makes the order total, and a total order is what lets
    // an UNSTABLE parallel sort be substituted without changing a single final id --
    // and final ids are what `nodes.bin`, `edges.bin` and every offset in the pack are
    // numbered by, so this is the one comparison in the build that must not move.
    eprintln!("Sorting {kept_count} spatial key(s)...");
    par::install(|| keys.par_sort_unstable());

    let mut final_of_kept = vec![0u32; kept_count as usize];
    let mut node_coords: Vec<geom::Pt> = Vec::with_capacity(kept_count as usize);
    let mut stops_final = Bitset::new(u64::from(kept_count));
    let mut prog = crate::progress::Progress::new("Final id assignment", keys.len() as u64);
    for (final_id, (_, dense)) in keys.iter().enumerate() {
        final_of_kept[kept.dense(u64::from(*dense)) as usize] = final_id as u32;
        node_coords.push(coords[*dense as usize]);
        if stop.get(u64::from(*dense)) {
            stops_final.set(final_id as u64);
        }
        prog.inc();
    }
    prog.finish();
    drop(keys);
    drop(stop);
    // The chain spill holds coordinates, so nothing downstream needs the array
    // indexed by *marked* node — the largest in the build — any longer.
    drop(coords);
    drop(index);
    let ids = FinalIds {
        kept: &kept,
        of_kept: &final_of_kept,
    };

    // --- The edge index -----------------------------------------------------
    let csr = build_csr(&collapsed.spill, kept_count, &ids)?;

    // --- Reconnect isolated transit stops -----------------------------------
    let rec = reconnect_isolated_stops(&node_coords, &stops_final, &csr);
    let synth = rec.synth;
    drop(stops_final);

    // --- Write --------------------------------------------------------------
    // Load the optional DEM up front so a bad `--dem` path fails before the write starts. `None`
    // leaves `elevation.bin` unwritten and every node's baked elevation at 0.
    let dem = match &opts.dem {
        Some(path) => Some(crate::dem::Dem::load(path)?),
        None => None,
    };
    let written = write_graph(
        out_dir,
        &node_coords,
        &collapsed.spill,
        &collapsed.lane_pool,
        &csr,
        &synth,
        &ids,
        opts.round_count(),
        dem.as_ref(),
    )?;
    collapsed.spill.remove();
    let unique_names = pool.unique_count();
    let name_bytes = pool.byte_len();
    pool.finish().map_err(io_err)?;

    println!(
        "Done. Nodes: {}  Edges: {}  intermediate.bin: {} byte(s)",
        kept_count, written.edge_count, written.intermediate_bytes
    );
    Ok(Stats {
        node_count: u64::from(kept_count),
        edge_count: written.edge_count,
        unique_names,
        name_bytes,
        lcc_size: rec.lcc_size,
        stops_already_connected: rec.already_connected,
        reconnected_stops: synth.len() / 2,
        stops_unreachable: rec.unreachable,
        raw_node_count: node_count,
        raw_edge_count,
        chain_splits: collapsed.splits,
        geometry_edges: written.geometry_edges,
        reversed_edges: written.reversed_edges,
        intermediate_bytes: written.intermediate_bytes,
        edges_bytes: written.edges_bytes,
        escape_count: written.escape_count,
        named_edges: written.named_edges,
        census: written.census,
    })
}

/// What either collapse strategy hands the writer.
///
/// Both produce the same thing — a chain spill plus the set of nodes that survive
/// — and differ only in how much memory it costs to get there and whether a chain
/// may cross from one OSM way into the next.
///
/// The chains are always on disk, even for the reference path that had them in
/// memory anyway, so that there is one writer. That also means `coords` — the
/// largest array in the build, and the only one indexed by *marked* rather than
/// surviving node — can be freed before the writer runs.
struct Collapsed {
    spill: chains::Spill,
    /// Dense ids that survive as graph nodes.
    kept: Bitset,
    lane_pool: Vec<u16>,
    chain_count: u64,
    splits: usize,
    raw_edge_count: u64,
}

/// The reference path: build every segment, then collapse chains across way
/// boundaries with [`compact`].
///
/// Kept as the baseline the within-way path is measured against. Its cost is the
/// segment array and the incidence CSR over it, which together are the reason a
/// planet build does not fit.
#[allow(clippy::too_many_arguments)]
fn collapse_segments<W: std::io::Write + Send>(
    input: &Path,
    spill_dir: &Path,
    spill_pts_dir: &Path,
    blobs: &[pbf::BlobLoc],
    blob_kinds: &[u8],
    index: &NodeIndex,
    coords: &[geom::Pt],
    stop: &Bitset,
    slots: u32,
    pool: &mut NamePool<W>,
) -> Result<Collapsed> {
    // One record per consecutive way-node pair, still carrying both directions'
    // lane data. Splitting into directed edges is deferred until after
    // compaction, because pairing a `u -> v` edge with its `v -> u` twin is only
    // unambiguous while they are still one object.
    let mut segs: Vec<Seg> = Vec::new();
    let mut lane_pool: Vec<u16> = Vec::new();
    let way_kinds = pbf::run_pass_sink(
        input,
        blobs,
        Some(blob_kinds),
        KIND_WAYS,
        "Pass 3: segments",
        WayPass::default,
        |state, block| way_blob(state, block, index, coords),
        |chunk| {
            let name_map = chunk.names.flush(pool).map_err(io_err)?;
            let lane_base = lane_pool.len() as u32;
            lane_pool.extend_from_slice(&chunk.lanes);
            cap_lane_pool(lane_pool.len())?;
            segs.reserve(chunk.segs.len());
            for mut s in chunk.segs {
                s.name_offset = if s.name_offset == u32::MAX {
                    NO_NAME
                } else {
                    name_map[s.name_offset as usize]
                };
                if s.fwd_lane_count > 0 {
                    s.fwd_lane_off += lane_base;
                }
                if s.bwd_lane_count > 0 {
                    s.bwd_lane_off += lane_base;
                }
                segs.push(s);
            }
            Ok(())
        },
    )?;
    report_way_blobs(blobs.len(), &way_kinds);

    let raw_edge_count: u64 = segs.iter().map(|s| if s.oneway { 1u64 } else { 2 }).sum();
    println!(
        "Built {} segment(s) ({raw_edge_count} directed edge(s) before compaction)",
        segs.len()
    );

    let comp = compact::compact(slots, &segs, &lane_pool, |n| coords[n as usize], |n| {
        stop.get(u64::from(n))
    });
    drop(segs);

    // A slot with no coordinates has no segments either, so compaction has no
    // reason to collapse it and would hand it a real node id. Filter it out here
    // instead: this is the second half of the dangling-reference guard.
    let mut kept = Bitset::new(u64::from(slots));
    for i in 0..slots {
        if comp.new_id[i as usize] != REMOVED && index.present.get(u64::from(i)) {
            kept.set(u64::from(i));
        }
    }
    let spill = chains::Spill::split(spill_dir, spill_pts_dir);
    spill.write(&comp.chains, &comp.pts, coords)?;
    Ok(Collapsed {
        spill,
        kept,
        lane_pool,
        chain_count: comp.chains.len() as u64,
        splits: comp.splits,
        raw_edge_count,
    })
}
