package com.vayunmathur.weather.ui.components.blocks

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.IconWind
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.R
import com.vayunmathur.weather.network.Current
import com.vayunmathur.weather.util.WindUnit
import com.vayunmathur.weather.util.compassDirection
import com.vayunmathur.weather.util.formatWind
import androidx.compose.ui.res.stringResource

/**
 * Port of WeatherMaster's `WindBlock`. Circular surface with a big arrow
 * drawable rotated by the wind direction degrees behind the value. Big
 * value + unit centered, "From X" direction bottom-center.
 */
@Composable
fun WindBlock(current: Current, unit: WindUnit) {
    val degrees = current.windDirection.toFloat()
    CircularStatBlock {
        Image(
            painter = painterResource(R.drawable.weather_wind_arrow_dominant),
            contentDescription = null,
            modifier = Modifier.matchParentSize().rotate(degrees),
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.inversePrimary),
        )
        Box(Modifier.align(Alignment.TopCenter)) {
            BlockHeader(
                icon = { m, c -> IconWind(m, c) },
                title = "Wind",
                topPadding = 36.dp,
            )
        }
        val windText = formatWind(current.windSpeed, unit)
        Row(
            modifier = Modifier.align(Alignment.Center).offset(y = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = windText.substringBefore(' '),
                style = MaterialTheme.typography.displayMedium,
                modifier = Modifier.alignByBaseline(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                text = windText.substringAfter(' '),
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.from_gusts, compassDirection(current.windDirection), formatWind(current.windGusts, unit)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
                .padding(horizontal = 16.dp),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
