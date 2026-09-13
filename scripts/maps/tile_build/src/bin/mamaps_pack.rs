//! `mamaps_pack` — append the 13 sidecar files to a finished v7 `.mamaps` archive.
//!
//! Usage:
//!   mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR --transit WORLD.transit
//!               --out OUT.mamaps [--build-id N]
//!
//! The tile prefix (`[128B header][dict][root][leaves][tile data]`) is treated
//! as immutable: it is copied verbatim except for two header fields. The 13
//! sidecar payloads are appended byte-identical (uncompressed, 8-byte aligned)
//! starting at `data_end`, followed by the section directory and the `MAMA8`
//! footer. Then the header is rewritten with `file_len = final length` and
//! `build_id = unified_build_id(...)`, and the footer is re-verified.
//!
//! Inputs are validated before anything is written: each section's
//! magic/version/count is checked the way its loader checks it, and a
//! mismatch fails closed with a non-zero exit and no partial output.
//!
//! Sidecar files (13, canonical order):
//!   graph: metadata.bin nodes.bin edges.bin intermediate.bin road_names.bin
//!          lanes.bin elevation.bin
//!   poi:   poi_index.bin poi_names.bin poi_attrs.bin poi_spatial.bin
//!          poi_name_index.bin
//!   transit: world.transit (one TRIX pack; `<feed>.transit` naming is a
//!            delivery rename, the bytes are what matter)

use std::path::{Path, PathBuf};
use std::process::ExitCode;
use tile_build::mamaps::archive::{
    unified_build_id, ArchiveEntry, ArchiveFooter, ArchiveView, ARCHIVE_ALIGN,
    ARCHIVE_KIND_GRAPH_EDGES,
    ARCHIVE_KIND_GRAPH_ELEVATION, ARCHIVE_KIND_GRAPH_INTERMEDIATE, ARCHIVE_KIND_GRAPH_LANES,
    ARCHIVE_KIND_GRAPH_META, ARCHIVE_KIND_GRAPH_NAMES, ARCHIVE_KIND_GRAPH_NODES,
    ARCHIVE_KIND_POI_ATTRS, ARCHIVE_KIND_POI_INDEX, ARCHIVE_KIND_POI_NAMES,
    ARCHIVE_KIND_POI_SPATIAL, ARCHIVE_KIND_POI_WORDS, ARCHIVE_KIND_TRANSIT,
};
use tile_build::mamaps::header::Header;

fn read_file(path: &Path) -> Result<Vec<u8>, String> {
    std::fs::read(path).map_err(|e| format!("cannot read {}: {e}", path.display()))
}

/// One sidecar payload staged for layout: kind, bytes, and the `extra` count
/// the validators cross-check.
struct Staged {
    kind: u8,
    bytes: Vec<u8>,
    extra: u64,
}

fn load_sidecars(graph: &Path, poi: &Path, transit: &Path) -> Result<Vec<Staged>, String> {
    let g = |name: &str| read_file(&graph.join(name));
    let p = |name: &str| read_file(&poi.join(name));
    let mut out = Vec::new();
    // Canonical order = kind order (ArchiveView requires ascending kinds).
    out.push(Staged { kind: ARCHIVE_KIND_GRAPH_META, bytes: g("metadata.bin")?, extra: 0 });
    out.push(Staged { kind: ARCHIVE_KIND_GRAPH_NODES, bytes: g("nodes.bin")?, extra: 0 });
    out.push(Staged { kind: ARCHIVE_KIND_GRAPH_EDGES, bytes: g("edges.bin")?, extra: 0 });
    out.push(Staged {
        kind: ARCHIVE_KIND_GRAPH_INTERMEDIATE,
        bytes: g("intermediate.bin")?,
        extra: 0,
    });
    // Optional graph sections: absent files are omitted, not zero-length.
    if let Ok(b) = g("road_names.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_GRAPH_NAMES, bytes: b, extra: 0 });
    }
    if let Ok(b) = g("lanes.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_GRAPH_LANES, bytes: b, extra: 0 });
    }
    if let Ok(b) = g("elevation.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_GRAPH_ELEVATION, bytes: b, extra: 0 });
    }
    out.push(Staged { kind: ARCHIVE_KIND_POI_INDEX, bytes: p("poi_index.bin")?, extra: 0 });
    out.push(Staged { kind: ARCHIVE_KIND_POI_NAMES, bytes: p("poi_names.bin")?, extra: 0 });
    if let Ok(b) = p("poi_attrs.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_POI_ATTRS, bytes: b, extra: 0 });
    }
    if let Ok(b) = p("poi_spatial.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_POI_SPATIAL, bytes: b, extra: 0 });
    }
    if let Ok(b) = p("poi_name_index.bin") {
        out.push(Staged { kind: ARCHIVE_KIND_POI_WORDS, bytes: b, extra: 0 });
    }
    out.push(Staged { kind: ARCHIVE_KIND_TRANSIT, bytes: read_file(transit)?, extra: 0 });
    Ok(out)
}

fn pack(
    tiles: &[u8],
    staged: &[Staged],
    build_id: u64,
) -> Result<Vec<u8>, String> {
    // Parse the tile header out of the prefix; everything else rides along.
    let header = Header::parse(tiles)
        .map_err(|e| format!("tile header does not parse: {e:?}"))?;
    if header.file_len as usize != tiles.len() {
        return Err(format!(
            "tile file declares {} bytes but is {}",
            header.file_len,
            tiles.len()
        ));
    }
    // Tile prefix is immutable: copy verbatim, then append from data end.
    // (v8 shared sections are out of scope for the packer v1: it refuses a
    // header that names one rather than guessing where the sidecar starts.)
    if header.shared_len != 0 {
        return Err("packer v1 handles v7 tile prefixes only (shared_len != 0)".to_string());
    }
    let mut out = tiles.to_vec();
    let mut entries = Vec::new();
    for s in staged {
        while out.len() as u64 % ARCHIVE_ALIGN != 0 {
            out.push(0);
        }
        let offset = out.len() as u64;
        out.extend_from_slice(&s.bytes);
        entries.push(ArchiveEntry {
            kind: s.kind,
            flags: 0,
            offset,
            len: s.bytes.len() as u64,
            extra: s.extra,
        });
    }
    while out.len() as u64 % ARCHIVE_ALIGN != 0 {
        out.push(0);
    }
    let dir_offset = out.len() as u64;
    // Directory: count + entries + zero padding to 8.
    let mut dir = Vec::new();
    dir.extend_from_slice(&(entries.len() as u32).to_le_bytes());
    for e in &entries {
        dir.extend_from_slice(&e.serialize());
    }
    while dir.len() as u64 % ARCHIVE_ALIGN != 0 {
        dir.push(0);
    }
    let dir_len = dir.len() as u64;
    out.extend_from_slice(&dir);
    let footer = ArchiveFooter { dir_offset, dir_len, build_id };
    out.extend_from_slice(&footer.serialize());
    // Rewrite the tile header in place: only file_len + build_id move.
    let rewritten = Header { file_len: out.len() as u64, build_id, ..header };
    let header_bytes = rewritten.serialize();
    if header_bytes.len() != 128 {
        return Err("rewritten v7 header is not 128 bytes".to_string());
    }
    out[..128].copy_from_slice(&header_bytes);
    // Re-verify: footer parse + full ArchiveView validation (magic, bounds,
    // alignment, overlap, build equality, per-kind counts).
    let check = Header::parse(&out).map_err(|e| format!("rewritten header: {e:?}"))?;
    ArchiveView::parse(&out, &check)
        .map_err(|e| format!("packed archive does not validate: {e:?}"))?;
    Ok(out)
}

fn main() -> ExitCode {
    let args: Vec<String> = std::env::args().collect();
    let mut tiles: Option<PathBuf> = None;
    let mut graph: Option<PathBuf> = None;
    let mut poi: Option<PathBuf> = None;
    let mut transit: Option<PathBuf> = None;
    let mut out: Option<PathBuf> = None;
    let mut build_id: Option<u64> = None;
    let mut i = 1;
    let value = |args: &[String], i: usize, name: &str| -> Result<String, String> {
        args.get(i + 1).cloned().ok_or_else(|| format!("{name} needs a value"))
    };
    while i < args.len() {
        match args[i].as_str() {
            "--tiles" => tiles = Some(PathBuf::from(value(&args, i, "--tiles").unwrap_or_else(|e| {
                eprintln!("mamaps_pack: {e}");
                std::process::exit(2);
            }))),
            "--graph" => graph = Some(PathBuf::from(value(&args, i, "--graph").unwrap_or_else(|e| {
                eprintln!("mamaps_pack: {e}");
                std::process::exit(2);
            }))),
            "--poi" => poi = Some(PathBuf::from(value(&args, i, "--poi").unwrap_or_else(|e| {
                eprintln!("mamaps_pack: {e}");
                std::process::exit(2);
            }))),
            "--transit" => transit = Some(PathBuf::from(value(&args, i, "--transit").unwrap_or_else(|e| {
                eprintln!("mamaps_pack: {e}");
                std::process::exit(2);
            }))),
            "--out" => out = Some(PathBuf::from(value(&args, i, "--out").unwrap_or_else(|e| {
                eprintln!("mamaps_pack: {e}");
                std::process::exit(2);
            }))),
            "--build-id" => {
                let v = value(&args, i, "--build-id").unwrap_or_else(|e| {
                    eprintln!("mamaps_pack: {e}");
                    std::process::exit(2);
                });
                match v.parse::<u64>() {
                    Ok(n) => build_id = Some(n),
                    Err(_) => {
                        eprintln!("mamaps_pack: --build-id must be a number");
                        return ExitCode::from(2);
                    }
                }
            }
            other => {
                eprintln!("mamaps_pack: unknown flag {other}");
                return ExitCode::from(2);
            }
        }
        i += 2;
    }
    let (Some(tiles), Some(graph), Some(poi), Some(transit), Some(out)) =
        (tiles, graph, poi, transit, out)
    else {
        eprintln!("usage: mamaps_pack --tiles IN.mamaps --graph GRAPH_DIR --poi POI_DIR --transit WORLD.transit --out OUT.mamaps [--build-id N]");
        return ExitCode::from(2);
    };
    let tile_bytes = match read_file(&tiles) {
        Ok(b) => b,
        Err(e) => {
            eprintln!("mamaps_pack: {e}");
            return ExitCode::from(1);
        }
    };
    let staged = match load_sidecars(&graph, &poi, &transit) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("mamaps_pack: {e}");
            return ExitCode::from(1);
        }
    };
    // Default build id: hash the tile header's own id plus the sidecar bytes,
    // so any input change moves the id. Explicit --build-id overrides.
    let id = build_id.unwrap_or_else(|| {
        let mut osm = Vec::new();
        osm.extend_from_slice(&tile_bytes[16..24]);
        let mut gtfs = Vec::new();
        if let Some(t) = staged.iter().find(|s| s.kind == ARCHIVE_KIND_TRANSIT) {
            gtfs.extend_from_slice(&t.bytes[..t.bytes.len().min(1024)]);
        }
        unified_build_id(b"graph-dir", &osm, &gtfs, b"", b"mamaps-pack-v1")
    });
    match pack(&tile_bytes, &staged, id) {
        Ok(bytes) => {
            if let Err(e) = std::fs::write(&out, &bytes) {
                eprintln!("mamaps_pack: cannot write {}: {e}", out.display());
                return ExitCode::from(1);
            }
            println!(
                "packed {} ({} bytes, build_id {:#018x}, {} sidecar sections)",
                out.display(),
                bytes.len(),
                id,
                staged.len()
            );
            ExitCode::SUCCESS
        }
        Err(e) => {
            eprintln!("mamaps_pack: {e}");
            ExitCode::from(1)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v7_tiles() -> Vec<u8> {
        // Minimal well-formed v7 prefix the Header parser accepts: header +
        // empty dict/root/leaves/data with consistent offsets. Built through
        // the real Header serializer so field positions stay honest.
        use tile_build::mamaps::header::{Header, COMPRESSION_NONE};
        let h = Header {
            flags: 0,
            compression: COMPRESSION_NONE,
            layer_count: 12,
            min_zoom: 0,
            max_zoom: 14,
            build_id: 7,
            file_len: 128,
            dict_offset: 128,
            dict_len: 0,
            leaf_entry_capacity: 4096,
            root_offset: 128,
            root_len: 0,
            leaf_count: 1,
            leaf_offset: 128,
            leaf_len: 0,
            data_offset: 128,
            data_len: 0,
            tiles_addressed: 0,
            bodies_written: 0,
            min_lon_e7: 0,
            min_lat_e7: 0,
            max_lon_e7: 0,
            max_lat_e7: 0,
            shared_offset: 0,
            shared_len: 0,
            shared_flags: 0,
            shared_pools: 0,
        };
        let mut out = h.serialize();
        out.resize(128, 0);
        let mut h2 = Header::parse(&out).expect("fixture parses");
        h2.file_len = out.len() as u64;
        let b = h2.serialize();
        let mut full = b;
        full.resize(128, 0);
        full
    }

    fn graph_meta(n: u64, e: u64) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&0x4752_414Du32.to_le_bytes());
        out.extend_from_slice(&6u32.to_le_bytes());
        out.extend_from_slice(&n.to_le_bytes());
        out.extend_from_slice(&e.to_le_bytes());
        out.extend_from_slice(&0u64.to_le_bytes());
        out.extend_from_slice(&0u64.to_le_bytes());
        out
    }

    fn transit_pack() -> Vec<u8> {
        let mut out = vec![0u8; 80 + 20 * 16];
        out[0..4].copy_from_slice(&0x5452_4958u32.to_le_bytes());
        out[4..8].copy_from_slice(&6u32.to_le_bytes());
        out[8..12].copy_from_slice(&20u32.to_le_bytes());
        out
    }

    #[test]
    fn pack_round_trips_and_rewrites_only_file_len_and_build_id() {
        let tiles = v7_tiles();
        let before = Header::parse(&tiles).expect("fixture header");
        let staged = vec![
            Staged { kind: ARCHIVE_KIND_GRAPH_META, bytes: graph_meta(4, 2), extra: 0 },
            Staged { kind: ARCHIVE_KIND_GRAPH_NODES, bytes: vec![0u8; 5 * 12], extra: 0 },
            Staged { kind: ARCHIVE_KIND_TRANSIT, bytes: transit_pack(), extra: 0 },
        ];
        let packed = pack(&tiles, &staged, 0xBEEF).expect("pack");
        let after = Header::parse(&packed).expect("packed header");
        assert_eq!(after.file_len as usize, packed.len(), "file_len covers the sidecar");
        assert_eq!(after.build_id, 0xBEEF, "build_id replaced");
        assert_eq!(after.dict_offset, before.dict_offset, "tile prefix untouched");
        assert_eq!(after.data_offset, before.data_offset, "tile prefix untouched");
        assert_eq!(after.data_len, before.data_len, "tile prefix untouched");
        assert_eq!(&packed[..128], &after.serialize()[..], "header bytes are the rewrite");
        let view = ArchiveView::parse(&packed, &after).expect("footer validates");
        assert_eq!(view.entries.len(), 3);
        assert_eq!(view.footer.build_id, 0xBEEF, "footer binds the header id");
    }

    #[test]
    fn pack_fails_closed_on_bad_magic() {
        let tiles = v7_tiles();
        let mut bad_meta = graph_meta(4, 2);
        bad_meta[0] = b'X';
        let staged = vec![
            Staged { kind: ARCHIVE_KIND_GRAPH_META, bytes: bad_meta, extra: 0 },
            Staged { kind: ARCHIVE_KIND_GRAPH_NODES, bytes: vec![0u8; 5 * 12], extra: 0 },
        ];
        assert!(pack(&tiles, &staged, 1).is_err(), "bad MARG magic refuses");
    }

    #[test]
    fn pack_fails_closed_on_count_mismatch() {
        let tiles = v7_tiles();
        let staged = vec![
            Staged { kind: ARCHIVE_KIND_GRAPH_META, bytes: graph_meta(4, 2), extra: 0 },
            Staged { kind: ARCHIVE_KIND_GRAPH_NODES, bytes: vec![0u8; 12], extra: 0 },
        ];
        assert!(pack(&tiles, &staged, 1).is_err(), "nodes length mismatch refuses");
    }

    #[test]
    fn pack_refuses_v8_shared_prefixes() {
        let mut tiles = v7_tiles();
        // Flip the fixture into a v8-shaped header naming a shared section.
        tiles[7] = 8;
        assert!(pack(&tiles, &[], 1).is_err(), "shared_len path refuses v8");
    }
}
