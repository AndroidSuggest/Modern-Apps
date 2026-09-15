use crate::mamaps::body::Body;
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::{COMPRESSION_DEFLATE, COMPRESSION_NONE, Header, HEADER_LEN};
use crate::mamaps::index::{self, RootEntry};
use crate::proto::{Error, Result, err};

/// Inflate one body frame, allocating exactly once.
///
/// The output size comes from the body header, which the writer keeps **outside** the compressed
/// frame precisely so this is possible: the length is known before the first byte is inflated, so
/// there is no grow-and-copy and no guess.
pub fn decompress(compression: u8, stored: &[u8]) -> Result<Vec<u8>> {
    match compression {
        COMPRESSION_NONE => Ok(stored.to_vec()),
        COMPRESSION_DEFLATE => {
            let raw_len = Body::raw_len(stored)? as usize;
            if raw_len < crate::mamaps::body::BODY_HEADER_LEN {
                return err("a .mamaps body frame declares a length shorter than a body header");
            }
            let mut out = vec![0u8; raw_len];
            // The body header is stored uncompressed ahead of the frame, so the frame itself is
            // everything after it and inflates to `raw_len` less that header.
            let header_len = crate::mamaps::body::BODY_HEADER_LEN;
            out[..header_len].copy_from_slice(&stored[..header_len]);
            let written = miniz_oxide::inflate::decompress_slice_iter_to_slice(
                &mut out[header_len..],
                std::iter::once(&stored[header_len..]),
                // Raw DEFLATE, matching what the writer emits: no zlib header, so no adler32
                // either.
                false,
                false,
            )
            .map_err(|e| Error(format!("a .mamaps body will not inflate: {e:?}")))?;
            if written != raw_len - header_len {
                return err(format!(
                    "a .mamaps body inflated to {} bytes, not the {} it declares",
                    written + header_len,
                    raw_len,
                ));
            }
            Ok(out)
        }
        other => err(format!("unknown .mamaps compression {other}")),
    }
}

/// The header, dictionary and root index of a file already in memory.
///
/// What `mamaps_dump` and the writer's own tests open with, so neither needs a `RangeReader`.
pub fn open_prefix(bytes: &[u8]) -> Result<(Header, Dictionary, Vec<RootEntry>)> {
    let header = Header::parse(bytes)?;
    if bytes.len() as u64 != header.file_len {
        return err(format!(
            "a .mamaps file declares {} bytes but is {}",
            header.file_len,
            bytes.len(),
        ));
    }
    let at = |offset: u64, len: u64| &bytes[offset as usize..(offset + len) as usize];
    let dictionary = Dictionary::parse(at(header.dict_offset, header.dict_len as u64))?;
    let root = index::parse_root(at(header.root_offset, header.root_len as u64))?;
    Ok((header, dictionary, root))
}

/// Every stored tile of a file already in memory, as `(tile_id, run_length, body bytes)`.
///
/// One entry per *stored body*, not per addressed tile, so a caller can see the dedup rather than
/// having it expanded away. Ascending by tile id.
pub fn read_all(bytes: &[u8]) -> Result<Vec<(u64, u32, Vec<u8>)>> {
    let (header, dictionary, root) = open_prefix(bytes)?;
    dictionary.check_matches_schema()?;
    let mut out = Vec::new();
    for entry in &root {
        let len = entry.leaf_entry_count as usize * index::LEAF_ENTRY_LEN;
        let at = (header.leaf_offset + entry.leaf_offset) as usize;
        if at + len > bytes.len() {
            return err("a .mamaps root entry runs past the file");
        }
        for tile in index::parse_leaf(&bytes[at..at + len])? {
            let start = (header.data_offset + entry.base_data_offset + tile.offset_delta as u64)
                as usize;
            let end = start + tile.length as usize;
            if end > bytes.len() {
                return err("a .mamaps leaf entry runs past the file");
            }
            out.push((
                entry.base_tile_id + tile.tile_id_lo as u64,
                tile.run_length,
                decompress(header.compression, &bytes[start..end])?,
            ));
        }
    }
    if out.windows(2).any(|pair| pair[1].0 <= pair[0].0) {
        return err("a .mamaps archive's tiles are not in ascending id order");
    }
    Ok(out)
}

/// A sanity bound so a reader can reject a prefix that is not a `.mamaps` file at all before
/// allocating anything, without depending on the reader's own constant.
pub const MIN_FILE_LEN: usize = HEADER_LEN;
