                }
                // Only what this op wrote. See `barrier_over`: the whole-arena form cost more
                // than the arithmetic it was protecting.
                let (offset, size) = match *op {
                    Op::Dispatch { push, .. } => {
                        let elems = u64::from(push.out_c)
                            * u64::from(push.out_h.max(1))
                            * u64::from(push.out_w.max(1));
                        (u64::from(push.out) * 2, elems * 2)
                    }
                    Op::Copy { dst, elems, .. } => (u64::from(dst) * 2, u64::from(elems) * 2),
                };
                // A zero-length range is not a barrier at all, and a shape this could not read
                // is a plan bug rather than something to guess around - so fall back to the
                // whole arena, which is always correct if slower.
                if size == 0 || offset + size > self.arena.size {
                    self.barrier(buffer);
                } else {
                    self.barrier_over(buffer, offset, size);
                }
            }

            let mut read_back = 0u64;
            for output in &self.plan.outputs {
                let bytes = (output.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset((output.at as vk::DeviceSize) * 2)
                    .dst_offset(read_back)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.arena.buffer,
                    self.staging.buffer,
                    std::slice::from_ref(&region),
                );
                read_back += bytes;
            }

            // Waiting on a fence makes the copy's writes *available*, but the device-to-host
            // domain operation still needs a memory dependency naming HOST_READ — and
            // HOST_COHERENT only removes the need for `vkInvalidateMappedMemoryRanges`, not
            // for this. Without it the readback in `infer` works on unified-memory parts and
            // is undefined on others, which is the worst kind of bug to leave in.
            let host_visible = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::HOST_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.staging.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::HOST,
                vk::DependencyFlags::empty(),
                &[],
                std::slice::from_ref(&host_visible),
                &[],
            );

            device
                .end_command_buffer(buffer)
                .map_err(|e| format!("end_command_buffer {e:?}"))
        }
    }

    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair.
    unsafe fn barrier(&self, buffer: vk::CommandBuffer) {
        // SAFETY: as `barrier_over`, which this defers to for the whole arena.
        unsafe { self.barrier_over(buffer, 0, vk::WHOLE_SIZE) }
    }

    /// A barrier over `offset .. offset + size` of the arena, in bytes.
    ///
    /// # Why the range matters
    ///
    /// It used to be the whole buffer on every op, on the reasoning that consecutive ops overlap
    /// anyway so there is nothing to narrow to. That is true of *which* ops depend on which, and
    /// false about what the barrier costs: a decode step is 1,094 ops and therefore 1,094
    /// whole-buffer barriers, and on a tile-based mobile GPU each one is a pipeline drain over a
    /// buffer that is now up to 303 MB. Measured on a Tensor G4 a decode step took 676 ms, of
    /// which the arithmetic accounts for perhaps a tenth - the rest was this.
    ///
    /// An op only ever makes visible what it wrote, so the range is its own output. Anything
    /// earlier was already made visible by the barrier that followed *it*.
    ///
    /// # How it is spelled is a variable, and it is switchable
    ///
    /// It is tempting to read a dispatch's dependency on the dispatch before it as only
    /// `SHADER_WRITE` to `SHADER_READ`, making the transfer stage and the two transfer accesses
    /// more than the spec requires. That is wrong here, and [`Formulation::Narrow`] is the
    /// measurement proving it: a copy is also an op in this runtime, so the transfer edge is load
    /// bearing and dropping it corrupts the output.
    ///
    /// Whether a *correct* spelling is cheaper than another correct one is a property of the
    /// driver rather than of the spec. On a Tensor G4 the answer turned out to be no — the four
    /// correct variants sit within 0.45% of each other. See [`Formulation`] and
    /// `analysis/maml_vs_litert.md` section 6.
    ///
    /// [`Formulation`] makes the choice a run-time knob rather than a rebuild. That is not
    /// convenience: the first attempt at this measured a rebuild against a rebuild, a shader edit
    /// silently failed to recompile in between, and the shader's 45% was attributed to the
    /// barrier. Switching within one process makes the comparison interleavable and immune to
    /// that. See `analysis/maml_vs_litert.md`.
    ///
    /// # Safety
    ///
    /// `buffer` must be inside a `begin`/`end` pair, and the range must be inside the arena.
    unsafe fn barrier_over(&self, buffer: vk::CommandBuffer, offset: u64, size: u64) {
        let formulation = Formulation::selected();
        if formulation == Formulation::None {
            return;
        }
        let (src_stage, dst_stage) = formulation.stages();
        let (src_access, dst_access) = formulation.accesses();
        // A global memory barrier names no buffer and no range, so the driver has nothing to
        // walk. If the cost scales with the range, this is where that shows up.
        if formulation == Formulation::Global || formulation == Formulation::GlobalNarrow {
            let barrier = vk::MemoryBarrier::default()
                .src_access_mask(src_access)
                .dst_access_mask(dst_access);
            self.context.device.cmd_pipeline_barrier(
                buffer,
                src_stage,
                dst_stage,
                vk::DependencyFlags::empty(),
                std::slice::from_ref(&barrier),
                &[],
                &[],
            );
            return;
        }
        let (offset, size) = if formulation == Formulation::Whole {
            (0, vk::WHOLE_SIZE)
        } else {
            (offset, size)
        };
        let barrier = vk::BufferMemoryBarrier::default()
            .src_access_mask(src_access)
            .dst_access_mask(dst_access)
            .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
            .buffer(self.arena.buffer)
            .offset(offset)
            .size(size);
        self.context.device.cmd_pipeline_barrier(
            buffer,
            src_stage,
            dst_stage,
            vk::DependencyFlags::empty(),
            &[],
            std::slice::from_ref(&barrier),
            &[],
        );
    }

    /// # Safety
    ///
    /// `buffer` must be recorded and not already pending.
    unsafe fn submit_and_wait(&self, buffer: vk::CommandBuffer) -> Result<(), String> {
        let device = &self.context.device;
        let fence = device
            .create_fence(&vk::FenceCreateInfo::default(), None)
            .map_err(|e| format!("create_fence {e:?}"))?;
        let buffers = [buffer];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        let guard = self.context.lock_queue();
        let submitted = device
            .queue_submit(self.context.queue, std::slice::from_ref(&submit), fence)
            .map_err(|e| format!("queue_submit {e:?}"));
        drop(guard);

        let result = submitted.and_then(|()| {
            device
                .wait_for_fences(&[fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))
        });
        // On a timeout the submission is still in flight, and the caller is about to drop
        // both the fence and the staging buffer it reads from. Wait the device out first:
        // destroying either while a queue operation references it is undefined, and a
        // one-off weights upload has nowhere to defer the cleanup to.
        if result.is_err() {
            let guard = self.context.lock_queue();
            let _ = device.device_wait_idle();
            drop(guard);
        }
        device.destroy_fence(fence, None);
        result
    }

    /// Preprocess `pixels`, run the network, and return the mask as `0..1` floats.
    ///
    /// `pixels` is `ARGB_8888` as `Bitmap.getPixels` produces it. The returned mask is
    /// `output_shape.h * output_shape.w` long, row-major.
    ///
    /// Single-input, single-output: this is the bitmap-in, mask-out path the two
    /// segmenters and the face embedder use. It refuses a plan shaped otherwise rather
    /// than silently returning only the first binding.
    pub fn infer(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
    ) -> Result<Vec<f32>, String> {
        let input = self.plan.input()?;
        // Reject a multi-output plan here rather than returning only the first binding:
        // `output_scratch` spans every output, so the concatenation would look like a
        // mask of the wrong size instead of an error.
        let _single_output = self.plan.output()?;
        preprocess::to_planar_f16(
            pixels,
            width,
            height,
            input.shape,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        Ok(self.output_scratch.iter().map(|&h| preprocess::f16_to_f32(h)).collect())
    }

    /// Letterbox `pixels` into the plan's input shape, run, and return **every** output.
    ///
    /// SCRFD's path: nine maps come back, in [`crate::nets::Plan::outputs`] order. `fit`
    /// must have been computed for the shape this net was built at, which
    /// [`preprocess::Letterbox::square`] guarantees for a fixed side.
    pub fn infer_letterboxed(
        &mut self,
        pixels: &[i32],
        width: u32,
        height: u32,
        fit: &preprocess::Letterbox,
    ) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        if fit.shape() != input.shape {
            return Err(format!(
                "a letterbox of {:?} for a net built at {:?}",
                fit.shape(),
                input.shape
            ));
        }
        preprocess::to_letterboxed_f16(
            pixels,
            width,
            height,
            fit,
            &self.normalise,
            &mut self.input_scratch,
        )?;
        self.submit()?;
        self.split_outputs()
    }

    /// The plan's input shape, so a caller can size the tensor it hands to
    /// [`Net::infer_raw`] without holding the plan itself.
    pub fn input_shape(&self) -> Result<crate::nets::Shape, String> {
        Ok(self.plan.input()?.shape)
    }

    /// Run the plan over `values` directly, with no preprocessing, and return every output.
    ///
    /// The bitmap paths above exist because most of these networks take an image. Supertonic's
    /// do not: the text encoder takes character ids, the sampler takes a latent and a
    /// conditioning and the vocoder takes the sampler's output, each produced by the previous
    /// stage rather than by a camera. So this is the path for a net whose input is a tensor
    /// someone else computed.
    ///
    /// `values` must be exactly the input shape's element count, in `[c, h, w]` order — the
    /// same order [`crate::nets::Plan`] uses everywhere. It is rounded to fp16 on the way in,
    /// which is the arena's precision, so a caller cannot hand over more accuracy than the
    /// device will keep.
    pub fn infer_raw(&mut self, values: &[f32]) -> Result<Vec<Vec<f32>>, String> {
        let input = self.plan.input()?;
        let wanted = input.shape.len() as usize;
        if values.len() != wanted {
            return Err(format!(
                "{} values for an input of {:?}, which holds {wanted}",
                values.len(),
                input.shape
            ));
        }
        self.input_scratch.clear();
        self.input_scratch.extend(values.iter().map(|&v| preprocess::f32_to_f16(v)));
        self.submit()?;
        self.split_outputs()
    }

    /// [`Net::infer_raw`] for a plan with more than one input, one slice per binding.
    ///
    /// The recorded command buffer packs the inputs end to end from offset 0 in declaration
    /// order, so this is the mirror of `split_outputs`. Supertonic's sampler takes seven — a
    /// latent, two conditionings, the timestep shifts and two rotary angle tables — and getting
    /// them out of order would be a wrong answer at the right shape, so each is checked against
    /// its own binding rather than the total.
    pub fn infer_raw_many(&mut self, inputs: &[&[f32]]) -> Result<Vec<Vec<f32>>, String> {
        if inputs.len() != self.plan.inputs.len() {
            return Err(format!(
                "{} inputs for a plan that declares {}",
                inputs.len(),
                self.plan.inputs.len()
            ));
        }
        self.input_scratch.clear();
        for (binding, values) in self.plan.inputs.iter().zip(inputs) {
            let wanted = binding.shape.len() as usize;
            if values.len() != wanted {
                return Err(format!(
                    "{} values for an input of {:?}, which holds {wanted}",
                    values.len(),
                    binding.shape
                ));
            }
            self.input_scratch.extend(values.iter().map(|&v| preprocess::f32_to_f16(v)));
        }
        self.submit()?;
        self.split_outputs()
    }

    /// Split the concatenated readback into one vector per output binding.
    ///
    /// The recorded command buffer packs the outputs end to end from offset 0 in the
    /// staging buffer, in declaration order, so this is the mirror of `record`.
    fn split_outputs(&self) -> Result<Vec<Vec<f32>>, String> {
        let mut outputs = Vec::with_capacity(self.plan.outputs.len());
        let mut at = 0usize;
        for binding in &self.plan.outputs {
            let len = binding.shape.len() as usize;
            let end = at.checked_add(len).ok_or("an output offset overflowed")?;
            let slice = self
                .output_scratch
                .get(at..end)
                .ok_or_else(|| format!("the readback is shorter than {end} elements"))?;
            outputs.push(slice.iter().map(|&h| preprocess::f16_to_f32(h)).collect());
            at = end;
        }
        Ok(outputs)
    }

    /// Upload `input_scratch`, submit the recorded buffer, and read `output_scratch` back.
    ///
    /// Factored out of the two `infer` variants because it is the whole of the unsafe,
    /// order-sensitive part: everything about poisoning, the fence and the queue lock is
    /// here once rather than twice.
    fn submit(&mut self) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        // SAFETY: the scratch buffer is exactly the fp16 elements the recorded copy
        // regions move, and `u16` has no padding or invalid bit patterns.
        let bytes = unsafe {
            std::slice::from_raw_parts(
                self.input_scratch.as_ptr().cast::<u8>(),
                std::mem::size_of_val(self.input_scratch.as_slice()),
            )
        };
        self.staging.write(bytes)?;

        let device = &self.context.device;
        let buffers = [self.command_buffer];
        let submit = vk::SubmitInfo::default().command_buffers(&buffers);
        // SAFETY: the fence is reset before the submit and waited on after it, so the
        // command buffer is never resubmitted while pending and the staging buffer is
        // never read while the GPU is writing it. `poisoned` is what keeps that true when
        // the wait times out. The queue and the pool are shared process-wide, hence the
        // lock around the submit; the fence wait is deliberately outside it.
        unsafe {
            device.reset_fences(&[self.fence]).map_err(|e| format!("reset_fences {e:?}"))?;
            // Poison first: from here until the wait returns, a submission may be pending,
            // and every path out of that state other than a successful wait is one this
            // net cannot recover from.
            self.poisoned = true;
            let guard = self.context.lock_queue();
            let submitted = device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), self.fence)
                .map_err(|e| format!("queue_submit {e:?}"));
            drop(guard);
            submitted?;
            device
                .wait_for_fences(&[self.fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
            self.poisoned = false;
        }

        self.staging.read_f16(&mut self.output_scratch)
    }

    /// The mask's dimensions, which is what the Kotlin wrapper reports to its caller.
    /// Copy the current arena into `into`, which must be at least as large.
    ///
    /// # Safety
    ///
    /// `into` must be a live device-local buffer of at least `self.arena.size` bytes, and no
    /// work may be in flight on this net.
    unsafe fn copy_arena(&mut self, into: &Buffer) -> Result<(), String> {
        if self.arena.size == 0 {
            return Ok(());
        }
        let device = &self.context.device;
        // SAFETY: the caller guarantees `into` is live and large enough, and the command buffer
        // is recorded and waited on entirely within this call.
        unsafe {
            device
                .begin_command_buffer(self.command_buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let region = vk::BufferCopy::default().src_offset(0).dst_offset(0).size(self.arena.size);
            device.cmd_copy_buffer(
                self.command_buffer,
                self.arena.buffer,
                into.buffer,
                std::slice::from_ref(&region),
            );
            device.end_command_buffer(self.command_buffer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()
        }
    }

    /// Copy arena ranges out, as raw fp16 bytes, concatenated in the order given.
    ///
    /// Its own submit rather than a hook in the inference path: this runs twice in a process at
    /// most - once to produce a cache, once to check one - so the simplest correct thing wins
    /// over anything folded into the hot path.
    fn read_arena(&mut self, ranges: &[(u32, u32)]) -> Result<Vec<u8>, String> {
        // Chunked to the staging buffer, which is half a megabyte against caches that run to
        // tens. One submit per chunk: this runs twice in a process, so the cost of the extra
        // submits is irrelevant beside not needing a second large allocation.
        let mut out = Vec::new();
        for batch in batches(ranges, self.staging.size) {
            out.extend_from_slice(&self.read_batch(&batch)?);
        }
        Ok(out)
    }

    /// One staging-sized batch of arena ranges, as raw fp16 bytes.
    fn read_batch(&mut self, ranges: &[(u32, u32)]) -> Result<Vec<u8>, String> {
        let total: u64 = ranges.iter().map(|&(_, elems)| u64::from(elems) * 2).sum();
        if total == 0 {
            return Ok(Vec::new());
        }
        let device = &self.context.device;
        // SAFETY: one command buffer, recorded and submitted here and waited on before return,
        // so nothing else observes it and the fence outlives the work.
        unsafe {
            device
                .begin_command_buffer(self.command_buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let mut at = 0u64;
            for &(from, elems) in ranges {