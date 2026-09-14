/// Gemma 4's audio tower, which is its own `.maml` and its own graph id.
///
/// Separate from [`Gemma4Handle`] for the reason the vision tower is: optional, separately
/// downloaded, and a failure here leaves the assistant answering without sound rather than not
/// answering.
///
/// # This one owns a front end, which the vision tower did not
///
/// The vision tower takes patches Kotlin already produced. This takes a **waveform**, and the
/// log-mel spectrogram between the two is [`crate::logmel`] - reference-verified, and until now
/// with no caller. It lives in the handle rather than being built per call because it holds the
/// Hann window, the 128-channel filter bank and the transform's twiddle tables, none of which
/// depend on the clip.
struct Gemma4AudioHandle {
    net: Reshaped<gemma4_audio::Mode>,
    /// The log-mel front end. Stateful only in its scratch buffers; one clip at a time.
    mel: crate::logmel::LogMel,
}

fn gemma4_audio_plan(offsets: &Offsets, mode: gemma4_audio::Mode) -> Result<Plan, String> {
    gemma4_audio::build(offsets, mode)
}

/// Bring up the audio tower from its `.maml`. Returns 0 on failure, having logged why.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor the caller detached and nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createGemma4Audio<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("the gemma4 audio tower is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it
    // on drop including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_gemma4_audio(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the gemma4 audio tower is unavailable: {e}"));
            0
        }
    }
}

fn build_gemma4_audio(file: File, offset: jlong, length: jlong) -> Result<Gemma4AudioHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::GEMMA4_AUDIO)?;
    if weights.len() != gemma4_audio::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), gemma4_audio::TENSORS));
    }
    // Recorded at the cap. `at` re-records per clip length, and unlike the vision tower's grid
    // there is only one axis to vary, so most conversations settle on a handful of lengths.
    // Recording at the longest means the first short clip re-records downward rather than the
    // arena having to grow.
    let longest = crate::logmel::frame_count(gemma4_audio::MAX_SAMPLES) as u32;
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        gemma4_audio::Mode::Clip { frames: longest },
        gemma4_audio_plan,
    )?;
    Ok(Gemma4AudioHandle { net, mel: crate::logmel::LogMel::new() })
}

/// Encode one clip into soft tokens: `[n, 1536]` flattened, or null.
///
/// `samples` is **16 kHz mono** in roughly `-1.0..1.0`. The front end has no gain of its own, so
/// the scale it arrives in is the scale the tower sees. The result goes straight to
/// `pushSoftGemma4`, unscaled, for the reason the vision tower's does.
///
/// # What this does to the waveform, and what it deliberately does not
///
/// Truncates to [`gemma4_audio::MAX_SAMPLES`] - thirty seconds, the reference's own cap and what
/// makes the token count bounded. It does **not** pad to a multiple of 128 samples. The reference
/// does, so a batch stacks, and then spends a validity mask through the whole tower undoing it;
/// this runtime records a plan per frame count and passes the clip at its true length. Measured
/// bit-identical against the export over 68 configurations. See `nets::gemma4_audio::prepare`.
///
/// # Safety
///
/// Called only by the JVM, with a live handle from `createGemma4Audio`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_encodeAudioGemma4<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    samples: JFloatArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createGemma4Audio` and is still live.
    // `&mut` because a new clip length re-records the net, and Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut Gemma4AudioHandle) };
    let encoded = read_float_array(&mut env, &samples)
        .and_then(|waveform| run_gemma4_audio(handle, &waveform));
    match encoded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("the gemma4 audio tower failed: {e}"));
            null
        }
    }
}

fn run_gemma4_audio(handle: &mut Gemma4AudioHandle, waveform: &[f32]) -> Result<Vec<f32>, String> {
    // The cap is the reference's, and it is what bounds the arena at 49.8 MiB. Truncating here
    // rather than refusing is deliberate: a caller who hands over a minute of audio wants the
    // first thirty seconds encoded, not an error.
    let capped = &waveform[..waveform.len().min(gemma4_audio::MAX_SAMPLES)];
    let frames = crate::logmel::frame_count(capped.len());
    let count = u32::try_from(frames).map_err(|_| format!("{frames} mel frames"))?;
    let tokens = gemma4_audio::tokens(count);
    if tokens < gemma4_audio::MIN_TOKENS {
        return Err(format!(
            "{} samples is {frames} mel frames and {tokens} soft tokens, under the {} the \
             attention band needs - about {} ms of audio",
            capped.len(),
            gemma4_audio::MIN_TOKENS,
            capped.len() * 1000 / crate::logmel::SAMPLE_RATE as usize
        ));
    }

    let mut mel = Vec::new();
    let produced = handle.mel.spectrogram(capped, &mut mel);
    if produced != frames {
        return Err(format!("the front end made {produced} frames, not {frames}"));
    }
    let input = gemma4_audio::prepare(&mel, count)?;
    let out = handle
        .net
        .at(gemma4_audio::Mode::Clip { frames: count })?
        .infer_raw_many(&[&input])?;
    let features = one_output(out)?;
    let width = gemma4_audio::OUT_DIM as usize;
    let rows = tokens as usize;
    if features.len() != rows * width {
        return Err(format!("{} values, not {}", features.len(), rows * width));
    }
    // The plan writes `[1536, 1, tokens]`; the decoder reads one soft token at a time, so this
    // hands back `[tokens, 1536]`.
    Ok(transpose(&features, width, rows))
}

/// Release the audio tower.
///
/// # Safety
///
/// Called only by the JVM, once, with a handle from `createGemma4Audio` that nothing else uses.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyGemma4Audio<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this runs once, after every other call on the handle.
    drop(unsafe { Box::from_raw(handle as *mut Gemma4AudioHandle) });
}
