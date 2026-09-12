package com.vayunmathur.cast.tv.platform

import android.util.Log
import android.view.Surface
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.DecodableFrame
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamKind
import com.vayunmathur.cast.protocol.StreamReady
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramSocket
import java.security.SecureRandom

private const val TAG = "ReceiverController"

/** How often the receiver's own throughput line goes out, paired with the sender's. */
private const val STATS_LOG_INTERVAL_MS = 1_000L

/** Feedback cadence. Well inside the target playout delay, so a NACK can still be played. */
private const val FEEDBACK_INTERVAL_MS = 50L

/**
 * The most often a decode failure may demand a key frame.
 *
 * Asking is not free: it discards every partial frame the assembler holds, so one request per refused
 * frame turns a burst of refusals into a stall it would otherwise have recovered from.
 */
private const val RESYNC_INTERVAL_MS = 500L

/**
 * How long the media loop waits for a codec configuration that has to arrive out of band.
 *
 * The wait itself is not new: `decoder == null` is already an unbounded state gated on the Surface,
 * and in practice this overlaps that wait entirely - the phone's encoder emits its configuration at
 * `start()`, long before `MirrorActivity` has produced anything to draw on. What is new is that it can
 * now fail, and it has to: a decoder that never starts is a black screen, and a black screen that
 * reports [ReceiverFailure.NoDecoder] would send the next hour of debugging to the wrong place.
 */
private const val CODEC_CONFIG_TIMEOUT_MS = 5_000L

/**
 * How much the receive socket may hold while the loop is busy decoding.
 *
 * An IDR at native resolution and ~24 Mbit/s is a few hundred packets arriving back to back once a
 * second, and the loop is single-threaded: it is inside `MediaCodec` when the burst lands. Nothing
 * set this before, so the default buffer overflowed and the loss looked like Wi-Fi. The kernel may
 * grant less than this, which is why it is read back and logged.
 */
private const val RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024

internal fun ReceiverController.startStreaming(
    channel: ControlChannel,
    keys: SessionKeys,
    config: StreamConfig,
    senderName: String,
) {
    frameWidth = config.width
    frameHeight = config.height
    // A new session's codec configuration has not arrived yet, and the previous session's would be
    // for a different encoder. This is the *only* place it is cleared - see the field's own note on
    // why a decoder release must not.
    videoCodecConfig = null
    // The socket is bound first, because the port it lands on is what STREAM_READY has to name -
    // filling in a port we hoped to get and then binding is how a sender ends up talking to nothing.
    val socket = try {
        DatagramSocket(0).apply {
            // Best effort: the kernel clamps to its own maximum and reports what it gave, so the
            // request is made and then the result is logged rather than assumed.
            runCatching { receiveBufferSize = RECEIVE_BUFFER_BYTES }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not bind a udp socket", e)
        channel.send(Bye(reason = "no udp socket"))
        _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.StreamEnded)) }
        return
    }
    val random = SecureRandom()
    val ready = StreamReady(
        udpPort = socket.localPort,
        audioSsrc = random.ssrc(StreamConstants.AUDIO_SSRC_MIN, StreamConstants.AUDIO_SSRC_MAX),
        videoSsrc = random.ssrc(StreamConstants.VIDEO_SSRC_MIN, StreamConstants.VIDEO_SSRC_MAX),
    )
    val negotiation = Negotiation.of(config, ready, keys)
    try {
        channel.send(ready)
    } catch (e: Exception) {
        // Nothing owns the socket yet, so a failure here would leak it for the life of the process.
        Log.w(TAG, "could not send STREAM_READY", e)
        runCatching { socket.close() }
        _state.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.StreamEnded)) }
        return
    }
    _state.update {
        it.copy(
            phase = ReceiverPhase.Mirroring(
                senderName = senderName,
                width = config.width,
                height = config.height,
                appLabel = config.appLabel,
                frameRate = config.frameRate,
            ),
        )
    }
    Log.i(
        TAG,
        "receiving ${config.videoCodec?.label ?: "audio only"} ${config.width}x${config.height} @ " +
            "${config.frameRate}fps (${config.bitRate / 1_000_000.0} Mbit/s) on udp " +
            "${socket.localPort}, " +
            "rcvbuf=${runCatching { socket.receiveBufferSize }.getOrDefault(0)}B, " +
            "playout=${StreamConstants.TARGET_DELAY_MS}ms" +
            if (config.videoCodec?.needsCodecConfig == true) "; waiting for its codec config" else "",
    )
    mediaJob = scope.launch { pump(socket, negotiation, config, channel) }
}

/**
 * The receive loop: datagrams in, frames out at their scheduled time, feedback out, and one log
 * line a second.
 *
 * **This coroutine owns the decoder and the audio track outright** - they are locals, not fields.
 * `MediaCodec` or `AudioTrack` released underneath a thread parked inside it is a native crash
 * rather than a catchable exception, so nothing outside this loop may touch them: [ReceiverController.detachSurface]
 * only clears the surface reference, and the loop does the release on its next pass. [ReceiverController.endMedia]
 * cancels *and joins* this job before anything else is dismantled.
 *
 * Decode stays **on this thread**, even though a playout queue now separates arrival from
 * presentation. A second thread would buy nothing - the queue is what decouples the two - and
 * would reintroduce exactly the native-crash surface the paragraph above exists to avoid.
 *
 * The decoder is started from inside the loop rather than before it because the surface arrives
 * asynchronously from `MirrorActivity`, and it is torn down and rebuilt the same way, which is what
 * makes a rotation mid-stream survivable.
 */
internal suspend fun ReceiverController.pump(
    socket: DatagramSocket,
    negotiation: Negotiation,
    config: StreamConfig,
    channel: ControlChannel,
) {
    var decoder: VideoDecoder? = null
    // The surface [decoder] was built against, so a *replacement* surface can be told from the
    // same one. Compared by identity: a new Surface for the same SurfaceView is a different
    // object, and that is exactly the case this exists to catch.
    var decoderSurface: Surface? = null
    // Hoisted into a local because the property comes from another module, where Kotlin will not
    // smart-cast it - and every use below is inside a branch that has already established there
    // is video.
    val videoCodec = config.videoCodec
    val player = if (config.audio) AudioPlayer().takeIf { it.start() } else null
    val playout = PlayoutQueue(
        targetDelayMs = StreamConstants.TARGET_DELAY_MS.toLong(),
        timebase = StreamConstants.VIDEO_TIMEBASE,
    )
    // Audio is held for the same interval as video, or the buffer below would put the sound
    // 150 ms ahead of the picture - which is worse than the judder it exists to remove.
    val audioPlayout = PlayoutQueue(
        targetDelayMs = StreamConstants.TARGET_DELAY_MS.toLong(),
        timebase = StreamConstants.AUDIO_TIMEBASE,
    )
    val media = MediaReceiver(
        socket = socket,
        negotiation = negotiation,
        // Queued, not decoded: the loop below decides when this frame is due.
        onVideo = { frame -> playout.add(frame, System.currentTimeMillis()) },
        // Not queued when there is no player to drain it into, or the queue would only grow.
        onAudio = { frame ->
            if (player != null) audioPlayout.add(frame, System.currentTimeMillis())
        },
        videoReady = { decoder != null },
    )
    var lastFeedback = 0L
    var lastStatsLog = 0L
    var lastResync = 0L
    // The gain currently on the track, so it is written only when the phone actually changes it.
    var appliedVolume = Float.NaN
    // When the wait for a codec configuration began, or 0 while nothing is waiting. Started from
    // the moment the decoder *could* otherwise have been built, so it does not run down while the
    // Activity is still producing a surface.
    var codecConfigWaitStartedAt = 0L
    try {
        while (currentCoroutineContext().isActive) {
            val activeSurface = surface
            // **Identity, not nullity.** A panel mode switch destroys the `SurfaceView`'s
            // surface and hands back a *different* one, frequently with no null in between -
            // `MirrorActivity.surfaceChanged` says as much, and re-attaches for that reason.
            // Testing only for null left the decoder drawing into a surface that had already
            // been torn down: the picture froze, the television asked for key frame after key
            // frame, and nothing recovered until a re-negotiation rebuilt this whole loop.
            // Switching the panel to match the stream made that the common case rather than a
            // rotation-only curiosity.
            val stale = decoder
            if (stale != null && activeSurface !== decoderSurface) {
                stale.release()
                decoder = null
                decoderSurface = null
                // Whatever is queued was scheduled for a decoder that no longer exists, and
                // its replacement can decode nothing until it has been given a key frame.
                playout.clear()
                media.requestKeyFrame(StreamKind.Video)
            }
            if (activeSurface == null) {
                // Nothing to draw on yet, or the surface has just gone. Either way the decoder
                // above is already released and there is nothing to build against.
            } else if (decoder == null && negotiation.hasVideo && videoCodec != null) {
                // Only consulted for a codec that needs it. An H.265 session finds its parameter
                // sets in the stream, and installing a `csd-0` it was not expecting would fail the
                // configure - so the field is read through the codec's own contract rather than
                // passed on because it happened to be set.
                val codecConfig = videoCodecConfig.takeIf { videoCodec.needsCodecConfig }
                if (videoCodec.needsCodecConfig && codecConfig == null) {
                    // **The one genuinely new wait.** This widens the entry condition of a state
                    // the pipeline already survives - `decoder == null`, which `MediaReceiver`'s
                    // pre-session video drop, `ReceiverSession.synchronised` and PLI already make
                    // recoverable - rather than adding a new one.
                    val waiting = System.currentTimeMillis()
                    if (codecConfigWaitStartedAt == 0L) {
                        codecConfigWaitStartedAt = waiting
                        Log.i(
                            TAG,
                            "surface ready; waiting for ${videoCodec.label}'s codec config",
                        )
                    } else if (waiting - codecConfigWaitStartedAt >= CODEC_CONFIG_TIMEOUT_MS) {
                        Log.w(
                            TAG,
                            "no ${videoCodec.label} codec config after " +
                                "${CODEC_CONFIG_TIMEOUT_MS}ms; nothing can be decoded",
                        )
                        // Named so the phone can remember it and start the next session on the
                        // other codec - a black screen it could not attribute would just repeat.
                        runCatching {
                            channel.send(Bye(reason = ByeReason.MISSING_CODEC_CONFIG))
                        }
                        _state.update {
                            it.copy(
                                phase = ReceiverPhase.Failed(
                                    ReceiverFailure.MissingCodecConfig,
                                ),
                            )
                        }
                        return
                    }
                } else {
                    val started = VideoDecoder(activeSurface, videoCodec)
                    if (started.start(config.width, config.height, codecConfig)) {
                        decoder = started
                        decoderSurface = activeSurface
                        Log.i(TAG, "decoder up; the picture starts at the next key frame")
                    } else {
                        _state.update {
                            it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.NoDecoder))
                        }
                        return
                    }
                }
            }
            media.pump()
            val now = System.currentTimeMillis()
            val active = decoder
            if (active != null) {
                while (true) {
                    val frame = playout.due(now) ?: break
                    val queued = active.decode(
                        frame.payload,
                        rtpToMicros(frame.rtpTimestamp),
                        frame.isKeyFrame,
                    )
                    if (!queued && now - lastResync >= RESYNC_INTERVAL_MS && !senderIdle()) {
                        // The session counted this frame as delivered, so its checkpoint has
                        // moved past a frame the decoder never got: every delta frame behind it
                        // now references something that does not exist. Only a key frame
                        // recovers, and nothing else in the pipeline can know to ask for one.
                        //
                        // **Rate-limited, because asking costs the assembler's partial frames.**
                        // A decoder whose input buffers are briefly full refuses several frames
                        // in a row - measured at 55 in the first seconds of a session - and one
                        // resync per refusal wipes the very packets retransmission was about to
                        // repair, turning a hiccup into a stall.
                        lastResync = now
                        media.requestKeyFrame(StreamKind.Video)
                    }
                }
                active.render()
            }
            if (player != null) {
                if (castVolume != appliedVolume) {
                    appliedVolume = castVolume
                    player.setVolume(appliedVolume)
                }
                while (true) {
                    val frame = audioPlayout.due(now) ?: break
                    player.play(frame.payload, audioRtpToMicros(frame.rtpTimestamp))
                }
            }
            if (now - lastFeedback >= FEEDBACK_INTERVAL_MS) {
                lastFeedback = now
                media.sendFeedback(senderIdle = senderIdle())
            }
            if (now - lastStatsLog >= STATS_LOG_INTERVAL_MS) {
                lastStatsLog = now
                // Paired with MirrorEngine's line on the phone. Two logs reporting at the same
                // cadence from both ends is what five rounds of hardware debugging never had.
                Log.i(
                    TAG,
                    media.throughputSummary() +
                        " playout=${playout.depth}f/rebased=${playout.rebases}" +
                        " dropped=${active?.framesDropped ?: 0}" +
                        audioHealth(player),
                )
            }
        }
    } finally {
        decoder?.release()
        player?.release()
        media.close()
    }
}

private fun SecureRandom.ssrc(min: Int, max: Int): Long = (min + nextInt(max - min + 1)).toLong()

/**
 * Encoded frames waiting for their turn on screen, or in the speakers.
 *
 * The receiver advertised a target playout delay in every RTCP report and implemented none of it:
 * each frame was rendered the moment it arrived, so Wi-Fi jitter mapped one-to-one onto visible
 * judder and the NACK machinery had no window to repair into. This is that window.
 *
 * Scheduling is by **RTP-timestamp delta from the first frame**, which is what makes playout follow
 * capture spacing rather than arrival spacing. Encoded frames are kilobytes, so the ~5 frames a
 * 150 ms buffer holds at 30 fps cost nothing to keep.
 *
 * [timebase] is the stream's own, because audio is timestamped in samples and video at 90 kHz - both
 * halves have to be held for the same *wall-clock* interval or the sound leads the picture.
 *
 * Not thread-safe, and does not need to be: it is filled from `MediaReceiver.pump`'s callback and
 * drained by the loop that calls it, both on the one media coroutine.
 */
internal class PlayoutQueue(private val targetDelayMs: Long, private val timebase: Int) {

    private val frames = ArrayDeque<DecodableFrame>()

    /** The sender's RTP clock mapped onto ours, established by the first frame after each reset. */
    private var baseRtp = -1L
    private var baseWallMs = 0L

    /** Times the mapping had to be re-established, each of which resets the buffer's depth. */
    var rebases: Long = 0
        private set

    val depth: Int get() = frames.size

    fun add(frame: DecodableFrame, nowMs: Long) {
        if (baseRtp < 0) rebase(frame.rtpTimestamp, nowMs)
        frames.addLast(frame)
        // **Corrected in both directions.** Each frame should land [targetDelayMs] after it arrives;
        // how far from that it actually lands is how far the mapping has drifted. Correcting only
        // lateness lets the buffer deepen without limit - measured at 7-8 frames, some 600 ms of
        // latency, on a mapping established during a slow patch and never revisited. Re-basing on the
        // *newest* frame keeps the deque ordered for free: playout time is monotonic in the RTP
        // timestamp, so pulling the schedule in makes older frames due sooner, never out of order.
        val drift = scheduleFor(frame.rtpTimestamp) - (nowMs + targetDelayMs)
        if (drift > targetDelayMs || drift < -targetDelayMs) {
            rebases++
            rebase(frame.rtpTimestamp, nowMs)
        }
    }

    /** The next frame whose time has come, or null while the buffer is still filling. */
    fun due(nowMs: Long): DecodableFrame? {
        val head = frames.firstOrNull() ?: return null
        if (scheduleFor(head.rtpTimestamp) > nowMs) return null
        return frames.removeFirst()
    }

    /** Dropped rather than played out: the decoder these were scheduled for has gone. */
    fun clear() {
        frames.clear()
        baseRtp = -1
    }

    private fun rebase(rtpTimestamp: Long, nowMs: Long) {
        baseRtp = rtpTimestamp
        baseWallMs = nowMs + targetDelayMs
    }

    private fun scheduleFor(rtpTimestamp: Long): Long =
        baseWallMs + (rtpTimestamp - baseRtp) * 1_000L / timebase
}
