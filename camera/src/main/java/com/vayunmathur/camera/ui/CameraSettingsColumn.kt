package com.vayunmathur.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.camera.R
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.library.ui.IconBlur
import com.vayunmathur.library.ui.IconContrast
import com.vayunmathur.library.ui.IconIso
import com.vayunmathur.library.ui.IconLightbulb
import com.vayunmathur.library.ui.IconSunny

@Composable
internal fun CameraSettingsColumn(
    activeSetting: CameraSetting?,
    zoomRatio: Float,
    availableZoomLevels: List<Pair<String, Float>>,
    onZoomSelected: (Float) -> Unit,
    exposureComp: Float,
    onExposureComp: (Float) -> Unit,
    shadows: Float,
    onShadows: (Float) -> Unit,
    warmth: Float,
    onWarmth: (Float) -> Unit,
    exposureTimeIndex: Int,
    onExposureTimeIndex: (Int) -> Unit,
    blurStrength: Float,
    onBlurStrength: (Float) -> Unit,
    manualIsoIndex: Int,
    isoStops: List<Int>,
    onManualIsoIndex: (Int) -> Unit,
    cameraMode: CameraMode,
    onSelectSetting: (CameraSetting?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (activeSetting) {
            null -> ZoomBar(
                currentZoom = zoomRatio,
                zoomLevels = availableZoomLevels,
                onZoomSelected = onZoomSelected
            )
            CameraSetting.BRIGHTNESS -> HorizontalSettingSlider(
                value = exposureComp,
                onValueChange = onExposureComp,
                icon = { m, c -> IconSunny(m, c) },
                label = stringResource(com.vayunmathur.camera.R.string.brightness)
            )
            CameraSetting.SHADOWS -> HorizontalSettingSlider(
                value = shadows,
                onValueChange = onShadows,
                icon = { m, c -> IconContrast(m, c) },
                label = stringResource(com.vayunmathur.camera.R.string.shadows)
            )
            CameraSetting.WARMTH -> HorizontalSettingSlider(
                value = warmth,
                onValueChange = onWarmth,
                icon = { m, c -> IconLightbulb(m, c) },
                label = stringResource(com.vayunmathur.camera.R.string.warmth)
            )
            CameraSetting.EXPOSURE_TIME -> ExposureTimeBar(
                selectedIndex = exposureTimeIndex,
                onIndexChange = onExposureTimeIndex
            )
            CameraSetting.PORTRAIT_BLUR -> HorizontalSettingSlider(
                value = blurStrength,
                onValueChange = onBlurStrength,
                icon = { m, c -> IconBlur(m, c) },
                label = stringResource(R.string.blur),
                valueRange = 0f..1f,
                displayValue = { "%.0f%%".format(it * 100) }
            )
            CameraSetting.ISO -> IsoBar(
                selectedIndex = manualIsoIndex,
                isoStops = isoStops,
                onIndexChange = onManualIsoIndex
            )
        }

        SettingsButtonRow(
            activeSetting = activeSetting,
            cameraMode = cameraMode,
            onSelect = onSelectSetting
        )
    }
}
