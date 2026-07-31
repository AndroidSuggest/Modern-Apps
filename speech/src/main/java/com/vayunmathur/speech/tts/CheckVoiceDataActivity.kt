package com.vayunmathur.speech.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.vayunmathur.speech.util.PiperModel

/**
 * Answers the framework's `CHECK_TTS_DATA` probe: the system TTS settings run this before
 * letting the user pick our engine, to learn which voices are installed. We report en-US as
 * available when the bundled Piper voice is present, otherwise as unavailable.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()
        if (PiperModel.isReady(this)) available.add(VOICE) else unavailable.add(VOICE)

        val data = Intent().apply {
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, available)
            putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailable)
        }
        setResult(
            if (available.isNotEmpty()) {
                TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            } else {
                TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
            },
            data,
        )
        finish()
    }

    private companion object {
        // TTS voice identifier: "<ISO3 language>-<ISO3 country>".
        const val VOICE = "eng-USA"
    }
}
