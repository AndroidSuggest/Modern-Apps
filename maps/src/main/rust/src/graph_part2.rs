impl Graph {
    /// Load the graph from `base` (a directory path, trailing slash optional).
    /// Returns `None` if any mandatory file is absent, if `metadata.bin` is not a
    /// [`GRAPH_VERSION`] header, or if **any** file's length is not exactly what
    /// `metadata.bin`'s counts say it should be.
    ///
    /// # Why the lengths are checked, and checked exactly
    ///
    /// The version lives in `metadata.bin` while the layouts live in `nodes.bin`
    /// and `edges.bin`, so a matching version field cannot catch a pack directory
    /// assembled from two vintages — only the lengths can. `nodes.bin`'s length used
    /// not to be checked at all, and [`Graph::node`] documents that callers
    /// guarantee `id <= node_count` with no bounds test, so a short or stale file
    /// read straight past the mapping.
    ///
    /// Exactly, not "at least", because a file that is too *long* is just as much a
    /// vintage mismatch as one that is too short, and one comparison catches both.
    ///
    /// `road_names.bin` is the one file with no count to check against: it is a
    /// byte pool whose size is its own, and [`Graph::road_name`] bounds every read
    /// on it instead.
    ///
    /// `intermediate.bin` is mandatory, not optional. The generator collapses
    /// degree-2 chains, so an edge is a whole road between junctions and its
    /// polyline is the only record of the road's shape. Without the file,
    /// `find_nearest_edge` would project onto a straight chord spanning an entire
    /// street: snapping and drawn geometry would both be wrong, not merely
    /// coarse. Refusing to load says so instead of routing badly.
    pub fn load(base: &str) -> Option<Graph> {
        let mut base = base.to_string();
        if !base.is_empty() && !base.ends_with('/') {
            base.push('/');
        }

        // metadata.bin: u32 magic, u32 version, then a u64 count per section that
        // needs one — node_count, edge_count, escape_count, named_edges. Scoped so
        // the mapping is released before the big regions are mapped.
        //
        // Explicit count fields rather than a generic section directory: each
        // version adds one, and a named field is more debuggable than speculative
        // generality.
        let (node_count, edge_count, escape_count, named_edges) = {
            let meta = MmapRegion::map(&format!("{base}metadata.bin"))?;
            if meta.len < 40 {
                return None;
            }
            let magic = unsafe { read_at::<u32>(meta.base(), 0) };
            let version = unsafe { read_at::<u32>(meta.base(), 1) };
            if magic != GRAPH_MAGIC || version != GRAPH_VERSION {
                return None;
            }
            let nodes = unsafe { read_at::<u64>(meta.base().add(8), 0) };
            let edges = unsafe { read_at::<u64>(meta.base().add(16), 0) };
            let escapes = unsafe { read_at::<u64>(meta.base().add(24), 0) };
            let named = unsafe { read_at::<u64>(meta.base().add(32), 0) };
            // `edge_ptr` is a `u32`, so an edge count past that ceiling would have
            // wrapped in the writer and pointed a node at another node's range. The
            // generator refuses to produce one; this refuses to read one. Neither
            // sparse table can describe more edges than exist.
            if u32::try_from(edges).is_err() || escapes > edges || named > edges {
                return None;
            }
            (u32::try_from(nodes).ok()?, edges, escapes, named)
        };

        // `edge_count` now comes from the header rather than from `edges.bin`'s
        // length. That is the point of version 4, and 5 and 6 are why: the record is
        // 7 bytes and the file has four more sections behind it, so its length is no
        // longer any simple multiple of anything.
        let nodes_region = MmapRegion::map(&format!("{base}nodes.bin"))?;
        let edges_region = MmapRegion::map(&format!("{base}edges.bin"))?;
        // nodes.bin is `NodeRec[node_count + 1]`: one sentinel record past the real
        // nodes, whose `edge_ptr` is `edge_count`. Eight call sites read it.
        let want_nodes =
            (u64::from(node_count) + 1) * std::mem::size_of::<NodeRec>() as u64;
        if nodes_region.len as u64 != want_nodes {
            return None;
        }

        // edges.bin's five sections, computed forwards from the metadata counts.
        // The total-length assertion below is what replaces v3's self-locating
        // trailer: if the computed sections do not exactly consume the region, the
        // file is not the one these counts describe.
        let escape_blocks = edge_count.div_ceil(ESCAPE_BLOCK) + 1;
        let escape_first_off =
            align_up(edge_count * std::mem::size_of::<EdgeRec>() as u64, SECTION_ALIGN);
        let escapes_off =
            escape_first_off + escape_blocks * std::mem::size_of::<u32>() as u64;
        let names_off = align_up(
            escapes_off + escape_count * std::mem::size_of::<EscapeRow>() as u64,
            SECTION_ALIGN,
        );
        let name_off_off = names_off + EdgeBitmap::bytes(edge_count);
        let want_edges = name_off_off + named_edges * std::mem::size_of::<u32>() as u64;
        if edges_region.len as u64 != want_edges {
            return None;
        }
        let edges = edges_region.base();
        let escape_first = unsafe { edges.add(escape_first_off as usize) };
        let escapes = unsafe { edges.add(escapes_off as usize) };
        let names = EdgeBitmap {
            rank: unsafe { edges.add(names_off as usize) },
            present: unsafe {
                edges.add((names_off + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let name_off = unsafe { edges.add(name_off_off as usize) };
        // The block index's last entry is the row total, and the rank index's is the
        // named-edge total. Both are the direct analogue of `intermediate.bin`'s
        // `rank_total == geometry_edges` check: they tie an index to the section it
        // indexes rather than trusting both to have been written by the same run.
        if unsafe { read_at::<u32>(escape_first, (escape_blocks - 1) as usize) } as u64
            != escape_count
            || names.total(edge_count) != named_edges
        {
            return None;
        }
        // Rows ascend, so bounding the last one bounds them all.
        if escape_count > 0 {
            let last = unsafe { read_at::<EscapeRow>(escapes, (escape_count - 1) as usize) };
            if u64::from(last.edge_idx) >= edge_count {
                return None;
            }
        }

        // intermediate.bin:
        //   [ blob ][ u64 rank[..] ][ u8 present[..] ][ u64 coarse[..] ]
        //   [ u16 within[..] ][ u64 G ]
        // Everything is recoverable from the last 8 bytes plus `edge_count`: the
        // tables are sized from `G` and from `E`, and the blob is whatever is left
        // over at offset 0. Validated on both axes, because a stale file from a
        // different graph vintage would otherwise be read out of bounds.
        let intermediate_region = MmapRegion::map(&format!("{base}intermediate.bin"))?;
        let inter_len = intermediate_region.len as u64;
        if inter_len < 8 {
            return None;
        }
        let geometry_edges =
            unsafe { read_at::<u64>(intermediate_region.base().add((inter_len - 8) as usize), 0) };
        // `G` can never exceed the number of directed edges. Checking it before
        // anything is sized from it is also what stops a garbage trailer from
        // overflowing the table arithmetic below into a plausible-looking total.
        if geometry_edges > edge_count {
            return None;
        }
        let bitmap_bytes = EdgeBitmap::bytes(edge_count);
        let coarse_bytes = (geometry_edges.div_ceil(INTERMEDIATE_BLOCK) + 1)
            * std::mem::size_of::<u64>() as u64;
        let within_bytes = (geometry_edges + 1) * std::mem::size_of::<u16>() as u64;
        let trailer = bitmap_bytes + coarse_bytes + within_bytes + 8;
        if inter_len < trailer {
            return None;
        }
        let blob_bytes = inter_len - trailer;
        let intermediate_data = intermediate_region.base();
        let geometry = EdgeBitmap {
            rank: unsafe { intermediate_data.add(blob_bytes as usize) },
            present: unsafe {
                intermediate_data
                    .add((blob_bytes + EdgeBitmap::present_offset(edge_count)) as usize)
            },
        };
        let intermediate_coarse = unsafe { intermediate_data.add((blob_bytes + bitmap_bytes) as usize) };
        let intermediate_within = unsafe { intermediate_coarse.add(coarse_bytes as usize) };
        // The two halves of the trailer have to agree with each other and with the
        // blob: the sentinel offset is the blob's length, and the rank total is
        // `G`. Both are cheap, and between them they catch a truncated file, a
        // mismatched vintage and a generator that lost count.
        let sentinel = unsafe {
            read_at::<u64>(
                intermediate_coarse,
                (geometry_edges / INTERMEDIATE_BLOCK) as usize,
            ) + u64::from(read_at::<u16>(intermediate_within, geometry_edges as usize))
        };
        if sentinel != blob_bytes || geometry.total(edge_count) != geometry_edges {
            return None;
        }

        let road_names_region = MmapRegion::map(&format!("{base}road_names.bin"));
        let (road_names, road_names_size) = match &road_names_region {
            Some(r) => (r.base(), r.len),
            None => (ptr::null(), 0),
        };

        // lanes.bin: [ u32 n ][ LaneEntry[n + 1] ][ u16 lane-mask blob ]. The index
        // is sized by its own header rather than by `edge_count`, so it is validated
        // against that; a truncated/mismatched file disables real lanes so routing
        // falls back to topology inference.
        let lanes_region = MmapRegion::map(&format!("{base}lanes.bin"));
        let lanes = lanes_region.as_ref().and_then(|r| {
            if r.len < 4 {
                return None;
            }
            let n = unsafe { read_at::<u32>(r.base(), 0) };
            let index = unsafe { r.base().add(4) };
            let index_bytes = 4 + (u64::from(n) + 1) * std::mem::size_of::<LaneEntry>() as u64;
            if (r.len as u64) < index_bytes {
                return None;
            }
            // The sentinel entry's offset is the blob length, so checking it
            // validates the blob as well as the index. That matters because
            // `edge_lane_masks` reads at offsets taken straight from the file.
            // Exactly, not "at least": the writer emits header + index + blob and
            // nothing else, so a longer file is a vintage mismatch too.
            let sentinel = unsafe { read_at::<LaneEntry>(index, n as usize) };
            if r.len as u64 != index_bytes + u64::from(sentinel.blob_off) {
                return None;
            }
            // Nothing else in this file mentions `edge_count`, so a stale-vintage
            // `lanes.bin` whose own header is self-consistent would otherwise load
            // and attribute masks to the wrong edges. Entries ascend, so bounding
            // the last one bounds them all.
            if n > 0 {
                let last = unsafe { read_at::<LaneEntry>(index, n as usize - 1) };
                if u64::from(last.edge_idx) >= edge_count {
                    return None;
                }
            }
            Some((index, n, unsafe { r.base().add(index_bytes as usize) }))
        });
        let (lane_index, lane_edges, lane_data) = lanes.unwrap_or((ptr::null(), 0, ptr::null()));

        // elevation.bin: `i16[node_count]`, one per real node in node-id order. Optional and
        // validated by length alone — a stale-vintage file whose node_count differs is simply
        // disabled (elevation reads 0), the same self-check lanes.bin does against `edge_count`.
        let elevation_region = MmapRegion::map(&format!("{base}elevation.bin"));
        let elevation = match &elevation_region {
            Some(r) if r.len as u64 == u64::from(node_count) * std::mem::size_of::<i16>() as u64 => {
                r.base()
            }
            _ => ptr::null(),
        };
        // A wrong-length elevation.bin is dropped, not kept mapped: nothing should read a stale
        // vintage's bytes, and holding the region alive would imply it is usable.
        let elevation_region = if elevation.is_null() { None } else { elevation_region };

        let nodes = nodes_region.base();
        // --- Derived tables (identical formulas to the C++ init) ---
        let mut lon_to_mm_scale = [0u32; 4096];
        for (i, scale) in lon_to_mm_scale.iter_mut().enumerate() {
            let lat_deg = (((i as i64 - 2048) << 19) as f64) / 1e7;
            *scale = ((111_139_000.0 / 1e7) * (lat_deg * DEG_TO_RAD).cos() * 1024.0) as u32;
        }

        let calc_scale =
            |speed_m_s: f64| -> u64 { ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64 };

        let mut time_scale_fixed = [0u64; 4];
        time_scale_fixed[WALK as usize] = calc_scale(WALK_SPEED_M_S);
        time_scale_fixed[BICYCLE as usize] = calc_scale(BICYCLE_SPEED_M_S);
        // Heuristic speed MUST be >= the fastest achievable edge speed (which is
        // clamped to MAX_DRIVING_KMH in get_edge_time_10ms) so the heuristic stays
        // consistent for the monotonic radix heap. See MAX_DRIVING_KMH.
        time_scale_fixed[DRIVING as usize] = calc_scale(MAX_DRIVING_KMH / 3.6);
        // PUBLIC_TRANSIT only ever walks in the road graph (see is_mode_allowed),
        // so it MUST use walk speed. Leaving it at a vehicle speed makes the A*
        // heuristic wildly over-optimistic: still correct, but catastrophically
        // slow because almost the whole graph gets expanded.
        time_scale_fixed[PUBLIC_TRANSIT as usize] = calc_scale(WALK_SPEED_M_S);

        let mut edge_time_multipliers = [[0u64; 16]; 4];
        for (m, row) in edge_time_multipliers.iter_mut().enumerate() {
            for (r, multiplier) in row.iter_mut().enumerate() {
                let speed_m_s = if m == DRIVING as usize {
                    match r as u8 {
                        1 => 105.0 / 3.6, // MOTORWAY
                        2 => 85.0 / 3.6,  // TRUNK
                        3 => 65.0 / 3.6,  // PRIMARY
                        4 => 55.0 / 3.6,  // SECONDARY
                        5 => 45.0 / 3.6,  // TERTIARY
                        _ => 30.0 / 3.6,
                    }
                } else if m == BICYCLE as usize {
                    BICYCLE_SPEED_M_S
                } else {
                    WALK_SPEED_M_S
                };
                *multiplier = ((100.0 / (speed_m_s * 1000.0)) * 4_294_967_296.0) as u64;
            }
        }

        Some(Graph {
            _nodes_region: nodes_region,
            _edges_region: edges_region,
            _intermediate_region: intermediate_region,
            _road_names_region: road_names_region,
            _lanes_region: lanes_region,
            _elevation_region: elevation_region,
            nodes,
            node_count,
            edge_count,
            edges,
            escape_first,
            escapes,
            escape_count,
            names,
            name_off,
            named_edges,
            intermediate_data,
            geometry,
            intermediate_coarse,
            intermediate_within,
            lane_index,
            lane_edges,
            lane_data,
            road_names,
            road_names_size,
            elevation,
            lon_to_mm_scale,
            time_scale_fixed,
            edge_time_multipliers,
        })
    }

    // --- Raw accessors (all unaligned-safe) ---

    #[inline]
    pub fn node(&self, id: u32) -> NodeMaster {
        // Callers guarantee id <= node_count (sentinel index is valid).
        let r = unsafe { read_at::<NodeRec>(self.nodes, id as usize) };
        NodeMaster {
            lat_e7: r.lat_e7,
            lon_e7: r.lon_e7,
            edge_ptr: u64::from(r.edge_ptr),
        }
    }

    /// Safe node fetch: returns a zeroed node for out-of-range ids, mirroring
    /// the C++ `get_node` fallback.
    ///
    /// Deliberately asymmetric with [`Graph::node`], which is unchecked: the
    /// sentinel index `node_count` is a *valid* read that this method rejects, and
    /// [`Graph::edge_range`] depends on being able to make it.
    #[inline]
    pub fn get_node(&self, id: u32) -> NodeMaster {
        if self.nodes.is_null() || id >= self.node_count {
            return NodeMaster {
                lat_e7: 0,
                lon_e7: 0,
                edge_ptr: 0,
            };
        }
        self.node(id)
    }

    /// Ground elevation of node `id` in metres, or 0 when the graph carries no `elevation.bin` or
    /// `id` is out of range. Baked from the DEM at build time (WS-G); a build without a DEM, or a
    /// node off the DEM's coverage, reads as 0 so the route profile is simply flat there.
    #[inline]
    pub fn node_elevation(&self, id: u32) -> i16 {
        if self.elevation.is_null() || id >= self.node_count {
            return 0;
        }
        unsafe { read_at::<i16>(self.elevation, id as usize) }
    }

    /// `[start, end)` of node `id`'s outgoing edges in `edges.bin`. `id` must be a
    /// real node, i.e. `id < node_count`.
    ///
    /// The only way to ask that question. Every caller that reads an `edge_ptr`
    /// wants a range, not an offset, so the pairing is expressed once here rather
    /// than transcribed at eight sites — which is also what stops a future
    /// `nodes.bin` layout from leaking out of this file.
    ///
    /// # What the sentinel record is for
    ///
    /// `nodes.bin` carries one record past the real nodes whose `edge_ptr` is
    /// `edge_count`. That is what makes this well-defined for the *last* real node:
    /// `id + 1` is read for every node, so without it the last one would read past
    /// the mapping and every other node's range would be one short of correct.
    /// `edge_range(node_count - 1).1 == edge_count` is the property, and it is
    /// load-bearing at eight sites.
    ///
    /// The sentinel index itself is the highest `id` this may be *passed plus one* —
    /// `edge_range(node_count)` would read `node_count + 1`, which does not exist.
    /// Every caller either iterates `0..node_count` or rejects `id >= node_count`
    /// first.
    ///
    /// Note that this goes through [`Graph::node`], which is unchecked, *not*
    /// [`Graph::get_node`], which would reject the sentinel index and silently
    /// return `edge_ptr = 0` — turning the last node's range into an empty one.
    #[inline]
    pub fn edge_range(&self, id: u32) -> (u64, u64) {
        debug_assert!(id < self.node_count, "node {id} is not a real node");
        (self.node(id).edge_ptr, self.node(id + 1).edge_ptr)
    }

    /// Edge `idx`, which must be one of node `source`'s outgoing edges.
    ///
    /// `source` is what makes the record readable at all: it stores `target` as a
    /// signed delta from it. Passing the wrong one produces a plausible node id
    /// rather than a crash, which is why the debug assertion below exists — it is the
    /// cheapest available guard against the one bug class delta coding introduces.
    #[inline]
    pub fn edge(&self, source: u32, idx: u64) -> Edge {
        debug_assert!(
            {
                let (s, e) = self.edge_range(source);
                (s..e).contains(&idx)
            },
            "edge {idx} is not in node {source}'s range {:?}",
            self.edge_range(source)
        );
        let r = unsafe { read_at::<EdgeRec>(self.edges, idx as usize) };
        let (target, dist_mm) = if r.escaped() {
            self.escaped_fields(idx)
        } else {
            (
                source.wrapping_add_signed(i32::from(r.target_delta)),
                r.dist_mm(),
            )
        };
        Edge {
            target,
            dist_mm,
            type_: r.type_,
            speed_limit: r.speed_limit,
        }
    }

    /// `(target, dist_mm)` from the escape table, for an edge whose record carries a
    /// sentinel.
    ///
    /// One load from the block index, then a scan of that block's rows — ~3 of them
    /// at a 0.3% escape rate, usually within one cache line. See [`ESCAPE_BLOCK`].
    ///
    /// A record with a sentinel and no matching row is a corrupt pack that the load-
    /// time checks cannot catch without an O(E) scan. It returns `u32::MAX` as the
    /// target, which every caller already rejects as out of range, so the edge is
    /// skipped rather than followed somewhere arbitrary.
    #[cold]
    fn escaped_fields(&self, idx: u64) -> (u32, u32) {
        let block = (idx / ESCAPE_BLOCK) as usize;
        let lo = unsafe { read_at::<u32>(self.escape_first, block) };
        let hi = unsafe { read_at::<u32>(self.escape_first, block + 1) };
        for r in lo..hi {
            let row = unsafe { read_at::<EscapeRow>(self.escapes, r as usize) };
            if u64::from(row.edge_idx) == idx {
                return (row.target, row.dist_mm);
            }
        }
        debug_assert!(false, "edge {idx} is escaped but has no escape row");
        (u32::MAX, DIST_MM_ESCAPE)
    }

    /// Whether edge `idx` of node `source` points at `want`.
    ///
    /// Four call sites scan a node's edge range looking for the one edge that reaches
    /// a given node and never use the target as a value. Delegating to [`Graph::edge`]
    /// rather than comparing in delta space keeps the escape handling in one place: