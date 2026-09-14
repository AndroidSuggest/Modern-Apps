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
/// modules; external callers only pass ids through. The MAML v2 emitter reads `.0`
/// through the accessor below.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Id(pub(crate) usize);

impl Id {
    /// The tensor slot, for consumers that map ids to positions (the MAML v2
    /// emitter, the v1 graph-section emitter).
    pub fn index(self) -> usize {
        self.0
    }
}

/// A recorded forward pass: the resolved plan plus the graph it came from.
///
/// [`Builder::record`] returns this instead of a bare [`Plan`] so the graph-section
/// emitter can serialise the nodes — with weight file indices recovered through the
/// read flags, shapes, and bindings — without re-deriving anything. The plan is what
/// runs; the rest is what the converter needs to reproduce it.
///
/// `pub` (not `pub(crate)`): the MAML v2 emitter (`crate::maml2::emit`) is the
/// second consumer of this contract, alongside the v1 graph-section emitter.
#[derive(Debug)]
pub struct Recorded {
    /// The resolved plan, as `finish` has always returned.
    pub plan: Plan,
    /// The fused nodes, in execution order.
    pub nodes: Vec<Node>,
    /// Shape per tensor id.
    pub shapes: Vec<Shape>,
    /// Input ids, in declaration order.
    pub inputs: Vec<Id>,
    /// Output ids, in the order `finish`/`record` was given. The emitter maps
    /// these to graph outputs; the plan bindings alone cannot identify them
    /// (arena offsets, not tensor ids).
    pub outputs: Vec<Id>,
    /// Pinned ids (inputs, outputs, persistent).
    pub pinned: Vec<Id>,
    /// Per-file-tensor read flags, so the emitter can name host tensors.
    pub read: Vec<bool>,
}

/// An unresolved step, against [`Id`]s rather than offsets.
///
/// `pub` (not `pub(crate)`): the MAML v2 emitter (`crate::maml2::emit`)
/// serialises these, alongside the v1 graph-section emitter. The variants
/// stay non-exhaustive to external consumers only by convention (see
/// `emit_section`).
#[derive(Clone, Debug)]
pub enum Node {
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
