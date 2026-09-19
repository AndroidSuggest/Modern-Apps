package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCamera2Interop::class)
internal fun CameraViewModel.captureSinglePhoto() {
    val capture = imageCapture ?: return
    _isCapturing.value = true
    val fileName = "IMG_${MediaStoreSaver.timestamp()}.jpg"
    val pending = prepareStillSave(fileName) ?: run {
        _isCapturing.value = false
        return
    }
    val outputOptions = pending.outputOptions

    val stop = CameraViewModel.EXPOSURE_TIME_STOPS[_exposureTimeIndex.value]
    // Manual shutter/ISO are already applied live via applyManualControls(); the only transient
    // per-capture override here is the night-mode emulation (fully-auto exposure + night active).
    // Skip it when the vendor NIGHT extension preview is bound: that session runs its own
    // multi-frame AE and rejects Camera2-interop AE_MODE_OFF/manual-exposure options, which makes
    // the capture fail. In that case a plain takePicture() lets the extension produce the shot.
    val nightExposure = if (nightModeActive.value && isExposureAuto() && !_nightPreviewActive.value)
        computeNightExposure() else null
    // Used only to drive the long-exposure countdown overlay.
    val exposureNanos = stop.nanos ?: nightExposure?.nanos
    val nightIso = nightExposure?.iso
    val cam2Control = try {
        boundCamera?.cameraControl?.let {
            androidx.camera.camera2.interop.Camera2CameraControl.from(it)
        }
    } catch (e: Exception) { Log.w("CameraViewModel", "Camera2 control unavailable", e); null }

    fun restoreAfterNight() {
        // Undo the transient night override by re-asserting the (auto) manual-control state.
        if (nightExposure != null) applyManualControls()
    }

    fun doCapture() {
        if (exposureNanos != null && exposureNanos >= 250_000_000L) {
            startLongExposureCountdown(exposureNanos)
        }
    fun finishCapture(uri: Uri?) {
            _isCapturing.value = false
            stopLongExposureCountdown()
            restoreAfterNight()
            pending.closeStream()
            if (uri != null) setLastCaptureUri(uri)
        }

        val warmth = _warmth.value
        val shadows = _shadows.value
        val mirror = mirrorCaptures
        val bokeh = _cameraMode.value == CameraMode.PORTRAIT
        val strength = _blurStrength.value
        if (bokeh || warmth != 0f || shadows != 0f) {
            // The warmth/shadows adjustment and the portrait bokeh only live in the preview
            // RenderEffect, so bake them into the pixels here: capture in-memory, re-run the
            // same shader/color matrix over the full-resolution frame, then re-encode.
            // Re-encoding drops the JPEG's EXIF, so we copy it back from the original frame
            // (plus GPS and the orientation tag) to match the normal path.
            // Caveat: this processed path is always SDR JPEG — decoding to an ARGB_8888 bitmap
            // discards any Ultra HDR gain map, so processed captures lose HDR even when
            // Ultra HDR is otherwise active. Normal (unprocessed) captures keep the gain map.
            capture.takePicture(
                ContextCompat.getMainExecutor(app),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val degrees = image.imageInfo.rotationDegrees
                        // cropRect reflects setCropAspectRatio; apply it to the decoded bitmap
                        // since the raw JPEG buffer is always full-frame for in-memory captures.
                        val cropRect = Rect(image.cropRect)
                        val sourceJpeg = try {
                            image.planes[0].buffer.let { buf ->
                                ByteArray(buf.remaining()).also { buf.get(it) }
                            }
                        } finally {
                            image.close()
                        }
                        viewModelScope.launch {
                            val uri = withContext(Dispatchers.IO) {
                                val decoded = cropToRect(
                                    BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size),
                                    cropRect
                                )
                                // The bokeh renderer folds the colour matrix and the mirror in
                                // as it composites; it leaves `decoded` alone if it can't run,
                                // so fall back to the plain colour pass.
                                val adjusted = (if (bokeh) {
                                    stillBokeh.render(decoded, degrees, strength, warmth, shadows, mirror)
                                } else null)
                                    ?: applyColorAdjustments(decoded, warmth, shadows, mirror)
                                val name = "IMG_${MediaStoreSaver.timestamp()}.jpg"
                                saveStillBitmap(name, adjusted)
                                    ?.also { writeCaptureExif(it, sourceJpeg, degrees, mirrored = mirror) }
                                    .also { adjusted.recycle() }
                            }
                            finishCapture(uri)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e("CameraViewModel", "Adjusted capture failed", exception)
                        finishCapture(null)
                    }
                }
            )
            return
        }

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    finishCapture(pending.resolveUri(outputFileResults))
                }
                override fun onError(exception: ImageCaptureException) {
                    finishCapture(null)
                }
            }
        )
    }

    if (nightExposure != null && cam2Control != null) {
        try {
            val options = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_AE_MODE,
                    android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF
                )
                .setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_EXPOSURE_TIME,
                    nightExposure.nanos
                )
            if (nightIso != null) {
                options.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.SENSOR_SENSITIVITY,
                    nightIso
                )
            }
            cam2Control.setCaptureRequestOptions(options.build())
                .addListener({ doCapture() }, ContextCompat.getMainExecutor(app))
        } catch (e: Exception) {
            Log.w("CameraViewModel", "Failed to set night exposure", e)
            doCapture()
        }
    } else {
        doCapture()
    }
}

/**
 * Capture path for the system IMAGE_CAPTURE intent. If the caller supplied an EXTRA_OUTPUT
 * Uri, the full-resolution JPEG is written there and [onSaved] is invoked with a null
 * thumbnail (the documented "no data extra needed" contract). Otherwise a downscaled
 * thumbnail Bitmap is returned for the result "data" extra.
 */
fun CameraViewModel.capturePhotoForResult(onSaved: (Bitmap?) -> Unit, onError: () -> Unit) {
    val capture = imageCapture ?: return onError()
    _isCapturing.value = true
    val executor = ContextCompat.getMainExecutor(app)
    val outputUri = resultOutputUri

    // Portrait bokeh / warmth / shadows only exist in the preview, so a shot taken in those
    // modes has to be re-processed before it goes back to the caller, exactly as
    // captureSinglePhoto() does for the gallery.
    if (_cameraMode.value == CameraMode.PORTRAIT || _warmth.value != 0f || _shadows.value != 0f) {
        capturePhotoForResultProcessed(capture, outputUri, onSaved, onError)
        return
    }

    if (outputUri != null) {
        val outputStream = try {
            app.contentResolver.openOutputStream(outputUri)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Could not open EXTRA_OUTPUT for writing", e)
            null
        }
        if (outputStream == null) {
            _isCapturing.value = false
            return onError()
        }
        val metadata = ImageCapture.Metadata().apply {
            updateLocation()
            if (_locationEnabled.value) location = lastLocation
            isReversedHorizontal = mirrorCaptures
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream)
            .setMetadata(metadata)
            .build()
        capture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                _isCapturing.value = false
                onSaved(null)
            }
            override fun onError(exception: ImageCaptureException) {
                _isCapturing.value = false
                Log.e("CameraViewModel", "IMAGE_CAPTURE to EXTRA_OUTPUT failed", exception)
                onError()
            }
        })
    } else {
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                _isCapturing.value = false
                val mirror = mirrorCaptures
                val thumbnail = try {
                    downscaledThumbnail(image, mirror)
                } finally {
                    image.close()
                }
                onSaved(thumbnail)
            }
            override fun onError(exception: ImageCaptureException) {
                _isCapturing.value = false
                Log.e("CameraViewModel", "IMAGE_CAPTURE thumbnail capture failed", exception)
                onError()
            }
        })
    }
}

/**
 * [capturePhotoForResult] for a shot that needs the preview's portrait bokeh / colour
 * adjustments baked in: capture in-memory, re-run them over the full-resolution frame, then
 * either re-encode into the caller's EXTRA_OUTPUT stream or hand back the "data" thumbnail.
 * Shares captureSinglePhoto()'s Ultra HDR caveat — the decode to ARGB_8888 drops the gain map.
 */
internal fun CameraViewModel.capturePhotoForResultProcessed(
    capture: ImageCapture,
    outputUri: Uri?,
    onSaved: (Bitmap?) -> Unit,
    onError: () -> Unit,
) {
    val warmth = _warmth.value
    val shadows = _shadows.value
    val mirror = mirrorCaptures
    val bokeh = _cameraMode.value == CameraMode.PORTRAIT
    val strength = _blurStrength.value

    capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val degrees = image.imageInfo.rotationDegrees
                val cropRect = Rect(image.cropRect)
                val sourceJpeg = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val decoded = cropToRect(
                                BitmapFactory.decodeByteArray(sourceJpeg, 0, sourceJpeg.size),
                                cropRect
                            )
                            val adjusted = (if (bokeh) {
                                stillBokeh.render(decoded, degrees, strength, warmth, shadows, mirror)
                            } else null)
                                ?: applyColorAdjustments(decoded, warmth, shadows, mirror)
                            // The gallery path leaves rotation to the EXIF tag, but a caller's
                            // Uri may not open "rw" for writeCaptureExif, so bake it into the
                            // pixels here and tag the result upright.
                            val upright = uprightCopy(adjusted, degrees, if (outputUri == null) 512 else 0)
                            adjusted.recycle()
                            if (outputUri == null) {
                                upright
                            } else {
                                val wrote = try {
                                    app.contentResolver.openOutputStream(outputUri)?.use { out ->
                                        upright.compress(Bitmap.CompressFormat.JPEG, 95, out)
                                    } ?: false
                                } finally {
                                    upright.recycle()
                                }
                                if (!wrote) error("Could not write to EXTRA_OUTPUT")
                                writeCaptureExif(outputUri, sourceJpeg, 0)
                                null
                            }
                        }
                    }
                    _isCapturing.value = false
                    result.fold(
                        onSuccess = { onSaved(it) },
                        onFailure = {
                            Log.e("CameraViewModel", "Processed IMAGE_CAPTURE failed", it)
                            onError()
                        }
                    )
                }
            }
            override fun onError(exception: ImageCaptureException) {
                _isCapturing.value = false
                Log.e("CameraViewModel", "Processed IMAGE_CAPTURE capture failed", exception)
                onError()
            }
        }
    )
}
