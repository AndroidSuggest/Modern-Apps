//! Per-tile symbol shaping: place labels as shaped candidates.
//!
//! M1 covers _place_ labels only. A symbol layer reads point features from the v2
//! `places` layer, looks up each feature's display name in the body's name table,
//! and shapes it via `tess::text` — string → advances, zoom-independent. Quad
//! emission happens per frame in the renderer (`record_symbol`), sized by the
//! frame's `text_size` ramp value.
//!
//! # Tessellate-time vs frame-time split
//!
//! Shaping here is tile-pure and runs once on the worker thread. Emission needs
//! the frame's text size (`text_size` ramp at the camera zoom) and tile span, so
//! the renderer re-emits quads every frame from the shaped candidates. That is
//! affordable: a tile carries dozens of labels, not thousands of road vertices.
//! Placement/collision across tiles is `placement.rs` (M1b); this module shapes
//! every candidate label unclipped.

mod billboard;
mod emit;
mod shape;

#[cfg(test)]
mod tests;

#[cfg(test)]
mod tests_extra;

pub use self::billboard::{
    billboard_clip, billboard_ortho2x2, billboard_push_flag, icon_push_billboard,
};
pub use self::emit::{emit_icon, emit_label};
pub use self::shape::{shape_label, shape_line_label};

// Test-only: `rank_for_layer` and `sprite_for` are exercised directly by the
// tests, so they are imported into scope for `use super::*` in test builds only.
#[cfg(test)]
use self::shape::{rank_for_layer, sprite_for};

/// Floats per vertex for a **text** label quad: `x, y, u, v, ax, ay` — see
/// [`crate::tess::text::FLOATS_PER_VERTEX`]. The text draws through the billboard symbol pipeline, which
/// reads the per-vertex ground anchor to stay upright under tilt.
pub const FLOATS_PER_VERTEX: usize = crate::tess::text::FLOATS_PER_VERTEX;

/// Floats per vertex for a POI **icon** quad: `x, y, u, v, ax, ay` — the same layout the text
/// beside it uses, because an icon billboards the same way its label does.
///
/// An icon carries the ground anchor on every corner and draws through the billboard pipeline, so
/// under tilt it stands up to face the camera instead of foreshortening into the ground plane.
/// Sharing one mechanism with the text is deliberate rather than incidental: an icon and its label
/// hang off the same anchor in the same pass, so a second billboard implementation could disagree
/// with the first and slide the pictogram off the name it belongs to.
pub const ICON_FLOATS_PER_VERTEX: usize = crate::tess::text::FLOATS_PER_VERTEX;

/// Floats per vertex for an app **marker** quad: `x, y, u, v`.
///
/// Markers resolve their corners to clip space on the CPU and draw through the plain on-ground
/// sprite pipeline with an identity matrix, so there is no tile-local anchor for a vertex shader
/// to project and this format must not grow.
pub const MARKER_FLOATS_PER_VERTEX: usize = 4;
