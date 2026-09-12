package com.vayunmathur.auto.service
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vayunmathur.auto.platform.AudioEvent
import com.vayunmathur.auto.platform.AudioSinkChannel
import com.vayunmathur.auto.platform.CarTts
import com.vayunmathur.auto.platform.MessagingAudio
import com.vayunmathur.auto.platform.MicSourceChannel

/**
 * The audio half of messaging, backed by the Phase 3 channels.
 *
 * Lazily resolved: ch14 opens before ch4/6 in wire order, so the sinks
 * may not exist when the messaging owner is created -- and TTS starts
 * with the session, so early utterances must not need a sink yet. Both
 * lambdas are read per call; a missing channel drops with a count.
 *
 * Notify/TTS synthesizes phone-side and frames out the WAV on the system
 * sink (ch4). A voice reply opens a ch6 mic turn whose retained PCM comes
 * back on [onResult] -- transcription itself is the message app's job (or
 * a future on-device pass), so the raw bytes hand off undecoded exactly
 * like a typed reply's text hands off unsent: this service knows GAL, not
 * SMS.
 *
 * Retires [MessagingAudio.NoOp]: the seam interface is unchanged, so
 * `MessagingCarAppService` codes against the same two methods.
 */
class CarMessagingAudio(
    private val tts: () -> CarTts?,
    private val mic: () -> MicSourceChannel?,
    private val onEvent: (AudioEvent) -> Unit = {},
    /** Hands retained mic PCM to whoever transcribes; empty when nobody does. */
    private val transcribe: (ByteArray, (String) -> Unit) -> Unit = { _, _ -> },
) : MessagingAudio {

    override fun speakOnCar(text: String) {
        tts()?.speak(text) ?: onEvent(AudioEvent.TtsDropped("no-tts"))
    }

    override fun beginVoiceReply(threadId: String, onResult: (String) -> Unit) {
        val channel = mic() ?: run {
            Log.w(TAG, "voice reply for $threadId with no mic channel")
            return
        }
        channel.beginTurn { pcm ->
            if (pcm.isEmpty()) {
                Log.d(TAG, "mic turn for $threadId yielded nothing")
                return@beginTurn
            }
            transcribe(pcm) { text ->
                if (text.isNotBlank()) {
                    Log.i(TAG, "mic turn for $threadId transcribed ${text.length} chars")
                    onResult(text)
                } else {
                    Log.d(TAG, "mic turn for $threadId transcribed nothing")
                }
            }
            // The turn stays open until the head unit ends it; the service
            // closes it on release or on the next reply-begin.
            channel.endTurn()
        }
        // Bound the turn: an unanswered mic stream must not retain forever.
        Handler(Looper.getMainLooper()).postDelayed(
            { channel.endTurn() },
            TURN_TIMEOUT_MS,
        )
    }

    private companion object {
        const val TAG = "MaAuto.MessagingAudio"

        /** A voice-reply turn longer than this is closed and yielded as-is. */
        const val TURN_TIMEOUT_MS = 15_000L
    }
}
