//! Ear-clipping polygon triangulation with hole support — a port of Mapbox's
//! `earcut` (2.2.x), the algorithm every vector-tile renderer uses for fills.
//!
//! # Why integer coordinates
//!
//! The reference implementation works in floating point and therefore needs
//! epsilons in its orientation tests. This port takes MVT tile coordinates, which
//! are **integers** — extent 4096, overspilling a little past the tile edge where
//! geometry was clipped — so every predicate here ([`area`], [`point_in_triangle`],
//! [`intersects`]) is an exact `i64` cross product. A degenerate case is decided the
//! same way every time on every device, which is what makes a golden-image
//! comparison in CI meaningful at all.
//!
//! # Shape of the algorithm
//!
//! Rings become a doubly linked list, held in a flat `Vec<Node>` addressed by index
//! rather than by pointer — no `Rc<RefCell<..>>`, no unsafe. Holes are joined to the
//! outer ring by a bridge so the whole polygon becomes one ring, then ears —
//! triangles of three consecutive vertices containing no other vertex — are clipped
//! one at a time. Above a size threshold the vertices are additionally indexed on a
//! **Z-order curve**, so the "does this ear contain another vertex" test visits only
//! spatially nearby vertices: a coastline ring in one tile runs to thousands of
//! vertices and the unhashed test is quadratic.
//!
//! Two fallbacks handle self-intersecting input, which real basemap data contains:
//! [`cure_local_intersections`] and [`split_earcut`]. Without them a bad ring
//! silently loses its fill.

/// A vertex in the ring and in the Z-order list. `usize::MAX` is the null link.
#[derive(Clone, Copy)]
struct Node {
    /// Index of this vertex's x in the caller's flat coordinate array.
    i: usize,
    x: i32,
    y: i32,
    prev: usize,
    next: usize,
    z: i32,
    prev_z: usize,
    next_z: usize,
    steiner: bool,
}

const NIL: usize = usize::MAX;

/// The linked-list arena. Indices into `nodes` are the "pointers".
struct Ring {
    nodes: Vec<Node>,
}

impl Ring {
    fn new(capacity: usize) -> Ring {
        Ring { nodes: Vec::with_capacity(capacity) }
    }

    fn insert(&mut self, i: usize, x: i32, y: i32, last: usize) -> usize {
        let at = self.nodes.len();
        self.nodes.push(Node {
            i,
            x,
            y,
            prev: NIL,
            next: NIL,
            z: 0,
            prev_z: NIL,
            next_z: NIL,
            steiner: false,
        });
        if last == NIL {
            self.nodes[at].prev = at;
            self.nodes[at].next = at;
        } else {
            let next = self.nodes[last].next;
            self.nodes[at].next = next;
            self.nodes[at].prev = last;
            self.nodes[next].prev = at;
            self.nodes[last].next = at;
        }
        at
    }

    fn remove(&mut self, p: usize) {
        let (prev, next) = (self.nodes[p].prev, self.nodes[p].next);
        self.nodes[next].prev = prev;
        self.nodes[prev].next = next;
        let (prev_z, next_z) = (self.nodes[p].prev_z, self.nodes[p].next_z);
        if prev_z != NIL {
            self.nodes[prev_z].next_z = next_z;
        }
        if next_z != NIL {
            self.nodes[next_z].prev_z = prev_z;
        }
    }

    #[inline]
    fn x(&self, p: usize) -> i32 {
        self.nodes[p].x
    }
    #[inline]
    fn y(&self, p: usize) -> i32 {
        self.nodes[p].y
    }
    #[inline]
    fn next(&self, p: usize) -> usize {
        self.nodes[p].next
    }
    #[inline]
    fn prev(&self, p: usize) -> usize {
        self.nodes[p].prev
    }

    /// Twice the signed area of the triangle, exactly.
    #[inline]
    fn area(&self, p: usize, q: usize, r: usize) -> i64 {
        let (px, py) = (self.nodes[p].x as i64, self.nodes[p].y as i64);
        let (qx, qy) = (self.nodes[q].x as i64, self.nodes[q].y as i64);
        let (rx, ry) = (self.nodes[r].x as i64, self.nodes[r].y as i64);
        (qy - py) * (rx - qx) - (qx - px) * (ry - qy)
    }

    #[inline]
    fn equals(&self, p: usize, q: usize) -> bool {
        self.nodes[p].x == self.nodes[q].x && self.nodes[p].y == self.nodes[q].y
    }
}

/// Triangulate a polygon.
///
/// `coords` is a flat `[x0, y0, x1, y1, ...]` of the outer ring followed by each
/// hole, with no repeated closing vertex. `hole_starts` gives the **vertex** index
/// (not coordinate index) each hole begins at. Returns triangle vertex indices,
/// three per triangle.
pub fn triangulate(coords: &[i32], hole_starts: &[usize]) -> Vec<u32> {
    let mut out = Vec::new();
    let outer_end = if hole_starts.is_empty() { coords.len() } else { hole_starts[0] * 2 };
    let mut ring = Ring::new(coords.len() / 2 + hole_starts.len() * 2);

    let mut outer = match linked_list(&mut ring, coords, 0, outer_end, true) {
        Some(p) => p,
        None => return out,
    };
    if ring.next(outer) == ring.prev(outer) {
        return out;
    }

    if !hole_starts.is_empty() {
        outer = eliminate_holes(&mut ring, coords, hole_starts, outer);
    }

    let (mut min_x, mut min_y, mut inv_size) = (0i32, 0i32, 0f64);
    // The Z-order index costs a sort, so it only pays for itself on rings big enough
    // for the quadratic ear test to hurt. 80 vertices is the reference's threshold.
    if coords.len() > 80 * 2 {
        min_x = coords[0];
        min_y = coords[1];
        let mut max_x = min_x;
        let mut max_y = min_y;
        let mut i = 2;
        while i < outer_end {
            let (x, y) = (coords[i], coords[i + 1]);
            if x < min_x {
                min_x = x;
            }
            if y < min_y {
                min_y = y;
            }
            if x > max_x {
                max_x = x;
            }
            if y > max_y {
                max_y = y;
            }
            i += 2;
        }
        let span = (max_x - min_x).max(max_y - min_y);
        inv_size = if span != 0 { 32767.0 / span as f64 } else { 0.0 };
    }

    earcut_linked(&mut ring, outer, &mut out, min_x, min_y, inv_size, 0);
    out
}

/// Build a circular doubly linked list from a ring, in the requested winding.
fn linked_list(
    ring: &mut Ring,
    coords: &[i32],
    start: usize,
    end: usize,
    clockwise: bool,
) -> Option<usize> {
    if end <= start {
        return None;
    }
    let mut last = NIL;
    if clockwise == (signed_area(coords, start, end) > 0) {
        let mut i = start;
        while i < end {
            last = ring.insert(i, coords[i], coords[i + 1], last);
            i += 2;
        }
    } else {
        let mut i = end;
        while i > start {
            i -= 2;
            last = ring.insert(i, coords[i], coords[i + 1], last);
        }
    }
    if last != NIL {
        let next = ring.next(last);
        if ring.equals(last, next) {
            ring.remove(last);
            last = next;
        }
    }
    if last == NIL {
        None
    } else {
        Some(last)
    }
}

/// Drop colinear and duplicate vertices; they can never form a valid ear.
fn filter_points(ring: &mut Ring, start: usize, end_in: usize) -> usize {
    let mut end = if end_in == NIL { start } else { end_in };
    let mut p = start;
    loop {
        let mut again = false;
        let (prev, next) = (ring.prev(p), ring.next(p));
        if !ring.nodes[p].steiner && (ring.equals(p, next) || ring.area(prev, p, next) == 0) {
            ring.remove(p);
            p = prev;
            end = p;
            if p == ring.next(p) {
                break;
            }
            again = true;
        } else {
            p = ring.next(p);
        }
        if !again && p == end {
            break;
        }
    }
    end
}

/// The main loop: clip ears off, falling back on two repair passes.
fn earcut_linked(
    ring: &mut Ring,
    ear_in: usize,
    out: &mut Vec<u32>,
    min_x: i32,
    min_y: i32,
    inv_size: f64,
    pass: u8,
) {
    if ear_in == NIL {
        return;
    }
    let mut ear = ear_in;
    if pass == 0 && inv_size > 0.0 {
        index_curve(ring, ear, min_x, min_y, inv_size);
    }

    let mut stop = ear;
    while ring.prev(ear) != ring.next(ear) {
        let prev = ring.prev(ear);
        let next = ring.next(ear);

        let is_ear = if inv_size > 0.0 {
            is_ear_hashed(ring, ear, min_x, min_y, inv_size)
        } else {
            is_ear(ring, ear)
        };
        if is_ear {
            out.push((ring.nodes[prev].i / 2) as u32);
            out.push((ring.nodes[ear].i / 2) as u32);
            out.push((ring.nodes[next].i / 2) as u32);
            ring.remove(ear);
            // Skipping the next vertex leads to fewer sliver triangles.
            ear = ring.next(next);
            stop = ring.next(next);
            continue;
        }

        ear = next;
        if ear != stop {
            continue;
        }

        // No ear was found in a full loop: the ring is not simple.
        match pass {
            0 => {
                let filtered = filter_points(ring, ear, NIL);
                earcut_linked(ring, filtered, out, min_x, min_y, inv_size, 1);
            }
            1 => {
                let filtered = filter_points(ring, ear, NIL);
                let cured = cure_local_intersections(ring, filtered, out);
                earcut_linked(ring, cured, out, min_x, min_y, inv_size, 2);
            }
            _ => split_earcut(ring, ear, out, min_x, min_y, inv_size),
        }
        break;
    }
}

/// Is the triangle `prev, ear, next` an ear?
fn is_ear(ring: &Ring, ear: usize) -> bool {
    let a = ring.prev(ear);
    let b = ear;
    let c = ring.next(ear);
    // Reflex vertices cannot be ears.
    if ring.area(a, b, c) >= 0 {
        return false;
    }

    let mut p = ring.next(c);
    while p != a {
        if point_in_triangle(ring, a, b, c, p) && ring.area(ring.prev(p), p, ring.next(p)) >= 0 {
            return false;
        }
        p = ring.next(p);
    }
    true
}

/// [`is_ear`], but visiting only vertices whose Z-order code falls inside the
/// candidate triangle's Z range — what turns the quadratic scan into something a
/// coastline tile can afford.
fn is_ear_hashed(ring: &Ring, ear: usize, min_x: i32, min_y: i32, inv_size: f64) -> bool {
    let a = ring.prev(ear);
    let b = ear;
    let c = ring.next(ear);
    if ring.area(a, b, c) >= 0 {
        return false;
    }

    let min_tx = ring.x(a).min(ring.x(b)).min(ring.x(c));
    let min_ty = ring.y(a).min(ring.y(b)).min(ring.y(c));
    let max_tx = ring.x(a).max(ring.x(b)).max(ring.x(c));
    let max_ty = ring.y(a).max(ring.y(b)).max(ring.y(c));
    let min_z = z_order(min_tx, min_ty, min_x, min_y, inv_size);
    let max_z = z_order(max_tx, max_ty, min_x, min_y, inv_size);

    let inside = |p: usize| -> bool {
        ring.x(p) >= min_tx
            && ring.x(p) <= max_tx
            && ring.y(p) >= min_ty
            && ring.y(p) <= max_ty
            && p != a
            && p != c
            && point_in_triangle(ring, a, b, c, p)
            && ring.area(ring.prev(p), p, ring.next(p)) >= 0
    };

    let mut p = ring.nodes[ear].prev_z;
    let mut n = ring.nodes[ear].next_z;
    // Walk outward in both directions while still inside the Z range.
    while p != NIL && ring.nodes[p].z >= min_z && n != NIL && ring.nodes[n].z <= max_z {
        if inside(p) {
            return false;
        }
        p = ring.nodes[p].prev_z;
        if inside(n) {
            return false;
        }
        n = ring.nodes[n].next_z;
    }
    while p != NIL && ring.nodes[p].z >= min_z {
        if inside(p) {
            return false;
        }
        p = ring.nodes[p].prev_z;
    }
    while n != NIL && ring.nodes[n].z <= max_z {
        if inside(n) {
            return false;
        }
        n = ring.nodes[n].next_z;
    }
    true
}

/// Cut off a self-intersecting sliver so the rest of the ring can be clipped.
fn cure_local_intersections(ring: &mut Ring, start_in: usize, out: &mut Vec<u32>) -> usize {
    let mut start = start_in;
    let mut p = start;
    loop {
        let a = ring.prev(p);
        let next = ring.next(p);
        let b = ring.next(next);
        if !ring.equals(a, b)
            && intersects(ring, a, p, next, b)
            && locally_inside(ring, a, b)
            && locally_inside(ring, b, a)
        {
            out.push((ring.nodes[a].i / 2) as u32);
            out.push((ring.nodes[p].i / 2) as u32);
            out.push((ring.nodes[b].i / 2) as u32);
            ring.remove(p);
            ring.remove(next);
            p = b;
            start = b;
        }
        p = ring.next(p);
        if p == start {
            break;
        }
    }
    filter_points(ring, p, NIL)
}

/// Split the polygon along a valid diagonal and triangulate the halves.
fn split_earcut(
    ring: &mut Ring,
    start: usize,
    out: &mut Vec<u32>,
    min_x: i32,
    min_y: i32,
    inv_size: f64,
) {
    let mut a = start;
    loop {
        let mut b = ring.next(ring.next(a));
        while b != ring.prev(a) {
            if ring.nodes[a].i != ring.nodes[b].i && is_valid_diagonal(ring, a, b) {
                let c = split_polygon(ring, a, b);
                let next_a = ring.next(a);
                let filtered_a = filter_points(ring, a, next_a);
                let next_c = ring.next(c);
                let filtered_c = filter_points(ring, c, next_c);
                earcut_linked(ring, filtered_a, out, min_x, min_y, inv_size, 0);
                earcut_linked(ring, filtered_c, out, min_x, min_y, inv_size, 0);
                return;
            }
            b = ring.next(b);
        }
        a = ring.next(a);
        if a == start {
            break;
        }
    }
}

include!("earcut_part1.rs");
include!("earcut_part2.rs");