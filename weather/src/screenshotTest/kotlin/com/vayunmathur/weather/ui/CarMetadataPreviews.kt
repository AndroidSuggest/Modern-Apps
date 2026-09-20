package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:weather`.
 *
 * Feeds representative [HostTemplate]s to the shared [CarTemplateView] renderer
 * from `:library:carhost`. Shows the saved-locations list and a forecast detail.
 * Numbered after the phone previews in [MetadataPreviews].
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "5-car-locations", device = CAR, showSystemUi = false)
    @Composable
    fun Preview5CarLocations() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.TemplateList(
                        title = "Weather",
                        sections = listOf(
                            HostUiSection(
                                header = "Weather",
                                rows = listOf(
                                    HostUiRow("San Francisco · Here", listOf("14° · Partly cloudy"), browse = true) {},
                                    HostUiRow("New York", listOf("United States", "8° · Light rain"), browse = true) {},
                                    HostUiRow("London", listOf("United Kingdom", "11° · Overcast"), browse = true) {},
                                    HostUiRow("Tokyo", listOf("Japan", "19° · Clear"), browse = true) {},
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
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.Pane(
                        title = "San Francisco",
                        rows = listOf(
                            HostUiRow("Now · 14° · Partly cloudy", listOf("Feels like 13° · Humidity 72% · Wind 12 km/h NW")),
                            HostUiRow("Today", listOf("16° / 9° · Partly cloudy · 10% rain")),
                            HostUiRow("Tuesday", listOf("17° / 10° · Sunny")),
                            HostUiRow("Wednesday", listOf("15° / 9° · Light rain · 60% rain")),
                            HostUiRow("Thursday", listOf("14° / 8° · Overcast")),
                        ),
                    ),
                )
            }
        }
    }
}
