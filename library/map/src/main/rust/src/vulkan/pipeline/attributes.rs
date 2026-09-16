//! The vertex-input attribute descriptions for the flat-layer pipelines.
//!
//! Split out of `pipelines.rs` so that file stays under the 500-line lint limit; the stride
//! of each format still comes from its tessellator's `FLOATS_PER_VERTEX` at the `build` call
//! site, so the two cannot drift.
use ash::vk;

/// Fill (and the position-only pipelines sharing its format: depth, puck, mask, scrim):
/// position + draped ground height, 12-byte stride.
pub fn fill_attributes() -> [vk::VertexInputAttributeDescription; 1] {
    [vk::VertexInputAttributeDescription::default()
        .location(0)
        .binding(0)
        .format(vk::Format::R32G32B32_SFLOAT)
        .offset(0)]
}

/// Stroke: position, join normal, extrude pair, distance-along, draped height. 32-byte stride.
pub fn line_attributes() -> [vk::VertexInputAttributeDescription; 5] {
    [
        vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(0),
        vk::VertexInputAttributeDescription::default()
            .location(1)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(8),
        vk::VertexInputAttributeDescription::default()
            .location(2)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(16),
        vk::VertexInputAttributeDescription::default()
            .location(3)
            .binding(0)
            .format(vk::Format::R32_SFLOAT)
            .offset(24),
        vk::VertexInputAttributeDescription::default()
            .location(4)
            .binding(0)
            .format(vk::Format::R32_SFLOAT)
            .offset(28),
    ]
}

/// Ribbon (road carriageways): position, join normal, the normalised across-road coordinate
/// `t`, tile-local distance-along, and the draped ground height. 28-byte stride. `t` is both
/// the vertex shader's extrusion multiplier and the fragment shader's marking coordinate,
/// which is what keeps this to seven floats rather than eight.
pub fn ribbon_attributes() -> [vk::VertexInputAttributeDescription; 5] {
    [
        vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(0),
        vk::VertexInputAttributeDescription::default()
            .location(1)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(8),
        vk::VertexInputAttributeDescription::default()
            .location(2)
            .binding(0)
            .format(vk::Format::R32_SFLOAT)
            .offset(16),
        vk::VertexInputAttributeDescription::default()
            .location(3)
            .binding(0)
            .format(vk::Format::R32_SFLOAT)
            .offset(20),
        vk::VertexInputAttributeDescription::default()
            .location(4)
            .binding(0)
            .format(vk::Format::R32_SFLOAT)
            .offset(24),
    ]
}
