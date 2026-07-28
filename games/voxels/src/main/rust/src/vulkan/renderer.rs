use ash::vk;
use std::collections::HashMap;
use glam::{Mat4, Vec3};
use crate::world::mesher::MeshData;
use crate::vulkan::context::VulkanContext;
use crate::vulkan::swapchain::Swapchain;
use crate::vulkan::pipeline::Pipelines;
use crate::vulkan::buffers::{AllocatedBuffer, Vertex as VulkanVertex, UboData};
use crate::texture_atlas::TextureAtlas;

pub struct ChunkGpuMesh { pub vertex_buffer: AllocatedBuffer, pub index_buffer: AllocatedBuffer, pub index_count: u32 }

pub struct VulkanRenderer {
    pub ctx: VulkanContext,
    pub swapchain: Swapchain,
    pub atlas: TextureAtlas,
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
        let ubo_buffer = unsafe { AllocatedBuffer::new(&instance, &device, phys, ubo_size, vk::BufferUsageFlags::UNIFORM_BUFFER, vk::MemoryPropertyFlags::HOST_VISIBLE | vk::MemoryPropertyFlags::HOST_COHERENT)? };
        let pipelines = unsafe { Pipelines::new(&device, swapchain.format, vk::Format::D32_SFLOAT, ubo_buffer.buffer, ubo_size, atlas.view, atlas.sampler)? };
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
        Ok(Self { ctx, swapchain, atlas, pipelines, command_pool, command_buffers, image_available, render_finished, in_flight_fences, ubo_buffer, ubo_data: UboData::default(), queued_meshes: HashMap::new(), gpu_meshes: HashMap::new(), current_frame: 0, frame_count: 0, width, height })
    }
    pub unsafe fn resize(&mut self, width: u32, height: u32) -> Result<(), String> {
        if width==0 || height==0 { return Ok(()); }
        self.ctx.device.device_wait_idle().map_err(|e| format!("wait {e:?}"))?;
        self.swapchain.cleanup(&self.ctx.device);
        self.swapchain = Swapchain::new(&self.ctx, width, height)?;
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
                    self.gpu_meshes.insert(key, ChunkGpuMesh{ vertex_buffer: vb, index_buffer: ib, index_count: mesh.indices.len() as u32 });
                }
                processed+=1;
            }
        }
        Ok(())
    }
    pub unsafe fn update_ubo(&mut self, view_proj: Mat4, player_pos: Vec3, time: f32) {
        let day_cycle=120.0; let day_t=(time/day_cycle)%1.0;
        let sun_angle=day_t*std::f32::consts::TAU - std::f32::consts::FRAC_PI_2;
        let sun_dir=Vec3::new(sun_angle.cos()*0.3, sun_angle.sin(), sun_angle.cos()*0.1).normalize_or_zero();
        let day_factor=(sun_dir.y*0.5+0.5).clamp(0.0,1.0).powf(0.6);
        let fog_color=if day_factor>0.5 { Vec3::new(0.53,0.81,0.92) } else { Vec3::new(0.05,0.07,0.15) }.lerp(Vec3::new(0.53,0.81,0.92), day_factor);
        self.ubo_data=UboData{ view_proj: view_proj.to_cols_array_2d(), sun_dir: sun_dir.to_array(), time, fog_color: fog_color.to_array(), fog_density: 0.008 / (1.0+day_factor*0.8).max(0.2), player_pos: [player_pos.x, player_pos.y, player_pos.z, 0.0], day_factor, _pad: [0.0;3] };
        let bytes=unsafe { std::slice::from_raw_parts((&self.ubo_data as *const UboData) as *const u8, std::mem::size_of::<UboData>()) };
        self.ubo_buffer.upload(&self.ctx.device, bytes);
    }
    pub unsafe fn draw_frame(&mut self) -> Result<bool, String> {
        let device=self.ctx.device.clone();
        let fence=self.in_flight_fences[self.current_frame];
        unsafe { device.wait_for_fences(std::slice::from_ref(&fence), true, u64::MAX).map_err(|e| format!("wait {e:?}"))?; device.reset_fences(std::slice::from_ref(&fence)).map_err(|e| format!("reset {e:?}"))?; }
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
            let barrier=vk::ImageMemoryBarrier::default().old_layout(vk::ImageLayout::UNDEFINED).new_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL).image(self.swapchain.images[image_index as usize]).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 }).dst_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
            device.cmd_pipeline_barrier(cmd, vk::PipelineStageFlags::TOP_OF_PIPE, vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT, vk::DependencyFlags::empty(), &[], &[], std::slice::from_ref(&barrier));
            let depth_barrier=vk::ImageMemoryBarrier::default().old_layout(vk::ImageLayout::UNDEFINED).new_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL).image(self.swapchain.depth_image).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::DEPTH, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 }).dst_access_mask(vk::AccessFlags::DEPTH_STENCIL_ATTACHMENT_WRITE);
            device.cmd_pipeline_barrier(cmd, vk::PipelineStageFlags::TOP_OF_PIPE, vk::PipelineStageFlags::EARLY_FRAGMENT_TESTS, vk::DependencyFlags::empty(), &[], &[], std::slice::from_ref(&depth_barrier));
            let clear_color=vk::ClearValue { color: vk::ClearColorValue { float32: [ self.ubo_data.fog_color[0]*self.ubo_data.day_factor, self.ubo_data.fog_color[1]*self.ubo_data.day_factor, self.ubo_data.fog_color[2]*self.ubo_data.day_factor+0.05, 1.0 ] } };
            let clear_depth=vk::ClearValue { depth_stencil: vk::ClearDepthStencilValue { depth: 1.0, stencil: 0 } };
            let color_att=vk::RenderingAttachmentInfo::default().image_view(self.swapchain.image_views[image_index as usize]).image_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL).load_op(vk::AttachmentLoadOp::CLEAR).store_op(vk::AttachmentStoreOp::STORE).clear_value(clear_color);
            let depth_att=vk::RenderingAttachmentInfo::default().image_view(self.swapchain.depth_view).image_layout(vk::ImageLayout::DEPTH_STENCIL_ATTACHMENT_OPTIMAL).load_op(vk::AttachmentLoadOp::CLEAR).store_op(vk::AttachmentStoreOp::DONT_CARE).clear_value(clear_depth);
            let rendering_info=vk::RenderingInfo::default().render_area(vk::Rect2D { offset: vk::Offset2D { x:0, y:0 }, extent: self.swapchain.extent }).layer_count(1).color_attachments(std::slice::from_ref(&color_att)).depth_attachment(&depth_att);
            device.cmd_begin_rendering(cmd, &rendering_info);
            let viewport=vk::Viewport::default().x(0.0).y(0.0).width(self.swapchain.extent.width as f32).height(self.swapchain.extent.height as f32).min_depth(0.0).max_depth(1.0);
            let scissor=vk::Rect2D::default().extent(self.swapchain.extent);
            device.cmd_set_viewport(cmd, 0, std::slice::from_ref(&viewport));
            device.cmd_set_scissor(cmd, 0, std::slice::from_ref(&scissor));
            device.cmd_bind_pipeline(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.pipeline);
            device.cmd_bind_descriptor_sets(cmd, vk::PipelineBindPoint::GRAPHICS, self.pipelines.layout, 0, std::slice::from_ref(&self.pipelines.descriptor_set), &[]);
            let player=Vec3::new(self.ubo_data.player_pos[0], self.ubo_data.player_pos[1], self.ubo_data.player_pos[2]);
            for ((cx,_sec_y,cz), gpu_mesh) in self.gpu_meshes.iter() {
                let cx_center=*cx as f32 *16.0+8.0; let cz_center=*cz as f32*16.0+8.0;
                let d=Vec3::new(cx_center-player.x, 0.0, cz_center-player.z).length();
                if d>220.0 { continue; }
                let offsets=[0u64];
                device.cmd_bind_vertex_buffers(cmd, 0, std::slice::from_ref(&gpu_mesh.vertex_buffer.buffer), &offsets);
                device.cmd_bind_index_buffer(cmd, gpu_mesh.index_buffer.buffer, 0, vk::IndexType::UINT32);
                device.cmd_draw_indexed(cmd, gpu_mesh.index_count, 1, 0, 0, 0);
            }
            device.cmd_end_rendering(cmd);
            let present_barrier=vk::ImageMemoryBarrier::default().old_layout(vk::ImageLayout::COLOR_ATTACHMENT_OPTIMAL).new_layout(vk::ImageLayout::PRESENT_SRC_KHR).image(self.swapchain.images[image_index as usize]).subresource_range(vk::ImageSubresourceRange { aspect_mask: vk::ImageAspectFlags::COLOR, base_mip_level: 0, level_count: 1, base_array_layer: 0, layer_count: 1 }).src_access_mask(vk::AccessFlags::COLOR_ATTACHMENT_WRITE);
            device.cmd_pipeline_barrier(cmd, vk::PipelineStageFlags::COLOR_ATTACHMENT_OUTPUT, vk::PipelineStageFlags::BOTTOM_OF_PIPE, vk::DependencyFlags::empty(), &[], &[], std::slice::from_ref(&present_barrier));
            device.end_command_buffer(cmd).map_err(|e| format!("end {e:?}"))?;
        }
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
        for (_, mut mesh) in self.gpu_meshes.drain() { mesh.vertex_buffer.destroy(&self.ctx.device); mesh.index_buffer.destroy(&self.ctx.device); }
        for &sem in &self.image_available { self.ctx.device.destroy_semaphore(sem, None); }
        for &sem in &self.render_finished { self.ctx.device.destroy_semaphore(sem, None); }
        for &f in &self.in_flight_fences { self.ctx.device.destroy_fence(f, None); }
        self.ctx.device.destroy_command_pool(self.command_pool, None);
        self.atlas.destroy(&self.ctx.device);
        self.pipelines.destroy(&self.ctx.device);
        self.ubo_buffer.destroy(&self.ctx.device);
        self.swapchain.cleanup(&self.ctx.device);
    }
}
