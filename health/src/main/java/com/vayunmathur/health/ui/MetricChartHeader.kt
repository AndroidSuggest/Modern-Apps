package com.vayunmathur.health.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.library.ui.IconBedtime
import com.vayunmathur.library.ui.IconBodySystem
import com.vayunmathur.library.ui.IconDirectionsWalk
import com.vayunmathur.library.ui.IconFavorite
import com.vayunmathur.library.ui.IconFire
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.ui.text.font.FontWeight

@Composable
internal fun ChartHeader(
    config: HealthMetricConfig,
    avgString: String,
    unitLabel: String,
) {
    val accent = colorFor(config)
    val icon = iconFor(config)
    Row(verticalAlignment = Alignment.Bottom) {
        Box(
            modifier = Modifier
                .padding(end = 12.dp, bottom = 10.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            icon(Modifier.size(20.dp), accent)
        }
        Text(
            text = avgString,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Light,
            color = accent,
        )
        Text(
            text = unitLabel,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 12.dp, start = 4.dp),
            color = LocalContentColor.current.copy(alpha = 0.6f)
        )
    }
}

private fun iconFor(config: HealthMetricConfig): @Composable (Modifier, Color) -> Unit = when (config.recordType) {
    RecordType.Steps, RecordType.Distance, RecordType.Floors, RecordType.Elevation,
    RecordType.Wheelchair, RecordType.Exercise -> { m, c -> IconDirectionsWalk(m, c) }
    RecordType.CaloriesActive, RecordType.CaloriesTotal, RecordType.CaloriesBasal -> { m, c -> IconFire(m, c) }
    RecordType.HeartRate, RecordType.RestingHeartRate, RecordType.HeartRateVariabilityRmssd,
    RecordType.RespiratoryRate, RecordType.OxygenSaturation, RecordType.BloodPressure,
    RecordType.BloodGlucose, RecordType.Vo2Max, RecordType.SkinTemperature -> { m, c -> IconFavorite(m, c) }
    RecordType.Weight, RecordType.Height, RecordType.BodyFat, RecordType.LeanBodyMass,
    RecordType.BoneMass, RecordType.BodyWaterMass -> { m, c -> IconBodySystem(m, c) }
    RecordType.Sleep, RecordType.Mindfulness -> { m, c -> IconBedtime(m, c) }
    RecordType.Hydration -> { m, c -> IconBedtime(m, c) }
    RecordType.Nutrition -> { m, c -> IconFire(m, c) }
}
