//! The scan helpers behind [`crate::tile::taper`]'s transition map, moved wholesale
//! out of `taper.rs` to keep that file under the `rustFileLength` limit.
//!
//! No logic changes: [`part_ends`] and [`transitions`] are called from
//! [`Nodes::scan`](crate::tile::taper::Nodes::scan) exactly as before.

use std::collections::HashMap;

/// How straight two ends have to run into each other to be one road continuing.
///
/// The dot product of the two outward directions, which is `-1` for a perfectly straight
/// through-run. `-0.8` admits about 37 degrees of bend — enough for a lane drop on a
/// curve, far short of a side street.
pub(crate) const STRAIGHT_ENOUGH: f32 = -0.8;

/// One end of one carriageway part, as the scan sees it.
#[derive(Debug, Clone, Copy)]
pub(crate) struct End {
    pub(crate) point: (i32, i32),
    /// The direction the road leaves this end in — *away* from its own geometry. Two
    /// ends that continue each other therefore point opposite ways.
    pub(crate) away: (f32, f32),
    pub(crate) lanes: u8,
}

/// The two ends of one part, or nothing when it has no two distinct points to take a
/// direction from.
///
/// Takes the tile's own `(i16, i16)` points rather than the flat form the tessellator
/// uses, so scanning a tile costs no allocation per part.
pub(crate) fn part_ends(points: &[(i16, i16)], lanes: u8) -> Vec<End> {
    let n = points.len();
    if n < 2 {
        return Vec::new();
    }
    let at = |i: usize| (i32::from(points[i].0), i32::from(points[i].1));
    let first = at(0);
    let last = at(n - 1);
    // The nearest point that is not coincident, so a repeated coordinate — which the
    // simplifier does leave behind — does not produce a zero direction.
    let after_first = (1..n).map(at).find(|&p| p != first);
    let before_last = (0..n - 1).rev().map(at).find(|&p| p != last);
    match (after_first, before_last) {
        (Some(second), Some(penultimate)) => vec![
            End {
                point: first,
                away: direction(second, first),
                lanes,
            },
            End {
                point: last,
                away: direction(penultimate, last),
                lanes,
            },
        ],
        _ => Vec::new(),
    }
}

/// The unit direction from `from` to `to`, or zero when they coincide — which
/// [`part_ends`] has already excluded, so a zero here simply never pairs.
fn direction(from: (i32, i32), to: (i32, i32)) -> (f32, f32) {
    let (dx, dy) = ((to.0 - from.0) as f32, (to.1 - from.1) as f32);
    let length = (dx * dx + dy * dy).sqrt();
    if length > 0.0 {
        (dx / length, dy / length)
    } else {
        (0.0, 0.0)
    }
}

/// Reduce a tile's part ends to the points where the carriageway changes width.
///
/// At each point the straightest *pair* of ends is the road continuing through it; every
/// other end there belongs to something joining, not continuing. Taking the single
/// global minimum makes the pair mutual for free — the two ends of the straightest pair
/// are each other's straightest — which is what stops two sections deriving different
/// partners and so different widths.
pub(crate) fn transitions(ends: &[End]) -> HashMap<(i32, i32), u8> {
    let mut at_point: HashMap<(i32, i32), Vec<usize>> = HashMap::new();
    for (i, end) in ends.iter().enumerate() {
        at_point.entry(end.point).or_default().push(i);
    }

    let mut out = HashMap::new();
    for (point, here) in at_point {
        if here.len() < 2 {
            continue;
        }
        let mut best: Option<(f32, u8, u8)> = None;
        for (rank, &a) in here.iter().enumerate() {
            for &b in &here[rank + 1..] {
                let (one, two) = (ends[a], ends[b]);
                let dot = one.away.0 * two.away.0 + one.away.1 * two.away.1;
                if dot > STRAIGHT_ENOUGH {
                    continue;
                }
                if best.is_none_or(|(previous, _, _)| dot < previous) {
                    best = Some((dot, one.lanes, two.lanes));
                }
            }
        }
        // Equal counts are the same road merely cut in two — there is nothing to ramp
        // between, and recording it would make every part of every road taper to itself.
        if let Some((_, one, two)) = best {
            if one != two {
                out.insert(point, one.min(two));
            }
        }
    }
    out
}
