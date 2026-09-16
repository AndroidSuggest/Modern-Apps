use crate::vulkan::context::Context;
use ash::vk;

use super::msaa::MsaaTarget;
use super::samples::supported_samples;
use super::stencil::{stencil_format, StencilTarget};

pub struct Swapchain {
    pub loader: ash::khr::swapchain::Device,
    pub swapchain: vk::SwapchainKHR,
    pub images: Vec<vk::Image>,
    pub views: Vec<vk::ImageView>,
    pub framebuffers: Vec<vk::Framebuffer>,
    pub render_pass: vk::RenderPass,
    pub format: vk::Format,
    pub extent: vk::Extent2D,
    /// Samples per pixel the pipelines must be built for.
    pub samples: vk::SampleCountFlags,
    /// The multisampled colour target, absent when `samples` is 1.
    msaa: Option<MsaaTarget>,
    /// The combined depth-stencil target: depth for the 3D layers, stencil for the region mask,
    /// and the attachment index it sits at.
    stencil: StencilTarget,
}

impl Swapchain {
    /// # Safety
    ///
    /// `context` must be live, and any previous swapchain must have been destroyed.
    pub unsafe fn new(context: &Context, width: u32, height: u32) -> Result<Swapchain, String> {
        let (width, height) = (width.max(1), height.max(1));
        let capabilities = context
            .surface_loader
            .get_physical_device_surface_capabilities(context.physical_device, context.surface)
            .map_err(|e| format!("surface capabilities {e:?}"))?;
        let formats = context
            .surface_loader
            .get_physical_device_surface_formats(context.physical_device, context.surface)
            .map_err(|e| format!("surface formats {e:?}"))?;
        let present_modes = context
            .surface_loader
            .get_physical_device_surface_present_modes(context.physical_device, context.surface)
            .map_err(|e| format!("present modes {e:?}"))?;

        // Prefer a plain UNORM format: Pixel's gralloc rejects B8G8R8A8_SRGB and the
        // result is a black screen with no error. See the module docs.
        let mut chosen = *formats.first().ok_or("the surface reports no formats")?;
        for candidate in &formats {
            if candidate.format == vk::Format::B8G8R8A8_UNORM
                || candidate.format == vk::Format::R8G8B8A8_UNORM
            {
                chosen = *candidate;
                break;
            }
        }

        let extent = if capabilities.current_extent.width == u32::MAX {
            vk::Extent2D { width, height }
        } else {
            vk::Extent2D {
                width: capabilities.current_extent.width.max(1),
                height: capabilities.current_extent.height.max(1),
            }
        };
        // FIFO is vsync, and the map is driven by Choreographer: anything else would only
        // queue frames nobody sees, at the cost of power.
        let present_mode = if present_modes.contains(&vk::PresentModeKHR::FIFO) {
            vk::PresentModeKHR::FIFO
        } else {
            present_modes[0]
        };
        let max = if capabilities.max_image_count == 0 {
            u32::MAX
        } else {
            capabilities.max_image_count
        };
        let image_count = (capabilities.min_image_count + 1).min(max);
        // IDENTITY where offered, so the presentation engine does not rotate an image we
        // already produced in display orientation.
        let pre_transform = if capabilities
            .supported_transforms
            .contains(vk::SurfaceTransformFlagsKHR::IDENTITY)
        {
            vk::SurfaceTransformFlagsKHR::IDENTITY
        } else {
            capabilities.current_transform
        };

        let loader = ash::khr::swapchain::Device::new(&context.instance, &context.device);
        let create_info = vk::SwapchainCreateInfoKHR::default()
            .surface(context.surface)
            .min_image_count(image_count)
            .image_format(chosen.format)
            .image_color_space(chosen.color_space)
            .image_extent(extent)
            .image_array_layers(1)
            .image_usage(vk::ImageUsageFlags::COLOR_ATTACHMENT)
            .image_sharing_mode(vk::SharingMode::EXCLUSIVE)
            .pre_transform(pre_transform)
            .composite_alpha(vk::CompositeAlphaFlagsKHR::OPAQUE)
            .present_mode(present_mode)
            .clipped(true);
        let swapchain = loader
            .create_swapchain(&create_info, None)
            .map_err(|e| format!("create_swapchain {e:?}"))?;
        let images = loader
            .get_swapchain_images(swapchain)
            .map_err(|e| format!("swapchain images {e:?}"))?;

        let samples = supported_samples(context);
        let multisampled = samples != vk::SampleCountFlags::TYPE_1;

        // One subpass. When multisampled the colour attachment is the transient MSAA
        // image (discarded after the resolve) and the swapchain image is the resolve
        // target; otherwise the swapchain image is the colour attachment directly.
        // Either way there are no mid-pass reads, so a tile-based GPU keeps the whole
        // frame in tile memory.
        let color = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(samples)
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(if multisampled {
                vk::AttachmentStoreOp::DONT_CARE
            } else {
                vk::AttachmentStoreOp::STORE
            })
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(if multisampled {
                vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL
            } else {
                vk::ImageLayout::PRESENT_SRC_KHR
            });
        let resolve = vk::AttachmentDescription::default()
            .format(chosen.format)
            .samples(vk::SampleCountFlags::TYPE_1)
            // The resolve writes every pixel, so there is nothing worth loading.
            .load_op(vk::AttachmentLoadOp::DONT_CARE)
            .store_op(vk::AttachmentStoreOp::STORE)
            .stencil_load_op(vk::AttachmentLoadOp::DONT_CARE)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::PRESENT_SRC_KHR);
        // Chosen before the render pass because the attachment has to name the format. A combined
        // depth-stencil format is required now that the depth aspect is used, and one is
        // universally available even where standalone `S8_UINT` is not.
        let stencil_format = stencil_format(context)?;
        let stencil = vk::AttachmentDescription::default()
            .format(stencil_format)
            .samples(samples)
            // Depth is cleared to the far plane (1.0) each frame and discarded after the subpass:
            // the 3D layers test/write it within the pass, nothing reads it afterwards, and on a
            // tile-based GPU a transient depth buffer never leaves tile memory.
            .load_op(vk::AttachmentLoadOp::CLEAR)
            .store_op(vk::AttachmentStoreOp::DONT_CARE)
            // Stencil is cleared to zero every frame: zero means "outside the selected region",
            // which is what the scrim tests for, and is also the right answer when nothing is
            // selected.
            .stencil_load_op(vk::AttachmentLoadOp::CLEAR)
            .stencil_store_op(vk::AttachmentStoreOp::DONT_CARE)
            .initial_layout(vk::ImageLayout::UNDEFINED)
            .final_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let attachments: Vec<vk::AttachmentDescription> = if multisampled {
            vec![color, resolve, stencil]
        } else {
            vec![color, stencil]
        };

        let color_ref = vk::AttachmentReference::default()
            .attachment(0)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let resolve_ref = vk::AttachmentReference::default()
            .attachment(1)
            .layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL);
        let stencil_ref = vk::AttachmentReference::default()
            .attachment(if multisampled { 2 } else { 1 })
            .layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        let mut subpass = vk::SubpassDescription::default()
            .pipeline_bind_point(vk::PipelineBindPoint::GRAPHICS)
            .color_attachments(std::slice::from_ref(&color_ref))
            .depth_stencil_attachment(&stencil_ref);
        if multisampled {
            subpass = subpass.resolve_attachments(std::slice::from_ref(&resolve_ref));
        }
        let dependency = vk::SubpassDependency::default()
            .src_subpass(vk::SUBPASS_EXTERNAL)
            .dst_subpass(0)
            .src_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_stage_mask(vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT)
            .dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
        let render_pass_info = vk::RenderPassCreateInfo::default()
            .attachments(&attachments)
            .subpasses(std::slice::from_ref(&subpass))
            .dependencies(std::slice::from_ref(&dependency));
        let render_pass = context
            .device
            .create_render_pass(&render_pass_info, None)
            .map_err(|e| format!("create_render_pass {e:?}"))?;

        let msaa = if multisampled {
            Some(MsaaTarget::new(context, chosen.format, extent, samples)?)
        } else {
            None
        };

        let stencil_target = StencilTarget::new(context, stencil_format, extent, samples)?;

        let mut views = Vec::with_capacity(images.len());
        let mut framebuffers = Vec::with_capacity(images.len());
        for &image in &images {
            let view_info = vk::ImageViewCreateInfo::default()
                .image(image)
                .view_type(vk::ImageViewType::TYPE_2D)
                .format(chosen.format)
                .subresource_range(vk::ImageSubresourceRange {
                    aspect_mask: vk::ImageAspectFlags::COLOR,
                    base_mip_level: 0,
                    level_count: 1,
                    base_array_layer: 0,
                    layer_count: 1,
                });
            let view = context
                .device
                .create_image_view(&view_info, None)
                .map_err(|e| format!("create_image_view {e:?}"))?;
            views.push(view);

            // Attachment order matches the render pass: colour, resolve, then stencil.
            let attached: Vec<vk::ImageView> = match &msaa {
                Some(target) => vec![target.view, view, stencil_target.view],
                None => vec![view, stencil_target.view],
            };
            let framebuffer_info = vk::FramebufferCreateInfo::default()
                .render_pass(render_pass)
                .attachments(&attached)
                .width(extent.width)
                .height(extent.height)
                .layers(1);
            framebuffers.push(
                context
                    .device
                    .create_framebuffer(&framebuffer_info, None)
                    .map_err(|e| format!("create_framebuffer {e:?}"))?,
            );
        }

        Ok(Swapchain {
            loader,
            swapchain,
            images,
            views,
            framebuffers,
            render_pass,
            format: chosen.format,
            extent,
            samples,
            msaa,
            stencil: stencil_target,
        })
    }

    /// # Safety
    ///
    /// The device must be idle.
    pub unsafe fn destroy(&mut self, device: &ash::Device) {
        for &framebuffer in &self.framebuffers {
            device.destroy_framebuffer(framebuffer, None);
        }
        self.framebuffers.clear();
        if let Some(target) = self.msaa.take() {
            device.destroy_image_view(target.view, None);
            device.destroy_image(target.image, None);
            device.free_memory(target.memory, None);
        }
        device.destroy_image_view(self.stencil.view, None);
        device.destroy_image(self.stencil.image, None);
        device.free_memory(self.stencil.memory, None);
        device.destroy_render_pass(self.render_pass, None);
        for &view in &self.views {
            device.destroy_image_view(view, None);
        }
        self.views.clear();
        self.loader.destroy_swapchain(self.swapchain, None);
    }
}
