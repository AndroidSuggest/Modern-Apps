use super::Taper;

/// The width ratio at each point, inserting the vertices the ramps need as it goes.
///
/// Empty when there is no taper, which the caller reads as one everywhere rather than
/// paying a vector per part for the case that is nearly every part.
pub(crate) fn taper_widths(points: &mut Vec<i32>, taper: Taper, run: f32) -> Vec<f32> {
    if taper.is_flat() {
        return Vec::new();
    }
    let lengths = cumulative(points);
    let Some(&total) = lengths.last() else { return Vec::new() };
    // Half the part at most, so the two ramps meet in the middle rather than crossing
    // and inverting. A section shorter than two ramps tapers over all of it.
    let run = run.min(total * 0.5);
    if run <= 0.0 {
        return Vec::new();
    }

    let mut wanted: Vec<f32> = Vec::new();
    if taper.end < 1.0 {
        wanted.push(total - run);
    }
    if taper.start < 1.0 {
        wanted.push(run);
    }
    // Two ramps that meet share the vertex they meet on. Inserting one for each would
    // put two coincident points in the line, and a zero-length segment has no direction
    // — the NaN `dedupe` exists to prevent, reintroduced after it has already run.
    if wanted.len() == 2 && (wanted[0] - wanted[1]).abs() < MIN_SPLIT_GAP {
        wanted.pop();
    }
    // Furthest along first: its insertion cannot move the index the nearer one was
    // measured at, so both are computed against the original line and applied in turn.
    let splits: Vec<(usize, i32, i32)> =
        wanted.iter().filter_map(|&along| split_at(points, &lengths, along)).collect();
    for (at, x, y) in splits {
        points.splice(at * 2..at * 2, [x, y]);
    }

    // Re-measured, because rounding an inserted point to integer tile units moves it a
    // fraction off the line it was cut from and so changes the length it sits on.
    let lengths = cumulative(points);
    let total = lengths.last().copied().unwrap_or(0.0);
    lengths
        .iter()
        .map(|&along| ramp(along, run, taper.start).min(ramp(total - along, run, taper.end)))
        .collect()
}

/// How near an existing point a ramp's own vertex may be inserted, in tile units.
///
/// The inserted point is rounded to integer tile units, so nearer than this and rounding
/// could land it exactly on its neighbour, where [`super::joins::dedupe`] has already run
/// and would not drop it again; nearer than a whole unit and
/// [`super::joins::direction`] would be normalising a sub-unit segment, which the
/// connector path relies on never happening.
const MIN_SPLIT_GAP: f32 = 1.5;

/// The ratio at a point `along` past the end that ramps to `at_end`: exactly `at_end` on
/// the end itself, exactly one from `run` onwards.
fn ramp(along: f32, run: f32, at_end: f32) -> f32 {
    let fraction = (along / run).clamp(0.0, 1.0);
    at_end + (1.0 - at_end) * fraction
}

/// Where a vertex has to be inserted to put one at arc length `at`, as
/// `(index it goes before, x, y)` — or nothing when a vertex is already close enough.
///
/// "Close enough" is [`MIN_SPLIT_GAP`].
fn split_at(points: &[i32], lengths: &[f32], at: f32) -> Option<(usize, i32, i32)> {
    let after = (1..lengths.len()).find(|&i| lengths[i] >= at)?;
    let before = after - 1;
    let span = lengths[after] - lengths[before];
    if span <= 0.0 || at - lengths[before] < MIN_SPLIT_GAP || lengths[after] - at < MIN_SPLIT_GAP {
        return None;
    }
    let fraction = (at - lengths[before]) / span;
    let x = points[before * 2] as f32
        + fraction * (points[after * 2] - points[before * 2]) as f32;
    let y = points[before * 2 + 1] as f32
        + fraction * (points[after * 2 + 1] - points[before * 2 + 1]) as f32;
    Some((after, x.round() as i32, y.round() as i32))
}

/// Arc length from the first point to each point, in tile units.
fn cumulative(points: &[i32]) -> Vec<f32> {
    let n = points.len() / 2;
    let mut out = Vec::with_capacity(n);
    let mut total = 0.0;
    out.push(0.0);
    for i in 1..n {
        total += super::joins::segment_length(points, i - 1, i);
        out.push(total);
    }
    out
}
