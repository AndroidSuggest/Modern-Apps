package com.vayunmathur.library.ocr
import android.content.Context
import android.graphics.Bitmap
import com.vayunmathur.library.ml.RecognizedLine
import com.vayunmathur.library.ml.TextRecognizerEt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/**
 * Shared on-device OCR engine using Baidu **PP-OCRv6**, ExecuTorch-only (Vulkan).
 *
 * The single path is the tiny-variant multifunction `.pte` (`TextRecognizerEt`: one
 * module, `detect` + `recognize` methods, 6,904-char ship latin dict) on the pure-Vulkan
 * ExecuTorch runtime. Detection is DBNet over a
 * PP-HGNetV2 backbone; recognition is a PP-LCNetV3 backbone into a two-block transformer
 * with a CTC head. See `analysis/et-ocr/ET_PATH.md` for the bundle notes.
 *
 * There is no LiteRT/`.tflite` fallback: when the `.pte` is absent (or the Vulkan
 * delegate is not linked) the engine is inert (returns empty text, never crashes) -
 * call [isAvailable] to check up front.
 *
 * The engine still adapts the result to the [OcrResult]/[TextBox] shape consumers expect.
 * Reading order is decided by the recogniser, so this no longer sorts.
 *
 * All heavy work runs off the main thread (Dispatchers.Default). Instances are safe to reuse
 * sequentially; concurrent calls are serialised internally.
 *
 * Usage:
 * ```
 * val ocr = OcrEngine(context)
 * val text = ocr.recognize(bitmap)   // suspend
 * ocr.close()                        // when done with a batch
 * ```
 */
class OcrEngine(private val context: Context) {
    /** A corner of a [TextBox] quad, in source-bitmap pixel coordinates. */
    data class Corner(val x: Float, val y: Float)
    /**
     * One recognised text region in source-bitmap pixel coordinates.
     *
     * [corners] are the four corners of the region's oriented quad in reading
     * order: corner 0 -> 1 runs along the text and corner 0 -> 3 spans its
     * height. [left]/[top]/[right]/[bottom] are the axis-aligned bounds of that
     * quad, so they stay meaningful for consumers that only need a rect.
     * [vertical] marks a region read as vertical script (stacked glyphs).
     */
    data class TextBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val corners: List<Corner> = listOf(
            Corner(left.toFloat(), top.toFloat()),
            Corner(right.toFloat(), top.toFloat()),
            Corner(right.toFloat(), bottom.toFloat()),
            Corner(left.toFloat(), bottom.toFloat()),
        ),
        val vertical: Boolean = false,
    ) {
        /** Centre of the quad. Bands reading order without skewing on tilt. */
        val centerY: Float get() = corners.sumOf { it.y.toDouble() }.toFloat() / corners.size
        val centerX: Float get() = corners.sumOf { it.x.toDouble() }.toFloat() / corners.size
    }
    /** Full result: the joined [text] plus the individual [boxes] it came from. */
    data class OcrResult(val text: String, val boxes: List<TextBox>)
    private val lock = Mutex()
    private var etRecognizer: TextRecognizerEt? = null
    private var etInitTried = false
    /** True if the ET backend is present and could be loaded. */
    suspend fun isAvailable(): Boolean = lock.withLock { ensureEtInit() }
    /**
     * Recognise all text in [bitmap] and return it as a single string (lines
     * joined by newlines), or an empty string if nothing is found or the engine
     * is unavailable. The caller's [bitmap] is not recycled.
     */
    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text
    /** Like [recognize] but also returns the per-region [TextBox]es. */
    suspend fun recognizeDetailed(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        // The lock is held across the whole call, not just the handle read: `close`
        // frees the sessions and reading one afterwards is a use-after-free.
        lock.withLock {
            if (!ensureEtInit()) return@withContext OcrResult("", emptyList())
            val engine = etRecognizer ?: return@withContext OcrResult("", emptyList())
            // The ET path returns null on backend failure (missing `.pte`, no delegate,
            // failed run): fail closed to empty, never a fallback.
            val lines = engine.recognize(bitmap) ?: return@withContext OcrResult("", emptyList())
            val boxes = lines
                .asSequence()
                .filter { it.text.isNotBlank() }
                .map { toTextBox(it) }
                .filter { it.right > it.left && it.bottom > it.top }
                .toList()
            OcrResult(boxes.joinToString("\n") { it.text }.trim(), boxes)
        }
    }
    /** Release the backend. */
    fun close() {
        try { etRecognizer?.close() } catch (_: Exception) {}
        etRecognizer = null
        etInitTried = false
    }
    /**
     * Create the ExecuTorch engine once. False when the `.pte` is absent (or the module
     * fails to load) — unavailable, never a throw.
     */
    private fun ensureEtInit(): Boolean {
        etRecognizer?.let { return it.isAvailable }
        if (etInitTried) return false
        etInitTried = true
        val created = TextRecognizerEt(context.applicationContext)
        if (!created.isAvailable) {
            created.close()
            return false
        }
        etRecognizer = created
        return true
    }
    private companion object {
        /**
         * A [RecognizedLine]'s quad and its axis-aligned bounds.
         *
         * The bounds are rounded outwards rather than truncated: a caller that draws the
         * rect should cover the glyphs rather than clip them, and `toInt()` on a float
         * pixel edge loses up to a pixel on each side.
         */
        fun toTextBox(line: RecognizedLine): OcrEngine.TextBox = OcrEngine.TextBox(
            text = line.text.trim(),
            left = kotlin.math.floor(line.left).toInt(),
            top = kotlin.math.floor(line.top).toInt(),
            right = kotlin.math.ceil(line.right).toInt(),
            bottom = kotlin.math.ceil(line.bottom).toInt(),
            corners = line.corners.map { OcrEngine.Corner(it.first, it.second) },
            vertical = line.vertical,
        )
    }
}
