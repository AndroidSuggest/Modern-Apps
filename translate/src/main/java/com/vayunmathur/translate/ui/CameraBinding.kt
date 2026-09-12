@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.util.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.load
import kotlin.concurrent.atomics.store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import android.graphics.Rect

/** CameraX binding: preview + analysis tied to one field of view, with OCR fill. */
@Composable
internal fun CameraBinding(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    scope: CoroutineScope,
    viewModel: TranslateViewModel,
    previewView: PreviewView,
    analysis: ImageAnalysis,
    analysisExecutor: ExecutorService,
    inFlight: AtomicBoolean,
    lastMs: AtomicLong,
    pausedValue: () -> Boolean,
    viewSize: IntSize,
    boundRotation: Int,
    onRotationChange: (Int) -> Unit,
    frameW: MutableState<Int>,
    frameH: MutableState<Int>,
    overlays: MutableState<List<OverlayBox>>,
    frozenFrame: MutableState<Bitmap?>,
    camera: MutableState<Camera?>,
    provider: MutableState<androidx.camera.lifecycle.ProcessCameraProvider?>,
) {
    // `Context.getDisplay()` throws on a non-visual context, hence the guard.
    fun displayRotation(): Int {
        val display = previewView.display ?: runCatching { context.display }.getOrNull()
        return display?.rotation ?: Surface.ROTATION_0
    }

    // The activity handles `orientation` itself (see AndroidManifest configChanges), so
    // nothing recreates on rotation. Without this the analyser keeps reporting the
    // rotation from bind time and every box lands 90° out of place.
    DisposableEffect(analysis) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) {
                    // Rebind rather than just retarget the analyser: the ViewPort is
                    // built for a rotation, so retargeting alone would leave the two
                    // streams disagreeing about what they are looking at again.
                    onRotationChange(displayRotation())
                }
            }
        }
        displayManager?.registerDisplayListener(listener, null)
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    // Release the camera when the screen goes away. Without this the torch stays lit,
    // the camera indicator stays up, and frames keep being handed to a dead executor.
    DisposableEffect(Unit) {
        onDispose {
            camera.value?.cameraControl?.enableTorch(false)
            analysis.clearAnalyzer()
            provider.value?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(viewSize, boundRotation) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        provider.value = cameraProvider
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (pausedValue() || inFlight.load() || now - lastMs.load() < ANALYSIS_INTERVAL_MS) {
                proxy.close()
                return@setAnalyzer
            }
            inFlight.store(true)
            val bmp = try {
                proxy.toBitmap()
            } catch (t: Throwable) {
                Log.e(TAG, "toBitmap failed", t)
                null
            }
            val rotation = proxy.imageInfo.rotationDegrees
            // The ViewPort's share of this buffer: exactly what the preview is showing.
            // Copied because the proxy's own Rect is recycled with it.
            val crop = Rect(proxy.cropRect)
            proxy.close()
            if (bmp == null) {
                inFlight.store(false)
                lastMs.store(System.currentTimeMillis())
                return@setAnalyzer
            }
            // Only the state writes belong on the main thread; the rotate is a
            // full-frame copy and OCR suspends onto Dispatchers.Default itself.
            scope.launch {
                try {
                    // Cropping to the viewport before OCR means the boxes come back in
                    // the coordinates of the region the user can actually see, and OCR
                    // stops spending its time on the strips either side that the preview
                    // never shows.
                    val upright = withContext(Dispatchers.Default) {
                        cropAndRotate(bmp, crop, rotation).also { if (it !== bmp) bmp.recycle() }
                    }
                    val result = viewModel.ocr.recognizeDetailed(upright)
                    frameW.value = upright.width
                    frameH.value = upright.height
                    overlays.value = result.boxes.map { box ->
                        OverlayBox(box.corners.map { Offset(it.x, it.y) }, box.text)
                    }
                    frozenFrame.value = upright
                } catch (t: Throwable) {
                    Log.e(TAG, "Frame analysis failed", t)
                } finally {
                    // Measure the gap from the *end* of the pass, so a slow OCR run
                    // doesn't immediately trigger the next one.
                    lastMs.store(System.currentTimeMillis())
                    inFlight.store(false)
                }
            }
        }

        try {
            cameraProvider.unbindAll()
            // A ViewPort is what makes the preview and the analysis stream agree on a
            // field of view. Bound as two loose use cases they each got their own crop
            // rect off a different resolution (1600×1200 vs 1280×960) — which is why the
            // frozen still was framed differently from the live preview, and why every
            // OCR box landed somewhere the text wasn't. With one, CameraX crops both to
            // the same region and hands us that region as `ImageProxy.cropRect`.
            val viewPort = ViewPort
                .Builder(Rational(viewSize.width, viewSize.height), boundRotation)
                .setScaleType(ViewPort.FILL_CENTER) // matches PreviewView's scale type
                .build()
            camera.value = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build(),
            )
            // Safe (and required) only after binding — setting it on the builder would
            // change the frame [ANALYSIS_SIZE] is resolved in.
            analysis.targetRotation = boundRotation
        } catch (t: Throwable) {
            Log.e(TAG, "Camera bind failed", t)
        }
    }
}

/** Builds the shared ImageAnalysis use case. */
@Composable
internal fun rememberCameraAnalysis(): ImageAnalysis {
    val context = LocalContext.current
    return remember {
        // Built once so the display-rotation listener and the dispose block can reach it.
        // Target rotation is pinned to ROTATION_0 here purely so [ANALYSIS_SIZE] is read in
        // sensor coords; the real rotation is applied after binding (see below).
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetRotation(Surface.ROTATION_0)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        )
                    )
                    .build()
            )
            .build()
    }
}

/** One background translator, decoupled from the OCR loop. */
@Composable
internal fun TranslationLoop(
    viewModel: TranslateViewModel,
    sourceLang: String,
    targetLang: String,
    translationAvailable: Boolean,
    overlays: List<OverlayBox>,
    onTranslations: (Map<String, String>) -> Unit,
) {
    // NLLB takes on the order of a second per line, so translating every line of every
    // frame inline (as this screen used to) meant the overlay updated once every several
    // seconds, always against a frame the camera had long since moved off. Instead we
    // memo per source line: repeat lines are free, and OCR keeps redrawing boxes at full
    // rate. Keyed on the source too: switching it must drop the memo, or lines keep
    // showing translations made from the previous source language.
    LaunchedEffect(sourceLang, targetLang, translationAvailable) {
        onTranslations(emptyMap())
        if (!translationAvailable) return@LaunchedEffect
        var memo = emptyMap<String, String>()
        while (isActive) {
            val next = overlays.firstOrNull { it.text !in memo }
            if (next == null) {
                delay(TRANSLATE_IDLE_POLL_MS)
                continue
            }
            // Memo failures as the source text so a line that can't be translated
            // isn't retried on every frame forever.
            val translated = viewModel.translate(next.text) ?: next.text
            memo = (memo + (next.text to translated)).let { updated ->
                if (updated.size <= TRANSLATION_CACHE_MAX) {
                    updated
                } else {
                    updated.entries.drop(updated.size - TRANSLATION_CACHE_MAX)
                        .associate { it.key to it.value }
                }
            }
            onTranslations(memo)
        }
    }
}
