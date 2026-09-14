//! Probe sampling and the spatial grid for corridor grouping, ported from
//! `scripts/maps/gtfs_ingest/src/bundle_part3.rs` (`sample_all`, `Grid`,
//! `routes_near`) and the cell helpers.
//!
//! Split from `corridor_group` along module lines (the `rustFileLength`
//! gate): sampling + lookup live here, runs + corridors + spans stay there.

use super::corridor_geom::{distance_m, walk_directed};
use super::corridor_group::{Candidate, CORRIDOR_M, COS_FOLD, SAMPLE_M};
use std::ops::Range;

/// One probe point along a candidate, with the unit direction of travel.
pub struct Sample {
    /// Owning route id.
    pub route: u32,
    /// e7 point.
    pub point: (i32, i32),
    /// Unit tangent x (east).
    pub ux: f64,
    /// Unit tangent y (north).
    pub uy: f64,
}

/// Every candidate's samples, flat, with the range each candidate's own
/// block occupies.
pub fn sample_all(candidates: &[Candidate]) -> (Vec<Sample>, Vec<Range<usize>>) {
    let mut out = Vec::new();
    let mut blocks = Vec::with_capacity(candidates.len());
    for candidate in candidates {
        let start = out.len();
        for dp in walk_directed(candidate.points, SAMPLE_M) {
            out.push(Sample {
                route: candidate.route,
                point: dp.point,
                ux: dp.ux,
                uy: dp.uy,
            });
        }
        blocks.push(start..out.len());
    }
    (out, blocks)
}

type Cell = (i32, i32);

/// Grid cell in metres, just above the search radius so a probe never has to
/// scan more than the nine cells around it.
const CELL_M: f64 = 40.0;

/// The cell a point falls in. Square in metres rather than in degrees, so a
/// cell near the pole is not a sliver.
fn cell_of(point: (i32, i32)) -> Cell {
    let lat = f64::from(point.0) * 1e-7;
    let cos_lat = lat.to_radians().cos().max(1e-6);
    let y = lat * 111_320.0 / CELL_M;
    let x = f64::from(point.1) * 1e-7 * 111_320.0 * cos_lat / CELL_M;
    (x.floor() as i32, y.floor() as i32)
}

/// A sorted flat `(cell, sample)` vector grid.
pub struct Grid {
    entries: Vec<(Cell, u32)>,
}

impl Grid {
    /// Build the grid over `samples`.
    pub fn build(samples: &[Sample]) -> Grid {
        let mut entries: Vec<(Cell, u32)> = samples
            .iter()
            .enumerate()
            .map(|(i, s)| (cell_of(s.point), i as u32))
            .collect();
        entries.sort_unstable();
        Grid { entries }
    }

    /// Routes with a sample within [`CORRIDOR_M`] of `at` running parallel
    /// to it, ascending and deduplicated. Always contains `at`'s own route.
    ///
    /// `seen` is a caller-owned scratch array of one slot per route holding
    /// the stamp of the sample a route was last accepted for; a route
    /// rejected against one sample stays eligible against a nearer one.
    pub fn routes_near(
        &self,
        samples: &[Sample],
        at: &Sample,
        out: &mut Vec<u32>,
        seen: &mut [u32],
        stamp: u32,
    ) {
        out.clear();
        out.push(at.route);
        if let Some(slot) = seen.get_mut(at.route as usize) {
            *slot = stamp;
        }
        let (cx, cy) = cell_of(at.point);
        for dx in -1..=1 {
            for dy in -1..=1 {
                let key = (cx + dx, cy + dy);
                let lo = self.entries.partition_point(|(k, _)| *k < key);
                let hi = self.entries.partition_point(|(k, _)| *k <= key);
                for &(_, i) in &self.entries[lo..hi] {
                    let Some(other) = samples.get(i as usize) else {
                        continue;
                    };
                    let Some(slot) = seen.get_mut(other.route as usize) else {
                        continue;
                    };
                    if *slot == stamp {
                        continue;
                    }
                    if (at.ux * other.ux + at.uy * other.uy).abs() < COS_FOLD {
                        continue;
                    }
                    if distance_m(at.point, other.point) <= CORRIDOR_M {
                        *slot = stamp;
                        out.push(other.route);
                    }
                }
            }
        }
        out.sort_unstable();
    }
}
