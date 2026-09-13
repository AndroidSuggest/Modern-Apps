//! The parsed shared section: [`SharedView`] and its whole-section parse.
//!
//! Pure moves out of the former single-file shared module; nothing here changed.

use crate::proto::{err, Result};

use super::consts::{
    SHARED_HEADER_LEN, SHARED_KIND_BUILDINGS, SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_GEOMETRY,
    SHARED_KIND_ID_RUNS, SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_SLIM_REFS,
    SHARED_KIND_STRINGS, SHARED_NAME_NONE, SHARED_POOL_ENTRY_LEN, SHARED_ROW_LEN,
    SHARED_SLIM_REF_LEN,
};
use super::geom::{SharedCanonicalGeom, SharedGeometryPool, SharedKeepMask};
use super::keys::decode_id_runs;
use super::pools::{
    SharedStringPool, parse_building_pool, parse_carriageway_pool, parse_lane_turns_pool,
};
use super::records::{
    SharedBuildingAttrs, SharedCarriageway, SharedHeader, SharedLaneTurns, SharedLogicalRow,
    SharedPoolDirEntry, SharedSlimRef,
};

/// A fully parsed shared section (read path; no `write` feature needed).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SharedView {
    pub header: SharedHeader,
    pub strings: SharedStringPool,
    pub rows: Vec<SharedLogicalRow>,
    pub buildings: Vec<SharedBuildingAttrs>,
    pub carriageways: Vec<SharedCarriageway>,
    pub lane_turns: Vec<SharedLaneTurns>,
    /// Decoded per-row stable ids (`ID_NONE` expanded), parallel to `rows`.
    /// Empty when the section carries no id pool.
    pub ids: Vec<u64>,
    pub slim_refs: Vec<SharedSlimRef>,
    /// Decoded canonical geometries (index 0 is the empty entry).
    ///
    /// Just `[default]` when the section carries no geometry pool — the
    /// pre-lane-A shape — so old sections parse without special-casing.
    /// Lanes B+ own how rows reference these; this file owns only the pool.
    pub geometries: Vec<SharedCanonicalGeom>,
}

impl SharedView {
    /// Parse a whole shared section: header, directory, then each pool.
    ///
    /// Every pool must lie inside `total_len`, pools must not overlap, and the
    /// counts in the header must agree with what the pools decode to. Rows must
    /// arrive sorted and distinct by `logical_id`; attribute indices must land
    /// inside their pools; `name_ref`s must land inside the string pool (or be
    /// zero). Anything else is corruption, refused here rather than indexed
    /// into on device.
    pub fn parse(buf: &[u8]) -> Result<SharedView> {
        let header = SharedHeader::parse(buf)?;
        if buf.len() < header.total_len as usize {
            return err(format!(
                "a shared section declares {} bytes but is {}",
                header.total_len,
                buf.len()
            ));
        }
        let buf = &buf[..header.total_len as usize];
        let dir_end = SHARED_HEADER_LEN + header.pool_count as usize * SHARED_POOL_ENTRY_LEN;
        if dir_end > buf.len() {
            return err("a shared section's directory runs past its end");
        }
        let mut entries = Vec::with_capacity(header.pool_count as usize);
        let mut previous_kind: Option<u8> = None;
        for i in 0..header.pool_count as usize {
            let at = SHARED_HEADER_LEN + i * SHARED_POOL_ENTRY_LEN;
            let e = SharedPoolDirEntry::parse(&buf[at..at + SHARED_POOL_ENTRY_LEN])?;
            if previous_kind.is_some_and(|p| e.kind <= p) {
                return err("a shared section's directory is not ordered by pool kind");
            }
            previous_kind = Some(e.kind);
            let end = e.offset.checked_add(e.len).ok_or_else(|| {
                crate::proto::Error("a shared pool's extent overflows".to_string())
            })?;
            if e.offset < dir_end as u64 || end > header.total_len as u64 {
                return err(format!("shared pool {} lies outside the section", e.kind));
            }
            entries.push(e);
        }
        // Pairwise overlap, because a writer that patched one offset and not
        // another would otherwise alias two pools to the same bytes.
        for (i, a) in entries.iter().enumerate() {
            for b in &entries[i + 1..] {
                if a.len == 0 || b.len == 0 {
                    continue;
                }
                if a.offset < b.offset + b.len && b.offset < a.offset + a.len {
                    return err("two shared pools overlap");
                }
            }
        }
        let slice_of = |kind: u8| -> Result<Option<&[u8]>> {
            match entries.iter().find(|e| e.kind == kind) {
                None => Ok(None),
                Some(e) => Ok(Some(&buf[e.offset as usize..(e.offset + e.len) as usize])),
            }
        };
        // Strings (optional only when string_count == 0).
        let strings = match slice_of(SHARED_KIND_STRINGS)? {
            None => {
                if header.string_count != 0 {
                    return err("a shared section names strings it does not carry");
                }
                SharedStringPool::default()
            }
            Some(bytes) => {
                let (pool, used) = SharedStringPool::parse(bytes)?;
                if used != bytes.len() {
                    return err("a shared string pool has trailing bytes past its padding");
                }
                if pool.names.len() as u32 != header.string_count {
                    return err(format!(
                        "a shared section declares {} strings but carries {}",
                        header.string_count,
                        pool.names.len()
                    ));
                }
                pool
            }
        };
        // Rows.
        let rows = match slice_of(SHARED_KIND_ROWS)? {
            None => {
                if header.row_count != 0 {
                    return err("a shared section names rows it does not carry");
                }
                Vec::new()
            }
            Some(bytes) => {
                let entry = entries.iter().find(|e| e.kind == SHARED_KIND_ROWS).expect("found");
                if entry.elem_len as usize != SHARED_ROW_LEN {
                    return err("a shared row pool declares the wrong stride");
                }
                if bytes.len() % SHARED_ROW_LEN != 0 {
                    return err("a shared row pool is not a whole number of rows");
                }
                let mut rows = Vec::with_capacity(bytes.len() / SHARED_ROW_LEN);
                for chunk in bytes.chunks_exact(SHARED_ROW_LEN) {
                    rows.push(SharedLogicalRow::parse(chunk)?);
                }
                if rows.len() as u32 != header.row_count {
                    return err(format!(
                        "a shared section declares {} rows but carries {}",
                        header.row_count,
                        rows.len()
                    ));
                }
                if rows.windows(2).any(|p| p[1].logical_id <= p[0].logical_id) {
                    return err("shared logical rows are not strictly ascending by id");
                }
                rows
            }
        };
        // Buildings (index 0 = default; pool always carries it when present).
        let buildings = match slice_of(SHARED_KIND_BUILDINGS)? {
            None => vec![SharedBuildingAttrs::default()],
            Some(bytes) => parse_building_pool(bytes)?,
        };
        // Carriageways.
        let carriageways = match slice_of(SHARED_KIND_CARRIAGEWAYS)? {
            None => vec![SharedCarriageway::default()],
            Some(bytes) => parse_carriageway_pool(bytes)?,
        };
        // Lane turns (whole-record interned; index 0 = empty).
        let lane_turns = match slice_of(SHARED_KIND_LANE_TURNS)? {
            None => vec![SharedLaneTurns::default()],
            Some(bytes) => parse_lane_turns_pool(bytes)?,
        };
        // Id runs (optional; when present, parallel to rows).
        let ids = match slice_of(SHARED_KIND_ID_RUNS)? {
            None => {
                if header.id_run_count != 0 {
                    return err("a shared section names id runs it does not carry");
                }
                Vec::new()
            }
            Some(bytes) => {
                let ids = decode_id_runs(bytes, header.id_run_count as usize)?;
                if ids.len() as u32 != header.id_run_count {
                    return err("a shared id pool decodes to the wrong count");
                }
                if !rows.is_empty() && ids.len() != rows.len() {
                    return err(format!(
                        "a shared id pool has {} id(s) for {} row(s)",
                        ids.len(),
                        rows.len()
                    ));
                }
                ids
            }
        };
        // Slim refs.
        let slim_refs = match slice_of(SHARED_KIND_SLIM_REFS)? {
            None => Vec::new(),
            Some(bytes) => {
                let entry =
                    entries.iter().find(|e| e.kind == SHARED_KIND_SLIM_REFS).expect("found");
                if entry.elem_len as usize != SHARED_SLIM_REF_LEN {
                    return err("a shared slim-ref pool declares the wrong stride");
                }
                if bytes.len() % SHARED_SLIM_REF_LEN != 0 {
                    return err("a shared slim-ref pool is not a whole number of refs");
                }
                bytes
                    .chunks_exact(SHARED_SLIM_REF_LEN)
                    .map(SharedSlimRef::parse)
                    .collect::<Result<Vec<_>>>()?
            }
        };
        // Geometry (optional; when absent every row reads as unshared, which
        // is exactly the pre-lane-A section shape).
        let geometries = match slice_of(SHARED_KIND_GEOMETRY)? {
            None => vec![SharedCanonicalGeom::default()],
            Some(bytes) => {
                let (pool, used) = SharedGeometryPool::parse(bytes)?;
                if used != bytes.len() {
                    return err("a shared geometry pool has trailing bytes past its padding");
                }
                pool.geoms
            }
        };
        // Cross-references: every index a row names must exist.
        for row in &rows {
            if row.name_ref != SHARED_NAME_NONE && row.name_ref as usize > strings.names.len() {
                return err(format!(
                    "shared row {} names string {} of {}",
                    row.logical_id,
                    row.name_ref,
                    strings.names.len()
                ));
            }
            if row.building_idx as usize >= buildings.len() {
                return err(format!(
                    "shared row {} names building {} of {}",
                    row.logical_id,
                    row.building_idx,
                    buildings.len()
                ));
            }
            if row.carriageway_idx as usize >= carriageways.len() {
                return err(format!(
                    "shared row {} names carriageway {} of {}",
                    row.logical_id,
                    row.carriageway_idx,
                    carriageways.len()
                ));
            }
            if row.lane_turns_idx as usize >= lane_turns.len() {
                return err(format!(
                    "shared row {} names lane-turns {} of {}",
                    row.logical_id,
                    row.lane_turns_idx,
                    lane_turns.len()
                ));
            }
        }
        Ok(SharedView { header, strings, rows, buildings, carriageways, lane_turns, ids, slim_refs, geometries })
    }

    /// The row with this `logical_id`, or `None` (rows are sorted, so binary).
    pub fn row(&self, logical_id: u32) -> Option<&SharedLogicalRow> {
        self.rows.binary_search_by_key(&logical_id, |r| r.logical_id).ok().map(|i| &self.rows[i])
    }

    /// This row's display name, or `None` for [`SHARED_NAME_NONE`].
    pub fn row_name(&self, row: &SharedLogicalRow) -> Option<&str> {
        self.strings.lookup(row.name_ref)
    }

    /// The canonical geometry at `geom_idx` (0 is the empty entry), or `None`
    /// past the pool.
    pub fn geometry(&self, geom_idx: u32) -> Option<&SharedCanonicalGeom> {
        self.geometries.get(geom_idx as usize)
    }

    /// The keep-mask `geom_idx` carries for (`row`, `zoom`), or `None` when
    /// none was emitted.
    pub fn keep_mask(&self, geom_idx: u32, row: u32, zoom: u8) -> Option<&SharedKeepMask> {
        self.geometry(geom_idx)?.mask_for(row, zoom)
    }
}
