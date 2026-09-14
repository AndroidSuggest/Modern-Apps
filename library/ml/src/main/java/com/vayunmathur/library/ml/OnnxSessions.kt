package com.vayunmathur.library.ml

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.Closeable

/**
 * Shared ONNX Runtime executor for the models that run on
 * `io.github.vayun-mathur:onnxruntime-reduced-android`.
 *
 * One process-wide [OrtEnvironment], one [OrtSession] per model file. Sessions are created
 * single-threaded (`setIntraOpNumThreads(1)`), matching the prior `ClipEmbedder` policy: vision
 * and audio models are small enough that extra threads cost more battery than latency.
 *
 * A model with an operator outside the reduced build's configured set fails at [open] time, not
 * at inference — callers must treat a null return as "model unavailable" and degrade, never
 * crash. See the catalog comment on `onnxruntime-reduced-android` in `gradle/libs.versions.toml`.
 *
 * Sessions are not thread-safe: the caller must serialize runs on a session, as the MAML handles
 * (`ClipHandle` and friends) already document.
 */
object OnnxSessions {
    private const val TAG = "OnnxSessions"

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val lock = Any()
    private val sessions = mutableMapOf<String, OrtSession>()

    /**
     * Open (or reuse) the session for [key], reading the model bytes via [readModel].
     *
     * Returns null when the model cannot run here: missing file, corrupt bytes, or an operator
     * the reduced build does not include. Never throws.
     */
    fun open(key: String, readModel: () -> ByteArray): OrtSession? {
        synchronized(lock) {
            sessions[key]?.let { return it }
            return try {
                val bytes = readModel()
                val opts = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(1)
                    setInterOpNumThreads(1)
                }
                val session = env.createSession(bytes, opts)
                sessions[key] = session
                Log.i(TAG, "ORT session open for $key")
                session
            } catch (t: Throwable) {
                Log.e(TAG, "cannot open ORT session for $key", t)
                null
            }
        }
    }

    /** Open a model bundled in assets, e.g. `clip/model_int8.onnx`. */
    fun openAsset(context: Context, assetPath: String): OrtSession? =
        open("asset:$assetPath") {
            context.applicationContext.assets.open(assetPath).use { it.readBytes() }
        }

    /** Open a model from an [AssetManager], e.g. when only assets (not a Context) are at hand. */
    fun openAssetManager(assets: android.content.res.AssetManager, assetPath: String): OrtSession? =
        open("asset:$assetPath") {
            assets.open(assetPath).use { it.readBytes() }
        }

    /** Open a model from a downloaded file, e.g. under `getExternalFilesDir`. */
    fun openFile(path: String): OrtSession? =
        open("file:$path") { java.io.File(path).readBytes() }

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

/** A model handle that owns exactly one cached [OnnxSessions] entry. */
abstract class OnnxHandle protected constructor(private val sessionKey: String) : Closeable {
    /** True when the session opened and can run. */
    abstract val isAvailable: Boolean

    /** Release this handle's session. Idempotent. */
    override fun close() = OnnxSessions.close(sessionKey)
}
