//! The two hardcoded forward passes, and the small compiler they are written against.
//!
//! # Why a plan and not a graph interpreter
//!
//! Each network is code: [`u2netp::build`] and [`selfie::build`] call [`Builder`] in
//! the order the ONNX nodes run. There is no operator table, no name lookup and no
//! topology in the weights file — a `.maml` is ordered tensors and nothing else.
//!
//! But the *shape* of the pass being code does not mean the offsets should be. So a
//! builder call records a [`Node`] against symbolic tensor ids, and [`Builder::finish`]
//! then does three things a hand-written pass would get wrong:
//!
//! 1. Propagates shapes, so every conv's output size is derived from its pads,
//!    stride and dilation rather than restated.
//! 2. Computes each tensor's last use and packs the arena with a free list, so U^2-Netp
//!    at 320x320 reuses memory instead of holding every intermediate at once.
//! 3. Resolves everything to `u32` element offsets in one place, which is the
//!    arithmetic the host tests check.
//!
//! The result is a flat [`Plan`] of [`Op`]s. `vulkan::run` records it into **one**
//! command buffer once, at construction, so an inference is an upload, one submit and
//! one readback — nothing here runs per frame.
//!
//! # Host-testable
//!
//! Nothing in this module or its children touches Vulkan. Builders take a
//! [`WeightSource`] rather than a [`crate::weights::Weights`], so `cargo test` builds
//! both real networks, in full, with no device and no asset.

/// A CPU implementation of every op, to check the resolved plans against. Test-only,
/// so it adds nothing to the shipped `.so`.
#[cfg(test)]
pub mod reference;
pub mod schedule;
pub mod gemma4;
pub mod gemma4_audio;
pub mod gemma4_vision;
pub mod mobilefacenet;
pub mod maia;
pub mod nllb;
pub mod nnfp;
pub mod ppocr_det;
pub mod ppocr_rec;
pub mod scrfd;
pub mod selfie;
pub mod supertonic_duration;
pub mod supertonic_sampler;
pub mod supertonic_text;
pub mod supertonic_vocoder;
pub mod tinyclip;
pub mod u2netp;
pub mod whisper;

/// A `1 x c x h x w` fp16 tensor. Batch is always 1; neither net is ever batched.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Shape {
    /// Channels.
    pub c: u32,
    /// Height.
    pub h: u32,
    /// Width.
    pub w: u32,
}

impl Shape {
    /// A shape, for readability at the call sites in the net modules.
    pub const fn new(c: u32, h: u32, w: u32) -> Shape {
        Shape { c, h, w }
    }

    /// Elements, which for fp16 is bytes / 2. Not `usize`, because every consumer is
    /// a `u32` push constant or a `u32` device offset.
    // No `is_empty`: a zero-sized tensor is a bug the builder rejects, not a state worth
    // asking about.
    #[allow(clippy::len_without_is_empty)]
    pub fn len(&self) -> u32 {
        self.c * self.h * self.w
    }
}

/// The activation a layer folds into its own store, saving a full pass over the
/// output. U^2-Netp is 112 ReLUs over up to 6.5M elements each; not fusing them would
/// roughly double its memory traffic.
///
/// Fusing is not merely an optimisation for [`Act::PRelu`]: MobileFaceNet's 34 `PRelu`
/// nodes each follow a `Conv` directly, so there is no shape of graph in which one
/// would need to stand alone.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Act {
    /// Store the accumulator as-is.
    None,
    /// `max(x, 0)`.
    Relu,
    /// `x * clamp(x/6 + 0.5, 0, 1)`, i.e. ONNX `HardSwish` at its default alpha/beta.
    HardSwish,
    /// `1 / (1 + exp(-x))`.
    Sigmoid,
    /// ONNX `PRelu`: `x < 0 ? slope[c] * x : x`, one slope per output channel.
    ///
    /// Unlike the others this carries state — the slope's position in the `.maml`
    /// tensor table, which [`Builder`] resolves to an offset like any other weight and
    /// passes down in [`Push::act_weight`]. A plain `Relu` is the same thing at slope
    /// zero, and is kept separate because it needs no memory traffic at all.
    PRelu(usize),
    /// `clamp(x, 0, 1)`.
    ///
    /// This is a **normalised** `HardSigmoid`. ONNX's is `clamp(alpha * x + beta, 0, 1)`
    /// and PP-OCRv5 uses two different alphas, but `alpha` and `beta` fold into the
    /// convolution's weight and bias — `scripts/ml/ppocr_fold.py` does it — so the
    /// runtime needs one parameterless clamp rather than an activation carrying two
    /// floats through the push block.
    Clip01,
    /// `x / (1 + exp(-x))`, i.e. ONNX `Sigmoid` multiplied by its own input.
    ///
    /// Seven uses in PP-OCRv5 recognition, where the export spells it out as
    /// `Mul(x, Sigmoid(x))`; the fold recognises that the way it recognises HardSwish.
    Swish,
    /// The **exact** GELU, `0.5 x (1 + erf(x / sqrt(2)))`.
    ///
    /// 44 uses across Supertonic's four networks, where the export spells it as an `Erf`.
    ///
    /// The tanh approximation would also have done: measured, the two forms differ by at most
    /// 4.7e-4 (at `x = 2.699`), and fp16's step there is 2.0e-3, so the difference is four
    /// times finer than the arena can represent. `erf` is used anyway for two reasons that are
    /// about agreement rather than accuracy — it is what the export computes, and
    /// `nets::erf` implements the same Abramowitz and Stegun 7.1.26 series for the host
    /// interpreter, so the reference and the shader are the same function by construction rather
    /// than by coincidence. The cost is comparable either way.
    Gelu,
}

impl Act {
    pub(crate) fn code(self) -> u32 {
        match self {
            Act::None => 0,
            Act::Relu => 1,
            Act::HardSwish => 2,
            Act::Sigmoid => 3,
            Act::PRelu(_) => 4,
            Act::Clip01 => 5,
            Act::Swish => 6,
            Act::Gelu => 8,
        }
    }
}

/// Which compute pipeline an [`Op::Dispatch`] wants.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    /// Dense, grouped or dilated convolution with a fused activation.
    Conv,
    /// Transposed convolution. One use, in the selfie net's 2x upsample to 256x256.
    ConvTranspose,
    /// Max pooling.
    MaxPool,
    /// Average pooling over an explicit window, floored and unpadded.
    ///
    /// One use: the pool that turns PP-OCRv5 recognition's `[480, 3, 80]` feature map
    /// into the `[480, 1, 40]` sequence its transformer reads, with kernel and stride
    /// both `(3, 2)`. The asymmetric window is the reason this carries `kh`/`kw` rather
    /// than one size, and the reason it is not [`Kind::GlobalAvgPool`] with a flag — that
    /// one reduces all of H and W to a single value and needs no window arithmetic.
    AvgPool,
    /// Bilinear resize, `half_pixel` coordinates.
    Resize,
    /// Nearest-neighbour resize, `asymmetric` coordinates and `floor` rounding.
    ///
    /// SCRFD's feature-pyramid upsamples are `mode=nearest`, which is a different
    /// pipeline rather than a flag on [`Kind::Resize`] because the two share no
    /// arithmetic: `src = floor(dst * in / out)` against `(dst + 0.5) * in / out - 0.5`.
    /// Running an FPN through the bilinear one blurs every lateral it adds.
    ResizeNearest,
    /// Mean over H and W, keeping C. The squeeze in a squeeze-excite block.
    GlobalAvgPool,
    /// Elementwise sum of two equal shapes.
    Add,
    /// `a * b` where `b` is `C x 1 x 1`. The excite in a squeeze-excite block.
    MulBroadcast,
    /// `a + b` where `b` is `C x 1 x 1`. A per-channel shift.
    ///
    /// The mirror of [`Kind::MulBroadcast`], and used for one thing: the four timestep
    /// conditioning layers of Supertonic's sampler, whose `Linear` from the time embedding the
    /// host evaluates because it depends on two scalars. See `shaders/add_bcast.comp`.
    AddBroadcast,
    /// Elementwise `a * b` over two equal shapes.
    Mul,
    /// `x * scale + shift`, both scalars. PP-OCRv5's "learnable affine block".
    ///
    /// One pass over the data for two multiplies. It exists because these sit *after* an
    /// activation, so unlike everything else constant in that export they cannot be
    /// folded into the preceding convolution's weight.
    ///
    /// # Why they cannot all fold forward either
    ///
    /// Pushing one into the *following* convolution looks free: `conv(a * x + t)[m]` is
    /// `a * conv(x)[m] + t * sum(W[m])`. That identity holds at an interior pixel and
    /// **fails at a border one**, because a padded convolution reads zero outside the
    /// input rather than `t`, so the constant's real contribution there is `t` times the
    /// sum of only the in-bounds taps. The correction varies per output position, so it is
    /// not a bias and there is no fold.
    ///
    /// `scripts/ml/ppocr_fold.py` therefore folds an affine only into an *unpadded*
    /// convolution, which leaves 14 in detection and 16 in recognition — all of them
    /// feeding either a padded depthwise or a squeeze-excite's two branches.
    ///
    /// Getting this wrong is invisible to every structural check: the layer table, the
    /// tensor count and the digest are all unchanged, and only the weight *values* differ.
    /// `scripts/ml/onnx_parity.py` is what caught it.
    Affine,
    /// Layer normalisation **over the channel axis**, with a per-channel affine.
    ///
    /// # Why over channels
    ///
    /// PP-OCRv5's recogniser is a CNN feeding a small transformer — `d_model` 120, 8
    /// heads, two blocks — and this runtime keeps its sequences in the layout the CNN
    /// already produces: `[d_model, 1, T]`, features in channels and the sequence along
    /// the width. Two things fall out of that and neither is an accident:
    ///
    /// * **Every linear projection is a 1x1 convolution.** All five feed-forward `Gemm`s
    ///   and all six attention projections are already expressible, so they need no new
    ///   pipeline.
    /// * **Splitting `d_model` into heads is free.** `[120, 1, T]` read as `[8, 15, T]`
    ///   is the same bytes in the same order, so there is no `Permute` and no `Reshape`
    ///   anywhere in the attention — the attention pipelines take the head count in
    ///   [`Push::group`] and index accordingly.
    ///
    /// The cost is that the reduction here is strided rather than contiguous. At
    /// `d_model` 120 that is 120 loads a stride apart per position, which is nothing
    /// against what a contiguous layout would cost in permutes.
    LayerNorm,
    /// Root-mean-square normalisation over the channel axis, with a per-channel gain.
    ///
    /// [`Kind::LayerNorm`] without the mean subtraction and without beta:
    /// `x / sqrt(mean(x^2) + eps) * gamma`. Sixteen uses, all Maia3's `norm1` / `norm2`.
    ///
    /// Separate from [`Kind::LayerNorm`] rather than a flag on it because the difference
    /// is also a difference in *weights read* — one rank-1 tensor rather than two — and
    /// [`Kind::weight_reads`] is what `vulkan::segment` uses to decide which slice of the
    /// `.maml` a pass has to have resident. A flag would make that arm depend on the push
    /// block's contents rather than its kind.
    RmsNorm,
    /// `S[h][i][j] = scale * sum_d Q[h][d][i] * K[h][d][j]`, attention's score map.
    ///
    /// Contracts over the *middle* axis of `[heads, head_dim, T]`, which is what makes
    /// the head split free — see [`Kind::LayerNorm`]. Takes the head count in
    /// [`Push::group`] and `1 / sqrt(head_dim)` in [`Push::param0_bits`].
    ///
    /// The output is `[heads, T, T]` with the key index innermost, so the row a
    /// [`Kind::Softmax`] normalises is contiguous.
    AttnScores,
    /// Softmax over the last axis, one row at a time.
    ///
    /// Kept separate from [`Kind::AttnScores`] rather than fused into it. Fusing would
    /// save writing a `heads * T * T` intermediate — 100 KiB at the recogniser's sizes,
    /// which is not a cost worth a shader that does two things — and would give up the
    /// per-score parallelism, since a fused pass has to be one invocation per *row* to
    /// see the whole distribution it is normalising.
    Softmax,
    /// [`Kind::Softmax`] with each row truncated at the diagonal: a **causal** mask.
    ///
    /// One use, TinyCLIP's text tower, whose three layers attend over all 77 tokens at once and
    /// may not read the future. Query `q`'s row is normalised over keys `0 ..= q` and the rest of
    /// it is written as zero, so [`Kind::AttnApply`] can multiply the whole row unchanged.
    ///
    /// A separate pipeline rather than a flag on [`Kind::Softmax`], because that one is shared by
    /// PP-OCRv5 recognition, both Supertonic encoders and SMaLL-100 — a push field the bound came
    /// from would break four shipping nets at once if it were ever left unset. See
    /// `shaders/softmax_causal.comp`.
    SoftmaxCausal,
    /// [`Kind::Softmax`] over only the leading `prefix + 1` entries of each row.
    ///
    /// The decode counterpart. Once a decode plan is built once at a maximum context rather than
    /// rebuilt per token, a score-map row is as wide as that maximum but only its leading span was
    /// written this step; the rest holds whatever the previous step left. Normalising the whole
    /// row would fold that stale data into the maximum and the sum.
    ///
    /// A separate pipeline for the same reason [`Kind::SoftmaxCausal`] is one, and more so: plain
    /// [`Kind::Softmax`] is shared by PP-OCRv5 recognition, both Supertonic encoders, whisper,
    /// maia and NLLB's own encoder. See `shaders/softmax_prefix.comp`.
    SoftmaxPrefix,
    /// Write one position into a KV cache at the row [`crate::vulkan::run::StepParams::prefix`]
    /// names.
    ///
    /// The only op whose *destination* is decided at submit time rather than at record time, and
    /// the reason a decode plan can be recorded once. See `shaders/cache_write.comp` and
    /// [`Builder::cache_write`].
    CacheWrite,
    /// `tanh(x / cap) * cap`, Gemma's `final_logit_softcapping`. See `shaders/softcap.comp`.
    Softcap,
    /// An [`Act`] applied on its own, for a value no convolution produced. See
    /// `shaders/activate.comp`.
    ///
    /// [`Act::PRelu`] is **not** supported: its slope is a weight tensor, and the one caller
    /// this exists for wants `Gelu`. Passing it here silently uses a zero slope, which is a
    /// plain `Relu` - so [`Builder::activate`] refuses it rather than letting that happen.
    Activate,
    /// `activate(gate) * up` over a fused `[gate | up]` projection, replacing two slices, an
    /// activation and a multiply. See `shaders/gated_activate.comp`.
    GatedActivate,
    /// Multiply by a scalar held in the weights. See `shaders/mul_scalar.comp`.
    MulScalar,
    /// Clamp to a `[min, max]` pair held in the weights. See `shaders/clamp.comp`.
    Clamp,
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// `O[h][d][i] = sum_j S[h][i][j] * V[h][d][j]`, attention's weighted sum.
    ///
    /// Writes `[d_model, 1, T]`, so the head concatenation that normally follows
    /// attention is not an op: output channel `c` belongs to head `c / head_dim` and
    /// lands where the concatenated result wants it. Head count in [`Push::group`].
    AttnApply,
    /// [`Kind::AttnScores`] plus a relative-position term, which is nine taps rather than the
    /// `[heads, T, 2T-1]` product and skew the export spells out. Table at [`Push::weight`] as
    /// `[2 * window + 1, head_dim]`, offset count in [`Push::kw`].
    ///
    /// Both of Supertonic's encoders use it: `supertonic_text` has four such layers and
    /// `supertonic_duration` two, all at `window_size` 4, so `OFFSETS` is 9 in each.
    AttnScoresRelative,
    /// [`Kind::AttnApply`] plus the value-side relative term. See
    /// [`Kind::AttnScoresRelative`].
    AttnApplyRelative,
    /// Attention scores over a **backward sliding window**, stored as a band, with the relative
    /// term and the logit cap fused in. See `shaders/attn_scores_banded.comp`.
    ///
    /// `[heads, T, band]` rather than `[heads, T, T]`: Gemma 4's audio tower attends
    /// `0 <= q - k <= 11`, so a square map computes sixty-two times what it keeps. Band width in
    /// [`Push::kh`], relative offsets in [`Push::kw`], table at [`Push::weight`] as
    /// `[heads, offsets, head_dim]`, cap in [`Push::param1_bits`].
    AttnScoresBanded,
    /// [`Kind::AttnScoresBanded`]'s value half: a band of probabilities against a
    /// `[d_model, 1, T]` sequence. See `shaders/attn_apply_banded.comp`.
    AttnApplyBanded,
    /// [`AttnScores`](Kind::AttnScores) for one query against a **position-major** K cache.
    ///
    /// A decoder step is one query, and its keys live in a cache that a position is appended to
    /// every step. `[d_model, 1, T]` makes that append `d_model` scattered two-byte copies, so a
    /// cache is `[T, 1, d_model]` instead and reads through this. See
    /// `shaders/attn_scores_cached.comp` for why the shared shaders are not extended.
    AttnScoresCached,
    /// [`AttnApply`](Kind::AttnApply) for one query against a **position-major** V cache.
    AttnApplyCached,
    /// A `1 x 1` convolution as a tiled matrix multiply, staging weights through shared memory.
    ///
    /// [`Kind::Conv`] reads each output element''s weights from global memory with no reuse, which
    /// costs nothing for a spatial kernel and everything for a wide `1 x 1` over a short
    /// sequence - measured at 15.2 GFLOP/s on a Tensor G4 against a device peak of order 1000.
    /// This stages a tile of weights once per workgroup instead. `Builder::conv` routes here
    /// automatically for an ungrouped `1 x 1`; see `conv_point.comp`.
    ///
    /// [`Push::count`] is the **tile** count for this kind, not the output element count.
    ConvPoint,
    /// [`Kind::Conv`] with int8 weights and one dequantisation scale per output channel.
    ///
    /// Only for a network where the weights dominate the download. The scale multiplies the
    /// finished accumulator rather than every tap, so the inner loop costs what fp16 costs;
    /// activations stay fp16, which is simpler than the export''s dynamic quantisation and
    /// strictly more accurate. [`Push::weight`] is a **word** offset here, not an fp16 one, and
    /// [`Push::act_weight`] holds the scale tensor — which is why [`Act::PRelu`] is refused.
    ConvInt8,
    /// [`Kind::ConvPoint`] with int8 weights: the tiled lowering of [`Kind::ConvInt8`].
    ///
    /// `Builder::emit` routes an int8 convolution here under exactly the conditions an fp16 one
    /// reaches [`Kind::ConvPoint`] under — ungrouped, `1 x 1`, stride 1, unpadded. Without it,
    /// quantising Supertonic's sampler would move 92% of its parameters onto the untiled path,
    /// which is far slower than the size saving is worth; see `conv_point_int8.comp`.
    ///
    /// [`Push::count`] is the **tile** count here, as it is for [`Kind::ConvPoint`].
    ConvPointInt8,
    /// `out[c][t] = table[id(t)][c]`, an embedding lookup.
    ///
    /// The only op here whose addresses depend on the data. Ids arrive as an ordinary fp16
    /// tensor, which is exact for a small vocabulary — fp16 holds every integer to 2048. Table at
    /// [`Push::weight`], its row count in [`Push::in_w`], and an out-of-range id clamps rather
    /// than reading past the table.
    ///
    /// # Vocabularies past 2048
    ///
    /// Above [`EMBED_LANE`] the gaps between representable fp16 integers open up, so a single
    /// id lane would silently land on a neighbouring row — Supertonic has 8,322 symbols and
    /// SMaLL-100 has 128,112. Those arrive **split across two lanes**, `id = lo + 2048 * hi`,
    /// as a `[2, 1, T]` tensor; [`Push::in_c`] carries the lane count. See [`embed_lanes`].
    /// [`Kind::ConvPointInt8`] for a single position: a matrix-vector product.
    ///
    /// The tiled kind's workgroup count is `out_c.div_ceil(16) * positions.div_ceil(16)`, so at one
    /// position 15 of every 16 tile columns are padding and 56 of every 64 invocations store
    /// nothing. A SMaLL-100 decode step is entirely single-position — 30 projections and a
    /// 128,112-class head, 128 times per translation — so that waste lands on the dominant cost.
    /// See `shaders/conv_vec_int8.comp`.
    ///
    /// Chosen automatically by [`Builder`]; no net asks for it.
    ConvVecInt8,
    /// [`Kind::ConvVecInt8`] with a four-bit kernel and a per-block scale. See
    /// `shaders/conv_vec_int4.comp`.
    ConvVecInt4,
    /// [`Kind::ConvPointInt8`] with a four-bit kernel and a per-block scale. See
    /// `shaders/conv_point_int4.comp`.
    ConvPointInt4,
    Embed,
    /// `out[i] = weights[i]`, a learned tensor copied into the arena.
    ///
    /// The only op that produces a tensor from nothing but the weights file. It exists because
    /// [`Kind::AttnScores`] contracts two *arena* tensors, and Supertonic's text encoder attends
    /// against 50 style keys that are entirely constant — `tanh(W_key . style_key + b_key)`,
    /// which the converter folds to one tensor. See `shaders/constant.comp`.
    Constant,
    /// Rotary position embedding over a `[C, 1, W]` sequence, the **half**-split convention.
    ///
    /// `out[j] = x[j] cos - x[j + half] sin` and `out[j + half] = x[j + half] cos + x[j] sin`,
    /// within each head. The angle table is the second operand rather than a weight: in this
    /// export the angle is `(position / length) * theta`, so it depends on the sequence length
    /// and the host rebuilds it per call. See `shaders/rotary.comp` for why the pairing
    /// convention matters more than it looks.
    Rotary,
}

/// [`Push::res`] / [`Push::shift`] when no addend is folded into the store.
///
/// Arena offset 0 is a live tensor — the first input is pinned there — so "none" needs a value
/// no allocation can hold. [`Builder::finish`] writes real offsets only onto the ops it fuses;
/// everything else keeps this through the manual [`Push`] default below.
pub const NO_FUSE: u32 = u32::MAX;

/// The push-constant block every shader declares.
///
/// `repr(C)` so field order is declaration order, which is what the SPIR-V offsets
/// assume. Deliberately one block shared by every pipeline: it fits inside the
/// 128 bytes the spec guarantees (asserted in [`tests`]), so there is a single
/// pipeline layout, no uniform buffers and no descriptor writes after setup.
///
/// `Default` is manual rather than derived: [`NO_FUSE`] is the opt-out for the two fused-addend
/// fields, and the derive would write 0, which is a live offset. Every `..Push::default()` site
/// outside the fusion fold therefore gets "no fusion" without naming it.
#[repr(C)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Push {
    /// Element offset of the first input in the activation arena.
    pub in0: u32,
    /// Element offset of the second input, for the binary ops.
    pub in1: u32,
    /// Element offset of the output in the arena.
    pub out: u32,
    /// Element offset of the kernel in the weights buffer.
    pub weight: u32,
    /// Element offset of the bias in the weights buffer.
    pub bias: u32,
    /// Input channels.
    pub in_c: u32,
    /// Input height.
    pub in_h: u32,
    /// Input width.
    pub in_w: u32,
    /// Output channels.
    pub out_c: u32,
    /// Output height.
    pub out_h: u32,
    /// Output width.
    pub out_w: u32,
    /// Kernel height.
    pub kh: u32,
    /// Kernel width.
    pub kw: u32,
    /// Vertical stride.
    pub stride_h: u32,
    /// Horizontal stride.
    pub stride_w: u32,
    /// Vertical dilation.
    pub dil_h: u32,
    /// Horizontal dilation.
    pub dil_w: u32,
    /// Padding above row 0. ONNX's `pads[0]`.
    pub pad_t: u32,
    /// Padding left of column 0. ONNX's `pads[1]`.
    pub pad_l: u32,
    /// Non-zero when the convolution replicates its border instead of reading zeros.
    ///
    /// ONNX spells this as a `Pad` node with `mode=edge` in front of a convolution whose own
    /// `pads` are all zero, which is how Supertonic''s vocoder keeps its length: twelve of them,
    /// one before every convolution. Zero padding there would corrupt up to twelve positions at
    /// each END of an utterance and leave the middle correct - an audible click at the start and
    /// finish of every sentence.
    pub pad_edge: u32,
    /// Convolution groups. `group == in_c == out_c` is depthwise.
    pub group: u32,
    /// [`Act::code`].
    pub act: u32,
    /// Element offset of the per-channel slope [`Act::PRelu`] reads, in the weights
    /// buffer. Zero and unread for every other activation.
    pub act_weight: u32,
    /// [`Kind::Affine`]'s multiplier, or [`Kind::AttnScores`]'s `1 / sqrt(head_dim)`,
    /// as raw bits. Unread by everything else.
    ///
    /// `f32` bits rather than an `f32` field so [`Push`] stays all-`u32` and
    /// `push_bytes` can keep treating it as a plain byte block with no padding.
    pub param0_bits: u32,
    /// [`Kind::Affine`]'s addend, or [`Kind::LayerNorm`]'s epsilon.
    pub param1_bits: u32,
    /// Output elements, so an over-dispatched workgroup can bail.
    ///
    /// Not always the element count: [`Kind::LayerNorm`] and [`Kind::Softmax`] each run
    /// one invocation over a whole reduction, so for them this is the number of those.
    pub count: u32,
    /// Non-zero when the attended key count comes from the step-params buffer, not from here.
    ///
    /// A decode step attends over one more position each token. Baking that into the plan is what
    /// made [`crate::vulkan::reshape::Reshaped`] re-record per token. When this is set, the three
    /// cached-attention kinds read `prefix + 1` from
    /// [`crate::vulkan::run::StepParams`] instead, and `in_w` / `out_w` stop being the key *count*
    /// and become only the key *stride* — the maximum the plan was built for.
    ///
    /// Zero for every net that does not decode, which is all of them but NLLB and whisper, so
    /// their recordings and their numbers are untouched.
    pub dyn_keys: u32,
    /// Key/value heads, when fewer than [`Push::group`]. Zero means as many as `group`.
    ///
    /// Grouped- and multi-query attention: several query heads share one key/value head, so a
    /// cache position is `kv_heads * head_dim` channels rather than `group * head_dim`. Gemma 4's
    /// text decoder is the extreme case, eight query heads to **one** key/value head.
    ///
    /// Zero for every net that predates this, so their caches and their pushes are unchanged.
    pub kv_heads: u32,

    /// Non-zero when this op's attention **slides**, so its first key is `window_start`.
    ///
    /// A push constant rather than a step parameter because whether a layer slides is
    /// structural: Gemma 4's layers 0-3 slide and layer 4 does not, for every step of every
    /// generation. Baking it at record time is what lets one [`crate::vulkan::run::StepParams`]
    /// serve a net that mixes both - the host computes `window_start` once and the full-attention
    /// layers ignore it.
    ///
    /// Without this the two kinds share a window, which is correct only while the whole prefix
    /// fits inside it. Past that the global layers would silently lose their long-range
    /// attention, which is the one thing they exist for, and only on conversations long enough
    /// that nobody tests them.
    pub sliding: u32,

    /// Independent rotary sub-blocks per head. 1 is ordinary 1-D RoPE.
    ///
    /// Gemma 4's vision tower uses 2: a 64-wide head is two 32-wide blocks, one rotated by the
    /// patch's row and one by its column, each with its own sixteen frequencies. Rotating the
    /// whole head as a single block would pair a row channel with a column channel, which is not
    /// a shape error and produces an image encoder that is subtly position-blind.
    pub rope_axes: u32,

    /// Arena offset of a residual addend folded into this op's store, or [`NO_FUSE`].
    ///
    /// [`Builder::finish`] removes a single-consumer `Add` after a convolution by storing
    /// `activate(acc + bias) + arena[res + index]` instead of emitting the add as its own
    /// dispatch. Same shape as the output, so the same index addresses both. Only the
    /// convolution kinds read it; every other op leaves [`NO_FUSE`].
    pub res: u32,
    /// Arena offset of a per-channel shift folded into this op's store, or [`NO_FUSE`].
    ///
    /// The `AddBroadcast` half of the same fold: Supertonic's timestep conditioning adds one
    /// value per channel, so the fused store adds `arena[shift + channel]`. `C` values, which
    /// is why this is a separate field rather than a second [`Push::in1`].
    pub shift: u32,
}

impl Default for Push {
    fn default() -> Push {
        Push {
            in0: 0,
            in1: 0,
            out: 0,
            weight: 0,
            bias: 0,
            in_c: 0,
            in_h: 0,
            in_w: 0,
            out_c: 0,
            out_h: 0,
            out_w: 0,
            kh: 0,
            kw: 0,
            stride_h: 0,
            stride_w: 0,
            dil_h: 0,
            dil_w: 0,
            pad_t: 0,
            pad_l: 0,
            pad_edge: 0,
            group: 0,
            act: 0,
            act_weight: 0,
            param0_bits: 0,
            param1_bits: 0,
            count: 0,
            dyn_keys: 0,
            kv_heads: 0,
            sliding: 0,
            rope_axes: 0,
            // The only fields whose zero value would be live: see [`NO_FUSE`].
            res: NO_FUSE,
            shift: NO_FUSE,
        }
    }
}

/// Which span of a score-map row a [`Node::Softmax`] normalises.
///
/// A dedicated pipeline each, rather than one shader branching on a push field, because
/// [`Kind::Softmax`] is shared by six shipping nets and a field left unset would break all of
/// them at once. See [`Kind::SoftmaxCausal`] and [`Kind::SoftmaxPrefix`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SoftmaxMode {
    /// The whole row.
    Full,
    /// Up to and including the diagonal; the rest is written as zero.
    Causal,
    /// The leading `prefix + 1` entries, the rest left untouched.
    Prefix,
}

/// How wide a quantised convolution's kernel is, and so how its scale is shaped.
///
/// The two are not interchangeable at the same scale layout: eight bits carry a row's dynamic
/// range with one scale per output channel, four bits do not, so [`Quant::I4`] takes a rank-2
/// scale of one per block of [`crate::weights::I4_BLOCK`] taps.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Quant {
    /// Signed 8-bit, one scale per output channel.
    I8,
    /// Signed 4-bit, one scale per block of taps.
    I4,
}

/// One step of a compiled forward pass.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Op {
    /// Run `kind` over `invocations` output elements.
    Dispatch {
        /// The pipeline to bind.
        kind: Kind,
        /// Its parameters.
        push: Push,
        /// Output elements, one per invocation.
        invocations: u32,
    },
    /// A contiguous copy inside the arena, in fp16 elements.
    ///
    /// This is how `Concat` along the channel axis is done, and it needs no shader:
    /// in NCHW a run of channels *is* contiguous, so concatenation is placing each
    /// part end to end. `vkCmdCopyBuffer` does that faster than a compute pass and
    /// is why there is no `concat.comp`.
    Copy {
        /// Source element offset.
        src: u32,
        /// Destination element offset.
        dst: u32,
        /// Elements to move.
        elems: u32,
    },
}

/// One weights-buffer byte offset an [`Op::Dispatch`] reads a tensor from.
///
/// Exists because `vulkan::segment` has to know which region of the weights file each op
/// touches, and the answer is a property of the *shaders* rather than of segmentation: three
/// of [`Push`]'s fields hold weights offsets, and which of them a shader reads — and in what
/// units — depends on the [`Kind`]. Keeping the table here means it sits beside the fields it
/// describes and beside `tests::assert_no_aliasing`, which does the same job for the arena.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct WeightRead {
    /// Byte offset into the `.maml` data section.
    pub at: u64,
    /// Which [`Push`] field it came from, for error messages.
    pub field: &'static str,
}

impl Kind {
    /// Every weights offset this kind reads, in bytes, given one op's [`Push`].
    ///
    /// Read off the shaders rather than inferred: `shaders/*.comp` name `p.weight`, `p.bias` and
    /// `p.act_weight` explicitly, and `prelu` in `common.glsl` is what makes `act_weight` a read
    /// of any kind carrying [`Act::PRelu`] rather than only of the int8 pair.
    ///
    /// Bytes, not element indices, because `weight` is a **32-bit word** index for the two int8
    /// kinds and an fp16 element index everywhere else — the one place that difference has to be
    /// resolved rather than carried.
    pub fn weight_reads(self, push: &Push) -> Vec<WeightRead> {
        let elems = |at: u32| u64::from(at) * 2;
        let words = |at: u32| u64::from(at) * 4;
        let mut reads = Vec::new();
        match self {
            // A kernel, a bias, and a per-channel slope when the activation is PRelu.
            Kind::Conv | Kind::ConvTranspose | Kind::ConvPoint => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
            }
            // As above, plus the dequantisation scale, which occupies `act_weight` and is why
            // `Builder::conv_int8` refuses `Act::PRelu`.
            Kind::ConvInt8 | Kind::ConvPointInt8 | Kind::ConvVecInt8 => {
                reads.push(WeightRead { at: words(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
                reads.push(WeightRead { at: elems(push.act_weight), field: "act_weight" });
            }
            // The same three reads: an int4 kernel is addressed through the identical 32-bit
            // word view, and its scale table is fp16 like an int8 one - only wider.
            Kind::ConvVecInt4 | Kind::ConvPointInt4 => {
                reads.push(WeightRead { at: words(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
                reads.push(WeightRead { at: elems(push.act_weight), field: "act_weight" });
            }
            // Gamma then beta, both rank-1.
            Kind::LayerNorm => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
                reads.push(WeightRead { at: elems(push.bias), field: "bias" });
            }
            // One table each: the relative position table, the literal to copy out, the
            // embedding rows. RMS norm is here too: gamma with no beta.
            Kind::AttnScoresRelative
            | Kind::AttnApplyRelative
            | Kind::AttnScoresBanded
            | Kind::Constant
            | Kind::Embed
            | Kind::RmsNorm => {
                reads.push(WeightRead { at: elems(push.weight), field: "weight" });
            }
            // Purely elementwise or arena-only. `attn_scores`, `softmax`, `attn_apply` and
            // `rotary` all take their second operand from the arena, and the cached attention
            // pair take theirs from a cache in the arena too.
            Kind::MaxPool
            | Kind::AvgPool
            | Kind::Resize
            | Kind::ResizeNearest
            | Kind::GlobalAvgPool
            | Kind::Add
            | Kind::MulBroadcast
            | Kind::AddBroadcast
            | Kind::Mul
            | Kind::Affine
            | Kind::AttnScores
            | Kind::AttnScoresCached
            | Kind::Softmax
            | Kind::SoftmaxCausal
            | Kind::SoftmaxPrefix
            | Kind::CacheWrite
            | Kind::Softcap
            | Kind::GatedActivate => {}
            Kind::Activate
            | Kind::MulScalar
            | Kind::Clamp
            | Kind::AttnApply
            | Kind::AttnApplyCached
            | Kind::AttnApplyBanded
            | Kind::Rotary => {}
        }
        if !reads.is_empty() && push.act == Act::PRelu(0).code() {
            let slope = WeightRead { at: elems(push.act_weight), field: "act_weight" };
            if !reads.contains(&slope) {
                reads.push(slope);
            }
        }
        reads
    }

    /// Every arena range this kind reads, in **fp16 elements**, given one op's [`Push`].
    ///
    /// The mirror of [`Kind::weight_reads`] for the other buffer, and the reason it is `pub` is
    /// [`super::schedule`]: deciding whether two ops need a barrier between them is exactly
    /// "does the later one read what the earlier one wrote", and that question needs both sides
    /// to be exact.
    ///
    /// # Why [`Reads::Unknown`] exists
    ///
    /// This table began as the arms of `tests::assert_no_aliasing`, which checks a *weaker*
    /// property: that an op does not read the range it is writing. A table that under-reports an
    /// operand still passes that check in every net where the operand happens not to overlap the
    /// output — but a scheduler believing the same table would drop a barrier and read a value
    /// the previous op had not finished writing, non-deterministically, on one driver.
    ///
    /// So there is no catch-all. A kind whose reads have not been checked against its shader
    /// answers [`Reads::Unknown`], which every caller must treat as "reads everything". Adding a
    /// kind is then a choice to audit it or to be conservative, rather than a default that is
    /// silently wrong.
    pub fn arena_reads(self, push: &Push) -> Reads {
        // Every input plane, as the generic shape-driven kinds read it.
        let dense = push.in_c * push.in_h * push.in_w;
        // Every output element. Not `push.count`: the reducing kinds dispatch one invocation per
        // reduction rather than per element, so their `count` understates what they touch.
        let written = push.out_c * push.out_h * push.out_w;
        let one = |at: u32, len: u32| Reads::Ranges(vec![(at, len)]);
        let two = |a: (u32, u32), b: (u32, u32)| Reads::Ranges(vec![a, b]);
        match self {
            // Both operands are the output's shape.
            Kind::Add | Kind::Mul => two((push.in0, written), (push.in1, written)),
            // Rotary reads a partner channel half a head away, so the whole plane, and the angle
            // table is `head_dim` channels of the same length.
            Kind::Rotary => two((push.in0, written), (push.in1, push.in_c * push.out_w)),
            // The gate is one value per channel, broadcast over H and W.
            Kind::MulBroadcast => two((push.in0, written), (push.in1, push.in_c)),
            // As `MulBroadcast`: one shift per channel.
            Kind::AddBroadcast => two((push.in0, written), (push.in1, push.out_c)),
            // Reads no arena at all - it is a copy out of the weights file.
            Kind::Constant => Reads::Ranges(Vec::new()),
            // Q and K are each `in_c` channels but of their own lengths, which the score map's
            // height and width carry.
            Kind::AttnScores | Kind::AttnScoresRelative | Kind::AttnScoresBanded => {
                two((push.in0, push.in_c * push.out_h), (push.in1, push.in_c * push.out_w))
            }
            // The score map is `[heads, queries, keys]`; V is a sequence of keys.
            Kind::AttnApply | Kind::AttnApplyRelative | Kind::AttnApplyBanded => {
                two((push.in0, push.group * push.out_w * push.in_w), (push.in1, dense))
            }
            // One query against a position-major cache. Q is one position of `in_c` channels; the
            // cache is `out_w` positions of `kv_heads * head_dim`, which under grouped- or
            // multi-query attention is narrower than `in_c`.
            Kind::AttnScoresCached => {
                let head_dim = push.in_c / push.group.max(1);
                let kv = if push.kv_heads == 0 { push.group } else { push.kv_heads };
                two((push.in0, push.in_c), (push.in1, push.out_w * kv * head_dim))
            }
            // One query's row of probabilities is `in_w` keys long, and the cache is `in_w`
            // positions of `kv_heads * head_dim`.
            Kind::AttnApplyCached => {
                let head_dim = push.out_c / push.group.max(1);
                let kv = if push.kv_heads == 0 { push.group } else { push.kv_heads };
                two((push.in0, push.group * push.in_w), (push.in1, push.in_w * kv * head_dim))
            }
            // One id per position per lane, so the read is `in_c * out_w` and not `dense` -
            // `in_w` here is the *table's* row count.
            Kind::Embed => one(push.in0, push.in_c * push.out_w),
            // One row in. Not `dense`: `in_h` here is the cache's capacity, not a spatial extent.
            Kind::CacheWrite => one(push.in0, push.count),
            // Convolutions, whose store may also add a folded residual and a folded
            // per-channel shift. The residual is the output's shape, so the same element
            // count the op writes; the shift is one value per output channel. `NO_FUSE`
            // reads nothing — see `Push::res` — so the ranges stay exact rather than
            // conservative, which is what `schedule` needs them to be.
            Kind::Conv
            | Kind::ConvTranspose
            | Kind::ConvInt8
            | Kind::ConvPoint
            | Kind::ConvPointInt8
            | Kind::ConvVecInt8
            | Kind::ConvPointInt4
            | Kind::ConvVecInt4 => {
                let mut ranges = vec![(push.in0, dense)];
                if push.res != NO_FUSE {
                    ranges.push((push.res, written));
                }
                if push.shift != NO_FUSE {
                    ranges.push((push.shift, push.out_c));
                }
                Reads::Ranges(ranges)
            }
            // The whole input plane, whatever the kernel touches.
            Kind::MaxPool
            | Kind::AvgPool
            | Kind::Resize
            | Kind::ResizeNearest
            | Kind::GlobalAvgPool
            | Kind::LayerNorm
            | Kind::RmsNorm
            | Kind::Softmax
            | Kind::SoftmaxCausal
            | Kind::SoftmaxPrefix
            | Kind::Affine
            | Kind::Softcap
            | Kind::Activate
            | Kind::GatedActivate
            | Kind::MulScalar
            | Kind::Clamp => one(push.in0, dense),
        }
    }
}

/// What [`Kind::arena_reads`] knows about an op's reads.
///
/// Not a bare `Vec`, so that "this kind has not been audited" is a value a caller has to handle
/// rather than an empty list it would mistake for "reads nothing". See [`Kind::arena_reads`].
///
/// No arm returns [`Reads::Unknown`] today — the match is exhaustive over [`Kind`], so a new kind
/// fails to compile until someone writes its reads, which is a stronger guarantee than a
/// conservative default would be. The variant stays for the kind that eventually cannot be
/// described statically, so that being conservative is a decision someone makes rather than one
/// they fall into.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Reads {
    /// Exactly these `(element offset, element count)` ranges, and nothing else.
    Ranges(Vec<(u32, u32)>),
    /// Unaudited. Treat as reading the whole arena.
    Unknown,
}

impl Reads {
    /// The ranges, or `None` when nothing is known and the caller must be conservative.
    pub fn ranges(&self) -> Option<&[(u32, u32)]> {
        match self {
            Reads::Ranges(ranges) => Some(ranges),
            Reads::Unknown => None,
        }
    }
}

/// Where one of a net's inputs or outputs lives, and what shape it is.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Binding {
    /// Element offset in the activation arena.
    pub at: u32,
    /// The tensor's shape, so the host does not restate it.
    pub shape: Shape,
}

/// A compiled forward pass: what to run, and how much scratch it needs.
#[derive(Debug, PartialEq, Eq)]
pub struct Plan {
    /// In order. Each depends on the results of the ones before it.
    pub ops: Vec<Op>,
    /// Elements the activation arena must hold.
    pub arena_elems: u32,
    /// Where the preprocessed inputs go, in the order they were declared.
    ///
    /// A `Vec` rather than one binding because the models this runtime is growing into
    /// are not single-input: Supertonic's sampler takes seven tensors and the SMaLL-100
    /// decoder four. Every vision net declares exactly one.
    pub inputs: Vec<Binding>,
    /// The tensors that survive between submits, in declaration order.
    ///
    /// A KV cache is the only kind so far. Exposed because a cache is worth **saving**: the
    /// system block and tool declarations are the same 1,100 positions on every device and every
    /// launch, so computing them once and shipping the result beats every device recomputing
    /// them forever. See `Net::export_pinned` and `Net::import_pinned`.
    pub pinned: Vec<Binding>,

    /// Where the results come back from, in the order [`Builder::finish`] was given.
    ///
    /// SCRFD has **nine** — score, box and keypoint maps at each of three strides —
    /// which is the reason this is a list.
    pub outputs: Vec<Binding>,
}

impl Plan {
    /// The only input, for the nets that have exactly one.
    pub fn input(&self) -> Result<Binding, String> {
        match self.inputs.as_slice() {
            [only] => Ok(*only),
            other => Err(format!("this net has {} inputs, not one", other.len())),
        }
    }

    /// The only output, for the nets that have exactly one.
    pub fn output(&self) -> Result<Binding, String> {
        match self.outputs.as_slice() {
            [only] => Ok(*only),
            other => Err(format!("this net has {} outputs, not one", other.len())),
        }
    }
}

/// Where a net's weights come from.
///
/// An indirection purely so the net modules are host-testable: the real
/// implementation is [`crate::weights::Weights`], and [`tests::Shapes`] is a stub that
/// only checks the shapes it is asked for. That lets `cargo test` build both networks
/// in full with no `.maml` on disk.
pub trait WeightSource {
    /// The fp16 element offset of tensor `index`, which must have shape `dims`.
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String>;
    /// The **32-bit word** offset of tensor `index`, for an int8 tensor.
    ///
    /// Int8 weights are read through a `uint` view of the same buffer, four bytes at a time,
    /// so their offsets are word indices rather than fp16 element indices. See
    /// [`crate::weights::Tensor::word_offset`].
    fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String>;

    /// How many tensors there are, so a builder can insist it consumed all of them.
    fn count(&self) -> usize;
}

impl WeightSource for crate::weights::Offsets {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        Ok(crate::weights::Offsets::shaped(self, index, dims)?.elem_offset())
    }
    fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let found = crate::weights::Offsets::shaped(self, index, dims)?;
        if !found.dtype.is_quantised() {
            return Err(format!("tensor {index} is fp16, but the pass wants a quantised kernel"));
        }
        Ok(found.word_offset())
    }

    fn count(&self) -> usize {
        self.len()
    }
}

/// Delegated to [`crate::weights::Offsets`], which is the same table without the blob, so a plan
/// built from a whole file and one rebuilt from a retained table cannot resolve differently.
impl WeightSource for crate::weights::Weights {
    fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        Ok(crate::weights::Weights::shaped(self, index, dims)?.elem_offset())
    }
    fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
        let found = crate::weights::Weights::shaped(self, index, dims)?;
        if !found.dtype.is_quantised() {
            return Err(format!("tensor {index} is fp16, but the pass wants a quantised kernel"));
        }
        Ok(found.word_offset())
    }

    fn count(&self) -> usize {
        self.len()
    }
}

/// A tensor in the graph being built. Copy, so it can be passed and reused freely.
///
/// The inner index is `pub(crate)`: the graph-section emitter in `weights.rs` maps ids
/// to computed positions, and the section loader maps them back. Both are in other
/// modules; external callers only pass ids through.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Id(pub(crate) usize);

/// A recorded forward pass: the resolved plan plus the graph it came from.
///
/// [`Builder::record`] returns this instead of a bare [`Plan`] so the graph-section
/// emitter can serialise the nodes — with weight file indices recovered through the
/// read flags, shapes, and bindings — without re-deriving anything. The plan is what
/// runs; the rest is what the converter needs to reproduce it.
#[derive(Debug)]
pub(crate) struct Recorded {
    /// The resolved plan, as `finish` has always returned.
    pub plan: Plan,
    /// The fused nodes, in execution order.
    pub nodes: Vec<Node>,
    /// Shape per tensor id.
    pub shapes: Vec<Shape>,
    /// Input ids, in declaration order.
    pub inputs: Vec<Id>,
    /// Pinned ids (inputs, outputs, persistent).
    pub pinned: Vec<Id>,
    /// Per-file-tensor read flags, so the emitter can name host tensors.
    pub read: Vec<bool>,
}

/// An unresolved step, against [`Id`]s rather than offsets.
///
/// `pub(crate)` rather than private: the graph-section emitter in `weights.rs` walks
/// these to serialise the forward pass, and the section loader replays them through
/// the `*_raw` builders. Both are in other modules; the variants stay non-exhaustive
/// to them only by convention (see `emit_section`).
#[derive(Clone, Debug)]
pub(crate) enum Node {
    Conv {
        input: Id,
        out: Id,
        weight: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pad: (u32, u32),
        group: u32,
        act: Act,
        /// Resolved offset of [`Act::PRelu`]'s slope, zero otherwise.
        act_weight: u32,
        transpose: bool,
        /// Replicate the border instead of reading zeros. See [`Push::pad_edge`].
        pad_edge: bool,
        /// A residual addend folded into the store. See [`Push::res`].
        res: Option<Id>,
        /// A per-channel shift folded into the store. See [`Push::shift`].
        shift: Option<Id>,
    },
    MaxPool {
        input: Id,
        out: Id,
        kernel: (u32, u32),
        stride: (u32, u32),
    },
    AvgPool {
        input: Id,
        out: Id,
        kernel: (u32, u32),
        stride: (u32, u32),
    },
    Resize {
        input: Id,
        out: Id,
        nearest: bool,
    },
    GlobalAvgPool {
        input: Id,
        out: Id,
    },
    Binary {
        kind: Kind,
        a: Id,
        b: Id,
        out: Id,
    },
    Concat {
        parts: Vec<Id>,
        out: Id,
    },
    Affine {
        input: Id,
        out: Id,
        scale: f32,
        shift: f32,
    },
    LayerNorm {
        input: Id,
        out: Id,
        gamma: u32,
        beta: u32,
        epsilon: f32,
    },
    RmsNorm {
        input: Id,
        out: Id,
        gamma: u32,
        epsilon: f32,
        /// Contiguous runs of channels normalised independently. One for a whole-axis norm.
        groups: u32,
    },
    AttnScores {
        q: Id,
        k: Id,
        out: Id,
        heads: u32,
        /// Heads supplying the keys. Equal to `heads` for ordinary multi-head attention.
        kv_heads: u32,
        scale: f32,
    },
    /// One query against a position-major K cache. See [`Kind::AttnScoresCached`].
    AttnScoresCached {
        q: Id,
        cache: Id,
        out: Id,
        heads: u32,
        /// Heads supplying the keys. Equal to `heads` for ordinary multi-head attention.
        kv_heads: u32,
        scale: f32,
        /// Take the key range from the step rather than the cache's shape. See [`Push::dyn_keys`].
        dynamic: bool,
        /// Whether this layer's window applies. See [`Push::sliding`].
        sliding: bool,
    },
    /// One query against a position-major V cache. See [`Kind::AttnApplyCached`].
    AttnApplyCached {
        probs: Id,
        cache: Id,
        out: Id,
        heads: u32,
        /// Heads supplying the values. Equal to `heads` for ordinary multi-head attention.
        kv_heads: u32,
        /// Take the key range from the step rather than the cache's shape. See [`Push::dyn_keys`].
        dynamic: bool,
        /// Whether this layer's window applies. See [`Push::sliding`].
        sliding: bool,
    },
    Softmax {
        input: Id,
        out: Id,
        /// Which of the three softmax shaders normalises the row.
        mode: SoftmaxMode,
        /// Whether this layer's window applies. See [`Push::sliding`].
        sliding: bool,
        /// Keys a causal row may look back over, or 0 for the whole prefix.
        window: u32,
    },
    /// Append `row` to `cache` at the step's prefix. See [`Kind::CacheWrite`].
    CacheWrite {
        row: Id,
        cache: Id,
    },
    /// `tanh(x / cap) * cap`. See [`Kind::Softcap`].
    Softcap {
        input: Id,
        out: Id,
        cap: f32,
    },
    /// An activation on its own. See [`Kind::Activate`].
    Activate {
        input: Id,
        out: Id,
        act: Act,
    },
    /// `activate(gate) * up` over a fused projection. See [`Kind::GatedActivate`].
    GatedActivate {
        input: Id,
        out: Id,
        act: Act,
    },
    /// Multiply by a scalar held in the weights. See [`Kind::MulScalar`].
    MulScalar {
        input: Id,
        out: Id,
        scale: u32,
    },
    /// Clamp to a range held in the weights. See [`Kind::Clamp`].
    Clamp {
        input: Id,
        out: Id,
        bounds: u32,
    },
    /// Concatenation along the **width** axis, one strided run per channel row. See
    /// [`Builder::concat_positions`].
    ConcatPositions {
        parts: Vec<Id>,
        out: Id,
    },
    Constant {
        out: Id,
        weight: u32,
    },
    Rotary {
        input: Id,
        angles: Id,
        out: Id,
        heads: u32,
        /// Independent rotary blocks per head. See [`Push::rope_axes`].
        axes: u32,
    },
    Embed {
        ids: Id,
        out: Id,
        table: u32,
        rows: u32,
    },
    SliceChannels {
        input: Id,
        out: Id,
        start: u32,
    },
    ConvInt8 {
        input: Id,
        out: Id,
        weight: u32,
        scale: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pad: (u32, u32),
        group: u32,
        act: Act,
        /// Whether the kernel is eight bits or four. See [`Quant`].
        quant: Quant,
        /// A residual addend folded into the store. See [`Push::res`].
        res: Option<Id>,
        /// A per-channel shift folded into the store. See [`Push::shift`].
        shift: Option<Id>,
    },
    AttnApply {
        probs: Id,
        v: Id,
        out: Id,
        heads: u32,
        /// Heads supplying the values. Equal to `heads` for ordinary multi-head attention.
        kv_heads: u32,
    },
    AttnScoresRelative {
        q: Id,
        k: Id,
        out: Id,
        heads: u32,
        scale: f32,
        table: u32,
        offsets: u32,
    },
    AttnApplyRelative {
        probs: Id,
        v: Id,
        out: Id,
        heads: u32,
        table: u32,
        offsets: u32,
    },
    /// See [`Kind::AttnScoresBanded`].
    AttnScoresBanded {
        q: Id,
        k: Id,
        out: Id,
        heads: u32,
        band: u32,
        table: u32,
        offsets: u32,
        scale: f32,
        cap: f32,
    },
    /// See [`Kind::AttnApplyBanded`].
    AttnApplyBanded {
        probs: Id,
        v: Id,
        out: Id,
        heads: u32,
        band: u32,
    },
}

/// Records a forward pass, then packs and resolves it.
pub struct Builder<'a> {
    weights: &'a dyn WeightSource,
    shapes: Vec<Shape>,
    nodes: Vec<Node>,
    /// Tensors that must keep a stable offset for the whole pass: the inputs and the
    /// outputs. Everything else is free to be reused once its last reader has run.
    pinned: Vec<Id>,
    error: Option<String>,
    inputs: Vec<Id>,
    /// One flag per tensor in the file, set when the pass reads it. See
    /// [`Builder::finish`], which insists every one was.
    read: Vec<bool>,
    /// Whether convolutions replicate their border rather than reading zeros.
    pad_edge: bool,
}

/// Arena allocations are aligned to this many fp16 elements, i.e. 16 bytes — the same
/// boundary `.maml` aligns its tensors to.
const ALIGN_ELEMS: u32 = 8;

/// `TILE` in `shaders/conv_point.comp` and `shaders/conv_point_int8.comp`.
///
/// Both tiled shaders are dispatched one workgroup per tile, so [`Builder::emit`] has to know
/// this to compute [`Push::count`]. Shared by the two so the fp16 and int8 lowerings cannot
/// drift apart.
const CONV_POINT_TILE: u32 = 16;

/// `ROWS` in `shaders/conv_vec_int8.comp`: output channels per workgroup.
///
/// That shader is dispatched one workgroup per group of this many channels, so [`Builder::emit`]
/// has to know it to compute [`Push::count`], exactly as it does for [`CONV_POINT_TILE`].
/// **Must equal `ROWS` in `conv_vec_int8.comp`.** The two are separate declarations in
/// separate languages and nothing checks them against each other; a mismatch leaves most
/// output channels never dispatched, which parity catches as zeros. Held by
/// `the_gemv_row_count_matches_both_shaders`.
const CONV_VEC_ROWS: u32 = 2;

/// `ROWS` in `shaders/conv_vec_int4.comp`: output channels per workgroup.
///
/// The int4 gemv runs eight rows per workgroup for the stream reason that shader's header
/// gives, while the int8 gemv stays at [`CONV_VEC_ROWS`]. Separate constants because they
/// are separate decisions now: the two shaders diverged, and one shared name would let an
/// edit to either silently dispatch the other wrong. [`Builder::emit`] uses this for the
/// int4 vector kinds; `the_int4_gemv_row_count_matches_its_shader` holds it against the shader.
const CONV_VEC_INT4_ROWS: u32 = 8;

#[cfg(test)]
fn gemv_rows_of(shader: &str) -> u32 {
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("shaders").join(shader);
    let source = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
    let line = source
        .lines()
        .find(|line| line.trim_start().starts_with("#define ROWS"))
        .unwrap_or_else(|| panic!("{shader} declares no ROWS"));
    line.split_whitespace()
        .nth(2)
        .and_then(|word| word.trim_end_matches('u').parse().ok())
        .unwrap_or_else(|| panic!("{shader} has an unreadable ROWS: {line}"))
}

/// `erf`, to about 1.5e-7 — Abramowitz and Stegun 7.1.26.
///
/// Rust has no `erf`, and [`Act::Gelu`] is the exact form rather than the tanh approximation, so
/// approximating the *activation* would be a different function. This approximates `erf` itself
/// instead, well below fp16's resolution.
///
/// Test-only now. It began in `post::duration` for VITS's separable stacks and was moved here so
/// that deleting Piper would not take it with it; with Piper gone its only remaining caller is
/// `nets::reference`, which is `#[cfg(test)]`. It stays beside [`Act::Gelu`] rather than moving
/// into that module because the two have to agree with `activate` in `common.glsl`, which is where
/// the *shipped* GELU is computed — the series here and the one there are the same coefficients,
/// and keeping them one scroll apart is what makes that checkable.
#[cfg(test)]
pub(crate) fn erf(x: f32) -> f32 {
    const A: [f32; 5] = [0.254_829_6, -0.284_496_74, 1.421_413_7, -1.453_152, 1.061_405_4];
    const P: f32 = 0.327_591_1;
    let sign = if x < 0.0 { -1.0 } else { 1.0 };
    let x = x.abs();
    let t = 1.0 / (1.0 + P * x);
    let mut poly = 0.0;
    for coefficient in A.iter().rev() {
        poly = (poly + coefficient) * t;
    }
    sign * (1.0 - poly * (-x * x).exp())
}

/// The largest integer fp16 holds exactly, and so the width of one embedding id lane.
///
/// Every integer below this has an exact fp16 representation; at 2049 the gaps open to 2 and
/// keep doubling. A table with more rows than this takes its ids as two lanes — see
/// [`Kind::Embed`] and [`embed_lanes`].
pub const EMBED_LANE: u32 = 2048;

/// Ids for [`Builder::embed`] over a table of more than [`EMBED_LANE`] rows, laid out as the
/// `[2, 1, T]` tensor it wants: all the low lanes, then all the high ones.
pub fn embed_lanes(ids: &[u32]) -> Vec<f32> {
    let mut out = Vec::with_capacity(ids.len() * 2);
    out.extend(ids.iter().map(|&id| (id % EMBED_LANE) as f32));
    out.extend(ids.iter().map(|&id| (id / EMBED_LANE) as f32));
    out
}

impl<'a> Builder<'a> {
    /// Start recording a pass. Declare its inputs with [`Builder::input`].
    pub fn new(weights: &'a dyn WeightSource) -> Builder<'a> {
        Builder {
            read: vec![false; weights.count()],
            weights,
            shapes: Vec::new(),
            nodes: Vec::new(),
            pinned: Vec::new(),
            error: None,
            inputs: Vec::new(),
            pad_edge: false,
        }
    }

    /// Make every convolution from here on replicate its border instead of reading zeros.
    ///
    /// A builder-level mode rather than an argument on [`Builder::conv`], because a network
    /// either pads this way throughout or not at all: Supertonic''s vocoder puts an ONNX `Pad`
    /// with `mode=edge` in front of all twelve of its convolutions, and threading a flag through
    /// every call site would be noise at each one. See [`Push::pad_edge`] for what goes wrong if
    /// this is missed.
    pub fn edge_padding(&mut self) {
        self.pad_edge = true;
    }

    /// Declare an input of `shape`.
    ///
    /// Callable more than once, in which case [`Plan::inputs`] lists them in this
    /// order and the host must upload them in the same one.
    pub fn input(&mut self, shape: Shape) -> Id {
        let id = self.tensor(shape);
        self.inputs.push(id);
        self.pinned.push(id);
        id
    }

    fn tensor(&mut self, shape: Shape) -> Id {
        self.shapes.push(shape);
        Id(self.shapes.len() - 1)
    }

    /// The first error recorded, so the net modules can chain calls without a `?` on
    /// each of 119 layers and still fail loudly.
    fn fail(&mut self, message: String) {
        if self.error.is_none() {
            self.error = Some(message);
        }
    }

    fn shape_of(&self, id: Id) -> Shape {
        // Ids only come from `tensor`, so this cannot be out of range; a zero shape
        // is returned rather than panicking if a future refactor breaks that, because
        // `finish` will reject the plan anyway.
        self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
    }

    /// The shape of `id`, for the net modules that need a channel count to size the
    /// next layer — a squeeze-excite's expand stage, for instance.
    pub fn shape(&self, id: Id) -> Shape {
        self.shape_of(id)
    }

    /// A convolution, with ONNX's semantics: weights `[m, in_c/group, kh, kw]`, pads
    /// `[top, left, bottom, right]`, output size floor-divided.
    ///
    /// `weight_index` and `bias_index` are positions in the `.maml` tensor table, so a
    /// net module reads as the ordered list of layers that it is.
    ///
    /// [`Builder::conv_raw`] is the same convolution with resolved offsets rather than
    /// table indices, for lowering a version-2 graph section. Hand-written passes use
    /// this; the section loader uses that; both push the same `Node`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let splits =
            group != 0 && in_shape.c.is_multiple_of(group) && m.is_multiple_of(group);
        if !splits {
            self.fail(format!(
                "tensor {weight_index}: {} in / {m} out channels do not split into \
                 {group} groups",
                in_shape.c
            ));
        }
        let per_group = in_shape.c.checked_div(group).unwrap_or(0);
        let weight = self.weight(weight_index, &[m, per_group, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);
        let act_weight = self.act_weight(act, m);
        self.push_conv(
            input,
            weight,
            bias,
            act_weight,
            m,
            kernel,
            stride,
            dilation,
            pads,
            group,
            act,
            false,
            self.pad_edge,
        )
    }

    /// The node push behind [`Builder::conv`] and [`Builder::conv_raw`].
    ///
    /// Split out so the two entry points — table indices for hand-written passes,
    /// resolved offsets for section lowering — share the shape propagation, the output
    /// allocation, and the node construction. The only thing they do differently is how
    /// the weight offsets are obtained.
    #[allow(clippy::too_many_arguments)]
    fn push_conv(
        &mut self,
        input: Id,
        weight: u32,
        bias: u32,
        act_weight: u32,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        transpose: bool,
        pad_edge: bool,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = conv_out(in_shape.h, kh, stride.0, dilation.0, pad_t + pad_b);
        let out_w = conv_out(in_shape.w, kw, stride.1, dilation.1, pad_l + pad_r);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation,
            pad: (pad_t, pad_l),
            group,
            act,
            act_weight,
            transpose,
            pad_edge,
            res: None,
            shift: None,
        });
        out
    }

    /// [`Builder::conv`] with resolved weight offsets rather than table indices.
    ///
    /// The version-2 graph section names file tensors by index, and the section parser
    /// already resolved and shape-checked them — re-resolving here would need the dims
    /// the section deliberately does not carry. So this takes offsets (exactly what
    /// `Offsets::shaped` returns) and `m` (the output channels, from the section's
    /// computed table) and pushes the same `Node::Conv` the indexed path would have.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_raw(
        &mut self,
        input: Id,
        weight: u32,
        bias: u32,
        act_weight: u32,
        m: u32,
        act: Act,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        pad_edge: bool,
    ) -> Id {
        self.push_conv(
            input, weight, bias, act_weight, m, kernel, stride, dilation, pads, group,
            act, false, pad_edge,
        )
    }

    /// [`Builder::conv`] with int8 weights and a per-output-channel dequantisation scale.
    ///
    /// Three tensors rather than two: the int8 kernel at `weight_index`, an `[m]` fp16 scale
    /// after it, and the fp16 bias after that. The scale is its own tensor because a `.maml`
    /// table entry is already full at 32 bytes, and a companion tensor needs no format version
    /// bump.
    ///
    /// The quantisation is symmetric and per output channel, so a value is `int8 * scale` and the
    /// scale multiplies the finished accumulator once. `Act::PRelu` is refused: it would need a
    /// second weights offset, and the push block has one spare which the scale is using.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_int8(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
    ) -> Id {
        self.conv_quantised(
            input, weight_index, m, kernel, stride, dilation, pads, group, act, Quant::I8,
        )
    }

    /// [`Builder::conv_int8`] with a **four-bit** kernel and a per-block scale.
    ///
    /// The scale tensor is rank 2, `(m, ceil(taps / I4_BLOCK))`, where a tap is one element of
    /// `per_group * kh * kw`. Four bits cannot hold an output row's dynamic range under a single
    /// scale; a block of 32 taps can.
    ///
    /// Only `1 x 1` is offered. The padded and grouped shader has no int4 counterpart, and the
    /// tensors worth quantising this far - projections and feed-forwards - are all pointwise.
    pub fn conv_int4(&mut self, input: Id, weight_index: usize, m: u32, act: Act) -> Id {
        self.conv_quantised(
            input,
            weight_index,
            m,
            (1, 1),
            (1, 1),
            (1, 1),
            (0, 0, 0, 0),
            1,
            act,
            Quant::I4,
        )
    }

    /// The body behind [`Builder::conv_int8`] and [`Builder::conv_int4`].
    ///
    /// The two differ only in the scale tensor's rank and in which shaders the plan lowers to, so
    /// everything else - the group check, the word-addressed kernel, the refusal of `PRelu` - is
    /// stated once here.
    #[allow(clippy::too_many_arguments)]
    fn conv_quantised(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        quant: Quant,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let splits =
            group != 0 && in_shape.c.is_multiple_of(group) && m.is_multiple_of(group);
        if !splits {
            self.fail(format!(
                "tensor {weight_index}: {} in / {m} out channels do not split into \
                 {group} groups",
                in_shape.c
            ));
        }
        if let Act::PRelu(_) = act {
            self.fail(format!(
                "tensor {weight_index}: a quantised convolution cannot carry a PRelu, whose \
                 per-channel slope would need the offset the scale occupies"
            ));
        }
        let per_group = in_shape.c.checked_div(group).unwrap_or(0);
        let weight = match self.weights.shaped_words(weight_index, &[m, per_group, kh, kw]) {
            Ok(offset) => {
                if let Some(slot) = self.read.get_mut(weight_index) {
                    *slot = true;
                }
                offset
            }
            Err(e) => {
                self.fail(e);
                0
            }
        };
        let scale = match quant {
            Quant::I8 => self.weight(weight_index + 1, &[m]),
            // One per block of taps, so the table is `(out, blocks)`. Resolved by shape, which is
            // what stops an int8 scale being read as an int4 one.
            Quant::I4 => {
                let blocks = (per_group * kh * kw).div_ceil(crate::weights::I4_BLOCK);
                self.weight(weight_index + 1, &[m, blocks])
            }
        };
        let bias = self.weight(weight_index + 2, &[m]);
        self.push_conv_int8(input, weight, scale, bias, m, kernel, stride, dilation, pads, group, act, quant)
    }

    /// The node push behind [`Builder::conv_quantised`] and [`Builder::conv_int8_raw`].
    ///
    /// As [`Builder::push_conv`] is for the fp16 path: the indexed and resolved entry
    /// points share shape propagation and node construction, differing only in how the
    /// weight offsets are obtained.
    #[allow(clippy::too_many_arguments)]
    fn push_conv_int8(
        &mut self,
        input: Id,
        weight: u32,
        scale: u32,
        bias: u32,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: Act,
        quant: Quant,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = conv_out(in_shape.h, kh, stride.0, dilation.0, pad_t + pad_b);
        let out_w = conv_out(in_shape.w, kw, stride.1, dilation.1, pad_l + pad_r);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::ConvInt8 {
            input,
            out,
            weight,
            scale,
            bias,
            kernel,
            stride,
            dilation,
            pad: (pad_t, pad_l),
            group,
            act,
            quant,
            res: None,
            shift: None,
        });
        out
    }

    /// [`Builder::conv_int8`] with resolved weight offsets rather than table indices.
    ///
    /// As [`Builder::conv_raw`]: the section parser validated shapes, so lowering only
    /// translates addressing. `m` is the section's computed output channels.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_int8_raw(
        &mut self,
        input: Id,
        weight: u32,
        scale: u32,
        bias: u32,
        m: u32,
        act: Act,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        quant: Quant,
    ) -> Id {
        self.push_conv_int8(
            input, weight, scale, bias, m, kernel, stride, dilation, pads, group, act,
            quant,
        )
    }

    /// Resolve [`Act::PRelu`]'s slope tensor, which is `[channels, 1, 1]` in the ONNX
    /// exports this reads.
    fn act_weight(&mut self, act: Act, channels: u32) -> u32 {
        match act {
            Act::PRelu(index) => self.weight(index, &[channels, 1, 1]),
            _ => 0,
        }
    }

    /// The common case in both nets: `3x3` or `1x1`, stride 1, `pad == dilation`
    /// (which keeps the output size), one group.
    pub fn conv_same(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: u32,
        dilation: u32,
        act: Act,
    ) -> Id {
        // `pad == dilation` holds the spatial size only for an odd kernel, which is
        // the only case either net uses it for.
        let pad = if kernel == 1 { 0 } else { dilation };
        self.conv(
            input,
            weight_index,
            m,
            (kernel, kernel),
            (1, 1),
            (dilation, dilation),
            (pad, pad, pad, pad),
            1,
            act,
        )
    }

    /// A transposed convolution: weights `[in_c, m/group, kh, kw]`, output
    /// `(in - 1) * stride + dilation * (k - 1) + 1 - pads`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_transpose(
        &mut self,
        input: Id,
        weight_index: usize,
        m: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        pads: (u32, u32, u32, u32),
        act: Act,
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let weight = self.weight(weight_index, &[in_shape.c, m, kh, kw]);
        let bias = self.weight(weight_index + 1, &[m]);
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = deconv_out(in_shape.h, kh, stride.0, pad_t + pad_b);
        let out_w = deconv_out(in_shape.w, kw, stride.1, pad_l + pad_r);
        let act_weight = self.act_weight(act, m);
        let out = self.tensor(Shape::new(m, out_h, out_w));
        self.nodes.push(Node::Conv {
            input,
            out,
            weight,
            bias,
            kernel,
            stride,
            dilation: (1, 1),
            pad: (pad_t, pad_l),
            group: 1,
            act,
            act_weight,
            transpose: true,
            pad_edge: false,
            res: None,
            shift: None,
        });
        out
    }

    fn weight(&mut self, index: usize, dims: &[u32]) -> u32 {
        match self.read.get_mut(index) {
            Some(slot) => *slot = true,
            None => self.fail(format!(
                "tensor {index}: the file holds {}",
                self.weights.count()
            )),
        }
        match self.weights.shaped(index, dims) {
            Ok(offset) => offset,
            Err(e) => {
                self.fail(e);
                0
            }
        }
    }

    /// `2x2` stride-2 max pooling, which is the only pooling either net does.
    ///
    /// ONNX marks these `ceil_mode=1`, but every pooled extent in U^2-Netp is even
    /// (320 halves five times to 10), so ceil and floor agree and the distinction is
    /// deliberately not modelled. [`tests::u2netp_pools_only_even_extents`] holds that.
    pub fn max_pool_2x2(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        if !in_shape.h.is_multiple_of(2) || !in_shape.w.is_multiple_of(2) {
            self.fail(format!(
                "max_pool_2x2 on {}x{}: ceil_mode is not modelled, so an odd extent \
                 would silently drop a row",
                in_shape.h, in_shape.w
            ));
        }
        let out = self.tensor(Shape::new(in_shape.c, in_shape.h / 2, in_shape.w / 2));
        self.nodes.push(Node::MaxPool {
            input,
            out,
            kernel: (2, 2),
            stride: (2, 2),
        });
        out
    }

    /// Average pooling over `kernel` at `stride`, which must tile the input exactly.
    ///
    /// The one use is `(3, 2)` at `(3, 2)` on a `3 x 80` map, so it tiles. Refusing
    /// anything else is deliberate: a window that overhangs makes the divisor a question
    /// — ONNX has `count_include_pad` for it and the two answers differ — and the shader
    /// divides by `kh * kw` unconditionally. A ragged extent would silently scale the
    /// edge of the sequence.
    pub fn avg_pool(&mut self, input: Id, kernel: (u32, u32), stride: (u32, u32)) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let out_h = conv_out(in_shape.h, kh, stride.0, 1, 0);
        let out_w = conv_out(in_shape.w, kw, stride.1, 1, 0);
        for (axis, extent, k, s, out) in [
            ('h', in_shape.h, kh, stride.0, out_h),
            ('w', in_shape.w, kw, stride.1, out_w),
        ] {
            if out == 0 || out.saturating_sub(1) * s + k != extent {
                self.fail(format!(
                    "avg_pool of {k} at stride {s} does not tile {extent} along {axis}, so \
                     the divisor would not be the window size"
                ));
            }
        }
        let out = self.tensor(Shape::new(in_shape.c, out_h, out_w));
        self.nodes.push(Node::AvgPool { input, out, kernel, stride });
        out
    }
    /// Bilinear resize to `like`'s spatial size, which is how both shipping nets always
    /// use it — U^2-Net's `_upsample_like`, and the selfie net's decoder skips.
    pub fn resize_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        self.resize_to(input, target.h, target.w)
    }

    /// Bilinear resize to an explicit size.
    pub fn resize_to(&mut self, input: Id, h: u32, w: u32) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, h, w));
        self.nodes.push(Node::Resize { input, out, nearest: false });
        out
    }

    /// Nearest-neighbour resize to `like`'s spatial size — SCRFD's two FPN upsamples.
    pub fn resize_nearest_like(&mut self, input: Id, like: Id) -> Id {
        let target = self.shape_of(like);
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, target.h, target.w));
        self.nodes.push(Node::Resize { input, out, nearest: true });
        out
    }

    /// Mean over H and W, to `C x 1 x 1`.
    pub fn global_avg_pool(&mut self, input: Id) -> Id {
        let in_shape = self.shape_of(input);
        let out = self.tensor(Shape::new(in_shape.c, 1, 1));
        self.nodes.push(Node::GlobalAvgPool { input, out });
        out
    }

    /// Elementwise `a + b`. Shapes must match.
    ///
    /// # Fusion
    ///
    /// [`Builder::finish`] may fold this into the convolution that produced one side —
    /// see `Node::Conv::res` — when that side has no other reader and the other side is
    /// already written by then. An FPN-style `add(earlier, later)` is the canonical
    /// non-residual: the skip side is *downstream* of the producer, so reading it from
    /// the producer's store would read an unwritten tensor, and the add stays its own
    /// dispatch. Write residuals as `add(skip, produced)` and the fold applies; the
    /// argument order carries no semantics either way.
    pub fn add(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sa != sb {
            self.fail(format!("add of {sa:?} and {sb:?}"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::Add, a, b, out });
        out
    }

    /// Elementwise `a * b`. Shapes must match; see [`Builder::mul_channel`] for the
    /// broadcasting form.
    ///
    /// 241 uses in Supertonic's flow-matching sampler alone, gating and scaling whole
    /// activations rather than whole channels.
    pub fn mul(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sa != sb {
            self.fail(format!("mul of {sa:?} and {sb:?}"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::Mul, a, b, out });
        out
    }

    /// `a * b` with `b` broadcast over H and W — the excite half of a squeeze-excite
    /// block, where `b` came from [`Builder::global_avg_pool`].
    pub fn mul_channel(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sb.c != sa.c || sb.h != 1 || sb.w != 1 {
            self.fail(format!("mul_channel of {sa:?} by {sb:?}, which is not Cx1x1"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::MulBroadcast, a, b, out });
        out
    }

    /// Concatenate along the channel axis. Spatial sizes must match.
    pub fn concat(&mut self, parts: &[Id]) -> Id {
        let shapes: Vec<Shape> = parts.iter().map(|&p| self.shape_of(p)).collect();
        let first = match shapes.first() {
            Some(&s) => s,
            None => {
                self.fail("concat of nothing".into());
                Shape::new(0, 0, 0)
            }
        };
        let mut channels = 0;
        for s in &shapes {
            if s.h != first.h || s.w != first.w {
                self.fail(format!("concat of {first:?} with {s:?}"));
            }
            channels += s.c;
        }
        let out = self.tensor(Shape::new(channels, first.h, first.w));
        self.nodes.push(Node::Concat { parts: parts.to_vec(), out });
        out
    }

    /// Concatenate along the **width** axis. Channels and height must match.
    ///
    /// One use, TinyCLIP's class token: prepending a single position to the `[256, 1, 196]` patch
    /// grid to make the `[256, 1, 197]` sequence the vision transformer reads.
    ///
    /// Unlike [`Builder::concat`] this is not one copy. The position axis is innermost, so a
    /// *part* is a column range of every channel rather than a contiguous run — the same fact that
    /// forced the position-major KV cache in [`Kind::AttnScoresCached`]. It is still only
    /// [`Op::Copy`] and needs no shader: `c * h` runs per part, recorded **once** when the plan is
    /// built rather than per inference. For CLIP that is 512 copies in the command buffer, for a
    /// tensor the class token is one 512-byte column of.
    pub fn concat_positions(&mut self, parts: &[Id]) -> Id {
        let shapes: Vec<Shape> = parts.iter().map(|&p| self.shape_of(p)).collect();
        let first = match shapes.first() {
            Some(&s) => s,
            None => {
                self.fail("a position concat of nothing".into());
                Shape::new(0, 0, 0)
            }
        };
        let mut width = 0;
        for s in &shapes {
            if s.c != first.c || s.h != first.h {
                self.fail(format!("a position concat of {first:?} with {s:?}"));
            }
            width += s.w;
        }
        let out = self.tensor(Shape::new(first.c, first.h, width));
        self.nodes.push(Node::ConcatPositions { parts: parts.to_vec(), out });
        out
    }

    /// `x * scale + shift`, elementwise with scalar parameters. See [`Kind::Affine`].
    pub fn affine(&mut self, input: Id, scale: f32, shift: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Affine { input, out, scale, shift });
        out
    }

    /// Layer normalisation over the channel axis, with a per-channel affine.
    ///
    /// `weight_index` is the gamma tensor's position in the `.maml` table; beta follows
    /// it, the way a convolution's bias follows its weight.
    pub fn layer_norm(&mut self, input: Id, weight_index: usize, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let gamma = self.weight(weight_index, &[shape.c]);
        let beta = self.weight(weight_index + 1, &[shape.c]);
        self.push_layer_norm(input, gamma, beta, epsilon)
    }

    /// The node push behind [`Builder::layer_norm`] and [`Builder::layer_norm_raw`].
    ///
    /// As [`Builder::push_conv` is for convolutions: shared shape passthrough and node
    /// construction, differing only in how the gamma/beta offsets are obtained.
    fn push_layer_norm(&mut self, input: Id, gamma: u32, beta: u32, epsilon: f32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::LayerNorm { input, out, gamma, beta, epsilon });
        out
    }

    /// [`Builder::layer_norm`] with resolved weight offsets rather than table indices.
    ///
    /// As [`Builder::conv_raw` for convolutions.
    pub fn layer_norm_raw(
        &mut self,
        input: Id,
        gamma: u32,
        beta: u32,
        epsilon: f32,
    ) -> Id {
        self.push_layer_norm(input, gamma, beta, epsilon)
    }

    /// Root-mean-square normalisation over the channel axis, with a per-channel gain.
    ///
    /// [`layer_norm`](Self::layer_norm) without the mean subtraction and without beta, so
    /// `weight_index` is a single tensor rather than the start of a pair.
    pub fn rms_norm(&mut self, input: Id, weight_index: usize, epsilon: f32) -> Id {
        self.rms_norm_grouped(input, weight_index, epsilon, 1)
    }

    /// [`Builder::rms_norm`] over each of `groups` contiguous runs of channels independently.
    ///
    /// One gain table of `channels / groups` entries, re-used by every group. This is Gemma 4's
    /// QK-norm: a query is `[heads * head_dim, 1, 1]` and each head's `head_dim` slice is
    /// normalised on its own, against one `head_dim`-long gamma.
    ///
    /// Not expressible as a reshape. The channels are head-major, so head `h`'s slice is
    /// contiguous at `h * head_dim`; a `[head_dim, 1, heads]` view would stride the wrong way and
    /// silently normalise across heads instead of within them.
    pub fn rms_norm_grouped(
        &mut self,
        input: Id,
        weight_index: usize,
        epsilon: f32,
        groups: u32,
    ) -> Id {
        let shape = self.shape_of(input);
        if groups == 0 || !shape.c.is_multiple_of(groups) {
            self.fail(format!("{} channels do not split into {groups} groups", shape.c));
        }
        let per_group = shape.c.checked_div(groups.max(1)).unwrap_or(0);
        let gamma = self.weight(weight_index, &[per_group]);
        let out = self.tensor(shape);
        self.nodes.push(Node::RmsNorm { input, out, gamma, epsilon, groups });
        out
    }

    /// Attention scores from `q` and `k`, both `[d_model, 1, T]`, into `[heads, T, T]`.
    ///
    /// The `1 / sqrt(head_dim)` scale is derived here rather than taken as an argument:
    /// it is a property of the head geometry, not a trained value, so there is no call
    /// site that could legitimately pass a different one.
    /// [`Builder::attn_scores`] where `kv_heads` heads supply the keys, and the query is
    /// **already scaled**.
    ///
    /// Gemma 4's prefill: eight query heads against one key head, and the `1 / sqrt(head_dim)`
    /// already folded into `q_norm` - see `nets::gemma4`'s `Q_NORM_CARRIES_SCALE`.
    pub fn attn_scores_grouped_prescaled(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        kv_heads: u32,
    ) -> Id {
        let (out, _) = self.score_map(q, k, heads, kv_heads);
        self.nodes.push(Node::AttnScores {
            q,
            k,
            out,
            heads,
            kv_heads,
            scale: 1.0,
        });
        out
    }

    pub fn attn_scores(&mut self, q: Id, k: Id, heads: u32) -> Id {
        let (out, scale) = self.score_map(q, k, heads, heads);
        self.nodes.push(Node::AttnScores { q, k, out, heads, kv_heads: heads, scale });
        out
    }

    /// [`Builder::attn_scores`] for a query that is **already scaled**.
    ///
    /// The uncached counterpart of [`Builder::attn_scores_cached_prescaled`], and it exists for
    /// the same export. Gemma 4's vision tower goes `q_proj -> Clip -> q_norm -> rotary ->
    /// MatMul` with no `Mul` anywhere in between, so there is no `1 / sqrt(head_dim)` to
    /// reproduce; its `q_norm` and `k_norm` gammas are uniform scalars whose product is about a
    /// half in every layer, which is where the scaling actually lives.
    ///
    /// Deriving the scale here as well would divide every score by eight. That is not a shape
    /// error and no layout test sees it - it just flattens all sixteen layers of attention.
    pub fn attn_scores_prescaled(&mut self, q: Id, k: Id, heads: u32) -> Id {
        let (out, _) = self.score_map(q, k, heads, heads);
        self.nodes.push(Node::AttnScores { q, k, out, heads, kv_heads: heads, scale: 1.0 });
        out
    }

    /// Attention scores for one query against a **position-major** K cache.
    ///
    /// `q` is `[d_model, 1, 1]` and `cache` is `[keys, 1, d_model]`, giving `[heads, 1, keys]`.
    /// The cache's axes are the other way round from every other sequence here, deliberately: see
    /// [`Kind::AttnScoresCached`].
    ///
    /// One query is what makes a causal mask unnecessary — a decode step attends over exactly the
    /// positions in the cache, so the prefix bound is the tensor's own length.
    pub fn attn_scores_cached(&mut self, q: Id, cache: Id, heads: u32) -> Id {
        self.attn_scores_cached_at(q, cache, heads, heads, false, true, true)
    }

    /// [`Builder::attn_scores_cached`] with the key count supplied by the step, not the shape.
    ///
    /// `cache` is then sized to the **maximum** context the plan is built for, and only its
    /// leading `prefix + 1` positions are attended. See [`Push::dyn_keys`].
    pub fn attn_scores_cached_dynamic(&mut self, q: Id, cache: Id, heads: u32) -> Id {
        self.attn_scores_cached_at(q, cache, heads, heads, true, true, true)
    }

    /// [`Builder::attn_scores_cached_dynamic`] where `kv_heads` heads supply the keys.
    ///
    /// The cache is then `[keys, 1, kv_heads * head_dim]` rather than `[keys, 1, d_model]`, which
    /// is how a decoder holding one KV head for eight query heads stores an eighth as much.
    pub fn attn_scores_cached_grouped(
        &mut self,
        q: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
    ) -> Id {
        self.attn_scores_cached_at(q, cache, heads, kv_heads, true, true, true)
    }

    /// [`Builder::attn_scores_cached_grouped`] for a query that is **already scaled**.
    ///
    /// The usual `1 / sqrt(head_dim)` is a property of the head geometry, so every other entry
    /// point derives it rather than taking it. Gemma 4 is the exception: its export folds the
    /// scale into `q_norm`'s gamma and applies none between the projection and the score matmul,
    /// so deriving it here as well would apply it twice - which is not a shape error, does not
    /// fail any layout test, and merely flattens every attention distribution in the model.
    ///
    /// `sliding` says whether this layer's window applies. See [`Push::sliding`].
    pub fn attn_scores_cached_prescaled(
        &mut self,
        q: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        sliding: bool,
    ) -> Id {
        self.attn_scores_cached_at(q, cache, heads, kv_heads, true, false, sliding)
    }

    fn attn_scores_cached_at(
        &mut self,
        q: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        dynamic: bool,
        derive_scale: bool,
        sliding: bool,
    ) -> Id {
        let (sq, sc) = (self.shape_of(q), self.shape_of(cache));
        if sq.h != 1 || sq.w != 1 {
            self.fail(format!(
                "a cached score map over q {sq:?}: a decode step is one query, so q is \
                 [d_model, 1, 1]"
            ));
        }
        if sc.h != 1 {
            self.fail(format!(
                "a cached score map over q {sq:?} and a cache {sc:?}: a cache is \
                 [keys, 1, kv_heads * head_dim], so its channels are the keys"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        if kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} key/value heads"));
        }
        if sc.w != kv_heads * head_dim {
            self.fail(format!(
                "a cache {sc:?} for {kv_heads} key/value heads of {head_dim}: its width should \
                 be {}",
                kv_heads * head_dim
            ));
        }
        let scale = if derive_scale { 1.0 / (head_dim.max(1) as f32).sqrt() } else { 1.0 };
        let out = self.tensor(Shape::new(heads, 1, sc.c));
        self.nodes.push(Node::AttnScoresCached { q, cache, out, heads, kv_heads, scale, dynamic, sliding });
        out
    }

    /// One query's attention output against a **position-major** V cache.
    ///
    /// `probs` is `[heads, 1, keys]` and `cache` is `[keys, 1, d_model]`, giving
    /// `[d_model, 1, 1]` — back in the channel-major layout the next projection reads, so the
    /// cache layout is confined to the two operands that are caches.
    pub fn attn_apply_cached(&mut self, probs: Id, cache: Id, heads: u32) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, heads, false, true)
    }

    /// [`Builder::attn_apply_cached`] with the key count supplied by the step, not the shape.
    ///
    /// Must be paired with [`Builder::attn_scores_cached_dynamic`] and
    /// [`Builder::softmax_prefix`]: all three read the same bound, and mixing a dynamic score map
    /// with a full-width sum would fold unattended positions into the result.
    pub fn attn_apply_cached_dynamic(&mut self, probs: Id, cache: Id, heads: u32) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, heads, true, true)
    }

    /// [`Builder::attn_apply_cached_dynamic`] where `kv_heads` heads supply the values.
    ///
    /// `out_channels` is the query side's width, `heads * head_dim`, which is what the next
    /// projection reads — it cannot be inferred from a cache that is narrower.
    pub fn attn_apply_cached_grouped(
        &mut self,
        probs: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        sliding: bool,
    ) -> Id {
        self.attn_apply_cached_at(probs, cache, heads, kv_heads, true, sliding)
    }

    fn attn_apply_cached_at(
        &mut self,
        probs: Id,
        cache: Id,
        heads: u32,
        kv_heads: u32,
        dynamic: bool,
        sliding: bool,
    ) -> Id {
        let (sp, sc) = (self.shape_of(probs), self.shape_of(cache));
        if sp.c != heads || sp.h != 1 {
            self.fail(format!("cached attention over probs {sp:?} with {heads} heads"));
        }
        if sc.h != 1 || sp.w != sc.c {
            self.fail(format!(
                "cached attention over probs {sp:?} and a cache {sc:?}: the key counts differ"
            ));
        }
        if heads == 0 || kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} key/value heads"));
        }
        if !sc.w.is_multiple_of(kv_heads.max(1)) {
            self.fail(format!("{} cache channels do not split into {kv_heads} heads", sc.w));
        }
        // The output is the **query** side's width, `heads * head_dim`, which is what the next
        // projection reads. Under grouped- or multi-query attention the cache is narrower than
        // that, so taking the width from the cache would silently produce a shorter tensor.
        let head_dim = sc.w.checked_div(kv_heads.max(1)).unwrap_or(0);
        let out = self.tensor(Shape::new(heads * head_dim, 1, 1));
        self.nodes.push(Node::AttnApplyCached { probs, cache, out, heads, kv_heads, dynamic, sliding });
        out
    }

    /// The same elements under a different shape, as one contiguous copy.
    ///
    /// A projection writes `[d_model, 1, 1]` and a position-major cache is `[T, 1, d_model]`, so
    /// appending the step's key means reading `d_model` elements as one *position* rather than as
    /// `d_model` channels. Those are the same bytes in the same order, and this is the relabelling.
    ///
    /// A copy rather than a view for the reason [`Builder::slice_channels`] gives: a view would
    /// have to survive the arena's last-use bookkeeping. It is `d_model` elements, and it reuses
    /// the concatenation path exactly — one [`Op::Copy`], no shader and no new op kind.
    pub fn reshaped(&mut self, input: Id, shape: Shape) -> Id {
        let from = self.shape_of(input);
        if from.len() != shape.len() {
            self.fail(format!("reshaping {from:?} to {shape:?} changes the element count"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Concat { parts: vec![input], out });
        out
    }

    /// The validation, output tensor and scale every score map shares, relative or not.
    ///
    /// `q` and `k` may be different lengths: the map is `[heads, queries, keys]`, which for
    /// self-attention is the square `[heads, T, T]` and for a cross-attention is not. They must
    /// still agree on the channel count, since that is what the dot product contracts over.
    fn score_map(&mut self, q: Id, k: Id, heads: u32, kv_heads: u32) -> (Id, f32) {
        let (sq, sk) = (self.shape_of(q), self.shape_of(k));
        if kv_heads == 0 || heads % kv_heads != 0 {
            self.fail(format!("{heads} query heads do not group into {kv_heads} kv heads"));
        }
        // K is `kv_heads` heads wide where Q is `heads` wide, so the channel counts agree only
        // for ordinary multi-head attention. What must always agree is the head dimension.
        if sq.c / heads.max(1) != sk.c / kv_heads.max(1) {
            self.fail(format!("attention over q {sq:?} and k {sk:?} at {kv_heads} kv heads"));
        }
        if sq.h != 1 || sk.h != 1 {
            self.fail(format!(
                "attention on {sq:?}: a sequence is [d_model, 1, T], so a height above \
                 one would silently reinterpret the layout"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        let head_dim = sq.c.checked_div(heads).unwrap_or(0);
        let scale = 1.0 / (head_dim.max(1) as f32).sqrt();
        (self.tensor(Shape::new(heads, sq.w, sk.w)), scale)
    }

    /// A tensor that keeps its contents from one execution to the next.
    ///
    /// The arena is one device-local buffer that nothing clears between submits, so a tensor the
    /// allocator never hands to anything else is still holding last execution's values when the
    /// next one starts. That is what lets a decoder keep its KV cache on the device instead of
    /// shipping it back to the host and in again every token.
    ///
    /// Unlike [`Builder::input`] this is **not** a plan input, which is the point: an input is
    /// overwritten from the staging buffer at the top of every recording, so a cache declared as
    /// one would be erased by the very submit meant to extend it.
    ///
    /// # What resets it
    ///
    /// [`crate::vulkan::run::Net::rebuild`] may allocate a larger arena and rebind to it, which
    /// drops the contents. For a decoder that is the correct behaviour and not a hazard: a
    /// rebuild happens when the plan's shape changes, which for NLLB means a new source sentence,
    /// and a new sentence must start from an empty cache anyway.
    ///
    /// # Cost
    ///
    /// Pinned for the whole pass, so it never shares space with an activation. A cache sized for
    /// the maximum context is charged in full to the arena whether or not a sentence reaches it.
    pub fn persistent(&mut self, shape: Shape) -> Id {
        let id = self.tensor(shape);
        self.pinned.push(id);
        id
    }

    /// Write `row` into `cache` at the row the step's prefix names.
    ///
    /// `cache` must come from [`Builder::persistent`] and be `[max_positions, 1, d_model]`; `row`
    /// is the `[d_model, 1, 1]` a projection just produced. Returns nothing, because the cache is
    /// not a value: it is a region later ops read by identity.
    pub fn cache_write(&mut self, row: Id, cache: Id) {
        let (sr, sc) = (self.shape_of(row), self.shape_of(cache));
        // One position, or a whole prefill's worth. The rows are contiguous and the cache is
        // position-major, so writing T of them is the same store with a longer count - see
        // `shaders/cache_write.comp`, which needed no change for this.
        if sc.w == 0 || sr.len() % sc.w != 0 {
            self.fail(format!(
                "appending {sr:?} to a cache {sc:?}: a position is the cache's width, \
                 {} elements, and this is not a whole number of them",
                sc.w
            ));
        }
        if sc.h != 1 {
            self.fail(format!("a cache is [max_positions, 1, d_model], not {sc:?}"));
        }
        self.nodes.push(Node::CacheWrite { row, cache });
    }

    /// Clamp to the `[min, max]` pair at `weight_index`, a `[2]` fp16 tensor.
    ///
    /// The bounds are weights rather than arguments because Gemma 4's vision tower has 177 of
    /// them, each calibrated separately, and `Builder` cannot read a value at build time.
    pub fn clamp(&mut self, input: Id, weight_index: usize) -> Id {
        let bounds = self.weight(weight_index, &[2]);
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Clamp { input, out, bounds });
        out
    }

    /// Multiply by a **scalar held in the weights**, a `[1]` tensor at `weight_index`.
    ///
    /// Distinct from [`Builder::affine`], whose scale is a compile-time constant. See
    /// [`Kind::MulScalar`].
    pub fn mul_scalar(&mut self, input: Id, weight_index: usize) -> Id {
        let scale = self.weight(weight_index, &[1]);
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::MulScalar { input, out, scale });
        out
    }

    /// An [`Act`] applied on its own, for a value no convolution produced.
    ///
    /// Every other activation in this runtime is folded into the projection before it. A gated
    /// feed-forward is the exception - see [`Kind::Activate`].
    pub fn activate(&mut self, input: Id, act: Act) -> Id {
        if matches!(act, Act::PRelu(_)) {
            self.fail("a standalone PRelu, whose slope is a weight this op does not bind".into());
        }
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Activate { input, out, act });
        out
    }

    /// `activate(gate) * up` for a fused `[gate | up]` projection, in one op.
    ///
    /// Replaces `slice_channels` twice, an `activate` and a `mul`: four dispatches where three
    /// exist only to hand data to the next. See `shaders/gated_activate.comp`.
    pub fn gated_activate(&mut self, input: Id, act: Act) -> Id {
        let shape = self.shape_of(input);
        if shape.c % 2 != 0 {
            self.fail(format!("a gated activation over {shape:?}, whose channels are not a pair"));
        }
        if matches!(act, Act::PRelu(_)) {
            self.fail("a gated PRelu, whose per-channel slope this does not carry".to_string());
        }
        let out = self.tensor(Shape::new(shape.c / 2, shape.h, shape.w));
        self.nodes.push(Node::GatedActivate { input, out, act });
        out
    }
    /// `tanh(x / cap) * cap`, elementwise. See [`Kind::Softcap`].
    pub fn softcap(&mut self, input: Id, cap: f32) -> Id {
        if !(cap > 0.0) {
            self.fail(format!("a softcap of {cap}, which must be positive"));
        }
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Softcap { input, out, cap });
        out
    }

    /// Softmax over the last axis, which for a score map is one query's distribution.
    pub fn softmax(&mut self, input: Id) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a softmax over {shape:?}, whose last axis is empty"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Full, sliding: false, window: 0 });
        out
    }

    /// [`Builder::softmax`] with each query's row truncated at the diagonal.
    ///
    /// The input must be a square score map `[heads, T, T]`: a causal mask is a statement about
    /// which *keys* a *query* may read, so queries and keys have to be the same sequence. A
    /// cross-attention map is not square and masking one would be meaningless rather than merely
    /// wrong, which is why this is refused instead of clamped.
    /// [`Builder::softmax_causal`] that also drops keys more than `window - 1` behind the query.
    ///
    /// The sliding half of a batched prefill. `window` of 0 is the plain causal mask.
    pub fn softmax_causal_windowed(&mut self, input: Id, window: u32) -> Id {
        let shape = self.shape_of(input);
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax {
            input,
            out,
            mode: SoftmaxMode::Causal,
            sliding: false,
            window,
        });
        out
    }

    pub fn softmax_causal(&mut self, input: Id) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a causal softmax over {shape:?}, whose last axis is empty"));
        }
        if shape.h != shape.w {
            self.fail(format!(
                "a causal softmax over {shape:?}: the mask is over one sequence attending to \
                 itself, so the map is [heads, T, T]"
            ));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Causal, sliding: false, window: 0 });
        out
    }

    /// [`Builder::softmax`] over only the leading `prefix + 1` entries of each row.
    ///
    /// For a decode plan built once at a maximum context: the row is `shape.w` wide, but the step
    /// supplies how much of it was written. See [`Kind::SoftmaxPrefix`].
    pub fn softmax_prefix(&mut self, input: Id, sliding: bool) -> Id {
        let shape = self.shape_of(input);
        if shape.w == 0 {
            self.fail(format!("a prefix softmax over {shape:?}, whose last axis is empty"));
        }
        let out = self.tensor(shape);
        self.nodes.push(Node::Softmax { input, out, mode: SoftmaxMode::Prefix, sliding, window: 0 });
        out
    }

    /// Channels `start .. start + count` of `input`, as a tensor of its own.
    ///
    /// One copy and no shader: a channel range of a `[C, H, W]` tensor is contiguous, so this
    /// is a element-range move like the one [`Builder::concat`] already uses. It is a copy
    /// rather than a view because a view would have to survive the arena's last-use
    /// bookkeeping, and the ranges here are a few hundred kilobytes.
    pub fn slice_channels(&mut self, input: Id, start: u32, count: u32) -> Id {
        let shape = self.shape_of(input);
        if count == 0 || start.saturating_add(count) > shape.c {
            self.fail(format!(
                "channels {start}..{} of {shape:?}",
                start.saturating_add(count)
            ));
        }
        let out = self.tensor(Shape::new(count, shape.h, shape.w));
        self.nodes.push(Node::SliceChannels { input, out, start });
        out
    }

    /// Declare that a tensor is read on the **host** rather than by the plan.
    ///
    /// [`Builder::finish`] refuses a file with an unread tensor, because that is what a forward
    /// pass which skipped a layer looks like from the outside. A few tensors legitimately never
    /// reach a shader: Supertonic's sampler conditions on a timestep embedding that is a function
    /// of two scalars, and on classifier-free-guidance tokens that differ per branch, so the host
    /// evaluates those and passes the results in as plan inputs. Naming them here keeps the
    /// invariant — nothing is *accidentally* unread — and puts the list in the net module beside
    /// the code that uses it.
    ///
    /// It also covers one file holding **several** passes. SMaLL-100 is one `.maml` and three
    /// plans — an encoder pass, a decode step and the logits projection — so no single one of them
    /// reads every tensor, and each names the others' as host tensors. The invariant that nothing
    /// is unread then has to be checked over the *union* of the plans, which is a test the net
    /// module owes because that is where the layout is; `nets::nllb` has it.
    pub fn host_tensor(&mut self, weight_index: usize, dims: &[u32]) {
        // Through `weight` so the shape is checked against the file like any other tensor.
        self.weight(weight_index, dims);
    }

    /// `a + b` where `b` is `C x 1 x 1`, a per-channel shift. See [`Kind::AddBroadcast`].
    pub fn add_channel(&mut self, a: Id, b: Id) -> Id {
        let (sa, sb) = (self.shape_of(a), self.shape_of(b));
        if sb.c != sa.c || sb.h != 1 || sb.w != 1 {
            self.fail(format!("add_channel of {sa:?} by {sb:?}, which is not Cx1x1"));
        }
        let out = self.tensor(sa);
        self.nodes.push(Node::Binary { kind: Kind::AddBroadcast, a, b, out });
        out
    }

    /// Rotary position embedding over a `[C, 1, W]` sequence. See [`Kind::Rotary`].
    ///
    /// `angles` is `[head_dim, 1, W]`: the cosines in its first `head_dim / 2` channels and the
    /// sines in the rest, one column per position.
    pub fn rotary(&mut self, input: Id, angles: Id, heads: u32) -> Id {
        self.rotary_axes(input, angles, heads, 1)
    }

    /// [`Builder::rotary`] over `axes` independent blocks inside each head.
    ///
    /// A 2-D position needs two rotations, not one over twice the channels: Gemma 4's vision
    /// tower rotates the first half of a 64-wide head by the patch's row and the second half by
    /// its column. Rotating the head as a single block would pair a row channel with a column
    /// channel - no shape error, and an encoder that is subtly position-blind.
    ///
    /// The angle table stays `[head_dim, 1, T]`, read as `axes` consecutive blocks of
    /// `head_dim / axes`, each cosines-then-sines.
    pub fn rotary_axes(&mut self, input: Id, angles: Id, heads: u32, axes: u32) -> Id {
        let (sx, sa) = (self.shape_of(input), self.shape_of(angles));
        if sx.h != 1 || sa.h != 1 {
            self.fail(format!("rotary on {sx:?}: a sequence is [d_model, 1, T]"));
        }
        if heads == 0 || !sx.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sx.c));
        }
        let head_dim = sx.c.checked_div(heads.max(1)).unwrap_or(0);
        if axes == 0 || !head_dim.is_multiple_of(axes.max(1)) {
            self.fail(format!(
                "rotary over a head of {head_dim} in {axes} blocks, which does not divide"
            ));
        }
        let block = head_dim.checked_div(axes.max(1)).unwrap_or(0);
        if block == 0 || !block.is_multiple_of(2) {
            self.fail(format!(
                "rotary over a block of {block}: it rotates 2-planes, so the block must be even"
            ));
        }
        if sa.c != head_dim || sa.w != sx.w {
            self.fail(format!(
                "rotary angles {sa:?} for {sx:?} in {heads} heads: the table is \
                 [head_dim, 1, T], cosines then sines"
            ));
        }
        let out = self.tensor(sx);
        self.nodes.push(Node::Rotary { input, angles, out, heads, axes });
        out
    }

    /// A learned tensor copied into the arena, so it can be an operand rather than a kernel.
    ///
    /// The only op that reads nothing from the arena. See [`Kind::Constant`] for why it exists
    /// at all; `shape` is what the `.maml` tensor holds, and its element count must match.
    pub fn constant(&mut self, weight_index: usize, shape: Shape) -> Id {
        let weight = self.weight(weight_index, &[shape.c, shape.h, shape.w]);
        let out = self.tensor(shape);
        self.nodes.push(Node::Constant { out, weight });
        out
    }

    /// An embedding lookup: `out[c][t] = table[id(t)][c]`, over a `[1, 1, T]` id tensor, or a
    /// `[2, 1, T]` one when the table has more than [`EMBED_LANE`] rows.
    ///
    /// `table` indexes a `[rows, channels]` tensor. A `sqrt(d_model)` scale applied after the
    /// lookup belongs in the table, folded by the converter, not here; see [`Kind::Embed`].
    pub fn embed(&mut self, ids: Id, table: usize, rows: u32, channels: u32) -> Id {
        let shape = self.shape_of(ids);
        let lanes = if rows > EMBED_LANE { 2 } else { 1 };
        if shape.c != lanes || shape.h != 1 {
            if lanes == 2 {
                // Refused rather than rounded: a single lane holds ids to 2048 exactly and
                // then starts landing on a neighbouring row, which reads as a plausible
                // wrong word rather than as a failure.
                self.fail(format!(
                    "embedding {shape:?}: {rows} rows is past {EMBED_LANE}, so the ids split \
                     across two lanes as a [2, 1, T] tensor"
                ));
            } else {
                self.fail(format!(
                    "embedding {shape:?}: ids are one per position, so a [1, 1, T] tensor"
                ));
            }
        }
        if rows == 0 || channels == 0 {
            self.fail(format!("an embedding table of {rows} x {channels}"));
        }
        let table = self.weight(table, &[rows, channels]);
        let out = self.tensor(Shape::new(channels, 1, shape.w));
        self.nodes.push(Node::Embed { ids, out, table, rows });
        out
    }

    /// [`Builder::attn_scores`] plus a relative-position term.
    ///
    /// `table` is a `[offsets, head_dim]` tensor of learned offsets, shared across heads.
    /// `offsets` must be odd: it is `2 * window + 1`, centred on zero displacement.
    pub fn attn_scores_relative(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        table: usize,
        offsets: u32,
    ) -> Id {
        let (out, scale) = self.score_map(q, k, heads, heads);
        let shape = self.shape_of(q);
        // A relative offset is `key - query`, so the two sequences have to be the same one.
        // Only the cross-attention variants take differing lengths.
        if shape.w != self.shape_of(k).w {
            self.fail(format!(
                "a relative score map over q {shape:?} and k {:?}: an offset is `key - query`, \
                 so both are positions in the same sequence",
                self.shape_of(k)
            ));
        }
        let head_dim = shape.c.checked_div(heads.max(1)).unwrap_or(0);
        self.check_offsets(offsets);
        let table = self.weight(table, &[offsets, head_dim]);
        self.nodes.push(Node::AttnScoresRelative { q, k, out, heads, scale, table, offsets });
        out
    }

    /// [`Builder::attn_apply`] plus the value-side relative term.
    pub fn attn_apply_relative(
        &mut self,
        probs: Id,
        v: Id,
        heads: u32,
        table: usize,
        offsets: u32,
    ) -> Id {
        let out = self.mixed(probs, v, heads);
        let shape = self.shape_of(v);
        let probs_shape = self.shape_of(probs);
        if probs_shape.h != probs_shape.w {
            self.fail(format!(
                "a relative value mix over {probs_shape:?}: an offset is `key - query`, so both \
                 are positions in the same sequence"
            ));
        }
        let head_dim = shape.c.checked_div(heads.max(1)).unwrap_or(0);
        self.check_offsets(offsets);
        let table = self.weight(table, &[offsets, head_dim]);
        self.nodes.push(Node::AttnApplyRelative { probs, v, out, heads, table, offsets });
        out
    }

    /// Attention scores over a backward sliding window of `band` keys, as a `[heads, T, band]`
    /// band, with the relative-position term and the `cap` logit softcap fused in.
    ///
    /// `q` and `k` are `[d_model, 1, T]`. Column `j` of query `i` is key `i - (band - 1) + j`,
    /// so only keys at or before the query are ever addressed and the columns that fall before
    /// the sequence are filled with the most negative finite fp16. A band row is therefore an
    /// ordinary softmax domain: follow this with [`Builder::softmax`], not a windowed mode.
    ///
    /// `table` is `[heads, offsets, head_dim]` — **per head**, and one-sided rather than centred
    /// on zero displacement, so [`Builder::attn_scores_relative`]'s shared centred table is a
    /// different tensor and `check_offsets` does not apply. Column `j` reads offset `j + 1`; see
    /// `nets::gemma4_audio::rel_column` for why offset 0 is unreachable and correct.
    ///
    /// `scale` multiplies the whole sum. Pass 1.0 when the caller has already scaled `q` and `k`,
    /// which Gemma 4's audio tower must: it scales the query by a scalar *and* a per-head_dim
    /// vector, and the key by a different scalar, and neither of those can live here — a vector
    /// does not factor out of a dot product, and the key's scalar applies to the content term
    /// but not to the relative one.
    pub fn attn_scores_banded(
        &mut self,
        q: Id,
        k: Id,
        heads: u32,
        band: u32,
        table: usize,
        offsets: u32,
        scale: f32,
        cap: f32,
    ) -> Id {
        let (sq, sk) = (self.shape_of(q), self.shape_of(k));
        if sq.c != sk.c || sq.w != sk.w {
            self.fail(format!(
                "banded attention over q {sq:?} and k {sk:?}: a band is a window into the same \
                 sequence, so both are [d_model, 1, T] with the same T"
            ));
        }
        if sq.h != 1 || sk.h != 1 {
            self.fail(format!(
                "banded attention on {sq:?}: a sequence is [d_model, 1, T], so a height above \
                 one would silently reinterpret the layout"
            ));
        }
        if heads == 0 || !sq.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sq.c));
        }
        if band == 0 || band > sq.w {
            self.fail(format!(
                "a band of {band} over a sequence of {}: the window is the keys a query may see, \
                 so it is between one and the whole sequence",
                sq.w
            ));
        }
        // Column `band - 1` reads offset `band`, so the table needs one more entry than the
        // band is wide. The export's is exactly that: twelve attended offsets in thirteen slots.
        if offsets <= band {
            self.fail(format!(
                "{offsets} relative offsets for a band of {band}: column j reads offset j + 1, \
                 so the widest column needs offset {band}"
            ));
        }
        if cap <= 0.0 {
            self.fail(format!("a logit cap of {cap}, which must be positive"));
        }
        let head_dim = sq.c.checked_div(heads.max(1)).unwrap_or(0);
        let table = self.weight(table, &[heads, offsets, head_dim]);
        let out = self.tensor(Shape::new(heads, sq.w, band));
        self.nodes
            .push(Node::AttnScoresBanded { q, k, out, heads, band, table, offsets, scale, cap });
        out
    }

    /// Apply a `[heads, T, band]` band of probabilities to `v`, a `[d_model, 1, T]` sequence.
    ///
    /// The value half of [`Builder::attn_scores_banded`], with the same window: column `j` of
    /// query `i` weights key `i - (band - 1) + j`. Columns that fall before the sequence are
    /// skipped rather than read, which is exact because the softmax already gave them zero.
    pub fn attn_apply_banded(&mut self, probs: Id, v: Id, heads: u32, band: u32) -> Id {
        let (sp, sv) = (self.shape_of(probs), self.shape_of(v));
        if sp.c != heads || sp.w != band {
            self.fail(format!(
                "a banded value mix over probs {sp:?}: {heads} heads and a band of {band} means \
                 [{heads}, T, {band}]"
            ));
        }
        if sv.h != 1 || sp.h != sv.w {
            self.fail(format!(
                "a banded value mix over probs {sp:?} and v {sv:?}: one row per query and one \
                 value per key, and a band's queries and keys are the same sequence"
            ));
        }
        if heads == 0 || !sv.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sv.c));
        }
        let out = self.tensor(Shape::new(sv.c, 1, sv.w));
        self.nodes.push(Node::AttnApplyBanded { probs, v, out, heads, band });
        out
    }

    /// A relative table is `2 * window + 1` entries centred on zero displacement.
    ///
    /// Only the parity is checked. The table's size is deliberately *not* related to the
    /// sequence length: for a one-phoneme utterance only the centre entry is ever reachable
    /// and the other eight go unused, which is correct rather than an error — "a" is a word.
    fn check_offsets(&mut self, offsets: u32) {
        if offsets == 0 || offsets.is_multiple_of(2) {
            self.fail(format!(
                "{offsets} relative offsets: the table is 2 * window + 1 entries centred on \
                 zero displacement, so an even count has no centre"
            ));
        }
    }

    /// Apply `probs`, a `[heads, T, T]` score map, to `v`, a `[d_model, 1, T]` sequence.
    /// [`Builder::attn_apply`] where `kv_heads` heads supply the values.
    pub fn attn_apply_grouped(&mut self, probs: Id, v: Id, heads: u32, kv_heads: u32) -> Id {
        let sv = self.shape_of(v);
        let sp = self.shape_of(probs);
        // The output is the **query** side's width: `heads * head_dim`, where V is only
        // `kv_heads * head_dim`. Taking it from V would silently produce a narrower tensor.
        let head_dim = sv.c.checked_div(kv_heads.max(1)).unwrap_or(0);
        let out = self.tensor(Shape::new(heads * head_dim, 1, sp.h));
        self.nodes.push(Node::AttnApply { probs, v, out, heads, kv_heads });
        out
    }

    pub fn attn_apply(&mut self, probs: Id, v: Id, heads: u32) -> Id {
        let out = self.mixed(probs, v, heads);
        self.nodes.push(Node::AttnApply { probs, v, out, heads, kv_heads: heads });
        out
    }

    /// The validation and output tensor every weighted sum shares, relative or not.
    ///
    /// The output is one vector per **query**, so its width comes from the score map's height
    /// rather than from `v`. For self-attention those are the same number.
    fn mixed(&mut self, probs: Id, v: Id, heads: u32) -> Id {
        let (sp, sv) = (self.shape_of(probs), self.shape_of(v));
        if sp.c != heads || sp.w != sv.w {
            self.fail(format!(
                "attention weights {sp:?} do not match {heads} heads over a sequence of \
                 {} from {sv:?}",
                sv.w
            ));
        }
        if sv.h != 1 {
            self.fail(format!("attention values {sv:?} are not [d_model, 1, T]"));
        }
        if heads == 0 || !sv.c.is_multiple_of(heads) {
            self.fail(format!("{} channels do not split into {heads} heads", sv.c));
        }
        self.tensor(Shape::new(sv.c, 1, sp.h))
    }

    /// Pack the arena and resolve every offset, with `outputs` as the result tensors.
    pub fn finish(self, outputs: &[Id]) -> Result<Plan, String> {
        // `finish` needs no file table: it resolves offsets, never indices. The
        // emitter passes the table; the plan path passes an empty stand-in the
        // recording never consults.
        let recorded = self.record(outputs, &crate::weights::Offsets::empty())?;
        Ok(recorded.plan)
    }

    /// Run the recording pipeline up to but excluding `Op` emission, for the graph
    /// emitter.
    ///
    /// `finish` is record-then-emit; the emitter needs the recorded graph (nodes with
    /// resolved weight file indices, shapes, and bindings) without the `Plan`. Split
    /// out so both share the fusion fold, the liveness, and the every-tensor rule —
    /// the emitter must see exactly the graph the plan would have been built from, or
    /// the equivalence test is circular.
    ///
    /// `offsets` is unused today: nodes already carry resolved offsets and the emitter
    /// inverts them through its own table. It stays in the signature so the recording
    /// can later carry file indices directly (see the `Recorded` docs) without
    /// changing every call site again.
    pub(crate) fn record(
        mut self,
        outputs: &[Id],
        offsets: &crate::weights::Offsets,
    ) -> Result<Recorded, String> {
        let _ = offsets;
        self.pinned.extend_from_slice(outputs);
        if let Some(e) = self.error.take() {
            return Err(e);
        }
        if self.inputs.is_empty() {
            return Err("a pass with no input".into());
        }
        if outputs.is_empty() {
            return Err("a pass with no output".into());
        }
        // Every tensor in the file must have been read, or explicitly declared unread by this
        // pass. An accidentally unread one is the shape of a forward pass that stopped early or
        // skipped a layer, which is otherwise invisible — the file loads, the pass runs, and one
        // layer convolves with whatever its neighbour's weights happen to be.
        if let Some(index) = self.read.iter().position(|&read| !read) {
            return Err(format!(
                "the forward pass never reads tensor {index} of {}. If this pass is one of \
                 several over one file, say so with `Builder::host_tensor`.",
                self.read.len()
            ));
        }

        self.fuse_elementwise(outputs);

        let plan = self.emit_all(outputs)?;
        Ok(Recorded {
            plan,
            nodes: self.nodes,
            shapes: self.shapes,
            inputs: self.inputs,
            pinned: self.pinned,
            read: self.read,
        })
    }

    /// Emit every node to `Op`s, packing the arena along the way.
    ///
    /// The second half of the old `finish`: allocate outputs before freeing inputs,
    /// resolve offsets, and build the bindings. Split out so `record` can stop before
    /// it — the emitter needs nodes and shapes, not dispatches.
    fn emit_all(&self, outputs: &[Id]) -> Result<Plan, String> {
        let last_use = self.last_use();
        let mut arena = Arena::new();
        let mut offsets: Vec<Option<u32>> = vec![None; self.shapes.len()];
        for &Id(id) in &self.pinned {
            let shape = self.shapes.get(id).copied().unwrap_or(Shape::new(0, 0, 0));
            *offsets.get_mut(id).ok_or("pinned id out of range")? =
                Some(arena.alloc(shape.len()));
        }

        let mut ops = Vec::new();
        for (step, node) in self.nodes.iter().enumerate() {
            // Allocate this node's output before freeing its inputs: an op reads and
            // writes the same buffer, so overlapping them would be a data race that
            // no barrier can fix.
            let out = node.out();
            if offsets.get(out.0).copied().flatten().is_none() {
                let shape = self.shapes.get(out.0).copied().ok_or("output id out of range")?;
                *offsets.get_mut(out.0).ok_or("output id out of range")? =
                    Some(arena.alloc(shape.len()));
            }

            let at = |id: Id| -> Result<u32, String> {
                offsets
                    .get(id.0)
                    .copied()
                    .flatten()
                    .ok_or_else(|| format!("step {step} reads tensor {} before it is written", id.0))
            };
            let shape = |id: Id| -> Shape {
                self.shapes.get(id.0).copied().unwrap_or(Shape::new(0, 0, 0))
            };
            self.emit(node, &at, &shape, &mut ops)?;

            for (id, &last) in last_use.iter().enumerate() {
                if last == Some(step) && !self.pinned.contains(&Id(id)) {
                    if let Some(offset) = offsets.get(id).copied().flatten() {
                        let len = self.shapes.get(id).map(|s| s.len()).unwrap_or(0);
                        arena.free(offset, len);
                    }
                }
            }
        }

        let binding = |id: Id| -> Result<Binding, String> {
            let at = offsets
                .get(id.0)
                .copied()
                .flatten()
                .ok_or_else(|| format!("tensor {} was never allocated", id.0))?;
            let shape = self
                .shapes
                .get(id.0)
                .copied()
                .ok_or_else(|| format!("tensor {} has no shape", id.0))?;
            Ok(Binding { at, shape })
        };
        Ok(Plan {
            ops,
            arena_elems: arena.high_water,
            inputs: self.inputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            pinned: self.pinned.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
            outputs: outputs.iter().map(|&id| binding(id)).collect::<Result<_, _>>()?,
        })
    }

    /// Fold single-consumer elementwise adds into their producing convolution.
    ///
    /// A ConvNeXt block is five dispatches — depthwise conv, layer norm, widening pointwise
    /// with GELU, narrowing pointwise, residual add — and Supertonic's sampler holds 28 of
    /// them. The last of the five only adds two tensors that already sit in cache, so the
    /// producing convolution stores `activate(acc + bias) + residual` directly and the add
    /// never becomes a dispatch. Same for the timestep `AddBroadcast`: it adds one value per
    /// channel, so the store adds `arena[shift + channel]` alongside.
    ///
    /// Runs to fixpoint because the two chain: the residual add's output feeds the timestep
    /// shift, so the first iteration folds the add into the convolution and the second folds
    /// the shift into the same store. Runs before [`Builder::last_use`] so liveness, the
    /// arena packing and [`Kind::arena_reads`] all see the folded graph rather than the
    /// spelled-out one.
    ///
    /// # What can fold, and what cannot
    ///
    /// The producer must be a `Conv` or `ConvInt8` node whose output the binary is the only
    /// reader of — counted over node inputs *and* plan outputs, since an output tensor has to
    /// survive even when nothing downstream reads it. Anything else (a second reader, a plan
    /// output, a non-convolution producer) keeps the add as its own op, which is always
    /// correct and merely one dispatch.
    ///
    /// Only `Add` and `AddBroadcast` fold. A fused multiply would have to round differently
    /// from the unfused pair, and everything else elementwise in these nets already rides in
    /// the convolution's own activation.
    fn fuse_elementwise(&mut self, outputs: &[Id]) {
        loop {
            if !self.fuse_one_elementwise(outputs) {
                return;
            }
        }
    }

    /// One fold, or `false` when no binary qualifies.
    fn fuse_one_elementwise(&mut self, outputs: &[Id]) -> bool {
        let folded = (0..self.nodes.len()).find_map(|i| {
            // The producer's index, the tensor it produced, the addend that folds, and
            // the binary's own output, which the sum moves onto. All `Copy`, so the
            // borrow of `self.nodes` ends here.
            let (index, produced, residual, shift, out) = match &self.nodes[i] {
                Node::Binary { kind: Kind::Add, a, b, out } => {
                    if let Some(index) = producer_of(&self.nodes, *a) {
                        (index, *a, Some(*b), None, *out)
                    } else if let Some(index) = producer_of(&self.nodes, *b) {
                        (index, *b, Some(*a), None, *out)
                    } else {
                        return None;
                    }
                }
                Node::Binary { kind: Kind::AddBroadcast, a, b, out } => {
                    // `add_channel(a, b)` validates `b` as the `C x 1 x 1` shift, so the
                    // producer side is always `a`.
                    let index = producer_of(&self.nodes, *a)?;
                    (index, *a, None, Some(*b), *out)
                }
                _ => return None,
            };
            // A self-add (`add(x, x)`) would fold into a convolution that reads the very
            // tensor it no longer writes — the old output has no writer once the fold
            // moves it. Refused rather than reasoned about: no net builds one.
            if residual == Some(produced) || shift == Some(produced) {
                return None;
            }
            // The addend must already exist when the producer runs. It always does in a
            // residual — unless the "skip" side is itself downstream of the producer. A
            // producer that (transitively) reads the addend would, after the fold, read
            // a tensor whose writer moved downstream of it: a read-before-write the
            // allocator cannot see, since it allocates in node order. SCRFD's neck does
            // exactly this (`add(p4, upsample(p5))` where `p5`'s lateral is the later
            // node). Refused by dataflow: the addend may only depend on nodes strictly
            // before the producer. Inputs and host tensors depend on nothing, so they
            // always qualify.
            let addend_ready = |id: Id| {
                let mut seen = vec![false; self.nodes.len()];
                let mut stack = vec![id];
                while let Some(next) = stack.pop() {
                    // No producing node: an input or a host tensor, written before the
                    // pass runs, so always ready.
                    let Some(node_index) =
                        self.nodes.iter().position(|node| node.out() == next)
                    else {
                        continue;
                    };
                    if node_index >= index {
                        return false;
                    }
                    if seen[node_index] {
                        continue;
                    }
                    seen[node_index] = true;
                    stack.extend(self.nodes[node_index].inputs());
                }
                true
            };
            if !residual.is_none_or(addend_ready) || !shift.is_none_or(addend_ready) {
                return None;
            }
            // The producer's output must reach exactly this binary: a second reader, or
            // the plan holding it as an output, keeps the add unfolded.
            let single = self.nodes.iter().enumerate()
                .filter(|(j, _)| *j != index && *j != i)
                .all(|(_, node)| !node.inputs().contains(&produced))
                && !outputs.contains(&produced);
            single.then_some((i, index, residual, shift, out))
        });
        let Some((i, index, residual, shift, out)) = folded else {
            return false;
        };
        // The output tensor moves onto the convolution: it stores the sum directly, and
        // the producer's old output — now unread by anything — is never allocated.
        match &mut self.nodes[index] {
            Node::Conv { out: conv_out, res, shift: fused_shift, .. } => {
                *conv_out = out;
                if residual.is_some() {
                    *res = residual;
                }
                if shift.is_some() {
                    *fused_shift = shift;
                }
            }
            Node::ConvInt8 { out: conv_out, res, shift: fused_shift, .. } => {
                *conv_out = out;
                if residual.is_some() {
                    *res = residual;
                }
                if shift.is_some() {
                    *fused_shift = shift;
                }
            }
            // `producer_of` only returns convolution nodes, so reaching this means the
            // predicate and the application disagree — a bug, and folding nothing is the
            // safe side of it.
            _ => return false,
        }
        self.nodes.remove(i);
        true
    }

    /// For each tensor, the last step that reads it, or `None` if nothing does.
    fn last_use(&self) -> Vec<Option<usize>> {
        let mut last = vec![None; self.shapes.len()];
        for (step, node) in self.nodes.iter().enumerate() {
            for Id(id) in node.inputs() {
                if let Some(slot) = last.get_mut(id) {
                    *slot = Some(step);
                }
            }
        }
        last
    }

    /// The arena offset of a folded addend, or [`NO_FUSE`] when there is none.
    ///
    /// `None` is the common case — only [`Builder::finish`]'s fusion fold sets these — and it
    /// must resolve without touching the allocator, since "no addend" is not "the tensor at 0".
    fn fuse_offset(
        id: Option<Id>,
        at: &dyn Fn(Id) -> Result<u32, String>,
    ) -> Result<u32, String> {
        match id {
            Some(id) => at(id),
            None => Ok(NO_FUSE),
        }
    }

    fn emit(
        &self,
        node: &Node,
        at: &dyn Fn(Id) -> Result<u32, String>,
        shape: &dyn Fn(Id) -> Shape,
        ops: &mut Vec<Op>,
    ) -> Result<(), String> {
        match node {
            Node::ConvInt8 {
                input,
                out,
                weight,
                scale,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                quant,
                res,
                shift,
            } => {
                let (si, so) = (shape(*input), shape(*out));
                // The same test `Node::Conv` applies below, less the two cases that cannot arise
                // here: there is no transposed int8 convolution, and `Builder::conv_int8` refuses
                // `Act::PRelu` outright because the scale occupies the offset a slope would need.
                //
                // It matters more here than it does there: Supertonic's sampler is 92% pointwise
                // by parameter count and runs `2 * STEPS` times an utterance, so leaving it on the
                // untiled shader would cost far more time than int8 saves space.
                let tiled = *group == 1
                    && kernel == &(1, 1)
                    && stride == &(1, 1)
                    && pad == &(0, 0)
                    && si.h == so.h
                    && si.w == so.w;
                let positions = so.h * so.w;
                // At one position the tiled shader pads 15 of every 16 columns and stores from 8 of
                // every 64 invocations, so a single-position pointwise convolution goes to the gemv
                // shader instead. It is the whole of a SMaLL-100 decode step.
                let vector = tiled && positions == 1;
                let tiles = so.c.div_ceil(CONV_POINT_TILE) * positions.div_ceil(CONV_POINT_TILE);
                // One workgroup per row-group: 2 channels for int8, 8 for int4 — the
                // two gemv shaders diverged, so each kind counts its own rows.
                let rows = match quant {
                    Quant::I8 => so.c.div_ceil(CONV_VEC_ROWS),
                    Quant::I4 => so.c.div_ceil(CONV_VEC_INT4_ROWS),
                };
                let kind = match (quant, tiled, vector) {
                    (Quant::I8, _, true) => Kind::ConvVecInt8,
                    (Quant::I8, true, false) => Kind::ConvPointInt8,
                    (Quant::I8, false, false) => Kind::ConvInt8,
                    (Quant::I4, _, true) => Kind::ConvVecInt4,
                    (Quant::I4, true, false) => Kind::ConvPointInt4,
                    (Quant::I4, false, false) => {
                        return Err(format!(
                            "an int4 convolution with a {}x{} kernel or {group} groups: only the \
                             two 1x1 lowerings are quantised to four bits, because the padded \
                             and grouped shader has no int4 counterpart",
                            kernel.0, kernel.1
                        ))
                    }
                };
                // Workgroups for the staged kinds, output elements for the untiled one.
                let count = match kind {
                    Kind::ConvVecInt8 | Kind::ConvVecInt4 => rows,
                    Kind::ConvPointInt8 | Kind::ConvPointInt4 => tiles,
                    _ => so.len(),
                };
                ops.push(Op::Dispatch {
                    kind,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        // A word offset, not an fp16 one: int8 is unpacked through the
                        // 32-bit view of the weights buffer.
                        weight: *weight,
                        bias: *bias,
                        // The dequantisation scale rides in the field `PRelu` would use,
                        // which is why the two cannot be combined.
                        act_weight: *scale,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // Neither staged shader reads these, unlike `Kind::ConvPoint`'s push block,
                        // which leaves them at zero. They are filled in either way so that
                        // `nets::reference` can serve all three kinds from one `conv_int8`, whose
                        // arithmetic is identical once the geometry above holds.
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        dil_h: dilation.0,
                        dil_w: dilation.1,
                        pad_t: pad.0,
                        pad_l: pad.1,
                        group: *group,
                        act: act.code(),
                        count,
                        res: Self::fuse_offset(*res, &at)?,
                        shift: Self::fuse_offset(*shift, &at)?,
                        ..Push::default()
                    },
                    // One workgroup of 64 per unit for the staged shaders; one invocation per
                    // output element for the untiled one.
                    invocations: match kind {
                        Kind::ConvVecInt8
                        | Kind::ConvPointInt8
                        | Kind::ConvVecInt4
                        | Kind::ConvPointInt4 => count * 64,
                        _ => count,
                    },
                });
            }
            Node::Conv {
                input,
                out,
                weight,
                bias,
                kernel,
                stride,
                dilation,
                pad,
                group,
                act,
                act_weight,
                transpose,
                pad_edge,
                res,
                shift,
            } => {
                let (si, so) = (shape(*input), shape(*out));
                // An ungrouped 1x1 goes to the tiled path. Its geometry is a matrix multiply
                // over `out_h * out_w` positions, so stride, dilation and padding are all
                // trivially identity and nothing else in the push block changes meaning.
                let tiled = !*transpose
                    && *group == 1
                    && kernel == &(1, 1)
                    && stride == &(1, 1)
                    && pad == &(0, 0)
                    && si.h == so.h
                    && si.w == so.w
                    && !matches!(act, Act::PRelu(_));
                if tiled {
                    let positions = so.h * so.w;
                    let tiles =
                        so.c.div_ceil(CONV_POINT_TILE) * positions.div_ceil(CONV_POINT_TILE);
                    ops.push(Op::Dispatch {
                        kind: Kind::ConvPoint,
                        push: Push {
                            in0: at(*input)?,
                            out: at(*out)?,
                            weight: *weight,
                            bias: *bias,
                            in_c: si.c,
                            in_h: si.h,
                            in_w: si.w,
                            out_c: so.c,
                            out_h: so.h,
                            out_w: so.w,
                            act: act.code(),
                            // Tiles, not elements: one workgroup per tile.
                            count: tiles,
                            res: Self::fuse_offset(*res, &at)?,
                            shift: Self::fuse_offset(*shift, &at)?,
                            ..Push::default()
                        },
                        // 64 invocations a workgroup, so this asks for exactly `tiles` of them.
                        invocations: tiles * 64,
                    });
                    return Ok(());
                }
                ops.push(Op::Dispatch {
                    kind: if *transpose { Kind::ConvTranspose } else { Kind::Conv },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *weight,
                        bias: *bias,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        dil_h: dilation.0,
                        dil_w: dilation.1,
                        pad_t: pad.0,
                        pad_l: pad.1,
                        pad_edge: u32::from(*pad_edge),
                        group: *group,
                        act: act.code(),
                        act_weight: *act_weight,
                        count: so.len(),
                        res: Self::fuse_offset(*res, &at)?,
                        shift: Self::fuse_offset(*shift, &at)?,
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::MaxPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::MaxPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AvgPool { input, out, kernel, stride } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: kernel.0,
                        kw: kernel.1,
                        stride_h: stride.0,
                        stride_w: stride.1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Resize { input, out, nearest } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: if *nearest { Kind::ResizeNearest } else { Kind::Resize },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::GlobalAvgPool { input, out } => {
                let (si, so) = (shape(*input), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::GlobalAvgPool,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: 1,
                        out_w: 1,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Binary { kind, a, b, out } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: *kind,
                    push: Push {
                        in0: at(*a)?,
                        in1: at(*b)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Concat { parts, out } => {
                let base = at(*out)?;
                let mut written = 0;
                for &part in parts {
                    let len = shape(part).len();
                    ops.push(Op::Copy {
                        src: at(part)?,
                        dst: base + written,
                        elems: len,
                    });
                    written += len;
                }
            }
            Node::Affine { input, out, scale, shift } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Affine,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: scale.to_bits(),
                        param1_bits: shift.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::LayerNorm { input, out, gamma, beta, epsilon } => {
                let so = shape(*out);
                // One invocation per position, each reducing over the channels, so the
                // dispatch is the spatial extent rather than the element count.
                let positions = so.h * so.w;
                ops.push(Op::Dispatch {
                    kind: Kind::LayerNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *gamma,
                        bias: *beta,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param1_bits: epsilon.to_bits(),
                        count: positions,
                        ..Push::default()
                    },
                    invocations: positions,
                });
            }
            Node::RmsNorm { input, out, gamma, epsilon, groups } => {
                let so = shape(*out);
                // One invocation per group per position, reducing over that group's channels.
                let positions = so.h * so.w * groups.max(&1);
                ops.push(Op::Dispatch {
                    kind: Kind::RmsNorm,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        weight: *gamma,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *groups,
                        param1_bits: epsilon.to_bits(),
                        count: positions,
                        ..Push::default()
                    },
                    invocations: positions,
                });
            }
            Node::AttnScores { q, k, out, heads, kv_heads, scale } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScores,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        kv_heads: *kv_heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnScoresCached { q, cache, out, heads, kv_heads, scale, dynamic, sliding } => {
                let (sq, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresCached,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*cache)?,
                        out: at(*out)?,
                        // `d_model`, which doubles as the cache's per-position stride: a cache is
                        // `[keys, 1, d_model]`, so one position is `in_c` elements.
                        in_c: sq.c,
                        in_h: 1,
                        in_w: 1,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        dyn_keys: u32::from(*dynamic),
                        kv_heads: *kv_heads,
                        sliding: u32::from(*sliding),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnApplyCached { probs, cache, out, heads, kv_heads, dynamic, sliding } => {
                let (sc, so) = (shape(*cache), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyCached,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*cache)?,
                        out: at(*out)?,
                        // As above: the cache's stride is `d_model`, which is also the output's
                        // channel count because attention preserves the width.
                        in_c: so.c,
                        in_h: 1,
                        // The key stride, which is the cache's *channel* count in this layout.
                        // When `dynamic`, only the leading `prefix + 1` of them are summed.
                        in_w: sc.c,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        count: so.len(),
                        dyn_keys: u32::from(*dynamic),
                        kv_heads: *kv_heads,
                        sliding: u32::from(*sliding),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnScoresRelative { q, k, out, heads, scale, table, offsets } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresRelative,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // The offset count reads as a kernel width, because that is what a
                        // band of `2 * window + 1` taps along the sequence is.
                        kw: *offsets,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnApplyRelative { probs, v, out, heads, table, offsets } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyRelative,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kw: *offsets,
                        group: *heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnScoresBanded { q, k, out, heads, band, table, offsets, scale, cap } => {
                let (si, so) = (shape(*q), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnScoresBanded,
                    push: Push {
                        in0: at(*q)?,
                        in1: at(*k)?,
                        out: at(*out)?,
                        weight: *table,
                        in_c: si.c,
                        in_h: si.h,
                        in_w: si.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        // The band reads as a kernel height and the offset count as its width:
                        // a window of taps along the sequence is what both of them are.
                        kh: *band,
                        kw: *offsets,
                        group: *heads,
                        param0_bits: scale.to_bits(),
                        param1_bits: cap.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::AttnApplyBanded { probs, v, out, heads, band } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApplyBanded,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        kh: *band,
                        group: *heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::SliceChannels { input, out, start } => {
                let so = shape(*out);
                // A channel range is contiguous, so this is one element-range move.
                ops.push(Op::Copy {
                    src: at(*input)? + start * so.h * so.w,
                    dst: at(*out)?,
                    elems: so.len(),
                });
            }
            Node::Embed { ids, out, table, rows } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Embed,
                    push: Push {
                        in0: at(*ids)?,
                        out: at(*out)?,
                        weight: *table,
                        // The table's row count, not the id tensor's extent: the shader
                        // clamps against it so an unknown symbol mispronounces a word
                        // rather than reading whatever follows the embedding.
                        in_w: *rows,
                        // Id lanes, 1 or 2. Not the output's channel count.
                        in_c: shape(*ids).c,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::CacheWrite { row, cache } => {
                let (sr, sc) = (shape(*row), shape(*cache));
                ops.push(Op::Dispatch {
                    kind: Kind::CacheWrite,
                    push: Push {
                        in0: at(*row)?,
                        out: at(*cache)?,
                        // The distance between cache rows, and the length of the one written.
                        in_c: sc.w,
                        // Positions the cache can hold, so a step past the end writes nothing.
                        in_h: sc.c,
                        in_w: 1,
                        // The cache's real dimensions: their product is the region
                        // `assert_no_aliasing` takes this op to write.
                        out_c: sc.c,
                        out_h: sc.h,
                        out_w: sc.w,
                        // Positions written at once. More than one is a prefill, whose K and V
                        // are channel-major and need transposing into the cache.
                        group: sr.len() / sc.w.max(1),
                        count: sr.len(),
                        ..Push::default()
                    },
                    invocations: sr.len(),
                });
            }
            Node::Clamp { input, out, bounds } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Clamp,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        act_weight: *bounds,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::MulScalar { input, out, scale } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::MulScalar,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        act_weight: *scale,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Activate { input, out, act } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Activate,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        act: act.code(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::GatedActivate { input, out, act } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::GatedActivate,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        // The distance from a gate element to its up partner: the whole gate
                        // half, which is the output's element count.
                        in_c: so.len(),
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        act: act.code(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Softcap { input, out, cap } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Softcap,
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        param0_bits: cap.to_bits(),
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Softmax { input, out, mode, sliding, window } => {
                let so = shape(*out);
                // One invocation per row of the last axis, each normalising `out_w`
                // contiguous elements, so the dispatch is rows rather than elements.
                let rows = so.c * so.h;
                ops.push(Op::Dispatch {
                    kind: match mode {
                        SoftmaxMode::Full => Kind::Softmax,
                        SoftmaxMode::Causal => Kind::SoftmaxCausal,
                        SoftmaxMode::Prefix => Kind::SoftmaxPrefix,
                    },
                    push: Push {
                        in0: at(*input)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        in_w: so.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: rows,
                        dyn_keys: u32::from(*mode == SoftmaxMode::Prefix),
                        sliding: u32::from(*sliding),
                        // Only the causal shader reads it, and only when non-zero.
                        kh: *window,
                        ..Push::default()
                    },
                    invocations: rows,
                });
            }
            Node::ConcatPositions { parts, out } => {
                let so = shape(*out);
                let base = at(*out)?;
                let mut column = 0;
                for &part in parts {
                    let sp = shape(part);
                    let src = at(part)?;
                    // A part is a column range, so one run per channel row rather than the single
                    // copy `Node::Concat` gets away with.
                    for row in 0..sp.c * sp.h {
                        ops.push(Op::Copy {
                            src: src + row * sp.w,
                            dst: base + row * so.w + column,
                            elems: sp.w,
                        });
                    }
                    column += sp.w;
                }
            }
            Node::AttnApply { probs, v, out, heads, kv_heads } => {
                let (sv, so) = (shape(*v), shape(*out));
                ops.push(Op::Dispatch {
                    kind: Kind::AttnApply,
                    push: Push {
                        in0: at(*probs)?,
                        in1: at(*v)?,
                        out: at(*out)?,
                        in_c: so.c,
                        in_h: so.h,
                        // The **key** count, which is V's length. `out_w` is the query count,
                        // and for a cross-attention those differ.
                        in_w: sv.w,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        kv_heads: *kv_heads,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Rotary { input, angles, out, heads, axes } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Rotary,
                    push: Push {
                        in0: at(*input)?,
                        in1: at(*angles)?,
                        out: at(*out)?,
                        // The head width, which is what the frequency index wraps on. Not the
                        // channel count.
                        in_c: so.c / heads.max(&1),
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        group: *heads,
                        rope_axes: *axes,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
            Node::Constant { out, weight } => {
                let so = shape(*out);
                ops.push(Op::Dispatch {
                    kind: Kind::Constant,
                    push: Push {
                        out: at(*out)?,
                        weight: *weight,
                        out_c: so.c,
                        out_h: so.h,
                        out_w: so.w,
                        count: so.len(),
                        ..Push::default()
                    },
                    invocations: so.len(),
                });
            }
        }
        Ok(())
    }
}

impl Node {
    pub(crate) fn out(&self) -> Id {
        match self {
            Node::Conv { out, .. }
            | Node::MaxPool { out, .. }
            | Node::AvgPool { out, .. }
            | Node::Resize { out, .. }
            | Node::GlobalAvgPool { out, .. }
            | Node::Binary { out, .. }
            | Node::Affine { out, .. }
            | Node::LayerNorm { out, .. }
            | Node::RmsNorm { out, .. }
            | Node::AttnScores { out, .. }
            | Node::AttnScoresCached { out, .. }
            | Node::AttnApplyCached { out, .. }
            | Node::AttnScoresRelative { out, .. }
            | Node::AttnApplyRelative { out, .. }
            | Node::AttnScoresBanded { out, .. }
            | Node::AttnApplyBanded { out, .. }
            | Node::Softmax { out, .. }
            | Node::Embed { out, .. }
            | Node::SliceChannels { out, .. }
            | Node::ConvInt8 { out, .. }
            | Node::AttnApply { out, .. }
            | Node::Constant { out, .. }
            | Node::Rotary { out, .. }
            | Node::ConcatPositions { out, .. }
            | Node::Concat { out, .. } => *out,
            | Node::Softcap { out, .. } => *out,
            | Node::Activate { out, .. }
            | Node::GatedActivate { out, .. } => *out,
            | Node::MulScalar { out, .. } => *out,
            | Node::Clamp { out, .. } => *out,
            // The cache is the destination, and it is pinned, so `finish` finds it already
            // allocated rather than making a fresh tensor for it.
            Node::CacheWrite { cache, .. } => *cache,
        }
    }

    fn inputs(&self) -> Vec<Id> {
        match self {
            Node::Conv { input, res, shift, .. } => {
                let mut reads = vec![*input];
                reads.extend(res.iter().copied());
                reads.extend(shift.iter().copied());
                reads
            }
            Node::ConvInt8 { input, res, shift, .. } => {
                let mut reads = vec![*input];
                reads.extend(res.iter().copied());
                reads.extend(shift.iter().copied());
                reads
            }
            Node::MaxPool { input, .. }
            | Node::AvgPool { input, .. }
            | Node::Resize { input, .. }
            | Node::Affine { input, .. }
            | Node::LayerNorm { input, .. }
            | Node::RmsNorm { input, .. }
            | Node::Softmax { input, .. }
            | Node::Softcap { input, .. }
            | Node::GatedActivate { input, .. }
            | Node::Activate { input, .. }
            | Node::MulScalar { input, .. }
            | Node::Clamp { input, .. }
            | Node::Embed { ids: input, .. }
            | Node::SliceChannels { input, .. }
            | Node::GlobalAvgPool { input, .. } => vec![*input],
            Node::Binary { a, b, .. } => vec![*a, *b],
            Node::Rotary { input, angles, .. } => vec![*input, *angles],
            Node::AttnScores { q: a, k: b, .. }
            | Node::AttnScoresCached { q: a, cache: b, .. }
            | Node::AttnApplyCached { probs: a, cache: b, .. }
            | Node::AttnApply { probs: a, v: b, .. }
            | Node::AttnScoresRelative { q: a, k: b, .. }
            | Node::AttnScoresBanded { q: a, k: b, .. }
            | Node::AttnApplyBanded { probs: a, v: b, .. }
            | Node::AttnApplyRelative { probs: a, v: b, .. } => {
                vec![*a, *b]
            }
            Node::Concat { parts, .. } => parts.clone(),
            Node::ConcatPositions { parts, .. } => parts.clone(),
            // The cache is listed alongside the row so that neither can be freed under this op.
            // The cache is pinned and so was never a candidate, but saying it here keeps the
            // dependency visible to `last_use` rather than relying on that.
            Node::CacheWrite { row, cache } => vec![*row, *cache],
            // The only op with no arena input at all: it reads the weights file.
            Node::Constant { .. } => Vec::new(),
        }
    }
}

/// The convolution node that wrote `id`, if one did.
///
/// `finish`'s fusion fold only folds into convolutions — the op whose store does the adding —
/// so this is the predicate that names a foldable producer. Anything else (`None`) keeps the
/// binary as its own op.
fn producer_of(nodes: &[Node], id: Id) -> Option<usize> {
    nodes.iter().position(|node| {
        matches!(node, Node::Conv { .. } | Node::ConvInt8 { .. }) && node.out() == id
    })
}

/// `floor((in + pad - dilation * (k - 1) - 1) / stride) + 1`, ONNX's convolution
/// output size.
fn conv_out(input: u32, kernel: u32, stride: u32, dilation: u32, pad_total: u32) -> u32 {
    let effective = dilation * (kernel - 1) + 1;
    let padded = input + pad_total;
    if padded < effective || stride == 0 {
        return 0;
    }
    (padded - effective) / stride + 1
}

/// `(in - 1) * stride + k - pad`, ONNX's transposed-convolution output size at
/// `dilation = 1` and no `output_padding` — which is the only form used.
fn deconv_out(input: u32, kernel: u32, stride: u32, pad_total: u32) -> u32 {
    let full = (input.max(1) - 1) * stride + kernel;
    full.saturating_sub(pad_total)
}

/// A first-fit free-list allocator over the activation arena.
///
/// The arena is one `VkBuffer`, so an "allocation" is an element offset into it. The
/// list is kept sorted and coalesced, which for the few hundred allocations either
/// net makes is far cheaper than the memory it saves: U^2-Netp's live set peaks well
/// below the sum of its intermediates, and holding them all would be tens of MB of
/// device memory for a net that reuses almost everything.
struct Arena {
    free: Vec<(u32, u32)>,
    high_water: u32,
}

impl Arena {
    fn new() -> Arena {
        Arena { free: Vec::new(), high_water: 0 }
    }

    fn alloc(&mut self, len: u32) -> u32 {
        let len = round_up(len.max(1));
        // Best fit. Measured against first fit on both real networks it makes no
        // difference to the high-water mark — U^2-Netp lands on 76 MiB either way — but it
        // is the better default for the shape of these allocations, which range from 64
        // elements to 6.5M, and it costs a linear scan of a list that never exceeds a
        // few dozen entries.
        //
        // The remaining ~30% over the true live set is fragmentation that no fit policy
        // fixes: a freed 13 MiB tensor gets split for a 6.6 MiB request and the halves
        // are then too small for the next 13 MiB one. Closing it needs the graph
        // changed rather than the allocator — see the note on memory in
        // `nets::u2netp`.
        let mut best: Option<usize> = None;
        for (i, &(_, size)) in self.free.iter().enumerate() {
            if size >= len && best.is_none_or(|b| self.free.get(b).is_some_and(|&(_, s)| size < s)) {
                best = Some(i);
            }
        }
        if let Some(i) = best {
            if let Some(&(start, size)) = self.free.get(i) {
                if size == len {
                    let _ = self.free.remove(i);
                } else if let Some(slot) = self.free.get_mut(i) {
                    *slot = (start + len, size - len);
                }
                return start;
            }
        }
        let start = self.high_water;
        self.high_water += len;
        start
    }

    fn free(&mut self, offset: u32, len: u32) {
        let len = round_up(len.max(1));
        let at = self.free.partition_point(|&(start, _)| start < offset);
        self.free.insert(at, (offset, len));
        // Coalesce with both neighbours, so a net that frees a run of equal-sized
        // tensors gets one big block back rather than a fragmented list.
        let mut i = 0;
        while i + 1 < self.free.len() {
            let (a_start, a_len) = self.free.get(i).copied().unwrap_or((0, 0));
            let (b_start, b_len) = self.free.get(i + 1).copied().unwrap_or((0, 0));
            if a_start + a_len == b_start {
                if let Some(slot) = self.free.get_mut(i) {
                    *slot = (a_start, a_len + b_len);
                }
                let _ = self.free.remove(i + 1);
            } else {
                i += 1;
            }
        }
    }
}

fn round_up(len: u32) -> u32 {
    len.div_ceil(ALIGN_ELEMS) * ALIGN_ELEMS
}

#[cfg(test)]
pub(crate) mod tests {

    /// `CONV_VEC_ROWS` must equal `ROWS` in the int8 gemv shader.
    ///
    /// The same number is declared twice across two languages and nothing connects them:
    /// Rust uses it to decide how many workgroups to dispatch, the shader to decide which
    /// channels a workgroup owns. Disagreeing does not fail to build - it dispatches too few
    /// workgroups and leaves most output channels never written, which reads as a plausible
    /// wrong answer rather than an error. That happened while tuning occupancy, and only the
    /// device parity fixtures caught it.
    ///
    /// The int4 gemv shader is deliberately not in this list: it runs `ROWS` 8 for the
    /// stream reason its header gives, and its workgroup count comes from
    /// [`CONV_VEC_INT4_ROWS`], checked by the test below it.
    #[test]
    fn the_gemv_row_count_matches_both_shaders() {
        assert_eq!(
            gemv_rows_of("conv_vec_int8.comp"),
            CONV_VEC_ROWS,
            "the int8 gemv shader and Rust disagree on channels per workgroup"
        );
    }

    /// `CONV_VEC_INT4_ROWS` must equal `ROWS` in the int4 gemv shader.
    ///
    /// The same agreement as the int8 test above, for the shader that diverged to 8:
    /// Rust dispatches `out / 8` workgroups and the shader owns 8 channels each. At 8
    /// the failure mode is the same — silent undispatch — and the shared-memory
    /// partials are also sized by it, so a mismatch corrupts the reduction too.
    #[test]
    fn the_int4_gemv_row_count_matches_its_shader() {
        assert_eq!(
            gemv_rows_of("conv_vec_int4.comp"),
            CONV_VEC_INT4_ROWS,
            "the int4 gemv shader and Rust disagree on channels per workgroup"
        );
    }
    use super::*;

    /// A [`WeightSource`] that knows only shapes.
    ///
    /// It hands back the tensor index as the offset, which is meaningless as an
    /// address but makes the plan reproducible, and it records every shape it was
    /// asked for so a test can assert the whole ordered layer table without a
    /// `.maml`.
    pub struct Shapes {
        pub asked: std::cell::RefCell<Vec<(usize, Vec<u32>)>>,
        pub count: usize,
    }

    impl Shapes {
        pub fn new(count: usize) -> Shapes {
            Shapes { asked: std::cell::RefCell::new(Vec::new()), count }
        }
    }

    impl WeightSource for Shapes {
        fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
            self.asked.borrow_mut().push((index, dims.to_vec()));
            Ok(index as u32)
        }
        fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
            self.asked.borrow_mut().push((index, dims.to_vec()));
            Ok(index as u32)
        }

        fn count(&self) -> usize {
            self.count
        }
    }

    /// A dispatch kind''s name, with `Conv`''s tiled lowerings folded back into the graph op.
    ///
    /// The op-inventory tests state what a network contains. Whether an ungrouped `1 x 1` is
    /// served by `conv.comp` or the tiled `conv_point.comp` is a lowering decision that those
    /// tests should not see, and folding it here keeps the assertions readable as counts of
    /// convolutions rather than counts of shaders. The int8 pair folds the same way.
    pub fn name_of(kind: super::Kind) -> String {
        match kind {
            super::Kind::ConvPoint => "Conv".to_string(),
            // Both staged int8 lowerings are the same graph op as the untiled one. Which shader
            // serves a `1 x 1` is a lowering decision the op-inventory tests should not see.
            super::Kind::ConvPointInt8 | super::Kind::ConvVecInt8 => "ConvInt8".to_string(),
            other => format!("{other:?}"),
        }
    }

    /// Assert that no op reads a region of the arena that it also writes.
    ///
    /// Every op reads and writes the same `VkBuffer`, and a convolution invocation reads
    /// many elements to write one, so a producer whose output overlapped its own input
    /// would be a data race that no barrier can fix and no test of the *output* would
    /// catch — it would just make the mask slightly wrong, differently on each driver.
    ///
    /// [`Builder::finish`] prevents it by allocating a node's output before freeing its
    /// inputs. This is the check that it worked, run against both real networks.
    pub fn assert_no_aliasing(plan: &Plan) {
        let disjoint = |a: (u32, u32), b: (u32, u32)| a.0 + a.1 <= b.0 || b.0 + b.1 <= a.0;
        for (step, op) in plan.ops.iter().enumerate() {
            let (out, reads) = match *op {
                Op::Copy { src, dst, elems } => ((dst, elems), vec![(src, elems)]),
                Op::Dispatch { kind, push, .. } => {
                    // The one table, in `Kind::arena_reads`. This assertion is the weaker of its
                    // two consumers - it only asks whether the reads overlap the write - but
                    // sharing it is what keeps `schedule`, which asks the stronger question,
                    // honest against every shipping net.
                    let reads = match kind.arena_reads(&push) {
                        Reads::Ranges(ranges) => ranges,
                        // Nothing to assert about a kind that has not been audited. It is not a
                        // failure here; `schedule` is where it costs something.
                        Reads::Unknown => continue,
                    };
                    let written = push.out_c * push.out_h * push.out_w;
                    ((push.out, written), reads)
                }
            };
            for read in reads {
                assert!(
                    disjoint(out, read),
                    "step {step} writes {}..{} and reads {}..{}",
                    out.0,
                    out.0 + out.1,
                    read.0,
                    read.0 + read.1,
                );
            }
        }
    }

    #[test]
    fn the_push_block_is_inside_the_guaranteed_limit() {
        // 128 bytes is the minimum `maxPushConstantsSize` the spec requires, so
        // staying under it means no device can reject this.
        assert!(
            std::mem::size_of::<Push>() <= 128,
            "{} bytes exceeds the guaranteed 128",
            std::mem::size_of::<Push>()
        );
    }

    #[test]
    fn the_push_block_has_no_padding() {
        // The shaders read it at fixed offsets, so a gap Rust inserted would shift
        // every field after it.
        assert_eq!(std::mem::size_of::<Push>(), 32 * 4);
        assert_eq!(std::mem::align_of::<Push>(), 4);
        // Vulkan only guarantees 128 bytes of push constants, so this is the ceiling the
        // block has to stay under however many modes get added to it.
        assert!(std::mem::size_of::<Push>() <= 128, "{}", std::mem::size_of::<Push>());
    }

    #[test]
    fn the_fused_addend_fields_default_to_opted_out() {
        // `..Push::default()` is how every non-convolution op is built. Offset 0 is the
        // first input tensor, so a derived default of 0 would fuse a live addend into
        // every one of them; the manual default must say [`NO_FUSE`] instead.
        let push = Push::default();
        assert_eq!(push.res, NO_FUSE);
        assert_eq!(push.shift, NO_FUSE);
    }

    #[test]
    fn conv_output_sizes_match_onnx() {
        // The selfie net's first layer: 256 -> 128 with asymmetric pads [0, 0, 1, 1], so
        // one row and column of padding in total.
        assert_eq!(conv_out(256, 3, 2, 1, 1), 128);
        // Its 5x5 stride-2 depthwise, pads [1,1,2,2]: 32 -> 16.
        assert_eq!(conv_out(32, 5, 2, 1, 1 + 2), 16);
        // U^2-Netp's dilated 3x3s, where pad == dilation holds the size.
        assert_eq!(conv_out(320, 3, 1, 1, 2), 320);
        assert_eq!(conv_out(20, 3, 1, 8, 16), 20);
        // 1x1, the majority of the selfie net.
        assert_eq!(conv_out(16, 1, 1, 1, 0), 16);
    }

    #[test]
    fn transposed_conv_output_size_matches_onnx() {
        // The selfie net's only ConvTranspose: 2x2 stride 2, 128 -> 256.
        assert_eq!(deconv_out(128, 2, 2, 0), 256);
    }

    #[test]
    fn the_arena_reuses_a_freed_block_rather_than_growing() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        assert_eq!((a, b), (0, 64));
        arena.free(a, 64);
        // The freed block is the first fit, so this must land back at 0 and leave the
        // high-water mark alone. If it does not, neither net's arena is bounded.
        assert_eq!(arena.alloc(64), 0);
        assert_eq!(arena.high_water, 128);
    }

    #[test]
    fn the_arena_coalesces_adjacent_frees() {
        let mut arena = Arena::new();
        let a = arena.alloc(64);
        let b = arena.alloc(64);
        let c = arena.alloc(64);
        arena.free(a, 64);
        arena.free(c, 64);
        arena.free(b, 64);
        assert_eq!(arena.free, vec![(0, 192)]);
        // All three coalesced, so a request larger than any one of them fits.
        assert_eq!(arena.alloc(192), 0);
        assert_eq!(arena.high_water, 192);
    }

    #[test]
    fn every_allocation_stays_16_byte_aligned() {
        let mut arena = Arena::new();
        // 3 elements is 6 bytes: the round-up is what keeps the next tensor aligned.
        for _ in 0..8 {
            assert_eq!(arena.alloc(3) % ALIGN_ELEMS, 0);
        }
    }

    #[test]
    fn a_wrong_weight_shape_fails_the_build() {
        struct Wrong;
        impl WeightSource for Wrong {
            fn shaped(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
                Err(format!("tensor {index} is not {dims:?}"))
            }
            fn shaped_words(&self, index: usize, dims: &[u32]) -> Result<u32, String> {
                Err(format!("tensor {index} is not {dims:?}"))
            }
            fn count(&self) -> usize {
                2
            }
        }
        let mut builder = Builder::new(&Wrong);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("bad weights");
        assert!(error.contains("tensor 0"), "{error}");
    }

    #[test]
    fn a_pass_that_leaves_tensors_unread_fails_the_build() {
        let source = Shapes::new(4);
        let mut builder = Builder::new(&source);
        let input = builder.input(Shape::new(3, 8, 8));
        let out = builder.conv_same(input, 0, 4, 3, 1, Act::Relu);
        let error = builder.finish(&[out]).expect_err("short pass");
        // Named by index, not just counted: a pass that skipped a layer in the middle
        // reads the right *number* of tensors and the wrong ones.
        assert!(error.contains("never reads tensor 2 of 4"), "{error}");
    }

    #[test]
    fn concat_lowers_to_contiguous_copies_and_no_shader() {
        let source = Shapes::new(0);
        let mut builder = Builder::new(&source);
        let a = builder.input(Shape::new(2, 2, 2));
        let b = builder.resize_to(a, 2, 2);
        let joined = builder.concat(&[a, b]);
        let plan = builder.finish(&[joined]).expect("builds");
        let copies: Vec<&Op> = plan.ops.iter().filter(|o| matches!(o, Op::Copy { .. })).collect();
        assert_eq!(copies.len(), 2);
        // The second part lands exactly one part's worth of elements after the first:
        // in NCHW a channel run is contiguous, which is the whole reason concat needs
        // no shader.
        match (copies.first(), copies.get(1)) {
            (Some(Op::Copy { dst: first, elems, .. }), Some(Op::Copy { dst: second, .. })) => {
                assert_eq!(*second, first + elems);
            }
            other => panic!("expected two copies, got {other:?}"),
        }
    }

    /// A residual add over a convolution's output folds into its store.
    ///
    /// The ConvNeXt shape: `narrowed = conv(widened)`, then `add(x, narrowed)`. One
    /// convolution dispatch and no add, with the sum stored where the add's output
    /// would have gone.
    #[test]
    fn a_single_consumer_residual_folds_into_the_producing_store() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let widened = builder.conv_same(x, 0, 8, 1, 1, Act::Relu);
        let narrowed = builder.conv_same(widened, 2, 4, 1, 1, Act::None);
        let out = builder.add(x, narrowed);
        // Tensor 4 is the file's spare: `finish` refuses an unread tensor, and this
        // pass legitimately owns only four of the five.
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(op, Op::Dispatch { kind: Kind::Add, .. })),
            "the residual add should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.res != NO_FUSE => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "exactly one fused store: {fused:?}");
        // The fused convolution writes the add's own output tensor, and reads the skip
        // side alongside its input.
        assert_eq!(fused[0].out, plan.outputs[0].at);
        // `x` is the first input, pinned at offset 0 — the case a zero sentinel could
        // not distinguish from "no residual".
        assert_eq!(fused[0].res, plan.inputs[0].at);
    }

    /// A timestep-style per-channel shift folds into the same store as the residual.
    ///
    /// `add_channel(conv_out, shift)` after the residual above: the convolution stores
    /// `activate(acc + bias) + residual + shift[channel]` in one dispatch, and neither
    /// binary survives.
    #[test]
    fn a_timestep_shift_folds_into_the_same_store() {
        let source = Shapes::new(7);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let shift_in = builder.input(Shape::new(4, 1, 1));
        let widened = builder.conv_same(x, 0, 8, 1, 1, Act::Relu);
        let narrowed = builder.conv_same(widened, 2, 4, 1, 1, Act::None);
        let residual = builder.add(x, narrowed);
        let out = builder.add_channel(residual, shift_in);
        // Tensors 4 and 5 are the file's spares; see the residual test above.
        builder.host_tensor(4, &[1]);
        builder.host_tensor(5, &[1]);
        builder.host_tensor(6, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                Op::Dispatch { kind: Kind::Add | Kind::AddBroadcast, .. }
            )),
            "both binaries should have folded: {:?}",
            plan.ops
        );
        let fused = plan
            .ops
            .iter()
            .filter_map(|op| match op {
                Op::Dispatch { push, .. } if push.shift != NO_FUSE => Some(push),
                _ => None,
            })
            .collect::<Vec<_>>();
        assert_eq!(fused.len(), 1, "exactly one shifted store: {fused:?}");
        assert_eq!(fused[0].out, plan.outputs[0].at);
        assert_eq!(fused[0].res, plan.inputs[0].at);
        assert_eq!(fused[0].shift, plan.inputs[1].at);
        // The shift tensor is one value per channel of the output.
        assert_eq!(plan.inputs[1].shape, Shape::new(4, 1, 1));
        assert_eq!(fused[0].out_c, 4);
    }

    /// An add whose convolution output has a second reader stays its own dispatch.
    ///
    /// Folding it would leave the other reader with no writer: the producer's old
    /// output tensor is never allocated once the fold moves the sum.
    #[test]
    fn a_shared_convolution_output_keeps_its_add() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let summed = builder.add(x, narrowed);
        // A second reader of the convolution's output: the folded store would orphan it.
        let mixed = builder.mul(summed, narrowed);
        // Tensors 2, 3 and 4 are the file's spares; see the residual test above.
        builder.host_tensor(2, &[1]);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[mixed]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the shared add must survive: {:?}",
            plan.ops
        );
    }

    /// An add held as a plan output stays its own dispatch.
    ///
    /// Nothing downstream reads it, but the host does — folding the sum into the
    /// convolution's store would leave the output binding pointing at an unwritten tensor.
    #[test]
    fn an_add_that_is_a_plan_output_keeps_its_dispatch() {
        let source = Shapes::new(5);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let summed = builder.add(x, narrowed);
        // Both the convolution's output and the sum escape: neither may fold.
        // Tensors 2, 3 and 4 are the file's spares; see the residual test above.
        builder.host_tensor(2, &[1]);
        builder.host_tensor(3, &[1]);
        builder.host_tensor(4, &[1]);
        let plan = builder.finish(&[narrowed, summed]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the output add must survive: {:?}",
            plan.ops
        );
    }

    /// An FPN-style `add(earlier, later)` stays its own dispatch.
    ///
    /// The skip side is downstream of the producing convolution, so reading it from
    /// the producer's store would read an unwritten tensor. See `Builder::add`.
    #[test]
    fn an_add_of_a_downstream_tensor_does_not_fold() {
        let source = Shapes::new(7);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 8));
        let early = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let later = builder.conv_same(early, 2, 4, 1, 1, Act::None);
        let grown = builder.resize_to(later, 1, 8);
        // `early` is upstream of the convolution that feeds `grown`'s side... but the
        // addend that matters is `grown` itself, produced after `early`: folding the
        // add into `early`'s store would read it before it is written.
        let merged = builder.add(early, grown);
        let out = builder.conv_same(merged, 4, 4, 1, 1, Act::None);
        // Tensor 6 is the file's spare; see the residual test above.
        builder.host_tensor(6, &[1]);
        let plan = builder.finish(&[out]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the FPN add must survive: {:?}",
            plan.ops
        );
    }

    /// A self-add never folds: the convolution would read the tensor it no longer writes.
    #[test]
    fn a_self_add_does_not_fold() {
        let source = Shapes::new(3);
        let mut builder = Builder::new(&source);
        let x = builder.input(Shape::new(4, 1, 4));
        let narrowed = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let doubled = builder.add(narrowed, narrowed);
        // Tensor 2 is the file's spare; see the residual test above.
        builder.host_tensor(2, &[1]);
        let plan = builder.finish(&[doubled]).expect("builds");
        assert_eq!(
            plan.ops.iter().filter(|op| matches!(op, Op::Dispatch { kind: Kind::Add, .. })).count(),
            1,
            "the self-add must survive: {:?}",
            plan.ops
        );
    }

    /// The folded plan carries the residual and the shift into the store.
    ///
    /// `nets::reference` serves fused pushes from the same arms as unfolded ones. This
    /// builds the residual-plus-shift graph over a real weights file — one tensor per
    /// slot, no overlaps — runs it through the host interpreter, and checks the fused
    /// store added both addends: outputs differ per position by exactly the input
    /// differences, and per channel pair by exactly the shift difference. An addend the
    /// fold dropped would show up as a missing difference.
    #[test]
    fn a_folded_plan_matches_its_unfolded_numbers() {
        use crate::nets::reference;
        // Four tensors, one per slot: two kernels and two biases. The shift input is
        // a plan input rather than a weight. Built directly — `Offsets` has no public
        // constructor — by parsing a hand-made `.maml` header, which also exercises
        // the real `WeightSource` path instead of the `Shapes` stub.
        let header = maml_header(&[
            (&[4, 2, 1, 1], crate::weights::DTYPE_F16),
            (&[4], crate::weights::DTYPE_F16),
            (&[2, 4, 1, 1], crate::weights::DTYPE_F16),
            (&[2], crate::weights::DTYPE_F16),
        ]);
        let parsed = crate::weights::Weights::parse(&header, crate::weights::graph::SELFIE)
            .expect("the hand-made header parses");
        let table = parsed.offsets();
        let mut builder = Builder::new(&table);
        let x = builder.input(Shape::new(2, 1, 2));
        let shift_in = builder.input(Shape::new(2, 1, 1));
        let widened = builder.conv_same(x, 0, 4, 1, 1, Act::None);
        let narrowed = builder.conv_same(widened, 2, 2, 1, 1, Act::None);
        let residual = builder.add(x, narrowed);
        let out = builder.add_channel(residual, shift_in);
        let plan = builder.finish(&[out]).expect("builds");
        assert!(
            plan.ops.iter().all(|op| !matches!(
                op,
                Op::Dispatch { kind: Kind::Add | Kind::AddBroadcast, .. }
            )),
            "test setup: both binaries should have folded: {:?}",
            plan.ops
        );
        // Kernels all ones, biases all 0.5 — every value exactly representable in fp16.
        // Laid out per the parsed table's own offsets: kernel0 at bytes 0..16, bias0
        // at 16..24, kernel1 at 32..48, bias1 at 48..52. (Each tensor starts
        // 16-aligned, so kernel1 pads to 32.)
        let half = |v: f32| crate::preprocess::f32_to_f16(v).to_le_bytes();
        let mut blob = vec![0u8; 52];
        for (offset, count, value) in [(0, 8, 1.0f32), (16, 4, 0.5), (32, 8, 1.0), (48, 2, 0.5)] {
            for e in 0..count {
                blob[offset + e * 2..offset + e * 2 + 2].copy_from_slice(&half(value));
            }
        }
        let x_values = [1.0f32, 2.0, 3.0, 4.0];
        let shift_values = [10.0f32, 20.0];
        let x_slice: &[f32] = &x_values;
        let shift_slice: &[f32] = &shift_values;
        let outputs =
            reference::run_multi(&plan, &blob, &[x_slice, shift_slice]).expect("runs");
        assert_eq!(outputs.len(), 1);
        // widened[c][p] = sum(x) + 0.5 = 10.5; narrowed[o][p] = 2 * 10.5 + 0.5 = 21.5;
        // out = narrowed + x + shift = 21.5 + x + shift, per channel: the contraction
        // is over the input's 2 channels, not the output's 4.
        //
        // The residual (x) and the shift are separate addends in the fused store, so
        // the differences pin each: positions in a channel differ by exactly the input
        // difference, and channel pairs differ by exactly the shift difference plus
        // the input difference. A dropped addend would zero one of those deltas.
        // `outputs[0]` is `[c, 1, w]` flattened channel-major: index `c * w + p`.
        // Hand-evaluated: x is channel 0 = [1, 2], channel 1 = [3, 4].
        // widened[c][p] = x[0][p] + x[1][p] + 0.5 = 4.5 / 6.5 per position;
        // narrowed[o][p] = 4 * widened + 0.5 = 18.5 / 26.5; out adds the residual
        // x and the shift [10, 20]: [29.5, 38.5, 41.5, 50.5].
        //
        // Same channel, adjacent positions differ by the narrowed delta (8.0) plus
        // the input delta (1.0); same position, adjacent channels differ by the
        // shift (10.0) plus the input (2.0). A dropped addend would zero one of
        // those deltas.
        let deltas = [
            (outputs[0][1] - outputs[0][0], 9.0),
            (outputs[0][3] - outputs[0][2], 9.0),
            (outputs[0][2] - outputs[0][0], 12.0),
            (outputs[0][3] - outputs[0][1], 12.0),
        ];
        for (found, wanted) in deltas {
            assert!((found - wanted).abs() < 0.06, "delta got {found}, want {wanted}");
        }
        // And the absolute level pins the convolution itself.
        assert!((outputs[0][0] - 29.5).abs() < 0.06, "level {:?}", outputs[0]);
    }

    /// A `.maml` header + table for `shapes`, with an empty data section.
    ///
    /// Test-only builder for [`a_folded_plan_matches_its_unfolded_numbers`]: `Offsets`
    /// has no public constructor, so the table is parsed the way a shipped asset is.
    /// `graph::SELFIE` is arbitrary — the id only has to match the parse call.
    ///
    /// `Weights::parse` requires the file to end exactly where the data section does,
    /// so the returned vector is padded with zeros to the declared length.
    fn maml_header(shapes: &[(&[u32], u32)]) -> Vec<u8> {
        use crate::weights::graph;
        let count = shapes.len() as u32;
        let table_bytes = shapes.len() * 32;
        let mut data_len = 0usize;
        for (dims, _) in shapes {
            let len: usize = dims.iter().map(|&d| d as usize).product();
            data_len = (data_len + len * 2).next_multiple_of(16);
        }
        let mut bytes = vec![0u8; 64 + table_bytes + data_len];
        bytes[0..4].copy_from_slice(b"MAML");
        bytes[4..8].copy_from_slice(&1u32.to_le_bytes());
        bytes[8..12].copy_from_slice(&graph::SELFIE.to_le_bytes());
        bytes[12..16].copy_from_slice(&count.to_le_bytes());
        bytes[48..52].copy_from_slice(&(64 + table_bytes as u32).to_le_bytes());
        for (i, (dims, dtype)) in shapes.iter().enumerate() {
            let at = 64 + i * 32;
            bytes[at..at + 4].copy_from_slice(&(dims.len() as u32).to_le_bytes());
            for (d, dim) in dims.iter().enumerate() {
                bytes[at + 4 + d * 4..at + 8 + d * 4].copy_from_slice(&dim.to_le_bytes());
            }
            bytes[at + 20..at + 24].copy_from_slice(&dtype.to_le_bytes());
            // 16-aligned running offset: tensor 0 at 0, each later tensor after the
            // previous one's padded end. Payloads are fp16 here, two bytes an element.
            let mut offset = 0usize;
            for (prev, _) in &shapes[..i] {
                let prev_len: usize = prev.iter().map(|&d| d as usize).product();
                offset = (offset + prev_len * 2).next_multiple_of(16);
            }
            let len: usize = dims.iter().map(|&d| d as usize).product();
            bytes[at + 24..at + 28].copy_from_slice(&(offset as u32).to_le_bytes());
            bytes[at + 28..at + 32].copy_from_slice(&(len as u32).to_le_bytes());
        }
        bytes[52..56].copy_from_slice(&(data_len as u32).to_le_bytes());
        bytes
    }
}
