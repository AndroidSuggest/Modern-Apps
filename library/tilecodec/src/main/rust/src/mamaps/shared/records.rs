//! Shared-section record types: the header, pool directory, logical rows, slim
//! refs and attribute records, with their parse/serialize impls.
//!
//! Pure moves out of the former single-file shared module; nothing here changed
//! except re-rooting `super::body` to `crate::mamaps::body`, which the extra
//! module level requires.

use crate::proto::{err, Result};

use super::consts::{
    KNOWN_ROW_FLAGS, KNOWN_SHARED_FLAGS, SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN,
    SHARED_FLAG_DETAIL_NUMERIC, SHARED_HEADER_LEN, SHARED_KIND_BUILDINGS,
    SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_GEOMETRY, SHARED_KIND_ID_RUNS,
    SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_SLIM_REFS, SHARED_KIND_STRINGS,
    SHARED_MAGIC, SHARED_POOL_ENTRY_LEN, SHARED_ROW_LEN, SHARED_SLIM_REF_LEN, SHARED_VERSION,
};
use crate::mamaps::body::{BuildingAttrs, Carriageway, LaneTurns, ROOF_ORIENT_MAX, ROOF_SHAPE_MAX};

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
                | SHARED_KIND_GEOMETRY
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
        if buf[6] > ROOF_SHAPE_MAX {
            return err(format!("a shared building record has roof shape {}", buf[6]));
        }
        if buf[8] > ROOF_ORIENT_MAX {
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

    pub fn from_body(a: &BuildingAttrs) -> SharedBuildingAttrs {
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

    pub fn to_body(&self) -> BuildingAttrs {
        BuildingAttrs {
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

    pub fn from_body(c: &Carriageway) -> SharedCarriageway {
        SharedCarriageway { forward: c.forward, backward: c.backward, solid_dividers: c.solid_dividers }
    }

    pub fn to_body(&self) -> Carriageway {
        Carriageway {
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

    pub fn from_body(t: &LaneTurns) -> SharedLaneTurns {
        SharedLaneTurns { forward: t.forward.clone(), backward: t.backward.clone() }
    }

    pub fn to_body(&self) -> LaneTurns {
        LaneTurns { forward: self.forward.clone(), backward: self.backward.clone() }
    }
}
