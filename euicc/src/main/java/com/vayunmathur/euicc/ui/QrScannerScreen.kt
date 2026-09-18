package com.vayunmathur.euicc.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.annotation.OptIn as AndroidOptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.vayunmathur.euicc.R
import com.vayunmathur.euicc.Route
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.IconQrCode
import com.vayunmathur.library.ui.PermissionWall
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.openAppSettings
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.util.NavBackStack
import java.util.concurrent.Executors

/**
 * Camera viewfinder for an eSIM activation code QR.
 *
 * Granted camera goes straight to the full-bleed preview below; denied camera gets the
 * shared [PermissionWall] so the layout matches every other gated screen. The preview
 * itself is a raw `Box` rather than a scaffold body — a viewfinder has no heading or
 * footer of its own, and the cancel action overlays it the way the platform LPA does.
 */
// RAW SCAFFOLD EXCEPTION: full-bleed camera viewfinder; AppScaffold hosts the bar only.
@Composable
fun QrScannerScreen(backStack: NavBackStack<Route>, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val messenger = rememberMessenger()
    val deniedMessage = stringResource(R.string.camera_permission_required)
    val requestCamera = rememberPermissionRequest(Manifest.permission.CAMERA) { granted ->
        hasPermission = granted
        if (!granted) messenger.show(deniedMessage)
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) requestCamera()
    }

    AppScaffold(
        title = stringResource(R.string.scan_qr_title),
        onNavigateBack = { backStack.pop() },
        scrollBehavior = appBarScrollBehavior(),
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            if (hasPermission) {
                CameraScanner(onResult = onResult)
            } else {
                Surface(Modifier.fillMaxSize()) {
                    PermissionWall(
                        title = stringResource(R.string.camera_permission_required),
                        actionLabel = stringResource(R.string.camera_permission_grant),
                        onRequest = requestCamera,
                        rationale = stringResource(R.string.camera_permission_rationale),
                        icon = { IconQrCode() },
                        settingsLabel = stringResource(R.string.camera_permission_settings),
                        onOpenSettings = { openAppSettings(context) },
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().padding(Spacing.xl),
                contentAlignment = Alignment.BottomCenter,
            ) {
                TextButton(onClick = { backStack.pop() }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@Composable
private fun CameraScanner(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var delivered by remember { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analyzer ->
                analyzer.setAnalyzer(executor, QrCodeAnalyzer { text ->
                    if (!delivered && looksLikeActivationCode(text)) {
                        delivered = true
                        ContextCompat.getMainExecutor(context).execute { onResult(text) }
                    }
                })
            }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

/**
 * Whether [code] is worth handing to the native parser. Shared with the manual entry
 * screen so a typed code and a scanned one are held to the same standard.
 */
internal fun looksLikeActivationCode(code: String): Boolean =
    code.startsWith("LPA:", ignoreCase = true) || code.contains('$')

/** ImageAnalysis analyzer that decodes QR codes with ZXing. */
private class QrCodeAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = MultiFormatReader()

    @AndroidOptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val source = PlanarYUVLuminanceSource(
            bytes,
            plane.rowStride,
            imageProxy.height,
            0, 0,
            imageProxy.width,
            imageProxy.height,
            false,
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        try {
            onDecoded(reader.decodeWithState(bitmap).text)
        } catch (_: NotFoundException) {
        } finally {
            reader.reset()
            imageProxy.close()
        }
    }
}
