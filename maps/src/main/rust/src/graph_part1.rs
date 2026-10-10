/// The whole-world routing dataset. Immutable after construction.
pub struct Graph {
    // mmap regions kept alive for the lifetime of the graph. The three
    // mandatory ones are `Some` on the multi-file path (one region per file)
    // and `None` on the single-archive path (the one archive region below
    // backs everything instead); exactly one side is populated, checked in
    // `assemble`.
    _nodes_region: Option<MmapRegion>,
    _edges_region: Option<MmapRegion>,
    _intermediate_region: Option<MmapRegion>,
    _road_names_region: Option<MmapRegion>,
    _lanes_region: Option<MmapRegion>,
    _elevation_region: Option<MmapRegion>,
    // The single-archive file on the `load_archive` path, which backs every
    // pointer above instead of the six per-file regions. `None` on the
    // multi-file `load` path.
    _archive_region: Option<MmapRegion>,
    // Owned byte backing for host tests (the host stub cannot mmap): the
    // graph keeps these alive exactly like the regions. Always empty in
    // production.
    _owned_buffers: Vec<Vec<u8>>,

    nodes: *const u8,
    pub node_count: u32, // real nodes; nodes.bin has node_count + 1 (sentinel)
    pub edge_count: u64,

    // `edges.bin`, in five sections:
    //   [ EdgeRec[E] ][ pad ][ u32 escape_first[E/1024 + 1] ][ EscapeRow[escapes] ]
    //   [ pad ][ u64 name rank[..] ][ u8 name present[..] ][ u32 name_off[named] ]
    // The records hold `target` as an i16 delta and `dist_mm` as a u24; either
    // field's sentinel sends both to the escape table. See `EdgeRec`.
    edges: *const u8,
    escape_first: *const u8, // u32[E.div_ceil(ESCAPE_BLOCK) + 1]
    escapes: *const u8,      // EscapeRow[escape_count], ascending by edge_idx
    /// Edges whose `target` or `dist_mm` came out of the escape table rather than
    /// the record. Validated against the block index's total at load.
    pub escape_count: u64,
    names: EdgeBitmap,
    name_off: *const u8, // u32[named_edges], indexed by rank in `names`
    /// Edges with a name at all. Validated against the rank index's total at load.
    pub named_edges: u64,

    // `intermediate.bin`, blob first and every table in a trailer. `off(g) =
    // coarse[g / INTERMEDIATE_BLOCK] + within[g]` for the g-th *geometry* edge,
    // where g is the edge's rank in `geometry`.
    intermediate_data: *const u8,   // delta-encoded coordinate bytes, at offset 0
    geometry: EdgeBitmap,           // which edges store a polyline, and their rank
    intermediate_coarse: *const u8, // u64[G.div_ceil(BLOCK) + 1]
    intermediate_within: *const u8, // u16[G + 1]

    // Real OSM turn-lane data (see `edge_lane_masks`). Sparse: only the edges that
    // actually carry `turn:lanes` appear, which on a planet is 2.9 M of 1.07 G.
    // Optional as a whole too — absent until the graph is regenerated with lane
    // extraction, in which case routing falls back to topology-inferred lanes.
    lane_index: *const u8, // LaneEntry[lane_edges + 1], ascending by edge_idx
    lane_edges: u32,
    lane_data: *const u8, // packed u16 per-lane masks

    road_names: *const u8,
    pub road_names_size: usize,

    // `elevation.bin`, an OPTIONAL `i16[node_count]` of per-node ground elevation in metres, baked
    // from the DEM at graph-build time (WS-G route profile). Null when the file is absent or its
    // length does not match `node_count`, in which case [`Graph::node_elevation`] reads 0 for every
    // node and the route profile comes out flat. A sidecar like `road_names.bin`/`lanes.bin`: no
    // metadata count and no GRAPH_VERSION bump, validated by its own length instead.
    //
    // Under v7 the file is a ZBLK section ([`elevation_blocked`]); the loader
    // dispatches on the magic, so absence still reads as all-zero.
    elevation: *const u8,

    /// v7 blocked views, parsed (not inflated) at load. `None` on the v6 path,
    /// where the raw pointers above serve directly. The unpacked bytes live in
    /// the [`Graph::unpacked`] caches below on first use.
    nodes_view: Option<BlockedView>,
    edges_view: Option<BlockedView>,
    elevation_view: Option<BlockedView>,

    /// Lazily inflated v7 sections, one per blocked file. Empty (and the raw
    /// pointers above null) on the v6 path.
    ///
    /// Full-section inflate on first use rather than a per-block cache: routing
    /// touches the whole graph anyway, so steady-state memory equals the v6
    /// resident set and the hot accessors keep their pointer arithmetic. The
    /// cost is a one-time inflate per section on first route, not per lookup.
    unpacked_nodes: std::sync::OnceLock<Vec<u8>>,
    unpacked_edges: std::sync::OnceLock<Vec<u8>>,
    unpacked_elevation: std::sync::OnceLock<Vec<u8>>,

    // Derived cost tables (computed in `load`, matching the C++ `init`).
    pub lon_to_mm_scale: [u32; 4096],
    pub time_scale_fixed: [u64; 4],
    pub edge_time_multipliers: [[u64; 16]; 4],
}

/// A parsed ZBLK section that borrows nothing.
///
/// The offset table is copied (8 bytes per block — 524 KB for 268 M planet
/// bodies) so this owns everything it needs except the frames themselves,
/// which stay in the mmap region (or archive slice) the caller keeps alive
/// exactly like the raw tables. No lifetime ties the two structs together.
pub(crate) struct BlockedView {
    record_len: usize,
    records_per_block: u64,
    unpacked_len: u64,
    /// Absolute file offsets of each block start, plus the file end.
    offsets: Vec<u64>,
    frames: *const u8,
    frames_len: u64,
}

// Raw pointers into a mapping the graph owns: Send exactly like the tables.
unsafe impl Send for BlockedView {}
unsafe impl Sync for BlockedView {}

impl BlockedView {
    /// Parse over `bytes` (the whole section file/slice). Refuses bad magic,
    /// unknown flags, zero stride, and offsets outside the section. Mirrors
    /// `tilecodec::blocked::Blocked::parse` without borrowing it, so no
    /// lifetime ties this view to the mapping.
    pub(crate) fn parse(bytes: &[u8]) -> Option<BlockedView> {
        if bytes.len() < 28 {
            return None;
        }
        let u32_at = |o: usize| {
            u32::from_le_bytes([bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3]])
        };
        let u64_at = |o: usize| {
            u64::from_le_bytes([
                bytes[o], bytes[o + 1], bytes[o + 2], bytes[o + 3], bytes[o + 4],
                bytes[o + 5], bytes[o + 6], bytes[o + 7],
            ])
        };
        if u32_at(0) != tilecodec::blocked::BLOCKED_MAGIC || u32_at(4) != 0 {
            return None;
        }
        let record_len = u32_at(8) as usize;
        let records_per_block = u32_at(12) as u64;
        let unpacked_len = u64_at(16);
        if record_len == 0 || records_per_block == 0 {
            return None;
        }
        let block_count = u32_at(24) as usize;
        let mut offsets = Vec::with_capacity(block_count + 1);
        for i in 0..=block_count {
            let o = u64_at(28 + i * 8);
            if o > bytes.len() as u64 || (i > 0 && o < offsets[i - 1]) {
                return None;
            }
            offsets.push(o);
        }
        // No end-equality check: a blocked record array may share its file
        // with trailing tables (`edges.bin`'s escapes/names). Truncation is
        // still refused above — an offset past the bytes fails.
        Some(BlockedView {
            record_len,
            records_per_block,
            unpacked_len,
            offsets,
            frames: bytes.as_ptr(),
            frames_len: bytes.len() as u64,
        })
    }

    /// Inflate every block and concatenate, verifying the unpacked length.
    /// Called once per section via the graph's `OnceLock`s.
    pub(crate) fn unpack(&self) -> Option<Vec<u8>> {
        let bytes = unsafe { std::slice::from_raw_parts(self.frames, self.frames_len as usize) };
        let parsed = tilecodec::blocked::Blocked::parse(bytes).ok()?;
        let mut cache = tilecodec::blocked::BlockCache::new(0);
        let mut out = Vec::with_capacity(self.unpacked_len as usize);
        for b in 0..parsed.block_count() {
            out.extend_from_slice(cache.get(&parsed, b).ok()?);
        }
        if out.len() as u64 != self.unpacked_len {
            return None;
        }
        Some(out)
    }
}

unsafe impl Send for Graph {}
unsafe impl Sync for Graph {}
