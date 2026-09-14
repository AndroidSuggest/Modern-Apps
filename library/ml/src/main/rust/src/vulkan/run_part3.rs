                let region = vk::BufferCopy::default()
                    .src_offset(u64::from(from) * 2)
                    .dst_offset(at)
                    .size(u64::from(elems) * 2);
                device.cmd_copy_buffer(
                    self.command_buffer,
                    self.arena.buffer,
                    self.staging.buffer,
                    std::slice::from_ref(&region),
                );
                at += u64::from(elems) * 2;
            }
            device.end_command_buffer(self.command_buffer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()?;
        }
        let mut halves = vec![0u16; (total / 2) as usize];
        self.staging.read_f16(&mut halves)?;
        let mut out = Vec::with_capacity(total as usize);
        for half in halves {
            out.extend_from_slice(&half.to_le_bytes());
        }
        Ok(out)
    }

    /// Copy `bytes` into arena ranges, the inverse of [`Net::read_arena`].
    fn write_arena(&mut self, ranges: &[(u32, u32)], bytes: &[u8]) -> Result<(), String> {
        let mut at = 0usize;
        for batch in batches(ranges, self.staging.size) {
            let size: usize = batch.iter().map(|&(_, e)| e as usize * 2).sum();
            let Some(slice) = bytes.get(at..at + size) else {
                return Err(format!("a cache shorter than its {} ranges", ranges.len()));
            };
            self.write_batch(&batch, slice)?;
            at += size;
        }
        Ok(())
    }

    /// One staging-sized batch, the inverse of [`Net::read_batch`].
    fn write_batch(&mut self, ranges: &[(u32, u32)], bytes: &[u8]) -> Result<(), String> {
        self.staging.write(bytes)?;
        let device = &self.context.device;
        // SAFETY: as `read_arena`.
        unsafe {
            device
                .begin_command_buffer(self.command_buffer, &vk::CommandBufferBeginInfo::default())
                .map_err(|e| format!("begin: {e:?}"))?;
            let mut at = 0u64;
            for &(to, elems) in ranges {
                let region = vk::BufferCopy::default()
                    .src_offset(at)
                    .dst_offset(u64::from(to) * 2)
                    .size(u64::from(elems) * 2);
                device.cmd_copy_buffer(
                    self.command_buffer,
                    self.staging.buffer,
                    self.arena.buffer,
                    std::slice::from_ref(&region),
                );
                at += u64::from(elems) * 2;
            }
            device.end_command_buffer(self.command_buffer).map_err(|e| format!("end: {e:?}"))?;
            self.run_once()
        }
    }

    /// Submit the recorded command buffer and wait for it, then restore the plan's recording.
    ///
    /// # The restore is not optional
    ///
    /// `Net` records its plan into `command_buffer` **once** and re-submits it for every
    /// inference. The transfer helpers above record into that same buffer, which resets it - so
    /// without putting the plan back, the next `infer` submits a buffer holding nothing but a
    /// `vkCmdCopyBuffer`. No shader runs, the outputs are whatever the arena already held, and
    /// the model answers with fluent nonsense.
    ///
    /// That is exactly what shipping the precomputed cache did: the bake prefilled correctly,
    /// exported, and every generation *after* the export was reading a net that no longer had a
    /// plan recorded.
    ///
    /// # Safety
    ///
    /// The command buffer must be recorded and ended, and not already in flight.
    unsafe fn run_once(&mut self) -> Result<(), String> {
        let device = &self.context.device;
        let submit =
            vk::SubmitInfo::default().command_buffers(std::slice::from_ref(&self.command_buffer));
        unsafe {
            device.reset_fences(&[self.fence]).map_err(|e| format!("reset: {e:?}"))?;
            // The queue is shared with every other net in the process, so the lock is not
            // optional - see `Context::lock_queue`.
            let guard = self.context.lock_queue();
            let submitted = device
                .queue_submit(self.context.queue, std::slice::from_ref(&submit), self.fence)
                .map_err(|e| format!("queue_submit {e:?}"));
            drop(guard);
            submitted?;
            device
                .wait_for_fences(&[self.fence], true, FENCE_TIMEOUT_NS)
                .map_err(|e| format!("wait_for_fences {e:?}"))?;
        }
        // SAFETY: the transfer above is complete - the fence was waited on - so the buffer is
        // free to re-record.
        unsafe { self.record() }
    }

    /// Bytes a second, measured by copying the arena to a scratch buffer of the same size.
    ///
    /// # What this settles
    ///
    /// `vkCmdCopyBuffer` is the simplest thing a GPU can do with memory: no unpacking, no
    /// arithmetic, no descriptors. Whatever rate it reaches is the ceiling every kernel here is
    /// working under.
    ///
    /// The decode step reads 1.32 GB and takes 270 ms, which is 4.9 GB/s. If a plain copy also
    /// sits near that, the shaders are already at the memory system's limit and no amount of
    /// saved arithmetic - integer dot products, fewer dispatches, better occupancy - can help.
    /// If the copy is several times faster, the gap is arithmetic and worth chasing.
    ///
    /// Three passes, best kept: the first pays for the allocation and any first-touch cost.
    pub fn copy_bandwidth(&mut self) -> Result<f64, String> {
        let scratch = Buffer::device_local(&self.context, self.arena.size)?;
        let mut best = f64::MAX;
        for _ in 0..3 {
            let started = std::time::Instant::now();
            // SAFETY: `scratch` is live, exactly the arena's size, and nothing is in flight -
            // `copy_arena` waits on its own fence before returning.
            unsafe { self.copy_arena(&scratch)? };
            best = best.min(started.elapsed().as_secs_f64());
        }
        // Read and written, so twice the buffer.
        Ok((self.arena.size as f64 * 2.0) / best)
    }

    /// The first `positions` of every pinned tensor, as raw fp16 bytes.
    ///
    /// # What this is for
    ///
    /// The system block and tool declarations are the same ~1,100 positions on every device and
    /// every launch, and prefilling them costs 14 seconds on a phone. They are also the same
    /// *numbers*: the tokens do not change, so neither do the keys and values. Computing them
    /// once here and shipping the result means no device ever computes them again.
    ///
    /// The layout is per-tensor and in plan order - `positions * width` elements from the start
    /// of each - so it does not depend on the arena's offsets and survives a different cache
    /// tier on the far side. It is not bit-identical across GPUs, which does not matter: the
    /// difference is fp16 rounding, well inside the quantisation noise the weights already have.
    pub fn export_pinned(&mut self, tensors: usize, positions: u32) -> Result<Vec<u8>, String> {
        let mut wanted = Vec::new();
        // `tensors` rather than all of them: a plan's pinned list is whatever survives between
        // submits, and for Gemma that is the thirty KV caches *and* the soft-token buffers the
        // multimodal path keeps. Only the caches are a function of the prompt prefix; the rest
        // belong to whatever image or clip was last seen and mean nothing to another device.
        let Some(caches) = self.plan.pinned.get(..tensors) else {
            return Err(format!("{tensors} pinned of {}", self.plan.pinned.len()));
        };
        for cache in caches {
            let width = cache.shape.w;
            if positions > cache.shape.c {
                return Err(format!("{positions} positions of a {} cache", cache.shape.c));
            }
            wanted.push((cache.at, positions * width));
        }
        self.read_arena(&wanted)
    }

    /// Write `bytes` back over the first `positions` of every pinned tensor.
    ///
    /// The inverse of [`Net::export_pinned`], and it checks the length rather than trusting it -
    /// a file from a different model or a different position count would otherwise be written
    /// into the caches and answered from.
    pub fn import_pinned(
        &mut self,
        tensors: usize,
        positions: u32,
        bytes: &[u8],
    ) -> Result<(), String> {
        let mut wanted = Vec::new();
        let mut expect = 0usize;
        let Some(caches) = self.plan.pinned.get(..tensors) else {
            return Err(format!("{tensors} pinned of {}", self.plan.pinned.len()));
        };
        for cache in caches {
            if positions > cache.shape.c {
                return Err(format!("{positions} positions of a {} cache", cache.shape.c));
            }
            let elems = positions * cache.shape.w;
            wanted.push((cache.at, elems));
            expect += elems as usize * 2;
        }
        if bytes.len() != expect {
            return Err(format!("a cache of {} bytes, not {expect}", bytes.len()));
        }
        self.write_arena(&wanted, bytes)
    }

    pub fn output_size(&self) -> Result<(u32, u32), String> {
        let output = self.plan.output()?;
        Ok((output.shape.w, output.shape.h))
    }

    /// Every output binding, so a multi-output caller can size and shape the maps
    /// [`Net::infer_letterboxed`] returns without rebuilding the plan.
    pub fn output_shapes(&self) -> &[crate::nets::Binding] {
        &self.plan.outputs
    }
}

/// Total elements across `bindings`, which is how much staging space one direction needs.
fn binding_elems(bindings: &[crate::nets::Binding]) -> usize {
    bindings.iter().map(|b| b.shape.len() as usize).sum()
}

/// Long enough that a slow first dispatch on a cold driver is not mistaken for a hang,
/// short enough that a genuinely hung GPU does not block a UI thread forever. `:camera`
/// runs this on a dedicated executor, `:photos` on its own thread, so neither blocks the
/// main thread even at the limit.
///
/// # Raised from five seconds
///
/// Five was chosen for a desktop, where any submit is milliseconds and a slow one means a hung
/// GPU. A phone is not that: a legitimate prefill of 64 positions streams 1.30 GB of weights and
/// takes seconds, and the old bound turned that into `wait_for_fences TIMEOUT` - which poisons
/// the net, so the retry fails too and the user sees two dead turns rather than a slow one.
///
/// Twenty still bounds a genuine hang; it just no longer calls slow hardware broken.
const FENCE_TIMEOUT_NS: u64 = 60_000_000_000;

fn push_bytes(push: &crate::nets::Push) -> &[u8] {
    // SAFETY: `Push` is `repr(C)` and entirely `u32`, so it has no padding and no
    // uninitialised bytes; it is read as exactly its own size.
    unsafe {
        std::slice::from_raw_parts(
            (push as *const crate::nets::Push).cast::<u8>(),
            std::mem::size_of::<crate::nets::Push>(),
        )
    }
}

impl Drop for Net {
    fn drop(&mut self) {
        // SAFETY: `vkDeviceWaitIdle` returns only once nothing on the device is pending, so
        // after it everything below is safe to destroy — including the case where `infer`
        // timed out and left a submission in flight, which is why `poisoned` only has to
        // block further submits and not the teardown.
        //
        // The result is ignored because the only ways it fails are device loss and host
        // OOM. After a lost device the spec explicitly permits destroying objects, and a
        // host OOM here means the process is about to die anyway; in both cases leaking
        // every handle instead would be worse.
        //
        // The lock is held across the wait because `vkDeviceWaitIdle` needs every queue on
        // the device to itself. Destroying the pool frees the command buffer with it, and needs
        // no lock because the pool is this net's alone. The three `Buffer` fields free
        // themselves afterwards through their own Drop, which runs after this body.
        unsafe {
            let device = &self.context.device;
            let guard = self.context.lock_queue();
            let _ = device.device_wait_idle();
            drop(guard);
            device.destroy_fence(self.fence, None);
            device.destroy_command_pool(self.command_pool, None);
            self.pipelines.destroy(device);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The GLSL `Params` block is written by hand, so the two layouts agree only by inspection.
    /// This is the part of that agreement a compiler can hold: `std430` packs a struct of `uint`s
    /// end to end, so the Rust side must be `u32`s with no padding and nothing else.
    #[test]
    fn the_step_params_block_matches_the_shader_layout() {
        assert_eq!(std::mem::size_of::<StepParams>(), 2 * 4, "two u32 fields, no padding");
        assert_eq!(std::mem::align_of::<StepParams>(), 4);
        assert_eq!(StepParams::default().as_bytes().len(), std::mem::size_of::<StepParams>());
        assert!(
            (std::mem::size_of::<StepParams>() as vk::DeviceSize) <= StepParams::BYTES,
            "the allocation must cover the struct",
        );
    }

    /// Field order is the whole contract with the shader, and a reorder is invisible to the type
    /// system. Distinct values placed through the struct must land at distinct known offsets.
    #[test]
    fn the_step_params_fields_are_in_the_declared_order() {
        let params = StepParams { prefix: 0x1111_1111, window_start: 0x2222_2222 };
        let bytes = params.as_bytes();
        assert_eq!(bytes.get(0..4), Some(&0x1111_1111u32.to_ne_bytes()[..]), "prefix first");
        assert_eq!(bytes.get(4..8), Some(&0x2222_2222u32.to_ne_bytes()[..]), "window_start second");
    }
}

/// Split `ranges` so each batch fits `budget` bytes, splitting a long range across batches.
///
/// A single KV cache row set is 16,384 positions of 512 bytes, far past any staging buffer, so
/// splitting *within* a range is the case that matters rather than merely between them.
fn batches(ranges: &[(u32, u32)], budget: u64) -> Vec<Vec<(u32, u32)>> {
    let per = (budget / 2).max(1) as u32;
    let mut out: Vec<Vec<(u32, u32)>> = Vec::new();
    let mut current: Vec<(u32, u32)> = Vec::new();
    let mut room = per;
    for &(at, elems) in ranges {
        let mut done = 0u32;
        while done < elems {
            if room == 0 {
                out.push(std::mem::take(&mut current));
                room = per;
            }
            let take = room.min(elems - done);
            current.push((at + done, take));
            done += take;
            room -= take;
        }
    }
    if !current.is_empty() {
        out.push(current);
    }
    out
}
