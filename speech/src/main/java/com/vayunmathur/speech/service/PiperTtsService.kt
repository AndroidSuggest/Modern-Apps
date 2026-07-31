package com.vayunmathur.speech.service

import android.media.AudioFormat
import android.os.Build
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import com.vayunmathur.speech.util.PiperEngine
import com.vayunmathur.speech.util.PiperModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * System text-to-speech engine backed by offline **Piper (VITS)** via sherpa-onnx. Once
 * selected as the device's TTS engine, any app that uses [android.speech.tts.TextToSpeech]
 * (Translate's read-aloud, TalkBack, ebook readers, …) synthesizes fully on-device — no
 * network, no Google.
 *
 * The bundled voice is English (en-US), so we advertise only that; the framework won't route
 * other languages to us. Synthesis streams PCM to the [SynthesisCallback] as sherpa produces
 * it, so audio starts before a long sentence finishes.
 */
class PiperTtsService : TextToSpeechService() {

    private val engine by lazy { PiperEngine(applicationContext) }

    @Volatile private var stopped = false

    override fun onCreate() {
        super.onCreate()
        // Warm the model (extract-on-first-use + load) off the main thread so the first
        // utterance doesn't stall.
        Thread { engine.preload() }.start()
    }

    override fun onDestroy() {
        engine.close()
        super.onDestroy()
    }

    // --- Language support: English (en-US) only. ---

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        // Guard against nulls — old implementations crashed calling equals() on null country.
        val l = lang?.lowercase() ?: return TextToSpeech.LANG_NOT_SUPPORTED
        if (l != "eng" && l != "en") return TextToSpeech.LANG_NOT_SUPPORTED
        // If voice isn't installed yet, still report as available so the framework doesn't
        // hide us; CheckVoiceDataActivity will report FAIL and the Settings will show
        // the download prompt. Once extracted we report country-level availability.
        val c = country?.lowercase()
        return when {
            c == null || c.isEmpty() || c == "usa" || c == "us" -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    // Provide a Voice list so modern Settings (API 21+) shows a voice and enables the Play
    // button. Without this, some OEM / AOSP builds disable Play when getVoices() is empty.
    // Do NOT extract here — that would ANR the TTS binder thread. Extraction happens
    // right after download in MainActivity (PiperModel.download + installIfNeeded on IO).
    override fun onGetVoices(): MutableList<Voice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return super.onGetVoices()
        val ctx = applicationContext
        if (!PiperModel.isExtracted(ctx)) return mutableListOf()
        val locale = Locale("en", "US")
        // Name must be stable, quality high, local (no network) so Settings enables preview.
        val voice = Voice(
            "en-us-x-ma-speech-local",
            locale,
            Voice.QUALITY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
        return mutableListOf(voice)
    }

    override fun onStop() {
        stopped = true
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        stopped = false
        val text = request.charSequenceText?.toString().orEmpty()

        if (!engine.preload()) {
            callback.error()
            return
        }
        val sampleRate = engine.sampleRate()
        if (sampleRate <= 0) {
            callback.error()
            return
        }
        if (text.isBlank()) {
            // Nothing to say, but the contract still wants a well-formed empty stream.
            callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1)
            callback.done()
            return
        }

        // Framework rate is a percentage of normal (100 = 1.0×); sherpa's speed is the same
        // multiplier (larger = faster). Clamp to a sane range.
        val speed = (request.speechRate / 100f).coerceIn(0.3f, 3.0f)

        if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, 1) != TextToSpeech.SUCCESS) {
            return
        }

        val maxBytes = callback.maxBufferSize
        val ok = engine.synthesize(text, speed) { samples ->
            if (stopped) return@synthesize false
            val pcm = floatsToPcm16(samples)
            var offset = 0
            while (offset < pcm.size) {
                if (stopped) return@synthesize false
                val n = minOf(maxBytes, pcm.size - offset)
                if (callback.audioAvailable(pcm, offset, n) != TextToSpeech.SUCCESS) {
                    return@synthesize false
                }
                offset += n
            }
            true
        }

        if (ok || stopped) callback.done() else callback.error()
    }

    /** Convert Piper's float samples ([-1, 1]) to little-endian 16-bit PCM bytes. */
    private fun floatsToPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var i = 0
        for (s in samples) {
            val v = (s.coerceIn(-1f, 1f) * 32767f).roundToInt()
            out[i++] = (v and 0xFF).toByte()
            out[i++] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }
}
