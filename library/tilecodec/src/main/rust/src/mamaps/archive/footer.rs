use super::consts::{ARCHIVE_FOOTER_LEN, ARCHIVE_MAGIC};
use crate::proto::{err, Result};

/// The 32-byte footer: magic, directory location, unified build id.
///
/// Byte map, all little-endian: `0..8` magic `MAMA8\0\0\0`, `8..16`
/// `dir_offset`, `16..24` `dir_len`, `24..32` `build_id`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveFooter {
    pub dir_offset: u64,
    pub dir_len: u64,
    pub build_id: u64,
}

impl ArchiveFooter {
    pub fn parse(buf: &[u8]) -> Result<ArchiveFooter> {
        if buf.len() < ARCHIVE_FOOTER_LEN {
            return err(format!(
                "a single-archive footer is {ARCHIVE_FOOTER_LEN} bytes, got {}",
                buf.len()
            ));
        }
        if &buf[0..8] != ARCHIVE_MAGIC {
            return err("not a single-archive footer (bad MAMA8 magic)");
        }
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6],
                buf[o + 7],
            ])
        };
        Ok(ArchiveFooter {
            dir_offset: u64_at(8),
            dir_len: u64_at(16),
            build_id: u64_at(24),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(ARCHIVE_FOOTER_LEN);
        out.extend_from_slice(ARCHIVE_MAGIC);
        out.extend_from_slice(&self.dir_offset.to_le_bytes());
        out.extend_from_slice(&self.dir_len.to_le_bytes());
        out.extend_from_slice(&self.build_id.to_le_bytes());
        debug_assert_eq!(out.len(), ARCHIVE_FOOTER_LEN);
        out
    }
}
