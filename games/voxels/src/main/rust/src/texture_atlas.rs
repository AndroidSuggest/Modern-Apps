use ash::vk;
use crate::vulkan::buffers::AllocatedBuffer;

pub const ATLAS_TILES_PER_ROW: u32 = 8;
pub const ATLAS_TILE_ROWS: u32 = 8;
pub const TILE_SIZE: u32 = 16;
pub const ATLAS_W: u32 = ATLAS_TILES_PER_ROW * TILE_SIZE; // 128
pub const ATLAS_H: u32 = ATLAS_TILE_ROWS * TILE_SIZE;     // 128

pub struct TextureAtlas {
    pub image: vk::Image,
    pub memory: vk::DeviceMemory,
    pub view: vk::ImageView,
    pub sampler: vk::Sampler,
}

impl TextureAtlas {
    pub unsafe fn new(
        instance: &ash::Instance,
        device: &ash::Device,
        phys: vk::PhysicalDevice,
        command_pool: vk::CommandPool,
        queue: vk::Queue,
        atlas_pixels: &[u8],
    ) -> Result<Self, String> {
        let img_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(vk::Format::R8G8B8A8_SRGB)
            .extent(vk::Extent3D { width: ATLAS_W, height: ATLAS_H, depth: 1 })
            .mip_levels(1).array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::TRANSFER_DST | vk::ImageUsageFlags::SAMPLED)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = device.create_image(&img_info, None).map_err(|e| format!("atlas image create failed: {e:?}"))?;
        let mem_reqs = device.get_image_memory_requirements(image);
        let mem_props = instance.get_physical_device_memory_properties(phys);
        let mem_type = (0..mem_props.memory_type_count).find(|&i| {
            (mem_reqs.memory_type_bits & (1 << i)) != 0 &&
            mem_props.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
        }).ok_or("no suitable mem for atlas")?;
        let alloc_info = vk::MemoryAllocateInfo::default().allocation_size(mem_reqs.size).memory_type_index(mem_type);
        let memory = device.allocate_memory(&alloc_info, None).map_err(|e| format!("atlas alloc failed: {e:?}"))?;
        device.bind_image_memory(image, memory, 0).map_err(|e| format!("bind atlas failed: {e:?}"))?;
        let staging = AllocatedBuffer::new(instance, device, phys, atlas_pixels.len() as u64, vk::BufferUsageFlags::TRANSFER_SRC, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)?;
        staging.upload(device, atlas_pixels);
        let cmd_alloc = vk::CommandBufferAllocateInfo::default().command_pool(command_pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(1);
        let cmd = device.allocate_command_buffers(&cmd_alloc).map_err(|e| format!("alloc atlas cmd failed: {e:?}"))?[0];
        let begin = vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        device.begin_command_buffer(cmd, &begin).map_err(|e| format!("begin atlas cmd failed: {e:?}"))?;
        let barrier_to_transfer = vk::ImageMemoryBarrier::default().old_layout(vk::ImageLayout::UNDEFINED).new_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL).image(image).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 }).src_access_mask(vk::AccessFlags::empty()).dst_access_mask(vk::AccessFlags::TRANSFER_WRITE);
        device.cmd_pipeline_barrier(cmd, vk::PipelineStageFlags::TOP_OF_PIPE, vk::PipelineStageFlags::TRANSFER, vk::DependencyFlags::empty(), &[], &[], std::slice::from_ref(&barrier_to_transfer));
        let region = vk::BufferImageCopy::default().image_subresource(vk::ImageSubresourceLayers { aspect_mask: vk::ImageAspectFlags::COLOR, mip_level: 0, base_array_layer: 0, layer_count: 1 }).image_extent(vk::Extent3D { width: ATLAS_W, height: ATLAS_H, depth: 1 });
        device.cmd_copy_buffer_to_image(cmd, staging.buffer, image, vk::ImageLayout::TRANSFER_DST_OPTIMAL, std::slice::from_ref(&region));
        let barrier_to_shader = vk::ImageMemoryBarrier::default().old_layout(vk::ImageLayout::TRANSFER_DST_OPTIMAL).new_layout(vk::ImageLayout::SHADER_READ_ONLY_OPTIMAL).image(image).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 }).src_access_mask(vk::AccessFlags::TRANSFER_WRITE).dst_access_mask(vk::AccessFlags::SHADER_READ);
        device.cmd_pipeline_barrier(cmd, vk::PipelineStageFlags::TRANSFER, vk::PipelineStageFlags::FRAGMENT_SHADER, vk::DependencyFlags::empty(), &[], &[], std::slice::from_ref(&barrier_to_shader));
        device.end_command_buffer(cmd).map_err(|e| format!("end atlas cmd failed: {e:?}"))?;
        let submit = vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&cmd));
        device.queue_submit(queue, std::slice::from_ref(&submit), vk::Fence::null()).map_err(|e| format!("submit atlas failed: {e:?}"))?;
        device.queue_wait_idle(queue).map_err(|e| format!("wait idle atlas failed: {e:?}"))?;
        device.free_command_buffers(command_pool, std::slice::from_ref(&cmd));
        unsafe { staging.destroy_inner(device) };
        let view_info = vk::ImageViewCreateInfo::default().image(image).view_type(vk::ImageViewType::TYPE_2D).format(vk::Format::R8G8B8A8_SRGB).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 });
        let view = device.create_image_view(&view_info, None).map_err(|e| format!("atlas view failed: {e:?}"))?;
        let sampler_info = vk::SamplerCreateInfo::default().mag_filter(vk::Filter::NEAREST).min_filter(vk::Filter::NEAREST).mipmap_mode(vk::SamplerMipmapMode::NEAREST).address_mode_u(vk::SamplerAddressMode::CLAMP_TO_EDGE).address_mode_v(vk::SamplerAddressMode::CLAMP_TO_EDGE).max_anisotropy(1.0).min_lod(0.0).max_lod(0.25);
        let sampler = device.create_sampler(&sampler_info, None).map_err(|e| format!("sampler failed: {e:?}"))?;
        Ok(Self { image, memory, view, sampler })
    }
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        device.destroy_sampler(self.sampler, None);
        device.destroy_image_view(self.view, None);
        device.free_memory(self.memory, None);
        device.destroy_image(self.image, None);
    }
}
pub fn load_atlas_bin() -> Vec<u8> {
    let maybe: &[u8] = include_bytes!("../shaders/atlas.bin");
    if maybe.len() == (ATLAS_W*ATLAS_H*4) as usize { maybe.to_vec() } else {
        let mut fallback = vec![0u8; (ATLAS_W*ATLAS_H*4) as usize];
        for y in 0..ATLAS_H { for x in 0..ATLAS_W {
            let i = ((y*ATLAS_W + x)*4) as usize;
            if (x/8 + y/8) %2==0 { fallback[i]=255; fallback[i+1]=0; fallback[i+2]=255; fallback[i+3]=255; } else { fallback[i]=0; fallback[i+1]=0; fallback[i+2]=0; fallback[i+3]=255; }
        }}
        fallback
    }
}
