use super::{FRAMES_IN_FLIGHT, Frame, QUAD_INDICES, QUAD_VERTICES, Quad, Renderer};
use crate::vulkan::buffers::{Buffer, ScratchRing};
use crate::vulkan::cache::ShaderCache;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::AtlasSet;
use crate::vulkan::pick::Pick;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::HashMap;

impl Renderer {
    /// # Safety
    ///
    /// `window` must be an acquired `ANativeWindow`; the renderer releases it on drop.
    pub unsafe fn new(
        window: *mut ANativeWindow,
        width: u32,
        height: u32,
        cache_dir: &std::path::Path,
    ) -> Result<Renderer, String> {
        let context = Context::new(window)?;
        let swapchain = Swapchain::new(&context, width, height)?;
        let atlas_set = AtlasSet::new(&context.device)?;
        // Before any pipeline is created, so the very first launch's twelve compiles are the ones
        // that get recorded. Its own subdirectory of `cache_dir`, not `cache_dir` itself: the tile
        // range cache deletes every *file* in its directory when the archive origin changes
        // (`tile::cache::RangeCache::invalidate_on_origin_change`), so a blob there would be wiped
        // by an unrelated archive republish and read as a random cold start. That deletion is
        // `remove_file`, which does not recurse into a subdirectory.
        let cache_path = cache_dir.join("pipeline");
        let mut pipeline_cache = ShaderCache::open(
            &context.instance,
            context.physical_device,
            &context.device,
            Some(&cache_path),
        );
        let pipelines = Pipelines::new(
            &context.device,
            swapchain.render_pass,
            swapchain.samples,
            Some(atlas_set.layout),
            pipeline_cache.handle(),
        )?;

        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            // Each frame's buffer is re-recorded every frame, so it must be individually
            // resettable rather than requiring a whole-pool reset.
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        let command_pool = context
            .device
            .create_command_pool(&pool_info, None)
            .map_err(|e| format!("create_command_pool {e:?}"))?;
        // The glyph atlas is a process-global built from the bundled fonts; upload
        // it now so every symbol draw can bind it. Failure is non-fatal: labels
        // simply don't draw until a build with working fonts (see fonts_staged).
        // Logging goes through eprintln: bridge::log needs `__android_log_write`,
        // which links on device but not on the host test binary.
        let (glyph_atlas, glyph_set) = unsafe {
            match super::upload::try_upload_glyph_atlas(&context, &atlas_set, command_pool) {
                Ok((image, set)) => (Some(image), Some(set)),
                Err(e) => {
                    eprintln!("glyph atlas upload skipped: {e}");
                    (None, None)
                }
            }
        };
        // The POI sprite sheet, on the same terms: a failure costs icons, not the map.
        // It takes the second of the two sets `AtlasSet` sizes its pool for.
        let (sprite_atlas, sprite_set) = unsafe {
            match super::upload::try_upload_sprite_atlas(&context, &atlas_set, command_pool) {
                Ok((image, set)) => (Some(image), Some(set)),
                Err(e) => {
                    eprintln!("sprite atlas upload skipped: {e}");
                    (None, None)
                }
            }
        };

        let allocate = vk::CommandBufferAllocateInfo::default()
            .command_pool(command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(FRAMES_IN_FLIGHT as u32);
        let command_buffers = context
            .device
            .allocate_command_buffers(&allocate)
            .map_err(|e| format!("allocate_command_buffers {e:?}"))?;

        let mut frames = Vec::with_capacity(FRAMES_IN_FLIGHT);
        for &command_buffer in &command_buffers {
            // Created signalled, so the first frame does not wait on a fence nothing has
            // submitted to.
            let fence_info =
                vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
            let semaphore_info = vk::SemaphoreCreateInfo::default();
            frames.push(Frame {
                command_buffer,
                in_flight: context
                    .device
                    .create_fence(&fence_info, None)
                    .map_err(|e| format!("create_fence {e:?}"))?,
                image_available: context
                    .device
                    .create_semaphore(&semaphore_info, None)
                    .map_err(|e| format!("create_semaphore {e:?}"))?,
                render_finished: context
                    .device
                    .create_semaphore(&semaphore_info, None)
                    .map_err(|e| format!("create_semaphore {e:?}"))?,
            });
        }

        // Read the extent before the swapchain moves into the struct: the surface may have
        // given us a different size than we asked for.
        let width = swapchain.extent.width;
        let height = swapchain.extent.height;

        // The overlay geometry, uploaded once and never touched again. Unlike the
        // atlases this is not optional: it is 32 bytes of vertices, and a device that
        // cannot allocate that cannot draw a tile either.
        let quad = Quad {
            vertices: Buffer::upload(
                &context.instance,
                context.physical_device,
                &context.device,
                vk::BufferUsageFlags::VERTEX_BUFFER,
                &QUAD_VERTICES,
            )?,
            indices: Buffer::upload(
                &context.instance,
                context.physical_device,
                &context.device,
                vk::BufferUsageFlags::INDEX_BUFFER,
                &QUAD_INDICES,
            )?,
        };

        // The offscreen id-buffer pass for tap picking. Self-contained and format-stable, so it is
        // built once here and survives swapchain rebuilds (only its target is resized).
        let pick = Pick::new(&context, pipeline_cache.handle())?;
        // Everything that compiles a shader has now run once. On a cold cache this is the write
        // that makes every later launch cheap; on a warm one the blob has not grown and this does
        // nothing.
        pipeline_cache.persist(&context.device);

        Ok(Renderer {
            context,
            swapchain,
            pipelines,
            atlas_set,
            glyph_atlas,
            glyph_set,
            sprite_atlas,
            sprite_set,
            command_pool,
            frames,
            frame_index: 0,
            tiles: HashMap::new(),
            retiring: Vec::new(),
            pipeline_cache,
            transients: Vec::new(),
            scratch: (0..FRAMES_IN_FLIGHT).map(|_| ScratchRing::default()).collect(),
            window,
            width,
            height,
            needs_rebuild: false,
            submitted_draws: Cell::new(0),
            placed: std::cell::RefCell::new(Vec::new()),
            placement_cache: std::cell::RefCell::new(None),
            overlays: Vec::new(),
            quad,
            selected_region: None,
            route: None,
            traffic_colors: HashMap::new(),
            traffic_enabled: false,
            pick,
            last_camera: None,
        })
    }
}
