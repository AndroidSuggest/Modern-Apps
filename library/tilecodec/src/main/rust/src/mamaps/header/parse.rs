//! Header parsing and validation: [`Header::parse`] plus its internal `check`.
//!
//! Pure moves out of the former single-file header module; nothing here changed
//! except re-rooting `super::shared` / `super::index` to `crate::mamaps::…`,
//! which the extra module level requires.

use crate::proto::{err, Result};

use super::consts::{
    COMPRESSION_DEFLATE, COMPRESSION_NONE, FLAG_LEAF_LEN_64, FORMAT_VERSION, FORMAT_VERSION_V8,
    HEADER_LEN, HEADER_LEN_V8, KNOWN_FLAGS, MAGIC, MAX_ZOOM,
};
use super::types::Header;

impl Header {
    /// Read a header out of the opening prefix.
    ///
    /// Byte map, all little-endian: `0..7` magic, `7` format version, `8..10` header_len,
    /// `10..12` flags, `12` compression, `13` layer_count, `14` min_zoom, `15` max_zoom,
    /// `16..24` build_id, `24..32` file_len, `32..40` dict_offset, `40..44` dict_len,
    /// `44..48` leaf_entry_capacity, `48..56` root_offset, `56..60` root_len, `60..64`
    /// leaf_count, `64..72` leaf_offset, `72..76` leaf_len, `76..80` reserved, `80..88`
    /// data_offset, `88..96` data_len, `96..104` tiles_addressed, `104..112` bodies_written,
    /// `112..128` the bbox as four `i32` of degrees times 1e7. On a v8 archive (byte 7 is 8,
    /// `header_len` 160) the tail follows: `128..136` shared_offset, `136..144` shared_len,
    /// `144..146` shared_flags, `146..150` shared_pools, `150..160` reserved zero. A
    /// version-7 header is 128 bytes and parses with the shared fields zeroed; anything past
    /// byte 128 in its buffer is the dictionary, not the header, and is ignored the way it
    /// always was.
    ///
    /// Every `u64` sits on an 8-byte boundary so a reader may take them as aligned loads.
    pub fn parse(buf: &[u8]) -> Result<Header> {
        if buf.len() < HEADER_LEN {
            return err(format!("a .mamaps header is {HEADER_LEN} bytes, got {}", buf.len()));
        }
        if &buf[0..7] != MAGIC {
            return err("not a .mamaps archive (bad magic)");
        }
        let version = buf[7];
        if version != FORMAT_VERSION && version != FORMAT_VERSION_V8 {
            return err(format!(
                "unsupported .mamaps format version {version} (this reader speaks v{FORMAT_VERSION} and v{FORMAT_VERSION_V8})",
            ));
        }
        let u16_at = |o: usize| u16::from_le_bytes([buf[o], buf[o + 1]]);
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let i32_at = |o: usize| i32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o],
                buf[o + 1],
                buf[o + 2],
                buf[o + 3],
                buf[o + 4],
                buf[o + 5],
                buf[o + 6],
                buf[o + 7],
            ])
        };

        // v7 ⟺ 128 bytes, v8 ⟺ 160: each version declares exactly one length, so a header
        // claiming otherwise — a v7 length behind a v8 version or the reverse — is refused
        // rather than parsed as the shape it resembles.
        let header_len = u16_at(8);
        let wire_len = if version == FORMAT_VERSION { HEADER_LEN } else { HEADER_LEN_V8 };
        if header_len as usize != wire_len {
            return err(format!(
                "a .mamaps v{version} header declares {header_len} bytes, not {wire_len}"
            ));
        }
        if buf.len() < wire_len {
            return err(format!(
                "a .mamaps v{version} header needs {wire_len} bytes, got {}",
                buf.len()
            ));
        }
        let flags = u16_at(10);
        // An unknown flag means the writer recorded something about the bodies that this reader
        // would ignore, and every flag in this format changes how a body must be handled. Better
        // to refuse the archive than to draw it wrong.
        if flags & !KNOWN_FLAGS != 0 {
            return err(format!("a .mamaps header sets unknown flags {:#06X}", flags & !KNOWN_FLAGS));
        }
        // Bytes 72..80 hold leaf_len: 72..76 low 32, 76..80 high 32 when
        // FLAG_LEAF_LEN_64 is set, otherwise 76..80 must be zero (v1 reserved).
        // Common path (NA ~418 MB) stays flag 0 + u32 + 0 — byte-identical to v1.
        let leaf_len = if (flags & FLAG_LEAF_LEN_64) != 0 {
            let lo = u32_at(72) as u64;
            let hi = u32_at(76) as u64;
            let v = lo | (hi << 32);
            if v <= u32::MAX as u64 {
                return err(format!(
                    "a .mamaps header sets FLAG_LEAF_LEN_64 but leaf_len {} fits u32",
                    v
                ));
            }
            v
        } else {
            if u32_at(76) != 0 {
                return err("a .mamaps header has a non-zero reserved word");
            }
            u32_at(72) as u64
        };
        // The v8 tail. A v7 header has no tail: its bytes past 128 are the dictionary the
        // prefix also carries, so they are ignored and the shared fields read as zero. v8
        // names a section or it is malformed: a 160-byte header with a zero-length section,
        // unknown shared flags, or a dirty reserved tail is refused here.
        let (shared_offset, shared_len, shared_flags, shared_pools) =
            if version == FORMAT_VERSION_V8 {
                let shared_offset = u64_at(128);
                let shared_len = u64_at(136);
                let shared_flags = u16_at(144);
                let shared_pools = u32_at(146);
                if shared_len == 0 {
                    return err("a .mamaps v8 header names a zero-length shared section");
                }
                if shared_flags != 0 {
                    return err(format!(
                        "a .mamaps v8 header sets unknown shared flags {shared_flags:#06X}"
                    ));
                }
                if buf[150..160].iter().any(|&b| b != 0) {
                    return err("a .mamaps v8 header has a non-zero reserved tail");
                }
                (shared_offset, shared_len, shared_flags, shared_pools)
            } else {
                (0, 0, 0, 0)
            };
        let header = Header {
            flags,
            compression: buf[12],
            layer_count: buf[13],
            min_zoom: buf[14],
            max_zoom: buf[15],
            build_id: u64_at(16),
            file_len: u64_at(24),
            dict_offset: u64_at(32),
            dict_len: u32_at(40),
            leaf_entry_capacity: u32_at(44),
            root_offset: u64_at(48),
            root_len: u32_at(56),
            leaf_count: u32_at(60),
            leaf_offset: u64_at(64),
            leaf_len,
            data_offset: u64_at(80),
            data_len: u64_at(88),
            tiles_addressed: u64_at(96),
            bodies_written: u64_at(104),
            min_lon_e7: i32_at(112),
            min_lat_e7: i32_at(116),
            max_lon_e7: i32_at(120),
            max_lat_e7: i32_at(124),
            shared_offset,
            shared_len,
            shared_flags,
            shared_pools,
        };
        header.check()?;
        Ok(header)
    }

    /// Everything a later read would otherwise discover the hard way.
    ///
    /// A corrupt or hostile header must not be able to name a section that overlaps another, runs
    /// past the file, or is implausibly large — because every subsequent read takes its length
    /// from a field here, and a wrong length is a read that either fails somewhere uninformative
    /// or poisons a disk cache entry with a body of the wrong size.
    pub(super) fn check(&self) -> Result<()> {
        if !matches!(self.compression, COMPRESSION_NONE | COMPRESSION_DEFLATE) {
            return err(format!("unknown .mamaps compression {}", self.compression));
        }
        if self.compressed() != (self.compression != COMPRESSION_NONE) {
            return err("a .mamaps header disagrees with itself about whether bodies are compressed");
        }
        if self.min_zoom > self.max_zoom || self.max_zoom > MAX_ZOOM {
            return err(format!(
                "a .mamaps header declares an impossible zoom range {}..={}",
                self.min_zoom, self.max_zoom,
            ));
        }
        if !self.leaf_entry_capacity.is_power_of_two() {
            return err(format!(
                "a .mamaps leaf capacity of {} is not a power of two",
                self.leaf_entry_capacity,
            ));
        }
        if self.leaf_count == 0 {
            return err("a .mamaps archive needs at least one leaf");
        }
        // A v8 archive's header is 160 bytes, so a section may not start inside the tail any
        // more than inside the first 128. Absent ⟺ all four shared fields zero; anything else
        // beside a zero length is a section claimed and not named.
        let header_floor = if self.shared_len == 0 {
            if self.shared_offset != 0 || self.shared_flags != 0 || self.shared_pools != 0 {
                return err("a .mamaps header names a shared section of zero length");
            }
            HEADER_LEN as u64
        } else {
            HEADER_LEN_V8 as u64
        };
        if self.file_len < header_floor {
            return err(format!("a .mamaps header declares a {} byte file", self.file_len));
        }
        if self.shared_len != 0 {
            // The pool directory sits at the section's head: its entries must fit inside the
            // section, and the directory itself must fit the opening prefix — hundreds of
            // pools past the seven kinds the shared section defines is corruption, refused
            // before any pool is fetched rather than allocated into.
            let dir = (crate::mamaps::shared::SHARED_HEADER_LEN as u64)
                .checked_add(
                    (self.shared_pools as u64)
                        .checked_mul(crate::mamaps::shared::SHARED_POOL_ENTRY_LEN as u64)
                        .ok_or_else(|| {
                            crate::proto::Error(
                                "a .mamaps shared section's directory overflows".to_string(),
                            )
                        })?,
                )
                .ok_or_else(|| {
                    crate::proto::Error(
                        "a .mamaps shared section's directory overflows".to_string(),
                    )
                })?;
            if dir > self.shared_len {
                return err(format!(
                    "a .mamaps shared section of {} bytes cannot hold its {} pool(s)",
                    self.shared_len, self.shared_pools,
                ));
            }
            if dir > crate::stream::OPEN_PREFIX_BYTES as u64 {
                return err(format!(
                    "a .mamaps shared section names {} pool(s), whose directory does not fit the {} byte opening prefix",
                    self.shared_pools,
                    crate::stream::OPEN_PREFIX_BYTES,
                ));
            }
        }
        let sections = [
            ("the dictionary", self.dict_offset, self.dict_len as u64),
            ("the root index", self.root_offset, self.root_len as u64),
            ("the leaf index", self.leaf_offset, self.leaf_len as u64),
            ("the tile data", self.data_offset, self.data_len),
        ];
        for (what, offset, len) in sections {
            if offset < header_floor {
                return err(format!("{what} overlaps the .mamaps header"));
            }
            match offset.checked_add(len) {
                Some(end) if end <= self.file_len => {}
                _ => return err(format!("{what} runs past the end of the .mamaps file")),
            }
        }
        // Pairwise, because a writer that patched one offset and not another would otherwise
        // produce a file whose index and data disagree about where a body lives.
        for (i, (what, offset, len)) in sections.iter().enumerate() {
            for (other, other_offset, other_len) in &sections[i + 1..] {
                if *len == 0 || *other_len == 0 {
                    continue;
                }
                if *offset < other_offset + other_len && *other_offset < offset + len {
                    return err(format!("{what} and {other} overlap in the .mamaps file"));
                }
            }
        }
        // The shared section against each of the four above: same three refusals — inside
        // the header, past the end (including an offset+length that wraps), overlapping a
        // section — with its own name on the diagnostic.
        if self.shared_len != 0 {
            if self.shared_offset < header_floor {
                return err("the shared section overlaps the .mamaps header");
            }
            // Checked once, so the overlap compares below cannot wrap.
            let shared_end =
                self.shared_offset.checked_add(self.shared_len).ok_or_else(|| {
                    crate::proto::Error(
                        "a .mamaps shared section's extent overflows".to_string(),
                    )
                })?;
            if shared_end > self.file_len {
                return err("the shared section runs past the end of the .mamaps file");
            }
            for &(other, other_offset, other_len) in &sections {
                if other_len == 0 {
                    continue;
                }
                if self.shared_offset < other_offset + other_len
                    && other_offset < shared_end
                {
                    return err(format!(
                        "the shared section and {other} overlap in the .mamaps file"
                    ));
                }
            }
        }
        if self.root_len as usize % crate::mamaps::index::ROOT_ENTRY_LEN != 0 {
            return err(format!(
                "a .mamaps root index of {} bytes is not a whole number of {} byte entries",
                self.root_len,
                crate::mamaps::index::ROOT_ENTRY_LEN,
            ));
        }
        if self.bodies_written > self.tiles_addressed {
            return err("a .mamaps header stores more bodies than it addresses tiles");
        }
        Ok(())
    }
}
