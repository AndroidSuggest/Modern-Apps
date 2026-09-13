use super::consts::PART_ENTRY_LEN;
use super::model::Part;
use crate::proto::{Result, err};

pub(crate) fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
    while value >= 0x80 {
        out.push((value as u8) | 0x80);
        value >>= 7;
    }
    out.push(value as u8);
}

// ─── v8.1 slim emit (lane B) ─────────────────────────────────────────────────
//
// The per-layer slim payload a v8.1 mixed body carries for its high-K layers, assembled here so
// lane B owns both sides of the 16-byte instance wire: [`encode_slim_instance`] packs one instance
// and `read::SlimBody::parse` unpacks it, with the fail-watched literal tests in `read` pinning the
// bytes from both ends.
//
// Wire per instance (16 B, all little-endian): `logical_id` (`u32`), `view_bits` (`u32`),
// `part_offset` (`u32`), `part_count` (`u32`) — the
// [`SharedSlimRef`](super::shared::SharedSlimRef) head plus the instance's range into its layer's
// own part table. The layer is implicit from the payload's index entry; kind, flags, lane count and
// transit styling resolve from the shared row at read time, never from these bytes.
//
// A payload is `[instances][part table][align4][varint arena]`, the parts and arena encoded exactly
// as the v7 layer payload [`serialize_into`] writes, so a reader walks them with the same code.
// Lane D (writer) calls this per slim layer; K≈1 layers (junction, earth, water) stay full v7 and
// never reach here.

/// Pack one 16-byte slim instance: `logical_id`, `view_bits`, `part_offset`, `part_count`, each
/// little-endian.
///
/// The single source of truth for the instance layout: `read`'s fail-watched tests build their
/// fixtures through this (rather than a parallel encoding) and assert the exact bytes, so emit and
/// parse cannot drift.
pub fn encode_slim_instance(
    logical_id: u32,
    view_bits: u32,
    part_offset: u32,
    part_count: u32,
) -> [u8; 16] {
    let mut out = [0u8; 16];
    out[0..4].copy_from_slice(&logical_id.to_le_bytes());
    out[4..8].copy_from_slice(&view_bits.to_le_bytes());
    out[8..12].copy_from_slice(&part_offset.to_le_bytes());
    out[12..16].copy_from_slice(&part_count.to_le_bytes());
    out
}

/// Assemble one slim layer payload: 16-byte instances, then the part table, then the varint coord
/// arena.
///
/// `refs` is one `(logical_id, view_bits, part_offset, part_count)` tuple per instance, in order.
/// Every range is bounded before anything is written — the same discipline [`serialize_into`]
/// applies: a caller that got a range wrong is refused here rather than emitting a payload that
/// resolves to another feature. Parts must tile the arena exactly as `serialize_into` requires.
pub fn assemble_slim_payload(
    refs: &[(u32, u32, u32, u32)],
    parts: &[Part],
    coords: &[(i16, i16)],
) -> Result<Vec<u8>> {
    for (i, &(_, _, part_offset, part_count)) in refs.iter().enumerate() {
        if part_count == 0 {
            return err(format!("slim instance {i} has no geometry"));
        }
        let end = (part_offset as usize).checked_add(part_count as usize).ok_or_else(|| {
            crate::proto::Error("a slim instance's parts overflow".to_string())
        })?;
        if end > parts.len() {
            return err(format!(
                "slim instance {i} indexes parts {part_offset}..{end} of {}",
                parts.len(),
            ));
        }
    }
    // The arena is written in parts-table order with no offsets of its own, so — exactly as
    // `serialize_into` requires — every part must start where the previous one ended and the parts
    // must cover the arena exactly.
    let mut at = 0u32;
    for part in parts {
        if part.coord_start != at {
            return err(format!(
                "slim parts are not contiguous: one starts at point {} where {at} was expected",
                part.coord_start,
            ));
        }
        at = at
            .checked_add(part.point_count)
            .ok_or_else(|| crate::proto::Error("a slim layer's parts overflow".to_string()))?;
    }
    if at as usize != coords.len() {
        return err(format!("slim parts cover {at} points but the arena holds {}", coords.len(),));
    }
    let mut out = Vec::with_capacity(
        refs.len() * 16 + parts.len() * PART_ENTRY_LEN + coords.len() * 3 + 4,
    );
    for &(logical_id, view_bits, part_offset, part_count) in refs {
        out.extend_from_slice(&encode_slim_instance(logical_id, view_bits, part_offset, part_count));
    }
    for part in parts {
        out.extend_from_slice(&part.coord_start.to_le_bytes());
        out.extend_from_slice(&part.point_count.to_le_bytes());
        out.extend_from_slice(&part.winding.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }
    while out.len() % 4 != 0 {
        out.push(0);
    }
    // The arena, in parts-table order with deltas restarting at each part — the same walk
    // `serialize_into` writes and `read` decodes. Contiguity above keeps every slice in bounds.
    for part in parts {
        let (mut px, mut py) = (0i32, 0i32);
        let start = part.coord_start as usize;
        for &(x, y) in &coords[start..start + part.point_count as usize] {
            push_uvarint(&mut out, crate::proto::zigzag_encode(x as i64 - px as i64));
            push_uvarint(&mut out, crate::proto::zigzag_encode(y as i64 - py as i64));
            (px, py) = (x as i32, y as i32);
        }
    }
    Ok(out)
}
