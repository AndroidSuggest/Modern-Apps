use super::codec::{check_body, decompress, read_exact_at, read_section};
use super::directory::parse_directory;
use super::types::{HEADER_LEN, Entry, Header};
use crate::proto::{Error, Result, err};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};

/// An archive read through a file handle, holding only its root directory.
///
/// [`Archive`] needs the whole file resident, which puts a second ceiling on every
/// planet job: `tile_join` read each input with `std::fs::read` and then `parse`
/// copied the leaf and data sections again, so a 17 GB merge needed roughly 34 GB
/// before the writer had allocated anything. This keeps the root directory and the
/// metadata — kilobytes — and reads one leaf directory or one tile body at a time into
/// a buffer it reuses.
///
/// Its entry walk takes `&mut self`, because reading a leaf directory reuses one
/// buffer. [`ArchiveFile::body_into`] does **not**: it reads at an explicit offset
/// without touching the handle's cursor, so any number of threads can pull bodies
/// from one archive at once. That is what lets `tile_join` merge in parallel.
///
/// `mmap` would also allow that and would avoid the copy, but it needs a dependency
/// and `unsafe`, and it turns a truncated file or an I/O error into a fault rather
/// than an `Err`. A positional read gets the same concurrency for neither price.
pub struct ArchiveFile {
    /// Declared first so the handle closes before [`Scratch`]-style cleanup or any
    /// caller's `remove_file` runs. Nothing depends on it today, but the ordering is
    /// free and Windows is unforgiving about open handles.
    file: File,
    path: PathBuf,
    pub header: Header,
    pub metadata: Vec<u8>,
    root: Vec<Entry>,
    /// Reused across leaf reads: the published basemap has 324 of them.
    leaf_buf: Vec<u8>,
}

impl ArchiveFile {
    /// Just the 127-byte header, for a caller that only wants to report on an archive.
    ///
    /// What `tile_join` prints its summary from. Re-reading a finished planet archive
    /// to recover its zoom range cost 8 GB of reads and an 8 GB allocation.
    pub fn read_header(path: impl AsRef<Path>) -> Result<Header> {
        let path = path.as_ref();
        let mut file = File::open(path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        let mut head = [0u8; HEADER_LEN];
        file.read_exact(&mut head)
            .map_err(|e| Error(format!("reading {}'s header: {e}", path.display())))?;
        Header::parse(&head)
    }

    pub fn open(path: impl Into<PathBuf>) -> Result<ArchiveFile> {
        let path = path.into();
        let mut file = File::open(&path)
            .map_err(|e| Error(format!("cannot read {}: {e}", path.display())))?;
        let file_len = file
            .metadata()
            .map_err(|e| Error(format!("cannot stat {}: {e}", path.display())))?
            .len();

        let mut head = [0u8; HEADER_LEN];
        file.read_exact(&mut head)
            .map_err(|e| Error(format!("reading {}'s header: {e}", path.display())))?;
        let header = Header::parse(&head)?;

        let root_raw = read_section(
            &mut file,
            file_len,
            header.root_offset,
            header.root_length,
            "root directory",
        )?;
        let root = parse_directory(&decompress(header.internal_compression, &root_raw)?)?;
        let metadata_raw = read_section(
            &mut file,
            file_len,
            header.metadata_offset,
            header.metadata_length,
            "metadata",
        )?;
        let metadata = decompress(header.internal_compression, &metadata_raw)?;
        // Checked once here so every later read can be bounds-checked against the
        // section length alone, exactly as `Archive` checks against its slice.
        for (off, len, what) in [
            (header.leaf_offset, header.leaf_length, "leaf directories"),
            (header.tile_data_offset, header.tile_data_length, "tile data"),
        ] {
            if off.checked_add(len).filter(|e| *e <= file_len).is_none() {
                return err(format!("PMTiles {what} runs past end of file"));
            }
        }

        Ok(ArchiveFile {
            file,
            path,
            header,
            metadata,
            root,
            leaf_buf: Vec::new(),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// Call `visit` once per tile entry in the archive, ascending by `tile_id`.
    ///
    /// Runs are **not** expanded: an entry covering `run_length` ids arrives once, and
    /// a caller that needs a row per id expands it itself. Leaf directories are read
    /// and inflated one at a time, so resident bytes are the root plus one leaf.
    pub fn visit_entries(
        &mut self,
        visit: &mut dyn FnMut(&Entry) -> Result<()>,
    ) -> Result<()> {
        // Indexed rather than iterated: reading a leaf needs `&mut self`, and `Entry`
        // is `Copy`, so a shared borrow of `root` would only be in the way.
        for i in 0..self.root.len() {
            let e = self.root[i];
            if e.run_length > 0 {
                check_body(&self.header, &e)?;
                visit(&e)?;
                continue;
            }
            let leaf = self.read_leaf(&e)?;
            for le in &leaf {
                if le.run_length > 0 {
                    check_body(&self.header, le)?;
                    visit(le)?;
                }
            }
        }
        Ok(())
    }

    fn read_leaf(&mut self, e: &Entry) -> Result<Vec<Entry>> {
        let len = e.length as u64;
        if e.offset
            .checked_add(len)
            .filter(|v| *v <= self.header.leaf_length)
            .is_none()
        {
            return err("PMTiles leaf entry runs past leaf section");
        }
        let at = self.header.leaf_offset + e.offset;
        self.leaf_buf.clear();
        self.leaf_buf.resize(len as usize, 0);
        self.file
            .seek(SeekFrom::Start(at))
            .map_err(|e| Error(format!("seeking {} to {at}: {e}", self.path.display())))?;
        self.file
            .read_exact(&mut self.leaf_buf)
            .map_err(|e| Error(format!("reading a leaf directory of {}: {e}", self.path.display())))?;
        parse_directory(&decompress(self.header.internal_compression, &self.leaf_buf)?)
    }

    /// Read the still-compressed body at `offset..offset + length` into `out`.
    ///
    /// `out` is a caller-owned buffer so a merge loop allocates once rather than once
    /// per tile. `offset` is `u64`: a planet layer's data section is far past
    /// `u32::MAX`.
    ///
    /// Takes `&self`: see [`read_exact_at`] for why, and [`ArchiveFile`] for what it
    /// buys.
    pub fn body_into(&self, offset: u64, length: u32, out: &mut Vec<u8>) -> Result<()> {
        if offset
            .checked_add(length as u64)
            .filter(|e| *e <= self.header.tile_data_length)
            .is_none()
        {
            return err(format!(
                "PMTiles body at {offset}+{length} runs past the {}-byte tile data section",
                self.header.tile_data_length
            ));
        }
        let at = self.header.tile_data_offset + offset;
        out.clear();
        out.resize(length as usize, 0);
        read_exact_at(&self.file, out, at)
            .map_err(|e| Error(format!("reading a tile body of {}: {e}", self.path.display())))
    }
}
