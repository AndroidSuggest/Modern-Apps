/// The shared-section summary: the 4-tuple `(rows, strings, pools, id_runs)` from the section
/// header, then one line per pool with its decoded count.
///
/// The section is archive-global, so `--tile`/`--layer` do not filter it. An archive built
/// without `--shared-table` carries no section and prints `shared\tabsent`.
pub fn shared_section_text(bytes: &[u8]) -> String {
    let Some(view) = find_shared(bytes) else {
        return "shared\tabsent\n".to_string();
    };
    let mut out = format!(
        "shared\trows={}\tstrings={}\tpools={}\tid_runs={}\n",
        view.header.row_count,
        view.header.string_count,
        view.header.pool_count,
        view.header.id_run_count,
    );
    for (kind, count) in [
        ("strings", view.strings.names.len()),
        ("rows", view.rows.len()),
        ("buildings", view.buildings.len()),
        ("carriageways", view.carriageways.len()),
        ("lane_turns", view.lane_turns.len()),
        ("id_runs", view.ids.len()),
        ("slim_refs", view.slim_refs.len()),
        ("geometries", view.geometries.len()),
        (
            "geom_points",
            view.geometries.iter().map(|g| g.points.len()).sum::<usize>(),
        ),
        (
            "geom_masks",
            view.geometries.iter().map(|g| g.masks.len()).sum::<usize>(),
        ),
    ] {
        out.push_str(&format!("pool\t{kind}\tcount={count}\n"));
    }
    out
}

/// The first byte range that parses as a shared section, scanning for `MBSH`.
///
/// The section is not header-indexed yet (that publication is lane B's wire), so the dump locates
/// it the way lane D lays it out: trailing bytes after the tile data, found by magic and
/// validated by parsing. A false-positive magic inside a compressed body fails
/// `SharedView::parse` and the scan moves on.
fn find_shared(bytes: &[u8]) -> Option<tile_build::mamaps::shared::SharedView> {
    use tile_build::mamaps::shared::{SHARED_MAGIC, SharedView};
    let mut at = 0usize;
    while at < bytes.len() {
        let found = bytes[at..].windows(SHARED_MAGIC.len()).position(|w| w == SHARED_MAGIC)?;
        at += found;
        if let Ok(view) = SharedView::parse(&bytes[at..]) {
            return Some(view);
        }
        at += 1;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Lane E: `--mode shared` prints the section 4-tuple. Built from a real encoded section so
    /// the magic scan and the parse are both exercised; the absent case pins the flag-off answer.
    /// Padded with noise either side, so the scan has to find the magic rather than assume an
    /// offset.
    #[test]
    fn shared_mode_prints_rows_strings_pools_and_id_runs() {
        use tile_build::mamaps::shared::{SharedBuilder, SharedLogicalRow, SharedSlimRef};
        let mut builder = SharedBuilder::new();
        builder.push_row(
            SharedLogicalRow {
                logical_id: 7,
                name_ref: builder.intern_string("Main St"),
                kind: 45,
                kind_detail: 0,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 0,
                lane_turns_idx: 0,
                flags: 0,
            },
            100,
        );
        builder.push_row(
            SharedLogicalRow {
                logical_id: 9,
                name_ref: 0,
                kind: 0,
                kind_detail: 0,
                view_bits: 0,
                building_idx: 0,
                carriageway_idx: 0,
                lane_turns_idx: 0,
                flags: 0,
            },
            tile_build::mamaps::body::ID_NONE,
        );
        builder.push_slim_ref(SharedSlimRef { logical_id: 7, view_bits: 0 });
        let section = builder.serialize().expect("section");
        let mut bytes = b"NOISE".to_vec();
        bytes.extend_from_slice(&section);
        bytes.extend_from_slice(b"MORE");
        let text = shared_section_text(&bytes);
        let mut lines = text.lines();
        assert_eq!(
            lines.next(),
            Some("shared\trows=2\tstrings=1\tpools=7\tid_runs=2"),
            "the 4-tuple: {text}",
        );
        // One line per pool, in kind order, with decoded counts.
        assert_eq!(lines.next(), Some("pool\tstrings\tcount=1"));
        assert_eq!(lines.next(), Some("pool\trows\tcount=2"));
        assert_eq!(lines.next(), Some("pool\tbuildings\tcount=1"), "the seeded default");
        assert_eq!(lines.next(), Some("pool\tcarriageways\tcount=1"), "the seeded default");
        assert_eq!(lines.next(), Some("pool\tlane_turns\tcount=1"), "the seeded empty");
        assert_eq!(lines.next(), Some("pool\tid_runs\tcount=2"));
        assert_eq!(lines.next(), Some("pool\tslim_refs\tcount=1"));
        assert_eq!(lines.next(), None, "no trailing lines");
        // No magic anywhere: the flag-off answer.
        assert_eq!(shared_section_text(b"no magic here"), "shared\tabsent\n");
        // A magic that parses as nothing: skipped, still absent.
        assert_eq!(shared_section_text(b"xxMBSHxx"), "shared\tabsent\n");
    }
}
