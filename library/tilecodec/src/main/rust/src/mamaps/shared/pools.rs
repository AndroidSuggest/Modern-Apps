//! Shared-section pools: the string pool plus the attribute-pool parse/serialize
//! helpers, and the varint/alignment utilities the other pool codecs share.
//!
//! Pure moves out of the former single-file shared module. `push_uvarint`, `align4`
//! and the pool helpers are `pub(super)` so the sibling pool modules can use them;
//! nothing else changed.

use crate::proto::{err, Result};

use super::consts::{SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN, SHARED_NAME_NONE};
use super::records::{SharedBuildingAttrs, SharedCarriageway, SharedLaneTurns};

#[inline]
pub(super) fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

pub(super) fn align4(at: usize) -> usize {
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

pub(super) fn parse_building_pool(buf: &[u8]) -> Result<Vec<SharedBuildingAttrs>> {
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

pub(super) fn parse_carriageway_pool(buf: &[u8]) -> Result<Vec<SharedCarriageway>> {
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

pub(super) fn parse_lane_turns_pool(buf: &[u8]) -> Result<Vec<SharedLaneTurns>> {
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

pub(super) fn serialize_building_pool(pool: &[SharedBuildingAttrs]) -> Vec<u8> {
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

pub(super) fn serialize_carriageway_pool(pool: &[SharedCarriageway]) -> Vec<u8> {
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

pub(super) fn serialize_lane_turns_pool(pool: &[SharedLaneTurns]) -> Vec<u8> {
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
