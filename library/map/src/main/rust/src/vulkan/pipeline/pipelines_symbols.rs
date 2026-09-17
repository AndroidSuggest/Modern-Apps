//! Symbol pipelines: glyph text, POI icons, app-marker sprites, and their
//! globe counterparts.
//!
//! Split from `pipelines.rs` (file-length limit). Same shaders, formats and
//! fixed-function state, moved verbatim.
use super::assemble::build;
use super::state::{Depth, Stencil};
use crate::tile::symbol;
use ash::vk;

/// Build the (symbol, icon, sprite) triple: billboard text, billboard icons
/// (same vertex shader + sprite fragment), and flat app-marker sprites.
#[allow(clippy::too_many_arguments)]
pub(super) fn symbol_pipelines(
    device: &ash::Device,
    symbol_layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    symbol_vert: vk::ShaderModule,
    symbol_billboard_vert: vk::ShaderModule,
    symbol_frag: vk::ShaderModule,
    sprite_frag: vk::ShaderModule,
    symbol_attributes: &[vk::VertexInputAttributeDescription],
    symbol_billboard_attributes: &[vk::VertexInputAttributeDescription],
    cache: vk::PipelineCache,
) -> (
    Result<vk::Pipeline, String>,
    Result<vk::Pipeline, String>,
    Result<vk::Pipeline, String>,
) {
    // SAFETY: same contract as every other `build` call.
    unsafe {
        (
            build(
                device,
                symbol_layout,
                render_pass,
                samples,
                symbol_billboard_vert,
                symbol_frag,
                (symbol::FLOATS_PER_VERTEX * 4) as u32,
                symbol_billboard_attributes,
                Stencil::Ignore,
                Depth::Off,
                cache,
            ),
            // POI icons: the billboard vertex shader (so they face the camera
            // under tilt, exactly as the text beside them does) paired with the
            // sprite fragment shader (because an icon is a picture, not a
            // distance field). No new shader — both halves already existed.
            build(
                device,
                symbol_layout,
                render_pass,
                samples,
                symbol_billboard_vert,
                sprite_frag,
                (symbol::ICON_FLOATS_PER_VERTEX * 4) as u32,
                symbol_billboard_attributes,
                Stencil::Ignore,
                Depth::Off,
                cache,
            ),
            build(
                device,
                symbol_layout,
                render_pass,
                samples,
                symbol_vert,
                sprite_frag,
                (symbol::MARKER_FLOATS_PER_VERTEX * 4) as u32,
                symbol_attributes,
                Stencil::Ignore,
                Depth::Off,
                cache,
            ),
        )
    }
}

/// Build the (symbol_globe, icon_globe) pair: the globe vertex shader on the
/// billboard format, paired with the glyph and sprite fragment shaders
/// respectively, through the atlas layout. Depth-tested so far-side labels
/// lose to the ball.
#[allow(clippy::too_many_arguments)]
pub(super) fn globe_symbol_pipelines(
    device: &ash::Device,
    symbol_layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    symbol_globe_vert: vk::ShaderModule,
    symbol_frag: vk::ShaderModule,
    sprite_frag: vk::ShaderModule,
    symbol_billboard_attributes: &[vk::VertexInputAttributeDescription],
    cache: vk::PipelineCache,
) -> (Result<vk::Pipeline, String>, Result<vk::Pipeline, String>) {
    // SAFETY: same contract as every other `build` call.
    unsafe {
        (
            build(
                device,
                symbol_layout,
                render_pass,
                samples,
                symbol_globe_vert,
                symbol_frag,
                (symbol::FLOATS_PER_VERTEX * 4) as u32,
                symbol_billboard_attributes,
                Stencil::Ignore,
                Depth::TestWrite,
                cache,
            ),
            build(
                device,
                symbol_layout,
                render_pass,
                samples,
                symbol_globe_vert,
                sprite_frag,
                (symbol::ICON_FLOATS_PER_VERTEX * 4) as u32,
                symbol_billboard_attributes,
                Stencil::Ignore,
                Depth::TestWrite,
                cache,
            ),
        )
    }
}
