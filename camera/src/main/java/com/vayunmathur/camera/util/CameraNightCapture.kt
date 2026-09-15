package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.vayunmathur.camera.platform.lensSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * On-demand NIGHT-extension capture: briefly rebinds a Preview + ImageCapture session with the
 * extension-enabled selector, takes one standard shot (the vendor handles the multi-frame night
 * merge internally), saves it, then restores the normal analysis-backed photo session so the
 * moon button keeps tracking brightness. The extension does its own timing, so no manual
 * long-exposure countdown here.
 */
internal suspend fun CameraViewModel.captureNightPhotoExtension() {
    _isCapturing.value = true
    // Drop the photo session so the UI's analyzer effect re-attaches PhotoAnalyzer once we restore.
    _photoSessionActive.value = false
    try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        val mgr = getExtensionsManager(provider)
        if (mgr == null) {
            // Unreachable in practice: entry is gated by isNightExtensionAvailable(), which
            // already resolved the manager. Bail out; finally restores the normal session.
            Log.w("CameraViewModel", "ExtensionsManager missing at night capture; skipping")
            return
        }
        provider.unbindAll()
        imageAnalysis = null
        _analysisStreamActive.value = false

        val baseSelector = lensSelector(_lensFacing.value, _selectedLens.value)
        val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        val capture = ImageCapture.Builder()
            .setFlashMode(getImageCaptureFlashMode())
            .build()
        imageCapture = capture
        boundCamera = bindSession(provider, owner, nightSelector, preview, capture)

        val contentValues = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
        val metadata = ImageCapture.Metadata().apply {
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).setMetadata(metadata).build()

        val savedUri = suspendCancellableCoroutine<Uri?> { cont ->
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        cont.resume(outputFileResults.savedUri)
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Night extension capture failed", exception)
                        cont.resume(null)
                    }
                }
            )
        }
        if (savedUri != null) setLastCaptureUri(savedUri)
    } catch (e: Exception) {
        Log.e("CameraViewModel", "Night extension capture path failed", e)
    } finally {
        // Rebind the normal 3-stream session; sets _photoSessionActive=true so the UI re-attaches
        // PhotoAnalyzer. If teardown interrupted us, this is superseded by the lifecycle rebind.
        setupPhotoSession()
        _isCapturing.value = false
    }
}

/**
 * Multi-frame night capture: locks the sensor to a per-frame night exposure/ISO, collects a
 * burst off the ImageAnalysis stream, then aligns + merges + brightens it (via
 * [NightCaptureEngine]) and saves. Falls back to the single long-exposure capture if the burst
 * is empty or the merge fails, so the user always gets a shot.
 */
internal fun CameraViewModel.captureNightPhotoCustom() {
    _isCapturing.value = true
    val perFrame = computeNightExposure(CameraViewModel.NIGHT_BURST_PER_FRAME_NANOS)
    // The countdown overlay shows the total burst duration.
    startLongExposureCountdown(perFrame.nanos * NightCaptureEngine.NIGHT_BURST_COUNT)

    captureNightBurst(perFrame) { frames ->
        if (frames.isEmpty()) {
            Log.w("CameraViewModel", "Night burst produced no frames; falling back to single capture")
            stopLongExposureCountdown()
            captureSinglePhoto()
            return@captureNightBurst
        }
        viewModelScope.launch {
            val uri = withContext(Dispatchers.Default) {
                val merged = NightCaptureEngine.merge(frames)
                frames.forEach { it.recycle() }
                merged?.let { bmp ->
                    val values = MediaStoreSaver.imageValues("IMG_${MediaStoreSaver.timestamp()}.jpg")
                    MediaStoreSaver.saveBitmap(app.contentResolver, values, bmp)
                        .also { bmp.recycle() }
                }
            }
            if (uri != null) {
                // Merged pixels are already upright/mirrored, so the orientation tag is normal.
                withContext(Dispatchers.IO) { writeCaptureExif(uri, null, 0) }
                _isCapturing.value = false
                stopLongExposureCountdown()
                setLastCaptureUri(uri)
            } else {
                Log.w("CameraViewModel", "Night merge failed; falling back to single capture")
                stopLongExposureCountdown()
                captureSinglePhoto()
            }
        }
    }
}

/**
 * Proper night burst: full-res capture via [ImageCapture] instead of low-res
 * [ImageAnalysis]. Locks 3A to [exposure], then fires [NightCaptureEngine.NIGHT_BURST_COUNT]
 * full-resolution in-memory captures, each converted to upright/consistent bitmap.
 * This avoids the previous path's analysis-resolution limitation and double JPEG
 * loss (now passed lossless RGBA to Rust via [StitchNative.newNightSession]).
 *
 * Falls back to empty list if [imageCapture] is unavailable, which makes
 * [captureNightPhotoCustom] fall back to single-frame long-exposure.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.captureNightBurst(exposure: NightExposure, onDone: (List<Bitmap>) -> Unit) {
    val capture = imageCapture ?: run {
        onDone(emptyList())
        return
    }
    val cam2Control = try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Camera2 control unavailable", e)
        null
    }
    val mirror = mirrorCaptures
    val collected = mutableListOf<Bitmap>()
    var alreadyDone = false

    fun restore3A() {
        if (cam2Control != null) {
            try {
                cam2Control.setCaptureRequestOptions(
                    androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE)
                        .clearCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK)
                        .build()
                )
            } catch (e: Exception) {
                Log.w("CameraViewModel", "Failed to restore auto 3A after night burst", e)
            }
        }
    }

    fun finish() {
        if (alreadyDone) return
        alreadyDone = true
        restore3A()
        // Re-assert (auto) manual-control state to fully undo night override.
        try {
            applyManualControls()
        } catch (_: Exception) {}
        onDone(collected.toList())
    }

    fun takeNext() {
        if (collected.size >= NightCaptureEngine.NIGHT_BURST_COUNT) {
            finish()
            return
        }
        val cap = imageCapture ?: run {
            finish()
            return
        }
        cap.takePicture(
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        // toBitmap() is provided by CameraX (used also in BokehAnalyzer)
                        val raw = image.toBitmap()
                        val matrix = Matrix().apply {
                            postRotate(image.imageInfo.rotationDegrees.toFloat())
                            if (mirror) postScale(-1f, 1f)
                        }
                        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                        if (upright !== raw) raw.recycle()
                        collected.add(upright)
                    } catch (e: Exception) {
                        Log.w("CameraViewModel", "Failed to convert night frame", e)
                    } finally {
                        image.close()
                    }
                    takeNext()
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.w("CameraViewModel", "Night frame capture failed, continuing", exception)
                    takeNext()
                }
            }
        )
    }

    // Lock AE off + set per-frame night exposure/ISO + lock AWB to avoid color drift, then start burst.
    if (cam2Control != null) {
        try {
            val optsBuilder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                    exposure.nanos
                )
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AWB_LOCK, true
                )
            exposure.iso?.let {
                optsBuilder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY, it
                )
            }
            cam2Control.setCaptureRequestOptions(optsBuilder.build())
                .addListener({ takeNext() }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to set night exposure for burst", e)
            takeNext()
        }
    } else {
        takeNext()
    }
}

internal data class NightExposure(val nanos: Long, val iso: Int?)

/**
 * Derives a night exposure/ISO from the bound sensor's characteristics: clamps [targetNanos]
 * into the sensor's exposure-time range and picks a high fraction of its sensitivity range.
 * Falls back to [targetNanos] (and auto ISO) if the characteristics are unavailable.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.computeNightExposure(targetNanos: Long = CameraViewModel.NIGHT_TARGET_EXPOSURE_NANOS): NightExposure {
    val fallback = NightExposure(targetNanos, null)
    return try {
        val cam = boundCamera ?: return fallback
        val info = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo)
        val expRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
        )
        val isoRange = info.getCameraCharacteristic(
            android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
        )
        val nanos = expRange?.let {
            targetNanos.coerceIn(it.lower, it.upper)
        } ?: targetNanos
        val iso = isoRange?.let {
            (it.lower + ((it.upper - it.lower) * CameraViewModel.NIGHT_ISO_FRACTION).roundToInt())
                .coerceIn(it.lower, it.upper)
        }
        NightExposure(nanos, iso)
    } catch (e: Exception) {
        Log.w("CameraViewModel", "Failed to read sensor ranges for night mode", e)
        fallback
    }
}

internal fun CameraViewModel.startLongExposureCountdown(nanos: Long) {
    val durationMs = nanos / 1_000_000
    _longExposureProgress.value = 1f
    _longExposureRemaining.value = formatExposureRemaining(durationMs)
    longExposureTimerJob?.cancel()
    longExposureTimerJob = viewModelScope.launch {
        val start = System.currentTimeMillis()
        while (true) {
            val elapsed = System.currentTimeMillis() - start
            val remaining = (durationMs - elapsed).coerceAtLeast(0)
            _longExposureProgress.value = remaining.toFloat() / durationMs
            _longExposureRemaining.value = formatExposureRemaining(remaining)
            if (remaining <= 0) break
            delay(50)
        }
    }
}

internal fun CameraViewModel.stopLongExposureCountdown() {
    longExposureTimerJob?.cancel()
    longExposureTimerJob = null
    _longExposureProgress.value = 0f
    _longExposureRemaining.value = ""
}

private fun formatExposureRemaining(ms: Long): String {
    val seconds = ms / 1000f
    return "%.1fs".format(seconds)
}
