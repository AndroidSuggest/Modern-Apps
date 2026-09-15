//! Geometry in tile space: projection, bounds, tile ranges and quantisation.
//!
//! This is the front of the tiling pipeline. Each stage is a separate function so
//! each can be tested on its own, and they compose in one fixed order:
//!
//! ```text
//! lon/lat  --project_geometry-->  world     (tile units x extent, per zoom)
//! world    --simplify::annotate->  world     (per-vertex significance, in place)
//! world    --clip::clip_geometry->  world   (against one tile's buffered rect)
//! world    --simplify::filter---->  world   (fewer vertices)
//! world    --translate----------->  tile    (origin at the tile's corner)
//! tile     --quantize------------>  integer tile coordinates
//! integer  --mvt::encode_*------->  command stream
//! ```
//!
//! ## Why "world x extent" and not fractional tile units
//!
//! Projection is the only expensive step, and a coastline crossing a thousand
//! tiles must not be projected a thousand times. So a geometry is projected
//! **once per zoom** into coordinates already scaled to the tile extent: tile
//! `(tx, ty)` then occupies `[tx*extent, (tx+1)*extent]`, and moving into it is a
//! subtraction. Clipping happens in that same space, against a rect built by
//! [`tile_rect`], so only the small clipped result is ever translated.
//!
//! ## Why the clip runs before the quantisation
//!
//! A clip introduces new vertices where an edge crosses the tile boundary, and
//! those crossings are not on the integer grid. Clipping in floats and rounding
//! afterwards puts the crossing within half a unit of the true intersection;
//! rounding first and clipping the integer polyline moves the whole edge before
//! the intersection is even computed, and the error compounds along a long edge.

use crate::mvt::DEFAULT_EXTENT;

/// A vertex. Longitude/latitude before projection, world or tile coordinates
/// after -- see each function for which space it works in.
pub type Pt = (f64, f64);

/// A vertex on the integer tile grid, ready to encode.
pub type IPt = (i32, i32);

/// A world-space vertex carrying how much shape it is responsible for.
///
/// `sig` is the squared distance the vertex deviates from the chord
/// [`crate::simplify::annotate`] measured it against, in the same world units as
/// `x` and `y`. It is the whole point of this type: significance is computed
/// **once** on the unclipped source ring and then compared against a per-zoom
/// threshold, so a vertex is kept or dropped identically in every ring and every
/// tile it appears in. Simplifying each clipped ring on its own is what let a
/// hole and the exterior it shared a tile-boundary edge with thin differently and
/// drift apart.
///
/// A named struct rather than a three-wide tuple so the compiler names every site
/// that has to think about the extra field.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct SigPt {
    pub x: f64,
    pub y: f64,
    pub sig: f64,
}

/// The significance of a vertex no threshold may remove: a path's own endpoints,
/// and every vertex the clipper invents on a tile boundary.
pub const ALWAYS: f64 = f64::INFINITY;

impl SigPt {
    /// A vertex whose significance is not yet known. [`crate::simplify::annotate`]
    /// overwrites this; a vertex it never chooses is genuinely worth nothing and
    /// zero is the right answer.
    pub fn new(x: f64, y: f64) -> SigPt {
        SigPt { x, y, sig: 0.0 }
    }
}

/// A vertex the geometry pipeline can carry: enough to be clipped, translated and
/// quantised without knowing whether it also carries significance.
///
/// [`Pt`] and [`SigPt`] both implement it, which is what lets [`clip`] and the
/// coordinate-only parts of this module be written once and tested on plain pairs.
///
/// [`clip`]: crate::clip
pub trait Vertex: Copy {
    fn xy(self) -> Pt;

    /// The same vertex moved to `to`, keeping everything else about it.
    fn moved(self, to: Pt) -> Self;

    /// A vertex invented at `at`, where an edge crosses a clip boundary.
    ///
    /// It is not in the source, so no annotation pass ever measured it, and
    /// nothing downstream may remove it: it is the only thing holding two rings
    /// that share a boundary edge to the same vertex set.
    fn boundary(at: Pt) -> Self;
}

impl Vertex for Pt {
    fn xy(self) -> Pt {
        self
    }
    fn moved(self, to: Pt) -> Pt {
        to
    }
    fn boundary(at: Pt) -> Pt {
        at
    }
}

impl Vertex for SigPt {
    fn xy(self) -> Pt {
        (self.x, self.y)
    }
    fn moved(self, (x, y): Pt) -> SigPt {
        SigPt { x, y, sig: self.sig }
    }
    fn boundary((x, y): Pt) -> SigPt {
        SigPt { x, y, sig: ALWAYS }
    }
}

/// Tile-boundary overspill, in extent units at [`DEFAULT_EXTENT`].
///
/// Geometry is clipped to the tile rect grown by this much, so a line crossing a
/// tile edge is drawn to slightly beyond it. Without it a renderer stroking a
/// 2 px-wide road would show a seam at every tile boundary, because the join and
/// the cap would have nothing on the far side to align with. 5 units at extent
/// 4096 is tippecanoe's default and what the published archives were built with.
pub const DEFAULT_BUFFER: f64 = 5.0;

/// [`DEFAULT_BUFFER`] scaled to an arbitrary extent, so the overspill stays the
/// same fraction of a tile.
pub fn buffer_for(extent: u32) -> f64 {
    DEFAULT_BUFFER * extent as f64 / DEFAULT_EXTENT as f64
}

/// A geometry, in whichever coordinate space the producing function documents.
///
/// The three variants are the multi- forms only: a single LineString is a
/// `Lines` of one, and a single Polygon a `Polygons` of one. Collapsing the
/// singular cases removes a whole layer of branching from the clipper and the
/// encoders, and MVT itself draws no distinction -- geometry type is per feature,
/// not per part.
///
/// `V` is the vertex type: [`Pt`] in lon/lat, which is what a source and a spill
/// record hold, and [`SigPt`] from [`project_geometry`] onwards, where the
/// pipeline needs each vertex's significance.
#[derive(Debug, Clone, PartialEq)]
pub enum Geometry<V = Pt> {
    Points(Vec<V>),
    Lines(Vec<Vec<V>>),
    /// Each polygon is `[exterior, hole, hole, ...]`. Ring orientation is *not*
    /// significant here; [`crate::mvt::encode_polygons`] derives it from the
    /// signed area rather than trusting the input.
    Polygons(Vec<Vec<Vec<V>>>),
}

/// The integer counterpart of [`Geometry`], always in one tile's coordinates.
#[derive(Debug, Clone, PartialEq)]
pub enum IntGeometry {
    Points(Vec<IPt>),
    Lines(Vec<Vec<IPt>>),
    Polygons(Vec<Vec<Vec<IPt>>>),
}

impl IntGeometry {
    pub fn is_empty(&self) -> bool {
        match self {
            IntGeometry::Points(p) => p.is_empty(),
            IntGeometry::Lines(l) => l.iter().all(|p| p.len() < 2),
            IntGeometry::Polygons(p) => p.iter().all(|rings| {
                rings.first().is_none_or(|r| r.len() < 3)
            }),
        }
    }
}

/// An axis-aligned box.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rect {
    pub min_x: f64,
    pub min_y: f64,
    pub max_x: f64,
    pub max_y: f64,
}

impl Rect {
    pub fn contains(&self, (x, y): Pt) -> bool {
        x >= self.min_x && x <= self.max_x && y >= self.min_y && y <= self.max_y
    }

    pub fn intersects(&self, other: &Rect) -> bool {
        self.min_x <= other.max_x
            && other.min_x <= self.max_x
            && self.min_y <= other.max_y
            && other.min_y <= self.max_y
    }
}

/// Web-Mercator project a lon/lat to fractional tile coordinates at `z`.
///
/// Latitude is clamped to the Mercator limit: the projection diverges at the
/// poles, and a feed with a `0,0`-style placeholder coordinate would otherwise
/// produce an infinity.
///
/// This is the single copy of the projection in the crate. Two copies that drift
/// apart would put a point layer and a line layer at subtly different places in
/// the same tile.
pub fn project(lon: f64, lat: f64, z: u8) -> Pt {
    let n = (1u64 << z) as f64;
    let lat = lat.clamp(-85.051_128_78, 85.051_128_78);
    let x = (lon.clamp(-180.0, 180.0) + 180.0) / 360.0 * n;
    let s = lat.to_radians().sin();
    let y = (0.5 - (((1.0 + s) / (1.0 - s)).ln()) / (4.0 * std::f64::consts::PI)) * n;
    (x, y)
}

/// [`project`], scaled so one tile spans `extent` units. This is "world"
/// coordinates: tile `(tx, ty)` occupies `[tx*extent, (tx+1)*extent]`.
pub fn project_scaled(lon: f64, lat: f64, z: u8, extent: u32) -> Pt {
    let (x, y) = project(lon, lat, z);
    (x * extent as f64, y * extent as f64)
}

/// Project a whole lon/lat geometry into world coordinates for one zoom.
///
/// The result's vertices carry no significance yet; [`crate::simplify::annotate`]
/// is what fills that in, and it is the next step in the pipeline.
pub fn project_geometry(g: &Geometry, z: u8, extent: u32) -> Geometry<SigPt> {
    let p = |&(lon, lat): &Pt| {
        let (x, y) = project_scaled(lon, lat, z, extent);
        SigPt::new(x, y)
    };
    match g {
        Geometry::Points(pts) => Geometry::Points(pts.iter().map(p).collect()),
        Geometry::Lines(lines) => {
            Geometry::Lines(lines.iter().map(|l| l.iter().map(p).collect()).collect())
        }
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| r.iter().map(p).collect()).collect())
                .collect(),
        ),
    }
}

/// The bounding box of every vertex, or `None` when there are none.
///
/// Non-finite vertices are skipped. They should not exist -- [`project`] clamps --
/// but a `NaN` reaching [`tile_range`] would silently produce an empty range,
/// and dropping it here at least keeps the rest of the geometry.
pub fn bounds<V: Vertex>(g: &Geometry<V>) -> Option<Rect> {
    let mut r: Option<Rect> = None;
    let mut add = |(x, y): Pt| {
        if !x.is_finite() || !y.is_finite() {
            return;
        }
        r = Some(match r {
            None => Rect { min_x: x, min_y: y, max_x: x, max_y: y },
            Some(b) => Rect {
                min_x: b.min_x.min(x),
                min_y: b.min_y.min(y),
                max_x: b.max_x.max(x),
                max_y: b.max_y.max(y),
            },
        });
    };
    match g {
        Geometry::Points(pts) => pts.iter().for_each(|p| add(p.xy())),
        Geometry::Lines(lines) => lines.iter().flatten().for_each(|p| add(p.xy())),
        Geometry::Polygons(polys) => polys.iter().flatten().flatten().for_each(|p| add(p.xy())),
    }
    r
}

/// An inclusive range of tile coordinates.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TileRange {
    pub x0: u64,
    pub y0: u64,
    pub x1: u64,
    pub y1: u64,
}

impl TileRange {
    /// Every `(x, y)` in the range, row-major, so a tiling pass is deterministic.
    pub fn iter(&self) -> impl Iterator<Item = (u64, u64)> + '_ {
        (self.y0..=self.y1).flat_map(move |y| (self.x0..=self.x1).map(move |x| (x, y)))
    }

    pub fn len(&self) -> u64 {
        (self.x1 - self.x0 + 1) * (self.y1 - self.y0 + 1)
    }

    pub fn is_empty(&self) -> bool {
        false
    }
}

/// Every tile a geometry can reach at zoom `z`, sorted and deduplicated.
///
/// **No longer the tiling path.** [`crate::subdivide`] replaced all three of this
/// function's callers, because finding the tiles and then clipping the whole feature
/// against each of them is `O(T · V)` however tight `T` is. What remains is its use as
/// that module's reference: a descent must not reach a tile this does not list, and the
/// two are compared over a corpus there. Kept for that, and for the safety property its
/// own tests below pin -- a conservative superset is a useful thing to have a name for.
///
/// The point of it over `tile_range(bounds(g))` is long thin diagonal features. A
/// bounding box is a terrible approximation of a route. Measured on one
/// transcontinental rail relation (40° of longitude) at z16:
///
/// | | tiles |
/// |---|---|
/// | its bounding box | 34,535,986 |
/// | tiles it actually crosses | 16,632 |
///
/// A 2077x difference, and each of those 34.5 million tiles ran a full clip and
/// simplify of the whole geometry. That is why `transit_lines` at planet scale took
/// hours.
///
/// Each SEGMENT's own box is used instead, and their union taken. A segment whose box
/// still spans many tiles is bisected until it does not, so the result does not depend
/// on the input's vertex density: the same path with only 2 vertices walks 16,626
/// tiles, within 0.1% of the 2000-vertex version. Without bisection a sparse line's
/// segment boxes are nearly as bad as the whole feature's.
///
/// It stays a conservative superset — a segment's box always contains the segment, and
/// bisecting preserves that — so no tile a feature reaches can be missed. A
/// line-walking algorithm would have to be exactly right to be safe; this only has to
/// be tight.
///
/// Polygons keep whole-ring boxes: a polygon legitimately covers its interior tiles,
/// and per-segment boxes would drop every tile strictly inside the ring.
pub fn tiles_touched<V: Vertex>(
    g: &Geometry<V>,
    z: u8,
    extent: u32,
    pad: f64,
    out: &mut Vec<(u64, u64)>,
) {
    out.clear();
    match g {
        // A point's box is a point; one range call each is already tight.
        Geometry::Points(pts) => {
            for p in pts {
                let (x, y) = p.xy();
                push_box(out, x, y, x, y, z, extent, pad);
            }
        }
        Geometry::Lines(lines) => {
            for line in lines {
                match line.as_slice() {
                    [] => {}
                    // A degenerate one-point "line" still occupies a tile.
                    [p] => {
                        let (x, y) = p.xy();
                        push_box(out, x, y, x, y, z, extent, pad)
                    }
                    _ => {
                        for seg in line.windows(2) {
                            push_segment(out, seg[0].xy(), seg[1].xy(), z, extent, pad, 0);
                        }
                    }
                }
            }
        }
        Geometry::Polygons(polys) => {
            for rings in polys {
                // The exterior ring's own box, which is the polygon's footprint --
                // interior tiles included, because the fill reaches them.
                let Some(ext) = rings.first() else { continue };
                let mut min = (f64::INFINITY, f64::INFINITY);
                let mut max = (f64::NEG_INFINITY, f64::NEG_INFINITY);
                for p in ext {
                    let (x, y) = p.xy();
                    if !x.is_finite() || !y.is_finite() {
                        continue;
                    }
                    min = (min.0.min(x), min.1.min(y));
                    max = (max.0.max(x), max.1.max(y));
                }
                push_box(out, min.0, min.1, max.0, max.1, z, extent, pad);
            }
        }
    }
    // A feature must appear in a tile's list exactly once: adjacent segments share
    // tiles, and a duplicate would encode the whole geometry twice into one tile.
    out.sort_unstable();
    out.dedup();
}

/// A segment box this wide or tall is worth bisecting rather than filling.
///
/// 2 means "no more than a 2x2 tile block", which is the smallest box a segment
/// crossing a tile corner can have — so bisection stops as soon as it is tight rather
/// than recursing forever on a segment that genuinely straddles a corner.
const MAX_SEGMENT_TILES: u64 = 2;

/// Recursion cap, so a pathological coordinate cannot spin. At depth 24 a segment has
/// been bisected into 16 million pieces; anything still too wide is a broken input and
/// filling its box is the safe answer.
const MAX_BISECT_DEPTH: u32 = 24;

fn push_segment(
    out: &mut Vec<(u64, u64)>,
    a: Pt,
    b: Pt,
    z: u8,
    extent: u32,
    pad: f64,
    depth: u32,
) {
    let (ax, ay) = a;
    let (bx, by) = b;
    if !ax.is_finite() || !ay.is_finite() || !bx.is_finite() || !by.is_finite() {
        return;
    }
    let rect = Rect {
        min_x: ax.min(bx),
        min_y: ay.min(by),
        max_x: ax.max(bx),
        max_y: ay.max(by),
    };
    let Some(r) = tile_range(&rect, z, extent, pad) else { return };
    let spans_many = (r.x1 - r.x0) >= MAX_SEGMENT_TILES || (r.y1 - r.y0) >= MAX_SEGMENT_TILES;
    if spans_many && depth < MAX_BISECT_DEPTH {
        // Bisecting at the midpoint keeps the superset property: the two halves
        // together cover exactly the same line.
        let mid = ((ax + bx) / 2.0, (ay + by) / 2.0);
        push_segment(out, a, mid, z, extent, pad, depth + 1);
        push_segment(out, mid, b, z, extent, pad, depth + 1);
        return;
    }
    out.extend(r.iter());
}

include!("geom_part1.rs");
