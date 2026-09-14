//! Clipping geometry to a tile's buffered rect.
//!
//! Two different algorithms, because lines and polygons need different answers
//! from a clip:
//!
//! * **Lines use Liang-Barsky.** A polyline crossing a tile can leave and re-enter
//!   it, so one input line yields **N output lines** -- the clip is a partition,
//!   not a truncation. Joining the pieces back up would draw a road straight
//!   across a bay it actually goes around.
//! * **Polygons use Sutherland-Hodgman.** A polygon must stay a closed area after
//!   clipping, so the parts of the tile boundary that bound the clipped shape have
//!   to become real edges. Liang-Barsky would give a set of disconnected arcs with
//!   no interior.
//!
//! ## What Sutherland-Hodgman does and does not do
//!
//! It clips against each of the four half-planes in turn, and it is exact for a
//! convex polygon. For a **concave** polygon whose clipped result is two or more
//! disjoint pieces, it returns a single ring joined by a degenerate sliver running
//! along the boundary. That sliver has zero area, so a scanline rasteriser fills
//! the right pixels, but it is one ring where a strict result would be two, and
//! that is not free: earcut cannot triangulate a self-touching ring reliably (see
//! `library/map/src/main/rust/src/tess/fill.rs`), so the renderer is left doing
//! the repair. Splitting them requires a general polygon clipper (Vatti or
//! Greiner-Hormann), which is a large piece of work. Documented here rather than
//! pretended away.
//!
//! ## Clip-introduced vertices are never removed
//!
//! A crossing this module interpolates is not in the source, so no annotation pass
//! measured it: it comes out of [`crate::geom::Vertex::boundary`] with significance
//! [`crate::geom::ALWAYS`]. That is what holds an exterior and a hole that both
//! reach the same tile edge to the same vertices along it. Thinning those two
//! coincident edges independently is what used to push a hole out through its own
//! exterior.
//!
//! ## The `NaN` question
//!
//! Both algorithms compare and divide. A non-finite vertex would make every
//! comparison false and could emit a `NaN` intersection, which
//! [`crate::geom::quantize`] then turns into a vertex at the tile origin. So
//! non-finite vertices are dropped on the way in; [`crate::geom::project`] clamps,
//! so they should not arise, but the clipper is the last place that can still tell
//! the difference between "outside" and "unknown".

use crate::geom::{Geometry, Pt, Rect, Vertex};

/// Clip a geometry to `rect`, in the same coordinate space as the input.
///
/// Points are kept or dropped whole; lines are partitioned; polygon rings are
/// clipped into new rings. Parts that vanish entirely are removed, so an empty
/// result means the geometry does not touch the rect at all.
pub fn clip_geometry<V: Vertex>(g: &Geometry<V>, rect: &Rect) -> Geometry<V> {
    match g {
        Geometry::Points(pts) => Geometry::Points(
            pts.iter()
                .copied()
                .filter(|p| finite(*p) && rect.contains(p.xy()))
                .collect(),
        ),
        Geometry::Lines(lines) => {
            let mut out = Vec::new();
            for line in lines {
                out.extend(clip_line(line, rect));
            }
            Geometry::Lines(out)
        }
        Geometry::Polygons(polys) => {
            let mut out = Vec::new();
            for rings in polys {
                let clipped = clip_polygon(rings, rect);
                if !clipped.is_empty() {
                    out.push(clipped);
                }
            }
            Geometry::Polygons(out)
        }
    }
}

fn finite<V: Vertex>(v: V) -> bool {
    let (x, y) = v.xy();
    x.is_finite() && y.is_finite()
}

/// Clip one polyline, returning the pieces that fall inside `rect`.
///
/// Consecutive segments that survive the clip and still meet are welded back into
/// one output line; a gap starts a new one. That is what makes the result a
/// partition of the original rather than a bag of segments.
pub fn clip_line<V: Vertex>(line: &[V], rect: &Rect) -> Vec<Vec<V>> {
    let mut out: Vec<Vec<V>> = Vec::new();
    let mut current: Vec<V> = Vec::new();

    for w in line.windows(2) {
        let (a, b) = (w[0], w[1]);
        if !finite(a) || !finite(b) {
            // Break the run: welding across a dropped vertex would invent an edge.
            if current.len() > 1 {
                out.push(std::mem::take(&mut current));
            } else {
                current.clear();
            }
            continue;
        }
        match clip_segment(a, b, rect) {
            None => {
                if current.len() > 1 {
                    out.push(std::mem::take(&mut current));
                } else {
                    current.clear();
                }
            }
            Some((ca, cb)) => {
                // By position, not by vertex: the near end of this segment and the
                // far end of the last are the same place but need not be the same
                // vertex -- one may be a source vertex and the other a crossing.
                if current.last().map(|v| v.xy()) == Some(ca.xy()) {
                    current.push(cb);
                } else {
                    if current.len() > 1 {
                        out.push(std::mem::take(&mut current));
                    } else {
                        current.clear();
                    }
                    current.push(ca);
                    current.push(cb);
                }
            }
        }
    }
    if current.len() > 1 {
        out.push(current);
    }
    out
}

/// Liang-Barsky: clip the segment `a -> b` to `rect`.
///
/// Returns the surviving sub-segment, or `None` when the segment misses the rect.
/// The algorithm narrows a parameter interval `[t0, t1]` along the segment against
/// each of the four boundaries; the segment is outside as soon as the interval
/// closes.
///
/// An endpoint the interval did not move is returned as itself, significance and
/// all; an endpoint it did move is a new vertex on the boundary.
///
/// A non-finite endpoint is `None`. Every comparison below would be false for a
/// `NaN`, so it would fall through as "inside" and emit a `NaN` intersection.
pub fn clip_segment<V: Vertex>(a: V, b: V, rect: &Rect) -> Option<(V, V)> {
    if !finite(a) || !finite(b) {
        return None;
    }
    let (ax, ay) = a.xy();
    let (bx, by) = b.xy();
    let (dx, dy) = (bx - ax, by - ay);
    let mut t0 = 0.0f64;
    let mut t1 = 1.0f64;

    // Each boundary is `p * t <= q`, where p is the direction component and q the
    // distance to the boundary.
    for &(p, q) in &[
        (-dx, ax - rect.min_x),
        (dx, rect.max_x - ax),
        (-dy, ay - rect.min_y),
        (dy, rect.max_y - ay),
    ] {
        if p == 0.0 {
            // Parallel to this boundary: inside iff already on the right side.
            if q < 0.0 {
                return None;
            }
            continue;
        }
        let t = q / p;
        if p < 0.0 {
            // Entering: raise the lower bound.
            if t > t1 {
                return None;
            }
            if t > t0 {
                t0 = t;
            }
        } else {
            // Leaving: lower the upper bound.
            if t < t0 {
                return None;
            }
            if t < t1 {
                t1 = t;
            }
        }
    }
    let at = |t: f64| (ax + t * dx, ay + t * dy);
    Some((
        if t0 == 0.0 { a } else { V::boundary(at(t0)) },
        if t1 == 1.0 { b } else { V::boundary(at(t1)) },
    ))
}

/// Clip a polygon's rings to `rect`.
///
/// A ring that clips away entirely is dropped. If the **exterior** ring
/// disappears the whole polygon does, holes included: a hole with no surrounding
/// area is not a shape, and emitting one would render as a solid patch of the
/// wrong colour.
pub fn clip_polygon<V: Vertex>(rings: &[Vec<V>], rect: &Rect) -> Vec<Vec<V>> {
    let mut out: Vec<Vec<V>> = Vec::new();
    for (i, ring) in rings.iter().enumerate() {
        let clipped = clip_ring(ring, rect);
        if clipped.len() < 3 {
            if i == 0 {
                return Vec::new();
            }
            continue;
        }
        out.push(clipped);
    }
    out
}

/// Sutherland-Hodgman: clip one ring against the four boundaries in turn.
///
/// The caller's closure convention is preserved: an explicitly closed ring
/// (first == last) is normalised on the way in and re-closed on the way out with a
/// **copy of the surviving first vertex**, so the two carry the same significance
/// and no later filter can open the ring by keeping one and dropping the other. An
/// unclosed ring stays unclosed. Fewer than three distinct vertices out means the
/// ring did not survive.
pub fn clip_ring<V: Vertex>(ring: &[V], rect: &Rect) -> Vec<V> {
    let was_closed = ring.len() > 1 && ring.first().map(|v| v.xy()) == ring.last().map(|v| v.xy());
    let open: Vec<V> = ring[..ring.len() - usize::from(was_closed)]
        .iter()
        .copied()
        .filter(|p| finite(*p))
        .collect();
    if open.len() < 3 {
        return Vec::new();
    }

    let mut current = open;
    for edge in [
        Edge::Left(rect.min_x),
        Edge::Right(rect.max_x),
        Edge::Bottom(rect.min_y),
        Edge::Top(rect.max_y),
    ] {
        if current.len() < 3 {
            return Vec::new();
        }
        let mut next: Vec<V> = Vec::with_capacity(current.len() + 4);
        for i in 0..current.len() {
            let a = current[(i + current.len() - 1) % current.len()];
            let b = current[i];
            let (a_in, b_in) = (edge.inside(a.xy()), edge.inside(b.xy()));
            if b_in {
                // Entering: the crossing comes first, then the vertex itself.
                if !a_in {
                    next.push(V::boundary(edge.intersect(a.xy(), b.xy())));
                }
                next.push(b);
            } else if a_in {
                // Leaving: only the crossing.
                next.push(V::boundary(edge.intersect(a.xy(), b.xy())));
            }
        }
        current = next;
    }

    // The clip can put two crossings on the same boundary point.
    current.dedup_by(|a, b| a.xy() == b.xy());
    if current.len() > 1 && current.first().map(|v| v.xy()) == current.last().map(|v| v.xy()) {
        current.pop();
    }
    if current.len() < 3 {
        return Vec::new();
    }
    if was_closed {
        let first = current[0];
        current.push(first);
    }
    current
}

/// Which side of a boundary a vertex is on, and where an edge crosses it.
#[derive(Clone, Copy)]
enum Edge {
    Left(f64),
    Right(f64),
    Bottom(f64),
    Top(f64),
}

impl Edge {
    fn inside(self, (x, y): Pt) -> bool {
        match self {
            Edge::Left(v) => x >= v,
            Edge::Right(v) => x <= v,
            Edge::Bottom(v) => y >= v,
            Edge::Top(v) => y <= v,
        }
    }

    /// Where `a -> b` crosses this boundary. Only called when the two vertices are
    /// on opposite sides, so the denominator cannot be zero.
    fn intersect(self, a: Pt, b: Pt) -> Pt {
        match self {
            Edge::Left(v) | Edge::Right(v) => {
                let t = (v - a.0) / (b.0 - a.0);
                (v, a.1 + t * (b.1 - a.1))
            }
            Edge::Bottom(v) | Edge::Top(v) => {
                let t = (v - a.1) / (b.1 - a.1);
                (a.0 + t * (b.0 - a.0), v)
            }
        }
    }
}

include!("clip_part1.rs");