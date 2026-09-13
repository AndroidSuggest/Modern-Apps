use tilecodec::mamaps::body::{
    LANE_LEFT, LANE_MERGE_TO_LEFT, LANE_MERGE_TO_RIGHT, LANE_REVERSE, LANE_RIGHT,
    LANE_SHARP_LEFT, LANE_SHARP_RIGHT, LANE_SLIGHT_LEFT, LANE_SLIGHT_RIGHT, LANE_THROUGH,
};

/// The arrow a single lane draws, chosen from its `turn:lanes` indication set.
///
/// A lane may carry several indications at once (`through;right` is common), so the mask is not one
/// of these — [`arrow_for`] reduces the set to the single glyph to draw. `Reverse` is a U-turn;
/// `MergeLeft`/`MergeRight` are the tapering-lane markers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnArrow {
    Through,
    SlightLeft,
    Left,
    SharpLeft,
    SlightRight,
    Right,
    SharpRight,
    Reverse,
    MergeLeft,
    MergeRight,
}

/// The arrow to draw for one lane's `LANE_*` mask, or `None` when the lane has no indication
/// (`LANE_NONE`, or an empty/unknown mask).
///
/// A lane often carries several indications; a single arrow has to be picked. The rule, highest
/// priority first, is **straightest-and-least-surprising wins**: a lane you can go straight through
/// is drawn as a through arrow even if it also allows a turn, because that is the movement a driver
/// defaults to; failing that the sharper, more committing turns are preferred over their slight
/// variants so the arrow does not under-state the manoeuvre; merges and U-turns come last as they
/// are the rarest and never co-occur with a through in real data. The ranking is total, so the
/// choice is deterministic for any mask.
pub fn arrow_for(mask: u16) -> Option<TurnArrow> {
    // In priority order; the first bit present wins.
    const RANK: &[(u16, TurnArrow)] = &[
        (LANE_THROUGH, TurnArrow::Through),
        (LANE_LEFT, TurnArrow::Left),
        (LANE_RIGHT, TurnArrow::Right),
        (LANE_SHARP_LEFT, TurnArrow::SharpLeft),
        (LANE_SHARP_RIGHT, TurnArrow::SharpRight),
        (LANE_SLIGHT_LEFT, TurnArrow::SlightLeft),
        (LANE_SLIGHT_RIGHT, TurnArrow::SlightRight),
        (LANE_MERGE_TO_LEFT, TurnArrow::MergeLeft),
        (LANE_MERGE_TO_RIGHT, TurnArrow::MergeRight),
        (LANE_REVERSE, TurnArrow::Reverse),
    ];
    RANK.iter().find(|(bit, _)| mask & bit != 0).map(|(_, arrow)| *arrow)
}
