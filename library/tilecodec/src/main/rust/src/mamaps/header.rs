//! The `.mamaps` header: 128 bytes that locate every other section — 160 on a v8 archive,
//! whose trailing 32 name the shared section.
//!
//! Every section is found **only** through an offset declared here. Nothing is inferred from
//! layout, because layout is not fixed: the real 137 GB PMTiles archive puts its leaf
//! directories *after* its tile data, and a reader that assumed otherwise would address the
//! wrong bytes. The same freedom is deliberately kept here.
//!
//! The header is small enough that it always arrives inside the reader's opening prefix
//! together with the dictionary and the root index, which is what makes a cold open one range
//! request. [`super::write`] asserts that budget rather than letting a build silently cost every
//! reader a third round trip.

use crate::proto::{err, Result};

pub const MAGIC: &[u8; 7] = b"MAMAPS\0";

/// Bumped only for a change a reader cannot ignore. The archive carries a
/// [`build_id`](Header::build_id) for "same format, different data".
///
/// v7 adds what the carriageway renderer needs to paint lane markings onto a road surface rather
/// than stroke parallel lines over it: a `FLAG_IS_ONEWAY` feature flag (bit 4), and one more
/// optional trailing body section behind `BODY_FLAG_ROAD_LANES` — a per-road **carriageway table**
/// (dense-parallel to the `roads` layer: the `lanes:forward`/`lanes:backward` split that says where
/// the centre line goes, and which dividers are solid) plus a per-tile **marking convention** byte
/// (left- or right-hand traffic, yellow or white centre line). The 24-byte feature record is
/// unchanged. The bump is forced twice over: an older reader rejects both an unknown body flag and
/// an unknown feature flag bit, so a v7 archive is a clean rejection rather than a wrong map.
///
/// v7 also adds the `junction` layer (id 11) to [`dict::LAYERS`](super::dict::LAYERS): one
/// `GEOM_LINE` feature per lane connector through an intersection, built from the same v6 routing
/// graph `traffic` reads. **That was only free because it landed inside v7's window.** Appending a
/// layer normally forces a bump of its own — v4 did exactly this for `traffic` — because `read`'s
/// `check_matches_schema` compares the whole dictionary on open, so every existing archive stops
/// opening. It cost nothing here only because v7 was committed but never built or shipped, leaving
/// no deployed reader to reject. The window shuts the moment a v7 archive exists: any layer added
/// after that costs v8.
///
/// v6 adds two optional trailing body sections behind new body flags: a per-building **S3DB
/// attribute table** (`BODY_FLAG_BUILDING_TABLE`, dense-parallel to the `buildings` layer —
/// heights, roof shape/height/direction/orientation and wall/roof colours for 3D extrusion) and a
/// per-tile **DEM heightmap** grid (`BODY_FLAG_HEIGHTMAP`, `u16` metres for 3D terrain relief).
/// The 24-byte feature record is unchanged, so the bump is not forced by the record width — it is
/// forced because an older reader rejects an unknown body flag, and would otherwise silently draw a
/// v6 tile flat. `Body::parse` refuses a version it does not speak, so an old v5 archive is a clean
/// rejection rather than a wrong map.
///
/// v5 bakes lane data into the `roads` layer: a per-feature **lane count** in the byte the
/// feature record kept reserved (byte 23), which the renderer expands into that many parallel
/// carriageway lanes with dividers at high zoom. The record width is unchanged, so the bump is
/// not forced by the dictionary (no new `kind`/`kind_detail`) — it is forced because an older
/// reader would read a v5 body's lane byte as the reserved zero it always was and draw every
/// multi-lane road as a single line. `Body::parse` refuses a version it does not speak, so the
/// mismatch is a clean rejection rather than a silently wrong map.
///
/// v4 adds the `traffic` layer (id 10) to [`dict::LAYERS`](super::dict::LAYERS): one
/// `GEOM_LINE` feature per drivable component segment of the v6 routing graph, each carrying
/// its packed `component_id` in the body id table. The layer addition alone forces the bump —
/// `read`'s `check_matches_schema` validates the whole dictionary on open, so an older reader
/// rejects a v4 archive anyway.
///
/// v3 adds `fuel`, `hotel` and `atm` to [`dict::KINDS`](super::dict::KINDS) and an optional
/// per-body feature id table. The kind additions alone force the bump: `read`'s
/// `check_matches_schema` validates the whole dictionary on open, so an older reader would
/// reject a v3 archive anyway. v1 is no longer read — the last v1 archive predates `places`,
/// `poi` and `transit` entirely.
pub const FORMAT_VERSION: u8 = 7;

/// The archive version byte of a v8 archive: one carrying a shared section.
///
/// v8 interns the long-lived per-feature attributes (names, logical rows, S3DB extrusion,
/// carriageway splits, lane turns, stable ids) into one archive-global shared section behind
/// `MBSH`, and appends a 32-byte tail to the header (bytes 128..160) naming it:
/// `shared_offset`/`shared_len`, `shared_flags`, `shared_pools`, reserved. An archive without
/// a shared section is byte-identical v7 — 128 bytes with version byte 7. With one it is 160
/// bytes with version byte 8. The bump is forced twice over, the way v6's and v7's were: an
/// older reader rejects both an unknown version byte and a header length that is not 128, so
/// a v8 archive is a clean rejection rather than a misread map. A v8 reader opens both
/// shapes: a 128-byte header reads as v7 with no shared section, a 160-byte header parses
/// the tail.
///
/// Tile bodies version independently: a v8 slim body carries body version 8 and resolves
/// through the shared section, while a v7 full body still carries 7. `Body::parse` keeps
/// gating on [`FORMAT_VERSION`], which is why this const exists beside it rather than
/// replacing it.
pub const FORMAT_VERSION_V8: u8 = 8;

pub const HEADER_LEN: usize = 128;

/// A v8 header's wire length: the 128 v7 bytes plus the 32-byte shared-section tail.
///
/// The first 128 bytes keep their v7 field positions exactly, so everything up to the tail
/// reads the same out of either shape.
pub const HEADER_LEN_V8: usize = 160;

/// Bodies are compressed frames; clear means the body is stored raw.
pub const FLAG_BODIES_COMPRESSED: u16 = 1 << 0;
/// At least one leaf entry has a `run_length` above 1, so a reader must honour runs.
pub const FLAG_RUN_LENGTH_PRESENT: u16 = 1 << 1;
/// Ring winding and hole containment were normalised at build time.
///
/// What lets `tess::fill`'s repair pass be skipped: with this set, every polygon part group has
/// exactly one CCW outer and its holes are CW and strictly inside it.
pub const FLAG_RINGS_VALIDATED: u16 = 1 << 2;
/// The leaf index exceeds 4 GiB (planet scale: ~268M bodies ×16 B ≈4.29 GB).
/// When set, bytes 72..80 of the header hold leaf_len as a little-endian u64
/// (instead of u32 leaf_len at 72..76 plus 0-reserved at 76..80). Common
/// path (NA ~418 MB) stays flag 0 + u32 + 0 — byte-identical to v1.
pub const FLAG_LEAF_LEN_64: u16 = 1 << 3;

const KNOWN_FLAGS: u16 =
    FLAG_BODIES_COMPRESSED | FLAG_RUN_LENGTH_PRESENT | FLAG_RINGS_VALIDATED | FLAG_LEAF_LEN_64;

/// Bodies stored as written.
pub const COMPRESSION_NONE: u8 = 0;
/// Raw DEFLATE, one independent frame per body.
///
/// Not zstd, which is what a container designed on paper would reach for. This crate's whole
/// dependency list is `miniz_oxide` — pure Rust, so it cross-compiles for
/// `aarch64-linux-android` with no C toolchain and nothing to configure — and zstd's encoder
/// would end that. Not gzip either: gzip costs 18 bytes of framing per body and existed only
/// because MapLibre requires it, which nothing reading this format does.
pub const COMPRESSION_DEFLATE: u8 = 1;

/// The deepest zoom a `tile_id_lo` can span inside one leaf. See [`super::index`].
pub const MAX_ZOOM: u8 = 22;

/// What a reader must know before it can address anything.
///
/// Field order **is** wire order, and the byte map is in [`Header::parse`]. Lengths are `u32`
/// wherever a section cannot plausibly exceed 4 GiB; offsets are `u64` without exception,
/// because the data section on its own is already past that on a planet build.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Header {
    pub flags: u16,
    pub compression: u8,
    pub layer_count: u8,
    pub min_zoom: u8,
    pub max_zoom: u8,
    /// Identifies the *data*, not the format: hashed over the generator revision, the input
    /// digest, the zoom range, the layer set and the simplification parameters.
    ///
    /// This is the only thing standing between republishing under a stable `immutable` URL and
    /// every existing reader serving stale tiles forever. It costs no extra request, because it
    /// is already in the prefix a reader fetches to open the archive at all.
    pub build_id: u64,
    /// The whole file, so a truncated download is caught on open rather than at the first tile
    /// that happens to land past the end.
    pub file_len: u64,
    pub dict_offset: u64,
    pub dict_len: u32,
    /// Leaf entries per leaf. A power of two, doubled at build time when the root would not fit.
    pub leaf_entry_capacity: u32,
    pub root_offset: u64,
    pub root_len: u32,
    pub leaf_count: u32,
    pub leaf_offset: u64,
    /// Leaf index length. u64 on the wire when FLAG_LEAF_LEN_64 is set
    /// (planet: ~4.29 GB); otherwise the 72..76 u32 plus 76..80 zero
    /// reserved — so NA/us-west stay byte-identical.
    pub leaf_len: u64,
    pub data_offset: u64,
    pub data_len: u64,
    /// Tiles that resolve to a body, counting every id a run covers.
    pub tiles_addressed: u64,
    /// Bodies actually stored, after run-length and content dedup.
    pub bodies_written: u64,
    pub min_lon_e7: i32,
    pub min_lat_e7: i32,
    pub max_lon_e7: i32,
    pub max_lat_e7: i32,
    /// Where the v8 shared section lives.
    ///
    /// `shared_len == 0` means no shared section: a v7 archive, whose serialization is the
    /// byte-identical 128-byte header with version byte 7. Nonzero means a v8 archive (160
    /// bytes, version byte 8) whose shared section starts at `shared_offset` and runs
    /// `shared_len` bytes. What lane C's `shared_location()` hook reads.
    pub shared_offset: u64,
    pub shared_len: u64,
    /// Header-level shared-section flags. None are defined yet, so any set bit is refused —
    /// the same rule as the top-level flags: a flag changes how the section must be handled,
    /// and ignoring one would draw the map wrong.
    pub shared_flags: u16,
    /// How many pool directory entries the shared section carries.
    ///
    /// Mirrors the section's own pool count so `Header::check` can require the directory (its
    /// header plus this many pool entries) to fit inside the section — and inside the opening
    /// prefix — before any pool is fetched.
    pub shared_pools: u32,
}

impl Header {
    pub fn compressed(&self) -> bool {
        self.flags & FLAG_BODIES_COMPRESSED != 0
    }

    pub fn rings_validated(&self) -> bool {
        self.flags & FLAG_RINGS_VALIDATED != 0
    }

    /// `(offset, len)` of the shared section, or `None` on a v7 archive.
    ///
    /// Absent ⟺ `shared_len == 0`. What lane C's `shared_location()` hook calls: on `Some`
    /// it fetches and parses the section, on `None` it stays on the v7 cost contract with no
    /// request made. Reads the header only, never the wire.
    pub fn shared_location(&self) -> Option<(u64, u64)> {
        (self.shared_len != 0).then_some((self.shared_offset, self.shared_len))
    }

    /// This header's wire length: 128 without a shared section, 160 with one.
    ///
    /// What the writer offsets the dictionary by: the dictionary starts where the header
    /// ends, and the header ends 32 bytes later on a v8 archive.
    pub fn wire_len(&self) -> usize {
        if self.shared_len == 0 {
            HEADER_LEN
        } else {
            HEADER_LEN_V8
        }
    }

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
    fn check(&self) -> Result<()> {
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
            let dir = (super::shared::SHARED_HEADER_LEN as u64)
                .checked_add(
                    (self.shared_pools as u64)
                        .checked_mul(super::shared::SHARED_POOL_ENTRY_LEN as u64)
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
        if self.root_len as usize % super::index::ROOT_ENTRY_LEN != 0 {
            return err(format!(
                "a .mamaps root index of {} bytes is not a whole number of {} byte entries",
                self.root_len,
                super::index::ROOT_ENTRY_LEN,
            ));
        }
        if self.bodies_written > self.tiles_addressed {
            return err("a .mamaps header stores more bodies than it addresses tiles");
        }
        Ok(())
    }

    /// The v7 bytes, or the v8 bytes when this names a shared section.
    ///
    /// Absent (`shared_len == 0`) serializes to the byte-identical 128-byte v7 header with
    /// version byte 7 — which is what keeps a shared-table-off build byte-identical v7.
    /// Present serializes to 160 bytes with version byte 8 and the tail at 128..160.
    pub fn serialize(&self) -> Vec<u8> {
        let v8 = self.shared_len != 0;
        if !v8 {
            debug_assert_eq!(self.shared_offset, 0, "a v7 header names no shared section");
            debug_assert_eq!(self.shared_flags, 0, "a v7 header sets no shared flags");
            debug_assert_eq!(self.shared_pools, 0, "a v7 header names no shared pools");
        }
        let wire_len = if v8 { HEADER_LEN_V8 } else { HEADER_LEN };
        let mut out = Vec::with_capacity(wire_len);
        out.extend_from_slice(MAGIC);
        out.push(if v8 { FORMAT_VERSION_V8 } else { FORMAT_VERSION });
        out.extend_from_slice(&(wire_len as u16).to_le_bytes());
        out.extend_from_slice(&self.flags.to_le_bytes());
        out.push(self.compression);
        out.push(self.layer_count);
        out.push(self.min_zoom);
        out.push(self.max_zoom);
        out.extend_from_slice(&self.build_id.to_le_bytes());
        out.extend_from_slice(&self.file_len.to_le_bytes());
        out.extend_from_slice(&self.dict_offset.to_le_bytes());
        out.extend_from_slice(&self.dict_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_entry_capacity.to_le_bytes());
        out.extend_from_slice(&self.root_offset.to_le_bytes());
        out.extend_from_slice(&self.root_len.to_le_bytes());
        out.extend_from_slice(&self.leaf_count.to_le_bytes());
        out.extend_from_slice(&self.leaf_offset.to_le_bytes());
        // 72..76 is always low 32 of leaf_len; 76..80 is high 32 when extended,
        // otherwise reserved zero. Common path (<4 GiB): high 32 == 0 → byte-identical to v1 (u32 + 0).
        out.extend_from_slice(&(self.leaf_len as u32).to_le_bytes());
        if (self.flags & FLAG_LEAF_LEN_64) != 0 {
            // Extended: 76..80 holds high 32 of the true u64 leaf_len (planet ~4.29 GB).
            out.extend_from_slice(&((self.leaf_len >> 32) as u32).to_le_bytes());
        } else {
            out.extend_from_slice(&0u32.to_le_bytes());
        }
        out.extend_from_slice(&self.data_offset.to_le_bytes());
        out.extend_from_slice(&self.data_len.to_le_bytes());
        out.extend_from_slice(&self.tiles_addressed.to_le_bytes());
        out.extend_from_slice(&self.bodies_written.to_le_bytes());
        for v in [self.min_lon_e7, self.min_lat_e7, self.max_lon_e7, self.max_lat_e7] {
            out.extend_from_slice(&v.to_le_bytes());
        }
        if v8 {
            out.extend_from_slice(&self.shared_offset.to_le_bytes());
            out.extend_from_slice(&self.shared_len.to_le_bytes());
            out.extend_from_slice(&self.shared_flags.to_le_bytes());
            out.extend_from_slice(&self.shared_pools.to_le_bytes());
            out.extend_from_slice(&[0u8; 10]);
        }
        debug_assert_eq!(out.len(), wire_len);
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A header naming sections that do not overlap, in a file big enough to hold them.
    fn plausible() -> Header {
        Header {
            flags: FLAG_BODIES_COMPRESSED,
            compression: COMPRESSION_DEFLATE,
            layer_count: 7,
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0x0123_4567_89AB_CDEF,
            file_len: 4096,
            dict_offset: 128,
            dict_len: 512,
            leaf_entry_capacity: 4096,
            root_offset: 640,
            root_len: 64,
            leaf_count: 2,
            leaf_offset: 704,
            leaf_len: 32,
            data_offset: 736,
            data_len: 3360,
            tiles_addressed: 9,
            bodies_written: 4,
            min_lon_e7: -1_242_000_000,
            min_lat_e7: 324_000_000,
            max_lon_e7: -1_140_000_000,
            max_lat_e7: 420_000_000,
            shared_offset: 0,
            shared_len: 0,
            shared_flags: 0,
            shared_pools: 0,
        }
    }

    #[test]
    fn a_header_round_trips_through_its_own_bytes() {
        let header = plausible();
        let bytes = header.serialize();
        assert_eq!(bytes.len(), HEADER_LEN, "the header is a fixed 128 bytes");
        assert_eq!(Header::parse(&bytes).expect("should parse"), header);
    }

    #[test]
    fn every_u64_field_is_eight_byte_aligned() {
        // So a reader may take them as aligned loads out of a zero-copy prefix slice.
        for offset in [16, 24, 32, 48, 64, 80, 88, 96, 104, 128, 136] {
            assert_eq!(offset % 8, 0, "a u64 sits at byte {offset}");
        }
    }

    #[test]
    fn a_header_with_trailing_bytes_still_parses() {
        // It arrives inside a 16 KiB prefix, never on its own.
        let mut bytes = plausible().serialize();
        bytes.extend_from_slice(&[0xAB; 1024]);
        assert!(Header::parse(&bytes).is_ok());
    }

    #[test]
    fn a_truncated_or_foreign_header_is_refused() {
        let bytes = plausible().serialize();
        assert!(Header::parse(&bytes[..HEADER_LEN - 1]).is_err(), "short");
        let mut wrong_magic = bytes.clone();
        wrong_magic[0] = b'P';
        assert!(Header::parse(&wrong_magic).is_err(), "PMTiles is not this format");
        let mut wrong_version = bytes.clone();
        wrong_version[7] = FORMAT_VERSION_V8 + 1;
        assert!(Header::parse(&wrong_version).is_err(), "a version past v8");
    }

    /// An unknown flag means the writer said something about the bodies this reader would
    /// ignore, and every flag here changes how a body must be handled.
    #[test]
    fn an_unknown_flag_or_a_dirty_reserved_word_is_refused() {
        let mut bytes = plausible().serialize();
        bytes[11] = 0x80;
        assert!(Header::parse(&bytes).is_err(), "an unknown flag");
        let mut bytes = plausible().serialize();
        bytes[76] = 1;
        assert!(Header::parse(&bytes).is_err(), "a reserved word a later version may claim");
    }

    /// Every later read takes its length from a header field, so a header that names an
    /// impossible section has to fail here rather than somewhere uninformative.
    #[test]
    fn a_header_whose_sections_do_not_fit_the_file_is_refused() {
        let cases: &[(&str, fn(&mut Header))] = &[
            ("data past the end", |h| h.data_len = 1 << 40),
            ("a section inside the header", |h| h.dict_offset = 8),
            ("the dictionary over the root", |h| h.dict_len = 600),
            ("a file shorter than the header", |h| h.file_len = 8),
            ("a zoom range inverted", |h| h.min_zoom = 15),
            ("a zoom past the renderer's maximum", |h| h.max_zoom = 30),
            ("a leaf capacity that is not a power of two", |h| h.leaf_entry_capacity = 3000),
            ("no leaves at all", |h| h.leaf_count = 0),
            ("an unknown compression", |h| h.compression = 9),
            ("more bodies than addressed tiles", |h| h.bodies_written = 10),
            ("a root that is not whole entries", |h| h.root_len = 63),
            ("a flag that contradicts the compression byte", |h| {
                h.compression = COMPRESSION_NONE;
            }),
        ];
        for (what, break_it) in cases {
            let mut header = plausible();
            break_it(&mut header);
            assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
        }
    }

    #[test]
    fn an_offset_length_pair_cannot_overflow_into_looking_valid() {
        let mut header = plausible();
        header.data_offset = u64::MAX - 8;
        header.data_len = 64;
        assert!(Header::parse(&header.serialize()).is_err(), "the end wraps");
    }

    /// Planet scale: leaf index is ~268M bodies ×16 B ≈4.29 GB > u32::MAX,
    /// overflowing the old 4 GiB leaf_len field by 1257 B in the failed run.
    /// Extended encoding uses FLAG_LEAF_LEN_64 and the formerly-reserved
    /// 76..80 word as high 32 of a u64 leaf_len (72..76 low 32). Common path
    /// (NA ~418 MB < u32::MAX) stays flag 0 + u32 + 0 → byte-identical to v1.
    #[test]
    fn a_leaf_len_past_u32_max_round_trips_and_common_path_stays_byte_identical() {
        // The leaf field itself round-trips via Header serialization; use a
        // large file_len so the overall header check (sections fit) doesn't
        // reject the test file. NA's file_len is 418 MB leaf_len + data, but
        // plausible() is 4096 B — enlarge for the planet-sized leaf.
        let planet_leaf: u64 = u32::MAX as u64 + 1257;
        // Extended: flag set, high word carries the overflow.
        let mut extended = Header {
            leaf_len: planet_leaf,
            flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
            ..plausible()
        };
        extended.data_offset = extended.leaf_offset + extended.leaf_len;
        extended.file_len = extended.data_offset + extended.data_len;
        let bytes_ext = extended.serialize();
        assert_eq!(bytes_ext.len(), HEADER_LEN);
        assert_ne!(bytes_ext[10] & 0x08, 0, "extended flag must be set");
        assert_ne!(&bytes_ext[76..80], &[0, 0, 0, 0], "high word must carry overflow (1257 beyond 4 GiB)");
        let parsed = Header::parse(&bytes_ext).expect("extended must parse");
        assert_eq!(parsed.leaf_len, planet_leaf);
        assert_eq!(parsed.flags & FLAG_LEAF_LEN_64, FLAG_LEAF_LEN_64);
        // Common path (NA ~418 MB) stays byte-identical to v1: flag 0, reserved 0, low 32 only.
        let na_leaf: u64 = 418 * 1024 * 1024;
        let mut common = Header {
            leaf_len: na_leaf,
            ..plausible()
        };
        common.data_offset = common.leaf_offset + common.leaf_len;
        common.file_len = common.data_offset + common.data_len;
        let bytes_common = common.serialize();
        assert_eq!(bytes_common.len(), HEADER_LEN);
        assert_eq!(bytes_common[10] & FLAG_LEAF_LEN_64 as u8, 0, "common flag must be 0");
        assert_eq!(&bytes_common[76..80], &[0, 0, 0, 0], "reserved must stay 0 for <4 GiB");
        assert_eq!(Header::parse(&bytes_common).expect("common must parse").leaf_len, na_leaf);
        // Byte-identity: common bytes up to leaf_len are identical to what v1
        // would have written (low 32 + 0 reserved); extended differs only in flag + high word.
        // Extended canonical: flag set but value fits u32 is rejected
        let mut bad = Header {
            leaf_len: 100,
            flags: FLAG_BODIES_COMPRESSED | FLAG_LEAF_LEN_64,
            ..plausible()
        };
        bad.data_offset = bad.leaf_offset + bad.leaf_len;
        bad.file_len = bad.data_offset + bad.data_len;
        assert!(Header::parse(&bad.serialize()).is_err(), "extended with small leaf_len must be rejected");
    }

    // Fail-watch convention (lane B): each test pins one wire fact as a literal, so a
    // revert of that fact quotes its failure (`left` vs `right`) rather than a vague
    // mismatch. Revert → quote the failure → restore.

    /// A header naming a shared section past the tile data: every v7 section shifted by
    /// the 32-byte tail, the section itself last, `file_len` covering it.
    fn plausible_v8() -> Header {
        let v7 = plausible();
        let shift = (HEADER_LEN_V8 - HEADER_LEN) as u64;
        Header {
            dict_offset: v7.dict_offset + shift,
            root_offset: v7.root_offset + shift,
            leaf_offset: v7.leaf_offset + shift,
            data_offset: v7.data_offset + shift,
            shared_offset: v7.data_offset + shift + v7.data_len,
            shared_len: 512,
            file_len: v7.data_offset + shift + v7.data_len + 512,
            shared_flags: 0,
            // The seven pool kinds the shared section defines.
            shared_pools: 7,
            ..v7
        }
    }

    /// Fail-watched: the v8 shape. 160 bytes behind version byte 8, field positions
    /// identical to v7 (lengths untouched, offsets shifted by exactly the tail), the tail
    /// naming the section. A revert of the version byte quotes `left: 7, right: 8`; of the
    /// length, `left: 128, right: 160`.
    #[test]
    fn a_v8_header_is_160_bytes_with_a_v7_identical_first_128() {
        let v8 = plausible_v8();
        let bytes = v8.serialize();
        let v7bytes = plausible().serialize();
        assert_eq!(bytes.len(), 160, "a v8 header is 160 bytes");
        assert_eq!(bytes[7], 8, "the version byte marks v8");
        assert_eq!(&bytes[8..10], &160u16.to_le_bytes(), "the header declares 160");
        assert_eq!(&bytes[0..7], &v7bytes[0..7], "magic");
        assert_eq!(&bytes[10..24], &v7bytes[10..24], "flags through build_id");
        assert_eq!(&bytes[24..32], &4640u64.to_le_bytes(), "file_len covers the section");
        // Offsets shift by exactly the 32-byte tail; lengths are untouched.
        for (at, off) in [(32usize, 160u64), (48, 672u64), (64, 736u64), (80, 768u64)] {
            assert_eq!(&bytes[at..at + 8], &off.to_le_bytes(), "offset at {at}");
        }
        assert_eq!(&bytes[40..44], &v7bytes[40..44], "dict_len");
        assert_eq!(&bytes[56..60], &v7bytes[56..60], "root_len");
        assert_eq!(&bytes[72..76], &v7bytes[72..76], "leaf_len low");
        assert_eq!(&bytes[88..112], &v7bytes[88..112], "data_len, tiles, bodies");
        assert_eq!(&bytes[112..128], &v7bytes[112..128], "bbox");
        // The tail itself, little-endian: section at 4128 for 512 bytes, 7 pools.
        assert_eq!(&bytes[128..136], &4128u64.to_le_bytes(), "shared_offset");
        assert_eq!(&bytes[136..144], &512u64.to_le_bytes(), "shared_len");
        assert_eq!(&bytes[144..146], &0u16.to_le_bytes(), "shared_flags");
        assert_eq!(&bytes[146..150], &7u32.to_le_bytes(), "shared_pools");
        assert_eq!(&bytes[150..160], &[0u8; 10], "reserved tail");
        assert_eq!(Header::parse(&bytes).expect("should parse"), v8);
        // It arrives inside a 16 KiB prefix like every header.
        let mut prefixed = bytes.clone();
        prefixed.extend_from_slice(&[0xAB; 1024]);
        assert!(Header::parse(&prefixed).is_ok());
    }

    /// Fail-watched: the v8 reader still opens a 128-byte v7 header, with the shared
    /// fields zeroed and no shared section.
    #[test]
    fn a_128_byte_v7_header_opens_with_no_shared_section() {
        let header = Header::parse(&plausible().serialize()).expect("v7 still parses");
        assert_eq!((header.shared_offset, header.shared_len), (0, 0));
        assert_eq!(header.shared_location(), None, "v7 carries no shared section");
        assert_eq!(header.wire_len(), 128);
    }

    /// Fail-watched: version and length are bound together. A v7 version declaring 160,
    /// a v8 version declaring 128, a v8 header cut to 159 bytes, and anything past v8
    /// are all refused — while 8 itself parses.
    #[test]
    fn a_version_length_mismatch_is_refused() {
        let mut v7declares160 = plausible().serialize();
        v7declares160[8..10].copy_from_slice(&160u16.to_le_bytes());
        assert!(Header::parse(&v7declares160).is_err(), "v7 declaring 160");
        let mut v8declares128 = plausible_v8().serialize();
        v8declares128[8..10].copy_from_slice(&128u16.to_le_bytes());
        assert!(Header::parse(&v8declares128).is_err(), "v8 declaring 128");
        let full = plausible_v8().serialize();
        assert!(Header::parse(&full[..159]).is_err(), "a v8 header cut to 159 bytes");
        assert!(Header::parse(&full[..128]).is_err(), "a v8 version in 128 bytes");
        let mut past = full.clone();
        past[7] = 9;
        assert!(Header::parse(&past).is_err(), "a version past v8");
    }

    /// Fail-watched: the tail's own hygiene. Unknown shared flags and a dirty reserved
    /// tail are refused at parse; a 160-byte header naming a zero-length section is
    /// refused with it.
    #[test]
    fn a_dirty_shared_tail_is_refused() {
        let mut flags = plausible_v8().serialize();
        flags[144] = 1;
        assert!(Header::parse(&flags).is_err(), "unknown shared flags");
        let mut reserved = plausible_v8().serialize();
        reserved[159] = 1;
        assert!(Header::parse(&reserved).is_err(), "a dirty reserved tail");
        let mut zero_len = plausible_v8().serialize();
        zero_len[136..144].copy_from_slice(&0u64.to_le_bytes());
        assert!(Header::parse(&zero_len).is_err(), "v8 naming a zero-length section");
    }

    /// Fail-watched: the shared section gets the same three refusals as every other
    /// section — inside the header (including the 32-byte v8 tail), past the end
    /// (including an offset+length that wraps), overlapping a section — plus the
    /// zero-length contradiction a hand-built header can state.
    #[test]
    fn a_shared_section_must_fit_without_overlapping() {
        let cases: &[(&str, fn(&mut Header))] = &[
            ("shared over the dictionary", |h| {
                h.shared_offset = 200;
            }),
            ("shared over the tile data", |h| {
                h.shared_offset = 1000;
                h.shared_len = 100;
            }),
            ("shared past the end", |h| {
                h.shared_offset = h.file_len - 100;
            }),
            ("shared inside the v8 tail", |h| {
                h.shared_offset = 140;
            }),
            ("shared wrapping the address space", |h| {
                h.shared_offset = u64::MAX - 8;
                h.shared_len = 64;
            }),
        ];
        for (what, break_it) in cases {
            let mut header = plausible_v8();
            break_it(&mut header);
            assert!(Header::parse(&header.serialize()).is_err(), "{what} should be refused");
        }
        // Zero length beside a nonzero offset is a section claimed and not named.
        let mut header = plausible();
        header.shared_offset = 4096;
        assert!(header.check().is_err(), "a zero-length section with an offset");
    }

    /// Fail-watched: the pool directory must fit its section and the opening prefix.
    /// 100 pools need 2432 bytes of directory, past the 512-byte section; 1000 pools
    /// need 24032, inside a 1 MiB section but past the 16 KiB prefix a corrupt count
    /// must never make a reader allocate into.
    #[test]
    fn a_pool_directory_must_fit_its_section_and_the_opening_prefix() {
        let mut past_section = plausible_v8();
        past_section.shared_pools = 100;
        assert!(
            Header::parse(&past_section.serialize()).is_err(),
            "a directory past its section"
        );
        let mut past_prefix = plausible_v8();
        past_prefix.shared_len = 1 << 20;
        past_prefix.file_len = past_prefix.shared_offset + past_prefix.shared_len;
        past_prefix.shared_pools = 1000;
        assert!(
            Header::parse(&past_prefix.serialize()).is_err(),
            "a directory past the opening prefix"
        );
        let mut absurd = plausible_v8();
        absurd.shared_pools = u32::MAX;
        assert!(
            Header::parse(&absurd.serialize()).is_err(),
            "an absurd pool count"
        );
    }

    /// Fail-watched: the lane C wiring. `shared_location()` is `None` on v7 and the
    /// section on v8; `wire_len()` is the dictionary's offset on either shape.
    #[test]
    fn shared_location_is_none_for_v7_and_the_section_for_v8() {
        assert_eq!(plausible().shared_location(), None);
        assert_eq!(plausible().wire_len(), HEADER_LEN);
        let v8 = plausible_v8();
        assert_eq!(v8.shared_location(), Some((4128, 512)));
        assert_eq!(v8.wire_len(), HEADER_LEN_V8);
    }
}
