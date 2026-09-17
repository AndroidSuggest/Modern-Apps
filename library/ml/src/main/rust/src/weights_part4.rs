/// The `.maml` tensor blob this describes is uploaded verbatim; this section only says what
/// to *do* with it. Nodes name file tensor indices for weights (resolved and shape-checked
/// against the table, exactly as [`crate::nets::Builder::weight`] does) and computed indices
/// for intermediate values. Lowering runs through the same `finish` — fusion fold, liveness,
/// arena packing — as a hand-written forward pass, so a section-derived plan and a
/// Rust-derived plan over the same graph are the same plan.
///
/// Layout (little-endian throughout, like the rest of the file):
///
/// ```text
/// 0   4   u32 node count N (0 < N <= 65536)
/// 4   ... N nodes, each:
///         1   u8 kind tag (see `TAG_*`)
///         ... kind payload, fixed size per kind
/// ... computed tensors: u32 count M, then M x (c, h, w) u32 triples
/// ... inputs: u32 count, then computed indices
/// ... outputs: u32 count, then computed indices
/// ... host tensors: u32 count, then (file index, rank, dims[4]) each
/// ```
///
/// Weight refs are file indices, validated against the table for both range and shape.
/// A file index past the table, or a shape the table disagrees with, is a load error —
/// the same strictness as the builder's, which is the point: a corrupt section must fail
/// here rather than dispatch a shader reading past a device buffer.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Graph {
    /// The section's nodes, in execution order.
    pub nodes: Vec<GraphNode>,
    /// Shapes of every computed tensor, indexed by node value references.
    pub computed: Vec<[u32; 3]>,
    /// Computed indices of the plan inputs, in declaration order.
    pub inputs: Vec<u32>,
    /// Computed indices of the plan outputs.
    pub outputs: Vec<u32>,
    /// `(file index, rank, dims)` tensors read on the host rather than by the plan.
    pub host: Vec<(u32, u32, [u32; 4])>,
}

/// One symbolic op. Phase 1 covers the foldable core — convolutions, the residual adds,
/// and layer norm — which is every node of the sampler's ConvNeXt blocks. Each variant
/// carries file tensor indices for weights and computed indices for values: the same
/// addressing `Builder` resolves, frozen to numbers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum GraphNode {
    /// `out = act(conv(input))`: kernel/bias file indices, geometry, activation code.
    Conv {
        input: u32,
        out: u32,
        weight: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: u32,
        /// Slope file index for PReLU, `u32::MAX` otherwise. See [`crate::nets::Act`].
        act_weight: u32,
        /// Replicate the border instead of reading zeros. See [`crate::nets::Push::pad_edge`].
        pad_edge: bool,
    },
    /// `out = act(scale * conv_int(input) + bias)`: kernel/scale/bias file indices.
    ConvInt8 {
        input: u32,
        out: u32,
        weight: u32,
        scale: u32,
        bias: u32,
        kernel: (u32, u32),
        stride: (u32, u32),
        dilation: (u32, u32),
        pads: (u32, u32, u32, u32),
        group: u32,
        act: u32,
        /// 0 = eight-bit, 1 = four-bit. Anything else is a load error.
        quant: u32,
    },
    /// `out = a + b`.
    Add { a: u32, b: u32, out: u32 },
    /// `out = a + b[c]`, one shift per channel.
    AddBroadcast { a: u32, b: u32, out: u32 },
    /// Mean/variance normalisation over channels with a per-channel affine.
    LayerNorm { input: u32, out: u32, gamma: u32, beta: u32, epsilon_bits: u32 },
    /// Mean over H and W, keeping C.
    GlobalAvgPool { input: u32, out: u32 },
    /// Same elements under the computed shape. The section states the shape; the
    /// validator requires the element counts to agree.
    Reshape { input: u32, out: u32 },
}

/// Kind tags, one byte on the wire. Sequential from zero; unknown tags are a load error.
pub const TAG_CONV: u8 = 0;
pub const TAG_CONV_INT8: u8 = 1;
pub const TAG_ADD: u8 = 2;
pub const TAG_ADD_BROADCAST: u8 = 3;
pub const TAG_LAYER_NORM: u8 = 4;
/// Mean over H and W, keeping C. Only the attention-bias generator needs it in phase 1.
pub const TAG_GLOBAL_AVG_POOL: u8 = 5;
/// A relabelling, not a move: same elements under a new shape. Only `reshaped`'s
/// single-part form serialises — a multi-part `Concat` is a real join, not a view.
pub const TAG_RESHAPE: u8 = 6;

/// Nodes past this count are refused. The largest net here emits ~2,000 nodes per branch;
/// 64K is headroom, not a target, and its purpose is bounding the parse loop against a
/// corrupt count field claiming billions.
const MAX_GRAPH_NODES: usize = 65_536;

/// Computed tensors past this count are refused, for the same reason as nodes.
const MAX_GRAPH_TENSORS: usize = 65_536;

/// No weight tensor here: PReLU slope absent, int8-scale positions, host-tensor slots —
/// anywhere a file index is optional, all-bits-set means none. Zero is a live tensor
/// (the first input pins offset 0 in spirit, and file index 0 is the first weight), so
/// it cannot mean "absent".
const NO_TENSOR: u32 = u32::MAX;
