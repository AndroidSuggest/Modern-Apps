package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One recognised line of text, in source-bitmap pixel coordinates.
 *
 * [corners] are the four corners of the region's oriented quad, ordered so that corner 0 to
 * 1 runs along the text and 0 to 3 spans its height. A detected region is a *rotated*
 * rectangle, not an upright one, so the quad carries information a rect would lose - and
 * [left]/[top]/[right]/[bottom] are its axis-aligned bounds for callers that only need one.
 */
data class RecognizedLine(
    val text: String,
    val confidence: Float,
    val corners: List<Pair<Float, Float>>,
    val vertical: Boolean,
) {
    val left: Float get() = corners.minOf { it.first }
    val top: Float get() = corners.minOf { it.second }
    val right: Float get() = corners.maxOf { it.first }
    val bottom: Float get() = corners.maxOf { it.second }
}

/**
 * On-device text recognition: PP-OCRv5 mobile detection and recognition on the reduced ONNX
 * Runtime build.
 *
 * The detector is DBNet over a PP-HGNetV2 backbone and the recogniser is a PP-LCNetV3
 * backbone into a two-block transformer with a CTC head. The recogniser is the **latin**
 * model - 836 characters covering Latin-script languages, no CJK - which is what keeps it
 * under 8 MB.
 *
 * Replaces the Vulkan `MlNative` path (`createPpocr`/`recognizeText`): both graphs are the
 * upstream exports (`ppocr_det`: `x [B,3,H,W]` in, `fetch_name_0 [B,1,H,W]` probability map
 * out; `ppocr_rec`: `x [B,3,48,W]` in, `fetch_name_0 [B,T,838]` logits out). Everything
 * between the two networks — DBNet regions, the rotated crop, CTC collapse, reading order —
 * is ported from `post::{dbnet,crop,ctc,ocr}` and now runs here in Kotlin rather than
 * natively, crossing one session per region instead of one JNI call per bitmap.
 *
 * # Vulkan-first inference (task 6)
 *
 * Landed `VulkanSessions`/`VulkanBridge` API (same package
 * `com.vayunmathur.library.ml`): `isUsable()`, `open(key, readModel, baseDir)`,
 * `preflight(key, readModel)` (null = clear), `close(handle)` on
 * `VulkanSessions`; packed little-endian `run` plus `lastOutputNames` /
 * `lastOutputShapes` on `VulkanBridge`.
 *
 * Vulkan only runs the two graphs. Preprocess (`OnnxPreprocess.readablePixels`,
 * `letterboxPlanar`, the BGR recognition normalisation below) and post (DBNet regions,
 * rotated crop/warp, CTC decode, reading order) stay in Kotlin and are shared by both
 * paths. Detection and recognition each hold their own `vulkanHandle` next to their ORT
 * session; every graph call tries Vulkan first and falls back to ORT, so mixed states
 * (det on Vulkan, rec on ORT and vice versa) all serve.
 *
 * # Availability
 *
 * The constructor never throws. [isAvailable] is false when a model is absent or has an
 * operator outside the reduced build - and then [recognize] returns an empty list.
 *
 * # Threading
 *
 * Not thread-safe, and holding a lock across the whole of [recognize] and [close] is
 * required rather than advisable.
 *
 * Not cheap to construct either - two sessions - so build one per batch of images and
 * [close] it, rather than one per image.
 */
class TextRecognizer(
    context: Context,
    detectionAsset: String = DETECTION_ASSET,
    recognitionAsset: String = RECOGNITION_ASSET,
    dictionaryAsset: String = DICTIONARY_ASSET,
) : AutoCloseable {
    private val app = context.applicationContext
    private val detAsset = detectionAsset
    private val recAsset = recognitionAsset
    private val lock = Any()

    @Volatile private var det: OrtSession? = null
    @Volatile private var rec: OrtSession? = null
    @Volatile private var detVulkanHandle: Long = 0L
    @Volatile private var recVulkanHandle: Long = 0L
    @Volatile private var dictionary: List<String>? = null
    @Volatile private var loadTried = false

    init {
        try {
            val text = app.assets.open(dictionaryAsset).useOrt { it.readBytes() }.decodeToString()
            dictionary = parseDictionary(text)
        } catch (e: Throwable) {
            Log.e(TAG, "cannot open the PP-OCRv5 dictionary", e)
            dictionary = null
        }
    }

    /** True if both networks came up and the dictionary parsed. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Recognise every line in [bitmap], in reading order.
     *
     * Returns an empty list when there is no text, when the engine is unavailable, or when
     * the pass failed - all three are "no text here" to a caller. The caller's [bitmap] is
     * not recycled.
     */
    fun recognize(bitmap: Bitmap): List<RecognizedLine> {
        if (!ensure()) return emptyList()
        val dict = dictionary ?: return emptyList()
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return emptyList()
        try {
            val fit = OnnxPreprocess.SquareFit.of(readable.width, readable.height, DET_SIDE)
            val input = OnnxPreprocess.letterboxPlanar(
                pixels, readable.width, readable.height, DET_SIDE, fit, OnnxPreprocess.PPOCR_DET,
            )
            // Vulkan-first for detection; ORT fallback per call.
            val map: FloatArray = runCatching { vulkanDetMap(detVulkanHandle, input) }.getOrNull()
                ?: ortDetMap(input)
                ?: return emptyList()
            val regions = dbnetRegions(map, DET_SIDE, DET_SIDE, fit)
            val lines = ArrayList<RecognizedLine>(regions.size)
            for (region in regions) {
                val line = recognizeRegion(
                    region, pixels, readable.width, readable.height, dict,
                ) ?: continue
                lines += line
            }
            orderReading(lines)
            return lines
        } catch (e: Throwable) {
            Log.e(TAG, "OCR failed", e)
            return emptyList()
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    /** Free both sessions and both Vulkan handles. Idempotent. */
    override fun close() {
        synchronized(lock) {
            det = null
            rec = null
            OnnxSessions.close("asset:$detAsset")
            OnnxSessions.close("asset:$recAsset")
            val detHandle = detVulkanHandle
            val recHandle = recVulkanHandle
            detVulkanHandle = 0L
            recVulkanHandle = 0L
            if (detHandle != 0L) {
                runCatching { VulkanSessions.close(detHandle) }
            }
            if (recHandle != 0L && recHandle != detHandle) {
                runCatching { VulkanSessions.close(recHandle) }
            }
        }
    }

    /**
     * Best-effort Vulkan open for one asset graph; leaves the handle at 0L on
     * any failure so ORT stays the fallback. Caller must hold [lock].
     */
    private fun tryVulkanLocked(asset: String, assign: (Long) -> Unit) {
        try {
            if (!VulkanSessions.isUsable()) return
            val key = "asset:$asset"
            if (key !in VulkanSessions.allowlist) return
            val modelBytes = try {
                app.assets.open(asset).use { it.readBytes() }
            } catch (e: Throwable) {
                Log.w(TAG, "cannot read $asset for vulkan preflight", e)
                return
            }
            val problem = VulkanSessions.preflight(key) { modelBytes }
            if (problem != null) {
                Log.w(TAG, "vulkan preflight skipped for $asset: $problem")
                return
            }
            val handle = VulkanSessions.open(key, { modelBytes }, null)
            if (handle != 0L) {
                assign(handle)
                Log.i(TAG, "vulkan session open for $key")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "vulkan open failed for $asset, using ORT", e)
        }
    }

    private fun ensure(): Boolean {
        if (dictionary == null) return false
        if ((det != null || detVulkanHandle != 0L) && (rec != null || recVulkanHandle != 0L)) return true
        synchronized(lock) {
            if (dictionary == null) return false
            if ((det != null || detVulkanHandle != 0L) && (rec != null || recVulkanHandle != 0L)) return true
            if (loadTried) return (det != null || detVulkanHandle != 0L) && (rec != null || recVulkanHandle != 0L)
            loadTried = true
            // Vulkan-first: one handle per graph.
            tryVulkanLocked(detAsset) { detVulkanHandle = it }
            tryVulkanLocked(recAsset) { recVulkanHandle = it }
            // ORT fallback is always attempted so either path can serve each stage.
            runCatching {
                det = OnnxSessions.openAsset(app, detAsset)
            }
            runCatching {
                rec = OnnxSessions.openAsset(app, recAsset)
            }
            return (det != null || detVulkanHandle != 0L) && (rec != null || recVulkanHandle != 0L)
        }
    }

    // -- Vulkan graph runs ----------------------------------------------------

    /**
     * Detection probability map from Vulkan, or null to fall back to ORT.
     * Single output (`fetch_name_0 [B,1,H,W]`); validated against
     * [VulkanBridge.lastOutputShapes] when available.
     */
    private fun vulkanDetMap(handle: Long, input: FloatArray): FloatArray? {
        if (handle == 0L) return null
        val outBytes = try {
            VulkanBridge.run(
                handle,
                arrayOf(DET_INPUT),
                intArrayOf(DTYPE_F32),
                longArrayOf(1, 3, DET_SIDE.toLong(), DET_SIDE.toLong()),
                longArrayOf(0),
                floatsToLe(input),
            )
        } catch (_: Throwable) {
            return null
        } ?: return null
        val flat = leToFloats(outBytes)
        if (flat.isEmpty()) return null
        runCatching { VulkanBridge.lastOutputShapes(handle) }.getOrNull()?.let { shapes ->
            if (shapes.isNotEmpty()) {
                var product = 1L
                for (dim in shapes) product *= if (dim < 0) 1L else dim
                // Batch/singleton dims collapse to the H*W map on the Kotlin side.
                if (flat.size != product.toInt() && flat.size != DET_SIDE * DET_SIDE) return null
            }
        }
        if (flat.size != DET_SIDE * DET_SIDE) {
            // Single-output graph: accept a leading batch/channel wrap, reject anything else.
            if (flat.size < DET_SIDE * DET_SIDE) return null
            return flat.copyOf(DET_SIDE * DET_SIDE)
        }
        return flat
    }

    /** Detection probability map from ORT, or null on failure. */
    private fun ortDetMap(input: FloatArray): FloatArray? {
        val detSession = det ?: return null
        val env = OrtEnvironment.getEnvironment()
        return try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, DET_SIDE.toLong(), DET_SIDE.toLong())).useOrt { tensor ->
                detSession.run(mapOf(DET_INPUT to tensor)).useOrt { result ->
                    val map = FloatArray(DET_SIDE * DET_SIDE)
                    ((result.get(DET_OUTPUT).get()) as OnnxTensor).floatBuffer.get(map)
                    map
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OCR detection failed", e)
            null
        }
    }

    /**
     * Recognition logits from Vulkan, or null to fall back to ORT.
     * Single output (`fetch_name_0 [B,T,838]` flattened to `T*838`).
     */
    private fun vulkanRecLogits(handle: Long, input: FloatArray): FloatArray? {
        if (handle == 0L) return null
        val outBytes = try {
            VulkanBridge.run(
                handle,
                arrayOf(REC_INPUT),
                intArrayOf(DTYPE_F32),
                longArrayOf(1, 3, REC_HEIGHT.toLong(), REC_WIDTH.toLong()),
                longArrayOf(0),
                floatsToLe(input),
            )
        } catch (_: Throwable) {
            return null
        } ?: return null
        val flat = leToFloats(outBytes)
        if (flat.isEmpty()) return null
        runCatching { VulkanBridge.lastOutputShapes(handle) }.getOrNull()?.let { shapes ->
            if (shapes.isNotEmpty()) {
                var product = 1L
                for (dim in shapes) product *= if (dim < 0) 1L else dim
                if (flat.size != product.toInt() && flat.size != REC_TIMESTEPS * LOGITS) return null
            }
        }
        if (flat.size != REC_TIMESTEPS * LOGITS) return null
        return flat
    }

    /** Recognition logits from ORT, or null on failure. */
    private fun ortRecLogits(input: FloatArray): FloatArray? {
        val recSession = rec ?: return null
        val env = OrtEnvironment.getEnvironment()
        return try {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, REC_HEIGHT.toLong(), REC_WIDTH.toLong())).useOrt { tensor ->
                recSession.run(mapOf(REC_INPUT to tensor)).useOrt { result ->
                    val flat = FloatArray(REC_TIMESTEPS * LOGITS)
                    ((result.get(REC_OUTPUT).get()) as OnnxTensor).floatBuffer.get(flat)
                    flat
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "OCR recognition failed", e)
            null
        }
    }

    // -- Dictionary -----------------------------------------------------------

    private fun parseDictionary(text: String): List<String>? {
        val entries = text.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .toList()
        if (entries.size != DICTIONARY_ENTRIES) {
            Log.e(TAG, "${entries.size} dictionary entries, expected $DICTIONARY_ENTRIES")
            return null
        }
        if (entries.any { it.codePointCount(0, it.length) != 1 }) {
            Log.e(TAG, "a dictionary entry is not a single character")
            return null
        }
        return entries + " "
    }

    // -- DBNet ----------------------------------------------------------------

    private data class RotatedRect(
        var cx: Float, var cy: Float,
        var width: Float, var height: Float,
        var angle: Float,
    ) {
        fun normalise() {
            while (angle >= 0f) {
                angle -= 90f
                val t = width; width = height; height = t
            }
            while (angle < -90f) {
                angle += 90f
                val t = width; width = height; height = t
            }
        }
    }

    private data class Region(val rect: RotatedRect, val score: Float, val vertical: Boolean)

    private fun dbnetRegions(
        probability: FloatArray,
        width: Int,
        height: Int,
        fit: OnnxPreprocess.SquareFit,
    ): List<Region> {
        val cells = width * height
        val text = BooleanArray(cells) { probability[it] >= DB_THRESHOLD }
        val labelled = BooleanArray(cells)
        val stack = ArrayList<Int>(1024)
        val boundary = ArrayList<Pair<Float, Float>>(256)
        val found = ArrayList<Region?>(64)
        for (start in 0 until cells) {
            if (found.size >= MAX_CANDIDATES) break
            if (!text[start] || labelled[start]) continue
            labelled[start] = true
            stack.clear()
            stack += start
            boundary.clear()
            var total = 0.0
            var count = 0
            while (stack.isNotEmpty()) {
                val at = stack.removeAt(stack.lastIndex)
                val x = at % width
                val y = at / width
                total += probability[at]
                count++
                val edge = x == 0 || y == 0 || x + 1 == width || y + 1 == height ||
                    !text[at - 1] || !text[at + 1] || !text[at - width] || !text[at + width]
                if (edge) boundary += Pair(x.toFloat(), y.toFloat())
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (text[next] && !labelled[next]) {
                            labelled[next] = true
                            stack += next
                        }
                    }
                }
            }
            val score = if (count > 0) (total / count).toFloat() else 0f
            if (score < BOX_THRESHOLD) {
                found += null
                continue
            }
            val rect = minAreaRect(boundary) ?: run { found += null; continue }
            if (max(rect.width, rect.height) < MIN_SIZE) {
                found += null
                continue
            }
            if (rect.width > rect.height) {
                val t = rect.width; rect.width = rect.height; rect.height = t
                rect.angle += 90f
            }
            val vertical = rect.height > rect.width * ELONGATED && rect.angle in -30f..30f
            if (vertical || rect.angle <= 0f) rect.angle += 180f
            rect.height += rect.width * (ENLARGE_RATIO - 1f)
            rect.width *= ENLARGE_RATIO
            rect.cx = (rect.cx - fit.offsetX) / fit.scale
            rect.cy = (rect.cy - fit.offsetY) / fit.scale
            rect.width /= fit.scale
            rect.height /= fit.scale
            found += Region(rect, score, vertical)
        }
        return found.filterNotNull()
    }

    private fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 3) return points.toList()
        val sorted = points.sortedWith(compareBy({ it.first }, { it.second })).distinct()
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        val hull = ArrayList<Pair<Float, Float>>(sorted.size + 1)
        for (pass in 0..1) {
            val lower = hull.size
            val iter = if (pass == 0) sorted else sorted.asReversed()
            for (point in iter) {
                while (hull.size >= lower + 2) {
                    val a = hull[hull.size - 2]
                    val b = hull[hull.size - 1]
                    if (cross(a, b, point) > 0f) break
                    hull.removeAt(hull.lastIndex)
                }
                hull += point
            }
            hull.removeAt(hull.lastIndex)
        }
        return hull
    }

    private fun minAreaRect(points: List<Pair<Float, Float>>): RotatedRect? {
        val hull = convexHull(points)
        if (hull.isEmpty()) return null
        val first = hull[0]
        if (hull.size == 1) {
            return RotatedRect(first.first, first.second, 0f, 0f, 0f).also { it.normalise() }
        }
        var best: RotatedRect? = null
        var bestArea = Float.MAX_VALUE
        for (index in hull.indices) {
            val a = hull[index]
            val b = hull[(index + 1) % hull.size]
            val dx = b.first - a.first
            val dy = b.second - a.second
            val length = sqrt(dx * dx + dy * dy)
            if (length < 1e-9f) continue
            val ux = dx / length
            val uy = dy / length
            var lowU = Float.MAX_VALUE; var highU = -Float.MAX_VALUE
            var lowV = Float.MAX_VALUE; var highV = -Float.MAX_VALUE
            for ((x, y) in hull) {
                val u = x * ux + y * uy
                val v = -x * uy + y * ux
                lowU = min(lowU, u); highU = max(highU, u)
                lowV = min(lowV, v); highV = max(highV, v)
            }
            val area = (highU - lowU) * (highV - lowV)
            if (area >= bestArea) continue
            bestArea = area
            val midU = (lowU + highU) * 0.5f
            val midV = (lowV + highV) * 0.5f
            best = RotatedRect(
                midU * ux - midV * uy, midU * uy + midV * ux,
                highU - lowU, highV - lowV,
                atan2(dy, dx) * 180f / Math.PI.toFloat(),
            ).also { it.normalise() }
        }
        return best ?: RotatedRect(first.first, first.second, 0f, 0f, 0f).also { it.normalise() }
    }

    // -- Crop -----------------------------------------------------------------

    private fun rectCorners(rect: RotatedRect): Array<Pair<Float, Float>> {
        val radians = rect.angle * Math.PI.toFloat() / 180f
        val sin = sin(radians)
        val cos = cos(radians)
        val along = rect.height * 0.5f
        val across = rect.width * 0.5f
        val alongX = -sin; val alongY = cos
        val acrossX = cos; val acrossY = sin
        fun corner(a: Float, b: Float) = Pair(
            rect.cx + alongX * a + acrossX * b,
            rect.cy + alongY * a + acrossY * b,
        )
        return arrayOf(
            corner(-along, -across), corner(along, -across),
            corner(along, across), corner(-along, across),
        )
    }

    private fun cropWidth(region: Region): Int {
        val short = max(region.rect.width, 1f)
        val long = max(region.rect.height, 1f)
        val scaled = max(REC_HEIGHT * long / short, 1f)
        val wanted = min(kotlin.math.round(scaled).toInt(), REC_WIDTH).coerceAtLeast(WIDTH_MULTIPLE)
        return ((wanted + WIDTH_MULTIPLE - 1) / WIDTH_MULTIPLE) * WIDTH_MULTIPLE
    }

    private fun warp(
        pixels: IntArray, width: Int, height: Int,
        corners: Array<Pair<Float, Float>>, outW: Int, outH: Int,
    ): IntArray {
        val origin = corners[0]
        val along = Pair(corners[1].first - corners[0].first, corners[1].second - corners[0].second)
        val across = Pair(corners[3].first - corners[0].first, corners[3].second - corners[0].second)
        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val v = (y + 0.5f) / outH
            for (x in 0 until outW) {
                val u = (x + 0.5f) / outW
                val sx = origin.first + along.first * u + across.first * v
                val sy = origin.second + along.second * u + across.second * v
                out[y * outW + x] = bilinear(pixels, width, height, sx, sy)
            }
        }
        return out
    }

    private fun bilinear(pixels: IntArray, width: Int, height: Int, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, (width - 1).toFloat())
        val cy = y.coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val wx = cx - x0
        val wy = cy - y0
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        fun at(px: Int, py: Int): Int = pixels[py * width + px]
        fun channel(shift: Int): Int {
            fun take(packed: Int): Float = ((packed shr shift) and 0xFF).toFloat()
            val top = take(at(x0, y0)) + (take(at(x1, y0)) - take(at(x0, y0))) * wx
            val bottom = take(at(x0, y1)) + (take(at(x1, y1)) - take(at(x0, y1))) * wx
            return (top + (bottom - top) * wy).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    // -- Recognition ----------------------------------------------------------

    private fun recognizeRegion(
        region: Region,
        pixels: IntArray, width: Int, height: Int,
        dict: List<String>,
    ): RecognizedLine? {
        val corners = rectCorners(region.rect)
        val content = cropWidth(region).coerceIn(WIDTH_MULTIPLE, REC_WIDTH)
        val cropped = warp(pixels, width, height, corners, content, REC_HEIGHT)
        val strip = if (content == REC_WIDTH) {
            cropped
        } else {
            val pad = (0xFF shl 24) or (PAD_LEVEL shl 16) or (PAD_LEVEL shl 8) or PAD_LEVEL
            val out = IntArray(REC_WIDTH * REC_HEIGHT) { pad }
            for (row in 0 until REC_HEIGHT) {
                cropped.copyInto(out, row * REC_WIDTH, row * content, row * content + content)
            }
            out
        }
        // Recognition normalisation is BGR `(v / 255 - 0.5) / 0.5`, planar, 48 x 320.
        val plane = REC_WIDTH * REC_HEIGHT
        val input = FloatArray(3 * plane)
        for (y in 0 until REC_HEIGHT) {
            for (x in 0 until REC_WIDTH) {
                val argb = strip[y * REC_WIDTH + x]
                val ch = intArrayOf(argb and 0xFF, (argb shr 8) and 0xFF, (argb shr 16) and 0xFF)
                for (c in 0..2) {
                    input[c * plane + y * REC_WIDTH + x] = (ch[c] / 255f - 0.5f) / 0.5f
                }
            }
        }
        // Vulkan-first per region; ORT fallback per region so one failed crop
        // never fails the whole bitmap.
        val flat: FloatArray = runCatching { vulkanRecLogits(recVulkanHandle, input) }.getOrNull()
            ?: ortRecLogits(input)
            ?: return null
        // Export is batch-major [T, 838]; the decode reads class-major [838, T].
        val used = content / WIDTH_MULTIPLE
        val decoded = ctcDecode(flat, REC_TIMESTEPS, used, dict) ?: return null
        val text = decoded.first.trim()
        if (text.isEmpty()) return null
        val quad = corners.map { Pair(it.first, it.second) }
        return RecognizedLine(text, decoded.second, quad, region.vertical)
    }

    private fun ctcDecode(
        batchMajor: FloatArray, timesteps: Int, used: Int, dict: List<String>,
    ): Pair<String, Float>? {
        if (timesteps == 0 || used > timesteps) return null
        fun at(label: Int, step: Int): Float = batchMajor[step * LOGITS + label]
        val text = StringBuilder()
        var total = 0.0
        var kept = 0
        var previous = -1
        for (step in 0 until used) {
            var best = 0
            var peak = at(0, step)
            for (label in 1 until LOGITS) {
                val value = at(label, step)
                if (value > peak) {
                    peak = value
                    best = label
                }
            }
            if (best != previous) {
                val character = if (best == 0) null else dict.getOrNull(best - 1)
                if (character != null) {
                    var denominator = 0.0
                    for (label in 0 until LOGITS) {
                        denominator += Math.exp((at(label, step) - peak).toDouble())
                    }
                    text.append(character)
                    total += 1.0 / denominator
                    kept++
                }
            }
            previous = best
        }
        val confidence = if (kept > 0) (total / kept).toFloat() else 0f
        return Pair(text.toString(), confidence)
    }

    private fun orderReading(lines: MutableList<RecognizedLine>) {
        fun centre(line: RecognizedLine): Pair<Float, Float> {
            val sx = line.corners.sumOf { it.first.toDouble() }.toFloat() / 4f
            val sy = line.corners.sumOf { it.second.toDouble() }.toFloat() / 4f
            return Pair(sx, sy)
        }
        lines.sortWith { a, b ->
            val (ax, ay) = centre(a)
            val (bx, by) = centre(b)
            val bandA = (ay / ROW_BAND).toInt()
            val bandB = (by / ROW_BAND).toInt()
            if (bandA != bandB) bandA - bandB else ax.compareTo(bx)
        }
    }

    private companion object {
        private const val TAG = "TextRecognizer"
        const val DETECTION_ASSET = "ppocr_det.onnx"
        const val RECOGNITION_ASSET = "ppocr_rec.onnx"
        const val DICTIONARY_ASSET = "ppocr_keys.txt"

        private const val DET_SIDE = 960
        private const val DET_INPUT = "x"
        private const val DET_OUTPUT = "fetch_name_0"
        private const val REC_INPUT = "x"
        private const val REC_OUTPUT = "fetch_name_0"
        private const val REC_HEIGHT = 48
        private const val REC_WIDTH = 320
        private const val WIDTH_MULTIPLE = 8
        private const val REC_TIMESTEPS = 40
        private const val LOGITS = 838
        private const val DICTIONARY_ENTRIES = 836

        private const val DB_THRESHOLD = 0.3f
        private const val BOX_THRESHOLD = 0.6f
        private const val ENLARGE_RATIO = 1.95f
        private const val MIN_SIZE = 3.0f
        private const val MAX_CANDIDATES = 1000
        private const val ELONGATED = 2.7f
        private const val PAD_LEVEL = 128
        private const val ROW_BAND = 16f

        /** ONNX TensorProto FLOAT, as carried in the Vulkan `dtypes` array. */
        private const val DTYPE_F32 = 1

        private fun floatsToLe(values: FloatArray): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(values.size * 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in values) buf.putFloat(v)
            return buf.array()
        }

        private fun leToFloats(bytes: ByteArray): FloatArray {
            val out = FloatArray(bytes.size / 4)
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .asFloatBuffer().get(out)
            return out
        }
    }
}
