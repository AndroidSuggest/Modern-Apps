//! The tile pyramid driver, and the drop policy.
//!
//! Turns a list of lon/lat features into a PMTiles archive:
//!
//! ```text
//! per zoom, per feature: project -> annotate -> tile range
//! per tile:              clip -> filter -> to_tile -> encode -> drop policy
//!                        -> gzip -> pmtiles::Builder
//! ```
//!
//! # The drop policy
//!
//! tippecanoe keeps a tile under its size limit with `--drop-densest-as-needed`, a
//! heuristic that removes whichever features are locally densest and whose result
//! depends on the order it happened to visit them. We do not reproduce it. Instead:
//!
//! 1. Features in a tile are put in a **stable importance order**: largest bounding
//!    box first, ties broken by the feature's index in the input. Size is the best
//!    cheap proxy for "matters at this zoom" -- at z6 a state boundary should
//!    survive and a suburban street should not -- and the index makes it total, so
//!    two runs over the same input always drop the same features.
//! 2. The largest prefix of that order which fits the gzipped byte budget is kept,
//!    found by binary search. A prefix, so the kept set is always the top-k most
//!    important, never a scattered subset.
//! 3. If even one feature does not fit, that one feature is kept anyway and the
//!    tile is reported as over budget. An empty tile is a hole in the map; an
//!    oversized one is a slow tile.
//!
//! Consequences worth being explicit about:
//!
//! * **Per-tile feature counts will not match tippecanoe's.** That is the point of
//!   `test/diff_pmtiles.py --max-feature-delta`: the divergence is bounded and
//!   measured rather than unknown.
//! * **`--extend-zooms-if-still-dropping` is deliberately not reproduced.** It can
//!   push an archive past its own `--maximum-zoom`, so an archive's advertised zoom
//!   range stops being a fact about its contents. Here `--maxzoom` is exactly the
//!   deepest zoom present.
//! * **`--detect-shared-borders` is not implemented.** Two *different* admin
//!   polygons that share a border are simplified independently, so at low zoom the
//!   border can show as a hairline gap or a hairline overlap. Reproducing that needs
//!   a shared topology pass across features, which is a project of its own. The
//!   intra-feature case — a hole and the exterior it shares a clipped tile edge with
//!   — is not affected: [`crate::simplify`] annotates each vertex once on the
//!   unclipped source and filters per zoom, so both rings keep the same vertices
//!   along the edge.

use crate::geom::{self, Geometry, IntGeometry};
use crate::mvt::{self, FeatureRef as MvtFeature, GeomType, Value, DEFAULT_EXTENT};
use crate::par;
use crate::pmtiles::{self, Builder};
use crate::progress::Progress;
use crate::proto::{err, Error, Result};
use crate::simplify;
use crate::spill::{self, GeomKind};
use crate::subdivide;
use rayon::prelude::*;
use std::path::Path;

/// tippecanoe's default maximum gzipped tile size, and what the published
/// archives were built against.
pub const DEFAULT_MAX_TILE_BYTES: usize = 500_000;

/// One feature to tile: geometry in lon/lat, plus its properties.
#[derive(Debug, Clone)]
pub struct Feature {
    pub geometry: Geometry,
    pub props: Vec<(String, Value)>,
}

pub struct Options {
    pub layer: String,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub extent: u32,
    /// Simplification tolerance multiplier; 1.0 is the default policy.
    pub simplification: f64,
    pub max_tile_bytes: usize,
    /// Print a per-zoom progress bar to stdout while tiling.
    ///
    /// Off by default so the library stays silent: the binaries turn it on, and the
    /// tests would otherwise interleave bars with their output.
    pub progress: bool,
    /// Print how long each zoom's bucket and encode pass took, to stderr.
    ///
    /// Separate from `progress` and from [`ZoomStats`]: a duration cannot go in the
    /// report, because the report is compared for equality by the byte-identity tests.
    /// This is the measurement that says which phase to optimise next, and guessing
    /// that from a total is how you end up optimising the wrong one.
    pub timing: bool,
}
impl Options {
    pub fn new(layer: impl Into<String>, min_zoom: u8, max_zoom: u8) -> Options {
        Options {
            layer: layer.into(),
            min_zoom,
            max_zoom,
            extent: DEFAULT_EXTENT,
            simplification: 1.0,
            max_tile_bytes: DEFAULT_MAX_TILE_BYTES,
            progress: false,
            timing: false,
        }
    }

    /// Show the tiling progress bar. What the `tile_*` binaries call.
    pub fn with_progress(mut self) -> Options {
        self.progress = true;
        self
    }
}

/// A one-line progress bar for one zoom's tile loop.
///
/// The count is the tiles that will be written. It used to be *candidate* tiles -- the
/// tiles `geom::tiles_touched` listed, which was larger, because a candidate whose
/// geometry clipped away to nothing was walked and then skipped. The quadtree descent
/// does not reach those tiles at all, so the two numbers are now the same one and the
/// bar agrees with the `tiles` column in the report below.
const TILES: &str = "tile(s)";

/// What one zoom cost, for the per-zoom report the drop policy owes the operator.
#[derive(Debug, Default, Clone, PartialEq)]
pub struct ZoomStats {
    pub zoom: u8,
    pub tiles: usize,
    /// Feature instances placed into tiles before the drop policy ran. A feature
    /// spanning four tiles counts four times, which is what the budget sees.
    pub placed: usize,
    pub kept: usize,
    pub dropped: usize,
    /// Tiles that could not be brought under budget even at one feature.
    pub over_budget: usize,
    pub largest_tile_bytes: usize,
}

/// One feature's contribution to one tile, ready for the drop policy.
///
/// Everything is borrowed. Both producers already own the geometry and the properties
/// somewhere -- the in-memory one in a per-tile vector, the streaming one in a loaded
/// bucket -- and a third copy per candidate would be the largest allocation in the
/// tiler. `extent` is [`extent_of`] precomputed at construction rather than recomputed
/// inside the importance sort, which would rescan every vertex `O(n log n)` times.
struct TileCandidate<'a> {
    /// The feature's position in the input, which is what makes the importance order
    /// total and therefore the drop policy deterministic.
    seq: u64,
    geom: &'a IntGeometry,
    props: &'a [(String, Value)],
    extent: i64,
}

/// One encoded candidate tile, waiting for the sequential fold that writes it.
///
/// The parallel passes produce these and the fold consumes them in `tile_id` order;
/// keeping the fields named is what makes that fold readable, since it has to update
/// five different counters plus the archive.
struct EncodedTile {
    id: u64,
    body: Vec<u8>,
    kept: usize,
    over_budget: bool,
    /// Candidates the tile held before the drop policy ran.
    placed: usize,
}

/// The stable importance order: largest bounding box first, input position breaking
/// ties.
///
/// Taken as loose fields so the streaming producer can sort its records by
/// `(tile_id, importance)` without first building candidates it cannot borrow from a
/// vector it is still sorting. One function either way, so the two producers cannot
/// disagree about which features a tight budget drops -- a disagreement no test
/// comparing anything less than whole bytes would catch.
fn by_importance_keys(
    a_extent: i64,
    a_seq: u64,
    b_extent: i64,
    b_seq: u64,
) -> std::cmp::Ordering {
    b_extent.cmp(&a_extent).then_with(|| a_seq.cmp(&b_seq))
}

fn by_importance(a: &TileCandidate, b: &TileCandidate) -> std::cmp::Ordering {
    by_importance_keys(a.extent, a.seq, b.extent, b.seq)
}

/// One feature's contribution to one zoom's spill: every record it produces, in one
/// allocation.
///
/// One `Vec` per feature rather than one per record, because a coastline at z16
/// reaches thousands of tiles and a `Vec` each would make the allocator the hot
/// path. `spans` indexes `blob`, so pushing is `set.push(id, &blob[at..at + len])`.
struct BucketedFeature {
    blob: Vec<u8>,
    spans: Vec<(u64, usize, usize)>,
}

/// Project one feature, clip it into every tile it reaches, and encode a spill
/// record per tile.
///
/// Pure: every argument is shared except `rec`, which is the caller's per-worker
/// scratch buffer. That is what lets the bucket pass run this across the pool — and
/// the reason the buffer is passed in rather than declared here is the one the serial
/// loop had, unchanged: allocating it per feature would cost three million
/// allocations a zoom.
///
/// The tile loop is [`subdivide::subdivide`], a quadtree descent, rather than a pass
/// over every tile [`geom::tiles_touched`] lists. Filtering stays **after** the clip
/// and per tile, which is where this crate has always done it and where
/// [`build_archive`] still does it -- the two are compared byte for byte, so the step
/// order has to match.
///
/// Takes the geometry and properties rather than a [`Feature`] so the chunked read path
/// can pass a [`crate::spill::NormalizedFeature`]'s fields straight in. The two types
/// hold the same two things, and converting would mean copying a geometry per feature
/// per zoom to gain nothing.
#[allow(clippy::too_many_arguments)]
fn bucket_feature(
    geometry: &Geometry,
    props: &[(String, Value)],
    seq: u64,
    z: u8,
    opts: &Options,
    buffer: f64,
    tolerance: f64,
    rec: &mut Vec<u8>,
) -> Result<BucketedFeature> {
    let t0 = std::time::Instant::now();
    // Project once per feature per zoom, not once per tile: a coastline can cross
    // thousands of tiles and the projection is the expensive part. Annotating here
    // for the same reason, and because it has to see the ring UNCLIPPED -- see
    // `simplify`'s module docs.
    let mut projected = geom::project_geometry(geometry, z, opts.extent);
    simplify::annotate(&mut projected);
    let mut out = BucketedFeature { blob: Vec::new(), spans: Vec::new() };
    // `encode_record` can fail on a record too large to describe, and the descent's
    // callback cannot return. The first failure is held and re-raised below rather
    // than panicking: a `?` inside the closure is not available and unwinding out of
    // it would cross the rayon boundary the callers put us behind.
    let mut failed: Option<Error> = None;
    subdivide::subdivide(&projected, z, opts.extent, buffer, &mut |tx, ty, clipped| {
        if failed.is_some() {
            return;
        }
        let filtered = simplify::filter(clipped, tolerance);
        let local = geom::to_tile(&filtered, tx, ty, opts.extent);
        if local.is_empty() {
            return;
        }
        rec.clear();
        let id = pmtiles::tile_id(z, tx, ty);
        if let Err(e) = spill::encode_record(id, seq, extent_of(&local), &local, props, rec) {
            failed = Some(e);
            return;
        }
        let at = out.blob.len();
        out.blob.extend_from_slice(rec);
        out.spans.push((id, at, out.blob.len() - at));
    });
    BUCKET_NANOS.fetch_add(
        t0.elapsed().as_nanos() as u64,
        std::sync::atomic::Ordering::Relaxed,
    );
    match failed {
        Some(e) => Err(e),
        None => Ok(out),
    }
}

/// Build the archive, and a per-zoom report.
///
/// **The in-memory oracle**, not the path for anything at scale. Per zoom this holds a
/// clipped copy of every geometry for every tile it reaches, so peak memory tracks the
/// zoom's output volume; [`build_archive_to`] is the streaming twin, produces the same
/// bytes, and is what the `tile_*` binaries use. Keeping the clipped copies is what buys
/// the quadtree descent — see [`crate::spill`]'s module docs for that trade.
pub fn build_archive(features: &[Feature], opts: &Options) -> Result<(Vec<u8>, Vec<ZoomStats>)> {
    if opts.min_zoom > opts.max_zoom {
        return err("minzoom is above maxzoom");
    }
    if opts.extent == 0 {
        return err("extent 0");
    }

    let geom_type = dominant_geom_type(features);
    let buffer = geom::buffer_for(opts.extent);
    let mut builder = Builder::new();
    builder.min_zoom = opts.min_zoom;
    builder.max_zoom = opts.max_zoom;
    builder.center_zoom = opts.min_zoom;
    builder.metadata = metadata(&opts.layer, opts.min_zoom, opts.max_zoom).into_bytes();
    if let Some(b) = lonlat_bounds(features) {
        builder.min_lon_e7 = e7(b.min_x);
        builder.min_lat_e7 = e7(b.min_y);
        builder.max_lon_e7 = e7(b.max_x);
        builder.max_lat_e7 = e7(b.max_y);
        builder.center_lon_e7 = e7((b.min_x + b.max_x) / 2.0);
        builder.center_lat_e7 = e7((b.min_y + b.max_y) / 2.0);
    }

    let mut report = Vec::new();
    for z in opts.min_zoom..=opts.max_zoom {
        let mut stats = ZoomStats { zoom: z, ..Default::default() };
        let tolerance = simplify::tolerance_for(z, opts.max_zoom, opts.simplification);

        // Project, annotate, descend, clip, thin and move into the tile -- per feature,
        // across the pool. `collect` into a Vec keeps the outer order the input's, which
        // is what makes the drop policy's tie-break deterministic below.
        //
        // Step for step what `bucket_feature` does, on purpose: the byte-identity tests
        // compare the two producers, so a step added to one and not the other is a test
        // failure rather than a silent divergence. Projection and annotation are once
        // per feature per zoom because projection is the expensive part and because
        // significance has to be measured on the UNCLIPPED ring; the filter is per tile
        // and AFTER the clip, which is this crate's order and not `tiler.rs`'s.
        let placed: Vec<Vec<(u64, i64, IntGeometry)>> = par::install(|| {
            features
                .par_iter()
                .map(|f| {
                    let mut p = geom::project_geometry(&f.geometry, z, opts.extent);
                    simplify::annotate(&mut p);
                    let mut out = Vec::new();
                    subdivide::subdivide(&p, z, opts.extent, buffer, &mut |tx, ty, clipped| {
                        let filtered = simplify::filter(clipped, tolerance);
                        let local = geom::to_tile(&filtered, tx, ty, opts.extent);
                        // Anything that vanishes here never reached the tile in the
                        // first place, so it is not a "drop".
                        if local.is_empty() {
                            return;
                        }
                        out.push((pmtiles::tile_id(z, tx, ty), extent_of(&local), local));
                    });
                    out
                })
                .collect()
        });

        // tile -> its candidates, in ascending feature index. Borrowed from `placed`
        // rather than moved out of it: a tile's geometry is read once by `fit_tile` and
        // a copy per candidate would be the largest allocation in the tiler.
        let mut by_tile: std::collections::HashMap<u64, Vec<(u64, i64, &IntGeometry)>> =
            std::collections::HashMap::new();
        for (i, one) in placed.iter().enumerate() {
            for (id, extent, local) in one {
                by_tile.entry(*id).or_default().push((i as u64, *extent, local));
            }
        }

        // Sorted on the id itself, so this is `tile_id` order by construction. Sorting
        // on `(tx, ty)` would not be: at z1 the lexicographic walk gives ids 1, 2, 4, 3,
        // and `pmtiles::StreamBuilder` hard-errors on a non-ascending id.
        let mut tiles: Vec<u64> = by_tile.keys().copied().collect();
        tiles.sort_unstable();
        let mut bar = Progress::new(format!("{} z{z}", opts.layer), tiles.len(), TILES, opts.progress);
        // Across tiles, not within one: a tile holds a handful of features, so mapping
        // its encode over the pool costs more in scheduling than the encoding is worth.
        // Tiles are independent and there are many, which is the seam. The fold below is
        // sequential and in `tile_id` order, so `stats` and the archive are exactly what
        // the serial version produced.
        for chunk in tiles.chunks(par::batch_len()) {
            let done: Vec<EncodedTile> = par::install(|| {
                chunk
                    .par_iter()
                    .with_min_len(par::min_task_len(chunk.len()))
                    .map_init(crate::gz::Compressor::new, |gz, &id| {
                        let mut candidates: Vec<TileCandidate> = by_tile[&id]
                            .iter()
                            .map(|&(seq, extent, geom)| TileCandidate {
                                seq,
                                geom,
                                props: &features[seq as usize].props,
                                extent,
                            })
                            .collect();
                        candidates.sort_by(by_importance);

                        let (body, kept, over) =
                            fit_tile(&candidates, &opts.layer, geom_type, opts, gz)?;
                        Ok(EncodedTile {
                            id,
                            body,
                            kept,
                            over_budget: over,
                            placed: candidates.len(),
                        })
                    })
                    .collect::<Result<Vec<_>>>()
            })?;

            for t in done {
                bar.tick(TILES);
                stats.placed += t.placed;
                stats.kept += t.kept;
                stats.dropped += t.placed - t.kept;
                if t.over_budget {
                    stats.over_budget += 1;
                }
                stats.largest_tile_bytes = stats.largest_tile_bytes.max(t.body.len());
                stats.tiles += 1;
                builder.add_tile_raw(t.id, t.body);
            }
        }
        bar.finish(TILES);
        report.push(stats);
    }

    Ok((builder.build()?, report))
}

/// Where the encode pass's time actually goes, accumulated across workers.
///
/// Nanoseconds, summed over every `fit_tile` probe. Only read under `--timing`, and
/// the two `Instant::now()` calls are per PROBE (a handful per tile), not per feature,
/// so leaving them in costs nothing measurable.
///
/// These exist because four rounds of plausible-sounding micro-optimisation to this
/// function -- a galloping search, hoisting the geometry encode, borrowing the value
/// dictionary key, boxing the compressor -- bought 3% between them. Splitting the
/// measurement is what identified the real cost.
static MVT_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
static GZIP_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
/// CPU spent inside [`bucket_feature`], summed over workers. Compared against the
/// bucket pass's WALL time, this says whether that pass is bound by its parallel
/// geometry work or by the serial stream read and spill write around it.
static BUCKET_NANOS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

include!("pyramid_part1.rs");
include!("pyramid_part2.rs");
include!("pyramid_part3.rs");
include!("pyramid_part4.rs");
include!("pyramid_part5.rs");
include!("pyramid_part6.rs");
include!("pyramid_part7.rs");