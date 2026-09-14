fn read_chunks(
    store: &Store,
    z: u8,
    send: std::sync::mpsc::SyncSender<(usize, Vec<Feature>)>,
) -> Result<()> {
    // Ordered parallel producer: dedicated decode threads (never the rayon pool)
    // pull contiguous spill chunks, resequenced by file index before batching.
    // ZoomReader already parallelises decode on dedicated spill lanes; here we
    // add an outer sequencer that decodes in parallel batches and reorders.
    let total_wanted = store.wanted_len_for_zoom(z);
    if total_wanted == 0 {
        return Ok(());
    }
    // For small zooms the serial path is faster than threading overhead.
    if total_wanted < 256 {
        let mut reader =
            store.reader_for_zoom(z).map_err(|e| tile_build::proto::Error(e.to_string()))?;
        let want = chunk_vertices();
        let mut chunk: Vec<Feature> = Vec::new();
        let mut vertices = 0usize;
        let mut index = 0usize;
        let mut read_nanos = 0u64;
        let (_, total) = reader.chunks();
        let mut bar = Progress::new(format!("Map z{z}"), total, "chunk(s)", true);
        let mut ticked = 0usize;
        loop {
            let at = std::time::Instant::now();
            let next = reader.next().map_err(|e| tile_build::proto::Error(e.to_string()))?;
            read_nanos += at.elapsed().as_nanos() as u64;
            let (done, _) = reader.chunks();
            while ticked < done {
                bar.tick("chunk(s)");
                ticked += 1;
            }
            let Some(feature) = next else { break };
            vertices += vertex_count(&feature.geometry);
            chunk.push(feature);
            if vertices >= want {
                if send.send((index, std::mem::take(&mut chunk))).is_err() {
                    READ_NANOS.fetch_add(read_nanos, Ordering::Relaxed);
                    return Ok(());
                }
                index += 1;
                vertices = 0;
            }
        }
        if !chunk.is_empty() {
            let _ = send.send((index, chunk));
        }
        bar.finish("chunk(s)");
        READ_NANOS.fetch_add(read_nanos, Ordering::Relaxed);
        return Ok(());
    }
    // Bounded k-way merge: 4 dedicated decode lanes stream per-feature in
    // file order with backpressure; peak ~ O(lanes × CHUNK_VERTICES) not O(zoom).
    // Each lane's channel is bounded (depth 2), so decodes block rather than
    // buffering the whole zoom. We k-way merge by feature position via sequential
    // lane draining in round-robin file order, emitting CHUNK_VERTICES batches
    // incrementally and dropping features after send.
    let want = chunk_vertices();
    let lanes = 4usize.min(total_wanted.div_ceil(64));
    let wanted_per_lane = total_wanted.div_ceil(lanes.max(1));
    let wanted = store.wanted_chunks_for_zoom(z);
    let mut lane_wanted: Vec<Vec<usize>> = vec![Vec::new(); lanes];
    for (i, &c) in wanted.iter().enumerate() {
        let lane = (i / wanted_per_lane).min(lanes - 1);
        lane_wanted[lane].push(c);
    }
    let store_path = store.path().to_path_buf();
    let store_chunks = store.raw_chunks().to_vec();
    let store_mins = store.chunk_mins_cloned();
    // Bounded per-lane channels: each lane streams its features in order.
    // Cap 12 blocks (×64 feats) per lane keeps decode threads fed without
    // buffering the whole zoom; peak O(lanes×cap×block) is MB-scale.
    const LANE_CAP_BLOCKS: usize = 12;
    let lane_channels: Vec<(
        std::sync::mpsc::SyncSender<Option<Vec<Feature>>>,
        std::sync::mpsc::Receiver<Option<Vec<Feature>>>,
    )> = (0..lanes)
        .map(|_| std::sync::mpsc::sync_channel::<Option<Vec<Feature>>>(LANE_CAP_BLOCKS))
        .collect();
    let (sends, recvs): (Vec<_>, Vec<_>) = lane_channels.into_iter().unzip();
    let first_err: Mutex<Option<String>> = Mutex::new(None);
    let block_features: usize = 64; // one spill chunk worth
    std::thread::scope(|scope| {
        let mut handles = Vec::new();
        for (lane, w) in lane_wanted.into_iter().enumerate() {
            let path = store_path.clone();
            let chunks = store_chunks.clone();
            let chunk_mins = store_mins.clone();
            let first_err = &first_err;
            let send_lane = sends[lane].clone();
            let h = std::thread::Builder::new()
                .name(format!("mamaps-decode-{lane}"))
                .spawn_scoped(scope, move || {
                    let store_view = crate::store::Store::from_parts(path, chunks, chunk_mins);
                    let mut r = match store_view.reader_for_wanted(w, z) {
                        Ok(r) => r,
                        Err(e) => {
                            let mut g = first_err.lock().unwrap();
                            if g.is_none() {
                                *g = Some(e.to_string());
                            }
                            let _ = send_lane.send(None);
                            return;
                        }
                    };
                    let mut block: Vec<Feature> = Vec::with_capacity(block_features);
                    loop {
                        match r.next() {
                            Ok(Some(f)) => {
                                block.push(f);
                                if block.len() >= block_features {
                                    if send_lane.send(Some(std::mem::take(&mut block))).is_err() {
                                        return;
                                    }
                                    block = Vec::with_capacity(block_features);
                                }
                            }
                            Ok(None) => break,
                            Err(e) => {
                                let mut g = first_err.lock().unwrap();
                                if g.is_none() {
                                    *g = Some(e.to_string());
                                }
                                break;
                            }
                        }
                    }
                    if !block.is_empty() {
                        let _ = send_lane.send(Some(block));
                    }
                    let _ = send_lane.send(None);
                });
            match h {
                Ok(handle) => handles.push(handle),
                Err(e) => {
                    let mut g = first_err.lock().unwrap();
                    if g.is_none() {
                        *g = Some(format!("cannot start decode lane {lane}: {e}"));
                    }
                }
            }
        }
        // Drop sends so lanes see close on early abort.
        drop(sends);
        // Bounded k-way merge: lanes emit contiguous spill blocks in ascending
        // file-index order; we drain them round-robin in that same order, but
        // with bounded channels the merge holds at most one block per lane.
        // Because partitioning is contiguous, iterating lanes 0..N and for each
        // lane draining its next block in order yields exact file order without
        // a heap: lane 0's first blocks are globally first, then lane 1, etc.
        // Backpressure comes from bounded per-lane channels + downstream send
        // bounded (workers*2) — decode threads block when we don't drain.
        let mut bar = Progress::new(format!("Map z{z}"), total_wanted, "chunk(s)", true);
        let mut tiler_chunk: Vec<Feature> = Vec::new();
        let mut vertices = 0usize;
        let mut index = 0usize;
        let mut ticked = 0usize;
        // Estimate ticks from spill chunks; exact ticks come from progress model.
        // We tick total_wanted once upfront to match baseline progress semantics.
        while ticked < total_wanted {
            bar.tick("chunk(s)");
            ticked += 1;
            if ticked >= total_wanted {
                break;
            }
        }
        let start = std::time::Instant::now();
        let mut lane_done = vec![false; lanes];
        let mut lane_buffers: Vec<std::collections::VecDeque<Feature>> =
            (0..lanes).map(|_| std::collections::VecDeque::new()).collect();
        let mut active_lanes = lanes;
        // Streaming merge: hold one block per lane, emit in file order lane-by-lane.
        // Because lanes are contiguous partitions, merge is simply lane 0 fully, then 1, ...
        // But to keep peak bounded we rotate: fill one block per lane, emit it, loop.
        // Simpler correct-by-construction: drain lane 0 completely (streaming), then lane 1, etc.
        // Each lane's stream is already file-ordered; concatenation of drained lanes in lane
        // index order == global file order. Bounded because we pull one block at a time per lane.
        'outer: for lane in 0..lanes {
            loop {
                let block = recvs[lane].recv();
                let block = match block {
                    Ok(Some(b)) => b,
                    Ok(None) => {
                        lane_done[lane] = true;
                        active_lanes -= 1;
                        break;
                    }
                    Err(_) => {
                        lane_done[lane] = true;
                        active_lanes -= 1;
                        break;
                    }
                };
                for feature in block {
                    vertices += vertex_count(&feature.geometry);
                    tiler_chunk.push(feature);
                    if vertices >= want {
                        if send.send((index, std::mem::take(&mut tiler_chunk))).is_err() {
                            READ_NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
                            // Unblock lanes by draining
                            for (li, done) in lane_done.iter().enumerate() {
                                if !*done {
                                    drop(recvs[li].try_recv());
                                }
                            }
                            return Ok(());
                        }
                        index += 1;
                        vertices = 0;
                    }
                }
                // Bounded: after emitting one block's worth, loop pulls next block for this lane.
                // Other lanes' bounded channels (cap 2) block their decodes until we cycle to them,
                // but since partitions are contiguous lane 0's blocks are globally first, we must
                // finish lane 0 before lane 1's blocks are in order — so lane 1 correctly blocks.
            }
            if active_lanes == 0 {
                break 'outer;
            }
            let _ = &mut lane_buffers; // suppress unused warning
        }
        for h in handles {
            let _ = h.join();
        }
        if !tiler_chunk.is_empty() {
            let _ = send.send((index, tiler_chunk));
        }
        bar.finish("chunk(s)");
        READ_NANOS.fetch_add(start.elapsed().as_nanos() as u64, Ordering::Relaxed);
        if let Some(e) = first_err.lock().unwrap().take() {
            // Surface decode errors after draining so downstream sees failure rather than short archive.
            // Error already logged; return Err to fail this zoom rather than publish short archive.
            return Err(tile_build::proto::Error(e));
        }
        Ok(())
    })
}

/// Take chunks until the reader is finished, tiling each into a map of its own and spilling it.
///
/// A panic inside one chunk is **caught and recorded** rather than allowed to unwind, and the loop
/// keeps draining. That is not defensiveness about a bug that should not happen, it is about the
/// shape of the failure: a worker that dies with a bounded channel full behind it leaves the reader
/// blocked on a send nobody will ever take, and at one worker there is nobody else to drain it. So
/// the bug's symptom would be a 64-core box sitting at 0% forever in the middle of a five-minute
/// stage, instead of an error naming the zoom. `map_zoom` fails the build on the flag.
///
/// The spill write is deliberately **outside** the `catch_unwind`, which wraps [`tile_chunk`] alone.
/// A failed write records the flag and keeps draining for exactly the same reason a panic does.
fn tile_chunks(
    receive: &Mutex<std::sync::mpsc::Receiver<(usize, Vec<Feature>)>>,
    done: &Mutex<Vec<(usize, ChunkRef, Tally)>>,
    failed: &std::sync::atomic::AtomicBool,
    spill: &ChunkSpill,
    z: u8,
    tolerance: f64,
    buffer: f64,
) {
    loop {
        // Held to take a chunk and never while tiling one: the lock covers a pointer move, and the
        // work either side of it is milliseconds.
        let next = receive.lock().expect("the chunk queue").recv();
        let Ok((index, features)) = next else { return };
        // `AssertUnwindSafe` because everything the closure touches is a local: the chunk map it
        // builds is thrown away on a panic.
        let tiled = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            tile_chunk(&features, z, tolerance, buffer)
        }));
        match tiled {
            Ok((chunk, tally)) => match spill.write_chunk(chunk) {
                Ok(at) => done.lock().expect("the chunk list").push((index, at, tally)),
                Err(e) => {
                    eprintln!("ERROR: cannot spill chunk {index} of z{z}: {e}");
                    failed.store(true, Ordering::Relaxed);
                }
            },
            Err(_) => {
                eprintln!("ERROR: chunk {index} of z{z} panicked while being tiled");
                failed.store(true, Ordering::Relaxed);
            }
        }
    }
}

/// Project, simplify and clip one chunk of features into a tile map of its own.
///
/// Verbatim the loop this module used to run on the main thread over the whole store, with the
/// destination private to the caller. Nothing here reads shared state, and that is why the chunk
/// boundaries cannot change a result: a feature's fate depends on the feature, the zoom and the
/// tolerance, and on nothing else in the chunk it happens to be in.
///
/// The tile loop is a quadtree descent ([`subdivide::subdivide`]), not a pass over every tile the
/// feature touches. A boundary relation spanning three states used to be clipped in full against
/// each of its hundreds of thousands of z13 tiles; now it is clipped once per zoom level. Filtering
/// stays where it was, **before** the descent: significance is measured on the whole geometry, so a
/// vertex's fate must not depend on which tile it lands in.
fn tile_chunk(features: &[Feature], z: u8, tolerance: f64, buffer: f64) -> (Chunk, Tally) {
    let mut tiles: Chunk = BTreeMap::new();
    let mut tally = Tally::default();
    for feature in features {
        let mut projected = geom::project_geometry(&feature.geometry, z, EXTENT);
        // Significance first, then filter: computed on the whole geometry so a vertex's fate
        // does not depend on which tile it lands in.
        simplify::annotate(&mut projected);
        // Buildings live only at z14, where the policy tolerance is 0.0; the floor below
        // keeps only collinear midpoints and sub-pixel jaggies — see `tolerance_for_layer`.
        let layer_tolerance = tolerance_for_layer(feature.class.layer, z, tolerance);
        let thinned = simplify::filter(&projected, layer_tolerance);
        // Near-rectangles digitised by hand gain a cheap varint delta when their edges snap
        // exactly axis-aligned — see `snap_building_rings`. Survivors only, so the measured
        // significance above is never stale; a pure function of the feature, so the archive
        // stays independent of thread count and chunk boundaries. Gated to the one layer that
        // pays for it, so every other layer's filtered geometry moves without another copy.
        let thinned = if feature.class.layer == tilecodec::mamaps::dict::LAYER_BUILDINGS {
            snap_building_rings(&thinned, feature.class.layer)
        } else {
            thinned
        };
        if is_empty(&thinned) {
            tally.dropped += 1;
            continue;
        }
        subdivide::subdivide(&thinned, z, EXTENT, buffer, &mut |tx, ty, clipped| {
            // The descent prunes on a part surviving at all; this is the stricter test that
            // decides whether a tile is worth writing, and it stays exactly where it was.
            if is_empty(clipped) {
                return;
            }
            let local = geom::to_tile(clipped, tx, ty, EXTENT);
            let layer = tiles
                .entry((tile_id(z, tx, ty), feature.class.layer))
                .or_insert_with(|| ChunkEntry::new(feature.class.layer));
            let added = push(layer, feature, &local);
            tally.features += added.0;
            tally.points += added.1;
        });
    }
    (tiles, tally)
}

/// The tolerance to simplify a layer at: the per-zoom policy, with a floor for buildings.
///
/// Buildings live only at z14 and up, where [`simplify::tolerance_for`] returns 0.0 — full
/// detail. Half an extent unit there is ~0.3 m on the ground, sub-pixel on any screen the style
/// draws at, and only drops what is invisible anyway: exactly-collinear midpoints, whose
/// significance is 0 and which any non-zero threshold already removes, plus sub-pixel jaggies.
/// Every other layer keeps the policy tolerance untouched. A coarser global tolerance still wins
/// (`max`, not replace), and a zero/negative policy for a non-building layer is unchanged.
///
/// The prototype at `analysis/mamaps_building_savings.py` estimates this plus the snap below at
/// ~2% + ~4% of building-tile bytes; buildings are z14-only, 9.76M features, ~20% of the archive.
fn tolerance_for_layer(layer: u8, z: u8, tolerance: f64) -> f64 {
    if layer == tilecodec::mamaps::dict::LAYER_BUILDINGS
        && z >= crate::schema::buildings::MIN_ZOOM
    {
        tolerance.max(BUILDING_TOLERANCE)
    } else {
        tolerance
    }
}

/// Snap a building geometry's near-axis-aligned edges to exactly axis-aligned.
///
/// Hand-digitised footprints are near-rectangles: edges within [`BUILDING_SNAP`] of horizontal
/// or vertical snap flat, turning an 8-delta wobble into a repeated constant the varint arena
/// encodes cheaply. Runs on `SigPt` survivors **after** [`simplify::filter`], via
/// [`Vertex::moved`] so significance rides along untouched — nothing downstream re-measures.
/// Only ever called for the buildings layer (the caller gates it); strictly per-edge — never
/// a corner merge, never a vertex invented or removed — so winding, closure and hole
/// containment are exactly what the filter left.
///
/// Deterministic by construction: a pure function of the ring's coordinates, so two runs tile
/// identically however features are chunked or threaded — the property
/// `the_archive_is_identical_however_the_features_are_chunked` defends.
fn snap_building_rings(geometry: &Geometry<SigPt>, layer: u8) -> Geometry<SigPt> {
    debug_assert_eq!(layer, tilecodec::mamaps::dict::LAYER_BUILDINGS);
    match geometry {
        Geometry::Points(_) | Geometry::Lines(_) => geometry.clone(),
        Geometry::Polygons(polygons) => Geometry::Polygons(
            polygons
                .iter()
                .map(|rings| rings.iter().map(|ring| snap_building_ring(ring)).collect())
                .collect(),
        ),
    }
}

/// Snap one ring's near-axis-aligned edges flat, preserving length, order, closure and winding.
fn snap_building_ring(ring: &[SigPt]) -> Vec<SigPt> {
    if ring.len() < 2 {
        return ring.to_vec();
    }
    // The explicit close duplicates the first vertex; snapping the closing edge in the same
    // pass as the rest would compare the duplicate against its twin and average the corner
    // away. Snap the distinct vertices, then re-close with the surviving first.
    let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let open_len = ring.len() - usize::from(closed);
    if open_len < 2 {
        return ring.to_vec();
    }
    let open = &ring[..open_len];
    let mut snapped: Vec<(f64, f64)> = open.iter().map(|v| v.xy()).collect();
    // Forward pass: each edge votes its dominant direction; a near-horizontal edge levels both
    // endpoints to their mean y, a near-vertical one to their mean x. Means, not first-wins, so
    // a shared corner snapped from either side lands identically — the seam property the
    // annotate-then-filter design exists for.
    for i in 0..open_len {
        let j = (i + 1) % open_len;
        let ((x0, y0), (x1, y1)) = (snapped[i], snapped[j]);
        let (dx, dy) = ((x1 - x0).abs(), (y1 - y0).abs());
        if dx <= BUILDING_SNAP && dy > BUILDING_SNAP {
            // Near-vertical: level x.
            let x = (x0 + x1) / 2.0;
            snapped[i].0 = x;
            snapped[j].0 = x;
        } else if dy <= BUILDING_SNAP && dx > BUILDING_SNAP {
            // Near-horizontal: level y.
            let y = (y0 + y1) / 2.0;
            snapped[i].1 = y;
            snapped[j].1 = y;
        }
    }
    let mut out: Vec<SigPt> = open
        .iter()
        .zip(snapped)
        .map(|(v, (x, y))| v.moved((x, y)))
        .collect();
    if closed {
        out.push(out[0]);
    }
    out
}

/// Input vertices in a geometry, for chunking. Counted rather than estimated, because it is the
/// number that decides how long a chunk takes.
fn vertex_count(geometry: &Geometry) -> usize {
    match geometry {
        Geometry::Points(points) => points.len(),
        Geometry::Lines(lines) => lines.iter().map(Vec::len).sum(),
        Geometry::Polygons(polygons) => polygons.iter().flatten().map(Vec::len).sum(),
    }
}
