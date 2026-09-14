use super::writer::StreamWriter;
use crate::mamaps::dict;
use crate::mamaps::header::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, FLAG_BODIES_COMPRESSED, FLAG_RINGS_VALIDATED,
    FLAG_RUN_LENGTH_PRESENT, HEADER_LEN, Header,
};
use crate::mamaps::index::{self};
use crate::proto::{Result, err};
use crate::stream::OPEN_PREFIX_BYTES;

impl StreamWriter {
    /// Everything ahead of the data section: header, dictionary, root, leaves, in that order.
    ///
    /// Section order on disk is header, dictionary, root, leaves, data — but every one of them is
    /// located by a header field, so a later version may reorder them freely.
    ///
    /// `shared_len` is the serialised v8 shared section's length and
    /// `shared_pools` its pool count, or zeros when [`Options::shared_table`](super::options::Options::shared_table)
    /// is off. The shared section itself is appended
    /// by the finishes, immediately after the tile data, so it always starts
    /// exactly at `data_offset + data_len`; stating its length here lets the
    /// header's `file_len` cover the whole file up front. With zeros the
    /// header is byte-identical v7.
    ///
    /// Returned parsed as well as serialized, because every check `Header::parse` makes is a check a
    /// reader will make on open, and finding out then means finding out on a device. It is checked
    /// here, before either finish emits a byte.
    ///
    /// No top-level flag is set for the shared section: lane B publishes it
    /// as a 160-byte v8 header (version byte 8) with `shared_offset` /
    /// `shared_len` / `shared_pools`, derived from these same two values.
    pub(crate) fn prefix(&self, shared_len: u64, shared_pools: u32) -> Result<(Header, Vec<u8>)> {
        if self.entries.is_empty() {
            return err("a .mamaps archive needs at least one tile");
        }
        let dictionary = dict::Dictionary::schema().serialize();
        let mut capacity = self.options.leaf_entry_capacity;

        // Grow the leaf size until the root fits the opening prefix, which is the same escape the
        // PMTiles writer takes and for the same reason. Twelve doublings takes 4096 to 16 M
        // entries per leaf; a root that still does not fit is a build failure, never a silent
        // third round trip charged to every reader forever.
        let (root, leaves) = loop {
            // A `None` is a leaf whose tile-id span will not fit a `u32`. A wider leaf spans more
            // ids, not fewer, so growing the capacity does not fix that one -- it runs out at the
            // cap below and reports. Kept distinct from the root-too-large case because the two want
            // opposite things from the capacity and a future fix has to choose between them.
            if let Some(split) = self.partition(capacity)? {
                let root_len = split.0.len() * index::ROOT_ENTRY_LEN;
                if HEADER_LEN + dictionary.len() + root_len <= OPEN_PREFIX_BYTES as usize {
                    break split;
                }
            }
            if capacity >= 1 << 24 {
                return err(format!(
                    "a .mamaps root index will not fit the {OPEN_PREFIX_BYTES} byte opening \
                     prefix even at {capacity} entries per leaf",
                ));
            }
            capacity *= 2;
        };

        let mut flags = 0u16;
        if self.options.compress {
            flags |= FLAG_BODIES_COMPRESSED;
        }
        if self.runs_used {
            flags |= FLAG_RUN_LENGTH_PRESENT;
        }
        if self.options.rings_validated {
            flags |= FLAG_RINGS_VALIDATED;
        }

        let leaf_bytes: Vec<u8> =
            leaves.iter().flat_map(|leaf| index::serialize_leaf(leaf)).collect();
        let leaf_len = leaf_bytes.len() as u64;
        // Planet scale: ~268M bodies ×16 B ≈4.29 GB > u32::MAX (1257 B overflow
        // in the failed run). Common path (NA ~418 MB, CA ~tens of MB) stays
        // flag 0 + u32 + 0 — byte-identical to v1. Extended path uses
        // FLAG_LEAF_LEN_64 and the 72..80 u64 slot (was u32+reserved).
        let needs_leaf64 = leaf_len > u32::MAX as u64;
        if needs_leaf64 {
            flags |= crate::mamaps::header::FLAG_LEAF_LEN_64;
        }
        let root_bytes = index::serialize_root(&root);
        // The dictionary starts where the header ends: 128 on a v7 archive,
        // 160 with a shared section (lane B's `wire_len`). Offsets shift with
        // it, so a v7 build's bytes never move.
        let header_len = if shared_len == 0 {
            HEADER_LEN as u64
        } else {
            crate::mamaps::header::HEADER_LEN_V8 as u64
        };
        let dict_offset = header_len;
        let root_offset = dict_offset + dictionary.len() as u64;
        let leaf_offset = root_offset + root_bytes.len() as u64;
        let data_offset = leaf_offset + leaf_bytes.len() as u64;
        let data_len = self.data.len();
        // The shared section starts exactly where the tile data ends; lane B
        // publishes it as `shared_offset`/`shared_len` (zero/absent on v7).
        let shared_offset = data_offset + data_len;

        let header = Header {
            flags,
            compression: if self.options.compress { COMPRESSION_DEFLATE } else { COMPRESSION_NONE },
            layer_count: dict::LAYERS.len() as u8,
            min_zoom: self.options.min_zoom,
            max_zoom: self.options.max_zoom,
            build_id: self.options.build_id,
            // The shared section rides past the tile data, so the file is the
            // prefix plus data plus shared. Zero when the flag is off: v7's
            // exact length, byte for byte.
            file_len: data_offset + data_len + shared_len,
            dict_offset,
            dict_len: dictionary.len() as u32,
            leaf_entry_capacity: capacity,
            root_offset,
            root_len: root_bytes.len() as u32,
            leaf_count: root.len() as u32,
            leaf_offset,
            leaf_len,
            data_offset,
            data_len,
            tiles_addressed: self.tiles_addressed,
            bodies_written: self.distinct,
            min_lon_e7: self.options.min_lon_e7,
            min_lat_e7: self.options.min_lat_e7,
            max_lon_e7: self.options.max_lon_e7,
            max_lat_e7: self.options.max_lat_e7,
            shared_offset: if shared_len == 0 { 0 } else { shared_offset },
            shared_len,
            shared_flags: 0,
            shared_pools,
        };

        let mut prefix = Vec::with_capacity(data_offset as usize);
        prefix.extend_from_slice(&header.serialize());
        prefix.extend_from_slice(&dictionary);
        prefix.extend_from_slice(&root_bytes);
        prefix.extend_from_slice(&leaf_bytes);
        debug_assert_eq!(prefix.len() as u64, data_offset);
        Ok((Header::parse(&prefix)?, prefix))
    }
}
