use super::instance::ArrowInstance;
use super::kind::TurnArrow;

/// The angle the glyph bends through for an indication, in radians, when nothing better is known.
///
/// Left turns are **negative** (tile-space `y` grows southward, so the left of an eastbound road is
/// `-y`); right turns positive. These are nominal — one representative angle per `LANE_*` class —
/// and are only the fallback: [`turn_angle`] prefers the real geometric angle wherever the lane's
/// exit heading is known, because a junction's arms meet at whatever angles the roads happen to
/// take, not at a handful of tidy multiples of thirty degrees.
///
/// Every angle here is fixed by its `LANE_*` class alone except the U-turn, which is the one
/// manoeuvre the driving convention turns around: it crosses the oncoming stream to the far kerb,
/// so it hooks left where traffic keeps right and right where traffic keeps left.
pub fn nominal_turn_angle(arrow: TurnArrow, left_hand: bool) -> f32 {
    use std::f32::consts::PI;
    match arrow {
        TurnArrow::Through => 0.0,
        TurnArrow::SlightLeft => -PI / 6.0,
        TurnArrow::Left => -PI / 2.0,
        TurnArrow::SharpLeft => -3.0 * PI / 4.0,
        TurnArrow::SlightRight => PI / 6.0,
        TurnArrow::Right => PI / 2.0,
        TurnArrow::SharpRight => 3.0 * PI / 4.0,
        TurnArrow::Reverse => {
            if left_hand {
                PI
            } else {
                -PI
            }
        }
        TurnArrow::MergeLeft => -PI / 6.0,
        TurnArrow::MergeRight => PI / 6.0,
    }
}

/// How far this arrow bends, in radians: the measured turn from the lane's approach heading to the
/// exit it leads to, or [`nominal_turn_angle`] when the archive carries no exit heading.
///
/// Wrapped to `(-pi, pi]`, so a manoeuvre is always drawn as the short way round.
pub fn turn_angle(inst: &ArrowInstance) -> f32 {
    match inst.exit_angle {
        Some(exit) => wrap_pi(exit - inst.angle),
        None => nominal_turn_angle(inst.arrow, inst.left_hand),
    }
}

/// Wrap an angle into `(-pi, pi]`.
fn wrap_pi(angle: f32) -> f32 {
    use std::f32::consts::PI;
    let wrapped = (angle + PI).rem_euclid(2.0 * PI) - PI;
    if wrapped <= -PI {
        wrapped + 2.0 * PI
    } else {
        wrapped
    }
}
