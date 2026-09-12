package com.vayunmathur.auto.platform

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 encoder feeding the video sink channel.
 *
 * The encoder's input [surface] is what the car display renders into, so frames never touch
 * the CPU: the virtual display composites straight into the encoder.
 *
 * Head units want Baseline profile H.264 ([gal codec][com.vayunmathur.auto.protocol.gal.MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP]),
 * and they want the codec-specific data (SPS/PPS) ahead of the first frame. MediaCodec
 * delivers that either as a `BUFFER_FLAG_CODEC_CONFIG` buffer or via the output format, and
 * we forward it as an ordinary frame because that is how GAL carries it.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitRate: Int = DEFAULT_BIT_RATE,
    private val onFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit,
) {
    private var codec: MediaCodec? = null

    /** The surface to render the car UI into. Valid between [start] and [stop]. */
    var surface: Surface? = null
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            .apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                // A keyframe every second: a head unit that joins late, or drops a frame,
                // recovers within a second rather than showing garbage until the next one.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(
                    MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                )
            }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = createInputSurface()
            start()
        }
    }

    /**
     * Drains whatever the encoder has ready.
     *
     * @return false once the encoder has signalled end of stream.
     */
    fun drain(timeoutUs: Long = 0): Boolean {
        val codec = codec ?: return false
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return true
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                index < 0 -> Unit
                else -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        onFrame(buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                }
            }
        }
    }

    /** Asks for a keyframe, which a head unit needs when it (re)gains video focus. */
    fun requestKeyFrame() {
        codec?.setParameters(
            android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
        )
    }

    fun stop() {
        surface?.release()
        surface = null
        codec?.run {
            runCatching { stop() }
            release()
        }
        codec = null
    }

    private companion object {
        /** Enough for 800x480 and 720p without obvious blocking. */
        const val DEFAULT_BIT_RATE = 4_000_000
    }
}
