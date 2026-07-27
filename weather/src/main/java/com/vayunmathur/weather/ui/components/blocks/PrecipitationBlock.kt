package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.vayunmathur.weather.R
import com.vayunmathur.weather.util.mmToInches
import java.util.Locale

/**
 * Precipitation amount for the resolved period (today / a selected day's
 * total, or a selected hour's amount) plus an optional short-range nowcast
 * subtitle derived from the 15-minute series. Square `extraLarge` surface to
 * match the other blocks.
 */
@Composable
fun PrecipitationBlock(
    amountMm: Double?,
    useInches: Boolean,
    nowcast: String?,
) {
    val (value, unit) = if (useInches) {
        val inches = mmToInches(amountMm ?: 0.0)
        String.format(Locale.US, "%.2f", inches) to "in"
    } else {
        val mm = amountMm ?: 0.0
        (if (mm < 10) String.format(Locale.US, "%.1f", mm) else mm.toInt().toString()) to "mm"
    }

    SquareBlock {
        Box(Modifier.align(Alignment.TopStart)) {
            BlockHeader(icon = { m, c -> IconRain(m, c) }, title = stringResource(R.string.metric_precipitation))
        }
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = unit,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (nowcast != null) {
            Text(
                text = nowcast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 14.dp),
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
