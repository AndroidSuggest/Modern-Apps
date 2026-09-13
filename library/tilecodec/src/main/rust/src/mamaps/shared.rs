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
//!
//! All multi-byte integers are little-endian. Every pool's length is padded with
//! zeroes to a 4-byte boundary so the next pool starts aligned.

use crate::proto::{err, Result};
use super::body::ID_NONE;

/// Magic bytes opening every shared section.
pub const SHARED_MAGIC: &[u8; 4] = b"MBSH";
/// The only shared-section version this reader speaks.
pub const SHARED_VERSION: u8 = 1;
/// `SharedHeader` wire length. Fixed so a reader can slice it out of a prefix.
pub const SHARED_HEADER_LEN: usize = 32;
/// One pool directory entry's wire length.
pub const SHARED_POOL_ENTRY_LEN: usize = 24;
/// One logical row's wire length.
pub const SHARED_ROW_LEN: usize = 32;
/// One slim reference's wire length.
pub const SHARED_SLIM_REF_LEN: usize = 8;
/// One shared building-attribute record's wire length (matches `body`'s 20 B).
pub const SHARED_BUILDING_LEN: usize = 20;
/// One shared carriageway record's wire length (matches `body`'s 6 B).
pub const SHARED_CARRIAGEWAY_LEN: usize = 6;

/// Pool-table selectors carried in [`SharedPoolDirEntry::kind`].
pub const SHARED_KIND_STRINGS: u8 = 1;
pub const SHARED_KIND_ROWS: u8 = 2;
pub const SHARED_KIND_BUILDINGS: u8 = 3;
pub const SHARED_KIND_CARRIAGEWAYS: u8 = 4;
pub const SHARED_KIND_LANE_TURNS: u8 = 5;
pub const SHARED_KIND_ID_RUNS: u8 = 6;
pub const SHARED_KIND_SLIM_REFS: u8 = 7;

/// `name_ref` (and any string reference) for "no name".
///
/// Index 0 is reserved for the same reason `body::NAME_NONE` is: most rows are
/// unnamed, and the common value should cost zero entropy. Valid references are
/// 1-based (`names[ref - 1]`), mirroring [`Body::name`](super::body::Body::name).
pub const SHARED_NAME_NONE: u32 = 0;

/// Attribute-pool index of the default/empty value.
///
/// Every attribute pool seeds index 0 with its default (`BuildingAttrs::default`,
/// a zero carriageway, empty lane turns) so "no data" is a zero index rather
/// than an absent entry. The builder returns 0 for default values without
/// storing a duplicate; the parser accepts 0 unconditionally and bounds-checks
/// anything above it.
pub const SHARED_ATTR_DEFAULT: u32 = 0;

/// Row flag: `kind_detail` is a number, not an interned id.
///
/// Mirrors `body::FLAG_DETAIL_NUMERIC` so a shared row and its per-tile feature
/// cannot disagree about what the field means.
pub const SHARED_FLAG_DETAIL_NUMERIC: u32 = 1 << 0;

const KNOWN_SHARED_FLAGS: u8 = 0;
const KNOWN_ROW_FLAGS: u32 = SHARED_FLAG_DETAIL_NUMERIC;

/// What a reader must know before it can address any pool.
///
/// Byte map, all little-endian: `0..4` magic `MBSH`, `4` version, `5` flags,
/// `6..8` header_len (= 32), `8..12` row_count, `12..16` string_count,
/// `16..20` pool_count, `20..24` total_len (whole shared section including this
/// header, the directory and every pool), `24..28` id_run_count (decoded ids,
/// Nones included, parallel to rows when the id pool is present), `28..32`
/// reserved zero.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SharedHeader {
    pub flags: u8,
    pub row_count: u32,
    pub string_count: u32,
    pub pool_count: u32,
    pub total_len: u32,
    pub id_run_count: u32,
}

impl SharedHeader {
    pub fn parse(buf: &[u8]) -> Result<SharedHeader> {
        if buf.len() < SHARED_HEADER_LEN {
            return err(format!(
                "a shared section header is {SHARED_HEADER_LEN} bytes, got {}",
                buf.len()
            ));
        }
        if &buf[0..4] != SHARED_MAGIC {
            return err("not a shared section (bad MBSH magic)");
        }
        if buf[4] != SHARED_VERSION {
            return err(format!(
                "unsupported shared section version {} (this reader speaks v{})",
                buf[4], SHARED_VERSION
            ));
        }
        if buf[5] & !KNOWN_SHARED_FLAGS != 0 {
            return err(format!("a shared section sets unknown flags {:#04x}", buf[5]));
        }
        if u16::from_le_bytes([buf[6], buf[7]]) as usize != SHARED_HEADER_LEN {
            return err("a shared section declares the wrong header length");
        }
        if u32::from_le_bytes([buf[28], buf[29], buf[30], buf[31]]) != 0 {
            return err("a shared section has a non-zero reserved word");
        }
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let header = SharedHeader {
            flags: buf[5],
            row_count: u32_at(8),
            string_count: u32_at(12),
            pool_count: u32_at(16),
            total_len: u32_at(20),
            id_run_count: u32_at(24),
        };
        header.check()?;
        Ok(header)
    }

    fn check(&self) -> Result<()> {
        if self.total_len < (SHARED_HEADER_LEN as u32)
            .checked_add(self.pool_count.checked_mul(SHARED_POOL_ENTRY_LEN as u32).ok_or_else(
                || crate::proto::Error("a shared section's directory overflows".to_string()),
            )?)
            .ok_or_else(|| crate::proto::Error("a shared section's header overflows".to_string()))?
        {
            return err("a shared section's total length does not cover its own directory");
        }
        if self.total_len % 4 != 0 {
            return err("a shared section's total length is not 4-byte aligned");
        }
        Ok(())
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(SHARED_HEADER_LEN);
        out.extend_from_slice(SHARED_MAGIC);
        out.push(SHARED_VERSION);
        out.push(self.flags);
        out.extend_from_slice(&(SHARED_HEADER_LEN as u16).to_le_bytes());
        out.extend_from_slice(&self.row_count.to_le_bytes());
        out.extend_from_slice(&self.string_count.to_le_bytes());
        out.extend_from_slice(&self.pool_count.to_le_bytes());
        out.extend_from_slice(&self.total_len.to_le_bytes());
        out.extend_from_slice(&self.id_run_count.to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        debug_assert_eq!(out.len(), SHARED_HEADER_LEN);
        out
    }
}

/// One pool directory entry: where a pool lives and what stride it has.
///
/// Byte map, all little-endian: `0` kind (one of `SHARED_KIND_*`), `1..4`
/// reserved zero, `4..8` elem_len (fixed record stride, or 0 for variable-length
/// pools: strings, lane turns, id runs), `8..16` offset (from the shared
/// section's first byte), `16..24` length (including padding).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SharedPoolDirEntry {
    pub kind: u8,
    pub elem_len: u32,
    pub offset: u64,
    pub len: u64,
}

impl SharedPoolDirEntry {
    pub fn parse(buf: &[u8]) -> Result<SharedPoolDirEntry> {
        if buf.len() < SHARED_POOL_ENTRY_LEN {
            return err("a shared pool entry runs past its directory");
        }
        if buf[1] != 0 || buf[2] != 0 || buf[3] != 0 {
            return err("a shared pool entry has non-zero reserved bytes");
        }
        let kind = buf[0];
        if !matches!(
            kind,
            SHARED_KIND_STRINGS
                | SHARED_KIND_ROWS
                | SHARED_KIND_BUILDINGS
                | SHARED_KIND_CARRIAGEWAYS
                | SHARED_KIND_LANE_TURNS
                | SHARED_KIND_ID_RUNS
                | SHARED_KIND_SLIM_REFS
        ) {
            return err(format!("a shared pool entry names unknown pool kind {kind}"));
        }
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6],
                buf[o + 7],
            ])
        };
        Ok(SharedPoolDirEntry { kind, elem_len: u32_at(4), offset: u64_at(8), len: u64_at(16) })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; SHARED_POOL_ENTRY_LEN];
        out[0] = self.kind;
        out[4..8].copy_from_slice(&self.elem_len.to_le_bytes());
        out[8..16].copy_from_slice(&self.offset.to_le_bytes());
        out[16..24].copy_from_slice(&self.len.to_le_bytes());
        out
    }
}

/// One logical (deduplicated) feature row: the join target slim refs point at.
///
/// Byte map, all little-endian: `0..4` logical_id, `4..8` name_ref (0 = none,
/// else 1-based into the string pool), `8..10` kind, `10..12` kind_detail,
/// `12..16` view_bits (per-tile visibility mask, opaque here), `16..20`
/// building_idx, `20..24` carriageway_idx, `24..28` lane_turns_idx (each 0 =
/// default/empty), `28..32` flags (only `SHARED_FLAG_DETAIL_NUMERIC` defined).
///
/// Rows are sorted ascending by `logical_id` and distinct, so lookup is a binary
/// search and a corrupt section cannot present two rows with one id.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SharedLogicalRow {
    pub logical_id: u32,
    pub name_ref: u32,
    pub kind: u16,
    pub kind_detail: u16,
    pub view_bits: u32,
    pub building_idx: u32,
    pub carriageway_idx: u32,
    pub lane_turns_idx: u32,
    pub flags: u32,
}

impl SharedLogicalRow {
    pub fn parse(buf: &[u8]) -> Result<SharedLogicalRow> {
        if buf.len() < SHARED_ROW_LEN {
            return err("a shared logical row runs past its pool");
        }
        let u16_at = |o: usize| u16::from_le_bytes([buf[o], buf[o + 1]]);
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let flags = u32_at(28);
        if flags & !KNOWN_ROW_FLAGS != 0 {
            return err("a shared logical row sets unknown flags");
        }
        Ok(SharedLogicalRow {
            logical_id: u32_at(0),
            name_ref: u32_at(4),
            kind: u16_at(8),
            kind_detail: u16_at(10),
            view_bits: u32_at(12),
            building_idx: u32_at(16),
            carriageway_idx: u32_at(20),
            lane_turns_idx: u32_at(24),
            flags,
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; SHARED_ROW_LEN];
        out[0..4].copy_from_slice(&self.logical_id.to_le_bytes());
        out[4..8].copy_from_slice(&self.name_ref.to_le_bytes());
        out[8..10].copy_from_slice(&self.kind.to_le_bytes());
        out[10..12].copy_from_slice(&self.kind_detail.to_le_bytes());
        out[12..16].copy_from_slice(&self.view_bits.to_le_bytes());
        out[16..20].copy_from_slice(&self.building_idx.to_le_bytes());
        out[20..24].copy_from_slice(&self.carriageway_idx.to_le_bytes());
        out[24..28].copy_from_slice(&self.lane_turns_idx.to_le_bytes());
        out[28..32].copy_from_slice(&self.flags.to_le_bytes());
        out
    }

    /// The numeric `kind_detail`, or `None` when the field is an interned id.
    pub fn detail_number(&self) -> Option<u16> {
        (self.flags & SHARED_FLAG_DETAIL_NUMERIC != 0).then_some(self.kind_detail)
    }
}

/// One per-tile slim reference into the shared rows: 8 B on the wire.
///
/// `logical_id` joins to [`SharedLogicalRow::logical_id`]; `view_bits` is the
/// tile-local visibility mask (a tile may hide a shared row without copying it).
/// Lanes C/D/E own how these ride in a body; this file owns only the record.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SharedSlimRef {
    pub logical_id: u32,
    pub view_bits: u32,
}

impl SharedSlimRef {
    pub fn parse(buf: &[u8]) -> Result<SharedSlimRef> {
        if buf.len() < SHARED_SLIM_REF_LEN {
            return err("a shared slim ref runs past its pool");
        }
        Ok(SharedSlimRef {
            logical_id: u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]),
            view_bits: u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; SHARED_SLIM_REF_LEN];
        out[0..4].copy_from_slice(&self.logical_id.to_le_bytes());
        out[4..8].copy_from_slice(&self.view_bits.to_le_bytes());
        out
    }
}

/// One shared S3DB building-attribute record: 20 B, layout-identical to
/// `body::BuildingAttrs` so conversion is a field copy, never a reinterpret.
///
/// Wire: `0..2` height (decimetres), `2..4` min_height, `4..6` roof_height,
/// `6` roof_shape, `7` roof_direction, `8` roof_orientation, `9..12` reserved
/// zero, `12..16` building_colour `0xAARRGGBB`, `16..20` roof_colour.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Hash)]
pub struct SharedBuildingAttrs {
    pub height: u16,
    pub min_height: u16,
    pub roof_height: u16,
    pub roof_shape: u8,
    pub roof_direction: u8,
    pub roof_orientation: u8,
    pub building_colour: u32,
    pub roof_colour: u32,
}

impl SharedBuildingAttrs {
    pub fn parse(buf: &[u8]) -> Result<SharedBuildingAttrs> {
        if buf.len() < SHARED_BUILDING_LEN {
            return err("a shared building record runs past its pool");
        }
        if buf[9] != 0 || buf[10] != 0 || buf[11] != 0 {
            return err("a shared building record has non-zero reserved bytes");
        }
        if buf[6] > super::body::ROOF_SHAPE_MAX {
            return err(format!("a shared building record has roof shape {}", buf[6]));
        }
        if buf[8] > super::body::ROOF_ORIENT_MAX {
            return err(format!("a shared building record has roof orientation {}", buf[8]));
        }
        Ok(SharedBuildingAttrs {
            height: u16::from_le_bytes([buf[0], buf[1]]),
            min_height: u16::from_le_bytes([buf[2], buf[3]]),
            roof_height: u16::from_le_bytes([buf[4], buf[5]]),
            roof_shape: buf[6],
            roof_direction: buf[7],
            roof_orientation: buf[8],
            building_colour: u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]),
            roof_colour: u32::from_le_bytes([buf[16], buf[17], buf[18], buf[19]]),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; SHARED_BUILDING_LEN];
        out[0..2].copy_from_slice(&self.height.to_le_bytes());
        out[2..4].copy_from_slice(&self.min_height.to_le_bytes());
        out[4..6].copy_from_slice(&self.roof_height.to_le_bytes());
        out[6] = self.roof_shape;
        out[7] = self.roof_direction;
        out[8] = self.roof_orientation;
        out[12..16].copy_from_slice(&self.building_colour.to_le_bytes());
        out[16..20].copy_from_slice(&self.roof_colour.to_le_bytes());
        out
    }

    pub fn is_default(&self) -> bool {
        *self == SharedBuildingAttrs::default()
    }

    pub fn from_body(a: &super::body::BuildingAttrs) -> SharedBuildingAttrs {
        SharedBuildingAttrs {
            height: a.height,
            min_height: a.min_height,
            roof_height: a.roof_height,
            roof_shape: a.roof_shape,
            roof_direction: a.roof_direction,
            roof_orientation: a.roof_orientation,
            building_colour: a.building_colour,
            roof_colour: a.roof_colour,
        }
    }

    pub fn to_body(&self) -> super::body::BuildingAttrs {
        super::body::BuildingAttrs {
            height: self.height,
            min_height: self.min_height,
            roof_height: self.roof_height,
            roof_shape: self.roof_shape,
            roof_direction: self.roof_direction,
            roof_orientation: self.roof_orientation,
            building_colour: self.building_colour,
            roof_colour: self.roof_colour,
        }
    }
}

/// One shared carriageway record: 6 B, layout-identical to `body::Carriageway`.
///
/// Wire: `0` forward lanes, `1` backward lanes, `2..6` solid divider bits LE.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Hash)]
pub struct SharedCarriageway {
    pub forward: u8,
    pub backward: u8,
    pub solid_dividers: u32,
}

impl SharedCarriageway {
    pub fn parse(buf: &[u8]) -> Result<SharedCarriageway> {
        if buf.len() < SHARED_CARRIAGEWAY_LEN {
            return err("a shared carriageway record runs past its pool");
        }
        Ok(SharedCarriageway {
            forward: buf[0],
            backward: buf[1],
            solid_dividers: u32::from_le_bytes([buf[2], buf[3], buf[4], buf[5]]),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; SHARED_CARRIAGEWAY_LEN];
        out[0] = self.forward;
        out[1] = self.backward;
        out[2..6].copy_from_slice(&self.solid_dividers.to_le_bytes());
        out
    }

    pub fn is_default(&self) -> bool {
        *self == SharedCarriageway::default()
    }

    pub fn from_body(c: &super::body::Carriageway) -> SharedCarriageway {
        SharedCarriageway { forward: c.forward, backward: c.backward, solid_dividers: c.solid_dividers }
    }

    pub fn to_body(&self) -> super::body::Carriageway {
        super::body::Carriageway {
            forward: self.forward,
            backward: self.backward,
            solid_dividers: self.solid_dividers,
        }
    }
}

/// One shared turn-lane record: interned as a **whole record**, never per lane.
///
/// Two roads sharing the same `(forward, backward)` mask vectors share one pool
/// entry; a road with no `turn:lanes` maps to index 0 (empty) and stores
/// nothing. Wire per entry: `u8` forward count, `u8` backward count, then that
/// many `u16` LE masks (forward then backward). Counts are `u8`, so a record
/// with more than 255 lanes each way is refused on the way in.
#[derive(Debug, Clone, PartialEq, Eq, Default, Hash)]
pub struct SharedLaneTurns {
    pub forward: Vec<u16>,
    pub backward: Vec<u16>,
}

impl SharedLaneTurns {
    pub fn is_empty(&self) -> bool {
        self.forward.is_empty() && self.backward.is_empty()
    }

    pub fn from_body(t: &super::body::LaneTurns) -> SharedLaneTurns {
        SharedLaneTurns { forward: t.forward.clone(), backward: t.backward.clone() }
    }

    pub fn to_body(&self) -> super::body::LaneTurns {
        super::body::LaneTurns { forward: self.forward.clone(), backward: self.backward.clone() }
    }
}

/// The junction key: FNV-1a over quantised centreline points.
///
/// Junctions have **no stable id** (confirmed) — a lane connector is a sampled
/// centreline, not an OSM element — so the geometry hash rides the content key
/// alongside `ID_NONE`. Tile-local clips hash differently, exactly as before:
/// no cross-tile junction dedup is claimed, and none is needed for the build
/// to succeed.
pub fn junction_key(points: &[(i16, i16)]) -> u32 {
    let mut hash: u32 = 0x811C_9DC5;
    for &(x, y) in points {
        for b in x.to_le_bytes().iter().chain(y.to_le_bytes().iter()) {
            hash ^= *b as u32;
            hash = hash.wrapping_mul(0x0100_0193);
        }
    }
    hash
}

/// One shared row's full content key: what makes two sightings the same row.
///
/// `(layer, stable_id, geom_hash)` plus every pooled value, so equal keys mean
/// equal rows and the builder can hand back the existing `logical_id` rather
/// than fail. `u64` stable ids (not a 32-bit fold) ride the key whole, so
/// low16-aliasing traffic edges are distinct by construction. Junctions carry
/// `ID_NONE` with a geometry hash; way rows carry the OSM id with no hash.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct SharedRowKey {
    pub layer: u8,
    pub stable_id: u64,
    pub geom_hash: u32,
    pub name: Option<String>,
    pub kind: u16,
    pub kind_detail: u16,
    pub flags: u32,
    pub building: SharedBuildingAttrs,
    pub carriageway: SharedCarriageway,
    pub lane_turns: SharedLaneTurns,
}

/// Encode stable ids: sorted delta + zigzag varint with RLE `ID_NONE`.
///
/// Input is the per-row stable id in `logical_id` order (`ID_NONE` where a row
/// has no stable identity — every junction row). Encoding walks the input in
/// order: each maximal run of `ID_NONE` of length `n` becomes two varints
/// (`0`, `n`); each non-zero id becomes one varint holding
/// `zigzag(delta) + 1`, where `delta` is the *signed* difference against the
/// previous non-zero id (or zero for the first). The `+ 1` reserves varint 0
/// as the RLE marker so a zero delta (a duplicate id) can never encode
/// silently. Deltas may be negative — row order is first-sighting order, which
/// says nothing about stable-id order — and the decoder enforces
/// `delta != 0`.
///
/// Ids above `i64::MAX` are refused: the zigzag step is defined over `i64`, and
/// real ids (OSM elements, packed z12/z13 component ids) are far below it.
pub fn encode_id_runs(ids: &[u64]) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut i = 0usize;
    let mut prev: u64 = 0;
    let mut first_nonzero = true;
    while i < ids.len() {
        if ids[i] == ID_NONE {
            let mut n = 0u64;
            while i < ids.len() && ids[i] == ID_NONE {
                n += 1;
                i += 1;
            }
            push_uvarint(&mut out, 0);
            push_uvarint(&mut out, n);
            continue;
        }
        let id = ids[i];
        if id > i64::MAX as u64 {
            return err(format!("a shared id {id} is past i64::MAX"));
        }
        let base = if first_nonzero { 0 } else { prev };
        let delta = (id as i64).wrapping_sub(base as i64);
        if delta == 0 {
            return err(format!("shared ids must be distinct in row order, got duplicate {id}"));
        }
        if !first_nonzero && prev > i64::MAX as u64 {
            return err("a shared id base is past i64::MAX");
        }
        let zz = crate::proto::zigzag_encode(delta);
        push_uvarint(&mut out, zz.checked_add(1).ok_or_else(|| {
            crate::proto::Error("a shared id delta overflows its marker offset".to_string())
        })?);
        prev = id;
        first_nonzero = false;
        i += 1;
    }
    Ok(out)
}

/// Decode [`encode_id_runs`]: the inverse, strictly validated.
///
/// Returns the per-row ids with `ID_NONE` runs expanded. A truncated stream, a
/// zero delta (which would mean a duplicate id), or trailing non-zero bytes are
/// all refused rather than decoded to a list the rows would misalign with.
/// Trailing zero bytes are the pool's 4-byte alignment padding and are
/// accepted only once the decoded count has reached `expected`.
pub fn decode_id_runs(buf: &[u8], expected: usize) -> Result<Vec<u64>> {
    let mut ids = Vec::with_capacity(expected);
    let mut pos = 0usize;
    // A manual cursor rather than `proto::Reader`, so that once the decoded
    // count reaches `expected` the remainder can be required to be zero
    // padding instead of being decoded as more ids.
    let mut uvarint_at = |pos: &mut usize| -> Result<u64> {
        let mut val: u64 = 0;
        let mut shift = 0u32;
        loop {
            if *pos >= buf.len() {
                return err("a shared id stream ends inside a varint");
            }
            let b = buf[*pos];
            *pos += 1;
            if shift >= 64 {
                return err("a shared id varint is longer than 64 bits");
            }
            val |= ((b & 0x7F) as u64) << shift;
            if b & 0x80 == 0 {
                return Ok(val);
            }
            shift += 7;
        }
    };
    let mut prev: u64 = 0;
    let mut first_nonzero = true;
    while pos < buf.len() {
        if ids.len() == expected {
            if buf[pos..].iter().all(|&b| b == 0) {
                break;
            }
            return err("a shared id stream is longer than its row count");
        }
        let v = uvarint_at(&mut pos)?;
        if v == 0 {
            let n = uvarint_at(&mut pos)? as usize;
            if n == 0 {
                return err("a shared id run has zero length");
            }
            if ids.len() + n > expected {
                return err("a shared id NONE-run runs past its row count");
            }
            ids.extend(std::iter::repeat(ID_NONE).take(n));
            continue;
        }
        let delta = crate::proto::zigzag_decode(v - 1) as i64;
        if delta == 0 {
            return err("a shared id delta is zero (duplicate id)");
        }
        let base = if first_nonzero { 0 } else { prev as i64 };
        let id = base.checked_add(delta).ok_or_else(|| {
            crate::proto::Error("a shared id delta overflows".to_string())
        })?;
        if id < 0 {
            return err("a shared id delta goes negative");
        }
        let id = id as u64;
        if id == ID_NONE {
            return err("a shared id delta lands on ID_NONE");
        }
        ids.push(id);
        prev = id;
        first_nonzero = false;
    }
    if ids.len() != expected {
        return err(format!(
            "a shared id stream decodes to {} id(s) for {expected} row(s)",
            ids.len()
        ));
    }
    Ok(ids)
}

#[inline]
fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

fn align4(at: usize) -> usize {
    (at + 3) & !3
}

/// The global string pool: `u32` count + `u32` offsets + uvarint-len UTF-8 blobs.
///
/// Wire: `u32` LE count `n`, then `n` × `u32` LE offsets (each measured from the
/// first blob byte — the byte after the offset table — to its blob), then the
/// blobs back to back, each a uvarint byte length followed by that many UTF-8
/// bytes. Offsets must ascend and land exactly on blob boundaries; the last
/// blob must end exactly at the pool's end. Whole pool padded to 4 B.
///
/// A `name_ref` of 0 is [`SHARED_NAME_NONE`]; otherwise `names[ref - 1]`.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SharedStringPool {
    pub names: Vec<String>,
}

impl SharedStringPool {
    pub fn lookup(&self, name_ref: u32) -> Option<&str> {
        (name_ref != SHARED_NAME_NONE)
            .then(|| self.names.get(name_ref as usize - 1).map(String::as_str))
            .flatten()
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&(self.names.len() as u32).to_le_bytes());
        let offsets_at = out.len();
        out.resize(offsets_at + self.names.len() * 4, 0);
        let mut blobs = Vec::new();
        let mut cursor = 0u32;
        for (k, name) in self.names.iter().enumerate() {
            out[offsets_at + k * 4..offsets_at + k * 4 + 4]
                .copy_from_slice(&cursor.to_le_bytes());
            let mut len_prefix = Vec::new();
            push_uvarint(&mut len_prefix, name.len() as u64);
            cursor += (len_prefix.len() + name.len()) as u32;
            blobs.extend_from_slice(&len_prefix);
            blobs.extend_from_slice(name.as_bytes());
        }
        out.extend_from_slice(&blobs);
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    }

    pub fn parse(buf: &[u8]) -> Result<(SharedStringPool, usize)> {
        if buf.len() < 4 {
            return err("a shared string pool ends before its count");
        }
        let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
        let table_end = 4usize
            .checked_add(count.checked_mul(4).ok_or_else(|| {
                crate::proto::Error("a shared string pool's offset table overflows".to_string())
            })?)
            .ok_or_else(|| {
                crate::proto::Error("a shared string pool's offset table overflows".to_string())
            })?;
        if table_end > buf.len() {
            return err("a shared string pool's offsets run past the section");
        }
        // Bounded by the slice before allocating: table_end <= buf.len() implies
        // count <= buf.len() / 4, so a corrupt count cannot ask for gigabytes.
        let mut offsets = Vec::with_capacity(count);
        for i in 0..count {
            let o = 4 + i * 4;
            offsets.push(u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]) as usize);
        }
        // Ascending, so a corrupt offset cannot alias two names to one blob.
        for pair in offsets.windows(2) {
            if pair[1] < pair[0] {
                return err("a shared string pool's offsets are not ascending");
            }
        }
        let blobs = &buf[table_end..];
        let mut names = Vec::with_capacity(count);
        // Walk each blob by its own length prefix, then require it to end
        // exactly where the next offset says (or at blobs_end for the last).
        // The last blob's extent comes from its length, never from the pool's
        // end — which includes alignment padding the blob check must not eat.
        let mut blobs_end = 0usize;
        for (k, off) in offsets.iter().enumerate() {
            if *off > blobs.len() {
                return err("a shared string offset points past its blobs");
            }
            let mut r = crate::proto::Reader::new(&blobs[*off..]);
            let len = r.uvarint()? as usize;
            let mut prefix = Vec::new();
            push_uvarint(&mut prefix, len as u64);
            let end = off.checked_add(prefix.len()).and_then(|e| e.checked_add(len)).ok_or_else(
                || crate::proto::Error("a shared string blob overflows".to_string()),
            )?;
            if end > blobs.len() {
                return err("a shared string blob runs past its pool");
            }
            if k + 1 < count {
                if end != offsets[k + 1] {
                    return err("a shared string blob's length does not reach the next offset");
                }
            } else {
                blobs_end = end;
            }
            let text = std::str::from_utf8(&blobs[off + prefix.len()..end])
                .map_err(|_| crate::proto::Error("a shared string is not UTF-8".to_string()))?;
            names.push(text.to_string());
        }
        if count == 0 {
            blobs_end = 0;
        }
        // Consumption is the aligned end of the last blob; trailing section bytes
        // belong to the next pool when parsing a whole section.
        let blobs_end_abs = table_end + blobs_end;
        let aligned = align4(blobs_end_abs);
        if aligned > buf.len() {
            return err("a shared string pool's padding runs past the section");
        }
        // Padding between the last blob and alignment must be zeroes.
        if buf[blobs_end_abs..aligned].iter().any(|&b| b != 0) {
            return err("a shared string pool has non-zero padding");
        }
        Ok((SharedStringPool { names }, aligned))
    }
}

/// A fully parsed shared section (read path; no `write` feature needed).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedView {
    pub header: SharedHeader,
    pub strings: SharedStringPool,
    pub rows: Vec<SharedLogicalRow>,
    pub buildings: Vec<SharedBuildingAttrs>,
    pub carriageways: Vec<SharedCarriageway>,
    pub lane_turns: Vec<SharedLaneTurns>,
    /// Decoded per-row stable ids (`ID_NONE` expanded), parallel to `rows`.
    /// Empty when the section carries no id pool.
    pub ids: Vec<u64>,
    pub slim_refs: Vec<SharedSlimRef>,
}

impl SharedView {
    /// Parse a whole shared section: header, directory, then each pool.
    ///
    /// Every pool must lie inside `total_len`, pools must not overlap, and the
    /// counts in the header must agree with what the pools decode to. Rows must
    /// arrive sorted and distinct by `logical_id`; attribute indices must land
    /// inside their pools; `name_ref`s must land inside the string pool (or be
    /// zero). Anything else is corruption, refused here rather than indexed
    /// into on device.
    pub fn parse(buf: &[u8]) -> Result<SharedView> {
        let header = SharedHeader::parse(buf)?;
        if buf.len() < header.total_len as usize {
            return err(format!(
                "a shared section declares {} bytes but is {}",
                header.total_len,
                buf.len()
            ));
        }
        let buf = &buf[..header.total_len as usize];
        let dir_end = SHARED_HEADER_LEN + header.pool_count as usize * SHARED_POOL_ENTRY_LEN;
        if dir_end > buf.len() {
            return err("a shared section's directory runs past its end");
        }
        let mut entries = Vec::with_capacity(header.pool_count as usize);
        let mut previous_kind: Option<u8> = None;
        for i in 0..header.pool_count as usize {
            let at = SHARED_HEADER_LEN + i * SHARED_POOL_ENTRY_LEN;
            let e = SharedPoolDirEntry::parse(&buf[at..at + SHARED_POOL_ENTRY_LEN])?;
            if previous_kind.is_some_and(|p| e.kind <= p) {
                return err("a shared section's directory is not ordered by pool kind");
            }
            previous_kind = Some(e.kind);
            let end = e.offset.checked_add(e.len).ok_or_else(|| {
                crate::proto::Error("a shared pool's extent overflows".to_string())
            })?;
            if e.offset < dir_end as u64 || end > header.total_len as u64 {
                return err(format!("shared pool {} lies outside the section", e.kind));
            }
            entries.push(e);
        }
        // Pairwise overlap, because a writer that patched one offset and not
        // another would otherwise alias two pools to the same bytes.
        for (i, a) in entries.iter().enumerate() {
            for b in &entries[i + 1..] {
                if a.len == 0 || b.len == 0 {
                    continue;
                }
                if a.offset < b.offset + b.len && b.offset < a.offset + a.len {
                    return err("two shared pools overlap");
                }
            }
        }
        let slice_of = |kind: u8| -> Result<Option<&[u8]>> {
            match entries.iter().find(|e| e.kind == kind) {
                None => Ok(None),
                Some(e) => Ok(Some(&buf[e.offset as usize..(e.offset + e.len) as usize])),
            }
        };
        // Strings (optional only when string_count == 0).
        let strings = match slice_of(SHARED_KIND_STRINGS)? {
            None => {
                if header.string_count != 0 {
                    return err("a shared section names strings it does not carry");
                }
                SharedStringPool::default()
            }
            Some(bytes) => {
                let (pool, used) = SharedStringPool::parse(bytes)?;
                if used != bytes.len() {
                    return err("a shared string pool has trailing bytes past its padding");
                }
                if pool.names.len() as u32 != header.string_count {
                    return err(format!(
                        "a shared section declares {} strings but carries {}",
                        header.string_count,
                        pool.names.len()
                    ));
                }
                pool
            }
        };
        // Rows.
        let rows = match slice_of(SHARED_KIND_ROWS)? {
            None => {
                if header.row_count != 0 {
                    return err("a shared section names rows it does not carry");
                }
                Vec::new()
            }
            Some(bytes) => {
                let entry = entries.iter().find(|e| e.kind == SHARED_KIND_ROWS).expect("found");
                if entry.elem_len as usize != SHARED_ROW_LEN {
                    return err("a shared row pool declares the wrong stride");
                }
                if bytes.len() % SHARED_ROW_LEN != 0 {
                    return err("a shared row pool is not a whole number of rows");
                }
                let mut rows = Vec::with_capacity(bytes.len() / SHARED_ROW_LEN);
                for chunk in bytes.chunks_exact(SHARED_ROW_LEN) {
                    rows.push(SharedLogicalRow::parse(chunk)?);
                }
                if rows.len() as u32 != header.row_count {
                    return err(format!(
                        "a shared section declares {} rows but carries {}",
                        header.row_count,
                        rows.len()
                    ));
                }
                if rows.windows(2).any(|p| p[1].logical_id <= p[0].logical_id) {
                    return err("shared logical rows are not strictly ascending by id");
                }
                rows
            }
        };
        // Buildings (index 0 = default; pool always carries it when present).
        let buildings = match slice_of(SHARED_KIND_BUILDINGS)? {
            None => vec![SharedBuildingAttrs::default()],
            Some(bytes) => parse_building_pool(bytes)?,
        };
        // Carriageways.
        let carriageways = match slice_of(SHARED_KIND_CARRIAGEWAYS)? {
            None => vec![SharedCarriageway::default()],
            Some(bytes) => parse_carriageway_pool(bytes)?,
        };
        // Lane turns (whole-record interned; index 0 = empty).
        let lane_turns = match slice_of(SHARED_KIND_LANE_TURNS)? {
            None => vec![SharedLaneTurns::default()],
            Some(bytes) => parse_lane_turns_pool(bytes)?,
        };
        // Id runs (optional; when present, parallel to rows).
        let ids = match slice_of(SHARED_KIND_ID_RUNS)? {
            None => {
                if header.id_run_count != 0 {
                    return err("a shared section names id runs it does not carry");
                }
                Vec::new()
            }
            Some(bytes) => {
                let ids = decode_id_runs(bytes, header.id_run_count as usize)?;
                if ids.len() as u32 != header.id_run_count {
                    return err("a shared id pool decodes to the wrong count");
                }
                if !rows.is_empty() && ids.len() != rows.len() {
                    return err(format!(
                        "a shared id pool has {} id(s) for {} row(s)",
                        ids.len(),
                        rows.len()
                    ));
                }
                ids
            }
        };
        // Slim refs.
        let slim_refs = match slice_of(SHARED_KIND_SLIM_REFS)? {
            None => Vec::new(),
            Some(bytes) => {
                let entry =
                    entries.iter().find(|e| e.kind == SHARED_KIND_SLIM_REFS).expect("found");
                if entry.elem_len as usize != SHARED_SLIM_REF_LEN {
                    return err("a shared slim-ref pool declares the wrong stride");
                }
                if bytes.len() % SHARED_SLIM_REF_LEN != 0 {
                    return err("a shared slim-ref pool is not a whole number of refs");
                }
                bytes
                    .chunks_exact(SHARED_SLIM_REF_LEN)
                    .map(SharedSlimRef::parse)
                    .collect::<Result<Vec<_>>>()?
            }
        };
        // Cross-references: every index a row names must exist.
        for row in &rows {
            if row.name_ref != SHARED_NAME_NONE && row.name_ref as usize > strings.names.len() {
                return err(format!(
                    "shared row {} names string {} of {}",
                    row.logical_id,
                    row.name_ref,
                    strings.names.len()
                ));
            }
            if row.building_idx as usize >= buildings.len() {
                return err(format!(
                    "shared row {} names building {} of {}",
                    row.logical_id,
                    row.building_idx,
                    buildings.len()
                ));
            }
            if row.carriageway_idx as usize >= carriageways.len() {
                return err(format!(
                    "shared row {} names carriageway {} of {}",
                    row.logical_id,
                    row.carriageway_idx,
                    carriageways.len()
                ));
            }
            if row.lane_turns_idx as usize >= lane_turns.len() {
                return err(format!(
                    "shared row {} names lane-turns {} of {}",
                    row.logical_id,
                    row.lane_turns_idx,
                    lane_turns.len()
                ));
            }
        }
        Ok(SharedView { header, strings, rows, buildings, carriageways, lane_turns, ids, slim_refs })
    }

    /// The row with this `logical_id`, or `None` (rows are sorted, so binary).
    pub fn row(&self, logical_id: u32) -> Option<&SharedLogicalRow> {
        self.rows.binary_search_by_key(&logical_id, |r| r.logical_id).ok().map(|i| &self.rows[i])
    }

    /// This row's display name, or `None` for [`SHARED_NAME_NONE`].
    pub fn row_name(&self, row: &SharedLogicalRow) -> Option<&str> {
        self.strings.lookup(row.name_ref)
    }
}

fn parse_building_pool(buf: &[u8]) -> Result<Vec<SharedBuildingAttrs>> {
    if buf.len() < 4 {
        return err("a shared building pool ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    let bytes = count.checked_mul(SHARED_BUILDING_LEN).ok_or_else(|| {
        crate::proto::Error("a shared building pool overflows".to_string())
    })?;
    if 4 + bytes > buf.len() {
        return err("a shared building pool's records run past the pool");
    }
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        out.push(SharedBuildingAttrs::parse(&buf[4 + i * SHARED_BUILDING_LEN..])?);
    }
    if !out.is_empty() && !out[0].is_default() {
        return err("a shared building pool's index 0 is not the default");
    }
    // The directory names this pool's exact extent, so anything past the
    // records (outside 4-byte alignment padding) is trailing garbage, refused
    // the way `Body::parse` refuses trailing bytes past its sections.
    let end = 4 + bytes;
    let aligned = align4(end);
    if aligned != buf.len() {
        return err("a shared building pool has trailing bytes past its records");
    }
    if buf[end..aligned].iter().any(|&b| b != 0) {
        return err("a shared building pool has non-zero padding");
    }
    Ok(out)
}

fn parse_carriageway_pool(buf: &[u8]) -> Result<Vec<SharedCarriageway>> {
    if buf.len() < 4 {
        return err("a shared carriageway pool ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    let bytes = count.checked_mul(SHARED_CARRIAGEWAY_LEN).ok_or_else(|| {
        crate::proto::Error("a shared carriageway pool overflows".to_string())
    })?;
    if 4 + bytes > buf.len() {
        return err("a shared carriageway pool's records run past the pool");
    }
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        out.push(SharedCarriageway::parse(&buf[4 + i * SHARED_CARRIAGEWAY_LEN..])?);
    }
    if !out.is_empty() && !out[0].is_default() {
        return err("a shared carriageway pool's index 0 is not the default");
    }
    let end = 4 + bytes;
    let aligned = align4(end);
    if aligned != buf.len() {
        return err("a shared carriageway pool has trailing bytes past its records");
    }
    if buf[end..aligned].iter().any(|&b| b != 0) {
        return err("a shared carriageway pool has non-zero padding");
    }
    Ok(out)
}

fn parse_lane_turns_pool(buf: &[u8]) -> Result<Vec<SharedLaneTurns>> {
    if buf.len() < 4 {
        return err("a shared lane-turns pool ends before its count");
    }
    let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
    let mut at = 4usize;
    let mut out = Vec::with_capacity(count);
    for _ in 0..count {
        if at + 2 > buf.len() {
            return err("a shared lane-turns record ends inside its counts");
        }
        let (fwd, bwd) = (buf[at] as usize, buf[at + 1] as usize);
        at += 2;
        let masks = fwd + bwd;
        let bytes = masks.checked_mul(2).ok_or_else(|| {
            crate::proto::Error("a shared lane-turns record overflows".to_string())
        })?;
        if at + bytes > buf.len() {
            return err("a shared lane-turns record's masks run past the pool");
        }
        let mut forward = Vec::with_capacity(fwd);
        let mut backward = Vec::with_capacity(bwd);
        for _ in 0..fwd {
            forward.push(u16::from_le_bytes([buf[at], buf[at + 1]]));
            at += 2;
        }
        for _ in 0..bwd {
            backward.push(u16::from_le_bytes([buf[at], buf[at + 1]]));
            at += 2;
        }
        out.push(SharedLaneTurns { forward, backward });
    }
    if !out.is_empty() && !out[0].is_empty() {
        return err("a shared lane-turns pool's index 0 is not empty");
    }
    let aligned = align4(at);
    if aligned != buf.len() {
        return err("a shared lane-turns pool has trailing bytes past its records");
    }
    if buf[at..aligned].iter().any(|&b| b != 0) {
        return err("a shared lane-turns pool has non-zero padding");
    }
    Ok(out)
}

fn serialize_building_pool(pool: &[SharedBuildingAttrs]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(pool.len() as u32).to_le_bytes());
    for a in pool {
        out.extend_from_slice(&a.serialize());
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out
}

fn serialize_carriageway_pool(pool: &[SharedCarriageway]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(pool.len() as u32).to_le_bytes());
    for c in pool {
        out.extend_from_slice(&c.serialize());
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out
}

fn serialize_lane_turns_pool(pool: &[SharedLaneTurns]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&(pool.len() as u32).to_le_bytes());
    for t in pool {
        out.push(t.forward.len() as u8);
        out.push(t.backward.len() as u8);
        for m in t.forward.iter().chain(&t.backward) {
            out.extend_from_slice(&m.to_le_bytes());
        }
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    out
}

/// The builder: interns strings/attrs whole-value and emits one section.
///
/// Write-gated so the interner maps (and their `HashMap` iteration) never reach
/// the read path — the same reason `mamaps::write` lives behind `write`.
#[cfg(feature = "write")]
pub struct SharedBuilder {
    strings: Vec<String>,
    string_idx: std::collections::HashMap<String, u32>,
    rows: Vec<SharedLogicalRow>,
    ids: Vec<u64>,
    buildings: Vec<SharedBuildingAttrs>,
    building_idx: std::collections::HashMap<[u8; SHARED_BUILDING_LEN], u32>,
    carriageways: Vec<SharedCarriageway>,
    carriageway_idx: std::collections::HashMap<u64, u32>,
    lane_turns: Vec<SharedLaneTurns>,
    lane_turns_idx: std::collections::HashMap<Vec<u16>, u32>,
    slim_refs: Vec<SharedSlimRef>,
    /// Content-keyed row dedup: the full [`SharedRowKey`] to its sequential
    /// `logical_id` (1-based in first-sighting order). Same file, so first
    /// sighting *is* the id — no content-derived reproducibility is needed,
    /// and no fold can collide.
    row_idx: std::collections::HashMap<SharedRowKey, u32>,
}

#[cfg(feature = "write")]
impl SharedBuilder {
    pub fn new() -> SharedBuilder {
        SharedBuilder {
            strings: Vec::new(),
            string_idx: std::collections::HashMap::new(),
            rows: Vec::new(),
            // Index 0 of every attr pool is the default, seeded up front.
            buildings: vec![SharedBuildingAttrs::default()],
            building_idx: std::collections::HashMap::new(),
            carriageways: vec![SharedCarriageway::default()],
            carriageway_idx: std::collections::HashMap::new(),
            lane_turns: vec![SharedLaneTurns::default()],
            lane_turns_idx: std::collections::HashMap::new(),
            ids: Vec::new(),
            slim_refs: Vec::new(),
            row_idx: std::collections::HashMap::new(),
        }
    }

    /// Intern a display name. Returns 0 ([`SHARED_NAME_NONE`]) for empty input.
    pub fn intern_string(&mut self, name: &str) -> u32 {
        if name.is_empty() {
            return SHARED_NAME_NONE;
        }
        if let Some(&r) = self.string_idx.get(name) {
            return r;
        }
        self.strings.push(name.to_string());
        let r = self.strings.len() as u32;
        self.string_idx.insert(name.to_string(), r);
        r
    }

    pub fn intern_building(&mut self, a: SharedBuildingAttrs) -> u32 {
        if a.is_default() {
            return SHARED_ATTR_DEFAULT;
        }
        let key = {
            let b = a.serialize();
            let mut k = [0u8; SHARED_BUILDING_LEN];
            k.copy_from_slice(&b);
            k
        };
        if let Some(&i) = self.building_idx.get(&key) {
            return i;
        }
        self.buildings.push(a);
        let i = self.buildings.len() as u32 - 1;
        self.building_idx.insert(key, i);
        i
    }

    pub fn intern_carriageway(&mut self, c: SharedCarriageway) -> u32 {
        if c.is_default() {
            return SHARED_ATTR_DEFAULT;
        }
        let key = (c.forward as u64) | ((c.backward as u64) << 8) | ((c.solid_dividers as u64) << 16);
        if let Some(&i) = self.carriageway_idx.get(&key) {
            return i;
        }
        self.carriageways.push(c);
        let i = self.carriageways.len() as u32 - 1;
        self.carriageway_idx.insert(key, i);
        i
    }

    /// Intern a whole turn-lane record (never per lane).
    pub fn intern_lane_turns(&mut self, t: SharedLaneTurns) -> u32 {
        if t.is_empty() {
            return SHARED_ATTR_DEFAULT;
        }
        if t.forward.len() > u8::MAX as usize || t.backward.len() > u8::MAX as usize {
            panic!("a shared lane-turns record carries more than 255 lanes each way");
        }
        let mut key = Vec::with_capacity(2 + t.forward.len() + t.backward.len());
        key.push(t.forward.len() as u16);
        key.push(t.backward.len() as u16);
        key.extend(t.forward.iter().copied());
        key.extend(t.backward.iter().copied());
        if let Some(&i) = self.lane_turns_idx.get(&key) {
            return i;
        }
        self.lane_turns.push(t);
        let i = self.lane_turns.len() as u32 - 1;
        self.lane_turns_idx.insert(key, i);
        i
    }

    /// Intern one logical row by full content, returning its sequential id.
    ///
    /// The first sighting of a [`SharedRowKey`] interns strings/attrs, pushes
    /// the row (id = rows-so-far + 1, since index 0 is reserved for "none" the
    /// way pools seed their defaults), and records the parallel stable id; a
    /// repeat sighting returns the existing id with nothing pushed. Equal keys
    /// mean equal rows, so a misjoin is unrepresentable: there is no fold to
    /// collide and no content check to fail.
    ///
    /// [`push_row`] remains for tests that pin explicit ids.
    pub fn intern_row(
        &mut self,
        key: SharedRowKey,
        kind: u16,
        kind_detail: u16,
        view_bits: u32,
        stable_id: u64,
    ) -> u32 {
        if let Some(&id) = self.row_idx.get(&key) {
            return id;
        }
        let name_ref = self.intern_string(key.name.as_deref().unwrap_or(""));
        let row = SharedLogicalRow {
            logical_id: self.rows.len() as u32 + 1,
            name_ref,
            kind,
            kind_detail,
            view_bits,
            building_idx: self.intern_building(key.building),
            carriageway_idx: self.intern_carriageway(key.carriageway),
            lane_turns_idx: self.intern_lane_turns(key.lane_turns.clone()),
            flags: key.flags,
        };
        let id = row.logical_id;
        self.rows.push(row);
        self.ids.push(stable_id);
        self.row_idx.insert(key, id);
        id
    }

    /// Push one logical row with its parallel stable id (`ID_NONE` when the row
    /// has no stable identity — every junction row). Duplicate `logical_id`s
    /// are refused at [`SharedBuilder::serialize`], not here, so a caller can
    /// stage rows in any order; the section is emitted sorted.
    pub fn push_row(&mut self, row: SharedLogicalRow, stable_id: u64) {
        self.rows.push(row);
        self.ids.push(stable_id);
    }

    pub fn push_slim_ref(&mut self, r: SharedSlimRef) {
        self.slim_refs.push(r);
    }

    /// Emit one shared section: header, directory, then pools in kind order.
    ///
    /// Rows are already in first-sighting order with sequential ids (see
    /// [`SharedBuilder::intern_row`); this re-sorts defensively and refuses a
    /// duplicate id, so a caller that bypassed `intern_row` via [`push_row`]
    /// still cannot emit two rows with one id. Pools a section
    /// has exactly one default entry of (a lone index-0 building table, say)
    /// are still written — the reader treats a missing pool as all-defaults,
    /// and writing the seed keeps "present but all default" distinct from
    /// "absent" for a diff.
    pub fn serialize(mut self) -> Result<Vec<u8>> {
        // Defensive: intern_row already emits sequential first-sighting ids,
        // but a push_row caller stages explicit ids in any order.
        let mut order: Vec<usize> = (0..self.rows.len()).collect();
        order.sort_by_key(|&i| self.rows[i].logical_id);
        if order.windows(2).any(|p| self.rows[p[0]].logical_id == self.rows[p[1]].logical_id) {
            return err("shared logical ids must be distinct");
        }
        let rows: Vec<SharedLogicalRow> = order.iter().map(|&i| self.rows[i]).collect();
        let ids: Vec<u64> = order.iter().map(|&i| self.ids[i]).collect();
        self.rows = rows;
        self.ids = ids;

        let string_pool = SharedStringPool { names: self.strings.clone() }.serialize();
        let mut rows_pool = Vec::new();
        for r in &self.rows {
            rows_pool.extend_from_slice(&r.serialize());
        }
        while rows_pool.len() % 4 != 0 {
            rows_pool.push(0);
        }
        let building_pool = serialize_building_pool(&self.buildings);
        let carriageway_pool = serialize_carriageway_pool(&self.carriageways);
        let lane_turns_pool = serialize_lane_turns_pool(&self.lane_turns);
        let id_pool = encode_id_runs(&self.ids)?;
        let mut id_pool_padded = id_pool.clone();
        while id_pool_padded.len() % 4 != 0 {
            id_pool_padded.push(0);
        }
        let mut slim_pool = Vec::new();
        for r in &self.slim_refs {
            slim_pool.extend_from_slice(&r.serialize());
        }
        while slim_pool.len() % 4 != 0 {
            slim_pool.push(0);
        }

        // Directory in kind order (the parser requires it).
        let pools: Vec<(u8, u32, Vec<u8>)> = vec![
            (SHARED_KIND_STRINGS, 0, string_pool),
            (SHARED_KIND_ROWS, SHARED_ROW_LEN as u32, rows_pool),
            (SHARED_KIND_BUILDINGS, SHARED_BUILDING_LEN as u32, building_pool),
            (SHARED_KIND_CARRIAGEWAYS, SHARED_CARRIAGEWAY_LEN as u32, carriageway_pool),
            (SHARED_KIND_LANE_TURNS, 0, lane_turns_pool),
            (SHARED_KIND_ID_RUNS, 0, id_pool_padded),
            (SHARED_KIND_SLIM_REFS, SHARED_SLIM_REF_LEN as u32, slim_pool),
        ];
        let pool_count = pools.len() as u32;
        let mut offset =
            (SHARED_HEADER_LEN + pools.len() * SHARED_POOL_ENTRY_LEN) as u64;
        let mut dir = Vec::new();
        for (kind, elem_len, bytes) in &pools {
            dir.push(SharedPoolDirEntry {
                kind: *kind,
                elem_len: *elem_len,
                offset,
                len: bytes.len() as u64,
            });
            offset += bytes.len() as u64;
        }
        let total_len = offset as u32;
        let header = SharedHeader {
            flags: 0,
            row_count: self.rows.len() as u32,
            string_count: self.strings.len() as u32,
            pool_count,
            total_len,
            id_run_count: self.ids.len() as u32,
        };
        let mut out = header.serialize();
        for e in &dir {
            out.extend_from_slice(&e.serialize());
        }
        for (_, _, bytes) in &pools {
            out.extend_from_slice(bytes);
        }
        debug_assert_eq!(out.len() as u32, total_len);
        Ok(out)
    }
}

#[cfg(feature = "write")]
impl Default for SharedBuilder {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Fail-watch convention: each test pins one wire fact as a literal, so a
    // revert of that fact quotes its failure (`left` vs `right`) rather than a
    // vague mismatch. Revert → quote the failure → restore.

    #[test]
    fn the_header_is_32_bytes_behind_mbsh() {
        let h = SharedHeader {
            flags: 0,
            row_count: 2,
            string_count: 1,
            pool_count: 7,
            total_len: 32 + 7 * 24 + 64,
            id_run_count: 2,
        };
        let bytes = h.serialize();
        assert_eq!(bytes.len(), 32, "SharedHeader must stay 32 B");
        assert_eq!(&bytes[0..4], b"MBSH");
        assert_eq!(SharedHeader::parse(&bytes).expect("parse"), h);
        let mut bad = bytes.clone();
        bad[0] = b'X';
        assert!(SharedHeader::parse(&bad).is_err(), "bad magic is refused");
    }

    #[test]
    fn pool_entries_are_24_bytes_with_known_kinds() {
        let e = SharedPoolDirEntry { kind: SHARED_KIND_ROWS, elem_len: 32, offset: 200, len: 64 };
        let bytes = e.serialize();
        assert_eq!(bytes.len(), 24, "dir entries must stay 24 B");
        assert_eq!(SharedPoolDirEntry::parse(&bytes).expect("parse"), e);
        let mut bad = bytes.clone();
        bad[0] = 99;
        assert!(SharedPoolDirEntry::parse(&bad).is_err(), "unknown pool kind");
    }

    #[test]
    fn logical_rows_are_32_bytes_and_carry_attr_indices() {
        let r = SharedLogicalRow {
            logical_id: 7,
            name_ref: 1,
            kind: 45,
            kind_detail: 4,
            view_bits: 0xFFFF,
            building_idx: 0,
            carriageway_idx: 1,
            lane_turns_idx: 0,
            flags: 0,
        };
        let bytes = r.serialize();
        assert_eq!(bytes.len(), 32, "logical rows must stay 32 B");
        assert_eq!(SharedLogicalRow::parse(&bytes).expect("parse"), r);
    }

    #[test]
    fn slim_refs_are_8_bytes() {
        let r = SharedSlimRef { logical_id: 9, view_bits: 3 };
        let bytes = r.serialize();
        assert_eq!(bytes.len(), 8);
        assert_eq!(SharedSlimRef::parse(&bytes).expect("parse"), r);
    }

    #[test]
    fn attr_records_keep_their_body_widths() {
        assert_eq!(SHARED_BUILDING_LEN, 20, "BuildingAttrs stays 20 B");
        assert_eq!(SHARED_CARRIAGEWAY_LEN, 6, "Carriageway stays 6 B");
        let b = SharedBuildingAttrs { height: 1200, roof_shape: 1, ..Default::default() };
        assert_eq!(b.serialize().len(), 20);
        assert_eq!(SharedBuildingAttrs::parse(&b.serialize()).expect("parse"), b);
        let c = SharedCarriageway { forward: 2, backward: 2, solid_dividers: 0b010 };
        assert_eq!(c.serialize().len(), 6);
        assert_eq!(SharedCarriageway::parse(&c.serialize()).expect("parse"), c);
    }

    #[test]
    fn name_ref_zero_is_none_and_lookup_is_one_based() {
        let pool = SharedStringPool { names: vec!["Main St".to_string()] };
        assert_eq!(pool.lookup(SHARED_NAME_NONE), None);
        assert_eq!(pool.lookup(1), Some("Main St"));
        assert_eq!(pool.lookup(2), None, "past the table");
        let (parsed, _) = SharedStringPool::parse(&pool.serialize()).expect("parse");
        assert_eq!(parsed, pool);
    }

    #[test]
    fn id_runs_round_trip_with_none_runs() {
        // Two junctions (NO stable id) then two traffic component ids.
        let ids = vec![ID_NONE, ID_NONE, 100, 250];
        let enc = encode_id_runs(&ids).expect("encode");
        assert_eq!(decode_id_runs(&enc, ids.len()).expect("decode"), ids);
        // All-NONE (a junctions-only section) is one RLE run.
        let nones = vec![ID_NONE; 5];
        let enc = encode_id_runs(&nones).expect("encode");
        assert_eq!(enc[0], 0, "RLE marker leads an all-NONE stream");
        assert_eq!(decode_id_runs(&enc, nones.len()).expect("decode"), nones);
        // Non-monotonic ids are fine (row order is first-sighting order);
        // duplicates are refused rather than silently delta'd.
        let jumbled = vec![250, 100, 300];
        let enc = encode_id_runs(&jumbled).expect("encode");
        assert_eq!(decode_id_runs(&enc, jumbled.len()).expect("decode"), jumbled);
        assert!(encode_id_runs(&[300, 300]).is_err(), "duplicate ids");
    }

    #[test]
    fn junction_keys_are_geometry_hashes_not_stable_ids() {
        let a = junction_key(&[(0, 0), (100, 50), (200, 0)]);
        let b = junction_key(&[(0, 0), (100, 50), (200, 1)]);
        assert_ne!(a, b, "distinct connectors hash distinctly");
        assert_eq!(a, junction_key(&[(0, 0), (100, 50), (200, 0)]), "stable for one geometry");
    }

    #[test]
    fn low16_aliasing_component_ids_are_distinct_rows() {
        // Two component ids sharing (edge_low16, seg): the old bare-low32 fold
        // aliased them to one row and failed the build. Content keys carry the
        // full u64, so they are distinct rows by construction.
        let mut b = SharedBuilder::new();
        let key = |id: u64| SharedRowKey {
            layer: 9,
            stable_id: id,
            geom_hash: 0,
            name: None,
            kind: 0,
            kind_detail: 0,
            flags: 0,
            building: SharedBuildingAttrs::default(),
            carriageway: SharedCarriageway::default(),
            lane_turns: SharedLaneTurns::default(),
        };
        let lo = |edge: u64, seg: u64| (edge << 16) | seg;
        let a = lo(0x0001_0042, 3);
        let c = lo(0x0002_0042, 3);
        assert_eq!(a as u32, c as u32, "low32 collides by construction");
        assert_ne!(
            b.intern_row(key(a), 0, 0, 0, a),
            b.intern_row(key(c), 0, 0, 0, c),
            "aliasing edges are distinct rows",
        );
    }

    #[test]
    fn whole_record_lane_turns_deduplicate_and_seed_empty_zero() {
        let t = SharedLaneTurns { forward: vec![1, 2], backward: vec![4] };
        let pool = serialize_lane_turns_pool(&[SharedLaneTurns::default(), t.clone()]);
        let parsed = parse_lane_turns_pool(&pool).expect("parse");
        assert!(parsed[0].is_empty(), "index 0 is empty");
        assert_eq!(parsed[1], t);
    }

    #[test]
    fn interned_rows_are_sequential_and_repeat_sightings_share_one_row() {
        let mut b = SharedBuilder::new();
        let key = |name: &str| SharedRowKey {
            layer: 1,
            stable_id: 100,
            geom_hash: 0,
            name: Some(name.to_string()),
            kind: 45,
            kind_detail: 0,
            flags: 0,
            building: SharedBuildingAttrs::default(),
            carriageway: SharedCarriageway::default(),
            lane_turns: SharedLaneTurns::default(),
        };
        let first = b.intern_row(key("Main St"), 45, 0, 0, 100);
        assert_eq!(first, 1, "the first row is id 1");
        let repeat = b.intern_row(key("Main St"), 45, 0, 0, 100);
        assert_eq!(repeat, 1, "a repeat sighting shares the row");
        let other = b.intern_row(key("Oak Ave"), 45, 0, 0, 200);
        assert_eq!(other, 2, "different content is a different row");
        let section = b.serialize().expect("section");
        let view = SharedView::parse(&section).expect("parse");
        assert_eq!(view.header.row_count, 2, "two rows, not three");
        assert_eq!(
            view.rows.iter().map(|r| r.logical_id).collect::<Vec<_>>(),
            vec![1, 2],
            "sequential ids in first-sighting order",
        );
    }
}
