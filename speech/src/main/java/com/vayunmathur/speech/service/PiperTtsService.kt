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
    // The Settings Play button is enabled only if:
    //   isLanguageAvailable >= LANG_AVAILABLE AND getDefaultVoiceNameFor returns a
    //   non-empty name that exists in getVoices().
    // Log "Couldn't find the default voice for eng-USA-" means the default
    // implementation of onGetDefaultVoiceNameFor returned null because
    // onIsLanguageAvailable always returned VAR_AVAILABLE, but the framework
    // expected COUNTRY_AVAILABLE for en-US (mismatch in onIsValidVoiceName).
    // Fix: proper hierarchy + override default voice to a name we publish.
    // Extraction happens right after download in MainActivity (IO), never here.

    private fun isEnglishIso3(lang: String?): Boolean {
        if (lang == null) return false
        val l = lang.lowercase()
        return l == "eng" || l == "en" || l.startsWith("en-") || l.startsWith("en_") || l == "en-us" || l == "en_us"
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        if (!isEnglishIso3(lang)) return TextToSpeech.LANG_NOT_SUPPORTED
        // Framework expects LANG_AVAILABLE for lang only, LANG_COUNTRY_AVAILABLE for
        // lang+country, LANG_COUNTRY_VAR_AVAILABLE for lang+country+variant.
        val hasCountry = !country.isNullOrEmpty()
        val hasVariant = !variant.isNullOrEmpty()
        return when {
            hasCountry && hasVariant -> TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
            hasCountry -> TextToSpeech.LANG_COUNTRY_AVAILABLE
            else -> TextToSpeech.LANG_AVAILABLE
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onGetLanguage(): Array<String> = arrayOf("eng", "USA", "")

    // Critical: override to bypass default logic that calls onIsValidVoiceName and
    // fails when we always returned VAR_AVAILABLE. Return a voice name that we
    // actually publish in onGetVoices().
    override fun onGetDefaultVoiceNameFor(lang: String?, country: String?, variant: String?): String? {
        if (onIsLanguageAvailable(lang, country, variant) == TextToSpeech.LANG_NOT_SUPPORTED) return null
        if (!PiperModel.isExtracted(applicationContext)) return null
        // Must match a Voice from onGetVoices() — use BCP-47 "en-US" so
        // Locale.forLanguageTag succeeds and Settings finds it.
        return "en-US"
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        // Accept our published names + any English BCP-47
        if (voiceName.equals("en-US", ignoreCase = true) || voiceName == "eng-USA") return TextToSpeech.SUCCESS
        if (voiceName.lowercase().startsWith("en")) {
            // "en-us-x-ma-speech-local" etc.
            return TextToSpeech.SUCCESS
        }
        return try {
            super.onIsValidVoiceName(voiceName)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetVoices(): MutableList<Voice> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return super.onGetVoices()
        val ctx = applicationContext
        val extracted = try {
            PiperModel.isExtracted(ctx)
        } catch (_: Throwable) {
            false
        }
        if (!extracted) return mutableListOf()
        // Publish both the BCP-47 tag the framework looks up ("en-US") and the legacy
        // ISO3 tag used by CHECK_TTS_DATA ("eng-USA") so legacy + modern paths both
        // find a default voice and enable Play.
        val v1 = Voice(
            "en-US",
            Locale.US,
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
        val v2 = Voice(
            "eng-USA",
            Locale("en", "US"),
            Voice.QUALITY_VERY_HIGH,
            Voice.LATENCY_NORMAL,
            false,
            emptySet()
        )
        return mutableListOf(v1, v2)
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
