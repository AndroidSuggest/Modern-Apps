/// The 512-d text embedding for `ids`, or null on failure.
///
/// `ids` must be the query's tokens **up to and including `<|endoftext|>`**, with the tokenizer's
/// padding trimmed off. CLIP pools at the end-of-text position, so the caller's trim decides which
/// position is pooled — and because the tower is causal, running `ids.len()` positions instead of
/// the padded 77 gives the identical vector for a fraction of the work.
///
/// Not L2-normalised, as `tinyclipImage` is not.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_tinyclipText<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    ids: JIntArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: as `tinyclipImage`.
    let handle = unsafe { &mut *(handle as *mut TinyclipHandle) };
    let embedded = match read_int_array(&mut env, &ids) {
        Ok(values) => run_tinyclip_text(handle, &values),
        Err(e) => Err(e),
    };
    match embedded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("tinyclip's text tower failed: {e}"));
            null
        }
    }
}

fn run_tinyclip_text(handle: &mut TinyclipHandle, ids: &[i32]) -> Result<Vec<f32>, String> {
    let tokens: Vec<u32> = ids
        .iter()
        .map(|&id| u32::try_from(id).map_err(|_| format!("{id} is not a token")))
        .collect::<Result<_, _>>()?;
    // The embedding and the learned positions, both on the host and summed in f32. See
    // `nets::tinyclip` for why neither is a shader.
    let embedded = tinyclip::embed_positions(handle.weights.reader(), &tokens)?;
    let len = u32::try_from(tokens.len()).map_err(|_| "a query longer than u32")?;
    let net = handle.net.at(tinyclip::Mode::Text { len })?;
    let out = one_output(net.infer_raw_many(&[&embedded])?)?;
    // The end-of-text position, which the caller's trim made the last one.
    tinyclip_column(&out, tokens.len() - 1, tokens.len())
}

/// Free TinyCLIP's net and its open weights file.
///
/// Exactly once per non-zero handle from `createTinyclip`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyTinyclip<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createTinyclip` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut TinyclipHandle) });
}

/// Maia3-5M, as `:games:chess` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, one plan
///
/// A plain [`Net`] rather than a [`Reshaped`]: the board is always 64 squares, so unlike
/// TinyCLIP's two towers or Whisper's growing decode there is nothing to re-record. One
/// forward pass per move, no search, no cache.
///
/// # The weights file stays open
///
/// [`maia::elo_embedding`] blends the two 128-vectors on the host — the blend weight is an
/// input, so it cannot be folded — so the [`Streamed`] is retained the way TinyCLIP's is
/// for its token table.
struct MaiaHandle {
    net: Net,
    weights: Streamed,
}

/// Bring up Maia3 from its one bundled `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the
/// file is a *range of the APK* rather than a file of its own, which is also why the asset
/// has to be stored uncompressed.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMaia<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("maia is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes
    // it on drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_maia(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("maia is unavailable: {e}"));
            0
        }
    }
}

fn build_maia(file: File, offset: jlong, length: jlong) -> Result<MaiaHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::MAIA)?;
    if weights.len() != maia::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), maia::TENSORS));
    }
    let plan = maia::build(&weights.offsets())?;
    let net = Net::new(context::shared()?, plan, &weights, RESCALE_ONLY)?;
    Ok(MaiaHandle { net, weights })
}

/// The 4352 move logits for a board, or null on failure.
///
/// `planes` is the 12 board planes as `12 * 64` floats, plane-major, square `rank * 8 + file`
/// — **already mirrored and colour-swapped if black is to move**, because the model only ever
/// sees the position from the mover's side and the move vocabulary has no black promotions
/// at all. `:games:chess` does that in `MaiaEngine`, next to the code that un-mirrors the
/// move it picks.
///
/// The logits are raw. Legal masking, temperature and sampling all need the caller's move
/// list, so they stay in Kotlin.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMaia` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_maiaLogits<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    planes: JFloatArray<'l>,
    self_elo: jint,
    oppo_elo: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createMaia` and is still live.
    // `&mut` because inference writes the net's staging buffer; Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut MaiaHandle) };
    let logits = match read_float_array(&mut env, &planes) {
        Ok(values) => run_maia(handle, &values, self_elo, oppo_elo),
        Err(e) => Err(e),
    };
    match logits.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("maia failed: {e}"));
            null
        }
    }
}

fn run_maia(
    handle: &mut MaiaHandle,
    planes: &[f32],
    self_elo: jint,
    oppo_elo: jint,
) -> Result<Vec<f32>, String> {
    let self_vector = maia::elo_embedding(handle.weights.reader(), self_elo as f32)?;
    let oppo_vector = maia::elo_embedding(handle.weights.reader(), oppo_elo as f32)?;
    let tokens = maia::tokens(planes, &self_vector, &oppo_vector)?;
    let outputs = handle.net.infer_raw_many(&[&tokens])?;
    let [scores, promo] = outputs.as_slice() else {
        return Err(format!("{} outputs, expected two", outputs.len()));
    };
    crate::post::maia::logits(scores, promo)
}

/// Free Maia3's net and close its weights file.
///
/// Exactly once per non-zero handle from `createMaia`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createMaia`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyMaia<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createMaia` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut MaiaHandle) });
}

/// The Now Playing audio fingerprinter and its log-mel front end, as `:nowplaying` holds it.
///
/// # One net, one plan, no state
///
/// A plain [`Net`] like Maia's: the input is always [`nnfp::WINDOW_FRAMES`] frames, so there is
/// nothing to re-record. The export was a *streaming* graph with six circular buffers, but
/// `nets::nnfp` unrolls that into a fixed window, which is what lets it be one recorded command
/// buffer — see that module for the argument. Two calls with the same samples give the same
/// embedding; there is no warm-up and no carried state.
///
/// # The front end lives here, not in Kotlin
///
/// [`crate::microfrontend::Frontend`] holds precomputed window, mel and twiddle tables, so
/// keeping one alive per handle means an embedding allocates nothing. It is reset before each
/// window because a window is self-contained: [`nnfp_embed`] is handed all the samples the
/// answer depends on.
///
/// # The weights file does not stay open
///
/// Unlike [`MaiaHandle`], no [`Streamed`] is retained. Maia keeps its file because
/// `elo_embedding` reads rows on the host at inference time; nothing here does, so the whole
/// file is uploaded in `Net::new` and the descriptor is closed rather than held on the APK for
/// the life of the process.
struct NnfpHandle {
    net: Net,
    frontend: crate::microfrontend::Frontend,
}

/// Bring up the fingerprinter from its one bundled `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the file
/// is a *range of the APK* rather than a file of its own, which is also why the asset has to be
/// stored uncompressed — `noCompress += "maml"` in the app's Gradle configuration.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createNnfp<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    if fd < 0 {
        log(&format!("nnfp is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes
    // it on drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_nnfp(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("nnfp is unavailable: {e}"));
            0
        }
    }
}

fn build_nnfp(file: File, offset: jlong, length: jlong) -> Result<NnfpHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::NNFP)?;
    if weights.len() != nnfp::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), nnfp::TENSORS));
    }
    let plan = nnfp::build(&weights.offsets())?;
    let net = Net::new(context::shared()?, plan, &weights, RESCALE_ONLY)?;
    let frontend = crate::microfrontend::Frontend::new(crate::microfrontend::NOW_PLAYING)?;
    // `weights` drops here, closing the descriptor: the whole file is already in the device
    // buffer and nothing reads it on the host.
    Ok(NnfpHandle { net, frontend })
}

/// The 64-value fingerprint for one window of audio, or null on failure.
///
/// `pcm` is exactly [`nnfp::WINDOW_SAMPLES`] mono 16-bit samples at 16 kHz — 415 ms, the
/// network's receptive field. i16 rather than f32 because that is what `AudioRecord` produces
/// and what the front end's fixed-point window expects; converting through float would lose
/// precision for nothing.
///
/// The embedding is **not** normalised. Whether the descriptor is compared by cosine, by L2 or
/// by a product quantiser could not be recovered from the model, so the caller's matcher
/// decides rather than this inventing a convention.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNnfp` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_nnfpEmbed<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JShortArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createNnfp` and is still live.
    // `&mut` because inference writes the net's staging buffer; Kotlin serialises calls.
    let handle = unsafe { &mut *(handle as *mut NnfpHandle) };
    let embedding = match read_short_array(&mut env, &pcm) {
        Ok(samples) => run_nnfp(handle, &samples),
        Err(e) => Err(e),
    };
    match embedding.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("nnfp failed: {e}"));
            null
        }
    }
}

fn run_nnfp(handle: &mut NnfpHandle, pcm: &[i16]) -> Result<Vec<f32>, String> {
    if pcm.len() != nnfp::WINDOW_SAMPLES {
        return Err(format!("{} samples, not {}", pcm.len(), nnfp::WINDOW_SAMPLES));
    }
    // A window is self-contained, so nothing from the last call may leak into this one.
    handle.frontend.reset();
    let mut frames = Vec::with_capacity(nnfp::WINDOW_FRAMES as usize * handle.frontend.channels());
    let produced = handle.frontend.process(pcm, &mut frames);
    if produced != nnfp::WINDOW_FRAMES as usize {
        return Err(format!("{produced} log-mel frames, not {}", nnfp::WINDOW_FRAMES));
    }
    let outputs = handle.net.infer_raw(&frames)?;
    let [embedding] = outputs.as_slice() else {
        return Err(format!("{} outputs, expected one", outputs.len()));
    };
    Ok(embedding.clone())
}

/// Free the fingerprinter's net.
///
/// Exactly once per non-zero handle from `createNnfp`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNnfp`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyNnfp<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createNnfp` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut NnfpHandle) });
}

/// Now Playing's always-on music gate. Handed to Kotlin as an opaque `jlong`.
///
/// Unlike every other handle in this file there is no device, no plan and no asset: the
/// gate is 8,200 int8 parameters embedded in the binary and it runs on the CPU. See
/// [`crate::gate`] for why. Construction cannot fail for want of hardware, only if the
/// front end rejects its configuration, so a zero return here means a genuine bug.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createMusicGate<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
) -> jlong {
    match crate::gate::MusicGate::new() {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("the music gate is unavailable: {e}"));
            0
        }
    }
}

/// Feed PCM and get one music probability per completed 10 ms hop, oldest first.
///
/// Returns an empty array rather than null when a call completes no hop or the gate is
/// still warming up — both are ordinary, and null is reserved for a real failure. The gate
/// is stateful across calls; `resetMusicGate` starts a fresh session.
///
/// # Safety
///
/// `handle` must be a live value from `createMusicGate`, and Kotlin must serialise calls.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_musicGatePush<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pcm: JShortArray<'l>,
    count: jint,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createMusicGate` and is live.
    let handle = unsafe { &mut *(handle as *mut crate::gate::MusicGate) };
    let scores = read_short_array(&mut env, &pcm).and_then(|samples| {
        let count = usize::try_from(count).unwrap_or(usize::MAX);
        if count > samples.len() {
            return Err(format!("{count} samples of a {}-sample array", samples.len()));
        }
        let mut out = Vec::new();
        handle.push(&samples[..count], &mut out);
        Ok(out)
    });
    match scores.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("the music gate failed: {e}"));
            null
        }
    }
}
