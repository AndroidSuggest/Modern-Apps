        assert_eq!(edge_count, stats.edge_count);
        // Uncompacted this was 3 bidirectional segments + 1 oneway = 7 directed
        // edges. Collapsing node 3 merges two of the Main St pairs into one, so
        // 2 (1-2) + 2 (2-4 via 3) + 1 (service) + 2 synthetic stop edges.
        assert_eq!(stats.raw_edge_count, 7);
        assert_eq!(edge_count, 2 + 2 + 1 + 2);

        // The sentinel's edge_ptr is the edge count, and edge_ptr is monotonic.
        assert_eq!(node_at(&o, 4).2, edge_count);
        assert_eq!(node_at(&o, 0).2, 0);
        for i in 0..4 {
            assert!(node_at(&o, i).2 <= node_at(&o, i + 1).2);
        }

        // Nodes are Morton-ordered.
        let keys: Vec<u64> = (0..4)
            .map(|i| {
                let (lat, lon, _) = node_at(&o, i);
                spatial_from_e7(lat, lon)
            })
            .collect();
        assert!(keys.windows(2).all(|w| w[0] <= w[1]), "{keys:?}");

        // Every edge's source range agrees with the nodes.bin pointers, and the
        // whole edge set matches the fixture's geometry.
        // (source lat/lon, target lat/lon, dist_mm, type, speed_limit, name)
        type SeenEdge = (i32, i32, i32, i32, u32, u8, u8, String);
        let mut seen: Vec<SeenEdge> = Vec::new();
        for lid in 0..4usize {
            let (slat, slon, start) = node_at(&o, lid);
            let end = node_at(&o, lid + 1).2;
            for e in start..end {
                let (target, dist, name_off, type_, speed) = edge_at(&o, e as usize);
                let (tlat, tlon, _) = node_at(&o, target as usize);
                let name = if name_off == NO_NAME {
                    String::new()
                } else {
                    name_at(&o, name_off)
                };
                // The road class must be recoverable after masking the flag off;
                // `geometry.rs::ROAD_TYPE_MASK` is the device's half of this.
                seen.push((slat, slon, tlat, tlon, dist, type_ & !REVERSE_GEOMETRY_FLAG, speed, name));
            }
        }
        assert_eq!(seen.len(), edge_count as usize);

        // Main St: 2 segments each way now, type 7 (residential), no maxspeed.
        let main: Vec<_> = seen.iter().filter(|e| e.7 == "Main St").collect();
        assert_eq!(main.len(), 4);
        assert!(main.iter().all(|e| e.5 == 7 && e.6 == 0));
        // The service way is a oneway from node 4 to node 2, 30 mph -> 48 km/h.
        let service: Vec<_> = seen.iter().filter(|e| e.5 == 8).collect();
        assert_eq!(service.len(), 1);
        assert_eq!(service[0].6, 48);
        assert_eq!((service[0].0, service[0].1), (370_030_000, -1_220_000_000));
        assert_eq!((service[0].2, service[0].3), (370_010_000, -1_220_000_000));
        // The bus stop was reconnected both ways with the synthetic type/speed.
        let synth: Vec<_> = seen.iter().filter(|e| e.5 == 12).collect();
        assert_eq!(synth.len(), 2);
        assert!(synth.iter().all(|e| e.6 == 5 && e.7.is_empty()));
        assert_eq!(stats.reconnected_stops, 1);
        assert_eq!(stats.stops_already_connected, 0);
        assert_eq!(stats.stops_unreachable, 0);
        assert_eq!(stats.lcc_size, 3);

        // The collapsed pair carries the merged distance: node 2 -> node 4 is
        // two 10_000e7-unit hops, so twice the 1-2 edge's length.
        let n1 = local_of(&o, 1).unwrap();
        let n2 = local_of(&o, 2).unwrap();
        let n4 = local_of(&o, 4).unwrap();
        let dist_of = |from: u32, to: u32, ty: u8| -> u32 {
            let (_, _, s) = node_at(&o, from as usize);
            let e = node_at(&o, from as usize + 1).2;
            (s..e)
                .filter_map(|k| {
                    let (t, d, _, raw, _) = edge_at(&o, k as usize);
                    (t == to && raw & !REVERSE_GEOMETRY_FLAG == ty).then_some(d)
                })
                .next()
                .unwrap()
        };
        let short = dist_of(n1, n2, 7);
        let merged = dist_of(n2, n4, 7);
        assert!(
            merged.abs_diff(short * 2) <= 2,
            "merged {merged} should be about twice {short}"
        );

        // road_names.bin holds each unique string once. "Main St" is the only way
        // name; "Test Stop" is the bus stop's code. "Plaza" belongs to an
        // unrouted area and must not appear.
        assert_eq!(o.names, b"Main St\0Test Stop\0".to_vec());
        assert_eq!(stats.unique_names, 2);
        assert_eq!(stats.name_bytes, 18);

        // lanes.bin: [u32 n][(edge_idx, off) x (n + 1)][u16 blob]. Only Main St's
        // forward direction carries turn:lanes, and collapsing must not lose them:
        // the 1-2 edge plus the merged 2-4 edge. Every other edge is simply absent
        // from the index rather than owning an empty range.
        let (index, blob) = lane_index(&o);
        assert_eq!(index.len(), 3, "two lane-bearing edges plus the sentinel");
        assert!(
            index[..2].windows(2).all(|w| w[0].0 < w[1].0),
            "index entries must ascend by edge index: {index:?}"
        );
        assert_eq!(index[2].0, u32::MAX, "the sentinel is not a real edge index");
        assert_eq!(index[2].1 as usize, blob.len() * 2, "the sentinel is the blob length");
        assert_eq!(o.lanes.len(), 4 + 3 * 8 + blob.len() * 2);
        // Two forward Main St edges now instead of three, two lane masks each.
        assert_eq!(blob, [[LANE_LEFT, LANE_THROUGH]; 2].concat());
        // Both real entries resolve, which with two of them is the first and the
        // last the binary search can land on.
        for (edge_idx, _) in &index[..2] {
            assert_eq!(
                lane_masks(&o, u64::from(*edge_idx)),
                Some(vec![LANE_LEFT, LANE_THROUGH])
            );
        }
        // Every edge not in the index reads as "no lanes", which is the state
        // `routing.rs`'s topology fallback already handles.
        let listed: Vec<u32> = index[..2].iter().map(|(e, _)| *e).collect();
        for k in 0..edge_count {
            if !listed.contains(&(k as u32)) {
                assert_eq!(lane_masks(&o, k), None, "edge {k} should carry no lanes");
            }
        }
    }

    #[test]
    fn intermediate_bin_reproduces_the_collapsed_geometry() {
        let (pbf_path, dir) = testpbf::write_sample("graph_inter");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);
        let edge_count = edge_count(&o);

        // Offsets reassemble monotonically from both levels, start at zero and end
        // at the blob length. The coarse entry for a block is the offset of that
        // block's first geometry edge, so every within-block value at g % 32 == 0
        // is zero. `G` itself is the trailer's last 8 bytes.
        let t = inter(&o);
        assert_eq!(t.geometry_edges, stats.geometry_edges);
        let offs: Vec<u64> = (0..=t.geometry_edges).map(|g| t.offset(g)).collect();
        assert_eq!(offs[0], 0);
        assert!(offs.windows(2).all(|w| w[0] <= w[1]), "{offs:?}");
        assert_eq!(
            *offs.last().unwrap(),
            t.blob_bytes as u64,
            "last offset must be the blob length"
        );
        for g in (0..=t.geometry_edges).step_by(INTERMEDIATE_BLOCK as usize) {
            let at = t.coarse_at + (g / INTERMEDIATE_BLOCK) as usize * 8;
            let coarse = u64::from_le_bytes(o.inter[at..at + 8].try_into().unwrap());
            assert_eq!(coarse, offs[g as usize], "coarse entry for the block at {g}");
        }
        // The presence bitmap and its rank index have to agree: an edge's rank is
        // the number of storing edges below it, and the total is `G`.
        let mut seen = 0u64;
        for k in 0..edge_count {
            assert_eq!(t.rank(k), seen, "rank at edge {k}");
            if t.present(k) {
                seen += 1;
            }
        }
        assert_eq!(seen, t.geometry_edges);
        assert_eq!(stats.intermediate_bytes, o.inter.len() as u64);

        // Every stored polyline must start at its edge's source and end at its
        // target, and obey the encoding limits.
        let mut with_geometry = 0u64;
        for k in 0..edge_count {
            let (target, _, _, _, _) = edge_at(&o, k as usize);
            let u = (0..node_count(&o) as u32)
                .rev()
                .find(|i| node_at(&o, *i as usize).2 <= k)
                .unwrap();
            let Some((pts, _)) = edge_coords(&o, k) else {
                continue;
            };
            with_geometry += 1;
            assert!(pts.len() <= geom::MAX_POINTS as usize);
            let (slat, slon, _) = node_at(&o, u as usize);
            let (tlat, tlon, _) = node_at(&o, target as usize);
            assert_eq!(pts[0], (slat, slon), "edge {k} geometry starts off its source");
            assert_eq!(
                pts[pts.len() - 1],
                (tlat, tlon),
                "edge {k} geometry ends off its target"
            );
        }

        // The two Main St edges across the collapsed node must pass through the
        // node that was removed. That is the whole claim of compaction: the
        // polyline is unchanged, only the node is gone.
        let (_, lat3, lon3) = testpbf::NODES.iter().find(|n| n.0 == 3).copied().unwrap();
        let through: Vec<_> = (0..edge_count)
            .filter_map(|k| edge_coords(&o, k))
            .filter(|(pts, _)| pts.contains(&(lat3, lon3)))
            .collect();
        assert_eq!(through.len(), 2, "both directions must retain node 3's vertex");
        for (pts, _) in &through {
            assert_eq!(pts.len(), 3);
        }
        assert_eq!(with_geometry, 2, "both directions of the merged pair resolve geometry");
        // Only one polyline is stored: the other direction defers to it. That is
        // what `REVERSE_GEOMETRY_FLAG` buys, and it halves the blob.
        assert_eq!(stats.geometry_edges, 1);
        assert_eq!(stats.reversed_edges, 1);
        let flagged: Vec<u64> = (0..edge_count)
            .filter(|k| edge_at(&o, *k as usize).3 & REVERSE_GEOMETRY_FLAG != 0)
            .collect();
        assert_eq!(flagged.len(), 1);
        // The flagged edge must be resolvable, which is only true because node 2
        // reaches node 4 exactly once. Node 4 reaches node 2 twice (the merged
        // Main St chain and the oneway service road), so had the chain been
        // oriented the other way the flag would have had to be suppressed.
        assert!(edge_coords(&o, flagged[0]).is_some());
    }

    /// A CSR over `kept` nodes from a list of directed edges.
    fn csr_of(kept: u32, edges: &[(u32, u32)]) -> Csr {
        let mut edge_ptr = vec![0u64; kept as usize + 1];
        for (s, _) in edges {
            edge_ptr[*s as usize + 1] += 1;
        }
        for i in 0..kept as usize {
            edge_ptr[i + 1] += edge_ptr[i];
        }
        let mut targets = vec![0u32; edges.len()];
        let mut cursor = edge_ptr.clone();
        for (s, t) in edges {
            targets[cursor[*s as usize] as usize] = *t;
            cursor[*s as usize] += 1;
        }
        for v in 0..kept as usize {
            targets[edge_ptr[v] as usize..edge_ptr[v + 1] as usize].sort_unstable();
        }
        Csr { edge_ptr, targets }
    }

    #[test]
    fn a_twin_is_unique_only_without_parallel_edges() {
        let csr = csr_of(3, &[(0, 1), (1, 0), (1, 2), (2, 1), (2, 1)]);
        // 1 -> 0 has exactly one twin (0 -> 1).
        assert!(twin_is_unique(&csr, &[], 1, 0));
        // 1 -> 2's twin side holds two parallel 2 -> 1 edges.
        assert!(!twin_is_unique(&csr, &[], 1, 2));
        // 2 -> 1's twin side has one 1 -> 2 edge.
        assert!(twin_is_unique(&csr, &[], 2, 1));
        // An absent twin is not unique either.
        assert!(!twin_is_unique(&csr, &[], 1, 1));
    }

    #[test]
    fn a_synthetic_connector_counts_towards_twin_uniqueness() {
        // The reader cannot tell a synthetic connector from a road, so a reversed
        // edge whose twin side also holds a connector back to its source would
        // resolve to whichever the scan met first. The connectors are not in the
        // CSR, so they have to be counted separately.
        let csr = csr_of(3, &[(0, 1), (1, 0)]);
        let synth = [
            Synth {
                source: 1,
                target: 0,
                dist_mm: 5,
            },
            Synth {
                source: 2,
                target: 0,
                dist_mm: 5,
            },
        ];
        // 0 -> 1's twin side now has the real 1 -> 0 *and* a connector 1 -> 0.
        assert!(!twin_is_unique(&csr, &synth, 0, 1));
        // 1 -> 0's twin side has only the real 0 -> 1; the connector points the
        // other way and must not be miscounted.
        assert!(twin_is_unique(&csr, &synth, 1, 0));
        // A node reachable only by a connector has exactly one edge back.
        assert!(twin_is_unique(&csr, &synth, 0, 2));
    }

    #[test]
    fn a_way_ref_to_a_node_absent_from_the_file_costs_only_its_own_pairs() {
        let (pbf_path, dir) = testpbf::write_dangling_sample("graph_dangling");
        let stats = build(&pbf_path, &dir).unwrap();
        let o = read_outputs(&dir);

        // The dangling ref is marked in the node bitset — marks come from way
        // refs — but no blob ever supplies its coordinates, so it must not reach
        // the graph at all.
        assert_eq!(stats.raw_node_count, 3);
        assert_eq!(node_count(&o), 3);

        // Every node in nodes.bin is one the file really defined. A phantom slot
        // would read (0, 0), which is a plausible-looking point in the Atlantic
        // and would corrupt distances and Morton keys without failing anything.
        let mut written: Vec<(i32, i32)> = (0..3)
            .map(|i| {
                let (lat, lon, _) = node_at(&o, i);
                (lat, lon)
            })
            .collect();
        written.sort();
        let mut want: Vec<(i32, i32)> = testpbf::DANGLING_NODES.iter().map(|n| (n.1, n.2)).collect();
        want.sort();
        assert_eq!(written, want);

        // The way is [1, 2, 999, 3], so the 1-2 pair resolves and both pairs
        // touching 999 do not: one bidirectional edge, and node 3 left isolated.
        assert_eq!(stats.raw_edge_count, 2);
        assert_eq!(stats.edge_count, 2);
        assert_eq!(stats.lcc_size, 2);
        for k in 0..stats.edge_count {
            let (target, _, _, type_, _) = edge_at(&o, k as usize);
            assert!((target as usize) < 3, "edge {k} targets a node that does not exist");
            assert_eq!(type_ & !REVERSE_GEOMETRY_FLAG, 7);
        }
    }

    #[test]
    fn a_dem_bakes_a_per_node_elevation_in_final_id_order() {
        // A whole-world DEM at zoom 0 (one grid covers every coordinate), so every fixture node is
        // guaranteed on coverage. A `dim x dim` ramp gives elevation that varies with position, so
        // the baked value is a real interpolation rather than a constant.
        let (pbf_path, dir) = testpbf::write_sample("graph_dem_bake");
        let dem_path = dir.join("world.mdem");
        let dim: u16 = 4;
        let mut bytes = Vec::new();
        bytes.extend_from_slice(b"MDEM");
        bytes.push(1); // version
        bytes.push(0); // out_zoom 0: a single tile covers the world
        bytes.extend_from_slice(&dim.to_le_bytes());
        bytes.extend_from_slice(&1u32.to_le_bytes()); // one tile
        bytes.extend_from_slice(&0u64.to_le_bytes()); // tile_id(0, 0, 0) == 0
        for row in 0..dim {
            for col in 0..dim {
                // 32768 bias + a ramp, so metres = row * 100 + col * 10 across the grid.
                let sample = 32768u16 + row * 100 + col * 10;
                bytes.extend_from_slice(&sample.to_le_bytes());
            }
        }
        std::fs::write(&dem_path, &bytes).unwrap();

        build_with(
            &pbf_path,
            &dir,
            Options {
                dem: Some(dem_path.clone()),
                ..Default::default()
            },
        )
        .unwrap();

        // elevation.bin is `i16[node_count]` in final-node-id order, parallel to nodes.bin.
        let nodes = std::fs::read(dir.join("nodes.bin")).unwrap();
        let elev = std::fs::read(dir.join("elevation.bin")).unwrap();
        let node_count = nodes.len() / 12 - 1; // one trailing sentinel node
        assert_eq!(elev.len(), node_count * 2, "one i16 per real node");

        // Round-trip: each baked metre equals the DEM sampled at that node's own coordinate.
        let dem = crate::dem::Dem::load(&dem_path).unwrap();
        let mut any_nonzero = false;
        for i in 0..node_count {
            let lat_e7 = i32::from_le_bytes(nodes[i * 12..i * 12 + 4].try_into().unwrap());
            let lon_e7 = i32::from_le_bytes(nodes[i * 12 + 4..i * 12 + 8].try_into().unwrap());
            let want = dem
                .sample_metres(f64::from(lon_e7) * 1e-7, f64::from(lat_e7) * 1e-7)
                .unwrap_or(0);
            let got = i16::from_le_bytes([elev[i * 2], elev[i * 2 + 1]]);
            assert_eq!(got, want, "node {i} baked elevation");
            any_nonzero |= got != 0;
        }
        assert!(any_nonzero, "the ramp should give at least one node a non-zero elevation");
    }

    #[test]
    fn a_build_without_a_dem_omits_elevation_bin() {
        let (pbf_path, dir) = testpbf::write_sample("graph_no_dem");
        build(&pbf_path, &dir).unwrap();
        assert!(
            !dir.join("elevation.bin").exists(),
            "no --dem means no elevation.bin, and the graph is still valid",
        );
    }

    #[test]
    fn two_runs_are_byte_identical() {
        let (pbf_path, dir_a) = testpbf::write_sample("graph_det_a");
        let (_, dir_b) = testpbf::write_sample("graph_det_b");
        build(&pbf_path, &dir_a).unwrap();
        build(&pbf_path, &dir_b).unwrap();
        for f in [
            "metadata.bin",
            "nodes.bin",
            "edges.bin",
            "lanes.bin",
            "road_names.bin",
            "intermediate.bin",
        ] {
            assert_eq!(
                std::fs::read(dir_a.join(f)).unwrap(),
                std::fs::read(dir_b.join(f)).unwrap(),
                "{f} differs between runs"
            );
        }
    }

    /// The gate for threading the graph build: all six outputs must be byte-identical
    /// at every thread count.
    ///
    /// `nodes.bin` is the one that would move first. Final node ids come from the
    /// spatial sort, and every offset in `edges.bin`, `lanes.bin` and
    /// `intermediate.bin` is expressed in terms of them — so a sort whose order is not
    /// total would not corrupt one file, it would silently renumber the whole pack.
    /// Multiple rounds are covered too, because `write_graph` partitions by source and
    /// each round writes its own slice of `intermediate.bin`.
    #[test]
    fn the_thread_count_changes_no_graph_bytes() {
        for rounds in [1u32, 3] {
            let (pbf_path, base_dir) = testpbf::write_sample(&format!("graph_threads_{rounds}_1"));
            let run = |n: usize, dir: &Path| {
                crate::par::set_threads(n);
                build_with(
                    &pbf_path,
                    dir,
                    Options {
                        rounds,
                        ..Default::default()
                    },
                )
                .unwrap();
                read_outputs(dir)
            };
            let base = run(1, &base_dir);
            for n in [2, 3, 32] {
                let (_, dir) = testpbf::write_sample(&format!("graph_threads_{rounds}_{n}"));
                let got = run(n, &dir);
                assert_eq!(got.meta, base.meta, "{rounds} round(s), {n} threads: metadata.bin");
                assert_eq!(got.nodes, base.nodes, "{rounds} round(s), {n} threads: nodes.bin");
                assert_eq!(got.edges, base.edges, "{rounds} round(s), {n} threads: edges.bin");
                assert_eq!(got.lanes, base.lanes, "{rounds} round(s), {n} threads: lanes.bin");
                assert_eq!(got.names, base.names, "{rounds} round(s), {n} threads: road_names.bin");
                assert_eq!(got.inter, base.inter, "{rounds} round(s), {n} threads: intermediate.bin");
            }
        }
        crate::par::clear_threads();
    }

    /// The substitution the spatial sort relies on: because the key is total, an
    /// unstable sort produces exactly what the old stable `sort_by_key(spatial)` did.
    ///