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
    elevation: *const u8,

    // Derived cost tables (computed in `load`, matching the C++ `init`).
    pub lon_to_mm_scale: [u32; 4096],
    pub time_scale_fixed: [u64; 4],
    pub edge_time_multipliers: [[u64; 16]; 4],
}

unsafe impl Send for Graph {}
unsafe impl Sync for Graph {}
