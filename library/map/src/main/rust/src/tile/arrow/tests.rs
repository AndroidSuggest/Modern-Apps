use super::glyph::{HEAD_HALF_WIDTH, SHAFT_HALF_WIDTH, TAIL_X};
use super::*;
use tilecodec::mamaps::body::{
    LaneTurns, LANE_LEFT, LANE_REVERSE, LANE_RIGHT, LANE_SLIGHT_LEFT, LANE_THROUGH,
};

/// The glyph rule is total and prefers a through movement, then the committing turn over its
/// slight variant. A lane with no indication draws nothing.
#[test]
fn the_arrow_rule_prefers_through_then_the_sharper_turn() {
    assert_eq!(arrow_for(LANE_THROUGH), Some(TurnArrow::Through));
    assert_eq!(arrow_for(LANE_LEFT), Some(TurnArrow::Left));
    // `through;right` is a through arrow: that is the default movement.
    assert_eq!(
        arrow_for(LANE_THROUGH | LANE_RIGHT),
        Some(TurnArrow::Through)
    );
    // A committing turn beats its slight variant when both are somehow set.
    assert_eq!(
        arrow_for(LANE_LEFT | LANE_SLIGHT_LEFT),
        Some(TurnArrow::Left)
    );
    assert_eq!(arrow_for(LANE_REVERSE), Some(TurnArrow::Reverse));
    assert_eq!(
        arrow_for(tilecodec::mamaps::body::LANE_NONE),
        None,
        "no indication, no arrow"
    );
    assert_eq!(arrow_for(0), None, "an empty mask draws nothing");
}

/// Forward lanes anchor near the way's end heading toward it, one arrow per marked lane, in
/// left-to-right ordinal order; a lane with no indication is skipped but does not shift the
/// others' ordinals.
#[test]
fn forward_arrows_sit_at_the_end_and_point_along_the_road() {
    // A straight eastbound road: heading is 0 radians, end at x=100.
    let line = [(0.0, 0.0), (50.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![
            LANE_LEFT,
            tilecodec::mamaps::body::LANE_NONE,
            LANE_THROUGH | LANE_RIGHT,
        ],
        backward: vec![],
    };
    let arrows = place_arrows(&line, &turns, (3, 0), false);
    assert_eq!(arrows.len(), 2, "the unmarked middle lane draws nothing");
    assert!(
        arrows.iter().all(|a| a.angle.abs() < 1e-6),
        "eastbound heading is 0"
    );
    assert!(
        arrows.iter().all(|a| a.count == 3),
        "the count is the whole lane set"
    );
    assert_eq!((arrows[0].ordinal, arrows[0].arrow), (0, TurnArrow::Left));
    assert_eq!(
        (arrows[1].ordinal, arrows[1].arrow),
        (2, TurnArrow::Through),
        "ordinal kept"
    );
    // The instance carries the junction end itself; the ground setback moves the drawn anchor
    // back off it.
    assert_eq!(arrows[0].junction_end, (100.0, 0.0));
    let per_m = tile_local_per_metre(14, 6331);
    let drawn = placed_anchor(&arrows[0], per_m);
    assert!(drawn.0 < 100.0, "drawn back from the junction, not on it");
}

/// Backward lanes anchor at the other end and point the other way.
#[test]
fn backward_arrows_point_down_the_way_from_its_start() {
    let line = [(0.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![],
        backward: vec![LANE_THROUGH],
    };
    let arrows = place_arrows(&line, &turns, (0, 1), false);
    assert_eq!(arrows.len(), 1);
    // Heading toward the start of an eastbound way is due west: pi radians.
    assert!((arrows[0].angle.abs() - std::f32::consts::PI).abs() < 1e-6);
    assert_eq!(arrows[0].junction_end, (0.0, 0.0));
    assert!(placed_anchor(&arrows[0], tile_local_per_metre(14, 6331)).0 > 0.0);
}

/// A line too short to have a heading draws nothing rather than an arrow pointing nowhere.
#[test]
fn a_degenerate_line_places_no_arrows() {
    let turns = LaneTurns {
        forward: vec![LANE_THROUGH],
        backward: vec![],
    };
    assert!(place_arrows(&[(1.0, 1.0)], &turns, (1, 0), false).is_empty());
    assert!(place_arrows(&[], &turns, (1, 0), false).is_empty());
    // Two coincident points have no direction.
    assert!(place_arrows(&[(5.0, 5.0), (5.0, 5.0)], &turns, (1, 0), false).is_empty());
}

/// The glyph runs *along* the lane before it bends, which is what makes a left turn read as a
/// left turn rather than as a straight arrow aimed across the road.
#[test]
fn the_glyph_runs_along_the_lane_and_then_bends() {
    use std::f32::consts::PI;
    // A through arrow is straight along +x with its tip at the far end and no sideways reach.
    let straight = unit_arrow_triangles(0.0);
    assert_eq!(
        straight.len() % 3,
        0,
        "a triangle list is a multiple of three vertices"
    );
    let max_x = straight.iter().fold(f32::MIN, |m, &(x, _)| m.max(x));
    assert!((max_x - 1.0).abs() < 1e-5, "the tip is at +x");
    assert!(
        straight
            .iter()
            .all(|&(_, y)| y.abs() < HEAD_HALF_WIDTH + 1e-5),
        "and it is straight"
    );

    // A left turn is an L. Its tail still lies on the lane axis pointing down the lane, and
    // only the far end has swung out to -y; a rotated straight arrow would instead have swung
    // its tail out to +y and crossed the road.
    let left = unit_arrow_triangles(-PI / 2.0);
    let tail_x = left.iter().fold(f32::MAX, |m, &(x, _)| m.min(x));
    assert!(
        (tail_x - TAIL_X).abs() < 1e-5,
        "the shaft starts behind the anchor, on the axis"
    );
    let tail_y = left
        .iter()
        .filter(|&&(x, _)| (x - TAIL_X).abs() < 1e-5)
        .fold(0.0f32, |m, &(_, y)| m.max(y.abs()));
    assert!(
        tail_y <= SHAFT_HALF_WIDTH + 1e-5,
        "the tail sits on the lane it leaves"
    );
    let tip = left[ARROW_VERTS - 2];
    assert!(tip.0 > 0.0, "the arrow advances down the lane first");
    assert!(
        tip.1 < -0.5,
        "and only then swings its head out to the left"
    );
}

/// The bend is the angle it is given, whatever that angle is — the whole point of measuring the
/// junction rather than bucketing it.
#[test]
fn the_bend_takes_any_angle_a_junction_happens_to_have() {
    for &turn in &[0.0f32, 0.07, -0.41, 0.83, -1.27, 2.6, -3.0] {
        let glyph = unit_arrow_triangles(turn);
        assert_eq!(
            glyph.len(),
            ARROW_VERTS,
            "the vertex count does not vary with the angle"
        );
        let (base_a, tip, base_b) = (
            glyph[ARROW_VERTS - 3],
            glyph[ARROW_VERTS - 2],
            glyph[ARROW_VERTS - 1],
        );
        let mid = ((base_a.0 + base_b.0) / 2.0, (base_a.1 + base_b.1) / 2.0);
        let aimed = (tip.1 - mid.1).atan2(tip.0 - mid.0);
        assert!(
            (aimed - turn).abs() < 1e-4,
            "asked for {turn}, head aims along {aimed}"
        );
    }
}

/// And it tracks that angle continuously: two junctions a hair apart draw glyphs a hair apart,
/// which a bucketed angle could not do.
#[test]
fn a_slightly_different_junction_draws_a_slightly_different_arrow() {
    let near = unit_arrow_triangles(0.50);
    let nudged = unit_arrow_triangles(0.51);
    let far = unit_arrow_triangles(0.60);
    let spread = |a: &[(f32, f32); ARROW_VERTS], b: &[(f32, f32); ARROW_VERTS]| {
        a.iter()
            .zip(b)
            .map(|(p, q)| (p.0 - q.0).hypot(p.1 - q.1))
            .fold(0.0f32, f32::max)
    };
    assert!(
        spread(&near, &nudged) > 1e-4,
        "the glyph tracks the angle, it does not snap"
    );
    assert!(
        spread(&near, &nudged) < spread(&near, &far),
        "and tracks it proportionately"
    );
}

/// A measured exit heading beats the nominal class angle, and the turn is always the short way
/// round.
#[test]
fn the_measured_exit_beats_the_nominal_angle_and_wraps_the_short_way() {
    let inst = ArrowInstance {
        junction_end: (0.5, 0.5),
        angle: 0.0,
        run: 0.0,
        ordinal: 0,
        count: 1,
        arrow: TurnArrow::Right,
        exit_angle: None,
        fan_offset: 0.0,
        left_hand: false,
    };
    let nominal = nominal_turn_angle(TurnArrow::Right, false);
    assert!(
        (turn_angle(&inst) - nominal).abs() < 1e-6,
        "no exit heading, fall back to class"
    );
    // An exit 22 degrees off the approach bends 22 degrees, not the 90 its class nominates.
    let measured = ArrowInstance {
        exit_angle: Some(0.384),
        ..inst
    };
    assert!((turn_angle(&measured) - 0.384).abs() < 1e-5);
    // Approach and exit either side of the wrap point: the turn is 0.28 radians, not -6.
    let wrapped = ArrowInstance {
        angle: 3.0,
        exit_angle: Some(-3.0),
        ..inst
    };
    let short_way = 2.0 * std::f32::consts::PI - 6.0;
    assert!(
        (turn_angle(&wrapped) - short_way).abs() < 1e-5,
        "got {}",
        turn_angle(&wrapped)
    );
}

/// The nominal angles keep the left-negative / right-positive sign convention the placement
/// geometry and the renderer are both written against.
#[test]
fn the_nominal_turn_angles_have_the_right_sign() {
    let nominal = |arrow| nominal_turn_angle(arrow, false);
    assert!(nominal(TurnArrow::Through).abs() < 1e-6);
    assert!(nominal(TurnArrow::Left) < 0.0, "left is a negative bend");
    assert!(nominal(TurnArrow::Right) > 0.0, "right is a positive bend");
    let (left, sharp) = (TurnArrow::Left, TurnArrow::SharpLeft);
    assert!(nominal(sharp) < nominal(left), "sharper bends further");
    let slight = TurnArrow::SlightLeft;
    assert!(nominal(slight) > nominal(left), "slighter bends less");
}

/// The U-turn is the one nominal angle the driving convention turns around: it is made across
/// the oncoming stream, so it hooks left where traffic keeps right and right where it keeps
/// left. Every other class is the same bend under either convention.
///
/// Checked as a half turn and a sign rather than against `±PI` exactly, and as an exact
/// negation of each other rather than by comparing two independently computed floats.
#[test]
fn a_u_turn_hooks_toward_the_kerb_traffic_drives_on() {
    use std::f32::consts::PI;
    let right_hand = nominal_turn_angle(TurnArrow::Reverse, false);
    let left_hand = nominal_turn_angle(TurnArrow::Reverse, true);
    assert!(
        (right_hand.abs() - PI).abs() < 1e-6,
        "a U-turn is a half turn either way"
    );
    assert!(
        (left_hand.abs() - PI).abs() < 1e-6,
        "a U-turn is a half turn either way"
    );
    assert!(right_hand < 0.0, "hooks left where traffic keeps right");
    assert!(left_hand > 0.0, "and right where traffic keeps left");
    assert!(
        (right_hand + left_hand).abs() < 1e-6,
        "the two are the same turn mirrored"
    );
    // And it is the only one that moves: every other class ignores the convention.
    for arrow in [
        TurnArrow::Through,
        TurnArrow::SlightLeft,
        TurnArrow::Left,
        TurnArrow::SharpLeft,
        TurnArrow::SlightRight,
        TurnArrow::Right,
        TurnArrow::SharpRight,
        TurnArrow::MergeLeft,
        TurnArrow::MergeRight,
    ] {
        let (r, l) = (
            nominal_turn_angle(arrow, false),
            nominal_turn_angle(arrow, true),
        );
        assert_eq!(r, l, "{arrow:?} is not a convention-dependent manoeuvre");
    }
}

/// And the convention reaches the glyph: a U-turn placed on a left-hand-traffic road bends the
/// other way from the same lane on a right-hand one.
#[test]
fn the_tiles_convention_reaches_the_u_turn_glyph() {
    let line = [(0.0, 0.0), (100.0, 0.0)];
    let turns = LaneTurns {
        forward: vec![LANE_REVERSE],
        backward: vec![],
    };
    let bend = |left_hand| {
        let arrows = place_arrows(&line, &turns, (1, 0), left_hand);
        assert_eq!(arrows.len(), 1);
        turn_angle(&arrows[0])
    };
    assert!(
        bend(false) < 0.0,
        "right-hand traffic hooks its U-turn left"
    );
    assert!(bend(true) > 0.0, "left-hand traffic hooks it right");
    // A measured exit heading still wins: the convention is only the fallback's business.
    let mut arrows = place_arrows(&line, &turns, (1, 0), true);
    arrows[0].exit_angle = Some(-0.5);
    assert!(
        turn_angle(&arrows[0]) < 0.0,
        "a known exit beats the nominal U-turn"
    );
}
