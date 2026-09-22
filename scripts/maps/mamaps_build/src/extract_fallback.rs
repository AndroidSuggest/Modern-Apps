//! Build-time containment fallback for place-label -> boundary links.
//!
//! Tapping a place label should outline its exact admin boundary. The authoritative half of that
//! link is membership: a boundary relation points at its own label node via an `admin_centre` or
//! `label` member, and stage A records that node -> relation pair into `region_links`
//! (`extract_part1.rs`). This module is the second half: for every place label that membership did
//! not link, pick the boundary that contains its centroid.
//!
//! # Matching order: members first, containment second
//!
//! The member map is never overwritten here. Fallback only inserts entries for place tagged-IDs
//! still missing from `region_links`, keyed the same way the tiler looks them up (the place's own
//! tagged node/way/relation id), so the two halves agree and `tiler_part5.rs` needs no change.
//!
//! # Where this runs
//!
//! Called from `main_part1.rs` between `extract()` returning and `tiler::Settings` construction,
//! with the finished [`Store`](crate::store::Store) and the member-built map. The store holds
//! whole lon/lat polygons (pre-tiler, pre-clip), which is the only place a containment test is
//! meaningful — at tiler encode time the geometry is tile-local clipped fragments. Two sequential
//! reads of the spill: one to collect boundary shapes, one to stream places and link them. No new
//! spills, no resident feature table.
//!
//! # Level bands
//!
//! A place kind only matches the admin levels it can actually name:
//!
//! | place kind | admin levels |
//! |---|---|
//! | `country` | 1–2 |
//! | `region` | 3–4 |
//! | `locality` | 7–12 |
//!
//! Levels 5–6 (county) intentionally match nothing. Those boundaries only link via relation
//! members; anything else masks nothing rather than the wrong county. `neighbourhood` and
//! `macrohood` places likewise match nothing — a suburb centroid inside a city boundary must not
//! outline the city.
//!
//! # `REGION_NONE` rules: when in doubt, mask nothing
//!
//! A missing link leaves the tiler's `REGION_NONE` path (mask nothing), so every uncertain case
//! emits nothing: zero in-band candidates, an ambiguous pick (two containers' areas within ~1% of
//! each other), or the place sitting on a ring edge (where ray-casting is a coin flip). Ambiguity
//! favors no-mask over wrong-mask.
//!
//! # Cost cap
//!
//! Boundaries are partitioned by band (a place only ever tests its own band) behind a coarse
//! 4-degree grid, with a bbox reject before any point-in-polygon test, in a single pass per read.
//! Per-place work is then a handful of candidates, not the whole boundary set. If profiling ever
//! shows this dominating, the documented escape hatch is a rank-based skip (only attempt fallback
//! for places above a population-rank floor) — not yet implemented, and the per-kind counts in
//! [`FallbackStats`] are the numbers that would size it.

use std::collections::HashMap;

use osm_ingest::proto::Result;
use osm_ingest::rings::{point_in_ring, ring_area, Polygon};
use tile_build::geom::Geometry;
use tilecodec::mamaps::body::ID_NONE;
use tilecodec::mamaps::dict::{KINDS, LAYER_BOUNDARIES, LAYER_PLACES};

/// What a run of the fallback did, for the build report.
#[derive(Debug, Default, Clone)]
pub struct FallbackStats {
    /// Linkable `places` features seen (tagged id, non-empty point, known kind).
    pub places_total: u64,
    /// Of those, already linked by relation members before fallback ran.
    pub member_linked: u64,
    /// Of those, newly linked by containment here.
    pub fallback_linked: u64,
    /// Of those, still unlinked after both halves (`total - member - fallback`).
    pub fallback_missed: u64,
    /// `(kind, total, linked)` per place kind, sorted by kind. `linked` counts both halves;
    /// the ratio is the per-kind hit rate the build log prints.
    pub per_kind: Vec<(String, u64, u64)>,
}

/// Which admin-level band a place or a boundary belongs to.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Band {
    Country,
    Region,
    Locality,
}

/// The admin levels a place kind may link to. `None` means the kind never links by containment
/// (county levels and sub-locality places — see the module docs).
fn band_for_place_kind(kind: &str) -> Option<Band> {
    match kind {
        "country" => Some(Band::Country),
        "region" => Some(Band::Region),
        "locality" => Some(Band::Locality),
        _ => None,
    }
}

/// The band an admin level belongs to. Levels 5–6 deliberately belong to no band.
fn band_for_level(level: u16) -> Option<Band> {
    match level {
        1..=2 => Some(Band::Country),
        3..=4 => Some(Band::Region),
        7..=12 => Some(Band::Locality),
        _ => None,
    }
}

/// A boundary candidate: one `region_area` shape with everything the selector needs precomputed.
struct Boundary {
    /// Tagged relation id, the link value the tiler stamps.
    id: u64,
    /// `admin_level` from the region shape's class (`kind_detail`).
    level: u16,
    /// Sum of `|ring_area|` over the shape's exterior rings — the same metric `RegionMesh`
    /// compares on device (a consistent planar measure, not ground area; only ever compared).
    area: f64,
    /// `(min_lon, min_lat, max_lon, max_lat)` over the shape, for the prefilter. `None` when the
    /// shape holds no vertices at all.
    bbox: Option<(f64, f64, f64, f64)>,
    /// Assembled polygons, exterior first then holes, lon/lat.
    polygons: Vec<Polygon>,
}

impl Boundary {
    fn new(id: u64, level: u16, polygons: Vec<Polygon>) -> Boundary {
        let area: f64 =
            polygons.iter().filter_map(|poly| poly.first()).map(|ext| ring_area(ext).abs()).sum();
        let mut bbox: Option<(f64, f64, f64, f64)> = None;
        for ring in polygons.iter().flatten() {
            for &(lon, lat) in ring {
                bbox = Some(match bbox {
                    None => (lon, lat, lon, lat),
                    Some((x0, y0, x1, y1)) => {
                        (x0.min(lon), y0.min(lat), x1.max(lon), y1.max(lat))
                    }
                });
            }
        }
        Boundary { id, level, area, bbox, polygons }
    }
}

/// A place kind name from its interned `Class.kind` (1-based into [`KINDS`]).
fn place_kind_name(kind: u16) -> Option<&'static str> {
    if kind == 0 {
        return None;
    }
    KINDS.get(kind as usize - 1).copied()
}

/// Grid cells are 4 degrees square: 90 over lon, 45 over lat.
const GRID_NX: usize = 90;
const GRID_NY: usize = 45;
/// A boundary covering more cells than this goes on the overflow list, tested for every place.
/// 4-degree cells make even Russia (~250 cells) indexable; this only catches degenerate bboxes.
const MAX_CELLS_PER_BOUNDARY: usize = 1024;

/// Boundaries partitioned spatially so one place tests a cellful, not the planet.
struct Grid {
    cells: Vec<Vec<usize>>,
    overflow: Vec<usize>,
}

fn cell_of(lon: f64, lat: f64) -> usize {
    let ix = (((lon + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1) as usize;
    let iy = (((lat + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1) as usize;
    iy * GRID_NX + ix
}

impl Grid {
    fn build(boundaries: &[Boundary]) -> Grid {
        let mut grid = Grid { cells: vec![Vec::new(); GRID_NX * GRID_NY], overflow: Vec::new() };
        for (i, b) in boundaries.iter().enumerate() {
            let Some((x0, y0, x1, y1)) = b.bbox else {
                continue;
            };
            let (cx0, cy0) = (
                (((x0 + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1),
                (((y0 + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1),
            );
            let (cx1, cy1) = (
                (((x1 + 180.0) / 4.0).floor() as i32).clamp(0, GRID_NX as i32 - 1),
                (((y1 + 90.0) / 4.0).floor() as i32).clamp(0, GRID_NY as i32 - 1),
            );
            let count = (cx1 - cx0 + 1) as usize * (cy1 - cy0 + 1) as usize;
            if count > MAX_CELLS_PER_BOUNDARY {
                grid.overflow.push(i);
                continue;
            }
            for cy in cy0..=cy1 {
                for cx in cx0..=cx1 {
                    grid.cells[cy as usize * GRID_NX + cx as usize].push(i);
                }
            }
        }
        grid
    }

    /// Boundary indices that may contain `point`: its cell plus the overflow list.
    fn candidates_for(&self, point: (f64, f64)) -> impl Iterator<Item = usize> + '_ {
        self.cells[cell_of(point.0, point.1)]
            .iter()
            .copied()
            .chain(self.overflow.iter().copied())
    }
}

/// Is `point` inside `polygons`? Inside some exterior and in none of its holes.
fn point_in_boundary(point: (f64, f64), polygons: &[Polygon]) -> bool {
    polygons.iter().any(|poly| {
        let Some((exterior, holes)) = poly.split_first() else {
            return false;
        };
        point_in_ring(point, exterior) && !holes.iter().any(|hole| point_in_ring(point, hole))
    })
}

/// Point-to-segment distance at or below one e7 grid step (~1 cm) counts as on the edge.
///
/// e7-quantised coordinates snap to a 1e-7 grid, so anything closer than that is
/// indistinguishable from exactly on the line — and ray-casting there is a coin flip.
fn point_on_segment(point: (f64, f64), a: (f64, f64), b: (f64, f64)) -> bool {
    const EPS: f64 = 1e-7;
    let (px, py) = point;
    let dx = b.0 - a.0;
    let dy = b.1 - a.1;
    let len2 = dx * dx + dy * dy;
    if len2 == 0.0 {
        return (px - a.0).abs() <= EPS && (py - a.1).abs() <= EPS;
    }
    // Perpendicular distance, scale-aware: |cross| / |segment|.
    let cross = dx * (py - a.1) - dy * (px - a.0);
    if (cross / len2.sqrt()).abs() > EPS {
        return false;
    }
    px >= a.0.min(b.0) - EPS
        && px <= a.0.max(b.0) + EPS
        && py >= a.1.min(b.1) - EPS
        && py <= a.1.max(b.1) + EPS
}

/// Is `point` on any ring edge of `polygons`? Rings are explicitly closed, but the wrap edge is
/// checked too so an unclosed ring cannot hide one.
fn point_on_boundary_edge(point: (f64, f64), polygons: &[Polygon]) -> bool {
    polygons.iter().flat_map(|poly| poly.iter()).any(|ring| {
        if ring.len() < 2 {
            return false;
        }
        (0..ring.len()).any(|i| point_on_segment(point, ring[i], ring[(i + 1) % ring.len()]))
    })
}

/// Pick the link for one place: smallest in-band container wins, ties and edges link nothing.
fn select_link(point: (f64, f64), band: Band, candidates: &[&Boundary]) -> Option<u64> {
    // In-band, bbox-passing candidates only — the bbox is what keeps this off the PIP path.
    let mut passing: Vec<&Boundary> = Vec::new();
    for b in candidates {
        if band_for_level(b.level) != Some(band) {
            continue;
        }
        let Some((x0, y0, x1, y1)) = b.bbox else {
            continue;
        };
        if point.0 < x0 || point.0 > x1 || point.1 < y0 || point.1 > y1 {
            continue;
        }
        passing.push(*b);
    }
    // On any in-band edge the ray cast cannot be trusted — mask nothing.
    if passing.iter().any(|b| point_on_boundary_edge(point, &b.polygons)) {
        return None;
    }
    let mut best: Option<&Boundary> = None;
    let mut second = f64::INFINITY;
    for b in passing {
        if b.area <= 0.0 || !point_in_boundary(point, &b.polygons) {
            continue;
        }
        match best {
            None => best = Some(b),
            Some(cur) if b.area < cur.area => {
                second = cur.area;
                best = Some(b);
            }
            Some(_) => {
                second = second.min(b.area);
            }
        }
    }
    let best = best?;
    // Two containers within ~1% of each other is a coin flip (overlapping claims, duplicate
    // relations) — mask nothing rather than the wrong one.
    if second.is_finite() && (second - best.area) / best.area < 0.01 {
        return None;
    }
    Some(best.id)
}

/// Extend `region_links` with containment fallback links for member-less place labels.
///
/// Reads the finished store twice (boundaries, then places), inserts only keys still missing so
/// member links always win, and returns the counts the build log reports. The tiler reads the
/// extended map unchanged.
pub fn extend_region_links(
    store: &crate::store::Store,
    region_links: &mut HashMap<u64, u64>,
) -> Result<FallbackStats> {
    // Pass 1: boundary shapes. Only `region_area` areas (the mask's own shapes, carrying the
    // admin level in `kind_detail`); border lines enclose nothing.
    let mut boundaries: Vec<Boundary> = Vec::new();
    let mut reader = store.reader()?;
    while let Some(feature) = reader.next()? {
        if feature.class.layer != LAYER_BOUNDARIES || !feature.class.area {
            continue;
        }
        if feature.id == ID_NONE {
            continue;
        }
        let Geometry::Polygons(polygons) = feature.geometry else {
            continue;
        };
        if polygons.is_empty() {
            continue;
        }
        boundaries.push(Boundary::new(feature.id, feature.class.kind_detail, polygons));
    }
    boundaries.retain(|b| b.bbox.is_some() && b.area > 0.0);
    let grid = Grid::build(&boundaries);

    // Pass 2: places. Positional, so node, way-centroided and relation-centroided labels all
    // link by the tagged id they already carry — the only path that can link non-node places,
    // since the member map is node-keyed.
    let mut stats = FallbackStats::default();
    let mut per_kind: HashMap<String, (u64, u64)> = HashMap::new();
    let mut buf: Vec<&Boundary> = Vec::new();
    let mut reader = store.reader()?;
    while let Some(feature) = reader.next()? {
        if feature.class.layer != LAYER_PLACES {
            continue;
        }
        if feature.id == ID_NONE {
            continue;
        }
        let Geometry::Points(points) = feature.geometry else {
            continue;
        };
        let Some(&point) = points.first() else {
            continue;
        };
        let Some(kind_name) = place_kind_name(feature.class.kind) else {
            continue;
        };
        stats.places_total += 1;
        let entry = per_kind.entry(kind_name.to_string()).or_insert((0, 0));
        entry.0 += 1;
        if region_links.contains_key(&feature.id) {
            stats.member_linked += 1;
            entry.1 += 1;
            continue;
        }
        let Some(band) = band_for_place_kind(kind_name) else {
            continue;
        };
        buf.clear();
        buf.extend(grid.candidates_for(point).map(|i| &boundaries[i]));
        if let Some(target) = select_link(point, band, &buf) {
            region_links.insert(feature.id, target);
            stats.fallback_linked += 1;
            entry.1 += 1;
        }
    }
    stats.fallback_missed =
        stats.places_total.saturating_sub(stats.member_linked + stats.fallback_linked);
    let mut kinds: Vec<(String, u64, u64)> = per_kind
        .into_iter()
        .map(|(kind, (total, linked))| (kind, total, linked))
        .collect();
    kinds.sort_by(|a, b| a.0.cmp(&b.0));
    stats.per_kind = kinds;
    Ok(stats)
}

include!("extract_fallback_part1.rs");
