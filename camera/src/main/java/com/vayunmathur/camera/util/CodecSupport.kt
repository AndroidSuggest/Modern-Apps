package com.vayunmathur.camera.util

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Reports whether the device can encode with modern video codecs. AV1 is only offered when a
 * true hardware encoder is present, because software AV1 encoding is too slow for realtime capture.
 */
object CodecSupport {

    // NOTE: These must NOT be lazy on main thread. MediaCodecList.REGULAR_CODECS
    // is known to block for 10-20s on some devices (codec service init). All callers
    // must query off main thread, or we risk Input ANR (FocusEvent timeout).
    // To force off-main discipline, we use nullable cached values initialized on demand.

    @Volatile
    private var cachedAv1: Boolean? = null
    @Volatile
    private var cachedHevc: Boolean? = null

    /** A hardware-accelerated AV1 (`video/av01`) encoder exists on this device. */
    val isHardwareAv1EncoderAvailable: Boolean
        get() = cachedAv1 ?: hasEncoder(MediaFormat.MIMETYPE_VIDEO_AV1, requireHardware = true).also { cachedAv1 = it }

    /** An HEVC/H.265 (`video/hevc`) encoder exists on this device. */
    val isHevcEncoderAvailable: Boolean
        get() = cachedHevc ?: hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, requireHardware = false).also { cachedHevc = it }

    /** Call off main thread to pre-warm caches safely. */
    fun prewarm() {
        try {
            if (cachedAv1 == null) cachedAv1 = hasEncoder(MediaFormat.MIMETYPE_VIDEO_AV1, true)
            if (cachedHevc == null) cachedHevc = hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC, false)
        } catch (_: Exception) {}
    }

    private fun hasEncoder(mimeType: String, requireHardware: Boolean): Boolean {
        return try {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Log.w("CodecSupport", "hasEncoder($mimeType) called on MAIN thread - will block! Stack:", Throwable())
            }
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                    (!requireHardware || info.isHardwareAccelerated) &&
                    info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        } catch (e: Exception) {
            Log.w("CodecSupport", "Failed to query encoders for $mimeType", e)
            false
        }
    }
}
