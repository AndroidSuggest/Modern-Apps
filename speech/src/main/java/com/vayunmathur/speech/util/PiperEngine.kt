package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Thin wrapper around sherpa-onnx's [OfflineTts] running an offline **Piper (VITS)** voice.
 * The voice is downloaded at runtime and extracted by [PiperModel] into filesDir (espeak-ng
 * phonemization needs real file paths); this loads it once and reuses it across syntheses.
 * Not thread-safe; drive it from one worker thread (the TTS framework calls
 * [com.vayunmathur.speech.service.PiperTtsService.onSynthesizeText] serially).
 *
 * IMPORTANT: sherpa 1.13.4's generateWithCallback JNI expects a Function1<FloatArray, Int>
 * as a *sam* interface, but R8/minify + Kotlin synthetic lambdas cause
 * NoSuchMethodError for ExternalSyntheticLambda (see tombstone_00). We use the blocking
 * generate() API and chunk the PCM ourselves to avoid the callback JNI path.
 */
class PiperEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var loadFailed = false

    /** Load now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    /** Native sample rate of the loaded voice (Hz); 0 if not loaded. */
    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    /**
     * Synthesize [text] into PCM float chunks. [onChunk] receives each chunk and returns
     * false to abort. Uses blocking generate() to avoid the crashy callback JNI.
     */
    @Synchronized
    fun synthesize(text: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean {
        if (!ensure()) return false
        val engine = tts ?: return false
        return try {
            // Blocking synthesis — returns full audio. We then stream it out in 4k-sample
            // chunks so the caller's audioAvailable loop still gets progressive output.
            val audio = engine.generate(text, /* sid = */ 0, speed)
            val samples = audio.samples ?: return false
            if (samples.isEmpty()) return true
            var offset = 0
            while (offset < samples.size) {
                val end = minOf(offset + 4096, samples.size)
                val chunk = samples.copyOfRange(offset, end)
                if (!onChunk(chunk)) return false
                offset = end
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "synthesize failed", t)
            false
        }
    }

    @Synchronized
    fun close() {
        try {
            tts?.release()
        } catch (_: Throwable) {
        }
        tts = null
    }

    @Synchronized
    private fun ensure(): Boolean {
        tts?.let { return true }
        if (loadFailed) return false
        // Voice should already be extracted right after download (MainActivity does it).
        // Don't try to extract here on the TTS binder/SynthThread — that would ANR the
        // system TTS service. If not extracted yet, just return false so the caller can
        // prompt the user to finish download in the app.
        if (!PiperModel.isExtracted(context)) return false
        return try {
            val dir = PiperModel.voiceDir(context)
            val onnx = PiperModel.onnxFile(context)
            if (onnx == null) {
                Log.e(TAG, "no .onnx voice model in $dir")
                loadFailed = true
                return false
            }
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = onnx.absolutePath,
                        tokens = File(dir, PiperModel.TOKENS).absolutePath,
                        dataDir = File(dir, PiperModel.ESPEAK_DATA).absolutePath,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            // null AssetManager → load everything from the filesystem paths above.
            tts = OfflineTts(null, config)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Piper load failed", t)
            loadFailed = true
            false
        }
    }

    companion object {
        private const val TAG = "PiperEngine"
    }
}
