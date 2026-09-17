/// The learnable affine blocks that survive the fold, in graph order.
///
/// `scripts/ml/ppocr_fold.py` pushes an affine into the convolution it feeds wherever that
/// is exact, which is wherever the convolution is **unpadded**. These fourteen are the ones
/// that are not: twelve feed a padded depthwise, and two feed a squeeze-excite's pool and
/// multiply at once.
///
/// The padding is the reason. `conv(a * x + t)` is `a * conv(x) + t * sum(W)` at an
/// interior pixel, but a padded convolution reads zero rather than `t` outside the input,
/// so the constant's real contribution at the border is `t` times the sum of only the
/// in-bounds taps — position-dependent, and therefore not a bias. Folding anyway biases
/// the border of every feature map and compounds down the backbone.
///
/// They are scalars, so they live here rather than in the `.maml`, which holds tensors:
/// putting a one-element tensor in the table for each would shift the whole ordered table.
pub(crate) const AFFINES: [(f32, f32); 14] = [
    (0.6775133, -0.22914815),
    (0.36771294, -0.87113833),
    (1.4989212, -0.5550208),
    (0.5583892, -0.9510202),
    (1.5417316, -0.05748394),
    (0.6538819, -0.815451),
    (0.790457, -0.1132517),
    (0.72976404, 0.04859176),
    (0.8216482, 0.1775174),
    (1.3613669, 0.18224998),
    (0.9679641, 0.13799295),
    (1.2535226, -1.0351486),
    (0.919406, 0.25822622),
    (0.93593925, 0.16590087),
];
