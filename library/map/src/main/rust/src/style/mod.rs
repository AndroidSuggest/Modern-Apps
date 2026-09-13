//! Which layers are drawn, in what order, in what colour - in light and dark.
//!
//! # Where the layers come from
//!
//! `style/basemap.flat.json`, one authored entry per drawn layer, read by [`paint`]. Order in
//! the file **is** draw order, and it is global rather than per tile: a road casing from one
//! tile must never cover the road fill of the tile next to it, so the renderer draws
//! layer-major across all tiles rather than tile-major across all layers.
//!
//! That file replaced a runtime interpretation of `style/basemap.json`, a 71-layer MapLibre
//! style: an expression evaluator, a filter reader, and a derivation that expanded one
//! data-driven layer into several because fill colour reaches the GPU as one push constant per
//! draw. All of it existed to reduce a general style to the seven properties this renderer
//! actually supports, and the last two rendering bugs lived in that reduction. The flat file
//! **is** one layer per colour, authored, so there is nothing left to derive — and
//! `paint::the_flat_style_agrees_with_basemap_json` cross-checks every value that exists in
//! both files, which is what keeps writing values down from becoming a third failed
//! transcription.
//!
//! # Where the colours come from
//!
//! Light is the bundled Protomaps style's own paint, except on lines — see [`paint`]'s docs for
//! that exception. Dark is not from a third-party style:
//! `maps/src/main/java/com/vayunmathur/maps/ui/theme/BasemapPalette.kt` already owns a
//! **contrast-checked dark palette for this exact archive**, and its module docs explain the
//! reasoning: a road label does not sit on a surface, it sits on the basemap, so deriving
//! basemap colours from the Material scheme "would let an unlucky accent produce grey-on-grey
//! terrain".
//!
//! So the dark values in the flat file are `BasemapPalette.darkFill`'s, by role. Two
//! consequences worth having: the schema matches by construction — these are the colours
//! written for `v4.pmtiles`' own layer names — and the five consumer apps end up looking like
//! the same product as `maps` rather than merely adjacent to it.
//!
//! # Switching variants is free
//!
//! Colour reaches the GPU as a push constant and the layer *set* is identical between
//! variants, so flipping light to dark re-uploads nothing and re-tessellates nothing. That
//! is why this is a runtime switch rather than a startup choice: the app can follow the
//! system theme.

pub mod paint;

mod filter;
mod kinds;
mod layer;
mod palette;
#[cfg(test)]
mod tests;

pub use self::filter::{KindFilter, SharedToggles};
pub use self::kinds::{Anchor, LayerKind, LayerToggles, Toggle, Variant};
pub use self::layer::{Layer, kind_id};
pub use self::palette::{
    LANE_RENDERING, MUTED_BLEND, Palette, background, layers, road_carriageway_layer,
};
#[cfg(test)]
pub use self::layer::kind_id_for_test;
#[cfg(test)]
pub use self::palette::layers_with_lane_rendering;
// Private: restores the original `fn detail_id`'s scope (visible to `style` and its
// children, e.g. `paint` via `super::detail_id`) without widening it crate-wide.
use self::layer::detail_id;
