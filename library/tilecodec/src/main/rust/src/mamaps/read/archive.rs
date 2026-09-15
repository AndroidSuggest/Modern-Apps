use super::helpers::decompress;
use crate::mamaps::body::Body;
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::Header;
use crate::mamaps::index::{LeafEntry, RootEntry, self};
use crate::pmtiles::tile_id;
use crate::proto::{Error, Result, err};
use crate::stream::{OPEN_PREFIX_BYTES, RangeReader};

/// How many parsed leaves to keep. Sixteen: a viewport walks a contiguous stretch
/// of the tile curve, so a small MRU covers panning without re-fetching.
const MAX_CACHED_LEAVES: usize = 16;

/// An open archive.
pub struct MamapsArchive<R: RangeReader> {
    /// Reachable from the crate so the request-counting tests in [`super`] can assert on the
    /// number of round trips, which is the contract this whole layout exists to hold.
    pub(crate) reader: R,
    pub header: Header,
    pub dictionary: Dictionary,
    root: Vec<RootEntry>,
    /// `(leaf offset, entries)`, most-recently-used last.
    leaves: Vec<(u64, Vec<LeafEntry>)>,
}

impl<R: RangeReader> MamapsArchive<R> {
    /// The reader this archive was opened on.
    ///
    /// So a caller can act on something it only learns from the header — the `build_id`, which
    /// decides whether the reader's disk cache is still addressing this build.
    pub fn reader(&self) -> &R {
        &self.reader
    }
    /// One range request: the header, the dictionary and the root index all live in the prefix.
    ///
    /// A file whose prefix does not hold all three is refused rather than fetched again. The
    /// writer asserts the budget, so a file that misses it was not written by this codec, and
    /// silently paying an extra round trip per open is exactly the cost the layout exists to
    /// avoid.
    pub fn open(reader: R) -> Result<MamapsArchive<R>> {
        let prefix = reader.read(0, OPEN_PREFIX_BYTES)?;
        let header = Header::parse(&prefix)?;
        let section = |offset: u64, len: u64, what: &str| -> Result<&[u8]> {
            let end = offset + len;
            if end > prefix.len() as u64 {
                return err(format!(
                    "{what} ends at byte {end}, outside the {OPEN_PREFIX_BYTES} byte prefix a \
                     .mamaps open reads -- this archive was not written by this codec",
                ));
            }
            Ok(&prefix[offset as usize..end as usize])
        };
        let dictionary =
            Dictionary::parse(section(header.dict_offset, header.dict_len as u64, "the dictionary")?)?;
        dictionary.check_matches_schema()?;
        if dictionary.layers.len() != header.layer_count as usize {
            return err("a .mamaps header and dictionary disagree on the layer count");
        }
        let root =
            index::parse_root(section(header.root_offset, header.root_len as u64, "the root index")?)?;
        if root.len() != header.leaf_count as usize {
            return err(format!(
                "a .mamaps header declares {} leaves but its root has {} entries",
                header.leaf_count,
                root.len(),
            ));
        }
        Ok(MamapsArchive { reader, header, dictionary, root, leaves: Vec::new() })
    }

    /// The decoded body for a tile, or `None` when the archive does not hold it.
    ///
    /// `None` rather than an error: most of a coastal viewport is off the edge of coverage, and
    /// that is the ordinary answer rather than a fault.
    pub fn tile(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        let Some(bytes) = self.tile_bytes(z, x, y)? else { return Ok(None) };
        Ok(Some(Body::parse(&bytes)?))
    }

    /// The decompressed body bytes for a tile, undecoded.
    pub fn tile_bytes(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Vec<u8>>> {
        if z < self.header.min_zoom || z > self.header.max_zoom || z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }
        let want = tile_id(z, x as u64, y as u64);
        let Some(root) = index::find_leaf(&self.root, want).copied() else { return Ok(None) };
        // Ahead of the leaf's base is impossible (the root is ordered), so this only rules out a
        // tile past a `u32` of span, which the writer refuses to produce.
        let Ok(offset) = u32::try_from(want - root.base_tile_id) else { return Ok(None) };

        let leaf = self.leaf(&root)?;
        let Some(entry) = index::find_tile(&leaf, offset).copied() else { return Ok(None) };

        // Every bound from a field, checked before the read rather than after it.
        let at = root.base_data_offset + entry.offset_delta as u64;
        if at + entry.length as u64 > self.header.data_len {
            return err("a .mamaps leaf entry runs past the tile data section");
        }
        let stored = self.exact(self.header.data_offset + at, entry.length, "a tile body")?;
        Ok(Some(decompress(self.header.compression, &stored)?))
    }

    /// The parsed leaf a root entry addresses, from the cache or over the wire.
    fn leaf(&mut self, root: &RootEntry) -> Result<Vec<LeafEntry>> {
        if let Some(position) = self.leaves.iter().position(|(offset, _)| *offset == root.leaf_offset)
        {
            // Move to the back: most-recently-used last.
            let hit = self.leaves.remove(position);
            let entries = hit.1.clone();
            self.leaves.push(hit);
            return Ok(entries);
        }
        let len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        if root.leaf_offset + len > self.header.leaf_len as u64 {
            return err("a .mamaps root entry runs past the leaf section");
        }
        let length = u32::try_from(len)
            .map_err(|_| Error("a .mamaps leaf is larger than a single read".to_string()))?;
        let raw = self.exact(self.header.leaf_offset + root.leaf_offset, length, "a leaf")?;
        let entries = index::parse_leaf(&raw)?;
        if self.leaves.len() >= MAX_CACHED_LEAVES {
            self.leaves.remove(0);
        }
        self.leaves.push((root.leaf_offset, entries.clone()));
        Ok(entries)
    }

    /// A read that must come back at its full length.
    ///
    /// A short body means the range was not honoured. Decompressing it would fail somewhere far
    /// less informative, and caching it would poison that range for every later read.
    fn exact(&self, offset: u64, length: u32, what: &str) -> Result<Vec<u8>> {
        let body = self.reader.read(offset, length)?;
        if body.len() != length as usize {
            return err(format!(
                "a .mamaps read of {what} wanted {length} bytes at {offset}, got {}",
                body.len(),
            ));
        }
        Ok(body)
    }
}
