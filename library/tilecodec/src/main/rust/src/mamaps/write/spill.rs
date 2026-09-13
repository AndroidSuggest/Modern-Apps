use crate::proto::Result;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const BUFFER_BYTES: usize = 1 << 20;

/// The data section of an archive being written: a scratch file with its tail held in memory.
///
/// Two callers want different things from it. Appending wants a buffer, so that a body is not a
/// syscall. Confirming a content-dedup candidate wants random access to any *earlier* body — a seek
/// and a read for anything already on disk, but a slice compare for anything still in the buffer,
/// and the bodies most recently written are the ones a repeat is likeliest to match.
pub(crate) struct Spill {
    pub(crate) path: PathBuf,
    /// Opened for reading as well as writing, because a content-dedup compare reads back a body
    /// this same handle wrote.
    pub(crate) file: File,
    /// The tail of the section, not yet in `file`.
    pub(crate) buffer: Vec<u8>,
    /// Bytes that *are* in `file`, which is also the offset `buffer` begins at.
    pub(crate) flushed: u64,
    /// Reused by [`Self::matches_at`], so a compare against the file allocates nothing.
    pub(crate) scratch: Vec<u8>,
    // TEMPORARY instrumentation, to measure how often a content-dedup confirmation has to read.
    pub(crate) confirms: u64,
    pub(crate) confirms_from_file: u64,
    pub(crate) bytes_from_file: u64,
}

impl Spill {
    pub(crate) fn create(dir: Option<&Path>) -> Result<Spill> {
        /// Two writers in one process — which the test suite has, and a planet build may — must not
        /// share a scratch file.
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = dir.map(Path::to_path_buf).unwrap_or_else(std::env::temp_dir);
        let path = dir.join(format!(
            "mamaps_bodies_{}_{}.tmp",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        // Truncating rather than `create_new`: a run that died left its scratch behind, and once a
        // pid is recycled, refusing to build over an abandoned file would be a worse failure than
        // overwriting it.
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(&path)
            .map_err(|e| scratch_error(&path, "create", e))?;
        Ok(Spill {
            path,
            file,
            buffer: Vec::with_capacity(BUFFER_BYTES),
            flushed: 0,
            scratch: Vec::new(),
            confirms: 0,
            confirms_from_file: 0,
            bytes_from_file: 0,
        })
    }

    /// Bytes appended so far, buffered or not — which is the offset the next body will land at.
    pub(crate) fn len(&self) -> u64 {
        self.flushed + self.buffer.len() as u64
    }

    pub(crate) fn append(&mut self, bytes: &[u8]) -> Result<()> {
        if self.buffer.len() + bytes.len() > BUFFER_BYTES {
            self.flush()?;
        }
        if bytes.len() >= BUFFER_BYTES {
            // A body bigger than the buffer goes straight through, because buffering it would only
            // copy it twice on the way to the same place.
            let Spill { file, path, flushed, .. } = self;
            write_all_at(file, path, *flushed, bytes)?;
            *flushed += bytes.len() as u64;
        } else {
            self.buffer.extend_from_slice(bytes);
        }
        Ok(())
    }

    /// Whether the `candidate.len()` bytes at `offset` are exactly `candidate`.
    ///
    /// The compare that makes a content-dedup hit a fact rather than a probability. Answered out of
    /// the buffer when the body is still in it, which costs nothing at all.
    pub(crate) fn matches_at(&mut self, offset: u64, candidate: &[u8]) -> Result<bool> {
        let start = offset as u64;
        let end = start + candidate.len() as u64;
        debug_assert!(end <= self.len(), "a dedup candidate past the end of the data section");
        self.confirms += 1;
        if start >= self.flushed {
            let at = (start - self.flushed) as usize;
            return Ok(self.buffer[at..at + candidate.len()] == *candidate);
        }
        self.confirms_from_file += 1;
        self.bytes_from_file += candidate.len() as u64;
        // A body straddling the boundary is one the buffer holds only the tail of. Flushing is
        // simpler than stitching two compares together, and can only happen once per body.
        if end > self.flushed {
            self.flush()?;
        }
        let Spill { file, path, scratch, .. } = self;
        scratch.resize(candidate.len(), 0);
        read_exact_at(file, path, start, scratch)?;
        Ok(scratch[..] == *candidate)
    }

    pub(crate) fn flush(&mut self) -> Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        let Spill { file, path, buffer, flushed, .. } = self;
        write_all_at(file, path, *flushed, buffer)?;
        *flushed += buffer.len() as u64;
        buffer.clear();
        Ok(())
    }

    /// Copy the whole section onto the end of the archive being written, and say how much.
    ///
    /// Through a buffer of its own rather than `std::io::copy`, whose eight kilobytes would make a
    /// California data section eighty thousand round trips in each direction.
    pub(crate) fn copy_to(&mut self, out: &mut File, out_path: &Path) -> Result<u64> {
        self.flush()?;
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        let mut chunk = vec![0u8; BUFFER_BYTES];
        let mut copied = 0u64;
        loop {
            let n = file.read(&mut chunk).map_err(|e| scratch_error(path, "read back from", e))?;
            if n == 0 {
                return Ok(copied);
            }
            out.write_all(&chunk[..n]).map_err(|e| {
                crate::proto::Error(format!(
                    "cannot copy the .mamaps data section into {}: {e}",
                    out_path.display(),
                ))
            })?;
            copied += n as u64;
        }
    }

    /// The same, onto the end of an archive being assembled in memory.
    ///
    /// Into a resized tail rather than through `read_to_end`, whose growth heuristics will happily
    /// double a 655 MB buffer and hold both halves while it copies — which is most of the peak this
    /// whole arrangement exists to remove. The length is known exactly, so nothing needs guessing.
    pub(crate) fn copy_to_vec(&mut self, out: &mut Vec<u8>) -> Result<u64> {
        self.flush()?;
        let at = out.len();
        let len = self.flushed;
        out.resize(at + len as usize, 0);
        let Spill { file, path, .. } = self;
        file.rewind().map_err(|e| scratch_error(path, "seek in", e))?;
        file.read_exact(&mut out[at..]).map_err(|e| scratch_error(path, "read back from", e))?;
        Ok(len)
    }
}

impl Drop for Spill {
    /// So a build that dies part way through cannot strand a copy of its data section — 652 MB for
    /// California and tens of gigabytes for a planet, which is why `tile_build::spill` does the
    /// same.
    ///
    /// The error is dropped on purpose. Scratch that outlives the process is untidy; panicking in a
    /// `Drop` while a real failure is unwinding would replace its diagnostic with this one.
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

/// Seeking before every access rather than tracking the cursor.
///
/// A dedup compare moves the file cursor, so a write that assumed it was still at the end would put
/// a body somewhere else in the data section — and the index would still say it was at the end.
/// These take the offset they want and are therefore immune to whatever ran last.
pub(crate) fn write_all_at(file: &mut File, path: &Path, offset: u64, bytes: &[u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.write_all(bytes).map_err(|e| scratch_error(path, "write to", e))
}

pub(crate) fn read_exact_at(file: &mut File, path: &Path, offset: u64, into: &mut [u8]) -> Result<()> {
    file.seek(SeekFrom::Start(offset)).map_err(|e| scratch_error(path, "seek in", e))?;
    file.read_exact(into).map_err(|e| scratch_error(path, "read back from", e))
}

pub(crate) fn scratch_error(path: &Path, doing: &str, e: std::io::Error) -> crate::proto::Error {
    crate::proto::Error(format!(
        "cannot {doing} the .mamaps body scratch file {}: {e}",
        path.display(),
    ))
}
