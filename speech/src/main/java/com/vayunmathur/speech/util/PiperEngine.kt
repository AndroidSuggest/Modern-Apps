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
 */
class PiperEngine(private val context: Context) {

    private var tts: OfflineTts? = null
    private var loadFailed = false

    /** Load now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    /** Native sample rate of the loaded voice (Hz); 0 if not loaded. */
    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    /**
     * Stream synthesis of [text]. [onChunk] receives PCM float samples ([-1, 1]) as they
     * are produced and returns false to abort (e.g. the caller was stopped). Returns true
     * if synthesis ran, false if the engine couldn't load.
     */
    @Synchronized
    fun synthesize(text: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean {
        if (!ensure()) return false
        val engine = tts ?: return false
        return try {
            // sherpa returns 1 to keep going, 0 to stop; it also returns the full audio,
            // which we ignore since we've already streamed it out chunk by chunk.
            engine.generateWithCallback(text, /* sid = */ 0, speed) { samples ->
                if (onChunk(samples)) 1 else 0
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
        // Extract the downloaded voice on first use. Don't latch loadFailed if it simply
        // isn't downloaded yet — a later call can succeed once the model arrives.
        if (!PiperModel.installIfNeeded(context)) return false
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
