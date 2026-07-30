package com.vayunmathur.translate.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Small wrapper over [TextToSpeech] that speaks translated text in the target
 * language's locale. Owns the engine lifecycle: create once, [shutdown] when the
 * owner is cleared. Safe to call [speak] before the engine finishes initialising
 * (the request is simply dropped).
 */
class TtsSpeaker(context: Context) {

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.w(TAG, "TextToSpeech init failed: $status")
        }
    }

    /** Whether the target [languageCode] can be spoken (locale data available). */
    fun isLanguageSupported(languageCode: String): Boolean {
        val engine = tts ?: return false
        if (!ready) return false
        return when (engine.isLanguageAvailable(Locale.forLanguageTag(languageCode))) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> true
            else -> false
        }
    }

    /** Speak [text] in [languageCode], flushing any in-progress utterance. */
    fun speak(text: String, languageCode: String) {
        val engine = tts ?: return
        if (!ready || text.isBlank()) return
        try {
            engine.language = Locale.forLanguageTag(languageCode)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (t: Throwable) {
            Log.e(TAG, "speak failed", t)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Throwable) {
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Throwable) {
        }
        tts = null
        ready = false
    }

    companion object {
        private const val TAG = "TtsSpeaker"
        private const val UTTERANCE_ID = "translate_output"
    }
}
