/// `SupertonicSynthesizer`'s constructor. Returns 0 on failure, having logged why.
///
/// Six assets, in two kinds. The four `.maml` plans arrive as **file descriptors** with a byte
/// range each; the codepoint table and one voice's style file arrive as byte arrays, because they
/// are 128 KB and 25 KB and nothing is saved by streaming them.
///
/// The voice is separate from the plans and swappable through
/// [`Java_com_vayunmathur_library_ml_MlNative_setSupertonicVoice`], because it is 25 KB against
/// the plans' ~105 MB and re-uploading those to change voice would be absurd.
///
/// # Why the plans are descriptors and not arrays
///
/// A `ByteArray` path allocates the model **three times**: the Java `byte[]`, the `Vec<u8>`
/// [`JNIEnv::convert_byte_array`] hands back, and [`Weights::parse`]'s own copy of the data
/// section. At the size a bundled Supertonic comes to that is ~300 MB of transient heap for a
/// ~105 MB model, which is an out-of-memory kill on a low-RAM device rather than a slow load.
///
/// [`Streamed`] reads the header and table only — a few kilobytes — and the upload then pulls the
/// data section through a fixed-size staging buffer, so the peak is one chunk.
///
/// `fds`, `offsets` and `lengths` are parallel, in the order the four graphs are listed below:
/// duration predictor, text encoder, sampler, vocoder. An `AssetFileDescriptor` carries all three
/// because an asset is a *range of the APK* rather than a file of its own.
///
/// # Ownership
///
/// Each descriptor must be **detached** by the caller: this takes ownership and closes it, on the
/// failure paths as much as the successful one. `AssetManager.openFd` also requires the asset to be
/// stored uncompressed, which is what `noCompress += "maml"` is for.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and descriptors nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createSupertonic<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    fds: JIntArray<'l>,
    offsets: JLongArray<'l>,
    lengths: JLongArray<'l>,
    indexer: JByteArray<'l>,
    style: JByteArray<'l>,
) -> jlong {
    match build_supertonic(&mut env, &fds, &offsets, &lengths, &indexer, &style) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("supertonic is unavailable: {e}"));
            0
        }
    }
}

/// The four `.maml` graphs, in the order `createSupertonic`'s parallel arrays list them.
const SUPERTONIC_GRAPHS: [u32; 4] =
    [graph::SUPERTONIC_DP, graph::SUPERTONIC_TTL, graph::SUPERTONIC_VE, graph::SUPERTONIC_VOC];

fn build_supertonic<'l>(
    env: &mut JNIEnv<'l>,
    fds: &JIntArray<'l>,
    offsets: &JLongArray<'l>,
    lengths: &JLongArray<'l>,
    indexer: &JByteArray<'l>,
    style: &JByteArray<'l>,
) -> Result<SupertonicHandle, String> {
    let count = SUPERTONIC_GRAPHS.len();
    // The descriptors first, and adopted into owning `File`s before anything else may fail. The
    // caller detached them, so a path that returns without wrapping one leaks it for the life of
    // the process — and one of the four is a descriptor onto the APK itself. Every check below the
    // adoption loop is therefore free to fail; nothing above it is.
    let opened = env
        .get_array_length(fds)
        .map_err(|e| format!("cannot size the descriptors: {e}"))? as usize;
    let mut raw = vec![0i32; opened];
    env.get_int_array_region(fds, 0, &mut raw)
        .map_err(|e| format!("cannot read the descriptors: {e}"))?;
    let mut files = Vec::with_capacity(opened);
    let mut unopened = None;
    for &fd in &raw {
        if fd < 0 {
            // Recorded rather than returned on, so the descriptors after it are still adopted.
            unopened = unopened.or(Some(fd));
            continue;
        }
        // SAFETY: the caller detached each descriptor, so nothing else owns it, and `File` closes
        // it on drop.
        files.push(unsafe { File::from_raw_fd(fd) });
    }
    if let Some(fd) = unopened {
        return Err(format!("descriptor {fd} is not open"));
    }
    if opened != count {
        return Err(format!("{opened} descriptors for {count} graphs"));
    }

    let mut at = vec![0i64; count];
    let mut len = vec![0i64; count];
    for (what, length) in [
        ("asset offsets", env.get_array_length(offsets)),
        ("asset lengths", env.get_array_length(lengths)),
    ] {
        let length = length.map_err(|e| format!("cannot size the {what}: {e}"))?;
        if length as usize != count {
            return Err(format!("{length} {what} for {count} graphs"));
        }
    }
    env.get_long_array_region(offsets, 0, &mut at)
        .map_err(|e| format!("cannot read the asset offsets: {e}"))?;
    env.get_long_array_region(lengths, 0, &mut len)
        .map_err(|e| format!("cannot read the asset lengths: {e}"))?;

    let mut streams = Vec::with_capacity(count);
    for (i, (file, graph_id)) in files.into_iter().zip(SUPERTONIC_GRAPHS).enumerate() {
        let (at, len) = (at.get(i).copied().unwrap_or(0), len.get(i).copied().unwrap_or(0));
        let (at, len) = match (u64::try_from(at), u64::try_from(len)) {
            (Ok(at), Ok(len)) => (at, len),
            _ => return Err(format!("graph {graph_id} spans {at}+{len}")),
        };
        streams.push(Streamed::open(file, at, len, graph_id)?);
    }
    let [duration_weights, text_weights, sampler_weights, vocoder_weights] =
        <[Streamed; 4]>::try_from(streams).map_err(|_| "four graphs were opened".to_string())?;

    let indexer = env
        .convert_byte_array(indexer)
        .map_err(|e| format!("cannot read the codepoint table: {e}"))?;
    if indexer.len() != supertonic::INDEXER_ENTRIES * 2 {
        return Err(format!("a codepoint table of {} bytes", indexer.len()));
    }
    let style =
        env.convert_byte_array(style).map_err(|e| format!("cannot read the style: {e}"))?;
    let voice = supertonic::Voice::read(&style)?;
    // The sampler's timestep shifts, rotary frequencies and folded style keys are all tensors in
    // its file that no shader ever reads, so they are read here rather than uploaded.
    let conditioning = supertonic::Conditioning::read(sampler_weights.reader())?;

    let device_at = std::time::Instant::now();
    let shared = context::shared()?;
    timing!("device {:.0} ms", device_at.elapsed().as_secs_f64() * 1000.0);
    let built = std::time::Instant::now();
    let duration_net = Reshaped::streamed(
        shared.clone(),
        duration_weights.offsets(),
        &duration_weights,
        SMALLEST,
        duration_plan,
    )?;
    timing!("dp net {:.0} ms", built.elapsed().as_secs_f64() * 1000.0);
    let built = std::time::Instant::now();
    let text_net =
        Reshaped::streamed(shared.clone(), text_weights.offsets(), &text_weights, SMALLEST, text_plan)?;
    timing!("ttl net {:.0} ms", built.elapsed().as_secs_f64() * 1000.0);
    let built = std::time::Instant::now();
    let sampler_net = Reshaped::streamed(
        shared.clone(),
        sampler_weights.offsets(),
        &sampler_weights,
        (SMALLEST, SMALLEST),
        sampler_plan,
    )?;
    timing!("ve net {:.0} ms", built.elapsed().as_secs_f64() * 1000.0);
    let built = std::time::Instant::now();
    let vocoder_net =
        Reshaped::streamed(shared, vocoder_weights.offsets(), &vocoder_weights, SMALLEST, vocoder_plan)?;
    timing!("voc net {:.0} ms", built.elapsed().as_secs_f64() * 1000.0);
    let handle = SupertonicHandle {
        duration: duration_net,
        text: text_net,
        sampler: sampler_net,
        vocoder: vocoder_net,
        conditioning,
        indexer,
        voice,
        rng: SplitMix { state: seed() },
    };
    // The four `Streamed` drop here, closing their descriptors: every byte they held is either in
    // device memory or in `conditioning`.
    Ok(handle)
}

/// Point an existing handle at another voice. Returns false on failure, having logged why.
///
/// # Safety
///
/// `handle` must be `0` or a live value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_setSupertonicVoice<'l>(
    env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    style: JByteArray<'l>,
) -> jni::sys::jboolean {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `synthesizeSupertonic` — the caller guarantees the handle is live and serialises
    // this against `destroySupertonic`.
    let state = unsafe { &mut *(handle as *mut SupertonicHandle) };
    let read = env
        .convert_byte_array(&style)
        .map_err(|e| format!("cannot read the voice: {e}"))
        .and_then(|bytes| supertonic::Voice::read(&bytes));
    match read {
        Ok(voice) => {
            state.voice = voice;
            1
        }
        Err(e) => {
            log(&format!("cannot change voice: {e}"));
            0
        }
    }
}

/// Free the four networks and everything beside them.
///
/// # Safety
///
/// `handle` must be `0` or a value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`], and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroySupertonic<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this came from `createSupertonic` and has not been destroyed.
    drop(unsafe { Box::from_raw(handle as *mut SupertonicHandle) });
}

/// Synthesise `text` in `language` and return the waveform, or null on failure.
///
/// `text` must already be **NFKD**-decomposed. That is the Kotlin side's job, through
/// `java.text.Normalizer`, because the model has no precomposed accents and doing the
/// decomposition here would mean carrying Unicode tables in the APK — see
/// [`supertonic::to_ids`].
///
/// `language` is the ISO-639-1 code the model should read in, or `na` for one it does not list.
/// It is not a hint: Supertonic 3 is the multilingual model and was trained with the tag always
/// present, so a wrong or missing one produces fluent-sounding non-words rather than an error.
///
/// The samples are mono `-1..1` at 44,100 Hz. Two calls with the same text differ: flow matching
/// starts from a sampled latent, which it is meant to.
///
/// # Safety
///
/// `handle` must be `0` or a live value from
/// [`Java_com_vayunmathur_library_ml_MlNative_createSupertonic`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_synthesizeSupertonic<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    language: JString<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: as `segment` — the caller guarantees the handle is live and serialises this against
    // `destroySupertonic`.
    let state = unsafe { &mut *(handle as *mut SupertonicHandle) };
    let words: String = match env.get_string(&text) {
        Ok(found) => found.into(),
        Err(e) => {
            log(&format!("cannot read the text: {e}"));
            return null;
        }
    };
    let code: String = match env.get_string(&language) {
        Ok(found) => found.into(),
        Err(e) => {
            log(&format!("cannot read the language: {e}"));
            return null;
        }
    };
    match speak_supertonic(state, &words, &code) {
        Ok(samples) => match new_float_array(&mut env, &samples) {
            Ok(array) => array,
            Err(e) => {
                log(&e);
                null
            }
        },
        Err(e) => {
            log(&format!("synthesis failed: {e}"));
            null
        }
    }
}

/// The whole pipeline for one utterance.
fn speak_supertonic(
    state: &mut SupertonicHandle,
    text: &str,
    language: &str,
) -> Result<Vec<f32>, String> {
    let SupertonicHandle {
        duration,
        text: encoder,
        sampler,
        vocoder,
        conditioning,
        indexer,
        voice,
        rng,
    } = state;
    // `synthesise` draws the starting latent once, after the duration predictor has settled the
    // frame count, so the generator has to be reachable from a `Fn` rather than pre-drawn.
    let noise = std::cell::RefCell::new(rng);
    let mut nets = SupertonicNets {
        duration,
        text: encoder,
        sampler,
        vocoder,
        timing: Timing::default(),
    };
    let whole = std::time::Instant::now();
    let out =
        supertonic::synthesise(&mut nets, conditioning, indexer, voice, text, language, &|count| {
            noise.borrow_mut().normal(count)
        });
    let t = &nets.timing;
    timing!(
        "utterance {:.0} ms: reshape {:.0}, duration {:.0}, text {:.0}, sampler {:.0} over {} calls, vocoder {:.0}",
        whole.elapsed().as_secs_f64() * 1000.0,
        t.reshape,
        t.duration,
        t.text,
        t.sampler,
        t.sampler_calls,
        t.vocoder,
    );
    out
}

/// Copy a Java `int[]` of ARGB pixels into `into`, checking it against `width` x `height`.
fn read_pixels<'l>(
    env: &mut JNIEnv<'l>,
    pixels: &JIntArray<'l>,
    width: jint,
    height: jint,
    into: &mut Vec<i32>,
) -> Result<(), String> {
    let count = match env.get_array_length(pixels) {
        Ok(n) if n >= 0 => n as usize,
        Ok(n) => return Err(format!("a pixel array of length {n}")),
        Err(e) => return Err(format!("cannot size the pixel array: {e}")),
    };
    if count != (width as usize) * (height as usize) {
        return Err(format!("{count} pixels for a {width}x{height} bitmap"));
    }
    into.resize(count, 0);
    env.get_int_array_region(pixels, 0, into)
        .map_err(|e| format!("cannot read the pixel array: {e}"))
}

/// The mask's width, so Kotlin does not have to know either network's input size.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maskWidth<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `segment`.
    unsafe { &*(handle as *const Handle) }
        .net
        .output_size()
        .map(|(width, _)| width as jint)
        .unwrap_or(0)
}

/// The mask's height.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`].
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maskHeight<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    // SAFETY: as `segment`.
    unsafe { &*(handle as *const Handle) }
        .net
        .output_size()
        .map(|(_, height)| height as jint)
        .unwrap_or(0)
}

/// Free everything the handle owns, waiting for the GPU to go idle first.
///
/// Must be called exactly once per non-zero handle. When it is the last segmenter, the
/// shared `VkDevice` goes away with it.
///
/// # Safety
///
/// As [`Java_com_vayunmathur_library_ml_MlNative_segment`], and the handle must not be
/// used again afterwards.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroy<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from a create function and has not
    // been destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut Handle) });
}

/// `[rows, columns]` to `[columns, rows]`.
///
/// The encoder output crosses the `translate::Nets` seam as `[positions, d_model]` and this runtime
/// works in `[d_model, positions]`, so it is transposed once on the way out and once per step on
/// the way back in. Eight kilobytes for a sentence, against a trait whose documented shape would
/// otherwise be wrong.
fn transpose(values: &[f32], rows: usize, columns: usize) -> Vec<f32> {
    let mut out = vec![0.0f32; values.len()];
    for row in 0..rows {
        for column in 0..columns {
            if let (Some(&from), Some(slot)) =
                (values.get(row * columns + column), out.get_mut(column * rows + row))
            {
                *slot = from;
            }
        }
    }
    out
}
