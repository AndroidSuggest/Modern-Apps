//! Per-lane turn arrows: turning an OSM `turn:lanes` mask into a drawable arrow, and placing one
//! arrow per lane at a road's junction end.
//!
//! The data half of this lives in the archive (`tilecodec::mamaps::body::LaneTurns`, one `LANE_*`
//! mask per lane, forward and backward); this is the render half. It is deliberately split into two
//! pure, testable pieces — [`arrow_for`] (which glyph a mask draws) and [`place_arrows`] (where the
//! arrows sit and which way they point) — so the choice of glyph and the placement geometry are
//! unit tests rather than screenshots. The GPU draw that turns an [`ArrowInstance`] into pixels is
//! the renderer's job.

pub mod angle;
pub mod glyph;
pub mod instance;
pub mod kind;
pub mod place;
#[cfg(test)]
mod tests;
#[cfg(test)]
mod tests_part2;
pub use angle::{nominal_turn_angle, turn_angle};
pub use glyph::{arrow_verts, unit_arrow_triangles, ARROW_VERTS};
pub use instance::ArrowInstance;
pub use kind::{arrow_for, TurnArrow};
pub use place::{fan_offset, lane_centre, place_arrows, placed_anchor, tile_local_per_metre};
