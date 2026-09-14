/// NLLB-200-distilled-600M, as `:translate` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// Its own handle type for the same reason the translation handle is: it owns a different set of
/// things, and one `destroy` guessing between them would be type confusion waiting to happen.
///
/// # One net, two passes
///
/// [`Reshaped`] keyed by [`nllb::Mode`]. The encoder and the decode step (which computes the tied
/// head itself) are two passes over **one** ~600 MiB file, so two `Net`s would upload it twice.
/// A rebuild is a `device_wait_idle` and a re-record, and a decode step's cache grows by one
/// position each time, so a translation of `n` tokens costs `n + 1` of them. That is the known
/// cost of holding the KV cache on the host, and it is what a future prefix bound in
/// [`crate::nets::Push`] would remove.
///
/// # The weights file stays open
///
/// Unlike Supertonic, whose host-side tensors are read once into [`supertonic::Conditioning`],
/// NLLB's host-side tensor is the ~250 MiB tied embedding, which cannot be pre-read. So the
/// [`Streamed`] is retained and [`nllb::embed_positions`] gathers a 1 KB row per token from it.
///
/// The tensor order is the contract in `maml_convert.collect_nllb` — see `nets::nllb`.
struct NllbHandle {
    net: Reshaped<nllb::Mode>,
    weights: Streamed,
    /// `scripts/ml/nllb_tokenizer.py`'s table, parsed per translation.
    tokenizer: Vec<u8>,
}

fn nllb_plan(offsets: &Offsets, mode: nllb::Mode) -> Result<Plan, String> {
    nllb::build(offsets, mode)
}

/// The two GPU passes, as [`translate::Nets`] wants them.
///
/// There is no host-side KV cache any more: the decode plan holds one in the arena and writes to
/// it in place. A sentence cannot leak into the next one because switching back to
/// [`nllb::Mode::Encode`] and out again re-records the plan, and the first step of a translation
/// writes row zero before reading anything.
struct NllbNets<'a> {
    net: &'a mut Reshaped<nllb::Mode>,
    weights: &'a Streamed,
    /// Source positions, so a decode step records at the length the encoder ran at.
    src_len: u32,
}

impl NllbNets<'_> {
    fn new<'a>(handle: &'a mut NllbHandle) -> NllbNets<'a> {
        NllbNets { net: &mut handle.net, weights: &handle.weights, src_len: 0 }
    }
}

impl translate::Nets for NllbNets<'_> {
    fn encode(&mut self, source: &[u32]) -> Result<Vec<f32>, String> {
        let len = u32::try_from(source.len()).map_err(|_| "a source longer than u32")?;
        let reader = self.weights.reader();
        // The embedding, `sqrt(d_model)` and the sinusoidal positions, all on the host. See
        // `nets::nllb` for why none of that is a shader.
        let embedded = nllb::embed_positions(reader, source, 0)?;
        let net = self.net.at(nllb::Mode::Encode { len })?;
        let out = one_output(net.infer_raw(&embedded)?)?;
        self.src_len = len;
        // The plan produces `[d_model, 1, len]`; the trait's contract is `[len, d_model]`. One
        // transpose here rather than a comment that disagrees with the trait.
        Ok(transpose(&out, nllb::D_MODEL as usize, source.len()))
    }

    fn decode_step(
        &mut self,
        token: u32,
        step: usize,
        encoded: &[f32],
    ) -> Result<Vec<f32>, String> {
        let width = nllb::D_MODEL as usize;
        let cache_len = u32::try_from(step).map_err(|_| "a step past u32")?;
        if cache_len >= nllb::MAX_DECODE_POSITIONS {
            return Err(format!(
                "step {step} is past the {} the KV cache holds",
                nllb::MAX_DECODE_POSITIONS
            ));
        }
        let reader = self.weights.reader();
        // `past = step`, which is what puts this token at position `step + 2`.
        let embedded = nllb::embed_positions(reader, &[token], cache_len)?;
        if !encoded.len().is_multiple_of(width) {
            return Err(format!("{} encoder values is not a whole number of {width}", encoded.len()));
        }
        let src_len = (encoded.len() / width) as u32;
        if src_len != self.src_len {
            return Err(format!("a step over {src_len} source positions after {}", self.src_len));
        }
        // Back to `[d_model, 1, src_len]`, which is what the cross-attention projections read.
        let source = transpose(encoded, encoded.len() / width, width);

        // The key no longer carries the step, so this matches after the first decode step and
        // `at` stops re-recording. The KV cache stays in the arena between submits.
        let net = self.net.at(nllb::Mode::DecodeStep { src_len })?;
        // The one thing that changes per token, and it is a memcpy rather than a re-record.
        net.set_params(StepParams { prefix: cache_len, window_start: 0 })?;
        let out = net.infer_raw_many(&[&embedded, &source])?;

        // Four logits splits and nothing else: the K and V rows are written straight into the
        // device-side cache by the plan, so there is no cache to bring back.
        if out.len() != nllb::HEAD_SPLITS {
            return Err(format!(
                "a decode step returned {} tensors, not {}",
                out.len(),
                nllb::HEAD_SPLITS
            ));
        }
        let mut logits = Vec::with_capacity(nllb::VOCAB as usize);
        for half in out.iter().take(nllb::HEAD_SPLITS) {
            logits.extend_from_slice(half);
        }
        if logits.len() != nllb::VOCAB as usize {
            return Err(format!("{} logits, not {}", logits.len(), nllb::VOCAB));
        }
        Ok(logits)
    }
}

/// Bring up NLLB from its one `.maml` and its tokenizer table. Returns 0 on failure.
///
/// # Safety
///
/// Called only by the JVM, with a valid `env`, arrays it owns, and a descriptor nothing else holds.
///
/// `createNllb` mirrors the old `createSmall100`, and `translateNllb` takes **both** a source and a
/// target token — the protocol flip vs small100. Agreed with app-eng (team `nllb-translate`).
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createNllb<'l>(
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
        log(&format!("nllb is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_nllb(&mut env, file, offset, length, &tokenizer) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("nllb is unavailable: {e}"));
            0
        }
    }
}

fn build_nllb<'l>(
    env: &mut JNIEnv<'l>,
    file: File,
    offset: jlong,
    length: jlong,
    tokenizer: &JByteArray<'l>,
) -> Result<NllbHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::NLLB)?;

    let tokenizer = env
        .convert_byte_array(tokenizer)
        .map_err(|e| format!("cannot read the tokenizer table: {e}"))?;
    // Parsed once here purely to refuse a bad table at construction rather than at the first
    // translation, when the UI has already committed to having a working engine.
    let parsed = Table::parse(&tokenizer)?;
    if parsed.len() != nllb::VOCAB as usize {
        return Err(format!("a tokenizer of {} pieces, not {}", parsed.len(), nllb::VOCAB));
    }

    // The smallest legal encoder, immediately replaced: `Net::new` needs a plan and the real shapes
    // are not known until a sentence arrives. `Net::rebuild` only ever grows the arena.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        nllb::Mode::Encode { len: SMALLEST },
        nllb_plan,
    )?;
    Ok(NllbHandle { net, weights, tokenizer })
}

/// Translate [`text`] from the language `source_token` names into the language `target_token`
/// names, or null on failure.
///
/// `text` must already be NFKC — `java.text.Normalizer.normalize(text, Form.NFKC)`. The model's
/// normaliser is `nmt_nfkc` with a precompiled charsmap, and reproducing that natively would
/// mean carrying Unicode tables the platform already has. See `post::sentencepiece`.
///
/// Unlike small100 before it, BOTH tokens are required: the source token goes on the encoder source
/// and the
/// target token forced-BOSes the decoder. Backwards it produces fluent output in the wrong
/// language rather than an error.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNllb` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_translateNllb<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    text: JString<'l>,
    source_token: jint,
    target_token: jint,
) -> jstring {
    if handle == 0 {
        return std::ptr::null_mut();
    }
    // SAFETY: the caller guarantees the handle came from `createNllb` and is still live. It is
    // `&mut` because a decode step re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut NllbHandle) };
    let translated = match env.get_string(&text) {
        Ok(text) => run_nllb(handle, &String::from(text), source_token, target_token),
        Err(e) => Err(format!("cannot read the source text: {e}")),
    };
    match translated {
        Ok(out) => match env.new_string(&out) {
            Ok(string) => string.into_raw(),
            Err(e) => {
                log(&format!("nllb cannot return its translation: {e}"));
                std::ptr::null_mut()
            }
        },
        Err(e) => {
            log(&format!("nllb failed: {e}"));
            std::ptr::null_mut()
        }
    }
}

fn run_nllb(
    handle: &mut NllbHandle,
    text: &str,
    source_token: jint,
    target_token: jint,
) -> Result<String, String> {
    let source =
        u32::try_from(source_token).map_err(|_| format!("{source_token} is not a token"))?;
    let target =
        u32::try_from(target_token).map_err(|_| format!("{target_token} is not a token"))?;
    // Cloned so the table can borrow it while the nets borrow the handle mutably.
    let tokenizer = handle.tokenizer.clone();
    let table = Table::parse(&tokenizer)?;
    let mut nets = NllbNets::new(handle);
    translate::translate(&mut nets, &table, source, target, text)
}

/// Free NLLB's net, its open weights file and its tokenizer table.
///
/// Exactly once per non-zero handle from `createNllb`. When it is the last user of the shared
/// `VkDevice`, the device goes away with it.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createNllb`, and must not be used again.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_destroyNllb<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // SAFETY: the caller guarantees this handle came from `createNllb` and has not been
    // destroyed. `Net`'s Drop waits for the device to go idle before freeing.
    drop(unsafe { Box::from_raw(handle as *mut NllbHandle) });
}

/// TinyCLIP, as `:photos` holds it. Handed to Kotlin as an opaque `jlong`.
///
/// # One net, two plans
///
/// [`Reshaped`] keyed by [`tinyclip::Mode`], as the translation handle is: the two towers share no
/// weights but they do share a 22.6 MiB file, so two `Net`s would upload it twice. Switching towers
/// is a `device_wait_idle` and a re-record — which is why an indexing run, which is `Mode::Image`
/// throughout, pays for exactly one.
///
/// # The weights file stays open
///
/// [`tinyclip::embed_positions`] gathers a 1 KB embedding row per token out of the 12.6 MiB token
/// table on the host, so the [`Streamed`] is retained for the same reason SMaLL-100's is.
struct TinyclipHandle {
    net: Reshaped<tinyclip::Mode>,
    weights: Streamed,
}

fn tinyclip_plan(offsets: &Offsets, mode: tinyclip::Mode) -> Result<Plan, String> {
    tinyclip::build(offsets, mode)
}

/// One column of a `[PROJECTION, 1, len]` output, which is the position both towers pool.
///
/// A position is a *column* in this runtime's layout, so it is strided rather than contiguous —
/// which is why the plan projects every position and this picks one. See `nets::tinyclip`.
fn tinyclip_column(out: &[f32], at: usize, len: usize) -> Result<Vec<f32>, String> {
    let width = tinyclip::PROJECTION as usize;
    if len == 0 || out.len() != width * len {
        return Err(format!("{} values is not {width} x {len}", out.len()));
    }
    (0..width)
        .map(|channel| {
            out.get(channel * len + at).copied().ok_or_else(|| format!("column {at} of {len}"))
        })
        .collect()
}

/// Bring up TinyCLIP from its one `.maml`. Returns 0 on failure.
///
/// The descriptor is an `AssetFileDescriptor`'s, so it carries an offset and a length: the file is
/// a *range of the APK* rather than a file of its own, which is also why the asset has to be stored
/// uncompressed.
///
/// # Safety
///
/// Called only by the JVM, with a descriptor nothing else holds.
#[no_mangle]
pub extern "system" fn Java_com_vayunmathur_library_ml_MlNative_createTinyclip<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    fd: jint,
    offset: jlong,
    length: jlong,
) -> jlong {
    // The descriptor first, and adopted into an owning `File` before anything else may fail, as
    // the old `createSmall100` did: the caller detached it, so a path that returns without wrapping
    // it
    // leaks it for the life of the process.
    if fd < 0 {
        log(&format!("tinyclip is unavailable: descriptor {fd} is not open"));
        return 0;
    }
    // SAFETY: the caller detached the descriptor, so nothing else owns it, and `File` closes it on
    // drop — including on every failure path below.
    let file = unsafe { File::from_raw_fd(fd) };
    match build_tinyclip(file, offset, length) {
        Ok(handle) => Box::into_raw(Box::new(handle)) as jlong,
        Err(e) => {
            log(&format!("tinyclip is unavailable: {e}"));
            0
        }
    }
}

fn build_tinyclip(file: File, offset: jlong, length: jlong) -> Result<TinyclipHandle, String> {
    let (at, len) = match (u64::try_from(offset), u64::try_from(length)) {
        (Ok(at), Ok(len)) => (at, len),
        _ => return Err(format!("the graph spans {offset}+{length}")),
    };
    let weights = Streamed::open(file, at, len, graph::TINYCLIP)?;
    if weights.len() != tinyclip::TENSORS {
        return Err(format!("a file of {} tensors, not {}", weights.len(), tinyclip::TENSORS));
    }
    // Recorded on the image tower, which is what an indexing run uses throughout. A text query
    // rebuilds once and rebuilds back on the next image.
    let net = Reshaped::streamed(
        context::shared()?,
        weights.offsets(),
        &weights,
        tinyclip::Mode::Image,
        tinyclip_plan,
    )?;
    Ok(TinyclipHandle { net, weights })
}

/// The 512-d image embedding for `pixels`, or null on failure.
///
/// `pixels` is `[3, 224, 224]` already normalised by CLIP's mean and standard deviation — the
/// resize, the centre crop and the normalisation are `ClipEmbedder.preprocess`'s, because they are
/// bitmap work the platform does better and they are what the tokenizer's counterpart would be.
///
/// The vector is **not** L2-normalised here. `ClipEmbedder.l2Normalize` does it, on both towers'
/// output and on nothing else, so the stored BLOB format is decided in one place.
///
/// # Safety
///
/// `handle` must be a non-zero value from `createTinyclip` that has not been destroyed.
#[no_mangle]
pub unsafe extern "system" fn Java_com_vayunmathur_library_ml_MlNative_tinyclipImage<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    pixels: JFloatArray<'l>,
) -> jfloatArray {
    let null = std::ptr::null_mut();
    if handle == 0 {
        return null;
    }
    // SAFETY: the caller guarantees the handle came from `createTinyclip` and is still live. It is
    // `&mut` because switching towers re-records the net, and Kotlin serialises calls on one handle.
    let handle = unsafe { &mut *(handle as *mut TinyclipHandle) };
    let embedded = match read_float_array(&mut env, &pixels) {
        Ok(values) => run_tinyclip_image(handle, &values),
        Err(e) => Err(e),
    };
    match embedded.and_then(|values| new_float_array(&mut env, &values)) {
        Ok(array) => array,
        Err(e) => {
            log(&format!("tinyclip's image tower failed: {e}"));
            null
        }
    }
}

fn run_tinyclip_image(handle: &mut TinyclipHandle, pixels: &[f32]) -> Result<Vec<f32>, String> {
    let side = tinyclip::IMAGE_SIZE as usize;
    let expected = 3 * side * side;
    if pixels.len() != expected {
        return Err(format!("{} pixel values, not {expected}", pixels.len()));
    }
    let net = handle.net.at(tinyclip::Mode::Image)?;
    let out = one_output(net.infer_raw_many(&[pixels])?)?;
    // The class token, position 0. Pooling the mean, or the last position, would produce a
    // normalised 512-d vector that is simply wrong; see `nets::tinyclip`.
    tinyclip_column(&out, 0, tinyclip::VISION_POSITIONS as usize)
}
