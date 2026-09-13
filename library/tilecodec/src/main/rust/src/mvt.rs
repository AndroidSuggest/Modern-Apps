//! Mapbox Vector Tile 2.1 codec — decode, edit, re-encode.
//!
//! ## The geometry contract, and how it changed
//!
//! [`Feature::geometry`] is the **raw command-integer stream**, never a decoded
//! ring. That is deliberate and it has not changed: [`crate::tiling::merge_tiles`]
//! only ever moves features between tiles, so carrying the stream through verbatim
//! means a re-encode cannot corrupt geometry we did not produce. That opaque path
//! is what makes the composite lossless, and it is load-bearing for the base
//! tileset's lines and polygons.
//!
//! What changed is that this module now also **builds and reads** those streams,
//! for the layers we tile ourselves: [`encode_points`], [`encode_lines`] and
//! [`encode_polygons`] on the way in, and [`decode_points`], [`decode_lines`] and
//! [`decode_polygons`] on the way out. The encoders sit *beside* the passthrough
//! rather than replacing it — a feature either came from a stream we are copying,
//! or from vertices we are encoding, and the two never meet.
//!
//! ## Polygon winding order
//!
//! The spec states it as signed area, not as a direction: applying the surveyor's
//! formula to a ring, an **exterior ring must come out positive and an interior
//! ring negative**. (Equivalently: clockwise and counter-clockwise on screen,
//! since tile `y` grows downward — which is why quoting the direction instead of
//! the sign is such a reliable way to get it backwards.)
//!
//! [`encode_polygons`] therefore computes [`signed_area`] and reverses the ring
//! when the sign is wrong, rather than trusting the caller. Input rings arrive from
//! a clipper and a simplifier, neither of which preserves orientation, so trusting
//! them would produce holes that render as fill and fills that render as holes.
//!
//! ## Property dictionaries
//!
//! Properties *are* decoded, since re-encoding rebuilds each layer's key/value
//! dictionaries. A re-encode is therefore semantically identical but not
//! byte-identical: dictionary order depends on first use, so an unchanged tile can
//! come back a few bytes different. Tests assert on the decoded model, which is
//! what the spec actually defines.
//!
//! Wire layout (`vector_tile.proto`):
//!   Tile    { repeated Layer layers = 3 }
//!   Layer   { required string name = 1, repeated Feature features = 2,
//!             repeated string keys = 3, repeated Value values = 4,
//!             optional uint32 extent = 5 [default 4096],
//!             required uint32 version = 15 [default 1] }
//!   Feature { optional uint64 id = 1, repeated uint32 tags = 2 [packed],
//!             optional GeomType type = 3, repeated uint32 geometry = 4 [packed] }
//!   Value   { string 1 | float 2 | double 3 | int64 4 | uint64 5 | sint64 6 |
//!             bool 7 }

pub mod decode;
pub mod encode;
pub mod geom;
pub mod model;

#[cfg(test)]
mod tests;

pub use model::{
    DEFAULT_EXTENT, DEFAULT_VERSION, Feature, FeatureRef, GeomType, Layer, Tile, Value,
};
pub use encode::{encode_layer_from, encode_tile_from};
pub use geom::{
    CMD_CLOSE_PATH, CMD_LINE_TO, CMD_MOVE_TO, PolygonRings, command, command_parts,
    decode_lines, decode_points, decode_polygons, encode_lines, encode_points, encode_polygons,
    signed_area,
};
#[cfg(test)]
pub(crate) use geom::push_delta;
