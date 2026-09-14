use super::{
    BuildingBuffers, CarriagewayBuffers, FRAMES_IN_FLIGHT, Frame, LayerBuffers, QUAD_INDICES,
    QUAD_VERTICES, Quad, RegionBuffers, Renderer, ResidentTile, RouteBuffers, TerrainBuffers,
    TrafficBuffers, TransientBuffers,
};
use crate::overlay::RouteMesh;
use crate::tile::geometry::TileMesh;
use crate::tile::select;
use crate::vulkan::buffers::{Buffer, ScratchRing};
use crate::vulkan::cache::ShaderCache;
use crate::vulkan::context::{ANativeWindow, Context};
use crate::vulkan::images::{AtlasSet, SampledImage};
use crate::vulkan::pick::Pick;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::swapchain::Swapchain;
use ash::vk;
use std::cell::Cell;
use std::collections::{HashMap, HashSet};

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
            match try_upload_glyph_atlas(&context, &atlas_set, command_pool) {
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
            match try_upload_sprite_atlas(&context, &atlas_set, command_pool) {
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

    /// Draw `mesh` as the navigation route, or take the route away with `None`.
    ///
    /// Pure state like [`set_user_puck`](Self::set_user_puck), and for a stronger reason:
    /// a route arrives once when the driver starts navigating and then does not change
    /// for the rest of the trip, so it has no business being an argument on
    /// [`render`](Self::render).
    ///
    /// The old buffers go through the same frames-in-flight grace queue the transient
    /// symbol buffers use — a command buffer submitted last frame may still be reading
    /// them, and freeing a live vertex buffer is the classic Vulkan use-after-free.
    ///
    /// On upload failure the route is left cleared rather than half-set, so a device that
    /// cannot allocate draws no route instead of a route with no indices.
    pub fn set_route(&mut self, mesh: Option<&RouteMesh>) -> Result<(), String> {
        if let Some(previous) = self.route.take() {
            self.transients.push(TransientBuffers {
                vbuf: previous.vertices,
                ibuf: previous.indices,
                frames: FRAMES_IN_FLIGHT,
            });
        }
        let Some(mesh) = mesh else { return Ok(()) };
        if mesh.indices.is_empty() {
            return Ok(());
        }
        unsafe {
            let vertices = Buffer::upload(
                &self.context.instance,
                self.context.physical_device,
                &self.context.device,
                vk::BufferUsageFlags::VERTEX_BUFFER,
                &mesh.vertices,
            )?;
            let indices = match Buffer::upload(
                &self.context.instance,
                self.context.physical_device,
                &self.context.device,
                vk::BufferUsageFlags::INDEX_BUFFER,
                &mesh.indices,
            ) {
                Ok(buffer) => buffer,
                Err(e) => {
                    vertices.destroy(&self.context.device);
                    return Err(e);
                }
            };
            self.route = Some(RouteBuffers {
                placement: mesh.placement,
                vertices,
                indices,
                index_count: mesh.indices.len() as u32,
                segments: mesh.segments.clone(),
            });
        }
        Ok(())
    }

    /// Upload a tile's geometry, replacing anything already resident for it.
    pub fn upload(&mut self, key: u64, mesh: &TileMesh) -> Result<(), String> {
        let mut layers = Vec::with_capacity(mesh.meshes.len());
        for layer_mesh in &mesh.meshes {
            if layer_mesh.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &layer_mesh.vertices,
                )?;
                let indices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &layer_mesh.indices,
                )?;
                layers.push(LayerBuffers {
                    layer_index: layer_mesh.layer_index,
                    kind: layer_mesh.kind,
                    vertices,
                    indices,
                    index_count: layer_mesh.indices.len() as u32,
                    color_override: layer_mesh.color_override,
                    lane: layer_mesh.lane,
                });
            }
        }
        let mut regions = Vec::with_capacity(mesh.regions.len());
        for region in &mesh.regions {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &region.vertices,
                )?;
                let indices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &region.indices,
                )?;
                regions.push(RegionBuffers {
                    id: region.id,
                    vertices,
                    indices,
                    index_count: region.indices.len() as u32,
                    rings: region.rings.clone(),
                    area: region.area,
                    level: region.level,
                });
            }
        }
        let mut traffic = Vec::with_capacity(mesh.traffic.len());
        for segment in &mesh.traffic {
            if segment.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &segment.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &segment.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                traffic.push(TrafficBuffers {
                    id: segment.id,
                    vertices,
                    indices,
                    index_count: segment.indices.len() as u32,
                });
            }
        }
        // The tile's road carriageways: one mesh per distinct road shape, uploaded like the
        // traffic segments above.
        let mut carriageways = Vec::with_capacity(mesh.carriageways.len());
        for road in &mesh.carriageways {
            if road.indices.is_empty() {
                continue;
            }
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &road.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &road.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                carriageways.push(CarriagewayBuffers {
                    layer_index: road.layer_index,
                    lanes: road.lanes,
                    split: road.split,
                    oneway: road.oneway,
                    vertices,
                    indices,
                    index_count: road.indices.len() as u32,
                });
            }
        }
        // The tile's 3D buildings, if any: one combined mesh, uploaded like the rest.
        let buildings = if mesh.buildings.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.buildings.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.buildings.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(BuildingBuffers {
                    vertices,
                    indices,
                    index_count: mesh.buildings.indices.len() as u32,
                })
            }
        };
        // The tile's terrain grid, if any: one combined mesh, uploaded like the buildings above.
        let terrain = if mesh.terrain.indices.is_empty() {
            None
        } else {
            unsafe {
                let vertices = Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::VERTEX_BUFFER,
                    &mesh.terrain.vertices,
                )?;
                let indices = match Buffer::upload(
                    &self.context.instance,
                    self.context.physical_device,
                    &self.context.device,
                    vk::BufferUsageFlags::INDEX_BUFFER,
                    &mesh.terrain.indices,
                ) {
                    Ok(buffer) => buffer,
                    Err(e) => {
                        vertices.destroy(&self.context.device);
                        return Err(e);
                    }
                };
                Some(TerrainBuffers {
                    vertices,
                    indices,
                    index_count: mesh.terrain.indices.len() as u32,
                })
            }
        };
        let tile = ResidentTile {
            layers,
            buildings,
            terrain,
            regions,
            traffic,
            carriageways,
            yellow_centre: mesh.yellow_centre,
            labels: mesh.labels.clone(),
            arrows: mesh.arrows.clone(),
            z: mesh.z,
            x: mesh.x,
            y: mesh.y,
            // Stamp the tile with the latest frame clock so the fade below measures from the
            // moment its GPU buffers landed. Before the first frame there is no clock yet;
            // 0.0 makes `now - uploaded_at` large, so a startup tile is fully opaque at once
            // (nothing is under it to fade over anyway).
            uploaded_at: self.last_camera.map(|c| c.time_seconds).unwrap_or(0.0),
            generation: mesh.generation,
        };
        if let Some(previous) = self.tiles.insert(key, tile) {
            self.retire(previous);
        }
        Ok(())
    }

    /// Drop resident tiles that are neither wanted nor useful as a stand-in, and enforce the
    /// residency cap.
    ///
    /// `keep` is the visible tiles and their ancestors, in the order [`select::resident_set`]
    /// produced them — coarsest first. `visible` is needed separately because descendants cannot
    /// be enumerated into a keep list without naming tiles that were never fetched; they are
    /// recognised here, against what is actually resident.
    ///
    /// This is also the **only** bound on GPU memory. Tiles live until they fall out of this, so
    /// the cap is not belt-and-braces: without it, retaining descendants would mean every deep
    /// tile visited during a session stays resident for as long as the camera sits above it.
    pub fn retain(&mut self, keep: &[u64], visible: &[select::TileId], cap: usize) {
        let visible_keys: HashSet<u64> = visible.iter().map(|t| t.key()).collect();
        let wanted: HashSet<u64> = keep.iter().copied().collect();

        // Rank by how much is lost if it goes, because the cap has to evict *something* and the
        // stand-ins are what it should reach for first.
        let mut ranked: Vec<(u8, u64)> = self
            .tiles
            .keys()
            .map(|&key| {
                let rank = if visible_keys.contains(&key) {
                    0 // on screen at its own zoom; evicting this is the blank frame itself
                } else if wanted.contains(&key) {
                    1 // an ancestor: one coarse tile covers many fine ones, so cheap to hold
                } else if select::stands_in_for_visible(key, visible, select::DESCENDANT_DEPTH) {
                    2 // a descendant: only covers a fraction of the screen, so the first to go
                } else {
                    3 // unrelated to anything on screen
                };
                (rank, key)
            })
            .collect();
        ranked.sort_unstable();

        let doomed: Vec<u64> = ranked
            .iter()
            .enumerate()
            .filter(|(at, (rank, _))| *rank == 3 || *at >= cap)
            .map(|(_, (_, key))| *key)
            .collect();
        for key in doomed {
            if let Some(tile) = self.tiles.remove(&key) {
                self.retire(tile);
            }
        }
    }

    /// Hold a tile's buffers until every in-flight frame that might reference them has
    /// finished.
    ///
    /// Freeing them immediately is the classic Vulkan use-after-free: a command buffer
    /// submitted last frame can still be executing, and destroying its vertex buffer is
    /// undefined behaviour that usually looks like corrupted geometry rather than a crash.
    fn retire(&mut self, tile: ResidentTile) {
        self.retiring.push((FRAMES_IN_FLIGHT, tile));
    }

    /// Free anything whose grace period has expired.
    pub(super) fn collect_retired(&mut self) {
        let device = &self.context.device;
        self.retiring.retain_mut(|(remaining, tile)| {
            if *remaining > 0 {
                *remaining -= 1;
                return true;
            }
            unsafe {
                for layer in &tile.layers {
                    layer.vertices.destroy(device);
                    layer.indices.destroy(device);
                }
                for region in &tile.regions {
                    region.vertices.destroy(device);
                    region.indices.destroy(device);
                }
                for segment in &tile.traffic {
                    segment.vertices.destroy(device);
                    segment.indices.destroy(device);
                }
                for road in &tile.carriageways {
                    road.vertices.destroy(device);
                    road.indices.destroy(device);
                }
                if let Some(buildings) = &tile.buildings {
                    buildings.vertices.destroy(device);
                    buildings.indices.destroy(device);
                }
                if let Some(terrain) = &tile.terrain {
                    terrain.vertices.destroy(device);
                    terrain.indices.destroy(device);
                }
            }
            false
        });
        // Transient per-frame symbol buffers retire on the same grace count, in
        // their own queue — no device_wait_idle stall on the frame path.
        self.transients.retain_mut(|t| {
            if t.frames > 0 {
                t.frames -= 1;
                return true;
            }
            unsafe {
                t.vbuf.destroy(device);
                t.ibuf.destroy(device);
            }
            false
        });
    }
}

/// Upload the process-global glyph atlas and allocate its descriptor set.
///
/// Separate from [`Renderer::new`] so the error paths read linearly. Called once
/// at startup; the bytes come from `tile::glyph::atlas()` (SDF R8 built from the
/// bundled Noto Sans at first use).
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
unsafe fn try_upload_glyph_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::glyph;
    if !glyph::fonts_staged() {
        return Err("bundled fonts are not valid TTFs".into());
    }
    let atlas = glyph::atlas();
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        glyph::ATLAS_PX,
        glyph::ATLAS_PX,
        vk::Format::R8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}

/// Upload the process-global POI sprite sheet and allocate its descriptor set.
///
/// [`try_upload_glyph_atlas`]'s counterpart, and the second of the two sets
/// [`AtlasSet`]'s pool is sized for. The sheet is already RGBA8, so
/// [`SampledImage::upload`] takes it unchanged — no expansion step like the glyph
/// atlas's R8 one.
///
/// # Safety
///
/// Same rules as the surrounding constructors: live device, idle queue.
unsafe fn try_upload_sprite_atlas(
    context: &Context,
    atlas_set: &AtlasSet,
    command_pool: vk::CommandPool,
) -> Result<(SampledImage, vk::DescriptorSet), String> {
    use crate::tile::sprite;
    let atlas = sprite::atlas();
    // An empty atlas is what `sprite::atlas()` leaves behind when the sheet will not
    // decode; uploading a zero-sized image would fail in the driver instead of here.
    if atlas.is_empty() {
        return Err("the sprite sheet carries no icons".into());
    }
    let image = SampledImage::upload(
        &context.instance,
        context.physical_device,
        &context.device,
        context.queue,
        context.queue_family_index,
        command_pool,
        &atlas.pixels,
        atlas.width,
        atlas.height,
        vk::Format::R8G8B8A8_UNORM,
    )?;
    let set = atlas_set.allocate(&context.device, &image)?;
    Ok((image, set))
}
