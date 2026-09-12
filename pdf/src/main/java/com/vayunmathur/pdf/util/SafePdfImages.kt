package com.vayunmathur.pdf.util

/**
 * The raster half of [SafePdfParser]: image payload decoding and its budgets.
 *
 * Split out so SafePdfParser.kt stays under the FileLength limit. The pixel and
 * byte budgets move with the decoder that enforces them; [SafePdfParser] keeps
 * referencing them through this object.
 */
object SafePdfImages {

    /**
     * Decoded-pixel budget, deliberately a SEPARATE constant from [MAX_IMAGE_DATA_BYTES] even
     * though the two currently hold the same literal: one counts compressed bytes on the wire,
     * the other counts pixels after decode (16 Mi pixels is 64 MB of ARGB_8888). The same
     * conflation exists on the Rust side as `MAX_IMAGE_BYTES` / `MAX_IMAGE_PIXELS`; sharing one
     * literal between the two is how a change to either silently moves the other.
     *
     * Enforced per FORMAT, not globally — see `decodeBitmap`.
     *
     * TWIN of `graphics_state.rs:286`, and the invariant is ONE-DIRECTIONAL:
     * this value must be >= Rust's, never <.
     *
     * `images.rs:848` decimates until `out_w * out_h <= MAX_IMAGE_PIXELS` (Rust's), then the
     * `format == 0` branch of `decodeBitmap` drops anything above this one. If this were
     * LOWERED below Rust's, Rust would emit rasters in the gap believing it had decimated them
     * to fit, and they would vanish here — the large-CCITT bug (F13) silently returning, with
     * both sides individually "correct" and no diagnostic on either. Raising it is safe.
     *
     * Same shape as `wire.rs`'s `WIRE_VERSION <= kotlin_version` check: the consumer may run
     * ahead of the producer, never behind. Written as a bare literal with an explicit type so
     * that check's file-reading approach can be pointed at this line too.
     */
    private const val MAX_IMAGE_PIXELS: Long = 16777216 // 16 * 1024 * 1024

    /**
     * TWIN of `graphics_state.rs:284` (used at `images.rs:1078`), with the same
     * consumer->=producer invariant as [MAX_IMAGE_PIXELS]: Rust refuses an image past its
     * value, so a SMALLER value here drops images Rust considered valid.
     */
    private const val MAX_IMAGE_DIM: Int = 20000

    /**
     * Largest raster payload this decoder will materialise for one image, in bytes.
     *
     * It is a LOCAL heap policy, not a wire invariant: Rust's own bound is looser in both
     * arms that reach it. `extract_image` downscales a decoded raster to 2048px on its long
     * side (16 MB of RGBA at worst, exactly this), but `extract_inline_image` only enforces
     * `MAX_IMAGE_PIXELS` (16 MP -> 64 MB of RGBA), and the format-1 JPEG passthrough hands
     * over the stream bytes with only a 64 MB ceiling on them. So a payload above this is a
     * legitimate stream we choose not to decode, NOT evidence that the buffer is corrupt —
     * which is why exceeding it skips the one primitive instead of ending the page.
     */
    internal const val MAX_IMAGE_DATA_BYTES = 16 * 1024 * 1024

    internal fun imageTooLarge(len: Int): Boolean = len > MAX_IMAGE_DATA_BYTES

    internal fun badDimensions(w: Int, h: Int): Boolean =
        w <= 0 || h <= 0 || w > MAX_IMAGE_DIM || h > MAX_IMAGE_DIM

    /**
     * Smallest power-of-two subsample bringing `w*h` within [MAX_IMAGE_PIXELS].
     * `BitmapFactory` rounds `inSampleSize` DOWN to a power of two, so compute one directly
     * rather than hand it a ratio it would round the wrong way (a rounded-down sample size
     * decodes LARGER than asked, which is the direction that OOMs).
     */
    internal fun sampleSizeFor(w: Int, h: Int): Int {
        var s = 1
        while ((w.toLong() / s) * (h.toLong() / s) > MAX_IMAGE_PIXELS) s = s shl 1
        return s
    }

    /** Decode an image payload: format 1 = JPEG bytes, 0 = raw RGBA8888. */
    internal fun decodeBitmap(w: Int, h: Int, format: Int, data: ByteArray): android.graphics.Bitmap? {
        if (badDimensions(w, h)) return null
        // The pixel budget is per format. The raw branch below allocates `w*h` ints before it
        // can do anything, so it must be refused outright — and Rust decimates that path, so
        // an oversized raw image is a contract violation rather than ordinary input. A JPEG
        // commits nothing until the decoder runs and is scaled down there instead.
        if (format != 1 && w.toLong() * h.toLong() > MAX_IMAGE_PIXELS) return null
        return try {
            when (format) {
                1 -> {
                    if (data.size > MAX_IMAGE_DATA_BYTES) {
                        android.util.Log.w("SafePdfParser", "JPEG too large ${data.size}")
                        null
                    } else {
                        // Subsample instead of dropping. Rust hands the JPEG over at full
                        // dimensions deliberately (images.rs:1082-1087) because this decoder
                        // is what is supposed to scale it; without inSampleSize it decodes at
                        // full size, so a 20 MP photo would commit ~80 MB of ARGB_8888.
                        val opts = android.graphics.BitmapFactory.Options()
                        opts.inSampleSize = sampleSizeFor(w, h)
                        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    }
                }
                0 -> {
                    if (data.size < w * h * 4) return null
                    val pixels = IntArray(w * h)
                    var p = 0
                    for (i in pixels.indices) {
                        val r = data[p].toInt() and 0xFF
                        val g = data[p + 1].toInt() and 0xFF
                        val b = data[p + 2].toInt() and 0xFF
                        val a = data[p + 3].toInt() and 0xFF
                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        p += 4
                    }
                    android.graphics.Bitmap.createBitmap(
                        pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888
                    )
                }
                else -> {
                    // Rust returns no image at all for a format it could not produce, so an
                    // unknown format here is a wire mismatch, not a failed decode to paper over.
                    android.util.Log.w("SafePdfParser", "Unknown bitmap format $format")
                    null
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("SafePdfParser", "decodeBitmap failed w=$w h=$h format=$format", t)
            null
        }
    }
}
