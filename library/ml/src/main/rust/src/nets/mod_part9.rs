impl<'a> Builder<'a> {
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
                // One workgroup per row-group: 2 channels for int8, 8 for int4 and Q2_K —
                // the three gemv shaders diverged, so each kind counts its own rows.
                let rows = match quant {
                    Quant::I8 => so.c.div_ceil(CONV_VEC_ROWS),
                    Quant::I4 => so.c.div_ceil(CONV_VEC_INT4_ROWS),
                    Quant::Q2K => so.c.div_ceil(CONV_VEC_Q2K_ROWS),
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
                    (Quant::Q2K, _, true) => Kind::ConvVecQ2K,
                    (Quant::Q2K, true, false) => Kind::ConvPointQ2K,
                    (Quant::Q2K, false, false) => Kind::ConvQ2K,
                };
                // Workgroups for the staged kinds, output elements for the untiled one.
                let count = match kind {
                    Kind::ConvVecInt8 | Kind::ConvVecInt4 | Kind::ConvVecQ2K => rows,
                    Kind::ConvPointInt8 | Kind::ConvPointInt4 | Kind::ConvPointQ2K => tiles,
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
                        | Kind::ConvPointInt4
                        | Kind::ConvVecQ2K
                        | Kind::ConvPointQ2K => count * 64,
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
            other => {
                self.emit_continued(other, at, shape, ops)?;
            }
        }
        Ok(())
    }
}