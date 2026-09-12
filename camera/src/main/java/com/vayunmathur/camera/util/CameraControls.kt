package com.vayunmathur.camera.util

import android.util.Log
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

fun CameraViewModel.setQrResult(text: String?) {
    _qrResult.value = text
}

/**
 * The CameraXViewfinder's built-in pinch-to-zoom already applied [ratio] to the camera, so this
 * only syncs the displayed value (used by the zoom bar). It does NOT call cameraControl again —
 * that would double-apply and could feedback-loop with onZoomRatioChanged.
 */
fun CameraViewModel.onViewfinderZoomRatio(ratio: Float) {
    _zoomRatio.value = ratio
    viewModelScope.launch { ds.setString("camera_zoom_ratio", ratio.toString()) }
}

fun CameraViewModel.setZoomRatio(ratio: Float) {
    val cam = boundCamera
    val zs = cam?.cameraInfo?.zoomState?.value
    Log.d("NightPreview", "setZoomRatio() requested=$ratio clamped? min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} current=${zs?.zoomRatio} nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} boundCamera=${cam != null}")
    val clamped = zs?.let {
        ratio.coerceIn(it.minZoomRatio, it.maxZoomRatio)
    } ?: ratio
    if (clamped != ratio) Log.w("NightPreview", "setZoomRatio() CLAMPED $ratio -> $clamped due to zoomState min/max – vendor NIGHT often reports max=1x, causing bar to show only 1x")
    _zoomRatio.value = clamped
    viewModelScope.launch { ds.setString("camera_zoom_ratio", clamped.toString()) }
    try {
        cam?.cameraControl?.setZoomRatio(clamped)
    } catch (e: Exception) {
        Log.e("NightPreview", "setZoomRatio() setZoomRatio() threw (was hidden before)", e)
    }
}

/**
 * Restore the previously selected zoom after a (re)bind instead of snapping back to the
 * hardware default. [_zoomRatio] holds the last value the user picked (kept in memory across
 * teardown/rebind and loaded from DataStore on process restart); clamp it to the new lens'
 * supported range and re-apply it to the camera. Fixes issue #631 (zoom reset on resume).
 */
internal fun CameraViewModel.restoreZoom(minZoom: Float, maxZoom: Float) {
    val desired = _zoomRatio.value.coerceIn(minZoom, maxZoom)
    _zoomRatio.value = desired
    try {
        boundCamera?.cameraControl?.setZoomRatio(desired)
    } catch (e: Exception) {
        Log.e("NightPreview", "restoreZoom() setZoomRatio() threw", e)
    }
}

fun CameraViewModel.updateZoomLevels(minZoom: Float, maxZoom: Float) {
    Log.d("NightPreview", "updateZoomLevels() min=$minZoom max=$maxZoom nightPreviewActive=${_nightPreviewActive.value} photoActive=${_photoSessionActive.value} currentRatio=${_zoomRatio.value} thread=${Thread.currentThread().name}")
    val levels = mutableListOf<Pair<String, Float>>()
    // Wide-angle entry only when the lens can actually zoom out past 1x.
    if (minZoom < 0.95f) {
        levels.add(formatZoomLabel(minZoom) to minZoom)
    }
    levels.add("1x" to 1f)
    for (tele in listOf(2f, 5f)) {
        if (tele <= maxZoom + 0.05f) levels.add(formatZoomLabel(tele) to tele)
    }
    Log.d("NightPreview", "updateZoomLevels() emitting levels=$levels – if min=1f max=1f, only [1x] will show, explaining 'all zoom levels also disappear'")
    _availableZoomLevels.value = levels
}

fun CameraViewModel.startFocusAndMetering(action: FocusMeteringAction) {
    // A fresh tap clears any existing AE/AF lock.
    _focusLocked.value = false
    boundCamera?.cameraControl?.startFocusAndMetering(action)
}

/**
 * Locks focus + exposure at the metered point by disabling auto-cancel, so 3A stays put until
 * the user taps again (long-press-to-lock). Sets [focusLocked] for the on-screen indicator.
 */
fun CameraViewModel.lockFocusAndMetering(action: FocusMeteringAction) {
    val cam = boundCamera ?: return
    cam.cameraControl.startFocusAndMetering(action)
    _focusLocked.value = true
}

fun CameraViewModel.clearFocusLock() {
    boundCamera?.cameraControl?.cancelFocusAndMetering()
    _focusLocked.value = false
}

fun CameraViewModel.enableTorch(enabled: Boolean) {
    boundCamera?.cameraControl?.enableTorch(enabled)
}

fun CameraViewModel.applyExposureCompensation(value: Float) {
    val cam = boundCamera ?: return
    val range = cam.cameraInfo.exposureState.exposureCompensationRange
    val index = (value * range.upper).toInt().coerceIn(range.lower, range.upper)
    cam.cameraControl.setExposureCompensationIndex(index)
}

/** Pushes the current flash mode onto the bound ImageCapture (runtime-mutable, no rebind). */
fun CameraViewModel.applyImageCaptureFlashMode() {
    imageCapture?.flashMode = getImageCaptureFlashMode()
}

/** Swaps the analyzer on the bound ImageAnalysis without rebinding. */
fun CameraViewModel.setImageAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
    setImageAnalyzer(analyzer, ContextCompat.getMainExecutor(app))
}

fun CameraViewModel.setImageAnalyzer(analyzer: ImageAnalysis.Analyzer?, executor: Executor) {
    desiredAnalyzer = analyzer
    desiredAnalyzerExecutor = executor
    attachDesiredAnalyzer()
}

/** Convenience for portrait bokeh – always runs off the dedicated bokeh thread. */
fun CameraViewModel.setBokehAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
    setImageAnalyzer(analyzer, bokehExecutor)
}

/** Convenience for the photo stream – always runs off the dedicated analysis thread. */
fun CameraViewModel.setPhotoAnalyzer(analyzer: ImageAnalysis.Analyzer?) {
    setImageAnalyzer(analyzer, analysisExecutor)
}

/**
 * Pushes [desiredAnalyzer] onto whatever ImageAnalysis is currently bound. A no-op while none
 * is, which is why the desired analyzer survives teardown: the next successful bind calls this
 * again and picks it back up.
 */
internal fun CameraViewModel.attachDesiredAnalyzer() {
    val analysis = imageAnalysis ?: return
    val analyzer = desiredAnalyzer
    val executor = desiredAnalyzerExecutor
    if (analyzer == null || executor == null) analysis.clearAnalyzer()
    else analysis.setAnalyzer(executor, analyzer)
}

/** Publishes whether the session just bound has an analysis stream, and re-arms the analyzer. */
internal fun CameraViewModel.onSessionBound() {
    _analysisStreamActive.value = imageAnalysis != null
    attachDesiredAnalyzer()
}

internal fun CameraViewModel.startRecordingTimer() {
    _isRecording.value = true
    _recordingPaused.value = false
    _recordingDurationSec.value = 0
    recordingTimerJob = viewModelScope.launch {
        while (true) {
            delay(1000)
            if (!_recordingPaused.value) _recordingDurationSec.value += 1
        }
    }
}

internal fun CameraViewModel.stopRecordingTimer() {
    _isRecording.value = false
    _recordingPaused.value = false
    recordingTimerJob?.cancel()
    _recordingDurationSec.value = 0
}

fun CameraViewModel.triggerShutter() {
    _shutterEvents.tryEmit(Unit)
}

fun CameraViewModel.setSloMoFps(fps: Int) {
    sloMoFps = fps
}
