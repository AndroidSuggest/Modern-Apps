package com.vayunmathur.cast.platform

import android.content.Context
import android.hardware.display.DisplayManager
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.platform.discovery.CastDiscoveryManager
import com.vayunmathur.cast.platform.mirror.CaptureGeometry
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.platform.mirror.MirrorStopReason
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.sdk.cast.CastContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of the live session.
 *
 * An object rather than ViewModel-owned state, because both [CastViewModel] and [CastService] act on
 * the same session and neither reliably outlives the other: rotating the device rebuilds the ViewModel,
 * and the service is what keeps the session alive while the app is not in front. `:share`'s
 * `ShareReceiveController` exists for the same reason.
 *
 * **The shape of a session changed with the protocol.** Under Cast the sequence was CONNECT → LAUNCH →
 * join → OFFER, and consent had to wait for the receiver app to come up. Now it is: connect, pair
 * (possibly waiting for six digits from the user), *then* ask for consent, then configure the stream
 * against the TV's real limits. Pairing before consent is deliberate - there is no point recording the
 * screen for a TV that is going to reject the code.
 *
 * Everything that touches [MirrorClient] happens on [scope] under [mutex], because it is a plain
 * sequential exchange on one socket and two callers interleaving would desynchronise the stream.
 */
object CastController {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val mutex = Mutex()

    internal var socket: ControlSocket? = null
    internal var client: MirrorClient? = null
    internal var watchJob: Job? = null

    /**
     * The keep-alive on a served session's control channel.
     *
     * Its own job rather than part of [watchJob], because that one is parked in a blocking read for
     * the whole session and has nowhere to run a timer.
     */
    internal var pingJob: Job? = null

    /** The running mirror. Its retransmit buffers live per-stream inside it. */
    internal var engine: MirrorEngine? = null

    /**
     * The HTTPS listener serving a content session, or null when there is not one.
     *
     * Beside [engine] rather than inside it, because the two are alternatives: a served session
     * starts no encoder at all. Torn down through the same paths, so a session that ends leaves no
     * open port behind.
     */
    internal var proxy: MediaProxyServer? = null

    /**
     * The codec the running engine is encoding with.
     *
     * Kept so a failure can be attributed to it and remembered against this TV: [MirrorStopReason] is
     * a cause rather than a codec, and by the time it arrives the geometry that chose the codec is
     * gone.
     */
    internal var activeCodec: VideoCodec? = null

    /**
     * The desktop session's source, kept because its display outlives every [engine] built for it.
     *
     * Null for a mirroring or content session, which have no display of their own to preserve.
     */
    internal var desktopSource: MirrorSource.SystemDisplay? = null

    /**
     * Watches the desktop display for the user choosing a different mode in Settings.
     *
     * There is no callback for "the user picked 1080p": the framework simply resizes the display
     * and the owner is expected to notice. Nothing did, which is why the stream used to carry on
     * at whatever geometry the session started with no matter what was selected.
     */
    internal var displayListener: DisplayManager.DisplayListener? = null

    /** Held alongside [displayListener], because unregistering needs the same service instance. */
    internal var displayManager: DisplayManager? = null

    /**
     * The geometry the running engine was built for, so a display change can be told from an echo.
     *
     * Our own re-negotiation resizes the display, which fires the very listener that started it.
     * Without something to compare against, that is a loop.
     */
    internal var activeGeometry: CaptureGeometry? = null

    /** Serialises re-negotiations so two mode changes in quick succession cannot interleave. */
    internal var renegotiateJob: Job? = null

    internal val _mirrorPhase = MutableStateFlow(MirrorPhase.Idle)
    val mirrorPhase: StateFlow<MirrorPhase> = _mirrorPhase.asStateFlow()

    internal val _degradation = MutableStateFlow(MirrorDegradation())
    val degradation: StateFlow<MirrorDegradation> = _degradation.asStateFlow()

    /** Why mirroring failed, already a user-facing sentence. */
    internal val _failure = MutableStateFlow<String?>(null)
    val mirrorFailure: StateFlow<String?> = _failure.asStateFlow()

    private var discoveryManager: CastDiscoveryManager? = null

    internal val _device = MutableStateFlow<CastDevice?>(null)
    val device: StateFlow<CastDevice?> = _device.asStateFlow()

    internal val _sessionState = MutableStateFlow(ClientState())
    val sessionState: StateFlow<ClientState> = _sessionState.asStateFlow()

    /** True from the moment a device is tapped until it is paired or refuses. */
    internal val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting.asStateFlow()

    /**
     * Whether the session being opened should go on to ask for capture consent.
     *
     * Remembered from [connect] because a code pairing returns to the user and comes back through
     * [submitPairCode], which would otherwise have to assume - and assuming "yes" is what would put the
     * screen-capture dialog in front of an app that launched the picker to send its own content.
     */
    internal var pendingMirror: Boolean = true

    /**
     * Notified when an SDK session ends for a reason the app did not ask for.
     *
     * Set by `ContentCastService` for the life of one session and cleared as it ends, so there is
     * exactly one at a time - which matches the one session this object holds. A callback rather than a
     * flow because there is nothing to observe when it is absent, and a reason has to reach the client
     * once rather than be a current value.
     */
    var onContentSessionEnded: ((Int) -> Unit)? = null

    /**
     * Notified when the television's remote is pressed during an SDK session.
     *
     * Set and cleared by `ContentCastService` alongside [onContentSessionEnded], and for the same
     * reasons: one session, one callback, and a command has to reach the app once rather than be a
     * current value.
     *
     * Null during screen mirroring, which has no transport to control - and [startWatch] does not even
     * offer the dispatch in that case, so a stray command never reaches this far.
     */
    var onPlaybackCommand: ((PlaybackCommand) -> Unit)? = null

    /**
     * Notified with the *television's* playback, during a served session.
     *
     * The inverse of [reportPlaybackState] and set on the same terms as [onPlaybackCommand]. Only a
     * served session produces these: it is the one case where the TV holds the player, so it is the one
     * case where the phone has to be told rather than telling.
     */
    var onTvPlaybackState: ((PlaybackState) -> Unit)? = null

    /**
     * The name of the app that last completed `CastPickerActivity`, resolved from its
     * `callingPackage`.
     *
     * Kept here rather than passed through the IPC on purpose: a self-reported label would be a lie an
     * app could tell, and the TV displays this. Empty means screen mirroring.
     */
    var contentAppLabel: String = ""

    fun discovery(context: Context): CastDiscoveryManager =
        discoveryManager ?: CastDiscoveryManager(context.applicationContext)
            .also { discoveryManager = it }

    /**
     * Log every packet and a throughput summary once a second.
     *
     * A plain switch rather than a build flag: mirroring can only be diagnosed on hardware, and
     * something that needs a recompile to turn on does not get turned on.
     */
    var verboseStreamLogging: Boolean = false

    fun stopMirroring(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            endContentSession(CastContract.REASON_CLIENT_CLOSED)
            // **Before [stopEngine], which closes the proxy.** The TV owns its own clock and buffer, so
            // told afterwards it would keep playing what it had already fetched and then fail its next
            // fetch against a port that has gone - an error card instead of the idle screen. `proxy`
            // being set is exactly "this was a served session"; nothing else needs telling.
            if (proxy != null) client?.let { mutex.withLock { it.sendContentEnded() } }
            stopEngine()
            _mirrorPhase.value = MirrorPhase.Idle
            _sessionState.update {
                if (it.phase == ClientPhase.Streaming) it.copy(phase = ClientPhase.Paired) else it
            }
            CastService.stopMirroring(appContext)
        }
    }

    internal fun onEngineStopped(context: Context, reason: MirrorStopReason) {
        val codec = activeCodec
        _failure.value = when (reason) {
            MirrorStopReason.Udp -> context.getString(R.string.cast_mirror_udp_failed)
            MirrorStopReason.NoEncoders -> context.getString(R.string.cast_mirror_no_encoder)
            MirrorStopReason.ReceiverGone -> context.getString(R.string.cast_mirror_receiver_gone)
            MirrorStopReason.CodecConfig ->
                context.getString(R.string.cast_mirror_codec_config_failed)
            MirrorStopReason.NoVideoOutput ->
                context.getString(R.string.cast_mirror_no_video_output)
        }
        _mirrorPhase.value = MirrorPhase.Failed
        endContentSession(CastContract.REASON_FAILED)
        CastService.stopMirroring(context)
        if (reason == MirrorStopReason.CodecConfig && codec != null) {
            endCodecConfigFailure(context, codec)
        }
    }

    /**
     * End the whole session, not just the mirror, and remember the codec that did it.
     *
     * **Unlike every other stop reason, this one leaves a perfectly healthy control channel.** Nothing
     * would tear the session down, so `_sessionState` would sit at [ClientPhase.Streaming] - and
     * [connect] treats that as live, so tapping the same TV again would return early and do nothing.
     * The user would be left unable to retry the very TV that just failed, which is exactly the retry
     * the demotion exists to make work.
     *
     * Launched rather than run inline because this is called from the engine's own video coroutine, and
     * [stopEngine] joins that coroutine - doing it here would be waiting on ourselves.
     */
    internal fun endCodecConfigFailure(context: Context, codec: VideoCodec) {
        val receiverId = client?.receiverId ?: _device.value?.id
        val message = context.getString(R.string.cast_mirror_codec_config_failed)
        scope.launch {
            if (receiverId != null) MirrorPreferences.demoteCodec(context, receiverId, codec)
            client?.let { mutex.withLock { it.sayGoodbye("no codec config") } }
            teardown()
            // After the teardown, which resets both of these - see startWatch for the same ordering.
            _failure.value = message
            _mirrorPhase.value = MirrorPhase.Failed
            CastService.stop(context)
        }
    }

    /**
     * Put the codec configuration on the control channel.
     *
     * Under [mutex] because the encoder loop and the RTCP loop both call this while [startWatch] is
     * reading the same socket, and two writers interleaving would corrupt a frame.
     */
    internal fun sendCodecConfig(activeClient: MirrorClient, csd: ByteArray) {
        scope.launch { mutex.withLock { activeClient.sendCodecConfig(csd) } }
    }

    internal fun stopEngine() {
        stopWatchingDisplay()
        desktopSource?.let {
            it.display?.release()
            it.display = null
        }
        desktopSource = null
        engine?.stop()
        engine = null
        activeCodec = null
        activeGeometry = null
        // The served session's other half. An open HTTPS port outliving the session it belonged to
        // would serve a token that is no longer anybody's.
        proxy?.stop()
        proxy = null
        // Cancelled here rather than only in `teardown`: `stopMirroring` deliberately leaves the
        // control channel and `watchJob` alive, so a ping loop left running would keep pinging on
        // behalf of a session that has ended - and hold that channel open for ever, which is more
        // than `ContentEnded`'s handback can safely be given. See [startKeepAlive].
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Tell an SDK client its session is over, once.
     *
     * Cleared as it fires, so the client hears exactly one reason: a teardown runs through several of
     * these paths and a client told twice would react to the second after it had already cleaned up.
     */
    internal fun endContentSession(reason: Int) {
        val notify = onContentSessionEnded ?: return
        onContentSessionEnded = null
        onPlaybackCommand = null
        onTvPlaybackState = null
        notify(reason)
    }

    /**
     * Drop everything.
     *
     * The socket is closed *before* [mutex] is taken, because closing is what unblocks a reader or
     * writer parked in it - and one of those may be the coroutine currently holding the lock. The state
     * reset then happens under the lock, so an in-flight exchange cannot publish the dead session's
     * state back over the cleared one.
     *
     * [keepFailure] is for the path that has just set a failure it wants the user to read; everything
     * else clears back to idle.
     */
    internal suspend fun teardown(keepFailure: Boolean = false) {
        watchJob?.cancel()
        endContentSession(CastContract.REASON_RECEIVER_GONE)
        stopEngine()
        socket?.close()
        mutex.withLock {
            watchJob = null
            socket = null
            client = null
            _isConnecting.value = false
            _mirrorPhase.value = MirrorPhase.Idle
            _degradation.value = MirrorDegradation()
            if (!keepFailure) {
                _device.value = null
                _sessionState.value = ClientState()
                _failure.value = null
            }
        }
    }
}
