//! A [`RangeReader`] over a local file: the pushed on-device `.mamaps` archive.
//!
//! When a full archive has been placed on the device — the app's external files dir —
//! the reader opens it directly and serves byte ranges straight from disk. There is no
//! network, no HTTP header fetch and no range cache: the file *is* the source of truth,
//! so none of the republished-under-a-stable-name machinery the caching reader needs
//! applies. When no such file exists the worker keeps using the URL-backed
//! [`CachingRangeReader`](super::CachingRangeReader) unchanged.

use std::fs::File;
use std::path::{Path, PathBuf};
use tilecodec::proto::{err, Error, Result};
use tilecodec::stream::RangeReader;

/// An archive read straight from a local file handle.
///
/// Holds only the open handle and its path. Every [`read`](RangeReader::read) is a
/// positional read that never moves the handle's cursor, so a single reader is safe to
/// use across reads without a lock — mirroring the positional-read strategy the codec's
/// own file reader uses.
pub struct FileRangeReader {
    file: File,
    path: PathBuf,
}

impl FileRangeReader {
    /// Open `path` for reading, returning an error the worker can log if it cannot be
    /// opened.
    pub fn open(path: impl Into<PathBuf>) -> Result<FileRangeReader> {
        let path = path.into();
        let file =
            File::open(&path).map_err(|e| Error(format!("cannot open {}: {e}", path.display())))?;
        Ok(FileRangeReader { file, path })
    }

    /// Whether `path` names an existing file, so the worker can decide up front whether
    /// to take the local path at all.
    pub fn exists(path: impl AsRef<Path>) -> bool {
        path.as_ref().is_file()
    }
}

impl RangeReader for FileRangeReader {
    fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let mut buf = vec![0u8; length as usize];
        let mut filled = 0usize;
        while filled < buf.len() {
            let Some(dst) = buf.get_mut(filled..) else {
                break;
            };
            let Some(at) = offset.checked_add(filled as u64) else {
                return err(format!(
                    "read offset overflow reading {}",
                    self.path.display()
                ));
            };
            #[cfg(windows)]
            let read = std::os::windows::fs::FileExt::seek_read(&self.file, dst, at);
            #[cfg(unix)]
            let read = std::os::unix::fs::FileExt::read_at(&self.file, dst, at);
            let n =
                read.map_err(|e| Error(format!("reading {} at {at}: {e}", self.path.display())))?;
            // A range past the end of the file comes back short rather than failing, as
            // the trait's contract requires and as an HTTP server would answer it.
            if n == 0 {
                break;
            }
            filled += n;
        }
        buf.truncate(filled);
        Ok(buf)
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
