//! Dictionary serialization: three counted tables back to back, padded to 4 bytes.
//!
//! Pure moves out of the former single-file dict module; nothing here changed.

use super::types::Dictionary;

impl Dictionary {
    /// Three counted tables back to back, then zero padding to a 4-byte boundary.
    ///
    /// Each table is a `u16` count followed by that many `u8`-length-prefixed strings. Padding
    /// so the root index that follows starts aligned and a reader can slice it zero-copy.
    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(1024);
        for table in [&self.layers, &self.kinds, &self.details] {
            out.extend_from_slice(&(table.len() as u16).to_le_bytes());
            for name in table {
                out.push(name.len() as u8);
                out.extend_from_slice(name.as_bytes());
            }
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    }
}
