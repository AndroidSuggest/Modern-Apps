//! NLLB-200-distilled-600M: a 200-language translation encoder-decoder, in one `.maml`.
//!
//! # What it is
//!
//! NLLB distilled to 600M parameters: 12 encoder layers, 12 decoder layers, `d_model` 1024,
//! 16 heads of 64, a 4096-wide feed-forward, and a 256,206-entry vocabulary shared between the
//! input embedding and the output projection.
//!
//! The architecture is `M2M100ForConditionalGeneration` — the same forward pass SMaLL-100 (which
//! this replaced) used, with 12 decoder layers instead of 3 and 202 language tokens instead of
//! 100. The two modules were kept side by side during the port so a reviewer could diff them;
//! every difference from the old `small100.rs` is one of: the vocabulary size, the head split
//! count and sizes, the decoder layer count, and the language-token constants in
//! `post::translate`.
//!
//! # One file, two passes
//!
//! [`Mode`] selects which of them [`build`] emits. They share the file because the embedding is
//! **tied**: it is the encoder's input table, the decoder's input table and the logits kernel, and
//! two files would upload ~250 MiB of it twice. Each pass names the others' tensors with
//! [`Builder::host_tensor`], and `tests::the_passes_cover_the_file_and_every_one_of_them_builds`
//! is what keeps that from hiding a genuinely unread layer.
//!
//! # The embedding is gathered on the host
//!
//! There is no int8 `embed.comp`, and there does not need to be. M2M-100's positions are **static
//! sinusoids**, so a token's input vector is a function of one table row and one integer:
//!
//! ```text
//! x[t] = embedding[id[t]] * sqrt(1024) + sinusoid(t + 2)
//! ```
//!
//! [`embed_positions`] reads the row with [`crate::weights::Reader::int8_row`], dequantises it by
//! that row's own scale, scales and adds the position in f32, and hands the result in as an
//! ordinary fp16 plan input. The math is fairseq's `M2M100SinusoidalPositionalEmbedding` with the
//! `+ 2` offset, verified against transformers' `modeling_m2m_100.py` by model-eng.
//!
//! ## The position offset is 2, not 0
//!
//! fairseq numbers positions as `cumsum(mask) * mask + padding_idx` with `padding_idx = 1`, so the
//! first real token sits at **position 2**. An off-by-two here produces fluent, plausible, subtly
//! wrong output, and no shape check anywhere catches it. It is pinned in
//! `tests::the_first_token_sits_at_position_two` and was verified against transformers'
//! `modeling_m2m_100.py` by model-eng.
//!
//! The sinusoid is fairseq's, which is not the usual one either: the two halves are
//! `[sin(all 512), cos(all 512)]` **concatenated rather than interleaved**, and the frequency
//! spacing divides by `half_dim - 1` = 511, not 512.
//!
//! # The head is four ops, not one
//!
//! 256,206 classes at 1024 channels is ~250 MiB of int8, and `maxStorageBufferRange`'s guaranteed
//! minimum is 128 MiB — so `scripts/ml/maml_convert.py` emits the tied weight as **four**
//! tensors over disjoint class ranges, [`Mode::DecodeStep`] emits four `ConvVecInt8` ops, and
//! `post::translate` argmaxes over all four — which it already did, because it argmaxes anyway.
//!
//! 256,206 is not divisible by 4, so the splits are uneven: the first two hold 64,052 classes
//! each and the last two 64,051 (see [`split_classes`]). No range is padded — padding would add
//! dummy logits that could win the argmax.
//!
//! # The FFN is ReLU, not GELU
//!
//! `config.json` says `activation_function: relu`, so [`feed_forward`] uses [`Act::Relu`]. A GELU
//! here would still run and still produce text, which is why the activation code is pinned in
//! `tests::the_encoder_is_twelve_pre_norm_layers`.
//!
//! # No attention mask, and none needed
//!
//! The runtime has no additive mask and no causal flag. Neither is required:
//!
//! * The encoder runs over one sentence with no padding, so every key is real.
//! * The decoder decodes one token at a time, so a step is **one query against `step + 1` keys**,
//!   which is causal by construction. That is what [`Builder::attn_scores_cached`]'s support for
//!   differing query and key lengths buys.
//!
//! [`Builder::attn_scores`] applies `1 / sqrt(head_dim)` itself, and `head_dim` is 64, which is
//! exactly the model's `self.scaling`. So nothing folds a query scale, and `sqrt(d_model)` stays
//! in the host gather rather than folded into the embedding.
//!
//! # Pre-norm
//!
//! The encoder and decoder layers normalise **before** each sublayer and skip around both, and
//! each stack ends with one more layer norm. Written the other way round the net still runs and
//! still produces text.
//!
//! # Decode protocol
//!
//! NLLB puts the **source** language token on the encoder source and forces the **target**
//! language token as the decoder's first token (forced-BOS). See
//! `post::translate::translate`. Getting this backwards produces fluent output in the wrong
//! language rather than an error.

use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use crate::weights::Reader;

/// Channels throughout: `d_model`.
pub const D_MODEL: u32 = 1024;

/// Attention heads, so `head_dim` is 64 and [`Builder::attn_scores`]'s own scale is the model's.
pub const HEADS: u32 = 16;

/// The feed-forward width, `encoder_ffn_dim` and `decoder_ffn_dim`.
pub const FFN: u32 = 4096;

/// Vocabulary entries, shared by the embedding and the logits projection.
///
/// 256,000 SentencePiece pieces + 202 flores language codes + `<mask>` + 4 specials, per
/// `config.json`'s `vocab_size` and model-eng's tokenizer inventory.
pub const VOCAB: u32 = 256_206;

/// Class ranges the tied weight is emitted as. See the module docs.
pub const HEAD_SPLITS: usize = 4;

/// Classes in each of the first two splits. The last two hold one fewer each.
pub const CLASSES_PER_SPLIT: u32 = 64_052;

/// Classes in the last two splits: 256,206 = 2 x 64,052 + 2 x 64,051.
pub const CLASSES_PER_TAIL_SPLIT: u32 = 64_051;

/// Classes in split `split`: the first two get the extra row each.
pub fn split_classes(split: usize) -> u32 {
    if split < 2 {
        CLASSES_PER_SPLIT
    } else {
        CLASSES_PER_TAIL_SPLIT
    }
}

/// Encoder layers.
pub const ENCODER_LAYERS: usize = 12;

/// Decoder layers. Twelve: the distilled-600M keeps the full M2M-100 decoder, unlike SMaLL-100's
/// three.
pub const DECODER_LAYERS: usize = 12;

/// `max_position_embeddings`, and therefore the longest source this can encode.
pub const MAX_POSITIONS: u32 = 1024;

/// Decoder positions the KV cache is built to hold.
///
/// The decode plan is recorded once at this length rather than rebuilt per token, so this is
/// charged to the arena in full for every translation: `2 * DECODER_LAYERS` caches of
/// `MAX_DECODE_POSITIONS * D_MODEL` fp16, which is 6 MB at these numbers.
///
/// It matches `post::translate::MAX_TOKENS`, the cap on the greedy loop, so the loop cannot
/// outrun the cache. Raising one without the other either wastes arena or drops positions:
/// `shaders/cache_write.comp` refuses a write past the end rather than running off it.
/// [`MAX_POSITIONS`] is the *positional embedding* limit and is a different, larger number.
pub const MAX_DECODE_POSITIONS: u32 = 128;

/// fairseq's padding id, which is also the offset every position is shifted by.
pub(crate) const PADDING_IDX: u32 = 1;

/// The epsilon in every layer norm.
pub(crate) const EPSILON: f32 = 1e-5;

/// Tensors per encoder layer: two norms of two, four projections of three, two more of three.
pub(crate) const ENCODER_LAYER_TENSORS: usize = 2 + 4 * 3 + 2 + 3 + 3;

/// Tensors per decoder layer: an encoder layer plus a cross-attention norm and four projections.
pub(crate) const DECODER_LAYER_TENSORS: usize = ENCODER_LAYER_TENSORS + 2 + 4 * 3;

/// The first of the four head splits. They come first, because the embedding is read before
/// anything else and the file is written in forward order.
pub(crate) const HEAD: usize = 0;

/// The first encoder layer.
pub(crate) const ENCODER: usize = HEAD + HEAD_SPLITS * 3;

/// The encoder's trailing layer norm.
pub(crate) const ENCODER_NORM: usize = ENCODER + ENCODER_LAYERS * ENCODER_LAYER_TENSORS;

/// The first decoder layer.
pub(crate) const DECODER: usize = ENCODER_NORM + 2;

/// The decoder's trailing layer norm.
pub(crate) const DECODER_NORM: usize = DECODER + DECODER_LAYERS * DECODER_LAYER_TENSORS;

/// Tensors the `.maml` must hold, and the count `maml_convert.py` writes: 12 head + 264 encoder
/// + 2 + 432 decoder + 2.
pub const TENSORS: usize = DECODER_NORM + 2;

/// Convolutions read as int8 rather than fp16, each carrying a third tensor for its scale.
///
/// **Every one of them.** Four head splits, all six projections of each encoder layer and all ten
/// of each decoder layer. Only the layer norms and the biases stay fp16.
pub const INT8_CONVS: usize = HEAD_SPLITS + ENCODER_LAYERS * 6 + DECODER_LAYERS * 10;

/// Which forward pass [`build`] emits.
///
/// One graph and two plans, run through [`crate::vulkan::run::Net::rebuild`] rather than two
/// nets, so the ~600 MiB upload happens once.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    /// The encoder over `len` tokens: `[1024, 1, len]` in, `[1024, 1, len]` out.
    Encode {
        /// Source tokens, after [`embed_positions`].
        len: u32,
    },
    /// One decoder step: the token just produced in, its logits out.
    ///
    /// A step is **one query** against `prefix + 1` keys — the positions already decoded plus this
    /// one — which is causal by construction and needs no mask.
    ///
    /// The key count is deliberately **not** part of this. It changes every token, and a plan
    /// keyed on it is re-recorded every token: a `device_wait_idle` under the queue lock, a fresh
    /// plan and a full re-emit, per token. So the plan is built once for
    /// [`MAX_DECODE_POSITIONS`], the K and V caches live on the device across steps
    /// ([`Builder::persistent`]), and how much of them is live arrives in
    /// [`crate::vulkan::run::StepParams::prefix`] at submit time.
    DecodeStep {
        /// Source positions the cross-attention attends over.
        src_len: u32,
    },
}

/// Hands out `.maml` tensor indices in the order the layers appear.
pub(crate) struct Layers {
    pub(crate) next: usize,
}

impl Layers {
    /// A weight and the bias after it: a layer norm's gamma and beta.
    pub(crate) fn take(&mut self) -> usize {
        let index = self.next;
        self.next += 2;
        index
    }

    /// An int8 kernel, its per-output-channel scale, and the bias after that.
    pub(crate) fn take3(&mut self) -> usize {
        let index = self.next;
        self.next += 3;
        index
    }
}

/// A `1 x 1` convolution with an int8 kernel, which every projection here is.
pub(crate) fn point(b: &mut Builder, l: &mut Layers, x: Id, out: u32, act: Act) -> Id {
    b.conv_int8(x, l.take3(), out, (1, 1), (1, 1), (1, 1), (0, 0, 0, 0), 1, act)
}

/// One pre-norm attention sublayer, plus its residual.
///
/// `queries` is where the query comes from and `keys` where the key and value do. They are the
/// same tensor for self-attention and differ for the cross-attention, which is the only difference
/// between the two — and the reason `attn_scores` and `attn_apply` both take two lengths.
fn attention(b: &mut Builder, l: &mut Layers, residual: Id, queries: Id, keys: Id) -> Id {
    let q = point(b, l, queries, D_MODEL, Act::None);
    let k = point(b, l, keys, D_MODEL, Act::None);
    let v = point(b, l, keys, D_MODEL, Act::None);
    let scores = b.attn_scores(q, k, HEADS);
    let probs = b.softmax(scores);
    let mixed = b.attn_apply(probs, v, HEADS);
    let projected = point(b, l, mixed, D_MODEL, Act::None);
    b.add(residual, projected)
}

/// The pre-norm feed-forward sublayer, plus its residual. `relu`, not `gelu`: `config.json` says
/// `activation_function: relu`.
pub(crate) fn feed_forward(b: &mut Builder, l: &mut Layers, x: Id) -> Id {
    let normed = b.layer_norm(x, l.take(), EPSILON);
    let inner = point(b, l, normed, FFN, Act::Relu);
    let projected = point(b, l, inner, D_MODEL, Act::None);
    b.add(x, projected)
}

/// Build one of NLLB's two passes. See [`Mode`].
pub fn build(weights: &dyn WeightSource, mode: Mode) -> Result<Plan, String> {
    Ok(record(weights, mode)?.plan)
}

/// Record one of NLLB's two passes: the resolved plan plus the graph.
///
/// [`build`] is this plus `Op` emission; the MAML v2 emitter needs the graph
/// without the plan, after the same fusion fold and the same every-tensor
/// rule. Split out so both share the bodies verbatim. See [`Builder::record`].
/// Each pass names the other's tensors host (the multi-pass file idiom);
/// the union check lives in the v2 emitter, which holds both recordings.
pub fn record(weights: &dyn WeightSource, mode: Mode) -> Result<crate::nets::Recorded, String> {
    match mode {
        Mode::Encode { len } => encode_record(weights, len),
        Mode::DecodeStep { src_len } => super::nllb_extra2::decode_step_record(weights, src_len),
    }
}

/// The encoder over `len` already-embedded positions.
fn encode(weights: &dyn WeightSource, len: u32) -> Result<Plan, String> {
    Ok(encode_record(weights, len)?.plan)
}

/// The recording behind [`encode`].
fn encode_record(weights: &dyn WeightSource, len: u32) -> Result<crate::nets::Recorded, String> {
    if len == 0 {
        return Err("an encoder pass over no tokens".into());
    }
    if len > MAX_POSITIONS {
        return Err(format!("{len} tokens, past the {MAX_POSITIONS} positions the model has"));
    }

    let l = &mut Layers { next: ENCODER };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // The tied weight and the whole decoder belong to the other pass. The trailing encoder
    // norm is part of this range, so it runs to `DECODER` rather than to `ENCODER_NORM`.
    name_host_tensors(b, std::slice::from_ref(&(ENCODER..DECODER)));

    let x = b.input(Shape::new(D_MODEL, 1, len));
    let mut x = x;
    for _ in 0..ENCODER_LAYERS {
        let normed = b.layer_norm(x, l.take(), EPSILON);
        x = attention(b, l, x, normed, normed);
        x = feed_forward(b, l, x);
    }
    if l.next != ENCODER_NORM {
        return Err(format!("the encoder claims {} tensors, not {ENCODER_NORM}", l.next));
    }
    let out = b.layer_norm(x, l.take(), EPSILON);
    if l.next != DECODER {
        return Err(format!("the encoder norm ends at {}, not {DECODER}", l.next));
    }
    builder.record(&[out], &crate::weights::Offsets::empty())
}

/// Name every tensor **outside** `read` as one this pass does not touch.
///
/// [`Builder::finish`] refuses an unread tensor, and no one of the two passes reads the whole
/// file. Declaring the complement rather than listing it keeps the two in step: adding a layer
/// changes the ranges and nothing else.
pub(crate) fn name_host_tensors(b: &mut Builder, read: &[std::ops::Range<usize>]) {
    for index in 0..TENSORS {
        if !read.iter().any(|range| range.contains(&index)) {
            b.host_tensor(index, &dims_of(index));
        }
    }
}

/// The shape of tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a *second* statement of the table that
/// `maml_convert.collect_nllb` writes — which is the point: a converter and a runtime that
/// disagree about a shape fail here rather than on the device.
pub(crate) fn dims_of(index: usize) -> Vec<u32> {
    if index < ENCODER {
        // A head split: int8 kernel, per-class scale, synthesised zero bias. The last two splits
        // are one class short.
        let split = index / 3;
        let classes = split_classes(split);
        return match index % 3 {
            0 => vec![classes, D_MODEL, 1, 1],
            _ => vec![classes],
        };
    }
    let (base, per_layer, layers) = if index < DECODER {
        (ENCODER, ENCODER_LAYER_TENSORS, ENCODER_LAYERS)
    } else {
        (DECODER, DECODER_LAYER_TENSORS, DECODER_LAYERS)
    };
    let end = base + per_layer * layers;
    if index >= end {
        // One of the two trailing layer norms.
        return vec![D_MODEL];
    }
    layer_dims((index - base) % per_layer, per_layer)
}

/// The shape of the `within`th tensor of a layer of `per_layer` tensors.
fn layer_dims(within: usize, per_layer: usize) -> Vec<u32> {
    // A layer is a sequence of groups: `[2]` for a norm, `[out, in, 1, 1] [out] [out]` for a
    // projection. Walking them is shorter than a table and cannot disagree with `Layers`.
    let mut groups: Vec<(usize, u32, u32)> = vec![(2, 0, 0)];
    groups.extend([(3, D_MODEL, D_MODEL); 4]);
    if per_layer == DECODER_LAYER_TENSORS {
        groups.push((2, 0, 0));
        groups.extend([(3, D_MODEL, D_MODEL); 4]);
    }
    groups.push((2, 0, 0));
    groups.push((3, FFN, D_MODEL));
    groups.push((3, D_MODEL, FFN));

    let mut at = 0;
    for (size, out, inputs) in groups {
        if within < at + size {
            let offset = within - at;
            return match (size, offset) {
                // A norm's gamma and beta.
                (2, _) => vec![D_MODEL],
                // A projection's kernel, then its scale and its bias.
                (_, 0) => vec![out, inputs, 1, 1],
                _ => vec![out],
            };
        }
        at += size;
    }
    vec![D_MODEL]
}

/// Which head split holds token `id`, and its row within that split.
///
/// The first two splits hold [`CLASSES_PER_SPLIT`] classes each and the last two one fewer, so
/// this is not one division: ids below twice the full width divide evenly, and the rest divide
/// over the tail width.
pub(crate) fn split_of(id: u32) -> (usize, u32) {
    if id < CLASSES_PER_SPLIT * 2 {
        ((id / CLASSES_PER_SPLIT) as usize, id % CLASSES_PER_SPLIT)
    } else {
        let rest = id - CLASSES_PER_SPLIT * 2;
        (2 + (rest / CLASSES_PER_TAIL_SPLIT) as usize, rest % CLASSES_PER_TAIL_SPLIT)
    }
}

/// The embedded, scaled and positioned source for `ids`, in the channel-major layout the plan
/// wants.
///
/// The `[1024, 1, len]` fp16 input to [`Mode::Encode`], as f32 for the caller to upload. See the
/// module docs for why this is on the host and for the `+ 2` on the position.
///
/// `past` is how many positions precede these ids, which is 0 for the encoder and the step number
/// for the decoder — fairseq's `past_key_values_length`, and it lands in the same arithmetic.
pub fn embed_positions(
    weights: Reader<'_>,
    ids: &[u32],
    past: u32,
) -> Result<Vec<f32>, String> {
    let width = D_MODEL as usize;
    let mut out = vec![0.0f32; width * ids.len()];
    let scale = (D_MODEL as f32).sqrt();
    for (at, &id) in ids.iter().enumerate() {
        if id >= VOCAB {
            return Err(format!("token {id} is past the {VOCAB}-entry vocabulary"));
        }
        let (split, row) = split_of(id);
        let kernel = HEAD + split * 3;
        let embedding = weights.int8_row(
            kernel,
            kernel + 1,
            &[split_classes(split), D_MODEL, 1, 1],
            row,
        )?;
        let position = past + at as u32 + 1 + PADDING_IDX;
        // `make_weights(num_positions + padding_idx + 1, ...)`, so the table's last row is
        // `MAX_POSITIONS + PADDING_IDX`.
        if position > MAX_POSITIONS + PADDING_IDX {
            return Err(format!("position {position} is past the model's table"));
        }
        for (channel, value) in embedding.iter().enumerate() {
            // Channel-major: this runtime indexes `[c, h, w]`, and the export is `[w, c]`.
            let slot = out
                .get_mut(channel * ids.len() + at)
                .ok_or("an embedding row is wider than d_model")?;
            *slot = value * scale + sinusoid(position, channel);
        }
    }
    Ok(out)
}

/// fairseq's sinusoidal position, channel `channel` of position `position`.
///
/// Not the interleaved `sin, cos, sin, cos` of the original transformer paper: the halves are
/// **concatenated**, so channels `0..512` are all the sines and `512..1024` all the cosines. And
/// the frequency spacing divides `ln(10000)` by `half_dim - 1`, not by `half_dim`.
///
/// `padding_idx` is 1 and its row is zeroed upstream, which never matters here because
/// [`embed_positions`] never asks for it — the smallest position it can produce is 2.
pub(crate) fn sinusoid(position: u32, channel: usize) -> f32 {
    let half = D_MODEL as usize / 2;
    let index = channel % half;
    let spacing = (10_000.0f32).ln() / (half as f32 - 1.0);
    let angle = position as f32 * (-(index as f32) * spacing).exp();
    if channel < half {
        angle.sin()
    } else {
        angle.cos()
    }
}
