package com.vayunmathur.library.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Face detection and face embedding, for `:photos`'s people clustering.
 *
 * **SCRFD 500M** finds faces and their five keypoints; **MobileFaceNet** (`w600k_mbf`,
 * ArcFace-trained) turns an aligned crop into a 512-d embedding. Both run on the reduced
 * ONNX Runtime build — see `photos/src/main/assets/README.md` for provenance and the
 * InsightFace licence.
 *
 * These replace `com.vayunmathur.ncnn.FaceDetector` and `FaceEmbedder` (and the Vulkan
 * `MlNative` path between them). Same two models, re-sourced from a licensed ONNX export.
 * The embeddings are **not** bit-identical to ncnn's, so `FaceRecognizer.EMBEDDER_VERSION`
 * has to be bumped alongside this — the stored index would otherwise mix two incompatible
 * embedding spaces and cluster nonsense.
 *
 * # SCRFD decode
 *
 * The export declares nine outputs: three score maps, three box maps, three keypoint maps
 * at strides 8, 16 and 32. Each is flattened from a `[H, W, anchors]` transpose
 * (`perm [2,3,0,1]`), so the layout is **cell-major**: element `(row, col, anchor)` sits
 * at `flat[(row * w + col) * 2 + anchor]`. This differs from the MAML path, which read
 * the convolution outputs before the transpose and decoded anchor-major — ported
 * accordingly. The box math (centre at `col * stride` with no half-cell offset, `+1` on
 * extents, five keypoints as stride-scaled offsets) and the greedy stable NMS
 * (score ≥ 0.5 keep, IoU strictly above 0.45 suppresses) mirror `post::nms`.
 *
 * The input is letterboxed into a 640×640 square (`OnnxPreprocess.SquareFit`, scale
 * `640 / long side`, truncated, centred, border raw-zero through the SCRFD
 * normalisation), and boxes are mapped back onto the source bitmap as `0..1` fractions.
 */

/** One detected face. Every coordinate is a fraction of the source bitmap, `0..1`. */
data class DetectedFaceBox(
    /** Left edge. */
    val left: Float,
    /** Top edge. */
    val top: Float,
    /** Right edge. */
    val right: Float,
    /** Bottom edge. */
    val bottom: Float,
    /** Left eye, in the detector's keypoint order rather than the viewer's left. */
    val leftEyeX: Float,
    /** Left eye. */
    val leftEyeY: Float,
    /** Right eye. */
    val rightEyeX: Float,
    /** Right eye. */
    val rightEyeY: Float,
    /** Detector confidence, post-sigmoid. */
    val score: Float,
)

/**
 * SCRFD 500M face detection.
 *
 * Not thread-safe: [detect] and [close] must not overlap. See the note in
 * [NativeSegmenter] about why the lock lives with the caller's threading rather than here.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.onnx` in the app's assets.
 */
class FaceDetector(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {
    private val app = context.applicationContext
    private val asset = assetName
    private val lock = Any()

    @Volatile private var session: OrtSession? = null
    @Volatile private var loadTried = false

    /** Whether the detector came up. False means people clustering is off. */
    val isAvailable: Boolean get() = ensure()

    /**
     * Every face in [bitmap], or an empty list if there are none or the detector is
     * unavailable.
     *
     * [bitmap] may be any size: it is letterboxed and normalised here.
     */
    fun detect(bitmap: Bitmap): List<DetectedFaceBox> {
        if (!ensure()) return emptyList()
        val live = session ?: return emptyList()
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return emptyList()
        try {
            val fit = OnnxPreprocess.SquareFit.of(readable.width, readable.height, LONG_SIDE)
            val input = OnnxPreprocess.letterboxPlanar(
                pixels, readable.width, readable.height, LONG_SIDE, fit, OnnxPreprocess.SCRFD,
            )
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, LONG_SIDE.toLong(), LONG_SIDE.toLong())).use { tensor ->
                live.run(mapOf(INPUT to tensor)).use { result ->
                    fun floats(name: String): FloatArray {
                        val out = FloatArray(sessionOutputSize(live, name))
                        ((result.get(name).get()) as OnnxTensor).floatBuffer.get(out)
                        return out
                    }
                    val faces = ArrayList<Face>()
                    for (level in STRIDES.indices) {
                        decode(
                            floats(SCORES[level]), floats(BOXES[level]), floats(KEYPOINTS[level]),
                            STRIDES[level], LONG_SIDE / STRIDES[level], faces,
                        )
                    }
                    suppress(faces)
                    return faces.map { toSource(it, fit, readable.width, readable.height) }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "scrfd inference failed", e)
            return emptyList()
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            session = null
            OnnxSessions.close(sessionKey())
        }
    }

    private fun sessionKey() = "asset:$asset"

    private fun ensure(): Boolean {
        session?.let { return true }
        synchronized(lock) {
            session?.let { return true }
            if (loadTried) return false
            loadTried = true
            session = OnnxSessions.openAsset(app, asset)
            return session != null
        }
    }

    private data class Face(
        val score: Float,
        val bounds: FloatArray,
        val keypoints: Array<FloatArray>,
    )

    private fun sessionOutputSize(session: OrtSession, name: String): Int {
        val info = session.outputInfo[name]?.info as? ai.onnxruntime.TensorInfo
            ?: error("no output $name")
        return info.shape.map { if (it < 0) 1 else it.toInt() }.reduce(Int::times)
    }

    /** Decode one stride's maps into proposals. Cell-major: `(row, col, anchor)`. */
    private fun decode(
        score: FloatArray,
        bbox: FloatArray,
        keypoints: FloatArray,
        stride: Int,
        cells: Int,
        out: MutableList<Face>,
    ) {
        for (anchor in 0 until ANCHORS) {
            for (row in 0 until cells) {
                for (col in 0 until cells) {
                    val cell = row * cells + col
                    val s = score[cell * ANCHORS + anchor]
                    if (s < SCORE_THRESHOLD) continue
                    val cx = (col * stride).toFloat()
                    val cy = (row * stride).toFloat()
                    val b = cell * ANCHORS * 4 + anchor * 4
                    val x0 = cx - bbox[b] * stride
                    val y0 = cy - bbox[b + 1] * stride
                    val x1 = cx + bbox[b + 2] * stride + 1f
                    val y1 = cy + bbox[b + 3] * stride + 1f
                    val kBase = cell * ANCHORS * KEYPOINT_COUNT * 2 + anchor * KEYPOINT_COUNT * 2
                    val points = Array(KEYPOINT_COUNT) { k ->
                        floatArrayOf(
                            cx + keypoints[kBase + k * 2] * stride,
                            cy + keypoints[kBase + k * 2 + 1] * stride,
                        )
                    }
                    out += Face(s, floatArrayOf(x0, y0, x1, y1), points)
                }
            }
        }
    }

    /** Greedy stable NMS: by score, drop overlaps with IoU strictly above threshold. */
    private fun suppress(faces: MutableList<Face>) {
        faces.sortByDescending { it.score }
        val kept = ArrayList<Face>(faces.size)
        for (face in faces) {
            val overlaps = kept.any { other ->
                val iw = min(face.bounds[2], other.bounds[2]) - max(face.bounds[0], other.bounds[0])
                val ih = min(face.bounds[3], other.bounds[3]) - max(face.bounds[1], other.bounds[1])
                if (iw <= 0f || ih <= 0f) return@any false
                val intersection = iw * ih
                val union = area(face) + area(other) - intersection
                union > 0f && intersection / union > IOU_THRESHOLD
            }
            if (!overlaps) kept += face
        }
        faces.clear()
        faces.addAll(kept)
    }

    private fun area(face: Face): Float {
        val w = max(face.bounds[2] - face.bounds[0], 0f)
        val h = max(face.bounds[3] - face.bounds[1], 0f)
        return w * h
    }

    /** Map out of letterboxed space onto the source bitmap as `0..1` fractions. */
    private fun toSource(face: Face, fit: OnnxPreprocess.SquareFit, width: Int, height: Int): DetectedFaceBox {
        val limitX = (max(width, 1) - 1).toFloat()
        val limitY = (max(height, 1) - 1).toFloat()
        fun undo(value: Float, pad: Int, limit: Float): Float =
            ((value - pad) / fit.scale).coerceIn(0f, limit)
        fun nx(value: Float, pad: Int): Float = undo(value, pad, limitX) / width
        fun ny(value: Float, pad: Int): Float = undo(value, pad, limitY) / height
        val leftEye = face.keypoints[0]
        val rightEye = face.keypoints[1]
        return DetectedFaceBox(
            left = nx(face.bounds[0], fit.offsetX),
            top = ny(face.bounds[1], fit.offsetY),
            right = nx(face.bounds[2], fit.offsetX),
            bottom = ny(face.bounds[3], fit.offsetY),
            leftEyeX = nx(leftEye[0], fit.offsetX),
            leftEyeY = ny(leftEye[1], fit.offsetY),
            rightEyeX = nx(rightEye[0], fit.offsetX),
            rightEyeY = ny(rightEye[1], fit.offsetY),
            score = face.score,
        )
    }

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "scrfd_500m.onnx"
        private const val TAG = "FaceDetector"
        private const val LONG_SIDE = 640
        private const val INPUT = "input.1"
        private const val ANCHORS = 2
        private const val KEYPOINT_COUNT = 5
        private const val SCORE_THRESHOLD = 0.5f
        private const val IOU_THRESHOLD = 0.45f
        private val STRIDES = intArrayOf(8, 16, 32)
        private val SCORES = arrayOf("443", "468", "493")
        private val BOXES = arrayOf("446", "471", "496")
        private val KEYPOINTS = arrayOf("449", "474", "499")
    }
}

/**
 * MobileFaceNet face embedding.
 *
 * Takes an aligned 112×112 crop and returns 512 floats. The result is **not**
 * L2-normalised — `FaceRecognizer` does that, as it did with ncnn.
 *
 * Not thread-safe: [embed] and [close] must not overlap.
 *
 * @param context used only to read the asset; not retained.
 * @param assetName the `.onnx` in the app's assets.
 */
class FaceEmbedder(context: Context, assetName: String = DEFAULT_ASSET) : AutoCloseable {
    private val app = context.applicationContext
    private val asset = assetName
    private val lock = Any()

    @Volatile private var session: OrtSession? = null
    @Volatile private var loadTried = false

    /** Whether the embedder came up. */
    val isAvailable: Boolean get() = ensure()

    /**
     * The 512-d embedding of an aligned [bitmap], or null on failure.
     *
     * [bitmap] should already be the canonical 112×112 crop; anything else is stretched
     * here, which for a face crop is not what the caller wanted.
     */
    fun embed(bitmap: Bitmap): FloatArray? {
        if (!ensure()) return null
        val live = session ?: return null
        val (pixels, readable) = OnnxPreprocess.readablePixels(bitmap) ?: return null
        try {
            val input = OnnxPreprocess.stretchPlanar(
                pixels, readable.width, readable.height,
                SIZE, SIZE, OnnxPreprocess.FACE_EMBED,
            )
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
                live.run(mapOf(INPUT to tensor)).use { result ->
                    val out = result[0] as OnnxTensor
                    val vec = FloatArray(EMBEDDING_LENGTH)
                    out.floatBuffer.get(vec)
                    if (vec.size != EMBEDDING_LENGTH) {
                        Log.e(TAG, "a ${vec.size}-value embedding, expected $EMBEDDING_LENGTH")
                        return null
                    }
                    return vec
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "mobilefacenet inference failed", e)
            return null
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    override fun close() {
        synchronized(lock) {
            session = null
            OnnxSessions.close(sessionKey())
        }
    }

    private fun sessionKey() = "asset:$asset"

    private fun ensure(): Boolean {
        session?.let { return true }
        synchronized(lock) {
            session?.let { return true }
            if (loadTried) return false
            loadTried = true
            session = OnnxSessions.openAsset(app, asset)
            return session != null
        }
    }

    companion object {
        /** What `:photos` ships. */
        const val DEFAULT_ASSET: String = "w600k_mbf.onnx"
        /** Length of the embedding MobileFaceNet produces. */
        const val EMBEDDING_LENGTH = 512
        private const val TAG = "FaceEmbedder"
        private const val SIZE = 112
        private const val INPUT = "input.1"
    }
}
