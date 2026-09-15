//! Simplification, in two halves: annotate once, then filter per zoom.
//!
//! [`annotate`] runs Douglas-Peucker **once** over each unclipped source ring or
//! line and records, on every vertex, how much shape that vertex is responsible
//! for. [`filter`] then keeps a vertex when its significance clears the zoom's
//! threshold. This is geojson-vt's design, and it is here for one reason:
//!
//! ## Why not Douglas-Peucker per tile
//!
//! [`crate::clip::clip_polygon`] clips each ring of a polygon on its own, so a hole
//! that crossed a tile edge ends up sharing an edge **exactly** with the clipped
//! exterior along the tile boundary. Running Douglas-Peucker on each ring
//! afterwards anchors each at its own first and last vertex -- positions that have
//! nothing to do with each other between two rings -- so those two coincident
//! edges get thinned differently and move apart by up to a unit. The hole then
//! straddles or escapes its exterior, earcut cannot express that, and the renderer
//! has no choice but to drop the hole: every affected island and lake fills in
//! solid.
//!
//! Annotating first fixes it by construction. A vertex's significance is a property
//! of the vertex, not of the ring it is being simplified inside, so it is kept or
//! dropped consistently in every ring and every tile it appears in. Vertices the
//! clipper invents on the boundary carry [`crate::geom::ALWAYS`], so a clipped ring
//! is never re-simplified along the edge it was cut at.
//!
//! ## Why annotating once is enough
//!
//! [`crate::geom::project`] is a pure scale by `2^z`, so every perpendicular
//! distance at one zoom is the same constant multiple of the distance at another:
//! significance is scale-invariant, and the zoom only picks the threshold. That is
//! also why the annotation pass uses the tolerance at `max_zoom`, which
//! [`tolerance_for`] defines as zero -- full recursion, so every vertex receives
//! its true significance rather than only those above some floor.
//!
//! It is also cheaper than what it replaces: once per feature per zoom instead of
//! once per feature per **tile**.
//!
//! ## Rings have two extra rules
//!
//! * **Closure is preserved.** A closed ring is filtered without its explicit
//!   closing vertex and re-closed with a copy of whichever vertex survived first,
//!   so the two can never disagree.
//! * **A ring never drops below four vertices**, i.e. three distinct corners plus
//!   the repeated close. Fewer than that encloses no area, and an MVT polygon with
//!   a two-vertex ring is a renderer's problem rather than a small one. When
//!   filtering would go that far the **ring is dropped instead**, and if that ring
//!   was the exterior the whole polygon goes with it. Keeping a collapsed sliver
//!   would paint a hairline in the layer's fill colour, which is worse than the
//!   feature being absent at that zoom.
//!
//! ## The tolerance policy is ours, not tippecanoe's
//!
//! [`tolerance_for`] is a fixed number of tile-extent units, and it returns zero at
//! the maximum zoom so the deepest tiles keep full detail. Because a tile is always
//! `extent` units across whatever the zoom, a constant tolerance is already
//! zoom-relative: the same 1 unit covers roughly 1000x more ground at z6 than at
//! z16, which is exactly the behaviour a pyramid wants. This is a deliberate,
//! documented policy rather than a reproduction of `--simplification`, and the
//! tests assert the policy's own invariants -- never equality with tippecanoe.

use crate::geom::{Geometry, SigPt, Vertex, ALWAYS};

/// Simplification tolerance at [`crate::mvt::DEFAULT_EXTENT`], in extent units.
///
/// One unit is 1/4096 of a tile, which at z16 is under a metre. Below that,
/// detail is not representable in the tile anyway.
pub const DEFAULT_TOLERANCE: f64 = 1.0;

/// The least a ring may be reduced to: three distinct corners plus the close.
pub const MIN_RING_VERTICES: usize = 4;

/// The tolerance to use at zoom `z`, given the archive's maximum zoom and a
/// multiplier (1.0 for the default policy).
///
/// Zero at `max_zoom`: the deepest zoom is the one a user sees at full scale, and
/// it is also the only zoom whose geometry cannot be recovered from a deeper one.
pub fn tolerance_for(z: u8, max_zoom: u8, multiplier: f64) -> f64 {
    if z >= max_zoom {
        0.0
    } else {
        DEFAULT_TOLERANCE * multiplier
    }
}

/// Record every vertex's significance, in place, on an unclipped geometry.
///
/// Must run **before** the clip: the whole point is that a vertex is measured
/// against its neighbours in the source, not against wherever a tile boundary
/// happened to cut the ring.
///
/// Points are left alone. There is nothing in a point to remove, and thinning a
/// point layer is the drop policy's job.
pub fn annotate(g: &mut Geometry<SigPt>) {
    match g {
        Geometry::Points(_) => {}
        Geometry::Lines(lines) => lines.iter_mut().for_each(|l| annotate_path(l)),
        Geometry::Polygons(polys) => polys
            .iter_mut()
            .flatten()
            .for_each(|ring| annotate_path(ring)),
    }
}

/// Keep the vertices whose significance clears `tolerance`.
///
/// A pure filter: no vertex is measured, moved or invented here, which is what
/// makes two rings that share an edge agree along it.
pub fn filter(g: &Geometry<SigPt>, tolerance: f64) -> Geometry<SigPt> {
    if tolerance <= 0.0 {
        return g.clone();
    }
    let tol_sq = tolerance * tolerance;
    match g {
        Geometry::Points(_) => g.clone(),
        Geometry::Lines(lines) => Geometry::Lines(
            lines
                .iter()
                .map(|l| filter_line(l, tol_sq))
                .filter(|l| l.len() >= 2)
                .collect(),
        ),
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .filter_map(|rings| {
                    let mut out: Vec<Vec<SigPt>> = Vec::with_capacity(rings.len());
                    for (i, ring) in rings.iter().enumerate() {
                        match filter_ring(ring, tol_sq) {
                            Some(r) => out.push(r),
                            // The exterior collapsing takes the holes with it.
                            None if i == 0 => return None,
                            None => continue,
                        }
                    }
                    (!out.is_empty()).then_some(out)
                })
                .collect(),
        ),
    }
}

/// Filter an open polyline. Its endpoints carry [`ALWAYS`] -- either from
/// [`annotate`], or from the clipper having put them on a tile boundary -- so they
/// survive without a special case for them.
pub fn filter_line(line: &[SigPt], tol_sq: f64) -> Vec<SigPt> {
    line.iter().copied().filter(|v| v.sig > tol_sq).collect()
}

/// Filter a ring, or `None` when the result would enclose no area.
///
/// A closed ring (first == last) stays closed; an unclosed one stays unclosed, and
/// its floor is one lower since it has no repeated vertex.
pub fn filter_ring(ring: &[SigPt], tol_sq: f64) -> Option<Vec<SigPt>> {
    let closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let floor = if closed { MIN_RING_VERTICES } else { MIN_RING_VERTICES - 1 };
    if ring.len() < floor {
        return None;
    }
    // The closing vertex is not filtered on its own: re-closing with a copy of the
    // surviving first vertex is what makes it impossible to open the ring.
    let open = &ring[..ring.len() - usize::from(closed)];
    let mut out: Vec<SigPt> = open.iter().copied().filter(|v| v.sig > tol_sq).collect();
    if out.len() + usize::from(closed) < floor {
        return None;
    }
    if closed {
        out.push(out[0]);
    }
    Some(out)
}

/// Run Douglas-Peucker to exhaustion over one path, writing each vertex's
/// significance as it goes.
///
/// The path's own two ends are [`ALWAYS`]: a line must keep its endpoints, and for
/// a closed ring the two ends are the same place, so anchoring both is what keeps
/// the ring closed.
pub fn annotate_path(pts: &mut [SigPt]) {
    let Some(last) = pts.len().checked_sub(1) else { return };
    pts[0].sig = ALWAYS;
    pts[last].sig = ALWAYS;
    if pts.len() < 3 {
        return;
    }
    mark(pts, 0, last);
}

/// Measure every vertex between two anchors and recurse into both halves.
///
/// Iterative rather than recursive: a coastline ring can be hundreds of thousands
/// of vertices, and the recursion depth is only bounded by the data.
///
/// There is no tolerance here -- recursion continues while any vertex is off the
/// chord at all, which is [`tolerance_for`] at `max_zoom` and is what gives every
/// vertex its true significance rather than a lower bound on it. A vertex that is
/// exactly on every chord it is ever measured against keeps significance zero and
/// is dropped by any non-zero threshold, which is correct: it carries no shape.
fn mark(pts: &mut [SigPt], first: usize, last: usize) {
    let mut stack = vec![(first, last)];
    while let Some((a, b)) = stack.pop() {
        if b <= a + 1 {
            continue;
        }
        let mut worst = 0.0f64;
        let mut worst_i = a;
        for i in (a + 1)..b {
            let d = seg_dist_sq(pts[i], pts[a], pts[b]);
            if d > worst {
                worst = d;
                worst_i = i;
            }
        }
        if worst > 0.0 {
            pts[worst_i].sig = worst;
            stack.push((a, worst_i));
            stack.push((worst_i, b));
        }
    }
}

/// Squared distance from `p` to the **segment** `a -> b`.
///
/// The segment, not the infinite line it lies on. The two agree wherever the foot
/// of the perpendicular is between the endpoints, which is the only case an open
/// polyline's first pass produces -- but a closed ring's first chord is degenerate
/// (`a == b`), and past that the chords are arbitrary, so a vertex beyond an
/// endpoint would otherwise be scored by a perpendicular that misses the segment
/// entirely. This is geojson-vt's `getSqSegDist`.
pub fn seg_dist_sq(p: SigPt, a: SigPt, b: SigPt) -> f64 {
    let (mut x, mut y) = (a.x, a.y);
    let (dx, dy) = (b.x - x, b.y - y);
    let len_sq = dx * dx + dy * dy;
    if len_sq > 0.0 {
        let t = ((p.x - x) * dx + (p.y - y) * dy) / len_sq;
        if t > 1.0 {
            x = b.x;
            y = b.y;
        } else if t > 0.0 {
            x += dx * t;
            y += dy * t;
        }
    }
    let (dx, dy) = (p.x - x, p.y - y);
    dx * dx + dy * dy
}
