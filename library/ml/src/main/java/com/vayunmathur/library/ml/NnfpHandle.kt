package com.vayunmathur.library.ml

import android.content.res.AssetManager
import android.util.Log

/**
 * On-device audio fingerprinting: the Now Playing embedding network.
 *
 * Ten alternating frequency and time convolutions over a 32-bin log-mel spectrogram, then a
 * depthwise head, at 172,576 parameters. [embed] turns 4.8 seconds of microphone audio into 64
 * numbers that identify what is playing.
 *
 * # Backend deferred
 *
 * The model ships as TFLite (`music_detector.sound_model_2`, recovered from an APK, with
 * custom `CIRCULAR_BUFFER` ops no stock runtime executes), and the Vulkan `.maml` path that
 * served it is deleted. Until the TFLite→ONNX export lands, this handle is always
 * unavailable: construction succeeds, [isAvailable] is false, and [embed] returns null so
 * callers degrade. The API (constants, [inAssets], [embed]) is unchanged so the port drops
 * in without touching callers.
 *
 * # It describes, it does not decide
 *
 * The embedding is a *descriptor*, not an answer. Matching it against a song database, deciding
 * whether the match is good enough, and pooling several windows into one query are all the
 * caller's, because none of them are in the model. In particular the vector is **not
 * normalised**.
 *
 * # Threading
 *
 * Not thread-safe. A caller must hold a lock across [embed] and [close].
 */
class NnfpHandle private constructor(private val source: String) : AutoCloseable {
    /** Always false until the ONNX port lands. See the class docs. */
    val isAvailable: Boolean get() = false

    /**
     * The [EMBEDDING] fingerprint values for one window, or null on failure.
     *
     * [pcm] must be exactly [WINDOW_SAMPLES] mono 16-bit samples at [SAMPLE_RATE] — 4.8
     * seconds. Currently always null: the backend is deferred.
     */
    fun embed(pcm: ShortArray): FloatArray? {
        if (pcm.size != WINDOW_SAMPLES) return null
        return null
    }

    /** Free the network. Idempotent. */
    override fun close() {
        // Nothing held.
    }

    override fun toString(): String = "Now Playing fingerprinter from $source"

    companion object {
        private const val TAG = "NnfpHandle"

        /** Samples per second the model was trained at. Resample anything else before [embed]. */
        const val SAMPLE_RATE = 16_000

        /** Samples in one analysis window: 25 ms. */
        const val FRAME_SAMPLES = 400

        /** Samples between windows: 10 ms, so frames arrive at 100 Hz. */
        const val HOP_SAMPLES = 160

        /** Log-mel frames behind one embedding — the network's receptive field. */
        const val WINDOW_FRAMES = 478

        /**
         * Samples [embed] requires: `FRAME_SAMPLES + HOP_SAMPLES * (WINDOW_FRAMES - 1)`, or
         * 4.795 s.
         */
        const val WINDOW_SAMPLES = FRAME_SAMPLES + HOP_SAMPLES * (WINDOW_FRAMES - 1)

        /** Values in one fingerprint. */
        const val EMBEDDING = 64

        /** The future ONNX graph. */
        const val GRAPH = "nnfp.onnx"

        /**
         * The model from the APK's assets, which is the only place it will live.
         *
         * Currently always unavailable; see the class docs.
         */
        fun inAssets(assets: AssetManager, path: String = GRAPH): NnfpHandle {
            if (!runCatching { assets.open(path).close() }.isSuccess) {
                Log.w(TAG, "$path is missing")
            }
            return NnfpHandle("the APK's $path")
        }
    }
}
