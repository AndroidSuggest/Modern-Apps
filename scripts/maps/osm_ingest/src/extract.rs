//! Vector-layer extraction: `.osm.pbf` -> one geojsonseq per layer.
//!
//! The `osmium tags-filter | osmium export | normalize_*.py` chain, done in one
//! pass over the PBF with no external tools. Each layer's schema lives in its own
//! module ([`crate::safety`] and so on); this module owns the PBF traversal, the
//! bbox filter, the deterministic ordering and the output file.
//!
//! ## Pass ordering is fixed, and reversed
//!
//! Layers that need way or relation geometry cannot be done in one pass, because
//! a PBF stores nodes before the ways that reference them. So the traversal runs
//! **relations, then ways, then nodes** -- the reverse of the file order:
//!
//! 1. **Relations** decide which ways matter (a boundary's member ways, a route's
//!    member ways).
//! 2. **Ways** decide which node coordinates matter -- both their own refs and the
//!    refs of the ways relations claimed.
//! 3. **Nodes** supply those coordinates, and any node-based features.
//!
//! Only the passes a layer actually needs are run. `safety` is node-based, so it
//! runs one. The first pass to run is given `blob_kinds = None`, which is what
//! makes [`crate::pbf::run_pass`] build the per-blob kind mask that later passes
//! use to skip whole blobs.
//!
//! When way and relation layers land here, their node coordinates must be stored
//! the way [`crate::poi_build`] does it -- a sorted `Vec` of the needed ids plus an
//! index-aligned coordinate array, looked up by `binary_search` -- and **not** the
//! way [`crate::graph_build`] does it. That module allocates a bitset sized from
//! `BITSET_SIZE = 20e9`, i.e. 2.5 GB keyed by raw node id, and peaks around 10 GB
//! on California. The sorted-`Vec` form costs 16 bytes per *needed* node, which
//! for a selected subset is orders of magnitude smaller.

use std::collections::HashMap;
use std::fs::File;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};

use crate::admin::{self, AdminTags};
use crate::bbox::{self, BBox};
use crate::geojson::{self, Coord, Feature, Geometry};
use crate::maxspeed::{self, MaxspeedTags};
use crate::nodeloc::{resolve_nodes, NodeLocations};
use crate::osm::{visit_block, Element, Member, MEMBER_WAY};
use crate::pbf::{self, KIND_NODES, KIND_RELATIONS, KIND_WAYS};
use crate::proto::{Error, Result};
use crate::rings::{self, MemberWay, RingStats};
use crate::roads::{self, RoadTags};
use crate::safety::{self, Kind, SafetyTags};
use crate::select::Select;
use crate::transit_lines::{self, TransitTags};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Layer {
    Safety,
    Maxspeed,
    Roads,
    TransitLines,
    AdminCity,
}

impl Layer {
    pub fn parse(s: &str) -> std::result::Result<Layer, String> {
        match s {
            "safety" => Ok(Layer::Safety),
            "maxspeed" => Ok(Layer::Maxspeed),
            "roads" => Ok(Layer::Roads),
            "transit_lines" => Ok(Layer::TransitLines),
            "admin_city" => Ok(Layer::AdminCity),
            other => Err(format!(
                "unknown --layer '{other}'; supported: {}",
                Layer::names().join(", ")
            )),
        }
    }

    pub fn names() -> Vec<&'static str> {
        vec!["safety", "maxspeed", "roads", "transit_lines", "admin_city"]
    }

    pub fn name(self) -> &'static str {
        match self {
            Layer::Safety => "safety",
            Layer::Maxspeed => "maxspeed",
            Layer::Roads => "roads",
            Layer::TransitLines => "transit_lines",
            Layer::AdminCity => "admin_city",
        }
    }
}

pub struct Options {
    pub layer: Layer,
    pub bbox: Option<BBox>,
}

#[derive(Debug, Default, PartialEq)]
pub struct Stats {
    pub features: usize,
    pub from_nodes: usize,
    pub from_ways: usize,
    pub from_relations: usize,
    /// Classified, but outside `--bbox`. Reported so an empty layer can be told
    /// apart from a badly placed box.
    pub outside_bbox: usize,
}

impl Stats {
    /// A layer assembled from two element kinds, reporting none of one of them.
    ///
    /// `transit_lines` is the case that matters: a rail corridor is covered by both
    /// a `railway=*` way and a `type=route` relation, and only the relation carries
    /// `colour`, `ref` and the route's `name`. A ways-only layer therefore looks
    /// full, has the right feature count, and renders every line in the app's grey
    /// `rail` fallback. It is not proof of a broken build -- a rural extract really
    /// can hold track that no PTv2 route covers -- so this is a warning, and the
    /// build scripts own the checks that can be certain.
    pub fn missing_half(&self, layer: Layer) -> Option<String> {
        if layer != Layer::TransitLines || self.from_relations > 0 || self.from_ways == 0 {
            return None;
        }
        Some(format!(
            "{} railway way(s) but 0 route relation(s). Only relations carry `colour`, \
             `ref` and the route `name`, so every line will render grey.",
            self.from_ways
        ))
    }
}

pub fn build(input: &Path, out: &Path, opts: &Options) -> Result<Stats> {
    // Fail before the passes rather than after them if the output is unwritable.
    if let Some(parent) = out.parent().filter(|p| !p.as_os_str().is_empty()) {
        std::fs::create_dir_all(parent)
            .map_err(|e| Error(format!("cannot create {}: {e}", parent.display())))?;
    }

    let blobs = pbf::scan_blobs(input)?;
    println!("Scanned {} data blob(s) in {}", blobs.len(), input.display());

    match opts.layer {
        Layer::Safety => build_safety(input, &blobs, out, opts),
        Layer::Maxspeed => build_maxspeed(input, &blobs, out, opts),
        Layer::Roads => build_roads(input, &blobs, out, opts),
        Layer::TransitLines => build_transit_lines(input, &blobs, out, opts),
        Layer::AdminCity => build_admin_city(input, &blobs, out, opts),
    }
}

// --- safety ---------------------------------------------------------------

/// One classified node, with the attributes it carries copied out of the block.
///
/// The pass borrows tag values from the `PrimitiveBlock` it is reading, which is
/// dropped when the blob is, so anything kept has to be owned.
struct SafetyRow {
    lat_e7: i32,
    lon_e7: i32,
    id: i64,
    kind: Kind,
    name: Option<String>,
    direction: Option<String>,
    operator: Option<String>,
    reference: Option<String>,
    surveillance_type: Option<String>,
}

#[derive(Default)]
struct SafetyPass {
    rows: Vec<SafetyRow>,
    outside_bbox: usize,
}

fn build_safety(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    // The `osmium tags-filter` expression this layer used, as a cheap screen: it
    // reads three tags to reject almost every node, instead of the ten the
    // classifier and the property builder would read between them. Tag lookup is
    // a linear scan, so that matters at planet scale.
    let select = Select::parse(&safety::FILTERS)?;
    let bbox = opts.bbox.as_ref();

    let (chunks, _) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_NODES,
        "Pass 1: nodes",
        SafetyPass::default,
        |state, block| safety_blob(state, block, &select, bbox),
    )?;

    let mut rows: Vec<SafetyRow> = Vec::new();
    let mut outside_bbox = 0usize;
    for chunk in chunks {
        rows.extend(chunk.rows);
        outside_bbox += chunk.outside_bbox;
    }

    // Deterministic order, so two runs of the same input are byte-identical and a
    // diff against the previous build shows only real changes. Position first
    // keeps features that are near each other near each other in the file, which
    // is what the tiler wants; the id only breaks ties.
    rows.sort_by_key(|r| (r.lat_e7, r.lon_e7, r.id));

    let mut writer = BufWriter::new(create(out)?);
    let mut line: Vec<u8> = Vec::new();
    for row in &rows {
        let tags = SafetyTags {
            name: row.name.as_deref(),
            direction: row.direction.as_deref(),
            operator: row.operator.as_deref(),
            reference: row.reference.as_deref(),
            surveillance_type: row.surveillance_type.as_deref(),
            ..Default::default()
        };
        let f: Feature = safety::feature(
            row.kind,
            &tags,
            row.lon_e7 as f64 * 1e-7,
            row.lat_e7 as f64 * 1e-7,
            row.id,
        );
        geojson::write_feature(&mut writer, &f, &mut line).map_err(io_err)?;
    }
    writer.flush().map_err(io_err)?;

    println!(
        "Wrote {} safety feature(s) to {}",
        rows.len(),
        out.display()
    );
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: rows.len(),
        from_nodes: rows.len(),
        from_ways: 0,
        from_relations: 0,
        outside_bbox,
    })
}

fn safety_blob(
    state: &mut SafetyPass,
    block: &pbf::PrimitiveBlock,
    select: &Select,
    bbox: Option<&BBox>,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_NODES, &mut kinds, &mut |el: Element| {
        if let Element::Node(n) = el {
            if n.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| n.tags.get_str(k)) {
                return Ok(());
            }
            let t = SafetyTags {
                highway: n.tags.get_str("highway"),
                man_made: n.tags.get_str("man_made"),
                enforcement: n.tags.get_str("enforcement"),
                surveillance_type: n.tags.get_str("surveillance:type"),
                camera_type: n.tags.get_str("camera:type"),
                operator: n.tags.get_str("operator"),
                manufacturer: n.tags.get_str("manufacturer"),
                name: n.tags.get_str("name"),
                direction: n.tags.get_str("direction"),
                reference: n.tags.get_str("ref"),
            };
            let Some(kind) = safety::classify(&t) else {
                return Ok(());
            };
            // Counted after classification, so the number means "safety features
            // outside the box" rather than "nodes outside the box".
            if !bbox::keep_e7(bbox, n.lat_e7, n.lon_e7) {
                state.outside_bbox += 1;
                return Ok(());
            }
            state.rows.push(SafetyRow {
                lat_e7: n.lat_e7,
                lon_e7: n.lon_e7,
                id: n.id,
                kind,
                name: t.name.map(str::to_string),
                direction: t.direction.map(str::to_string),
                operator: t.operator.map(str::to_string),
                reference: t.reference.map(str::to_string),
                surveillance_type: t.surveillance_type.map(str::to_string),
            });
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- maxspeed -------------------------------------------------------------

/// One way with a posted limit, with its tag values copied out of the block.
struct MaxspeedRow {
    id: i64,
    refs: Vec<i64>,
    maxspeed: String,
    highway: Option<String>,
    name: Option<String>,
}

fn build_maxspeed(
    input: &Path,
    blobs: &[pbf::BlobLoc],
    out: &Path,
    opts: &Options,
) -> Result<Stats> {
    let select = Select::parse(&maxspeed::FILTERS)?;

    // Pass 1 runs with `blob_kinds = None`, which is what makes run_pass build the
    // per-blob kind mask the node pass then uses to skip node-only blobs.
    let (chunks, blob_kinds) = pbf::run_pass(
        input,
        blobs,
        None,
        KIND_WAYS,
        "Pass 1: ways",
        Vec::<MaxspeedRow>::new,
        |state: &mut Vec<MaxspeedRow>, block| maxspeed_blob(state, block, &select),
    )?;
    let mut rows: Vec<MaxspeedRow> = Vec::new();
    for chunk in chunks {
        rows.extend(chunk);
    }
    println!("{} way(s) with a posted limit", rows.len());

    let table = NodeLocations::new(rows.iter().flat_map(|r| r.refs.iter().copied()).collect())?;
    println!("{} node location(s) needed", table.len());
    let table = resolve_nodes(input, blobs, &blob_kinds, "Pass 2: nodes", table)?;

    let mut lines: Vec<LineFeature> = Vec::with_capacity(rows.len());
    let mut outside_bbox = 0usize;
    for row in &rows {
        let coords = table.line(&row.refs);
        if coords.len() < 2 {
            continue;
        }
        if !parts_touch_bbox(std::slice::from_ref(&coords), opts.bbox.as_ref()) {
            outside_bbox += 1;
            continue;
        }
        let t = MaxspeedTags {
            maxspeed: Some(row.maxspeed.as_str()),
            highway: row.highway.as_deref(),
            name: row.name.as_deref(),
            ..Default::default()
        };
        let f = maxspeed::feature(&row.maxspeed, &t, Geometry::LineString(coords), row.id);
        lines.push(LineFeature { sort: ("way", row.id), rendered: render(&f) });
    }

    let written = write_lines(out, lines)?;
    println!("Wrote {} maxspeed feature(s) to {}", written, out.display());
    if outside_bbox > 0 {
        println!("{outside_bbox} feature(s) dropped by --bbox");
    }
    Ok(Stats {
        features: written,
        from_nodes: 0,
        from_ways: written,
        from_relations: 0,
        outside_bbox,
    })
}

fn maxspeed_blob(
    state: &mut Vec<MaxspeedRow>,
    block: &pbf::PrimitiveBlock,
    select: &Select,
) -> Result<u8> {
    let mut kinds = 0u8;
    visit_block(block, KIND_WAYS, &mut kinds, &mut |el: Element| {
        if let Element::Way(w) = el {
            if w.refs.len() < 2 || w.tags.is_empty() {
                return Ok(());
            }
            if !select.matches(|k| w.tags.get_str(k)) {
                return Ok(());
            }
            let t = MaxspeedTags {
                maxspeed: w.tags.get_str("maxspeed"),
                maxspeed_forward: w.tags.get_str("maxspeed:forward"),
                maxspeed_backward: w.tags.get_str("maxspeed:backward"),
                highway: w.tags.get_str("highway"),
                name: w.tags.get_str("name"),
            };
            if let Some(value) = maxspeed::extract(&t) {
                state.push(MaxspeedRow {
                    id: w.id,
                    refs: w.refs.to_vec(),
                    maxspeed: value.to_string(),
                    highway: t.highway.map(str::to_string),
                    name: t.name.map(str::to_string),
                });
            }
        }
        Ok(())
    })?;
    Ok(kinds)
}

// --- roads ----------------------------------------------------------------

/// One road way, with every tag value the layer emits copied out of the block.
///
/// The tag strings are owned because the pass borrows them from the
/// `PrimitiveBlock` it is reading, which is dropped with the blob. Thirteen
/// `Option<String>` per road is the one place this layer is heavier than
/// `maxspeed`, and since this is every road rather than only the ones with a posted
/// limit, `build_roads` consumes the rows as it renders them rather than holding
/// both the rows and the output at once.
struct RoadRow {
    id: i64,
    refs: Vec<i64>,
    class: u8,
    maxspeed: Option<String>,
    lanes: Option<String>,
    lanes_forward: Option<String>,
    lanes_backward: Option<String>,
    turn_lanes: Option<String>,
    turn_lanes_forward: Option<String>,
    turn_lanes_backward: Option<String>,
    oneway: Option<String>,
    width: Option<String>,
    bridge: Option<String>,
    tunnel: Option<String>,
    layer: Option<String>,
}

include!("extract_part1.rs");
include!("extract_part2.rs");
include!("extract_part3.rs");
include!("extract_part4.rs");