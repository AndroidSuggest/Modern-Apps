package com.vayunmathur.cast.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.PING_INTERVAL_MS
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.service.CastService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "CastController"

/**
 * Become the control socket's single reader, now the exchange is finished.
 *
 * **Not started any earlier.** [MirrorClient.awaitEnd] reads the socket, and `configureStream`
 * has a request/response to do on it - a second reader would consume the `STREAM_READY` that
 * negotiation is waiting for. Until this starts, a dead TV surfaces as a failed `configureStream`,
 * which is just as prompt.
 *
 * [transportControls] is what gates the remote to app content. Screen mirroring has no transport
 * to control, so it is not merely that the overlay should not appear on the TV - there is nothing
 * a command could be applied to on this end either.
 */
internal fun CastController.startWatch(
    appContext: Context,
    activeClient: MirrorClient,
    device: CastDevice,
    /** Null for a served session, where nothing was encoded and there is no codec to demote. */
    codec: VideoCodec?,
    transportControls: Boolean = false,
    /**
     * Whether to keep the control channel warm, which every session needs.
     *
     * This once defaulted off for screen mirroring on the grounds that its traffic is RTP.
     * That was wrong: RTP is a separate [java.nio.channels.DatagramChannel], and nothing it
     * carries can reset a read deadline on the TCP control socket. A mirror or desktop
     * session gets no inbound control frames at all, so the read parked in
     * [MirrorClient.awaitEnd] always expired and killed the session after exactly
     * `ControlSocket.READ_TIMEOUT_MS`.
     */
    keepAlive: Boolean = false,
) {
    if (keepAlive) startKeepAlive(activeClient)
    watchJob = scope.launch {
        // Read late rather than captured: `ContentCastService` registers its callback only after
        // `startContentSession` returns, which is after this job has already started - the same
        // ordering `onContentSessionEnded` has.
        val dispatch: ((PlaybackCommand) -> Unit)? = if (transportControls) {
            { command -> onPlaybackCommand?.invoke(command) }
        } else {
            null
        }
        val states: ((PlaybackState) -> Unit)? = if (transportControls) {
            { state -> onTvPlaybackState?.invoke(state) }
        } else {
            null
        }
        val reason = activeClient.awaitEnd(dispatch, states)
        // **Another path may already own this teardown.** Closing the socket is what unblocks the
        // read above, and [endCodecConfigFailure] closes it deliberately - so a return from
        // `awaitEnd` is not proof that the TV ended the session. Whoever cancelled this job is
        // publishing its own failure, and a second teardown here would clear it.
        if (!isActive) return@launch
        Log.i(TAG, "the control channel closed")
        val receiverId = activeClient.receiverId ?: device.id
        // Read before the teardown clears it, so a failure already on screen survives a socket that
        // then closed without a reason of its own - otherwise the message a user has to read would
        // be replaced by a blank idle state.
        val standing = _failure.value
        // Cleared first: teardown cancels watchJob, and this coroutine *is* watchJob.
        watchJob = null
        teardown()
        // Published *after* the teardown, which resets the phase and clears the failure - the
        // order matters, and setting either first would only have it wiped.
        val message = if (reason == ByeReason.MISSING_CODEC_CONFIG && codec != null) {
            Log.w(TAG, "'${device.friendlyName}' never got ${codec.label}'s codec config")
            MirrorPreferences.demoteCodec(appContext, receiverId, codec)
            appContext.getString(R.string.cast_mirror_codec_config_failed)
        } else {
            standing
        }
        if (message != null) {
            _failure.value = message
            _mirrorPhase.value = MirrorPhase.Failed
        }
        CastService.stop(appContext)
    }
}

/**
 * Sends a keep-alive every [PING_INTERVAL_MS] for as long as a content session is live.
 *
 * A content session is the case where the control channel can go completely silent while
 * everything is working: the TV fetches the media over HTTPS and owns its own clock, so unless
 * one end volunteers playback snapshots there is nothing to say. Both ends give a read 60 seconds,
 * so silence used to end the session on whichever side read first - which is what tore a session
 * down a minute in while a track was still being encoded.
 *
 * **Stopped when the session is, even though `ContentEnded` hands the channel back rather than
 * closing it.** Keeping it running would hold that channel open indefinitely - and a second
 * content session on a channel whose [watchJob] is still parked in a blocking read has two readers
 * of one socket, so the reply to its `CONTENT_SESSION` goes to the wrong one. Letting the idle
 * channel lapse at the read timeout is what bounds that window to a minute rather than for ever.
 *
 * Under [CastController.mutex] like every other writer, because this is one more thread writing
 * one socket.
 */
internal fun CastController.startKeepAlive(activeClient: MirrorClient) {
    pingJob?.cancel()
    pingJob = scope.launch {
        while (isActive) {
            delay(PING_INTERVAL_MS)
            if (!isActive) return@launch
            mutex.withLock { activeClient.sendPing() }
        }
    }
}
