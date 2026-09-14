//! Tests for [`super::traffic`], kept beside the schema rules they pin.
#[cfg(test)]
pub(crate) mod tests {
    use super::super::traffic::*;
    use crate::store::Sink;
    use tilecodec::mamaps::dict::LAYER_TRAFFIC;

    /// One directed edge of a synthetic graph.
    pub(crate) struct EdgeSpec {
        pub source: u32,
        pub target: u32,
        pub type_: u8,
        /// Interior points as `(d_lat, d_lon)` deltas, chained from the source. An empty list is a
        /// straight chord, which is how the presence bitmap says "this edge stores no geometry".
        pub interior: Vec<(i16, i16)>,
    }

    pub(crate) struct GraphFixture {
        pub dir: std::path::PathBuf,
    }
    impl Drop for GraphFixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    /// Write a real v6 graph to a fresh temp directory, byte for byte as the contract specifies.
    ///
    /// `pub(crate)` because [`crate::schema::junction`] reads the same four files and tests against
    /// the same layout; a second writer would be a second chance to disagree with `osm_ingest`.
    ///
    /// `edges` must be grouped by `source` ascending, which is what makes `nodes.bin`'s `edge_ptr`
    /// a CSR row pointer. `lanes` is `(edge_idx, masks)` ascending, and writes no `lanes.bin` at
    /// all when empty — which is the common shape of a real graph.
    pub(crate) fn write_graph(
        tag: &str,
        coords: &[(i32, i32)],
        edges: &[EdgeSpec],
        lanes: &[(u32, Vec<u16>)],
    ) -> GraphFixture {
        use std::sync::atomic::{AtomicU64, Ordering};
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let dir = std::env::temp_dir().join(format!(
            "mamaps_{tag}_{}_{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
        ));
        std::fs::create_dir_all(&dir).expect("mkdir");

        let node_count = coords.len() as u64;
        let edge_count = edges.len() as u64;
        assert!(
            edges.windows(2).all(|w| w[0].source <= w[1].source),
            "a CSR row pointer needs the edges grouped by source",
        );

        // metadata.bin (40 bytes).
        let mut meta = Vec::new();
        meta.extend_from_slice(&MARG_MAGIC.to_le_bytes());
        meta.extend_from_slice(&GRAPH_VERSION.to_le_bytes());
        meta.extend_from_slice(&node_count.to_le_bytes());
        meta.extend_from_slice(&edge_count.to_le_bytes());
        meta.extend_from_slice(&0u64.to_le_bytes()); // escape_count
        meta.extend_from_slice(&0u64.to_le_bytes()); // named_edges
        std::fs::write(dir.join("metadata.bin"), &meta).expect("meta");

        // nodes.bin: NodeRec[node_count + 1], 12 bytes each. `edge_ptr` is the index of the first
        // edge leaving each node, and the sentinel holds `edge_count`.
        let mut nodes = Vec::new();
        for (n, (lat, lon)) in coords.iter().enumerate() {
            let first = edges.iter().position(|e| e.source as usize >= n).unwrap_or(edges.len());
            nodes.extend_from_slice(&lat.to_le_bytes());
            nodes.extend_from_slice(&lon.to_le_bytes());
            nodes.extend_from_slice(&(first as u32).to_le_bytes());
        }
        nodes.extend_from_slice(&0i32.to_le_bytes());
        nodes.extend_from_slice(&0i32.to_le_bytes());
        nodes.extend_from_slice(&(edge_count as u32).to_le_bytes());
        std::fs::write(dir.join("nodes.bin"), &nodes).expect("nodes");

        // edges.bin: EdgeRec[edge_count], 7 bytes: i16 target_delta, u24 dist, u8 type_, u8 speed.
        let mut raw = Vec::new();
        for e in edges {
            let delta = (i64::from(e.target) - i64::from(e.source)) as i16;
            raw.extend_from_slice(&delta.to_le_bytes());
            // dist: a plausible non-escape value.
            raw.extend_from_slice(&[0x10, 0x00, 0x00]);
            raw.push(e.type_);
            raw.push(50); // speed_limit
        }
        // Pad the EdgeRec section to an 8-byte boundary, then the all-zero escape block index.
        while raw.len() % 8 != 0 {
            raw.push(0);
        }
        for _ in 0..(edge_count.div_ceil(ESCAPE_BLOCK) + 1) {
            raw.extend_from_slice(&0u32.to_le_bytes());
        }
        std::fs::write(dir.join("edges.bin"), &raw).expect("edges");

        // intermediate.bin: the interior-point blob, then the presence bitmap, the coarse offsets
        // and the per-geometry-edge within-block offsets, then the geometry-edge count.
        let mut blob = Vec::new();
        let mut within = Vec::new();
        let mut present = vec![0u8; edge_count.div_ceil(8).max(1) as usize];
        let mut g_edges = 0u64;
        for (idx, e) in edges.iter().enumerate() {
            if e.interior.is_empty() {
                continue;
            }
            present[idx / 8] |= 1 << (idx % 8);
            within.extend_from_slice(&(blob.len() as u16).to_le_bytes());
            for (d_lat, d_lon) in &e.interior {
                blob.extend_from_slice(&d_lat.to_le_bytes());
                blob.extend_from_slice(&d_lon.to_le_bytes());
            }
            g_edges += 1;
        }
        within.extend_from_slice(&(blob.len() as u16).to_le_bytes());

        let mut inter = blob;
        // One rank word per RANK_BLOCK_BYTES of presence, all zero: every fixture here is well
        // under 512 edges, so the rank of the first block is 0 and the rest is a popcount scan.
        for _ in 0..(edge_count.div_ceil(512) + 1) {
            inter.extend_from_slice(&0u64.to_le_bytes());
        }
        inter.extend_from_slice(&present);
        for _ in 0..(g_edges.div_ceil(INTERMEDIATE_BLOCK) + 1) {
            inter.extend_from_slice(&0u64.to_le_bytes());
        }
        inter.extend_from_slice(&within);
        inter.extend_from_slice(&g_edges.to_le_bytes());
        std::fs::write(dir.join("intermediate.bin"), &inter).expect("inter");

        // lanes.bin: [u32 n][(u32 edge_idx, u32 blob_off) x (n + 1)][u16 blob]. Sparse, so a graph
        // with no tagged turn lanes carries no file at all.
        if !lanes.is_empty() {
            let mut index = Vec::new();
            let mut masks = Vec::new();
            index.extend_from_slice(&(lanes.len() as u32).to_le_bytes());
            for (idx, lane_masks) in lanes {
                index.extend_from_slice(&idx.to_le_bytes());
                index.extend_from_slice(&(masks.len() as u32).to_le_bytes());
                for m in lane_masks {
                    masks.extend_from_slice(&m.to_le_bytes());
                }
            }
            // The trailing sentinel exists only to give the last edge an end offset.
            index.extend_from_slice(&u32::MAX.to_le_bytes());
            index.extend_from_slice(&(masks.len() as u32).to_le_bytes());
            index.extend_from_slice(&masks);
            std::fs::write(dir.join("lanes.bin"), &index).expect("lanes");
        }

        GraphFixture { dir }
    }

    /// The traffic layer's own graph: four nodes in a square. Edges:
    /// * 0: node0 -> node1, one-way, curved (one interior point). Emitted: 2 segments.
    /// * 1: node1 -> node2, straight two-way (canonical, `1 < 2`). Emitted: 1 segment.
    /// * 2: node2 -> node1, straight two-way (non-canonical twin of edge 1). Skipped.
    /// * 3: node2 -> node3, pedestrian (type 10). Skipped (not drivable).
    fn write_fixture() -> GraphFixture {
        let coords: [(i32, i32); 4] = [
            (35_000_000, -120_000_000),
            (35_000_000, -119_990_000),
            (35_010_000, -119_990_000),
            (35_010_000, -120_000_000),
        ];
        let edge = |source, target, type_, interior: &[(i16, i16)]| EdgeSpec {
            source,
            target,
            type_,
            interior: interior.to_vec(),
        };
        write_graph(
            "traffic",
            &coords,
            &[
                // An interior point roughly midway, curving off the straight chord.
                edge(0, 1, 1, &[(3_000, -5_000)]),
                edge(1, 2, 7, &[]),
                edge(2, 1, 7, &[]),
                edge(2, 3, 10, &[]),
            ],
            &[],
        )
    }

    #[test]
    fn the_component_id_packing_round_trips() {
        for (edge, seg) in [(0u64, 0u32), (1, 0), (5, 254), (1_000_000, 42)] {
            let id = pack_component_id(edge, seg);
            assert_eq!(unpack_component_id(id), (edge, seg));
        }
        // The contract's worked example.
        assert_eq!(pack_component_id(5, 3), (5 << 16) | 3);
    }

    #[test]
    fn a_synthetic_graph_streams_the_expected_segments() {
        let fixture = write_fixture();
        let spill = fixture.dir.join("features.tmp");
        let mut sink = Sink::create(&spill).expect("sink");
        let emitted = stream_graph(&fixture.dir, &mut sink).expect("stream");
        let store = sink.finish(&spill).expect("finish");

        // Edge 0 (curved, 3 vertices) -> 2 segments; edge 1 (straight canonical) -> 1 segment.
        // Edge 2 is the non-canonical twin of edge 1 (skipped); edge 3 is pedestrian (skipped).
        assert_eq!(emitted, 3, "two segments from the curved edge, one from the straight two-way");
        assert_eq!(store.len(), 3);

        // Read every feature back and confirm each id unpacks to a valid (big_edge_id, seg_index).
        let mut reader = store.reader().expect("reader");
        let mut ids = Vec::new();
        while let Some(feature) = reader.next().expect("read") {
            assert_eq!(feature.class.layer, LAYER_TRAFFIC);
            assert_eq!(feature.class.min_zoom, MIN_ZOOM);
            let (edge, seg) = unpack_component_id(feature.id);
            assert!(edge < 4, "edge {edge} is past the graph");
            assert!(seg <= 254, "seg_index {seg} does not fit the contract");
            ids.push((edge, seg));
        }
        // The curved edge 0 contributes segments 0 and 1; the straight edge 1 contributes segment 0.
        assert!(ids.contains(&(0, 0)), "{ids:?}");
        assert!(ids.contains(&(0, 1)), "{ids:?}");
        assert!(ids.contains(&(1, 0)), "{ids:?}");
        let _ = std::fs::remove_file(&spill);
    }
}
