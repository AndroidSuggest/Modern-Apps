//! Which routes share a corridor, and which parallel lane each one takes through it.
//!
//! Where several services run over one physical track — six Muni Metro lines through the
//! Market Street subway, BART and Caltrain sharing an alignment — every route's polyline
//! lies on top of every other's and only one colour is visible. The standard transit-map
//! treatment is to fan them out into parallel coloured lines, and this decides which line
//! goes where.
//!
//! # Why here and not in the tiler or the renderer
//!
//! "Which routes share this corridor" is a global property of the feed set. The tiler sees
//! features one tile at a time and the renderer sees one tile at a time in arbitrary order,
//! so either would have to decide it per tile — which is exactly what puts a jog in every
//! route at every tile seam. It is decided once, here, and travels with the feature.
//!
//! # What comes out
//!
//! An ordered list of [`Span`]s per candidate, each a polyline and the inputs to a lane
//! choice. A route that shares nothing is one span: its own geometry, ordinal zero of one.
//! A route that runs through a corridor is cut at the corridor's ends, and the part inside
//! draws the **corridor's own reference polyline** rather than its own survey. That is what
//! makes the members of a corridor exactly parallel and evenly spaced: two agencies' surveys
//! of one track differ by a metre or two, which is a large fraction of the gap between lanes.
//!
//! The lane itself is *not* decided here. A span carries the colour's ordinal among the
//! corridor's distinct colours and how many of those there are, and the renderer turns that
//! into an offset — because how many lanes a corridor draws depends on the camera zoom, which
//! nothing here has. A taper travels alongside as a 0–255 fraction of whatever that offset
//! turns out to be, so a route eases into its lane instead of stepping sideways onto it. The
//! ease is drawn on the route's *own* survey, and only where the route arrives from its own
//! alignment: between two corridors it is in a lane on both sides and steps from one to the
//! other.
//!
//! # How
//!
//! Every candidate is resampled at [`SAMPLE_M`] and its samples are dropped into a spatial
//! grid — the sorted flat `Vec<(cell, id)>` [`crate::index`] uses for footpath transfers,
//! not a `HashMap` of buckets. For each sample, the routes with a sample within
//! [`CORRIDOR_M`] *running parallel to it* are that sample's local membership.
//!
//! A candidate's samples are then cut into maximal **runs** of equal membership. Runs, not
//! one membership per route: a route takes its lane from the corridor it is in *here*, so a
//! service that shares Market Street with nine others and the Bay Bridge with four takes a
//! lane in each. Picking one membership for a whole route was the previous rule, and it is
//! what left BART's five services on `-5, -3, +3, +7, +9` across the bay — slots assigned
//! for Market Street and carried where they meant nothing.
//!
//! Membership flickers sample to sample wherever a route drifts in and out of another's
//! tolerance, so runs shorter than [`MIN_SHARED_M`] are merged into their neighbour until
//! none are left. That threshold already existed for exactly this judgement: two alignments
//! that touch for fifty metres leaving a station are not a corridor.
//!
//! Parallelism is what keeps two tracks crossing at a junction from reading as a corridor:
//! bearings are compared folded to `[0, 180)`, so opposite directions over one track count
//! as parallel and a crossing does not.
//!
//! # Which lane is which
//!
//! The order across a corridor is the order its members arrive in, so a line never crosses
//! its neighbours where corridor membership changes. Each member's side is read off the
//! stretch of its own untouched survey immediately outside the corridor mouth — its
//! **approach** — where two routes that then run over one identical alignment are still tens
//! of metres apart and the sign is unambiguous. Inside the corridor they are within a metre
//! or two of the reference and the sign is survey noise.
//!
//! Nothing propagates an order between corridors. It is read off the geometry at each one
//! independently, and abutting corridors agree because the ground does.
//!
//! Nothing here may depend on `HashMap` iteration order — the exporter's output has to be
//! byte-identical between runs — so every collection that feeds a decision is a sorted
//! vector or a `BTreeMap`.

use crate::shapes::{distance_m, project, resample};
use rayon::prelude::*;
use std::cmp::Ordering;
use std::collections::BTreeMap;
use std::ops::Range;
use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
use std::sync::Mutex;

/// Two polylines closer than this everywhere are the same line, and two tracks closer than
/// this are one corridor.
///
/// Inside the band `shapes.rs` already establishes: well above `SIMPLIFY_TOLERANCE_M`
/// (2.5 m), which is below the disagreement between two agencies' surveys of one track, and
/// well below `MAX_STOP_OFFSET_M` (150 m), which is a shape belonging to another pattern.
pub const CORRIDOR_M: f64 = 30.0;

/// How close a member's own survey has to come to the reference before it stops drawing its
/// own track and hands over to the corridor's.
///
/// The other half of the judgement [`CORRIDOR_M`] makes, which the two shared until now: 30 m
/// is "these two tracks are one corridor", and this is "they are close enough that drawing
/// one on the other's geometry cannot be seen". Two lines closing at a shallow angle become
/// one corridor while still 30 m apart, and handing over there puts the whole 30 m into a
/// single sideways step at the mouth. Above the metre or two two agencies' surveys of one
/// track disagree by, and well below [`CORRIDOR_M`].
const SNAP_M: f64 = 8.0;

/// Spacing of the probe samples along a polyline.
///
/// Small enough that two parallel tracks are compared point-to-point rather than
/// point-to-segment without the along-track offset mattering: half a step is 5 m against a
/// 30 m radius.
const SAMPLE_M: f64 = 10.0;

/// Grid cell in metres, just above the search radius so a probe never has to scan more than
/// the nine cells around it. The sizing rule [`crate::index`]'s transfer grid uses.
const CELL_M: f64 = 40.0;

/// How much track two routes have to share before it counts as a corridor.
///
/// Also the smoothing window on the runs. Membership flips sample to sample wherever a
/// route grazes another's tolerance, and cutting on every flip would shatter a route into
/// dozens of features; a run shorter than this is absorbed into its neighbour instead.
/// Three hundred metres is well above a station throat and well below any shared trunk
/// worth drawing.
const MIN_SHARED_M: f64 = 300.0;

/// Cosine of the angle within which two bearings count as parallel (25°).
///
/// Compared on `|u · v|`, so the fold to `[0, 180)` is free: two routes over one track
/// stored in opposite directions are parallel, two tracks crossing at a junction are not.
const COS_FOLD: f64 = 0.906_307_787;

/// The shortest ease a member spends coming into its lane, and the whole of it where the
/// member is already on the reference when its run begins.
///
/// Without it a route steps sideways by up to nine Dp at the corridor mouth, which reads as
/// a break in the line rather than as a fan opening. Where the member converges slowly the
/// ease is longer than this: it runs from the mouth to wherever the survey first comes within
/// [`SNAP_M`].
const TAPER_M: f64 = 100.0;

/// Features in one taper. The taper travels as a 0–255 fraction of the full lane offset, so
/// the step count is only how many pieces the ease is cut into — and, because the fraction is
/// an equal-step ramp, how big the jump between two of them is. Eight puts every jump at
/// 255/9, about 1 Dp at a 9 Dp offset.
const TAPER_STEPS: usize = 8;

/// How much of a member's own survey, immediately outside the corridor mouth, decides which
/// side of the corridor it takes.
///
/// Across the shared stretch two routes on one track sit within a couple of metres of the
/// reference and the sign of that is survey noise. On the approach they are still diverging,
/// and the sign is what separates "came from the north" from "came from the south". Above
/// [`TAPER_M`] so it clears the ease-in, and below [`MIN_SHARED_M`] so it cannot reach back
/// into the body of the corridor before.
const APPROACH_M: f64 = 150.0;

/// Points sampled along the approach. The mean of a handful, because one point lands wherever
/// the survey's own vertices happen to fall.
const APPROACH_SAMPLES: usize = 8;

/// One route's polyline, offered for slotting.
pub struct Candidate<'a> {
    pub points: &'a [(i32, i32)],
    /// Which route this polyline belongs to. Two polylines of one route — a branch and its
    /// trunk — count as one member of a corridor, because the lane is per route and not per
    /// line.
    pub route: u32,
    /// `0xRRGGBB`. The lane is per colour, and the colour is the tie-break on the order
    /// within a corridor when two surveys genuinely coincide.
    pub color: u32,
    /// The route's display name. The second half of that tie-break, so a rebuild puts the
    /// same route in the same lane.
    pub name: &'a str,
}

/// One stretch of a candidate as it should be drawn.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Span {
    /// The polyline to emit. The candidate's own geometry outside a corridor and across the
    /// ease into one, and the corridor's reference geometry over the body.
    pub points: Vec<(i32, i32)>,
    /// This colour's index among the corridor's distinct colours, from zero. Zero outside a
    /// corridor.
    ///
    /// The order is **positional**: the colours are laid out across the corridor in the order
    /// their routes arrive at it, so two groups that merge do not interleave and nothing
    /// crosses. Zero is the left-hand side of the reference's direction of travel.
    ///
    /// The index is over the distinct **colours** of the corridor, not its routes. Two routes
    /// of one colour are indistinguishable once drawn, so giving them a lane each spends width
    /// on a difference nobody can see — and it is what doubles a network whose feed publishes
    /// each direction as its own route (BART's `Yellow-N` and `Yellow-S`) or splits one
    /// service into branch variants. Sharing a lane makes them coincide exactly, because they
    /// are also drawing the same reference geometry.
    pub ordinal: u8,
    /// How many distinct colours the corridor carries. One outside a corridor.
    ///
    /// Unclamped: how many lanes to actually draw is the renderer's decision, because it
    /// depends on the camera zoom and the exporter has no zoom.
    pub lanes: u8,
    /// How far into its lane this piece sits, over 255. 255 outside a corridor and on a
    /// corridor's body; less only across a taper at its mouth.
    pub taper: u8,
}

/// Cut every candidate into spans and give each one its lane. One list out per candidate in,
/// in the same order, never empty.
pub fn assign(candidates: &[Candidate]) -> Vec<Vec<Span>> {
    if candidates.is_empty() {
        return Vec::new();
    }
    let route_count = candidates.iter().map(|c| c.route as usize + 1).max().unwrap_or(0);
    let (samples, blocks) = sample_all(candidates);
    let grid = Grid::build(&samples);

    // Local membership per sample, interned: distinct sets are few even on a world feed set,
    // and holding one `Vec<u32>` per sample is what would not fit.
    let mut sets: Vec<Vec<u32>> = Vec::new();
    let mut set_ids: BTreeMap<Vec<u32>, u32> = BTreeMap::new();
    let mut per_sample: Vec<u32> = Vec::with_capacity(samples.len());

    // The probe is the whole cost of a large run — one per 10 m of every line, 287 million of
    // them on a world feed set — and it ran on one core for the better part of an hour.
    //
    // It splits in two. Finding which routes are near a sample is a pure read of the grid, so
    // that part goes wide: the samples are cut into one contiguous range per core and each
    // thread writes its answers into its own flat buffer. Interning those answers into set ids
    // cannot go wide, because an id is assigned on first sight and the ids have to come out in
    // sample order or every downstream lane moves. So the threads hand back their buffers in
    // range order and the interning walks them sequentially — cheap, since it is `BTreeMap`
    // lookups over an answer already computed.
    //
    // Flat `(values, ends)` buffers rather than `Vec<Vec<u32>>`: 287 million heap allocations
    // is its own kind of slow, and the whole point of interning is not to hold that many
    // vectors at once.
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let span = samples.len().div_ceil(threads.max(1)).max(1);
    let probed = AtomicUsize::new(0);
    let parts: Mutex<Vec<(usize, Vec<u32>, Vec<u32>)>> = Mutex::new(Vec::new());
    {
        let grid = &grid;
        let samples = &samples;
        let parts = &parts;
        let probed = &probed;
        let total = samples.len();
        std::thread::scope(|scope| {
            for t in 0..threads {
                let lo = t * span;
                if lo >= total {
                    break;
                }
                let hi = ((t + 1) * span).min(total);
                scope.spawn(move || {
                    // Per thread, so the stamp trick in `routes_near` needs no sharing: a
                    // stamp only has to be unique against this thread's own array.
                    let mut seen: Vec<u32> = vec![0; route_count];
                    let mut stamp: u32 = 0;
                    let mut near: Vec<u32> = Vec::new();
                    let mut values: Vec<u32> = Vec::new();
                    let mut ends: Vec<u32> = Vec::with_capacity(hi - lo);
                    for (i, sample) in samples[lo..hi].iter().enumerate() {
                        stamp += 1;
                        grid.routes_near(samples, sample, &mut near, &mut seen, stamp);
                        values.extend_from_slice(&near);
                        ends.push(values.len() as u32);
                        // Batched, because an atomic per probe would cost more than the probe.
                        if i % 65_536 == 0 {
                            let n = probed.fetch_add(65_536, AtomicOrdering::Relaxed) + 65_536;
                            eprint!(
                                "\r{:<28} [{:>3}%] {total} sample(s)",
                                "Corridor probe",
                                (n * 100 / total.max(1)).min(100)
                            );
                        }
                    }
                    parts.lock().expect("probe pool").push((t, values, ends));
                });
            }
        });
    }
    eprintln!("\r{:<28} [100%] {} sample(s)", "Corridor probe", samples.len());

    // Back in sample order, then interned sequentially: first-seen order is what fixes the ids.
    let mut parts = parts.into_inner().expect("probe pool");
    parts.sort_unstable_by_key(|(t, _, _)| *t);
    for (_, values, ends) in &parts {
        let mut start = 0usize;
        for &end in ends {
            let near = &values[start..end as usize];
            start = end as usize;
            let id = match set_ids.get(near) {
                Some(id) => *id,
                None => {
                    let id = sets.len() as u32;
                    sets.push(near.to_vec());
                    set_ids.insert(near.to_vec(), id);
                    id
                }
            };
            per_sample.push(id);
        }
    }
    drop(parts);

    // Distance along each candidate, for its own geometry and for its probes. `resample`
    // walks *along* the polyline, so probe `k` is exactly `k * SAMPLE_M` along it and the
    // last one is its end. Summing the chords between probes instead would fall short of
    // that wherever the alignment curves, and the drift accumulates over a route's length
    // until the last run is cut short of the line's own end.
    let own_cum: Vec<Vec<f64>> = candidates.iter().map(|c| cumulative(c.points)).collect();
    let probe_cum: Vec<Vec<f64>> = blocks
        .iter()
        .zip(&own_cum)
        .map(|(block, own)| {
            let total = own.last().copied().unwrap_or(0.0);
            let count = block.len();
            (0..count)
                .map(|k| if k + 1 == count { total } else { (k as f64 * SAMPLE_M).min(total) })
                .collect()
        })
        .collect();

    let runs: Vec<Vec<Run>> = blocks
        .iter()
        .zip(&probe_cum)
        .map(|(block, cum)| cut_runs(&per_sample[block.clone()], cum))
        .collect();

    let mut named: Vec<Option<(u32, &str)>> = vec![None; route_count];
    for candidate in candidates {
        named[candidate.route as usize].get_or_insert((candidate.color, candidate.name));
    }

    // Named too: on a large set the work after the probe loop is still minutes, and without a
    // line here the run goes silent again the moment the bar reaches 100%.
    eprintln!("{:<28} {} candidate(s)", "Corridor spans", candidates.len());
    let corridors = corridors_of(candidates, &sets, &runs, &named, &own_cum, &probe_cum);

    if std::env::var_os("TRANSIT_BUNDLE_DEBUG").is_some() {
        for (set, corridor) in &corridors {
            let members = &sets[*set as usize];
            eprintln!(
                "corridor of {} over {} colour(s): {}",
                members.len(),
                corridor.colours.len(),
                members
                    .iter()
                    .map(|&m| {
                        let (colour, name) = named[m as usize].unwrap_or((0, ""));
                        format!("{name}/{colour:06X}")
                    })
                    .collect::<Vec<String>>()
                    .join(" "),
            );
        }
    }

    // The last phase, and per candidate independent: each one cuts its own runs against
    // corridors it only reads. Left sequential it was the tail that made a world set look
    // hung again after the corridor bar hit 100%.
    //
    // Handed out one candidate at a time rather than in equal contiguous ranges, because the
    // cost per candidate is wildly uneven — a transcontinental line carries orders of magnitude
    // more runs than a tram loop. Splitting the range evenly gave whichever thread drew the
    // long-distance rail all the work and left the rest idle: a world set sat at 98% on one
    // core for ten minutes with sixty-three threads finished. Sample probes are uniform enough
    // for a range split; candidates are not.
    let total = candidates.len();
    // Largest-first dispatch (longest-processing-time scheduling): hand out the heaviest
    // candidates first so the one transcontinental line overlaps with all the small work
    // instead of being picked last and grinding alone on one core at the end (the 99%
    // straggler). Cost is proxied by run count -- the same "orders of magnitude more runs"
    // the note above names. Output is unaffected: each result carries its candidate index and
    // the parts are re-sorted by it below, so dispatch order never reaches the archive.
    let order: Vec<usize> = {
        let mut o: Vec<usize> = (0..total).collect();
        o.sort_unstable_by_key(|&i| std::cmp::Reverse(runs[i].len()));
        o
    };
    let cut_next = AtomicUsize::new(0);
    let cut_done = AtomicUsize::new(0);
    let cut_parts: Mutex<Vec<(usize, Vec<Span>)>> = Mutex::new(Vec::with_capacity(total));
    {
        let corridors = &corridors;
        let cut_parts = &cut_parts;
        let cut_done = &cut_done;
        let cut_next = &cut_next;
        let samples = &samples;
        let runs = &runs;
        let blocks = &blocks;
        let probe_cum = &probe_cum;
        let own_cum = &own_cum;
        let order = &order;
        std::thread::scope(|scope| {
            for _ in 0..threads.min(total.max(1)) {
                scope.spawn(move || {
                    let mut local: Vec<(usize, Vec<Span>)> = Vec::new();
                    loop {
                        let slot = cut_next.fetch_add(1, AtomicOrdering::Relaxed);
                        if slot >= total {
                            break;
                        }
                        let at = order[slot];
                        local.push((
                            at,
                            spans_of(
                                &candidates[at],
                                &runs[at],
                                &samples[blocks[at].clone()],
                                &probe_cum[at],
                                &own_cum[at],
                                corridors,
                            ),
                        ));
                        let n = cut_done.fetch_add(1, AtomicOrdering::Relaxed) + 1;
                        if n % 64 == 0 {
                            eprint!(
                                "\r{:<28} [{:>3}%] {total} line(s)",
                                "Corridor cut",
                                n * 100 / total.max(1)
                            );
                        }
                    }
                    cut_parts.lock().expect("cut pool").extend(local);
                });
            }
        });
    }
    eprintln!("\r{:<28} [100%] {total} line(s)", "Corridor cut");
    let mut cut_parts = cut_parts.into_inner().expect("cut pool");
    cut_parts.sort_unstable_by_key(|(at, _)| *at);
    cut_parts.into_iter().map(|(_, spans)| spans).collect()
}

/// A maximal stretch of one candidate's samples holding the same membership, as inclusive
/// indices into that candidate's own block of samples.
struct Run {
    from: usize,
    to: usize,
    set: u32,
}

/// A shared corridor: the geometry every member of it draws, and the lane order.
struct Corridor {
    /// One member's polyline, folded into the canonical direction, which the whole corridor
    /// draws slices of. Owned rather than borrowed because the fold may reverse it.
    points: Vec<(i32, i32)>,
    cum: Vec<f64>,
    /// The corridor's distinct colours, ordered across it: the first is the left-hand side of
    /// the reference's direction of travel, which is the side [`Span::ordinal`] zero draws on.
    colours: Vec<u32>,
}

/// Cut a candidate's samples into runs of constant membership, then smooth away the ones too
/// short to be a corridor.
fn cut_runs(per_sample: &[u32], cum: &[f64]) -> Vec<Run> {
    let Some(&first) = per_sample.first() else { return Vec::new() };
    let mut runs = vec![Run { from: 0, to: 0, set: first }];
    for (at, &set) in per_sample.iter().enumerate().skip(1) {
        let last = runs.last_mut().expect("seeded above");
        if last.set == set {
            last.to = at;
        } else {
            runs.push(Run { from: at, to: at, set });
        }
    }
    smooth(&mut runs, cum);
    runs
}

include!("bundle_part1.rs");
include!("bundle_part2.rs");
include!("bundle_part3.rs");
include!("bundle_part4.rs");
include!("bundle_part5.rs");