package com.vayunmathur.library.ml

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType

/**
 * Shared LiteRT executor for the quantized ladder ship rungs
 * (`analysis/tflite/quant/ladder_v2.json`), over the `CompiledModel` API
 * (`com.google.ai.edge.litert:litert-api` — the only LiteRT artifact on the
 * graph; the `litert` AAR shares its manifest package, which AGP rejects).
 *
 * One process-wide cache, one [CompiledModel] per model key. Sessions are not
 * thread-safe: the caller must serialize runs, as the handles already document.
 *
 * A model that fails to load or run yields null, never a throw — callers treat
 * null as "model unavailable" and degrade, never crash.
 */
object LiteRtSessions {
    private const val TAG = "LiteRtSessions"

    private val lock = Any()
    private val sessions = mutableMapOf<String, CompiledModel>()

    /**
     * Open (or reuse) the model for [key].
     *
     * Returns null when the model cannot run here: missing file, corrupt bytes,
     * or an operator the runtime cannot execute. Never throws.
     */
    fun open(key: String, openModel: () -> CompiledModel): CompiledModel? {
        synchronized(lock) {
            sessions[key]?.let { return it }
            return try {
                val model = openModel()
                sessions[key] = model
                Log.i(TAG, "LiteRT session open for $key")
                model
            } catch (t: Throwable) {
                Log.e(TAG, "cannot open LiteRT session for $key", t)
                null
            }
        }
    }

    /** Open a model bundled in assets. */
    fun openAsset(context: Context, assetPath: String): CompiledModel? =
        open("asset:$assetPath") {
            CompiledModel.create(context.applicationContext.assets, assetPath)
        }

    /** Open a model from an [AssetManager]. */
    fun openAssetManager(assets: AssetManager, assetPath: String): CompiledModel? =
        open("asset:$assetPath") { CompiledModel.create(assets, assetPath) }

    /** Open a model from a downloaded file, e.g. under `getExternalFilesDir`. */
    fun openFile(path: String): CompiledModel? =
        open("file:$path") { CompiledModel.create(path) }

    /**
     * Run one signature invocation.
     *
     * [inputs] maps signature input name to a correctly-shaped array
     * (FloatArray/LongArray/IntArray, row-major). [outputs] lists the signature
     * output names to read. Returns a map of output name to raw array
     * (FloatArray for float outputs, ByteArray for int8/uint8), or null on failure.
     */
    fun runSignature(
        model: CompiledModel,
        inputs: Map<String, Any>,
        outputs: List<String>,
        sigKey: String = "serving_default",
    ): Map<String, Any>? {
        return try {
            val inBuffers = HashMap<String, TensorBuffer>(inputs.size)
            try {
                for ((name, array) in inputs) {
                    val buf = model.createInputBuffer(name, sigKey)
                    when (array) {
                        is FloatArray -> buf.writeFloat(array)
                        is LongArray -> buf.writeLong(array)
                        is IntArray -> buf.writeInt(array)
                        is ByteArray -> buf.writeInt8(array)
                        else -> {
                            buf.close()
                            throw IllegalArgumentException("unsupported input type for $name")
                        }
                    }
                    inBuffers[name] = buf
                }
                val outBuffers = HashMap<String, TensorBuffer>(outputs.size)
                try {
                    for (name in outputs) {
                        outBuffers[name] = model.createOutputBuffer(name, sigKey)
                    }
                    model.run(inBuffers, outBuffers, sigKey)
                    val out = HashMap<String, Any>(outBuffers.size)
                    for ((name, buf) in outBuffers) {
                        val type = model.getOutputTensorType(name, sigKey)
                        out[name] = when (type.elementType) {
                            TensorType.ElementType.FLOAT -> buf.readFloat()
                            TensorType.ElementType.INT -> buf.readInt()
                            TensorType.ElementType.INT64 -> buf.readLong()
                            else -> buf.readInt8()
                        }
                    }
                    out
                } finally {
                    for (buf in outBuffers.values) runCatching { buf.close() }
                }
            } finally {
                for (buf in inBuffers.values) runCatching { buf.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT runSignature failed", t)
            null
        }
    }

    /**
     * Run one invocation on a signature-less model (index-ordered I/O).
     *
     * [inputs] are row-major arrays in input order; [kinds] gives each output's
     * element kind ("f32"/"i32"/"i64"/"u8"/"i8"). Returns the outputs in order,
     * or null on failure.
     */
    fun run(
        model: CompiledModel,
        inputs: List<Any>,
        kinds: List<String>,
    ): List<Any>? {
        return try {
            val inBuffers = inputs.map { array ->
                val buf = model.createInputBuffers(1).first()
                when (array) {
                    is FloatArray -> buf.writeFloat(array)
                    is LongArray -> buf.writeLong(array)
                    is IntArray -> buf.writeInt(array)
                    is ByteArray -> buf.writeInt8(array)
                    else -> {
                        buf.close()
                        throw IllegalArgumentException("unsupported input type")
                    }
                }
                buf
            }
            val outBuffers = model.createOutputBuffers(kinds.size)
            try {
                model.run(inBuffers, outBuffers, 0)
                outBuffers.mapIndexed { i, buf ->
                    when (kinds[i]) {
                        "i32" -> buf.readInt()
                        "i64" -> buf.readLong()
                        "u8", "i8" -> buf.readInt8()
                        else -> buf.readFloat()
                    }
                }
            } finally {
                for (buf in outBuffers) runCatching { buf.close() }
                for (buf in inBuffers) runCatching { buf.close() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "LiteRT run failed", t)
            null
        }
    }

    /**
     * Pass-through for float outputs; null otherwise.
     *
     * All ladder ship rungs keep float32 outputs (weight-only quantization leaves
     * activations float), so there is nothing to dequantize. The CompiledModel API
     * exposes no per-tensor quant params, and no shipped rung needs them.
     */
    fun dequantize(
        model: CompiledModel,
        sigKey: String,
        outputName: String,
        raw: Any,
    ): FloatArray? = raw as? FloatArray

    /** Drop the cached session for [key]. Idempotent. */
    fun close(key: String) {
        synchronized(lock) {
            sessions.remove(key)?.let { runCatching { it.close() } }
        }
    }

    /** Drop every cached session. Idempotent. For tests and process teardown. */
    fun closeAll() {
        synchronized(lock) {
            sessions.keys.toList().forEach { close(it) }
        }
    }
}
