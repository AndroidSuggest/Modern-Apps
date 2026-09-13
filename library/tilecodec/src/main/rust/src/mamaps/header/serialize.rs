//! Header serialization: the v7 bytes, or the v8 bytes when a shared section is named.
//!
//! Pure moves out of the former single-file header module; nothing here changed.

use super::consts::{
    FLAG_LEAF_LEN_64, FORMAT_VERSION, FORMAT_VERSION_V8, HEADER_LEN, HEADER_LEN_V8, MAGIC,
};
use super::types::Header;

impl Header {
    /// The v7 bytes, or the v8 bytes when this names a shared section.
    ///
    /// Absent (`shared_len == 0`) serializes to the byte-identical 128-byte v7 header with
    /// version byte 7 — which is what keeps a shared-table-off build byte-identical v7.
    /// Present serializes to 160 bytes with version byte 8 and the tail at 128..160.
    pub fn serialize(&self) -> Vec<u8> {
        let v8 = self.shared_len != 0;
        if !v8 {
            debug_assert_eq!(self.shared_offset, 0, "a v7 header names no shared section");
            debug_assert_eq!(self.shared_flags, 0, "a v7 header sets no shared flags");
            debug_assert_eq!(self.shared_pools, 0, "a v7 header names no shared pools");
        }
        let wire_len = if v8 { HEADER_LEN_V8 } else { HEADER_LEN };
        let mut out = Vec::with_capacity(wire_len);
        out.extend_from_slice(MAGIC);
        out.push(if v8 { FORMAT_VERSION_V8 } else { FORMAT_VERSION });
        out.extend_from_slice(&(wire_len as u16).to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.push(self.compression);
        out.push(self.layer_count);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        out.extend_from_slice(&self.build_id.to_le_bytes());
        out.extend_from_slice(&self.file_len.to_le_bytes());
        out.extend_from_slice(&self.dict_offset.to_le_bytes());
        out.extend_from_slice(&self.dict_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_entry_capacity.to_le_bytes());
        out.extend_from_slice(&self.root_offset.to_le_bytes());
        out.extend_from_slice(&self.root_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_count.to_le_bytes());
        out.extend_from_slice(&self.leaf_offset.to_le_bytes());
        // 72..76 is always low 32 of leaf_len; 76..80 is high 32 when extended,
        // otherwise reserved zero. Common path (<4 GiB): high 32 == 0 → byte-identical to v1 (u32 + 0).
        out.extend_from_slice(&(self.leaf_len as u32).to_le_bytes());
        if (self.flags & FLAG_LEAF_LEN_64) != 0 {
            // Extended: 76..80 holds high 32 of the true u64 leaf_len (planet ~4.29 GB).
            out.extend_from_slice(&((self.leaf_len >> 32) as u32).to_le_bytes());
        } else {
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        out.extend_from_slice(&self.data_offset.to_le_bytes());
        out.extend_from_slice(&self.data_len.to_le_bytes());
        out.extend_from_slice(&self.tiles_addressed.to_le_bytes());
        out.extend_from_slice(&self.bodies_written.to_le_bytes());
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        if v8 {
            out.extend_from_slice(&self.shared_offset.to_le_bytes());
            out.extend_from_slice(&self.shared_len.to_le_bytes());
            out.extend_from_slice(&self.shared_flags.to_le_bytes());
            out.extend_from_slice(&self.shared_pools.to_le_bytes());
            out.extend_from_slice(&[0u8; 10]);
        }
        debug_assert_eq!(out.len(), wire_len);
        out
    }
}
