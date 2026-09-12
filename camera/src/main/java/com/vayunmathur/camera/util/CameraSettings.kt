package com.vayunmathur.camera.util

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal fun CameraViewModel.loadSettings() {
    ds.getString("camera_flash")?.let { _flashMode.value = FlashMode.valueOf(it) }
    ds.getString("camera_timer")?.let { _timerDuration.value = TimerDuration.valueOf(it) }
    ds.getString("camera_aspect_ratio")?.let { _aspectRatio.value = AspectRatioOption.valueOf(it) }
    ds.getString("camera_video_codec")?.let {
        _videoCodec.value = try { VideoCodec.valueOf(it) } catch (_: Exception) {
            when {
                CodecSupport.isHardwareAv1EncoderAvailable -> VideoCodec.AV1
                CodecSupport.isHevcEncoderAvailable -> VideoCodec.HEVC
                else -> VideoCodec.AVC
            }
        }
    }
    ds.getString("camera_location")?.let { _locationEnabled.value = it.toBoolean() }
    ds.getString("camera_audio_source")?.let {
        _audioInputSource.value = try { AudioInputSource.valueOf(it) } catch (_: Exception) { AudioInputSource.CAMCORDER }
    }
    // Guard against a blank persisted value: Uri.parse("") yields a non-null
    ds.getString("camera_last_capture")?.takeIf { it.isNotBlank() }?.let { _lastCaptureUri.value = it.toUri() }
    ds.getString("camera_grid")?.let { _gridEnabled.value = it.toBoolean() }
    ds.getString("camera_level")?.let { _levelEnabled.value = it.toBoolean() }
    ds.getString("camera_mic_muted")?.let { _micMuted.value = it.toBoolean() }
    ds.getString("camera_zoom_ratio")?.toFloatOrNull()?.let { _zoomRatio.value = it }
    ds.getString("camera_mirror_front")?.let { _mirrorFront.value = it.toBoolean() }
    if (_levelEnabled.value) registerLevelSensor()
}

fun CameraViewModel.setFlashMode(mode: FlashMode) {
    _flashMode.value = mode
    viewModelScope.launch { ds.setString("camera_flash", mode.name) }
}

/**
 * Toggle horizontal mirroring of the front-camera preview and saved photo/video (issue #632).
 * Takes effect on the next capture; the preview updates immediately via the UI layer.
 */
fun CameraViewModel.setMirrorFront(enabled: Boolean) {
    _mirrorFront.value = enabled
    viewModelScope.launch { ds.setString("camera_mirror_front", enabled.toString()) }
}

fun CameraViewModel.toggleTorch() {
    _torchEnabled.value = !_torchEnabled.value
}

fun CameraViewModel.setTimerDuration(duration: TimerDuration) {
    _timerDuration.value = duration
    viewModelScope.launch { ds.setString("camera_timer", duration.name) }
}

fun CameraViewModel.setAspectRatio(ratio: AspectRatioOption) {
    _aspectRatio.value = ratio
    // Re-apply the crop to the live capture use case so the next shot (and
    // its preview cropRect) matches the newly selected ratio without a rebind.
    imageCapture?.setCropAspectRatio(currentCropAspectRatio())
    viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
}

/** Cycles the aspect ratio 4:3 → 16:9 → 1:1 → 4:3 (top-bar icon). */
fun CameraViewModel.cycleAspectRatio() {
    val order = listOf(
        AspectRatioOption.RATIO_4_3,
        AspectRatioOption.RATIO_16_9,
        AspectRatioOption.RATIO_1_1
    )
    val next = order[(order.indexOf(_aspectRatio.value) + 1) % order.size]
    setAspectRatio(next)
}

fun CameraViewModel.setLocationEnabled(enabled: Boolean) {
    _locationEnabled.value = enabled
    viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
}

fun CameraViewModel.setVideoCodec(codec: VideoCodec) {
    _videoCodec.value = codec
    viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
}

fun CameraViewModel.setAudioInputSource(source: AudioInputSource) {
    _audioInputSource.value = source
    viewModelScope.launch { ds.setString("camera_audio_source", source.name) }
}

fun CameraViewModel.toggleGrid() {
    _gridEnabled.value = !_gridEnabled.value
    viewModelScope.launch { ds.setString("camera_grid", _gridEnabled.value.toString()) }
}

fun CameraViewModel.toggleLevel() {
    _levelEnabled.value = !_levelEnabled.value
    if (_levelEnabled.value) registerLevelSensor() else unregisterLevelSensor()
    viewModelScope.launch { ds.setString("camera_level", _levelEnabled.value.toString()) }
}

/** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
fun CameraViewModel.setBlurStrength(value: Float) {
    _blurStrength.value = value.coerceIn(0f, 1f)
}

fun CameraViewModel.setExposureCompensation(value: Float) {
    _exposureCompensation.value = value
}

fun CameraViewModel.setWarmth(value: Float) {
    _warmth.value = value
}

fun CameraViewModel.setShadows(value: Float) {
    _shadows.value = value
}

internal fun CameraViewModel.setLastCaptureUri(uri: Uri?) {
    _lastCaptureUri.value = uri
    viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.updateLocation() {
    if (!_locationEnabled.value) return
    try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to read last known location", e)
    }
}
