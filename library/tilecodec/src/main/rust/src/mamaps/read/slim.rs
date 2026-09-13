use crate::mamaps::body::{BODY_FLAG_EXTENDED_COUNTS, BODY_FLAG_HEIGHTMAP, BODY_FLAG_ROAD_LANES, Heightmap, LAYER_INDEX_LEN, Layer, MarkingConvention, PART_ENTRY_LEN, Part, WINDING_HOLE, WINDING_OUTER, align4, parse_layer};
use crate::mamaps::header::FORMAT_VERSION_V8 as V8_FORMAT_VERSION;
use crate::mamaps::shared::SharedSlimRef;
use crate::proto::{Error, Result, err};


// ─── v8.1 slim bodies: 16B instances + mixed slim/full layers (lane B) ───────
//
// A v8 tile body carries per-instance slim records instead of full 24-byte
// feature records and no per-tile side tables at all: names, ids, colours,
// lane triples, carriageways and building attrs all resolve through the
// archive-global [`SharedView`] (lane A), keyed by `logical_id`. Geometry is
// NOT shared in v8.1 — every slim layer still carries its own part table and
// varint coord arena, encoded exactly as [`Body`] carries them — so what this
// section parses per tile is refs + parts + arena, and what it borrows from
// the shared section is everything else.
//
// Wire: the same 16-byte header shape as `Body` (magic `MBD`, `raw_len`,
// `extent`, layer count, flags, reserved) with version [`FORMAT_VERSION_V8`],
// a 12-byte index entry per layer (same stride as `Body`'s), then per layer
// EITHER a slim payload (`[16B instances][part table][align4][coord arena]`)
// OR full v7 layer bytes (`[24B features][part table][align4][coord arena]`),
// chosen per layer by the index entry's second byte (0 = full, 1 = slim) —
// and, each announced by its flag, an optional marking-convention byte then
// an optional heightmap. K≈1 layers (junction, earth, water) stay full v7
// untouched; a v7 body never reaches here:
// [`tile_resolved`](MamapsArchive::tile_resolved) sends anything that is not
// version 8 to [`Body::parse`].
//
// Lanes D (writer) and E (tiler/dump) own the other side of this wire. The
// only contract this file assumes about it is stated on each item below.

/// The tile-body version a slim body carries: lane B's v8 archive version.
///
/// Bodies version with their archive — a v8 slim body carries body version 8
/// and resolves through the shared section, while a v7 full body still
/// carries 7 and goes to `Body::parse`.

/// One per-tile slim instance's wire length: an 8-byte [`SharedSlimRef`] head
/// (parsed by lane A's parser, not re-decoded here) plus `part_offset` /
/// `part_count` (`u32` each) into the layer's own part table. 16 bytes, fully
/// packed — no reserved bytes. The layer is implicit from the payload's index
/// entry; kind, flags, lane count and transit styling resolve from the shared
/// row, never from these bytes.
pub(crate) const SLIM_INSTANCE_LEN: usize = 16;

/// Slim-body header length — the same 16-byte shape as `Body`'s.
pub(crate) const SLIM_HEADER_LEN: usize = 16;

/// Whether decompressed body bytes are a v8 slim body rather than a v7 full
/// body. Anything else — v7, corrupt, foreign — goes down the legacy path so
/// its errors are unchanged byte for byte.
pub(crate) fn is_v8_body(bytes: &[u8]) -> bool {
    bytes.len() >= 4 && bytes[0..3] == *b"MBD" && bytes[3] == V8_FORMAT_VERSION
}

/// A layer index entry's encoding byte (`buf[at + 1]`, the byte v7 keeps reserved zero):
/// full v7 layer bytes.
///
/// What keeps K≈1 layers (junction, earth, water) byte-identical v7 inside a v8 body: their index
/// entries are v7 index entries verbatim (reserved byte 0, `u16` feature count) and their payloads
/// parse with the v7 layer parser, inline styling and all, passing resolve through untouched.
pub(crate) const SLIM_INDEX_FULL: u8 = 0;
/// A layer index entry's encoding byte: a slim payload (16-byte instances plus the layer's own
/// part table and coord arena).
pub(crate) const SLIM_INDEX_SLIM: u8 = 1;

/// One slim instance: who it shares (the ref) plus its part range.
///
/// The ref head is 8 bytes on the wire ([`SharedSlimRef`]); the remaining 8 are the instance's
/// part range into its layer's own part table. Everything else — kind, kind_detail, geometry type,
/// flags, lane count, transit styling — resolves through the row the ref names at [`resolve_body`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct SlimInstance {
    pub logical_id: u32,
    pub view_bits: u32,
    pub part_offset: u32,
    pub part_count: u32,
}

impl SlimInstance {
    pub(crate) fn parse(buf: &[u8]) -> Result<SlimInstance> {
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
        Ok(SlimInstance {
            logical_id: slim.logical_id,
            view_bits: slim.view_bits,
            part_offset,
            part_count,
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

/// One layer of a v8 body, in either encoding.
///
/// Slim layers carry refs plus per-tile geometry and resolve styling from the shared section;
/// full layers carry v7 bytes verbatim and pass through untouched. The index entry's second byte
/// chooses per layer, so one body mixes both.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BodyLayer {
    Slim(SlimLayer),
    Full(Layer),
}

/// A whole v8 tile body: per-layer payloads in either encoding, no per-tile side tables.
///
/// `pub` so the tiler's tests can parse the mixed bodies they emit; the fields
/// stay `pub(crate)` where they were.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SlimBody {

    pub extent: u16,
    pub layers: Vec<BodyLayer>,
    pub convention: Option<MarkingConvention>,
    pub heightmap: Option<Heightmap>,
}

impl SlimBody {
    /// Parse a version-8 body: the same 16-byte header shape as `Body` (magic `MBD`, `raw_len`,
    /// `extent`, layer count, flags, reserved), a 12-byte index entry per layer, then per layer
    /// either a slim payload (16-byte instances, a part table and a varint coord arena) or full v7
    /// layer bytes, chosen by the index entry's second byte (0 = full, 1 = slim). Every count is
    /// bounded by the slice it is read from before anything is allocated, like `Body::parse`.
    ///
    /// `pub` so the tiler's tests can parse the mixed bodies they emit.
    pub fn parse(buf: &[u8]) -> Result<SlimBody> {
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
        if flags & !(BODY_FLAG_EXTENDED_COUNTS | BODY_FLAG_HEIGHTMAP | BODY_FLAG_ROAD_LANES) != 0 {
            return err(format!("unknown slim body flags {flags:#04x}"));
        }
        if flags & BODY_FLAG_EXTENDED_COUNTS == 0
            && u32::from_le_bytes([buf[12], buf[13], buf[14], buf[15]]) != 0
        {
            return err("a .mamaps slim body has a non-zero reserved word");
        }
        let has_extended = (flags & BODY_FLAG_EXTENDED_COUNTS) != 0;
        let mut index_end = SLIM_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        if has_extended {
            index_end = index_end.checked_add(layer_count * 4).ok_or_else(|| {
                Error("a .mamaps slim body's extended counts overflow".to_string())
            })?;
        }
        if index_end > buf.len() {
            return err("a .mamaps slim body's layer index runs past its end");
        }
        // Extended counts live right after the layer index, one u32 LE per layer in order —
        // the same rule `Body::parse` follows.
        let ext_at = SLIM_HEADER_LEN + layer_count * LAYER_INDEX_LEN;
        let mut layers = Vec::with_capacity(layer_count);
        let mut previous: Option<u8> = None;
        for i in 0..layer_count {
            let at = SLIM_HEADER_LEN + i * LAYER_INDEX_LEN;
            let layer_id = buf[at];
            if previous.is_some_and(|p| layer_id <= p) {
                return err("a .mamaps slim body's layers are not ordered by id");
            }
            previous = Some(layer_id);
            // The encoding byte: 0 is a v7 index entry verbatim (full layer bytes, with the `u16`
            // as its feature count); 1 is a slim payload (the `u16` as its instance count).
            // Anything else is a writer this reader does not speak. With extended counts the
            // `u16` is the 0xFFFE sentinel and the real count is the u32 after the index.
            let encoding = buf[at + 1];
            let record_count = if has_extended {
                u32::from_le_bytes([
                    buf[ext_at + i * 4],
                    buf[ext_at + i * 4 + 1],
                    buf[ext_at + i * 4 + 2],
                    buf[ext_at + i * 4 + 3],
                ]) as usize
            } else {
                u16::from_le_bytes([buf[at + 2], buf[at + 3]]) as usize
            };
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
            let payload = &buf[offset..end];
            match encoding {
                SLIM_INDEX_SLIM => {
                    layers.push(BodyLayer::Slim(parse_slim_layer(layer_id, record_count, payload)?));
                }
                // Full layers reuse `body`'s own layer parser — not re-decoded here — so a full
                // layer inside a v8 body is byte-for-byte the v7 layer it would have been.
                SLIM_INDEX_FULL => {
                    layers.push(BodyLayer::Full(parse_layer(
                        layer_id,
                        record_count,
                        payload,
                    )?));
                }
                other => {
                    return err(format!(
                        "a .mamaps layer index names unknown encoding {other}"
                    ));
                }
            }
        }
        // The optional trailing sections, in order: the marking convention
        // byte, then the heightmap, each announced by its flag rather than
        // inferred from leftover bytes — the same rule `Body::parse` follows.
        let mut convention = None;
        let mut heightmap = None;
        // A body with no trailing sections is exactly its payloads, unpadded — the same rule
        // `Body::parse` follows, so a mixed body whose payloads end mid-word still parses.
        let payloads_end = slim_payloads_end(buf, layer_count, index_end)?;
        if flags == 0 {
            if payloads_end != buf.len() {
                return err(format!(
                    "a .mamaps slim body has {} trailing byte(s) past its payloads",
                    buf.len().saturating_sub(payloads_end),
                ));
            }
        } else {
            let mut at = align4(payloads_end);
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
pub(crate) fn slim_payloads_end(buf: &[u8], layer_count: usize, index_end: usize) -> Result<usize> {
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

/// One slim layer payload: 16-byte instances, then parts, then the coordinate arena.
///
/// Bounds before allocation throughout, like `parse_layer`: a corrupt body must fail here, not by
/// indexing past a slice or allocating on a count it read out of the same corrupt bytes. The layer
/// is implicit from the payload's own index entry — no per-instance layer byte — so a mis-pointed
/// ref cannot mislabel it. The payload is exactly its three sections: anything past the arena is
/// corruption, refused here rather than ignored (which is also what rejects a 32-byte-era payload
/// behind the slim bit: its "part table" lands mid-record and its arena never ends where the
/// payload does).
pub(crate) fn parse_slim_layer(layer_id: u8, instance_count: usize, buf: &[u8]) -> Result<SlimLayer> {
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
    if at != buf.len() {
        return err(format!(
            "a .mamaps slim layer has {} trailing byte(s) past its coordinate arena",
            buf.len() - at,
        ));
    }
    Ok(SlimLayer { layer_id, instances, parts, coords })
}

/// One unsigned LEB128 varint and the bytes it consumed.
///
/// `proto::Reader` does not expose its cursor, so the slim arena decodes
/// inline — the same wire `proto::Writer::uvarint` writes, seven bits at a
/// time, low group first.
pub(crate) fn read_uvarint(buf: &[u8]) -> Result<(u64, usize)> {
    let mut val: u64 = 0;
    for (i, &b) in buf.iter().enumerate().take(10) {
        val |= ((b & 0x7F) as u64) << (7 * i);
        if b & 0x80 == 0 {
            return Ok((val, i + 1));
        }
    }
    err("a truncated or overlong varint")
}

pub(crate) fn zigzag(v: u64) -> i32 {
    crate::proto::zigzag_decode(v) as i32
}

/// The per-tile heightmap trailing a slim body: a `u16` side length then
/// `dim * dim` `u16` samples, row-major. The same wire as `Body`'s — terrain
/// is sampled per tile, not shared.
pub(crate) fn parse_slim_heightmap(buf: &[u8]) -> Result<(Heightmap, usize)> {
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
