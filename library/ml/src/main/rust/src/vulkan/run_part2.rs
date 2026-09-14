impl Net {

    /// Record the whole plan: input copy, every op, output copy.
    ///
    /// Barriers follow the construction-time [`Schedule`](crate::nets::schedule):
    /// a barrier precedes an op exactly when the schedule says a RAW, WAR, or
    /// WAW hazard needs one — not after every op. Independent work between two
    /// barriers is left for the GPU to overlap; that overlap is the entire
    /// point of scheduling (see `analysis/maml_vs_litert.md` section 6).
    fn record(&self) -> Result<(), String> {
        let device = &self.context.device;
        let buffer = self.command_buffer;
        // SAFETY: `buffer` is a primary command buffer from this device's pool, not
        // currently pending, and every handle referenced below outlives it (they are all
        // fields of `self`, dropped after it in `Drop`).
        unsafe {
            device
                .begin_command_buffer(buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin_command_buffer {e:?}"))?;

            // The weights were written by `upload_weights`, in a different submission. A
            // fence wait between submissions orders them but is not a memory dependency, so
            // making that TRANSFER_WRITE visible to every SHADER_READ below needs a real
            // barrier. Recorded once at the top rather than folded into `barrier`, because
            // the weights buffer is never written again.
            let weights_visible = vk::BufferMemoryBarrier::default()
                .src_access_mask(vk::AccessFlags::TRANSFER_WRITE)
                .dst_access_mask(vk::AccessFlags::SHADER_READ)
                .src_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .dst_queue_family_index(vk::QUEUE_FAMILY_IGNORED)
                .buffer(self.weights.buffer)
                .offset(0)
                .size(vk::WHOLE_SIZE);
            device.cmd_pipeline_barrier(
                buffer,
                vk::PipelineStageFlags::TRANSFER,
                vk::PipelineStageFlags::COMPUTE_SHADER,
                vk::DependencyFlags::empty(),
                &[],
                std::slice::from_ref(&weights_visible),
                &[],
            );

            // One copy per input, packed end to end in the staging buffer in declaration
            // order. Both shipping nets have exactly one; SCRFD's nine outputs come back
            // the same way below.
            let mut staged = 0u64;
            for input in &self.plan.inputs {
                let bytes = (input.shape.len() as vk::DeviceSize) * 2;
                let region = vk::BufferCopy::default()
                    .src_offset(staged)
                    .dst_offset((input.at as vk::DeviceSize) * 2)
                    .size(bytes);
                device.cmd_copy_buffer(
                    buffer,
                    self.staging.buffer,
                    self.arena.buffer,
                    std::slice::from_ref(&region),
                );
                staged += bytes;
            }
            self.barrier(buffer);

            for (step, op) in self.plan.ops.iter().enumerate() {
                // The schedule's barrier goes *before* the op that needs it.
                // Step 0 never needs one: nothing has run yet in this submit
                // (the weights/input copies above carry their own barriers).
                if step > 0 {
                    match self.schedule.steps.get(step) {
                        Some(scheduled) if scheduled.barrier => {
                            // Whole-arena ordering: the schedule proves *that* two
                            // ops must be ordered, and the recorder orders them
                            // with the same spelling the per-op barrier used
                            // (transfer included — a copy is an op here). The
                            // range narrowing stays dead: the sweep measured
                            // all correct spellings within 0.45% of each other,
                            // and a narrower range that drops TRANSFER is the
                            // incorrect `Narrow` variant, not an optimisation.
                            let _ = scheduled.transfer;
                            self.barrier(buffer);
                        }
                        Some(_) => {}
                        None => {
                            return Err(format!(
                                "step {step} of {} has no schedule entry",
                                self.plan.ops.len()
                            ));
                        }
                    }
                }
                match *op {
                    Op::Dispatch { kind, push, invocations } => {
                        device.cmd_bind_pipeline(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.for_kind(kind),
                        );
                        // The window an op's weights are visible through, and the push rebased
                        // into it. Both are the identity unless the file was larger than one
                        // descriptor's range, so the common case records what it always did.
                        let segment =
                            self.segments.for_op(step, kind, &push, &self.tensors)?.unwrap_or(0);
                        let set = match self.pipelines.descriptor_sets.get(segment) {
                            Some(&set) => set,
                            None => return Err(format!("step {step} wants segment {segment}")),
                        };
                        device.cmd_bind_descriptor_sets(
                            buffer,
                            vk::PipelineBindPoint::COMPUTE,
                            self.pipelines.layout(),
                            0,
                            &[set],
                            &[],
                        );
                        let push = self.segments.rebase(segment, kind, &push);
                        device.cmd_push_constants(
                            buffer,
                            self.pipelines.layout(),
                            vk::ShaderStageFlags::COMPUTE,
                            0,
                            push_bytes(&push),
                        );
                        // Split across x and y: the widest layer here needs 102,400
                        // workgroups and `maxComputeWorkGroupCount` is only guaranteed to
                        // be 65,535 per dimension. `global_index()` in the shaders
                        // flattens the grid back, and the grid over-covers, which is what
                        // `push.count` is checked against.
                        let groups = invocations.div_ceil(WORKGROUP);
                        device.cmd_dispatch(
                            buffer,
                            groups.min(MAX_WORKGROUPS_PER_DIM),
                            groups.div_ceil(MAX_WORKGROUPS_PER_DIM),
                            1,
                        );
                    }
                    Op::Copy { src, dst, elems } => {
                        // Same buffer for source and destination. The spec allows that as
                        // long as the regions do not overlap, which the arena allocator
                        // guarantees and `nets::u2netp` asserts.
                        let region = vk::BufferCopy::default()
                            .src_offset((src as vk::DeviceSize) * 2)
                            .dst_offset((dst as vk::DeviceSize) * 2)
                            .size((elems as vk::DeviceSize) * 2);
                        device.cmd_copy_buffer(
                            buffer,
                            self.arena.buffer,
                            self.arena.buffer,
                            std::slice::from_ref(&region),
                        );
                    }
                }
            }

            // Order the last op's writes before the readback copies below.
            //
            // The schedule places barriers *before* ops that need them, but the
            // output copies are not ops in the plan — so no schedule entry covers
            // the final write → transfer-read edge. The old per-op recorder got
            // this for free (its barrier after the last op did exactly this);
            // without it the readback is undefined and returns zeros on Mali.
            self.barrier(buffer);

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
}