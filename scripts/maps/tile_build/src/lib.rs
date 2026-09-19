//! MVT + PMTiles v3 tile-build tools for the Maps basemap.
//!
//! Replaces `tippecanoe` and `tile-join`. Neither has a Windows path, which would
//! otherwise leave the `transit_stops` layer unbuildable on the dev box, so the
//! whole tile chain is now cargo-only.
//!
//! Built in layers, each usable on its own:
//!
//!   * [`geojson`] — the GeoJSONSeq reader the tilers share. Hand-rolled, not
//!     serde: `miniz_oxide` being the only dependency is what lets this crate
//!     build offline.
//!   * [`geom`] — projection, bounds, tile ranges and quantisation: lon/lat in,
//!     integer tile coordinates out.
//!   * [`clip`] — Liang-Barsky for lines, Sutherland-Hodgman for polygons, against
//!     a tile's buffered rect.
//!   * [`simplify`] — Douglas-Peucker once per feature to score every vertex, then
//!     a per-zoom threshold on those scores.
//!   * [`subdivide`] — tile one feature by walking the tile quadtree, so a
//!     continent-spanning feature costs one vertex pass per zoom level rather than
//!     one per tile it touches.
//!   * [`pyramid`] — the tile pyramid driver and the drop policy.
//!   * [`par`] — the thread budget (`--threads`, `MAPS_THREADS`) and the pool the
//!     tilers run on. Parallelism never changes an output byte.
//!   * [`spill`] — the on-disk record format and `tile_id`-range buckets the streaming
//!     tiler partitions through, so peak memory tracks the tile COUNT rather than the
//!     input bytes.
//!
//! [`geom`], [`clip`] and [`simplify`] compose in one fixed order; [`geom`]'s module
//! docs give the pipeline.
//!
//! **Output is not byte-identical to tippecanoe, by design.** Its
//! `--drop-densest-as-needed` is a lossy per-tile heuristic and
//! `--extend-zooms-if-still-dropping` can push an archive past its own maximum
//! zoom; we implement a deterministic policy and a fixed max zoom instead. Tests
//! assert our own invariants — ring closure, winding order, Douglas-Peucker
//! monotonicity, a PMTiles round trip — never equality with tippecanoe. See
//! [`pyramid`] for the policy and its consequences.

pub mod boolean;
pub mod anon;
pub mod clip;
pub mod geojson;
pub mod geojson_extra;
pub mod geom;
pub mod par;
pub mod progress;
pub mod pyramid;
pub mod simplify;
pub mod spill;
pub mod subdivide;

// The formats themselves live in `tilecodec`, shared with the Android renderer in
// `:library:map` — it has to read exactly what this crate writes, and one codec is
// one thing to keep in agreement with the published archive.
//
// Re-exported at the crate root rather than referenced as `tilecodec::mvt`, so
// every `crate::mvt` / `crate::pmtiles` / `crate::gz` / `crate::proto` path in
// `pyramid`, `spill` and `geom` keeps resolving unchanged. The extraction
// is meant to be invisible to the tiler.
pub use tilecodec::{gz, mamaps, mvt, pmtiles, proto};
