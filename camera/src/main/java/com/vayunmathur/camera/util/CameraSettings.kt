package com.vayunmathur.camera.util

import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

internal fun CameraViewModel.loadSettings() {
    loadCaptureSettings()
    loadPreferenceToggles()
    if (levelEnabledMutable.value) registerLevelSensor()
}

/** Flash/timer/aspect/codec/audio-source/save-target/last-capture restores. */
private fun CameraViewModel.loadCaptureSettings() {
    ds.getString("camera_flash")?.let {
        runCatching { flashModeMutable.value = FlashMode.valueOf(it) }
    }
    ds.getString("camera_timer")?.let {
        runCatching { timerDurationMutable.value = TimerDuration.valueOf(it) }
    }
    ds.getString("camera_aspect_ratio")?.let {
        runCatching { aspectRatioMutable.value = AspectRatioOption.valueOf(it) }
    }
    ds.getString("camera_video_codec")?.let {
        videoCodecMutable.value = try { VideoCodec.valueOf(it) } catch (_: Exception) {
            when {
                CodecSupport.isHardwareAv1EncoderAvailable -> VideoCodec.AV1
                CodecSupport.isHevcEncoderAvailable -> VideoCodec.HEVC
                else -> VideoCodec.AVC
            }
        }
    }
    ds.getString("camera_audio_source")?.let {
        audioInputSourceMutable.value = try {
            AudioInputSource.valueOf(it)
        } catch (_: Exception) {
            AudioInputSource.CAMCORDER
        }
    }
    loadSaveTarget()
    // Guard against a blank persisted value: Uri.parse("") yields a non-null
    ds.getString("camera_last_capture")?.takeIf { it.isNotBlank() }?.let { lastCaptureUriMutable.value = it.toUri() }
}

/** Boolean/float preference restores (location, grid, level, mic, zoom, mirror). */
private fun CameraViewModel.loadPreferenceToggles() {
    ds.getString("camera_location")?.let { locationEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_grid")?.let { gridEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_level")?.let { levelEnabledMutable.value = it.toBoolean() }
    ds.getString("camera_mic_muted")?.let { micMutedMutable.value = it.toBoolean() }
    ds.getString("camera_zoom_ratio")?.toFloatOrNull()?.let { zoomRatioMutable.value = it }
    ds.getString("camera_mirror_front")?.let { mirrorFrontMutable.value = it.toBoolean() }
}

fun CameraViewModel.setFlashMode(mode: FlashMode) {
    flashModeMutable.value = mode
    viewModelScope.launch { ds.setString("camera_flash", mode.name) }
}

/**
 * Toggle horizontal mirroring of the front-camera preview and saved photo/video (issue #632).
 * Takes effect on the next capture; the preview updates immediately via the UI layer.
 */
fun CameraViewModel.setMirrorFront(enabled: Boolean) {
    mirrorFrontMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_mirror_front", enabled.toString()) }
}

fun CameraViewModel.toggleTorch() {
    torchEnabledMutable.value = !torchEnabledMutable.value
}

fun CameraViewModel.setTimerDuration(duration: TimerDuration) {
    timerDurationMutable.value = duration
    viewModelScope.launch { ds.setString("camera_timer", duration.name) }
}

fun CameraViewModel.setAspectRatio(ratio: AspectRatioOption) {
    aspectRatioMutable.value = ratio
    // Re-apply the crop to the live capture use case so the next shot (and
    // its preview cropRect) matches the newly selected ratio without a rebind.
    imageCapture?.setCropAspectRatio(currentCropAspectRatio())
    viewModelScope.launch { ds.setString("camera_aspect_ratio", ratio.name) }
}

/** Cycles the aspect ratio 4:3 → 3:2 → 16:9 → 1:1 → 4:3 (top-bar icon). */
fun CameraViewModel.cycleAspectRatio() {
    val order = listOf(
        AspectRatioOption.RATIO_4_3,
        AspectRatioOption.RATIO_3_2,
        AspectRatioOption.RATIO_16_9,
        AspectRatioOption.RATIO_1_1
    )
    val next = order[(order.indexOf(aspectRatioMutable.value) + 1) % order.size]
    setAspectRatio(next)
}

fun CameraViewModel.setLocationEnabled(enabled: Boolean) {
    locationEnabledMutable.value = enabled
    viewModelScope.launch { ds.setString("camera_location", enabled.toString()) }
}

fun CameraViewModel.setVideoCodec(codec: VideoCodec) {
    videoCodecMutable.value = codec
    viewModelScope.launch { ds.setString("camera_video_codec", codec.name) }
}

fun CameraViewModel.setAudioInputSource(source: AudioInputSource) {
    audioInputSourceMutable.value = source
    viewModelScope.launch { ds.setString("camera_audio_source", source.name) }
}

fun CameraViewModel.toggleGrid() {
    gridEnabledMutable.value = !gridEnabledMutable.value
    viewModelScope.launch { ds.setString("camera_grid", gridEnabledMutable.value.toString()) }
}

fun CameraViewModel.toggleLevel() {
    levelEnabledMutable.value = !levelEnabledMutable.value
    if (levelEnabledMutable.value) registerLevelSensor() else unregisterLevelSensor()
    viewModelScope.launch { ds.setString("camera_level", levelEnabledMutable.value.toString()) }
}

/** Maps the 0..1 blur-strength UI value to the bokeh shader's blurScale multiplier. */
fun CameraViewModel.setBlurStrength(value: Float) {
    blurStrengthMutable.value = value.coerceIn(0f, 1f)
}

fun CameraViewModel.setExposureCompensation(value: Float) {
    exposureCompensationMutable.value = value
}

fun CameraViewModel.setWarmth(value: Float) {
    warmthMutable.value = value
}

fun CameraViewModel.setShadows(value: Float) {
    shadowsMutable.value = value
}

internal fun CameraViewModel.setLastCaptureUri(uri: Uri?) {
    lastCaptureUriMutable.value = uri
    viewModelScope.launch { ds.setString("camera_last_capture", uri?.toString() ?: "") }
}

@android.annotation.SuppressLint("MissingPermission")
fun CameraViewModel.updateLocation() {
    if (!locationEnabledMutable.value) return
    try {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastLocation = lm.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } catch (e: SecurityException) {
        Log.w("CameraViewModel", "Failed to read last known location", e)
    }
}
