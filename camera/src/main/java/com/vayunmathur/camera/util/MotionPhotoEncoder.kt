package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes a short list of bitmaps into an H.264 MP4 (used as the trailing clip of a Motion Photo).
 *
 * Uses MediaCodec in ByteBuffer/Image mode with COLOR_FormatYUV420Flexible: each ARGB bitmap is
 * converted to I420 into the codec's input [android.media.Image], respecting its plane strides, so
 * the path stays portable across encoders without needing EGL. Runs synchronously on the caller's
 * (background) thread. Rotation is carried by the muxer orientation hint rather than rotating pixels.
 */
object MotionPhotoEncoder {

    private const val TAG = "MotionPhotoEncoder"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val TIMEOUT_US = 10_000L
    /** Micros per second; YUV420 bytes per pixel (3/2); channel mask. */
    private const val MICROS_PER_SECOND = 1_000_000L
    private const val YUV420_NUMERATOR = 3
    private const val YUV420_DENOMINATOR = 2
    private const val CHANNEL_MASK = 0xFF

    /**
     * Encodes [frames] to [outputFile] at [fps]. All frames must share dimensions (the first
     * frame's are used). Returns true on success. The caller owns recycling the bitmaps.
     */
    fun encode(frames: List<Bitmap>, outputFile: File, rotationDegrees: Int, fps: Int = DEFAULT_FPS): Boolean {
        if (frames.isEmpty()) return false
        // Encoders require even dimensions.
        val width = frames[0].width and 1.inv()
        val height = frames[0].height and 1.inv()
        if (width < MIN_ENCODE_SIDE || height < MIN_ENCODE_SIDE) return false

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec = startEncoder(width, height, fps)
            muxer = MediaMuxer(outputFile.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotationDegrees)
            val pump = FramePump(codec, muxer, frames, width, height, fps)
            pump.run()
            muxerStarted = pump.muxerStarted
            return true
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Motion Photo MP4 encode failed", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
        }
    }

    /** Minimum (even) encode dimension; encoder timeout; default fps. */
    private const val MIN_ENCODE_SIDE = 2
    private const val DEFAULT_FPS = 15

    /** Creates, configures and starts the AVC encoder for [width]x[height]@[fps]. */
    private fun startEncoder(width: Int, height: Int, fps: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, width * height * 4)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        return MediaCodec.createEncoderByType(MIME).also {
            it.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            it.start()
        }
    }

    /** Pumps frames into the encoder and muxes output until end-of-stream. */
    private class FramePump(
        private val codec: MediaCodec,
        private val muxer: MediaMuxer,
        private val frames: List<Bitmap>,
        private val width: Int,
        private val height: Int,
        private val fps: Int
    ) {
        var muxerStarted = false
            private set
        private var trackIndex = -1
        private val bufferInfo = MediaCodec.BufferInfo()

        fun run() {
            var frameIndex = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        if (frameIndex < frames.size) {
                            queueFrame(inIndex, frameIndex)
                            frameIndex++
                        } else {
                            queueEndOfStream(inIndex, frameIndex)
                            inputDone = true
                        }
                    }
                }
                outputDone = drainOutput()
            }
        }

        private fun queueFrame(inIndex: Int, frameIndex: Int) {
            val ptsUs = frameIndex * MICROS_PER_SECOND / fps
            val image = codec.getInputImage(inIndex)
            if (image != null) {
                fillI420(image, frames[frameIndex], width, height)
            }
            val size = width * height * YUV420_NUMERATOR / YUV420_DENOMINATOR
            codec.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
        }

        private fun queueEndOfStream(inIndex: Int, frameIndex: Int) {
            codec.queueInputBuffer(
                inIndex, 0, 0, frameIndex * MICROS_PER_SECOND / fps,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM
            )
        }

        /** Drains one output buffer; returns true when end-of-stream is reached. */
        private fun drainOutput(): Boolean {
            val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                outIndex >= 0 -> {
                    writeSample(outIndex)
                    codec.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return true
                    }
                }
            }
            return false
        }

        private fun writeSample(outIndex: Int) {
            val encoded: ByteBuffer? = codec.getOutputBuffer(outIndex)
            if (isConfigFreeSample(encoded)) {
                encoded!!.position(bufferInfo.offset)
                encoded.limit(bufferInfo.offset + bufferInfo.size)
                muxer.writeSampleData(trackIndex, encoded, bufferInfo)
            }
        }

        /** True when the output buffer holds a non-empty, non-codec-config sample. */
        private fun isConfigFreeSample(encoded: ByteBuffer?): Boolean {
            return encoded != null && muxerStarted &&
                (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
                bufferInfo.size > 0
        }
    }

    /** BT.601 luma/chroma weights, rounding bias, and channel ranges. */
    private const val LUMA_R_WEIGHT = 66
    private const val LUMA_G_WEIGHT = 129
    private const val LUMA_B_WEIGHT = 25
    private const val CHROMA_RB_WEIGHT = 112
    private const val CHROMA_GU_WEIGHT = -74
    private const val CHROMA_GV_WEIGHT = -94
    private const val CHROMA_RU_WEIGHT = -38
    private const val CHROMA_BV_WEIGHT = -18
    private const val FIXED_POINT_ROUND = 128
    private const val FIXED_POINT_SHIFT = 8
    private const val LUMA_OFFSET = 16
    private const val CHROMA_OFFSET = 128
    private const val CHANNEL_MIN = 0
    private const val CHANNEL_MAX = 255
    private const val CHROMA_SUBSAMPLE = 2

    /** Converts an ARGB_8888 [bitmap] into the YUV [image]'s I420 planes (BT.601). */
    private fun fillI420(image: android.media.Image, bitmap: Bitmap, width: Int, height: Int) {
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixStride = uPlane.pixelStride
        val vPixStride = vPlane.pixelStride

        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = argb[y * width + x]
                val r = (c shr 16) and CHANNEL_MASK
                val g = (c shr 8) and CHANNEL_MASK
                val b = c and CHANNEL_MASK
                val yy = (
                    (LUMA_R_WEIGHT * r + LUMA_G_WEIGHT * g + LUMA_B_WEIGHT * b + FIXED_POINT_ROUND) shr
                        FIXED_POINT_SHIFT
                    ) + LUMA_OFFSET
                yBuf.put(y * yRowStride + x, yy.coerceIn(CHANNEL_MIN, CHANNEL_MAX).toByte())
                if (y and 1 == 0 && x and 1 == 0) {
                    writeChromaPixel(uBuf, vBuf, uRowStride, vRowStride, uPixStride, vPixStride, x, y, r, g, b)
                }
            }
        }
    }

    /** Writes the subsampled U/V pair for source pixel ([x], [y]). */
    private fun writeChromaPixel(
        uBuf: java.nio.ByteBuffer,
        vBuf: java.nio.ByteBuffer,
        uRowStride: Int,
        vRowStride: Int,
        uPixStride: Int,
        vPixStride: Int,
        x: Int,
        y: Int,
        r: Int,
        g: Int,
        b: Int
    ) {
        val u = (
            (CHROMA_RU_WEIGHT * r + CHROMA_GU_WEIGHT * g + CHROMA_RB_WEIGHT * b + FIXED_POINT_ROUND) shr
                FIXED_POINT_SHIFT
            ) + CHROMA_OFFSET
        val v = (
            (CHROMA_RB_WEIGHT * r + CHROMA_GV_WEIGHT * g + CHROMA_BV_WEIGHT * b + FIXED_POINT_ROUND) shr
                FIXED_POINT_SHIFT
            ) + CHROMA_OFFSET
        val cx = x / CHROMA_SUBSAMPLE
        val cy = y / CHROMA_SUBSAMPLE
        uBuf.put(cy * uRowStride + cx * uPixStride, u.coerceIn(CHANNEL_MIN, CHANNEL_MAX).toByte())
        vBuf.put(cy * vRowStride + cx * vPixStride, v.coerceIn(CHANNEL_MIN, CHANNEL_MAX).toByte())
    }
}
