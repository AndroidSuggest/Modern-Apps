package com.vayunmathur.speech.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.vayunmathur.speech.util.PiperModel
import com.vayunmathur.speech.util.PiperVoiceRegistry

/**
 * Answers the framework's `CHECK_TTS_DATA` probe: the system TTS settings run this
 * before letting the user pick our engine, to learn which voices are installed.
 *
 * Originally reported only `eng-USA` (en-US Amy medium). After multilingual
 * expansion we report all installed voices as ISO3 triples (e.g. `eng-USA`,
 * `deu-DEU`, `fra-FRA`, ...) so each language shows an enabled Play button.
 *
 * Legacy single-voice path via [PiperModel] is kept as fallback so older installs
 * still PASS before migration runs.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()

        try {
            PiperVoiceRegistry.migrateLegacyIfNeeded(this)
            val installed = PiperVoiceRegistry.installedDefs(this)
            if (installed.isNotEmpty()) {
                for (def in installed) {
                    available.add("${def.iso3}-${def.iso3Country}")
                }
                // Also add uninstalled voices as unavailable so Settings can
                // distinguish missing vs present if framework cares.
                for (def in PiperVoiceRegistry.ALL) {
                    if (def !in installed) {
                        unavailable.add("${def.iso3}-${def.iso3Country}")
                    }
                }
            } else {
                // Fallback: legacy single-voice check (pre-migration or empty)
                if (PiperModel.isExtracted(this)) {
                    available.add(VOICE)
                } else {
                    unavailable.add(VOICE)
                    // Report all others as unavailable too
                    for (def in PiperVoiceRegistry.ALL) {
                        if (def.code != "en") {
                            unavailable.add("${def.iso3}-${def.iso3Country}")
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Safety: at least report eng-USA if legacy exists.
            if (PiperModel.isExtracted(this)) {
                available.add(VOICE)
            } else {
                unavailable.add(VOICE)
            }
        }

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
        // Legacy single-voice id.
        const val VOICE = "eng-USA"
    }
}
