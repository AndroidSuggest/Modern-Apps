//! The shared geometry pool (lane A v8.1): canonical vertex arrays with
//! per-(row, zoom) keep-masks, with their hash and wire codecs.
//!
//! Pure moves out of the former single-file shared module; nothing here changed
//! except re-rooting `super::header` to `crate::mamaps::header`, which the extra
//! module level requires.

use crate::mamaps::header::MAX_ZOOM;
use crate::proto::{err, Result};

use super::pools::{align4, push_uvarint};

/// Lane A v8.1: canonical geometry + per-zoom keep-masks (pool kind 8).
///
/// Tile arenas repeat the same full-detail vertices on every tile that clips
/// a feature; this pool interns each distinct vertex array once (keyed by
/// [`canonical_geom_hash`]) and records, per (row, zoom), which vertices
/// survive simplification ([`SharedKeepMask`]). Lanes B+ own how rows point
/// at this pool; this file owns only the pool and its encoding. Kinds 1..=7
/// are untouched: a section without kind 8 parses exactly as before, and a
/// builder that interns no geometry emits exactly the old seven pools.
///
/// Wire (variable length, `elem_len` 0, zero-padded to 4 B): `u32` LE geom
/// count (index 0 is the empty entry, like every attribute pool), then per
/// geometry `u32` LE point count + `u32` LE mask count, then the canonical
/// vertices as zigzag varint deltas from the origin — the same encoding tile
/// arenas decode, one `(dx, dy)` pair per point, from the origin with no
/// per-part restarts since a canonical array is one vertex array — then per
/// mask `u32` LE row (`logical_id`, never 0), `u8` zoom, 3 reserved zero
/// bytes, `u32` LE bit count (always the parent's point count), `u32` LE run
/// count, then that many bitvec-RLE runs of `u8` bit (0 or 1) + `u32` LE
/// length (every length ≥ 1, bits strictly alternating, lengths summing to
/// the bit count; masks are long keep/drop runs, so RLE is the whole
/// compression). An empty geometry carries no masks and no runs.
///
/// Geometry-pool index of "no geometry".
///
/// Index 0 is the empty entry, the same convention every attribute pool
/// follows (see [`SHARED_ATTR_DEFAULT`](super::consts::SHARED_ATTR_DEFAULT)): nothing stored, zero on the wire.
pub const SHARED_GEOM_NONE: u32 = 0;

/// One canonical full-detail vertex array with its per-(row, zoom) masks.
///
/// `points` is the full-detail array every mask subsets; `masks` holds one
/// [`SharedKeepMask`] per (row, zoom) the builder emitted, in emission
/// order. Index 0 of a written pool is always empty (enforced on parse, like
/// every attribute pool).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SharedCanonicalGeom {
    pub points: Vec<(i16, i16)>,
    pub masks: Vec<SharedKeepMask>,
}

impl SharedCanonicalGeom {
    pub fn is_empty(&self) -> bool {
        self.points.is_empty() && self.masks.is_empty()
    }

    /// This geometry's mask for (`row`, `zoom`), or `None` when none was
    /// emitted.
    pub fn mask_for(&self, row: u32, zoom: u8) -> Option<&SharedKeepMask> {
        self.masks.iter().find(|m| m.row == row && m.zoom == zoom)
    }
}

/// Which canonical vertices one row keeps at one zoom.
///
/// `keep[i]` says whether `points[i]` of the parent geometry survives
/// simplification for (`row`, `zoom`); `keep.len()` always equals the
/// parent's point count. The parent index is positional — the mask's slot in
/// its geometry — so it rides no field on the wire.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SharedKeepMask {
    pub row: u32,
    pub zoom: u8,
    pub keep: Vec<bool>,
}

impl SharedKeepMask {
    /// The mask bit for vertex `i`, or `None` past the mask.
    pub fn keeps(&self, i: usize) -> Option<bool> {
        self.keep.get(i).copied()
    }

    /// How many vertices survive.
    pub fn kept_count(&self) -> usize {
        self.keep.iter().filter(|&&k| k).count()
    }
}

/// The content hash canonical geometries intern by: FNV-1a (64-bit) over the
/// little-endian point bytes, with the length mixed in.
///
/// The builder keys on this hash and compares full content on a hit, so a
/// collision shares nothing it should not: equal content shares one index,
/// different content never does.
pub fn canonical_geom_hash(points: &[(i16, i16)]) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for &(x, y) in points {
        for b in x.to_le_bytes().iter().chain(y.to_le_bytes().iter()) {
            hash ^= *b as u64;
            hash = hash.wrapping_mul(0x0100_0000_01b3);
        }
    }
    hash ^= points.len() as u64;
    hash = hash.wrapping_mul(0x0100_0000_01b3);
    hash
}

/// Encode canonical vertices: zigzag varint deltas from the origin, one
/// `(dx, dy)` pair per point — the same walk tile arenas encode.
pub fn encode_canonical_verts(points: &[(i16, i16)]) -> Vec<u8> {
    let mut out = Vec::new();
    let (mut px, mut py) = (0i32, 0i32);
    for &(x, y) in points {
        push_uvarint(&mut out, crate::proto::zigzag_encode(x as i64 - px as i64));
        push_uvarint(&mut out, crate::proto::zigzag_encode(y as i64 - py as i64));
        (px, py) = (x as i32, y as i32);
    }
    out
}

/// Decode [`encode_canonical_verts`]: exactly `point_count` points from
/// `buf`, returning the points and the bytes consumed.
///
/// A truncated stream or a delta that walks outside `i16` is refused rather
/// than decoded to a shorter array a mask would misalign with.
pub fn decode_canonical_verts(buf: &[u8], point_count: usize) -> Result<(Vec<(i16, i16)>, usize)> {
    let uvarint_at = |pos: &mut usize| -> Result<u64> {
        let mut val: u64 = 0;
        let mut shift = 0u32;
        loop {
            if *pos >= buf.len() {
                return err("a shared geometry stream ends inside a varint");
            }
            let b = buf[*pos];
            *pos += 1;
            if shift >= 64 {
                return err("a shared geometry varint is longer than 64 bits");
            }
            val |= ((b & 0x7F) as u64) << shift;
            if b & 0x80 == 0 {
                return Ok(val);
            }
            shift += 7;
        }
    };
    // Bounded by the stream before allocating: two varints minimum per
    // point, so a corrupt count cannot ask for gigabytes.
    let min = point_count.checked_mul(2).ok_or_else(|| {
        crate::proto::Error("a shared geometry's points overflow".to_string())
    })?;
    if min > buf.len() {
        return err("a shared geometry's vertices run past its pool");
    }
    let mut pos = 0usize;
    let mut points = Vec::with_capacity(point_count);
    let (mut x, mut y) = (0i32, 0i32);
    for _ in 0..point_count {
        x += crate::proto::zigzag_decode(uvarint_at(&mut pos)?) as i32;
        y += crate::proto::zigzag_decode(uvarint_at(&mut pos)?) as i32;
        let (Ok(px), Ok(py)) = (i16::try_from(x), i16::try_from(y)) else {
            return err("a shared geometry delta walks outside what an i16 holds");
        };
        points.push((px, py));
    }
    Ok((points, pos))
}

/// Encode a keep-mask as bitvec RLE: maximal `(bit, length)` runs.
///
/// Empty masks encode to no runs; anything else starts a run per maximal
/// constant span, so the decoder's alternation check holds by construction.
pub fn encode_keep_runs(keep: &[bool]) -> Vec<(u8, u32)> {
    let mut runs = Vec::new();
    let mut i = 0usize;
    while i < keep.len() {
        let bit = keep[i];
        let mut n = 0u32;
        while i < keep.len() && keep[i] == bit {
            n += 1;
            i += 1;
        }
        runs.push((u8::from(bit), n));
    }
    runs
}

/// Decode bitvec RLE: `run_count` `(bit, length)` pairs summing to
/// `bit_count`, read from `buf` at `*pos`.
///
/// Refuses a bit past 1, a zero length, two adjacent runs with one bit, or a
/// total that is not `bit_count` — any of which would misalign the mask
/// against its vertices.
pub fn decode_keep_runs(
    buf: &[u8],
    pos: &mut usize,
    bit_count: usize,
    run_count: usize,
) -> Result<Vec<bool>> {
    // Bounded before allocating: each run costs 5 bytes on the wire.
    let bytes = run_count.checked_mul(5).ok_or_else(|| {
        crate::proto::Error("a shared keep-mask's runs overflow".to_string())
    })?;
    if bytes > buf.len().saturating_sub(*pos) {
        return err("a shared keep-mask's runs run past its pool");
    }
    if run_count == 0 {
        if bit_count != 0 {
            return err("a nonempty shared keep-mask has no runs");
        }
        return Ok(Vec::new());
    }
    if bit_count == 0 {
        return err("an empty shared keep-mask carries runs");
    }
    let mut keep = Vec::with_capacity(bit_count);
    let mut prev: Option<u8> = None;
    for _ in 0..run_count {
        let bit = buf[*pos];
        let len = u32::from_le_bytes([buf[*pos + 1], buf[*pos + 2], buf[*pos + 3], buf[*pos + 4]])
            as usize;
        *pos += 5;
        if bit > 1 {
            return err(format!(
                "a shared keep-mask run names bit {bit}, which is neither keep nor drop"
            ));
        }
        if len == 0 {
            return err("a shared keep-mask run has zero length");
        }
        if prev.is_some_and(|p| p == bit) {
            return err("a shared keep-mask's runs do not alternate");
        }
        prev = Some(bit);
        if keep.len() + len > bit_count {
            return err("a shared keep-mask's runs run past its bit count");
        }
        keep.extend(std::iter::repeat(bit == 1).take(len));
    }
    if keep.len() != bit_count {
        return err(format!(
            "a shared keep-mask decodes to {} bit(s) for {bit_count} vertices",
            keep.len()
        ));
    }
    Ok(keep)
}

/// The geometry pool: canonical vertex arrays with per-(row, zoom) masks.
///
/// Wire framing lives here; [`SharedView`](super::view::SharedView) holds the decoded `geoms` the way
/// it holds decoded rows. Index 0 is always the empty entry when the pool is
/// present (enforced on parse, like every attribute pool).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SharedGeometryPool {
    pub geoms: Vec<SharedCanonicalGeom>,
}

impl SharedGeometryPool {
    /// The geometry at `geom_idx` (0 is the empty entry), or `None` past the
    /// pool.
    pub fn lookup(&self, geom_idx: u32) -> Option<&SharedCanonicalGeom> {
        self.geoms.get(geom_idx as usize)
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&(self.geoms.len() as u32).to_le_bytes());
        for g in &self.geoms {
            out.extend_from_slice(&(g.points.len() as u32).to_le_bytes());
            out.extend_from_slice(&(g.masks.len() as u32).to_le_bytes());
            out.extend_from_slice(&encode_canonical_verts(&g.points));
            for m in &g.masks {
                out.extend_from_slice(&m.row.to_le_bytes());
                out.push(m.zoom);
                out.extend_from_slice(&[0u8; 3]);
                out.extend_from_slice(&(m.keep.len() as u32).to_le_bytes());
                let runs = encode_keep_runs(&m.keep);
                out.extend_from_slice(&(runs.len() as u32).to_le_bytes());
                for (bit, len) in &runs {
                    out.push(*bit);
                    out.extend_from_slice(&len.to_le_bytes());
                }
            }
        }
        while out.len() % 4 != 0 {
            out.push(0);
        }
        out
    }

    pub fn parse(buf: &[u8]) -> Result<(SharedGeometryPool, usize)> {
        if buf.len() < 4 {
            return err("a shared geometry pool ends before its count");
        }
        let count = u32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]) as usize;
        // Bounded by the slice before allocating: 8 framing bytes minimum
        // per geometry, so a corrupt count cannot ask for gigabytes.
        if count > buf.len() / 8 {
            return err("a shared geometry pool's count runs past its pool");
        }
        let mut at = 4usize;
        let mut geoms = Vec::with_capacity(count);
        for _ in 0..count {
            if at + 8 > buf.len() {
                return err("a shared geometry entry ends inside its counts");
            }
            let point_count =
                u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]]) as usize;
            let mask_count =
                u32::from_le_bytes([buf[at + 4], buf[at + 5], buf[at + 6], buf[at + 7]]) as usize;
            at += 8;
            // Each mask costs 16 framing bytes minimum.
            if mask_count > buf.len().saturating_sub(at) / 16 {
                return err("a shared geometry entry's mask count runs past its pool");
            }
            let (points, used) = decode_canonical_verts(&buf[at..], point_count)?;
            at += used;
            let mut masks = Vec::with_capacity(mask_count);
            for _ in 0..mask_count {
                if at + 16 > buf.len() {
                    return err("a shared keep-mask ends inside its header");
                }
                let row = u32::from_le_bytes([buf[at], buf[at + 1], buf[at + 2], buf[at + 3]]);
                let zoom = buf[at + 4];
                if buf[at + 5] != 0 || buf[at + 6] != 0 || buf[at + 7] != 0 {
                    return err("a shared keep-mask has non-zero reserved bytes");
                }
                let bit_count = u32::from_le_bytes([
                    buf[at + 8],
                    buf[at + 9],
                    buf[at + 10],
                    buf[at + 11],
                ]) as usize;
                let run_count = u32::from_le_bytes([
                    buf[at + 12],
                    buf[at + 13],
                    buf[at + 14],
                    buf[at + 15],
                ]) as usize;
                at += 16;
                if row == 0 {
                    return err("a shared keep-mask names row 0");
                }
                if zoom > MAX_ZOOM {
                    return err(format!(
                        "a shared keep-mask names zoom {zoom}, past the maximum {}",
                        MAX_ZOOM
                    ));
                }
                if bit_count != point_count {
                    return err(format!(
                        "a shared keep-mask has {bit_count} bit(s) for {point_count} vertices"
                    ));
                }
                let mut pos = at;
                let keep = decode_keep_runs(buf, &mut pos, bit_count, run_count)?;
                at = pos;
                masks.push(SharedKeepMask { row, zoom, keep });
            }
            // One mask per (row, zoom) within a geometry: a second mask for
            // the same pair would leave that row's zoom ambiguous.
            for (i, a) in masks.iter().enumerate() {
                for b in &masks[i + 1..] {
                    if a.row == b.row && a.zoom == b.zoom {
                        return err(format!(
                            "a shared geometry carries two masks for row {} at zoom {}",
                            a.row, a.zoom
                        ));
                    }
                }
            }
            geoms.push(SharedCanonicalGeom { points, masks });
        }
        if geoms.is_empty() {
            return err("a shared geometry pool carries no entries");
        }
        if !geoms[0].is_empty() {
            return err("a shared geometry pool's index 0 is not empty");
        }
        // Consumption is the aligned end of the last record; trailing section
        // bytes belong to the next pool when parsing a whole section.
        let aligned = align4(at);
        if aligned != buf.len() {
            return err("a shared geometry pool has trailing bytes past its records");
        }
        if buf[at..aligned].iter().any(|&b| b != 0) {
            return err("a shared geometry pool has non-zero padding");
        }
        Ok((SharedGeometryPool { geoms }, aligned))
    }
}
