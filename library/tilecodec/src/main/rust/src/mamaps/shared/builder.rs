//! The shared-section builder: interns strings/attrs/geometry whole-value and
//! emits one section.
//!
//! Pure moves out of the former single-file shared module; nothing here changed
//! except re-rooting `super::header` to `crate::mamaps::header`. Imports stay
//! `write`-gated with the items they serve.

#[cfg(feature = "write")]
use crate::mamaps::header::MAX_ZOOM;
#[cfg(feature = "write")]
use crate::proto::{err, Result};
#[cfg(feature = "write")]
use super::consts::{
    SHARED_ATTR_DEFAULT, SHARED_BUILDING_LEN, SHARED_CARRIAGEWAY_LEN, SHARED_HEADER_LEN,
    SHARED_KIND_BUILDINGS, SHARED_KIND_CARRIAGEWAYS, SHARED_KIND_GEOMETRY, SHARED_KIND_ID_RUNS,
    SHARED_KIND_LANE_TURNS, SHARED_KIND_ROWS, SHARED_KIND_SLIM_REFS, SHARED_KIND_STRINGS,
    SHARED_NAME_NONE, SHARED_POOL_ENTRY_LEN, SHARED_ROW_LEN, SHARED_SLIM_REF_LEN,
};
#[cfg(feature = "write")]
use super::geom::{
    SHARED_GEOM_NONE, SharedCanonicalGeom, SharedGeometryPool, SharedKeepMask, canonical_geom_hash,
};
#[cfg(feature = "write")]
use super::keys::{SharedRowKey, encode_id_runs};
#[cfg(feature = "write")]
use super::pools::{
    SharedStringPool, serialize_building_pool, serialize_carriageway_pool,
    serialize_lane_turns_pool,
};
#[cfg(feature = "write")]
use super::records::{
    SharedBuildingAttrs, SharedCarriageway, SharedHeader, SharedLaneTurns, SharedLogicalRow,
    SharedPoolDirEntry, SharedSlimRef,
};

/// The builder: interns strings/attrs whole-value (plus canonical geometry) and emits one section.
///
/// Write-gated so the interner maps (and their `HashMap` iteration) never reach
/// the read path — the same reason `mamaps::write` lives behind `write`.
#[cfg(feature = "write")]
pub struct SharedBuilder {
    strings: Vec<String>,
    string_idx: std::collections::HashMap<String, u32>,
    rows: Vec<SharedLogicalRow>,
    ids: Vec<u64>,
    buildings: Vec<SharedBuildingAttrs>,
    building_idx: std::collections::HashMap<[u8; SHARED_BUILDING_LEN], u32>,
    carriageways: Vec<SharedCarriageway>,
    carriageway_idx: std::collections::HashMap<u64, u32>,
    lane_turns: Vec<SharedLaneTurns>,
    lane_turns_idx: std::collections::HashMap<Vec<u16>, u32>,
    slim_refs: Vec<SharedSlimRef>,
    /// Canonical geometries in first-intern order (index 0 is the empty
    /// entry, like every attribute pool), each with its per-(row, zoom)
    /// keep-masks in emission order. Dedup keys on [`canonical_geom_hash`]
    /// with a full-content compare on a hit, so a hash collision shares
    /// nothing it should not.
    geoms: Vec<SharedCanonicalGeom>,
    geom_idx: std::collections::HashMap<u64, Vec<u32>>,
    /// (geom_idx, row, zoom) to the mask's index within its geometry, so one
    /// (row, zoom) pair can never carry two masks for one geometry.
    mask_idx: std::collections::HashMap<(u32, u32, u8), u32>,
    /// Content-keyed row dedup: the full [`SharedRowKey`] to its sequential
    /// `logical_id` (1-based in first-sighting order). Same file, so first
    /// sighting *is* the id — no content-derived reproducibility is needed,
    /// and no fold can collide.
    row_idx: std::collections::HashMap<SharedRowKey, u32>,
}

#[cfg(feature = "write")]
impl SharedBuilder {
    pub fn new() -> SharedBuilder {
        SharedBuilder {
            strings: Vec::new(),
            string_idx: std::collections::HashMap::new(),
            rows: Vec::new(),
            // Index 0 of every attr pool is the default, seeded up front.
            buildings: vec![SharedBuildingAttrs::default()],
            building_idx: std::collections::HashMap::new(),
            carriageways: vec![SharedCarriageway::default()],
            carriageway_idx: std::collections::HashMap::new(),
            lane_turns: vec![SharedLaneTurns::default()],
            lane_turns_idx: std::collections::HashMap::new(),
            ids: Vec::new(),
            slim_refs: Vec::new(),
            geoms: vec![SharedCanonicalGeom::default()],
            geom_idx: std::collections::HashMap::new(),
            mask_idx: std::collections::HashMap::new(),
            row_idx: std::collections::HashMap::new(),
        }
    }

    /// Intern a display name. Returns 0 ([`SHARED_NAME_NONE`]) for empty input.
    pub fn intern_string(&mut self, name: &str) -> u32 {
        if name.is_empty() {
            return SHARED_NAME_NONE;
        }
        if let Some(&r) = self.string_idx.get(name) {
            return r;
        }
        self.strings.push(name.to_string());
        let r = self.strings.len() as u32;
        self.string_idx.insert(name.to_string(), r);
        r
    }

    pub fn intern_building(&mut self, a: SharedBuildingAttrs) -> u32 {
        if a.is_default() {
            return SHARED_ATTR_DEFAULT;
        }
        let key = {
            let b = a.serialize();
            let mut k = [0u8; SHARED_BUILDING_LEN];
            k.copy_from_slice(&b);
            k
        };
        if let Some(&i) = self.building_idx.get(&key) {
            return i;
        }
        self.buildings.push(a);
        let i = self.buildings.len() as u32 - 1;
        self.building_idx.insert(key, i);
        i
    }

    pub fn intern_carriageway(&mut self, c: SharedCarriageway) -> u32 {
        if c.is_default() {
            return SHARED_ATTR_DEFAULT;
        }
        let key = (c.forward as u64) | ((c.backward as u64) << 8) | ((c.solid_dividers as u64) << 16);
        if let Some(&i) = self.carriageway_idx.get(&key) {
            return i;
        }
        self.carriageways.push(c);
        let i = self.carriageways.len() as u32 - 1;
        self.carriageway_idx.insert(key, i);
        i
    }

    /// Intern a whole turn-lane record (never per lane).
    pub fn intern_lane_turns(&mut self, t: SharedLaneTurns) -> u32 {
        if t.is_empty() {
            return SHARED_ATTR_DEFAULT;
        }
        if t.forward.len() > u8::MAX as usize || t.backward.len() > u8::MAX as usize {
            panic!("a shared lane-turns record carries more than 255 lanes each way");
        }
        let mut key = Vec::with_capacity(2 + t.forward.len() + t.backward.len());
        key.push(t.forward.len() as u16);
        key.push(t.backward.len() as u16);
        key.extend(t.forward.iter().copied());
        key.extend(t.backward.iter().copied());
        if let Some(&i) = self.lane_turns_idx.get(&key) {
            return i;
        }
        self.lane_turns.push(t);
        let i = self.lane_turns.len() as u32 - 1;
        self.lane_turns_idx.insert(key, i);
        i
    }

    /// Intern one logical row by full content, returning its sequential id.
    ///
    /// The first sighting of a [`SharedRowKey`] interns strings/attrs, pushes
    /// the row (id = rows-so-far + 1, since index 0 is reserved for "none" the
    /// way pools seed their defaults), and records the parallel stable id; a
    /// repeat sighting returns the existing id with nothing pushed. Equal keys
    /// mean equal rows, so a misjoin is unrepresentable: there is no fold to
    /// collide and no content check to fail.
    ///
    /// [`push_row`] remains for tests that pin explicit ids.
    pub fn intern_row(
        &mut self,
        key: SharedRowKey,
        kind: u16,
        kind_detail: u16,
        view_bits: u32,
        stable_id: u64,
    ) -> u32 {
        if let Some(&id) = self.row_idx.get(&key) {
            return id;
        }
        let name_ref = self.intern_string(key.name.as_deref().unwrap_or(""));
        let row = SharedLogicalRow {
            logical_id: self.rows.len() as u32 + 1,
            name_ref,
            kind,
            kind_detail,
            view_bits,
            building_idx: self.intern_building(key.building),
            carriageway_idx: self.intern_carriageway(key.carriageway),
            lane_turns_idx: self.intern_lane_turns(key.lane_turns.clone()),
            flags: key.flags,
        };
        let id = row.logical_id;
        self.rows.push(row);
        self.ids.push(stable_id);
        self.row_idx.insert(key, id);
        id
    }

    /// Push one logical row with its parallel stable id (`ID_NONE` when the row
    /// has no stable identity — every junction row). Duplicate `logical_id`s
    /// are refused at [`SharedBuilder::serialize`], not here, so a caller can
    /// stage rows in any order; the section is emitted sorted.
    pub fn push_row(&mut self, row: SharedLogicalRow, stable_id: u64) {
        self.rows.push(row);
        self.ids.push(stable_id);
    }

    pub fn push_slim_ref(&mut self, r: SharedSlimRef) {
        self.slim_refs.push(r);
    }

    /// Intern a canonical full-detail vertex array, returning its pool index.
    ///
    /// Empty input is [`SHARED_GEOM_NONE`] without storing anything;
    /// otherwise the content hash ([`canonical_geom_hash`]) finds the
    /// existing entry and only a full-content miss pushes a new one, so
    /// equal arrays share one index however many rows clip them.
    pub fn intern_geometry(&mut self, points: &[(i16, i16)]) -> u32 {
        if points.is_empty() {
            return SHARED_GEOM_NONE;
        }
        let hash = canonical_geom_hash(points);
        if let Some(chain) = self.geom_idx.get(&hash) {
            for &i in chain {
                if self.geoms[i as usize].points == points {
                    return i;
                }
            }
        }
        self.geoms.push(SharedCanonicalGeom { points: points.to_vec(), masks: Vec::new() });
        let i = self.geoms.len() as u32 - 1;
        self.geom_idx.entry(hash).or_default().push(i);
        i
    }

    /// Emit one row's keep-mask for one zoom against an interned geometry,
    /// returning the mask's index within that geometry.
    ///
    /// `keep.len()` must equal the geometry's point count and `zoom` must be
    /// a real zoom (`0..=MAX_ZOOM`). Re-emitting the same (row, zoom) with
    /// the same bits returns the existing index; re-emitting it with
    /// different bits panics, since one row's zoom cannot keep two vertex
    /// sets.
    pub fn emit_keep_mask(&mut self, row: u32, zoom: u8, geom_idx: u32, keep: &[bool]) -> u32 {
        if geom_idx == SHARED_GEOM_NONE {
            panic!("a shared keep-mask names the empty geometry");
        }
        let point_len = self
            .geoms
            .get(geom_idx as usize)
            .unwrap_or_else(|| {
                panic!("a shared keep-mask names geometry {geom_idx} of {}", self.geoms.len())
            })
            .points
            .len();
        if row == 0 {
            panic!("a shared keep-mask names row 0");
        }
        if zoom > MAX_ZOOM {
            panic!(
                "a shared keep-mask names zoom {zoom}, past the maximum {}",
                MAX_ZOOM
            );
        }
        if keep.len() != point_len {
            panic!("a shared keep-mask has {} bit(s) for {point_len} vertices", keep.len());
        }
        let key = (geom_idx, row, zoom);
        if let Some(&i) = self.mask_idx.get(&key) {
            if self.geoms[geom_idx as usize].masks[i as usize].keep.as_slice() == keep {
                return i;
            }
            panic!("a shared geometry carries two masks for row {row} at zoom {zoom}");
        }
        self.geoms[geom_idx as usize].masks.push(SharedKeepMask { row, zoom, keep: keep.to_vec() });
        let i = self.geoms[geom_idx as usize].masks.len() as u32 - 1;
        self.mask_idx.insert(key, i);
        i
    }

    /// Emit one shared section: header, directory, then pools in kind order.
    ///
    /// Rows are already in first-sighting order with sequential ids (see
    /// [`SharedBuilder::intern_row`); this re-sorts defensively and refuses a
    /// duplicate id, so a caller that bypassed `intern_row` via [`push_row`]
    /// still cannot emit two rows with one id. Pools a section
    /// has exactly one default entry of (a lone index-0 building table, say)
    /// are still written — the reader treats a missing pool as all-defaults,
    /// and writing the seed keeps "present but all default" distinct from
    /// "absent" for a diff. The geometry pool is the exception: it is
    /// omitted when no geometry was interned, so sections that never share
    /// geometry stay byte-identical to before lane A.
    pub fn serialize(mut self) -> Result<Vec<u8>> {
        // Defensive: intern_row already emits sequential first-sighting ids,
        // but a push_row caller stages explicit ids in any order.
        let mut order: Vec<usize> = (0..self.rows.len()).collect();
        order.sort_by_key(|&i| self.rows[i].logical_id);
        if order.windows(2).any(|p| self.rows[p[0]].logical_id == self.rows[p[1]].logical_id) {
            return err("shared logical ids must be distinct");
        }
        let rows: Vec<SharedLogicalRow> = order.iter().map(|&i| self.rows[i]).collect();
        let ids: Vec<u64> = order.iter().map(|&i| self.ids[i]).collect();
        self.rows = rows;
        self.ids = ids;

        let string_pool = SharedStringPool { names: self.strings.clone() }.serialize();
        let mut rows_pool = Vec::new();
        for r in &self.rows {
            rows_pool.extend_from_slice(&r.serialize());
        }
        while rows_pool.len() % 4 != 0 {
            rows_pool.push(0);
        }
        let building_pool = serialize_building_pool(&self.buildings);
        let carriageway_pool = serialize_carriageway_pool(&self.carriageways);
        let lane_turns_pool = serialize_lane_turns_pool(&self.lane_turns);
        let id_pool = encode_id_runs(&self.ids)?;
        let mut id_pool_padded = id_pool.clone();
        while id_pool_padded.len() % 4 != 0 {
            id_pool_padded.push(0);
        }
        let mut slim_pool = Vec::new();
        for r in &self.slim_refs {
            slim_pool.extend_from_slice(&r.serialize());
        }
        while slim_pool.len() % 4 != 0 {
            slim_pool.push(0);
        }

        // Directory in kind order (the parser requires it). The geometry
        // pool rides last, and only when the builder interned geometry:
        // kinds 1..=7 emit byte-identical sections whether or not lane A
        // exists.
        let mut pools: Vec<(u8, u32, Vec<u8>)> = vec![
            (SHARED_KIND_STRINGS, 0, string_pool),
            (SHARED_KIND_ROWS, SHARED_ROW_LEN as u32, rows_pool),
            (SHARED_KIND_BUILDINGS, SHARED_BUILDING_LEN as u32, building_pool),
            (SHARED_KIND_CARRIAGEWAYS, SHARED_CARRIAGEWAY_LEN as u32, carriageway_pool),
            (SHARED_KIND_LANE_TURNS, 0, lane_turns_pool),
            (SHARED_KIND_ID_RUNS, 0, id_pool_padded),
            (SHARED_KIND_SLIM_REFS, SHARED_SLIM_REF_LEN as u32, slim_pool),
        ];
        if self.geoms.len() > 1 {
            pools.push((
                SHARED_KIND_GEOMETRY,
                0,
                SharedGeometryPool { geoms: self.geoms.clone() }.serialize(),
            ));
        }
        let pool_count = pools.len() as u32;
        let mut offset =
            (SHARED_HEADER_LEN + pools.len() * SHARED_POOL_ENTRY_LEN) as u64;
        let mut dir = Vec::new();
        for (kind, elem_len, bytes) in &pools {
            dir.push(SharedPoolDirEntry {
                kind: *kind,
                elem_len: *elem_len,
                offset,
                len: bytes.len() as u64,
            });
            offset += bytes.len() as u64;
        }
        let total_len = offset as u32;
        let header = SharedHeader {
            flags: 0,
            row_count: self.rows.len() as u32,
            string_count: self.strings.len() as u32,
            pool_count,
            total_len,
            id_run_count: self.ids.len() as u32,
        };
        let mut out = header.serialize();
        for e in &dir {
            out.extend_from_slice(&e.serialize());
        }
        for (_, _, bytes) in &pools {
            out.extend_from_slice(bytes);
        }
        debug_assert_eq!(out.len() as u32, total_len);
        Ok(out)
    }
}

#[cfg(feature = "write")]
impl Default for SharedBuilder {
    fn default() -> Self {
        Self::new()
    }
}
