use super::codec::{check_body, decompress, find_entry};
use super::directory::parse_directory;
use super::hilbert::tile_id;
use super::types::{Entry, Header};
use crate::proto::{Result, err};

/// A whole archive held in memory.
///
/// Fine for the layers we build (tens of MB). The 1.5 GB published basemap is
/// composited by streaming instead — see `tile_join`.
pub struct Archive {
    pub header: Header,
    pub metadata: Vec<u8>,
    root: Vec<Entry>,
    leaves: Vec<u8>,
    tile_data: Vec<u8>,
}

impl Archive {
    pub fn parse(buf: &[u8]) -> Result<Archive> {
        let header = Header::parse(buf)?;
        let slice = |off: u64, len: u64, what: &str| -> Result<&[u8]> {
            let (o, l) = (off as usize, len as usize);
            match o.checked_add(l).filter(|e| *e <= buf.len()) {
                Some(end) => Ok(&buf[o..end]),
                None => err(format!("PMTiles {what} runs past end of file")),
            }
        };
        let root_raw = slice(header.root_offset, header.root_length, "root directory")?;
        let root = parse_directory(&decompress(header.internal_compression, root_raw)?)?;
        let metadata = decompress(
            header.internal_compression,
            slice(header.metadata_offset, header.metadata_length, "metadata")?,
        )?;
        let leaves = slice(header.leaf_offset, header.leaf_length, "leaf directories")?.to_vec();
        let tile_data =
            slice(header.tile_data_offset, header.tile_data_length, "tile data")?.to_vec();
        Ok(Archive { header, metadata, root, leaves, tile_data })
    }

    /// Raw (still `tile_compression`-compressed) bytes for a tile, or `None`.
    pub fn tile_raw(&self, z: u8, x: u64, y: u64) -> Result<Option<&[u8]>> {
        let want = tile_id(z, x, y);
        let Some(entry) = find_entry(&self.root, want).copied() else { return Ok(None) };
        if entry.run_length > 0 {
            return self.tile_bytes(&entry).map(Some);
        }
        // A leaf pointer. The spec allows arbitrary nesting; producers emit two
        // levels, so cap the walk rather than risk looping on a corrupt file.
        let mut entry = entry;
        for _ in 0..4 {
            let leaf = self.read_leaf(&entry)?;
            let Some(e) = find_entry(&leaf, want).copied() else { return Ok(None) };
            if e.run_length > 0 {
                return self.tile_bytes(&e).map(Some);
            }
            entry = e;
        }
        err("PMTiles directory nested deeper than 4 levels")
    }

    /// Tile-data bytes an entry addresses.
    fn tile_bytes(&self, e: &Entry) -> Result<&[u8]> {
        let (o, l) = (e.offset as usize, e.length as usize);
        match o.checked_add(l).filter(|v| *v <= self.tile_data.len()) {
            Some(end) => Ok(&self.tile_data[o..end]),
            None => err("PMTiles tile entry runs past tile data"),
        }
    }

    /// Parse the leaf directory a leaf-pointer entry addresses.
    fn read_leaf(&self, e: &Entry) -> Result<Vec<Entry>> {
        let (o, l) = (e.offset as usize, e.length as usize);
        let end = match o.checked_add(l).filter(|v| *v <= self.leaves.len()) {
            Some(v) => v,
            None => return err("PMTiles leaf entry runs past leaf section"),
        };
        parse_directory(&decompress(self.header.internal_compression, &self.leaves[o..end])?)
    }

    /// Decompressed tile bytes (an MVT body), or `None` when absent.
    pub fn tile(&self, z: u8, x: u64, y: u64) -> Result<Option<Vec<u8>>> {
        match self.tile_raw(z, x, y)? {
            None => Ok(None),
            Some(raw) => Ok(Some(decompress(self.header.tile_compression, raw)?)),
        }
    }

    /// Every `(tile_id, raw bytes)` in the archive, ascending. Runs are expanded,
    /// so a run of identical ocean tiles yields one item per tile id.
    /// Every `(tile_id, body offset, body length)` this archive holds, ascending.
    ///
    /// Unlike [`Archive::iter_tiles`] this hands back OFFSETS rather than slices, so a
    /// caller can hold the whole tile list of a 26-million-tile archive without also
    /// holding its 8 GB of bodies. Runs are expanded, because a run means several ids
    /// share one body and each id still needs its own row.
    ///
    /// The offset is `u64` and must stay that way. A single planet layer's data section
    /// is well past `u32::MAX` -- `maxspeed` alone is 8.3 GB -- so narrowing it silently
    /// wraps every offset beyond 4 GiB onto the wrong bytes, which surfaces as
    /// "not a gzip stream" halfway through a merge.
    pub fn tile_offsets(&self) -> Result<Vec<(u64, u64, u32)>> {
        let mut out = Vec::new();
        self.visit_entries(&mut |e| {
            for k in 0..e.run_length as u64 {
                out.push((e.tile_id + k, e.offset, e.length));
            }
            Ok(())
        })?;
        Ok(out)
    }

    /// Call `visit` once per tile entry, ascending by `tile_id`, runs unexpanded.
    ///
    /// The in-memory counterpart of [`ArchiveFile::visit_entries`], with the same
    /// contract, so a caller written against one works against the other.
    pub fn visit_entries(&self, visit: &mut dyn FnMut(&Entry) -> Result<()>) -> Result<()> {
        for e in &self.root {
            if e.run_length > 0 {
                check_body(&self.header, e)?;
                visit(e)?;
                continue;
            }
            let (o, l) = (e.offset as usize, e.length as usize);
            if o + l > self.leaves.len() {
                return err("PMTiles leaf entry runs past leaf section");
            }
            let leaf = parse_directory(&decompress(
                self.header.internal_compression,
                &self.leaves[o..o + l],
            )?)?;
            for le in &leaf {
                if le.run_length > 0 {
                    check_body(&self.header, le)?;
                    visit(le)?;
                }
            }
        }
        Ok(())
    }

    /// The still-compressed body at `offset..offset + length` in the data section.
    ///
    /// `offset` is `u64`: a planet layer's data section is far larger than `u32::MAX`.
    pub fn body_at(&self, offset: u64, length: u32) -> Result<&[u8]> {
        let end = offset
            .checked_add(length as u64)
            .filter(|e| *e <= self.tile_data.len() as u64);
        match end {
            Some(end) => Ok(&self.tile_data[offset as usize..end as usize]),
            None => err(format!(
                "PMTiles body at {offset}+{length} runs past the {}-byte tile data section",
                self.tile_data.len()
            )),
        }
    }

    pub fn iter_tiles(&self) -> Result<Vec<(u64, &[u8])>> {
        let mut out = Vec::new();
        for e in &self.root {
            if e.run_length > 0 {
                push_run(&mut out, e, &self.tile_data)?;
            } else {
                let (o, l) = (e.offset as usize, e.length as usize);
                if o + l > self.leaves.len() {
                    return err("PMTiles leaf entry runs past leaf section");
                }
                let leaf = parse_directory(&decompress(
                    self.header.internal_compression,
                    &self.leaves[o..o + l],
                )?)?;
                for le in &leaf {
                    if le.run_length > 0 {
                        push_run(&mut out, le, &self.tile_data)?;
                    }
                }
            }
        }
        Ok(out)
    }
}

fn push_run<'a>(out: &mut Vec<(u64, &'a [u8])>, e: &Entry, data: &'a [u8]) -> Result<()> {
    let (o, l) = (e.offset as usize, e.length as usize);
    if o + l > data.len() {
        return err("PMTiles tile entry runs past tile data");
    }
    let bytes = &data[o..o + l];
    for k in 0..e.run_length as u64 {
        out.push((e.tile_id + k, bytes));
    }
    Ok(())
}

