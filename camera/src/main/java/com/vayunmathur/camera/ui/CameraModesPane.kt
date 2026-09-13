package com.vayunmathur.camera.ui

import androidx.compose.runtime.Composable
import com.vayunmathur.camera.util.CameraMode

@Composable
internal fun CameraModesPane(
    cameraMode: CameraMode,
    isPhotoType: Boolean,
    sloMoSupported: Boolean?,
    onModeSelected: (CameraMode) -> Unit,
    iconRotation: Float,
    onPickerChanged: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
) {
    ModeSelector(
        cameraMode = cameraMode,
        isPhotoType = isPhotoType,
        sloMoSupported = sloMoSupported,
        onModeSelected = onModeSelected
    )

    BottomBar(
        cameraMode = cameraMode,
        isPhotoType = isPhotoType,
        iconRotation = iconRotation,
        onPickerChanged = onPickerChanged,
        onSettingsClick = onSettingsClick
    )
}
