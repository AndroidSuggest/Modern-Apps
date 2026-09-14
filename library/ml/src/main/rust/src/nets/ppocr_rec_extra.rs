use super::ppocr_rec::{
    AFFINES, D_MODEL, FEATURES, HEIGHT, LOGITS, TENSORS, WIDTH_MULTIPLE,
};
use super::{Act, Builder, Id, Plan, Shape, WeightSource};
use super::ppocr_rec::{along_sequence, block, depthwise, point, squeeze_excite};

/// Build the recognition pass for a `48 x width` crop.
///
/// `width` must be a positive multiple of [`WIDTH_MULTIPLE`]; the output is
/// `[LOGITS, 1, width / 8]`.
pub fn build(weights: &dyn WeightSource, width: u32) -> Result<Plan, String> {
    Ok(record(weights, width)?.plan)
}

/// Record the recognition pass for a `48 x width` crop.
///
/// [`build`] is this plus `Op` emission; the MAML v2 emitter needs the graph
/// without the plan, after the same fusion fold and the same every-tensor
/// rule. Split out so both share the body verbatim. See [`Builder::record`].
pub fn record(weights: &dyn WeightSource, width: u32) -> Result<crate::nets::Recorded, String> {
    use super::ppocr_rec::Layers;
    if width == 0 || !width.is_multiple_of(WIDTH_MULTIPLE) {
        return Err(format!(
            "a recognition width of {width}: three stride-2 stages act on it, so it must \
             be a positive multiple of {WIDTH_MULTIPLE}"
        ));
    }

    let l = &mut Layers { next: 0 };
    let mut builder = Builder::new(weights);
    let b = &mut builder;
    let input = b.input(Shape::new(3, HEIGHT, width));
    let affine = |b: &mut Builder, x: Id, which: usize| -> Id {
        let (scale, shift) = AFFINES[which];
        b.affine(x, scale, shift)
    };

    // Stem. The only convolution that strides both axes, and the only one in the backbone
    // with **no** activation: the export puts its batch norm here and the affine and
    // HardSwish after the depthwise that follows.
    let mut x = b.conv(input, l.take(), 16, (3, 3), (2, 2), (1, 1), (1, 1, 1, 1), 1, Act::None);

    // Six depthwise/pointwise pairs at 3x3, striding one axis at a time. Each pair after
    // the first is preceded by an affine, because the depthwise it feeds is padded; the
    // first is not, because the stem it follows has no affine to leave behind.
    for (index, (out, stride)) in [
        (32, (1, 1)),
        (64, (1, 1)),
        (64, (1, 1)),
        (128, (2, 1)),
        (128, (1, 1)),
        (240, (1, 2)),
    ]
    .into_iter()
    .enumerate()
    {
        if index > 0 {
            x = affine(b, x, index - 1);
        }
        x = depthwise(b, l, x, 3, stride);
        x = point(b, l, x, out, Act::HardSwish);
    }

    // Four depthwise/pointwise pairs at 5x5, all at 240 channels.
    for index in 0..4 {
        x = affine(b, x, 5 + index);
        x = depthwise(b, l, x, 5, (1, 1));
        x = point(b, l, x, 240, Act::HardSwish);
    }
    // Then a fifth depthwise that halves the height and feeds the squeeze-excite
    // directly. There is no pointwise between the two — this is the one place the
    // backbone's depthwise/pointwise alternation breaks, and pairing it up regardless
    // reads every subsequent tensor one layer out of step.
    x = affine(b, x, 9);
    x = depthwise(b, l, x, 5, (2, 1));
    x = affine(b, x, 10);
    x = squeeze_excite(b, l, x, 60);

    x = point(b, l, x, FEATURES, Act::HardSwish);
    x = affine(b, x, 11);
    x = depthwise(b, l, x, 5, (1, 1));
    x = affine(b, x, 12);
    x = squeeze_excite(b, l, x, 120);

    x = point(b, l, x, FEATURES, Act::HardSwish);
    x = affine(b, x, 13);
    x = depthwise(b, l, x, 5, (2, 1));
    x = point(b, l, x, FEATURES, Act::HardSwish);
    x = affine(b, x, 14);
    x = depthwise(b, l, x, 5, (1, 1));
    x = point(b, l, x, FEATURES, Act::HardSwish);
    x = affine(b, x, 15);

    // Where a feature map becomes a sequence: the three surviving rows collapse to one
    // and the width halves a final time.
    let pooled = b.avg_pool(x, (3, 2), (3, 2));

    // Down to `d_model`, then the two blocks.
    let narrow = along_sequence(b, l, pooled, 60);
    let mut sequence = point(b, l, narrow, D_MODEL, Act::Swish);
    for _ in 0..2 {
        sequence = block(b, l, sequence);
    }
    // The fifth layer norm, and the only one at 1e-6.
    sequence = b.layer_norm(sequence, l.take(), 1e-6);

    // Back up to the backbone's width, and rejoined to the features it came from. The
    // pooled tensor comes first, which is the order the export's `Concat` uses.
    let widened = point(b, l, sequence, FEATURES, Act::Swish);
    let joined = b.concat(&[pooled, widened]);

    let narrow = along_sequence(b, l, joined, 60);
    let head = point(b, l, narrow, D_MODEL, Act::Swish);
    // The classifier. No softmax: see the module docs.
    let logits = point(b, l, head, LOGITS, Act::None);

    if l.next != TENSORS {
        return Err(format!("the forward pass claims {} tensors, not {TENSORS}", l.next));
    }
    builder.record(&[logits], &crate::weights::Offsets::empty())
}
