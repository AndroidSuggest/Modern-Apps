//! Coalesced step accumulation while walking the reconstructed path.
use crate::geometry::*;
use crate::graph::*;
use super::lanes::{junction_lanes, real_lanes};
use super::model::{INVALID_NODE, StepData};

/// Accumulates coalesced steps while walking the reconstructed path.
pub(crate) struct StepBuilder<'a> {
    pub(crate) g: &'a Graph,
    pub(crate) traffic: &'a TrafficSpeeds,
    pub(crate) mode: i32,
    pub(crate) steps: Vec<StepData>,
    pub(crate) last_bearing: f64,
    /// Junction node for the next segment's maneuver (or [`INVALID_NODE`]).
    pub(crate) pending_junction: u32,
    /// Node we arrive from at that junction, to exclude the U-turn lane.
    pub(crate) pending_prev: u32,
    /// Edge the driver is on approaching that junction, source of real OSM
    /// turn:lanes ([`INVALID_EDGE`] when unknown / at the first maneuver).
    pub(crate) pending_approach: u64,
}

impl<'a> StepBuilder<'a> {
    #[allow(clippy::too_many_arguments)]
    pub(crate) fn add_segment(
        &mut self,
        lat1: f64,
        lon1: f64,
        lat2: f64,
        lon2: f64,
        name_off: u32,
        type_: u8,
        limit: u8,
        dist_mm: u32,
        edge_idx: u64,
        elev1: f64,
        elev2: f64,
    ) {
        // Junction context for lane derivation is set on the builder before the
        // first segment of a main-path edge and consumed (then cleared) here.
        let junction_node = self.pending_junction;
        let prev_node = self.pending_prev;
        let approach_edge = self.pending_approach;
        self.pending_junction = INVALID_NODE;
        self.pending_prev = INVALID_NODE;
        self.pending_approach = INVALID_EDGE;
        let mut ratio = 1.0;
        if edge_idx != INVALID_EDGE {
            let traffic_speed = *self.traffic.get(&edge_idx).unwrap_or(&0);
            if traffic_speed > 0 && limit > 0 {
                ratio = traffic_speed as f64 / limit as f64;
            }
        }

        let time_10ms =
            get_edge_time_10ms(self.g, self.traffic, edge_idx, dist_mm, type_, limit, self.mode);
        let bearing = get_bearing(
            (lat1 * 1e7) as i32,
            (lon1 * 1e7) as i32,
            (lat2 * 1e7) as i32,
            (lon2 * 1e7) as i32,
        );

        let ratio_cat = |r: f64| -> i32 {
            if r < 0.5 {
                0
            } else if r < 0.9 {
                1
            } else {
                2
            }
        };

        let maneuver = if self.steps.is_empty() {
            0
        } else {
            get_maneuver(self.last_bearing, bearing)
        };

        let push_new = {
            match self.steps.last() {
                None => true,
                Some(back) => {
                    name_off != back.name_off
                        || ratio_cat(ratio) != ratio_cat(back.speed_ratio)
                        || (maneuver != 9 && maneuver != 0)
                }
            }
        };
        if push_new {
            let lanes = if junction_node != INVALID_NODE {
                // Prefer real OSM turn:lanes from the approach edge; fall back to
                // topology inference at the junction when the edge has no tags.
                real_lanes(self.g, approach_edge, maneuver).unwrap_or_else(|| {
                    junction_lanes(self.g, junction_node, prev_node, self.last_bearing, maneuver)
                })
            } else {
                Vec::new()
            };
            self.steps.push(StepData {
                name_off,
                dist_mm: 0,
                time_10ms: 0,
                coords: vec![lon1, lat1],
                elevations: vec![elev1],
                maneuver,
                speed_ratio: ratio,
                lanes,
            });
        }

        let back = self.steps.last_mut().unwrap();
        back.dist_mm += dist_mm as u64;
        back.time_10ms += time_10ms as u64;
        back.coords.push(lon2);
        back.coords.push(lat2);
        back.elevations.push(elev2);
        self.last_bearing = bearing;
    }
}
