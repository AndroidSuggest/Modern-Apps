/// Hook every transit stop that is *not* attached to a real road network onto the
/// nearest node that is. Without this a matched GTFS stop that OSM never attached
/// to a road is unreachable and the transit planner cannot get the user to it.
///
/// # Why component *size* and not the largest component
///
/// The question worth asking is "is this stop attached to a road network?", not
/// "is it attached to the biggest one?". On a region those coincide — the largest
/// component is 98.2% of California — so the pass used to find the largest
/// component and reconnect everything outside it. On a planet they do not:
/// continents are not road-connected, so the largest component is
/// Eurasia-plus-Africa at 57.7% and every stop in the Americas, Australia, Japan,
/// the UK and Indonesia was classified as isolated. The code then tried to bridge
/// an ocean to reach a road in France, failed 1,173,347 times — a quarter of all
/// stops — and paid for a widening search out to a million-node index window
/// before each failure.
///
/// So a stop needs reconnecting only when its own component is smaller than
/// [`MIN_ROUTABLE_COMPONENT`], and the target is the nearest node in *any*
/// component at or above that threshold. A stop alone on an untagged node is a
/// component of size 1 and is still connected; a stop on a small island's road
/// network is left alone, because it is already as reachable as that island gets;
/// a stop in North America connects to North America. That also removes the
/// pathology rather than its symptom: a qualifying node is now metres away, so the
/// widening search almost always succeeds on its first window.
///
/// The threshold is capped at the largest component that exists, because a graph
/// with no component of 1000 nodes still has a road network — it is just a small
/// one — and requiring a size nothing reaches would reconnect nothing at all.
///
/// One caveat on the word "component": the scan follows out-edges only, because
/// that is the index the build has — a reverse CSR would be another 4.3 GB of
/// `targets` at planet scale. So the partition is by out-reachability, and a node
/// fed only by incoming one-ways can be labelled separately from the network that
/// feeds it, undercounting that network's size. The error is in the safe direction:
/// such a node looks *less* connected than it is, so at worst it gains a connector
/// it did not need. The same traversal is what measured California's largest
/// component at 98.24%, which bounds how far off it can be in practice.
///
/// `coords` and `stops` are both in final (Morton) id space.
fn reconnect_isolated_stops(coords: &[geom::Pt], stops: &Bitset, csr: &Csr) -> Reconnected {
    let n = coords.len();
    if n == 0 {
        return Reconnected {
            lcc_size: 0,
            synth: Vec::new(),
            already_connected: 0,
            unreachable: 0,
        };
    }
    println!("Identifying connected components...");

    let mut component = vec![u32::MAX; n];
    // One size per component id. Planet has ~291 K components, so 1.2 MB.
    let mut sizes: Vec<u32> = Vec::new();
    let mut queue: Vec<u32> = Vec::with_capacity(n);
    let mut prog = crate::progress::Progress::new("Connected components", n as u64);
    for start in 0..n {
        prog.inc();
        if component[start] != u32::MAX {
            continue;
        }
        let id = sizes.len() as u32;
        let base = queue.len();
        queue.push(start as u32);
        component[start] = id;
        let mut head = base;
        while head < queue.len() {
            let u = queue[head];
            head += 1;
            for v in csr.out(u) {
                if component[*v as usize] == u32::MAX {
                    component[*v as usize] = id;
                    queue.push(*v);
                }
            }
        }
        sizes.push((queue.len() - base) as u32);
    }
    prog.finish();
    drop(queue);

    let lcc_size = sizes.iter().copied().max().unwrap_or(0);
    let threshold = MIN_ROUTABLE_COMPONENT.min(lcc_size);
    println!(
        "{} component(s), largest {lcc_size} / {n} node(s) ({:.1}%); a component of \
         {threshold}+ node(s) counts as routable",
        sizes.len(),
        f64::from(lcc_size) * 100.0 / n as f64
    );

    let routable = |node: u32| sizes[component[node as usize] as usize] >= threshold;
    let mut stop_count = 0usize;
    let mut isolated: Vec<u32> = Vec::new();
    for i in 0..n as u32 {
        if stops.get(u64::from(i)) {
            stop_count += 1;
            if !routable(i) {
                isolated.push(i);
            }
        }
    }
    let already_connected = stop_count - isolated.len();
    if isolated.is_empty() {
        println!("{stop_count} stop(s), all already on a routable component");
        return Reconnected {
            lcc_size: u64::from(lcc_size),
            synth: Vec::new(),
            already_connected,
            unreachable: 0,
        };
    }

    // The nodes are Morton-sorted, so index distance approximates spatial
    // distance: widen an index window around the stop until it contains a node in
    // a routable component, then take the closest one in that window.
    let found = par::map_chunks(&isolated, 256, |_, chunk| {
        let mut out: Vec<Synth> = Vec::new();
        let mut failed = 0usize;
        for &i in chunk {
            let stop = coords[i as usize];
            let mut best: Option<(u32, u32)> = None;
            let mut radius = 1000usize;
            while radius <= 1_000_000 {
                let lo = (i as usize).saturating_sub(radius);
                let hi = (i as usize + radius).min(n);
                for j in lo..hi {
                    if !routable(j as u32) {
                        continue;
                    }
                    let d = accurate_dist_mm(stop.0, stop.1, coords[j].0, coords[j].1);
                    if best.is_none_or(|(bd, _)| d < bd) {
                        best = Some((d, j as u32));
                    }
                }
                if best.is_some() {
                    break;
                }
                radius *= 10;
            }
            if let Some((dist_mm, target)) = best {
                out.push(Synth {
                    source: i,
                    target,
                    dist_mm,
                });
                out.push(Synth {
                    source: target,
                    target: i,
                    dist_mm,
                });
            } else {
                failed += 1;
            }
        }
        (out, failed)
    });

    let unreachable: usize = found.iter().map(|(_, f)| f).sum();
    let mut synth: Vec<Synth> = found.into_iter().flat_map(|(e, _)| e).collect();
    println!(
        "{stop_count} stop(s): {already_connected} already connected, {} reconnected, \
         {unreachable} found nothing",
        synth.len() / 2
    );
    if unreachable > 0 {
        // The index window caps at 1e6 nodes, so a stop with no routable road
        // within that window stays unreachable. Say so rather than dropping it
        // silently: an unreachable stop is exactly what this pass exists to
        // prevent. A large count here means MIN_ROUTABLE_COMPONENT is too high.
        println!("WARNING: {unreachable} isolated stop(s) had no routable road nearby");
    }
    synth.sort_by_key(|s| (s.source, s.target));
    Reconnected {
        lcc_size: u64::from(lcc_size),
        synth,
        already_connected,
        unreachable,
    }
}

/// What `write_graph` produced, for [`Stats`].
struct Written {
    edge_count: u64,
    geometry_edges: u64,
    reversed_edges: u64,
    intermediate_bytes: u64,
    edges_bytes: u64,
    escape_count: u64,
    named_edges: u64,
    census: Census,
}

/// Write the pack in `rounds` source-partitioned rounds.
///
/// # Why rounds, and not computed offsets
///
/// The obvious alternative is to compute each edge's index and `pwrite` it into
/// place. That is sound for `edges.bin` — fixed 14-byte records in a pre-sized
/// file — but wrong for the two blob files. The reader derives a polyline's length
/// from the next offset entry and a lane run's from the next index entry, so
/// `intermediate.bin` and `lanes.bin` are forced into edge-index order with
/// monotonic offsets, and writing a blob at a computed offset would mean knowing
/// every earlier blob's length first: a per-edge length array, a prefix sum and a
/// second encode pass.
///
/// Partitioning by source instead makes every write an append. Each round takes a
/// contiguous range of final node ids, scans the chain spill for the edges whose
/// *source* falls in that range, sorts that buffer, and writes both blobs, the
/// presence bitmap, the offset tables, `edges.bin` and `nodes.bin` sequentially.
/// Offsets come out monotonic by construction, `nodes.bin`'s running edge counter
/// works because the rounds ascend, and the `(source, target)` grouping the reader
/// and [`twin_is_unique`] depend on is restored within each round.
///
/// The cost is `rounds` sequential reads of the spill instead of one.
#[allow(clippy::too_many_arguments)]
fn write_graph(
    out_dir: &Path,
    node_coords: &[geom::Pt],
    spill: &chains::Spill,
    lane_pool: &[u16],
    csr: &Csr,
    synth: &[Synth],
    ids: &FinalIds,
    rounds: u32,
    dem: Option<&crate::dem::Dem>,
) -> Result<Written> {
    let kept = node_coords.len() as u32;
    let edge_count = csr.edge_count() + synth.len() as u64;
    // `nodes.bin`'s `edge_ptr` is a `u32`, so an edge count past that ceiling would
    // wrap and point a node at another node's edge range.
    cap_u32("directed edge", edge_count)?;
    println!("Writing {edge_count} edge(s) in {rounds} round(s)...");
    let chain_total = spill.chain_count()?;

    let mut nodes_out = BufWriter::new(create(&out_dir.join("nodes.bin"))?);
    let mut edges_out = EdgeFile::create(out_dir, "edges.bin", edge_count)?;

    // `intermediate.bin` puts its blob at offset 0 and everything that indexes it
    // in a trailer, so it is written strictly forwards with no scratch file at all
    // (see [`GeomFile`]). `lanes.bin` is sparse and so cannot size its index up
    // front; its blob streams to scratch instead and the index is written in front
    // of it.
    let mut inter = GeomFile::create(out_dir, "intermediate.bin", edge_count)?;
    let mut lanes = LaneFile::create(out_dir, "lanes.bin")?;

    let mut scratch: Vec<geom::Pt> = Vec::new();
    let mut encoded: Vec<u8> = Vec::new();
    let mut lane_bytes: Vec<u8> = Vec::new();
    let mut buffer: Vec<TmpEdge> = Vec::new();
    let mut points = spill.points()?;
    let mut edge_ptr: u64 = 0;
    let mut geometry_edges: u64 = 0;
    let mut reversed_edges: u64 = 0;
    let mut census = Census::default();

    for round in 0..rounds {
        // Split by node count. Contiguous and ascending, so the four output streams
        // stay append-only across the whole run.
        let lo = (u64::from(kept) * u64::from(round) / u64::from(rounds)) as u32;
        let hi = (u64::from(kept) * u64::from(round + 1) / u64::from(rounds)) as u32;

        buffer.clear();
        let mut hdr = spill.headers()?;
        let mut ci = 0u32;
        let mut prog = crate::progress::Progress::new(
            format!("Round {}/{rounds}: scan", round + 1),
            chain_total,
        );
        while let Some(c) = hdr.next()? {
            let (a, b) = (ids.get(c.first), ids.get(c.last));
            if (lo..hi).contains(&a) {
                buffer.push(chain_edge(&c, ci, a, b, false));
            }
            if !c.oneway && (lo..hi).contains(&b) {
                buffer.push(chain_edge(&c, ci, b, a, true));
            }
            ci += 1;
            prog.inc();
        }
        prog.finish();
        let synth_lo = synth.partition_point(|s| s.source < lo);
        let synth_hi = synth.partition_point(|s| s.source < hi);
        for s in &synth[synth_lo..synth_hi] {
            buffer.push(TmpEdge {
                source: s.source,
                target: s.target,
                dist_mm: s.dist_mm,
                name_offset: NO_NAME,
                type_: RECONNECT_TYPE,
                speed_limit: RECONNECT_SPEED,
                lane_off: NO_LANES,
                lane_count: 0,
                chain: NO_CHAIN,
                chain_rev: false,
                pts_start: 0,
                pts_len: 0,
            });
        }

        // The CSR already knows how many edges this range owns. Checking against it
        // catches both an edge landing in the wrong round and an edge whose source
        // is not a surviving node — which is what the old
        // `assert_eq!(cursor, edges.len())` was for, now checked per round and
        // before anything is written rather than after everything is.
        let expected = csr.edge_ptr[hi as usize] - csr.edge_ptr[lo as usize]
            + (synth_hi - synth_lo) as u64;
        if buffer.len() as u64 != expected {
            return Err(Error(format!(
                "round {round} covering node(s) {lo}..{hi} collected {} edge(s), \
                 but the edge index says {expected}",
                buffer.len()
            )));
        }

        // Grouped by source for the reader, then by target so a node's parallel
        // edges are adjacent, then by chain so the order is total. The sort is
        // stable and the buffer was built in chain order, so the two directions of
        // a self-loop keep the order they were emitted in.
        buffer.sort_by_key(|e| (e.source, e.target, e.chain));

        let mut cursor = 0usize;
        let mut wprog = crate::progress::Progress::new(
            format!("Round {}/{rounds}: write", round + 1),
            u64::from(hi - lo),
        );
        for v in lo..hi {
            let node = node_coords[v as usize];
            write_node(&mut nodes_out, node.0, node.1, edge_ptr as u32).map_err(io_err)?;
            let degree_base = edge_ptr;
            while cursor < buffer.len() && buffer[cursor].source == v {
                let e = &buffer[cursor];
                census.edge(e.source, e.target, e.dist_mm, e.name_offset != NO_NAME);

                // --- geometry ---
                let mut type_ = e.type_;
                let mut stores = false;
                // A two-point chain is just the chord between the edge's own
                // endpoints, and under the interior-only encoding that is what an
                // absent blob already means, so storing it would cost a presence
                // bit to say nothing — and, more to the point here, a read to find
                // that out.
                if e.chain != NO_CHAIN && e.pts_len >= 3 {
                    if e.chain_rev
                        && e.source != e.target
                        && twin_is_unique(csr, synth, e.source, e.target)
                    {
                        type_ |= REVERSE_GEOMETRY_FLAG;
                        reversed_edges += 1;
                    } else {
                        // Only now is the polyline actually needed. Deciding the
                        // flag first matters more than it looks: this is the only
                        // random read in the writer, so every edge that defers to
                        // its twin is one seek saved, and on a filesystem whose
                        // syscalls are expensive that dominated the whole build.
                        points.read(e.pts_start, e.pts_len, &mut scratch)?;
                        if e.chain_rev {
                            scratch.reverse();
                        }
                        assert!(
                            geom::fits(&scratch),
                            "chain of {} points cannot be encoded; the collapse should \
                             have split it",
                            scratch.len()
                        );
                        // The blob holds only the interior, so the reader rebuilds
                        // both ends out of `nodes.bin`.
                        assert_endpoints(&scratch, node_coords, e.source, e.target, e.chain);
                        encoded.clear();
                        let n = geom::encode(&scratch, &mut encoded);
                        debug_assert_eq!(n as usize, geom::encoded_points(&scratch) as usize);
                        inter.store(&encoded)?;
                        geometry_edges += 1;
                        stores = true;
                    }
                }
                if !stores {
                    inter.skip();
                }

                edges_out.push(e, type_)?;
                if e.lane_count > 0 && e.lane_off != NO_LANES {
                    let start = e.lane_off as usize;
                    lane_bytes.clear();
                    for m in &lane_pool[start..start + e.lane_count as usize] {
                        lane_bytes.extend_from_slice(&m.to_le_bytes());
                    }
                    lanes.push(edge_ptr, &lane_bytes)?;
                }
                edge_ptr += 1;
                cursor += 1;
            }
            census.node((edge_ptr - degree_base) as u32);
            wprog.inc();
        }
        wprog.finish();
        debug_assert_eq!(cursor, buffer.len(), "the round left edges unwritten");
    }

    if edge_ptr != edge_count {
        return Err(Error(format!(
            "wrote {edge_ptr} edge(s) into a file sized for {edge_count}"
        )));
    }
    // Trailing sentinel so `graph.rs` can read node(v + 1).edge_ptr as the end of
    // node v's edge range.
    write_node(&mut nodes_out, 0, 0, edge_ptr as u32).map_err(io_err)?;
    nodes_out.flush().map_err(io_err)?;
    let escape_count = edges_out.escapes.len() as u64;
    let named_edges = edges_out.name_offsets.len() as u64;
    let edges_bytes = edges_out.finish(edge_count)?;

    let intermediate_bytes = inter.finish(edge_count)?;
    lanes.finish()?;

    // metadata.bin's magic + version let the device refuse a pack directory
    // holding files from two different vintages instead of misreading it, and its
    // counts are what every other file's length is validated against.
    let mut meta = create(&out_dir.join("metadata.bin"))?;
    meta.write_all(&GRAPH_MAGIC.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&GRAPH_VERSION.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&u64::from(kept).to_le_bytes()).map_err(io_err)?;
    meta.write_all(&edge_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&escape_count.to_le_bytes()).map_err(io_err)?;
    meta.write_all(&named_edges.to_le_bytes()).map_err(io_err)?;
    meta.flush().map_err(io_err)?;

    // elevation.bin: `i16[node_count]` metres, one per node in final-id order — an OPTIONAL sidecar
    // like road_names.bin/lanes.bin, so a build without a DEM simply omits it and the device reads
    // every node's elevation as 0. `node_coords` is already indexed by final node id, so a single
    // forward pass matches the order `nodes.bin` was written in. No metadata count and no
    // GRAPH_VERSION bump: the reader validates its length against node_count and disables it on a
    // mismatch, exactly as it does for lanes.bin.
    if let Some(dem) = dem {
        let mut elev_out = BufWriter::new(create(&out_dir.join("elevation.bin"))?);
        for &(lat_e7, lon_e7) in node_coords {
            let lat = f64::from(lat_e7) * 1e-7;
            let lon = f64::from(lon_e7) * 1e-7;
            let metres = dem.sample_metres(lon, lat).unwrap_or(0);
            elev_out.write_all(&metres.to_le_bytes()).map_err(io_err)?;
        }
        elev_out.flush().map_err(io_err)?;
    }

    Ok(Written {
        edge_count,
        geometry_edges,
        reversed_edges,
        intermediate_bytes,
        edges_bytes,
        escape_count,
        named_edges,
        census,
    })
}
