use super::types::Entry;
use crate::proto::{Reader, Result, Writer, err};

/// Serialize a directory. Entries must be sorted ascending by `tile_id`.
pub fn serialize_directory(entries: &[Entry]) -> Vec<u8> {
    let mut w = Writer::with_capacity(entries.len() * 5);
    w.uvarint(entries.len() as u64);
    let mut last = 0u64;
    for e in entries {
        w.uvarint(e.tile_id - last);
        last = e.tile_id;
    }
    for e in entries {
        w.uvarint(e.run_length as u64);
    }
    for e in entries {
        w.uvarint(e.length as u64);
    }
    for (i, e) in entries.iter().enumerate() {
        // 0 is the "picks up exactly where the last one ended" shorthand, which is
        // the common case in a clustered archive and saves several bytes per entry.
        let contiguous = i > 0
            && e.offset == entries[i - 1].offset + entries[i - 1].length as u64;
        if contiguous {
            w.uvarint(0);
        } else {
            w.uvarint(e.offset + 1);
        }
    }
    w.into_vec()
}

/// Parse a directory body (already decompressed).
pub fn parse_directory(buf: &[u8]) -> Result<Vec<Entry>> {
    let mut r = Reader::new(buf);
    let n = r.uvarint()? as usize;
    // A directory is 5 varints per entry at minimum one byte each, so a count
    // wildly larger than the buffer is corruption, not a huge directory.
    if n > buf.len() {
        return err(format!("PMTiles directory claims {n} entries in {} bytes", buf.len()));
    }
    let mut entries = vec![
        Entry { tile_id: 0, offset: 0, length: 0, run_length: 0 };
        n
    ];
    let mut last = 0u64;
    for e in entries.iter_mut() {
        last += r.uvarint()?;
        e.tile_id = last;
    }
    for e in entries.iter_mut() {
        e.run_length = r.uvarint()? as u32;
    }
    for e in entries.iter_mut() {
        e.length = r.uvarint()? as u32;
    }
    for i in 0..n {
        let v = r.uvarint()?;
        entries[i].offset = if v == 0 {
            if i == 0 {
                return err("PMTiles directory's first entry uses the contiguous shorthand");
            }
            entries[i - 1].offset + entries[i - 1].length as u64
        } else {
            v - 1
        };
    }
    if !r.at_end() {
        return err("trailing bytes after PMTiles directory");
    }
    Ok(entries)
}

