package com.vayunmathur.library.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlin.math.min
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * On-device text recognition on ExecuTorch: the PP-OCRv6 **tiny** multifunction `.pte` (one
 * module, `detect` + `recognize` methods) with the ship latin dictionary.
 *
 * The `.pte` is the tiny-variant Vulkan fp16 export (`pp_ocrv6_tiny_vulkan_fp16.pte`,
 * ~3.1 MB) on the pure-Vulkan ExecuTorch runtime
 * (`library/ml/libs/executorch-vulkan-1.4.0.aar`, XNNPACK linkage removed per lead
 * directive). `OcrEngine` serves this path only; there is no LiteRT fallback — see
 * `analysis/et-ocr/ET_PATH.md`. The `.pte`
 * does not ship yet, so today this resolves to unavailable; the dictionary
 * (`ppocrv6_keys.txt`) already ships.
 *
 * The tiny rec graph emits 6906 classes — exactly the ship layout (index 0 = CTC blank,
 * ship-key `i` ↔ logit `i + 1`, last logit = space) — so NO 18k full-charset asset is
 * needed: the ET path reuses the shipped latin dict (task 22 relaunch, Vulkan-only).
 *
 * # One multifunction module, two methods
 *
 * `detect` takes `[1,3,H,W]` f32 NCHW straight from `OnnxPreprocess.letterboxPlanar` — no
 * CHW-to-NHWC swizzle — and returns the `[1,1,H',W']` f32 probability map. `recognize`
 * takes `[1,3,48,W]` f32 NCHW and returns `[1,T,6906]` f32 with the softmax already baked
 * in: index 0 is the CTC blank, `shipKeys[i]` decodes logit `i + 1`, and the last logit
 * is the space. Everything between
 * the two nets (DBNet regions, the rotated crop, reading order) is [OcrPost]; only the
 * CTC read differs (probs direct vs the raw-logit softmax denominator the old LiteRT
 * path computed).
 *
 * # Availability
 *
 * The constructor never throws. [isAvailable] is false when the `.pte` is absent, the
 * Vulkan delegate is not linked, the dictionary fails to parse, or a run fails — and
 * then [recognize] returns null. A failed ET run inside [recognize] likewise yields null
 * so the caller (`OcrEngine`) fails the image closed to empty.
 *
 * # Threading
 *
 * Not thread-safe: one module serves both methods, so a caller must hold a lock across
 * [recognize] and [close].
 */
class TextRecognizerEt(
    context: Context,
    etAsset: String = ET_ASSET,
    dictionaryAsset: String = ET_DICTIONARY_ASSET,
) : AutoCloseable {
    private val app = context.applicationContext
    private val primaryAsset = etAsset
    private val dictAsset = dictionaryAsset
    private val lock = Any()

    @Volatile private var module: Module? = null
    @Volatile private var moduleKey: String = ""
    @Volatile private var dictionary: List<String>? = null
    @Volatile private var etTried = false

    /** One detector output: the probability map with its own geometry. */
    data class EtDetMap(val probs: FloatArray, val width: Int, val height: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is EtDetMap &&
                width == other.width && height == other.height && probs.contentEquals(other.probs))

        override fun hashCode(): Int = 31 * (31 * width + height) + probs.contentHashCode()
    }

    /** One recogniser output: baked-softmax probabilities, row-major `[T, classes]`. */
    data class EtRecProbs(val probs: FloatArray, val timesteps: Int, val classes: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is EtRecProbs &&
                timesteps == other.timesteps && classes == other.classes &&
                probs.contentEquals(other.probs))

        override fun hashCode(): Int =
            31 * (31 * timesteps + classes) + probs.contentHashCode()
    }

    init {
        try {
            val text = app.assets.open(dictAsset).use { it.readBytes() }.decodeToString()
            dictionary = parseDictionary(text)
        } catch (e: Throwable) {
            Log.i(TAG, "no ET OCR charset at $dictAsset, ET path unavailable", e)
            dictionary = null
        }
    }

    /** True if the module came up and the charset parsed. */
    val isAvailable: Boolean get() = dictionary != null && ensureEt() != null

    /**
     * Recognise every line in [bitmap], in reading order — or null when the backend failed.
     *
     * Null (module missing, delegate absent, run failed) fails the image closed to empty.
     * An empty list means detection ran and found no
     * text, which is final — no fallback. The caller's [bitmap] is not recycled.
     */
    fun recognize(bitmap: Bitmap): List<RecognizedLine>? {
        val dict = dictionary ?: return null
        val mod = ensureEt() ?: return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val fit = OnnxPreprocess.SquareFit.of(readable.width, readable.height, DET_SIDE)
            // No NHWC swizzle: the `.pte` eats the planar NCHW buffer directly.
            val planar = OnnxPreprocess.letterboxPlanar(
                pixels, readable.width, readable.height, DET_SIDE, fit, OnnxPreprocess.PPOCR_DET,
            )
            val map = detect(planar, mod) ?: return null
            val regions = OcrPost.dbnetRegions(map.probs, map.width, map.height, fit)
            val lines = ArrayList<RecognizedLine>(regions.size)
            for (region in regions) {
                val line = recognizeRegion(region, pixels, readable.width, readable.height, dict, mod)
                    ?: continue
                lines += line
            }
            OcrPost.orderReading(lines)
            return lines
        } catch (e: Throwable) {
            Log.e(TAG, "ET OCR failed", e)
            return null
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /**
     * The `detect` method over an NCHW `[1,3,side,side]` input, or null on failure.
     *
     * Public for on-device parity probes (det-map cosine vs the LiteRT ship rung).
     */
    fun detect(nchw: FloatArray, side: Int = DET_SIDE): EtDetMap? {
        val mod = ensureEt() ?: return null
        return detect(nchw, mod, side)
    }

    /**
     * The `recognize` method over an NCHW `[1,3,48,width]` input, or null on failure.
     *
     * Public for on-device parity probes (argmax strings vs the LiteRT ship rung).
     */
    fun recognizeRegion(nchw: FloatArray, width: Int): EtRecProbs? {
        val mod = ensureEt() ?: return null
        return recognizeProbs(nchw, width, mod)
    }

    /** Free the module. Idempotent. */
    override fun close() {
        synchronized(lock) {
            module = null
            if (moduleKey.isNotEmpty()) ExecutorchSessions.close(moduleKey)
            moduleKey = ""
        }
    }

    private fun detect(nchw: FloatArray, mod: Module, side: Int = DET_SIDE): EtDetMap? {
        if (nchw.size != 3 * side * side) return null
        val (flat, shape) = runEtTensor(
            mod, nchw, longArrayOf(1, 3, side.toLong(), side.toLong()), DETECT_METHOD,
        ) ?: return null
        if (shape.size != 4 || shape[0] != 1L || shape[1] != 1L) return null
        val width = shape[3].toInt()
        val height = shape[2].toInt()
        if (width <= 0 || height <= 0 || flat.size != width * height) return null
        return EtDetMap(flat, width, height)
    }

    private fun recognizeProbs(nchw: FloatArray, width: Int, mod: Module): EtRecProbs? {
        if (width <= 0 || width > OcrPost.REC_WIDTH || nchw.size != 3 * OcrPost.REC_HEIGHT * width) return null
        val (flat, shape) = runEtTensor(
            mod, nchw, longArrayOf(1, 3, OcrPost.REC_HEIGHT.toLong(), width.toLong()), RECOGNIZE_METHOD,
        ) ?: return null
        if (shape.size != 3 || shape[0] != 1L) return null
        val timesteps = shape[1].toInt()
        val classes = shape[2].toInt()
        if (timesteps <= 0 || classes <= 0 || flat.size != timesteps * classes) return null
        return EtRecProbs(flat, timesteps, classes)
    }

    /**
     * One ET method invocation returning floats with their shape.
     *
     * Reads through [floatsAllowingHalf]: the staged variants produce f32 outputs (fp16
     * compute, int8 compute with f32 I/O — see `analysis/et-ocr/ET_PATH.md`), but a future
     * quant rung may lower outputs to HALF, and failing closed there would pin every call
     * to unavailable forever. Uses [ExecutorchSessions.run], never `runFloat`,
     * because `runFloat` only serves `forward` and drops the shape this caller needs.
     */
    private fun runEtTensor(
        mod: Module,
        data: FloatArray,
        shape: LongArray,
        method: String,
    ): Pair<FloatArray, LongArray>? {
        return try {
            val input = EValue.from(Tensor.fromBlob(data, shape))
            val outs = synchronized(lock) {
                ExecutorchSessions.run(mod, listOf(input), method)
            } ?: return null
            val value = outs.firstOrNull() ?: return null
            if (!value.isTensor) {
                Log.w(TAG, "ET $method produced no tensor")
                return null
            }
            val outShape = value.toTensor().shape()
            // want=1: any non-empty tensor reads; geometry is validated downstream.
            val flat = value.floatsAllowingHalf("$TAG $method", 1) ?: return null
            Pair(flat, outShape)
        } catch (e: Throwable) {
            Log.w(TAG, "ET $method run failed", e)
            null
        }
    }

    private fun recognizeRegion(
        region: OcrPost.Region,
        pixels: IntArray,
        width: Int,
        height: Int,
        dict: List<String>,
        mod: Module,
    ): RecognizedLine? {
        val corners = OcrPost.rectCorners(region.rect)
        val content = OcrPost.cropWidth(region).coerceIn(OcrPost.WIDTH_MULTIPLE, OcrPost.REC_WIDTH)
        val cropped = OcrPost.warp(pixels, width, height, corners, content, OcrPost.REC_HEIGHT)
        // Dynamic width needs no grey pad: the `.pte` accepts any multiple-of-8 width.
        // Recognition normalisation is BGR `(v / 255 - 0.5) / 0.5`, NCHW 48 x W.
        val input = FloatArray(3 * OcrPost.REC_HEIGHT * content)
        val plane = OcrPost.REC_HEIGHT * content
        for (y in 0 until OcrPost.REC_HEIGHT) {
            for (x in 0 until content) {
                val argb = cropped[y * content + x]
                val ch = intArrayOf(argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
                for (c in 0..2) {
                    input[c * plane + y * content + x] = (ch[c] / 255f - 0.5f) / 0.5f
                }
            }
        }
        val out = recognizeProbs(input, content, mod) ?: return null
        // Logit count is dict entries + 1 for the CTC blank: the tiny graph emits 6906
        // = blank + 6904 ship keys + space, and `parseDictionary` already appended the
        // space, so `dict.size` is 6905. The stale full-variant code compared against
        // bare `dict.size` (6906 != 6905) and would have nulled out every line.
        if (out.classes != dict.size + 1) {
            Log.e(TAG, "${out.classes} ET logits vs ${dict.size} charset entries")
            return null
        }
        // The export's timestep count tracks width (`T = W / 8` at the staged 320-wide
        // rung); never read past the content's own columns.
        val used = min(out.timesteps, content / OcrPost.WIDTH_MULTIPLE)
        val decoded = ctcDecodeProbs(out.probs, out.timesteps, used, out.classes, dict) ?: return null
        val text = decoded.first.trim()
        if (text.isEmpty()) return null
        val quad = corners.map { Pair(it.first, it.second) }
        return RecognizedLine(text, decoded.second, quad, region.vertical)
    }

    /**
     * Greedy CTC collapse over baked-softmax [probs] (`[timesteps, classes]` row-major).
     *
     * Index 0 is the blank; logit `i` is `dict[i - 1]`. Confidence is the mean winner
     * probability — the export already normalised, so there is no manual softmax
     * denominator.
     */
    private fun ctcDecodeProbs(
        probs: FloatArray,
        timesteps: Int,
        used: Int,
        classes: Int,
        dict: List<String>,
    ): Pair<String, Float>? {
        // classes = dict entries + 1 (blank at index 0); dict[i - 1] decodes logit i.
        if (timesteps == 0 || used > timesteps || classes != dict.size + 1) return null
        val text = StringBuilder()
        var total = 0.0
        var kept = 0
        var previous = -1
        for (step in 0 until used) {
            val base = step * classes
            var best = 0
            var peak = probs[base]
            for (label in 1 until classes) {
                val value = probs[base + label]
                if (value > peak) {
                    peak = value
                    best = label
                }
            }
            if (best != previous && best != 0) {
                val character = dict.getOrNull(best - 1) ?: return null
                text.append(character)
                total += peak
                kept++
            }
            previous = best
        }
        val confidence = if (kept > 0) (total / kept).toFloat() else 0f
        return Pair(text.toString(), confidence)
    }

    private fun parseDictionary(text: String): List<String>? {
        val entries = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .toList()
        if (entries.size != DICTIONARY_ENTRIES) {
            Log.e(TAG, "${entries.size} ET charset entries, expected $DICTIONARY_ENTRIES")
            return null
        }
        if (entries.any { it.codePointCount(0, it.length) != 1 }) {
            Log.e(TAG, "an ET charset entry is not a single character")
            return null
        }
        return entries + " "
    }

    /**
     * The module, loaded once: the Vulkan fp16 `.pte` when the delegate is linked, else
     * null — unavailable, never a throw. Vulkan-only per lead directive; there is no
     * XNNPACK fallback asset.
     */
    private fun ensureEt(): Module? {
        if (module != null) return module
        synchronized(lock) {
            if (module != null) return module
            if (etTried) return null
            etTried = true
            val vulkan = ExecutorchSessions.registeredBackends()
                ?.any { it.contains("Vulkan", ignoreCase = true) } == true
            if (!vulkan) {
                Log.i(TAG, "Vulkan backend absent, $primaryAsset unavailable")
                return null
            }
            return openEt(primaryAsset)
        }
    }

    private fun openEt(asset: String): Module? {
        val opened = try {
            ExecutorchSessions.openAsset(app, asset)
        } catch (e: Throwable) {
            Log.i(TAG, "no ET OCR asset at $asset, ET path unavailable", e)
            null
        }
        if (opened != null) {
            module = opened
            moduleKey = "asset:$asset"
        }
        return opened
    }

    companion object {
        private const val TAG = "TextRecognizerEt"

        /** The ExecuTorch graph, tiny-variant Vulkan fp16 (task 22 relaunch, Vulkan-only). */
        const val ET_ASSET: String = "pp_ocrv6_tiny_vulkan_fp16.pte"

        /**
         * The tiny-variant dictionary: the shipped latin dict (6,904 entries, one per line,
         * UTF-8, LF) — the tiny rec emits 6906 logits = the ship layout, NOT the 18,709
         * full-charset superset (`ppocrv6_charset.txt` never ships) and NOT
         * `ppocr_keys_v1.txt` (6,623 lines, a different rec checkpoint).
         */
        const val ET_DICTIONARY_ASSET: String = "ppocrv6_keys.txt"

        private const val DETECT_METHOD = "detect"
        private const val RECOGNIZE_METHOD = "recognize"

        private const val DET_SIDE = 640

        /** Ship latin dict entries (6,904) minus the space, which is appended at load. */
        private const val DICTIONARY_ENTRIES = 6904
    }
}
