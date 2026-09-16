use crate::vulkan::context::Context;
use ash::vk;

/// The combined depth-stencil buffer.
///
/// The **stencil** aspect punches the selected region out of the mask scrim; the **depth**
/// aspect (added by WS0) lets the perspective 3D layers occlude one another. One image serves
/// both because every device offers a combined depth-stencil format even where standalone
/// `S8_UINT` is missing.
///
/// Transient and lazily allocated for the same reason as [`super::msaa::MsaaTarget`]: both aspects are
/// written and read within the single subpass and never afterwards, so on a tile-based GPU it
/// lives in tile memory and needs no backing store. That is what keeps the transient-attachment
/// bandwidth argument in the module docs true even though this attachment now carries depth too.
pub(super) struct StencilTarget {
    pub(super) image: vk::Image,
    pub(super) memory: vk::DeviceMemory,
    pub(super) view: vk::ImageView,
}

/// A combined depth+stencil attachment format the device supports, cheapest first.
///
/// Both aspects are used now — depth by the 3D layers, stencil by the region mask — so a
/// standalone `S8_UINT` (which several drivers offer but which carries no depth) is no longer a
/// candidate. Every Vulkan implementation must support at least one of `D24_UNORM_S8_UINT` or
/// `D32_SFLOAT_S8_UINT`, so this never fails on real hardware.
pub(super) unsafe fn stencil_format(context: &Context) -> Result<vk::Format, String> {
    for candidate in [
        vk::Format::D24_UNORM_S8_UINT,
        vk::Format::D32_SFLOAT_S8_UINT,
    ] {
        let properties = context
            .instance
            .get_physical_device_format_properties(context.physical_device, candidate);
        if properties
            .optimal_tiling_features
            .contains(vk::FormatFeatureFlags::DEPTH_STENCIL_ATTACHMENT)
        {
            return Ok(candidate);
        }
    }
    Err("no combined depth-stencil attachment format".into())
}

impl StencilTarget {
    /// Allocate the stencil attachment, transient and lazily allocated like [`super::msaa::MsaaTarget::new`].
    pub(super) unsafe fn new(
        context: &Context,
        format: vk::Format,
        extent: vk::Extent2D,
        samples: vk::SampleCountFlags,
    ) -> Result<StencilTarget, String> {
        let image_info = vk::ImageCreateInfo::default()
            .image_type(vk::ImageType::TYPE_2D)
            .format(format)
            .extent(vk::Extent3D {
                width: extent.width,
                height: extent.height,
                depth: 1,
            })
            .mip_levels(1)
            .array_layers(1)
            .samples(samples)
            .tiling(vk::ImageTiling::OPTIMAL)
            .usage(
                vk::ImageUsageFlags::DEPTH_STENCIL_ATTACHMENT
                    | vk::ImageUsageFlags::TRANSIENT_ATTACHMENT,
            )
            .sharing_mode(vk::SharingMode::EXCLUSIVE)
            .initial_layout(vk::ImageLayout::UNDEFINED);
        let image = context
            .device
            .create_image(&image_info, None)
            .map_err(|e| format!("create stencil image {e:?}"))?;

        let requirements = context.device.get_image_memory_requirements(image);
        let properties = context
            .instance
            .get_physical_device_memory_properties(context.physical_device);
        let find = |flags: vk::MemoryPropertyFlags| -> Option<u32> {
            (0..properties.memory_type_count).find(|&i| {
                requirements.memory_type_bits & (1 << i) != 0
                    && properties.memory_types[i as usize]
                        .property_flags
                        .contains(flags)
            })
        };
        let type_index =
            find(vk::MemoryPropertyFlags::DEVICE_LOCAL | vk::MemoryPropertyFlags::LAZILY_ALLOCATED)
                .or_else(|| find(vk::MemoryPropertyFlags::DEVICE_LOCAL))
                .ok_or("no device-local memory type for the stencil target")?;

        let allocate = vk::MemoryAllocateInfo::default()
            .allocation_size(requirements.size)
            .memory_type_index(type_index);
        let memory = match context.device.allocate_memory(&allocate, None) {
            Ok(memory) => memory,
            Err(e) => {
                context.device.destroy_image(image, None);
                return Err(format!("allocate stencil memory {e:?}"));
            }
        };
        if let Err(e) = context.device.bind_image_memory(image, memory, 0) {
            context.device.destroy_image(image, None);
            context.device.free_memory(memory, None);
            return Err(format!("bind stencil memory {e:?}"));
        }

        // The view names only the stencil aspect even when the format carries depth too, which
        // is what a depth-stencil attachment view must do when only one aspect is used.
        let aspect = if format == vk::Format::S8_UINT {
            vk::ImageAspectFlags::STENCIL
        } else {
            vk::ImageAspectFlags::DEPTH | vk::ImageAspectFlags::STENCIL
        };
        let view_info = vk::ImageViewCreateInfo::default()
            .image(image)
            .view_type(vk::ImageViewType::TYPE_2D)
            .format(format)
            .subresource_range(vk::ImageSubresourceRange {
                aspect_mask: aspect,
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
                return Err(format!("create stencil image view {e:?}"));
            }
        };
        Ok(StencilTarget {
            image,
            memory,
            view,
        })
    }
}
