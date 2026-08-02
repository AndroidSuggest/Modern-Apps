package com.vayunmathur.speech.util

import android.content.Context
import android.util.Log
import com.vayunmathur.ncnn.Vits

/**
 * Thin wrapper around the ncnn AAR's [Vits] running an offline **Piper (VITS)** voice.
 * The voice is downloaded at runtime and extracted by [PiperModel] into filesDir; this
 * loads it once and reuses it across syntheses. Not thread-safe; drive it from one
 * worker thread (the TTS framework calls
 * [com.vayunmathur.speech.service.PiperTtsService.onSynthesizeText] serially).
 *
 * [Vits.generate] is blocking and returns the whole utterance, so we chunk the PCM
 * ourselves to keep the caller's audioAvailable loop progressive.
 */
class PiperEngine(private val context: Context) {

    private var tts: Vits? = null
    private var loadFailed = false

    /** Load now (e.g. to warm up off the main thread). Returns true if ready. */
    fun preload(): Boolean = ensure()

    /** Native sample rate of the loaded voice (Hz); 0 if not loaded. */
    fun sampleRate(): Int = tts?.sampleRate() ?: 0

    /**
     * Synthesize [text] into PCM float chunks. [onChunk] receives each chunk and returns
     * false to abort.
     */
    @Synchronized
    fun synthesize(text: String, speed: Float, onChunk: (FloatArray) -> Boolean): Boolean {
        if (!ensure()) return false
        val engine = tts ?: return false
        return try {
            val samples = engine.generate(text, /* sid = */ 0, speed)
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
            tts?.close()
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
            val engine = Vits(dir.absolutePath)
            if (!engine.isAvailable) {
                Log.e(TAG, "Vits could not load the voice in $dir")
                engine.close()
                loadFailed = true
                return false
            }
            tts = engine
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
