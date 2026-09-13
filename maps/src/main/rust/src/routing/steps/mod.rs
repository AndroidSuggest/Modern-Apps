//! Navigation steps, path reconstruction, elevation, and lane guidance.
//!
//! Split into [`model`] ([`StepData`]), [`elevation`] (ascent/descent and
//! per-point interpolation), [`lanes`] (turn-lane guidance), [`builder`]
//! (coalesced step accumulation), and [`reconstruct`] (path reconstruction).
//! Pure moves; this module only declares the submodules and re-exports the
//! public surface.

mod builder;
mod elevation;
mod lanes;
mod model;
mod reconstruct;

pub use model::StepData;
pub use elevation::{ascent_descent, route_ascent_descent};
pub(crate) use elevation::{edge_point_elevations, proj_elevation};
pub use reconstruct::reconstruct_path;
