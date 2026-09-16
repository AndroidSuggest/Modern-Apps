package com.vayunmathur.library.ml

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File
import org.pytorch.executorch.EValue
import org.pytorch.executorch.ExecuTorchRuntime
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor

/**
 * Shared ExecuTorch executor for Vulkan-lowered `.pte` models (e.g. selfie segmentation).
 *
 * One process-wide cache, one [Module] per model key. `Module.load` takes a file path, so
 * asset-bundled models are staged once under `cacheDir/executorch` before loading. Backend
 * selection (Vulkan delegation) happens at export time against the custom Vulkan AAR in
 * `library/ml/libs/`; [numThreads] only sizes the intra-op pool and defaults to 1,
 * matching the [OnnxSessions] battery policy. Non-delegated ops fall back to portable
 * CPU kernels — there is no XNNPACK in this build (Vulkan-only per lead directive).
 *
 * Sessions are not thread-safe for concurrent runs on the same module (ET serializes
 * internally per module, but callers must still serialize runs as the handles document).
 *
 * A model that fails to load or run yields null, never a throw — callers treat null as
 * "model unavailable" and degrade, never crash.
 */
object ExecutorchSessions {
    private const val TAG = "ExecutorchSessions"

    private val lock = Any()
    private val sessions = mutableMapOf<String, Module>()

    /**
     * Open (or reuse) the module for [key].
     *
     * Returns null when the model cannot run here: missing file, corrupt bytes, or an
     * operator the lowered model does not include. Never throws.
     */
    fun open(key: String, numThreads: Int = 1, loadModel: () -> Module): Module? {
        synchronized(lock) {
            sessions[key]?.let { return it }
            return try {
                val module = loadModel()
                sessions[key] = module
                Log.i(TAG, "ExecuTorch session open for $key")
                module
            } catch (t: Throwable) {
                Log.e(TAG, "cannot open ExecuTorch session for $key", t)
                null
            }
        }
    }

    /** Open a model from an absolute file path, e.g. under `getExternalFilesDir`. */
    fun openPath(path: String, numThreads: Int = 1): Module? =
        open("file:$path", numThreads) {
            Module.load(path, Module.LOAD_MODE_FILE, numThreads)
        }

    /** Open a model bundled in assets, e.g. `selfie_segmentation_vulkan_fp16.pte`. */
    fun openAsset(context: Context, assetPath: String, numThreads: Int = 1): Module? =
        openAssetManager(
            context.applicationContext.assets,
            assetPath,
            File(context.applicationContext.cacheDir, "executorch"),
            numThreads,
        )

    /** Open a model from an [AssetManager], staging the bytes under [stageDir] first. */
    fun openAssetManager(
        assets: AssetManager,
        assetPath: String,
        stageDir: File,
        numThreads: Int = 1,
    ): Module? =
        open("asset:$assetPath", numThreads) {
            Module.load(stageAsset(assets, assetPath, stageDir).absolutePath, Module.LOAD_MODE_FILE, numThreads)
        }

    private fun stageAsset(assets: AssetManager, assetPath: String, stageDir: File): File {
        stageDir.mkdirs()
        val out = File(stageDir, assetPath.replace('/', '_'))
        if (!out.exists()) {
            assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out
    }

    /**
     * Run one `forward` (or [methodName]) invocation.
     *
     * [inputs] are wrapped with [EValue.from] by the caller. Returns the raw output
     * [EValue]s in order, or null on failure. Callers extract tensors via `toTensor()`.
     */
    fun run(
        model: Module,
        inputs: List<EValue>,
        methodName: String = "forward",
    ): Array<EValue>? {
        return try {
            if (methodName == "forward") model.forward(*inputs.toTypedArray())
            else model.execute(methodName, *inputs.toTypedArray())
        } catch (t: Throwable) {
            Log.e(TAG, "ExecuTorch run failed", t)
            null
        }
    }

    /**
     * Run one float-tensor invocation and return the first output as a [FloatArray].
     *
     * Covers the vision drop-ins (float CHW in, float mask/logits out, e.g. selfie
     * 1x3x256x256 → 1x1x256x256). Returns null when the model is missing, the output
     * is not a float tensor, or inference fails.
     */
    fun runFloat(
        model: Module,
        data: FloatArray,
        shape: LongArray,
        methodName: String = "forward",
    ): FloatArray? {
        return try {
            val input = EValue.from(Tensor.fromBlob(data, shape))
            val outputs = run(model, listOf(input), methodName) ?: return null
            val tensor = outputs.firstOrNull()?.takeIf { it.isTensor }?.toTensor() ?: return null
            if (tensor.dtype() != org.pytorch.executorch.DType.FLOAT) {
                Log.e(TAG, "ExecuTorch runFloat: output dtype ${tensor.dtype()} is not FLOAT")
                return null
            }
            val out = FloatArray(tensor.numel().toInt())
            tensor.copyDataInto(java.nio.FloatBuffer.wrap(out))
            out
        } catch (t: Throwable) {
            Log.e(TAG, "ExecuTorch runFloat failed", t)
            null
        }
    }

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

    /**
     * Backends linked into the bundled `libexecutorch.so` (expect `Vulkan`).
     *
     * The custom AAR in `library/ml/libs/` is Vulkan-only (no XNNPACK) — a `.pte`
     * without Vulkan delegation falls back to portable CPU kernels for non-delegated
     * ops. Returns null (never throws) when the runtime cannot be queried. For
     * wiring-time checks and diagnostics, not for hot paths.
     */
    fun registeredBackends(): Set<String>? {
        return try {
            ExecuTorchRuntime.getRegisteredBackends().toSet().also {
                Log.i(TAG, "ExecuTorch registered backends: $it")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "cannot query ExecuTorch backends", t)
            null
        }
    }
}
