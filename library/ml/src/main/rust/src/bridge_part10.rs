/// MADLAD400-3B-MT, as `:translate` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// Its own handle type for the same reason the translation handle is: it owns a different set of
/// things, and one `destroy` guessing between them would be type confusion waiting to happen.
///
/// # One net, two passes
///
/// [`Reshaped`] keyed by [`madlad::Mode`]. The encoder and the decode step (which computes the
/// untied logits head itself) are two passes over **one** ~2.9 GiB file, so two `Net`s would
/// upload it twice. A rebuild is a `device_wait_idle` and a re-record, and a decode step's cache
/// grows by one position each time, so a translation of `n` tokens costs `n + 1` of them. That is
/// the known cost of holding the KV cache on the host, and it is what a future prefix bound in
/// [`crate::nets::Push`] would remove.
///
/// # The weights file stays open
///
/// Unlike Supertonic, whose host-side tensors are read once into [`supertonic::Conditioning`],
/// MADLAD's host-side tensors are the ~250 MiB input table (gathered a 1 KB row per token) and
/// the two 1 KB bias tables (read once per shape). So the [`Streamed`] is retained and
/// [`madlad_part1::embed_positions`] / [`madlad_part1::relative_bias`] read from it per call.
///
/// # The bias is computed per call, on the host
///
/// [`madlad_part1::relative_bias`] gathers the `[32, 16]` block-0 tables into the `[16, queries, keys]`
/// matrix one shape needs. The encoder computes one matrix per sentence; each decode step
/// computes one row. Both arrive as plan inputs beside the token — never as weights, which
/// would need a table per shape.
///
/// The tensor order is the contract in `maml_convert.collect_madlad` — see `nets::madlad`.
struct MadladHandle {
    net: Reshaped<madlad::Mode>,
    weights: Streamed,
    /// `scripts/ml/fetch_madlad400.py`'s Unigram table, parsed per translation.
    tokenizer: Vec<u8>,
}

fn madlad_plan(offsets: &Offsets, mode: madlad::Mode) -> Result<Plan, String> {
    madlad::build(offsets, mode)
}

/// The two GPU passes, as [`madlad_post::Nets`] wants them.
///
/// There is no host-side KV cache any more: the decode plan holds one in the arena and writes to
/// it in place. A sentence cannot leak into the next one because switching back to
/// [`madlad::Mode::Encode`] and out again re-records the plan, and the first step of a translation
/// writes row zero before reading anything.
struct MadladNets<'a> {
    net: &'a mut Reshaped<madlad::Mode>,
    weights: &'a Streamed,
    /// Source positions, so a decode step records at the length the encoder ran at.
    src_len: u32,
}

impl MadladNets<'_> {
    fn new<'a>(handle: &'a mut MadladHandle) -> MadladNets<'a> {
        MadladNets { net: &mut handle.net, weights: &handle.weights, src_len: 0 }
    }
}

impl madlad_post::Nets for MadladNets<'_> {
    fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String> {
        let len = u32::try_from(source.len()).map_err(|_| "a source longer than u32")?;
        let reader = self.weights.reader();
        // The embedding with no scale and no positions — T5 has neither — all on the host.
        // See `nets::madlad` for why none of that is a shader.
        let embedded = madlad_part1::embed_positions(reader, source, 0)?;
        // The encoder bias: bidirectional over 0..len, gathered from the encoder block-0 table.
        let queries: Vec<u32> = (0..len).collect();
        let bias = madlad_part1::relative_bias(reader, madlad::TABLE_ENC, &queries, &queries, true)?;
        let net = self.net.at(madlad::Mode::Encode { len })?;
        let out = one_output(net.infer_raw_many(&[&embedded, &bias])?)?;
        self.src_len = len;
        // The plan produces `[d_model, 1, len]`; the trait's contract is `[len, d_model]`. One
        // transpose here rather than a comment that disagrees with the trait.
        Ok(transpose(&out, madlad::D_MODEL as usize, source.len()))
    }

    fn decode_step(
        &mut self,
        token: u32,
        step: usize,
        encoded: &[f32],
    ) -> Result<Vec<f32>, String> {
        let width = madlad::D_MODEL as usize;
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= madlad::MAX_DECODE_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                madlad::MAX_DECODE_POSITIONS
            ));
        }
        let reader = self.weights.reader();
        let embedded = madlad_part1::embed_positions(reader, &[token], cache_len)?;
        if !encoded.len().is_multiple_of(width) {
            return Err(format!("{} encoder values is not a whole number of {width}", encoded.len()));
        }
        let src_len = (encoded.len() / width) as u32;
        if src_len != self.src_len {
            return Err(format!("a step over {src_len} source positions after {}", self.src_len));
        }
        // Back to `[d_model, 1, src_len]`, which is what the cross-attention projections read.
        let source = transpose(encoded, encoded.len() / width, width);

        // The step's bias row: query `step` (absolute) against keys `0..=step`, causal, from
        // the decoder block-0 table. Padded with zeros to the plan width; `softmax_prefix`
        // only normalises the leading `prefix + 1` entries, so the tail never matters.
        let queries = [cache_len];
        let keys: Vec<u32> = (0..=cache_len).collect();
        let row = madlad_part1::relative_bias(reader, madlad::TABLE_DEC, &queries, &keys, false)?;
        let mut bias = vec![0.0f32; madlad::HEADS as usize * madlad::MAX_DECODE_POSITIONS as usize];
        for head in 0..madlad::HEADS as usize {
            let from = head * keys.len();
            let to = head * madlad::MAX_DECODE_POSITIONS as usize;
            bias[to..to + keys.len()].copy_from_slice(&row[from..from + keys.len()]);
        }

        // The key no longer carries the step, so this matches after the first decode step and
        // `at` stops re-recording. The KV cache stays in the arena between submits.
        let net = self.net.at(madlad::Mode::DecodeStep { src_len })?;
        // The one thing that changes per token, and it is a memcpy rather than a re-record.
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        let out = net.infer_raw_many(&[&embedded, &source, &bias])?;

        // Four logits splits and nothing else: the K and V rows are written straight into the
        // device-side cache by the plan, so there is no cache to bring back.
        if out.len() != madlad::HEAD_SPLITS {
            return Err(format!(
                "a decode step returned {} tensors, not {}",
                out.len(),
                madlad::HEAD_SPLITS
            ));
        }
        let mut logits = Vec::with_capacity(madlad::VOCAB as usize);
        for half in out.iter().take(madlad::HEAD_SPLITS) {
            logits.extend_from_slice(half);
        }
        if logits.len() != madlad::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), madlad::VOCAB));
        }
        Ok(logits)
    }
}

/// Bring up MADLAD from its one `.maml` and its Unigram tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
///
/// `createMadlad` mirrors `createNllb`, and `translateMadlad` takes a **target tag**
/// (`<2en>`) rather than two flores ids — the protocol difference vs NLLB. Agreed with
/// app-eng (team `nllb-translate`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMadlad<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
    tokenizer: JByteArray<'l>,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail: the
    // caller detached it, so a path that returns without wrapping it leaks it for the life of the
    // process. Every check below the adoption is therefore free to fail; nothing above it is.
    if fd < 0 {
        log(&format!("madlad is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_madlad(&mut env, file, offset, length, &tokenizer) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("madlad is unavailable: {e}"));
            0
        }
    }
}

fn build_madlad<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    tokenizer: &JByteArray<'l>,
) -> Result<MadladHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::MADLAD)?;
    if weights.len() != madlad::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), madlad::TENSORS));
    }

    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here purely to refuse a bad table at construction rather than at the first
    // translation, when the UI has already committed to having a working engine.
    let parsed = Table::parse_with(&tokenizer, T5)?;
    if parsed.len() != madlad::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), madlad::VOCAB));
    }

    // The smallest legal encoder, immediately replaced: `Net::new` needs a plan and the real shapes
    // are not known until a sentence arrives. `Net::rebuild` only ever grows the arena.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        madlad::Mode::Encode { len: SMALLEST },
        madlad_plan,
    )?;
    Ok(MadladHandle { net, weights, tokenizer })
}

/// Translate [`text`] into the language `tag` names (e.g. `"<2en>"`), or null on failure.
///
/// `text` must already have double spaces collapsed (HF's T5 normaliser; the Unigram model
/// itself is the identity). The tag is validated against the table — an unknown tag is refused
/// rather than mistranslated.
///
/// Unlike NLLB's `translateNllb`, ONE tag is required rather than two language tokens: the tag
/// leads the encoder source and the decoder starts unforced from `<unk>`. Backwards it produces
/// fluent output in the wrong language rather than an error.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMadlad` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_translateMadlad<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    tag: JString<'l>,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createMadlad` and is still live. It is
    // `&mut` because a decode step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut MadladHandle) };
    let translated = match (env.get_string(&text), env.get_string(&tag)) {
        (Ok(text), Ok(tag)) => {
            run_madlad(handle, &String::from(text), &String::from(tag))
        }
        _ => Err("cannot read the source text or the target tag".into()),
    };
    match translated {
        Ok(out) => match env.new_string(&out) {
            Ok(string) => string.into_raw(),
            Err(e) => {
                log(&format!("madlad cannot return its translation: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("madlad failed: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn run_madlad(handle: &mut MadladHandle, text: &str, tag: &str) -> Result<String, String> {
    // Cloned so the table can borrow it while the nets borrow the handle mutably.
    let tokenizer = handle.tokenizer.clone();
    let table = Table::parse_with(&tokenizer, T5)?;
    let mut nets = MadladNets::new(handle);
    madlad_post::translate(&mut nets, &table, tag, text)
}

/// Free MADLAD's net, its open weights file and its tokenizer table.
///
/// Exactly once per non-zero handle from `createMadlad`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMadlad`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMadlad<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMadlad` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut MadladHandle) });
}
