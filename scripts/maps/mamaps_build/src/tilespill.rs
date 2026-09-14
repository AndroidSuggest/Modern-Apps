//! On-disk scratch for one zoom's tile chunks: the missing half of an external sort.
//!
//! [`crate::tiler`]'s map phase produces one `BTreeMap<(tile, layer), BodyLayer>` per chunk and its
//! reduce k-way merges them. Those per-chunk maps already *are* the sorted runs of an external sort
//! -- produced free, as a side effect of clipping -- and `Merged` already *is* its merge phase. The
//! only piece missing was the disk in between, and holding it in memory instead is what put a
//! north-america z14 at 53 GB resident and would put a planet z14 near 244 GB.
//!
//! This module is that disk. A worker hands a finished chunk to [`ChunkSpill::write_chunk`] and gets
//! back a [`ChunkRef`]; the merge opens a [`ChunkReader`] per ref and walks them exactly as it
//! walked the maps. Peak becomes `O(threads) + O(READ_BUDGET)` rather than `O(extract)`.
//!
//! Modelled on `tile_build::spill`, and the conventions are that module's: manual little-endian
//! encode and decode, a zero-filled reserved tail so a field can be added without moving the ones
//! already there, a length that is a pure function of the header so a reader validates before it
//! allocates, counts cross-checked against the file's own length, and `Drop` cleanup so a run that
//! dies mid-planet cannot strand a hundred gigabytes.
//!
//! # The format
//!
//! One chunk is a plain sequential stream of entries in `BTreeMap::into_iter` order -- ascending
//! `(tile_id, layer_id)` -- with **no index**. The merge is strictly forward-only per stream, so an
//! index would be bytes nothing reads.
//!
//! ```text
//! 0..8    u64 tile_id
//! 8..12   u32 features
//! 12..16  u32 parts
//! 16..20  u32 coords
//! 20..21  u8  layer_id
//! 21..24  reserved, zero
//! 24..28  u32 names (label strings in this entry's table)
//! 28..32  u32 ids (feature ids in this entry's table: 0, or one per feature)
//! 32..    packed features (23 B), then parts (10 B), then coords (4 B), then ids (8 B),
//!          then names (u32 length + UTF-8 bytes each)
//! ```
//!
//! The id count took the second reserved word, which is what that word was for. Ids sit inside
//! the fixed arenas rather than after the names so [`payload_bytes`] stays a pure function of the
//! header — a reader still validates the whole fixed run before it allocates any of it.
//!
//! Packed field by field rather than cast wholesale from the in-memory struct. `body::Feature` is
//! `kind`, `kind_detail`, `geom_type`, `flags`, `parts_offset`, `part_count` -- fourteen bytes of
//! payload in a sixteen-byte struct with no `#[repr(C)]`, so a bulk cast would be both unsound and
//! dependent on the host's layout. Fourteen bytes is also less to write.
//!
//! Names ride per entry (not per chunk or per zoom) so the merge never holds more than one
//! entry's strings at a time: a tile's labels are dozens of strings, a zoom's are millions.
//!
//! # Reading with a budget, not a constant
//!
//! The merge holds one cursor per chunk: about 5,700 at a north-america z14 and about 27,000 at a
//! planet one. A fixed per-reader buffer is the wrong shape at both ends -- generous at 5,700, ruin
//! at 27,000. [`read_window_bytes`] divides a total [`READ_BUDGET`] by the stream count instead, so
//! the sum is a flat ceiling until the [`MIN_WINDOW`] floor takes over; the floor exists because a
//! read smaller than it costs more in syscalls than it saves in memory, and at the stream counts a
//! real build reaches the budget is still what binds.
//!
//! One pass, not a cascade. A cascading merge -- merge runs in groups, then merge the results -- is
//! available and order-preserving, and it is the escape hatch if the merge thread's decode turns out
//! to be the cost. It was not taken because it doubles I/O and doubles peak disk to buy memory that
//! [`read_window_bytes`] already bounds.
//!
//! # Random reads
//!
//! Thousands of interleaved forward cursors is a fine access pattern on NVMe and a poor one on
//! spinning media. [`READ_BUDGET`] is the dial: a larger window makes each stream's reads longer and
//! rarer at the cost of resident bytes.

use std::collections::BTreeMap;
use std::fs::File;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use tilecodec::mamaps::body::{
    BuildingAttrs, Carriageway, Feature as BodyFeature, Layer as BodyLayer, Part,
    CARRIAGEWAY_RECORD_LEN,
};
use tilecodec::proto::{err, Error, Result};

use crate::tiler::ChunkEntry;

/// Bytes of fixed header before one entry's arenas.
pub const ENTRY_HEADER_BYTES: usize = 32;

/// Packed width of one [`BodyFeature`] in the spill: kind, kind_detail, geom_type, flags,
/// name_idx, parts_offset, part_count, transit_color, transit_ordinal, transit_lanes,
/// transit_taper, lane_count. Twenty-four, exactly the body's 24-byte record: the fields are
/// the codec's own, so the scratch format never lags the codec by a version.
const FEATURE_BYTES: usize = 24;
/// Packed width of one [`Part`] in the spill: coord_start, point_count, winding. Ten — the
/// body's 12-byte entry carries a reserved half-word the scratch format does not need.
const PART_BYTES: usize = 10;
/// Packed width of one `(i16, i16)` coordinate.
const COORD_BYTES: usize = 4;
/// Packed width of one feature id. Fixed rather than varint for the same reason the body's id
/// table is: unsorted OSM ids have no ordering to delta against.
const ID_BYTES: usize = 8;

/// Packed width of one [`BuildingAttrs`] in the spill: height, min_height, roof_height (u16 each),
/// roof_shape, roof_direction, roof_orientation (u8 each), building_colour, roof_colour (u32
/// each). Seventeen — no alignment padding, unlike the body's 20-byte on-disk record.
const BUILDING_BYTES: usize = 17;

/// An entry longer than this is corruption, not a large tile layer. A tile-layer is capped at 65,535
/// features by the body format, so the largest plausible entry is orders of magnitude below this.
const MAX_ENTRY_BYTES: u64 = 1 << 30;

/// Encoded bytes a worker buffers before it writes. Large enough that a chunk of a few thousand
/// small entries is one or two writes, small enough that the buffer is noise next to the chunk map
/// it is draining.
const FLUSH_BYTES: usize = 1 << 20;

/// Bytes the whole merge may hold in read windows, divided across its streams.
pub const READ_BUDGET: usize = 256 << 20;
/// Smallest read window. Below this the syscall costs more than the memory saves.
pub const MIN_WINDOW: usize = 16 << 10;
/// Largest read window. A stream is forward-only, so beyond this a longer window is only read-ahead
/// the page cache would have done anyway.
pub const MAX_WINDOW: usize = 1 << 20;

/// How many bytes each of `streams` concurrent readers may buffer.
///
/// **Not observable in the output.** A reader yields the same entries in the same order whatever its
/// window is, which is what `the_archive_is_identical_however_the_read_window_is_sized` holds this
/// to; the window is a memory/syscall trade and nothing else.
pub fn read_window_bytes(streams: usize) -> usize {
    let forced = WINDOW_OVERRIDE.load(Ordering::Relaxed);
    if forced > 0 {
        return forced;
    }
    window_for(streams)
}

fn window_for(streams: usize) -> usize {
    (READ_BUDGET / streams.max(1)).clamp(MIN_WINDOW, MAX_WINDOW)
}

/// A window forced by a test, or zero for the budget. Zero rather than an `Option`, because this is
/// read on the merge's own path and a build never sets it.
static WINDOW_OVERRIDE: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);

/// Pin the read window, or release it with zero. Tests only: the window has to be forcible for the
/// claim that it is not observable to be worth anything.
#[cfg(test)]
pub fn set_read_window(bytes: usize) {
    WINDOW_OVERRIDE.store(bytes, Ordering::Relaxed);
}

/// Where one chunk's bytes live in a [`ChunkSpill`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ChunkRef {
    pub at: u64,
    pub len: u64,
    pub entries: u64,
}

/// One zoom's chunks, in one file.
///
/// `&self` throughout, so every worker writes through it and every reader reads through it -- the
/// same discipline as `tile_build::spill::NormalizedChunks`, and for the same reason: one handle,
/// many threads, positional I/O and no shared cursor.
pub struct ChunkSpill {
    /// `None` only while dropping, where the handle has to close before the file can be unlinked or
    /// Windows refuses the delete.
    file: Option<File>,
    path: PathBuf,
    /// The next free byte, and so also the total reserved. Held for one integer add per chunk and
    /// never across I/O: a worker must not queue behind another worker's write with a finished chunk
    /// in hand.
    at: Mutex<u64>,
    written: AtomicU64,
    written_entries: AtomicU64,
    read: AtomicU64,
    read_entries: AtomicU64,
}

impl ChunkSpill {
    /// Create the scratch file, truncating whatever a killed run left at the path.
    ///
    /// Read *and* write on one handle, because that is the whole shape of this module: the workers
    /// write positionally through it and the merge reads positionally through it, and a second
    /// handle would be a second file description with nothing to gain.
    pub fn create(path: impl Into<PathBuf>) -> Result<ChunkSpill> {
        let path = path.into();
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| Error(format!("cannot create {}: {e}", path.display())))?;
        Ok(ChunkSpill {
            file: Some(file),
            path,
            at: Mutex::new(0),
            written: AtomicU64::new(0),
            written_entries: AtomicU64::new(0),
            read: AtomicU64::new(0),
            read_entries: AtomicU64::new(0),
        })
    }

    fn file(&self) -> &File {
        self.file.as_ref().expect("the tile chunk spill outlives its readers and writers")
    }

    /// Write one finished chunk and say where it went.
    ///
    /// Takes the map **by value** and encodes out of `into_iter`, flushing every [`FLUSH_BYTES`], so
    /// the map shrinks as it is written instead of being copied whole into a buffer beside itself.
    ///
    /// The byte range is reserved under the cursor lock and written outside it. That ordering is the
    /// point: the lock covers an integer add, so a worker that has just finished a chunk is never
    /// waiting on another worker's disk.
    pub fn write_chunk(&self, map: BTreeMap<(u64, u8), ChunkEntry>) -> Result<ChunkRef> {
        let entries = map.len() as u64;
        let mut len = 0u64;
        for ((_, layer_id), entry) in &map {
            if *layer_id != entry.layer.layer_id {
                // The merge keys its heap on the map key and reads `layer_id` off the layer it
                // yields, so the two disagreeing would mean the round trip had to preserve both. It
                // is one field on disk because in this generator they are one field.
                return err(format!(
                    "a tile chunk entry is keyed on layer {layer_id} but carries layer {}",
                    entry.layer.layer_id
                ));
            }
            len += entry_bytes(entry)?;
        }

        let at = {
            let mut next = self.at.lock().expect("the tile chunk spill's cursor");
            let at = *next;
            *next += len;
            at
        };

        let mut buf: Vec<u8> = Vec::new();
        let mut cursor = at;
        for ((tile, layer_id), layer) in map {
            encode_entry(tile, layer_id, &layer, &mut buf);
            if buf.len() >= FLUSH_BYTES {
                cursor += self.flush(&mut buf, cursor)?;
            }
        }
        cursor += self.flush(&mut buf, cursor)?;

        if cursor != at + len {
            return err(format!(
                "a tile chunk reserved {len} byte(s) and wrote {}",
                cursor - at
            ));
        }
        self.written.fetch_add(len, Ordering::Relaxed);
        self.written_entries.fetch_add(entries, Ordering::Relaxed);
        Ok(ChunkRef { at, len, entries })
    }

    /// Write `buf` at `offset` and clear it. Returns what it wrote.
    fn flush(&self, buf: &mut Vec<u8>, offset: u64) -> Result<u64> {
        if buf.is_empty() {
            return Ok(0);
        }
        write_all_at(self.file(), buf, offset)
            .map_err(|e| Error(format!("writing {}: {e}", self.path.display())))?;
        let n = buf.len() as u64;
        buf.clear();
        Ok(n)
    }

    /// A forward cursor over one chunk, buffering `window` bytes at a time.
    pub fn reader(&self, chunk: &ChunkRef, window: usize) -> ChunkReader<'_> {
        ChunkReader {
            spill: self,
            at: chunk.at,
            end: chunk.at + chunk.len,
            left: chunk.entries,
            buf: Vec::new(),
            used: 0,
            window: window.max(ENTRY_HEADER_BYTES),
        }
    }

    /// Reserved, written, on disk and read back must all agree.
    ///
    /// `tile_build::spill::BucketSet::seal`'s triple entry, for the same reason: a chunk that
    /// quietly lost entries would produce an archive with holes in it and nothing downstream could
    /// tell. Called once the merge has drained every reader.
    pub fn check_books(&self) -> Result<()> {
        let reserved = *self.at.lock().expect("the tile chunk spill's cursor");
        let written = self.written.load(Ordering::Relaxed);
        if reserved != written {
            return err(format!(
                "{} reserved {reserved} byte(s) and wrote {written}",
                self.path.display()
            ));
        }
        let on_disk = std::fs::metadata(&self.path)
            .map_err(|e| Error(format!("cannot stat {}: {e}", self.path.display())))?
            .len();
        if on_disk != written {
            return err(format!(
                "{} is {on_disk} byte(s) on disk but {written} were written to it",
                self.path.display()
            ));
        }
        let read = self.read.load(Ordering::Relaxed);
        if read != written {
            return err(format!(
                "{} wrote {written} byte(s) and read back {read}",
                self.path.display()
            ));
        }
        let pushed = self.written_entries.load(Ordering::Relaxed);
        let pulled = self.read_entries.load(Ordering::Relaxed);
        if pushed != pulled {
            return err(format!(
                "{} wrote {pushed} entr(ies) and read back {pulled}",
                self.path.display()
            ));
        }
        Ok(())
    }
}

impl Drop for ChunkSpill {
    fn drop(&mut self) {
        // The handle first, or Windows refuses the delete.
        self.file = None;
        let _ = std::fs::remove_file(&self.path);
    }
}

/// A forward-only cursor over one chunk's byte range.
///
/// Reads through `&ChunkSpill`, so every cursor in a merge shares one file handle.
pub struct ChunkReader<'a> {
    spill: &'a ChunkSpill,
    /// Disk offset of the first byte not yet in `buf`.
    at: u64,
    /// One past the chunk's last byte.
    end: u64,
    /// Entries the header said were written and this cursor has not yielded.
    left: u64,
    buf: Vec<u8>,
    /// How much of `buf` has been yielded.
    used: usize,
    window: usize,
}

include!("tilespill_part1.rs");
include!("tilespill_part2.rs");
include!("tilespill_part3.rs");
include!("tilespill_part4.rs");