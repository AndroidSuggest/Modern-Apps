package com.vayunmathur.maps.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiAction
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:maps`.
 *
 * Car Templates cannot render in Layoutlib, so these feed representative
 * [HostTemplate]s (the parsed shape the head unit renders) to the shared
 * [CarTemplateView] renderer from `:library:carhost` — the same renderer MA Auto
 * uses. Numbered after the phone previews in [MetadataPreviews] so the listing
 * order stays phone-first. See `common-conventions-preview-metadata`.
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "6-car-navigation", device = CAR, showSystemUi = false)
    @Composable
    fun Preview6CarNavigation() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.Navigation(
                        navigating = true,
                        cue = "Turn right onto 16th St",
                        road = "16th St",
                        distanceText = "280 m",
                        etaText = "11 min",
                        lanesText = "← ↑ →",
                        actions = listOf(HostUiAction("End") {}),
                    ),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "7-car-search", device = CAR, showSystemUi = false)
    @Composable
    fun Preview7CarSearch() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.MapWithContent(
                        content = HostTemplate.TemplateList(
                            title = "Search",
                            sections = listOf(
                                HostUiSection(
                                    rows = listOf(
                                        HostUiRow("Ferry Building Marketplace", listOf("1 Ferry Building, San Francisco"), browse = true) {},
                                        HostUiRow("Ferry Plaza Farmers Market", listOf("1 Ferry Building, San Francisco"), browse = true) {},
                                        HostUiRow("Golden Gate Ferry Terminal", listOf("Pier 1, San Francisco"), browse = true) {},
                                        HostUiRow("Oakland Ferry Dock", listOf("Clay St, Oakland"), browse = true) {},
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }
}
