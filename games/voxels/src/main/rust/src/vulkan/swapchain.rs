use ash::vk;
use crate::vulkan::context::VulkanContext;

pub struct Swapchain {
    pub loader: ash::khr::swapchain::Device,
    pub swapchain: vk::SwapchainKHR,
    pub images: Vec<vk::Image>,
    pub image_views: Vec<vk::ImageView>,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    pub depth_image: vk::Image,
    pub depth_mem: vk::DeviceMemory,
    pub depth_view: vk::ImageView,
}

impl Swapchain {
    pub fn new(ctx: &VulkanContext, width: u32, height: u32) -> Result<Self, String> {
        unsafe { Self::new_inner(ctx, width.max(1), height.max(1)) }
    }
    unsafe fn new_inner(ctx: &VulkanContext, width: u32, height: u32) -> Result<Self, String> {
        let caps = ctx.surface_loader.get_physical_device_surface_capabilities(ctx.physical_device, ctx.surface).map_err(|e| format!("caps {e:?}"))?;
        let formats = ctx.surface_loader.get_physical_device_surface_formats(ctx.physical_device, ctx.surface).map_err(|e| format!("formats {e:?}"))?;
        let present_modes = ctx.surface_loader.get_physical_device_surface_present_modes(ctx.physical_device, ctx.surface).map_err(|e| format!("present_modes {e:?}"))?;
        let mut chosen_format = formats[0];
        for fmt in &formats {
            if fmt.format == vk::Format::B8G8R8A8_SRGB && fmt.color_space == vk::ColorSpaceKHR::SRGB_NONLINEAR {
                chosen_format = *fmt; break;
            }
        }
        let extent = if caps.current_extent.width == u32::MAX { vk::Extent2D { width, height } } else { vk::Extent2D { width: caps.current_extent.width.max(1), height: caps.current_extent.height.max(1) } };
        let present_mode = if present_modes.contains(&vk::PresentModeKHR::MAILBOX) { vk::PresentModeKHR::MAILBOX } else { vk::PresentModeKHR::FIFO };
        let image_count = (caps.min_image_count + 1).min(if caps.max_image_count==0 { 3 } else { caps.max_image_count });
        let loader = ash::khr::swapchain::Device::new(&ctx.instance, &ctx.device);
        let ci = vk::SwapchainCreateInfoKHR::default().surface(ctx.surface).min_image_count(image_count).image_format(chosen_format.format).image_color_space(chosen_format.color_space).image_extent(extent).image_array_layers(1).image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT).image_sharing_mode(vk::SharingMode::EXCLUSIVE).pre_transform(caps.current_transform).composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE).present_mode(present_mode).clipped(true);
        let swapchain = loader.create_swapchain(&ci, None).map_err(|e| format!("create_swapchain {e:?}"))?;
        let images = loader.get_swapchain_images(swapchain).map_err(|e| format!("get_images {e:?}"))?;
        let image_views = images.iter().map(|&img| {
            let vi = vk::ImageViewCreateInfo::default().image(img).view_type(vk::ImageViewType::TYPE_2D).format(chosen_format.format).components(vk::ComponentMapping::default()).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 });
            ctx.device.create_image_view(&vi, None).unwrap()
        }).collect::<Vec<_>>();

        let depth_format = vk::Format::D32_SFLOAT;
        let depth_ci = vk::ImageCreateInfo::default().image_type(vk::ImageType::TYPE_2D).format(depth_format).extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 }).mip_levels(1).array_layers(1).samples(vk::SampleCountFlags::TYPE_1).tiling(vk::ImageTiling::OPTIMAL).usage(vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT).sharing_mode(vk::SharingMode::EXCLUSIVE).initial_layout(vk::ImageLayout::UNDEFINED);
        let depth_image = ctx.device.create_image(&depth_ci, None).map_err(|e| format!("depth img {e:?}"))?;
        let mem_reqs = ctx.device.get_image_memory_requirements(depth_image);
        let mem_props = ctx.instance.get_physical_device_memory_properties(ctx.physical_device);
        let mem_type = (0..mem_props.memory_type_count).find(|&i| (mem_reqs.memory_type_bits & (1<<i))!=0 && mem_props.memory_types[i as usize].property_flags.contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)).ok_or("no mem for depth")?;
        let alloc = vk::MemoryAllocateInfo::default().allocation_size(mem_reqs.size).memory_type_index(mem_type);
        let depth_mem = ctx.device.allocate_memory(&alloc, None).map_err(|e| format!("alloc depth {e:?}"))?;
        ctx.device.bind_image_memory(depth_image, depth_mem, 0).map_err(|e| format!("bind depth {e:?}"))?;
        let depth_view_ci = vk::ImageViewCreateInfo::default().image(depth_image).view_type(vk::ImageViewType::TYPE_2D).format(depth_format).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::DEPTH, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 });
        let depth_view = ctx.device.create_image_view(&depth_view_ci, None).map_err(|e| format!("depth view {e:?}"))?;
        Ok(Self { loader, swapchain, images, image_views, format: chosen_format.format, extent, depth_image, depth_mem, depth_view })
    }
    pub fn cleanup(&mut self, device: &ash::Device) {
        unsafe {
            device.destroy_image_view(self.depth_view, None);
            device.free_memory(self.depth_mem, None);
            device.destroy_image(self.depth_image, None);
            for &v in &self.image_views { device.destroy_image_view(v, None); }
            self.loader.destroy_swapchain(self.swapchain, None);
        }
    }
}
