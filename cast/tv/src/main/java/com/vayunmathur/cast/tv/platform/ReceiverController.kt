package com.vayunmathur.cast.tv.platform

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PairResult
import com.vayunmathur.cast.protocol.PairingGate
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodecConfig
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.ServerSocket
import java.security.SecureRandom

private const val TAG = "ReceiverController"

/** One press of the remote's volume key. Sixteen steps end to end, which is what a TV usually offers. */
private const val VOLUME_STEP = 1f / 16f

/**
 * The single owner of the live receiving session.
 *
 * An object rather than ViewModel-owned state, for exactly the reason `:cast`'s `CastController` is:
 * `ReceiverService` keeps the sockets alive while nothing is on screen, and both `MainActivity` and
 * `MirrorActivity` observe the same session while neither reliably outlives the other.
 *
 * **One phone at a time, and a second connection is refused rather than swapped in.** A TV has one
 * screen, and letting any device on the LAN displace a running session would be a denial of service
 * with no authentication needed to mount it. A phone that dies is cleared by the control socket's read
 * timeout rather than by making that trade.
 */
object ReceiverController {

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    internal val _state = MutableStateFlow(ReceiverUiState())
    val state: StateFlow<ReceiverUiState> = _state.asStateFlow()

    internal var advertiser: ReceiverAdvertiser? = null
    internal var serverSocket: ServerSocket? = null
    internal var acceptJob: Job? = null
    internal var sessionJob: Job? = null
    internal var mediaJob: Job? = null

    /**
     * The surface `MirrorActivity` owns, when it has one.
     *
     * Video is **dropped rather than buffered** until it exists, and dropped before it reaches the
     * receiver session - see `MediaReceiver.pump`. Volatile because the media loop reads it while the
     * Activity's callbacks write it.
     */
    @Volatile
    internal var surface: Surface? = null

    /**
     * The player for a served content session, or null when there is not one.
     *
     * Volatile because the Activity's surface callbacks read it while the session coroutine writes
     * it. Its own field rather than a branch inside the media loop: a served session has no RTP, no
     * decoder and no playout queue, so there is nothing for that loop to do.
     */
    @Volatile
    internal var contentPlayer: ContentPlayer? = null

    /** The frame size the phone said it would send, so the Activity can letterbox to it. */
    @Volatile
    var frameWidth: Int = 0
        internal set

    @Volatile
    var frameHeight: Int = 0
        internal set

    /**
     * The video codec configuration the phone sent, for a codec that cannot carry it in-band.
     *
     * Volatile because the control coroutine writes it while the media loop reads it, and cleared only
     * when a new session begins. **Deliberately not cleared when the decoder is released**: a surface
     * loss - a rotation - releases the decoder and rebuilds it, and a field cleared on the way past
     * would leave the replacement waiting for bytes that are only emitted once per encoder, wedging the
     * session for good.
     */
    @Volatile
    internal var videoCodecConfig: ByteArray? = null

    /**
     * The output gain the phone last asked for, 0..1.
     *
     * A field rather than a call into [AudioPlayer], because that object belongs to the media loop
     * outright - see `pump`. The loop picks this up on its next pass, which is at most one socket
     * timeout away and imperceptible for a volume change.
     */
    @Volatile
    internal var castVolume: Float = 1f

    internal val pairingGate = PairingGate()

    /**
     * The live control channel, for sending back up it.
     *
     * The handshake reads and writes this channel from one coroutine, which is why it was never a
     * field before. A remote press is the first thing that originates on *this* end at an arbitrary
     * moment, so it needs a way in - [ControlChannel.send] is synchronised for the same reason.
     * Volatile because the UI thread reads it while the session coroutine writes it.
     */
    @Volatile
    internal var channel: ControlChannel? = null

    /**
     * Do what the remote asked, wherever the player for it happens to be.
     *
     * **A served session applies the press here**, because that is where the player is: the phone
     * paused its own before the cast started, so a round trip to it would move nothing and take a
     * LAN's latency doing it. The phone finds out from the next snapshot, half a second later at
     * worst. Screen mirroring is the other way round - the phone's player is the one making the
     * sound - so the command goes to it, unchanged.
     *
     * [PlaybackAction.Next] and [PlaybackAction.Previous] always go to the phone, whichever kind of
     * session it is: the queue is a list only the phone can see. Dropped silently with no session,
     * which is the honest answer - the remote was pressed at a screen with nothing playing on it.
     */
    fun send(command: PlaybackCommand) {
        val served = contentPlayer
        // On the main thread already, which is where ExoPlayer must be touched: every caller is
        // `MirrorActivity`'s key handling.
        if (served != null && served.apply(command)) {
            // A press has to be visible before the reporter's next tick, or the overlay lags the
            // sound coming out of this very box.
            onPlaybackState(served.snapshot())
            return
        }
        val live = channel ?: return
        scope.launch {
            runCatching { live.send(command) }
                .onFailure { Log.w(TAG, "could not send ${command.action}", it) }
        }
    }

    /** Skip forward or back by whichever interval the end holding the player uses. */
    fun skip(forward: Boolean) {
        send(PlaybackCommand(if (forward) PlaybackAction.SkipForward else PlaybackAction.SkipBack))
    }

    /**
     * Nudge the shared volume level up or down by one step.
     *
     * The level is read from the last snapshot rather than held separately, so there is one answer to
     * "how loud is it" however the session is arranged. [send] then puts the new level wherever the
     * player is: on this box's own player for a served session, or on the phone for screen mirroring,
     * where the phone owns the level, stores it, and keeps it for local playback afterwards.
     *
     * Returns false with no session, so the key falls through to the box's own volume control.
     */
    fun nudgeVolume(up: Boolean): Boolean {
        val current = _state.value.playback?.state?.volume ?: return false
        val level = (current + if (up) VOLUME_STEP else -VOLUME_STEP).coerceIn(0f, 1f)
        send(PlaybackCommand(PlaybackAction.SetVolume, value = level.toDouble()))
        return true
    }

    fun start(context: Context) {
        if (acceptJob != null) return
        val appContext = context.applicationContext
        acceptJob = scope.launch { listen(appContext) }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        // The session coroutine owns its own field; from outside, cancelling it is what ends it, and
        // its finally then stops the media loop through the same path a normal end takes.
        sessionJob?.cancel()
        endMedia()
        advertiser?.unadvertise()
        advertiser = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        forgetPlayback()
        _state.update { it.copy(phase = ReceiverPhase.Starting) }
    }

    /**
     * Drop everything the last session said about playback.
     *
     * Both halves together, always: clearing [ReceiverUiState.playback] is what takes the overlay away,
     * and resetting [castVolume] is what stops the *next* session inheriting this one's gain. Missing
     * the second is a quiet, nasty failure - a screen-mirroring session that follows a quiet cast would
     * play at that gain for ever, with no snapshot to change it and `nudgeVolume` declining to try.
     *
     * The metadata goes with them, and has to: it is not merely stale but wrong, and a cover left on
     * screen over the next session's audio would look deliberate.
     */
    internal fun forgetPlayback() {
        castVolume = 1f
        _state.update {
            it.copy(
                playback = null,
                nowPlaying = null,
                playingResourceId = "",
                artwork = null,
            )
        }
    }

    /** Called by `MirrorActivity` once its `SurfaceView` has a surface to draw into. */
    fun attachSurface(newSurface: Surface) {
        surface = newSurface
        // A served session hands the surface straight to its player. Nothing is being decoded here,
        // so there is no media loop to notice one appearing.
        contentPlayer?.setSurface(newSurface)
    }

    /**
     * The surface is going away - rotation, or the Activity finishing.
     *
     * Only the reference is cleared here. The media loop owns the decoder and releases it when it
     * next sees that there is nowhere to draw, so nothing is ever released underneath a `MediaCodec`
     * call in flight.
     */
    fun detachSurface() {
        // Taken off the player first: handing a released surface to ExoPlayer is what breaks the
        // *next* session rather than this one.
        contentPlayer?.setSurface(null)
        surface = null
    }

    // ---- accepting ----

    private suspend fun listen(context: Context) {
        val store = PairingStore(context)
        val identity = PqcIdentity.loadOrCreate(store, prefix = "castTv")
        val deviceId = store.deviceId()
        val name = store.friendlyName(fallback = defaultName())
        // Audio is advertised alongside video for the first time. It never needed negotiating while
        // every session had a picture - a TV with no Opus decoder simply played silence - but an
        // audio-only session stands or falls on it, and the phone can only refuse by name if it was
        // told.
        val limits = VideoDecoder.limits().copy(audioCodecs = AudioPlayer.limits())

        val server = try {
            ServerSocket(0)
        } catch (e: Exception) {
            Log.e(TAG, "could not bind a control socket", e)
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        serverSocket = server

        val nsd = ReceiverAdvertiser(context)
        advertiser = nsd
        nsd.advertise(friendlyName = name, deviceId = deviceId, port = server.localPort, limits = limits)
        _state.value = ReceiverUiState(
            phase = ReceiverPhase.Advertising,
            deviceName = name,
            localNetworkBlocked = nsd.localNetworkBlocked,
        )
        Log.i(TAG, "receiving as '$name' ($deviceId) on control port ${server.localPort}")

        while (scope.isActive) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (scope.isActive) Log.w(TAG, "accept failed", e)
                return
            }
            if (sessionJob?.isActive == true) {
                // One screen, one session. Refusing is what stops anyone on the LAN from displacing a
                // running mirror without authenticating at all.
                Log.i(TAG, "refusing ${socket.inetAddress} - already receiving")
                runCatching { socket.close() }
                continue
            }
            val channel = ControlChannel(socket)
            // A failure from the previous session has been on screen long enough; the phone connecting
            // now is what the user cares about. The previous session's playback goes with it, or the
            // next phone would inherit a seek bar describing something that is no longer playing.
            forgetPlayback()
            _state.update {
                if (it.phase is ReceiverPhase.Failed) {
                    it.copy(phase = ReceiverPhase.Advertising)
                } else {
                    it
                }
            }
            this@ReceiverController.channel = channel
            sessionJob = scope.launch {
                try {
                    runSession(context, channel, store, identity, deviceId, name, limits)
                } catch (e: Exception) {
                    Log.w(TAG, "session ended", e)
                } finally {
                    channel.close()
                    this@ReceiverController.channel = null
                    endMedia()
                    forgetPlayback()
                    // A failure the user has to read is *not* overwritten with "ready" - it stays until
                    // the next phone tries, which is when it stops being the useful thing to show.
                    if (scope.isActive) {
                        _state.update {
                            if (it.phase is ReceiverPhase.Failed) {
                                it
                            } else {
                                it.copy(
                                    phase = ReceiverPhase.Advertising,
                                    localNetworkBlocked = nsd.localNetworkBlocked,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- one session ----

    private suspend fun runSession(
        context: Context,
        channel: ControlChannel,
        store: PairingStore,
        identity: PqcIdentity,
        deviceId: String,
        deviceName: String,
        limits: DecoderLimits,
    ) {
        val transcript = Transcript()

        val hello = channel.receive() ?: return
        val greeting = hello.message as? Hello ?: return
        if (greeting.version != PROTOCOL_VERSION) {
            Log.w(TAG, "refusing protocol version ${greeting.version}, we speak $PROTOCOL_VERSION")
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        transcript.add(hello.body)
        Log.i(TAG, "'${greeting.senderName}' connected from ${channel.remoteAddress}")

        transcript.add(
            channel.send(
                TvIdentity(
                    receiverName = deviceName,
                    receiverId = deviceId,
                    publicBundle = ProtocolBase64.encode(identity.publicBundle),
                    limits = limits,
                    displayModes = PanelModes.of(context),
                ),
            ),
        )

        val sealed = channel.receive() ?: return
        val sealedSecret = sealed.message as? SealedSecret ?: return
        transcript.add(sealed.body)
        val secret = ProtocolBase64.decode(sealedSecret.sealed)
            ?.let { SecretSealing.open(identity, it) }
        if (secret == null) {
            // The phone sealed to a bundle that is not ours - a stale one from before a reset, or an
            // attacker's. Either way there is no shared secret and nothing to do but close.
            Log.w(TAG, "could not open the sealed secret; the phone has a stale identity for us")
            _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
            return
        }
        val keys = SessionKeys.of(secret)
        // From here on the control channel is AES-256-GCM. Both ends install the cipher at exactly
        // this point, which is what keeps them in step without a per-frame flag an attacker could
        // clear.
        channel.codec.useSessionKey(keys.control)
        val transcriptValue = transcript.value()

        if (!authenticate(channel, store, keys, transcriptValue, greeting)) return
        _state.update { it.copy(phase = ReceiverPhase.Connected(greeting.senderName)) }

        // **A loop rather than one configuration, because a content session gives the channel back.**
        // Ending a cast is far more common than disconnecting a television, and the pairing is exactly
        // what the user just spent time on - so `CONTENT_ENDED` returns here and the next cast starts
        // without re-picking the TV. Screen mirroring never comes back: its session lives in the UDP
        // loop until the socket dies.
        while (true) {
            val configured = channel.receive() ?: return
            // The fork between the two kinds of session. Screen mirroring has no file behind it and
            // keeps the RTP path; anything with a file is served over HTTPS and decoded here, which is
            // why seeking becomes an offset and a pause is nobody's business but this end's.
            when (val first = configured.message) {
                is StreamConfig -> {
                    startStreaming(channel, keys, first, greeting.senderName)
                    mirrorSession(channel, keys, first, greeting.senderName)
                    return
                }
                is ContentSession -> {
                    if (!serveContent(context, channel, first, greeting.senderName)) return
                    _state.update { it.copy(phase = ReceiverPhase.Connected(greeting.senderName)) }
                    forgetPlayback()
                }
                is Bye -> {
                    Log.i(TAG, "'${greeting.senderName}' said goodbye")
                    return
                }
                // Echoed rather than treated as a surprise, so a keep-alive cannot end the very channel
                // it exists to preserve. A snapshot, a command or a metadata update still in flight
                // from the session that just ended is dropped for the same reason: there is no longer
                // a player for the first two, and nothing on screen for the third. Metadata is the
                // likeliest of the three to land here, because the phone prepares it off the path that
                // ends the session - a cover read and re-compressed while the user closed the cast.
                is Ping -> runCatching { channel.send(Ping) }
                is PlaybackState, is PlaybackCommand, is NowPlaying -> Unit
                else -> return
            }
        }
    }

    /**
     * The rest of a screen-mirroring session: everything that arrives while the UDP loop runs.
     *
     * The session now lives in that loop; this coroutine stays here so a BYE or a dropped socket tears
     * everything down through the same path. It is also where the codec configuration arrives, which
     * for AV1 is what the media loop is waiting on before it can start a decoder at all - it is
     * repeated on every key-frame request, so this handles it more than once. And it is where playback
     * snapshots arrive, twice a second, for as long as an app is casting encoded content - as does the
     * metadata that says what that content *is*, which an encoded video needs quite as much as a
     * served track did.
     */
    private suspend fun mirrorSession(
        channel: ControlChannel,
        keys: SessionKeys,
        config: StreamConfig,
        senderName: String,
    ) {
        // The session's *current* configuration, which is no longer fixed for its lifetime: the
        // phone re-negotiates when the user picks a different resolution or refresh rate.
        var current = config
        while (true) {
            val next = channel.receive() ?: break
            when (val message = next.message) {
                is Bye -> {
                    Log.i(TAG, "'$senderName' said goodbye")
                    return
                }
                // **A second STREAM_CONFIG re-arms the session rather than being ignored.**
                // Silently dropping it here - which `else` used to do - left the phone waiting
                // for a STREAM_READY that never came while it had already stopped sending, so a
                // resolution change showed up as the picture freezing on its last frame. The old
                // decoder and socket go first: they are sized and bound for the geometry being
                // replaced.
                is StreamConfig -> {
                    Log.i(
                        TAG,
                        "'$senderName' is switching to ${message.width}x${message.height} " +
                            "@ ${message.frameRate}fps",
                    )
                    endMedia()
                    startStreaming(channel, keys, message, senderName)
                    current = message
                }
                is VideoCodecConfig -> onVideoCodecConfig(message, current)
                is PlaybackState -> onPlaybackState(message)
                // What the encoded picture is of. Names no resource - nothing is being served here -
                // so the gate in `ReceiverUiState.nowPlayingForCurrentItem` passes it straight
                // through: this end holds no player, so the phone's latest word is the only word.
                is NowPlaying -> _state.update { it.copy(nowPlaying = message) }
                // As in the content-session loop below: echoed so the phone's read deadline moves
                // too. Screen mirroring never sends one, so this only fires for app content.
                is Ping -> runCatching { channel.send(Ping) }
                else -> Unit
            }
        }
    }

    /**
     * Take a playback snapshot, and stamp it with the moment it became true.
     *
     * The timestamp is taken here rather than in the UI because this is the closest thing to "when it
     * was so" that exists - anything later would fold recomposition delay into the seek bar's anchor.
     *
     * Fed from the phone for screen mirroring and from this box's own player for a served session,
     * which is the whole of the direction change: the overlay is drawn from whichever end owns the
     * clock, through one field either way.
     *
     * Not logged. Two of these a second would drown the once-a-second throughput line that everything
     * else about a session is diagnosed from.
     */
    internal fun onPlaybackState(message: PlaybackState) {
        castVolume = message.volume
        _state.update {
            it.copy(
                playback = PlaybackSnapshot(
                    state = message,
                    receivedAtMs = System.currentTimeMillis(),
                ),
            )
        }
    }

    /**
     * Take the codec configuration the phone sent, and say enough about it to diagnose it from a log.
     *
     * **The length and the leading bytes are the whole point of this log line.** Whether AV1's
     * `BUFFER_FLAG_CODEC_CONFIG` is an `av1C` configuration record or a bare sequence-header OBU is a
     * device fact, not an API one, and it decides whether `csd-0` was the right place to put it - so it
     * has to be readable from the one hardware session where it can be answered. `0x81` leads an `av1C`
     * record (marker 1, version 1); `0x0a` leads a sequence header OBU with a size field.
     */
    internal fun onVideoCodecConfig(message: VideoCodecConfig, config: StreamConfig) {
        val codec = config.videoCodec
        if (codec == null) {
            // An audio-only session has no decoder to configure. Ignored rather than treated as a
            // fault: it means the phone sent one anyway, which is harmless and says nothing useful.
            Log.w(TAG, "a codec config arrived for a session with no video")
            return
        }
        val csd = ProtocolBase64.decode(message.csd)
        if (csd == null || csd.isEmpty()) {
            Log.w(TAG, "the phone sent an unreadable codec config")
            return
        }
        val shape = when (csd.first()) {
            0x81.toByte() -> "an av1C configuration record"
            0x0a.toByte() -> "a sequence header OBU"
            else -> "an unrecognised form"
        }
        Log.i(
            TAG,
            "codec config for ${codec.label}: ${csd.size} bytes, $shape, " +
                "[${csd.take(16).joinToString(" ") { "%02x".format(it) }}]; " +
                "the stream is ${config.width}x${config.height}",
        )
        videoCodecConfig = csd
    }

    /**
     * Whether the phone has told us it is deliberately not producing frames.
     *
     * The one thing that distinguishes "paused" from "broken", and the pipeline had no way to know it
     * until the phone started reporting playback: a paused sender and a dead link look identical from
     * here - no frames arriving - and every recovery mechanism is built for the second. False for screen
     * mirroring, which never reports playback and can never be paused, so its behaviour is unchanged.
     */
    internal fun senderIdle(): Boolean =
        _state.value.playback?.state?.let { !it.playing && !it.buffering } == true

    /**
     * Stop the media loop.
     *
     * The media job is **joined**, not just cancelled: it holds a `MediaCodec` and an `AudioTrack`, and
     * a second session starting while the first is still inside a call on either would release one out
     * from under the other. `runBlocking` is acceptable because the loop only ever parks for the
     * socket's 10 ms timeout.
     *
     * Deliberately does **not** touch `sessionJob`. That field is written only by [listen], which is
     * the one coroutine that decides whether a connection is accepted; a session's own `finally`
     * clearing it could otherwise land *after* [listen] had stored the next session's job, leaving the
     * field null and letting a third phone in alongside a running one.
     */
    internal fun endMedia() {
        val media = mediaJob
        mediaJob = null
        if (media != null) {
            runCatching {
                runBlocking {
                    media.cancel()
                    media.join()
                }
            }
        }
        frameWidth = 0
        frameHeight = 0
    }

    /** Video RTP timestamps are 90 kHz; `MediaCodec` wants microseconds. */
    internal fun rtpToMicros(rtpTimestamp: Long): Long =
        rtpTimestamp * 1_000_000L / StreamConstants.VIDEO_TIMEBASE

    /**
     * What the audio path is doing, in the one line that reports everything else.
     *
     * A rebuild is worth saying out loud even though it recovered: silence that came back is a fault
     * that will happen again, and a session that reports nothing looks identical to one that never had
     * a problem.
     */
    internal fun audioHealth(player: AudioPlayer?): String = when {
        player == null -> ""
        player.failed -> " audio=failed after ${player.restarts} rebuilds"
        player.restarts > 0 -> " audio=recovered/${player.restarts} rebuilds"
        else -> ""
    }

    /** Audio is timestamped in samples, so its divisor is the sample rate. */
    internal fun audioRtpToMicros(rtpTimestamp: Long): Long =
        rtpTimestamp * 1_000_000L / StreamConstants.AUDIO_TIMEBASE

    private fun SecureRandom.ssrc(min: Int, max: Int): Long = (min + nextInt(max - min + 1)).toLong()

    /** What the TV calls itself, before the user renames it. */
    private fun defaultName(): String =
        listOf(Build.MODEL, Build.DEVICE)
            .firstOrNull { !it.isNullOrBlank() }
            ?: "MA Cast TV"
}
