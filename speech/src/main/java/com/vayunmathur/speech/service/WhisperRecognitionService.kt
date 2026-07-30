package com.vayunmathur.speech.service

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.vayunmathur.speech.util.WhisperEngine
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * System speech-recognition service backed by offline **Whisper** (ncnn, ~99 languages,
 * auto-detect). When selected as the device's recognition service, any app using
 * [android.speech.SpeechRecognizer] (Translate, keyboards, …) transcribes fully on-device.
 *
 * Whisper is a whole-utterance model, not a streaming one. To still deliver live results,
 * an **energy VAD** watches for pauses in speech: at each short pause (a natural phrase
 * boundary) the audio-so-far is re-transcribed and delivered via [Callback.partialResults].
 * A longer trailing silence, the client's stopListening, or a 29 s cap ends the utterance
 * and the final pass is delivered via [Callback.results]. Transcription runs on a single
 * background worker so the recording thread never stops draining the mic.
 */
class WhisperRecognitionService : RecognitionService() {

    private val engine by lazy { WhisperEngine(applicationContext) }
    @Volatile private var session: Session? = null

    override fun onCreate() {
        super.onCreate()
        // Warm up the model (mmapped from APK assets) off the main thread so the first
        // recognition doesn't stall on the initial load.
        Thread { engine.preload() }.start()
    }

    override fun onStartListening(recognizerIntent: Intent, callback: Callback) {
        if (session != null) {
            safe { callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
            return
        }
        if (!engine.isModelPresent()) {
            safe { callback.error(SpeechRecognizer.ERROR_SERVER) }
            return
        }
        // BCP-47 (e.g. "en-US") → Whisper ISO-639-1 ("en"); null/blank ⇒ auto-detect.
        val lang = recognizerIntent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?.substringBefore('-')?.lowercase()?.takeIf { it.isNotBlank() }
        Session(callback, lang).also { session = it }.start()
    }

    override fun onStopListening(callback: Callback) {
        session?.requestStop()
    }

    override fun onCancel(callback: Callback) {
        session?.cancel()
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        engine.close()
        super.onDestroy()
    }

    private fun clearSession(s: Session) {
        if (session === s) session = null
    }

    /** One recognition session: records, VADs for pauses, transcribes. */
    private inner class Session(private val cb: Callback, private val lang: String?) {
        private val chunks = ArrayList<ShortArray>()
        @Volatile private var running = false
        @Volatile private var userStopped = false
        @Volatile private var cancelled = false
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        // All Whisper calls (partials + final) run here, one at a time, so the engine
        // stays single-threaded while the recording thread keeps draining the mic.
        private val transcribeExec = Executors.newSingleThreadExecutor()
        @Volatile private var partialPending = false

        fun start() {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (minBuf <= 0) { finishError(SpeechRecognizer.ERROR_AUDIO); return }
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuf, SAMPLE_RATE * 2),
                )
            } catch (t: Throwable) {
                Log.e(TAG, "AudioRecord init failed", t); null
            }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                rec?.release(); finishError(SpeechRecognizer.ERROR_AUDIO); return
            }
            record = rec
            running = true
            safe { cb.readyForSpeech(Bundle()) }
            try { rec.startRecording() } catch (t: Throwable) {
                Log.e(TAG, "startRecording failed", t); finishError(SpeechRecognizer.ERROR_AUDIO); return
            }
            thread = Thread { loop() }.apply { start() }
        }

        fun requestStop() { userStopped = true }

        fun cancel() {
            cancelled = true
            running = false
            releaseRecord()
            transcribeExec.shutdownNow()
        }

        private fun loop() {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            var speechStarted = false
            var began = false
            var silenceMs = 0
            var totalMs = 0
            // Fire at most one partial per pause: armed by any speech, disarmed on fire.
            var partialArmed = false
            while (running) {
                val n = record?.read(chunk, 0, chunk.size) ?: -1
                if (n <= 0) { if (cancelled) return else continue }
                chunks.add(chunk.copyOf(n))
                val chunkMs = n * 1000 / SAMPLE_RATE
                totalMs += chunkMs
                val rms = rms(chunk, n)
                safe { cb.rmsChanged(rmsToDb(rms)) }

                if (rms > SPEECH_RMS) {
                    if (!began) { safe { cb.beginningOfSpeech() }; began = true }
                    speechStarted = true
                    silenceMs = 0
                    partialArmed = true // new speech since last partial → allow the next pause to fire
                } else if (speechStarted) {
                    silenceMs += chunkMs
                }

                // VAD pause → emit a partial for the audio so far (phrase boundary).
                if (speechStarted && partialArmed && !partialPending &&
                    silenceMs in PARTIAL_SILENCE_MS until END_SILENCE_MS
                ) {
                    partialArmed = false
                    partialPending = true
                    val snapshot = flatten()
                    transcribeExec.execute {
                        val t = engine.transcribe(snapshot, lang)?.trim()
                        partialPending = false
                        if (!cancelled && !t.isNullOrBlank()) {
                            safe {
                                cb.partialResults(Bundle().apply {
                                    putStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(t),
                                    )
                                })
                            }
                        }
                    }
                }

                if (cancelled) return
                if (userStopped) break
                if (speechStarted && silenceMs >= END_SILENCE_MS) break
                if (totalMs >= MAX_MS) break
                if (!speechStarted && totalMs >= NO_SPEECH_MS) { finishNoMatch(); return }
            }
            if (cancelled) return
            finishAndTranscribe(speechStarted)
        }

        private fun finishAndTranscribe(speechStarted: Boolean) {
            releaseRecord()
            safe { cb.endOfSpeech() }
            if (!speechStarted) { finishNoMatch(); return }
            val audio = flatten()
            // Queue the final pass behind any in-flight partial so the engine is only ever
            // touched by one thread; this is the last task submitted.
            transcribeExec.execute {
                val text = engine.transcribe(audio, lang)?.trim()
                if (cancelled) return@execute
                if (text.isNullOrBlank()) { finishNoMatch(); return@execute }
                val b = Bundle().apply {
                    putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                    putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                }
                safe { cb.results(b) }
                clearSession(this)
            }
            transcribeExec.shutdown()
        }

        private fun flatten(): ShortArray {
            val total = chunks.sumOf { it.size }
            val out = ShortArray(total)
            var o = 0
            for (c in chunks) { System.arraycopy(c, 0, out, o, c.size); o += c.size }
            return out
        }

        private fun finishNoMatch() {
            releaseRecord()
            safe { cb.error(SpeechRecognizer.ERROR_NO_MATCH) }
            clearSession(this)
        }

        private fun finishError(code: Int) {
            releaseRecord()
            safe { cb.error(code) }
            clearSession(this)
        }

        private fun releaseRecord() {
            running = false
            try { record?.stop() } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            record = null
        }
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    private fun rms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / n)
    }

    private fun rmsToDb(rms: Double): Float =
        (10.0 * log10(rms + 1.0)).toFloat().coerceIn(0f, 30f) / 3f // roughly 0..10 for the UI meter

    companion object {
        private const val TAG = "WhisperRecognition"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val SPEECH_RMS = 500.0      // 16-bit RMS above this counts as speech
        private const val PARTIAL_SILENCE_MS = 350 // a pause this long → emit a partial
        private const val END_SILENCE_MS = 1000    // trailing silence that ends the utterance
        private const val NO_SPEECH_MS = 8000      // give up if nothing is said
        private const val MAX_MS = 29000           // Whisper handles <= 30 s
    }
}
