use crate::vulkan::context::Context;
use ash::vk;

/// The id-buffer format. `R32_UINT` is a mandatory colour-attachment format on every Vulkan
/// implementation, so this never has to negotiate one the way the swapchain colour format does.
pub(super) const PICK_FORMAT: vk::Format = vk::Format::R32_UINT;

/// The `R32_UINT` image the ids are drawn into, its view and framebuffer.
pub(super) struct PickTarget {
    pub(super) image: vk::Image,
    memory: vk::DeviceMemory,
    view: vk::ImageView,
    pub(super) framebuffer: vk::Framebuffer,
    pub(super) extent: vk::Extent2D,
}

impl PickTarget {
    pub(super) unsafe fn new(
        context: &Context,
        render_pass: vk::RenderPass,
        extent: vk::Extent2D,
    ) -> Result<PickTarget, String> {
        let device = &context.device;
        // Colour attachment we also transfer from: not transient/lazy like the MSAA and depth
        // targets, because the whole point is to copy a pixel out to the host afterwards.
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(PICK_FORMAT)
            .extent(vk::Extent3D {
                width: extent.width,
                height: extent.height,
                depth: 1,
            })
            .mip_levels(1)
            .array_layers(1)
            .samples(vk::SampleCountFlags::TYPE_1)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(vk::ImageUsageFlags::COLOR_ATTACHMENT | vk::ImageUsageFlags::TRANSFER_SRC)
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = device
            .create_image(&image_info, None)
            .map_err(|e| format!("pick create_image {e:?}"))?;

        let requirements = device.get_image_memory_requirements(image);
        let properties = context
            .instance
            .get_physical_device_memory_properties(context.physical_device);
        let type_index = (0..properties.memory_type_count)
            .find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize]
                        .property_flags
                        .contains(vk::MemoryPropertyFlags::DEVICE_LOCAL)
            })
            .ok_or("no device-local memory type for the pick target")?;
        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match device.allocate_memory(&allocate, None) {
            Ok(m) => m,
            Err(e) => {
                device.destroy_image(image, None);
                return Err(format!("pick allocate_memory {e:?}"));
            }
        };
        if let Err(e) = device.bind_image_memory(image, memory, 0) {
            device.free_memory(memory, None);
            device.destroy_image(image, None);
            return Err(format!("pick bind_image_memory {e:?}"));
        }

        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(PICK_FORMAT)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = match device.create_image_view(&view_info, None) {
            Ok(v) => v,
            Err(e) => {
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("pick create_image_view {e:?}"));
            }
        };

        let framebuffer_info = vk::FramebufferCreateInfo::default()
            .render_pass(render_pass)
            .attachments(std::slice::from_ref(&view))
            .width(extent.width)
            .height(extent.height)
            .layers(1);
        let framebuffer = match device.create_framebuffer(&framebuffer_info, None) {
            Ok(f) => f,
            Err(e) => {
                device.destroy_image_view(view, None);
                device.free_memory(memory, None);
                device.destroy_image(image, None);
                return Err(format!("pick create_framebuffer {e:?}"));
            }
        };

        Ok(PickTarget {
            image,
            memory,
            view,
            framebuffer,
            extent,
        })
    }

    pub(super) unsafe fn destroy(self, device: &ash::Device) {
        device.destroy_framebuffer(self.framebuffer, None);
        device.destroy_image_view(self.view, None);
        device.destroy_image(self.image, None);
        device.free_memory(self.memory, None);
    }
}
