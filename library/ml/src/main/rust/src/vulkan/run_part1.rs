impl Net {
    /// Allocate, upload the weights and record the plan.
    ///
    /// `weights` is read for its data section but not retained: once the blob is in
    /// device-local memory the host copy is dropped, which for U^2-Netp gives back 2.1 MB
    /// of heap.
    ///
    /// A [`Blob`] rather than a [`crate::weights::Weights`] so that a bundled net can stream
    /// straight out of the APK. Nothing here reads the tensor table — the [`Plan`] already carries
    /// every resolved offset — which is what makes the two interchangeable.
    pub fn new(
        context: Arc<Context>,
        plan: Plan,
        weights: &dyn Blob,
        normalise: Normalise,
    ) -> Result<Net, String> {
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        // Vulkan forbids a zero-sized buffer, and the descriptor set needs a real one bound to
        // the weights binding whether or not any shader reads it. A plan can legitimately read
        // no weights at all — a purely elementwise one does — so this floors the allocation
        // rather than refusing the plan.
        let weights_bytes = (weights.data_len() as vk::DeviceSize).max(2);
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        // One staging buffer for both directions: the inputs and the outputs are never in
        // flight at the same time, because an inference is a single blocking submit. Each
        // side packs its bindings end to end from offset 0, in declaration order.
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Before allocating anything: a file this device's descriptors cannot describe must fail
        // here, with the limit in the message, rather than at the first dispatch that reads past
        // a range. Windowing depends only on the length, so `rebuild` never redoes it.
        let segments = Segments::plan(weights_bytes, &context.limits)?;
        // A few kilobytes, kept because `record` needs each tensor's extent to know which window
        // an op fits in, and `rebuild` installs plans this net was not constructed with.
        let tensors = weights.tensors().to_vec();

        let weights_buffer = Buffer::device_local(&context, weights_bytes)?;
        let arena = Buffer::device_local(&context, arena_bytes)?;
        let staging = Buffer::staging(&context, staging_bytes)?;
        let params = Buffer::step_params(&context, StepParams::BYTES)?;

        let staged = std::time::Instant::now();
        let pipelines = Pipelines::new(
            &context,
            arena.buffer,
            arena_bytes,
            weights_buffer.buffer,
            params.buffer,
            segments.all(),
        )?;
        timing!("pipelines {:.0} ms", staged.elapsed().as_secs_f64() * 1000.0);

        // `RESET_COMMAND_BUFFER`, so a net can re-record without reallocating.
        let pool_info = vk::CommandPoolCreateInfo::default()
            .queue_family_index(context.queue_family_index)
            .flags(vk::CommandPoolCreateFlags::RESET_COMMAND_BUFFER);
        // SAFETY: a plain object creation on a device this net holds an `Arc` to. It is stored in
        // the struct below before anything fallible runs, so `Drop` destroys it on every path.
        let command_pool = unsafe { context.device.create_command_pool(&pool_info, None) }
            .map_err(|e| format!("create_command_pool {e:?}"))?;

        let mut net = Net {
            context,
            plan,
            normalise,
            weights: weights_buffer,
            arena,
            staging,
            params,
            pipelines,
            segments,
            tensors,
            command_pool,
            command_buffer: vk::CommandBuffer::null(),
            fence: vk::Fence::null(),
            poisoned: false,
            input_scratch: vec![0u16; input_elems],
            output_scratch: vec![0u16; output_elems],
        };

        let staged = std::time::Instant::now();
        net.upload_weights(weights)?;
        timing!(
            "upload {} MB in {:.0} ms",
            weights_bytes / (1024 * 1024),
            staged.elapsed().as_secs_f64() * 1000.0
        );
        net.command_buffer = net.allocate_command_buffer()?;
        net.fence = net.create_fence()?;
        // Written before the first record so a shader reading it never sees uninitialised
        // memory, even on a plan that never calls `set_params`.
        net.set_params(StepParams::default())?;
        let staged = std::time::Instant::now();
        net.record()?;
        timing!(
            "record {:.0} ms for {} ops, arena {} KB",
            staged.elapsed().as_secs_f64() * 1000.0,
            net.plan.ops.len(),
            arena_bytes / 1024
        );
        Ok(net)
    }

    /// Install the values the next submit reads, without touching the recording.
    ///
    /// The buffer is host-coherent, so the write is visible to a later submit with no flush and
    /// no barrier. It must not be called while a submit is in flight — `infer` blocks on its
    /// fence before returning, so holding `&mut self` is enough to guarantee that.
    pub fn set_params(&mut self, params: StepParams) -> Result<(), String> {
        self.params.write(params.as_bytes())
    }

    /// Re-record this net at `plan`'s shapes, keeping the weights already uploaded.
    ///
    /// Supertonic's nets are shaped by the utterance — the text encoder runs at the character
    /// count, the sampler and the vocoder at the latent length — and a [`Plan`] is one command
    /// buffer recorded at fixed shapes, so a new length needs a new recording. It does not need a
    /// new upload: the weights are the expensive part (198 MB for Supertonic, 605 MB for
    /// SMaLL-100) and they do not depend on the length. So this keeps the weights buffer and
    /// re-uses the arena and the staging buffer too, growing them only when the new shapes need
    /// more than the old ones did — which for a sequence of utterances means the allocation
    /// happens a handful of times rather than once per sentence.
    ///
    /// `plan` must have been built against the same weights as this net's. Nothing here checks
    /// that, because [`crate::nets::Builder::finish`] already refuses a plan that does not read
    /// every tensor in its file, so a plan for a different `.maml` cannot reach here.
    pub fn rebuild(&mut self, plan: Plan) -> Result<(), String> {
        if self.poisoned {
            return Err("this network is unusable after an earlier failure".into());
        }
        let arena_bytes = (plan.arena_elems as vk::DeviceSize) * 2;
        let input_elems = binding_elems(&plan.inputs);
        let output_elems = binding_elems(&plan.outputs);
        let staging_bytes = (input_elems.max(output_elems) as vk::DeviceSize) * 2;

        // Allocate before touching any state. A failure part-way through must leave the net
        // running at its old plan: a descriptor pointing at a new arena while the recorded
        // command buffer still copies the inputs into the old one would be a wrong answer at the
        // right shape, which is far worse than an error.
        let grown_arena = if arena_bytes > self.arena.size {
            Some(Buffer::device_local(&self.context, arena_bytes)?)
        } else {
            None
        };
        let grown_staging = if staging_bytes > self.staging.size {
            Some(Buffer::staging(&self.context, staging_bytes)?)
        } else {
            None
        };

        // SAFETY: a descriptor set may not be rewritten, and a bound buffer may not be freed,
        // while a command buffer using either is pending. `submit` waits on its fence before
        // returning and the check above rejects the one case where it did not, so nothing is in
        // fact pending — but this costs one round trip per length change, not per inference, and
        // it makes that reasoning unnecessary rather than load-bearing.
        unsafe {
            let guard = self.context.lock_queue();
            let idle = self.context.device.device_wait_idle();
            drop(guard);
            idle.map_err(|e| format!("device_wait_idle {e:?}"))?;
        }

        // Past here nothing fails until `record`, so the net cannot be left describing one shape
        // and recording another. Each old `Buffer` frees itself as it is replaced, after the wait
        // above.
        if let Some(arena) = grown_arena {
            // Carry the old arena over first.
            //
            // A wider plan needs a bigger arena, and a fresh `Buffer` holds undefined memory -
            // so swapping it in silently replaced every **persistent** tensor with rubbish. The
            // KV caches live there, which made this a wrong answer rather than a crash: the
            // model attended over noise and produced fluent nonsense, non-deterministically,
            // and only once a prompt was long enough to make the prefill plan outgrow the
            // decode plan's arena. Short prompts never tripped it, which is why it survived
            // every test until a 1,900-position prefix.
            //
            // Copying the whole old arena is more than the persistent tensors strictly need, but
            // offsets are assigned per plan and the scratch above them is written before it is
            // read - so the correct-by-construction thing is to preserve all of it.
            // SAFETY: both buffers are live and owned here, the copy is inside its own submit,
            // and nothing else touches either until the fence is waited on.
            unsafe { self.copy_arena(&arena)? };
            self.pipelines.rebind_arena(&self.context.device, arena.buffer, arena.size);
            self.arena = arena;
        }
        if let Some(staging) = grown_staging {
            self.staging = staging;
        }
        self.input_scratch.resize(input_elems, 0);
        self.output_scratch.resize(output_elems, 0);
        self.plan = plan;

        // A `record` that fails leaves the command buffer part-written, and submitting that is
        // not something a later caller may be allowed to do. There is no way back — both of its
        // failure modes are device loss or host OOM — so the net is retired instead.
        let staged = std::time::Instant::now();
        if let Err(e) = self.record() {
            self.poisoned = true;
            return Err(e);
        }
        timing!(
            "rebuild-record {:.0} ms for {} ops, arena {} KB, dispatches {}, invocations {}",
            staged.elapsed().as_secs_f64() * 1000.0,
            self.plan.ops.len(),
            self.arena.size / 1024,
            self.plan.ops.iter().filter(|o| matches!(o, Op::Dispatch { .. })).count(),
            self.plan
                .ops
                .iter()
                .map(|o| match o {
                    Op::Dispatch { invocations, .. } => *invocations as u64,
                    _ => 0,
                })
                .sum::<u64>()
        );
        Ok(())
    }

    /// Staging bytes per copy. Large enough that the per-chunk round trip is noise against the
    /// transfer, small enough to be an unremarkable allocation on a low-RAM device.
    ///
    /// `pub(crate)` only so the parity fixture can assert its blob is bigger than one chunk. A
    /// fixture that fits in a single copy exercises none of the `dst_offset` arithmetic.
    pub(crate) const CHUNK_BYTES: u64 = 8 * 1024 * 1024;

    /// Copy the weights blob to device-local memory through the staging buffer, in chunks.
    ///
    /// Its own staging allocation and its own one-shot command buffer, because the permanent
    /// staging buffer is sized for one input and the weights are ten times that. Both are freed
    /// before returning; this happens once per net.
    ///
    /// # Why chunked, and why this small
    ///
    /// A single copy needs a staging buffer the size of the whole blob. For Supertonic's 127 MB
    /// sampler that is 127 MB of `HOST_VISIBLE` memory on top of the 127 MB device-local
    /// destination, at the moment of load — and it defeats the point of streaming the data section
    /// out of the APK, since the peak would be the whole file again just in a different allocation.
    ///
    /// [`Net::CHUNK_BYTES`] instead, one `cmd_copy_buffer` per chunk at the right `dst_offset`.
    /// Each is submitted and waited on before the next is written, because the staging buffer is
    /// reused and overwriting it while a copy is still reading it is a race. That costs one round
    /// trip per chunk — 16 for that sampler — against a transfer that is bandwidth-bound anyway,
    /// and it happens once per net rather than once per inference.
    fn upload_weights(&self, weights: &dyn Blob) -> Result<(), String> {
        let total = weights.data_len();
        if total == 0 {
            return Ok(());
        }
        let chunk = total.min(Self::CHUNK_BYTES);
        let staging = Buffer::staging(&self.context, chunk as vk::DeviceSize)?;
        let mut buffer = vec![0u8; usize::try_from(chunk).map_err(|_| "a chunk overflowed")?];
        let command_buffer = self.allocate_command_buffer()?;
        // SAFETY: the command buffer was just allocated from this device's pool and is
        // freed on every path below; each copy is bounds-checked by the loop, which never asks
        // for more than `chunk` bytes of staging or writes past `total` of the destination.
        unsafe {
            let device = &self.context.device;
            let mut written = 0u64;
            let outcome = loop {
                if written >= total {
                    break Ok(());
                }
                let size = chunk.min(total - written);
                let piece = match buffer.get_mut(..size as usize) {
                    Some(piece) => piece,
                    None => break Err("a chunk is larger than its buffer".to_string()),
                };
                if let Err(e) = weights.read_at(written, piece) {
                    break Err(e);
                }
                if let Err(e) = staging.write(piece) {
                    break Err(e);
                }
                let copy = || -> Result<(), String> {
                    device
                        .begin_command_buffer(
                            command_buffer,
                            &vk::CommandBufferBeginInfo::default()
                                .flags(vk::CommandBufferUsageFlags::ONE_TIME_SUBMIT),
                        )
                        .map_err(|e| format!("begin_command_buffer {e:?}"))?;
                    let region = vk::BufferCopy::default()
                        .dst_offset(written as vk::DeviceSize)
                        .size(size as vk::DeviceSize);
                    device.cmd_copy_buffer(
                        command_buffer,
                        staging.buffer,
                        self.weights.buffer,
                        std::slice::from_ref(&region),
                    );
                    device
                        .end_command_buffer(command_buffer)
                        .map_err(|e| format!("end_command_buffer {e:?}"))?;
                    // Waited on before the next chunk overwrites the staging buffer. The pool was
                    // created with `RESET_COMMAND_BUFFER`, so re-beginning the same buffer is
                    // legal once its submission has completed.
                    self.submit_and_wait(command_buffer)
                };
                if let Err(e) = copy() {
                    break Err(e);
                }
                written += size;
            };
            device.free_command_buffers(self.command_pool, &[command_buffer]);
            // `staging` drops here, after the last fence has been waited on.
            outcome
        }
    }

    fn allocate_command_buffer(&self) -> Result<vk::CommandBuffer, String> {
        let info = vk::CommandBufferAllocateInfo::default()
            .command_pool(self.command_pool)
            .level(vk::CommandBufferLevel::PRIMARY)
            .command_buffer_count(1);
        // SAFETY: an allocation from this net's own pool, which no other thread can reach.
        let buffers = unsafe { self.context.device.allocate_command_buffers(&info) }
            .map_err(|e| format!("allocate_command_buffers {e:?}"))?;
        buffers
            .first()
            .copied()
            .ok_or_else(|| "allocate_command_buffers returned nothing".into())
    }

    fn create_fence(&self) -> Result<vk::Fence, String> {
        // SAFETY: a plain object creation on a device this net holds an `Arc` to.
        unsafe {
            self.context
                .device
                .create_fence(&vk::FenceCreateInfo::default(), None)
                .map_err(|e| format!("create_fence {e:?}"))
        }
    }
}