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

    /// Mark one file tensor read, for loaders that resolve weights without
    /// passing through the indexed builders. See [`Builder::mark_read`].
    pub fn mark_one(&mut self, index: usize) {
        if let Some(slot) = self.read.get_mut(index) {
            *slot = true;
        } else {
            self.fail(format!("tensor {index}: the file holds {}", self.read.len()));
        }
    }

    /// Mark file tensors as read without resolving them.
    ///
    /// The `*_raw` entry points take resolved offsets, so they never touch
    /// the `read` flags — but `record`/`finish` still gate on every flag
    /// being set. A loader that proves coverage structurally (from the node
    /// refs, as the MAML v2 loader does — and as the v1 graph-section loader
    /// does for its host tensors) marks the proven set here before
    /// finishing. The flags must be exactly the proven set: marking an
    /// unread tensor silences the gate that catches dropped layers.
    ///
    /// Unlike [`Builder::host_tensor`], this checks nothing: the caller has
    /// already validated shapes (the v1 section parser shape-checks at parse;
    /// the v2 loader at inference). Prefer `host_tensor` wherever the dims
    /// are at hand; use this only for tensors whose shape the loader
    /// deliberately does not carry.
    pub fn mark_read(&mut self, read: &[bool]) {
        if read.len() == self.read.len() {
            self.read.copy_from_slice(read);
        } else {
            self.fail(format!(
                "marking {} tensors of a {}-tensor table",
                read.len(),
                self.read.len()
            ));
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
    ///
    /// `res`/`shift` replay a fused epilogue: the MAML v2 loader lowers files
    /// whose producer already carries the folded store (the emitter ran after
    /// fusion), so the addend must ride the node rather than a second Binary.
    /// The readiness and single-consumer checks that `fuse_elementwise` ran at
    /// record time are re-checked at load by inference (SSA + topological
    /// order); passing an addend that is not ready is a read-before-write the
    /// allocator cannot see.
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

    /// [`Builder::conv_raw`] with a fused residual addend and per-channel shift.
    ///
    /// The MAML v2 form of the above: `res`/`shift` are tensor ids whose
    /// contents the producer's store adds, exactly as `fuse_elementwise`
    /// sets them. `None` behaves as `conv_raw`.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_raw_fused(
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
        res: Option<Id>,
        shift: Option<Id>,
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
            transpose: false,
            pad_edge,
            res,
            shift,
        });
        out
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

    /// [`Builder::conv_int8_raw`] with a fused residual addend and per-channel
    /// shift. The MAML v2 form: same contract as [`Builder::conv_raw_fused`],
    /// for quantised kernels.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_int8_raw_fused(
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
        res: Option<Id>,
        shift: Option<Id>,
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
            res,
            shift,
        });
        out
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
        let out = self.tensor(Shape::new(m, out_h, out_w));
        let act_weight = self.act_weight(act, m);
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

    /// [`Builder::conv_transpose`] with resolved weight offsets rather than
    /// table indices.
    ///
    /// As [`Builder::conv_raw`]: the section parser validated shapes, so
    /// lowering only translates addressing. `m` is the section's computed
    /// output channels. No fused form: `fuse_elementwise` never folds into a
    /// transpose (its producer predicate names `Conv`/`ConvInt8` nodes), so
    /// there is no `res`/`shift` to replay.
    #[allow(clippy::too_many_arguments)]
    pub fn conv_transpose_raw(
        &mut self,
        input: Id,
        weight: u32,
        bias: u32,
        act_weight: u32,
        m: u32,
        act: Act,
        kernel: (u32, u32),
        stride: (u32, u32),
        pads: (u32, u32, u32, u32),
    ) -> Id {
        let in_shape = self.shape_of(input);
        let (kh, kw) = kernel;
        let (pad_t, pad_l, pad_b, pad_r) = pads;
        let out_h = deconv_out(in_shape.h, kh, stride.0, pad_t + pad_b);
        let out_w = deconv_out(in_shape.w, kw, stride.1, pad_l + pad_r);
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
}