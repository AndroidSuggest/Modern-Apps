package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.graphics.drawable.IconCompat
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.weather.R

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:weather`.
 *
 * Feeds representative [HostTemplate]s to the shared [CarTemplateView] renderer
 * from `:library:carhost`. Shows the saved-locations list and a forecast detail,
 * each row carrying its WMO condition glyph. Numbered after the phone previews.
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "5-car-locations", device = CAR, showSystemUi = false)
    @Composable
    fun Preview5CarLocations() {
        val ctx = LocalContext.current
        fun icon(res: Int) = IconCompat.createWithResource(ctx, res)
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.TemplateList(
                        title = "Weather",
                        sections = listOf(
                            HostUiSection(
                                header = "Weather",
                                rows = listOf(
                                    HostUiRow("San Francisco · Here", listOf("14° · Partly cloudy"), browse = true, image = icon(R.drawable.outline_partly_cloudy_day_24)) {},
                                    HostUiRow("New York", listOf("United States", "8° · Light rain"), browse = true, image = icon(R.drawable.outline_rain_24)) {},
                                    HostUiRow("London", listOf("United Kingdom", "11° · Overcast"), browse = true, image = icon(R.drawable.outline_cloudy_24)) {},
                                    HostUiRow("Tokyo", listOf("Japan", "19° · Clear"), browse = true, image = icon(R.drawable.outline_clear_day_24)) {},
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "6-car-detail", device = CAR, showSystemUi = false)
    @Composable
    fun Preview6CarDetail() {
        val ctx = LocalContext.current
        fun icon(res: Int) = IconCompat.createWithResource(ctx, res)
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.Pane(
                        title = "San Francisco",
                        rows = listOf(
                            HostUiRow("Now · 14° · Partly cloudy", listOf("Feels like 13° · Humidity 72% · Wind 12 km/h NW"), image = icon(R.drawable.outline_partly_cloudy_day_24)),
                            HostUiRow("Today", listOf("16° / 9° · Partly cloudy · 10% rain"), image = icon(R.drawable.outline_partly_cloudy_day_24)),
                            HostUiRow("Tuesday", listOf("17° / 10° · Sunny"), image = icon(R.drawable.outline_clear_day_24)),
                            HostUiRow("Wednesday", listOf("15° / 9° · Light rain · 60% rain"), image = icon(R.drawable.outline_rain_24)),
                            HostUiRow("Thursday", listOf("14° / 8° · Overcast"), image = icon(R.drawable.outline_cloudy_24)),
                        ),
                    ),
                )
            }
        }
    }
}
