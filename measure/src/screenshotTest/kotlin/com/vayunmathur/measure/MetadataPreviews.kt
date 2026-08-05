package com.vayunmathur.measure

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.measure.data.model.Anchor
import com.vayunmathur.measure.data.model.MeasurementKind
import com.vayunmathur.measure.data.model.SavedMeasurement
import com.vayunmathur.measure.data.model.TrackingQuality
import com.vayunmathur.measure.ui.ArMeasureActions
import com.vayunmathur.measure.ui.ArMeasureUiState
import com.vayunmathur.measure.ui.CompassActions
import com.vayunmathur.measure.ui.CompassUiState
import com.vayunmathur.measure.ui.LevelActions
import com.vayunmathur.measure.ui.LevelUiState
import com.vayunmathur.measure.ui.RulerUiState
import com.vayunmathur.measure.ui.SavedActions
import com.vayunmathur.measure.ui.SavedUiState
import com.vayunmathur.measure.ui.SettingsActions
import com.vayunmathur.measure.ui.SettingsUiState
import com.vayunmathur.measure.ui.pages.ArMeasureContent
import com.vayunmathur.measure.ui.pages.CompassContent
import com.vayunmathur.measure.ui.pages.LevelContent
import com.vayunmathur.measure.ui.pages.RulerContent
import com.vayunmathur.measure.ui.pages.SavedMeasurementsContent
import com.vayunmathur.measure.ui.pages.SettingsContent

/**
 * Store listing screenshots, rendered from Compose previews rather than an instrumented
 * test. Numbered so the collected image order matches the listing order.
 */

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview1Compass() {
    DynamicTheme {
        CompassContent(
            state = CompassUiState(
                azimuthTrueDeg = 41.0,
                azimuthMagDeg = 54.0,
                declinationDeg = -13.0,
                hasLocation = true,
            ),
            actions = CompassActions.Noop,
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview2Level() {
    DynamicTheme {
        LevelContent(
            state = LevelUiState(pitchDeg = 0.2, rollDeg = -0.1, isFlat = true),
            actions = LevelActions.Noop,
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview3Ruler() {
    DynamicTheme {
        RulerContent(state = RulerUiState(pixelsPerMm = 9.5f))
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview4ArMeasure() {
    DynamicTheme {
        ArMeasureContent(
            state = ArMeasureUiState(
                quality = TrackingQuality.Good,
                hasPlane = true,
                cameraPermissionGranted = true,
                anchors = listOf(
                    Anchor(1, 0.25, 0.62, 0.0, onPlane = true),
                    Anchor(2, 0.72, 0.55, 0.0, onPlane = true),
                ),
                distanceM = 2.436,
            ),
            actions = ArMeasureActions.Noop,
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview5Saved() {
    DynamicTheme {
        SavedMeasurementsContent(
            state = SavedUiState(
                measurements = listOf(
                    SavedMeasurement(1, "Kitchen wall", MeasurementKind.Distance, 3.42, 0L),
                    SavedMeasurement(2, "Rug", MeasurementKind.Area, 4.18, 0L),
                    SavedMeasurement(3, "Desk depth", MeasurementKind.Distance, 0.735, 0L),
                ),
            ),
            actions = SavedActions.Noop,
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
fun Preview6Settings() {
    DynamicTheme {
        SettingsContent(
            state = SettingsUiState(levelCalibrated = true),
            actions = SettingsActions.Noop,
        )
    }
}
