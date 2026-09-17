//! Moon pipeline + layouts: the maps-only lunar-globe disc.
//!
//! Split from `pipelines.rs` (file-length limit). The Moon needs TWO sampled
//! images (color set 0, DEM set 1), so it gets its own descriptor-set layout +
//! pipeline layout; the descriptor pool + sets stay Moon-owned, created at
//! upload time (see `upload_moon::set_moon_textures`).
use super::assemble::build;
use super::state::{Depth, Stencil};
use crate::tess::fill;
use ash::vk;

/// Build the Moon descriptor-set layout + pipeline layout.
///
/// Both layouts are returned (not yet consumed) so the caller's match keeps
/// owning the error path. On failure the caller destroys whatever was created.
pub(super) fn moon_layouts(
    device: &ash::Device,
    push_range: &vk::PushConstantRange,
) -> Result<(vk::DescriptorSetLayout, vk::PipelineLayout), String> {
    // SAFETY: layouts outlive the pipelines built from them; destroyed in
    // `Pipelines::destroy` while the device is idle.
    unsafe {
        let moon_bindings = [
            vk::DescriptorSetLayoutBinding::default()
                .binding(0)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default()
                .binding(1)
                .descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER)
                .descriptor_count(1)
                .stage_flags(vk::ShaderStageFlags::FRAGMENT),
        ];
        let ds_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&moon_bindings);
        let ds_layout = device
            .create_descriptor_set_layout(&ds_info, None)
            .map_err(|e| format!("create_descriptor_set_layout(moon) {e:?}"))?;
        let layout_info = vk::PipelineLayoutCreateInfo::default()
            .push_constant_ranges(std::slice::from_ref(push_range))
            .set_layouts(std::slice::from_ref(&ds_layout));
        match device.create_pipeline_layout(&layout_info, None) {
            Ok(layout) => Ok((ds_layout, layout)),
            Err(e) => {
                device.destroy_descriptor_set_layout(ds_layout, None);
                Err(format!("create_pipeline_layout(moon) {e:?}"))
            }
        }
    }
}

/// Build the Moon disc pipeline: `moon.vert`/`moon.frag` over the position-only
/// fill format (the shared unit quad: -1..1 local, bent onto the disc by the
/// vertex shader). Depth-tested so it participates in the same depth regime as
/// the globe tiles it replaces.
///
/// The vertex format is the fill one (vec3 position): the quad upload in
/// `Renderer::new` is already that format, so no new vertex path.
#[allow(clippy::too_many_arguments)]
pub(super) fn moon_pipeline(
    device: &ash::Device,
    moon_layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    moon_vert: vk::ShaderModule,
    moon_frag: vk::ShaderModule,
    fill_attributes: &[vk::VertexInputAttributeDescription],
    cache: vk::PipelineCache,
) -> Result<vk::Pipeline, String> {
    // SAFETY: same contract as every other `build` call.
    unsafe {
        build(
            device,
            moon_layout,
            render_pass,
            samples,
            moon_vert,
            moon_frag,
            (fill::FLOATS_PER_VERTEX * 4) as u32,
            fill_attributes,
            Stencil::Ignore,
            Depth::TestWrite,
            cache,
        )
    }
}
