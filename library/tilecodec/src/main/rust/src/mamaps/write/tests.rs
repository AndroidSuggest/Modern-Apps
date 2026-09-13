use super::*;
use crate::mamaps::body::Body;
    use crate::mamaps::header::Header;
    use crate::mamaps::shared::{SharedLogicalRow, SharedView, SHARED_MAGIC};
    use crate::pmtiles::tile_id;

    // Fail-watch convention (lane A's): each test pins one wire fact as a
    // literal, so a revert of that fact quotes its failure (`left` vs
    // `right`) rather than a vague mismatch. Revert → quote → restore.

    fn tiny_body() -> Body {
        Body::new(crate::mamaps::body::DEFAULT_EXTENT)
    }

    fn two_tiles(options: Options) -> Vec<u8> {
        let mut w = StreamWriter::new(options).expect("options");
        w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
        w.append(tile_id(1, 0, 0), &tiny_body()).expect("append");
        w.finish().expect("finish")
    }

    /// The flag defaults OFF, and OFF is byte-identical v7 however it is spelled.
    #[test]
    fn shared_table_defaults_off_and_off_is_byte_identical_v7() {
        assert!(
            !Options::default().shared_table,
            "shared_table must default OFF so default builds stay v7"
        );
        let mut w = StreamWriter::new(Options::default()).expect("options");
        assert!(w.shared_builder().is_none(), "no builder when the flag is off");
        drop(w);
        let implicit = two_tiles(Options::default());
        let explicit = two_tiles(Options { shared_table: false, ..Options::default() });
        assert_eq!(implicit, explicit, "flag OFF must be byte-identical v7 either way");
        let header = Header::parse(&implicit).expect("header");
        assert_eq!(
            header.file_len as usize, implicit.len(),
            "file_len covers exactly the v7 sections"
        );
        assert_eq!(implicit.len(), 128 + header.dict_len as usize + header.root_len as usize + header.leaf_len as usize + header.data_len as usize);
        assert_eq!(implicit[7], 7, "a shared-table-off archive is version byte 7");
        assert!(header.shared_location().is_none(), "no shared section published when off");
    }

    /// The shared section starts exactly at `data_offset + data_len`, opens
    /// behind `MBSH`, and `file_len` covers it — and both finishes emit it.
    ///
    /// The header publishes the section too: version byte 8, 160 bytes, and
    /// `shared_offset`/`shared_len` naming exactly the trailing bytes (lane
    /// B's fields, wired here). Off, the header stays 128-byte v7.
    #[test]
    fn a_shared_section_starts_exactly_at_data_end_and_file_len_covers_it() {
        let mut w =
            StreamWriter::new(Options { shared_table: true, ..Options::default() }).expect("options");
        assert!(w.shared_builder().is_some(), "the flag buys a builder");
        // Interned in tile-id arrival order: z0's row before z1's.
        let name = w.shared_builder().expect("builder").intern_string("Main St");
        assert_eq!(name, 1, "the first string is ref 1 (0 is none)");
        w.shared_builder().expect("builder").push_row(
            SharedLogicalRow {
                logical_id: 7,
                name_ref: name,
                kind: 45,
                kind_detail: 0,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 0,
                lane_turns_idx: 0,
                flags: 0,
            },
            crate::mamaps::body::ID_NONE,
        );
        w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
        w.append(tile_id(1, 0, 0), &tiny_body()).expect("append");
        let bytes = w.finish().expect("finish");

        let header = Header::parse(&bytes).expect("header");
        assert_eq!(header.file_len as usize, bytes.len(), "file_len covers the shared section");
        let at = (header.data_offset + header.data_len) as usize;
        let view = SharedView::parse(&bytes[at..]).expect("parse the shared section");
        // Lane B's publication: a shared table is a v8 header (160 bytes,
        // version 8) whose shared fields name the trailing bytes exactly.
        assert_eq!(
            header.shared_location(),
            Some((at as u64, bytes.len() as u64 - at as u64)),
            "shared_offset/shared_len name the bytes past the tile data",
        );
        assert_eq!(
            header.shared_pools, view.header.pool_count,
            "the header's pool count is the section's own",
        );
        assert!(at < bytes.len(), "a shared table adds trailing bytes past the tile data");
        assert_eq!(&bytes[at..at + 4], SHARED_MAGIC, "the shared section starts at data end");
        assert_eq!(view.header.row_count, 1, "one logical row");
        assert_eq!(view.header.string_count, 1, "one interned name");
        assert_eq!(view.strings.lookup(1), Some("Main St"));

        // `finish_to_path` is the same file, not a second archive.
        let out = std::env::temp_dir().join(format!("mamaps_lane_d_{}", std::process::id()));
        let mut w =
            StreamWriter::new(Options { shared_table: true, ..Options::default() }).expect("options");
        let name = w.shared_builder().expect("builder").intern_string("Main St");
        w.shared_builder().expect("builder").push_row(
            SharedLogicalRow {
                logical_id: 7,
                name_ref: name,
                kind: 45,
                kind_detail: 0,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 0,
                lane_turns_idx: 0,
                flags: 0,
            },
            crate::mamaps::body::ID_NONE,
        );
        w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
        w.append(tile_id(1, 0, 0), &tiny_body()).expect("append");
        w.finish_to_path(&out).expect("finish_to_path");
        let streamed = std::fs::read(&out).expect("read back");
        assert_eq!(streamed, bytes, "streamed and in-memory finishes emit one archive");
        let _ = std::fs::remove_file(&out);
    }

    /// Rows hit the wire ascending by `logical_id` whatever order they arrive
    /// in: `serialize` sorts, so hash-map or thread order can never leak out.
    #[test]
    fn shared_rows_emit_in_logical_id_order_whichever_order_they_arrive_in() {
        let build = |order: &[u32]| {
            let mut w = StreamWriter::new(Options { shared_table: true, ..Options::default() })
                .expect("options");
            {
                let b = w.shared_builder().expect("builder");
                for &id in order {
                    b.push_row(
                        SharedLogicalRow {
                            logical_id: id,
                            name_ref: 0,
                            kind: 1,
                            kind_detail: 0,
                            view_bits: 0,
                            building_idx: 0,
                            carriageway_idx: 0,
                            lane_turns_idx: 0,
                            flags: 0,
                        },
                        id as u64 * 1000,
                    );
                }
            }
            w.append(tile_id(0, 0, 0), &tiny_body()).expect("append");
            w.finish().expect("finish")
        };
        let (scrambled, ordered) = (build(&[3, 1, 2]), build(&[1, 2, 3]));
        assert_eq!(scrambled, ordered, "row push order must not reach the wire");
        let header = Header::parse(&scrambled).expect("header");
        let at = (header.data_offset + header.data_len) as usize;
        let view = SharedView::parse(&scrambled[at..]).expect("parse");
        let ids: Vec<u32> = view.rows.iter().map(|r| r.logical_id).collect();
        assert_eq!(ids, vec![1, 2, 3], "rows on the wire ascend by logical_id");
    }
