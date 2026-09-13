package com.vayunmathur.camera.util

import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop

fun CameraViewModel.setExposureTimeIndex(index: Int) {
    _exposureTimeIndex.value = index.coerceIn(0, CameraViewModel.EXPOSURE_TIME_STOPS.lastIndex)
    applyManualControls()
}

/** ISO index 0 == Auto; otherwise a 1-based index into [_isoStops]. */
fun CameraViewModel.setManualIsoIndex(index: Int) {
    _manualIsoIndex.value = index.coerceIn(0, _isoStops.value.size)
    applyManualControls()
}

@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.camera2ControlOrNull(): androidx.camera.camera2.interop.Camera2CameraControl? = try {
    boundCamera?.cameraControl?.let {
        androidx.camera.camera2.interop.Camera2CameraControl.from(it)
    }
} catch (e: Exception) {
    Log.w("CameraViewModel", "Camera2 control unavailable", e)
    null
}

/** The manual ISO for the current index, or null when set to Auto. */
internal fun CameraViewModel.manualIso(): Int? =
    _manualIsoIndex.value.takeIf { it > 0 }?.let { _isoStops.value.getOrNull(it - 1) }

/** True when both shutter and ISO are on Auto (no manual exposure). */
internal fun CameraViewModel.isExposureAuto(): Boolean =
    _exposureTimeIndex.value == 0 && _manualIsoIndex.value == 0

/**
 * Rebuilds a single [CaptureRequestOptions] from the current manual exposure/ISO state and
 * pushes it to the bound camera, affecting the live preview and subsequent stills. When on Auto
 * the options are cleared, reverting to CameraX's default auto behavior (including tap-to-focus).
 * Called on every manual-control change and re-applied after a session rebind.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun CameraViewModel.applyManualControls() {
    val cam2 = camera2ControlOrNull() ?: return
    val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()

    // Manual exposure / ISO with linkage: if either is manual, lock AE off and set both,
    // seeding the un-set one from the last auto-converged value (or a sensible default).
    val manualShutter = CameraViewModel.EXPOSURE_TIME_STOPS[_exposureTimeIndex.value].nanos
    val manualIso = manualIso()
    if (manualShutter != null || manualIso != null) {
        val exposure = manualShutter ?: lastAeExposureNanos ?: 16_666_667L // ~1/60s
        val iso = manualIso ?: lastAeIso ?: _isoStops.value.getOrNull(_isoStops.value.size / 2) ?: 400
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
            android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME, exposure
        )
        builder.setCaptureRequestOption(
            android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, iso
        )
    }

    try {
        // An empty options set clears any previously-applied manual 3A → full auto.
        cam2.setCaptureRequestOptions(builder.build())
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to apply manual controls", e)
    }
}

internal fun CameraViewModel.resetManualControls() {
    _manualIsoIndex.value = 0
    _exposureTimeIndex.value = 0
}

/** Reads the bound sensor's ISO range → stop list for the manual ISO control. */
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.readManualControlRanges() {
    Log.d("NightPreview", "readManualControlRanges() called bound=${boundCamera != null} thread=${Thread.currentThread().name}")
    val cam = boundCamera ?: run {
        Log.w("NightPreview", "readManualControlRanges() no bound camera, returning")
        return
    }
    try {
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
        val isoRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        Log.d("NightPreview", "readManualControlRanges() isoRange=$isoRange")
        _isoStops.value = if (isoRange != null) {
            val filtered = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)
                .filter { it in isoRange.lower..isoRange.upper }
                .ifEmpty { listOf(isoRange.lower, isoRange.upper) }
            Log.d("NightPreview", "readManualControlRanges() filtered stops=$filtered")
            filtered
        } else {
            Log.w("NightPreview", "readManualControlRanges() isoRange null, emitting emptyList -> ISO bar notAvailable")
            emptyList()
        }
    } catch (e: Exception) {
        Log.e("NightPreview", "readManualControlRanges() FAILED (was Warn, hidden) – could affect ISO bar + manual controls", e)
    }
}
