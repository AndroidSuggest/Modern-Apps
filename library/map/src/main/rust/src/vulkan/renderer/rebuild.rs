use super::Renderer;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;

impl Renderer {
    pub fn resize(&mut self, width: u32, height: u32) {
        if width == self.width && height == self.height {
            return;
        }
        self.width = width;
        self.height = height;
        self.needs_rebuild = true;
    }

    /// Rebuild the swapchain after a resize, rotation or out-of-date present.
    pub(super) fn rebuild(&mut self) -> Result<(), String> {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            // The pipelines reference the old render pass, so they go with it. This is the
            // expensive part and the reason `pipeline_cache` exists: without it every one of the
            // twelve is recompiled from SPIR-V here, on the Choreographer callback, inside a
            // frame.
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            self.swapchain = Swapchain::new(&self.context, self.width, self.height)?;
            self.pipelines = Pipelines::new(
                &self.context.device,
                self.swapchain.render_pass,
                self.swapchain.samples,
                Some(self.atlas_set.layout),
                self.pipeline_cache.handle(),
            )?;
            // Only writes if the driver actually added something — a rebuild to the same sample
            // count adds nothing, so the steady state costs no I/O on the frame path.
            self.pipeline_cache.persist(&self.context.device);
        }
        self.width = self.swapchain.extent.width;
        self.height = self.swapchain.extent.height;
        Ok(())
    }
}

impl Drop for Renderer {
    fn drop(&mut self) {
        unsafe {
            let _ = self.context.device.device_wait_idle();
            for tile in self.tiles.values() {
                // Draw slices are plain indices into the pools — each pool is one pair.
                if let Some((v, i)) = &tile.flat {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some((v, i)) = &tile.lines {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some((v, i)) = &tile.ribbons {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(&self.context.device);
                    buildings.indices.destroy(&self.context.device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(&self.context.device);
                    terrain.indices.destroy(&self.context.device);
                }
            }
            self.tiles.clear();
            for (_, tile) in &self.retiring {
                if let Some((v, i)) = &tile.flat {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some((v, i)) = &tile.lines {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some((v, i)) = &tile.ribbons {
                    v.destroy(&self.context.device);
                    i.destroy(&self.context.device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(&self.context.device);
                    buildings.indices.destroy(&self.context.device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(&self.context.device);
                    terrain.indices.destroy(&self.context.device);
                }
            }
            self.retiring.clear();
            if let Some(route) = &self.route {
                route.vertices.destroy(&self.context.device);
                route.indices.destroy(&self.context.device);
            }
            if let Some(rails) = &self.rail_lines {
                rails.vertices.destroy(&self.context.device);
                rails.indices.destroy(&self.context.device);
            }
            for transient in &self.transients {
                transient.vbuf.destroy(&self.context.device);
                transient.ibuf.destroy(&self.context.device);
            }
            self.transients.clear();
            for ring in &self.scratch {
                ring.destroy(&self.context.device);
            }
            self.scratch.clear();
            for frame in &self.frames {
                self.context.device.destroy_fence(frame.in_flight, None);
                self.context
                    .device
                    .destroy_semaphore(frame.image_available, None);
                self.context
                    .device
                    .destroy_semaphore(frame.render_finished, None);
            }
            self.context
                .device
                .destroy_command_pool(self.command_pool, None);
            if let Some(image) = &self.glyph_atlas {
                image.destroy(&self.context.device);
            }
            if let Some(image) = &self.sprite_atlas {
                image.destroy(&self.context.device);
            }
            self.quad.vertices.destroy(&self.context.device);
            self.quad.indices.destroy(&self.context.device);
            self.pick.destroy(&self.context.device);
            self.pipeline_cache.destroy(&self.context.device);
            self.atlas_set.destroy(&self.context.device);
            self.pipelines.destroy(&self.context.device);
            self.swapchain.destroy(&self.context.device);
            // `context`'s own Drop destroys the device, surface and instance after this.
            if !self.window.is_null() {
                crate::vulkan::context::ANativeWindow_release(self.window);
                self.window = std::ptr::null_mut();
            }
        }
    }
}
