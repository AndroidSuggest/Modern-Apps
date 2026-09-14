/// Drain one bucket set in ascending index, recursing into anything over budget.
///
/// Sub-buckets ascend within a parent and parents ascend, so the ids handed to
/// [`crate::pmtiles::StreamBuilder`] ascend at every depth.
#[allow(clippy::too_many_arguments)]
fn encode_buckets(
    set: &spill::BucketSet,
    scratch_dir: &Path,
    z: u8,
    depth: u32,
    opts: &Options,
    geom_type: GeomType,
    limits: &StreamLimits,
    builder: &mut pmtiles::StreamBuilder,
    stats: &mut ZoomStats,
    bar: &mut Progress,
    prof: &mut EncodeProfile,
) -> Result<()> {
    for i in 0..set.len() {
        let records = set.records_in(i);
        if records == 0 {
            continue;
        }
        let bytes = set.bytes_in(i);
        let (lo, hi) = set.range_of(i);

        if bytes > limits.bucket_budget_bytes {
            // `--buckets 1` would give a child covering exactly this range, so the split
            // is a no-op that copies the bucket once per level until the depth cap. A
            // split has to actually narrow the range to be worth doing.
            let splittable = hi - lo > 1
                && limits.buckets > 1
                && depth < limits.max_repartition_depth;
            if !splittable {
                // A single tile's candidates cannot be split any further, and capping
                // them would change which features survive -- an output change, and out
                // of scope. Erroring beats being killed by the OOM reaper halfway
                // through a planet run with nothing to show for it.
                if hi - lo == 1 {
                    let (_, x, y) = pmtiles::tile_zxy(lo);
                    return err(format!(
                        "z{z}/{x}/{y} alone holds {records} spill record(s) in {bytes} byte(s), \
                         over the {}-byte bucket budget; raise --bucket-budget-bytes",
                        limits.bucket_budget_bytes
                    ));
                }
                return err(format!(
                    "z{z} tile ids {lo}..{hi} hold {records} spill record(s) in {bytes} byte(s), \
                     over the {}-byte bucket budget, and the range cannot be split further \
                     (--buckets {}, --max-repartition-depth {}, depth {depth})",
                    limits.bucket_budget_bytes, limits.buckets, limits.max_repartition_depth
                ));
            }
            // `lo` is unique across every sibling at every depth, so the child's files
            // cannot collide with anyone else's.
            let mut child = spill::BucketSet::new(
                scratch_dir,
                &format!("z{z}_d{}_{lo}", depth + 1),
                lo,
                hi,
                limits.buckets,
            )?;
            let mut reader = set
                .reader(i)?
                .ok_or_else(|| Error(format!("bucket {i} of z{z} vanished mid-pass")))?;
            let mut buf = Vec::new();
            while let Some(r) = reader.next()? {
                buf.clear();
                r.encode(&mut buf)?;
                child.push(r.tile_id, &buf)?;
            }
            child.seal()?;
            encode_buckets(
                &child,
                scratch_dir,
                z,
                depth + 1,
                opts,
                geom_type,
                limits,
                builder,
                stats,
                bar,
                prof,
            )?;
            continue;
        }

        let mut recs = set.load(i)?;
        // The total sort key: tile id first, then the drop policy's own order, so a
        // group is already in importance order when `fit_tile` sees it.
        recs.sort_by(|a, b| {
            a.tile_id
                .cmp(&b.tile_id)
                .then_with(|| by_importance_keys(a.extent, a.seq, b.extent, b.seq))
        });

        // Runs of equal `tile_id`. Each run is one candidate tile, and `fit_tile` is
        // pure -- all-shared refs, a fresh `Layer`/`Tile` per call, no statics -- so the
        // runs are independent and that is the seam.
        //
        // Parallel WITHIN a bucket rather than across buckets: one bucket is resident at
        // a time either way, so peak memory is unchanged and `--bucket-budget-bytes`
        // keeps meaning what it says. Going across buckets instead would hold one
        // decoded bucket per thread and turn a 690 MiB peak into ~22 GB at 32 threads.
        let mut groups: Vec<(usize, usize)> = Vec::new();
        let mut k = 0usize;
        while k < recs.len() {
            let id = recs[k].tile_id;
            let mut j = k;
            while j < recs.len() && recs[j].tile_id == id {
                j += 1;
            }
            groups.push((k, j));
            prof.groups += 1;
            if j - k > prof.max_group {
                prof.max_group = j - k;
                prof.max_group_id = id;
            }
            k = j;
        }

        let encode_group = |gz: &mut crate::gz::Compressor,
                            &(k, j): &(usize, usize)|
         -> Result<EncodedTile> {
            let candidates: Vec<TileCandidate> = recs[k..j]
                .iter()
                .map(|r| TileCandidate {
                    seq: r.seq,
                    geom: &r.geom,
                    props: &r.props,
                    extent: r.extent,
                })
                .collect();
            let (body, kept, over) = fit_tile(&candidates, &opts.layer, geom_type, opts, gz)?;
            Ok(EncodedTile {
                id: recs[k].tile_id,
                body,
                kept,
                over_budget: over,
                placed: candidates.len(),
            })
        };

        // A batch at a time, so live memory is a bounded number of encoded bodies
        // rather than every body in the bucket. The fold below is sequential and in
        // ascending group order, which is what keeps `add_tile_raw` fed ascending ids
        // and the `stats` sums and `largest_tile_bytes` max identical to a serial run.
        for chunk in groups.chunks(par::batch_len()) {
            let done: Vec<EncodedTile> = par::install(|| {
                chunk
                    .par_iter()
                    .with_min_len(par::min_task_len(chunk.len()))
                    .map_init(crate::gz::Compressor::new, encode_group)
                    .collect::<Result<Vec<_>>>()
            })?;
            for t in done {
                for _ in 0..t.placed {
                    bar.tick(ENCODED);
                }
                stats.placed += t.placed;
                stats.kept += t.kept;
                stats.dropped += t.placed - t.kept;
                if t.over_budget {
                    stats.over_budget += 1;
                }
                stats.largest_tile_bytes = stats.largest_tile_bytes.max(t.body.len());
                stats.tiles += 1;
                builder.add_tile_raw(t.id, &t.body)?;
            }
        }
    }
    Ok(())
}

/// Print the per-zoom report the drop policy owes the operator.
pub fn print_report(report: &[ZoomStats], out: &mut impl std::io::Write) -> std::io::Result<()> {
    writeln!(out, "  zoom  tiles   placed     kept  dropped  largest")?;
    for s in report {
        writeln!(
            out,
            "  z{:<4} {:>6} {:>8} {:>8} {:>8} {:>8}{}",
            s.zoom,
            s.tiles,
            s.placed,
            s.kept,
            s.dropped,
            s.largest_tile_bytes,
            if s.over_budget > 0 {
                format!("  ({} tile(s) over budget)", s.over_budget)
            } else {
                String::new()
            }
        )?;
    }
    Ok(())
}

// --- the shared CLI -------------------------------------------------------

/// Which geometry a binary will tile. A layer is styled as one thing, so mixing
/// kinds in one archive is a mistake upstream; each binary accepts exactly one and
/// counts what it turns away.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Accept {
    Lines,
    Polygons,
}

impl Accept {
    fn matches(self, g: &Geometry) -> bool {
        matches!(
            (self, g),
            (Accept::Lines, Geometry::Lines(_)) | (Accept::Polygons, Geometry::Polygons(_))
        )
    }

    fn describe(self) -> &'static str {
        match self {
            Accept::Lines => "LineString or MultiLineString",
            Accept::Polygons => "Polygon or MultiPolygon",
        }
    }
}
