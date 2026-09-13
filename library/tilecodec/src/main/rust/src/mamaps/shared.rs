//! The v8 shared section: deduplicated attributes across tiles (`MBSH`).
//!
//! INTEGRATOR (mod.rs registration — one line, applied separately; this file is
//! create-only and edits nothing itself):
//! ```rust,ignore
//! pub mod shared;
//! ```
//!
//! # Why a shared section exists
//!
//! Per-tile bodies repeat the same long-lived attributes on every tile that touches
//! a feature: a building's S3DB extrusion, a road's carriageway split, a lane's
//! turn arrows, a POI's name. The shared section interns each distinct value once
//! and leaves per-tile bodies holding [`SharedSlimRef`]s (8 B: `logical_id` +
//! `view_bits`). Lanes C/D/E stub off the struct names below and own the per-tile
//! wiring; this file owns only the shared pools and their encoding.
//!
//! # Keys: what identifies a logical row
//!
//! `logical_id`s are **sequential build-local ids** (1, 2, 3, … in first-sighting
//! order), assigned by [`SharedBuilder::intern_row`]. Everything the archive holds
//! lives in one file, so first-sighting order *is* the id — no content-derived
//! reproducibility is needed, and no fold can collide. The drain guard that used
//! to fail the build on a key collision is gone with the fold that caused it:
//! two sightings of the same content key return the same id (one row, many slim
//! refs), and different content is a different row, so a misjoin is
//! unrepresentable rather than checked.
//!
//! The content key ([`SharedRowKey`]) is `(layer, stable_id, geom_hash)` plus the
//! full row content:
//! * **Traffic** keys by **`component_id`** (validated stable at z12/z13). One
//!   `GEOM_LINE` feature per drivable component segment; the full `u64` rides the
//!   key and the id-runs pool, so low16-aliasing edges are distinct rows by
//!   construction.
//! * **Junctions** have **no stable id** (confirmed) — a lane connector is a
//!   sampled centreline, not an OSM element — so the key carries the
//!   [`junction_key`] geometry hash alongside `ID_NONE`. Tile-local clips hash
//!   differently, exactly as before: no cross-tile junction dedup is claimed.
//! * **Roads/buildings** key by OSM way id when stage A plumbs one; `ID_NONE`
//!   rows are skipped, never hashed.
//!
//! # Layout
//!
//! ```text
//! [SharedHeader 32][PoolDir × pool_count × 24][pools...]
//! ```
//!
//! Order on disk is **not** part of the format. Every pool is located only by its
//! [`SharedPoolDirEntry`] offset/length, the same rule the top-level header
//! follows: a reader that assumed layout would address the wrong bytes once a
//! writer reorders pools.
//!
//! Pools and their [`SharedKind`] ids:
//!
//! | kind | pool | encoding |
//! |---|---|---|
//! | 1 | string pool | `u32` count + `count` × `u32` offsets + blobs (`uvarint` len + UTF-8) |
//! | 2 | logical rows | raw [`SharedLogicalRow`] 32 B records, `row_count` of them |
//! | 3 | building attrs | `u32` count + 20 B [`SharedBuildingAttrs`] records |
//! | 4 | carriageways | `u32` count + 6 B [`SharedCarriageway`] records |
//! | 5 | lane turns | `u32` count + variable [`SharedLaneTurns`] entries (whole-record interned) |
//! | 6 | id runs | varint stream: sorted delta + zigzag + RLE [`ID_NONE`](super::body::ID_NONE) |
//! | 7 | slim refs | raw [`SharedSlimRef`] 8 B records |
//! | 8 | geometry | canonical verts (zigzag varint deltas, tile-arena encoding) + per-(row, zoom) keep-masks (bitvec RLE) |
//!
//! All multi-byte integers are little-endian. Every pool's length is padded with
//! zeroes to a 4-byte boundary so the next pool starts aligned.

mod builder;
mod consts;
mod geom;
mod keys;
mod pools;
mod records;
mod view;

#[cfg(test)]
mod tests;

#[cfg(feature = "write")]
pub use builder::SharedBuilder;
pub use consts::{
    SHARED_ATTR_DEFAULT, SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN, SHARED_FLAG_DETAIL_NUMERIC,
    SHARED_HEADER_LEN, SHARED_KIND_BUILDINGS, SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_GEOMETRY,
    SHARED_KIND_ID_RUNS, SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_SLIM_REFS,
    SHARED_KIND_STRINGS, SHARED_MAGIC, SHARED_NAME_NONE, SHARED_POOL_ENTRY_LEN, SHARED_ROW_LEN,
    SHARED_SLIM_REF_LEN, SHARED_VERSION,
};
pub use geom::{
    SHARED_GEOM_NONE, SharedCanonicalGeom, SharedGeometryPool, SharedKeepMask, canonical_geom_hash,
    decode_canonical_verts, decode_keep_runs, encode_canonical_verts, encode_keep_runs,
};
pub use keys::{SharedRowKey, decode_id_runs, encode_id_runs, junction_key};
pub use pools::SharedStringPool;
pub use records::{
    SharedBuildingAttrs, SharedCarriageway, SharedHeader, SharedLaneTurns, SharedLogicalRow,
    SharedPoolDirEntry, SharedSlimRef,
};
pub use view::SharedView;
