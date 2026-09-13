use super::consts::*;
use crate::proto::{err, Result};

/// One section-directory entry: where a payload lives and what count it carries.
///
/// `extra` is per-kind: for `GRAPH_META` it is unused (0 — the counts live in
/// the payload); for `GRAPH_NODES`/`GRAPH_ELEVATION` the node count `N`; for
/// `GRAPH_EDGES`/`GRAPH_INTERMEDIATE` the edge count `E`; for POI kinds the
/// record count; for `TRANSIT` the route count (0 when unknown). Validators
/// cross-check it against the payload; loaders may trust it only after
/// [`ArchiveView::parse`] succeeds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveEntry {
    pub kind: u8,
    pub flags: u32,
    pub offset: u64,
    pub len: u64,
    pub extra: u64,
}

impl ArchiveEntry {
    pub fn parse(buf: &[u8]) -> Result<ArchiveEntry> {
        if buf.len() < ARCHIVE_ENTRY_LEN {
            return err("a single-archive entry runs past its directory");
        }
        if buf[1] != 0 || buf[2] != 0 || buf[3] != 0 {
            return err("a single-archive entry has non-zero reserved bytes");
        }
        let kind = buf[0];
        if !matches!(
            kind,
            ARCHIVE_KIND_GRAPH_META
                | ARCHIVE_KIND_GRAPH_NODES
                | ARCHIVE_KIND_GRAPH_EDGES
                | ARCHIVE_KIND_GRAPH_INTERMEDIATE
                | ARCHIVE_KIND_GRAPH_NAMES
                | ARCHIVE_KIND_GRAPH_LANES
                | ARCHIVE_KIND_GRAPH_ELEVATION
                | ARCHIVE_KIND_POI_INDEX
                | ARCHIVE_KIND_POI_NAMES
                | ARCHIVE_KIND_POI_ATTRS
                | ARCHIVE_KIND_POI_SPATIAL
                | ARCHIVE_KIND_POI_WORDS
                | ARCHIVE_KIND_TRANSIT
        ) {
            return err(format!("a single-archive entry names unknown section kind {kind}"));
        }
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6],
                buf[o + 7],
            ])
        };
        let flags = u32_at(4);
        if flags != 0 {
            return err(format!("a single-archive entry sets unknown flags {flags:#010x}"));
        }
        Ok(ArchiveEntry {
            kind,
            flags,
            offset: u64_at(8),
            len: u64_at(16),
            extra: u64_at(24),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; ARCHIVE_ENTRY_LEN];
        out[0] = self.kind;
        out[4..8].copy_from_slice(&self.flags.to_le_bytes());
        out[8..16].copy_from_slice(&self.offset.to_le_bytes());
        out[16..24].copy_from_slice(&self.len.to_le_bytes());
        out[24..32].copy_from_slice(&self.extra.to_le_bytes());
        out
    }
}
