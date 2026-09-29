/// Tile every feature and write the archive.
/// Tile every feature in `store` into a `.mamaps` archive.
///
/// The store is read **once per zoom**, in order, rather than held in memory for all of them. Fifteen
/// sequential passes over a file cost seconds on any modern disk; holding the features cost 4.9 GB of
/// a measured 10.03 GB California peak.
pub fn build(store: &Store, settings: &Settings) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    adopt_thread_budget();
    let mut writer = writer_for(store, settings)?;
    let per_zoom = tile_zooms(store, settings, &mut writer)?;
    let bytes = writer.finish()?;
    Ok((bytes, per_zoom))
}

/// Tile every feature in `store` straight into the archive at `out`, without ever holding the
/// archive in memory.
///
/// `build` above returns the whole archive as a `Vec<u8>` -- fine for tests, fatal for a planet:
/// the data section alone is tens of GB, and holding it beside the merge's own peak is what OOMs
/// a box with ~50 GB of commit headroom right after z14 merges. This runs the identical zoom loop
/// ([`tile_zooms`]) and finishes through [`StreamWriter::finish_to_path`], so the only thing ever
/// held is the prefix (the index, small next to the bodies). Same tiles, same order, same bytes:
/// the writer appends identically either way, and `finish_to_path` assembles the same
/// header/dictionary/root/leaves/data layout as `finish`.
pub fn build_to_path(
    store: &Store,
    settings: &Settings,
    out: &std::path::Path,
) -> Result<Vec<ZoomStats>> {
    adopt_thread_budget();
    let mut writer = writer_for(store, settings)?;
    let per_zoom = tile_zooms(store, settings, &mut writer)?;
    writer.finish_to_path(out)?;
    Ok(per_zoom)
}

/// The [`StreamWriter`] for a build: zoom range, build id, compression, and the body scratch
/// beside the output.
///
/// The scratch placement matters: the writer spills the data section (tens of GB on a planet)
/// through a temp file, and the default is the system temp dir -- routinely a small system
/// volume. Beside the output it shares the volume the operator already sized for the build
/// (feature spill, tile chunks, archive), and it is removed on drop like every other scratch
/// file. Path only, never bytes: the archive is identical wherever the scratch lives.
fn writer_for(store: &Store, settings: &Settings) -> Result<StreamWriter> {
    let bbox = store.bbox();
    StreamWriter::new(Options {
        min_zoom: 0,
        max_zoom: crate::DEFAULT_MAX_ZOOM,
        build_id: settings.build_id,
        compress: true,
        // Stage C runs on every tile below, so the claim is true. It is what lets the renderer
        // skip its repair pass — and the renderer still keeps that pass, gated, because a claim is
        // only as good as the generator making it.
        rings_validated: true,
        min_lon_e7: bbox.0,
        min_lat_e7: bbox.1,
        max_lon_e7: bbox.2,
        max_lat_e7: bbox.3,
        spill_dir: settings.scratch.parent().map(std::path::Path::to_path_buf),
        ..Options::default()
    })
}

/// The zoom loop both builders share: map, merge, encode and append z0..=z14 through `writer`.
///
/// Extracted whole from `build` so the two finishes cannot diverge: the per-zoom stats, the
/// progress bars, the `check_books` gate and the append order are identical, and only the final
/// assembly differs (in-memory vs streaming).
fn tile_zooms(
    store: &Store,
    settings: &Settings,
    writer: &mut StreamWriter,
) -> Result<Vec<ZoomStats>> {

    let mut per_zoom = Vec::new();
    // The tile-chunk spill's backend, decided once from the feature count: z14 holds the most
    // clipped copies at once (the 53 GB north-america case that motivated the spill), so sizing
    // for the whole store is the safe side. Per-zoom files share one scratch path -- each zoom's
    // spill truncates it on create and removes it on drop, so peak disk is still the largest
    // single zoom.
    let chunk_plan = plan_chunk_spill(store, &settings.scratch);
    // The operator's override wins over the gate: `force_chunk_spill_file` (from
    // `MAPS_ANON_SPILL`, unset or non-`1`) stages through the scratch file even when the gate
    // picks anon. Same chunks, same bytes, no commit charge for the merge to trip over.
    let chunk_plan = if settings.force_chunk_spill_file
        && chunk_plan == osm_ingest::mem::SpillPlan::Anon
    {
        println!(
            "  [tiler] chunk spill -> {} (file backend: MAPS_ANON_SPILL)",
            settings.scratch.display(),
        );
        osm_ingest::mem::SpillPlan::File
    } else {
        chunk_plan
    };
    for z in 0..=crate::DEFAULT_MAX_ZOOM {
        let mut stats = ZoomStats { zoom: z, ..ZoomStats::default() };
        let tolerance =
            simplify::tolerance_for(z, crate::DEFAULT_MAX_ZOOM, DEFAULT_SIMPLIFICATION);
        let buffer = geom::buffer_for(EXTENT);

        // Per zoom, so peak scratch is the largest single zoom rather than the sum, and so a build
        // that dies at z14 leaves one zoom behind rather than fifteen. Anonymous
        // pagefile-backed memory (no file) when commit allows, else the scratch file; the merge
        // reads through the same ChunkReader either way.
        let spill = ChunkSpill::create_planned(&settings.scratch, chunk_plan)?;
        let mapped = std::time::Instant::now();
        let (chunks, tally) = map_zoom(store, settings, z, tolerance, buffer, &spill)?;
        stats.map_ms = mapped.elapsed().as_millis() as u64;
        stats.features = tally.features;
        stats.points = tally.points;
        stats.dropped = tally.dropped;

        // Ascending by tile id because the merge makes it so, not because a sort step was
        // remembered. A `BTreeMap` per chunk and a heap across them is the same guarantee the
        // single-threaded `BTreeMap` gave; the maps now live on disk and the guarantee does not
        // change, because a chunk's bytes are written in its key order and read back in file order.
        let mut merged = merge(&chunks, &spill);
        // Merge, encode and append have no total to count against -- the tile count is only known once
        // the merge has produced it -- so the merge bar counts tiles merged and the encode line below
        // counts tiles written. Two numbers that converge at the end of the zoom. Still the difference
        // between forty silent minutes and a number that moves: the merge is single-threaded and is
        // where a zoom's wall clock goes.
        let mut merged_tiles = 0usize;
        let mut merge_shown = 0usize;
        let mut written = 0usize;
        let mut shown = 0usize;
        loop {
            // Cost-batched rather than count-batched: z14 has tiles whose merged geometry is tens
            // of MB (a dense city tile's whole layer set) beside rural tiles worth bytes. A
            // fixed `batch_len()` of those holds ~GB of merged tiles plus their encoded copies
            // before the writer takes them -- on top of the merge's own peak, on a box with
            // ~50 GB of commit headroom, which is where `Map z14 [100%]` went followed by a
            // 512 KiB allocation failure. Capped at `MERGE_BATCH_BYTES` of merged cost, so a
            // heavy tile still gets its own batch (a minimum of one batch per pull) and common
            // zooms -- whose batches never reach the cap -- take the same count they always
            // did. Order is untouched: batches are contiguous pulls of the merge in order, and
            // `encode_batch` preserves tile order, so the archive does not move.
            //
            // Batched rather than a task per tile: one tile's stage C and encode is tens of
            // microseconds and rayon's stealing costs more than that. Batched rather than a whole
            // zoom at once because the batch is what bounds the encoded bytes held before the
            // writer takes them.
            //
            // The merge is timed around `collect` because that is where it happens: `merge` returns
            // a lazy iterator, so pulling a batch out of it is the k-way merge doing its work.
            let merging = std::time::Instant::now();
            // `collect` into a `Result`, because a truncated scratch file must fail the build rather
            // than end the zoom early and publish a short archive.
            let batch: Vec<(u64, Vec<ChunkEntry>)> =
                merge_batch(&mut merged, par::batch_len())?;
            stats.merge_ms += merging.elapsed().as_millis() as u64;
            if batch.is_empty() {
                break;
            }
            // The merge did its work when `collect` returned: count the tiles now, so the bar moves
            // during the serial merge rather than only when the parallel encode drains.
            merged_tiles += batch.len();
            if merged_tiles - merge_shown >= 25_000 {
                merge_shown = merged_tiles;
                eprint!("\r{:<28} [{merged_tiles:>10} tile(s)]", format!("Merge z{z}"));
                let _ = std::io::Write::flush(&mut std::io::stderr());
            }
            let encoding = std::time::Instant::now();
            let done = encode_batch(batch, &settings.dem, store.conventions(), &settings.region_links)?;
            stats.encode_ms += encoding.elapsed().as_millis() as u64;
            let appending = std::time::Instant::now();
            for (id, encoded, rings, lines) in done {
                stats.rings.add(rings);
                stats.lines.add(lines);
                let encoded = encoded;
                let Some((stored, raw_len, hash)) = encoded else { continue };
                // Uncompressed, as this column has always meant.
                stats.bytes += raw_len as u64;
                stats.tiles += 1;
                writer.append_stored_with_hash(id, &stored, hash)?;
                written += 1;
                // Every 25k tiles: often enough to look alive on a continent, rare enough that the
                // write itself is never the cost.
                if written - shown >= 25_000 {
                    shown = written;
                    eprint!("\r{:<28} [{written:>10} tile(s)]", format!("Encode z{z}"));
                    let _ = std::io::Write::flush(&mut std::io::stderr());
                }
            }
            stats.append_ms += appending.elapsed().as_millis() as u64;
        }
        if merged_tiles > 0 {
            eprintln!("\r{:<28} [{merged_tiles:>10} tile(s)]", format!("Merge z{z}"));
        }
        if written > 0 {
            eprintln!("\r{:<28} [{written:>10} tile(s)]", format!("Encode z{z}"));
        }
        // Reserved, written, on disk and read back must agree. A chunk that quietly lost entries
        // would produce an archive with holes in it and nothing downstream could tell.
        drop(merged);
        spill.check_books()?;
        per_zoom.push(stats);
    }

    Ok(per_zoom)
}

/// One merge pull, capped by merged-geometry cost rather than by tile count.
///
/// Pulls up to `max_tiles` from the merge, stopping early once the accumulated `tile_cost` --
/// features plus coordinates, the same proxy the encode pass balances tasks by -- reaches
/// [`MERGE_BATCH_BYTES`]. Always pulls at least one tile, so a tile heavier than the cap on its
/// own still makes progress. The pulled prefix is contiguous merge order, so feeding it to
/// `encode_batch` (which preserves tile order) keeps the archive byte-identical: the only
/// change is how many tiles one batch holds.
fn merge_batch(
    merged: &mut Merged<'_>,
    max_tiles: usize,
) -> Result<Vec<(u64, Vec<ChunkEntry>)>> {
    let mut batch: Vec<(u64, Vec<ChunkEntry>)> = Vec::new();
    let mut cost: u64 = 0;
    for tile in merged.by_ref().take(max_tiles) {
        let (id, layers) = tile?;
        cost += tile_cost(&layers);
        batch.push((id, layers));
        // Checked after pushing, so the batch is never empty and a heavy tile closes its own
        // batch -- the same shape as `partition_by_cost`'s grouping, for the same reason.
        if cost >= MERGE_BATCH_BYTES {
            break;
        }
    }
    Ok(batch)
}

/// Cap on one merge pull's merged-geometry cost, in `tile_cost` units (features + coordinates).
///
/// 8 M units is ~64 MB of merged tile geometry per batch at typical z14 density -- small against
/// the box, large against the pool (still hundreds of tiles per batch on common zooms, so the
/// encode pass stays fed). The failure this bounds held ~GB: `batch_len()` tiles at 64 threads
/// is 4096 tiles, and a planet z14 batch of those carries dense-city tiles tens of MB each.
const MERGE_BATCH_BYTES: u64 = 8 << 20;

/// Which backend one zoom's tile-chunk spill stages in.
///
/// The chunk spill holds clipped copies -- one record per (feature, tile) pair -- so its size
/// scales with the store's feature count times the tiling fan-out, not with anything known
/// exactly up front. The estimate is `features x 512 B`: a clipped copy carries the feature's
/// packed body (tens of bytes) plus its share of parts/coords, and z14's fan-out dominates.
/// Over-estimating files a zoom that would have fit anon; under-estimating pages it. Files are
/// the safe side -- same chunks, removed on drop -- so the constant leans that way.
///
/// Unknown limits (or a small store under 1 M features): the historical anon path, no behaviour
/// change.
fn plan_chunk_spill(store: &Store, scratch: &std::path::Path) -> osm_ingest::mem::SpillPlan {
    const CHUNK_BYTES_PER_FEATURE: u64 = 512;
    const SMALL_STORE_FEATURES: u64 = 1_000_000;
    if store.len() < SMALL_STORE_FEATURES {
        return osm_ingest::mem::SpillPlan::Anon;
    }
    let mut budget = osm_ingest::mem::StageBudget::default();
    // Reuse the anon-commit field for the chunk estimate: it is the number `decide` compares
    // against the commit allowance, and the chunk spill is the only anon charge this phase adds
    // (the feature spill is already staged and counted separately).
    budget.ways_spill_bytes = store.len().saturating_mul(CHUNK_BYTES_PER_FEATURE);
    match osm_ingest::mem::SpillPlan::decide(&budget, scratch, "tiler chunk spill") {
        Ok(plan) => {
            if plan != osm_ingest::mem::SpillPlan::Anon {
                println!(
                    "  [tiler] chunk spill -> {} (file backend: commit budget)",
                    scratch.display(),
                );
            }
            plan
        }
        Err(e) => {
            // A zoom that fits neither backend is not a hard failure here: later zooms are
            // smaller, and each zoom re-creates the spill. Warn and take files -- the operator
            // sees the message and the build still has a chance.
            eprintln!("WARNING: {e}; continuing with file staging");
            osm_ingest::mem::SpillPlan::File
        }
    }
}

/// The map half of one zoom: every feature in the store, clipped into per-chunk tile maps and
/// spilled to `spill` as each is finished.
///
/// Returns the chunks **in read order**, which is the only order the reduce may use them in.
///
/// The reader gets a thread of its own rather than a task on the pool, and that is not a
/// preference. A producer that blocks on a full channel from inside the pool it feeds deadlocks when
/// the pool has one thread: the single worker *is* the producer, so nothing can drain what it is
/// waiting to write. Off the pool, one thread is merely slow — which matters, because one thread is
/// a configuration this has to stay byte-identical at.
fn map_zoom(
    store: &Store,
    settings: &Settings,
    z: u8,
    tolerance: f64,
    buffer: f64,
    spill: &ChunkSpill,
) -> Result<(Vec<ChunkRef>, Tally)> {
    // The full budget, **not** minus the reader's prefetch lanes. Subtracting them was tried, on the
    // reasoning that 64 workers plus 16 lanes plus a reader is 81 runnable threads on 64 CPUs. It
    // fixed us-west (151.9 s to 94.4 s) and cost north-america more than it saved (766.9 s to
    // 900.4 s), because the premise is only true when the lanes are actually running: on
    // north-america the workers are the ones blocked, waiting on a channel the reader cannot fill
    // fast enough, so the lanes were competing with nothing and the subtraction just removed a
    // quarter of the clipping. z14's map went 138.7 s to 170.0 s, almost exactly the ratio.
    //
    // Which of those two regimes a build is in is what `crate::store::PREFETCH_LANES` has to be set
    // for, and it is not knowable from the thread count.
    let workers = par::threads().max(1);
    // Bounded, because this is the one place the parallel tiler holds features the sequential one
    // did not: `2 * threads` chunks of about `chunk_vertices()` vertices, and no more however far
    // ahead of the workers the reader gets.
    let (send, receive) = std::sync::mpsc::sync_channel::<(usize, Vec<Feature>)>(workers * 2);
    let receive = Mutex::new(receive);
    let done: Mutex<Vec<(usize, ChunkRef, Tally)>> = Mutex::new(Vec::new());
    let failed = std::sync::atomic::AtomicBool::new(false);

    std::thread::scope(|scope| -> Result<()> {
        let reader = std::thread::Builder::new()
            .name("mamaps-read".to_string())
            .spawn_scoped(scope, move || read_chunks(store, z, send))
            .map_err(|e| tile_build::proto::Error(format!("cannot start the store reader: {e}")))?;

        // One worker runs on this thread whatever happens, so a machine that will not give us
        // threads is slow rather than a build hung on a channel nobody is draining.
        let mut spawned = Vec::new();
        for i in 1..workers {
            let worker = std::thread::Builder::new()
                .name(format!("mamaps-tile-{i}"))
                .stack_size(WORKER_STACK)
                .spawn_scoped(scope, || {
                    tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer, &settings.country_zooms)
                });
            match worker {
                Ok(handle) => spawned.push(handle),
                Err(e) => {
                    eprintln!("WARNING: cannot start tiling thread {i} ({e}); continuing without");
                    break;
                }
            }
        }
        tile_chunks(&receive, &done, &failed, spill, z, tolerance, buffer, &settings.country_zooms);
        for handle in spawned {
            handle.join().map_err(|_| {
                tile_build::proto::Error("a tiling thread panicked".to_string())
            })?;
        }
        reader.join().map_err(|_| {
            tile_build::proto::Error("the store reader thread panicked".to_string())
        })?
    })?;

    if failed.load(Ordering::Relaxed) {
        // Refused rather than published. A skipped chunk is a handful of features missing from a
        // handful of tiles: no error on device, no visibly broken tile, just an archive that is
        // quietly not the one the report describes.
        return err(format!("a chunk of z{z} could not be tiled; see the message above"));
    }

    let mut chunks = done.into_inner().expect("the chunk list outlives its workers");
    // **The reduce order.** By the index a chunk was read at, never by the order it finished in, and
    // never by where in the scratch file it happened to land. This one line is what keeps the
    // archive independent of the thread count.
    chunks.sort_by_key(|(index, _, _)| *index);
    let mut tally = Tally::default();
    for (_, _, chunk_tally) in &chunks {
        tally.add(*chunk_tally);
    }
    Ok((chunks.into_iter().map(|(_, at, _)| at).collect(), tally))
}

/// Cut the store into chunks of roughly [`chunk_vertices`] input vertices and send them on.
///
/// The cut is a function of the feature stream alone — not of the thread count, not of how fast the
/// workers drain — so two runs chunk identically even before the merge makes the boundaries
/// invisible.
///
/// A feature below the zoom's own floor never arrives: [`crate::store::ZoomReader`] drops it in one
/// of its prefetch lanes. That filter used to be right here, and here it was one thread discarding
/// most of a billion features — see the reader's own docs for what that cost.
/// Nanoseconds spent deserialising the spill, summed across every zoom.
///
/// Measured because `map_ms` covers the whole of [`map_zoom`], reader included, so it reports the
/// single-threaded reader as though it were parallel work. This is the number that says how much of
/// the map phase cannot be spread over the pool: time inside `reader.next()` alone, excluding any
/// blocking on the bounded channel, which is the workers being slow rather than the reader being the
/// bottleneck.
static READ_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Take the accumulated reader time and reset it.
pub fn read_seconds() -> f64 {
    READ_NANOS.swap(0, Ordering::Relaxed) as f64 / 1e9
}
