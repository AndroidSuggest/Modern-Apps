/// Discard the front end's partial frame and the trunk's 230 ms of context.
///
/// # Safety
///
/// `handle` must be a live value from `createMusicGate`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_resetMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees the handle came from `createMusicGate` and is live.
    unsafe { &mut *(handle as *mut crate::gate::MusicGate) }.reset();
}

/// Free the gate. Idempotent on the Kotlin side, which nulls its handle first.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMusicGate`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMusicGate` and has not
    // been destroyed.
    drop(unsafe { Box::from_raw(handle as *mut crate::gate::MusicGate) });
}

/// whisper-base, as `:speech` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, two plans, and two kinds of cache
///
/// [`Reshaped`] keyed by [`whisper::Mode`]. The encoder runs once per utterance; a decode step is
/// re-recorded every token because its self-attention cache grows by one position, which is a
/// `device_wait_idle` and a re-record per token — the same known cost the translation handle
/// documents.
///
/// The **cross-attention** caches are the difference. Whisper's decoder cross-attends over 1500
/// encoder positions through each layer's own key and value projections, so recomputing them per
/// step would be 4.7 GMAC against the logits head's 26.5 MMAC. [`whisper::Mode::Encode`] computes
/// them once, they are held here, and each step passes them back in. See `nets::whisper`.
///
/// # The weights file stays open
///
/// [`whisper::embed_positions`] gathers a 1 KB embedding row per token out of the 26.6 MB tied table
/// on the host, so the [`Streamed`] is retained for the same reason SMaLL-100's is.
struct WhisperHandle {
    net: Reshaped<whisper::Mode>,
    weights: Streamed,
    /// Read from `generation_config.json` by Kotlin and checked once at construction.
    decoding: whisper_post::Decoding,
}

fn whisper_plan(offsets: &Offsets, mode: whisper::Mode) -> Result<Plan, String> {
    whisper::build(offsets, mode)
}

/// Cross-attention caches per decoder layer, K then V. See [`WhisperHandle`].
const WHISPER_CROSS: usize = whisper::DECODER_LAYERS * 2;

/// The two GPU passes, as [`whisper_post::Nets`] wants them, plus the cross-attention caches.
///
/// Built per transcription and dropped with it. The **self-attention** caches are not here at all
/// any more: the decode plan holds them in the arena and writes to them in place.
struct WhisperNets<'a> {
    net: &'a mut Reshaped<whisper::Mode>,
    weights: &'a Streamed,
    /// Each layer's cross-attention K then V, `[512 * 1500]` each, channel-major and constant for
    /// the whole transcript.
    cross: Vec<Vec<f32>>,
}

impl WhisperNets<'_> {
    fn new<'a>(handle: &'a mut WhisperHandle) -> WhisperNets<'a> {
        WhisperNets { net: &mut handle.net, weights: &handle.weights, cross: Vec::new() }
    }
}

impl whisper_post::Nets for WhisperNets<'_> {
    fn encode(&mut self, mel: &[f32]) -> Result<(), String> {
        let expected = whisper::MELS as usize * whisper::MEL_FRAMES as usize;
        if mel.len() != expected {
            return Err(format!("{} mel values, not {expected}", mel.len()));
        }
        let net = self.net.at(whisper::Mode::Encode)?;
        let mut out = net.infer_raw_many(&[mel])?;
        // Output 0 is the hidden states, which only the parity script reads; the twelve cross
        // caches follow, in layer order.
        if out.len() != 1 + WHISPER_CROSS {
            return Err(format!("the encoder returned {} tensors, not {}", out.len(), 1 + WHISPER_CROSS));
        }
        self.cross = out.split_off(1);
        Ok(())
    }

    fn decode_step(&mut self, token: u32, step: usize) -> Result<Vec<f32>, String> {
        if self.cross.len() != WHISPER_CROSS {
            return Err("a decode step before the encoder ran".into());
        }
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= whisper::MAX_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                whisper::MAX_POSITIONS
            ));
        }
        // The embedding row and the learned position, both on the host and summed in f32.
        let embedded = whisper::embed_positions(self.weights.reader(), &[token], cache_len)?;

        // One key for every step, so this re-records once - leaving `Encode` - and never again.
        let net = self.net.at(whisper::Mode::DecodeStep)?;
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        // Declaration order: the token, then the twelve cross caches.
        let mut inputs: Vec<&[f32]> = Vec::with_capacity(1 + WHISPER_CROSS);
        inputs.push(&embedded);
        for held in &self.cross {
            inputs.push(held);
        }
        let out = net.infer_raw_many(&inputs)?;

        // The logits, and nothing else: the self-attention rows went into the device-side cache.
        if out.len() != 1 {
            return Err(format!("a decode step returned {} tensors, not one", out.len()));
        }
        let logits = out.into_iter().next().ok_or("no logits")?;
        if logits.len() != whisper::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), whisper::VOCAB));
        }
        Ok(logits)
    }
}

/// Bring up whisper-base from its one `.maml` and the ids read from `generation_config.json`.
///
/// Returns 0 on failure. The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a
/// length: the file is a range of the APK rather than a file of its own.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createWhisper<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
    special: JIntArray<'l>,
    languages: JIntArray<'l>,
    suppress: JIntArray<'l>,
    suppress_at_begin: JIntArray<'l>,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail: the
    // caller detached it, so a path that returns without wrapping it leaks it for the life of the
    // process.
    if fd < 0 {
        log(&format!("whisper is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    let built = build_whisper(
        &mut env,
        file,
        offset,
        length,
        &special,
        &languages,
        &suppress,
        &suppress_at_begin,
    );
    match built {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("whisper is unavailable: {e}"));
            0
        }
    }
}

/// The five scalars `createWhisper` takes in its `special` array, in order.
///
/// An array rather than five `jint` parameters because the JNI signature is already eight arguments
/// wide, and because these five arrive together out of one JSON file.
const WHISPER_SPECIAL: usize = 5;

#[allow(clippy::too_many_arguments)]
fn build_whisper<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    special: &JIntArray<'l>,
    languages: &JIntArray<'l>,
    suppress: &JIntArray<'l>,
    suppress_at_begin: &JIntArray<'l>,
) -> Result<WhisperHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::WHISPER)?;
    if weights.len() != whisper::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), whisper::TENSORS));
    }

    let mut ids = |array: &JIntArray<'l>| -> Result<Vec<u32>, String> {
        read_int_array(env, array)?
            .into_iter()
            .map(|id| u32::try_from(id).map_err(|_| format!("{id} is not a token")))
            .collect()
    };
    let special = ids(special)?;
    let [start_of_transcript, end_of_text, transcribe, no_timestamps, max_length] =
        <[u32; WHISPER_SPECIAL]>::try_from(special.as_slice())
            .map_err(|_| format!("{} special ids, not {WHISPER_SPECIAL}", special.len()))?;
    let decoding = whisper_post::Decoding {
        start_of_transcript,
        end_of_text,
        transcribe,
        no_timestamps,
        max_length: max_length as usize,
        languages: ids(languages)?,
        suppress: ids(suppress)?,
        suppress_at_begin: ids(suppress_at_begin)?,
    };
    // Checked here rather than per transcription, so a broken `generation_config.json` fails at
    // construction — when the UI can still report the recogniser as unavailable.
    decoding.check(whisper::VOCAB)?;

    // Recorded on the encoder, which is what every transcription runs first.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        whisper::Mode::Encode,
        whisper_plan,
    )?;
    Ok(WhisperHandle { net, weights, decoding })
}

/// Transcribe one log-mel window into token ids, or null on failure.
///
/// `mel` is `[80 * 3000]` row-major, from `WhisperFeatures.logMel`. `language` is a `<|xx|>` token
/// the caller resolved from a code, or **negative** to detect.
///
/// The ids come back raw, including any special or timestamp token the model emitted:
/// `WhisperTokenizer` is what skips those, and it is unchanged by this port.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createWhisper` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_transcribeWhisper<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    mel: JFloatArray<'l>,
    language: jint,
) -> jintArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createWhisper` and is still live. It is
    // `&mut` because every step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut WhisperHandle) };
    let wanted = if language < 0 { None } else { u32::try_from(language).ok() };
    let ids = match read_float_array(&mut env, &mel) {
        Ok(values) => run_whisper(handle, &values, wanted),
        Err(e) => Err(e),
    };
    match ids.and_then(|ids| new_int_array(&mut env, &ids)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("whisper failed: {e}"));
            null
        }
    }
}

fn run_whisper(
    handle: &mut WhisperHandle,
    mel: &[f32],
    language: Option<u32>,
) -> Result<Vec<i32>, String> {
    // Cloned so the config can be read while the nets borrow the handle mutably. A hundred-odd ids
    // against a transcription that reads a 70 MiB file.
    let decoding = handle.decoding.clone();
    let mut nets = WhisperNets::new(handle);
    let ids = whisper_post::transcribe(&mut nets, &decoding, mel, language)?;
    ids.into_iter()
        .map(|id| i32::try_from(id).map_err(|_| format!("token {id} does not fit an int")))
        .collect()
}

/// Free whisper's net, its open weights file and both caches.
///
/// Exactly once per non-zero handle from `createWhisper`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createWhisper`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyWhisper<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createWhisper` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut WhisperHandle) });
}

fn new_int_array(env: &mut JNIEnv, values: &[i32]) -> Result<jintArray, String> {
    let array: JIntArray = env
        .new_int_array(values.len() as jint)
        .map_err(|e| format!("new_int_array: {e}"))?;
    env.set_int_array_region(&array, 0, values)
        .map_err(|e| format!("set_int_array_region: {e}"))?;
    Ok(array.into_raw())
}

fn read_float_array(env: &mut JNIEnv, array: &JFloatArray) -> Result<Vec<f32>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0.0f32; len.max(0) as usize];
    env.get_float_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_float_array_region: {e}"))?;
    Ok(out)
}

fn read_int_array(env: &mut JNIEnv, array: &JIntArray) -> Result<Vec<i32>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0i32; len.max(0) as usize];
    env.get_int_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_int_array_region: {e}"))?;
    Ok(out)
}

fn read_short_array(env: &mut JNIEnv, array: &JShortArray) -> Result<Vec<i16>, String> {
    let len = env.get_array_length(array).map_err(|e| format!("array length: {e}"))?;
    let mut out = vec![0i16; len.max(0) as usize];
    env.get_short_array_region(array, 0, &mut out)
        .map_err(|e| format!("get_short_array_region: {e}"))?;
    Ok(out)
}

fn new_float_array(env: &mut JNIEnv, values: &[f32]) -> Result<jfloatArray, String> {
    let array: JFloatArray = env
        .new_float_array(values.len() as jint)
        .map_err(|e| format!("new_float_array: {e}"))?;
    env.set_float_array_region(&array, 0, values)
        .map_err(|e| format!("set_float_array_region: {e}"))?;
    Ok(array.into_raw())
}

fn log(message: &str) {
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(priority: i32, tag: *const u8, text: *const u8) -> i32;
    }
    const ERROR: i32 = 6;
    let tag = b"ModelRunner\0";
    let mut text: Vec<u8> = message.as_bytes().to_vec();
    text.push(0);
    // SAFETY: both pointers are to NUL-terminated buffers that outlive the call.
    unsafe {
        let _ = __android_log_write(ERROR, tag.as_ptr(), text.as_ptr());
    }
}


/// Gemma 4 E2B, as `:openassistant` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # Why the loop is Kotlin's and not this module's
///
/// litertlm owned the whole turn: template, tool calls, sampling, streaming. Replacing it with a
/// single `generate(prompt) -> String` would reproduce that opacity and put the chat template,
/// the tool protocol and the stop conditions in Rust, where none of them belong. So the boundary
/// is one token: Kotlin pushes ids, asks for the next, and decides what to do with it. That is
/// also what lets the UI stream, since it already streams by writing each chunk to Room.
///
/// # Two files
///
/// The text model and the embedding are separate `.maml`s with separate graph ids. Nothing on the
/// device reads the embedding - a step needs one row of a 262144-row table - so it stays a
/// [`Streamed`] and is gathered on the host, exactly as NLLB's tied embedding is.
struct Gemma4Handle {
    net: Reshaped<gemma4::Pass>,
    /// The text model, retained for the rotary tables which are also host-read.
    weights: Streamed,
    /// The two embedding tables.
    embed: Streamed,
    /// `scripts/ml/gemma4_tokenizer.py`'s table.
    tokenizer: Vec<u8>,
    /// Rotary angles for the sliding layers, `[MAX_CONTEXT, HEAD_DIM]`, read once.
    ///
    /// A few megabytes held for the life of the handle rather than re-read per token. At 40 ms a
    /// token a re-read would be most of the step.
    local: Vec<f32>,
    /// The same for the full-attention layers, `[MAX_CONTEXT, GLOBAL_HEAD_DIM]`.
    global: Vec<f32>,
    /// The largest tier this device's memory affords. See `gemma4::tier_for`.
    ceiling: u32,

    /// Positions the KV caches are allocated for: one of [`gemma4::CONTEXT_TIERS`].
    ///
    /// Chosen per device rather than compiled in, because 18 KB a position means the tier *is*
    /// the memory: 19 MB at 1,024 and 302 MB at 16,384. A phone with 4 GB should hold a short
    /// conversation rather than fail to start.
    context: u32,

    /// Positions already in the KV cache, which is the next token's position.
    ///
    /// Resetting a conversation sets this to zero and nothing else: attention reads only
    /// `[window_start, prefix]`, so rows past the new prefix are never looked at again and
    /// overwriting them lazily is free.
    position: u32,
}

fn gemma4_plan(offsets: &Offsets, pass: gemma4::Pass) -> Result<Plan, String> {
    gemma4::build(offsets, pass)
}
