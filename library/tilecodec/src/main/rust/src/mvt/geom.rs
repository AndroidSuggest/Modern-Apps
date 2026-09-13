use crate::proto;

// --- Geometry command integers ------------------------------------------------

pub const CMD_MOVE_TO: u32 = 1;
pub const CMD_LINE_TO: u32 = 2;
pub const CMD_CLOSE_PATH: u32 = 7;

/// Pack a command id and its repeat count into a command integer.
#[inline]
pub fn command(cmd: u32, count: u32) -> u32 {
    (cmd & 0x7) | (count << 3)
}

/// Split a command integer into `(command, count)`.
#[inline]
pub fn command_parts(v: u32) -> (u32, u32) {
    (v & 0x7, v >> 3)
}

/// Build the geometry stream for a multipoint layer: one `MoveTo` covering every
/// point, with zigzagged deltas between successive points, as the spec requires.
pub fn encode_points(points: &[(i32, i32)]) -> Vec<u32> {
    if points.is_empty() {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(1 + points.len() * 2);
    out.push(command(CMD_MOVE_TO, points.len() as u32));
    let (mut cx, mut cy) = (0i32, 0i32);
    for &(x, y) in points {
        out.push(proto::zigzag_encode((x - cx) as i64) as u32);
        out.push(proto::zigzag_encode((y - cy) as i64) as u32);
        cx = x;
        cy = y;
    }
    out
}

/// Decode a point layer's geometry stream back to absolute tile coordinates.
/// Non-point geometry yields `None` — callers that need lines or polygons should
/// pass the raw stream through instead.
pub fn decode_points(geometry: &[u32]) -> Option<Vec<(i32, i32)>> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    let mut i = 0usize;
    while i < geometry.len() {
        let (cmd, count) = command_parts(geometry[i]);
        i += 1;
        if cmd != CMD_MOVE_TO {
            return None;
        }
        for _ in 0..count {
            if i + 1 >= geometry.len() {
                return None;
            }
            cx += proto::zigzag_decode(geometry[i] as u64) as i32;
            cy += proto::zigzag_decode(geometry[i + 1] as u64) as i32;
            i += 2;
            out.push((cx, cy));
        }
    }
    Some(out)
}

/// Twice the signed area of a ring, by the surveyor's (shoelace) formula.
///
/// Doubled and kept as an integer so there is no division and no rounding: only
/// the **sign** decides winding order, and only zero decides degeneracy, so the
/// factor of two is irrelevant and dropping it would introduce a rounding step
/// into a decision that must be exact.
///
/// Per the MVT spec, a positive result means an exterior ring and a negative one
/// an interior ring. An explicit closing vertex is optional: the sum wraps from
/// the last vertex to the first either way, and a repeated vertex contributes
/// nothing.
///
/// `i64` throughout: two `i32` spans multiply to 62 bits, and a ring long enough
/// to overflow the accumulator would need more vertices than a tile can hold.
pub fn signed_area(ring: &[(i32, i32)]) -> i64 {
    let n = ring.len();
    if n < 3 {
        return 0;
    }
    let mut sum = 0i64;
    for i in 0..n {
        let (x1, y1) = ring[i];
        let (x2, y2) = ring[(i + 1) % n];
        sum += x1 as i64 * y2 as i64 - x2 as i64 * y1 as i64;
    }
    sum
}

/// Build the geometry stream for a line layer.
///
/// Each part is `MoveTo(1)` then `LineTo(n-1)`, with zigzagged deltas and the
/// cursor carried across parts, as the spec requires. Parts with fewer than two
/// distinct vertices are skipped: a `LineTo(0)` is illegal, and a lone `MoveTo`
/// would encode a point inside a line layer.
pub fn encode_lines(lines: &[Vec<(i32, i32)>]) -> Vec<u32> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    for line in lines {
        if line.len() < 2 {
            continue;
        }
        out.push(command(CMD_MOVE_TO, 1));
        push_delta(&mut out, line[0], &mut cx, &mut cy);
        out.push(command(CMD_LINE_TO, (line.len() - 1) as u32));
        for p in &line[1..] {
            push_delta(&mut out, *p, &mut cx, &mut cy);
        }
    }
    out
}

/// One polygon: its exterior ring first, then its holes.
pub type PolygonRings = Vec<Vec<(i32, i32)>>;

/// Build the geometry stream for a polygon layer.
///
/// Per polygon, the exterior ring comes first and its holes follow, each as
/// `MoveTo(1)`, `LineTo(n-1)`, `ClosePath`. Three things this function does that
/// the caller must not have to think about:
///
/// * **Closure is implicit.** `ClosePath` re-draws the edge back to the ring's
///   start, so an explicit closing vertex is stripped. Leaving it in emits a
///   zero-length segment, and some renderers treat that as a degenerate ring.
/// * **Orientation is derived, not trusted.** [`signed_area`] decides, and the ring
///   is reversed when the sign is wrong. The clipper and the simplifier upstream do
///   not preserve orientation, so the input's own winding means nothing.
/// * **Zero-area rings are dropped.** They cannot be oriented, and a hole with no
///   area is invisible at best.
///
/// A polygon whose exterior ring is dropped is dropped entirely, holes included: a
/// hole with nothing around it renders as solid fill.
pub fn encode_polygons(polygons: &[PolygonRings]) -> Vec<u32> {
    let mut out = Vec::new();
    let (mut cx, mut cy) = (0i32, 0i32);
    for rings in polygons {
        // Held back until the exterior is known good, so a dropped exterior takes
        // its holes with it instead of emitting orphans.
        let mut staged: Vec<Vec<(i32, i32)>> = Vec::with_capacity(rings.len());
        for (i, ring) in rings.iter().enumerate() {
            let mut open = ring.as_slice();
            while open.len() > 1 && open.first() == open.last() {
                open = &open[..open.len() - 1];
            }
            if open.len() < 3 {
                if i == 0 {
                    staged.clear();
                    break;
                }
                continue;
            }
            let area = signed_area(open);
            if area == 0 {
                if i == 0 {
                    staged.clear();
                    break;
                }
                continue;
            }
            // Exterior positive, interior negative, per the spec.
            let want_positive = i == 0;
            let mut ring: Vec<(i32, i32)> = open.to_vec();
            if (area > 0) != want_positive {
                ring.reverse();
            }
            staged.push(ring);
        }
        for ring in &staged {
            out.push(command(CMD_MOVE_TO, 1));
            push_delta(&mut out, ring[0], &mut cx, &mut cy);
            out.push(command(CMD_LINE_TO, (ring.len() - 1) as u32));
            for p in &ring[1..] {
                push_delta(&mut out, *p, &mut cx, &mut cy);
            }
            out.push(command(CMD_CLOSE_PATH, 1));
            // ClosePath moves the cursor back to the ring's start, so the next
            // ring's MoveTo delta is measured from there, not from the last vertex.
            cx = ring[0].0;
            cy = ring[0].1;
        }
    }
    out
}

#[inline]
pub(crate) fn push_delta(out: &mut Vec<u32>, (x, y): (i32, i32), cx: &mut i32, cy: &mut i32) {
    out.push(proto::zigzag_encode((x - *cx) as i64) as u32);
    out.push(proto::zigzag_encode((y - *cy) as i64) as u32);
    *cx = x;
    *cy = y;
}

/// Decode a line layer's geometry stream. `None` on anything that is not a
/// sequence of `MoveTo(1)` + `LineTo(n)` parts.
pub fn decode_lines(geometry: &[u32]) -> Option<Vec<Vec<(i32, i32)>>> {
    let mut out: Vec<Vec<(i32, i32)>> = Vec::new();
    let mut cursor = Cursor::new(geometry);
    while let Some((cmd, count)) = cursor.next_command() {
        match cmd {
            CMD_MOVE_TO => {
                if count != 1 {
                    return None;
                }
                out.push(vec![cursor.next_point()?]);
            }
            CMD_LINE_TO => {
                let line = out.last_mut()?;
                if count == 0 {
                    return None;
                }
                for _ in 0..count {
                    line.push(cursor.next_point()?);
                }
            }
            _ => return None,
        }
    }
    Some(out)
}

/// Decode a polygon layer's geometry stream into `[polygon][ring][vertex]`, with
/// each ring closed explicitly.
///
/// Rings are grouped into polygons by orientation, as the spec prescribes: a
/// positive-area ring starts a new polygon and negative-area rings attach to the
/// one before them. A stream that opens with a hole is rejected rather than
/// guessed at.
pub fn decode_polygons(geometry: &[u32]) -> Option<Vec<PolygonRings>> {
    let mut out: Vec<PolygonRings> = Vec::new();
    let mut current: Vec<(i32, i32)> = Vec::new();
    let mut cursor = Cursor::new(geometry);
    while let Some((cmd, count)) = cursor.next_command() {
        match cmd {
            CMD_MOVE_TO => {
                if count != 1 || !current.is_empty() {
                    return None;
                }
                current.push(cursor.next_point()?);
            }
            CMD_LINE_TO => {
                if current.is_empty() || count == 0 {
                    return None;
                }
                for _ in 0..count {
                    current.push(cursor.next_point()?);
                }
            }
            CMD_CLOSE_PATH => {
                if count != 1 || current.len() < 3 {
                    return None;
                }
                let ring = std::mem::take(&mut current);
                // ClosePath returns the cursor to the ring's start.
                cursor.set(ring[0]);
                let positive = signed_area(&ring) > 0;
                let mut closed = ring;
                closed.push(closed[0]);
                if positive {
                    out.push(vec![closed]);
                } else {
                    out.last_mut()?.push(closed);
                }
            }
            _ => return None,
        }
    }
    // An unterminated ring means a truncated stream.
    current.is_empty().then_some(out)
}

/// Walks a command stream, carrying the delta cursor.
struct Cursor<'a> {
    geometry: &'a [u32],
    i: usize,
    x: i32,
    y: i32,
}

impl<'a> Cursor<'a> {
    fn new(geometry: &'a [u32]) -> Cursor<'a> {
        Cursor { geometry, i: 0, x: 0, y: 0 }
    }

    fn next_command(&mut self) -> Option<(u32, u32)> {
        let v = *self.geometry.get(self.i)?;
        self.i += 1;
        Some(command_parts(v))
    }

    fn next_point(&mut self) -> Option<(i32, i32)> {
        let dx = *self.geometry.get(self.i)?;
        let dy = *self.geometry.get(self.i + 1)?;
        self.i += 2;
        self.x = self.x.wrapping_add(proto::zigzag_decode(dx as u64) as i32);
        self.y = self.y.wrapping_add(proto::zigzag_decode(dy as u64) as i32);
        Some((self.x, self.y))
    }

    fn set(&mut self, (x, y): (i32, i32)) {
        self.x = x;
        self.y = y;
    }
}
