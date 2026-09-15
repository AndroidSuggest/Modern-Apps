use super::consts::ARCHIVE_ALIGN;
use super::entry::ArchiveEntry;
use super::footer::ArchiveFooter;

/// Round `n` up to `ARCHIVE_ALIGN`. Power-of-two align, same as graph.rs.
#[inline]
pub fn align_up(n: u64) -> u64 {
    (n + ARCHIVE_ALIGN - 1) & !(ARCHIVE_ALIGN - 1)
}

/// Where the sidecar starts: past the tile data.
///
/// Reads the header only, never the wire. Every archive is v7, so this is
/// always `data_offset + data_len`. The header's own checks (overlap, fit)
/// already ran in `Header::parse`.
pub fn sidecar_start(header: &crate::mamaps::header::Header) -> u64 {
    header.data_offset + header.data_len
}

/// Serialize a directory + footer for `entries` already laid out at 8-byte
/// aligned offsets with `build_id`.
///
/// Entries must ascend by kind with zero flags; the directory is `count` +
/// entries + zero padding to 8 bytes, and the footer names it. Used by the
/// packer (lane E) and by the tests; the tile writer itself is untouched.
pub fn serialize_dir(entries: &[ArchiveEntry], build_id: u64, dir_offset: u64) -> (Vec<u8>, ArchiveFooter) {
    let mut dir = Vec::new();
    dir.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for e in entries {
        dir.extend_from_slice(&e.serialize());
    }
    while dir.len() as u64 % ARCHIVE_ALIGN != 0 {
        dir.push(0);
    }
    let footer = ArchiveFooter {
        dir_offset,
        dir_len: dir.len() as u64,
        build_id,
    };
    (dir, footer)
}
