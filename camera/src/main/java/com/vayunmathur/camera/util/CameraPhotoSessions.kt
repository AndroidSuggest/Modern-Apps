package com.vayunmathur.camera.util

import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance

/**
 * Binds a manual Preview + ImageCapture + ImageAnalysis session for the photo modes
 * (PHOTO / PORTRAIT / PANORAMA / PHOTOSPHERE / QR). ImageCapture requests the sensor's
 * maximum resolution; if the 3-stream max-res combination exceeds a device's stream-config
 * limits, it falls back to a default ImageCapture resolution. ImageAnalysis is always
 * capped (~1.2 MP) — see the note in [CameraViewModel.bindSession].
 */
@OptIn(ExperimentalCamera2Interop::class)
suspend fun CameraViewModel.setupPhotoSession(): Boolean {
    Log.d("NightPreview", "setupPhotoSession() ENTRY thread=${Thread.currentThread().name} lens=${_lensFacing.value} surfaceBefore=${_surfaceRequest.value?.resolution}")
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        Log.d("NightPreview", "setupPhotoSession() got providerHash=${provider.hashCode()}")
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()

        val previewBuilder = Preview.Builder()
        // Snapshot auto-converged AE ISO/exposure off the repeating preview requests so a
        // half-manual exposure can seed the un-set parameter.
        try {
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(aeSnapshotCallback)
            Log.d("NightPreview", "setupPhotoSession() attached AE snapshot callback")
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPhotoSession() Could not attach AE snapshot callback (was hidden as Warn)", e)
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider { request ->
            Log.d("NightPreview", "setupPhotoSession() surfaceRequest emitted res=${request.resolution} format=${request.javaClass.simpleName} thread=${Thread.currentThread().name} nightActive=${nightModeActive.value}")
            _surfaceRequest.value = request
        }
        Log.d("NightPreview", "setupPhotoSession() preview surfaceProvider attached")

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        // Ultra HDR (JPEG with a gain map) when the sensor/pipeline supports it. Queried once
        // here; the bind ladder falls back to plain JPEG if the Ultra HDR combo can't bind.
        val ultraHdrSupported = try {
            val cameraInfo = provider.getCameraInfo(selector)
            val caps = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            Log.d("NightPreview", "setupPhotoSession() ultraHdrSupported=$caps selector=$selector")
            caps
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPhotoSession() Could not query Ultra HDR support (was hidden as Warn)", e)
            false
        }

        fun bind(maxRes: Boolean, ultraHdr: Boolean): Camera {
            Log.d("NightPreview", "setupPhotoSession() bind(maxRes=$maxRes ultraHdr=$ultraHdr) START thread=${Thread.currentThread().name}")
            return try {
                val selectorBuilder = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (maxRes) {
                    selectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                }
                val captureBuilder = ImageCapture.Builder()
                    .setResolutionSelector(selectorBuilder.build())
                    .setFlashMode(getImageCaptureFlashMode())
                if (ultraHdr) {
                    captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                val capture = captureBuilder.build()
                imageCapture = capture
                // Crop stills to the selected aspect ratio (1:1 / 16:9 / 4:3). CameraX crops
                // OutputFileOptions saves to this and exposes it as cropRect for in-memory shots.
                capture.setCropAspectRatio(currentCropAspectRatio())
                // Cap the analysis stream at ~1.2 MP, independently of [maxRes] (which stays
                // about ImageCapture — stills are unaffected and still come off the sensor at
                // full resolution). Nothing reading this stream benefits from sensor
                // resolution: PhotoAnalyzer samples average luminance, runs a ZXing decode
                // over the whole Y plane, and copies each frame via toBitmap() for the
                // Motion-Photo ring buffer, whose frames MotionPhotoEncoder then converts to
                // I420 in a per-pixel loop. All of that is paid per frame and scales with
                // area, so an uncapped stream made QR scanning and motion capture far more
                // expensive on high-end sensors for no quality gain. Night mode is unaffected:
                // captureNightBurst() shoots full-resolution frames through ImageCapture.
                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960), // ~1.2 MP
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                                )
                            )
                            .build()
                    )
                val analysis = analysisBuilder.build()
                imageAnalysis = analysis
                bindSession(provider, owner, selector, preview, capture, analysis).also {
                    Log.d("NightPreview", "setupPhotoSession() bind SUCCESS res=${it.cameraInfo} zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} max=${it.cameraInfo.zoomState.value?.maxZoomRatio}")
                }
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPhotoSession() bind(maxRes=$maxRes ultra=$ultraHdr) EXCEPTION – root cause of black preview when fallback also fails", e)
                throw e
            }
        }

        // Fallback ladder: UltraHDR+maxres → UltraHDR+default → JPEG+default.
        boundCamera = try {
            bind(maxRes = true, ultraHdr = ultraHdrSupported)
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPhotoSession() Max-res bind failed (was Warn, hidden); retrying at default resolution. This is where resolution becomes lower and cannot take full quality!", e)
            try {
                provider.unbindAll()
            } catch (e2: Exception) {
                Log.e("NightPreview", "setupPhotoSession() unbindAll on fallback failed (hidden)", e2)
            }
            try {
                bind(maxRes = false, ultraHdr = ultraHdrSupported)
            } catch (e2: Exception) {
                Log.e("NightPreview", "setupPhotoSession() default-res ultraHdr=$ultraHdrSupported bind FAILED (was hidden)", e2)
                if (!ultraHdrSupported) throw e2
                Log.e("NightPreview", "setupPhotoSession() Ultra HDR bind failed (was Warn), falling back to plain JPEG – lower quality path")
                try {
                    provider.unbindAll()
                } catch (e3: Exception) {
                    Log.e("NightPreview", "setupPhotoSession() unbindAll on second fallback failed", e3)
                }
                bind(maxRes = false, ultraHdr = false)
            }
        }

        val zs = boundCamera?.cameraInfo?.zoomState?.value
        Log.d("NightPreview", "setupPhotoSession() bound zoomState min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} ratio=${zs?.zoomRatio} thread=${Thread.currentThread().name}")
        boundCamera?.cameraInfo?.zoomState?.value?.let {
            Log.d("NightPreview", "setupPhotoSession() calling updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio} – should show .5,1x,2x,5x if >1x else only 1x")
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            Log.d("NightPreview", "setupNightPreviewSession() updated zoomRatio=${_zoomRatio.value} levels=${_availableZoomLevels.value}")
        }
        readManualControlRanges()
        applyManualControls()
        boundCamera?.cameraInfo?.let { observeNightModeIndicator(it) }
        onSessionBound()
        _photoSessionActive.value = true
        Log.d("NightPreview", "setupPhotoSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution} nightIndicatorSupported=$nightIndicatorSupported")
        true
    } catch (e: Exception) {
        Log.e("NightPreview", "setupPhotoSession() OUTER CATCH – Failed to set up photo session – solid black root? ${e.javaClass.simpleName} ${e.message}", e)
        false
    }
}

/**
 * Binds the CameraX NIGHT extension for the live PREVIEW (plain PHOTO mode):
 * Preview + ImageCapture, and — when the vendor extension reports it supports
 * concurrent analysis via [ExtensionsManager.isImageAnalysisSupported] — an
 * ImageAnalysis stream too, so [PhotoAnalyzer] keeps sampling luminance and
 * night mode can auto-disengage when the scene brightens (otherwise the moon
 * button is the manual exit). Falls back to the normal photo session if the
 * extension isn't available or can't be bound.
 */
suspend fun CameraViewModel.setupNightPreviewSession(): Boolean {
    Log.d("NightPreview", "setupNightPreviewSession() ENTRY thread=${Thread.currentThread().name} lens=${_lensFacing.value} surfaceBefore=${_surfaceRequest.value?.resolution}")
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        Log.d("NightPreview", "setupNightPreviewSession() got cameraProvider=$provider")
        val mgr = getExtensionsManager(provider)
        Log.d("NightPreview", "setupNightPreviewSession() ExtensionsManager=${mgr != null} cacheExt=${extensionsManager != null}")
        if (mgr == null) {
            Log.w("NightPreview", "setupNightPreviewSession() manager NULL, falling back to normal photo session")
            return setupPhotoSession()
        }
        val baseSelector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()
        val extAvail = try {
            mgr.isExtensionAvailable(baseSelector, ExtensionMode.NIGHT)
        } catch (e: Exception) {
            Log.e("NightPreview", "setupNightPreviewSession() isExtensionAvailable threw (was hidden)", e)
            false
        }
        Log.d("NightPreview", "setupNightPreviewSession() isExtensionAvailable(NIGHT)=$extAvail lens=${_lensFacing.value}")
        if (!extAvail) {
            Log.w("NightPreview", "setupNightPreviewSession() extension NOT available on lens=${_lensFacing.value}, falling back")
            return setupPhotoSession()
        }
        Log.d("NightPreview", "setupNightPreviewSession() unbinding all before night selector")
        try {
            provider.unbindAll()
            Log.d("NightPreview", "setupNightPreviewSession() provider.unbindAll SUCCESS")
        } catch (e: Exception) {
            Log.e("NightPreview", "setupNightPreviewSession() unbindAll FAILED (was hidden)", e)
            throw e
        }
        val analysisSupported = try {
            mgr.isImageAnalysisSupported(baseSelector, ExtensionMode.NIGHT)
        } catch (e: Exception) {
            Log.e("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported query FAILED", e)
            false
        }
        Log.d("NightPreview", "setupNightPreviewSession() isImageAnalysisSupported=$analysisSupported")

        // Proven-on-stock path: getExtensionEnabledCameraSelector + bindToLifecycle. It's
        // deprecated in 1.7.0-alpha02, but it's what actually binds on devices where the vendor
        // NIGHT extender works. On GrapheneOS/Pixel it throws "Framework size list map ...", but
        // we never get here there: isNightExtensionAvailable() probes first and hides the mode.
        val nightSelector = mgr.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.NIGHT)

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            Log.d("NightPreview", "setupNightPreviewSession() NEW surfaceRequest emitted res=${request.resolution}")
            _surfaceRequest.value = request
        }

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        val capture = ImageCapture.Builder()
            .setFlashMode(getImageCaptureFlashMode())
            .build()
        imageCapture = capture

        fun bind(withAnalysis: Boolean): Camera {
            Log.d("NightPreview", "setupNightPreviewSession() bind(withAnalysis=$withAnalysis) START")
            return if (withAnalysis) {
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                imageAnalysis = analysis
                bindSession(provider, owner, nightSelector, preview, capture, analysis)
            } else {
                Log.w("NightPreview", "setupNightPreviewSession() binding NIGHT without ImageAnalysis – QR scanning, luminance sampling and Motion Photo are off for this session")
                imageAnalysis = null
                bindSession(provider, owner, nightSelector, preview, capture)
            }
        }

        boundCamera = try {
            bind(withAnalysis = analysisSupported)
        } catch (e: Exception) {
            Log.w("NightPreview", "setupNightPreviewSession() bind FAILED withAnalysis=$analysisSupported: ${e.javaClass.simpleName} ${e.message}", e)
            if (!analysisSupported) throw e
            try { provider.unbindAll() } catch (_: Exception) {}
            bind(withAnalysis = false)
        }

        val zs = boundCamera?.cameraInfo?.zoomState?.value
        Log.d("NightPreview", "setupNightPreviewSession() boundCamera zoomState min=${zs?.minZoomRatio} max=${zs?.maxZoomRatio} current=${zs?.zoomRatio} – vendor NIGHT extension often reports 1x-only; this explains zoom bar disappearing (only 1x). Full-res capture is still max-res? No, extension uses default resolution, lower than max-res photo session, cannot take full quality while in extension preview")
        boundCamera?.cameraInfo?.zoomState?.value?.let {
            Log.d("NightPreview", "setupNightPreviewSession() calling updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            Log.d("NightPreview", "setupPanoramaSession() levels=${_availableZoomLevels.value}")
        }
        // Do NOT observe getNightModeIndicator() on the extension camera: it reports
        // UNKNOWN/NOT_RECOMMENDED there, which fights the normal session's RECOMMENDED reading
        // and flips _lowLightDetected → an engage/disengage toggle loop. Night stays engaged
        // (frozen at the value the normal session detected) until the moon button turns it off.
        // We do watch the extension camera's state to catch async ExtensionCaptureSession
        // failures, and its strength for the UI indicator.
        boundCamera?.cameraInfo?.let {
            observeExtensionStrength(it)
            observeExtensionCameraState(it)
        }
        onSessionBound()
        _nightPreviewActive.value = true
        _photoSessionActive.value = true
        Log.d("NightPreview", "setupNightPreviewSession() SUCCESS – nightPreviewActive=true photoSessionActive=true surfaceRequest=${_surfaceRequest.value?.resolution}")
        true
    } catch (e: Exception) {
        Log.e("NightPreview", "setupNightPreviewSession() OUTER CATCH – FAILED to set up night preview, falling back to normal photo session. Root cause of solid black: exception=${e.javaClass.simpleName} msg=${e.message}", e)
        _nightPreviewActive.value = false
        // The extension genuinely can't bind here (e.g. GrapheneOS/Pixel). Remember it so night
        // isn't offered again for a week; flipping nightExtensionUsable false also makes the UI
        // drop useNightPreview immediately, so we don't loop back into this failure.
        recordNightExtensionFailure()
        val fallback = try {
            setupPhotoSession()
        } catch (e2: Exception) {
            Log.e("NightPreview", "setupNightPreviewSession() fallback setupPhotoSession() ALSO FAILED (double hidden)", e2)
            false
        }
        Log.d("NightPreview", "setupNightPreviewSession() fallback result=$fallback")
        fallback
    }
}

/**
 * Binds a lean Preview + capped-resolution ImageAnalysis session for the
 * panorama and photo-sphere modes. These sweep off the analysis stream and
 * never use ImageCapture. The analysis stream is capped at ~3 MP: the pano
 * analyzer decodes every delivered frame to a Bitmap, so an uncapped max-res
 * stream janks the preview, while the 8 MP compose canvas means higher
 * per-frame resolution barely affects the stitched output.
 */
suspend fun CameraViewModel.setupPanoramaSession(): Boolean {
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()

        val previewBuilder = Preview.Builder()
        val preview = previewBuilder.build()
        preview.setSurfaceProvider { request -> _surfaceRequest.value = request }

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        // Cap the analysis stream at ~3 MP. The compose canvas is bounded to
        // 8 MP, so per-frame resolution beyond a few MP adds little to the
        // stitched output — but the analyzer converts every delivered frame to
        // a Bitmap, so a max-res stream makes that conversion (and its GC
        // churn) heavy enough to jank the preview during the sweep. ~3 MP keeps
        // it smooth. Falls back to the device-default analysis resolution if
        // this bound can't bind.
        fun bind(capped: Boolean): Camera {
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            if (capped) {
                analysisBuilder.setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(2016, 1512), // ~3 MP
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        )
                        .build()
                )
            }
            val analysis = analysisBuilder.build()
            imageAnalysis = analysis
            imageCapture = null // No ImageCapture in this session.
            return bindSession(provider, owner, selector, preview, analysis)
        }

        boundCamera = try {
            Log.d("NightPreview", "setupPanoramaSession() bind capped=true START")
            bind(capped = true)
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPanoramaSession() Capped panorama bind FAILED (was hidden as Warn), retrying at default – resolution lower!", e)
            try {
                provider.unbindAll()
                Log.d("NightPreview", "setupPanoramaSession() fallback unbindAll SUCCESS")
            } catch (e2: Exception) {
                Log.e("NightPreview", "setupPanoramaSession() fallback unbindAll FAILED (swallowed)", e2)
            }
            try {
                bind(capped = false)
            } catch (e2: Exception) {
                Log.e("NightPreview", "setupPanoramaSession() default bind ALSO FAILED – black root ${e2.javaClass.simpleName} ${e2.message}", e2)
                throw e2
            }
        }

        val zsP = boundCamera?.cameraInfo?.zoomState?.value
        Log.d("NightPreview", "setupPanoramaSession() bound zoom min=${zsP?.minZoomRatio} max=${zsP?.maxZoomRatio} ratio=${zsP?.zoomRatio}")
        boundCamera?.cameraInfo?.zoomState?.value?.let {
            Log.d("NightPreview", "setupPanoramaSession() updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
            Log.d("NightPreview", "setupPortraitSession() after update levels=${_availableZoomLevels.value} ratio=${_zoomRatio.value}")
        }
        onSessionBound()
        _photoSessionActive.value = true
        Log.d("NightPreview", "setupPanoramaSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution}")
        true
    } catch (e: Exception) {
        Log.e("NightPreview", "setupPanoramaSession() OUTER CATCH – Failed solid black? ${e.javaClass.simpleName} ${e.message}", e)
        false
    }
}

/**
 * Portrait session: full-resolution ImageCapture (final image stays max-res) but capped
 * ImageAnalysis (~0.8 MP, 1024x768) for smooth preview segmentation. This fixes the
 * "No supported surface combination" bind failures that happened when portrait reused
 * the then-uncapped 3-stream photo path (Preview + max ImageCapture + max ImageAnalysis) for a
 * model that only needs 256x256.
 *
 * Fallback ladder prioritizes keeping max-res capture:
 * capped+max+UHD → capped+max+JPEG → capped+default+UHD → capped+default+JPEG → default+default
 */
@OptIn(ExperimentalCamera2Interop::class)
suspend fun CameraViewModel.setupPortraitSession(): Boolean {
    Log.d("NightPreview", "setupPortraitSession() ENTRY lens=${_lensFacing.value} thread=${Thread.currentThread().name}")
    return try {
        val provider = ProcessCameraProvider.awaitInstance(app)
        cameraProvider = provider
        Log.d("NightPreview", "setupPortraitSession() providerHash=${provider.hashCode()}")
        provider.unbindAll()

        val selector = CameraSelector.Builder()
            .requireLensFacing(_lensFacing.value)
            .build()

        val previewBuilder = Preview.Builder()
        try {
            androidx.camera.camera2.interop.Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(aeSnapshotCallback)
            Log.d("NightPreview", "setupPortraitSession() attached AE snapshot callback")
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPortraitSession() Could not attach AE snapshot callback (was Warn)", e)
        }
        val preview = previewBuilder.build()
        preview.setSurfaceProvider { request ->
            Log.d("NightPreview", "setupPortraitSession() surfaceRequest res=${request.resolution} thread=${Thread.currentThread().name}")
            _surfaceRequest.value = request
        }

        val owner = ManualLifecycleOwner()
        owner.start()
        sessionLifecycleOwner = owner

        val ultraHdrSupported = try {
            val cameraInfo = provider.getCameraInfo(selector)
            val sup = ImageCapture.getImageCaptureCapabilities(cameraInfo).supportedOutputFormats.contains(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
            Log.d("NightPreview", "setupPortraitSession() ultraHdrSupported=$sup")
            sup
        } catch (e: Exception) {
            Log.e("NightPreview", "setupPortraitSession() Could not query Ultra HDR support (hidden)", e)
            false
        }

        fun bind(
            cappedAnalysis: Boolean,
            maxResCapture: Boolean,
            ultraHdr: Boolean
        ): Camera {
            Log.d("NightPreview", "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr) START")
            return try {
                // Capture: always try max-res first to keep final image full-res.
                val captureSelectorBuilder = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                if (maxResCapture) {
                    captureSelectorBuilder.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                }
                val captureBuilder = ImageCapture.Builder()
                    .setResolutionSelector(captureSelectorBuilder.build())
                    .setFlashMode(getImageCaptureFlashMode())
                if (ultraHdr) {
                    captureBuilder.setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR)
                }
                val capture = captureBuilder.build()
                imageCapture = capture
                // Crop stills to the selected aspect ratio (1:1 / 16:9 / 4:3). CameraX crops
                // OutputFileOptions saves to this and exposes it as cropRect for in-memory shots.
                capture.setCropAspectRatio(currentCropAspectRatio())

                val analysisBuilder = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                if (cappedAnalysis) {
                    analysisBuilder.setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1024, 768), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                    )
                } else if (maxResCapture) {
                    analysisBuilder.setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
                    )
                }
                val analysis = analysisBuilder.build()
                imageAnalysis = analysis
                bindSession(provider, owner, selector, preview, capture, analysis).also {
                    Log.d("NightPreview", "setupPortraitSession() bind SUCCESS capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr zoom min=${it.cameraInfo.zoomState.value?.minZoomRatio} max=${it.cameraInfo.zoomState.value?.maxZoomRatio}")
                }
            } catch (e: Exception) {
                Log.e("NightPreview", "setupPortraitSession() bind(capped=$cappedAnalysis maxRes=$maxResCapture ultra=$ultraHdr) FAILED – swallowed before! ${e.javaClass.simpleName} ${e.message}", e)
                throw e
            }
        }

        // Build attempt ladder; keep max-res capture attempts first per user request.
        data class Attempt(val capped: Boolean, val maxRes: Boolean, val ultra: Boolean)
        val attempts = mutableListOf<Attempt>()
        if (ultraHdrSupported) {
            attempts.add(Attempt(true, true, true))
            attempts.add(Attempt(true, true, false))
            attempts.add(Attempt(true, false, true))
            attempts.add(Attempt(true, false, false))
            attempts.add(Attempt(false, false, true))
            attempts.add(Attempt(false, false, false))
        } else {
            attempts.add(Attempt(true, true, false))
            attempts.add(Attempt(true, false, false))
            attempts.add(Attempt(false, false, false))
        }

        var bound: Camera? = null
        var lastError: Exception? = null
        for ((capped, maxRes, ultra) in attempts) {
            try {
                if (bound != null) {
                    Log.d("NightPreview", "setupPortraitSession() unbindAll before attempt capped=$capped maxRes=$maxRes ultra=$ultra")
                    provider.unbindAll()
                }
                bound = bind(capped, maxRes, ultra)
                Log.d("NightPreview", "setupPortraitSession() bind ladder SUCCESS capped=$capped maxRes=$maxRes ultra=$ultra zoom min=${bound.cameraInfo.zoomState.value?.minZoomRatio} max=${bound.cameraInfo.zoomState.value?.maxZoomRatio}")
                break
            } catch (e: Exception) {
                lastError = e
                Log.e("NightPreview", "setupPortraitSession() Portrait bind failed (capped=$capped maxRes=$maxRes ultra=$ultra) – was Warn with swallowed stack, root of black? ${e.javaClass.simpleName} msg=${e.message}", e)
                try {
                    provider.unbindAll()
                } catch (e2: Exception) {
                    Log.e("NightPreview", "setupPortraitSession() unbindAll in catch FAILED (hidden)", e2)
                }
            }
        }
        if (bound == null) Log.e("NightPreview", "setupPortraitSession() ALL attempts FAILED! lastError=${lastError?.javaClass?.simpleName} ${lastError?.message} – produces black preview?", lastError ?: Exception("none"))
        boundCamera = bound ?: throw (lastError ?: IllegalStateException("Portrait session bind failed"))

        val zsPor = boundCamera?.cameraInfo?.zoomState?.value
        Log.d("NightPreview", "setupPortraitSession() final zoom min=${zsPor?.minZoomRatio} max=${zsPor?.maxZoomRatio} ratio=${zsPor?.zoomRatio} – if max=1, zoom bar will show only 1x")
        boundCamera?.cameraInfo?.zoomState?.value?.let {
            Log.d("NightPreview", "setupPortraitSession() updateZoomLevels min=${it.minZoomRatio} max=${it.maxZoomRatio}")
            updateZoomLevels(it.minZoomRatio, it.maxZoomRatio)
            restoreZoom(it.minZoomRatio, it.maxZoomRatio)
        }
        _sloMoSupported.value = true
        readManualControlRanges()
        applyManualControls()
        onSessionBound()
        _photoSessionActive.value = true
        Log.d("NightPreview", "setupPortraitSession() SUCCESS photoActive=true surface=${_surfaceRequest.value?.resolution}")
        true
    } catch (e: Exception) {
        Log.e("NightPreview", "setupPortraitSession() OUTER CATCH – Failed black root? ${e.javaClass.simpleName} ${e.message}", e)
        false
    }
}
