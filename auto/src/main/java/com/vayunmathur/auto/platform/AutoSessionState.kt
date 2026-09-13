package com.vayunmathur.auto.platform

import android.os.SystemClock
import com.vayunmathur.auto.protocol.AckTracker
import com.vayunmathur.auto.protocol.AudioSinkRole
import com.vayunmathur.auto.protocol.FocusArbitration
import com.vayunmathur.auto.protocol.VideoFocus
import com.vayunmathur.auto.protocol.gal.AudioFocusState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The video stream the head unit accepted, for the phone status screen. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val configIndex: Int,
)

/**
 * Something the video channel observed, forwarded to [AutoSessionState] for the phone UI.
 *
 * Streaming never waits on these: they are fire-and-forget observations, and the service
 * forwards them without gating any protocol step on the UI.
 */
sealed interface VideoEvent {
    /** The head unit accepted video; these are the parameters it chose. */
    data class Setup(val info: VideoInfo) : VideoEvent

    /** Video focus moved, e.g. to `VIDEO_FOCUS_NATIVE` when the car takes the screen back. */
    data class FocusChanged(val mode: String) : VideoEvent

    /** The first encoded frame went out. */
    data object FirstFrame : VideoEvent

    /**
     * One encoded frame went out.
     *
     * [presentationTimeUs] is the codec timestamp; [latencyUs] is monotonic
     * queue + encode time for this frame (send time minus its timestamp).
     * Sender-side only: this counts frames handed to the socket, never frames
     * decoded or shown by the head unit.
     */
    data class FrameSent(val presentationTimeUs: Long, val latencyUs: Long) : VideoEvent

    /**
     * The head unit acknowledged frames (0x8004 on the video channel).
     *
     * [ackSeq] is the `ack` frame counter from the `MediaAck`, unsigned-extended;
     * null when the head unit omits the field. The counter is **mod 256** — it
     * wraps, so the session unwraps it rather than treating a lower value as a
     * regression. [extraCount] is how many `field3` entries rode along. An ack
     * confirms receipt, not visibility — the sender-side proxy for "visible" is
     * acks advancing in step with frames.
     */
    data class AckReceived(val ackSeq: Long?, val extraCount: Int) : VideoEvent

    /** An ack named a session that is not ours; the session id lines up otherwise. */
    data class AckMismatch(val expected: Int, val actual: Int) : VideoEvent

    /** One encoder drain produced [framesOut] frames (0 when it had nothing ready). */
    data class EncoderDrained(val framesOut: Int) : VideoEvent

    /** The virtual-display + encoder-input-surface pair became valid or went away. */
    data class SurfaceChanged(val valid: Boolean) : VideoEvent
}

/**
 * The bridge between the projection session and the phone UI.
 *
 * The session runs on the single `ma-auto-projection` worker thread and the ViewModel lives
 * on the main thread, so they share nothing but these flows. Setting a [MutableStateFlow]
 * value is thread-safe, which is what makes worker-thread emission safe here; the encoder
 * pump itself stays single-threaded exactly as before.
 */
object AutoSessionState {
    private val _connection =
        MutableStateFlow<AutoConnectionState>(AutoConnectionState.Disconnected)
    val connection: StateFlow<AutoConnectionState> = _connection.asStateFlow()

    private val _video = MutableStateFlow<VideoInfo?>(null)
    val video: StateFlow<VideoInfo?> = _video.asStateFlow()

    private val _focusMode = MutableStateFlow<String?>(null)
    val focusMode: StateFlow<String?> = _focusMode.asStateFlow()

    /**
     * Arbitrated video focus, mirrored from the session's [FocusArbitration].
     * The phone status screen and the input sink gate on this, not on the raw
     * 0x8008 mode string: transient and no-input variants fold in here.
     */
    private val _videoFocus = MutableStateFlow(VideoFocus.NONE)
    val videoFocus: StateFlow<VideoFocus> = _videoFocus.asStateFlow()

    /** Last audio focus state the head unit reported; INVALID until the first 0x13. */
    private val _audioFocus = MutableStateFlow(AudioFocusState.AUDIO_FOCUS_STATE_INVALID)
    val audioFocus: StateFlow<AudioFocusState> = _audioFocus.asStateFlow()

    /**
     * Whether head-unit input may be injected. Derived from video focus, not
     * negotiated: projected video with input focus only.
     */
    private val _inputAllowed = MutableStateFlow(false)
    val inputAllowed: StateFlow<Boolean> = _inputAllowed.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    private val _acksSeen = MutableStateFlow(0L)
    val acksSeen: StateFlow<Long> = _acksSeen.asStateFlow()

    private val _ackMismatches = MutableStateFlow(0L)
    val ackMismatches: StateFlow<Long> = _ackMismatches.asStateFlow()

    /**
     * Encode-side frames per second over a sliding window of send timestamps.
     * Sender-side: frames handed to the socket per second, not frames displayed.
     */
    private val _encodedFps = MutableStateFlow(0.0)
    val encodedFps: StateFlow<Double> = _encodedFps.asStateFlow()

    /**
     * Head-unit acks per second over a sliding window of ack timestamps.
     * The sender-side proxy for "visible" is this advancing in step with
     * [encodedFps], not the total in [acksSeen].
     */
    private val _ackFps = MutableStateFlow(0.0)
    val ackFps: StateFlow<Double> = _ackFps.asStateFlow()

    /** Millis since the last 0x8004 ack; null when none has arrived this session. */
    private val _lastAckAgeMs = MutableStateFlow<Long?>(null)
    val lastAckAgeMs: StateFlow<Long?> = _lastAckAgeMs.asStateFlow()

    /** Wall-clock millis of the last 0x8004 ack; the UI derives a live age from this. */
    private val _lastAckAt = MutableStateFlow<Long?>(null)
    val lastAckAt: StateFlow<Long?> = _lastAckAt.asStateFlow()

    /** Highest 0x8004 `ack` counter seen, unwrapped across mod-256 wraps; null until an ack carries the field. */
    private val _lastAckSeq = MutableStateFlow<Long?>(null)
    val lastAckSeq: StateFlow<Long?> = _lastAckSeq.asStateFlow()

    /** Rolling mean encode-to-send latency in microseconds over recent frames. */
    private val _avgEncodeLatencyUs = MutableStateFlow<Long?>(null)
    val avgEncodeLatencyUs: StateFlow<Long?> = _avgEncodeLatencyUs.asStateFlow()

    /** Encoder drains so far; frames ride out via [framesSent]. */
    private val _encoderDrains = MutableStateFlow(0L)
    val encoderDrains: StateFlow<Long> = _encoderDrains.asStateFlow()

    /** Whether the virtual display + encoder input surface pair is currently up. */
    private val _surfaceValid = MutableStateFlow(false)
    val surfaceValid: StateFlow<Boolean> = _surfaceValid.asStateFlow()

    /** Latest now-playing snapshot from the on-device media session; null until one reports. */
    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<NowPlayingInfo?> = _nowPlaying.asStateFlow()

    /** ch14 threads mirrored this session; reset per socket like the video counters. */
    private val _threadsPosted = MutableStateFlow(0L)
    val threadsPosted: StateFlow<Long> = _threadsPosted.asStateFlow()

    /** ch14 message bodies posted this session. */
    private val _messagesPosted = MutableStateFlow(0L)
    val messagesPosted: StateFlow<Long> = _messagesPosted.asStateFlow()

    /** Head-unit replies (typed or voice) received this session. */
    private val _repliesReceived = MutableStateFlow(0L)
    val repliesReceived: StateFlow<Long> = _repliesReceived.asStateFlow()

    /** ch8 touch frames injected this session; reset per socket like the rest. */
    private val _touchesInjected = MutableStateFlow(0L)
    val touchesInjected: StateFlow<Long> = _touchesInjected.asStateFlow()

    /** ch8 key presses/releases injected this session. */
    private val _keysInjected = MutableStateFlow(0L)
    val keysInjected: StateFlow<Long> = _keysInjected.asStateFlow()

    /** ch8 scroll ticks injected this session. */
    private val _scrollsInjected = MutableStateFlow(0L)
    val scrollsInjected: StateFlow<Long> = _scrollsInjected.asStateFlow()

    /** ch8 reports dropped for lack of input focus this session. */
    private val _inputDropped = MutableStateFlow(0L)
    val inputDropped: StateFlow<Long> = _inputDropped.asStateFlow()

    /** ch7 SensorRequests sent this session; reset per socket like the rest. */
    private val _sensorsSubscribed = MutableStateFlow(0L)
    val sensorsSubscribed: StateFlow<Long> = _sensorsSubscribed.asStateFlow()

    /** ch7 SensorBatches received this session. */
    private val _sensorBatches = MutableStateFlow(0L)
    val sensorBatches: StateFlow<Long> = _sensorBatches.asStateFlow()

    /** ch7 SensorErrors observed this session (never fatal). */
    private val _sensorErrors = MutableStateFlow(0L)
    val sensorErrors: StateFlow<Long> = _sensorErrors.asStateFlow()

    /** Guidance setup/config observations on ch3 this session. */
    private val _guidanceEvents = MutableStateFlow(0L)
    val guidanceEvents: StateFlow<Long> = _guidanceEvents.asStateFlow()

    /** Nav-status posts on ch10 this session. */
    private val _navStatusPosts = MutableStateFlow(0L)
    val navStatusPosts: StateFlow<Long> = _navStatusPosts.asStateFlow()

    /**
     * Per-role sink status (ch4 SYS, ch5 MEDIA); empty until a channel opens.
     * Reset per socket like the rest: a new socket means a new session.
     */
    private val _sinkStatus = MutableStateFlow<Map<AudioSinkRole, AudioSinkStatus>>(emptyMap())
    val sinkStatus: StateFlow<Map<AudioSinkRole, AudioSinkStatus>> = _sinkStatus.asStateFlow()

    /** PCM bytes framed out on ch4/5 this session. */
    private val _audioBytesSent = MutableStateFlow(0L)
    val audioBytesSent: StateFlow<Long> = _audioBytesSent.asStateFlow()

    /** Music PCM bytes captured into the ch5 sink this session. */
    private val _musicBytesCaptured = MutableStateFlow(0L)
    val musicBytesCaptured: StateFlow<Long> = _musicBytesCaptured.asStateFlow()

    /** 0x800B sync pulses answered on ch4/5 this session. */
    private val _audioSyncs = MutableStateFlow(0L)
    val audioSyncs: StateFlow<Long> = _audioSyncs.asStateFlow()

    /** 0x8004 sink acks received this session. */
    private val _audioAcks = MutableStateFlow(0L)
    val audioAcks: StateFlow<Long> = _audioAcks.asStateFlow()

    /** TTS utterances that reached the car this session. */
    val ttsSpoken: StateFlow<Long> = _ttsSpoken.asStateFlow()

    /** TTS utterances dropped (no sink, bad wav, dead engine) this session. */
    private val _ttsDropped = MutableStateFlow(0L)
    val ttsDropped: StateFlow<Long> = _ttsDropped.asStateFlow()

    /** ch6 mic turns that yielded retained PCM this session. */
    private val _micTurns = MutableStateFlow(0L)
    val micTurns: StateFlow<Long> = _micTurns.asStateFlow()

    /** ch6 mic chunks acked upstream this session. */
    val micAcks: StateFlow<Long> = _micAcks.asStateFlow()

    /** Live telecom calls currently tracked by the InCall owners; 0 with none. */
    private val _activeCalls = MutableStateFlow(0)
    val activeCalls: StateFlow<Int> = _activeCalls.asStateFlow()
    /**
     * Whether the head unit reports calls available (control 24, parsed
     * protocol-side into the session). Null until the first verdict; the
     * service mirrors it with the session like focus, and the InCall owners
     * gate projected call UI on it.
     */
    private val _callAvailable = MutableStateFlow<Boolean?>(null)
    val callAvailable: StateFlow<Boolean?> = _callAvailable.asStateFlow()

    /**
     * Guards [frameTimes], [ackTimes] and [ackTracker]. Frames are observed on the
     * main-thread vsync drain while acks arrive on the `ma-auto-projection` pump
     * thread, so the sliding windows are shared across threads. Flow writes stay
     * lock-free (StateFlow sets are thread-safe); only the deque/tracker math
     * takes this lock.
     */
    private val telemetryLock = Any()

    /**
     * Send/ack timestamps backing the fps windows. Guarded by [telemetryLock]:
     * frames arrive on the main-thread vsync drain, acks on the pump thread.
     */
    private val frameTimes = ArrayDeque<Long>()
    private val ackTimes = ArrayDeque<Long>()

    /**
     * Last unwrapped 0x8004 ack counter. The wire `ack` field is a frame counter
     * mod 256, so it wraps: an [AckTracker] folds arrivals into a monotonically
     * non-decreasing sequence. Guarded by [telemetryLock] with the windows above.
     */
    private val ackTracker = AckTracker()

    /** Wall-clock millis when the session became active, for the phone's elapsed counter. */
    private val _sessionStartedAt = MutableStateFlow<Long?>(null)
    val sessionStartedAt: StateFlow<Long?> = _sessionStartedAt.asStateFlow()

    /**
     * Whole days until the shipped GAL leaf expires, parsed at runtime from the
     * PEM so it stays correct across a rotation. Null until the service seeds it
     * (or when the leaf cannot be parsed). Deliberately outside [resetTelemetry]:
     * the credential outlives any one session.
     */
    private val _credentialDaysLeft = MutableStateFlow<Long?>(null)
    val credentialDaysLeft: StateFlow<Long?> = _credentialDaysLeft.asStateFlow()

    /** Records the days remaining on the shipped GAL leaf; see [credentialDaysLeft]. */
    fun onCredentialExpiry(daysLeft: Long) {
        _credentialDaysLeft.value = daysLeft
    }

    /** A socket was accepted; version negotiation and the TLS handshake are next. */
    fun onSocketAccepted() {
        _video.value = null
        _focusMode.value = null
        resetFocus()
        _nowPlaying.value = null
        _framesSent.value = 0
        _acksSeen.value = 0
        _ackMismatches.value = 0
        _threadsPosted.value = 0
        _messagesPosted.value = 0
        _repliesReceived.value = 0
        _touchesInjected.value = 0
        _keysInjected.value = 0
        _scrollsInjected.value = 0
        _inputDropped.value = 0
        _sensorsSubscribed.value = 0
        _sensorBatches.value = 0
        _sensorErrors.value = 0
        _guidanceEvents.value = 0
        _navStatusPosts.value = 0
        _sinkStatus.value = emptyMap()
        _audioBytesSent.value = 0
        _musicBytesCaptured.value = 0
        _audioSyncs.value = 0
        _audioAcks.value = 0
        _ttsSpoken.value = 0
        _ttsDropped.value = 0
        _micTurns.value = 0
        _micAcks.value = 0
        _callAvailable.value = null
        _activeCalls.value = 0
        _activeCall.value = null
        resetTelemetry()
        _sessionStartedAt.value = null
        _connection.value = AutoConnectionState.Connecting
    }

    /** Discovery completed and the bring-up is running against [carName]. */
    fun onActive(carName: String) {
        _sessionStartedAt.value = System.currentTimeMillis()
        _connection.value = AutoConnectionState.Projecting(carName)
    }

    /** The link came up but authentication failed, or the session otherwise refused us. */
    fun onRejected() {
        _connection.value = AutoConnectionState.Rejected
    }

    /** Mirrors one control-24 call-availability verdict; see [callAvailable]. */
    fun onCallAvailability(available: Boolean) {
        _callAvailable.value = available
    }

    /**
     * Active call details for the projected call card ([UnCallView] shape).
     * Null with no live call; replaced wholesale on every telecom callback
     * so the card never shows a half-updated row. [state] is the
     * `android.telecom.Call` state int; [number] is the handle's scheme part
     * (null when withheld); [startedMs] is wall-clock accept time for the
     * duration ticker (0 while ringing); [held]/[muted] drive the
     * disabled-alpha + action states.
     */
    private val _activeCall = MutableStateFlow<ActiveCallInfo?>(null)
    val activeCall: StateFlow<ActiveCallInfo?> = _activeCall.asStateFlow()

    /** One telecom call entered an InCall owner; see [activeCall]. */
    fun onCallAdded(info: ActiveCallInfo) {
        _activeCall.value = info
        _activeCalls.value++
    }

    /** One telecom call changed state; replaces the card snapshot wholesale. */
    fun onCallChanged(info: ActiveCallInfo) {
        _activeCall.value = info
    }

    /** One telecom call left its InCall owner; floors at zero, never negative. */
    fun onCallRemoved() {
        _activeCall.value = null
        _activeCalls.value = (_activeCalls.value - 1).coerceAtLeast(0)
    }

    /** The head unit went away cleanly. */
    fun onDisconnected() {
        _connection.value = AutoConnectionState.Disconnected
        _video.value = null
        _focusMode.value = null
        resetFocus()
        _nowPlaying.value = null
        _sessionStartedAt.value = null
        resetTelemetry()
    }

    /**
     * Mirrors the session's [FocusArbitration] into the phone-visible flows.
     *
     * Called from `GalControlSession.onFocusChange` on the pump thread; StateFlow
     * sets are thread-safe, so no lock is needed. The input sink gates on
     * [inputAllowed] and the audio sinks on [audioFocus], never on the raw
     * 0x8008 string. The [AutoConnectionState.Projecting] snapshot carries the
     * video focus too, so the status screen reads one source of truth.
     */
    fun onFocusChanged(arbitration: FocusArbitration) {
        _videoFocus.value = arbitration.videoFocus
        _audioFocus.value = arbitration.audioFocus
        _inputAllowed.value = arbitration.inputAllowed
        val current = _connection.value
        if (current is AutoConnectionState.Projecting) {
            _connection.value = current.copy(videoFocus = arbitration.videoFocus)
        }
    }

    /** Clears the arbitrated focus flows; a new socket means a new session. */
    private fun resetFocus() {
        _videoFocus.value = VideoFocus.NONE
        _audioFocus.value = AudioFocusState.AUDIO_FOCUS_STATE_INVALID
        _inputAllowed.value = false
    }

    /**
     * Clears every Phase 0 telemetry counter. Called from [onSocketAccepted] and
     * [onDisconnected] alongside the existing counters — a new socket means a
     * new session, so stale fps/age/seq values must not leak across.
     */
    private fun resetTelemetry() {
        synchronized(telemetryLock) {
            frameTimes.clear()
            ackTimes.clear()
            ackTracker.reset()
        }
        _encodedFps.value = 0.0
        _ackFps.value = 0.0
        _lastAckAgeMs.value = null
        _lastAckAt.value = null
        _lastAckSeq.value = null
        _avgEncodeLatencyUs.value = null
        _encoderDrains.value = 0
        _surfaceValid.value = false
    }

    /** End of a session: a recorded failure means refusal, anything else a clean parting. */
    fun onSessionEnd(failure: String?) {
        if (failure != null) onRejected() else onDisconnected()
    }

    fun onVideoEvent(event: VideoEvent) {
        when (event) {
            is VideoEvent.Setup -> _video.value = event.info
            is VideoEvent.FocusChanged -> _focusMode.value = event.mode
            VideoEvent.FirstFrame -> Unit
            is VideoEvent.FrameSent -> {
                _framesSent.value++
                val now = SystemClock.uptimeMillis()
                val fps = synchronized(telemetryLock) {
                    frameTimes.addLast(now)
                    while (frameTimes.isNotEmpty() && now - frameTimes.first() > FPS_WINDOW_MS) {
                        frameTimes.removeFirst()
                    }
                    frameTimes.size * 1_000.0 / FPS_WINDOW_MS
                }
                _encodedFps.value = fps
                // Blend this frame's encode-to-send latency into the rolling mean.
                val previous = _avgEncodeLatencyUs.value
                _avgEncodeLatencyUs.value =
                    if (previous == null) event.latencyUs else (previous + event.latencyUs) / 2
                // Keep the ack age live while frames flow: it is the gap since the
                // last ack, recomputed on every frame, not just when an ack lands.
                refreshAckAge(System.currentTimeMillis())
            }
            is VideoEvent.AckReceived -> {
                _acksSeen.value++
                recordAck(event.ackSeq)
            }
            is VideoEvent.AckMismatch -> {
                _acksSeen.value++
                _ackMismatches.value++
                // A mismatched ack still proves the head unit is alive and consuming.
                _lastAckAt.value = System.currentTimeMillis()
                _lastAckAgeMs.value = 0
            }
            is VideoEvent.EncoderDrained -> _encoderDrains.value++
            is VideoEvent.SurfaceChanged -> _surfaceValid.value = event.valid
        }
    }

    /** Records a media snapshot. Thread-safe like every other flow write here. */
    fun onMediaEvent(event: MediaEvent) {
        when (event) {
            is MediaEvent.NowPlayingChanged -> _nowPlaying.value = event.info
        }
    }

    /** Records a messaging-channel observation. Thread-safe like every other flow write here. */
    fun onMessagingEvent(event: MessagingEvent) {
        when (event) {
            // Absolute, not incremental: the owner posts the whole thread map per
            // message, so its size is the session total.
            is MessagingEvent.ThreadsPosted -> _threadsPosted.value = event.count.toLong()
            is MessagingEvent.MessagePosted -> _messagesPosted.value++
            is MessagingEvent.ReplyReceived -> _repliesReceived.value++
            is MessagingEvent.ReplySent -> _repliesReceived.value++
            is MessagingEvent.ReplyFailed -> Unit
            is MessagingEvent.MarkedRead -> Unit
        }
    }

    /** Records an input-channel observation. Thread-safe like every other flow write here. */
    fun onInputEvent(event: InputEvent) {
        when (event) {
            is InputEvent.BindingRequested -> Unit
            is InputEvent.BindingAnswered -> Unit
            is InputEvent.Touch -> _touchesInjected.value++
            is InputEvent.Key -> _keysInjected.value++
            is InputEvent.Scroll -> _scrollsInjected.value++
            is InputEvent.VolumeKey -> _keysInjected.value++
            is InputEvent.FeedbackSent -> Unit
            InputEvent.DroppedNoFocus -> _inputDropped.value++
        }
    }

    /** Records an audio/mic observation. Thread-safe like every other flow write here. */
    fun onAudioEvent(event: AudioEvent) {
        when (event) {
            is AudioEvent.SinkSetup -> Unit
            is AudioEvent.SinkStatus -> _sinkStatus.value += event.status.role to event.status
            is AudioEvent.SinkStarted -> Unit
            is AudioEvent.SinkStopped -> Unit
            is AudioEvent.FramesSent -> _audioBytesSent.value += event.bytes
            is AudioEvent.SyncReceived -> _audioSyncs.value++
            is AudioEvent.AckReceived -> _audioAcks.value++
            is AudioEvent.TtsSpoken -> _ttsSpoken.value++
            is AudioEvent.TtsDropped -> _ttsDropped.value++
            is AudioEvent.MicTurn -> _micTurns.value++
            AudioEvent.MicAcked -> _micAcks.value++
            AudioEvent.MicIdle -> Unit
            is AudioEvent.MusicCaptured -> _musicBytesCaptured.value += event.bytes
            is AudioEvent.MusicDropped -> Unit
        }
    }

    /** Records a sensor/guidance/nav-status observation. Thread-safe like every other flow write here. */
    fun onSensorEvent(event: SensorEvent) {
        when (event) {
            is SensorEvent.Subscribed -> _sensorsSubscribed.value++
            is SensorEvent.SubscriptionAnswered -> Unit
            is SensorEvent.BatchReceived -> _sensorBatches.value++
            is SensorEvent.SensorError -> _sensorErrors.value++
            SensorEvent.GuidanceSetup -> _guidanceEvents.value++
            is SensorEvent.GuidanceConfigured -> _guidanceEvents.value++
            SensorEvent.NavStatusPosted -> _navStatusPosts.value++
        }
    }

    /** Shares the ack timestamp/rate bookkeeping between matching and mismatched acks. */
    private fun recordAck(ackSeq: Long?) {
        val nowWall = System.currentTimeMillis()
        val nowUptime = SystemClock.uptimeMillis()
        _lastAckAt.value = nowWall
        _lastAckAgeMs.value = 0
        val fps: Double
        val unwrapped: Long?
        synchronized(telemetryLock) {
            ackTimes.addLast(nowUptime)
            while (ackTimes.isNotEmpty() && nowUptime - ackTimes.first() > FPS_WINDOW_MS) {
                ackTimes.removeFirst()
            }
            fps = ackTimes.size * 1_000.0 / FPS_WINDOW_MS
            unwrapped = if (ackSeq != null) ackTracker.onAck(ackSeq) else null
        }
        _ackFps.value = fps
        if (unwrapped != null) {
            _lastAckSeq.value = unwrapped
        }
    }

    /** Recomputes the last-ack age against [nowWall]; a no-op until the first ack. */
    private fun refreshAckAge(nowWall: Long) {
        val at = _lastAckAt.value ?: return
        _lastAckAgeMs.value = (nowWall - at).coerceAtLeast(0)
    }

    /** Sliding window the fps rates are computed over. */
    private const val FPS_WINDOW_MS = 5_000L
}
