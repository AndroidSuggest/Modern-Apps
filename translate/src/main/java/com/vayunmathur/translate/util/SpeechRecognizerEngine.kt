package com.vayunmathur.translate.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Live speech-to-text, behind a clean interface. Implementations stream partial
 * transcripts via [onPartial] as words arrive and a final transcript via
 * [onFinal]. All callbacks are delivered on the main thread.
 */
interface SpeechRecognizerEngine {
    /** True if this engine can actually transcribe on this device. */
    fun isAvailable(): Boolean

    /**
     * Begin listening for speech in [languageCode] (ISO-639-1, "auto" allowed —
     * falls back to the device default). [onPartial] fires repeatedly with the
     * best in-progress hypothesis; [onFinal] fires once with the settled text;
     * [onError] reports a human-readable failure.
     */
    fun start(
        languageCode: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    )

    /** Stop listening (keeps the engine usable for a later [start]). */
    fun stop()

    /** Release all resources. */
    fun destroy()
}

/**
 * Offline speech recognition backed by [TranslateNative]. Stubbed: no whisper
 * weights are bundled so [isAvailable] is false and [start] immediately errors,
 * causing callers to fall back to [AndroidSpeechRecognizer]. Kept as a clean
 * seam for a real on-device model. Never crashes if the native lib is absent.
 */
class NativeSpeech : SpeechRecognizerEngine {

    override fun isAvailable(): Boolean {
        if (!TranslateNative.isAvailable) return false
        return try {
            TranslateNative.nativeSpeechAvailable()
        } catch (t: Throwable) {
            Log.e(TAG, "nativeSpeechAvailable failed", t)
            false
        }
    }

    override fun start(
        languageCode: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        // No offline model shipped; report unavailable so the caller falls back.
        onError("Offline speech model not installed")
    }

    override fun stop() {}
    override fun destroy() {}

    companion object {
        private const val TAG = "NativeSpeech"
    }
}

/**
 * Speech recognition using the platform [SpeechRecognizer] with partial results
 * (`EXTRA_PARTIAL_RESULTS`). Works on devices that ship a recognizer (most, via
 * Google/Samsung). Must be created and used on the main thread.
 */
class AndroidSpeechRecognizer(private val context: Context) : SpeechRecognizerEngine {

    private var recognizer: SpeechRecognizer? = null

    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun start(
        languageCode: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (!isAvailable()) {
            onError("Speech recognition unavailable on this device")
            return
        }
        // Recreate per session so a previous error state never leaks in.
        recognizer?.destroy()
        val sr = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }

        val locale = if (languageCode == Languages.AUTO.code) {
            Locale.getDefault()
        } else {
            Locale.forLanguageTag(languageCode)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        sr.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                onError(describeError(error))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                firstResult(results)?.let(onFinal)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        try {
            sr.startListening(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "startListening failed", t)
            onError("Could not start speech recognition")
        }
    }

    override fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Throwable) {
        }
    }

    override fun destroy() {
        try {
            recognizer?.destroy()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
        else -> "Speech recognition error"
    }

    companion object {
        private const val TAG = "AndroidSpeech"
    }
}
