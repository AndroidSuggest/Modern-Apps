package com.vayunmathur.camera.platform

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Log
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.domain.LensSelectionLogic
import com.vayunmathur.camera.domain.LensType
import com.vayunmathur.camera.domain.PhysicalLens
import com.vayunmathur.camera.util.CameraViewModel
import kotlin.math.sqrt

/** Maps the pure [LensFacing] to the CameraX selector constant. */
fun LensFacing.toSelectorInt(): Int = when (this) {
    LensFacing.BACK -> CameraSelector.LENS_FACING_BACK
    LensFacing.FRONT -> CameraSelector.LENS_FACING_FRONT
}

/** Maps a CameraX lens-facing constant to [LensFacing]; null for external/unknown. */
fun Int.toLensFacing(): LensFacing? = when (this) {
    CameraSelector.LENS_FACING_BACK -> LensFacing.BACK
    CameraSelector.LENS_FACING_FRONT -> LensFacing.FRONT
    else -> null
}

/**
 * Enumerates every physical/logical lens via [ProcessCameraProvider.availableCameraInfos]
 * plus `Camera2CameraInfo` interop (camera ID, `LENS_FACING`, focal lengths, sensor
 * physical size for the 35mm-equivalent label, mono color-filter detection).
 *
 * Types are assigned per facing family sorted by focal length: the lens whose
 * 35mm-equivalent is closest to ~27mm is the wide reference (1x); shorter is
 * ultra-wide, longer is telephoto; a MONO color-filter arrangement wins over the
 * focal heuristic. Front-facing families mark their reference lens FRONT.
 *
 * Safe to call off the main thread; never throws (returns empty on failure).
 */
@OptIn(ExperimentalCamera2Interop::class)
fun enumerateLenses(provider: ProcessCameraProvider): List<PhysicalLens> {
    data class Raw(
        val id: String,
        val facing: LensFacing,
        val focalMm: Float,
        val eq35Mm: Float?,
        val mono: Boolean,
    )
    val raws = provider.availableCameraInfos.mapNotNull { info ->
        try {
            val cam2 = Camera2CameraInfo.from(info)
            val facing = when (
                cam2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
            ) {
                CameraMetadata.LENS_FACING_FRONT -> LensFacing.FRONT
                CameraMetadata.LENS_FACING_BACK -> LensFacing.BACK
                else -> return@mapNotNull null // External / unknown: not a phone lens.
            }
            val focal = cam2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
            )?.firstOrNull() ?: return@mapNotNull null
            val sensorSize = cam2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE
            )
            val eq35 = if (sensorSize != null && sensorSize.width > 0 && sensorSize.height > 0) {
                val diag = sqrt(sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height)
                focal * 43.27f / diag
            } else {
                null
            }
            val mono = cam2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT
            ) == CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO
            Raw(cam2.getCameraId(), facing, focal, eq35, mono)
        } catch (e: Exception) {
            Log.w("LensSelector", "Skipping camera info during lens enumeration", e)
            null
        }
    }

    return LensFacing.entries.flatMap { facing ->
        val family = raws.filter { it.facing == facing }.sortedBy { it.focalMm }
        if (family.isEmpty()) return@flatMap emptyList()
        val nonMono = family.filterNot { it.mono }
        // Wide reference: 35mm-eq closest to ~27mm; fall back to the median focal length.
        val reference = nonMono.minByOrNull { it.eq35Mm?.let { eq -> kotlin.math.abs(eq - 27f) } ?: Float.MAX_VALUE }
            ?: nonMono.getOrNull(nonMono.size / 2)
            ?: family.first()
        family.map { raw ->
            val type = when {
                raw.mono -> LensType.MONOCHROME
                raw == reference && facing == LensFacing.FRONT -> LensType.FRONT
                raw == reference -> LensType.WIDE
                raw.focalMm < reference.focalMm * 0.85f -> LensType.ULTRA_WIDE
                raw.focalMm > reference.focalMm * 1.4f -> LensType.TELEPHOTO
                else -> LensType.WIDE
            }
            PhysicalLens(
                logicalCameraId = raw.id,
                lensType = type,
                facing = facing,
                focalLengthMm35Eq = raw.eq35Mm,
                nominalZoomRatio = raw.focalMm / reference.focalMm,
                fallbackPriority = when (type) {
                    LensType.WIDE, LensType.FRONT -> 0
                    LensType.ULTRA_WIDE -> 1
                    LensType.TELEPHOTO -> 2
                    LensType.MONOCHROME -> 3
                },
                labelKey = when (type) {
                    LensType.ULTRA_WIDE -> "ultrawide"
                    LensType.WIDE -> "wide"
                    LensType.TELEPHOTO -> "tele"
                    LensType.MONOCHROME -> "mono"
                    LensType.FRONT -> "front"
                },
            )
        }
    }
}

/**
 * Fills [_availableLenses][com.vayunmathur.camera.util.CameraViewModel] once per
 * process and anchors the default selection (same-facing wide/front). No-op once
 * populated; call at the top of every session setup after obtaining the provider.
 */
fun CameraViewModel.ensureLensesEnumerated(provider: ProcessCameraProvider) {
    if (_availableLenses.value.isNotEmpty()) return
    val all = try {
        enumerateLenses(provider)
    } catch (e: Exception) {
        Log.w("LensSelector", "Lens enumeration failed; sessions fall back to facing-only selectors", e)
        emptyList()
    }
    _availableLenses.value = all
    if (_selectedLens.value == null) {
        val facing = _lensFacing.value.toLensFacing() ?: LensFacing.BACK
        _selectedLens.value = LensSelectionLogic.filterByFacing(all, facing)
            .minByOrNull { it.fallbackPriority }
    }
    Log.d("LensSelector", "Enumerated ${all.size} lenses; selected=${_selectedLens.value?.labelKey}")
}

/** Lenses of the current facing, ordered by fallback priority. */
fun CameraViewModel.currentLensFamily(): List<PhysicalLens> {
    val facing = _lensFacing.value.toLensFacing() ?: LensFacing.BACK
    return LensSelectionLogic.filterByFacing(_availableLenses.value, facing)
}

/**
 * Builds the selector for [facingInt], pinned to [lens] via a `CameraFilter` on
 * the logical camera ID when non-null. A null lens keeps the legacy facing-only
 * behavior.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun CameraViewModel.lensSelector(facingInt: Int, lens: PhysicalLens?): CameraSelector {
    val builder = CameraSelector.Builder().requireLensFacing(facingInt)
    if (lens != null) {
        val id = lens.logicalCameraId
        builder.addCameraFilter { infos ->
            infos.filter { info ->
                try {
                    Camera2CameraInfo.from(info).getCameraId() == id
                } catch (_: Exception) {
                    false
                }
            }
        }
    }
    return builder.build()
}

/**
 * Binds via [bind], retrying down the lens ladder: requested lens → same-facing
 * wide → any same-facing (priority order). When enumeration produced nothing,
 * the ladder is a single facing-only selector — identical to the legacy path.
 *
 * @return the lens that actually bound (null when facing-only) plus the camera.
 */
fun CameraViewModel.bindWithFallback(
    provider: ProcessCameraProvider,
    requested: PhysicalLens?,
    family: List<PhysicalLens>,
    bind: (CameraSelector) -> Camera,
): Pair<PhysicalLens?, Camera> {
    val facingInt = _lensFacing.value
    val ordered = buildList {
        if (requested != null) add(requested)
        family.sortedBy { it.fallbackPriority }.forEach { if (it != requested) add(it) }
        if (requested == null && family.isEmpty()) add(null)
    }
    var lastError: Exception? = null
    var first = true
    for (candidate in ordered) {
        try {
            // The caller unbinds before the first attempt; unbind between retries only.
            if (!first) {
                try {
                    provider.unbindAll()
                } catch (_: Exception) {
                }
            }
            first = false
            val camera = bind(lensSelector(facingInt, candidate))
            if (candidate != requested) {
                Log.w("LensSelector", "Fell back to lens=${candidate?.labelKey} (requested=${requested?.labelKey})")
            }
            return candidate to camera
        } catch (e: Exception) {
            lastError = e
            Log.w("LensSelector", "Bind failed on lens=${candidate?.labelKey}; trying next", e)
        }
    }
    throw lastError ?: IllegalStateException("Lens bind failed with an empty ladder")
}
