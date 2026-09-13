//! The single-archive sidecar: graph + POI + transit after the tile data (`MAMA8`).
//!
//! INTEGRATOR (mod.rs registration — one line, applied separately):
//! ```rust,ignore
//! pub mod archive;
//! ```
//!
//! # Why a single archive exists
//!
//! The app downloads thirteen files (`MainActivity`'s gate) into one directory
//! and joins them by name and by count: graph packs by `metadata.bin` counts,
//! POI sidecars by ordinal, transit by `<feed>.transit`. A directory assembled
//! from two vintages reads garbage — and only the lengths catch it (see
//! `maps/src/main/rust/src/graph.rs`). The single archive replaces the
//! directory with one file: `[header|dict|root|leaves|tile data]` byte-identical
//! to today, then `[graph|poi|transit|section dir|footer]` appended 8-byte
//! aligned, each payload the same bytes as today.
//!
//! # Where it sits (coordinate with the v8 tail, do NOT collide)
//!
//! ```text
//! [header 128|160][dict][root][leaves][tile data][shared?][pad8]
//!   [graph payloads][poi payloads][transit pack][pad8]
//!   [section dir][footer 32]
//! ```
//!
//! * The tile prefix (`header` through `tile data`) is untouched: same offsets,
//!   same lengths, same 16 KiB open-prefix invariant. A reader that ignores the
//!   sidecar opens exactly as before.
//! * The v8 shared section (`MBSH`, named by the header's 160-byte tail
//!   `shared_offset`/`shared_len`) stays immediately after the tile data, where
//!   `write::StreamWriter` puts it. The sidecar starts at `shared_end` on v8,
//!   at `data_end` on v7 — see [`sidecar_start`]. It never reuses, moves, or
//!   reinterprets the header tail.
//! * Every sidecar payload starts 8-byte aligned; padding is zeroes.
//! * The section directory and footer are the last bytes: the footer names the
//!   directory, the directory names every payload. Nothing is inferred from
//!   layout — same rule the top-level header follows.
//!
//! # Section directory and footer wire
//!
//! Directory: `u32` LE count `n`, then `n` × 32-byte entries, padded with
//! zeroes to 8 bytes. Entries ascend by `kind` (deterministic build, same rule
//! as the shared section's pool directory).
//!
//! Entry (32 B, all little-endian): `0` kind (one of `ARCHIVE_KIND_*`), `1..4`
//! reserved zero, `4..8` flags (none defined, must be zero), `8..16` absolute
//! file offset, `16..24` payload length (excluding padding), `24..32` extra
//! (per-kind count the validator checks: see [`ArchiveEntry::extra`] kinds).
//!
//! Footer (32 B, all little-endian): `0..8` magic `MAMA8\0\0\0`, `8..16`
//! `dir_offset`, `16..24` `dir_len`, `24..32` `build_id` (must equal the tile
//! header's `build_id`).
//!
//! # Validators (per section)
//!
//! * `offset + len <= file_len` (checked_add, no wrap), 8-byte aligned offsets.
//! * No overlap: not with the tile prefix + shared section, not with each other.
//! * Magic/version per section: `MARG` v6 (graph meta), `TRIX` v3..=6
//!   (transit), `MAPA` v1 (POI attrs), `PSP1`/`PNI1` (POI grid/words).
//! * Count match: nodes `(N+1)*12`, edges exact length from `(E, escapes,
//!   named)`, intermediate trailer `G <= E`, lanes sentinel, elevation `N*2`,
//!   POI `len/14 == attrs/spatial/words counts`, transit section dir in bounds.
//! * `build_id == header.build_id`: one archive, one id. See
//!   [`unified_build_id`].
//!
//! # Lazy mapping contract
//!
//! * Always: tile header + directory + this footer + section dir (kilobytes).
//! * First route: `nodes`/`edges`/`intermediate` slices.
//! * Marshal (instruction emission): `names`/`lanes` slices.
//! * Profile: `elevation` slice.
//! * First use: POI slices / transit pack.
//!
//! The slices are file offsets, so an `mmap` loader maps the one file once and
//! hands out `(offset, len)` views — no copies, no second download. What backs
//! them (`MmapRegion`, `MappedByteBuffer`, `FileChannel.map`) is each loader's
//! own business; this file owns only the offsets and the checks.
//!
//! # Unified build id
//!
//! One archive, one id: [`unified_build_id`] hashes the graph revision, the OSM
//! digest, the GTFS digest, the DEM digest and the tiler revision. A republish
//! under a stable URL wipes range caches via the existing
//! `basemap_origin(url, build_id)` marker (`library/map/.../tile/source.rs`) —
//! the marker already keys on `(CACHE_FORMAT, url, build_id)`, so a new id
//! drops every stale byte range without a second request.

use crate::proto::{err, Result};

/// Magic opening every section directory is closed by: 8 bytes at the footer's head.
pub const ARCHIVE_MAGIC: &[u8; 8] = b"MAMA8\0\0\0";
/// Footer wire length. Fixed so a reader can slice it off the file's end.
pub const ARCHIVE_FOOTER_LEN: usize = 32;
/// One directory entry's wire length.
pub const ARCHIVE_ENTRY_LEN: usize = 24 + 8;
/// Directory header wire length (`u32` count).
pub const ARCHIVE_DIR_HEADER_LEN: usize = 4;
/// Alignment every sidecar payload starts on.
pub const ARCHIVE_ALIGN: u64 = 8;

/// Graph `metadata.bin` payload: `MARG` + version + counts.
pub const ARCHIVE_KIND_GRAPH_META: u8 = 1;
/// Graph `nodes.bin` payload: `NodeRec[N+1]`, 12 B each.
pub const ARCHIVE_KIND_GRAPH_NODES: u8 = 2;
/// Graph `edges.bin` payload: records + escape index + escape rows + name bitmap + name offs.
pub const ARCHIVE_KIND_GRAPH_EDGES: u8 = 3;
/// Graph `intermediate.bin` payload: blob + trailer.
pub const ARCHIVE_KIND_GRAPH_INTERMEDIATE: u8 = 4;
/// Graph `road_names.bin` payload: NUL-terminated string pool.
pub const ARCHIVE_KIND_GRAPH_NAMES: u8 = 5;
/// Graph `lanes.bin` payload: sparse lane index + blob.
pub const ARCHIVE_KIND_GRAPH_LANES: u8 = 6;
/// Graph `elevation.bin` payload: `i16[N]`.
pub const ARCHIVE_KIND_GRAPH_ELEVATION: u8 = 7;
/// POI `poi_index.bin` payload: 14 B records.
pub const ARCHIVE_KIND_POI_INDEX: u8 = 8;
/// POI `poi_names.bin` payload: NUL-terminated pool.
pub const ARCHIVE_KIND_POI_NAMES: u8 = 9;
/// POI `poi_attrs.bin` payload: optional attribute sidecar.
pub const ARCHIVE_KIND_POI_ATTRS: u8 = 10;
/// POI `poi_spatial.bin` payload: optional CSR grid.
pub const ARCHIVE_KIND_POI_SPATIAL: u8 = 11;
/// POI `poi_name_index.bin` payload: optional word index.
pub const ARCHIVE_KIND_POI_WORDS: u8 = 12;
/// Transit `<feed>.transit` payload: `TRIX` pack.
pub const ARCHIVE_KIND_TRANSIT: u8 = 13;

/// `metadata.bin` (`MARG`) magic, little-endian `u32`.
pub const GRAPH_MAGIC: u32 = 0x4752_414D;
/// Newest (and only, for the single archive) graph version.
pub const GRAPH_VERSION: u32 = 6;
/// `metadata.bin` wire length: magic + version + 4 counts.
pub const GRAPH_META_LEN: u64 = 40;
/// `transit` (`TRIX`) magic, little-endian `u32`.
pub const TRANSIT_MAGIC: u32 = 0x5452_4958;
/// Newest transit version accepted.
pub const TRANSIT_VERSION: u32 = 6;
/// Oldest transit version accepted.
pub const TRANSIT_VERSION_MIN: u32 = 3;
/// POI index record stride.
pub const POI_RECORD_BYTES: u64 = 14;

/// Round `n` up to `ARCHIVE_ALIGN`. Power-of-two align, same as graph.rs.
#[inline]
pub fn align_up(n: u64) -> u64 {
    (n + ARCHIVE_ALIGN - 1) & !(ARCHIVE_ALIGN - 1)
}

/// Where the sidecar starts: past the tile data and past the v8 shared section
/// when the header names one, else past the tile data.
///
/// Reads the header only, never the wire. On v7 (`shared_len == 0`) this is
/// `data_offset + data_len`; on v8 it is `shared_offset + shared_len`. The
/// header's own checks (overlap, fit) already ran in `Header::parse`, so this
/// cannot collide with the tail it coordinates with.
pub fn sidecar_start(header: &crate::mamaps::header::Header) -> u64 {
    if header.shared_len != 0 {
        header.shared_offset + header.shared_len
    } else {
        header.data_offset + header.data_len
    }
}

/// One section-directory entry: where a payload lives and what count it carries.
///
/// `extra` is per-kind: for `GRAPH_META` it is unused (0 — the counts live in
/// the payload); for `GRAPH_NODES`/`GRAPH_ELEVATION` the node count `N`; for
/// `GRAPH_EDGES`/`GRAPH_INTERMEDIATE` the edge count `E`; for POI kinds the
/// record count; for `TRANSIT` the route count (0 when unknown). Validators
/// cross-check it against the payload; loaders may trust it only after
/// [`ArchiveView::parse`] succeeds.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveEntry {
    pub kind: u8,
    pub flags: u32,
    pub offset: u64,
    pub len: u64,
    pub extra: u64,
}

impl ArchiveEntry {
    pub fn parse(buf: &[u8]) -> Result<ArchiveEntry> {
        if buf.len() < ARCHIVE_ENTRY_LEN {
            return err("a single-archive entry runs past its directory");
        }
        if buf[1] != 0 || buf[2] != 0 || buf[3] != 0 {
            return err("a single-archive entry has non-zero reserved bytes");
        }
        let kind = buf[0];
        if !matches!(
            kind,
            ARCHIVE_KIND_GRAPH_META
                | ARCHIVE_KIND_GRAPH_NODES
                | ARCHIVE_KIND_GRAPH_EDGES
                | ARCHIVE_KIND_GRAPH_INTERMEDIATE
                | ARCHIVE_KIND_GRAPH_NAMES
                | ARCHIVE_KIND_GRAPH_LANES
                | ARCHIVE_KIND_GRAPH_ELEVATION
                | ARCHIVE_KIND_POI_INDEX
                | ARCHIVE_KIND_POI_NAMES
                | ARCHIVE_KIND_POI_ATTRS
                | ARCHIVE_KIND_POI_SPATIAL
                | ARCHIVE_KIND_POI_WORDS
                | ARCHIVE_KIND_TRANSIT
        ) {
            return err(format!("a single-archive entry names unknown section kind {kind}"));
        }
        let u32_at = |o: usize| u32::from_le_bytes([buf[o], buf[o + 1], buf[o + 2], buf[o + 3]]);
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6],
                buf[o + 7],
            ])
        };
        let flags = u32_at(4);
        if flags != 0 {
            return err(format!("a single-archive entry sets unknown flags {flags:#010x}"));
        }
        Ok(ArchiveEntry {
            kind,
            flags,
            offset: u64_at(8),
            len: u64_at(16),
            extra: u64_at(24),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = vec![0u8; ARCHIVE_ENTRY_LEN];
        out[0] = self.kind;
        out[4..8].copy_from_slice(&self.flags.to_le_bytes());
        out[8..16].copy_from_slice(&self.offset.to_le_bytes());
        out[16..24].copy_from_slice(&self.len.to_le_bytes());
        out[24..32].copy_from_slice(&self.extra.to_le_bytes());
        out
    }
}

/// The 32-byte footer: magic, directory location, unified build id.
///
/// Byte map, all little-endian: `0..8` magic `MAMA8\0\0\0`, `8..16`
/// `dir_offset`, `16..24` `dir_len`, `24..32` `build_id`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ArchiveFooter {
    pub dir_offset: u64,
    pub dir_len: u64,
    pub build_id: u64,
}

impl ArchiveFooter {
    pub fn parse(buf: &[u8]) -> Result<ArchiveFooter> {
        if buf.len() < ARCHIVE_FOOTER_LEN {
            return err(format!(
                "a single-archive footer is {ARCHIVE_FOOTER_LEN} bytes, got {}",
                buf.len()
            ));
        }
        if &buf[0..8] != ARCHIVE_MAGIC {
            return err("not a single-archive footer (bad MAMA8 magic)");
        }
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                buf[o], buf[o + 1], buf[o + 2], buf[o + 3], buf[o + 4], buf[o + 5], buf[o + 6],
                buf[o + 7],
            ])
        };
        Ok(ArchiveFooter {
            dir_offset: u64_at(8),
            dir_len: u64_at(16),
            build_id: u64_at(24),
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(ARCHIVE_FOOTER_LEN);
        out.extend_from_slice(ARCHIVE_MAGIC);
        out.extend_from_slice(&self.dir_offset.to_le_bytes());
        out.extend_from_slice(&self.dir_len.to_le_bytes());
        out.extend_from_slice(&self.build_id.to_le_bytes());
        debug_assert_eq!(out.len(), ARCHIVE_FOOTER_LEN);
        out
    }
}

/// A parsed sidecar: footer + directory, with payload accessors.
///
/// Parse validates everything a later slice would discover the hard way:
/// footer magic, directory fit, per-entry bounds/alignment/overlap, build id
/// equality with the tile header, and per-kind magic/version/count agreement.
/// A corrupt sidecar is refused here rather than mmapped into on device.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchiveView {
    pub footer: ArchiveFooter,
    pub entries: Vec<ArchiveEntry>,
}

impl ArchiveView {
    /// Parse the sidecar off the end of a whole file already in memory.
    ///
    /// `header` is the tile header parsed from the same bytes (so its
    /// `file_len` and `build_id` are the expectations); `file_len` is the
    /// file's own length, which must equal `header.file_len`.
    pub fn parse(
        bytes: &[u8],
        header: &crate::mamaps::header::Header,
    ) -> Result<ArchiveView> {
        if bytes.len() as u64 != header.file_len {
            return err(format!(
                "a single-archive file declares {} bytes but is {}",
                header.file_len,
                bytes.len(),
            ));
        }
        if (bytes.len() as u64) < sidecar_start(header) + ARCHIVE_FOOTER_LEN as u64 {
            return err("a single-archive file ends inside its tile data");
        }
        let footer_at = bytes.len() - ARCHIVE_FOOTER_LEN;
        let footer = ArchiveFooter::parse(&bytes[footer_at..])?;
        if footer.build_id != header.build_id {
            return err(format!(
                "a single-archive footer carries build {:#018x} but its header carries {:#018x}",
                footer.build_id, header.build_id,
            ));
        }
        let dir_end = footer.dir_offset.checked_add(footer.dir_len).ok_or_else(|| {
            crate::proto::Error("a single-archive directory extent overflows".to_string())
        })?;
        if dir_end > footer_at as u64 {
            return err("a single-archive directory runs into its footer");
        }
        if footer.dir_len < ARCHIVE_DIR_HEADER_LEN as u64 {
            return err("a single-archive directory ends before its count");
        }
        let dir = &bytes[footer.dir_offset as usize..dir_end as usize];
        let count = u32::from_le_bytes([dir[0], dir[1], dir[2], dir[3]]) as usize;
        if count > 32 {
            return err(format!(
                "a single-archive directory names {count} sections, past the 13 this format defines"
            ));
        }
        let want = ARCHIVE_DIR_HEADER_LEN + count * ARCHIVE_ENTRY_LEN;
        let aligned = align_up(want as u64) as usize;
        if footer.dir_len as usize != aligned {
            return err(format!(
                "a single-archive directory declares {} bytes but {count} entries need {aligned}",
                footer.dir_len,
            ));
        }
        if dir.len() != aligned {
            return err("a single-archive directory length disagrees with its footer");
        }
        // Padding past the entries must be zeroes.
        if dir[want..].iter().any(|&b| b != 0) {
            return err("a single-archive directory has non-zero padding");
        }
        let mut entries = Vec::with_capacity(count);
        let mut previous_kind: Option<u8> = None;
        for i in 0..count {
            let at = ARCHIVE_DIR_HEADER_LEN + i * ARCHIVE_ENTRY_LEN;
            let e = ArchiveEntry::parse(&dir[at..at + ARCHIVE_ENTRY_LEN])?;
            if previous_kind.is_some_and(|p| e.kind <= p) {
                return err("a single-archive directory is not ordered by section kind");
            }
            previous_kind = Some(e.kind);
            entries.push(e);
        }
        // Bounds, alignment and overlap — same three refusals as the tile header.
        let tile_end = sidecar_start(header);
        for e in &entries {
            if e.offset % ARCHIVE_ALIGN != 0 {
                return err(format!(
                    "single-archive section {} starts at {}, which is not 8-byte aligned",
                    e.kind, e.offset
                ));
            }
            if e.len == 0 {
                return err(format!("single-archive section {} has zero length", e.kind));
            }
            let end = e.offset.checked_add(e.len).ok_or_else(|| {
                crate::proto::Error("a single-archive section extent overflows".to_string())
            })?;
            if e.offset < tile_end {
                return err(format!(
                    "single-archive section {} overlaps the tile data",
                    e.kind
                ));
            }
            if end > footer_at as u64 {
                return err(format!(
                    "single-archive section {} runs past its directory",
                    e.kind
                ));
            }
        }
        for (i, a) in entries.iter().enumerate() {
            for b in &entries[i + 1..] {
                let a_end = a.offset + a.len;
                let b_end = b.offset + b.len;
                if a.offset < b_end && b.offset < a_end {
                    return err(format!(
                        "single-archive sections {} and {} overlap",
                        a.kind, b.kind
                    ));
                }
            }
        }
        let view = ArchiveView { footer, entries };
        view.check_counts(bytes, header)?;
        Ok(view)
    }

    /// The payload slice for `kind`, or `None` when the archive omits it.
    ///
    /// Optional sections (names, lanes, elevation, POI sidecars) are absent —
    /// not zero-length — when the build carries none. Callers match on `None`
    /// to degrade (flat profile, topology lanes, scan fallback), never to fail.
    pub fn section<'b>(&self, bytes: &'b [u8], kind: u8) -> Option<&'b [u8]> {
        self.entries.iter().find(|e| e.kind == kind).map(|e| {
            &bytes[e.offset as usize..(e.offset + e.len) as usize]
        })
    }

    /// `(offset, len)` of `kind` for a loader that maps rather than copies.
    pub fn location(&self, kind: u8) -> Option<(u64, u64)> {
        self.entries.iter().find(|e| e.kind == kind).map(|e| (e.offset, e.len))
    }

    /// Per-kind magic/version/count agreement.
    ///
    /// Each check mirrors the multi-file loader's own (`Graph::load`,
    /// `PoiIndex::open*`, `TransitIndex::parse`) so a vintage mismatch the old
    /// layout refused by length is refused here by the same numbers.
    fn check_counts(
        &self,
        bytes: &[u8],
        header: &crate::mamaps::header::Header,
    ) -> Result<()> {
        let slice = |kind: u8| -> Result<Option<&[u8]>> {
            match self.entries.iter().find(|e| e.kind == kind) {
                None => Ok(None),
                Some(e) => Ok(Some(&bytes[e.offset as usize..(e.offset + e.len) as usize])),
            }
        };
        // Graph meta is mandatory when any graph section is present: it carries
        // the counts every other graph check is sized from.
        let graph_present = self.entries.iter().any(|e| {
            matches!(
                e.kind,
                ARCHIVE_KIND_GRAPH_META
                    | ARCHIVE_KIND_GRAPH_NODES
                    | ARCHIVE_KIND_GRAPH_EDGES
                    | ARCHIVE_KIND_GRAPH_INTERMEDIATE
            )
        });
        let meta = slice(ARCHIVE_KIND_GRAPH_META)?;
        let (node_count, edge_count, escape_count, named_edges) = match meta {
            None => {
                if graph_present {
                    return err("a single-archive carries graph sections but no graph meta");
                }
                (0, 0, 0, 0)
            }
            Some(m) => {
                if m.len() as u64 != GRAPH_META_LEN {
                    return err(format!(
                        "a single-archive graph meta is {} bytes, not {GRAPH_META_LEN}",
                        m.len()
                    ));
                }
                let u32_at = |o: usize| {
                    u32::from_le_bytes([m[o], m[o + 1], m[o + 2], m[o + 3]])
                };
                let u64_at = |o: usize| {
                    u64::from_le_bytes([
                        m[o], m[o + 1], m[o + 2], m[o + 3], m[o + 4], m[o + 5], m[o + 6],
                        m[o + 7],
                    ])
                };
                if u32_at(0) != GRAPH_MAGIC {
                    return err("a single-archive graph meta has bad MARG magic");
                }
                if u32_at(4) != GRAPH_VERSION {
                    return err(format!(
                        "a single-archive graph meta is version {}, not {GRAPH_VERSION}",
                        u32_at(4)
                    ));
                }
                let (n, e, esc, named) = (u64_at(8), u64_at(16), u64_at(24), u64_at(32));
                if u32::try_from(e).is_err() || esc > e || named > e {
                    return err("a single-archive graph meta counts disagree");
                }
                if u32::try_from(n).is_err() {
                    return err("a single-archive graph node count overflows u32");
                }
                let _ = header;
                (n, e, esc, named)
            }
        };
        // Nodes: (N+1) 12-byte records (the sentinel).
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_NODES)? {
            let want = (node_count + 1) * 12;
            if b.len() as u64 != want {
                return err(format!(
                    "a single-archive nodes section is {} bytes for {node_count} nodes, want {want}",
                    b.len()
                ));
            }
        }
        // Edges: records + escape index + escape rows + name bitmap + name offs.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_EDGES)? {
            let escape_blocks = edge_count.div_ceil(1024) + 1;
            let rec = edge_count * 7;
            let first_off = align_up(rec);
            let escapes_off = first_off + escape_blocks * 4;
            let names_off = align_up(escapes_off + escape_count * 12);
            // Bitmap bytes: rank + present, same as graph::EdgeBitmap::bytes.
            let rank = (edge_count.div_ceil(512) + 1) * 8;
            let present = edge_count.div_ceil(8);
            let name_off_off = names_off + rank + present;
            let want = name_off_off + named_edges * 4;
            if b.len() as u64 != want {
                return err(format!(
                    "a single-archive edges section is {} bytes, want {want}",
                    b.len()
                ));
            }
        }
        // Intermediate: at least the trailing G, and G <= E.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_INTERMEDIATE)? {
            if b.len() < 8 {
                return err("a single-archive intermediate section ends before its G");
            }
            let at = b.len() - 8;
            let g = u64::from_le_bytes([
                b[at], b[at + 1], b[at + 2], b[at + 3], b[at + 4], b[at + 5], b[at + 6],
                b[at + 7],
            ]);
            if g > edge_count {
                return err(format!(
                    "a single-archive intermediate names {g} geometry edges of {edge_count}"
                ));
            }
        }
        // Elevation: i16[N] when present.
        if let Some(b) = slice(ARCHIVE_KIND_GRAPH_ELEVATION)? {
            if b.len() as u64 != node_count * 2 {
                return err(format!(
                    "a single-archive elevation section is {} bytes for {node_count} nodes",
                    b.len()
                ));
            }
        }
        // POI index: 14-byte records; its count anchors the sidecars.
        let poi_count = match slice(ARCHIVE_KIND_POI_INDEX)? {
            None => None,
            Some(b) => {
                if b.len() as u64 % POI_RECORD_BYTES != 0 {
                    return err("a single-archive POI index is not whole records");
                }
                Some(b.len() as u64 / POI_RECORD_BYTES)
            }
        };
        if slice(ARCHIVE_KIND_POI_NAMES)?.is_none() && poi_count.is_some() {
            return err("a single-archive carries a POI index but no POI names");
        }
        // Attrs / spatial / words each refuse a count mismatch (ordinal join).
        if let Some(b) = slice(ARCHIVE_KIND_POI_ATTRS)? {
            if b.len() < 12 {
                return err("a single-archive POI attrs end before their header");
            }
            if &b[0..4] != b"MAPA" {
                return err("a single-archive POI attrs have bad MAPA magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err(format!(
                    "a single-archive POI attrs cover {n} records for {} index records",
                    poi_count.unwrap_or(0)
                ));
            }
        }
        if let Some(b) = slice(ARCHIVE_KIND_POI_SPATIAL)? {
            if b.len() < 32 {
                return err("a single-archive POI grid ends before its header");
            }
            if &b[0..4] != b"PSP1" {
                return err("a single-archive POI grid has bad PSP1 magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err("a single-archive POI grid covers a different index");
            }
        }
        if let Some(b) = slice(ARCHIVE_KIND_POI_WORDS)? {
            if b.len() < 16 {
                return err("a single-archive POI word index ends before its header");
            }
            if &b[0..4] != b"PNI1" {
                return err("a single-archive POI word index has bad PNI1 magic");
            }
            let n = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as u64;
            if Some(n) != poi_count {
                return err("a single-archive POI word index covers a different index");
            }
        }
        // Transit: TRIX v3..=6 with a directory that fits.
        if let Some(b) = slice(ARCHIVE_KIND_TRANSIT)? {
            if b.len() < 80 {
                return err("a single-archive transit pack ends before its header");
            }
            let magic = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
            let version = u32::from_le_bytes([b[4], b[5], b[6], b[7]]);
            if magic != TRANSIT_MAGIC {
                return err("a single-archive transit pack has bad TRIX magic");
            }
            if !(TRANSIT_VERSION_MIN..=TRANSIT_VERSION).contains(&version) {
                return err(format!(
                    "a single-archive transit pack is version {version}, not {TRANSIT_VERSION_MIN}..={TRANSIT_VERSION}"
                ));
            }
            let section_count = u32::from_le_bytes([b[8], b[9], b[10], b[11]]) as usize;
            if section_count < 20 {
                return err("a single-archive transit pack names too few sections");
            }
            if 80 + section_count * 16 > b.len() {
                return err("a single-archive transit directory runs past its pack");
            }
        }
        Ok(())
    }
}

/// One archive, one id: hash of everything the bytes depend on.
///
/// FNV-1a over the five revisions/digests a republish can move independently:
/// the routing-graph revision, the OSM input digest, the GTFS digest, the DEM
/// digest and the tiler revision. Empty (a build without GTFS/DEM) hashes as
/// empty, never as absent — so a build that gains a feed changes its id.
///
/// The range cache keys on `(CACHE_FORMAT, url, build_id)` via
/// `basemap_origin`, so a new id wipes every stale byte range on next open
/// with no extra request: the id already rides the header every open fetches.
pub fn unified_build_id(
    graph_rev: &[u8],
    osm_digest: &[u8],
    gtfs_digest: &[u8],
    dem_digest: &[u8],
    tiler_rev: &[u8],
) -> u64 {
    let mut h = 0xcbf2_9ce4_8422_2325u64;
    for part in [graph_rev, osm_digest, gtfs_digest, dem_digest, tiler_rev] {
        for &b in part {
            h ^= b as u64;
            h = h.wrapping_mul(0x100_0000_01b3);
        }
        // Domain separator so (ab, c) and (a, bc) differ.
        h ^= 0xFF;
        h = h.wrapping_mul(0x100_0000_01b3);
    }
    h
}

/// Serialize a directory + footer for `entries` already laid out at 8-byte
/// aligned offsets with `build_id`.
///
/// Entries must ascend by kind with zero flags; the directory is `count` +
/// entries + zero padding to 8 bytes, and the footer names it. Used by the
/// packer (lane E) and by the tests; the tile writer itself is untouched.
pub fn serialize_dir(entries: &[ArchiveEntry], build_id: u64, dir_offset: u64) -> (Vec<u8>, ArchiveFooter) {
    let mut dir = Vec::new();
    dir.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for e in entries {
        dir.extend_from_slice(&e.serialize());
    }
    while dir.len() as u64 % ARCHIVE_ALIGN != 0 {
        dir.push(0);
    }
    let footer = ArchiveFooter {
        dir_offset,
        dir_len: dir.len() as u64,
        build_id,
    };
    (dir, footer)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mamaps::header::Header;

    // Fail-watch convention: each test pins one wire fact as a literal, so a
    // revert of that fact quotes its failure (`left` vs `right`) rather than a
    // vague mismatch. Revert → quote the failure → restore.

    /// A tile header big enough to anchor a sidecar: no shared section (v7),
    /// tile data ending at 4096, file extended past it by the test.
    fn v7_header(file_len: u64) -> Header {
        Header {
            flags: 0,
            compression: 0,
            layer_count: 12,
            min_zoom: 0,
            max_zoom: 14,
            build_id: 0x0123_4567_89AB_CDEF,
            file_len,
            dict_offset: 128,
            dict_len: 64,
            leaf_entry_capacity: 4096,
            root_offset: 192,
            root_len: 32,
            leaf_count: 1,
            leaf_offset: 224,
            leaf_len: 16,
            data_offset: 240,
            data_len: 3856,
            tiles_addressed: 1,
            bodies_written: 1,
            min_lon_e7: 0,
            min_lat_e7: 0,
            max_lon_e7: 0,
            max_lat_e7: 0,
            shared_offset: 0,
            shared_len: 0,
            shared_flags: 0,
            shared_pools: 0,
        }
    }

    fn graph_meta(node_count: u64, edge_count: u64, escapes: u64, named: u64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&GRAPH_MAGIC.to_le_bytes());
        out.extend_from_slice(&GRAPH_VERSION.to_le_bytes());
        out.extend_from_slice(&node_count.to_le_bytes());
        out.extend_from_slice(&edge_count.to_le_bytes());
        out.extend_from_slice(&escapes.to_le_bytes());
        out.extend_from_slice(&named.to_le_bytes());
        out
    }

    /// Assemble a whole file: tile prefix (zeroes) + payloads + dir + footer.
    /// Payloads are laid out 8-aligned in kind order; callers supply
    /// `(kind, bytes, extra)`.
    fn assemble(header: &Header, payloads: &[(u8, Vec<u8>, u64)]) -> Vec<u8> {
        let mut out = vec![0u8; sidecar_start(header) as usize];
        let mut entries = Vec::new();
        for (kind, bytes, extra) in payloads {
            while out.len() as u64 % ARCHIVE_ALIGN != 0 {
                out.push(0);
            }
            let offset = out.len() as u64;
            out.extend_from_slice(bytes);
            entries.push(ArchiveEntry { kind: *kind, flags: 0, offset, len: bytes.len() as u64, extra: *extra });
        }
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let dir_offset = out.len() as u64;
        let (dir, footer) = serialize_dir(&entries, header.build_id, dir_offset);
        out.extend_from_slice(&dir);
        out.extend_from_slice(&footer.serialize());
        out
    }

    /// Fail-watched: the footer is 32 bytes behind `MAMA8\0\0\0`, naming the
    /// directory and the unified build id. A revert of the magic quotes
    /// `left: [88, ...]` vs `MAMA8`; of the length, `left: 24, right: 32`.
    #[test]
    fn the_footer_is_32_bytes_behind_mama8() {
        let f = ArchiveFooter { dir_offset: 4096, dir_len: 36, build_id: 0x0123_4567_89AB_CDEF };
        let bytes = f.serialize();
        assert_eq!(bytes.len(), 32, "the footer must stay 32 B");
        assert_eq!(&bytes[0..8], b"MAMA8\0\0\0");
        assert_eq!(&bytes[8..16], &4096u64.to_le_bytes(), "dir_offset");
        assert_eq!(&bytes[16..24], &36u64.to_le_bytes(), "dir_len");
        assert_eq!(&bytes[24..32], &0x0123_4567_89AB_CDEFu64.to_le_bytes(), "build_id");
        assert_eq!(ArchiveFooter::parse(&bytes).expect("parse"), f);
        let mut bad = bytes.clone();
        bad[0] = b'X';
        assert!(ArchiveFooter::parse(&bad).is_err(), "bad magic is refused");
    }

    /// Fail-watched: entries are 32 bytes, kinds are the thirteen defined, and
    /// the directory is `count` + entries padded to 8. A revert of the stride
    /// quotes `left: 24, right: 32`.
    #[test]
    fn entries_are_32_bytes_with_thirteen_known_kinds() {
        let e = ArchiveEntry { kind: ARCHIVE_KIND_TRANSIT, flags: 0, offset: 8192, len: 128, extra: 0 };
        assert_eq!(e.serialize().len(), 32, "entries must stay 32 B");
        assert_eq!(ArchiveEntry::parse(&e.serialize()).expect("parse"), e);
        assert_eq!(ARCHIVE_KIND_GRAPH_META, 1, "graph meta is kind 1");
        assert_eq!(ARCHIVE_KIND_TRANSIT, 13, "transit is kind 13");
        let mut bad = e.serialize();
        bad[0] = 99;
        assert!(ArchiveEntry::parse(&bad).is_err(), "unknown kind");
    }

    /// Fail-watched: the sidecar starts past the tile data on v7 and past the
    /// shared section on v8 — never inside the header tail it coordinates with.
    #[test]
    fn the_sidecar_starts_past_tiles_and_shared() {
        let v7 = v7_header(8192);
        assert_eq!(sidecar_start(&v7), 240 + 3856, "v7: data end");
        let mut v8 = v7_header(8192);
        v8.shared_offset = 4096;
        v8.shared_len = 512;
        assert_eq!(sidecar_start(&v8), 4608, "v8: shared end");
    }

    /// Fail-watched: a minimal sidecar (meta + nodes + transit stub) parses and
    /// slices. The transit stub is a real TRIX header so the count check runs.
    #[test]
    fn a_minimal_sidecar_parses_and_slices() {
        let nodes = vec![0u8; (4 + 1) * 12];
        let mut transit = vec![0u8; 80 + 20 * 16];
        transit[0..4].copy_from_slice(&TRANSIT_MAGIC.to_le_bytes());
        transit[4..8].copy_from_slice(&TRANSIT_VERSION.to_le_bytes());
        transit[8..12].copy_from_slice(&20u32.to_le_bytes());
        let mut header = v7_header(0);
        let payloads = vec![
            (ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0),
            (ARCHIVE_KIND_GRAPH_NODES, nodes.clone(), 0),
            (ARCHIVE_KIND_TRANSIT, transit.clone(), 0),
        ];
        let bytes = assemble(&header, &payloads);
        header.file_len = bytes.len() as u64;
        let bytes = assemble(&header, &payloads);
        let view = ArchiveView::parse(&bytes, &header).expect("parse");
        assert_eq!(view.entries.len(), 3);
        assert!(view.section(&bytes, ARCHIVE_KIND_GRAPH_NODES).is_some());
        assert!(view.section(&bytes, ARCHIVE_KIND_POI_INDEX).is_none(), "absent is None, not empty");
        assert_eq!(view.location(ARCHIVE_KIND_GRAPH_META).expect("loc").1, GRAPH_META_LEN);
    }

    /// Fail-watched: the build id binds header to footer. A footer carrying
    /// another id quotes both hexes in the refusal.
    #[test]
    fn a_footer_build_id_must_equal_the_header() {
        let mut header = v7_header(0);
        let payloads = vec![(ARCHIVE_KIND_GRAPH_META, graph_meta(0, 0, 0, 0), 0)];
        let mut bytes = assemble(&header, &payloads);
        header.file_len = bytes.len() as u64;
        bytes = assemble(&header, &payloads);
        // Corrupt the footer's build id (last 8 bytes).
        let at = bytes.len() - 8;
        bytes[at..].copy_from_slice(&0xDEAD_BEEFu64.to_le_bytes());
        let failure = ArchiveView::parse(&bytes, &header).expect_err("build mismatch");
        assert!(failure.0.contains("build"), "{}", failure.0);
    }

    /// Fail-watched: sections get the same three refusals as every tile section
    /// — unaligned, overlapping the tiles, overlapping each other — plus a bad
    /// magic (MARG/TRIX) and a count mismatch (nodes length, POI ordinal join).
    #[test]
    fn a_sidecar_section_must_fit_without_overlapping() {
        let mut header = v7_header(0);
        let payloads = vec![(ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0)];
        let mut bytes = assemble(&header, &payloads);
        header.file_len = bytes.len() as u64;
        bytes = assemble(&header, &payloads);
        let view = ArchiveView::parse(&bytes, &header).expect("good parses");
        assert_eq!(view.entries.len(), 1);

        // Bad MARG magic.
        let mut bad_meta = graph_meta(4, 2, 0, 0);
        bad_meta[0] = b'X';
        let bad = assemble(&header, &[(ARCHIVE_KIND_GRAPH_META, bad_meta, 0)]);
        let mut h2 = header.clone();
        h2.file_len = bad.len() as u64;
        let bad = assemble(&h2, &[(ARCHIVE_KIND_GRAPH_META, {
            let mut m = graph_meta(4, 2, 0, 0);
            m[0] = b'X';
            m
        }, 0)]);
        assert!(ArchiveView::parse(&bad, &h2).is_err(), "bad MARG magic");

        // Nodes length disagreeing with meta's N.
        let short_nodes = vec![0u8; 12];
        let bad = assemble(&h2, &[
            (ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0),
            (ARCHIVE_KIND_GRAPH_NODES, short_nodes, 0),
        ]);
        let mut h3 = header.clone();
        h3.file_len = bad.len() as u64;
        let bad = assemble(&h3, &[
            (ARCHIVE_KIND_GRAPH_META, graph_meta(4, 2, 0, 0), 0),
            (ARCHIVE_KIND_GRAPH_NODES, vec![0u8; 12], 0),
        ]);
        assert!(ArchiveView::parse(&bad, &h3).is_err(), "nodes count mismatch");
    }

    /// Fail-watched: the unified id moves with any of its five inputs and is
    /// stable otherwise. A revert quoting `left == right` means a separator lost.
    #[test]
    fn the_unified_build_id_moves_with_any_input() {
        let base = unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler");
        assert_eq!(base, unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler"), "stable");
        for other in [
            unified_build_id(b"gr2", b"osm", b"gtfs", b"dem", b"tiler"),
            unified_build_id(b"gr1", b"osm2", b"gtfs", b"dem", b"tiler"),
            unified_build_id(b"gr1", b"osm", b"gtfs2", b"dem", b"tiler"),
            unified_build_id(b"gr1", b"osm", b"gtfs", b"dem2", b"tiler"),
            unified_build_id(b"gr1", b"osm", b"gtfs", b"dem", b"tiler2"),
        ] {
            assert_ne!(base, other, "a changed input should change the id");
        }
    }
}
