package com.vayunmathur.library.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.vayunmathur.ncnn.PpOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Shared on-device OCR engine using Baidu **PP-OCRv5 mobile** running on
 * **ncnn** (Tencent, BSD-3, CPU-only — no Google Play Services, no ML Kit). The
 * detection (DBNet) + recognition (CTC, full ~18k-char dictionary covering
 * Latin/CJK/digits/symbols) pipeline and its models live entirely inside the
 * `com.github.vayun-mathur:ncnn-android` AAR, which also bundles OpenCV for the
 * detection post-processing. Both the Photos and PDF apps depend on this one
 * module so they share the engine.
 *
 * The whole detect+recognize pass runs natively in one call; this class adapts
 * the result to the [OcrResult]/[TextBox] shape consumers expect and orders the
 * lines top-to-bottom for readable output.
 *
 * If the native library/models are unavailable the engine is inert (returns
 * empty text, never crashes) — call [isAvailable] to check up front.
 *
 * All heavy work runs off the main thread (Dispatchers.Default). Instances are
 * safe to reuse sequentially; concurrent calls are serialised internally.
 *
 * Usage:
 * ```
 * val ocr = OcrEngine(context)
 * val text = ocr.recognize(bitmap)   // suspend
 * ocr.close()                        // when done with a batch
 * ```
 */
class OcrEngine(private val context: Context) {

    /** One recognised, axis-aligned text region in source-bitmap pixel coordinates. */
    data class TextBox(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** Full result: the joined [text] plus the individual [boxes] it came from. */
    data class OcrResult(val text: String, val boxes: List<TextBox>)

    private val lock = Mutex()
    private var ocr: PpOcr? = null
    private var initTried = false

    /** True if the native library + models are present and could be loaded. */
    suspend fun isAvailable(): Boolean = lock.withLock { ensureInit() }

    /**
     * Recognise all text in [bitmap] and return it as a single string (lines
     * joined by newlines), or an empty string if nothing is found or the engine
     * is unavailable. The caller's [bitmap] is not recycled.
     */
    suspend fun recognize(bitmap: Bitmap): String = recognizeDetailed(bitmap).text

    /** Like [recognize] but also returns the per-region [TextBox]es. */
    suspend fun recognizeDetailed(bitmap: Bitmap): OcrResult = withContext(Dispatchers.Default) {
        lock.withLock {
            if (!ensureInit()) return@withContext OcrResult("", emptyList())
            val engine = ocr ?: return@withContext OcrResult("", emptyList())
            try {
                val boxes = engine.recognize(bitmap)
                    .asSequence()
                    .filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
                    // Reading order: top-to-bottom, then left-to-right within a row band.
                    .sortedWith(compareBy({ it.top / READING_ROW_BAND }, { it.left }))
                    .map { TextBox(it.text.trim(), it.left, it.top, it.right, it.bottom) }
                    .toList()
                OcrResult(boxes.joinToString("\n") { it.text }.trim(), boxes)
            } catch (e: Exception) {
                Log.e(TAG, "OCR failed", e)
                OcrResult("", emptyList())
            }
        }
    }

    /** Release the native models. */
    fun close() {
        try { ocr?.close() } catch (_: Exception) {}
        ocr = null
        initTried = false
    }

    /** Create the native PP-OCRv5 engine once. */
    private fun ensureInit(): Boolean {
        if (ocr != null) return true
        if (initTried) return false
        initTried = true
        return try {
            ocr = PpOcr(context)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialise ncnn PP-OCRv5", e)
            ocr = null
            false
        }
    }

    companion object {
        private const val TAG = "OcrEngine"
        private const val READING_ROW_BAND = 16
    }
}
