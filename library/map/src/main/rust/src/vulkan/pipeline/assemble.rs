use ash::vk;

use super::state::{Depth, Stencil};

#[allow(clippy::too_many_arguments)]
pub(super) unsafe fn build(
    device: &ash::Device,
    layout: vk::PipelineLayout,
    render_pass: vk::RenderPass,
    samples: vk::SampleCountFlags,
    vertex: vk::ShaderModule,
    fragment: vk::ShaderModule,
    stride: u32,
    attributes: &[vk::VertexInputAttributeDescription],
    stencil: Stencil,
    depth: Depth,
    cache: vk::PipelineCache,
) -> Result<vk::Pipeline, String> {
    let entry = c"main";
    let stages = [
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::VERTEX)
            .module(vertex)
            .name(entry),
        vk::PipelineShaderStageCreateInfo::default()
            .stage(vk::ShaderStageFlags::FRAGMENT)
            .module(fragment)
            .name(entry),
    ];

    let binding = vk::VertexInputBindingDescription::default()
        .binding(0)
        .stride(stride)
        .input_rate(vk::VertexInputRate::VERTEX);
    let vertex_input = vk::PipelineVertexInputStateCreateInfo::default()
        .vertex_binding_descriptions(std::slice::from_ref(&binding))
        .vertex_attribute_descriptions(attributes);
    let assembly = vk::PipelineInputAssemblyStateCreateInfo::default()
        .topology(vk::PrimitiveTopology::TRIANGLE_LIST);

    // Viewport and scissor are dynamic, so a resize does not rebuild the pipeline — only
    // the swapchain.
    let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
    let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
    let viewport_state = vk::PipelineViewportStateCreateInfo::default()
        .viewport_count(1)
        .scissor_count(1);

    // No culling: tessellated tile geometry arrives in whatever winding the clipper left
    // it in, and a culled road is an invisible road.
    let rasterization = vk::PipelineRasterizationStateCreateInfo::default()
        .polygon_mode(vk::PolygonMode::FILL)
        .cull_mode(vk::CullModeFlags::NONE)
        .front_face(vk::FrontFace::COUNTER_CLOCKWISE)
        .line_width(1.0);
    // Must match the render pass's colour attachment, which `swapchain` chooses from
    // what the device supports.
    let multisample =
        vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(samples);

    // Straight src-alpha over one-minus-src-alpha. Layer order does the rest.
    let blend_attachment = vk::PipelineColorBlendAttachmentState::default()
        .blend_enable(true)
        .src_color_blend_factor(vk::BlendFactor::SRC_ALPHA)
        .dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .color_blend_op(vk::BlendOp::ADD)
        .src_alpha_blend_factor(vk::BlendFactor::ONE)
        .dst_alpha_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA)
        .alpha_blend_op(vk::BlendOp::ADD)
        // The mask writes stencil only. Letting it write colour would paint the region's
        // triangles over the map in whatever the fill shader produced.
        .color_write_mask(if stencil == Stencil::Write {
            vk::ColorComponentFlags::empty()
        } else {
            vk::ColorComponentFlags::RGBA
        });
    let blend = vk::PipelineColorBlendStateCreateInfo::default()
        .attachments(std::slice::from_ref(&blend_attachment));

    // A subpass with a depth-stencil attachment requires this state on every pipeline. The flat
    // 2D layers pass `Depth::Off` (test + write disabled), so the attachment WS0 added is present
    // but inert for them — their colour output is unchanged. The 3D layers pass `Depth::TestWrite`.
    let stencil_op = match stencil {
        Stencil::Ignore => vk::StencilOpState::default(),
        // `REPLACE` with reference 1 rather than increment: the region's tile pieces overlap at
        // shared edges, and any counting op would disagree with itself there.
        Stencil::Write => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::ALWAYS)
            .pass_op(vk::StencilOp::REPLACE)
            .fail_op(vk::StencilOp::REPLACE)
            .depth_fail_op(vk::StencilOp::REPLACE)
            .compare_mask(0xff)
            .write_mask(0xff)
            .reference(1),
        Stencil::TestOutside => vk::StencilOpState::default()
            .compare_op(vk::CompareOp::NOT_EQUAL)
            .pass_op(vk::StencilOp::KEEP)
            .fail_op(vk::StencilOp::KEEP)
            .depth_fail_op(vk::StencilOp::KEEP)
            .compare_mask(0xff)
            .write_mask(0)
            .reference(1),
    };
    let (depth_test, depth_write) = match depth {
        Depth::Off => (false, false),
        Depth::TestWrite => (true, true),
    };
    let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default()
        .depth_test_enable(depth_test)
        .depth_write_enable(depth_write)
        .depth_compare_op(vk::CompareOp::LESS)
        .stencil_test_enable(stencil != Stencil::Ignore)
        .front(stencil_op)
        .back(stencil_op);

    let info = vk::GraphicsPipelineCreateInfo::default()
        .stages(&stages)
        .vertex_input_state(&vertex_input)
        .input_assembly_state(&assembly)
        .viewport_state(&viewport_state)
        .rasterization_state(&rasterization)
        .multisample_state(&multisample)
        .depth_stencil_state(&depth_stencil)
        .color_blend_state(&blend)
        .dynamic_state(&dynamic)
        .layout(layout)
        .render_pass(render_pass)
        .subpass(0);

    device
        .create_graphics_pipelines(cache, std::slice::from_ref(&info), None)
        .map(|pipelines| pipelines[0])
        .map_err(|(_, e)| format!("create_graphics_pipelines {e:?}"))
}
