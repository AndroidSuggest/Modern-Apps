impl Gemma4Handle {
    /// Feed one token and return its logits, or `None` while only filling the cache.
    fn step(&mut self, token: u32, want_logits: bool) -> Result<Option<Vec<f32>>, String> {
        let (hidden, per_layer) = self.gather(token)?;
        self.step_hidden(&hidden, &per_layer, want_logits)
    }

    /// One token's embedding and per-layer inputs, device-combined when available.
    ///
    /// The 36 int4 rows still gather on host (`int4_row` is mmap-cheap); the
    /// 13.8M-MAC `combine` SGEMV runs on-device via `gather_net` when the
    /// handle carries it, else on host via `gemma4::gather` (bit-parity gate
    /// between the two lives in `run_gemma4 --gather-parity`). `gather_net`
    /// is `None` until that gate passes — today this is always the host path.
    fn gather(&mut self, token: u32) -> Result<(Vec<f32>, Vec<f32>), String> {
        // Host rows (both paths need them — see `gemma4_gather` module docs
        // for why the row lookups did not move).
        let reader = self.embed.reader();
        if token >= gemma4::VOCAB {
            return Err(format!("token {token} is past the {}-entry vocabulary", gemma4::VOCAB));
        }
        let hidden = reader.int4_row(
            gemma4::embed::TOKENS,
            gemma4::embed::TOKENS + 1,
            &[gemma4::VOCAB, gemma4::D_MODEL],
            token,
        )?;
        let per_layer_row = if gemma4::embed::PLACEHOLDERS.contains(&token) { 0 } else { token };
        let mut embedded = Vec::with_capacity((gemma4::PER_LAYER as usize) * gemma4::LAYERS);
        for i in 0..gemma4::LAYERS {
            let at = gemma4::embed::table(i);
            embedded.extend(reader.int4_row(at, at + 1, &[gemma4::VOCAB, gemma4::PER_LAYER], per_layer_row)?);
        }
        drop(reader);
        if let Some(net) = self.gather_net.as_mut() {
            let at = net.at(())?;
            let out = at.infer_raw_many(&[&hidden, &embedded])?;
            if out.len() == 1 && out[0].len() == (gemma4::PER_LAYER as usize) * gemma4::LAYERS {
                return Ok((hidden, out.into_iter().next().unwrap_or_default()));
            }
            // Wrong shape: fall through to the host combine rather than failing.
        }
        // Host combine: the same projection + grouped-norm + scaled add that
        // `gemma4_gather::build_plan` runs on-device. Inlined (not shared)
        // because `gemma4_part4::combine` is private to that module AND reads
        // through a `Reader`; this takes the already-gathered rows. The two
        // must agree exactly, which the `--gather-parity` gate enforces.
        Ok((hidden.clone(), gather_combine_host(&self.embed, &hidden, &embedded)?))
    }

    /// Feed one **soft token**: an encoder's output standing in for a token's embedding.
    ///
    /// The per-layer inputs come from the pad token, which is what the reference does - it
    /// rewrites the placeholder id to `pad_token_id` before gathering, then overwrites only the
    /// hidden state with the encoder's row.
    fn step_soft(&mut self, hidden: &[f32], want_logits: bool) -> Result<Option<Vec<f32>>, String> {
        let per_layer = gemma4::gather_soft(&self.embed.reader(), hidden, 0)?;
        self.step_hidden(hidden, &per_layer, want_logits)
    }

    /// Feed one token and return the greedy next token, reduced on the device.
    ///
    /// Same transformer step as [`step`](Self::step) but the head runs
    /// through the greedy plan (`build_plan_greedy`): 16 splits concatenate
    /// on-device, one `Argmax` reduces to the winning index, and 1 value
    /// crosses back instead of 262144 logits. Falls back to the host argmax
    /// when the head net is absent. The sampling path (`logitsGemma4`) never
    /// calls this — it needs the full logits.
    fn step_greedy(&mut self, token: u32) -> Result<u32, String> {
        let (hidden, per_layer) = self.gather(token)?;
        self.step_hidden_greedy(&hidden, &per_layer)
    }

    /// The step itself, once both halves of the input are in hand.
    fn step_hidden(
        &mut self,
        hidden: &[f32],
        per_layer: &[f32],
        want_logits: bool,
    ) -> Result<Option<Vec<f32>>, String> {
        if self.position >= self.context {
            return Err(format!("the cache is full at {} positions", self.context));
        }
        let row = |table: &[f32], width: u32| -> Vec<f32> {
            let from = (self.position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let local = row(&self.local, gemma4::HEAD_DIM);
        let global = row(&self.global, gemma4::GLOBAL_HEAD_DIM);
        // Prefill positions do not need logits, and until now they computed them anyway: the
        // plan was `DecodeStep` unconditionally and `want_logits` only decided whether the host
        // kept the result. The head is four int4 projections over the 262,144-entry vocabulary,
        // 227 MB of the 1.30 GB of weights - 17.5 % of the bytes a pass touches, discarded on
        // every one of the ~1,870 positions a prompt is long.
        //
        // `Prefill { tokens: 1 }` runs all thirty-five layers, fills all thirty caches and stops
        // before the final norm and the head. Its one output is the hidden state, which nothing
        // here wants.
        //
        // SAFETY OF SWITCHING PLANS MID-CONVERSATION, which is not obvious and is not guaranteed
        // by the type system: `Reshaped::at` re-records whenever the mode changes, and the caches
        // live in the arena, so the two plans must agree on where they are. They do - measured,
        // not assumed: every cache operand is identical across the two, at all twenty-nine
        // offsets, because at `tokens: 1` the inputs are the same size and `finish` assigns arena
        // offsets by walking the pinned list in declaration order. That equality is pinned by a
        // test in `nets::gemma4`; if it ever fails, prefill writes the caches where decode does
        // not read them and the model answers fluently from whatever was in the arena.
        //
        // The arena never reallocates either, which would drop the caches outright: decode's is
        // the larger of the two, the net is created at decode, and `rebuild` only grows.
        let mode = if want_logits {
            gemma4::Mode::DecodeStep
        } else {
            gemma4::Mode::Prefill { tokens: 1 }
        }
        .at(self.context);
        let at = self.net.at(mode)?;
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let ran = std::time::Instant::now();
        let out = at.infer_raw_many(&[hidden, per_layer, &local, &global])?;
        // Every sixteenth, so the log is a sample rather than a flood.
        if self.position % 16 == 0 {
            log(&format!(
                "gemma4 decode at {}: {:.0} ms gpu, head {}",
                self.position,
                ran.elapsed().as_secs_f64() * 1000.0,
                if want_logits { "on" } else { "off" },
            ));
        }
        self.position += 1;
        if !want_logits {
            return Ok(None);
        }
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not 1 hidden state", out.len()));
        }
        let hidden = &out[0];
        if hidden.len() != gemma4::D_MODEL as usize {
            return Err(format!("hidden state of {} values, not {}", hidden.len(), gemma4::D_MODEL));
        }
        Ok(Some(self.head_logits(hidden)?))
    }

    /// The greedy step itself, once both halves of the input are in hand.
    ///
    /// Same transformer submit as [`step_hidden`](Self::step_hidden) with
    /// `want_logits`, then the device argmax via
    /// [`head_greedy`](Self::head_greedy): 1 value back, no 512 KB readback,
    /// no host scan. `stepGemma4` calls this; `logitsGemma4` keeps calling
    /// `step_hidden` for the full logits.
    fn step_hidden_greedy(
        &mut self,
        hidden: &[f32],
        per_layer: &[f32],
    ) -> Result<u32, String> {
        // The transformer half is identical to the logits path: DecodeStep,
        // same params, same cache discipline. Only the head differs.
        if self.position >= self.context {
            return Err(format!("the cache is full at {} positions", self.context));
        }
        let row = |table: &[f32], width: u32| -> Vec<f32> {
            let from = (self.position * width) as usize;
            table[from..from + width as usize].to_vec()
        };
        let local = row(&self.local, gemma4::HEAD_DIM);
        let global = row(&self.global, gemma4::GLOBAL_HEAD_DIM);
        let mode = gemma4::Mode::DecodeStep.at(self.context);
        let at = self.net.at(mode)?;
        at.set_params(StepParams {
            prefix: self.position,
            window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
        })?;
        let out = at.infer_raw_many(&[hidden, per_layer, &local, &global])?;
        self.position += 1;
        if out.len() != 1 {
            return Err(format!("a step returned {} tensors, not 1 hidden state", out.len()));
        }
        let hidden = &out[0];
        if hidden.len() != gemma4::D_MODEL as usize {
            return Err(format!("hidden state of {} values, not {}", hidden.len(), gemma4::D_MODEL));
        }
        self.head_greedy(hidden)
    }

    /// Logits for `hidden`, GPU head when the EMBED file carries chunks.
    ///
    /// The device runs the 16 chunk projections and the host concatenates
    /// the splits, then the same softcap path as the host head below. Falls
    /// back to the host head on files without chunks (or when the head net
    /// failed to build).
    fn head_logits(&mut self, hidden: &[f32]) -> Result<Vec<f32>, String> {
        let Some(head) = self.head.as_mut() else {
            return self.tied_head_logits(hidden);
        };
        let at = head.at(())?;
        let out = at.infer_raw_many(&[hidden])?;
        if out.len() != crate::nets::gemma4_head::HEAD_CHUNKS {
            return Err(format!(
                "the head returned {} splits, not {}",
                out.len(),
                crate::nets::gemma4_head::HEAD_CHUNKS
            ));
        }
        let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
        for split in &out {
            if split.len() != crate::nets::gemma4_head::CLASSES_PER_CHUNK as usize {
                return Err(format!("a head split of {} values, not {}", split.len(), crate::nets::gemma4_head::CLASSES_PER_CHUNK));
            }
            for &value in split {
                logits.push(gemma4::LOGIT_CAP * (value / gemma4::LOGIT_CAP).tanh());
            }
        }
        Ok(logits)
    }

    /// The greedy next token for `hidden`, reduced on the device.
    ///
    /// Same head submit through the greedy plan (`build_plan_greedy`): the 16
    /// splits concatenate on-device and one `Argmax` op reduces to a single
    /// scalar holding the winning index as fp16 bits. Reads back 1 value
    /// instead of 262144 — no 512 KB readback, no host scan. Falls back to
    /// the host argmax over [`head_logits`](Self::head_logits) when the head
    /// net is absent. The full-logits path (`logitsGemma4`) never calls this.
    ///
    /// NOTE: the greedy plan has a different output signature (16 splits + 1
    /// id) from the logits plan, so it runs on the dedicated `head_greedy`
    /// handle (same EMBED upload, separate recording). Falls back to the
    /// host argmax when that handle is absent (no int8 chunks in file).
    fn head_greedy(&mut self, hidden: &[f32]) -> Result<u32, String> {
        use crate::nets::gemma4_head;
        let Some(greedy) = self.head_greedy.as_mut() else {
            let logits = self.head_logits(hidden)?;
            return Ok(argmax(&logits));
        };
        let at = greedy.at(())?;
        let out = at.infer_raw_many(&[hidden])?;
        if out.len() != gemma4_head::HEAD_CHUNKS + 1 {
            return Err(format!(
                "greedy head returned {} outputs, not {}",
                out.len(),
                gemma4_head::HEAD_CHUNKS + 1
            ));
        }
        let id_out = &out[gemma4_head::HEAD_CHUNKS];
        if id_out.len() != 2 {
            return Err(format!("greedy id holds {} values, not 2", id_out.len()));
        }
        Ok(id_out[1] as u32 * 2048 + id_out[0] as u32)
    }

    /// Tied-head logits on the host: `hidden @ H^T` over the raw-scale head table.
    ///
    /// The head table is int4 `[VOCAB, D_MODEL]` (S10's `embedder.decode`
    /// composite), dequantised a quarter at a time (see `HEAD_SPLITS`) to
    /// bound the transient fp32. `LOGIT_CAP` softcapping matches the
    /// reference's `_post_process_decoding`. A Vulkan tied-head GEMM
    /// (chunked over vocab, table resident in VRAM) is the follow-up that
    /// removes this ~0.4 GFLOP host matmul from every step.
    ///
    /// The table is raw-scale (see [`gemma4::EMBED_GAIN`]): no division, no
    /// un-scaling - the dots go straight to the softcap, exactly as the
    /// reference's `decode_softmax` does.
    fn tied_head_logits(&self, hidden: &[f32]) -> Result<Vec<f32>, String> {
        use gemma4::embed;
        let mut logits = Vec::with_capacity(gemma4::VOCAB as usize);
        for split in 0..gemma4::HEAD_SPLITS {
            let start = (split as u32) * gemma4::CLASSES_PER_SPLIT;
            let block = self.embed.reader().fp16_rows(
                embed::HEAD_TABLE,
                &[gemma4::VOCAB, gemma4::D_MODEL],
                start,
                gemma4::CLASSES_PER_SPLIT,
            )?;
            for row in block.chunks_exact(gemma4::D_MODEL as usize) {
                let dot: f32 = row.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum();
                let capped = gemma4::LOGIT_CAP * (dot / gemma4::LOGIT_CAP).tanh();
                logits.push(capped);
            }
        }
        Ok(logits)
    }


    /// Reallocate the caches so at least `needed` positions fit. Returns the new capacity.
    ///
    /// # This throws the cache away
    ///
    /// The caches live in the arena, and a bigger arena is a different allocation - nothing
    /// copies the old contents across. So a grow resets the position to zero and the caller has
    /// to feed the whole prompt again.
    ///
    /// That is why the tiers double rather than creep: over a long conversation the re-prefill
    /// happens four times, not once per message. The caller is told by the return value, which
    /// it must treat as "the cache is now empty".
    fn grow(&mut self, needed: u32) -> Result<u32, String> {
        if needed <= self.context {
            return Ok(self.context);
        }
        let Some(tier) = gemma4::CONTEXT_TIERS
            .iter()
            .copied()
            .find(|tier| *tier >= needed && *tier <= self.ceiling)
        else {
            return Err(format!(
                "{needed} positions, and this device's cache stops at {}",
                self.ceiling
            ));
        };
        // Carry the cache across rather than dropping it.
        //
        // A bigger arena is a different allocation, so nothing moves by itself - and the first
        // version of this simply reset to zero and let the caller re-feed. That is very
        // expensive and, worse, silently undoes the precomputed prefix: `loadPrefix` grows to
        // fit the ~1,870-position system block and tool declarations, then the first turn needs
        // room for a reply, grows again, and the cache that was just loaded from disk is gone.
        //
        // Copying it out and back costs one staging round trip - tens of milliseconds against
        // the tens of seconds of prefill it saves.
        let carried = if self.position > 0 {
            let at = self.net.at(gemma4::Mode::DecodeStep.at(self.context))?;
            Some(at.export_pinned(gemma4::CACHE_TENSORS, self.position)?)
        } else {
            None
        };
        let mut rebuilt = Reshaped::streamed(
            context::shared()?,
            self.weights.offsets(),
            &self.weights,
            gemma4::Mode::DecodeStep.at(tier),
            gemma4_plan,
        )?;
        if let Some(bytes) = &carried {
            let at = rebuilt.at(gemma4::Mode::DecodeStep.at(tier))?;
            at.import_pinned(gemma4::CACHE_TENSORS, self.position, bytes)?;
        }
        log(&format!(
            "gemma4 cache grew {} -> {tier} positions ({} MB), so the prompt is re-fed",
            self.context,
            (u64::from(tier) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        ));
        log(&format!(
            "gemma4 carried {} positions into the new cache",
            if carried.is_some() { self.position } else { 0 }
        ));
        self.net = rebuilt;
        self.context = tier;
        // `self.position` is deliberately kept: the cache came with it.
        Ok(tier)
    }

    /// Feed many tokens in **one** submit, filling the caches and returning nothing.
    ///
    /// The prompt path, and the reason a conversation starts in under a second rather than in
    /// tens of them. At one position a pass is bandwidth-bound: it reads 1.30 GB of weights to
    /// produce a single 1536-wide vector, which measured 28.81 ms. At T positions it reads the
    /// same 1.30 GB once and every projection becomes a GEMM over T columns, so the weights are
    /// amortised T ways.
    ///
    /// Chunked rather than one submit for the whole prompt, because attention is quadratic in T
    /// and the score maps are a real allocation - eight heads of T x T at [`CHUNK`] is 16 MB,
    /// and the arena has to hold it alongside everything else.
    fn prefill(&mut self, tokens: &[u32]) -> Result<(), String> {
        if tokens.is_empty() {
            return Ok(());
        }
        if self.position as usize + tokens.len() > self.context as usize {
            return Err(format!(
                "a prompt of {} positions past this device's {} cache",
                self.position as usize + tokens.len(),
                self.context
            ));
        }
        for chunk in tokens.chunks(CHUNK as usize) {
            let width = chunk.len() as u32;
            let gathering = std::time::Instant::now();
            // Gathered per position and laid out `[C, 1, T]` - channel-major, which is what
            // every op in the pass expects and what `cache_write` transposes on the way in.
            let mut hidden = vec![0f32; (gemma4::D_MODEL * width) as usize];
            let mut per_layer =
                vec![0f32; (gemma4::PER_LAYER * gemma4::LAYERS as u32 * width) as usize];
            let mut local = vec![0f32; (gemma4::HEAD_DIM * width) as usize];
            let mut global = vec![0f32; (gemma4::GLOBAL_HEAD_DIM * width) as usize];
            for (offset, &token) in chunk.iter().enumerate() {
                let (h, p) = gemma4::gather(&self.embed.reader(), token)?;
                let column = offset as u32;
                let position = self.position + column;
                place(&mut hidden, &h, column, width);
                place(&mut per_layer, &p, column, width);
                // Written out rather than closed over: a closure returning a borrow of its own
                // argument needs a named lifetime, and two call sites do not justify one.
                let from = (position * gemma4::HEAD_DIM) as usize;
                let row = &self.local[from..from + gemma4::HEAD_DIM as usize];
                place(&mut local, row, column, width);
                let from = (position * gemma4::GLOBAL_HEAD_DIM) as usize;
                let row = &self.global[from..from + gemma4::GLOBAL_HEAD_DIM as usize];
                place(&mut global, row, column, width);
            }
            let gathered = gathering.elapsed();
            let submitted = std::time::Instant::now();
            let at = self.net.at(gemma4::Mode::Prefill { tokens: width }.at(self.context))?;
            let recorded = submitted.elapsed();
            at.set_params(StepParams {
                prefix: self.position,
                window_start: self.position.saturating_sub(gemma4::WINDOW - 1),
            })?;
            let ran = std::time::Instant::now();
            at.infer_raw_many(&[&hidden, &per_layer, &local, &global])?;
            // Timed on the device because nothing about a desktop GPU predicts a phone's. The
            // record time is called out separately: `Reshaped` re-records whenever the mode or
            // the width changes, and a prompt whose last chunk is short pays it twice.
            log(&format!(
                "gemma4 prefill {width} positions: {:.0} ms gather, {:.0} ms record, {:.0} ms gpu",
                gathered.as_secs_f64() * 1000.0,
                recorded.as_secs_f64() * 1000.0,
                ran.elapsed().as_secs_f64() * 1000.0,
            ));
            self.position += width;
        }
        Ok(())
    }
} // close impl Gemma4Handle: everything below is a free function.

/// Host `combine`: projection + grouped-norm + scaled add over rows the
/// caller already gathered. Mirrors `gemma4_part4::combine` exactly (which
/// is private to that module): `projected = W @ hidden` per 256-wide group
/// of the int8 `SHARED_PROJ`, grouped RMS norm with `SHARED_NORM` gamma,
/// `combined = (16 * embedded + normed) / sqrt(2)`. The device combine plan
/// (`gemma4_gather::build_plan`) must agree bit-for-bit — the
/// `--gather-parity` gate in `run_gemma4` enforces it.
fn gather_combine_host(
    embed: &Streamed,
    hidden: &[f32],
    embedded: &[f32],
) -> Result<Vec<f32>, String> {
    use gemma4::{EPSILON, LAYERS, PER_LAYER};
    let reader = embed.reader();
    let proj = reader.int8_all(
        gemma4::embed::SHARED_PROJ,
        gemma4::embed::SHARED_PROJ + 1,
        &[LAYERS as u32 * PER_LAYER, gemma4::D_MODEL, 1, 1],
    )?;
    let gamma = reader.fp16(gemma4::embed::SHARED_NORM, &[PER_LAYER])?;
    let rows = LAYERS;
    let wide = PER_LAYER as usize;
    const GATHER_SCALE: f32 = 16.0;
    let inv_sqrt_2 = 1.0 / std::f32::consts::SQRT_2;
    let mut out = Vec::with_capacity(rows * wide);
    for r in 0..rows {
        let row = &proj[r * wide * gemma4::D_MODEL as usize..(r + 1) * wide * gemma4::D_MODEL as usize];
        let emb = &embedded[r * wide..(r + 1) * wide];
        let mut group = vec![0f32; wide];
        for (o, wrow) in group.iter_mut().zip(row.chunks_exact(gemma4::D_MODEL as usize)) {
            *o = wrow.iter().zip(hidden.iter()).map(|(a, b)| a * b).sum::<f32>();
        }
        let mean_sq = group.iter().map(|v| v * v).sum::<f32>() / wide as f32;
        let norm = 1.0 / (mean_sq + EPSILON).sqrt();
        for (v, &g) in group.iter_mut().zip(gamma.iter()) {
            *v = *v * norm * g;
        }
        for (v, &e) in group.iter_mut().zip(emb.iter()) {
            *v = (*v + GATHER_SCALE * e) * inv_sqrt_2;
        }
        out.extend_from_slice(&group);
    }
    Ok(out)
}

fn gemma4_head_greedy_plan(offsets: &Offsets, _: ()) -> Result<Plan, String> {
    crate::nets::gemma4_head::build_plan_greedy(offsets)
}

/// The device combine plan over an EMBED file, or `None` when the file
/// predates the plan's tensor expectations (see `nets::gemma4_gather`).
///
/// Upload-once at construction alongside the head nets (same resident
/// upload, one more recording). The step path (`gather()` above) submits
/// per token with host fallback on shape mismatch.
fn gemma4_gather_plan(embed: &Streamed) -> Option<Reshaped<()>> {
    Reshaped::streamed(
        context::shared().ok()?,
        embed.offsets(),
        embed,
        (),
        |offsets, ()| crate::nets::gemma4_gather::build_plan(offsets),
    )
    .ok()
}

/// The standalone head plan over a head file (`graph::GEMMA4_HEAD`).
///
/// `None` when no head file was opened. The head file holds ONLY the 48
/// int8 triples (~402 MB), so this upload replaces the 3.6 GB EMBED upload
/// on memory-constrained devices (Pixel 8 OOM-reboot, twice, 2026-09-26:
/// TEXT 1.1 GB + EMBED 3.6 GB vs ~2.6 GB free). Preferred over
/// `gemma4_head_plan` whenever present — same numerics, one-ninth the bytes.
fn gemma4_head_file_plan(head: &Streamed) -> Option<Reshaped<()>> {
    let offsets = head.offsets();
    if offsets.len() != crate::nets::gemma4_head::HEAD_FILE_TENSORS {
        return None;
    }
    Reshaped::streamed(
        context::shared().ok()?,
        offsets,
        head,
        (),
        |offsets, ()| crate::nets::gemma4_head::build_plan_standalone(offsets, false),
    )
    .ok()
}

/// The greedy standalone head plan: 16 splits + device argmax id.
/// See `gemma4_head_file_plan` (file-size rationale) and `build_plan_greedy`.
fn gemma4_head_file_plan_greedy(head: &Streamed) -> Option<Reshaped<()>> {
    let offsets = head.offsets();
    if offsets.len() != crate::nets::gemma4_head::HEAD_FILE_TENSORS {
        return None;
    }
    Reshaped::streamed(
        context::shared().ok()?,
        offsets,
        head,
        (),
        |offsets, ()| crate::nets::gemma4_head::build_plan_standalone(offsets, true),
    )
    .ok()
}

/// The GPU tied-head plan over an EMBED file, or `None` when the file has
/// no head chunks (see `nets::gemma4_head`).
///
/// Prefer the standalone head file when one was opened (see
/// `gemma4_head_file_plan`): its 402 MB upload replaces the 3.6 GB EMBED
/// upload the EMBED-resident chunks would force. Falls back to the
/// EMBED-resident chunks (host flow) when no head file is present.
fn gemma4_head_plan(embed: &Streamed) -> Option<Reshaped<()>> {
    let offsets = embed.offsets();
    if offsets.len() < crate::nets::gemma4_head::TENSORS_WITH_HEAD {
        return None;
    }
    Reshaped::streamed(
        context::shared().ok()?,
        offsets,
        embed,
        (),
        |offsets, ()| crate::nets::gemma4_head::build_plan(offsets),
    )
    .ok()
}

/// The greedy head plan over an EMBED file: 16 splits + device argmax id.
///
/// `None` on files without int8 chunks (the greedy path needs the vector
/// routing; fp16 `ConvPoint` at 1 position is correct but the extra dispatch
/// mix is unmeasured — greedy stays on the int8 plan). The caller reads back
/// 17 outputs (16 splits for the sampling path + 1 two-lane id) and takes
/// the id for greedy decoding.
fn gemma4_head_plan_greedy(embed: &Streamed) -> Option<Reshaped<()>> {
    let offsets = embed.offsets();
    if offsets.len() < crate::nets::gemma4_head::TENSORS_WITH_HEAD8 {
        return None;
    }
    Reshaped::streamed(
        context::shared().ok()?,
        offsets,
        embed,
        (),
        gemma4_head_greedy_plan,
    )
    .ok()
}

/// Positions one prefill submit covers. Large enough that a prompt is **one** submit.
///
/// # Splitting a prefill is wrong, not merely slower
///
/// `prefill_layer` attends T queries against the chunk's own T keys. Positions in a second chunk
/// therefore never see the first chunk's keys at all, and the answer depends on where the split
/// fell - the same 31-token prompt gives "Paris", "Berlin" or "Rome" at chunk 8, 16 and 64.
/// Only the last is right, and it is right because it is a single chunk.
///
/// Verified against the sequential path, which is the decode path and unambiguously correct: a
/// single chunk agrees with it exactly at 100, 400, 900 and 1,907 positions.
///
/// The real repair is to attend over the cache rather than the chunk, as decode does, which
/// makes any chunking correct. Until then the chunk must cover the prompt, and that is
/// affordable here because the fixed prefix arrives precomputed - a turn prefills only the new
/// tokens, not the 1,900 before them.
const CHUNK: u32 = 4096;

/// Write one position's `values` into column `column` of a `[C, 1, width]` block.
///
/// The layout is channel-major - channel `c` at `c * width + column` - which is the transpose of
/// how the values arrive. Getting this backwards is not a shape error and produces a prompt whose
/// tokens are scrambled across channels.
fn place(into: &mut [f32], values: &[f32], column: u32, width: u32) {
    for (channel, &value) in values.iter().enumerate() {
        let at = channel * width as usize + column as usize;
        if let Some(slot) = into.get_mut(at) {
            *slot = value;
        }
    }
}

/// The most likely token, and nothing else.
///
/// Greedy. litertlm sampled with `top_k` 64 and `top_p` 0.95 and this does not, which is a real
/// behavioural change: replies become deterministic and slightly flatter. Sampling belongs on the
/// Kotlin side of the boundary where a seed can be held and a temperature exposed, and adding it
/// here would put a policy decision in the wrong module.
fn argmax(logits: &[f32]) -> u32 {
    let mut best = (f32::NEG_INFINITY, 0u32);
    for (index, &value) in logits.iter().enumerate() {
        if value > best.0 {
            best = (value, index as u32);
        }
    }
    best.1
}

/// Bring up Gemma 4 from its two `.maml`s and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and descriptors nothing else holds.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    text_fd: jint,
    text_offset: jlong,
    text_length: jlong,
    embed_fd: jint,
    embed_offset: jlong,
    embed_length: jlong,
    head_fd: jint,
    head_offset: jlong,
    head_length: jlong,
    tokenizer: JByteArray<'l>,
    // Bytes of KV cache this device will spend. See `gemma4::tier_for`.
    budget: jlong,
) -> jlong {
    // Both descriptors are adopted before anything may fail. The caller detached them, so a path
    // that returns without wrapping one leaks it for the life of the process - and there are
    // three here (the head is optional), so the usual single-`fd` shape is not enough.
    if text_fd < 0 || embed_fd < 0 {
        log(&format!("gemma4 is unavailable: descriptors {text_fd} and {embed_fd}"));
        return 0;
    }
    // SAFETY: the caller detached both, so nothing else owns them, and `File` closes them on drop
    // including on every failure path below.
    let text = unsafe { File::from_raw_fd(text_fd) };
    let embed = unsafe { File::from_raw_fd(embed_fd) };
    // The head file is optional: -1 means absent (old downloads), and native
    // falls back to the host head. A non-negative fd is adopted like the rest.
    let head: Option<File> = if head_fd >= 0 {
        // SAFETY: same contract as text/embed above.
        Some(unsafe { File::from_raw_fd(head_fd) })
    } else {
        None
    };
    let spans = (
        u64::try_from(text_offset),
        u64::try_from(text_length),
        u64::try_from(embed_offset),
        u64::try_from(embed_length),
        u64::try_from(head_offset),
        u64::try_from(head_length),
    );
    let built = match spans {
        (Ok(ta), Ok(tl), Ok(ea), Ok(el), Ok(ha), Ok(hl)) => {
            build_gemma4(&mut env, text, ta, tl, embed, ea, el, head, ha, hl, &tokenizer, budget.max(0) as u64)
        }
        _ => Err("a graph span that is not a positive offset and length".to_string()),
    };
    match built {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("gemma4 is unavailable: {e}"));
            0
        }
    }
}

#[allow(clippy::too_many_arguments)]
fn build_gemma4<'l>(
    env: &mut JNIEnv<'l>,
    text: File,
    text_at: u64,
    text_len: u64,
    embed: File,
    embed_at: u64,
    embed_len: u64,
    head: Option<File>,
    head_at: u64,
    head_len: u64,
    tokenizer: &JByteArray<'l>,
    budget: u64,
) -> Result<Gemma4Handle, String> {
    let weights = Streamed::open(text, text_at, text_len, graph::GEMMA4_TEXT)?;
    let embed = Streamed::open(embed, embed_at, embed_len, graph::GEMMA4_EMBED)?;
    // The optional head file: opened (header-checked against GEMMA4_HEAD)
    // only when the caller passed a live descriptor. `None` keeps the old
    // two-file behavior exactly — host head, no third upload.
    let head_file: Option<Streamed> = match head {
        Some(file) => Some(Streamed::open(file, head_at, head_len, graph::GEMMA4_HEAD)?),
        None => None,
    };
    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here to refuse a bad table at construction rather than at the first turn, when
    // the UI has already committed to having a working assistant.
    let parsed = Table::parse_with(&tokenizer, GEMMA)?;
    if parsed.len() != gemma4::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), gemma4::VOCAB));
    }
    if !parsed.has_byte_fallback() {
        return Err("a tokenizer without byte fallback, which cannot spell every reply".into());
    }
    let reader = weights.reader();
    let local = reader.fp16(gemma4::ROTARY_LOCAL, &[gemma4::MAX_CONTEXT, gemma4::HEAD_DIM])?;
    let global =
        reader.fp16(gemma4::ROTARY_GLOBAL, &[gemma4::MAX_CONTEXT, gemma4::GLOBAL_HEAD_DIM])?;
    drop(reader);
    // The largest cache this device's memory budget affords. Kotlin measures the device;
    // native turns that into positions, because only native knows a position costs 18 KB.
    let cache = gemma4::tier_for(budget);
    log(&format!(
        "gemma4 cache {cache} positions, {} MB, from a {} MB budget",
        (u64::from(cache) * u64::from(gemma4::BYTES_PER_POSITION)) / 1_000_000,
        budget / 1_000_000,
    ));
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4::Mode::DecodeStep.at(gemma4::CONTEXT_TIERS[0]),
        gemma4_plan,
    )?;
    // The GPU tied head, upload-once. Prefer the standalone head file when
    // one was opened: its ~402 MB upload replaces the 3.6 GB EMBED upload
    // (TEXT 1.1 GB + EMBED 3.6 GB = OOM-reboot on a Pixel 8, twice,
    // 2026-09-26). Falls back to the EMBED-resident chunks, then the host
    // head, when no head file is present.
    let (head, head_greedy) = match &head_file {
        Some(file) => (gemma4_head_file_plan(file), gemma4_head_file_plan_greedy(file)),
        None => (gemma4_head_plan(&embed), gemma4_head_plan_greedy(&embed)),
    };
    // The device combine: built (plan verified by unit tests) but NOT YET
    // WIRED into the step path — `gather_net: None` until the parity gate
    // below passes. The recording is cheap; the behavior change is not, so
    // it lands separately after host + P8 bit-parity is proven.
    //
    // GATE PASSED on host (2026-09-26): `--gather-parity` reports cosine
    // 1.000000 over all 6 golden-prompt tokens, device vs host. Wire it:
    // the combine submit replaces the host SGEMV in `gather()` above, with
    // host fallback on shape mismatch (same contract as the head nets).
    let gather_net: Option<Reshaped<()>> = gemma4_gather_plan(&embed);
    Ok(Gemma4Handle {
        net,
        head,
        head_greedy,
        gather_net,
        weights,
        embed,
        head_file,
        tokenizer,
        local,
        global,
        ceiling: cache,
        // Start at the smallest tier whatever the device affords. A conversation that stays
        // short never pays for a cache it does not use, and 19 MB against 301 MB is the
        // difference between the assistant being a background cost and being the reason
        // something else was killed.
        context: gemma4::CONTEXT_TIERS[0],
        position: 0,
    })
}

/// Encode text to token ids, matching HuggingFace's `tokenizers` for this vocabulary.
///
/// `specials` are matched literally and never merged into - the chat markers a template inserts.
/// Passing them from Kotlin rather than hardcoding them here is deliberate: which markers a
/// prompt may contain is a policy question, and a model that let a user's text spell a turn
/// boundary would let them forge one.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    specials: JObjectArray<'l>,
) -> jintArray {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4` and is still live.
    let handle = unsafe { &*(handle as *const Gemma4Handle) };
    let encoded = match env.get_string(&text) {
        Ok(text) => encode_gemma4(&mut env, handle, &String::from(text), &specials),
        Err(e) => Err(format!("cannot read the prompt: {e}")),
    };
    match encoded {
        Ok(ids) => match new_int_array(&mut env, &ids) {
            Ok(array) => array,
            Err(e) => {
                log(&format!("gemma4 cannot return its token ids: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("gemma4 cannot encode: {e}"));
            std::ptr::null_mut()
        }
    }
}
