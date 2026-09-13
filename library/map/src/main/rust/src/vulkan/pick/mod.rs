//! Offscreen id-buffer picking: which feature is under a tap.
//!
//! # Why a GPU id buffer rather than a CPU hit-test
//!
//! The pins moved into the renderer as billboarded sprites (see [`crate::marker`]), so the CPU no
//! longer holds their screen boxes — and under tilt a screen box is not a simple projection of a
//! lon/lat anyway. Picking therefore mirrors what the renderer already draws: render every
//! pickable feature's **id** to an offscreen `R32_UINT` image with the same matrices the visible
//! frame uses, then read back the single tapped pixel. Overlapping features resolve by draw order
//! for free — the last one drawn to that pixel is the one on top — which is the same rule the
//! visible frame resolves them by.
//!
//! This is the offscreen sibling of the region mask ([`crate::vulkan::renderer`]'s
//! `record_region_mask`): a second attachment written in its own pass and consumed by the host,
//! not shown. It is invoked out of band from [`Renderer::pick_at`](crate::vulkan::renderer) on a
//! tap, not every frame, so a one-shot submit with a `queue_wait_idle` (as the atlas upload does)
//! is the whole of its synchronisation — no per-frame cost and nothing added to the hot loop.
//!
//! # Slots, not raw ids
//!
//! An `R32_UINT` pixel holds 32 bits, while a feature id is a `u64`, so the pass writes a **1-based
//! per-pick slot** (0 = nothing) and the caller maps the slot back to the feature. That also keeps
//! the mechanism uniform across feature kinds: today markers, and any of labels/POI/regions the
//! renderer later chooses to add carry their own slot the same way.

mod pass;
mod pipeline;
mod target;

pub use pass::Pick;
