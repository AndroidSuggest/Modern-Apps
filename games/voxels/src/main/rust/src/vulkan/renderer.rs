use ash::vk;
use std::collections::HashMap;
use glam::{Mat4, Vec3};
use crate::world::mesher::MeshData;
use crate::vulkan::context::VulkanContext;
use crate::vulkan::swapchain::Swapchain;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::buffers::{AllocatedBuffer, Vertex as VulkanVertex, UboData};
use crate::texture_atlas::TextureAtlas;

pub struct ChunkGpuMesh {
    pub vertex_buffer: AllocatedBuffer,
    pub index_buffer: AllocatedBuffer,
    pub index_count: u32,
    pub water_vertex_buffer: Option<AllocatedBuffer>,
    pub water_index_buffer: Option<AllocatedBuffer>,
    pub water_index_count: u32,
}

fn smoothstep(e0: f32, e1: f32, x: f32) -> f32 { let t = ((x - e0) / (e1 - e0)).clamp(0.0, 1.0); t * t * (3.0 - 2.0 * t) }

// Gribb-Hartmann frustum planes from a clip matrix (glam column-major, Vulkan z in [0,1]).
fn frustum_planes(m: Mat4) -> [glam::Vec4; 6] {
    let (r0, r1, r2, r3) = (m.row(0), m.row(1), m.row(2), m.row(3));
    [r3 + r0, r3 - r0, r3 + r1, r3 - r1, r2, r3 - r2]
}
fn aabb_in_frustum(planes: &[glam::Vec4; 6], min: Vec3, max: Vec3) -> bool {
    for p in planes {
        let px = if p.x >= 0.0 { max.x } else { min.x };
        let py = if p.y >= 0.0 { max.y } else { min.y };
        let pz = if p.z >= 0.0 { max.z } else { min.z };
        if p.x * px + p.y * py + p.z * pz + p.w < 0.0 { return false; }
    }
    true
}

pub struct VulkanRenderer {
    pub ctx: VulkanContext,
    pub swapchain: Swapchain,
    pub atlas: TextureAtlas,
    pub shadow: crate::vulkan::shadow::ShadowMap,
    pub postfx: crate::vulkan::postfx::PostFx,
    pub pipelines: Pipelines,
    pub command_pool: vk::CommandPool,
    pub command_buffers: Vec<vk::CommandBuffer>,
    pub image_available: Vec<vk::Semaphore>,
    pub render_finished: Vec<vk::Semaphore>,
    pub in_flight_fences: Vec<vk::Fence>,
    pub ubo_buffer: AllocatedBuffer,
    pub ubo_data: UboData,
    pub queued_meshes: HashMap<(i32,i32,i32), Option<MeshData>>,
    pub gpu_meshes: HashMap<(i32,i32,i32), ChunkGpuMesh>,
    pub current_frame: usize,
    pub frame_count: u64,
    pub width: u32,
    pub height: u32,
    // GPU profiling (timestamp queries): [shadow, main, bloom, composite] ms, plus draw stats.
    pub query_pool: vk::QueryPool,
    pub ts_period: f32,
    pub ts_written: Vec<bool>,
    pub pass_ms: [f32; 4],
    pub drawn_sections: u32,
}

impl VulkanRenderer {
    pub unsafe fn new(ctx: VulkanContext, width: u32, height: u32) -> Result<Self, String> {
        let swapchain = Swapchain::new(&ctx, width.max(1), height.max(1))?;
        let ubo_size = std::mem::size_of::<UboData>() as u64;
        let instance = ctx.instance.clone();
        let device = ctx.device.clone();
        let phys = ctx.physical_device;
        let pool_info = vk::CommandPoolCreateInfo::default().flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER).queue_family_index(ctx.queue_family_index);
        let command_pool = device.create_command_pool(&pool_info, None).map_err(|e| format!("pool {e:?}"))?;
        let atlas_pixels = crate::texture_atlas::load_atlas_bin();
        let atlas = unsafe { TextureAtlas::new(&instance, &device, phys, command_pool, ctx.queue, &atlas_pixels)? };
        let shadow = unsafe { crate::vulkan::shadow::ShadowMap::new(&instance, &device, phys)? };
        let postfx = unsafe { crate::vulkan::postfx::PostFx::new(&instance, &device, phys, &swapchain, vk::Format::D32_SFLOAT)? };
        let ubo_buffer = unsafe { AllocatedBuffer::new(&instance, &device, phys, ubo_size, vk::BufferUsageFlags::UNIFORM_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)? };
        let pipelines = unsafe { Pipelines::new(&device, postfx.main_rp, shadow.render_pass, swapchain.render_pass, postfx.post_rp, postfx.desc_layout, ubo_buffer.buffer, ubo_size, atlas.view, atlas.sampler, shadow.view, shadow.sampler)? };
        let alloc_info = vk::CommandBufferAllocateInfo::default().command_pool(command_pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(swapchain.images.len() as u32);
        let command_buffers = device.allocate_command_buffers(&alloc_info).map_err(|e| format!("alloc cmd {e:?}"))?;
        let sem_info = vk::SemaphoreCreateInfo::default();
        let fence_info = vk::FenceCreateInfo::default().flags(vk::FenceCreateFlags::SIGNALED);
        let mut image_available = Vec::new();
        let mut render_finished = Vec::new();
        let mut in_flight_fences = Vec::new();
        for _ in 0..swapchain.images.len() {
            image_available.push(device.create_semaphore(&sem_info, None).map_err(|e| format!("sem {e:?}"))?);
            render_finished.push(device.create_semaphore(&sem_info, None).map_err(|e| format!("sem {e:?}"))?);
            in_flight_fences.push(device.create_fence(&fence_info, None).map_err(|e| format!("fence {e:?}"))?);
        }
        let img_count = swapchain.images.len();
        let query_pool = device.create_query_pool(&vk::QueryPoolCreateInfo::default().query_type(vk::QueryType::TIMESTAMP).query_count(img_count as u32 * 5), None).map_err(|e| format!("query pool {e:?}"))?;
        let ts_period = ctx.device_properties.limits.timestamp_period;
        Ok(Self { ctx, swapchain, atlas, shadow, postfx, pipelines, command_pool, command_buffers, image_available, render_finished, in_flight_fences, ubo_buffer, ubo_data: UboData::default(), queued_meshes: HashMap::new(), gpu_meshes: HashMap::new(), current_frame: 0, frame_count: 0, width, height, query_pool, ts_period, ts_written: vec![false; img_count], pass_ms: [0.0;4], drawn_sections: 0 })
    }
    pub unsafe fn resize(&mut self, width: u32, height: u32) -> Result<(), String> {
        if width==0 || height==0 { return Ok(()); }
        self.ctx.device.device_wait_idle().map_err(|e| format!("wait {e:?}"))?;
        self.swapchain.cleanup(&self.ctx.device);
        self.swapchain = Swapchain::new(&self.ctx, width, height)?;
        self.postfx.resize(&self.ctx.instance, &self.ctx.device, self.ctx.physical_device, &self.swapchain)?;
        self.width = width; self.height = height;
        self.ctx.device.free_command_buffers(self.command_pool, &self.command_buffers);
        let alloc_info = vk::CommandBufferAllocateInfo::default().command_pool(self.command_pool).level(vk::CommandBufferLevel::PRIMARY).command_buffer_count(self.swapchain.images.len() as u32);
        self.command_buffers = self.ctx.device.allocate_command_buffers(&alloc_info).map_err(|e| format!("alloc resize {e:?}"))?;
        Ok(())
    }
    pub unsafe fn enqueue_mesh(&mut self, chunk_x: i32, sec_y: i32, chunk_z: i32, mesh: Option<MeshData>) {
        self.queued_meshes.insert((chunk_x, sec_y, chunk_z), mesh);
    }
    pub unsafe fn process_pending_uploads(&mut self) -> Result<(), String> {
        let mut processed=0;
        let keys: Vec<_> = self.queued_meshes.keys().copied().collect();
        for key in keys {
            if processed>=12 { break; }
            if let Some(opt) = self.queued_meshes.remove(&key) {
                if let Some(mut old) = self.gpu_meshes.remove(&key) {
                    self.ctx.device.device_wait_idle().ok();
                    old.vertex_buffer.destroy(&self.ctx.device);
                    old.index_buffer.destroy(&self.ctx.device);
                    if let Some(mut wb) = old.water_vertex_buffer.take() { wb.destroy(&self.ctx.device); }
                    if let Some(mut wb) = old.water_index_buffer.take() { wb.destroy(&self.ctx.device); }
                }
                if let Some(mesh) = opt {
                    if mesh.is_empty() { processed+=1; continue; }
                    let vbytes = unsafe { std::slice::from_raw_parts(mesh.vertices.as_ptr() as *const u8, mesh.vertices.len() * std::mem::size_of::<VulkanVertex>()) };
                    let ibytes = unsafe { std::slice::from_raw_parts(mesh.indices.as_ptr() as *const u8, mesh.indices.len()*4) };
                    let instance=self.ctx.instance.clone(); let device=self.ctx.device.clone(); let phys=self.ctx.physical_device;
                    let vb = AllocatedBuffer::new(&instance, &device, phys, vbytes.len() as u64, vk::BufferUsageFlags::VERTEX_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)?;
                    vb.upload(&device, vbytes);
                    let ib = AllocatedBuffer::new(&instance, &device, phys, ibytes.len() as u64, vk::BufferUsageFlags::INDEX_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)?;
                    ib.upload(&device, ibytes);
                    // Water surface buffers (optional).
                    let (mut wvb, mut wib, mut wcount) = (None, None, 0u32);
                    if !mesh.water_vertices.is_empty() {
                        let wvbytes = unsafe { std::slice::from_raw_parts(mesh.water_vertices.as_ptr() as *const u8, mesh.water_vertices.len() * std::mem::size_of::<VulkanVertex>()) };
                        let wibytes = unsafe { std::slice::from_raw_parts(mesh.water_indices.as_ptr() as *const u8, mesh.water_indices.len()*4) };
                        let w_v = AllocatedBuffer::new(&instance, &device, phys, wvbytes.len() as u64, vk::BufferUsageFlags::VERTEX_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)?;
                        w_v.upload(&device, wvbytes);
                        let w_i = AllocatedBuffer::new(&instance, &device, phys, wibytes.len() as u64, vk::BufferUsageFlags::INDEX_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)?;
                        w_i.upload(&device, wibytes);
                        wvb = Some(w_v); wib = Some(w_i); wcount = mesh.water_indices.len() as u32;
                    }
                    self.gpu_meshes.insert(key, ChunkGpuMesh{ vertex_buffer: vb, index_buffer: ib, index_count: mesh.indices.len() as u32, water_vertex_buffer: wvb, water_index_buffer: wib, water_index_count: wcount });
                }
                processed+=1;
            }
        }
        Ok(())
    }
    pub unsafe fn update_ubo(&mut self, view_proj: Mat4, player_pos: Vec3, time: f32, underwater: f32) {
        let day_cycle=120.0; let day_t=(time/day_cycle)%1.0;
        let sun_angle=day_t*std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
        let sun_dir=Vec3::new(sun_angle.cos()*0.3, sun_angle.sin(), sun_angle.cos()*0.1).normalize_or_zero();
        let day_factor=(sun_dir.y*0.5+0.5).clamp(0.0,1.0).powf(0.6);
        // Horizon sky colour approximating the sky shader, so distance fog melts terrain into the sky.
        let sun_up = smoothstep(-0.10, 0.22, sun_dir.y);
        let day_sky = Vec3::new(0.52, 0.66, 0.86);
        let sunset = Vec3::new(0.85, 0.45, 0.28);
        let night = Vec3::new(0.03, 0.04, 0.09);
        let horizon_day = day_sky.lerp(sunset, (1.0 - sun_up) * 0.7);
        let fog_color = night.lerp(horizon_day, sun_up);
        // Shared surface lighting (same sun the sky/clouds use). Warm at sunrise/sunset, white at noon.
        // Kept modest so daylight doesn't blow out to white after tonemapping.
        let sun_color = Vec3::new(1.0, 0.45, 0.20).lerp(Vec3::new(1.0, 0.96, 0.88), sun_up) * (1.5 * sun_up + 0.03);
        // Sky-dome ambient: blue-ish in day, dark at night; keeps shadowed faces from going black.
        let ambient_color = Vec3::new(0.04, 0.05, 0.09).lerp(Vec3::new(0.34, 0.40, 0.52), sun_up);
        let inv_view_proj = view_proj.inverse();
        // Sun shadow map: orthographic view from the sun toward the player, covering a box around them.
        let up = if sun_dir.y.abs() > 0.99 { Vec3::Z } else { Vec3::Y };
        let light_dist = 140.0;
        let light_eye = player_pos + sun_dir * light_dist;
        let light_view = Mat4::look_at_rh(light_eye, player_pos, up);
        let half = 90.0;
        let light_proj = Mat4::orthographic_rh(-half, half, -half, half, 1.0, light_dist * 2.0);
        let light_view_proj = light_proj * light_view;
        self.ubo_data=UboData{ view_proj: view_proj.to_cols_array_2d(), sun_dir: sun_dir.to_array(), time, fog_color: fog_color.to_array(), fog_density: 0.008 / (1.0+day_factor*0.8).max(0.2), player_pos: [player_pos.x, player_pos.y, player_pos.z, underwater], day_factor, _pad: [0.0;3], inv_view_proj: inv_view_proj.to_cols_array_2d(), sun_color: sun_color.to_array(), cloud_shadow: 0.6, ambient_color: ambient_color.to_array(), _pad2: 0.0, light_view_proj: light_view_proj.to_cols_array_2d() };
        let bytes=unsafe { std::slice::from_raw_parts((&self.ubo_data as *const UboData) as *const u8, std::mem::size_of::<UboData>()) };
        self.ubo_buffer.upload(&self.ctx.device, bytes);
    }
    pub unsafe fn draw_frame(&mut self) -> Result<bool, String> {
        let device=self.ctx.device.clone();
        let fence=self.in_flight_fences[self.current_frame];
        unsafe { device.wait_for_fences(std::slice::from_ref(&fence), true, u64::MAX).map_err(|e| format!("wait {e:?}"))?; device.reset_fences(std::slice::from_ref(&fence)).map_err(|e| format!("reset {e:?}"))?; }
        // Read last frame's GPU timestamps for this slot (its fence just signaled) -> per-pass ms.
        let ts_base = (self.current_frame * 5) as u32;
        if self.ts_written[self.current_frame] && self.ts_period > 0.0 {
            let mut ts = [0u64; 5];
            if unsafe { device.get_query_pool_results(self.query_pool, ts_base, &mut ts, vk::QueryResultFlags::TYPE_64) }.is_ok() {
                let ms = |a: u64, b: u64| (b.wrapping_sub(a) as f64 * self.ts_period as f64 / 1_000_000.0) as f32;
                self.pass_ms = [ms(ts[0],ts[1]), ms(ts[1],ts[2]), ms(ts[2],ts[3]), ms(ts[3],ts[4])];
            }
        }
        self.drawn_sections = 0;
        let frustum = frustum_planes(Mat4::from_cols_array_2d(&self.ubo_data.view_proj));
        let image_index=match unsafe { self.swapchain.loader.acquire_next_image(self.swapchain.swapchain, u64::MAX, self.image_available[self.current_frame], vk::Fence::null()) } {
            Ok((idx,_))=>idx,
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR)=>{ if self.width>0 && self.height>0 { self.resize(self.width,self.height)?; } return Ok(false); }
            Err(e)=>return Err(format!("acquire {e:?}")),
        };
        unsafe { self.process_pending_uploads()?; }
        let cmd=self.command_buffers[image_index as usize];
        let begin_info=vk::CommandBufferBeginInfo::default().flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT);
        unsafe {
            device.begin_command_buffer(cmd, &begin_info).map_err(|e| format!("begin {e:?}"))?;
            device.cmd_reset_query_pool(cmd, self.query_pool, ts_base, 5);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::TOP_OF_PIPE, self.query_pool, ts_base);
            // --- Shadow pass: render terrain depth from the sun into the shadow map. ---
            let sdim = crate::vulkan::shadow::SHADOW_DIM;
            let shadow_clear = vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
            let shadow_begin = vk::RenderPassBeginInfo::default().render_pass(self.shadow.render_pass).framebuffer(self.shadow.framebuffer).render_area(vk::Rect2D { offset: vk::Offset2D { x:0, y:0 }, extent: vk::Extent2D { width: sdim, height: sdim } }).clear_values(std::slice::from_ref(&shadow_clear));
            device.cmd_begin_render_pass(cmd, &shadow_begin, vk::SubpassContents::INLINE);
            let svp = vk::Viewport::default().x(0.0).y(0.0).width(sdim as f32).height(sdim as f32).min_depth(0.0).max_depth(1.0);
            let ssc = vk::Rect2D::default().extent(vk::Extent2D { width: sdim, height: sdim });
            device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&svp));
            device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&ssc));
            device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.layout, 0, std::slice::from_ref(&self.pipelines.descriptor_set), &[]);
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.shadow_pipeline);
            {
                // Cull to the sun's orthographic shadow box (±90m), not a wide 160m circle: the box is
                // all the shadow map can hold, so anything outside it was being drawn only to be clipped.
                let light_frustum = frustum_planes(Mat4::from_cols_array_2d(&self.ubo_data.light_view_proj));
                for ((cx,sec_y,cz), gpu_mesh) in self.gpu_meshes.iter() {
                    if gpu_mesh.index_count == 0 { continue; }
                    let mn = Vec3::new(*cx as f32*16.0, *sec_y as f32*16.0, *cz as f32*16.0);
                    if !aabb_in_frustum(&light_frustum, mn, mn + Vec3::splat(16.0)) { continue; }
                    let offs=[0u64];
                    device.cmd_bind_vertex_buffers(cmd, 0, std::slice::from_ref(&gpu_mesh.vertex_buffer.buffer), &offs);
                    device.cmd_bind_index_buffer(cmd, gpu_mesh.index_buffer.buffer, 0, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(cmd, gpu_mesh.index_count, 1, 0, 0, 0);
                }
            }
            device.cmd_end_render_pass(cmd);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.query_pool, ts_base + 1); // shadow done
            let ext = self.swapchain.extent;
            let hdr_clear=vk::ClearValue { color: vk::ClearColorValue { float32: [ self.ubo_data.fog_color[0]*self.ubo_data.day_factor, self.ubo_data.fog_color[1]*self.ubo_data.day_factor, self.ubo_data.fog_color[2]*self.ubo_data.day_factor+0.05, 1.0 ] } };
            let depth_clear=vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
            let main_clears=[hdr_clear, depth_clear];
            // --- Main pass: render the world into the HDR offscreen target (linear HDR). ---
            let main_begin=vk::RenderPassBeginInfo::default().render_pass(self.postfx.main_rp).framebuffer(self.postfx.main_fb).render_area(vk::Rect2D { offset: vk::Offset2D { x:0, y:0 }, extent: ext }).clear_values(&main_clears);
            device.cmd_begin_render_pass(cmd, &main_begin, vk::SubpassContents::INLINE);
            let viewport=vk::Viewport::default().x(0.0).y(0.0).width(ext.width as f32).height(ext.height as f32).min_depth(0.0).max_depth(1.0);
            let scissor=vk::Rect2D::default().extent(ext);
            device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&viewport));
            device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));
            device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.layout, 0, std::slice::from_ref(&self.pipelines.descriptor_set), &[]);
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.pipeline);
            for ((cx,sec_y,cz), gpu_mesh) in self.gpu_meshes.iter() {
                if gpu_mesh.index_count == 0 { continue; }
                let mn = Vec3::new(*cx as f32*16.0, *sec_y as f32*16.0, *cz as f32*16.0);
                if !aabb_in_frustum(&frustum, mn, mn + Vec3::splat(16.0)) { continue; }
                self.drawn_sections += 1;
                let offsets=[0u64];
                device.cmd_bind_vertex_buffers(cmd, 0, std::slice::from_ref(&gpu_mesh.vertex_buffer.buffer), &offsets);
                device.cmd_bind_index_buffer(cmd, gpu_mesh.index_buffer.buffer, 0, vk::IndexType::UINT32);
                device.cmd_draw_indexed(cmd, gpu_mesh.index_count, 1, 0, 0, 0);
            }
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.sky_pipeline);
            device.cmd_draw(cmd, 3, 1, 0, 0);
            // Volumetric clouds: depth-tested layer at fixed altitude, blended over sky + terrain.
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.cloud_pipeline);
            device.cmd_draw(cmd, 3, 1, 0, 0);
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.water_pipeline);
            for ((cx,sec_y,cz), gpu_mesh) in self.gpu_meshes.iter() {
                if gpu_mesh.water_index_count == 0 { continue; }
                let mn = Vec3::new(*cx as f32*16.0, *sec_y as f32*16.0, *cz as f32*16.0);
                if !aabb_in_frustum(&frustum, mn, mn + Vec3::splat(16.0)) { continue; }
                if let (Some(wvb), Some(wib)) = (&gpu_mesh.water_vertex_buffer, &gpu_mesh.water_index_buffer) {
                    let offsets=[0u64];
                    device.cmd_bind_vertex_buffers(cmd, 0, std::slice::from_ref(&wvb.buffer), &offsets);
                    device.cmd_bind_index_buffer(cmd, wib.buffer, 0, vk::IndexType::UINT32);
                    device.cmd_draw_indexed(cmd, gpu_mesh.water_index_count, 1, 0, 0, 0);
                }
            }
            device.cmd_end_render_pass(cmd);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.query_pool, ts_base + 2); // main (terrain+sky+water) done

            // --- Bloom: bright-pass then separable blur, at half resolution. ---
            let bext = self.postfx.bloom_extent;
            let bvp = vk::Viewport::default().x(0.0).y(0.0).width(bext.width as f32).height(bext.height as f32).min_depth(0.0).max_depth(1.0);
            let bsc = vk::Rect2D::default().extent(bext);
            let zero_clear = vk::ClearValue { color: vk::ClearColorValue { float32: [0.0,0.0,0.0,1.0] } };
            let post_layout = self.pipelines.post_layout;
            let post_rp = self.postfx.post_rp;
            let post_pass = |fb: vk::Framebuffer, pipe: vk::Pipeline, dset: vk::DescriptorSet, pc: [f32;4]| {
                unsafe {
                    let bg = vk::RenderPassBeginInfo::default().render_pass(post_rp).framebuffer(fb).render_area(vk::Rect2D{offset:vk::Offset2D{x:0,y:0},extent:bext}).clear_values(std::slice::from_ref(&zero_clear));
                    device.cmd_begin_render_pass(cmd, &bg, vk::SubpassContents::INLINE);
                    device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&bvp));
                    device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&bsc));
                    device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, pipe);
                    device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, post_layout, 0, std::slice::from_ref(&dset), &[]);
                    let bytes = std::slice::from_raw_parts(pc.as_ptr() as *const u8, 16);
                    device.cmd_push_constants(cmd, post_layout, vk::ShaderStageFlags::FRAGMENT, 0, bytes);
                    device.cmd_draw(cmd, 3, 1, 0, 0);
                    device.cmd_end_render_pass(cmd);
                }
            };
            let (bw, bh) = (bext.width as f32, bext.height as f32);
            post_pass(self.postfx.bloom_a_fb, self.pipelines.bright_pipeline, self.postfx.bright_set, [0.0,0.0,0.0,0.0]);
            post_pass(self.postfx.bloom_b_fb, self.pipelines.blur_pipeline, self.postfx.blur_h_set, [1.0/bw, 0.0, 0.0, 0.0]);
            post_pass(self.postfx.bloom_a_fb, self.pipelines.blur_pipeline, self.postfx.blur_v_set, [0.0, 1.0/bh, 0.0, 0.0]);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.query_pool, ts_base + 3); // bloom done

            // --- Composite: FXAA + bloom add + tonemap -> swapchain. ---
            let comp_clears=[hdr_clear, depth_clear];
            let comp_begin=vk::RenderPassBeginInfo::default().render_pass(self.swapchain.render_pass).framebuffer(self.swapchain.framebuffers[image_index as usize]).render_area(vk::Rect2D { offset: vk::Offset2D { x:0, y:0 }, extent: ext }).clear_values(&comp_clears);
            device.cmd_begin_render_pass(cmd, &comp_begin, vk::SubpassContents::INLINE);
            device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&viewport));
            device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.composite_pipeline);
            device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, post_layout, 0, std::slice::from_ref(&self.postfx.composite_set), &[]);
            let cpc = [1.0/ext.width as f32, 1.0/ext.height as f32, 0.6f32, 0.0f32];
            device.cmd_push_constants(cmd, post_layout, vk::ShaderStageFlags::FRAGMENT, 0, std::slice::from_raw_parts(cpc.as_ptr() as *const u8, 16));
            device.cmd_draw(cmd, 3, 1, 0, 0);
            device.cmd_end_render_pass(cmd);
            device.cmd_write_timestamp(cmd, vk::PipelineStageFlags::BOTTOM_OF_PIPE, self.query_pool, ts_base + 4); // composite done
            device.end_command_buffer(cmd).map_err(|e| format!("end {e:?}"))?;
        }
        self.ts_written[self.current_frame] = true;
        let wait_sem=[self.image_available[self.current_frame]];
        let wait_stages=[vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT];
        let signal_sem=[self.render_finished[self.current_frame]];
        let cmd_bufs=[self.command_buffers[image_index as usize]];
        let submit=vk::SubmitInfo::default().wait_semaphores(&wait_sem).wait_dst_stage_mask(&wait_stages).command_buffers(&cmd_bufs).signal_semaphores(&signal_sem);
        unsafe { self.ctx.device.queue_submit(self.ctx.queue, std::slice::from_ref(&submit), self.in_flight_fences[self.current_frame]).map_err(|e| format!("submit {e:?}"))?; }
        let swapchains=[self.swapchain.swapchain];
        let indices=[image_index];
        let present_info=vk::PresentInfoKHR::default().wait_semaphores(&signal_sem).swapchains(&swapchains).image_indices(&indices);
        match unsafe { self.swapchain.loader.queue_present(self.ctx.queue, &present_info) } {
            Ok(_)=>{},
            Err(vk::Result::ERROR_OUT_OF_DATE_KHR)|Err(vk::Result::SUBOPTIMAL_KHR)=>{},
            Err(e)=>return Err(format!("present {e:?}")),
        }
        self.current_frame=(self.current_frame+1)%self.image_available.len();
        self.frame_count+=1;
        Ok(true)
    }
    pub unsafe fn destroy(&mut self) {
        self.ctx.device.device_wait_idle().ok();
        for (_, mut mesh) in self.gpu_meshes.drain() {
            mesh.vertex_buffer.destroy(&self.ctx.device); mesh.index_buffer.destroy(&self.ctx.device);
            if let Some(mut wb) = mesh.water_vertex_buffer.take() { wb.destroy(&self.ctx.device); }
            if let Some(mut wb) = mesh.water_index_buffer.take() { wb.destroy(&self.ctx.device); }
        }
        for &sem in &self.image_available { self.ctx.device.destroy_semaphore(sem, None); }
        for &sem in &self.render_finished { self.ctx.device.destroy_semaphore(sem, None); }
        for &f in &self.in_flight_fences { self.ctx.device.destroy_fence(f, None); }
        self.ctx.device.destroy_command_pool(self.command_pool, None);
        self.ctx.device.destroy_query_pool(self.query_pool, None);
        self.atlas.destroy(&self.ctx.device);
        self.shadow.destroy(&self.ctx.device);
        self.pipelines.destroy(&self.ctx.device);
        self.postfx.destroy(&self.ctx.device);
        self.ubo_buffer.destroy(&self.ctx.device);
        self.swapchain.cleanup(&self.ctx.device);
    }
}
