/// Read serially, project and clip across the pool, push serially.
///
/// The fallback shape, for a source that is only a cursor. Everything between the read
/// and the write -- projection, clipping, simplification, record encoding -- is
/// per-feature pure and is where the time goes, so that part threads; the read does not.
/// Live memory is one batch's records, which is what bounds the batch.
#[allow(clippy::too_many_arguments)]
fn bucket_serial(
    src: &mut impl FeatureSource,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    let mut rec = Vec::new();
    let mut seq = 0u64;
    let batch_len = par::batch_len();
    let mut batch: Vec<Feature> = Vec::with_capacity(batch_len);
    loop {
        batch.clear();
        while batch.len() < batch_len {
            match src.next()? {
                Some(f) => batch.push(f),
                None => break,
            }
        }
        if batch.is_empty() {
            break;
        }
        let first = seq;
        let encoded: Vec<Result<BucketedFeature>> = if batch.len() == 1 {
            // One feature is not worth a pool round trip, and this is the path the
            // single-threaded case and the tail of every stream take.
            vec![bucket_feature(
                &batch[0].geometry,
                &batch[0].props,
                first,
                z,
                opts,
                buffer,
                tolerance,
                &mut rec,
            )]
        } else {
            par::install(|| {
                batch
                    .par_iter()
                    .enumerate()
                    .with_min_len(par::min_task_len(batch.len()))
                    .map_init(Vec::new, |rec, (i, f)| {
                        bucket_feature(
                            &f.geometry,
                            &f.props,
                            first + i as u64,
                            z,
                            opts,
                            buffer,
                            tolerance,
                            rec,
                        )
                    })
                    .collect()
            })
        };
        for one in encoded {
            let one = one?;
            for &(id, at, len) in &one.spans {
                set.push(id, &one.blob[at..at + len])?;
            }
            bar.tick(BUCKETED);
        }
        seq += batch.len() as u64;
    }
    Ok(())
}

/// Read, decode, project and clip across the pool; push serially.
///
/// The production shape. The serial path's remaining bottleneck was `src.next()`: it
/// decodes a geometry and a property vector per feature, allocating both, and does so
/// once per zoom on whichever single thread owns the cursor while the pool waits. Here a
/// worker takes a whole chunk, reads its byte range positionally, and decodes it itself,
/// so the decode scales with the pool and the pass no longer has a serial middle.
///
/// Chunk `c` starts at feature `c * NORM_CHUNK_FEATURES` by construction of the index,
/// which is what lets this reproduce the sequential path's `seq` exactly rather than
/// approximately.
#[allow(clippy::too_many_arguments)]
fn bucket_chunked(
    chunks: &spill::NormalizedChunks,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    // One chunk per task, and as many chunks per batch as the serial path holds
    // features, so peak live memory is unchanged: a batch's encoded records bound it
    // either way.
    let per_batch = (par::batch_len() / spill::NORM_CHUNK_FEATURES as usize).max(1);
    let total = chunks.chunk_count();
    let mut ids: Vec<usize> = Vec::with_capacity(per_batch);
    let mut at = 0usize;
    while at < total {
        let end = (at + per_batch).min(total);
        ids.clear();
        ids.extend(at..end);
        let encoded: Vec<Result<Vec<BucketedFeature>>> = par::install(|| {
            ids.par_iter()
                .with_min_len(par::min_task_len(ids.len()))
                .map_init(
                    || (Vec::new(), Vec::new(), Vec::new()),
                    |(scratch, feats, rec), &ci| {
                        chunks.read_into(ci, scratch, feats)?;
                        let first = ci as u64 * spill::NORM_CHUNK_FEATURES;
                        let mut out = Vec::with_capacity(feats.len());
                        for (j, f) in feats.iter().enumerate() {
                            out.push(bucket_feature(
                                &f.geometry,
                                &f.props,
                                first + j as u64,
                                z,
                                opts,
                                buffer,
                                tolerance,
                                rec,
                            )?);
                        }
                        Ok(out)
                    },
                )
                .collect()
        });
        for one in encoded {
            for f in one? {
                for &(id, o, len) in &f.spans {
                    set.push(id, &f.blob[o..o + len])?;
                }
                bar.tick(BUCKETED);
            }
        }
        at = end;
    }
    Ok(())
}

/// Build the archive straight to `out`, with peak memory set by the tile count.
///
/// The same drop policy, the same encoder and the same [`ZoomStats`] as
/// [`build_archive`] — which stays as the oracle the byte-identity tests pin this
/// against — but nothing proportional to the input bytes is ever resident. Per zoom:
///
/// 1. **The bucket pass** rewinds `src`, projects each feature once, finds the tiles it
///    touches, and for each of them clips, moves into the tile and simplifies, writing a
///    [`crate::spill`] record. Empties are skipped exactly as [`build_archive`] skips
///    them, so `placed` counts the same things.
/// 2. **The encode pass** walks the buckets in ascending index, re-partitioning any that
///    exceed the budget, and for each loaded bucket sorts by `(tile_id, importance)`,
///    groups runs of equal `tile_id`, and calls the same [`fit_tile`].
///
/// The bucket set is dropped at the end of each zoom, so peak spill disk is the largest
/// SINGLE zoom rather than the sum of them — `tile_id` is zoom-major, so every id at
/// `z` precedes every id at `z+1` and a zoom can be finished before the next begins.
///
/// I/O, with `F` the source, `S_z` a zoom's spill, `T` the archive and `Z` zooms:
/// `read(F) · Z + write(S_z) + read(S_z)` per zoom, plus `3·T` for the writer's scratch
/// round trip. Disk peak is `F + max(S_z) + T`, plus one full copy of an over-budget
/// bucket per level of re-partition below it — an ancestor set's files stay alive while
/// its children are drained, so a heavily skewed metro range costs
/// `depth × that bucket's bytes` on top.
pub fn build_archive_to(
    out: impl AsRef<Path>,
    scratch_dir: impl AsRef<Path>,
    opts: &Options,
    src: &mut impl FeatureSource,
    limits: &StreamLimits,
) -> Result<Vec<ZoomStats>> {
    if opts.min_zoom > opts.max_zoom {
        return err("minzoom is above maxzoom");
    }
    if opts.extent == 0 {
        return err("extent 0");
    }
    // This path needs `zoom_base(max_zoom + 1)` for the bucket range, which
    // `build_archive` never computes and which overflows above z30. Refused rather than
    // left to panic: the in-memory path merely produces nonsense that deep, and a
    // message is a better failure than a shift overflow.
    if opts.max_zoom > 30 {
        return err(format!(
            "maxzoom {} is above 30, the deepest zoom a PMTiles tile id is exact at",
            opts.max_zoom
        ));
    }
    let out = out.as_ref();
    let scratch_dir = scratch_dir.as_ref();
    std::fs::create_dir_all(scratch_dir)
        .map_err(|e| Error(format!("cannot create {}: {e}", scratch_dir.display())))?;

    let geom_type = geom_type_of(src.geom_kind());
    let buffer = geom::buffer_for(opts.extent);

    // Beside the OUTPUT, not in the spill directory: `finish` copies this section into
    // the archive, and a cross-filesystem copy of 40 GB would be needlessly slow.
    let tile_scratch = {
        let mut p = out.as_os_str().to_owned();
        p.push(".tiledata");
        std::path::PathBuf::from(p)
    };
    let mut builder = pmtiles::StreamBuilder::new(tile_scratch)?;
    builder.min_zoom = opts.min_zoom;
    builder.max_zoom = opts.max_zoom;
    builder.center_zoom = opts.min_zoom;
    builder.metadata = metadata(&opts.layer, opts.min_zoom, opts.max_zoom).into_bytes();
    match src.bounds() {
        Some(b) => {
            builder.min_lon_e7 = e7(b.min_x);
            builder.min_lat_e7 = e7(b.min_y);
            builder.max_lon_e7 = e7(b.max_x);
            builder.max_lat_e7 = e7(b.max_y);
            builder.center_lon_e7 = e7((b.min_x + b.max_x) / 2.0);
            builder.center_lat_e7 = e7((b.min_y + b.max_y) / 2.0);
        }
        // `Builder::new` defaults latitude to +/-850_511_290 and `StreamBuilder::new` to
        // +/-850_511_287. Three units, and on an input with no bounds nothing else
        // overwrites them -- so leaving them alone would make the two producers disagree
        // on an empty archive's header.
        None => {
            builder.min_lon_e7 = -1_800_000_000;
            builder.min_lat_e7 = -850_511_290;
            builder.max_lon_e7 = 1_800_000_000;
            builder.max_lat_e7 = 850_511_290;
        }
    }

    let mut report = Vec::new();
    for z in opts.min_zoom..=opts.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..Default::default() };
        let tolerance = simplify::tolerance_for(z, opts.max_zoom, opts.simplification);

        // Named per zoom so a set cannot be mistaken for the previous zoom's, and
        // scoped to the zoom so its files go before the next one allocates disk.
        let mut set = spill::BucketSet::new(
            scratch_dir,
            &format!("z{z}"),
            pmtiles::zoom_base(z),
            pmtiles::zoom_base(z + 1),
            limits.buckets,
        )?;

        src.rewind()?;
        let mut bar = Progress::new(
            format!("{} z{z} bucket", opts.layer),
            src.len() as usize,
            BUCKETED,
            opts.progress,
        );
        let phase = std::time::Instant::now();
        bucket_pass(src, z, opts, buffer, tolerance, &mut set, &mut bar)?;
        bar.finish(BUCKETED);
        let bucketed_in = phase.elapsed();
        set.seal()?;
        let phase = std::time::Instant::now();

        let mut bar = Progress::new(
            format!("{} z{z} encode", opts.layer),
            set.total_records() as usize,
            ENCODED,
            opts.progress,
        );
        let mut prof = EncodeProfile::default();
        encode_buckets(
            &set,
            scratch_dir,
            z,
            0,
            opts,
            geom_type,
            limits,
            &mut builder,
            &mut stats,
            &mut bar,
            &mut prof,
        )?;
        bar.finish(ENCODED);
        if opts.timing {
            let (_, x, y) = pmtiles::tile_zxy(prof.max_group_id);
            use std::sync::atomic::Ordering::Relaxed;
            let mvt_s = MVT_NANOS.swap(0, Relaxed) as f64 / 1e9;
            let gz_s = GZIP_NANOS.swap(0, Relaxed) as f64 / 1e9;
            eprintln!(
                "  z{z}: bucket {:.1}s [{:.0}s CPU], encode {:.1}s [mvt {:.0}s + gzip \
                 {:.0}s of CPU across all workers] ({} record(s), {} tile(s), biggest \
                 z{z}/{x}/{y} with {} candidate(s) = {:.1}%)",
                bucketed_in.as_secs_f64(),
                BUCKET_NANOS.swap(0, Relaxed) as f64 / 1e9,
                phase.elapsed().as_secs_f64(),
                mvt_s,
                gz_s,
                set.total_records(),
                prof.groups,
                prof.max_group,
                prof.max_group as f64 / set.total_records().max(1) as f64 * 100.0,
            );
        }
        report.push(stats);
    }

    builder.finish(out)?;
    Ok(report)
}

/// What the encode pass spent its time on, for `--timing`.
///
/// The interesting number is the LARGEST group: the encode pass parallelises across
/// tiles, so one tile holding a large fraction of a zoom's records sets a floor no
/// thread count can get under.
#[derive(Default)]
struct EncodeProfile {
    groups: usize,
    max_group: usize,
    max_group_id: u64,
}
