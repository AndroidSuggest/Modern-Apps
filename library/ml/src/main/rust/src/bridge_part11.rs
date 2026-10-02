// Gemma-4 bridge free functions, split out of `bridge_part6.rs` so each
// file stays under the 500-line Rust limit: the host `combine`, the
// head/gather plan constructors, the prefill chunking helpers, and the
// `createGemma4` / `encodeGemma4` JNI entry points with their builder.
//
// Same module scope via `bridge.rs` (`include!`), so item order is
// irrelevant — e.g. `CHUNK` here is read by `prefill` in part6.

/// Host `combine`: projection + grouped-norm + scaled add over rows the
/// caller already gathered.
///
/// A thin wrapper over `gemma4::combine_cached` — the single shared
/// implementation, served from the process-constant combine cache (see
/// `nets::gemma4_part4`). The device combine plan
/// (`gemma4_gather::build_plan`) must agree bit-for-bit — the
/// `--gather-parity` gate in `run_gemma4` enforces it.
fn gather_combine_host(
    embed: &Streamed,
    hidden: &[f32],
    embedded: &[f32],
) -> Result<Vec<f32>, String> {
    crate::nets::gemma4::combine_cached(&embed.reader(), hidden, embedded)
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
