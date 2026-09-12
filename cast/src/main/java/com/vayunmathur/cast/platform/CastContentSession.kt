package com.vayunmathur.cast.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.platform.mirror.MirrorDegradation
import com.vayunmathur.cast.platform.mirror.MirrorEngine
import com.vayunmathur.cast.platform.mirror.MirrorGeometry
import com.vayunmathur.cast.platform.mirror.MirrorSource
import com.vayunmathur.cast.protocol.CodecNegotiation
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.MediaResourceResolver
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.sdk.cast.CastContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private const val TAG = "CastController"

/**
 * Stream another app's content instead of the screen.
 *
 * The second entry point beside [CastController.startMirroring], and the only one `:sdk:cast`
 * reaches. Requires a TV already connected and paired - which is what `CastPickerActivity` is
 * for - because there is nothing an SDK caller could do about a pair code, and the picker is
 * also what establishes the [appLabel] the TV displays.
 *
 * Suspends until the stream is live or has failed, so the service can answer
 * `MSG_SESSION_READY` with real numbers rather than a promise. Screen mirroring and this remain
 * mutually exclusive; the single engine is what enforces it.
 */
suspend fun CastController.startContentSession(
    context: Context,
    width: Int,
    height: Int,
    wantAudio: Boolean,
    appLabel: String,
    wantVideo: Boolean = true,
    resources: MediaResourceResolver? = null,
): ContentSessionResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val activeClient = client
    val device = _device.value
    val phase = _sessionState.value.phase
    if (activeClient == null || device == null ||
        (phase != ClientPhase.Paired && phase != ClientPhase.Streaming)
    ) {
        Log.w(TAG, "asked for an app-content session with no paired TV")
        return@withContext ContentSessionResult.Failed(CastContract.REASON_NO_SESSION)
    }
    endContentSession(CastContract.REASON_PREEMPTED)
    stopEngine()
    _mirrorPhase.value = MirrorPhase.Negotiating
    _degradation.value = MirrorDegradation()
    _failure.value = null

    if (resources != null) {
        return@withContext startServedSession(
            context = appContext,
            device = device,
            activeClient = activeClient,
            resources = resources,
            wantVideo = wantVideo,
            appLabel = appLabel,
        )
    }

    val codec = when (val choice = chooseCodec(appContext, device, activeClient, width, height)) {
        is CodecOutcome.Refused -> {
            Log.w(TAG, "refusing an app-content session: ${choice.message}")
            _mirrorPhase.value = MirrorPhase.Failed
            _failure.value = choice.message
            return@withContext ContentSessionResult.Failed(CastContract.REASON_FAILED)
        }
        is CodecOutcome.Chosen -> choice
    }
    val geometry = MirrorGeometry.forContent(width, height, codec.selection)
    val frameRate = geometry.frameRate
    val outcome = mutex.withLock {
        activeClient.configureStream(
            width = geometry.width,
            height = geometry.height,
            frameRate = frameRate,
            bitRate = geometry.bitRate,
            videoCodec = codec.codec,
            audio = wantAudio,
            video = true,
            appLabel = appLabel,
        )
    }
    val ready = outcome as? HandshakeOutcome.Ready
    if (ready == null) {
        Log.w(TAG, "the TV would not agree an app-content stream: $outcome")
        _mirrorPhase.value = MirrorPhase.Failed
        _failure.value = appContext.getString(R.string.cast_mirror_negotiation_failed)
        return@withContext ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }

    val newEngine = MirrorEngine(
        context = appContext,
        source = MirrorSource.Content(appLabel = appLabel, wantAudio = wantAudio),
        receiverHost = device.host,
        negotiation = ready.negotiation,
        geometry = geometry,
        videoCodec = codec.codec,
        frameRate = frameRate,
        onDegraded = { _degradation.value = it },
        onStopped = { reason -> onEngineStopped(appContext, reason) },
        onCodecConfig = { csd -> sendCodecConfig(activeClient, csd) },
    ).apply { hexDump = verboseStreamLogging }
    engine = newEngine
    activeCodec = codec.codec
    // No surface means there is nowhere for the app to draw, which for an SDK session is the whole
    // point - unlike mirroring, it cannot usefully degrade to audio only.
    val surface = if (newEngine.start()) newEngine.contentSurface else null
    if (surface == null) {
        newEngine.stop()
        engine = null
        activeCodec = null
        _mirrorPhase.value = MirrorPhase.Failed
        return@withContext ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }
    _mirrorPhase.value = MirrorPhase.Mirroring
    _sessionState.update {
        it.copy(phase = ClientPhase.Streaming, negotiation = ready.negotiation)
    }
    startWatch(appContext, activeClient, device, codec.codec, transportControls = true, keepAlive = true)
    ContentSessionResult.Started(
        surface = surface,
        audioWriteEnd = newEngine.audioWriteEnd,
        width = geometry.width,
        height = geometry.height,
        // Rounded, because `CastContract.KEY_GRANTED_FRAME_RATE` is an `Int` in a Bundle read
        // by third-party SDK callers. The wire rate is a float so it can name one of the TV's
        // panel modes exactly; an app drawing into a surface only needs the whole number.
        frameRate = frameRate.roundToInt(),
        receiverName = activeClient.receiverName ?: device.friendlyName,
    )
}

/**
 * Start serving app content instead of encoding it.
 *
 * This is the whole architectural change on this end. The proxy binds an ephemeral HTTPS port,
 * the fingerprint of its throwaway certificate goes to the TV over the already-encrypted control
 * channel, and the TV fetches byte ranges from it. No encoder is created, no codec is negotiated
 * and no geometry is chosen: the file is already encoded, and the TV can decode it.
 *
 * The audio decoder *is* checked, before anything is started. A TV with no Opus decoder used to
 * accept an audio-only session and then play silence with nothing to explain it, and that is the
 * one refusal worth making early.
 */
internal suspend fun CastController.startServedSession(
    context: Context,
    device: CastDevice,
    activeClient: MirrorClient,
    resources: MediaResourceResolver,
    wantVideo: Boolean,
    appLabel: String,
): ContentSessionResult {
    val limits = activeClient.limits ?: DecoderLimits()
    if (!CodecNegotiation.canPlayAudio(limits)) {
        Log.w(TAG, "refusing a served session: '${device.friendlyName}' advertised no Opus decoder")
        _failure.value = context.getString(R.string.cast_mirror_tv_no_audio)
        _mirrorPhase.value = MirrorPhase.Failed
        return ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }

    // The address the kernel chose to reach this TV, not whichever interface happens to be first:
    // a phone can be on Wi-Fi, a VPN and a tethering bridge at once, and only one of those is
    // reachable back from the television.
    val host = socket?.localAddress
    if (host == null || host.hostAddress == null) {
        Log.w(TAG, "no local address on the control channel; nothing could be served")
        _mirrorPhase.value = MirrorPhase.Failed
        return ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }

    val token = MediaProxyServer.randomToken()
    val server = MediaProxyServer(token, resources)
    val endpoint = server.start(listOf(host))
    if (endpoint == null) {
        _mirrorPhase.value = MirrorPhase.Failed
        return ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }
    proxy = server

    val outcome = mutex.withLock {
        activeClient.openContentSession(
            host = host.hostAddress!!,
            port = endpoint.port,
            certificateFingerprint = endpoint.certificateFingerprint,
            token = token,
            video = wantVideo,
            appLabel = appLabel,
        )
    }
    if (outcome is ContentOutcome.Refused) {
        // Nothing is going to fetch from it, and an open port outlives the session that needed it.
        server.stop()
        proxy = null
        _failure.value = outcome.detail.ifBlank { context.getString(R.string.cast_mirror_tv_no_audio) }
        _mirrorPhase.value = MirrorPhase.Failed
        return ContentSessionResult.Failed(CastContract.REASON_FAILED)
    }

    _mirrorPhase.value = MirrorPhase.Mirroring
    _sessionState.update { it.copy(phase = ClientPhase.Streaming) }
    startWatch(context, activeClient, device, codec = null, transportControls = true, keepAlive = true)
    return ContentSessionResult.Serving(
        receiverName = activeClient.receiverName ?: device.friendlyName,
        hasVideo = wantVideo,
    )
}

/** Tell the TV to play a resource the app will be asked for. */
fun CastController.playMedia(media: PlayMedia) {
    val activeClient = client ?: return
    scope.launch { mutex.withLock { activeClient.playMedia(media) } }
}

/**
 * Tell the TV what the item it is playing actually is.
 *
 * Under [CastController.mutex] like [playMedia] and for the same reason. Silent with no session:
 * this is enrichment, and a snapshot that arrives a moment after a cast ended has nothing to
 * enrich.
 */
fun CastController.setNowPlaying(nowPlaying: NowPlaying) {
    val activeClient = client ?: return
    scope.launch { mutex.withLock { activeClient.sendNowPlaying(nowPlaying) } }
}

/**
 * Ask the TV to do something, in a served session.
 *
 * Under [CastController.mutex] like [playMedia] and for the same reason: one socket, several
 * writers. Silently does nothing with no session, because the caller is an app's transport and a
 * session that has just ended is the ordinary reason for a press to go nowhere.
 */
fun CastController.sendPlaybackCommand(command: PlaybackCommand) {
    val activeClient = client ?: return
    scope.launch { mutex.withLock { activeClient.sendPlaybackCommand(command) } }
}

/**
 * Put a playback snapshot on the control channel, so the TV can draw a seek bar.
 *
 * Under [CastController.mutex] for exactly the reason [sendCodecConfig] is, with one more writer
 * to serialise against than before: the encoder loop, the RTCP loop and now a twice-a-second
 * heartbeat all write to the one socket.
 *
 * Silently does nothing with no session, rather than reporting it. The caller is a poll loop that
 * cannot know precisely when the session ended, and there is nothing for it to do about the answer.
 */
fun CastController.reportPlaybackState(state: PlaybackState) {
    val activeClient = client ?: return
    scope.launch { mutex.withLock { activeClient.sendPlaybackState(state) } }
}
