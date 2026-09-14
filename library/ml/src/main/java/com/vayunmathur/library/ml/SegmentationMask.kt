package com.vayunmathur.library.ml

/** A segmentation mask: [width] × [height] probabilities in `0..1`, row-major. */
class SegmentationMask(
    /** Columns. Fixed per network, and independent of the input bitmap's size. */
    val width: Int,
    /** Rows. */
    val height: Int,
    /**
     * Foreground probability per pixel.
     *
     * A fresh array each call — the caller owns it and both call sites blend or threshold
     * it in place.
     */
    val mask: FloatArray,
)
