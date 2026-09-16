//! Streamed `.mdem` dataset writer: header with a zero tile count, one tile
//! record appended at a time, then the real count patched into the header.
//! The file never needs the whole grid set in memory (a world run at the old
//! z14/dim17 defaults held tens of GB of grids) — peak disk use is the output
//! file itself.
//!
//! Writes go through a [`BufWriter`] so a tile record is a couple of `memcpy`s
//! into the buffer rather than a syscall or two each, and one scratch buffer is
//! reused across tiles instead of a fresh `Vec` per record.

use std::fs::File;
use std::io::BufWriter;
use std::path::Path;

const OUT_MAGIC: &[u8; 4] = b"MDEM";
const OUT_VERSION: u8 = 1;

/// Offset of the `u32` tile count in the output header
/// (magic 4 + version 1 + zoom 1 + dim 2); patched in after the streamed body.
const COUNT_AT: u64 = 8;

/// Buffered `.mdem` writer plus a reused scratch buffer for tile records.
pub struct DatasetWriter {
    out: BufWriter<File>,
    scratch: Vec<u8>,
}

/// Open the dataset and write its header with a zero tile count; the count is
/// patched in by [`finish_dataset`].
pub fn open_dataset(out_path: &Path, out_zoom: u8, dim: u16) -> Result<DatasetWriter, String> {
    use std::io::Write;
    let file =
        File::create(out_path).map_err(|e| format!("creating {}: {e}", out_path.display()))?;
    let mut out = BufWriter::new(file);
    out.write_all(OUT_MAGIC)
        .map_err(|e| format!("writing {}: {e}", out_path.display()))?;
    out.write_all(&[OUT_VERSION, out_zoom])
        .map_err(|e| format!("writing {}: {e}", out_path.display()))?;
    out.write_all(&dim.to_le_bytes())
        .map_err(|e| format!("writing {}: {e}", out_path.display()))?;
    out.write_all(&0u32.to_le_bytes())
        .map_err(|e| format!("writing {}: {e}", out_path.display()))?;
    Ok(DatasetWriter { out, scratch: Vec::new() })
}

/// Append one tile's record: its pmtiles id then its `dim * dim` samples.
pub fn write_tile(w: &mut DatasetWriter, id: u64, grid: &[u16]) -> Result<(), String> {
    use std::io::Write;
    w.scratch.clear();
    w.scratch.reserve(8 + grid.len() * 2);
    w.scratch.extend_from_slice(&id.to_le_bytes());
    for &s in grid {
        w.scratch.extend_from_slice(&s.to_le_bytes());
    }
    w.out
        .write_all(&w.scratch)
        .map_err(|e| format!("writing tile {id}: {e}"))
}

/// Patch the real tile count into the header at [`COUNT_AT`], then flush.
pub fn finish_dataset(mut w: DatasetWriter, count: u32) -> Result<(), String> {
    use std::io::{Seek, SeekFrom, Write};
    w.out
        .seek(SeekFrom::Start(COUNT_AT))
        .map_err(|e| format!("seeking tile count: {e}"))?;
    w.out
        .write_all(&count.to_le_bytes())
        .map_err(|e| format!("writing tile count: {e}"))?;
    w.out
        .flush()
        .map_err(|e| format!("flushing dataset: {e}"))?;
    Ok(())
}
