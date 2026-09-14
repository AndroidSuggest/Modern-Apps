/// Bridge every hole into the outer ring, so the polygon becomes one ring.
fn eliminate_holes(
    ring: &mut Ring,
    coords: &[i32],
    hole_starts: &[usize],
    outer_in: usize,
) -> usize {
    let mut queue: Vec<usize> = Vec::with_capacity(hole_starts.len());
    for (h, &start) in hole_starts.iter().enumerate() {
        let start = start * 2;
        let end = if h < hole_starts.len() - 1 { hole_starts[h + 1] * 2 } else { coords.len() };
        if let Some(list) = linked_list(ring, coords, start, end, false) {
            if list == ring.next(list) {
                ring.nodes[list].steiner = true;
            }
            queue.push(leftmost(ring, list));
        }
    }
    queue.sort_by_key(|&p| ring.x(p));

    let mut outer = outer_in;
    for hole in queue {
        outer = eliminate_hole(ring, hole, outer);
    }
    outer
}

fn eliminate_hole(ring: &mut Ring, hole: usize, outer: usize) -> usize {
    let bridge = match find_hole_bridge(ring, hole, outer) {
        Some(b) => b,
        None => return outer,
    };
    let bridge_reverse = split_polygon(ring, bridge, hole);
    // Filter colinear points around both cuts.
    let next_reverse = ring.next(bridge_reverse);
    filter_points(ring, bridge_reverse, next_reverse);
    let next_bridge = ring.next(bridge);
    filter_points(ring, bridge, next_bridge)
}

/// A visible vertex of the outer ring to bridge `hole` to: cast a ray left from the
/// hole's leftmost vertex and take the nearest edge it hits.
///
/// The ray intersection is computed in `f64`, as the reference does. It is a distance
/// comparison rather than an orientation predicate, and integer truncation here would
/// occasionally accept an edge whose true intersection lies past the hole —
/// producing a bridge that crosses the polygon.
fn find_hole_bridge(ring: &Ring, hole: usize, outer: usize) -> Option<usize> {
    let hx = ring.x(hole);
    let hy = ring.y(hole);
    let mut qx = f64::NEG_INFINITY;
    let mut m = NIL;

    let mut p = outer;
    loop {
        let next = ring.next(p);
        if hy <= ring.y(p) && hy >= ring.y(next) && ring.y(next) != ring.y(p) {
            let x = ring.x(p) as f64
                + (hy - ring.y(p)) as f64 * (ring.x(next) - ring.x(p)) as f64
                    / (ring.y(next) - ring.y(p)) as f64;
            if x <= hx as f64 && x > qx {
                qx = x;
                m = if ring.x(p) < ring.x(next) { p } else { next };
                if x == hx as f64 {
                    // The hole touches the outline.
                    return Some(m);
                }
            }
        }
        p = next;
        if p == outer {
            break;
        }
    }
    if m == NIL {
        return None;
    }

    // Look for a better bridge: among the reflex vertices inside the triangle
    // (hole, ray hit, m), take the one at the smallest angle to the ray.
    let stop = m;
    let mx = ring.x(m);
    let my = ring.y(m);
    let mut tan_min = f64::INFINITY;
    let mut best = m;
    let mut p = m;
    loop {
        let in_triangle = hx >= ring.x(p)
            && ring.x(p) >= mx
            && hx != ring.x(p)
            && point_in_triangle_f(
                if hy < my { hx as f64 } else { qx },
                hy as f64,
                mx as f64,
                my as f64,
                if hy < my { qx } else { hx as f64 },
                hy as f64,
                ring.x(p) as f64,
                ring.y(p) as f64,
            );
        if in_triangle {
            let tan = ((hy - ring.y(p)) as f64).abs() / (hx - ring.x(p)) as f64;
            let better = locally_inside(ring, p, hole)
                && (tan < tan_min
                    || (tan == tan_min
                        && (ring.x(p) > ring.x(best)
                            || (ring.x(p) == ring.x(best) && sector_contains_sector(ring, best, p)))));
            if better {
                best = p;
                tan_min = tan;
            }
        }
        p = ring.next(p);
        if p == stop {
            break;
        }
    }
    Some(best)
}

/// Does the reflex sector at `m` contain the sector at `p`?
fn sector_contains_sector(ring: &Ring, m: usize, p: usize) -> bool {
    ring.area(ring.prev(m), m, ring.prev(p)) < 0 && ring.area(ring.next(p), m, ring.next(m)) < 0
}

/// Build the Z-order linked list for the ring.
fn index_curve(ring: &mut Ring, start: usize, min_x: i32, min_y: i32, inv_size: f64) {
    let mut p = start;
    loop {
        if ring.nodes[p].z == 0 {
            ring.nodes[p].z = z_order(ring.x(p), ring.y(p), min_x, min_y, inv_size);
        }
        let (prev, next) = (ring.prev(p), ring.next(p));
        ring.nodes[p].prev_z = prev;
        ring.nodes[p].next_z = next;
        p = next;
        if p == start {
            break;
        }
    }
    let prev_z = ring.nodes[p].prev_z;
    ring.nodes[prev_z].next_z = NIL;
    ring.nodes[p].prev_z = NIL;
    sort_linked(ring, p);
}

/// Merge sort the Z-order list in place — the reference's Simon Tatham sort.
fn sort_linked(ring: &mut Ring, list_in: usize) {
    let mut list = list_in;
    let mut in_size = 1usize;
    loop {
        let mut p = list;
        list = NIL;
        let mut tail = NIL;
        let mut num_merges = 0;

        while p != NIL {
            num_merges += 1;
            let mut q = p;
            let mut p_size = 0usize;
            for _ in 0..in_size {
                p_size += 1;
                q = ring.nodes[q].next_z;
                if q == NIL {
                    break;
                }
            }
            let mut q_size = in_size;

            while p_size > 0 || (q_size > 0 && q != NIL) {
                let e;
                if p_size != 0 && (q_size == 0 || q == NIL || ring.nodes[p].z <= ring.nodes[q].z) {
                    e = p;
                    p = ring.nodes[p].next_z;
                    p_size -= 1;
                } else {
                    e = q;
                    q = ring.nodes[q].next_z;
                    q_size -= 1;
                }
                if tail != NIL {
                    ring.nodes[tail].next_z = e;
                } else {
                    list = e;
                }
                ring.nodes[e].prev_z = tail;
                tail = e;
            }
            p = q;
        }
        ring.nodes[tail].next_z = NIL;
        in_size *= 2;
        if num_merges <= 1 {
            break;
        }
    }
}

/// Interleave the low 16 bits of the normalised coordinates: a Z-order code.
///
/// `inv_size` already carries the `32767 / span` scale (see [`triangulate`]), so the
/// coordinate is only multiplied by it — never scaled by 32767 a second time. Doing that
/// pushed the inputs to ~32767², far past the 15 bits the interleaving masks below assume,
/// so the codes stopped reflecting spatial locality. The sorted Z-list then bore no relation
/// to position, [`is_ear_hashed`] walked out of range before reaching the points that
/// actually sat inside a candidate triangle, and it accepted ears that were not ears. The
/// result was overlapping triangles: the ocean polygon covered 0.98 of its tile instead of
/// 0.71, painting over every continent it should have cut out.
///
/// Only polygons past [`triangulate`]'s 80-vertex threshold take this path, which is why
/// small test cases passed and every real tile was wrong.
///
/// Size alone is a weak guard, which is what makes this fragile. A ring exercises the hashed
/// lookup once it is past the threshold, but it only *depends* on the lookup being correct if
/// some candidate ear has to be rejected by finding a vertex inside it. Convex and monotone
/// rings never do — an ear is always available, so they come out exact however corrupted the
/// codes are, at any vertex count. Forcing the dependency takes interior points the lookup
/// must actually reach, and holes are the cheapest way to get them; see
/// `a_hashed_ring_rejects_ears_that_contain_a_hole_vertex`.
fn z_order(x_in: i32, y_in: i32, min_x: i32, min_y: i32, inv_size: f64) -> i32 {
    let mut x = ((x_in - min_x) as f64 * inv_size) as i32;
    let mut y = ((y_in - min_y) as f64 * inv_size) as i32;

    x = (x | (x << 8)) & 0x00FF00FF;
    x = (x | (x << 4)) & 0x0F0F0F0F;
    x = (x | (x << 2)) & 0x33333333;
    x = (x | (x << 1)) & 0x55555555;

    y = (y | (y << 8)) & 0x00FF00FF;
    y = (y | (y << 4)) & 0x0F0F0F0F;
    y = (y | (y << 2)) & 0x33333333;
    y = (y | (y << 1)) & 0x55555555;

    x | (y << 1)
}

/// The leftmost node of a ring, where a hole bridge starts.
fn leftmost(ring: &Ring, start: usize) -> usize {
    let mut p = start;
    let mut best = start;
    loop {
        if ring.x(p) < ring.x(best) || (ring.x(p) == ring.x(best) && ring.y(p) < ring.y(best)) {
            best = p;
        }
        p = ring.next(p);
        if p == start {
            break;
        }
    }
    best
}

/// Exact: is `p` inside the triangle `a, b, c`?
fn point_in_triangle(ring: &Ring, a: usize, b: usize, c: usize, p: usize) -> bool {
    let (px, py) = (ring.x(p) as i64, ring.y(p) as i64);
    let (ax, ay) = (ring.x(a) as i64 - px, ring.y(a) as i64 - py);
    let (bx, by) = (ring.x(b) as i64 - px, ring.y(b) as i64 - py);
    let (cx, cy) = (ring.x(c) as i64 - px, ring.y(c) as i64 - py);
    cx * ay - ax * cy >= 0 && ax * by - bx * ay >= 0 && bx * cy - cx * by >= 0
}

/// [`point_in_triangle`] for the hole-bridge search, which works in `f64`.
#[allow(clippy::too_many_arguments)]
fn point_in_triangle_f(
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    cx: f64,
    cy: f64,
    px: f64,
    py: f64,
) -> bool {
    (cx - px) * (ay - py) - (ax - px) * (cy - py) >= 0.0
        && (ax - px) * (by - py) - (bx - px) * (ay - py) >= 0.0
        && (bx - px) * (cy - py) - (cx - px) * (by - py) >= 0.0
}

/// Is `a-b` a valid diagonal: inside the polygon, crossing nothing?
fn is_valid_diagonal(ring: &Ring, a: usize, b: usize) -> bool {
    let (an, ap) = (ring.next(a), ring.prev(a));
    ring.nodes[an].i != ring.nodes[b].i
        && ring.nodes[ap].i != ring.nodes[b].i
        && !intersects_polygon(ring, a, b)
        && ((locally_inside(ring, a, b)
            && locally_inside(ring, b, a)
            && middle_inside(ring, a, b)
            && (ring.area(ring.prev(a), a, ring.prev(b)) != 0
                || ring.area(a, ring.prev(b), b) != 0))
            // The zero-length special case.
            || (ring.equals(a, b)
                && ring.area(ring.prev(a), a, ring.next(a)) > 0
                && ring.area(ring.prev(b), b, ring.next(b)) > 0))
}

/// Do the segments `p1-q1` and `p2-q2` intersect?
fn intersects(ring: &Ring, p1: usize, q1: usize, p2: usize, q2: usize) -> bool {
    let o1 = ring.area(p1, q1, p2).signum();
    let o2 = ring.area(p1, q1, q2).signum();
    let o3 = ring.area(p2, q2, p1).signum();
    let o4 = ring.area(p2, q2, q1).signum();

    if o1 != o2 && o3 != o4 {
        return true;
    }
    // Collinear and overlapping.
    (o1 == 0 && on_segment(ring, p1, p2, q1))
        || (o2 == 0 && on_segment(ring, p1, q2, q1))
        || (o3 == 0 && on_segment(ring, p2, p1, q2))
        || (o4 == 0 && on_segment(ring, p2, q1, q2))
}

/// For collinear `p, q, r`: does `q` lie on segment `p-r`?
fn on_segment(ring: &Ring, p: usize, q: usize, r: usize) -> bool {
    ring.x(q) <= ring.x(p).max(ring.x(r))
        && ring.x(q) >= ring.x(p).min(ring.x(r))
        && ring.y(q) <= ring.y(p).max(ring.y(r))
        && ring.y(q) >= ring.y(p).min(ring.y(r))
}

/// Does the diagonal `a-b` cross any polygon edge?
fn intersects_polygon(ring: &Ring, a: usize, b: usize) -> bool {
    let mut p = a;
    loop {
        let next = ring.next(p);
        if ring.nodes[p].i != ring.nodes[a].i
            && ring.nodes[next].i != ring.nodes[a].i
            && ring.nodes[p].i != ring.nodes[b].i
            && ring.nodes[next].i != ring.nodes[b].i
            && intersects(ring, p, next, a, b)
        {
            return true;
        }
        p = next;
        if p == a {
            break;
        }
    }
    false
}

/// Does `a-b` leave `a` on the inside of the polygon?
fn locally_inside(ring: &Ring, a: usize, b: usize) -> bool {
    let (prev, next) = (ring.prev(a), ring.next(a));
    if ring.area(prev, a, next) < 0 {
        ring.area(a, b, next) >= 0 && ring.area(a, prev, b) >= 0
    } else {
        ring.area(a, b, prev) < 0 || ring.area(a, next, b) < 0
    }
}

/// Is the midpoint of `a-b` inside the polygon? A crossing count along a horizontal
/// ray.
///
/// Everything is doubled so the midpoint stays integral, and the "is the midpoint
/// left of this edge's crossing" test is a cross-product sign rather than a division —
/// integer division truncates toward zero, which on a negative slope would decide
/// a boundary case differently from the floating-point reference.
///
/// `dy` is deliberately **not** doubled, though `px2`, `py2`, `y2` and the `x(p) * 2`
/// term all are. Doubling it as well scales `px2 * dy` and `x(p) * 2 * dy` but leaves
/// `dx * (py2 - y2)` behind, so the two halves of the intersection no longer share a
/// scale and the crossing lands at the wrong point along the edge. Restoring the
/// apparent symmetry therefore looks like a tidy-up and is a bug. It is caught by
/// exactly one test, `the_midpoint_test_meets_a_slanted_edge_at_its_true_crossing`,
/// which needs a deliberately shallow edge: on an axis-aligned one the offset term is
/// zero and either scaling passes, so ordinary rectangular test shapes cannot see it.
fn middle_inside(ring: &Ring, a: usize, b: usize) -> bool {
    let px2 = ring.x(a) as i64 + ring.x(b) as i64;
    let py2 = ring.y(a) as i64 + ring.y(b) as i64;
    let mut inside = false;
    let mut p = a;
    loop {
        let next = ring.next(p);
        let y2 = ring.y(p) as i64 * 2;
        let next_y2 = ring.y(next) as i64 * 2;
        if (y2 > py2) != (next_y2 > py2) && ring.y(next) != ring.y(p) {
            let dx = ring.x(next) as i64 - ring.x(p) as i64;
            let dy = ring.y(next) as i64 - ring.y(p) as i64;
            // 2 * xIntersect * dy, so the comparison never divides.
            let scaled = dx * (py2 - y2) + ring.x(p) as i64 * 2 * dy;
            let left_of = if dy > 0 { px2 * dy < scaled } else { px2 * dy > scaled };
            if left_of {
                inside = !inside;
            }
        }
        p = next;
        if p == a {
            break;
        }
    }
    inside
}

/// Cut the polygon along `a-b`, producing two rings, and return the node belonging
/// to the second one.
fn split_polygon(ring: &mut Ring, a: usize, b: usize) -> usize {
    let (ai, ax, ay) = (ring.nodes[a].i, ring.nodes[a].x, ring.nodes[a].y);
    let (bi, bx, by) = (ring.nodes[b].i, ring.nodes[b].x, ring.nodes[b].y);
    let a2 = ring.nodes.len();
    ring.nodes.push(Node {
        i: ai,
        x: ax,
        y: ay,
        prev: NIL,
        next: NIL,
        z: 0,
        prev_z: NIL,
        next_z: NIL,
        steiner: false,
    });
    let b2 = ring.nodes.len();
    ring.nodes.push(Node {
        i: bi,
        x: bx,
        y: by,
        prev: NIL,
        next: NIL,
        z: 0,
        prev_z: NIL,
        next_z: NIL,
        steiner: false,
    });

    let an = ring.next(a);
    let bp = ring.prev(b);

    ring.nodes[a].next = b;
    ring.nodes[b].prev = a;
    ring.nodes[a2].next = an;
    ring.nodes[an].prev = a2;
    ring.nodes[b2].next = a2;
    ring.nodes[a2].prev = b2;
    ring.nodes[bp].next = b2;
    ring.nodes[b2].prev = bp;
    b2
}
