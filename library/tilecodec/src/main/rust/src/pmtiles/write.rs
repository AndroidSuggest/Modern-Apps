use super::codec::compress;
use super::directory::serialize_directory;
use super::hilbert::tile_id;
use super::types::{COMPRESSION_GZIP, HEADER_LEN, TILE_TYPE_MVT, Entry, Header, MAX_ROOT_BYTES};
use crate::gz;
use crate::proto::{Error, Result, err};
use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};

/// Accumulates tiles and serializes a whole v3 archive.
pub struct Builder {
    /// `tile_id -> already tile-compressed bytes`.
    tiles: Vec<(u64, Vec<u8>)>,
    pub metadata: Vec<u8>,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl Default for Builder {
    fn default() -> Self {
        Builder::new()
    }
}

impl Builder {
    pub fn new() -> Builder {
        Builder {
            tiles: Vec::new(),
            metadata: b"{}".to_vec(),
            min_zoom: 0,
            max_zoom: 0,
            // Whole Mercator world, as the published archive uses.
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_290,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_290,
            center_zoom: 0,
            center_lon_e7: 0,
            center_lat_e7: 0,
        }
    }

    /// Add a tile from its uncompressed MVT body.
    pub fn add_tile(&mut self, z: u8, x: u64, y: u64, mvt: &[u8]) {
        self.add_tile_raw(tile_id(z, x, y), gz::compress(mvt));
    }

    /// Add a tile that is already gzipped, e.g. copied straight out of another
    /// archive without a decode/re-encode cycle.
    pub fn add_tile_raw(&mut self, tile_id: u64, compressed: Vec<u8>) {
        self.tiles.push((tile_id, compressed));
    }

    pub fn is_empty(&self) -> bool {
        self.tiles.is_empty()
    }

    pub fn len(&self) -> usize {
        self.tiles.len()
    }

    /// Serialize the archive.
    pub fn build(mut self) -> Result<Vec<u8>> {
        self.tiles.sort_by_key(|(id, _)| *id);
        self.tiles.dedup_by_key(|(id, _)| *id);

        // Deduplicate identical tile bodies: a basemap is mostly repeated ocean and
        // empty land, and this is what makes an archive of 1.5 M tiles hold ~1 M
        // distinct bodies.
        let mut blob = Vec::new();
        let mut seen: HashMap<Vec<u8>, (u64, u32)> = HashMap::new();
        let mut entries: Vec<Entry> = Vec::with_capacity(self.tiles.len());
        let mut addressed = 0u64;
        for (id, data) in &self.tiles {
            addressed += 1;
            let (offset, length) = match seen.get(data) {
                Some(&v) => v,
                None => {
                    let v = (blob.len() as u64, data.len() as u32);
                    blob.extend_from_slice(data);
                    seen.insert(data.clone(), v);
                    v
                }
            };
            // Extend the previous entry's run when this tile is the next id and
            // resolves to the same bytes.
            if let Some(prev) = entries.last_mut() {
                if prev.offset == offset
                    && prev.length == length
                    && prev.tile_id + prev.run_length as u64 == *id
                {
                    prev.run_length += 1;
                    continue;
                }
            }
            entries.push(Entry { tile_id: *id, offset, length, run_length: 1 });
        }
        let tile_contents = seen.len() as u64;
        let tile_entries = entries.len() as u64;

        let (root_body, leaf_body) = self.split_directories(&entries)?;
        let root = compress(COMPRESSION_GZIP, &root_body)?;
        let metadata = compress(COMPRESSION_GZIP, &self.metadata)?;

        let root_offset = HEADER_LEN as u64;
        let metadata_offset = root_offset + root.len() as u64;
        let leaf_offset = metadata_offset + metadata.len() as u64;
        let tile_data_offset = leaf_offset + leaf_body.len() as u64;

        let header = Header {
            root_offset,
            root_length: root.len() as u64,
            metadata_offset,
            metadata_length: metadata.len() as u64,
            leaf_offset,
            leaf_length: leaf_body.len() as u64,
            tile_data_offset,
            tile_data_length: blob.len() as u64,
            addressed_tiles: addressed,
            tile_entries,
            tile_contents,
            // Entries are written in ascending tile_id and their offsets ascend with
            // them, which is exactly what `clustered` promises a reader.
            clustered: true,
            internal_compression: COMPRESSION_GZIP,
            tile_compression: COMPRESSION_GZIP,
            tile_type: TILE_TYPE_MVT,
            min_zoom: self.min_zoom,
            max_zoom: self.max_zoom,
            min_lon_e7: self.min_lon_e7,
            min_lat_e7: self.min_lat_e7,
            max_lon_e7: self.max_lon_e7,
            max_lat_e7: self.max_lat_e7,
            center_zoom: self.center_zoom,
            center_lon_e7: self.center_lon_e7,
            center_lat_e7: self.center_lat_e7,
        };

        let mut out = Vec::with_capacity(
            HEADER_LEN + root.len() + metadata.len() + leaf_body.len() + blob.len(),
        );
        out.extend_from_slice(&header.serialize());
        out.extend_from_slice(&root);
        out.extend_from_slice(&metadata);
        out.extend_from_slice(&leaf_body);
        out.extend_from_slice(&blob);
        Ok(out)
    }
}

/// The scratch file's lifetime, so a run that dies mid-planet cannot strand tens of
/// gigabytes. Follows `osm_ingest::chains::Spill`.
///
/// A field of its own rather than `impl Drop for StreamBuilder`, because a struct's
/// fields drop in declaration order and the removal must happen **after** the write
/// handle closes: Windows refuses to delete a file something still holds open. So
/// `data` is declared first and this second.
struct Scratch(PathBuf);

impl Drop for Scratch {
    fn drop(&mut self) {
        // Best effort: a leftover scratch is bad, but failing a finished build over an
        // undeletable temporary would be worse.
        let _ = std::fs::remove_file(&self.0);
    }
}

/// A [`Builder`] that never holds the archive in memory.
///
/// [`Builder`] is fine for a single layer but cannot assemble a planet-scale join: it
/// keeps every body in `tiles`, a deduplicated copy in `blob`, a third copy as the keys
/// of its `seen` map, and then serialises a fourth into one `Vec<u8>`. Measured on the
/// real overlay merge — 36.8 M tiles, 17 GB of input — that is about 76 GB and it is
/// killed long before it finishes.
///
/// This writes each body straight to a scratch file as it arrives and keeps only the
/// directory in memory (24 bytes per entry), so peak memory is set by the tile COUNT
/// rather than the tile BYTES. The scratch file is needed because PMTiles puts its
/// directories before its data and their size is not known until the last tile is in.
///
/// Bodies are still deduplicated, which matters — an overlay archive is mostly repeated
/// empty tiles. The map is keyed by a 64-bit hash and a length, and a hit is CONFIRMED
/// by re-reading the candidate body out of the scratch file and comparing it. Trusting
/// a hash alone would silently serve one tile's bytes for another about once every
/// 27,000 planet builds, which is far too often for a corruption that nothing
/// downstream can detect.
///
/// Tiles must arrive in ascending `tile_id`, which is what lets runs be coalesced and
/// what `clustered` promises a reader. [`StreamBuilder::add_tile_raw`] enforces it
/// rather than trusting the caller.
pub struct StreamBuilder {
    data: BufWriter<File>,
    scratch: Scratch,
    data_len: u64,
    /// How much of `data` has actually reached the file. Lets a dedup lookup skip the
    /// flush unless the body it wants is still sitting in the write buffer.
    flushed: u64,
    entries: Vec<Entry>,
    /// `(hash, length) -> the offsets already written with that hash`. Several offsets
    /// per key only when a hash collides, which is why it is a list.
    seen: HashMap<(u64, u32), Vec<u64>>,
    addressed: u64,
    distinct: u64,
    last_id: Option<u64>,
    pub metadata: Vec<u8>,
    pub min_zoom: u8,
    pub max_zoom: u8,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    pub center_zoom: u8,
    pub center_lon_e7: i32,
    pub center_lat_e7: i32,
}

impl StreamBuilder {
    /// `scratch` is created, written through, and removed by [`Self::finish`]. Put it
    /// on the same filesystem as the output: the last step copies it there.
    pub fn new(scratch: impl Into<PathBuf>) -> Result<StreamBuilder> {
        let scratch_path = scratch.into();
        // Read AND write: dedup confirms a hash hit by reading the candidate body back,
        // and `File::create` alone gives a write-only handle that then fails on Windows.
        let file = std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&scratch_path)
            .map_err(|e| Error(format!("cannot create {}: {e}", scratch_path.display())))?;
        Ok(StreamBuilder {
            data: BufWriter::with_capacity(1 << 20, file),
            scratch: Scratch(scratch_path),
            data_len: 0,
            flushed: 0,
            entries: Vec::new(),
            seen: HashMap::new(),
            addressed: 0,
            distinct: 0,
            last_id: None,
            metadata: Vec::new(),
            min_zoom: 0,
            max_zoom: 0,
            min_lon_e7: -1_800_000_000,
            min_lat_e7: -850_511_287,
            max_lon_e7: 1_800_000_000,
            max_lat_e7: 850_511_287,
            center_zoom: 0,
            center_lon_e7: 0,
            center_lat_e7: 0,
        })
    }

    /// Append one already-compressed tile. Ids must ascend.
    pub fn add_tile_raw(&mut self, tile_id: u64, compressed: &[u8]) -> Result<()> {
        if let Some(prev) = self.last_id {
            if tile_id <= prev {
                return err(format!(
                    "StreamBuilder needs ascending tile ids, got {tile_id} after {prev}"
                ));
            }
        }
        self.last_id = Some(tile_id);
        self.addressed += 1;

        let key = (hash64(compressed), compressed.len() as u32);
        // Field borrows taken separately: `seen` is read while `data` is written, which
        // is why the comparison is a free function rather than a method on self.
        let mut found: Option<u64> = None;
        if let Some(offsets) = self.seen.get(&key) {
            for &off in offsets {
                if body_matches(&mut self.data, &mut self.flushed, off, compressed)? {
                    found = Some(off);
                    break;
                }
            }
        }
        let offset = match found {
            Some(off) => off,
            None => {
                let off = self.data_len;
                self.data
                    .write_all(compressed)
                    .map_err(|e| Error(format!("writing tile data: {e}")))?;
                self.data_len += compressed.len() as u64;
                self.distinct += 1;
                self.seen.entry(key).or_default().push(off);
                off
            }
        };

        let length = compressed.len() as u32;
        if let Some(prev) = self.entries.last_mut() {
            if prev.offset == offset
                && prev.length == length
                && prev.tile_id + prev.run_length as u64 == tile_id
            {
                prev.run_length += 1;
                return Ok(());
            }
        }
        self.entries.push(Entry { tile_id, offset, length, run_length: 1 });
        Ok(())
    }

    /// Assemble the archive at `out`, then remove the scratch file.
    pub fn finish(mut self, out: impl AsRef<Path>) -> Result<()> {
        self.data
            .flush()
            .map_err(|e| Error(format!("flushing tile data: {e}")))?;
        self.flushed = self.data_len;

        let (root_body, leaf_body) = split_entries(&self.entries)?;
        let root = compress(COMPRESSION_GZIP, &root_body)?;
        let metadata = compress(COMPRESSION_GZIP, &self.metadata)?;
        let root_offset = HEADER_LEN as u64;
        let metadata_offset = root_offset + root.len() as u64;
        let leaf_offset = metadata_offset + metadata.len() as u64;
        let tile_data_offset = leaf_offset + leaf_body.len() as u64;
        let header = Header {
            root_offset,
            root_length: root.len() as u64,
            metadata_offset,
            metadata_length: metadata.len() as u64,
            leaf_offset,
            leaf_length: leaf_body.len() as u64,
            tile_data_offset,
            tile_data_length: self.data_len,
            addressed_tiles: self.addressed,
            tile_entries: self.entries.len() as u64,
            tile_contents: self.distinct,
            clustered: true,
            internal_compression: COMPRESSION_GZIP,
            tile_compression: COMPRESSION_GZIP,
            tile_type: TILE_TYPE_MVT,
            min_zoom: self.min_zoom,
            max_zoom: self.max_zoom,
            min_lon_e7: self.min_lon_e7,
            min_lat_e7: self.min_lat_e7,
            max_lon_e7: self.max_lon_e7,
            max_lat_e7: self.max_lat_e7,
            center_zoom: self.center_zoom,
            center_lon_e7: self.center_lon_e7,
            center_lat_e7: self.center_lat_e7,
        };

        let out = out.as_ref();
        let mut w = BufWriter::with_capacity(
            1 << 20,
            File::create(out).map_err(|e| Error(format!("cannot create {}: {e}", out.display())))?,
        );
        let mut put = |bytes: &[u8]| -> Result<()> {
            w.write_all(bytes)
                .map_err(|e| Error(format!("writing {}: {e}", out.display())))
        };
        put(&header.serialize())?;
        put(&root)?;
        put(&metadata)?;
        put(&leaf_body)?;

        // Stream the data section across rather than loading it: it is the whole point
        // of the scratch file. Read through `get_mut` rather than `into_inner`, so no
        // field is moved out and `Scratch` stays in charge of the cleanup.
        let src = self.data.get_mut();
        src.seek(SeekFrom::Start(0))
            .map_err(|e| Error(format!("rewinding scratch: {e}")))?;
        let mut buf = vec![0u8; 1 << 20];
        let mut copied = 0u64;
        loop {
            let n = src
                .read(&mut buf)
                .map_err(|e| Error(format!("reading scratch: {e}")))?;
            if n == 0 {
                break;
            }
            w.write_all(&buf[..n])
                .map_err(|e| Error(format!("writing {}: {e}", out.display())))?;
            copied += n as u64;
        }
        if copied != self.data_len {
            return err(format!(
                "scratch {} held {copied} tile byte(s), expected {}",
                self.scratch.0.display(),
                self.data_len
            ));
        }
        w.flush()
            .map_err(|e| Error(format!("flushing {}: {e}", out.display())))?;
        // `self` — and with it `Scratch` — drops here, which is what removes the
        // scratch file.
        Ok(())
    }
}

/// FNV-1a. Only ever a bucket key - every hit is confirmed by comparing bytes - so it
/// needs to be fast and well spread, not collision-proof.
fn hash64(data: &[u8]) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Read a body back out of the scratch file and compare it, so a hash hit is a fact
/// rather than a probability.
///
/// Flushes only when the candidate is still in the write buffer. Duplicates point at
/// much earlier offsets in practice, so the 1 MiB buffer keeps working and the read is
/// served by the page cache.
fn body_matches(
    data: &mut BufWriter<File>,
    flushed: &mut u64,
    offset: u64,
    want: &[u8],
) -> Result<bool> {
    let end = offset + want.len() as u64;
    if end > *flushed {
        data.flush()
            .map_err(|e| Error(format!("flushing tile data: {e}")))?;
        let at = data
            .get_mut()
            .stream_position()
            .map_err(|e| Error(format!("reading scratch position: {e}")))?;
        *flushed = at;
    }
    let file = data.get_mut();
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| Error(format!("seeking scratch to {offset}: {e}")))?;
    let mut buf = vec![0u8; want.len()];
    let read = file.read_exact(&mut buf);
    // Restore the append position before propagating anything, or the next write lands
    // in the middle of the data section.
    file.seek(SeekFrom::End(0))
        .map_err(|e| Error(format!("restoring scratch position: {e}")))?;
    read.map_err(|e| Error(format!("re-reading scratch at {offset}: {e}")))?;
    Ok(buf == want)
}

impl Builder {
    fn split_directories(&self, entries: &[Entry]) -> Result<(Vec<u8>, Vec<u8>)> {
        split_entries(entries)
    }
}

/// Fit the entries into a root directory small enough for a one-request open,
/// spilling into leaf directories when they do not fit.
///
/// Free-standing because both writers need it and neither owns the entries.
fn split_entries(entries: &[Entry]) -> Result<(Vec<u8>, Vec<u8>)> {
        let flat = serialize_directory(entries);
        if flat.len() <= MAX_ROOT_BYTES {
            return Ok((flat, Vec::new()));
        }
        // Grow the leaf size until one entry per leaf fits in the root. Starting at
        // 4096 matches what the published archive uses.
        let mut leaf_size = 4096usize;
        for _ in 0..12 {
            let mut root: Vec<Entry> = Vec::new();
            let mut leaves: Vec<u8> = Vec::new();
            for chunk in entries.chunks(leaf_size) {
                let body = compress(COMPRESSION_GZIP, &serialize_directory(chunk))?;
                root.push(Entry {
                    tile_id: chunk[0].tile_id,
                    offset: leaves.len() as u64,
                    length: body.len() as u32,
                    run_length: 0,
                });
                leaves.extend_from_slice(&body);
            }
            let root_body = serialize_directory(&root);
            if root_body.len() <= MAX_ROOT_BYTES {
                return Ok((root_body, leaves));
            }
            leaf_size *= 2;
        }
        err("cannot fit a PMTiles root directory even with very large leaves")
}
