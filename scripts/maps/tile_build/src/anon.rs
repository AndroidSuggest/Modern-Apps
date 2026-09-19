//! Anonymous pagefile-backed scratch: virtual memory with no file behind it.
//!
//! The spill backends ([`crate::spill::NormalizedWriter`], `ChunkSpill`) stage
//! hundreds of gigabytes through temp files — `tiles.features.tmp` alone hits
//! ~300GB on a world build. Every one of those files is write-once then read
//! positionally (offsets, never paths), which is exactly what an anonymous
//! mapping serves: `memmap2::MmapMut::map_anon` reserves address space backed
//! by the pagefile, no directory entry, no cleanup on kill.
//!
//! On Windows this is `MapViewOfFile(INVALID_HANDLE_VALUE)`: commit charge
//! against RAM + pagefile, paged by the OS like any other memory. It does NOT
//! reduce the bytes — a 300GB spill still needs 300GB of commit — it removes
//! the *file*: no 6x-free-disk preflight, no stranded `.tmp` on kill, no
//! delete pass. The pagefile cap is the bound (check `Win32_PageFileSetting`;
//! system-managed is what a world build wants).
//!
//! Growth: mappings don't grow, so this reserves in 1GB segments and chains
//! them. Readers see one contiguous `&[u8]` via segment lookup — offsets stay
//! plain `u64`, identical to file offsets, so every call site keeps working.

use crate::proto::{err, Error, Result};

/// One mapping segment. 1GB: large enough that a 300GB spill is 300 segments
/// (a small table), small enough that the tail wastes at most 1GB of commit.
const SEGMENT_BYTES: usize = 1 << 30;

/// A growable anonymous byte store with file-like offsets.
///
/// Write-once, then positional reads: `push` appends, `finish` seals, and
/// `read_at` serves any range. Offsets are `u64` from zero, exactly like the
/// file offsets the spill index already records — swapping the backend changes
/// no index, no record format, no byte of output.
pub struct AnonStore {
    segments: Vec<memmap2::MmapMut>,
    /// Committed (written) length. Reads past this are corruption, same as a
    /// short read off the file.
    len: u64,
    sealed: bool,
}

impl AnonStore {
    pub fn new() -> AnonStore {
        AnonStore { segments: Vec::new(), len: 0, sealed: false }
    }

    /// Append `bytes`, returning the offset they start at.
    pub fn push(&mut self, bytes: &[u8]) -> Result<u64> {
        if self.sealed {
            return err("an anon store is sealed: no more writes".to_string());
        }
        let at = self.len;
        self.write_at(at, bytes)?;
        Ok(at)
    }

    /// Write `bytes` at `offset`, growing the store as needed. Workers reserve
    /// ranges concurrently and flush out of order, so `offset` may lie past
    /// the current length — the reservation cursor guarantees the ranges are
    /// back-to-back and `check_books` verifies reserved == written at the end.
    /// Bytes past the current length read as zero until written (same as a
    /// sparse file), but every reserved byte is written before the merge
    /// reads, so no reader ever sees the gap.
    pub fn write_at(&mut self, offset: u64, bytes: &[u8]) -> Result<()> {
        if self.sealed {
            return err("an anon store is sealed: no more writes".to_string());
        }
        let end = offset.checked_add(bytes.len() as u64).ok_or_else(|| {
            Error("an anon store write overflows".to_string())
        })?;
        let mut at = offset;
        let mut rest = bytes;
        while !rest.is_empty() {
            let seg_idx = (at as usize) / SEGMENT_BYTES;
            let seg_off = (at as usize) % SEGMENT_BYTES;
            while seg_idx >= self.segments.len() {
                let map = memmap2::MmapMut::map_anon(SEGMENT_BYTES)
                    .map_err(|e| Error(format!("cannot commit anon segment: {e}")))?;
                self.segments.push(map);
            }
            let room = SEGMENT_BYTES - seg_off;
            let take = room.min(rest.len());
            self.segments[seg_idx][seg_off..seg_off + take].copy_from_slice(&rest[..take]);
            at += take as u64;
            rest = &rest[take..];
        }
        self.len = self.len.max(end);
        Ok(())
    }

    /// Seal: no more writes. Reads are valid from here on.
    pub fn finish(&mut self) {
        self.sealed = true;
    }

    pub fn len(&self) -> u64 {
        self.len
    }

    pub fn is_empty(&self) -> bool {
        self.len == 0
    }

    /// Copy `out.len()` bytes from `offset` into `out`. Bounds-checked like a
    /// file read: past-`len` is an error, not a zero fill.
    pub fn read_at(&self, mut offset: u64, mut out: &mut [u8]) -> Result<()> {
        let end = offset.checked_add(out.len() as u64).ok_or_else(|| {
            Error("an anon store read overflows".to_string())
        })?;
        if end > self.len {
            return err(format!(
                "an anon store read of {} byte(s) at {offset} runs past its {len}",
                out.len(),
                len = self.len,
            ));
        }
        while !out.is_empty() {
            let seg_idx = (offset as usize) / SEGMENT_BYTES;
            let seg_off = (offset as usize) % SEGMENT_BYTES;
            let take = (SEGMENT_BYTES - seg_off).min(out.len());
            out[..take].copy_from_slice(&self.segments[seg_idx][seg_off..seg_off + take]);
            offset += take as u64;
            out = &mut out[take..];
        }
        Ok(())
    }

    /// A borrowed slice for `len` bytes at `offset`, when the range sits in
    /// one segment. Avoids the copy for hot paths; falls back to `read_at`
    /// into a scratch buffer when it straddles.
    pub fn slice_at(&self, offset: u64, len: usize) -> Result<&[u8]> {
        let end = (offset as usize).checked_add(len).ok_or_else(|| {
            Error("an anon store slice overflows".to_string())
        })?;
        if end as u64 > self.len {
            return err(format!(
                "an anon store slice of {len} byte(s) at {offset} runs past its {l}",
                l = self.len,
            ));
        }
        let seg_idx = (offset as usize) / SEGMENT_BYTES;
        let seg_off = (offset as usize) % SEGMENT_BYTES;
        if seg_off + len <= SEGMENT_BYTES {
            Ok(&self.segments[seg_idx][seg_off..seg_off + len])
        } else {
            err("an anon store slice straddles segments: use read_at".to_string())
        }
    }
}

impl Default for AnonStore {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_push_reads_back_at_its_own_offset() {
        let mut s = AnonStore::new();
        let a = s.push(b"hello").expect("push");
        let b = s.push(b"world!").expect("push");
        assert_eq!(a, 0);
        assert_eq!(b, 5);
        s.finish();
        let mut out = [0u8; 5];
        s.read_at(0, &mut out).expect("read");
        assert_eq!(&out, b"hello");
        let mut out2 = [0u8; 6];
        s.read_at(5, &mut out2).expect("read");
        assert_eq!(&out2, b"world!");
    }

    #[test]
    fn a_read_past_the_end_is_an_error_not_a_zero_fill() {
        let mut s = AnonStore::new();
        s.push(b"hi").expect("push");
        s.finish();
        let mut out = [0u8; 3];
        assert!(s.read_at(0, &mut out).is_err());
        assert!(s.read_at(2, &mut out[..1]).is_err());
    }

    #[test]
    fn a_sealed_store_refuses_writes() {
        let mut s = AnonStore::new();
        s.finish();
        assert!(s.push(b"x").is_err());
    }

    #[test]
    fn a_slice_within_one_segment_borrows() {
        let mut s = AnonStore::new();
        s.push(b"abcdef").expect("push");
        s.finish();
        assert_eq!(s.slice_at(1, 3).expect("slice"), b"bcd");
    }
}
