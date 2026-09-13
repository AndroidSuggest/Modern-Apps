//! Reading a `.mamaps` archive over range requests.
//!
//! The cost contract, which is the reason the container is shaped the way it is:
//!
//! | | requests |
//! |---|---|
//! | open | 1 |
//! | a tile whose leaf is cached | 1 |
//! | a tile whose leaf is not | 2 |
//! | ever | never 3 |
//!
//! Open is one request because the header, the dictionary and the root index all fit inside
//! [`OPEN_PREFIX_BYTES`] and none of the three is compressed, so all three are used straight out
//! of the prefix. Sixteen cached leaves at 4096 entries each address 65 536 tiles with no
//! directory traffic at all, which matches the locality the transport was tuned around.
//!
//! **Every read takes its length from a header or entry field.** No sentinel, no length
//! discovered mid-stream, no read-until-it-looks-done — because the disk cache in `tile::cache`
//! only stores a `206` whose body length equals what was asked for, so a read of the wrong length
//! does not merely fail, it poisons that range for every later read.

use crate::mamaps::body::{
    align4, Body, BuildingAttrs, Carriageway, Feature, Heightmap, LaneTurns, Layer,
    MarkingConvention, Part, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ROAD_LANES, FLAG_DETAIL_NUMERIC,
    FLAG_IS_BRIDGE, FLAG_IS_LINK, FLAG_IS_ONEWAY, FLAG_IS_TUNNEL, GEOM_LINE, GEOM_POINT,
    GEOM_POLYGON, ID_NONE, LAYER_INDEX_LEN, NAME_NONE, PART_ENTRY_LEN, WINDING_HOLE,
    WINDING_OUTER,
};
use crate::mamaps::shared::{
    SharedLogicalRow, SharedSlimRef, SharedView, SHARED_FLAG_DETAIL_NUMERIC,
};
use crate::mamaps::dict::Dictionary;
use crate::mamaps::header::{
    Header, COMPRESSION_DEFLATE, COMPRESSION_NONE, FORMAT_VERSION_V8 as V8_FORMAT_VERSION,
    HEADER_LEN,
};
use crate::mamaps::index::{self, LeafEntry, RootEntry};
use crate::pmtiles::tile_id;
use crate::proto::{err, Error, Result};
use crate::stream::{RangeReader, OPEN_PREFIX_BYTES};

/// How many parsed leaves to keep. Sixteen, matching `stream::StreamArchive`, because the access
/// pattern is the same one: a viewport walks a contiguous stretch of the curve.
const MAX_CACHED_LEAVES: usize = 16;

/// An open archive.
pub struct MamapsArchive<R: RangeReader> {
    /// Reachable from the crate so the request-counting tests in [`super`] can assert on the
    /// number of round trips, which is the contract this whole layout exists to hold.
    pub(crate) reader: R,
    pub header: Header,
    pub dictionary: Dictionary,
    root: Vec<RootEntry>,
    /// `(leaf offset, entries)`, most-recently-used last.
    leaves: Vec<(u64, Vec<LeafEntry>)>,
    /// The parsed v8 shared section, fetched on first use. `None` until a v8
    /// tile needs it — and always `None` on a v7 archive, which carries no
    /// shared section.
    shared: Option<SharedView>,
}

impl<R: RangeReader> MamapsArchive<R> {
    /// The reader this archive was opened on.
    ///
    /// So a caller can act on something it only learns from the header — the `build_id`, which
    /// decides whether the reader's disk cache is still addressing this build.
    pub fn reader(&self) -> &R {
        &self.reader
    }
    /// One range request: the header, the dictionary and the root index all live in the prefix.
    ///
    /// A file whose prefix does not hold all three is refused rather than fetched again. The
    /// writer asserts the budget, so a file that misses it was not written by this codec, and
    /// silently paying an extra round trip per open is exactly the cost the layout exists to
    /// avoid.
    pub fn open(reader: R) -> Result<MamapsArchive<R>> {
        let prefix = reader.read(0, OPEN_PREFIX_BYTES)?;
        let header = Header::parse(&prefix)?;
        let section = |offset: u64, len: u64, what: &str| -> Result<&[u8]> {
            let end = offset + len;
            if end > prefix.len() as u64 {
                return err(format!(
                    "{what} ends at byte {end}, outside the {OPEN_PREFIX_BYTES} byte prefix a \
                     .mamaps open reads -- this archive was not written by this codec",
                ));
            }
            Ok(&prefix[offset as usize..end as usize])
        };
        let dictionary =
            Dictionary::parse(section(header.dict_offset, header.dict_len as u64, "the dictionary")?)?;
        dictionary.check_matches_schema()?;
        if dictionary.layers.len() != header.layer_count as usize {
            return err("a .mamaps header and dictionary disagree on the layer count");
        }
        let root =
            index::parse_root(section(header.root_offset, header.root_len as u64, "the root index")?)?;
        if root.len() != header.leaf_count as usize {
            return err(format!(
                "a .mamaps header declares {} leaves but its root has {} entries",
                header.leaf_count,
                root.len(),
            ));
        }
        Ok(MamapsArchive { reader, header, dictionary, root, leaves: Vec::new(), shared: None })
    }

    /// The decoded body for a tile, or `None` when the archive does not hold it.
    ///
    /// `None` rather than an error: most of a coastal viewport is off the edge of coverage, and
    /// that is the ordinary answer rather than a fault.
    pub fn tile(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        let Some(bytes) = self.tile_bytes(z, x, y)? else { return Ok(None) };
        Ok(Some(Body::parse(&bytes)?))
    }

    /// The decompressed body bytes for a tile, undecoded.
    pub fn tile_bytes(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Vec<u8>>> {
        if z < self.header.min_zoom || z > self.header.max_zoom || z >= 32 {
            return Ok(None);
        }
        let n = 1u64 << z;
        if x as u64 >= n || y as u64 >= n {
            return Ok(None);
        }
        let want = tile_id(z, x as u64, y as u64);
        let Some(root) = index::find_leaf(&self.root, want).copied() else { return Ok(None) };
        // Ahead of the leaf's base is impossible (the root is ordered), so this only rules out a
        // tile past a `u32` of span, which the writer refuses to produce.
        let Ok(offset) = u32::try_from(want - root.base_tile_id) else { return Ok(None) };

        let leaf = self.leaf(&root)?;
        let Some(entry) = index::find_tile(&leaf, offset).copied() else { return Ok(None) };

        // Every bound from a field, checked before the read rather than after it.
        let at = root.base_data_offset + entry.offset_delta as u64;
        if at + entry.length as u64 > self.header.data_len {
            return err("a .mamaps leaf entry runs past the tile data section");
        }
        let stored = self.exact(self.header.data_offset + at, entry.length, "a tile body")?;
        Ok(Some(decompress(self.header.compression, &stored)?))
    }

    /// The parsed leaf a root entry addresses, from the cache or over the wire.
    fn leaf(&mut self, root: &RootEntry) -> Result<Vec<LeafEntry>> {
        if let Some(position) = self.leaves.iter().position(|(offset, _)| *offset == root.leaf_offset)
        {
            // Move to the back: most-recently-used last.
            let hit = self.leaves.remove(position);
            let entries = hit.1.clone();
            self.leaves.push(hit);
            return Ok(entries);
        }
        let len = root.leaf_entry_count as u64 * index::LEAF_ENTRY_LEN as u64;
        if root.leaf_offset + len > self.header.leaf_len as u64 {
            return err("a .mamaps root entry runs past the leaf section");
        }
        let length = u32::try_from(len)
            .map_err(|_| Error("a .mamaps leaf is larger than a single read".to_string()))?;
        let raw = self.exact(self.header.leaf_offset + root.leaf_offset, length, "a leaf")?;
        let entries = index::parse_leaf(&raw)?;
        if self.leaves.len() >= MAX_CACHED_LEAVES {
            self.leaves.remove(0);
        }
        self.leaves.push((root.leaf_offset, entries.clone()));
        Ok(entries)
    }

    /// A read that must come back at its full length.
    ///
    /// A short body means the range was not honoured. Decompressing it would fail somewhere far
    /// less informative, and caching it would poison that range for every later read.
    fn exact(&self, offset: u64, length: u32, what: &str) -> Result<Vec<u8>> {
        let body = self.reader.read(offset, length)?;
        if body.len() != length as usize {
            return err(format!(
                "a .mamaps read of {what} wanted {length} bytes at {offset}, got {}",
                body.len(),
            ));
        }
        Ok(body)
    }

    /// Whether this archive carries a v8 shared section.
    ///
    /// False on every v7 archive — which is all of them until lane B lands
    /// `shared_offset`/`shared_len` on the header.
    pub fn has_shared(&self) -> bool {
        self.shared_location().is_some()
    }

    /// The parsed shared table, fetching it on first use and caching it after.
    ///
    /// `None` on a v7 archive, with no request made: the v7 cost contract
    /// (open 1 / warm 1 / cold 2 / never 3) is untouched. On a v8 archive this
    /// is one range request for the whole shared section the first time a v8
    /// tile needs it, then zero — the pools are archive-global.
    pub fn shared_table(&mut self) -> Result<Option<&SharedView>> {
        if self.shared.is_some() {
            return Ok(self.shared.as_ref());
        }
        let Some((offset, len)) = self.shared_location() else { return Ok(None) };
        match offset.checked_add(len) {
            Some(end) if end <= self.header.file_len => {}
            _ => return err("a .mamaps shared section runs past the end of the file"),
        }
        let length = u32::try_from(len).map_err(|_| {
            Error("a .mamaps shared section is larger than a single read".to_string())
        })?;
        let raw = self.exact(offset, length, "the shared section")?;
        let view = SharedView::parse(&raw)?;
        if view.header.total_len as u64 != len {
            return err(format!(
                "a .mamaps shared section declares {} bytes but its header names {len}",
                view.header.total_len,
            ));
        }
        self.shared = Some(view);
        Ok(self.shared.as_ref())
    }

    /// The decoded body for a tile, resolving v8 slim refs through the shared
    /// table and reading v7 bodies exactly as [`tile`](Self::tile) does.
    ///
    /// On a v7 archive this is byte-for-byte [`tile`](Self::tile): the bytes
    /// come from [`tile_bytes`](Self::tile_bytes) untouched and a version-7
    /// body goes to [`Body::parse`] as before. A version-8 body is parsed as
    /// slim refs and resolved against [`shared_table`](Self::shared_table);
    /// without a shared section that is an error rather than a wrong map.
    pub fn tile_resolved(&mut self, z: u8, x: u32, y: u32) -> Result<Option<Body>> {
        let Some(bytes) = self.tile_bytes(z, x, y)? else { return Ok(None) };
        if !is_v8_body(&bytes) {
            return Ok(Some(Body::parse(&bytes)?));
        }
        let slim = SlimBody::parse(&bytes)?;
        let Some(shared) = self.shared_table()? else {
            return err(
                "a v8 tile body needs the archive's shared section, which this file does not carry",
            );
        };
        Ok(Some(resolve_body(shared, &slim)?))
    }

    /// `(offset, len)` of the shared section, or `None` on a v7 archive.
    ///
    /// Delegates to [`Header::shared_location`](super::header::Header::shared_location):
    /// absent ⟺ `shared_len == 0`. Reads the header only, never the wire.
    fn shared_location(&self) -> Option<(u64, u64)> {
        self.header.shared_location()
    }
}

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

// ─── v8 slim bodies: lazy shared fetch + slim resolve (lane C) ───────────────
//
// A v8 tile body carries per-instance slim records instead of full 24-byte
// feature records and no per-tile side tables at all: names, ids, colours,
// lane triples, carriageways and building attrs all resolve through the
// archive-global [`SharedView`] (lane A), keyed by `logical_id`. Geometry is
// NOT shared in v8.0 — every slim layer still carries its own part table and
// varint coord arena, encoded exactly as [`Body`] carries them — so what this
// section parses per tile is refs + parts + arena, and what it borrows from
// the shared section is everything else.
//
// Wire: the same 16-byte header shape as `Body` (magic `MBD`, `raw_len`,
// `extent`, layer count, flags, reserved) with version [`FORMAT_VERSION_V8`],
// a 12-byte index entry per layer (same stride as `Body`'s), then per layer:
// `[slim instances][part table][align4][coord arena]`, and — each announced by
// its flag — an optional marking-convention byte then an optional heightmap.
// A v7 body never reaches here: [`tile_resolved`](MamapsArchive::tile_resolved)
// sends anything that is not version 8 to [`Body::parse`].
//
// Lanes D (writer) and E (tiler/dump) own the other side of this wire. The
// only contract this file assumes about it is stated on each item below.

/// The tile-body version a slim body carries: lane B's v8 archive version.
///
/// Bodies version with their archive — a v8 slim body carries body version 8
/// and resolves through the shared section, while a v7 full body still
/// carries 7 and goes to `Body::parse`.

/// One per-tile slim instance's wire length: an 8-byte [`SharedSlimRef`] head
/// (parsed by lane A's parser, not re-decoded here), then `part_offset` /
/// `part_count` into the layer's own part table, the layer id, geometry and
/// transit bytes, and the transit colour.
pub(crate) const SLIM_INSTANCE_LEN: usize = 32;

/// Slim-body header length — the same 16-byte shape as `Body`'s.
pub(crate) const SLIM_HEADER_LEN: usize = 16;

/// Whether decompressed body bytes are a v8 slim body rather than a v7 full
/// body. Anything else — v7, corrupt, foreign — goes down the legacy path so
/// its errors are unchanged byte for byte.
fn is_v8_body(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && bytes[0..3] == *b"MBD" && bytes[3] == V8_FORMAT_VERSION
}

/// One slim instance: who it shares (the ref) plus what stays per tile.
///
/// The ref head is 8 bytes on the wire ([`SharedSlimRef`]); the remaining 24
/// carry the instance's part range and the small fields the v7 feature record
/// kept inline: which layer it belongs to, its geometry type and flags, its
/// lane count, its transit colour and lane triple. Names, ids and attribute
/// pools resolve through the row the ref names.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SlimInstance {
    pub logical_id: u32,
    pub view_bits: u32,
    pub part_offset: u32,
    pub part_count: u32,
    pub layer_id: u8,
    pub geom_type: u8,
    pub flags: u8,
    pub lane_count: u8,
    pub transit_color: u32,
    pub transit_ordinal: u8,
    pub transit_lanes: u8,
    pub transit_taper: u8,
}

impl SlimInstance {
    fn parse(buf: &[u8]) -> Result<SlimInstance> {
        if buf.len() < SLIM_INSTANCE_LEN {
            return err("a .mamaps slim instance runs past its payload");
        }
        // The ref head is lane A's record; decode it with lane A's parser so
        // the two can never disagree about the first 8 bytes.
        let slim = SharedSlimRef::parse(buf)?;
        let part_offset =
            u32::from_le_bytes([buf[8], buf[9], buf[10], buf[11]]);
        let part_count =
            u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]);
        if part_count == 0 {
            return err("a .mamaps slim instance has no geometry");
        }
        let layer_id = buf[16];
        if buf[17] != 0 || buf[18] != 0 || buf[19] != 0 {
            return err("a .mamaps slim instance has non-zero reserved bytes");
        }
        let geom_type = buf[20];
        if !matches!(geom_type, GEOM_LINE | GEOM_POLYGON | GEOM_POINT) {
            return err(format!(
                "a .mamaps slim instance has geometry type {geom_type}, which this format does not carry"
            ));
        }
        let flags = buf[21];
        if flags
            & !(FLAG_IS_TUNNEL
                | FLAG_IS_BRIDGE
                | FLAG_IS_LINK
                | FLAG_DETAIL_NUMERIC
                | FLAG_IS_ONEWAY)
            != 0
        {
            return err("a .mamaps slim instance sets unknown flags");
        }
        if buf[23] != 0 || buf[31] != 0 {
            return err("a .mamaps slim instance has a non-zero reserved byte");
        }
        Ok(SlimInstance {
            logical_id: slim.logical_id,
            view_bits: slim.view_bits,
            part_offset,
            part_count,
            layer_id,
            geom_type,
            flags,
            lane_count: buf[22],
            transit_color: u32::from_le_bytes([buf[24], buf[25], buf[26], buf[27]]),
            transit_ordinal: buf[28],
            transit_lanes: buf[29],
            transit_taper: buf[30],
        })
    }
}

/// One slim layer's refs, part table and coordinate arena, decoded.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SlimLayer {
    pub layer_id: u8,
    pub instances: Vec<SlimInstance>,
    pub parts: Vec<Part>,
    /// `x, y` pairs in extent units, in parts-table order like `Body`'s.
    pub coords: Vec<(i16, i16)>,
}

/// A whole slim tile body: per-layer refs plus geometry, no side tables.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct SlimBody {
    pub extent: u16,
    pub layers: Vec<SlimLayer>,
    pub convention: Option<MarkingConvention>,
    pub heightmap: Option<Heightmap>,
}

impl SlimBody {
    /// Parse a version-8 slim body: the same 16-byte header shape as `Body`
    /// (magic `MBD`, `raw_len`, `extent`, layer count, flags, reserved), a
    /// 12-byte index entry per layer, then per layer slim instances, a part
    /// table and a varint coord arena. Every count is bounded by the slice it
    /// is read from before anything is allocated, like `Body::parse`.
    pub(crate) fn parse(buf: &[u8]) -> Result<SlimBody> {
        if buf.len() < SLIM_HEADER_LEN {
            return err("a .mamaps slim body is shorter than its own header");
        }
        if buf[0..3] != *b"MBD" {
            return err("not a .mamaps tile body (bad magic)");
        }
        if buf[3] != V8_FORMAT_VERSION {
            return err(format!("not a v8 slim body (version {})", buf[3]));
        }
        let raw_len = u32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]) as usize;
        if raw_len != buf.len() {
            return err(format!(
                "a .mamaps slim body declares {raw_len} bytes but is {}",
                buf.len(),
            ));
        }
        let extent = u16::from_le_bytes([buf[8], buf[9]]);
        if extent == 0 {
            return err("a .mamaps slim body has a zero extent");
        }
        let layer_count = buf[10] as usize;
        let flags = buf[11];
        if flags & !(BODY_FLAG_HEIGHTMAP | BODY_FLAG_ROAD_LANES) != 0 {
            return err(format!("unknown slim body flags {flags:#04x}"));
        }
        if u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]) != 0 {
            return err("a .mamaps slim body has a non-zero reserved word");
        }
        let index_end = SLIM_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        if index_end > buf.len() {
            return err("a .mamaps slim body's layer index runs past its end");
        }
        let mut layers = Vec::with_capacity(layer_count);
        let mut previous: Option<u8> = None;
        for i in 0..layer_count {
            let at = SLIM_HEADER_LEN + i * LAYER_INDEX_LEN;
            let layer_id = buf[at];
            if previous.is_some_and(|p| layer_id <= p) {
                return err("a .mamaps slim body's layers are not ordered by id");
            }
            previous = Some(layer_id);
            if buf[at + 1] != 0 {
                return err("a .mamaps slim layer index has a non-zero reserved byte");
            }
            let instance_count = u16::from_le_bytes([buf[at + 2], buf[at + 3]]) as usize;
            let offset =
                u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]])
                    as usize;
            let length =
                u32::from_le_bytes([buf[at + 8], buf[at + 9], buf[at + 10], buf[at + 11]])
                    as usize;
            let end = offset.checked_add(length).ok_or_else(|| {
                Error("a .mamaps slim layer's extent overflows".to_string())
            })?;
            if offset < index_end || end > buf.len() {
                return err("a .mamaps slim layer's payload is outside the body");
            }
            layers.push(parse_slim_layer(layer_id, instance_count, &buf[offset..end])?);
        }
        // The optional trailing sections, in order: the marking convention
        // byte, then the heightmap, each announced by its flag rather than
        // inferred from leftover bytes — the same rule `Body::parse` follows.
        let mut convention = None;
        let mut heightmap = None;
        let mut at = align4(slim_payloads_end(buf, layer_count, index_end)?);
        if flags == 0 {
            if at != buf.len() {
                return err(format!(
                    "a .mamaps slim body has {} trailing byte(s) past its payloads",
                    buf.len().saturating_sub(at),
                ));
            }
        } else {
            if at > buf.len() {
                return err("a .mamaps slim body's trailing sections start past its end");
            }
            if flags & BODY_FLAG_ROAD_LANES != 0 {
                if at >= buf.len() {
                    return err("a .mamaps slim body ends inside its marking convention");
                }
                convention = Some(MarkingConvention::from_byte(buf[at])?);
                at = align4(at + 1);
                if at > buf.len() {
                    return err("a .mamaps slim body's convention padding runs past its end");
                }
            }
            if flags & BODY_FLAG_HEIGHTMAP != 0 {
                let (grid, used) = parse_slim_heightmap(&buf[at..])?;
                at += used;
                heightmap = Some(grid);
            }
            if at != buf.len() {
                return err(format!(
                    "a .mamaps slim body has {} trailing byte(s) past its trailing sections",
                    buf.len() - at,
                ));
            }
        }
        Ok(SlimBody { extent, layers, convention, heightmap })
    }
}

/// The unaligned end of the last slim payload, as the index declares it.
///
/// Mirrors `body`'s `payloads_end`: payloads start at the aligned `index_end`,
/// so the trailing sections start at the aligned maximum of their ends.
fn slim_payloads_end(buf: &[u8], layer_count: usize, index_end: usize) -> Result<usize> {
    let mut end = index_end;
    for i in 0..layer_count {
        let at = SLIM_HEADER_LEN + i * LAYER_INDEX_LEN;
        let offset =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]])
                as usize;
        let length =
            u32::from_le_bytes([buf[at + 8], buf[at + 9], buf[at + 10], buf[at + 11]])
                as usize;
        let payload_end = offset.checked_add(length).ok_or_else(|| {
            Error("a .mamaps slim layer's extent overflows".to_string())
        })?;
        end = end.max(payload_end);
    }
    Ok(end)
}

/// One slim layer payload: instances, then parts, then the coordinate arena.
///
/// Bounds before allocation throughout, like `parse_layer`: a corrupt body
/// must fail here, not by indexing past a slice or allocating on a count it
/// read out of the same corrupt bytes.
fn parse_slim_layer(layer_id: u8, instance_count: usize, buf: &[u8]) -> Result<SlimLayer> {
    let instances_len =
        instance_count.checked_mul(SLIM_INSTANCE_LEN).ok_or_else(|| {
            Error("a .mamaps slim layer overflows".to_string())
        })?;
    if instances_len > buf.len() {
        return err("a .mamaps slim layer's instances run past its payload");
    }
    let mut instances = Vec::with_capacity(instance_count);
    let mut parts_needed = 0usize;
    for i in 0..instance_count {
        let instance = SlimInstance::parse(&buf[i * SLIM_INSTANCE_LEN..])?;
        if instance.layer_id != layer_id {
            return err(format!(
                "a .mamaps slim instance in layer {layer_id} names layer {}",
                instance.layer_id,
            ));
        }
        let end = (instance.part_offset as usize)
            .checked_add(instance.part_count as usize)
            .ok_or_else(|| Error("a .mamaps slim instance's parts overflow".to_string()))?;
        parts_needed = parts_needed.max(end);
        instances.push(instance);
    }

    let parts_at = instances_len;
    let parts_len = parts_needed.checked_mul(PART_ENTRY_LEN).ok_or_else(|| {
        Error("a .mamaps slim layer's parts overflow".to_string())
    })?;
    match parts_at.checked_add(parts_len) {
        Some(end) if end <= buf.len() => {}
        _ => return err("a .mamaps slim layer's parts run past its payload"),
    }
    let mut parts = Vec::with_capacity(parts_needed);
    let mut coords_needed = 0usize;
    for i in 0..parts_needed {
        let at = parts_at + i * PART_ENTRY_LEN;
        let coord_start =
            u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]]) as usize;
        let point_count =
            u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]])
                as usize;
        let winding = u16::from_le_bytes([buf[at + 8], buf[at + 9]]);
        if !matches!(winding, WINDING_OUTER | WINDING_HOLE) {
            return err(format!(
                "a .mamaps slim part has winding {winding}, which is neither outer nor hole"
            ));
        }
        if u16::from_le_bytes([buf[at + 10], buf[at + 11]]) != 0 {
            return err("a .mamaps slim part has a non-zero reserved half-word");
        }
        let end = coord_start.checked_add(point_count).ok_or_else(|| {
            Error("a .mamaps slim part's coordinates overflow".to_string())
        })?;
        coords_needed = coords_needed.max(end);
        parts.push(Part {
            coord_start: coord_start as u32,
            point_count: point_count as u32,
            winding,
        });
    }

    let coords_at = align4(parts_at + parts_len);
    if coords_at > buf.len() {
        return err("a .mamaps slim layer's coordinate arena starts past its payload");
    }
    // Walked in parts-table order, like `Body`'s: each part's points are
    // deltas from the one before it, restarting at every part.
    let mut coords = Vec::with_capacity(coords_needed);
    let mut at = coords_at;
    for part in &parts {
        if part.coord_start as usize != coords.len() {
            return err(format!(
                "a .mamaps slim part claims to start at point {} but the arena is {} points in",
                part.coord_start,
                coords.len(),
            ));
        }
        let (mut x, mut y) = (0i32, 0i32);
        for _ in 0..part.point_count {
            let rest =
                buf.get(at..).ok_or_else(|| Error("a .mamaps slim arena ends early".to_string()))?;
            let (dx, used) = read_uvarint(rest)?;
            at += used;
            let rest =
                buf.get(at..).ok_or_else(|| Error("a .mamaps slim arena ends early".to_string()))?;
            let (dy, used) = read_uvarint(rest)?;
            at += used;
            x = x
                .checked_add(zigzag(dx))
                .ok_or_else(|| Error("a .mamaps slim coordinate delta overflows".to_string()))?;
            y = y
                .checked_add(zigzag(dy))
                .ok_or_else(|| Error("a .mamaps slim coordinate delta overflows".to_string()))?;
            let (Ok(x16), Ok(y16)) = (i16::try_from(x), i16::try_from(y)) else {
                return err("a .mamaps slim coordinate delta walks outside the extent an i16 holds");
            };
            coords.push((x16, y16));
        }
    }
    if coords.len() != coords_needed {
        return err(format!(
            "a .mamaps slim layer decoded {} points where its parts want {coords_needed}",
            coords.len(),
        ));
    }
    Ok(SlimLayer { layer_id, instances, parts, coords })
}

/// One unsigned LEB128 varint and the bytes it consumed.
///
/// `proto::Reader` does not expose its cursor, so the slim arena decodes
/// inline — the same wire `proto::Writer::uvarint` writes, seven bits at a
/// time, low group first.
fn read_uvarint(buf: &[u8]) -> Result<(u64, usize)> {
    let mut val: u64 = 0;
    for (i, &b) in buf.iter().enumerate().take(10) {
        val |= ((b & 0x7F) as u64) << (7 * i);
        if b & 0x80 == 0 {
            return Ok((val, i + 1));
        }
    }
    err("a truncated or overlong varint")
}

fn zigzag(v: u64) -> i32 {
    crate::proto::zigzag_decode(v) as i32
}

/// The per-tile heightmap trailing a slim body: a `u16` side length then
/// `dim * dim` `u16` samples, row-major. The same wire as `Body`'s — terrain
/// is sampled per tile, not shared.
fn parse_slim_heightmap(buf: &[u8]) -> Result<(Heightmap, usize)> {
    if buf.len() < 2 {
        return err("a .mamaps slim heightmap ends before its dimension");
    }
    let dim = u16::from_le_bytes([buf[0], buf[1]]);
    if dim == 0 {
        return err("a .mamaps slim heightmap has a zero dimension");
    }
    let cells = dim as usize * dim as usize;
    let bytes = cells.checked_mul(2).ok_or_else(|| {
        Error("a .mamaps slim heightmap's grid overflows".to_string())
    })?;
    let mut at = 2usize;
    if at + bytes > buf.len() {
        return err("a .mamaps slim heightmap's samples run past the body");
    }
    let mut samples = Vec::with_capacity(cells);
    for i in 0..cells {
        let o = at + i * 2;
        samples.push(u16::from_le_bytes([buf[o], buf[o + 1]]));
    }
    at += bytes;
    let aligned = align4(at);
    if aligned > buf.len() {
        return err("a .mamaps slim heightmap's padding runs past the body");
    }
    Ok((Heightmap { dim, samples }, aligned))
}

/// Resolve a slim body against the shared table into a full [`Body`].
///
/// `logical_id → row → pools`: names materialise a per-tile table in
/// first-use order, and each attribute side table appears exactly when some
/// feature needs a non-default entry — a tile of all defaults carries no
/// trailing sections, the same omission rule the v7 writer applies, so a
/// resolved tile compares equal to the v7 body it was built from. Geometry
/// comes from the per-tile arena: v8.0 has no geometry pool, so parts and
/// coords are copied through with their starts rebased to the resolved layer.
pub(crate) fn resolve_body(shared: &SharedView, slim: &SlimBody) -> Result<Body> {
    let mut names: Vec<String> = Vec::new();
    let mut layers = Vec::with_capacity(slim.layers.len());
    let mut ids: Vec<(u8, Vec<u64>)> = Vec::new();
    let mut turn_lanes: Vec<(u8, Vec<LaneTurns>)> = Vec::new();
    let mut buildings: Vec<(u8, Vec<BuildingAttrs>)> = Vec::new();
    let mut carriageways: Vec<(u8, Vec<Carriageway>)> = Vec::new();
    for slim_layer in &slim.layers {
        let mut features = Vec::with_capacity(slim_layer.instances.len());
        let mut parts: Vec<Part> = Vec::new();
        let mut coords: Vec<(i16, i16)> = Vec::new();
        let mut layer_ids: Vec<u64> = Vec::new();
        let mut layer_turns: Vec<LaneTurns> = Vec::new();
        let mut layer_buildings: Vec<BuildingAttrs> = Vec::new();
        let mut layer_carriageways: Vec<Carriageway> = Vec::new();
        for instance in &slim_layer.instances {
            // One binary search serves both the row and its parallel stable
            // id (`SharedView::ids` runs alongside `rows`); a miss is a
            // corrupt ref, refused rather than read as a neighbour's feature.
            let row_idx = shared
                .rows
                .binary_search_by_key(&instance.logical_id, |r| r.logical_id)
                .map_err(|_| {
                    Error(format!(
                        "a .mamaps slim instance names logical row {} of {}",
                        instance.logical_id,
                        shared.rows.len(),
                    ))
                })?;
            let row = &shared.rows[row_idx];
            // `view_bits` ride the ref for the renderer's clip/simplification
            // epoch and are not geometry: resolve passes them through
            // unchecked (a stale renderer refuses them; this reader assembles
            // the parts the instance names).
            let _ = instance.view_bits;
            // The instance carries the small inline fields; the row carries
            // the shared ones. The two must agree where they overlap, or a
            // mis-pointed ref would silently restyle a feature.
            check_row_matches_instance(row, instance)?;
            let name_idx = match shared.row_name(row) {
                None => NAME_NONE,
                Some(name) => {
                    let pos = match names.iter().position(|n| n == name) {
                        Some(i) => i + 1,
                        None => {
                            names.push(name.to_string());
                            names.len()
                        }
                    };
                    u16::try_from(pos).map_err(|_| {
                        Error(
                            "a .mamaps tile names more than 65535 distinct strings".to_string(),
                        )
                    })?
                }
            };
            let end = (instance.part_offset as usize)
                .checked_add(instance.part_count as usize)
                .filter(|end| *end <= slim_layer.parts.len())
                .ok_or_else(|| {
                    Error(
                        "a .mamaps slim instance indexes parts its layer does not carry"
                            .to_string(),
                    )
                })?;
            let parts_offset = parts.len() as u32;
            for part in &slim_layer.parts[instance.part_offset as usize..end] {
                let coord_end = (part.coord_start as usize)
                    .checked_add(part.point_count as usize)
                    .filter(|end| *end <= slim_layer.coords.len())
                    .ok_or_else(|| {
                        Error(
                            "a .mamaps slim part indexes points its layer does not carry"
                                .to_string(),
                        )
                    })?;
                coords.extend_from_slice(
                    &slim_layer.coords[part.coord_start as usize..coord_end],
                );
                parts.push(Part {
                    coord_start: coords.len() as u32 - part.point_count,
                    point_count: part.point_count,
                    winding: part.winding,
                });
            }
            features.push(Feature {
                kind: row.kind,
                kind_detail: row.kind_detail,
                geom_type: instance.geom_type,
                flags: instance.flags,
                name_idx,
                parts_offset,
                part_count: instance.part_count,
                transit_color: instance.transit_color,
                transit_ordinal: instance.transit_ordinal,
                transit_lanes: instance.transit_lanes,
                transit_taper: instance.transit_taper,
                lane_count: instance.lane_count,
            });
            resolve_attrs(
                shared,
                row,
                row_idx,
                &mut layer_ids,
                &mut layer_turns,
                &mut layer_buildings,
                &mut layer_carriageways,
            )?;
        }
        let layer_id = slim_layer.layer_id;
        if layer_ids.iter().any(|id| *id != ID_NONE) {
            ids.push((layer_id, layer_ids));
        }
        if layer_turns.iter().any(|t| !t.is_empty()) {
            turn_lanes.push((layer_id, layer_turns));
        }
        if layer_buildings.iter().any(|a| *a != BuildingAttrs::default()) {
            buildings.push((layer_id, layer_buildings));
        }
        if layer_carriageways.iter().any(|c| !c.is_empty()) {
            carriageways.push((layer_id, layer_carriageways));
        }
        layers.push(Layer { layer_id, features, parts, coords });
    }
    Ok(Body {
        extent: slim.extent,
        layers,
        names,
        ids,
        turn_lanes,
        buildings,
        heightmap: slim.heightmap.clone(),
        carriageways,
        convention: slim.convention,
    })
}

/// The row and the instance must agree where they overlap.
///
/// The row carries the feature's identity (`kind`, `kind_detail`, and the
/// numeric-detail bit); the instance carries the per-tile view of it
/// (geometry type, feature flags, lane count, transit styling). A ref that
/// pointed at another feature's row would otherwise restyle it silently —
/// the failure here names both sides.
fn check_row_matches_instance(row: &SharedLogicalRow, instance: &SlimInstance) -> Result<()> {
    let numeric = row.flags & SHARED_FLAG_DETAIL_NUMERIC != 0;
    let expects_numeric = instance.flags & FLAG_DETAIL_NUMERIC != 0;
    if numeric != expects_numeric {
        return err(format!(
            "a .mamaps slim instance and logical row {} disagree about kind_detail",
            row.logical_id,
        ));
    }
    Ok(())
}

/// One row's attribute indices into the shared pools, plus its stable id.
///
/// Each index is 0 for the pool's default (lane A's reader treats a missing
/// pool as all-defaults, so an empty pool with index 0 reads as default
/// without special-casing); anything above a present pool's length is
/// corruption. The stable id comes from the row's own slot in the parallel
/// id vector — `ID_NONE` for junction-style rows with no stable identity —
/// so every junction still costs its slot rather than misaligning the rest.
#[allow(clippy::too_many_arguments)]
fn resolve_attrs(
    shared: &SharedView,
    row: &SharedLogicalRow,
    row_idx: usize,
    ids: &mut Vec<u64>,
    turn_lanes: &mut Vec<LaneTurns>,
    buildings: &mut Vec<BuildingAttrs>,
    carriageways: &mut Vec<Carriageway>,
) -> Result<()> {
    // `None` is the pool's default (index 0 reads as default even against an
    // empty pool, so a section that carries no pool at all needs no
    // special-casing); anything above a present pool's length is corruption.
    let idx = |pool_len: usize, idx: u32, what: &str| -> Result<Option<usize>> {
        if idx == 0 {
            return Ok(None);
        }
        let i = idx as usize;
        if i >= pool_len {
            return err(format!(
                "shared row {} names {what} {idx} of {pool_len}",
                row.logical_id,
            ));
        }
        Ok(Some(i))
    };
    buildings.push(
        match idx(shared.buildings.len(), row.building_idx, "building")? {
            None => BuildingAttrs::default(),
            Some(i) => shared.buildings[i].to_body(),
        },
    );
    carriageways.push(
        match idx(shared.carriageways.len(), row.carriageway_idx, "carriageway")? {
            None => Carriageway::default(),
            Some(i) => shared.carriageways[i].to_body(),
        },
    );
    turn_lanes.push(
        match idx(shared.lane_turns.len(), row.lane_turns_idx, "lane-turns")? {
            None => LaneTurns::default(),
            Some(i) => shared.lane_turns[i].to_body(),
        },
    );
    ids.push(if shared.ids.is_empty() {
        ID_NONE
    } else {
        *shared.ids.get(row_idx).ok_or_else(|| {
            Error(format!(
                "a shared id pool has {} id(s) for {} row(s)",
                shared.ids.len(),
                shared.rows.len(),
            ))
        })?
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mamaps::dict;
    use crate::mamaps::index::{ROOT_ENTRY_LEN, LEAF_ENTRY_LEN};
    use crate::mamaps::shared::{
        SharedBuildingAttrs, SharedCarriageway, SharedHeader, SharedPoolDirEntry,
        SharedSlimRef, SharedStringPool, SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN,
        SHARED_HEADER_LEN, SHARED_KIND_BUILDINGS, SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_ID_RUNS,
        SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_STRINGS, SHARED_POOL_ENTRY_LEN,
        SHARED_ROW_LEN, SHARED_NAME_NONE,
    };
    use crate::pmtiles::tile_id;
    use std::cell::RefCell;

    /// Where the slim payload starts: header plus one index entry, already
    /// 4-aligned. The corruption tests below mutate payload bytes by absolute
    /// offset, so this — not a literal — anchors them.
    const PAYLOAD_AT: usize = SLIM_HEADER_LEN + LAYER_INDEX_LEN;

    // Fail-watch convention (lane C): each test pins one wire or behaviour
    // fact as a literal, so a revert of that fact quotes its failure (`left`
    // vs `right`) rather than a vague mismatch. Revert → quote → restore.
    // These tests were written without a rebuild (lane orders); they run with
    // `cargo test -p tilecodec` once the lane's turn in the build queue comes.

    /// A [`RangeReader`] over bytes in memory that logs every range asked for,
    /// so a test can assert on the **number of round trips** as well as the
    /// bytes. The same shape `mod.rs`'s `Counting` uses, local so this lane
    /// touches only `read.rs`.
    struct Memory {
        bytes: Vec<u8>,
        requests: RefCell<Vec<(u64, u32)>>,
    }

    impl RangeReader for Memory {
        fn read(&self, offset: u64, length: u32) -> Result<Vec<u8>> {
            self.requests.borrow_mut().push((offset, length));
            if offset >= self.bytes.len() as u64 {
                return Ok(Vec::new());
            }
            let end = (offset + length as u64).min(self.bytes.len() as u64);
            Ok(self.bytes[offset as usize..end as usize].to_vec())
        }
    }

    /// A one-line v7 body whose contents depend on `seed`.
    fn body_for(seed: i16) -> Body {
        let mut roads = Layer::new(dict::LAYER_ROADS);
        roads.features.push(Feature {
            kind: 45,
            kind_detail: dict::NONE,
            geom_type: GEOM_LINE,
            flags: 0,
            name_idx: NAME_NONE,
            parts_offset: 0,
            part_count: 1,
            transit_color: 0,
            transit_ordinal: 0,
            transit_lanes: 0,
            transit_taper: 0,
            lane_count: 0,
        });
        roads.parts.push(Part { coord_start: 0, point_count: 2, winding: WINDING_OUTER });
        roads.coords = vec![(0, 0), (seed, seed)];
        Body {
            extent: crate::mamaps::body::DEFAULT_EXTENT,
            layers: vec![roads],
            names: Vec::new(),
            ids: Vec::new(),
            turn_lanes: Vec::new(),
            buildings: Vec::new(),
            heightmap: None,
            carriageways: Vec::new(),
            convention: None,
        }
    }

    /// A minimal one-tile v7 archive, hand-built (no `write` feature needed):
    /// `[header 128][dict][root 1×32][leaf 1×16][body]`. Everything is
    /// addressed through header offsets, like the real writer emits.
    fn v7_archive() -> (Vec<u8>, Body) {
        let body = body_for(100);
        let raw = crate::mamaps::body::serialize(&body).expect("serialize");
        let dict = Dictionary::schema().serialize();
        let mut header = Header {
            flags: 0,
            compression: COMPRESSION_NONE,
            layer_count: Dictionary::schema().layers.len() as u8,
            min_zoom: 0,
            max_zoom: 0,
            build_id: 0x0123_4567_89AB_CDEF,
            file_len: 0,
            dict_offset: HEADER_LEN as u64,
            dict_len: dict.len() as u32,
            leaf_entry_capacity: 4096,
            root_offset: 0,
            root_len: ROOT_ENTRY_LEN as u32,
            leaf_count: 1,
            leaf_offset: 0,
            leaf_len: LEAF_ENTRY_LEN as u64,
            data_offset: 0,
            data_len: raw.len() as u64,
            tiles_addressed: 1,
            bodies_written: 1,
            min_lon_e7: 0,
            min_lat_e7: 0,
            max_lon_e7: 0,
            max_lat_e7: 0,
            // A v7 archive: no shared section, so the header stays 128 bytes
            // with version byte 7 and lane B's tail fields zeroed.
            shared_offset: 0,
            shared_len: 0,
            shared_flags: 0,
            shared_pools: 0,
        };
        header.root_offset = header.dict_offset + header.dict_len as u64;
        header.leaf_offset = header.root_offset + header.root_len as u64;
        header.data_offset = header.leaf_offset + header.leaf_len;
        header.file_len = header.data_offset + header.data_len;
        let mut root = Vec::new();
        root.extend_from_slice(&tile_id(0, 0, 0).to_le_bytes());
        root.extend_from_slice(&0u64.to_le_bytes());
        root.extend_from_slice(&0u64.to_le_bytes());
        root.extend_from_slice(&1u32.to_le_bytes());
        root.extend_from_slice(&0u32.to_le_bytes());
        let mut leaf = Vec::new();
        leaf.extend_from_slice(&0u32.to_le_bytes());
        leaf.extend_from_slice(&1u32.to_le_bytes());
        leaf.extend_from_slice(&0u32.to_le_bytes());
        leaf.extend_from_slice(&(raw.len() as u32).to_le_bytes());
        let mut bytes = header.serialize();
        bytes.extend_from_slice(&dict);
        bytes.extend_from_slice(&root);
        bytes.extend_from_slice(&leaf);
        bytes.extend_from_slice(&raw);
        assert_eq!(bytes.len() as u64, header.file_len);
        (bytes, body)
    }

    /// One uvarint, appended — the same wire `proto::Writer::uvarint` writes.
    fn push_uvarint(out: &mut Vec<u8>, mut value: u64) {
        while value >= 0x80 {
            out.push((value as u8) | 0x80);
            value >>= 7;
        }
        out.push(value as u8);
    }

    /// A whole shared section for the resolve tests: two strings, two rows
    /// (a named road with a carriageway + lane turns, an unnamed default
    /// road), attr pools seeded with their index-0 defaults, and id runs for
    /// two stable ids. Built with lane A's own serializers so the test pins
    /// the shared contract, not a parallel encoding.
    fn shared_section() -> Vec<u8> {
        use crate::mamaps::shared::encode_id_runs;
        let strings = SharedStringPool { names: vec!["Oak Ave".to_string(), "Main St".to_string()] }.serialize();
        let rows = [
            SharedLogicalRow {
                logical_id: 7,
                name_ref: 1,
                kind: 45,
                kind_detail: dict::NONE,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 1,
                lane_turns_idx: 1,
                flags: 0,
            },
            SharedLogicalRow {
                logical_id: 9,
                name_ref: SHARED_NAME_NONE,
                kind: 45,
                kind_detail: dict::NONE,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 0,
                lane_turns_idx: 0,
                flags: 0,
            },
        ];
        let mut rows_pool = Vec::new();
        for r in &rows {
            rows_pool.extend_from_slice(&r.serialize());
        }
        let buildings = {
            let mut out = Vec::new();
            out.extend_from_slice(&1u32.to_le_bytes());
            out.extend_from_slice(&SharedBuildingAttrs::default().serialize());
            out
        };
        let carriageways = {
            let mut out = Vec::new();
            out.extend_from_slice(&2u32.to_le_bytes());
            out.extend_from_slice(&SharedCarriageway::default().serialize());
            out.extend_from_slice(
                &SharedCarriageway { forward: 2, backward: 2, solid_dividers: 0b010 }.serialize(),
            );
            while out.len() % 4 != 0 {
                out.push(0);
            }
            out
        };
        let lane_turns = {
            let mut out = Vec::new();
            out.extend_from_slice(&2u32.to_le_bytes());
            out.extend_from_slice(&[0u8, 0u8]);
            out.extend_from_slice(&[2u8, 1u8]);
            for m in [1u16, 2u16, 4u16] {
                out.extend_from_slice(&m.to_le_bytes());
            }
            while out.len() % 4 != 0 {
                out.push(0);
            }
            out
        };
        let mut id_pool = encode_id_runs(&[12_345_678_901, ID_NONE]).expect("encode");
        while id_pool.len() % 4 != 0 {
            id_pool.push(0);
        }
        let pools: Vec<(u8, u32, Vec<u8>)> = vec![
            (SHARED_KIND_STRINGS, 0, strings),
            (SHARED_KIND_ROWS, SHARED_ROW_LEN as u32, rows_pool),
            (SHARED_KIND_BUILDINGS, SHARED_BUILDING_LEN as u32, buildings),
            (SHARED_KIND_CARRIAGEWAYS, SHARED_CARRIAGEWAY_LEN as u32, carriageways),
            (SHARED_KIND_LANE_TURNS, 0, lane_turns),
            (SHARED_KIND_ID_RUNS, 0, id_pool),
        ];
        let mut offset = (SHARED_HEADER_LEN + pools.len() * SHARED_POOL_ENTRY_LEN) as u64;
        let mut dir = Vec::new();
        for (kind, elem_len, bytes) in &pools {
            dir.push(SharedPoolDirEntry { kind: *kind, elem_len: *elem_len, offset, len: bytes.len() as u64 });
            offset += bytes.len() as u64;
        }
        let header = SharedHeader {
            flags: 0,
            row_count: 2,
            string_count: 2,
            pool_count: pools.len() as u32,
            total_len: offset as u32,
            id_run_count: 2,
        };
        let mut out = header.serialize();
        for e in &dir {
            out.extend_from_slice(&e.serialize());
        }
        for (_, _, bytes) in &pools {
            out.extend_from_slice(bytes);
        }
        assert_eq!(out.len() as u32, header.total_len);
        out
    }

    /// Append one 12-byte slim layer index entry.
    fn slim_index(out: &mut Vec<u8>, layer_id: u8, count: u16, offset: u32, len: u32) {
        out.push(layer_id);
        out.push(0);
        out.extend_from_slice(&count.to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        out.extend_from_slice(&len.to_le_bytes());
    }

    /// Append one 32-byte slim instance record.
    #[allow(clippy::too_many_arguments)]
    fn slim_instance(
        out: &mut Vec<u8>,
        logical_id: u32,
        view_bits: u32,
        part_offset: u32,
        part_count: u32,
        layer_id: u8,
    ) {
        out.extend_from_slice(&SharedSlimRef { logical_id, view_bits }.serialize());
        out.extend_from_slice(&part_offset.to_le_bytes());
        out.extend_from_slice(&part_count.to_le_bytes());
        out.push(layer_id);
        out.extend_from_slice(&[0u8, 0u8, 0u8]);
        out.push(GEOM_LINE);
        out.push(0);
        out.push(0);
        out.push(0);
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&[0u8, 0u8, 0u8, 0u8]);
    }

    /// Append one 12-byte part entry.
    fn slim_part(out: &mut Vec<u8>, coord_start: u32, point_count: u32) {
        out.extend_from_slice(&coord_start.to_le_bytes());
        out.extend_from_slice(&point_count.to_le_bytes());
        out.extend_from_slice(&WINDING_OUTER.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes());
    }

    /// A slim body resolving against [`shared_section`]: one roads layer with
    /// two instances (row 7 with parts 0..1, row 9 with parts 1..2), a part
    /// table and a four-point arena. `flags` selects the trailing sections.
    fn slim_body(flags: u8) -> Vec<u8> {
        let mut payload = Vec::new();
        slim_instance(&mut payload, 7, 0, 0, 1, dict::LAYER_ROADS);
        slim_instance(&mut payload, 9, 0, 1, 1, dict::LAYER_ROADS);
        slim_part(&mut payload, 0, 2);
        slim_part(&mut payload, 2, 2);
        while payload.len() % 4 != 0 {
            payload.push(0);
        }
        // Deltas restart at each part: part 0 runs (0,0)->(10,0),
        // part 1 runs (5,5)->(15,5). Zigzagged: 0,0,20,0 then 10,10,20,0.
        for d in [0u64, 0, 20, 0, 10, 10, 20, 0] {
            push_uvarint(&mut payload, d);
        }
        let index_end = align4(SLIM_HEADER_LEN + LAYER_INDEX_LEN);
        let mut out = Vec::new();
        out.extend_from_slice(b"MBD");
        out.push(V8_FORMAT_VERSION);
        out.extend_from_slice(&0u32.to_le_bytes());
        out.extend_from_slice(&crate::mamaps::body::DEFAULT_EXTENT.to_le_bytes());
        out.push(1);
        out.push(flags);
        out.extend_from_slice(&0u32.to_le_bytes());
        slim_index(&mut out, dict::LAYER_ROADS, 2, index_end as u32, payload.len() as u32);
        while out.len() % 4 != 0 {
            out.push(0);
        }
        assert_eq!(out.len(), index_end);
        out.extend_from_slice(&payload);
        if flags & BODY_FLAG_ROAD_LANES != 0 {
            out.push(MarkingConvention::default().to_byte());
            while out.len() % 4 != 0 {
                out.push(0);
            }
        }
        if flags & BODY_FLAG_HEIGHTMAP != 0 {
            out.extend_from_slice(&2u16.to_le_bytes());
            for _ in 0..4 {
                out.extend_from_slice(&32768u16.to_le_bytes());
            }
            while out.len() % 4 != 0 {
                out.push(0);
            }
        }
        let raw_len = out.len() as u32;
        out[4..8].copy_from_slice(&raw_len.to_le_bytes());
        out
    }

    /// Fail-watched: the v7 legacy path through the new code. A hand-built
    /// v7 archive opens in one request, `tile()` and `tile_resolved()` agree
    /// byte for byte, the request counts are the legacy 1/2/1, and no shared
    /// request is ever made.
    #[test]
    fn v7_tiles_read_byte_identical_through_the_new_code() {
        let (bytes, body) = v7_archive();
        let mut a = MamapsArchive::open(Memory { bytes, requests: RefCell::new(Vec::new()) })
            .expect("open");
        assert_eq!(
            a.reader.requests.borrow().as_slice(),
            &[(0, OPEN_PREFIX_BYTES)],
            "a cold open is one request"
        );
        assert!(!a.has_shared(), "a v7 archive carries no shared section");
        assert!(a.shared_table().expect("shared").is_none(), "and none is fetched");
        assert_eq!(a.reader.requests.borrow().len(), 1, "asking changed nothing");

        let legacy = a.tile(0, 0, 0).expect("read").expect("present");
        assert_eq!(legacy, body);
        assert_eq!(a.reader.requests.borrow().len(), 1 + 2, "a cold tile is a leaf plus a body");
        let resolved = a.tile_resolved(0, 0, 0).expect("read").expect("present");
        assert_eq!(resolved, legacy, "resolved == legacy, byte for byte");
        assert_eq!(resolved, body);
        assert_eq!(a.reader.requests.borrow().len(), 1 + 2 + 1, "warm leaf: only the body");
        assert!(a.tile(1, 0, 0).expect("read").is_none(), "past max_zoom stays None");
        assert!(a.tile_resolved(1, 0, 0).expect("read").is_none(), "on both paths");
    }

    /// Fail-watched: a cached shared table costs no requests. Injected here
    /// (the header hook lands with lane B); the fetch path itself is pinned
    /// by `shared_table_parses_and_caches` in lane B's harness.
    #[test]
    fn a_cached_shared_table_resolves_without_requests() {
        let (bytes, _) = v7_archive();
        let mut a = MamapsArchive::open(Memory { bytes, requests: RefCell::new(Vec::new()) })
            .expect("open");
        a.shared = Some(SharedView::parse(&shared_section()).expect("shared"));
        assert!(a.shared_table().expect("shared").is_some());
        assert_eq!(a.reader.requests.borrow().len(), 1, "no fetch for a cached table");
        let slim = SlimBody::parse(&slim_body(0)).expect("slim");
        let resolved = resolve_body(a.shared_table().expect("shared").expect("present"), &slim)
            .expect("resolve");
        assert_eq!(resolved.layers.len(), 1);
        assert_eq!(resolved.layers[0].features.len(), 2);
        assert_eq!(a.reader.requests.borrow().len(), 1, "resolve is pure: still no requests");
    }

    /// Fail-watched: slim refs resolve to the bodies they were built from.
    /// Row 7 is the named road (carriageway 2/2, lane turns, stable id);
    /// row 9 is the unnamed default road. Geometry comes from the per-tile
    /// arena, names/attrs/ids from the pools.
    #[test]
    fn slim_refs_resolve_logical_id_to_row_to_pools() {
        let shared = SharedView::parse(&shared_section()).expect("shared");
        assert_eq!(shared.rows.len(), 2);
        assert_eq!(shared.row(7).expect("row 7").name_ref, 1);
        assert_eq!(shared.row_name(shared.row(7).expect("row")), Some("Oak Ave"));
        let slim = SlimBody::parse(&slim_body(0)).expect("slim");
        assert_eq!(slim.extent, crate::mamaps::body::DEFAULT_EXTENT);
        let body = resolve_body(&shared, &slim).expect("resolve");

        let layer = body.layer(dict::LAYER_ROADS).expect("roads");
        assert_eq!(layer.features.len(), 2);
        assert_eq!(layer.features[0].kind, 45);
        assert_eq!(layer.features[0].name(&body), Some("Oak Ave"));
        assert_eq!(layer.features[1].name(&body), None);
        assert_eq!(body.names, vec!["Oak Ave".to_string()], "first-use order, deduped");
        // Geometry is per-tile: two parts, four points, starts rebased.
        assert_eq!(layer.parts.len(), 2);
        assert_eq!(layer.coords, vec![(0, 0), (10, 0), (5, 5), (15, 5)]);
        assert_eq!(layer.points(&layer.parts[1])[0], (5, 5), "deltas restart per part");
        // Attrs: row 7's carriageway and lane turns, row 9's defaults.
        let cars = &body.carriageways;
        assert_eq!(cars.len(), 1, "non-default carriageways are kept");
        assert_eq!(cars[0].0, dict::LAYER_ROADS);
        assert_eq!(
            cars[0].1[0],
            Carriageway { forward: 2, backward: 2, solid_dividers: 0b010 },
        );
        assert_eq!(cars[0].1[1], Carriageway::default());
        let turns = &body.turn_lanes;
        assert_eq!(turns.len(), 1);
        assert_eq!(turns[0].1[0].forward, vec![1u16, 2u16]);
        assert_eq!(turns[0].1[0].backward, vec![4u16]);
        assert!(turns[0].1[1].is_empty());
        assert!(body.buildings.is_empty(), "all-default building attrs are omitted");
        // Ids: row 7's stable id rides its own slot; the junction-style row
        // keeps ID_NONE without misaligning the rest.
        assert_eq!(body.ids, vec![(dict::LAYER_ROADS, vec![12_345_678_901, ID_NONE])]);
        assert_eq!(body.feature_id(dict::LAYER_ROADS, 0), Some(12_345_678_901));
        assert_eq!(body.feature_id(dict::LAYER_ROADS, 1), Some(ID_NONE));
        assert!(body.convention.is_none() && body.heightmap.is_none(), "no flags, no sections");
    }

    /// Fail-watched: `view_bits` are renderer epoch metadata, not geometry —
    /// a ref carrying them resolves to the same feature.
    #[test]
    fn view_bits_do_not_change_the_resolved_feature() {
        let shared = SharedView::parse(&shared_section()).expect("shared");
        let mut bytes = slim_body(0);
        bytes[PAYLOAD_AT + 4..PAYLOAD_AT + 8].copy_from_slice(&0xDEAD_BEEFu32.to_le_bytes());
        let slim = SlimBody::parse(&bytes).expect("slim");
        assert_eq!(slim.layers[0].instances[0].view_bits, 0xDEAD_BEEF);
        let body = resolve_body(&shared, &slim).expect("resolve");
        assert_eq!(body.layer(dict::LAYER_ROADS).expect("roads").features.len(), 2);
        assert_eq!(
            body.layer(dict::LAYER_ROADS).expect("roads").features[0].name(&body),
            Some("Oak Ave"),
        );
    }

    /// Fail-watched: the trailing sections ride their flags. The convention
    /// byte follows the payloads, then the heightmap grid.
    #[test]
    fn convention_and_heightmap_trail_their_flags() {
        let shared = SharedView::parse(&shared_section()).expect("shared");
        let slim = SlimBody::parse(&slim_body(BODY_FLAG_ROAD_LANES | BODY_FLAG_HEIGHTMAP))
            .expect("slim");
        assert_eq!(slim.convention, Some(MarkingConvention::default()));
        let grid = slim.heightmap.clone().expect("heightmap");
        assert_eq!((grid.dim, grid.samples.len()), (2, 4));
        let body = resolve_body(&shared, &slim).expect("resolve");
        assert_eq!(body.convention, Some(MarkingConvention::default()));
        assert_eq!(body.heightmap, Some(grid));
    }

    /// Fail-watched: corrupt slim bodies fail here, never past a slice end.
    #[test]
    fn a_corrupt_slim_body_is_refused() {
        let good = slim_body(0);
        assert!(SlimBody::parse(&good[..SLIM_HEADER_LEN - 1]).is_err(), "shorter than the header");
        let cases: &[(&str, fn(&mut Vec<u8>))] = &[
            ("bad magic", |b| b[0] = b'X'),
            ("a v7 version byte", |b| b[3] = 7),
            ("a zero extent", |b| b[8..10].copy_from_slice(&0u16.to_le_bytes())),
            ("an unknown flag", |b| b[11] = 0x80),
            ("a dirty reserved word", |b| b[12] = 1),
            ("a layer count past the index", |b| b[10] = 200),
            ("a payload outside the body", |b| {
                b[20..24].copy_from_slice(&999_999u32.to_le_bytes())
            }),
            ("trailing garbage with no flags", |b| b.push(0xFF)),
            ("an instance with no geometry", |b| {
                b[PAYLOAD_AT + 12..PAYLOAD_AT + 16].copy_from_slice(&0u32.to_le_bytes())
            }),
            ("an instance of another layer", |b| b[PAYLOAD_AT + 16] = dict::LAYER_WATER),
            ("a bad part winding", |b| {
                let at = PAYLOAD_AT + 2 * SLIM_INSTANCE_LEN;
                b[at + 8..at + 10].copy_from_slice(&7u16.to_le_bytes())
            }),
        ];
        for (what, break_it) in cases {
            let mut bytes = good.clone();
            break_it(&mut bytes);
            // Length-declared bodies must restate their length after mutation.
            let len = bytes.len() as u32;
            if bytes.len() >= 8 {
                bytes[4..8].copy_from_slice(&len.to_le_bytes());
            }
            assert!(SlimBody::parse(&bytes).is_err(), "{what} should be refused");
        }
    }

    /// Fail-watched: corrupt refs fail at resolve, naming the row.
    #[test]
    fn a_ref_to_no_row_or_a_mismatched_row_is_refused_at_resolve() {
        let shared = SharedView::parse(&shared_section()).expect("shared");
        let mut slim = SlimBody::parse(&slim_body(0)).expect("slim");
        slim.layers[0].instances[0].logical_id = 404;
        let failure = resolve_body(&shared, &slim).expect_err("no such row");
        assert!(failure.0.contains("404"), "{}", failure.0);

        // A row whose numeric-detail bit disagrees with the instance's flag.
        let mut slim = SlimBody::parse(&slim_body(0)).expect("slim");
        slim.layers[0].instances[0].flags |= FLAG_DETAIL_NUMERIC;
        assert!(resolve_body(&shared, &slim).is_err(), "numeric-detail mismatch");

        // An attribute index past its pool.
        let mut shared = shared.clone();
        shared.rows[0].carriageway_idx = 99;
        let slim = SlimBody::parse(&slim_body(0)).expect("slim");
        let failure = resolve_body(&shared, &slim).expect_err("past the pool");
        assert!(failure.0.contains("99"), "{}", failure.0);
    }
}
