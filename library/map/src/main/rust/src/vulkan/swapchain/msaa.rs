use crate::vulkan::context::Context;
use ash::vk;

pub(super) struct MsaaTarget {
    pub(super) image: vk::Image,
    pub(super) memory: vk::DeviceMemory,
    pub(super) view: vk::ImageView,
}

impl MsaaTarget {
    /// Allocate the multisampled colour target.
    ///
    /// `TRANSIENT_ATTACHMENT` with `LAZILY_ALLOCATED` memory is the whole point on a
    /// tile-based GPU: the samples never leave tile memory, so the driver backs the
    /// image with nothing. Desktop and emulator drivers do not offer that memory type,
    /// so it falls back to a normal device-local allocation.
    pub(super) unsafe fn new(
        context: &Context,
        format: vk::Format,
        extent: vk::Extent2D,
        samples: vk::SampleCountFlags,
    ) -> Result<MsaaTarget, String> {
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(format)
            .extent(vk::Extent3D { width: extent.width, height: extent.height, depth: 1 })
            .mip_levels(1)
            .array_layers(1)
            .samples(samples)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(
                vk::ImageUsageFlags::COLOR_ATTACHMENT
                    | vk::ImageUsageFlags::TRANSIENT_ATTACHMENT,
            )
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = context
            .device
            .create_image(&image_info, None)
            .map_err(|e| format!("create MSAA image {e:?}"))?;

        let requirements = context.device.get_image_memory_requirements(image);
        let properties =
            context.instance.get_physical_device_memory_properties(context.physical_device);
        let find = |flags: vk::MemoryPropertyFlags| -> Option<u32> {
            (0..properties.memory_type_count).find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize].property_flags.contains(flags)
            })
        };
        let type_index = find(
            vk::MemoryPropertyFlags::DEVICE_LOCAL | vk::MemoryPropertyFlags::LAZILY_ALLOCATED,
        )
        .or_else(|| find(vk::MemoryPropertyFlags::DEVICE_LOCAL))
        .ok_or("no device-local memory type for the MSAA target")?;

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match context.device.allocate_memory(&allocate, None) {
            Ok(memory) => memory,
            Err(e) => {
                context.device.destroy_image(image, None);
                return Err(format!("allocate MSAA memory {e:?}"));
            }
        };
        if let Err(e) = context.device.bind_image_memory(image, memory, 0) {
            context.device.destroy_image(image, None);
            context.device.free_memory(memory, None);
            return Err(format!("bind MSAA memory {e:?}"));
        }

        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: vk::ImageAspectFlags::COLOR,
                base_mip_level: 0,
                level_count: 1,
                base_array_layer: 0,
                layer_count: 1,
            });
        let view = match context.device.create_image_view(&view_info, None) {
            Ok(view) => view,
            Err(e) => {
                context.device.destroy_image(image, None);
                context.device.free_memory(memory, None);
                return Err(format!("create MSAA image view {e:?}"));
            }
        };
        Ok(MsaaTarget { image, memory, view })
    }
}
