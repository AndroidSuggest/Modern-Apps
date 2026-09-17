//! Pipeline vertex-attribute tables: the per-format attribute descriptions.
//!
//! Split from `pipelines.rs` (file-length limit). Same values, moved verbatim —
//! the formats are load-bearing (stride + offsets must match the tessellators),
//! so the doc comments move with them.
use ash::vk;

/// Sprite (app markers): position (already clip-space) + uv (atlas), 4 floats.
/// Markers resolve their corners on the CPU and draw through an identity
/// matrix, so there is no tile-local anchor to project and this format must
/// not grow. POI icons no longer use it — they moved to the billboard format
/// below so they face the camera under tilt.
pub(super) fn symbol_attributes() -> [vk::VertexInputAttributeDescription; 2] {
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
    ]
}

/// Symbol text and POI icons (billboarded): position (tile-local) + uv (atlas)
/// + ground anchor (tile-local) + the anchor's tile-normalised ground height, 7
/// floats. The anchor lets `symbol_billboard.vert` keep point labels and their
/// icons upright and pinned to the ground under tilt; at pitch 0 it is ignored
/// and output is unchanged. One format for both is what keeps an icon on top of
/// its label.
pub(super) fn symbol_billboard_attributes() -> [vk::VertexInputAttributeDescription; 4] {
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
    ]
}

/// Building (WS-A): position+height (3 floats), face normal (3 floats), then
/// the per-vertex ARGB colour as one `R8G8B8A8_UNORM` word the shader reads as
/// a 0..1 vec4. 28-byte stride.
pub(super) fn building_attributes() -> [vk::VertexInputAttributeDescription; 3] {
    [
        vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32B32_SFLOAT)
            .offset(0),
        vk::VertexInputAttributeDescription::default()
            .location(1)
            .binding(0)
            .format(vk::Format::R32G32B32_SFLOAT)
            .offset(12),
        vk::VertexInputAttributeDescription::default()
            .location(2)
            .binding(0)
            .format(vk::Format::R8G8B8A8_UNORM)
            .offset(24),
    ]
}

/// Terrain (WS-G): position+height (3 floats) then the surface normal (3
/// floats). 24-byte stride, no colour — the ground colour is the pushed `earth`
/// colour, not per-vertex.
pub(super) fn terrain_attributes() -> [vk::VertexInputAttributeDescription; 2] {
    [
        vk::VertexInputAttributeDescription::default()
            .location(0)
            .binding(0)
            .format(vk::Format::R32G32B32_SFLOAT)
            .offset(0),
        vk::VertexInputAttributeDescription::default()
            .location(1)
            .binding(0)
            .format(vk::Format::R32G32_SFLOAT)
            .offset(12),
    ]
}
