package com.vayunmathur.auto.platform

/**
 * The audio half of messaging, owned by audio-dev's Phase 3 channels.
 *
 * Notify/TTS rides the guidance/system sink (ch4) and a voice reply opens a
 * mic turn on the source channel (ch6). Those owners have not landed yet, so
 * `MessagingCarAppService` codes against this seam and ships with [NoOp]:
 * sends are counted and observed, audio is silently skipped.
 *
 * Assumptions (noted for the audio-dev handoff, sent via the ma-auto-parity
 * channel rather than waited on):
 * 1. TTS/notification audio goes out a ch4 sink; [speakOnCar] hands text to
 *    whichever sink audio-dev designates.
 * 2. A voice reply is one ch6 mic turn for [beginVoiceReply]'s thread; the
 *    transcription comes back as text on [onResult], which the caller treats
 *    exactly like a typed head-unit reply.
 */
interface MessagingAudio {
    /** Reads [text] aloud on the car speakers. Fire-and-forget. */
    fun speakOnCar(text: String)

    /**
     * Opens a mic turn for a reply to [threadId]. [onResult] receives the
     * transcription and may be invoked from any thread; sinks must be
     * thread-safe (a `StateFlow` set, a queued send) rather than doing work
     * inline. Never invoked when the turn yields nothing.
     */
    fun beginVoiceReply(threadId: String, onResult: (String) -> Unit)

    /** Pre-audio-dev holder: observes the call shape, moves no audio. */
    object NoOp : MessagingAudio {
        override fun speakOnCar(text: String) = Unit
        override fun beginVoiceReply(threadId: String, onResult: (String) -> Unit) = Unit
    }
}
