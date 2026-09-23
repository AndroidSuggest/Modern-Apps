//! A [`RangeReader`] over a local file: the pushed on-device `.mamaps` archive.
//!
//! When a full archive has been placed on the device — the app's external files dir —
//! the whole file is mapped read-only up front ([`memmap2::Mmap`]) and every range read
//! is served as a slice of that mapping. There is no network, no HTTP header fetch and
//! no range cache: the file *is* the source of truth, so none of the
//! republished-under-a-stable-name machinery the caching reader needs applies. When no
//! such file exists the worker keeps using the URL-backed
//! [`CachingRangeReader`](super::CachingRangeReader) unchanged.
//!
//! Mapping the whole file (rather than `read_at` per range) matters because the archive
//! is gigabytes: with plain positional reads every tile fetch pays syscalls plus a copy
//! through the page cache, and the workers re-read the same leaf directories constantly.
//! With the mapping the OS pages the file in and out on demand and repeated reads of the
//! same bytes cost no I/O at all.

use std::fs::File;
use std::path::{Path, PathBuf};
use tilecodec::proto::{err, Error, Result};
use tilecodec::stream::RangeReader;

/// An archive read straight from a whole-file read-only mapping.
///
/// Holds the mapping (plus the path for error messages). Reads never touch a file
/// descriptor — they slice the mapping — so sharing one reader across threads needs no
/// lock, and the mapping itself is `Send + Sync`.
pub struct FileRangeReader {
    mmap: Option<memmap2::Mmap>,
    path: PathBuf,
}

impl FileRangeReader {
    /// Map the whole file read-only up front, returning an error the worker can
    /// log if it cannot be opened or mapped. An empty file maps to no mapping
    /// rather than failing: `mmap` of length 0 fails on every platform, and an
    /// empty archive simply answers every range as past-the-end.
    pub fn open(path: impl Into<PathBuf>) -> Result<FileRangeReader> {
        let path = path.into();
        let file =
            File::open(&path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        let len = file
            .metadata()
            .map_err(|e| Error(format!("cannot stat {}: {e}", path.display())))?
            .len();
        let mmap = if len == 0 {
            None
        } else {
            // SAFETY: the archive lands on device via `.part`-then-rename
            // (`InitialDownloader`), so a mapped file is never mutated in place
            // afterwards — rename replaces the path, never the bytes under an
            // existing mapping. The mapping is read-only and kept alive in
            // `self` for as long as any slice of it can be read.
            let mapped = unsafe { memmap2::MmapOptions::new().map(&file) }
                .map_err(|e| Error(format!("cannot map {}: {e}", path.display())))?;
            Some(mapped)
        };
        Ok(FileRangeReader { mmap, path })
    }

    /// Whether `path` names an existing file, so the worker can decide up front whether
    /// to take the local path at all.
    pub fn exists(path: impl AsRef<Path>) -> bool {
        path.as_ref().is_file()
    }
}

impl RangeReader for FileRangeReader {
    /// Serve the range as a slice of the whole-file mapping: no syscalls, no copy
    /// through the page cache beyond what the OS already paged in. A range past the
    /// end comes back short rather than failing, as the trait's contract requires
    /// and as an HTTP server would answer it.
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let bytes: &[u8] = match self.mmap.as_ref() {
            Some(mmap) => mmap,
            None => return Ok(Vec::new()),
        };
        // Entirely past the end is empty, not an error — as the trait's
        // contract requires and as an HTTP server would answer it.
        let Ok(start) = usize::try_from(offset) else {
            return err(format!(
                "read offset {offset} out of range for {}",
                self.path.display()
            ));
        };
        if start >= bytes.len() {
            return Ok(Vec::new());
        }
        let Some(end) = start
            .checked_add(length as usize)
            .map(|e| e.min(bytes.len()))
        else {
            return err(format!(
                "read offset overflow reading {}",
                self.path.display()
            ));
        };
        let Some(slice) = bytes.get(start..end) else {
            return err(format!(
                "read range {offset}..{} out of range for {}",
                offset + u64::from(length),
                self.path.display()
            ));
        };
        Ok(slice.to_vec())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_file(name: &str, bytes: &[u8]) -> PathBuf {
        let path =
            std::env::temp_dir().join(format!("filerangereader-{name}-{}", std::process::id()));
        std::fs::write(&path, bytes).expect("write temp file");
        path
    }

    #[test]
    fn reads_an_exact_range() {
        let path = temp_file("exact", &[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]);
        let reader = FileRangeReader::open(&path).expect("open");
        assert_eq!(reader.read(2, 4).expect("read"), vec![2, 3, 4, 5]);
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_range_past_the_end_comes_back_short() {
        let path = temp_file("short", &[1, 2, 3, 4]);
        let reader = FileRangeReader::open(&path).expect("open");
        // Asking for eight bytes of a four-byte file returns the two that exist.
        assert_eq!(reader.read(2, 8).expect("read"), vec![3, 4]);
        // Entirely past the end is empty, not an error.
        assert!(reader.read(10, 4).expect("read").is_empty());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn a_zero_length_read_is_empty() {
        let path = temp_file("zero", &[1, 2, 3]);
        let reader = FileRangeReader::open(&path).expect("open");
        assert!(reader.read(0, 0).expect("read").is_empty());
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn exists_reports_presence() {
        let path = temp_file("exists", &[0]);
        assert!(FileRangeReader::exists(&path));
        let _ = std::fs::remove_file(&path);
        assert!(!FileRangeReader::exists(&path));
    }
}
