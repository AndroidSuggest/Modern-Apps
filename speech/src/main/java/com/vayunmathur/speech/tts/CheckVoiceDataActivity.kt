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
 * Must keep ISO3 format (e.g. `eng-USA`, `deu-DEU`) in EXTRA_AVAILABLE_VOICES.
 * The Play button in Settings is enabled only if CHECK returns PASS and
 * PiperTtsService.onGetVoices() contains voices matching BCP-47 and ISO3 variants,
 * and onGetDefaultVoiceNameFor(null,null,null) returns a non-empty installed voice
 * name. If any of those disagree, Play silently does nothing — this was the bug
 * after registry renamed to generic IDs (en_US-high) while old dirs still existed
 * as en_US-lessac-low.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val available = ArrayList<String>()
        val unavailable = ArrayList<String>()

        try {
            // Ensure old low/medium -> high migration + removal of dup archives
            PiperVoiceRegistry.migrateLegacyIfNeeded(this)
            // Also allow old speaker-specific dirs to count as installed (via fallback
            // findAnyValidDirForBcp47) so Settings Play works on upgraded installs.
            val installed = PiperVoiceRegistry.installedDefs(this)
            if (installed.isNotEmpty()) {
                for (def in installed) {
                    val iso = "${def.iso3}-${def.iso3Country}"
                    if (!available.contains(iso)) {
                        available.add(iso)
                    }
                }
                // Report everything else as unavailable, de-duped.
                for (def in PiperVoiceRegistry.ALL) {
                    val iso = "${def.iso3}-${def.iso3Country}"
                    if (iso !in available && iso !in unavailable) {
                        // Only report as unavailable if not extracted
                        if (!PiperVoiceRegistry.isExtracted(this, def)) {
                            unavailable.add(iso)
                        }
                    }
                }
            } else {
                if (PiperModel.isExtracted(this)) {
                    available.add(VOICE)
                } else {
                    unavailable.add(VOICE)
                    for (def in PiperVoiceRegistry.ALL) {
                        val iso = "${def.iso3}-${def.iso3Country}"
                        if (def.code != "en" && iso !in unavailable && iso !in available) {
                            unavailable.add(iso)
                        }
                    }
                }
            }
        } catch (_: Throwable) {
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
            if (available.isNotEmpty()) TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
            else TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL,
            data,
        )
        finish()
    }

    private companion object {
        const val VOICE = "eng-USA"
    }
}
