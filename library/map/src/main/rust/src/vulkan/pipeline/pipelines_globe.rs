//! Globe pipelines: the depth-tested fill/line/ribbon variants that bend
//! tile-local geometry onto the orthographic sphere.
//!
//! Split from `pipelines.rs` (file-length limit). Same vertex formats as the
//! flat twins; depth 1 - z (limb 0, sub-camera point ~1) with the pass cleared
//! to 1.0, so nearer (larger z) wins the LESS test.
use super::assemble::build;
use super::state::{Depth, Stencil};
use crate::tess::{fill, stroke};
use ash::vk;

/// Build the (fill_globe, line_globe, ribbon_globe) triple.
///
/// Returns the three build results (not yet unwrapped) so the caller's match
/// keeps owning the error path — same convention as the inline `build` calls
/// this replaces.
#[allow(clippy::too_many_arguments)]
pub(super) fn globe_pipelines(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    fill_globe_vert: vk::ShaderModule,
    fill_globe_frag: vk::ShaderModule,
    line_globe_vert: vk::ShaderModule,
    line_globe_frag: vk::ShaderModule,
    ribbon_globe_vert: vk::ShaderModule,
    ribbon_globe_frag: vk::ShaderModule,
    fill_attributes: &[vk::VertexInputAttributeDescription],
    line_attributes: &[vk::VertexInputAttributeDescription],
    ribbon_attributes: &[vk::VertexInputAttributeDescription],
    cache: vk::PipelineCache,
) -> (
    Result<vk::Pipeline, String>,
    Result<vk::Pipeline, String>,
    Result<vk::Pipeline, String>,
) {
    // SAFETY: same contract as every other `build` call — render pass outlives
    // the pipelines, layout matches the push block.
    unsafe {
        (
            build(
                device,
                layout,
                render_pass,
                samples,
                fill_globe_vert,
                fill_globe_frag,
                (fill::FLOATS_PER_VERTEX * 4) as u32,
                fill_attributes,
                Stencil::Ignore,
                Depth::TestWrite,
                cache,
            ),
            build(
                device,
                layout,
                render_pass,
                samples,
                line_globe_vert,
                line_globe_frag,
                (stroke::FLOATS_PER_VERTEX * 4) as u32,
                line_attributes,
                Stencil::Ignore,
                Depth::TestWrite,
                cache,
            ),
            build(
                device,
                layout,
                render_pass,
                samples,
                ribbon_globe_vert,
                ribbon_globe_frag,
                (crate::tess::ribbon::FLOATS_PER_VERTEX * 4) as u32,
                ribbon_attributes,
                Stencil::Ignore,
                Depth::TestWrite,
                cache,
            ),
        )
    }
}
