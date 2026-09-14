#[allow(clippy::too_many_arguments)]
fn push_box(
    out: &mut Vec<(u64, u64)>,
    ax: f64,
    ay: f64,
    bx: f64,
    by: f64,
    z: u8,
    extent: u32,
    pad: f64,
) {
    if !ax.is_finite() || !ay.is_finite() || !bx.is_finite() || !by.is_finite() {
        return;
    }
    let b = Rect {
        min_x: ax.min(bx),
        min_y: ay.min(by),
        max_x: ax.max(bx),
        max_y: ay.max(by),
    };
    if let Some(r) = tile_range(&b, z, extent, pad) {
        out.extend(r.iter());
    }
}

/// The tiles a world-space bounding box touches at zoom `z`, grown by `pad`
/// world units so a feature just outside a tile still reaches into its buffer.
///
/// Returns `None` when the box lies entirely off the grid. It is otherwise
/// clamped to the grid: a geometry straddling the antimeridian is truncated
/// rather than wrapped, which is what the published archives do and what the
/// renderer expects.
pub fn tile_range(b: &Rect, z: u8, extent: u32, pad: f64) -> Option<TileRange> {
    let n = 1u64 << z;
    let e = extent as f64;
    let last = (n - 1) as f64;

    let fx0 = ((b.min_x - pad) / e).floor();
    let fy0 = ((b.min_y - pad) / e).floor();
    let fx1 = ((b.max_x + pad) / e).floor();
    let fy1 = ((b.max_y + pad) / e).floor();
    if !fx0.is_finite() || !fy0.is_finite() || !fx1.is_finite() || !fy1.is_finite() {
        return None;
    }
    // Entirely off the grid on either axis: no tile can hold it.
    if fx1 < 0.0 || fy1 < 0.0 || fx0 > last || fy0 > last {
        return None;
    }
    Some(TileRange {
        x0: fx0.max(0.0) as u64,
        y0: fy0.max(0.0) as u64,
        x1: fx1.min(last) as u64,
        y1: fy1.min(last) as u64,
    })
}

/// One tile's clip rect in **world** coordinates, grown by `buffer`.
pub fn tile_rect(tx: u64, ty: u64, extent: u32, buffer: f64) -> Rect {
    let e = extent as f64;
    let (ox, oy) = (tx as f64 * e, ty as f64 * e);
    Rect {
        min_x: ox - buffer,
        min_y: oy - buffer,
        max_x: ox + e + buffer,
        max_y: oy + e + buffer,
    }
}

/// Shift a geometry by `(dx, dy)`. Applied after the clip, with the tile's world
/// origin negated, to put the geometry in tile coordinates.
pub fn translate<V: Vertex>(g: &Geometry<V>, dx: f64, dy: f64) -> Geometry<V> {
    let t = |v: &V| {
        let (x, y) = v.xy();
        v.moved((x + dx, y + dy))
    };
    match g {
        Geometry::Points(pts) => Geometry::Points(pts.iter().map(t).collect()),
        Geometry::Lines(lines) => {
            Geometry::Lines(lines.iter().map(|l| l.iter().map(t).collect()).collect())
        }
        Geometry::Polygons(polys) => Geometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| r.iter().map(t).collect()).collect())
                .collect(),
        ),
    }
}

/// Convenience for the two steps that always follow a clip: translate into the
/// tile, then round onto the integer grid.
pub fn to_tile<V: Vertex>(g: &Geometry<V>, tx: u64, ty: u64, extent: u32) -> IntGeometry {
    let e = extent as f64;
    quantize(&translate(g, -(tx as f64 * e), -(ty as f64 * e)))
}

/// Round onto the integer tile grid, dropping consecutive duplicates.
///
/// Quantisation is what creates duplicates: two vertices a thousandth of a unit
/// apart become the same integer, and an MVT `LineTo` with a zero delta is a
/// wasted three bytes that some renderers treat as a degenerate segment. Rings
/// keep their explicit closing vertex here; [`crate::mvt::encode_polygons`]
/// strips it, since `ClosePath` implies it.
pub fn quantize<V: Vertex>(g: &Geometry<V>) -> IntGeometry {
    match g {
        Geometry::Points(pts) => IntGeometry::Points(pts.iter().map(round_pt).collect()),
        Geometry::Lines(lines) => {
            IntGeometry::Lines(lines.iter().map(|l| dedup(l.iter().map(round_pt))).collect())
        }
        Geometry::Polygons(polys) => IntGeometry::Polygons(
            polys
                .iter()
                .map(|rings| rings.iter().map(|r| dedup(r.iter().map(round_pt))).collect())
                .collect(),
        ),
    }
}

fn round_pt<V: Vertex>(v: &V) -> IPt {
    let (x, y) = v.xy();
    (round_i32(x), round_i32(y))
}

/// Round to the nearest integer, saturating rather than wrapping.
///
/// Quantisation runs after the clip, so a coordinate here is always within a
/// buffered tile and nowhere near the `i32` limits. If one is not, `as i32` on a
/// `NaN` is silently `0` and on a huge float is silently the saturated bound --
/// both plausible-looking coordinates. Saturating deliberately keeps the bogus
/// value bogus, so it shows up as an obviously wrong vertex rather than a subtly
/// wrong one.
fn round_i32(v: f64) -> i32 {
    if v.is_nan() {
        return 0;
    }
    v.round().clamp(i32::MIN as f64, i32::MAX as f64) as i32
}

fn dedup(it: impl Iterator<Item = IPt>) -> Vec<IPt> {
    let mut out: Vec<IPt> = Vec::new();
    for p in it {
        if out.last() != Some(&p) {
            out.push(p);
        }
    }
    out
}
