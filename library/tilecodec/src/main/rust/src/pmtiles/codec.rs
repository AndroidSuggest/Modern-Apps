use super::types::{COMPRESSION_GZIP, COMPRESSION_NONE, Entry, Header};
use crate::gz;
use crate::proto::{Error, Result, err};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};

/// Fill `buf` from `offset` without moving the file's cursor.
///
/// The cursor is the only reason a read would need `&mut File`, and it is shared
/// between clones of a handle — so seek-then-read cannot be done concurrently on one
/// archive, while this can. Both platforms expose a positional read; neither is
/// guaranteed to return everything at once, hence the loop.
///
/// `pub` because [`crate::stream::StreamArchive`] and `tile_build`'s
/// `spill::NormalizedChunks` both need the same thing for the same reason: many
/// threads pulling disjoint ranges out of one file. It was `pub(crate)` while the
/// only caller lived in this crate.
pub fn read_exact_at(
    file: &File,
    mut buf: &mut [u8],
    mut offset: u64,
) -> std::io::Result<()> {
    while !buf.is_empty() {
        #[cfg(windows)]
        let n = std::os::windows::fs::FileExt::seek_read(file, buf, offset)?;
        #[cfg(unix)]
        let n = std::os::unix::fs::FileExt::read_at(file, buf, offset)?;
        if n == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "file ended mid-body",
            ));
        }
        buf = &mut buf[n..];
        offset += n as u64;
    }
    Ok(())
}
/// An entry's body must lie inside the data section. Shared by both readers so the
/// message a corrupt archive produces does not depend on which one opened it.
pub(crate) fn check_body(header: &Header, e: &Entry) -> Result<()> {
    if e.offset
        .checked_add(e.length as u64)
        .filter(|v| *v <= header.tile_data_length)
        .is_none()
    {
        return err("PMTiles entry runs past the tile data section");
    }
    Ok(())
}

pub(crate) fn read_section(
    file: &mut File,
    file_len: u64,
    offset: u64,
    length: u64,
    what: &str,
) -> Result<Vec<u8>> {
    if offset
        .checked_add(length)
        .filter(|e| *e <= file_len)
        .is_none()
    {
        return err(format!("PMTiles {what} runs past end of file"));
    }
    file.seek(SeekFrom::Start(offset))
        .map_err(|e| Error(format!("seeking to the {what}: {e}")))?;
    let mut buf = vec![0u8; length as usize];
    file.read_exact(&mut buf)
        .map_err(|e| Error(format!("reading the {what}: {e}")))?;
    Ok(buf)
}
pub(crate) fn find_entry(entries: &[Entry], want: u64) -> Option<&Entry> {
    let idx = match entries.binary_search_by(|e| e.tile_id.cmp(&want)) {
        Ok(i) => i,
        Err(0) => return None,
        Err(i) => i - 1,
    };
    let e = &entries[idx];
    if e.run_length == 0 {
        // Leaf pointers cover everything from their id to the next entry's.
        return Some(e);
    }
    if want < e.tile_id + e.run_length as u64 {
        Some(e)
    } else {
        None
    }
}

pub(crate) fn decompress(kind: u8, data: &[u8]) -> Result<Vec<u8>> {
    match kind {
        COMPRESSION_NONE => Ok(data.to_vec()),
        COMPRESSION_GZIP => gz::decompress(data),
        other => err(format!("unsupported PMTiles compression {other}")),
    }
}

pub(crate) fn compress(kind: u8, data: &[u8]) -> Result<Vec<u8>> {
    match kind {
        COMPRESSION_NONE => Ok(data.to_vec()),
        COMPRESSION_GZIP => Ok(gz::compress(data)),
        other => err(format!("unsupported PMTiles compression {other}")),
    }
}

