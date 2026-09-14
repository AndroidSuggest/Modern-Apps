//! Self-checks for [`crate::write`], extracted wholesale so `write.rs` stays small.
//!
//! Kept as a sibling module so production callers keep using `crate::write::*`
//! with no path changes; only the test imports point at `crate::write`.

#[cfg(test)]
mod tests {
    use crate::extract::{Row, Strings};
    use crate::format::*;
    use crate::write::{decode_column, decode_dict, verify, write};

    struct TempDir(std::path::PathBuf);
    impl TempDir {
        fn new(tag: &str) -> TempDir {
            let p = std::env::temp_dir().join(format!("geodb-{tag}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&p);
            std::fs::create_dir_all(&p).unwrap();
            TempDir(p)
        }
    }
    impl Drop for TempDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    /// Build rows the way extraction does, so the test exercises the same interning path the
    /// real build uses rather than a shortcut around it.
    fn sample_rows(count: usize) -> (Vec<Row>, Strings) {
        let mut strings = Strings::default();
        let mut state = 0x1234_5678u64;
        let mut next = || {
            state = state.wrapping_mul(6364136223846793005).wrapping_add(1);
            state
        };
        let rows = (0..count)
            .map(|i| {
                let kind = match i % 4 {
                    0 => K_ADDRESS,
                    1 => K_STREET,
                    2 => K_POI,
                    _ => K_PLACE,
                };
                let name =
                    if kind == K_ADDRESS { String::new() } else { format!("Feature {}", i % 977) };
                let house = if kind == K_ADDRESS { format!("{}", i % 200) } else { String::new() };
                let street =
                    if kind == K_ADDRESS { format!("Street {}", i % 313) } else { String::new() };
                let ids = [
                    strings.dicts[D_NAME].intern(&name),
                    strings.dicts[D_HOUSE].intern(&house),
                    strings.dicts[D_STREET].intern(&street),
                    strings.dicts[D_CITY].intern(&format!("City {}", i % 47)),
                    strings.dicts[D_STATE].intern(&format!("State {}", i % 11)),
                    strings.dicts[D_COUNTRY].intern(["US", "GB", "DE", "JP"][i % 4]),
                    strings.dicts[D_POSTCODE].intern(&format!("{:05}", i % 9000)),
                ];
                Row {
                    lat_e7: (next() % 1_800_000_000) as i64 as i32 - 900_000_000,
                    lon_e7: ((next() % 3_600_000_000) as i64 - 1_800_000_000) as i32,
                    kind,
                    ids,
                }
            })
            .collect();
        (rows, strings)
    }

    #[test]
    fn a_database_round_trips_through_the_verifier() {
        let d = TempDir::new("roundtrip");
        let (mut rows, mut strings) = sample_rows(BLOCK * 2 + 123);
        let out = d.0.join("geocoder-v3.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, rows.len());
        assert!(report.total > 0);
        // `write` sorts in place and rewrites ids, so `rows` is now exactly what the file
        // should contain.
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn records_come_out_in_grid_then_z_order() {
        let d = TempDir::new("order");
        let (mut rows, mut strings) = sample_rows(2000);
        let out = d.0.join("g.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        for w in rows.windows(2) {
            let (a, b) = (&w[0], &w[1]);
            let ca = cell_id(a.lat_e7, a.lon_e7);
            let cb = cell_id(b.lat_e7, b.lon_e7);
            assert!(ca <= cb, "cells out of order");
            if ca == cb {
                assert!(
                    morton_in_cell(a.lat_e7, a.lon_e7) <= morton_in_cell(b.lat_e7, b.lon_e7),
                    "z-order broken inside a cell"
                );
            }
        }
    }

    #[test]
    fn two_runs_produce_identical_bytes() {
        let d = TempDir::new("determinism");
        let (mut a, mut sa) = sample_rows(1500);
        let (mut b, mut sb) = sample_rows(1500);
        // Feed the second run in a different order; the sort must erase the difference.
        b.reverse();
        let pa = d.0.join("a.geodb");
        let pb = d.0.join("b.geodb");
        let _ = write(&pa, &mut a, &mut sa).unwrap();
        let _ = write(&pb, &mut b, &mut sb).unwrap();
        assert_eq!(std::fs::read(&pa).unwrap(), std::fs::read(&pb).unwrap());
    }

    #[test]
    fn unicode_survives_the_dictionaries() {
        let d = TempDir::new("unicode");
        let mut strings = Strings::default();
        let mut rows: Vec<Row> = ["Caf\u{e9} de Flore", "\u{6771}\u{4eac}\u{99c5}", "\u{395}\u{3bb}\u{3bb}", "\u{1f3d4} Peak", "\u{cd}safj\u{f6}r\u{f0}ur"]
            .iter()
            .enumerate()
            .map(|(i, name)| Row {
                lat_e7: 37_0000000 + i as i32 * 1000,
                lon_e7: -122_0000000,
                kind: K_POI,
                ids: [
                    strings.dicts[D_NAME].intern(name),
                    strings.dicts[D_HOUSE].intern(""),
                    strings.dicts[D_STREET].intern(""),
                    strings.dicts[D_CITY].intern("Somewhere"),
                    strings.dicts[D_STATE].intern(""),
                    strings.dicts[D_COUNTRY].intern("US"),
                    strings.dicts[D_POSTCODE].intern(""),
                ],
            })
            .collect();
        let out = d.0.join("u.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        // The names must survive as bytes, not merely as ids.
        let buf = std::fs::read(&out).unwrap();
        let size = u32::from_be_bytes(buf[12..16].try_into().unwrap()) as usize;
        let names = decode_dict(&buf[16..16 + size]).unwrap();
        assert!(names.iter().any(|s| s == "\u{6771}\u{4eac}\u{99c5}"), "{names:?}");
        assert!(names.iter().any(|s| s == "\u{1f3d4} Peak"), "{names:?}");
    }

    #[test]
    fn the_name_index_covers_named_records_and_excludes_the_rest() {
        let d = TempDir::new("nameidx");
        let (mut rows, mut strings) = sample_rows(500);
        let named = rows.iter().filter(|r| r.kind != K_ADDRESS).count();
        let out = d.0.join("n.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        verify(&out, &rows).unwrap();

        let buf = std::fs::read(&out).unwrap();
        let mut cursor = 12usize;
        let mut sections = Vec::new();
        for _ in 0..SECTIONS {
            let size = u32::from_be_bytes(buf[cursor..cursor + 4].try_into().unwrap()) as usize;
            cursor += 4;
            sections.push(&buf[cursor..cursor + size]);
            cursor += size;
        }
        let nm_rec = decode_column(sections[DICTS + COLUMNS + 3], false).unwrap();
        assert_eq!(nm_rec.len(), named, "every named record must be indexed, and only those");
    }

    #[test]
    fn a_single_record_database_is_valid() {
        let d = TempDir::new("one");
        let (mut rows, mut strings) = sample_rows(1);
        let out = d.0.join("one.geodb");
        let report = write(&out, &mut rows, &mut strings).unwrap();
        assert_eq!(report.n, 1);
        verify(&out, &rows).unwrap();
    }

    #[test]
    fn the_verifier_rejects_a_database_that_does_not_match() {
        let d = TempDir::new("mismatch");
        let (mut rows, mut strings) = sample_rows(100);
        let out = d.0.join("m.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();

        let mut wrong = rows.clone();
        wrong[7].ids[D_NAME] = wrong[7].ids[D_NAME].wrapping_add(1);
        assert!(verify(&out, &wrong).is_err(), "a changed name id must be caught");

        let mut short = rows.clone();
        let _ = short.pop();
        assert!(verify(&out, &short).is_err(), "a record count change must be caught");
    }

    #[test]
    fn trailing_bytes_are_rejected() {
        let d = TempDir::new("trailing");
        let (mut rows, mut strings) = sample_rows(50);
        let out = d.0.join("t.geodb");
        let _ = write(&out, &mut rows, &mut strings).unwrap();
        let mut buf = std::fs::read(&out).unwrap();
        buf.push(0);
        std::fs::write(&out, &buf).unwrap();
        assert!(verify(&out, &rows).is_err());
    }
}
