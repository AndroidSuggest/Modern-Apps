package com.vayunmathur.auto.platform

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * Speaks notification text on the car speakers through the system sink (ch4).
 *
 * The platform [TextToSpeech] engine synthesizes to a WAV file on a
 * background thread; the sink unwraps it ([AudioCodec.stripWavToPcm16Mono])
 * and frames it as 0x0000 bulk. Synthesis is fire-and-forget: a missing
 * engine, a failed file or an overlong utterance drops with a count, never a
 * crash and never a block of the caller.
 *
 * Owns its own thread -- never the pump thread, never the sink's audio
 * thread. The only thing shared is the sink's thread-safe [AudioSinkChannel.feedWav].
 */
class CarTts(
    context: Context,
    private val systemSink: () -> AudioSinkChannel?,
    private val onEvent: (AudioEvent) -> Unit = {},
) {
    private val appContext = context.applicationContext

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var engineReady = false

    fun start() {
        val worker = HandlerThread("ma-auto-tts").also { it.start() }
        thread = worker
        handler = Handler(worker.looper)
        handler?.post {
            engine = TextToSpeech(appContext) { status ->
                engineReady = status == TextToSpeech.SUCCESS
                if (engineReady) {
                    engine?.language = Locale.getDefault()
                    engine?.setOnUtteranceProgressListener(progressListener)
                } else {
                    Log.w(TAG, "tts engine unavailable (status $status)")
                }
            }
        }
    }

    /** Reads [text] aloud on the car speakers. Fire-and-forget. */
    fun speak(text: String) {
        val target = handler ?: return
        val snapshot = text.take(MAX_CHARS)
        target.post { synthesize(snapshot) }
    }

    fun stop() {
        handler?.removeCallbacksAndMessages(null)
        handler?.post {
            runCatching { engine?.stop(); engine?.shutdown() }
            engine = null
            engineReady = false
        }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun synthesize(text: String) {
        val tts = engine
        if (!engineReady || tts == null) {
            Log.d(TAG, "dropping ${text.length} chars; tts not ready")
            onEvent(AudioEvent.TtsDropped("engine-unready"))
            return
        }
        val sink = systemSink() ?: run {
            Log.d(TAG, "dropping ${text.length} chars; no system sink")
            onEvent(AudioEvent.TtsDropped("no-sink"))
            return
        }
        val file = File(appContext.cacheDir, "maauto-tts-${UUID.randomUUID()}.wav")
        val utteranceId = file.name
        val queued = tts.synthesizeToFile(text, null, file, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts synthesize failed ($queued)")
            runCatching { file.delete() }
            onEvent(AudioEvent.TtsDropped("synthesize-failed"))
            return
        }
        // synthesizeToFile returns before the file lands; the progress
        // listener feeds the sink on onDone. A stuck utterance stays a stuck
        // file in the cache dir, which the OS reclaims -- never a stuck call.
        pending[utteranceId] = PendingUtterance(sink, file)
    }

    private data class PendingUtterance(val sink: AudioSinkChannel, val file: File)

    private val pending = mutableMapOf<String, PendingUtterance>()

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            val pendingUtterance = synchronized(pending) { pending.remove(utteranceId) }
                ?: return
            val wav = runCatching { pendingUtterance.file.readBytes() }.getOrNull()
            runCatching { pendingUtterance.file.delete() }
            if (wav == null) {
                onEvent(AudioEvent.TtsDropped("read-failed"))
                return
            }
            pendingUtterance.sink.feedWav(wav)
        }

        override fun onError(utteranceId: String?) {
            synchronized(pending) { pending.remove(utteranceId) }?.let {
                runCatching { it.file.delete() }
            }
            Log.w(TAG, "tts utterance $utteranceId failed")
            onEvent(AudioEvent.TtsDropped("utterance-error"))
        }
    }

    private companion object {
        const val TAG = "MaAuto.Tts"

        /** A utterance longer than this is trimmed: car TTS is prompts, not chapters. */
        const val MAX_CHARS = 400
    }
}
