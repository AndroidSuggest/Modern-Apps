use ash::vk;
use std::ffi::CString;

const BLOCK_VERT_SPV: &[u8] = include_bytes!("../../shaders/block.vert.spv");
const BLOCK_FRAG_SPV: &[u8] = include_bytes!("../../shaders/block.frag.spv");

pub struct Pipelines {
    pub layout: vk::PipelineLayout,
    pub pipeline: vk::Pipeline,
    pub descriptor_set_layout: vk::DescriptorSetLayout,
    pub descriptor_pool: vk::DescriptorPool,
    pub descriptor_set: vk::DescriptorSet,
}

impl Pipelines {
    pub unsafe fn new(device: &ash::Device, swapchain_format: vk::Format, depth_format: vk::Format, ubo_buffer: vk::Buffer, ubo_size: u64, atlas_view: vk::ImageView, atlas_sampler: vk::Sampler) -> Result<Self, String> {
        let bindings = [
            vk::DescriptorSetLayoutBinding::default().binding(0).descriptor_type(vk::DescriptorType::UNIFORM_BUFFER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::VERTEX | vk::ShaderStageFlags::FRAGMENT),
            vk::DescriptorSetLayoutBinding::default().binding(1).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(1).stage_flags(vk::ShaderStageFlags::FRAGMENT),
        ];
        let dsl_info = vk::DescriptorSetLayoutCreateInfo::default().bindings(&bindings);
        let descriptor_set_layout = device.create_descriptor_set_layout(&dsl_info, None).map_err(|e| format!("create dsl {e:?}"))?;
        let layouts = [descriptor_set_layout];
        let pl_info = vk::PipelineLayoutCreateInfo::default().set_layouts(&layouts);
        let layout = device.create_pipeline_layout(&pl_info, None).map_err(|e| format!("layout {e:?}"))?;
        let pool_sizes = [vk::DescriptorPoolSize::default().ty(vk::DescriptorType::UNIFORM_BUFFER).descriptor_count(2), vk::DescriptorPoolSize::default().ty(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).descriptor_count(2)];
        let pool_info = vk::DescriptorPoolCreateInfo::default().pool_sizes(&pool_sizes).max_sets(2);
        let descriptor_pool = device.create_descriptor_pool(&pool_info, None).map_err(|e| format!("pool {e:?}"))?;
        let alloc_info = vk::DescriptorSetAllocateInfo::default().descriptor_pool(descriptor_pool).set_layouts(&layouts);
        let sets = device.allocate_descriptor_sets(&alloc_info).map_err(|e| format!("alloc sets {e:?}"))?;
        let descriptor_set = sets[0];
        let buffer_info = vk::DescriptorBufferInfo::default().buffer(ubo_buffer).offset(0).range(ubo_size);
        let image_info = vk::DescriptorImageInfo::default().image_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image_view(atlas_view).sampler(atlas_sampler);
        let writes = [vk::WriteDescriptorSet::default().dst_set(descriptor_set).dst_binding(0).descriptor_type(vk::DescriptorType::UNIFORM_BUFFER).buffer_info(std::slice::from_ref(&buffer_info)), vk::WriteDescriptorSet::default().dst_set(descriptor_set).dst_binding(1).descriptor_type(vk::DescriptorType::COMBINED_IMAGE_SAMPLER).image_info(std::slice::from_ref(&image_info))];
        device.update_descriptor_sets(&writes, &[]);
        let vert_module = Self::create_module(device, BLOCK_VERT_SPV)?;
        let frag_module = Self::create_module(device, BLOCK_FRAG_SPV)?;
        let entry_name = CString::new("main").unwrap();
        let stages = [vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::VERTEX).module(vert_module).name(&entry_name), vk::PipelineShaderStageCreateInfo::default().stage(vk::ShaderStageFlags::FRAGMENT).module(frag_module).name(&entry_name)];
        let binding = crate::vulkan::buffers::Vertex::binding_description();
        let attrs = crate::vulkan::buffers::Vertex::attribute_descriptions();
        let vertex_input = vk::PipelineVertexInputStateCreateInfo::default().vertex_binding_descriptions(std::slice::from_ref(&binding)).vertex_attribute_descriptions(&attrs);
        let input_assembly = vk::PipelineInputAssemblyStateCreateInfo::default().topology(vk::PrimitiveTopology::TRIANGLE_LIST);
        let viewport_state = vk::PipelineViewportStateCreateInfo::default().viewport_count(1).scissor_count(1);
        let raster = vk::PipelineRasterizationStateCreateInfo::default().polygon_mode(vk::PolygonMode::FILL).cull_mode(vk::CullModeFlags::BACK).front_face(vk::FrontFace::COUNTER_CLOCKWISE).line_width(1.0);
        let multisample = vk::PipelineMultisampleStateCreateInfo::default().rasterization_samples(vk::SampleCountFlags::TYPE_1);
        let depth_stencil = vk::PipelineDepthStencilStateCreateInfo::default().depth_test_enable(true).depth_write_enable(true).depth_compare_op(vk::CompareOp::LESS);
        let color_blend_attachment = vk::PipelineColorBlendAttachmentState::default().color_write_mask(vk::ColorComponentFlags::RGBA).blend_enable(true).src_color_blend_factor(vk::BlendFactor::SRC_ALPHA).dst_color_blend_factor(vk::BlendFactor::ONE_MINUS_SRC_ALPHA).color_blend_op(vk::BlendOp::ADD);
        let color_blend = vk::PipelineColorBlendStateCreateInfo::default().attachments(std::slice::from_ref(&color_blend_attachment));
        let dynamic_states = [vk::DynamicState::VIEWPORT, vk::DynamicState::SCISSOR];
        let dynamic = vk::PipelineDynamicStateCreateInfo::default().dynamic_states(&dynamic_states);
        let mut pipeline_rendering_info = vk::PipelineRenderingCreateInfo::default().color_attachment_formats(std::slice::from_ref(&swapchain_format)).depth_attachment_format(depth_format);
        let pipeline_info = vk::GraphicsPipelineCreateInfo::default().stages(&stages).vertex_input_state(&vertex_input).input_assembly_state(&input_assembly).viewport_state(&viewport_state).rasterization_state(&raster).multisample_state(&multisample).depth_stencil_state(&depth_stencil).color_blend_state(&color_blend).dynamic_state(&dynamic).layout(layout).push_next(&mut pipeline_rendering_info);
        let pipelines = device.create_graphics_pipelines(vk::PipelineCache::null(), std::slice::from_ref(&pipeline_info), None).map_err(|(_, e)| format!("create pipelines {e:?}"))?;
        device.destroy_shader_module(vert_module, None);
        device.destroy_shader_module(frag_module, None);
        Ok(Self { layout, pipeline: pipelines[0], descriptor_set_layout, descriptor_pool, descriptor_set })
    }
    unsafe fn create_module(device: &ash::Device, spv_bytes: &[u8]) -> Result<vk::ShaderModule, String> {
        if spv_bytes.len() % 4 != 0 { return Err("spv not aligned".into()); }
        let code = unsafe { std::slice::from_raw_parts(spv_bytes.as_ptr() as *const u32, spv_bytes.len() / 4) };
        let info = vk::ShaderModuleCreateInfo::default().code(code);
        device.create_shader_module(&info, None).map_err(|e| format!("create shader module {e:?}"))
    }
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_pipeline(self.pipeline, None);
        device.destroy_pipeline_layout(self.layout, None);
        device.destroy_descriptor_pool(self.descriptor_pool, None);
        device.destroy_descriptor_set_layout(self.descriptor_set_layout, None);
    }
}
