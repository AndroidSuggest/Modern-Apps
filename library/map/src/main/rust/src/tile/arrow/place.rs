use super::instance::ArrowInstance;
use super::kind::arrow_for;
use tilecodec::mamaps::body::LaneTurns;

/// How far back from the junction the arrows sit, in **ground metres**.
///
/// A distance on the ground, not a fraction of anything and not a count of pixels: a painted turn
/// arrow sits a fixed distance behind the stop line whatever the zoom, and whatever length the
/// archive's simplifier happened to leave the way's last segment. The rule this replaced was 15% of
/// that final segment, so a way split a few metres short of its junction node — which is most of
/// them — set its arrow back well under a metre and drew it inside the intersection.
///
/// **This number has to clear the intersection box, and how big that box is is decided in a crate
/// this one cannot see.** `scripts/maps/mamaps_build/src/schema/junction.rs` carries the build
/// side's own setback, its nominal guess at an intersection's half-width, and that crate is a
/// detached-workspace host tool: it depends on `tilecodec` and so does this crate, but neither
/// depends on the other, so there is no import that could make the two one definition. They are
/// coupled by this paragraph and by nothing else. Sharing them means lifting the junction box size
/// into `tilecodec`, which both sides already depend on, and reading it from both — a change to
/// `tilecodec` and to the build side, neither of which is this file.
const SETBACK_M: f32 = 20.0;

/// How far the straight run may stray from the approach's ray, as a fraction of the distance
/// walked back along it.
///
/// Measured cumulatively from the junction end rather than segment by segment: the archive
/// quantises vertices onto the tile grid, so a short segment's heading is mostly quantisation
/// noise, while the drift of a vertex from the ray is not.
const STRAIGHT_RUN_DRIFT: f32 = 0.1;

/// The fraction of a road's whole length either direction's setback may use.
///
/// Under a half so the two directions cannot meet in the middle of a short way, which is the case
/// that draws them as one shaft with a head at each end.
const RUN_FRACTION: f32 = 0.45;

/// Tile-local units — the tile's 0..1 square — per ground metre at tile `(z, y)`'s own latitude.
///
/// The tile square is a *Web Mercator* square and Mercator's scale carries a `1 / cos φ` term, so a
/// fixed number of tile-local units is a different ground distance at every latitude: what is 20 m
/// at the equator is 15.9 m at 38°N and 10 m at 60°N. Converting through the tile's own latitude is
/// what makes [`SETBACK_M`] the same distance on the ground everywhere, which is the whole reason
/// for stating it in metres.
///
/// Taken at the tile's centre row. A tile spans a few hundred metres wherever arrows draw, over
/// which `cos φ` moves by well under a percent.
pub fn tile_local_per_metre(z: u8, y: u32) -> f32 {
    /// The equator in metres: one whole turn of Mercator x.
    const EQUATOR_M: f64 = 40_075_016.686;
    let tiles = (1u64 << z.min(31)) as f64;
    let n = std::f64::consts::PI * (1.0 - 2.0 * (f64::from(y) + 0.5) / tiles);
    let lat = n.sinh().atan();
    (tiles / (EQUATOR_M * lat.cos())) as f32
}

/// Where this arrow's glyph sits: [`SETBACK_M`] metres back along the approach from the road's
/// junction end, or as far as the approach runs straight, whichever is nearer.
///
/// `local_per_metre` is [`tile_local_per_metre`] for the tile the arrow was built in. It is the
/// caller's to supply because [`place_arrows`] is handed a polyline and nothing that identifies the
/// tile it came from, and the renderer holds the tile.
pub fn placed_anchor(inst: &ArrowInstance, local_per_metre: f32) -> (f32, f32) {
    let back = (SETBACK_M * local_per_metre).min(inst.run).max(0.0);
    (inst.junction_end.0 - inst.angle.cos() * back, inst.junction_end.1 - inst.angle.sin() * back)
}

/// How far the approach runs straight back from its junction end, in tile-local units.
///
/// The setback travels in a straight line along the approach heading, so it may only travel as far
/// as the road keeps going that way. Walked back vertex by vertex from the end, taking each
/// vertex's distance *along* the heading while its distance *across* the heading stays within
/// [`STRAIGHT_RUN_DRIFT`] of that — so a road that curves away from its junction shortens its own
/// setback rather than throwing the arrow off the carriageway. Capped at [`RUN_FRACTION`] of the
/// whole line.
///
/// `unit` is the direction of travel at the end, normalised; `backward` picks which end.
fn straight_run(line: &[(f32, f32)], backward: bool, unit: (f32, f32)) -> f32 {
    let total: f32 = line.windows(2).map(|p| (p[1].0 - p[0].0).hypot(p[1].1 - p[0].1)).sum();
    let tip = if backward { line[0] } else { line[line.len() - 1] };
    let mut run = 0.0f32;
    for i in 1..line.len() {
        let p = if backward { line[i] } else { line[line.len() - 1 - i] };
        let (vx, vy) = (tip.0 - p.0, tip.1 - p.1);
        let along = vx * unit.0 + vy * unit.1;
        let across = (vx * unit.1 - vy * unit.0).abs();
        // `along <= run` also stops a polyline that doubles back on itself.
        if along <= run || across > STRAIGHT_RUN_DRIFT * along {
            break;
        }
        run = along;
    }
    run.min(total * RUN_FRACTION)
}

/// Place one arrow per lane for a road's turn masks, in tile-local coordinates.
///
/// `line` is the road's centreline (tile-local 0..1). Forward lanes are placed at the **last**
/// point (the way's junction end) heading toward it; backward lanes at the **first** point heading
/// toward it (i.e. back down the way). A lane whose mask has no indication draws nothing. The
/// `ordinal` runs left to right in the direction of travel, matching the mask order the archive
/// stores.
///
/// The instances carry the junction end itself, not the glyph's position: the setback that puts the
/// arrow back on the approach is [`SETBACK_M`] ground metres, and nothing here knows the tile's
/// zoom or latitude to convert that into tile-local units. What this pass does contribute is
/// [`straight_run`], the distance the setback may travel before it would leave the road; the two
/// meet in [`placed_anchor`].
///
/// `lanes_each_way` is `(forward, backward)` — how the road's lanes divide between the two
/// directions, the **same pair the centre line is placed from**, so the arrows and the marking
/// cannot land on different boundaries. With `left_hand` it decides which half of the carriageway
/// each direction's fan sits on, via [`fan_offset`]. `(0, 0)` means nothing is known about the
/// road's shape, which keeps every fan centred.
///
/// Returns an empty vec for a line too short to have a heading, so a degenerate clip draws nothing
/// rather than an arrow pointing nowhere.
pub fn place_arrows(
    line: &[(f32, f32)],
    turns: &LaneTurns,
    lanes_each_way: (u8, u8),
    left_hand: bool,
) -> Vec<ArrowInstance> {
    let mut out = Vec::new();
    if line.len() < 2 {
        return out;
    }
    let (forward, backward) = lanes_each_way;
    let total = forward.saturating_add(backward);
    place_dir(line, &turns.forward, false, forward, total, left_hand, &mut out);
    place_dir(line, &turns.backward, true, backward, total, left_hand, &mut out);
    out
}

/// How far this direction's fan sits from the road's centreline, in lane widths, positive to the
/// right of the direction of travel.
///
/// A direction's lanes occupy one *half* of the carriageway, not the middle of it: under right-hand
/// traffic they are the rightmost `own_lanes` of the road's `total_lanes` and under left-hand
/// traffic the leftmost. [`place_arrows`] builds the fan centred on the road's centreline, so this
/// is the shift that moves it onto that half — half the lanes the other direction takes, signed by
/// the convention. Without it every arrow on a two-way road is drawn a full half-carriageway into
/// the oncoming lanes, in either convention.
///
/// Zero, and so no shift at all, whenever the direction *is* the whole road: a one-way carries
/// every lane, and a road whose shape is unknown has no trustworthy total to measure a half from.
/// Both keep the centred fan, which is where every arrow sat before the convention reached this
/// pass.
pub fn fan_offset(own_lanes: u8, total_lanes: u8, left_hand: bool) -> f32 {
    let spare = f32::from(total_lanes.saturating_sub(own_lanes)) / 2.0;
    if left_hand {
        -spare
    } else {
        spare
    }
}

/// Where this arrow's lane sits across the road, in lane widths from the centreline, positive to
/// the right of the direction of travel.
///
/// The fan — `ordinal + 0.5 - count / 2` — spreads a direction's lanes about its own centre, and
/// [`fan_offset`] moves that centre onto the half of the carriageway the direction occupies.
/// Multiplying by one lane's width in device pixels is the whole of the renderer's lateral
/// placement, which is why it lives here as a pure function rather than in the Android-only pass.
pub fn lane_centre(inst: &ArrowInstance) -> f32 {
    let count = f32::from(inst.count.max(1));
    f32::from(inst.ordinal) + 0.5 - count / 2.0 + inst.fan_offset
}

/// One direction's arrows. `backward` reverses which end and which way the heading points.
///
/// `own_lanes` is how many lanes this direction has per the road's split and `total_lanes` the
/// whole road; both are widened to cover the masks actually present, because an arrow placed past
/// the edge of the carriageway is worse than one placed on a road we have miscounted.
fn place_dir(
    line: &[(f32, f32)],
    masks: &[u16],
    backward: bool,
    own_lanes: u8,
    total_lanes: u8,
    left_hand: bool,
    out: &mut Vec<ArrowInstance>,
) {
    if masks.is_empty() {
        return;
    }
    // The junction node and the point just before it along the direction of travel.
    let (tip, prev) = if backward {
        (line[0], line[1])
    } else {
        (line[line.len() - 1], line[line.len() - 2])
    };
    let (dx, dy) = (tip.0 - prev.0, tip.1 - prev.1);
    if dx == 0.0 && dy == 0.0 {
        return;
    }
    let angle = dy.atan2(dx);
    let len = dx.hypot(dy);
    let run = straight_run(line, backward, (dx / len, dy / len));
    let marked = masks.len().min(u8::MAX as usize) as u8;
    let count = own_lanes.max(marked);
    let fan_offset = fan_offset(count, total_lanes.max(count), left_hand);
    for (i, &mask) in masks.iter().enumerate().take(u8::MAX as usize) {
        if let Some(arrow) = arrow_for(mask) {
            let ordinal = i as u8;
            out.push(ArrowInstance {
                junction_end: tip,
                angle,
                run,
                ordinal,
                count,
                arrow,
                exit_angle: None,
                fan_offset,
                left_hand,
            });
        }
    }
}
