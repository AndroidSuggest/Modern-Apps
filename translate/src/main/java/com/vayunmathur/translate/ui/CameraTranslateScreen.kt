package com.vayunmathur.translate.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFlashOff
import com.vayunmathur.library.ui.IconFlashOn
import com.vayunmathur.library.ui.IconPause
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.PermissionsChecker
import com.vayunmathur.library.ui.Text
import com.vayunmathur.translate.util.Languages
import com.vayunmathur.translate.util.TranslateViewModel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.roundToInt

/** Minimum gap between analyzed frames (ms) — throttles OCR to keep it light. */
private const val ANALYSIS_INTERVAL_MS = 700L

/** One translated line positioned in the (upright) analysed-bitmap's pixel space. */
private data class OverlayBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val text: String,
    val translated: Boolean,
)

@Composable
fun CameraTranslateScreen(viewModel: TranslateViewModel, onBack: () -> Unit) {
    PermissionsChecker(
        permissions = arrayOf(Manifest.permission.CAMERA),
        text = "Grant camera access to translate what you see",
    ) {
        CameraContent(viewModel, onBack)
    }
}

@Composable
private fun CameraContent(viewModel: TranslateViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val translationAvailable by viewModel.translationAvailable.collectAsState()

    val previewView = remember { PreviewView(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    // OCR/translation results, in analysed-bitmap pixel coords.
    var overlays by remember { mutableStateOf<List<OverlayBox>>(emptyList()) }
    var frameW by remember { mutableStateOf(0) }
    var frameH by remember { mutableStateOf(0) }
    var frozenFrame by remember { mutableStateOf<Bitmap?>(null) }

    // Read by the analysis-thread callback; kept fresh via rememberUpdatedState.
    val pausedState = rememberUpdatedState(paused)
    val availableState = rememberUpdatedState(translationAvailable)

    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val inFlight = remember { AtomicBoolean(false) }
    val lastMs = remember { AtomicLong(0L) }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    LaunchedEffect(torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (pausedState.value || inFlight.get() || now - lastMs.get() < ANALYSIS_INTERVAL_MS) {
                proxy.close()
                return@setAnalyzer
            }
            lastMs.set(now)
            inFlight.set(true)
            val bmp = try {
                proxy.toBitmap()
            } catch (t: Throwable) {
                Log.e(TAG, "toBitmap failed", t)
                null
            }
            val rotation = proxy.imageInfo.rotationDegrees
            proxy.close()
            if (bmp == null) {
                inFlight.set(false)
                return@setAnalyzer
            }
            // Heavy work off the analysis thread; OCR/translate suspend internally
            // onto Dispatchers.Default. State writes happen back on the main thread.
            scope.launch {
                try {
                    val upright = rotateBitmap(bmp, rotation)
                    val result = viewModel.ocr.recognizeDetailed(upright)
                    val boxes = result.boxes.map { box ->
                        val translation =
                            if (availableState.value) viewModel.translate(box.text) else null
                        OverlayBox(
                            left = box.left,
                            top = box.top,
                            right = box.right,
                            bottom = box.bottom,
                            text = translation ?: box.text,
                            translated = translation != null,
                        )
                    }
                    frameW = upright.width
                    frameH = upright.height
                    overlays = boxes
                    frozenFrame = upright
                } finally {
                    inFlight.set(false)
                }
            }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Camera bind failed", t)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val viewW = constraints.maxWidth.toFloat()
            val viewH = constraints.maxHeight.toFloat()

            // Live preview, or the frozen last frame while paused.
            val frozen = frozenFrame
            if (paused && frozen != null) {
                Image(
                    bitmap = frozen.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            }

            // Map analysed-bitmap coords -> view coords assuming PreviewView's default
            // FILL_CENTER (center-crop): scale by the larger ratio, then centre.
            if (frameW > 0 && frameH > 0) {
                val scale = max(viewW / frameW, viewH / frameH)
                val dx = (viewW - frameW * scale) / 2f
                val dy = (viewH - frameH * scale) / 2f
                overlays.forEach { ob ->
                    val l = ob.left * scale + dx
                    val t = ob.top * scale + dy
                    val w = (ob.right - ob.left) * scale
                    val h = (ob.bottom - ob.top) * scale
                    if (w <= 0f || h <= 0f) return@forEach
                    val fontSp = (with(density) { h.toDp().value } * 0.5f).coerceIn(9f, 22f)
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(l.roundToInt(), t.roundToInt()) }
                            .size(
                                width = with(density) { w.toDp() },
                                height = with(density) { h.toDp() },
                            )
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xF2000000))
                            .padding(horizontal = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = ob.text,
                            color = Color.White,
                            fontSize = fontSp.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Back button (top-left).
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .background(Color(0x99000000), RoundedCornerShape(24.dp)),
        ) {
            IconBack(tint = Color.White)
        }

        if (!translationAvailable) {
            Text(
                text = "Translation model not installed — showing detected text only",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(Color(0xB3000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // Bottom controls: language pickers + pause/resume + torch.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
                .background(Color(0xB3000000), RoundedCornerShape(24.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LanguagePicker(
                selectedCode = sourceLang,
                options = Languages.SOURCES,
                onSelected = viewModel::setSource,
                modifier = Modifier.width(120.dp),
            )
            IconButton(onClick = { paused = !paused }) {
                if (paused) IconPlay(tint = Color.White) else IconPause(tint = Color.White)
            }
            IconButton(onClick = { torchOn = !torchOn }) {
                if (torchOn) IconFlashOn(tint = Color.White) else IconFlashOff(tint = Color.White)
            }
            LanguagePicker(
                selectedCode = targetLang,
                options = Languages.TARGETS,
                onSelected = viewModel::setTarget,
                modifier = Modifier.width(120.dp),
            )
        }
    }
}

/** Rotate [src] by [degrees] so OCR sees an upright image. Returns [src] if 0°. */
private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
    if (degrees % 360 == 0) return src
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
}

private const val TAG = "CameraTranslate"
