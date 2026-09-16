use super::angle::turn_angle;
use super::instance::ArrowInstance;
use super::place::placed_anchor;

// This glyph and the lane-guidance glyphs in
// `library/ui/src/main/java/com/vayunmathur/library/ui/Icons.kt` (`laneVector`) are deliberate
// counterparts: the same shaft-then-bend-then-filled-head construction over the same `LANE_*`
// vocabulary, so a manoeuvre reads the same painted on the road as it does in the guidance bar.
// One is Compose vector paths and the other is Rust tessellation, so there is no code to share
// across that boundary — the two have to be changed together or the map and the bar drift apart.

/// Half-width of the arrow's shaft, in unit-arrow coordinates.
pub(crate) const SHAFT_HALF_WIDTH: f32 = 0.15;
/// The back of the shaft: the straight run `TAIL_X..BEND_START_X` lies along the lane.
pub(crate) const TAIL_X: f32 = -0.9;
/// Where the bend leaves the straight run — the counterpart of `LANE_FORK_Y` in `Icons.kt`.
const BEND_START_X: f32 = 0.0;
/// Arc length of the bend, the same whatever the angle, so every glyph carries the same amount of
/// paint and a sharper turn curls tighter instead of reaching further across the road.
const BEND_LEN: f32 = 0.55;
const HEAD_LEN: f32 = 0.45;
pub(crate) const HEAD_HALF_WIDTH: f32 = 0.4;
/// Pieces the bend is sampled into. Six is smooth at the ~9dp the arrow draws at, and is fixed
/// rather than angle-dependent so a glyph's vertex count is the constant [`ARROW_VERTS`].
const BEND_SEGMENTS: usize = 6;

/// Vertices in one arrow's triangle list, whatever angle it bends through.
///
/// One quad (six vertices) per centreline segment, plus the head's three. Constant so the renderer
/// can size a tile's whole vertex buffer up front — this used to be `UNIT_ARROW_TRIANGLES.len()`
/// back when a single straight shape was merely rotated.
pub const ARROW_VERTS: usize = 6 * (BEND_SEGMENTS + 1) + 3;

/// The glyph's centreline for a bend of `turn` radians: the tail, then the bend sampled into
/// [`BEND_SEGMENTS`] pieces, each point paired with the path's heading there.
///
/// The bend is a circular arc of fixed length [`BEND_LEN`] and therefore radius `BEND_LEN / |turn|`
/// — tangent to the lane where it starts and pointing exactly along `turn` where it ends, for any
/// `turn` at all rather than for a handful of buckets. As `turn` approaches zero the radius
/// diverges and the arc becomes the straight continuation of the shaft, which is taken directly.
fn unit_centreline(turn: f32) -> [((f32, f32), f32); BEND_SEGMENTS + 2] {
    let mut pts = [((TAIL_X, 0.0), 0.0f32); BEND_SEGMENTS + 2];
    let sweep = turn.abs();
    let side = if turn < 0.0 { -1.0 } else { 1.0 };
    // Below this an arc of this length departs from its chord by far less than a pixel, and
    // `BEND_LEN / sweep` is on its way to overflowing; draw the straight run it is indistinguishable
    // from instead.
    let straight = sweep < 1e-3;
    let radius = if straight { 0.0 } else { BEND_LEN / sweep };
    for i in 0..=BEND_SEGMENTS {
        let t = i as f32 / BEND_SEGMENTS as f32;
        let u = sweep * t;
        let point = if straight {
            (BEND_START_X + BEND_LEN * t, 0.0)
        } else {
            (
                BEND_START_X + radius * u.sin(),
                side * radius * (1.0 - u.cos()),
            )
        };
        pts[i + 1] = (point, side * u);
    }
    pts
}

/// One edge of the shaft at a centreline point, offset perpendicular to the heading there.
fn shaft_edge(((x, y), heading): ((f32, f32), f32), half_width: f32) -> (f32, f32) {
    (
        x - heading.sin() * half_width,
        y + heading.cos() * half_width,
    )
}

/// The arrow as a triangle list in unit coordinates (roughly `-1..1`), running along `+x` — the
/// direction of travel — and then bending through `turn` radians.
///
/// A left turn is therefore an L: it follows the lane and only then bends, the way a road-surface
/// marking and the navigation lane bar both draw one. It is emphatically *not* a straight arrow
/// aimed sideways across the road, which is what rotating a single fixed shape produced. `turn` is
/// continuous, so every angle a junction can present is drawable.
///
/// Returned by value as a fixed-size array rather than a `Vec`: [`arrow_verts`] calls this once per
/// arrow per frame, so it must not allocate. Vertices are `(x, y)`, three per triangle, with the
/// head last in the order base, tip, base.
pub fn unit_arrow_triangles(turn: f32) -> [(f32, f32); ARROW_VERTS] {
    let mut out = [(0.0f32, 0.0f32); ARROW_VERTS];
    let pts = unit_centreline(turn);
    // The shaft, one quad per centreline segment. Both corners of a segment's end use that point's
    // own heading, so consecutive quads share an edge exactly and the ribbon has no gap at a joint.
    let mut n = 0;
    for pair in pts.windows(2) {
        let (a, b) = (pair[0], pair[1]);
        let (al, ar) = (
            shaft_edge(a, -SHAFT_HALF_WIDTH),
            shaft_edge(a, SHAFT_HALF_WIDTH),
        );
        let (bl, br) = (
            shaft_edge(b, -SHAFT_HALF_WIDTH),
            shaft_edge(b, SHAFT_HALF_WIDTH),
        );
        out[n..n + 6].copy_from_slice(&[al, bl, br, al, br, ar]);
        n += 6;
    }
    // The head: a filled triangle off the end of the bend, aimed along where the bend left off.
    let ((ex, ey), heading) = pts[pts.len() - 1];
    let (hc, hs) = (heading.cos(), heading.sin());
    out[n] = (ex + hs * HEAD_HALF_WIDTH, ey - hc * HEAD_HALF_WIDTH);
    out[n + 1] = (ex + hc * HEAD_LEN, ey + hs * HEAD_LEN);
    out[n + 2] = (ex - hs * HEAD_HALF_WIDTH, ey + hc * HEAD_HALF_WIDTH);
    out
}

/// Transform the unit arrow into tile-local triangle vertices for one placed arrow, appending
/// `x, y` pairs (three per triangle) to `out`.
///
/// `scale` is tile-local units per unit-arrow coordinate (the arrow's screen size ÷ the tile's
/// screen span); `lateral` is the sideways lane offset in the same tile-local units, applied
/// perpendicular to the road heading so each lane's arrow sits over its lane; `local_per_metre` is
/// [`super::place::tile_local_per_metre`] for the tile, which is what turns the ground setback into this tile's
/// units. The along-road setback is applied here rather than baked into the instance so that the
/// one function the renderer calls per arrow cannot be made to skip it. The glyph is rotated by the
/// road heading alone — the manoeuvre is *built into* the shape by [`turn_angle`] rather than added
/// to the rotation. This is the exact math the renderer runs per frame, factored out so it is a
/// unit test rather than a screenshot.
pub fn arrow_verts(
    inst: &ArrowInstance,
    scale: f32,
    lateral: f32,
    local_per_metre: f32,
    out: &mut Vec<f32>,
) {
    // Perpendicular to the road heading (left is -y in tile space), for the lane offset.
    let (rc, rs) = (inst.angle.cos(), inst.angle.sin());
    let (ax, ay) = placed_anchor(inst, local_per_metre);
    let (cx, cy) = (ax - rs * lateral, ay + rc * lateral);
    for (ux, uy) in unit_arrow_triangles(turn_angle(inst)) {
        let rx = ux * rc - uy * rs;
        let ry = ux * rs + uy * rc;
        out.push(cx + rx * scale);
        out.push(cy + ry * scale);
    }
}
