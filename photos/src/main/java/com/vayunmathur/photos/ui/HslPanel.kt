package com.vayunmathur.photos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.FilterChipDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.photos.data.HslAdjustments
import com.vayunmathur.photos.data.HslChannelAdjustment
import com.vayunmathur.photos.data.HslColorRange
import kotlin.math.roundToInt

@Composable
fun HslPanel(
    hsl: HslAdjustments,
    selectedRange: HslColorRange,
    onRangeSelected: (HslColorRange) -> Unit,
    onHslChanged: (HslAdjustments) -> Unit,
) {
    val current = hsl.channels[selectedRange] ?: HslChannelAdjustment()
    val chipColor = when (selectedRange) {
        HslColorRange.Red -> Color.Red
        HslColorRange.Orange -> Color(0xFFFF8C00)
        HslColorRange.Yellow -> Color.Yellow
        HslColorRange.Green -> Color.Green
        HslColorRange.Cyan -> Color.Cyan
        HslColorRange.Blue -> Color.Blue
        HslColorRange.Purple -> Color(0xFF8B00FF)
        HslColorRange.Magenta -> Color.Magenta
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            HslColorRange.entries.forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = { onRangeSelected(range) },
                    label = { Text(stringResource(range.labelRes), fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = chipColor.copy(alpha = 0.3f),
                    ),
                )
            }
        }

        HslSlider("Hue", current.hue, -180f, 180f) { value ->
            onHslChanged(hsl.copy(channels = hsl.channels + (selectedRange to current.copy(hue = value))))
        }
        HslSlider("Saturation", current.saturation, -100f, 100f) { value ->
            onHslChanged(hsl.copy(channels = hsl.channels + (selectedRange to current.copy(saturation = value))))
        }
        HslSlider("Luminance", current.luminance, -100f, 100f) { value ->
            onHslChanged(hsl.copy(channels = hsl.channels + (selectedRange to current.copy(luminance = value))))
        }
    }
}

@Composable
private fun HslSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(72.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${value.roundToInt()}",
            fontSize = 12.sp,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
