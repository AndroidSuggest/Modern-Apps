//! Route elevation: per-point interpolation and ascent/descent totals.
use crate::geometry::*;
use crate::graph::*;
use super::model::StepData;

/// Cumulative ascent and descent (metres) over a sequence of per-coordinate
/// elevations: ascent sums the positive steps, descent the magnitude of the
/// negative ones. Pure, so the route-profile arithmetic is testable without a
/// graph.
pub fn ascent_descent(elevs: &[f64]) -> (f64, f64) {
    let mut asc = 0.0;
    let mut desc = 0.0;
    for w in elevs.windows(2) {
        let d = w[1] - w[0];
        if d > 0.0 {
            asc += d;
        } else {
            desc -= d;
        }
    }
    (asc, desc)
}

/// Total cumulative ascent/descent (metres) of a whole route. Consecutive steps
/// share their join coordinate, so each step after the first contributes its
/// elevations minus that first (duplicate) point, giving one continuous profile.
pub fn route_ascent_descent(steps: &[StepData]) -> (f64, f64) {
    let mut all: Vec<f64> = Vec::new();
    for s in steps {
        if all.is_empty() {
            all.extend_from_slice(&s.elevations);
        } else if s.elevations.len() > 1 {
            all.extend_from_slice(&s.elevations[1..]);
        }
    }
    ascent_descent(&all)
}

/// Linear interpolation of an elevation for every entry of `cum` (a monotonic
/// non-decreasing cumulative-distance array), ramping from `e0` at distance 0 to
/// `e1` at the final distance. A zero-length span reads `e0` throughout. Pure.
fn interp_by_cumdist(cum: &[f64], e0: f64, e1: f64) -> Vec<f64> {
    let total = cum.last().copied().unwrap_or(0.0);
    cum.iter()
        .map(|&c| if total > 0.0 { e0 + (e1 - e0) * c / total } else { e0 })
        .collect()
}

/// Per-point elevations (metres) for `pts` in traversal order, linearly
/// interpolated by cumulative ground distance between the source elevation `e_src`
/// (at `pts[0]`) and the target elevation `e_dst` (at the last point). Interior
/// polyline vertices have no baked elevation of their own, so they ride the ramp
/// between the two junction nodes the edge connects.
pub(crate) fn edge_point_elevations(g: &Graph, pts: &[LatLon], e_src: f64, e_dst: f64) -> Vec<f64> {
    let n = pts.len();
    if n == 0 {
        return Vec::new();
    }
    if n == 1 {
        return vec![e_src];
    }
    let mut cum = vec![0f64; n];
    for i in 1..n {
        let d = fast_dist_mm(g, pts[i - 1].lat_e7, pts[i - 1].lon_e7, pts[i].lat_e7, pts[i].lon_e7);
        cum[i] = cum[i - 1] + f64::from(d);
    }
    interp_by_cumdist(&cum, e_src, e_dst)
}

/// The elevation (metres) of a projection sitting `dist_a_mm` along an edge whose
/// two endpoint nodes are at `e_a` and `e_b`, `dist_b_mm` being the remaining
/// distance to the far node. A degenerate zero-length edge reads `e_a`.
pub(crate) fn proj_elevation(g: &Graph, node_a: u32, node_b: u32, dist_a_mm: u32, dist_b_mm: u32) -> f64 {
    let e_a = f64::from(g.node_elevation(node_a));
    let e_b = f64::from(g.node_elevation(node_b));
    let total = f64::from(dist_a_mm) + f64::from(dist_b_mm);
    if total > 0.0 {
        e_a + (e_b - e_a) * f64::from(dist_a_mm) / total
    } else {
        e_a
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn step(elevations: Vec<f64>) -> StepData {
        StepData {
            name_off: NO_NAME,
            dist_mm: 0,
            time_10ms: 0,
            coords: elevations.iter().flat_map(|_| [0.0, 0.0]).collect(),
            elevations,
            maneuver: 0,
            speed_ratio: 1.0,
            lanes: Vec::new(),
        }
    }

    #[test]
    fn ascent_and_descent_sum_the_signed_steps() {
        // A climb to 10, back to 5, up to 20: ascent 10 + 15 = 25, descent 5.
        let (asc, desc) = ascent_descent(&[0.0, 10.0, 5.0, 20.0]);
        assert_eq!(asc, 25.0);
        assert_eq!(desc, 5.0);
        // A flat profile has neither.
        assert_eq!(ascent_descent(&[7.0, 7.0, 7.0]), (0.0, 0.0));
        // Too short to have any step.
        assert_eq!(ascent_descent(&[3.0]), (0.0, 0.0));
    }

    #[test]
    fn a_route_over_synthetic_terrain_has_the_expected_profile_and_cumulative_ascent() {
        // Two coalesced steps that share their join coordinate (elevation 5). The route profile is
        // the concatenation minus that duplicate: [0, 10, 5, 20, 15].
        let steps = vec![step(vec![0.0, 10.0, 5.0]), step(vec![5.0, 20.0, 15.0])];
        let (asc, desc) = route_ascent_descent(&steps);
        // Climbs: 0->10 (+10) and 5->20 (+15) = 25. Drops: 10->5 (-5) and 20->15 (-5) = 10.
        assert_eq!(asc, 25.0, "cumulative ascent");
        assert_eq!(desc, 10.0, "cumulative descent");
    }

    #[test]
    fn a_single_climbing_edge_interpolates_monotonically() {
        // Interior vertices ride the ramp between the two node elevations by cumulative distance.
        let elevs = interp_by_cumdist(&[0.0, 25.0, 50.0, 100.0], 0.0, 200.0);
        assert_eq!(elevs, vec![0.0, 50.0, 100.0, 200.0]);
        assert!(elevs.windows(2).all(|w| w[1] >= w[0]), "a climb is monotonic non-decreasing");
    }

    #[test]
    fn a_zero_length_span_reads_the_source_elevation() {
        // A degenerate edge (both endpoints coincident) has no ramp; every point reads e0.
        assert_eq!(interp_by_cumdist(&[0.0, 0.0, 0.0], 42.0, 99.0), vec![42.0, 42.0, 42.0]);
    }
}
