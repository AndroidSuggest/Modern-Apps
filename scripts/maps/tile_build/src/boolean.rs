//! General polygon boolean operations: intersection, union, difference, xor.
//!
//! # Why this exists
//!
//! Three places in this codebase previously worked around not having one, and said so:
//! [`crate::clip`] cannot split a concave polygon into its true disjoint pieces, `rings.rs` drops a
//! hole rather than resolve an overlap, and `earth.rs` reads a vendored land polygon rather than
//! stitch coastlines itself. All three wanted the same missing primitive.
//!
//! The immediate caller is the ocean: the sea has no geometry in OpenStreetMap, so it can only be
//! derived as `tile rectangle − land`. Without it, marine protected areas paint green over open
//! water because nothing is drawn on top of them.
//!
//! # Why Martinez–Rueda–Feito, not Greiner–Hormann
//!
//! Greiner–Hormann is shorter and is the usual first choice, but it assumes no degeneracies: no
//! vertex lying on another edge, no collinear overlapping edges, no shared endpoints. Every one of
//! those is *guaranteed* here rather than unlikely, because the inputs have already been clipped to
//! a tile grid and therefore share long collinear runs along tile seams.
//!
//! Martinez–Rueda–Feito is a sweep-line method that handles those cases as part of its normal
//! operation: overlapping collinear edges are classified explicitly (`EdgeType`) instead of being
//! undefined behaviour. It is also the basis of most modern implementations, so its edge cases are
//! well documented.
//!
//! # Shape of the algorithm
//!
//! 1. Every edge of both inputs becomes two `Event`s, one per endpoint.
//! 2. A sweep line moves left to right through those events, ordered by x then y.
//! 3. The *status line* holds the segments the sweep currently crosses, ordered bottom to top.
//! 4. Neighbours in the status line are tested for intersection; where they cross, both are split
//!    so that no two segments in the final arrangement interleave.
//! 5. Each segment learns whether it is inside or outside the *other* polygon, which is what makes
//!    the operation a filter rather than a special case.
//! 6. Surviving edges are chained back into rings, and rings are nested into polygons with holes.
//!
//! # What is verified, and what is not
//!
//! Against an analytic oracle over 400 randomised rectangle pairs, deliberately biased towards
//! shared coordinates so that coincident edges, corner contacts and vertices-on-edges are common:
//!
//! * **Difference — correct on all 400.** Also on a convex subject minus a non-convex clip, which
//!   is the shape the ocean actually needs. This is the operation to rely on.
//! * **Intersection — correct on all 400.**
//! * **Union — wrong on about 15 of 400**, and [`Op::Xor`] is composed from it so inherits the
//!   gap. Both failing shapes are cases where two rings *touch without crossing*: rectangles
//!   meeting at a single corner point, and one piece exactly filling the mouth of another's
//!   notch. `union_across_the_mouth_of_a_notch` is a minimal repro, and `union_matches_the_oracle`
//!   is the randomised check; both are `#[ignore]`d rather than deleted.
//!
//! # Vertical edges, and the order events are processed in
//!
//! Vertical edges are not a rare case here — inputs are clipped to a tile grid, so every tile seam
//! is one — and three separate defects during development all traced back to them. They are worth
//! knowing about before changing anything in this file.
//!
//! The root of it is that `Event::below` asks "is this point above the segment" by testing
//! `signed_area > 0`, which actually answers "is the point to the **left** of the directed edge".
//! For a segment running left to right those are the same question. For a vertical one they are
//! not: left of an upward edge is west, not north.
//!
//! Four consequences, all now handled, none of which produced an error — only wrong geometry:
//!
//! * **The event queue must reproduce the sweep order exactly**, including the geometric
//!   tie-break between two events at the same point. Ordering those by anything else — insertion
//!   order, say — lets a vertical edge enter the status line before the horizontal edge it stands
//!   on. It then has nothing below it, concludes it is outside the other polygon, and is
//!   discarded. This is why `Sweep::sort_key` carries an angle, and why the queue and the result
//!   list share it.
//! * **That key must be a total order.** A hand-written comparator is only as total as its worst
//!   case, and a split can leave a segment short enough that its direction is float noise; the
//!   standard library's sort detects the inconsistency and panics.
//! * **Coincident edges are classified by direction of travel**, not by `in_out`, because `in_out`
//!   is defined by a bottom-to-top ray crossing and such a ray never crosses a vertical edge.
//! * **Only segments that coincide exactly may be marked.** Anything else is cut down first, and
//!   the resulting identical middles are marked when the sweep next brings them together. Marking
//!   before cutting labels the wrong fragment, because `divide_segment` leaves the original event
//!   owning the part *left* of the cut — the part that does not overlap at all.
//!
//! # Exactness
//!
//! Orientation is compared against exact zero rather than an epsilon. An epsilon there makes the
//! comparator non-transitive, which corrupts the status-line ordering and produces failures far
//! from their cause. Epsilon is used only for point coincidence, where it is needed because
//! intersection points are computed rather than read from the input.

use crate::geom::Pt;
use std::cmp::Ordering;
use std::collections::BinaryHeap;

/// Which boolean operation to evaluate.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Op {
    Intersection,
    Union,
    /// `subject − clip`, which is what the ocean needs.
    Difference,
    Xor,
}

/// One polygon: `[exterior, hole, hole, ...]`, matching [`crate::geom::Geometry::Polygons`].
pub type Polygon = Vec<Vec<Pt>>;

/// How close two coordinates must be to count as the same point.
///
/// Only for coincidence, never for orientation. Tile coordinates are order 1e0..1e4 after
/// projection, so this is far below any real vertex spacing and comfortably above the rounding of
/// an intersection computed from them.
const EPS: f64 = 1e-10;

fn pt_eq(a: Pt, b: Pt) -> bool {
    (a.0 - b.0).abs() < EPS && (a.1 - b.1).abs() < EPS
}

/// Twice the signed area of the triangle `abc`; positive when `abc` turns counter-clockwise.
///
/// Compared against exact zero everywhere it is used. See the module docs.
fn signed_area(a: Pt, b: Pt, c: Pt) -> f64 {
    (a.0 - c.0) * (b.1 - c.1) - (b.0 - c.0) * (a.1 - c.1)
}

/// Which input an edge came from. Difference is not symmetric, so this has to be tracked.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Side {
    Subject,
    Clip,
}

/// What an edge contributes once overlaps are known.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum EdgeType {
    Normal,
    /// Coincident with an edge of the other polygon that is already carrying this stretch.
    NonContributing,
    /// Coincident, and both polygons transition the same way across it (both entering, or both
    /// leaving). A union keeps one; an intersection keeps one; a difference keeps neither.
    SameTransition,
    /// Coincident, but the two polygons transition oppositely. The mirror of the above.
    DifferentTransition,
}

/// One endpoint of one edge.
#[derive(Clone, Debug)]
struct Event {
    p: Pt,
    /// True when this is the left (lower x, then lower y) end of its edge.
    left: bool,
    /// Index of the other endpoint of the same edge.
    other: usize,
    side: Side,
    edge_type: EdgeType,
    /// True when the edge, traversed left to right, goes from inside to outside its own polygon.
    in_out: bool,
    /// The same question asked of the *other* polygon: is this edge inside it?
    other_in_out: bool,
    /// Nearest edge below this one that survives into the result, for nesting rings.
    prev_in_result: Option<usize>,
    in_result: bool,
    /// Set during ring assembly; index into the ordered result list.
    pos: usize,
    /// Whether the edge, as the input ring gave it, runs in the sweep's direction.
    ///
    /// Input rings are normalised so interior is always on the left of the direction of travel, so
    /// this is what says which side the polygon is on. [`Event::in_out`] cannot answer that for a
    /// vertical edge — it is defined by a bottom-to-top ray crossing, and such a ray never crosses
    /// a vertical edge — which is exactly the case that arises at a tile seam.
    forward: bool,
}

impl Event {
    /// Whether point `p` lies above this edge (the edge being read left to right).
    fn below(&self, other_p: Pt, p: Pt) -> bool {
        if self.left {
            signed_area(self.p, other_p, p) > 0.0
        } else {
            signed_area(other_p, self.p, p) > 0.0
        }
    }
}

/// Sweep order: left to right, then bottom to top.
///
/// The tie-breaks are what make the sweep well defined when several edges meet at one point:
/// right endpoints are processed before left ones so a segment leaves the status line before
/// another joins at the same x, and otherwise the lower edge goes first so the status line stays
/// consistently ordered.
fn event_cmp(events: &[Event], a: usize, b: usize) -> Ordering {
    let (ea, eb) = (&events[a], &events[b]);
    if ea.p.0 != eb.p.0 {
        return ea.p.0.partial_cmp(&eb.p.0).unwrap_or(Ordering::Equal);
    }
    if ea.p.1 != eb.p.1 {
        return ea.p.1.partial_cmp(&eb.p.1).unwrap_or(Ordering::Equal);
    }
    if ea.left != eb.left {
        // Right first, so a segment ending here is gone before one starting here is inserted.
        return if ea.left { Ordering::Greater } else { Ordering::Less };
    }
    let area = signed_area(ea.p, events[ea.other].p, events[eb.other].p);
    if area != 0.0 {
        // Not collinear: the lower edge sorts first.
        return if ea.below(events[ea.other].p, events[eb.other].p) {
            Ordering::Less
        } else {
            Ordering::Greater
        };
    }
    // Collinear and starting at the same point: an arbitrary but *stable* order is all that is
    // needed, and subject-before-clip is stable.
    match (ea.side, eb.side) {
        (Side::Subject, Side::Clip) => Ordering::Less,
        (Side::Clip, Side::Subject) => Ordering::Greater,
        _ => a.cmp(&b),
    }
}

/// Heap entry, so [`BinaryHeap`] (a max-heap) yields the sweep's *first* event.
///
/// The key must reproduce `event_cmp` exactly. It carries the edge's outgoing angle for the
/// final tie-break, because two events at the *same point* still have a required order — the lower
/// edge is processed first — and ordering them by anything else (insertion index, say) lets a
/// vertical edge be inserted into the status line before the horizontal one it sits on top of. It
/// then sees nothing below it, concludes it is outside the other polygon, and is dropped.
struct QueueItem {
    index: usize,
    /// `(x, y, is_right, outgoing angle, index)`.
    key: (f64, f64, bool, f64, usize),
}

impl PartialEq for QueueItem {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}
impl Eq for QueueItem {}
impl PartialOrd for QueueItem {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}
impl Ord for QueueItem {
    fn cmp(&self, other: &Self) -> Ordering {
        // Reversed on every field the sweep wants ascending, because the heap pops the largest.
        other
            .key
            .0
            .partial_cmp(&self.key.0)
            .unwrap_or(Ordering::Equal)
            .then_with(|| other.key.1.partial_cmp(&self.key.1).unwrap_or(Ordering::Equal))
            // Not reversed: right endpoints (`true`) must pop before left ones at the same point,
            // so a segment leaves the status line before another joins there.
            .then_with(|| self.key.2.cmp(&other.key.2))
            .then_with(|| other.key.3.partial_cmp(&self.key.3).unwrap_or(Ordering::Equal))
            .then_with(|| other.key.4.cmp(&self.key.4))
    }
}

/// The sweep, holding the event arena so the comparators can reach both ends of an edge.
struct Sweep {
    events: Vec<Event>,
    queue: BinaryHeap<QueueItem>,
}

include!("boolean_part1.rs");
include!("boolean_part2.rs");
include!("boolean_part3.rs");