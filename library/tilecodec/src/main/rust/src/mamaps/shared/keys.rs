//! Shared-section content keys: the junction hash, the row dedup key and the
//! stable-id run codec.
//!
//! Pure moves out of the former single-file shared module; nothing here changed
//! except taking `ID_NONE` from `crate::mamaps::body`, which the extra module
//! level requires.

use crate::mamaps::body::ID_NONE;
use crate::proto::{err, Result};

use super::pools::push_uvarint;
use super::records::{SharedBuildingAttrs, SharedCarriageway, SharedLaneTurns};

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
