// The Gemma-4 GPU tied head: logits on the device, LiteRT-style.
//
// The text pass (`Mode::DecodeStep`) returns the normed hidden state and the
// host computes `hidden @ HEAD_TABLE^T` in `bridge_part6::tied_head_logits` —
// 4 chunked `fp16_rows` reads that dequant 805 MB fp16->fp32 per token
// (~2 s). This pass runs the same projection on the device: one
// `[1536, 1, 1]` hidden input in, one `[16384, 1, 1]` logits split per chunk
// out, upload-once and submit-per-token.
//
// The head table lives in the EMBED file, so this plan builds over the
// EMBED offsets (one file per plan — see `Builder::new`), with every other
// EMBED tensor named via `host_tensor` (same complement pattern as
// `nllb::name_host_tensors`). The converter emits the chunks as 16 rank-4
// fp16 kernels + zero biases after the legacy rank-2 table
// (`s10_to_maml.collect_embed`); indices 0..111 are unchanged so old files
// without chunks still parse for gather — this plan then fails its `shaped`
// checks and the caller falls back to the host head.
//
// One 262144-wide fp16 tensor spans ~768 MiB, far over the ~96 MiB
// segment-window reach at guaranteed 128 MiB `maxStorageBufferRange`
// (`vulkan::segment::for_op` refuses it: "Split the tensor in the
// converter"). 16x16384 chunks are ~50 MB each — inside the window with
// margin on host and P8. Each chunk lowers to `Kind::ConvPoint`
// (`conv_point.comp`); there is no fp16 `ConvVec`, so the 1-position tiles
// run ~93.75% idle lanes — correct but wasteful, and `ConvVecF16` is the
// follow-up, not this pass.
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::gemma4::{D_MODEL, LAYERS, PER_LAYER, VOCAB};
use super::gemma4::embed;

/// Vocab classes per chunk: 262144 / 16.
pub const CLASSES_PER_CHUNK: u32 = 16384;

/// Head chunks the converter emits. Must match `GEMMA4_HEAD_SPLITS`.
pub const HEAD_CHUNKS: usize = 16;

/// First tensor of the chunked head: kernel of chunk 0.
///
/// Layout after the legacy rank-2 table (tensor 112): chunk `s` owns
/// `HEAD_BASE + s * 2` (fp16 kernel `[CLASSES_PER_CHUNK, 1536, 1, 1]`) and
/// `HEAD_BASE + s * 2 + 1` (fp16 zero bias `[CLASSES_PER_CHUNK]`).
pub const HEAD_BASE: usize = embed::HEAD_CHUNKS;

/// Tensors the chunked head adds: kernel + bias per chunk.
pub const HEAD_CHUNK_TENSORS: usize = HEAD_CHUNKS * 2;

/// Tensors an EMBED file with GPU-head chunks holds.
pub const TENSORS_WITH_HEAD: usize = embed::TENSORS_WITH_HEAD;

/// A `1 x 1` fp16 convolution: the device-side dot of one vocab chunk.
fn point(b: &mut Builder, at: usize, x: Id, out: u32) -> Id {
    b.conv(
        x,
        at,
        out,
        (1, 1),
        (1, 1),
        (1, 1),
        (0, 0, 0, 0),
        1,
        Act::None,
    )
}

/// Build the head pass over the EMBED file's offsets.
///
/// One `[1536, 1, 1]` hidden-state input, one `[CLASSES_PER_CHUNK, 1, 1]`
/// fp16 output per chunk, in vocab order. The host concatenates the 16
/// splits and runs the existing softcap + argmax, exactly as it does for
/// the host-computed logits today.
pub fn build_plan(weights: &dyn WeightSource) -> Result<Plan, String> {
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    // Every EMBED tensor this pass does not read, named so `finish` accepts
    // the plan. The chunks are the only tensors read; the legacy rank-2
    // table (tensor 112) stays host-side for gather-time fallback.
    for index in 0..TENSORS_WITH_HEAD {
        if index < HEAD_BASE || index >= HEAD_BASE + HEAD_CHUNK_TENSORS {
            b.host_tensor(index, &dims_of(index));
        }
    }
    let x = b.input(Shape::new(D_MODEL, 1, 1));
    let mut outs = Vec::with_capacity(HEAD_CHUNKS);
    let mut next = HEAD_BASE;
    for _ in 0..HEAD_CHUNKS {
        let at = next;
        next += 2;
        outs.push(point(b, at, x, CLASSES_PER_CHUNK));
    }
    if next != HEAD_BASE + HEAD_CHUNK_TENSORS {
        return Err(format!("the head claims {} tensors, not {HEAD_CHUNK_TENSORS}", next - HEAD_BASE));
    }
    builder.finish(&outs)
}

/// The shape of EMBED tensor `index`, derived from the layout constants.
///
/// `host_tensor` checks it against the file, so this is a second statement
/// of the table `s10_to_maml.collect_embed` writes — a converter and a
/// runtime that disagree about a shape fail here rather than on the device.
pub fn dims_of(index: usize) -> Vec<u32> {
    if index < embed::TABLES {
        // Working triple (0-2, int4 kernel + per-block scale + bias),
        // shared projection triple (3-5, int8 kernel + scale + bias),
        // shared norm (6). File kernels are rank-2 [rows, taps] (host
        // readers take rank-2 dims); only Builder::conv demands rank-4.
        if index < 3 {
            return match index {
                0 => vec![VOCAB, D_MODEL],
                1 => vec![VOCAB, D_MODEL.div_ceil(crate::weights::I4_BLOCK)],
                _ => vec![VOCAB],
            };
        }
        if index < 6 {
            return match index {
                3 => vec![
                    LAYERS as u32 * PER_LAYER,
                    D_MODEL,
                    1,
                    1,
                ],
                4 => vec![LAYERS as u32 * PER_LAYER],
                _ => vec![LAYERS as u32 * PER_LAYER],
            };
        }
        return vec![PER_LAYER];
    }
    if index < embed::HEAD_TABLE {
        // Per-layer mmap tables: int4 triple each (kernel rank-2
        // [VOCAB, 256], per-block scale, bias).
        return match (index - embed::TABLES) % 3 {
            0 => vec![VOCAB, PER_LAYER],
            1 => vec![VOCAB, PER_LAYER.div_ceil(crate::weights::I4_BLOCK)],
            _ => vec![VOCAB],
        };
    }
    if index == embed::HEAD_TABLE {
        // Legacy rank-2 fp16 head table (host path).
        return vec![VOCAB, D_MODEL];
    }
    // Chunked head: kernel, bias alternating.
    if (index - HEAD_BASE) % 2 == 0 {
        vec![CLASSES_PER_CHUNK, D_MODEL, 1, 1]
    } else {
        vec![CLASSES_PER_CHUNK]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::nets::tests::Shapes;

    #[test]
    fn the_head_layout_matches_the_converter() {
        // `s10_to_maml.collect_embed` head section: legacy rank-2 fp16 table
        // at 112, then 16 rank-4 fp16 kernels + zero biases. Indices 0..111
        // unchanged so old files still parse for gather.
        assert_eq!(embed::HEAD_TABLE, 112);
        assert_eq!(HEAD_BASE, 113);
        assert_eq!(HEAD_CHUNKS, 16);
        assert_eq!(CLASSES_PER_CHUNK, 16384);
        assert_eq!(CLASSES_PER_CHUNK as usize * HEAD_CHUNKS, VOCAB as usize);
        assert_eq!(HEAD_CHUNK_TENSORS, 32);
        assert_eq!(TENSORS_WITH_HEAD, 145);
        assert_eq!(embed::TENSORS_WITH_HEAD, 145);
    }

    #[test]
    fn the_head_pass_builds_and_reads_every_chunk() {
        // One hidden input in, 16 vocab splits out, against the stub source
        // so `Builder::finish`'s unread-tensor invariant does the work: every
        // EMBED tensor is either a chunk this pass reads or named host-side.
        let source = Shapes::new(TENSORS_WITH_HEAD);
        let plan = build_plan(&source).expect("the head pass builds");
        assert_eq!(plan.inputs.len(), 1);
        assert_eq!(plan.inputs[0].shape, Shape::new(D_MODEL, 1, 1));
        assert_eq!(plan.outputs.len(), HEAD_CHUNKS);
        for output in &plan.outputs {
            assert_eq!(output.shape, Shape::new(CLASSES_PER_CHUNK, 1, 1));
        }
        // 16 fp16 1x1 convolutions, one per chunk.
        let mut convs = 0;
        for op in &plan.ops {
            if let crate::nets::Op::Dispatch { kind, .. } = op {
                if matches!(kind, crate::nets::Kind::Conv | crate::nets::Kind::ConvPoint) {
                    convs += 1;
                }
            }
        }
        assert_eq!(convs, HEAD_CHUNKS, "one fp16 conv per chunk");
        crate::nets::tests::assert_no_aliasing(&plan);
        let sched = crate::nets::schedule::schedule(&plan);
        crate::nets::schedule::is_sound(&plan, &sched).expect("the head schedule is sound");
    }

    #[test]
    fn the_chunk_shapes_cover_the_vocabulary() {
        // Chunk s owns classes [s * 16384, (s + 1) * 16384): contiguous and
        // gap-free, so concatenating the 16 outputs is the full logits row.
        let mut covered = 0u32;
        for s in 0..HEAD_CHUNKS {
            let _ = s;
            covered += CLASSES_PER_CHUNK;
        }
        assert_eq!(covered, VOCAB);
    }
}
