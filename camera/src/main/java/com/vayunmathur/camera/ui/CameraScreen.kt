// RAW ANIMATION EXCEPTION: the panorama capture flash is imperative - an Animatable snapped to
// white as each guide dot is captured and released back to zero, with no declarative target to
// animate towards; the shutter and record-button springs are tuned to this viewfinder's own feel.
package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.FlashMode
import com.vayunmathur.camera.util.TimerDuration
import com.vayunmathur.camera.util.capturePhotoForResult
import com.vayunmathur.camera.util.setFlashMode
import com.vayunmathur.camera.util.setQrResult
import com.vayunmathur.camera.util.setTimerDuration
import com.vayunmathur.camera.util.startPanorama
import com.vayunmathur.camera.util.startPhotosphere
import com.vayunmathur.camera.util.stopPanorama
import com.vayunmathur.camera.util.stopPhotosphere
import com.vayunmathur.camera.util.takePhoto
import com.vayunmathur.camera.util.toggleGrid
import com.vayunmathur.camera.util.toggleHighSpeedRecording
import com.vayunmathur.camera.util.toggleLevel
import com.vayunmathur.camera.util.toggleMicMuted
import com.vayunmathur.camera.util.toggleRecording
import com.vayunmathur.camera.util.toggleTorch
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.util.NavBackStack

@Composable
fun CameraScreen(
    backStack: NavBackStack<Route>,
    viewModel: CameraViewModel,
    onCaptureResult: ((Bitmap?) -> Unit)? = null
) {
    val view = LocalView.current
    val state = rememberCameraScreenState(viewModel)

    // The shutter action, shared by the on-screen button and the volume-key (hardware) shutter.
    val performCapture: () -> Unit = {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        if (state.longExposureProgress <= 0f) when {
            state.cameraMode == CameraMode.PHOTOSPHERE -> {
                if (state.panoSweeping) viewModel.stopPhotosphere() else viewModel.startPhotosphere()
            }
            state.cameraMode == CameraMode.PANORAMA -> {
                if (state.panoSweeping) viewModel.stopPanorama() else viewModel.startPanorama()
            }
            state.isSloMo && state.highSpeedActive -> viewModel.toggleHighSpeedRecording()
            state.isPhotoType -> {
                if (onCaptureResult != null) {
                    viewModel.capturePhotoForResult(
                        onSaved = { thumbnail -> onCaptureResult(thumbnail) },
                        onError = { /* stay in camera so the user can retry */ }
                    )
                } else {
                    viewModel.takePhoto()
                }
            }
            else -> viewModel.toggleRecording()
        }
    }
    val currentCapture by rememberUpdatedState(performCapture)

    CameraScreenEffects(state = state, viewModel = viewModel, onCapture = { currentCapture() })

    // Tracks physical orientation purely to rotate the on-screen control icons.
    val animatedRotation = animatedFloat(
        target = state.deviceRotation.toFloat(),
        spec = Motion.over(300)
    )

    // RAW SCAFFOLD EXCEPTION: full-bleed viewfinder with a black surface and its own custom
    // top/bottom control bars (TopBar/ShutterRow/ModeSelector/BottomBar). It has no Material
    // top app bar or title, so AppScaffold would inject an unwanted bar, and TopAppBarOverlay
    // provides neither the black container surface nor the system-inset padding the preview
    // relies on. The bare Scaffold(containerColor) is the correct primitive here.
    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Fractional overlay offsets so pills scale with window height on
            // wide/short desktop windows instead of fixed dp (was 280/60/100dp).
            val qrBottomPadding = maxHeight * 0.3f
            val indicatorTopPadding = maxHeight * 0.07f
            val focusTopPadding = maxHeight * 0.12f
            Column(modifier = Modifier.fillMaxSize()) {
                CameraTopBar(
                    flashMode = state.flashMode,
                    torchEnabled = state.torchEnabled,
                    gridEnabled = state.gridEnabled,
                    levelEnabled = state.levelEnabled,
                    aspectRatio = state.aspectRatio,
                    isPhotoType = state.isPhotoType,
                    isVideoType = state.isVideoType,
                    micMuted = state.micMuted,
                    timerDuration = state.timerDuration,
                    onFlashToggle = {
                        if (state.torchEnabled) {
                            viewModel.toggleTorch()
                        } else {
                            val next = when (state.flashMode) {
                                FlashMode.OFF -> FlashMode.ON
                                FlashMode.ON -> FlashMode.AUTO
                                FlashMode.AUTO -> FlashMode.OFF
                            }
                            viewModel.setFlashMode(next)
                        }
                    },
                    onTorchToggle = { viewModel.toggleTorch() },
                    onGridToggle = { viewModel.toggleGrid() },
                    onLevelToggle = { viewModel.toggleLevel() },
                    onAspectCycle = { viewModel.cycleAspectRatio() },
                    onMicToggle = { viewModel.toggleMicMuted() },
                    onTimerCycle = {
                        val next = when (state.timerDuration) {
                            TimerDuration.NONE -> TimerDuration.THREE
                            TimerDuration.THREE -> TimerDuration.FIVE
                            TimerDuration.FIVE -> TimerDuration.TEN
                            TimerDuration.TEN -> TimerDuration.NONE
                        }
                        viewModel.setTimerDuration(next)
                    },
                    iconRotation = animatedRotation
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    CameraPreviewBox(
                        state = state,
                        viewModel = viewModel,
                        animatedRotation = animatedRotation,
                    )
                }

                CameraBottomControls(
                    state = state,
                    viewModel = viewModel,
                    backStack = backStack,
                    onCaptureResult = onCaptureResult,
                    onCapture = { currentCapture() },
                    animatedRotation = animatedRotation,
                )
            }

            CameraStatusIndicators(
                qrResult = state.qrResult,
                onDismissQr = { viewModel.setQrResult(null) },
                context = LocalContext.current,
                qrBottomPadding = qrBottomPadding,
                isRecording = state.isRecording,
                recordingDuration = state.recordingDuration,
                indicatorTopPadding = indicatorTopPadding,
                burstActive = state.burstActive,
                burstCount = state.burstCount,
                focusLocked = state.focusLocked,
                focusTopPadding = focusTopPadding,
            )
        }
    }
}
