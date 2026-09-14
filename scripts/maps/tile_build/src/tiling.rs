//! Tile a point layer, and merge tilesets — the `tippecanoe` and `tile-join`
//! replacements.
//!
//! Points need no clipping, simplification or winding-order handling, so this
//! module stays as simple as a point layer allows. The geometry core that lines and
//! polygons need lives in [`crate::geom`], [`crate::clip`] and
//! [`crate::simplify`]; [`merge_tiles`] still never looks inside a geometry stream,
//! which is what makes a composite lossless.

use crate::geom::project;
use crate::mvt::{self, GeomType, Tile, Value, DEFAULT_EXTENT};
use crate::par;
use crate::pmtiles::{self, Archive, ArchiveFile, Builder};
use crate::progress::Progress;
use rayon::prelude::*;
use std::path::{Path, PathBuf};
use crate::proto::Result;
use std::collections::HashMap;

/// One point to be tiled.
#[derive(Debug, Clone)]
pub struct Point {
    pub lon: f64,
    pub lat: f64,
    pub props: Vec<(String, Value)>,
}

/// One tile's points as `(x, y, source index)`. The index is carried so the feature
/// order can be made total, which is what makes a rebuild byte-identical.
type PointBucket = Vec<(i32, i32, usize)>;

/// Bucket points into tiles for one zoom and encode each as an MVT.
///
/// Returns `(tile_id, mvt body)`. Points landing outside the tile grid are
/// dropped rather than clamped into the edge tile, which would pile every bad
/// coordinate onto one pin.
pub fn tile_points(
    layer_name: &str,
    points: &[Point],
    z: u8,
    extent: u32,
) -> Vec<(u64, Vec<u8>)> {
    let n = 1u64 << z;
    let mut by_tile: HashMap<(u64, u64), PointBucket> = HashMap::new();
    for (i, p) in points.iter().enumerate() {
        let (fx, fy) = project(p.lon, p.lat, z);
        if !fx.is_finite() || !fy.is_finite() {
            continue;
        }
        let (tx, ty) = (fx.floor(), fy.floor());
        if tx < 0.0 || ty < 0.0 || tx >= n as f64 || ty >= n as f64 {
            continue;
        }
        let (tx, ty) = (tx as u64, ty as u64);
        // Position within the tile, quantised to the extent grid.
        let px = ((fx - tx as f64) * extent as f64).round() as i32;
        let py = ((fy - ty as f64) * extent as f64).round() as i32;
        by_tile.entry((tx, ty)).or_default().push((
            px.clamp(0, extent as i32),
            py.clamp(0, extent as i32),
            i,
        ));
    }

    // Out of the map before encoding, so the encode is an indexed map the pool can
    // split. The map's own iteration order is arbitrary; the sort at the end is what
    // makes the result deterministic, exactly as before.
    let mut buckets: Vec<((u64, u64), PointBucket)> = by_tile.into_iter().collect();
    let mut out: Vec<(u64, Vec<u8>)> = par::install(|| {
        buckets
            .par_iter_mut()
            .map(|((tx, ty), pts)| {
                // Deterministic feature order, so a rebuild is byte-identical.
                pts.sort_by_key(|&(x, y, i)| (x, y, i));
                // Geometry first, so the borrowed views below have something to point
                // at; properties are never copied. See `mvt::FeatureRef`.
                let geoms: Vec<Vec<u32>> = pts
                    .iter()
                    .map(|&(x, y, _)| mvt::encode_points(&[(x, y)]))
                    .collect();
                let body = mvt::encode_tile_from(
                    layer_name,
                    extent,
                    pts.iter().zip(geoms.iter()).map(|(&(_, _, i), geometry)| {
                        mvt::FeatureRef {
                            id: None,
                            geom_type: GeomType::Point,
                            geometry,
                            props: &points[i].props,
                        }
                    }),
                );
                (pmtiles::tile_id(z, *tx, *ty), body)
            })
            .collect()
    });
    out.sort_by_key(|(id, _)| *id);
    out
}

/// Build a single-layer point archive across `min_zoom..=max_zoom`, silently.
pub fn build_point_archive(
    layer_name: &str,
    points: &[Point],
    min_zoom: u8,
    max_zoom: u8,
) -> Result<Vec<u8>> {
    build_point_archive_with(layer_name, points, min_zoom, max_zoom, false)
}

/// As [`build_point_archive`], with a per-zoom progress bar when `progress`.
///
/// The binaries pass true and the library defaults to false, so tests stay quiet.
/// Worth having for points and not only for lines: `ma_pois` is 22.6 M features at
/// planet scale and used to print nothing at all between its start and its report.
pub fn build_point_archive_with(
    layer_name: &str,
    points: &[Point],
    min_zoom: u8,
    max_zoom: u8,
    progress: bool,
) -> Result<Vec<u8>> {
    let mut b = Builder::new();
    b.min_zoom = min_zoom;
    b.max_zoom = max_zoom;
    b.center_zoom = min_zoom;
    b.metadata = point_metadata(layer_name, min_zoom, max_zoom).into_bytes();
    for z in min_zoom..=max_zoom {
        // `tile_points` buckets and MVT-encodes the whole zoom before returning, so
        // the bar covers the gzip loop -- which is where the time goes -- and the
        // bucketing shows as a pause before the bar appears.
        let tiles = tile_points(layer_name, points, z, DEFAULT_EXTENT);
        let mut bar = Progress::new(format!("{layer_name} z{z}"), tiles.len(), TILES, progress);
        // Level-9 DEFLATE is the cost here, and it is per-tile independent. A batch at
        // a time so live memory is a few compressed bodies per thread rather than a
        // second copy of the whole zoom, and the drain below stays in `tile_id` order.
        for chunk in tiles.chunks(par::batch_len()) {
            let packed: Vec<Vec<u8>> = par::install(|| {
                chunk
                    .par_iter()
                    .with_min_len(par::min_task_len(chunk.len()))
                    .map_init(crate::gz::Compressor::new, |gz, (_, body)| gz.compress(body))
                    .collect()
            });
            for ((id, _), gz) in chunk.iter().zip(packed) {
                bar.tick(TILES);
                b.add_tile_raw(*id, gz);
            }
        }
        bar.finish(TILES);
    }
    b.build()
}

/// Points are bucketed before they are counted, so unlike the pyramid's candidates
/// every one of these really is written.
const TILES: &str = "tile(s)";

/// The `json` metadata blob a PMTiles archive carries. MapLibre does not need it
/// to render a styled layer, but `pmtiles show` and friends read it, so emitting a
/// truthful vector_layers list keeps the archive introspectable.
fn point_metadata(layer_name: &str, min_zoom: u8, max_zoom: u8) -> String {
    format!(
        "{{\"vector_layers\":[{{\"id\":\"{layer_name}\",\"minzoom\":{min_zoom},\
         \"maxzoom\":{max_zoom}}}]}}"
    )
}

/// A merge input: its header, its metadata, its tile entries and its bodies.
///
/// Implemented for both `&`[`Archive`] and [`ArchiveFile`], so one merge works whether
/// the inputs are resident or on disk. The entry walk takes `&mut self` because a file
/// reader reuses one leaf buffer; `body_into` takes `&self` so the merge can pull
/// bodies for many tiles at once.
pub trait TileSource: Sync {
    fn header(&self) -> &pmtiles::Header;
    fn metadata(&self) -> &[u8];
    /// Every tile entry, ascending by `tile_id`, runs unexpanded.
    fn visit_entries(
        &mut self,
        visit: &mut dyn FnMut(&pmtiles::Entry) -> Result<()>,
    ) -> Result<()>;
    /// The still-compressed body at `offset..offset + length`, into a reused buffer.
    fn body_into(&self, offset: u64, length: u32, out: &mut Vec<u8>) -> Result<()>;
}

impl TileSource for &Archive {
    fn header(&self) -> &pmtiles::Header {
        &self.header
    }

    fn metadata(&self) -> &[u8] {
        &self.metadata
    }

    fn visit_entries(
        &mut self,
        visit: &mut dyn FnMut(&pmtiles::Entry) -> Result<()>,
    ) -> Result<()> {
        Archive::visit_entries(self, visit)
    }

    fn body_into(&self, offset: u64, length: u32, out: &mut Vec<u8>) -> Result<()> {
        let body = Archive::body_at(self, offset, length)?;
        out.clear();
        out.extend_from_slice(body);
        Ok(())
    }
}

impl TileSource for ArchiveFile {
    fn header(&self) -> &pmtiles::Header {
        &self.header
    }

    fn metadata(&self) -> &[u8] {
        &self.metadata
    }

    fn visit_entries(
        &mut self,
        visit: &mut dyn FnMut(&pmtiles::Entry) -> Result<()>,
    ) -> Result<()> {
        ArchiveFile::visit_entries(self, visit)
    }

    fn body_into(&self, offset: u64, length: u32, out: &mut Vec<u8>) -> Result<()> {
        ArchiveFile::body_into(self, offset, length, out)
    }
}

/// Merge several archives straight to `out`, holding no archive in memory.
///
/// The in-memory [`merge_archives`] is fine for a metro extract and cannot do a planet
/// overlay join: it copies every input tile into a `HashMap`, then [`Builder`] keeps
/// three more copies of every body. Measured on the real 7-layer overlay merge — 36.8 M
/// tiles, 17 GB of inputs — that is roughly 76 GB and gets killed.
///
/// This keeps only a `(tile_id, input, offset, length)` row per input tile — 24 bytes —
/// and one tile body at a time, so peak memory is set by the tile COUNT: about 1.5 GB
/// for that same merge, versus 76 GB.
///
/// Whether the INPUTS are resident is the caller's choice, through [`TileSource`]. Pass
/// [`ArchiveFile`]s and nothing but their root directories is held, which is what makes
/// a planet-sized input joinable at all; pass `&`[`Archive`]s and it behaves as before.
pub fn merge_archives_to<S: TileSource>(
    inputs: &mut [S],
    out: impl AsRef<Path>,
    scratch: impl Into<PathBuf>,
    progress: bool,
) -> Result<()> {
    let mut min_zoom = u8::MAX;
    let mut max_zoom = 0u8;
    let mut bounds: Option<(i32, i32, i32, i32)> = None;
    for a in inputs.iter() {
        let h = a.header();
        min_zoom = min_zoom.min(h.min_zoom);
        max_zoom = max_zoom.max(h.max_zoom);
        bounds = Some(match bounds {
            None => (h.min_lon_e7, h.min_lat_e7, h.max_lon_e7, h.max_lat_e7),
            Some((w, s, e, n)) => (
                w.min(h.min_lon_e7),
                s.min(h.min_lat_e7),
                e.max(h.max_lon_e7),
                n.max(h.max_lat_e7),
            ),
        });
    }
    // Folded before the entry walk takes the mutable borrows.
    let metadata = if inputs.is_empty() {
        None
    } else {
        let metas: Vec<&[u8]> = inputs.iter().map(|a| a.metadata()).collect();
        Some(merge_metadata(&metas))
    };

    // One row per input tile, sorted by (tile_id, input index) so a tile's sources come
    // out in input order -- which is what makes a later input win a layer collision.
    //
    // The offset is u64. A planet layer's data section is well past u32::MAX, so a
    // narrower field wraps every body beyond 4 GiB onto the wrong bytes.
    let mut rows: Vec<(u64, u32, u64, u32)> = Vec::new();
    for (i, a) in inputs.iter_mut().enumerate() {
        // Pushed straight from the walk: materialising each input's own offset list
        // first would hold a second copy of the rows for no gain.
        a.visit_entries(&mut |e| {
            for k in 0..e.run_length as u64 {
                rows.push((e.tile_id + k, i as u32, e.offset, e.length));
            }
            Ok(())
        })?;
    }
    rows.sort_unstable_by_key(|(id, i, _, _)| (*id, *i));

    let mut b = pmtiles::StreamBuilder::new(scratch)?;
    b.min_zoom = if min_zoom == u8::MAX { 0 } else { min_zoom };
    b.max_zoom = max_zoom;
    b.center_zoom = b.min_zoom;
    if let Some((w, s, e, n)) = bounds {
        b.min_lon_e7 = w;
        b.min_lat_e7 = s;
        b.max_lon_e7 = e;
        b.max_lat_e7 = n;
        b.center_lon_e7 = midpoint_e7(w, e);
        b.center_lat_e7 = midpoint_e7(s, n);
    }
    if let Some(m) = metadata {
        b.metadata = m;
    }

    let mut bar = Progress::new("merging".to_string(), rows.len(), TILES, progress);
    // The two counters that say whether threading this function pays. A tile only one
    // input holds is a byte copy; only the overlapping groups do real work. Measured on
    // a two-layer overlay join of 323k tiles: 30% copied, 70% re-encoded — so the
    // deflate on those 70% dominates, and it threads.
    let mut copied = 0usize;
    let mut merged_groups = 0usize;

    // Runs of equal `tile_id`. Each is one output tile and they are independent: bodies
    // are read at explicit offsets (`TileSource::body_into` takes `&self`), so several
    // workers can pull from the same archive at once.
    let mut groups: Vec<(usize, usize)> = Vec::new();
    let mut k = 0usize;
    while k < rows.len() {
        let id = rows[k].0;
        let mut j = k;
        while j < rows.len() && rows[j].0 == id {
            j += 1;
        }
        groups.push((k, j));
        k = j;
    }

    // Shared for the parallel phase now the mutable entry walk above is done.
    let inputs: &[S] = inputs;
    // What one group produced, plus which path produced it.
    struct Joined {
        id: u64,
        body: Vec<u8>,
        was_copied: bool,
        rows: usize,
    }

    let join_group = |scratch: &mut (Vec<u8>, crate::gz::Compressor),
                      &(k, j): &(usize, usize)|
     -> Result<Joined> {
        let (body, gz) = scratch;
        let id = rows[k].0;

        if let [(_, i, off, len)] = rows[k..j] {
            let src = &inputs[i as usize];
            if src.header().tile_compression == pmtiles::COMPRESSION_GZIP {
                // Sole owner, already gzip: the producer's bytes go straight out.
                src.body_into(off, len, body)?;
                return Ok(Joined {
                    id,
                    body: body.clone(),
                    was_copied: true,
                    rows: j - k,
                });
            }
        }
        let mut decoded: Vec<Vec<u8>> = Vec::with_capacity(j - k);
        for &(_, i, off, len) in &rows[k..j] {
            let src = &inputs[i as usize];
            src.body_into(off, len, body)?;
            decoded.push(match src.header().tile_compression {
                pmtiles::COMPRESSION_NONE => body.clone(),
                _ => crate::gz::decompress(body)?,
            });
        }
        let merged = if decoded.len() == 1 {
            decoded.pop().unwrap_or_default()
        } else {
            merge_tiles(&decoded)?
        };
        Ok(Joined {
            id,
            body: gz.compress(&merged),
            was_copied: false,
            rows: j - k,
        })
    };

    // A batch at a time, so live memory is a bounded number of output bodies rather
    // than the whole join. The fold is sequential and in ascending group order, which
    // is what keeps `add_tile_raw` fed ascending ids.
    for chunk in groups.chunks(par::batch_len()) {
        let done: Vec<Joined> = par::install(|| {
            chunk
                .par_iter()
                .with_min_len(par::min_task_len(chunk.len()))
                .map_init(
                    || (Vec::new(), crate::gz::Compressor::new()),
                    join_group,
                )
                .collect::<Result<Vec<_>>>()
        })?;
        for t in done {
            for _ in 0..t.rows {
                bar.tick(TILES);
            }
            if t.was_copied {
                copied += 1;
            } else {
                merged_groups += 1;
            }
            b.add_tile_raw(t.id, &t.body)?;
        }
    }
    bar.finish(TILES);
    if progress {
        let total = copied + merged_groups;
        println!(
            "merged {total} tile(s): {copied} copied through ({:.1}%), \
             {merged_groups} re-encoded",
            copied as f64 / total.max(1) as f64 * 100.0
        );
    }
    b.finish(out)
}

include!("tiling_part1.rs");
#[cfg(test)]
mod tests {
    include!("tiling_part2.rs");
    include!("tiling_part3.rs");
}
