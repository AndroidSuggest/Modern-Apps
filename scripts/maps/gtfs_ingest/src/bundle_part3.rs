impl Covered {
    /// Record every metre of `line` as drawn.
    pub fn add(&mut self, line: &[(i32, i32)]) {
        self.add_tagged(line, 0);
    }

    /// Record every metre of `line` as drawn by service `tag`.
    pub fn add_tagged(&mut self, line: &[(i32, i32)], tag: u32) {
        for sample in walk(line) {
            let at = self.points.len() as u32;
            self.cells.entry(cell_of(sample.0)).or_default().push(at);
            self.points.push(sample);
            self.tags.push(tag);
        }
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// The colour-scoped gates above stop a service being drawn twice, but they are deliberately
    /// blind to *other* services: two lines sharing a track are two real services and both should
    /// draw, fanned into lanes. That holds for a city. It does not hold for a planet, where one
    /// physical alignment is republished by a city feed, the regional feed that contains it and a
    /// national feed on top, each under its own `route_color` and often its own `route_type` — all
    /// of which read as distinct services and each claim a lane. That is what turns one railway
    /// into fifteen jagged parallel lines when you zoom in.
    ///
    /// So the rule is a ceiling rather than a ban: a few services over one track is real, fifteen
    /// is a data artefact. Returns the worst point rather than an average, because a line that
    /// joins a crowded trunk for part of its length is exactly the case worth suppressing.
    ///
    /// `limit` stops the walk as soon as any point reaches it. The answer above the ceiling is
    /// never used, only compared against it, and this is called once per surviving line over a
    /// structure holding every sample of every line already drawn.
    pub fn crowd_reaches(&self, line: &[(i32, i32)], limit: usize) -> bool {
        if limit == 0 {
            return true;
        }
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in &walk(line) {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, limit);
            if seen.len() >= limit {
                return true;
            }
        }
        false
    }

    /// The most distinct services already drawn over any one metre of `line`.
    ///
    /// Unbounded, for tests and for reporting. Prefer [`crowd_reaches`](Self::crowd_reaches) on
    /// the build path, which stops as soon as the answer can no longer change the decision.
    pub fn crowd(&self, line: &[(i32, i32)]) -> usize {
        let mut worst = 0;
        let mut seen: Vec<u32> = Vec::new();
        for &(point, ux, uy) in &walk(line) {
            seen.clear();
            self.tags_over(point, ux, uy, &mut seen, usize::MAX);
            worst = worst.max(seen.len());
        }
        worst
    }

    /// Every distinct service drawn within [`CORRIDOR_M`] of this point, running parallel to it.
    ///
    /// Stops once `limit` distinct services have been found, since no caller needs more.
    fn tags_over(&self, point: (i32, i32), ux: f64, uy: f64, out: &mut Vec<u32>, limit: usize) {
        let (cx, cy) = cell_of(point);
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else { continue };
            for &i in bucket {
                let (other, oux, ouy) = self.points[i as usize];
                if (ux * oux + uy * ouy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, other) <= CORRIDOR_M {
                    let tag = self.tags[i as usize];
                    if !out.contains(&tag) {
                        out.push(tag);
                        if out.len() >= limit {
                            return;
                        }
                    }
                }
            }
        }
    }

    /// Is every metre of `line` already drawn?
    ///
    /// Walked at [`SAMPLE_M`] rather than at the line's own vertices, whose spacing is a
    /// feed's business and is sometimes hundreds of metres.
    pub fn contains(&self, line: &[(i32, i32)]) -> bool {
        self.covered_fraction(line) >= 1.0
    }

    /// How much of `line` is already drawn, from 0 to 1. A line too short to walk covers nothing,
    /// so it reports 0 and is kept — matching what the all-or-nothing test used to do with it.
    ///
    /// The all-or-nothing [`contains`](Self::contains) is too strict for one real case: a route
    /// published twice at slightly different *lengths*. A short-turn, or one feed's version
    /// running two stops further than another's, shares 95% of its alignment with what is already
    /// drawn, adds a little genuinely new track at one end, and is therefore kept **whole** —
    /// duplicating the 95%. On the ground that draws the A line as two strands about a metre
    /// apart, each fanned into its own corridor lane and tapered, so one line reads as two
    /// diverging and reconverging.
    ///
    /// Subtracting the covered part instead is not the answer and was tried: it cut every route
    /// into fragments and left holes where a piece fell below the length worth emitting. A
    /// fraction keeps the whole-line rule and only moves where the threshold sits.
    pub fn covered_fraction(&self, line: &[(i32, i32)]) -> f64 {
        let walked = walk(line);
        if walked.is_empty() {
            return 0.0;
        }
        let drawn = walked.iter().filter(|&&(point, ux, uy)| self.covers(point, ux, uy)).count();
        drawn as f64 / walked.len() as f64
    }

    /// Is this point already drawn, by track running parallel to it?
    ///
    /// Returns on the first match, so visit order matters — see [`NEIGHBOURHOOD`], which is
    /// ordered for exactly that reason. The buckets are unbounded: [`add`](Self::add) records
    /// every sample of every line it draws, and a trunk alignment republished by a dozen feeds is
    /// a dozen overlapping sample runs in the same cells.
    fn covers(&self, point: (i32, i32), ux: f64, uy: f64) -> bool {
        let (cx, cy) = cell_of(point);
        for (dx, dy) in NEIGHBOURHOOD {
            let Some(bucket) = self.cells.get(&(cx + dx, cy + dy)) else { continue };
            for &i in bucket {
                let (other, oux, ouy) = self.points[i as usize];
                if (ux * oux + uy * ouy).abs() < COS_FOLD {
                    continue;
                }
                if distance_m(point, other) <= CORRIDOR_M {
                    return true;
                }
            }
        }
        false
    }
}

/// Every candidate's samples, flat, with the range each candidate's own block occupies.
fn sample_all(candidates: &[Candidate]) -> (Vec<Sample>, Vec<Range<usize>>) {
    let mut out = Vec::new();
    let mut blocks = Vec::with_capacity(candidates.len());
    for candidate in candidates {
        let start = out.len();
        for (point, ux, uy) in walk(candidate.points) {
            out.push(Sample { route: candidate.route, point, ux, uy });
        }
        blocks.push(start..out.len());
    }
    (out, blocks)
}

type Cell = (i32, i32);

/// The cell a point falls in. Square in metres rather than in degrees, so a cell near the
/// pole is not a sliver: longitude is scaled by the cosine of its own latitude.
fn cell_of(point: (i32, i32)) -> Cell {
    let lat = point.0 as f64 * 1e-7;
    let cos_lat = lat.to_radians().cos().max(1e-6);
    let y = lat * 111_320.0 / CELL_M;
    let x = point.1 as f64 * 1e-7 * 111_320.0 * cos_lat / CELL_M;
    (x.floor() as i32, y.floor() as i32)
}

fn bucket<T: Copy>(grid: &[(Cell, T)], key: Cell) -> &[(Cell, T)] {
    let lo = grid.partition_point(|(k, _)| *k < key);
    let hi = grid.partition_point(|(k, _)| *k <= key);
    &grid[lo..hi]
}

/// A sorted flat `(cell, sample)` vector, as [`crate::index`]'s transfer grid is.
struct Grid {
    entries: Vec<(Cell, u32)>,
}

impl Grid {
    fn build(samples: &[Sample]) -> Grid {
        let mut entries: Vec<(Cell, u32)> =
            samples.iter().enumerate().map(|(i, s)| (cell_of(s.point), i as u32)).collect();
        entries.sort_unstable();
        Grid { entries }
    }

    /// The routes with a sample within [`CORRIDOR_M`] of `at` running parallel to it,
    /// ascending and deduplicated. Always contains `at`'s own route.
    ///
    /// `seen` is a caller-owned scratch array of one slot per route, holding the `stamp` of
    /// the sample a route was last accepted for. It replaces an `out.contains()` linear scan
    /// that ran once per neighbouring sample: a 40 m cell holds ~4 samples per line at
    /// [`SAMPLE_M`], so the nine-cell neighbourhood holds ~36 per route in the corridor, and
    /// scanning `out` for each made the probe quadratic in *local route density*. On a
    /// world-scale set that is the whole cost — dense metros carry the same trunk track
    /// republished by every feed covering the city, so density there is far higher than the
    /// feed count suggests, and the run time grows much faster than the input does.
    ///
    /// A route is stamped only when it is **accepted**, never when the angle or distance test
    /// rejects it. That is what the `contains` check did — it tested membership of `out`, not
    /// of everything examined — and a route rejected against one sample must stay eligible
    /// against a nearer one in the same neighbourhood.
    fn routes_near(
        &self,
        samples: &[Sample],
        at: &Sample,
        out: &mut Vec<u32>,
        seen: &mut [u32],
        stamp: u32,
    ) {
        out.clear();
        out.push(at.route);
        seen[at.route as usize] = stamp;
        let (cx, cy) = cell_of(at.point);
        for dx in -1..=1 {
            for dy in -1..=1 {
                for &(_, i) in bucket(&self.entries, (cx + dx, cy + dy)) {
                    let other = &samples[i as usize];
                    if seen[other.route as usize] == stamp {
                        continue;
                    }
                    if (at.ux * other.ux + at.uy * other.uy).abs() < COS_FOLD {
                        continue;
                    }
                    if distance_m(at.point, other.point) <= CORRIDOR_M {
                        seen[other.route as usize] = stamp;
                        out.push(other.route);
                    }
                }
            }
        }
        out.sort_unstable();
    }
}
