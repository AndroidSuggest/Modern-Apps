#[cfg(test)]
mod tests {
    use super::*;

    /// A straight line of nodes 0.001 degrees apart, well inside the i16 delta
    /// range, so nothing in these tests is interpolated.
    fn coords(n: usize) -> Vec<geom::Pt> {
        (0..n)
            .map(|i| (370_000_000 + (i as i32) * 10_000, -1_220_000_000))
            .collect()
    }

    #[test]
    fn a_run_is_split_to_fit_the_geometry_budget() {
        // Nodes 0.02 degrees apart: about 7 encoded points per segment, so ~36
        // segments fill one edge's 256-point budget.
        let far: Vec<geom::Pt> = (0..81)
            .map(|i| (370_000_000 + i * 200_000, -1_220_000_000))
            .collect();
        let path: Vec<u32> = (0..81).collect();
        let runs = split_runs(&path, &far);
        assert!(runs.len() >= 3, "{runs:?}");
        // The runs must tile the path, sharing their boundary nodes.
        assert_eq!(runs[0].0, 0);
        assert_eq!(runs.last().unwrap().1, 80);
        for w in runs.windows(2) {
            assert_eq!(w[0].1, w[1].0, "runs must share a node");
        }
        // Every run must be encodable, or be a bare chord the reader can infer.
        for (lo, hi) in &runs {
            let pts: Vec<geom::Pt> = path[*lo..=*hi].iter().map(|n| far[*n as usize]).collect();
            assert!(pts.len() == 2 || geom::fits(&pts), "{} points", pts.len());
        }
    }

    #[test]
    fn a_single_unencodable_segment_becomes_its_own_run() {
        // 30 degrees apart: one segment needs ~9155 encoded points, far past the
        // ceiling, so it must be emitted alone and left without geometry.
        let far = vec![(100_000_000, 0), (400_000_000, 0), (700_000_000, 0)];
        let runs = split_runs(&[0, 1, 2], &far);
        assert_eq!(runs, vec![(0, 1), (1, 2)]);
    }

    #[test]
    fn a_short_run_is_never_split() {
        let c = coords(5);
        assert_eq!(split_runs(&[0, 1, 2, 3, 4], &c), vec![(0, 4)]);
        assert_eq!(split_runs(&[0, 1], &c), vec![(0, 1)]);
    }

    fn chain(pts_start: u64, pts_len: u32) -> Chain {
        Chain {
            pts_start,
            pts_len,
            dist_mm: 4_000,
            name_offset: 17,
            type_: 7,
            speed_limit: 48,
            oneway: true,
            fwd_lane_off: 3,
            fwd_lane_count: 2,
            bwd_lane_off: 9,
            bwd_lane_count: 1,
        }
    }

    fn spill_dir(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("osm_ingest_{tag}"));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn the_spill_round_trips_chains_and_resolves_points_to_coordinates() {
        let dir = spill_dir("spill_round_trip");
        let all = coords(6);
        // Two chains tiling six points, laid out contiguously as the chain pass
        // produces them.
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];

        let spill = Spill::new(&dir);
        spill.write(&src, &dense, &all).unwrap();
        assert_eq!(spill.chain_count().unwrap(), 2);

        let (back, pts) = spill.read_all().unwrap();
        assert_eq!(pts, all, "points come back as coordinates, in order");
        assert_eq!(back.len(), 2);
        // The endpoints are node ids, which coordinates alone could not recover.
        assert_eq!((back[0].first, back[0].last), (0, 3));
        assert_eq!((back[1].first, back[1].last), (4, 5));
        for (a, b) in src.iter().zip(&back) {
            assert_eq!(b.pts_start, a.pts_start);
            assert_eq!(b.pts_len, a.pts_len);
            assert_eq!(b.dist_mm, a.dist_mm);
            assert_eq!(b.name_offset, a.name_offset);
            assert_eq!(b.type_, a.type_);
            assert_eq!(b.speed_limit, a.speed_limit);
            assert_eq!(b.oneway, a.oneway);
            assert_eq!((b.fwd_lane_off, b.fwd_lane_count), (a.fwd_lane_off, a.fwd_lane_count));
            assert_eq!((b.bwd_lane_off, b.bwd_lane_count), (a.bwd_lane_off, a.bwd_lane_count));
        }
        // Each chain's polyline is addressable without reading the ones before it,
        // which is what the fixed-size header buys.
        assert_eq!(back[1].pts(&pts), &all[4..6]);

        spill.remove();
        assert!(spill.chain_count().is_err(), "the spill is gone after remove");
    }

    #[test]
    fn two_spills_of_the_same_chains_are_byte_identical() {
        let all = coords(6);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];
        let mut written: Vec<(Vec<u8>, Vec<u8>)> = Vec::new();
        for tag in ["spill_det_a", "spill_det_b"] {
            let dir = spill_dir(tag);
            let spill = Spill::new(&dir);
            spill.write(&src, &dense, &all).unwrap();
            written.push((
                std::fs::read(&spill.hdr).unwrap(),
                std::fs::read(&spill.pts).unwrap(),
            ));
        }
        assert_eq!(written[0].0, written[1].0, "chains.hdr differs between runs");
        assert_eq!(written[0].1, written[1].1, "chains.pts differs between runs");
        // The record stride is what lets a later pass seek to chain k, so it is
        // part of the format and not an implementation detail.
        assert_eq!(written[0].0.len() as u64, 2 * CHAIN_REC_BYTES);
        assert_eq!(written[0].1.len() as u64, 6 * CHAIN_PT_BYTES);
    }

    #[test]
    fn streaming_the_spill_matches_writing_it_in_one_go() {
        // The chain pass streams into the spill from its sink so it never holds the
        // chain set; the reference path still writes it in one call. Those two must
        // produce the same bytes, or the two collapse paths would not be comparable
        // and the round-count byte-identity test would be checking the wrong thing.
        let all = coords(9);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5, 6, 7, 8];
        let src = vec![chain(0, 4), chain(4, 2), chain(6, 3)];

        let batch = Spill::new(&spill_dir("spill_batch"));
        batch.write(&src, &dense, &all).unwrap();

        let streamed = Spill::new(&spill_dir("spill_stream"));
        let mut w = streamed.writer().unwrap();
        for c in &src {
            let lo = c.pts_start as usize;
            w.push(c, &dense[lo..lo + c.pts_len as usize], &all).unwrap();
        }
        assert_eq!(w.finish().unwrap(), 3);

        assert_eq!(
            std::fs::read(&batch.hdr).unwrap(),
            std::fs::read(&streamed.hdr).unwrap(),
            "chains.hdr differs between the batch and streaming writers"
        );
        assert_eq!(
            std::fs::read(&batch.pts).unwrap(),
            std::fs::read(&streamed.pts).unwrap(),
            "chains.pts differs between the batch and streaming writers"
        );
        assert_eq!(streamed.chain_count().unwrap(), 3);
    }

    #[test]
    fn splitting_the_two_files_across_directories_changes_no_bytes() {
        // The header and the points can live on different filesystems, because one
        // is streamed and the other is seeked. Which directory each lands in must
        // not affect what is in them.
        let all = coords(6);
        let dense: Vec<u32> = vec![0, 1, 2, 3, 4, 5];
        let src = vec![chain(0, 4), chain(4, 2)];

        let together = Spill::new(&spill_dir("spill_together"));
        together.write(&src, &dense, &all).unwrap();

        let hdr_dir = spill_dir("spill_apart_hdr");
        let pts_dir = spill_dir("spill_apart_pts");
        let apart = Spill::split(&hdr_dir, &pts_dir);
        apart.write(&src, &dense, &all).unwrap();

        assert_eq!(
            std::fs::read(&together.hdr).unwrap(),
            std::fs::read(&apart.hdr).unwrap()
        );
        assert_eq!(
            std::fs::read(&together.pts).unwrap(),
            std::fs::read(&apart.pts).unwrap()
        );
        // Each file really is under its own directory, and the reader finds both.
        assert!(apart.hdr.starts_with(&hdr_dir));
        assert!(apart.pts.starts_with(&pts_dir));
        let (chains, pts) = apart.read_all().unwrap();
        assert_eq!(chains.len(), 2);
        assert_eq!(pts, all);

        apart.remove();
        assert!(!apart.hdr.exists() && !apart.pts.exists());
    }

    #[test]
    fn a_header_that_is_not_a_whole_number_of_records_is_rejected() {
        let dir = spill_dir("spill_ragged");
        let all = coords(2);
        let spill = Spill::new(&dir);
        spill.write(&[chain(0, 2)], &[0, 1], &all).unwrap();
        // Truncating mid-record must fail loudly: read_all would otherwise decode a
        // partial record as a chain full of zeros, which is a structurally valid
        // chain pointing at node 0.
        let mut bytes = std::fs::read(&spill.hdr).unwrap();
        bytes.truncate(bytes.len() - 1);
        std::fs::write(&spill.hdr, &bytes).unwrap();
        assert!(spill.chain_count().is_err());
        assert!(spill.read_all().is_err());
    }
}
