package com.vayunmathur.camera.platform

import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.readManualControlRanges
import com.vayunmathur.camera.util.refreshNightExtensionUsable
import com.vayunmathur.camera.util.restoreZoom
import com.vayunmathur.camera.util.updateZoomLevels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-lens capability snapshot refreshed after every bind, so the UI (zoom
 * pills, flash availability, ISO stops, night/slo-mo affordances) reflects the
 * lens that actually bound rather than the previous one.
 */
data class LensCapabilities(
    val lensId: String?,
    val minZoom: Float,
    val maxZoom: Float,
    val zoomLevels: List<Pair<String, Float>>,
    val hasFlashUnit: Boolean,
    val isoStops: List<Int>,
    val exposureCompRange: ClosedRange<Float>?,
    val nightExtensionUsable: Boolean,
)

/**
 * Re-probes everything the UI derives from the bound camera and publishes it
 * on the ViewModel. Reuses the existing per-bind probes
 * ([updateZoomLevels]/[restoreZoom], [readManualControlRanges],
 * [observeNightModeIndicator], [refreshNightExtensionUsable]) and adds the
 * missing coverage: flash-unit availability per lens.
 *
 * Call on the main thread (LiveData `observeForever` expects it); the heavier
 * extension probe hops to Dispatchers.Default like the UI's launcher effect.
 */
@OptIn(ExperimentalCamera2Interop::class)
suspend fun CameraViewModel.refreshCapabilities(bound: Camera, lensId: String?) {
    val zs = try {
        bound.cameraInfo.zoomState.value
    } catch (e: Exception) {
        Log.w("LensSelector", "Could not read zoom state", e)
        null
    }
    val minZoom = zs?.minZoomRatio ?: 1f
    val maxZoom = zs?.maxZoomRatio ?: 1f
    updateZoomLevels(minZoom, maxZoom)
    restoreZoom(minZoom, maxZoom)

    readManualControlRanges()

    val hasFlash = try {
        bound.cameraInfo.hasFlashUnit()
    } catch (e: Exception) {
        Log.w("LensSelector", "Could not query flash unit", e)
        _hasFlashUnit.value
    }
    _hasFlashUnit.value = hasFlash

    val expRange = try {
        val r = bound.cameraInfo.exposureState.exposureCompensationRange
        r.lower.toFloat()..r.upper.toFloat()
    } catch (e: Exception) {
        Log.w("LensSelector", "Could not query exposure-compensation range", e)
        null
    }
    if (expRange != null) _exposureCompRange.value = expRange

    // Skip the night indicator here: the setup paths own that observation, and
    // re-observing the *extension* camera reports UNKNOWN/NOT_RECOMMENDED, which
    // fights the normal session's RECOMMENDED reading (engage/disengage loop —
    // see the note in setupNightPreviewSession).
    withContext(Dispatchers.Default) {
        refreshNightExtensionUsable(_cameraMode.value)
    }

    _lensCapabilities.value = LensCapabilities(
        lensId = lensId,
        minZoom = minZoom,
        maxZoom = maxZoom,
        zoomLevels = LensSelectionLogic.buildZoomLevels(minZoom, maxZoom),
        hasFlashUnit = hasFlash,
        isoStops = _isoStops.value,
        exposureCompRange = expRange ?: _lensCapabilities.value?.exposureCompRange,
        nightExtensionUsable = _nightExtensionUsable.value,
    )
    Log.d("LensSelector", "Refreshed capabilities lens=$lensId zoom=[$minZoom,$maxZoom] flash=$hasFlash")
}
