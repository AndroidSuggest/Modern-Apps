/// Bounding box, as `(min_x, min_y, max_x, max_y)`. Assumes a non-empty ring, which the
/// [`super::MIN_RING_COORDS`] gate in [`super::tessellate`] guarantees.
pub(crate) fn bounds(ring: &[(i32, i32)]) -> (i32, i32, i32, i32) {
    let mut box_ = (ring[0].0, ring[0].1, ring[0].0, ring[0].1);
    for &(x, y) in &ring[1..] {
        box_.0 = box_.0.min(x);
        box_.1 = box_.1.min(y);
        box_.2 = box_.2.max(x);
        box_.3 = box_.3.max(y);
    }
    box_
}

pub(crate) fn boxes_overlap(a: (i32, i32, i32, i32), b: (i32, i32, i32, i32)) -> bool {
    a.0 <= b.2 && a.2 >= b.0 && a.1 <= b.3 && a.3 >= b.1
}

pub(crate) fn box_area(box_: (i32, i32, i32, i32)) -> i64 {
    (box_.2 as i64 - box_.0 as i64) * (box_.3 as i64 - box_.1 as i64)
}

/// Even-odd point-in-ring.
///
/// The crossing test is an integer cross product rather than a division, as in
/// [`super::super::earcut`]: truncation would put a vertex on one side of an edge here and the
/// other side there, and the ring grouping this feeds has to be the same on every device.
fn point_in_ring(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    let mut inside = false;
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        if (y1 > y) != (y2 > y) {
            let dy = y2 as i64 - y1 as i64;
            let along = (x as i64 - x1 as i64) * dy;
            let at = (y as i64 - y1 as i64) * (x2 as i64 - x1 as i64);
            if if dy > 0 { along < at } else { along > at } {
                inside = !inside;
            }
        }
    }
    inside
}

/// Is the point exactly on one of the ring's edges?
///
/// [`point_in_ring`] is an even-odd crossing test, so it answers inconsistently for a point
/// that lies *on* the boundary — which side it lands on depends on which edge it sits on.
/// Real coastline holes touch their exterior, so that ambiguity has to be resolved
/// deliberately rather than left to the crossing parity.
fn on_boundary(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        let cross = (x as i64 - x1 as i64) * (y2 as i64 - y1 as i64)
            - (y as i64 - y1 as i64) * (x2 as i64 - x1 as i64);
        if cross == 0 && x >= x1.min(x2) && x <= x1.max(x2) && y >= y1.min(y2) && y <= y1.max(y2) {
            return true;
        }
    }
    false
}

/// Is the point inside the ring, counting its boundary as inside?
pub(crate) fn point_within_ring(x: i32, y: i32, ring: &[(i32, i32)]) -> bool {
    point_in_ring(x, y, ring) || on_boundary(x, y, ring)
}

/// Twice the unsigned area of the ring.
pub(crate) fn ring_area2(ring: &[(i32, i32)]) -> i64 {
    let mut sum = 0i64;
    for i in 0..ring.len() {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % ring.len()];
        sum += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    sum.abs()
}

/// Does either ring have a vertex inside the other? Cheaper than an edge-crossing test and
/// enough to spot the overlapping holes, which overlap over an area rather than just
/// grazing.
pub(crate) fn rings_overlap(a: &[(i32, i32)], b: &[(i32, i32)]) -> bool {
    a.iter().any(|&(x, y)| point_in_ring(x, y, b)) || b.iter().any(|&(x, y)| point_in_ring(x, y, a))
}

/// Vertex count of `ring` with any repeated closing vertices excluded.
pub(crate) fn open_length(ring: &[(i32, i32)]) -> usize {
    let mut length = ring.len();
    while length >= 2 && ring[0] == ring[length - 1] {
        length -= 1;
    }
    length
}
