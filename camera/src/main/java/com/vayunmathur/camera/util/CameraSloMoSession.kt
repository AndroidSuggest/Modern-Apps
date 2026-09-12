package com.vayunmathur.camera.util

import android.util.Log
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

/**
 * Sets up a true high-speed (HFR) session for Slo-Mo.
 *
 * Requirements per product spec:
 * - Back camera only (no front-camera / selfie Slo-Mo)
 * - No normal-speed fallbacks: only bind true HFR (slowMotionEnabled=true); if it fails,
 *   return false so the caller knows the device can't do Slo-Mo.
 * - Quality handling is Slo-Mo-only and does NOT affect VIDEO/PHOTO/etc (those use their own
 *   QualitySelector in setupVideoSession/setupPhotoSession). This method's quality selection
 *   only touches the HFR Recorder built here.
 */
@OptIn(ExperimentalCamera2Interop::class)
suspend fun CameraViewModel.setupHighSpeedSession(): Boolean {
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        provider.unbindAll()

        // Enforce back camera — Slo-Mo is disallowed on front.
        if (_lensFacing.value != CameraSelector.LENS_FACING_BACK) {
            _lensFacing.value = CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val cameraInfo = provider.getCameraInfo(selector)
        val capabilities = Recorder.getHighSpeedVideoCapabilities(cameraInfo)
        if (capabilities == null) {
            Log.d("SloMo", "High-speed video not supported on back camera on this device")
            _sloMoSupported.value = false
            return false
        }

        // Only look at SDR qualities supported by the HFR path — this is Slo-Mo-local.
        // Bug fix for Pixel 8 black screen: default VideoCapture quality (often UHD) is
        // not supported for HFR on many devices; filtering to supported HFR qualities fixes bind.
        val supportedQualities = capabilities.getSupportedQualities(
            androidx.camera.core.DynamicRange.SDR
        )
        Log.d("SloMo", "High-speed supported qualities (back): $supportedQualities")

        if (supportedQualities.isEmpty()) {
            Log.e("SloMo", "High-speed reports no supported qualities")
            _sloMoSupported.value = false
            return false
        }

        // Build a QualitySelector using ONLY qualities that HFR actually supports.
        // Prefer FHD/HD which are the typical slo-mo resolutions on Pixel 8 (1080p 120/240fps).
        // This never touches other modes' quality selectors.
        val preferredOrder = listOf(Quality.FHD, Quality.HD, Quality.UHD, Quality.SD)
        val orderedQualities = preferredOrder.filter { it in supportedQualities }
            .ifEmpty { supportedQualities.toList() }
        val qualitySelector = QualitySelector.fromOrderedList(
            orderedQualities,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        )

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

        val recorder = Recorder.Builder()
            .setQualitySelector(qualitySelector)
            .build()
        // MirrorMode is not allowed for high-speed video (HighSpeedVideoSessionConfig
        // validates and throws IllegalArgumentException). Slo-Mo is back-only anyway.
        val videoCapture = VideoCapture.Builder(recorder).build()
        highSpeedVideoCapture = videoCapture

        // Query available HFR frame-rate ranges via a temp config.
        val tempConfig = HighSpeedVideoSessionConfig.Builder(videoCapture)
            .setPreview(preview)
            .setSlowMotionEnabled(true)
            .build()
        val ranges = try {
            cameraInfo.getSupportedFrameRateRanges(tempConfig)
        } catch (e: Exception) {
            Log.w("SloMo", "Failed to query HFR ranges", e)
            emptyList()
        }
        Log.d("SloMo", "High-speed supported frame rate ranges: $ranges")

        if (ranges.isEmpty()) {
            Log.e("SloMo", "No high-speed frame rate ranges available")
            _sloMoSupported.value = false
            return false
        }

        // Try ranges from highest fps downwards — Pixel 8 back: 240fps, 120fps.
        // No normal-speed (<=30fps) fallbacks allowed; only true HFR >= 60fps.
        val hfrRanges = ranges.filter { it.upper >= 60 }.sortedByDescending { it.upper }
        if (hfrRanges.isEmpty()) {
            Log.e("SloMo", "No true HFR (>=60fps) ranges found")
            _sloMoSupported.value = false
            return false
        }

        var bound = false
        var lastError: Exception? = null
        for (range in hfrRanges) {
            try {
                provider.unbindAll()
                // Re-attach surface provider after unbindAll for preview to re-emit request
                preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

                val configBuilder = HighSpeedVideoSessionConfig.Builder(videoCapture)
                    .setPreview(preview)
                    .setSlowMotionEnabled(true)
                    .setFrameRateRange(range)
                    .setAutoRotationEnabled(true)

                val owner = ManualLifecycleOwner()
                owner.start()
                sessionLifecycleOwner?.destroy()
                sessionLifecycleOwner = owner

                boundCamera = provider.bindToLifecycle(owner, selector, configBuilder.build())
                sloMoFps = range.upper
                Log.d("SloMo", "High-speed session bound at ${range.upper}fps (range=$range), quality=$orderedQualities")
                bound = true
                break
            } catch (e: Exception) {
                lastError = e
                Log.w("SloMo", "Failed to bind HFR at $range, trying next", e)
                sessionLifecycleOwner?.destroy()
                sessionLifecycleOwner = null
            }
        }

        if (!bound) {
            Log.e("SloMo", "All HFR ranges failed, last error: $lastError")
            _sloMoSupported.value = false
            return false
        }

        // Set anti-banding to reduce flicker under artificial light
        try {
            val cam2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(
                boundCamera!!.cameraControl
            )
            cam2Control.setCaptureRequestOptions(
                androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                        android.hardware.camera2.CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO
                    )
                    .build()
            )
        } catch (e: Exception) {
            Log.w("SloMo", "Could not set anti-banding", e)
        }

        boundCamera?.cameraInfo?.zoomState?.value?.let {
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            Log.d("NightPreview", "setupPhotoSession() after levels=${_availableZoomLevels.value} ratio=${_zoomRatio.value}")
        }
        _sloMoSupported.value = true
        _highSpeedActive.value = true
        true
    } catch (e: Exception) {
        Log.e("SloMo", "Failed to set up high-speed session", e)
        false
    }
}
