package com.vayunmathur.auto.platform

import android.content.Context
import android.media.MediaCodec
import android.util.Log
import com.vayunmathur.auto.protocol.GalConnection
import com.vayunmathur.auto.protocol.GalMessage
import com.vayunmathur.auto.protocol.gal.MediaAck
import com.vayunmathur.auto.protocol.gal.MediaSetupRequest
import com.vayunmathur.auto.protocol.gal.MediaSetupResponse
import com.vayunmathur.auto.protocol.gal.MediaStartRequest
import com.vayunmathur.auto.protocol.gal.Service
import com.vayunmathur.auto.protocol.gal.VideoConfiguration
import com.vayunmathur.auto.protocol.gal.VideoFocusIndication
import com.vayunmathur.auto.protocol.gal.VideoFocusMode
import com.vayunmathur.auto.protocol.gal.VideoFocusRequest
import com.vayunmathur.auto.protocol.gal.VideoResolution
import java.nio.ByteBuffer

/**
 * The video sink channel: brings the display and encoder up once the head unit agrees.
 *
 * The bring-up is a four-step conversation and the order matters — a head unit that gets
 * frames before it has acknowledged the start request drops them silently:
 *
 *  1. → `MediaSetupRequest`      claim the channel for video
 *  2. ← `MediaSetupResponse`     the head unit says which configuration it will accept
 *  3. → `VideoFocusRequest`      ask for the screen
 *  4. → `MediaStartRequest`      then, and only then, start streaming
 */
class VideoSinkChannel(
    private val context: Context,
    private val service: Service,
    private val connection: GalConnection,
    /**
     * Fire-and-forget observations for the phone status screen. Never blocks and never
     * gates streaming: the service forwards these to the session state without waiting.
     */
    private val onEvent: (VideoEvent) -> Unit = {},
) {
    private var display: CarDisplay? = null
    private var encoder: VideoEncoder? = null
    private var sessionId = -1
    private var configurationIndex = 0
    private var firstFrameSent = false

    /** Chosen from what the head unit advertised in discovery. */
    private val configuration: VideoConfiguration? =
        service.mediaSink.videoConfigsList.firstOrNull()

    val channelId: Int get() = service.id

    /** Step 1. Called once the channel is open. */
    fun requestSetup() {
        connection.send(
            channelId,
            GalMessage.Media.SETUP_REQUEST,
            MediaSetupRequest.newBuilder().setMediaType(MEDIA_TYPE_VIDEO).build().toByteArray(),
        )
    }

    fun onMessage(type: Int, payload: ByteArray) {
        when (type) {
            GalMessage.Media.CONFIG -> onSetupResponse(payload)
            GalMessage.Video.FOCUS_INDICATION -> onFocus(payload)
            GalMessage.Media.ACK -> onAck(payload)
            else -> Log.d(TAG, "unhandled video message 0x${type.toString(16)}")
        }
    }

    private fun onSetupResponse(payload: ByteArray) {
        val response = MediaSetupResponse.parseFrom(payload)
        configurationIndex = response.configurationIndicesList.firstOrNull() ?: 0
        Log.i(TAG, "head unit accepted video, config index $configurationIndex")

        // Step 3: ask for the screen before starting the stream.
        connection.send(
            channelId,
            GalMessage.Video.FOCUS_REQUEST,
            VideoFocusRequest.newBuilder()
                .setMode(VideoFocusMode.VIDEO_FOCUS_PROJECTED)
                .build()
                .toByteArray(),
        )
        startStreaming()
    }

    private fun onFocus(payload: ByteArray) {
        val mode = VideoFocusIndication.parseFrom(payload).mode
        Log.i(TAG, "video focus is now $mode")
        onEvent(VideoEvent.FocusChanged(mode.name))
        // Regaining the screen needs a fresh keyframe; the head unit has nothing to decode
        // against otherwise and would show garbage until the next scheduled one.
        if (mode == VideoFocusMode.VIDEO_FOCUS_PROJECTED) encoder?.requestKeyFrame()
    }

    private fun onAck(payload: ByteArray) {
        // Flow control: the head unit has consumed frames. Nothing to do while we send
        // unthrottled, but parsing it confirms the session id lines up.
        val ack = MediaAck.parseFrom(payload)
        if (ack.sessionId != sessionId) {
            Log.w(TAG, "ack for session ${ack.sessionId}, expected $sessionId")
            onEvent(VideoEvent.AckMismatch(expected = sessionId, actual = ack.sessionId))
        } else {
            onEvent(VideoEvent.AckReceived)
        }
    }

    private fun startStreaming() {
        val config = configuration
        val (width, height) = config?.codecResolution.dimensions()
        val frameRate = config?.frameRate?.takeIf { it > 0 } ?: DEFAULT_FRAME_RATE
        val density = config?.density?.takeIf { it > 0 } ?: DEFAULT_DENSITY

        Log.i(TAG, "starting video ${width}x$height @${frameRate} dpi $density")
        onEvent(VideoEvent.Setup(VideoInfo(width, height, frameRate, configurationIndex)))

        sessionId = 0
        firstFrameSent = false
        val encoder = VideoEncoder(width, height, frameRate, onFrame = ::sendFrame)
        this.encoder = encoder
        encoder.start()

        display = CarDisplay(context, width, height, density).also {
            it.show(checkNotNull(encoder.surface) { "encoder produced no input surface" })
        }

        // Step 4.
        connection.send(
            channelId,
            GalMessage.Media.START_REQUEST,
            MediaStartRequest.newBuilder()
                .setSessionId(sessionId)
                .setConfigurationIndex(configurationIndex)
                .build()
                .toByteArray(),
        )
    }

    private fun sendFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!firstFrameSent) {
            firstFrameSent = true
            onEvent(VideoEvent.FirstFrame)
        }
        onEvent(VideoEvent.FrameSent)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        // Timestamped form: an 8-byte microsecond timestamp then the access unit. Codec
        // config (SPS/PPS) rides the same way, which is what a head unit expects first.
        val payload = ByteBuffer.allocate(Long.SIZE_BYTES + bytes.size)
            .putLong(info.presentationTimeUs)
            .put(bytes)
            .array()
        connection.send(channelId, GalMessage.Media.DATA_WITH_TIMESTAMP, payload)
    }

    /** Pushes any encoded frames out. Called from the connection's loop. */
    fun pumpEncoder() {
        encoder?.drain()
    }

    fun release() {
        display?.release()
        display = null
        encoder?.stop()
        encoder = null
    }

    private fun VideoResolution?.dimensions(): Pair<Int, Int> = when (this) {
        VideoResolution.VIDEO_1280x720 -> 1280 to 720
        VideoResolution.VIDEO_1920x1080 -> 1920 to 1080
        VideoResolution.VIDEO_2560x1440 -> 2560 to 1440
        VideoResolution.VIDEO_3840x2160 -> 3840 to 2160
        VideoResolution.VIDEO_720x1280 -> 720 to 1280
        VideoResolution.VIDEO_1080x1920 -> 1080 to 1920
        VideoResolution.VIDEO_1440x2560 -> 1440 to 2560
        VideoResolution.VIDEO_2160x3840 -> 2160 to 3840
        // 800x480 is the DHU default and a sane fallback for anything unrecognised.
        else -> 800 to 480
    }

    private companion object {
        const val TAG = "MaAuto.Video"

        /** `MediaSetupRequest.media_type`: 1 audio, 3 video. */
        const val MEDIA_TYPE_VIDEO = 3
        const val DEFAULT_FRAME_RATE = 30
        const val DEFAULT_DENSITY = 160
    }
}
