/// Encode the largest prefix of `candidates` that fits the byte budget.
///
/// Binary search on the prefix length: `O(log n)` gzip calls per tile rather than
/// one per dropped feature, and the answer does not depend on the search order.
/// Returns the gzipped body, how many features it holds, and whether it is still
/// over budget -- which happens when even a single feature does not fit, and is the
/// one case worth telling the operator about.
fn fit_tile(
    candidates: &[TileCandidate],
    layer_name: &str,
    geom_type: GeomType,
    opts: &Options,
    gz: &mut crate::gz::Compressor,
) -> Result<(Vec<u8>, usize, bool)> {
    // Geometry command integers, computed ONCE for the whole tile.
    //
    // These do not depend on `n`, and the binary search calls `encode` about
    // `log2(len)` times -- so building them inside the probe re-did the same work
    // eleven times over, one fresh `Vec<u32>` allocation per candidate per probe. On
    // California z11 that is 4.36 M records x ~11 probes of pure waste, and it was
    // most of the 92 s that zoom spent encoding.
    let geoms: Vec<Vec<u32>> = candidates
        .iter()
        .map(|c| match c.geom {
            IntGeometry::Points(p) => mvt::encode_points(p),
            IntGeometry::Lines(l) => mvt::encode_lines(l),
            IntGeometry::Polygons(p) => mvt::encode_polygons(p),
        })
        .collect();
    // Candidates whose geometry survived, ascending. A candidate that clips away to
    // nothing is skipped rather than emitted, exactly as before.
    let live: Vec<usize> = (0..candidates.len())
        .filter(|&i| !geoms[i].is_empty())
        .collect();

    let mut encode = |n: usize| -> Vec<u8> {
        // `live` is ascending, so this is precisely the surviving candidates among the
        // first `n` -- the same set the old per-probe loop produced.
        let refs = live
            .iter()
            .take_while(|&&i| i < n)
            .map(|&i| MvtFeature {
                id: None,
                geom_type,
                geometry: &geoms[i],
                // Properties are BORROWED. Cloning them per candidate per tile was
                // ~35% of this function's cost, and a feature reaching many tiles paid
                // it once per tile. See `mvt::FeatureRef`.
                props: candidates[i].props,
            });
        let t0 = std::time::Instant::now();
        let body = mvt::encode_tile_from(layer_name, opts.extent, refs);
        let t1 = std::time::Instant::now();
        let out = gz.compress(&body);
        MVT_NANOS.fetch_add(
            (t1 - t0).as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
        GZIP_NANOS.fetch_add(
            t1.elapsed().as_nanos() as u64,
            std::sync::atomic::Ordering::Relaxed,
        );
        out
    };

    let all = encode(candidates.len());
    if all.len() <= opts.max_tile_bytes {
        return Ok((all, candidates.len(), false));
    }

    // Largest n in 1..len with encode(n) within budget. `lo` is always known to
    // fit or to be the floor of 1; `hi` is known not to.
    //
    // The probe SEQUENCE is part of the output contract, so do not "improve" this into
    // a galloping search. Gzipped size is not strictly monotone in n -- adding a
    // feature can occasionally compress to fewer bytes -- so a search that visits
    // different n lands on a different boundary. Tried on California: galloping up
    // from 1 changed the drop count from 710,215 to 710,217 and moved the archive,
    // while saving nothing, because the cost here is not the number of probes.
    let mut lo = 1usize;
    let mut hi = candidates.len();
    let mut best = encode(1);
    while lo < hi {
        let mid = lo + (hi - lo).div_ceil(2);
        let body = encode(mid);
        if body.len() <= opts.max_tile_bytes {
            best = body;
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    // One feature that does not fit is kept anyway: an empty tile is a hole in the
    // map, an oversized one is merely slow.
    let over = best.len() > opts.max_tile_bytes;
    Ok((best, lo, over))
}

/// A cheap importance proxy: the geometry's span in tile units, as a single
/// number. Bigger means more of the tile is affected by keeping it.
fn extent_of(g: &IntGeometry) -> i64 {
    let mut min = (i32::MAX, i32::MAX);
    let mut max = (i32::MIN, i32::MIN);
    let mut seen = false;
    let mut add = |(x, y): (i32, i32)| {
        seen = true;
        min = (min.0.min(x), min.1.min(y));
        max = (max.0.max(x), max.1.max(y));
    };
    match g {
        IntGeometry::Points(p) => p.iter().for_each(|p| add(*p)),
        IntGeometry::Lines(l) => l.iter().flatten().for_each(|p| add(*p)),
        IntGeometry::Polygons(p) => p.iter().flatten().flatten().for_each(|p| add(*p)),
    }
    if !seen {
        return 0;
    }
    (max.0 as i64 - min.0 as i64) + (max.1 as i64 - min.1 as i64)
}

/// The MVT geometry type for the layer.
///
/// MVT tags each feature individually, but a layer is styled as one thing, so a
/// mixed layer is a mistake somewhere upstream. Taking the first feature's type
/// and applying it throughout makes that mistake visible as wrong rendering rather
/// than hiding it as a silently split layer.
fn dominant_geom_type(features: &[Feature]) -> GeomType {
    geom_type_of(features.first().map(|f| GeomKind::of(&f.geometry)))
}

/// The same rule, from a kind a source reported rather than a feature in hand.
fn geom_type_of(kind: Option<GeomKind>) -> GeomType {
    match kind {
        Some(GeomKind::Points) => GeomType::Point,
        Some(GeomKind::Lines) => GeomType::LineString,
        Some(GeomKind::Polygons) => GeomType::Polygon,
        None => GeomType::Unknown,
    }
}

fn lonlat_bounds(features: &[Feature]) -> Option<geom::Rect> {
    features.iter().fold(None, |acc, f| fold_bounds(acc, &f.geometry))
}

/// Grow `acc` to cover `g`. The whole of [`lonlat_bounds`], so the streaming path can
/// fold the same function over a source it only ever sees one feature at a time and
/// arrive at the same header bytes.
pub fn fold_bounds(acc: Option<geom::Rect>, g: &Geometry) -> Option<geom::Rect> {
    let Some(b) = geom::bounds(g) else { return acc };
    Some(match acc {
        None => b,
        Some(a) => geom::Rect {
            min_x: a.min_x.min(b.min_x),
            min_y: a.min_y.min(b.min_y),
            max_x: a.max_x.max(b.max_x),
            max_y: a.max_y.max(b.max_y),
        },
    })
}

pub(crate) fn e7(deg: f64) -> i32 {
    (deg * 1e7).round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it to
/// render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful `vector_layers` list keeps the archive introspectable.
fn metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

// --- the streaming producer -----------------------------------------------

/// How much disk and recursion the streaming producer may use.
pub struct StreamLimits {
    /// Buckets per partition level. Must be a power of four so a bucket is exactly one
    /// quadtree cell's descendants, and must stay under the process's file-descriptor
    /// limit — see [`crate::spill::BucketSet`].
    pub buckets: usize,
    /// A bucket over this many bytes is re-partitioned rather than loaded. This is the
    /// number that sets peak memory, because a loaded bucket is resident.
    pub bucket_budget_bytes: u64,
    pub max_repartition_depth: u32,
}

/// 256 buckets: four levels of the quadtree per partition, and well under any
/// descriptor limit.
pub const DEFAULT_BUCKETS: usize = 256;

/// 512 MiB of encoded records per bucket. Decoded they cost more, so this is a budget
/// on the spill bytes rather than a promise about RSS; Phase 6's measurement is what
/// turns it into one.
pub const DEFAULT_BUCKET_BUDGET_BYTES: u64 = 512 << 20;

/// Recursion cap, so a pathological range cannot spin.
///
/// Not the thing that terminates the recursion: a child's span is its parent's range
/// divided by the bucket count, so it strictly shrinks and the single-`tile_id` floor is
/// always reached. This is the backstop, and it has to have room for the smallest useful
/// bucket count — 4 buckets need one level per zoom, so 24 covers z16 with headroom
/// while 8 would refuse a build the partition could have finished.
pub const DEFAULT_MAX_REPARTITION_DEPTH: u32 = 24;

impl Default for StreamLimits {
    fn default() -> StreamLimits {
        StreamLimits {
            buckets: DEFAULT_BUCKETS,
            bucket_budget_bytes: DEFAULT_BUCKET_BUDGET_BYTES,
            max_repartition_depth: DEFAULT_MAX_REPARTITION_DEPTH,
        }
    }
}

/// A rewindable feature stream, which is all [`build_archive_to`] needs of its input.
///
/// Rewindable because the producer makes one pass per zoom: six passes over a compact
/// binary is far cheaper than six re-parses of planet GeoJSON, and it is what lets the
/// features not be resident.
///
/// `bounds` and `geom_kind` come from a fold the caller already did while writing the
/// source, so there is no bounds pre-pass:
/// [`crate::pmtiles::StreamBuilder`] serialises its header last.
pub trait FeatureSource {
    fn rewind(&mut self) -> Result<()>;
    fn next(&mut self) -> Result<Option<Feature>>;
    /// How many features a pass will yield, for the progress bar.
    fn len(&self) -> u64;
    fn is_empty(&self) -> bool {
        self.len() == 0
    }
    fn bounds(&self) -> Option<geom::Rect>;
    /// The FIRST feature's kind, the rule [`dominant_geom_type`] already applies.
    fn geom_kind(&self) -> Option<GeomKind>;

    /// A positionally-readable view of the same features, when there is one.
    ///
    /// `Some` lets the bucket pass read and decode the source across the pool rather
    /// than through this cursor; `None` keeps it sequential. Only the file-backed source
    /// has one: [`SliceSource`]'s features are already resident and there is nothing to
    /// read. It is also `None` for a file written before the index existed, or an empty
    /// one, so the sequential path stays the fallback rather than a special case.
    fn chunks(&self) -> Option<&spill::NormalizedChunks> {
        None
    }
}

/// A [`FeatureSource`] over features already in memory.
///
/// What the byte-identity tests drive the streaming producer from, so they need no
/// temporary input file and compare exactly the same features the in-memory path saw.
pub struct SliceSource<'a> {
    features: &'a [Feature],
    at: usize,
    bounds: Option<geom::Rect>,
    kind: Option<GeomKind>,
}

impl<'a> SliceSource<'a> {
    pub fn new(features: &'a [Feature]) -> SliceSource<'a> {
        SliceSource {
            features,
            at: 0,
            bounds: lonlat_bounds(features),
            kind: features.first().map(|f| GeomKind::of(&f.geometry)),
        }
    }
}

impl FeatureSource for SliceSource<'_> {
    fn rewind(&mut self) -> Result<()> {
        self.at = 0;
        Ok(())
    }

    fn next(&mut self) -> Result<Option<Feature>> {
        let f = self.features.get(self.at).cloned();
        if f.is_some() {
            self.at += 1;
        }
        Ok(f)
    }

    fn len(&self) -> u64 {
        self.features.len() as u64
    }

    fn bounds(&self) -> Option<geom::Rect> {
        self.bounds
    }

    fn geom_kind(&self) -> Option<GeomKind> {
        self.kind
    }
}

/// A [`FeatureSource`] over [`crate::spill`]'s normalized file. The production path.
pub struct NormalizedSource {
    reader: spill::NormalizedReader,
    summary: spill::NormalizedSummary,
    chunks: Option<spill::NormalizedChunks>,
}

impl NormalizedSource {
    /// `summary` is what [`crate::spill::NormalizedWriter::finish`] returned for this
    /// file. Passed in rather than re-derived, because deriving it would be the pre-pass
    /// the design exists to avoid.
    pub fn open(
        path: impl Into<std::path::PathBuf>,
        summary: spill::NormalizedSummary,
    ) -> Result<NormalizedSource> {
        let path = path.into();
        // A second handle on the same file, for the chunked reads. Separate because the
        // sequential reader owns a cursor and positional reads must not disturb it.
        let chunks = if summary.chunk_count() == 0 {
            None
        } else {
            Some(spill::NormalizedChunks::open(
                path.clone(),
                summary.chunks.clone(),
            )?)
        };
        Ok(NormalizedSource {
            reader: spill::NormalizedReader::open(path)?,
            summary,
            chunks,
        })
    }
}

impl FeatureSource for NormalizedSource {
    fn rewind(&mut self) -> Result<()> {
        self.reader.rewind()
    }

    fn next(&mut self) -> Result<Option<Feature>> {
        Ok(self.reader.next()?.map(|f| Feature {
            geometry: f.geometry,
            props: f.props,
        }))
    }

    fn len(&self) -> u64 {
        self.summary.count
    }

    fn bounds(&self) -> Option<geom::Rect> {
        self.summary.bounds
    }

    fn geom_kind(&self) -> Option<GeomKind> {
        self.summary.geom_kind
    }

    fn chunks(&self) -> Option<&spill::NormalizedChunks> {
        self.chunks.as_ref()
    }
}

/// Features bucketed in one zoom's first pass.
const BUCKETED: &str = "feature(s) bucketed";

/// Spill records encoded in one zoom's second pass.
const ENCODED: &str = "record(s) encoded";

/// One zoom's bucket pass: project every feature, clip it into each tile it reaches, and
/// write a spill record per `(feature, tile)`.
///
/// Two read shapes, one output. Both derive `seq` from the feature's index in the source
/// rather than from arrival order, which is what makes them interchangeable:
/// `encode_buckets` sorts each bucket by the total key `(tile_id, extent, seq)`, so push
/// order never reaches the archive.
#[allow(clippy::too_many_arguments)]
fn bucket_pass(
    src: &mut impl FeatureSource,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    set: &mut spill::BucketSet,
    bar: &mut Progress,
) -> Result<()> {
    if let Some(chunks) = src.chunks() {
        return bucket_chunked(chunks, z, opts, buffer, tolerance, set, bar);
    }
    bucket_serial(src, z, opts, buffer, tolerance, set, bar)
}
