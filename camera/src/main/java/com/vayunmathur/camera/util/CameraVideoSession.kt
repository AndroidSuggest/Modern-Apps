package com.vayunmathur.camera.util

import android.media.MediaFormat
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture

suspend fun CameraViewModel.setupVideoSession(): Boolean {
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()
        val cameraInfo = provider.getCameraInfo(selector)

        val selectedCodec = _videoCodec.value
        // Respect user setting with fallback: AV1 > HEVC > AVC priority for availability check.
        // If selected codec isn't available, fall back to next best available.
        val useAv1 = selectedCodec == VideoCodec.AV1 &&
            CodecSupport.isHardwareAv1EncoderAvailable && av1SupportedByCamera(cameraInfo)
        val useHevc = !useAv1 && selectedCodec != VideoCodec.AVC &&
            CodecSupport.isHevcEncoderAvailable && hevcSupportedByCamera(cameraInfo)
        val useOpus = false
        // HLG10 (10-bit HDR) when the camera's video pipeline supports it. Uses the default
        // HEVC/H.264 codec (AV1 stays off). Preview and VideoCapture must share the dynamic
        // range or the bind fails, so both are gated together.
        val hlgSupported = hlgSupportedByCamera(cameraInfo)
        Log.d("VideoSession", "Codec selection: av1=$useAv1, hevc=$useHevc, opus=$useOpus, hlg10=$hlgSupported")

        // Cinematic mode enables video stabilization; all video modes always record at max
        // quality/fps (no UI picker).
        val cinematic = _cameraMode.value == CameraMode.CINEMATIC
        val bestFpsRange = highestFpsRange(cameraInfo)
        val stabilizationMode = if (cinematic) preferredStabilizationMode(cameraInfo) else null
        Log.d("VideoSession", "Video tuning: fps=$bestFpsRange, stabilization=$stabilizationMode")

        // Prefer UHD, then FHD, then HD, falling back to the next lower supported quality.
        val qualitySelector = QualitySelector.fromOrderedList(
            listOf(Quality.UHD, Quality.FHD, Quality.HD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        )

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        fun bind(av1: Boolean, hevc: Boolean, opus: Boolean, hlg: Boolean, snapshot: Boolean): Camera {
            val dynamicRange = if (hlg) androidx.camera.core.DynamicRange.HLG_10_BIT
                else androidx.camera.core.DynamicRange.SDR
            val previewBuilder = Preview.Builder()
                .setDynamicRange(dynamicRange)
            applyVideoCaptureRequestOptions(previewBuilder, bestFpsRange, stabilizationMode)
            val preview = previewBuilder.build()
            preview.setSurfaceProvider { request -> _surfaceRequest.value = request }
            val recorderBuilder = Recorder.Builder()
                .setQualitySelector(qualitySelector)
                .setAudioSource(_audioInputSource.value.specValue)
            if (av1) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_AV1)
            else if (hevc) recorderBuilder.setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            if (opus) recorderBuilder.setAudioMimeType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            val captureBuilder = VideoCapture.Builder(recorderBuilder.build())
                .setMirrorMode(
                    if (_mirrorFront.value) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY
                    else MirrorMode.MIRROR_MODE_OFF
                )
                .setDynamicRange(dynamicRange)
            applyVideoCaptureRequestOptions(captureBuilder, bestFpsRange, stabilizationMode)
            val capture = captureBuilder.build()
            videoCapture = capture
            recordingWithAv1 = av1
            recordingWithHevc = hevc
            return if (snapshot) {
                // Extra ImageCapture use case enables taking a still while recording (SDR JPEG).
                val still = ImageCapture.Builder()
                    .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    .build()
                imageCapture = still
                bindSession(provider, owner, selector, preview, capture, still)
            } else {
                imageCapture = null
                bindSession(provider, owner, selector, preview, capture)
            }
        }

        // Bind ladder: prefer HDR + snapshot, then drop the snapshot use case, then drop HDR.
        val attempts = buildList {
            add(hlgSupported to true)
            add(hlgSupported to false)
            if (hlgSupported) {
                add(false to true)
                add(false to false)
            }
        }
        var bound: Camera? = null
        var lastError: Exception? = null
        for ((hlg, snapshot) in attempts) {
            try {
                provider.unbindAll()
                bound = bind(useAv1, useHevc, useOpus, hlg, snapshot)
                break
            } catch (e: Exception) {
                lastError = e
                Log.w("VideoSession", "Video bind failed (hlg=$hlg, snapshot=$snapshot); trying next", e)
            }
        }
        boundCamera = bound ?: throw (lastError ?: IllegalStateException("Video session bind failed"))
        _videoSnapshotSupported.value = imageCapture != null

        boundCamera?.cameraInfo?.zoomState?.value?.let {
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
        }
        _videoSessionActive.value = true
        true
    } catch (e: Exception) {
        Log.e("VideoSession", "Failed to set up video session", e)
        false
    }
}
