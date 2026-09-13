//! One coalesced navigation step and the shared "no junction" sentinel.

/// One coalesced navigation step (pre-localization). Marshaled to Kotlin
/// `OfflineRouter.RawStep` in `lib.rs`.
pub struct StepData {
    pub name_off: u32,
    pub dist_mm: u64,
    pub time_10ms: u64,
    pub coords: Vec<f64>, // flat [lon, lat, lon, lat, ...]
    /// Ground elevation in metres for each coordinate in [`StepData::coords`], so
    /// `elevations.len() == coords.len() / 2`. Interpolated from the per-node
    /// elevation baked into the graph (WS-G): interior polyline vertices are
    /// linearly interpolated by cumulative distance between their edge's two node
    /// elevations. All zero when the graph carries no `elevation.bin`.
    pub elevations: Vec<f64>,
    pub maneuver: i32,
    pub speed_ratio: f64,
    /// Derived turn-lane guidance for this step's maneuver. Each entry is one
    /// available turn lane at the junction (ordered left→right), packed as
    /// `dir_mask * 2 + valid` where `dir_mask` is a bitmask of maneuver-enum
    /// ordinals the lane offers (a real OSM lane can allow several turns, e.g.
    /// through+right) and `valid` is 1 when that lane leads onto the taken route.
    /// Built from real OSM `turn:lanes` when present, else from junction
    /// topology. Empty when the junction has no meaningful choice (single
    /// continuation) or no node context.
    pub lanes: Vec<i32>,
}

/// Sentinel for "no junction node" in [`super::builder::StepBuilder::add_segment`].
pub(crate) const INVALID_NODE: u32 = 0xFFFF_FFFF;
