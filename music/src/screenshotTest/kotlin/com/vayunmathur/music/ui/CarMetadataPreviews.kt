package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.carhost.HostUiTab
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:music`.
 *
 * Feeds representative [HostTemplate]s to the shared [CarTemplateView] renderer
 * from `:library:carhost` (the same renderer MA Auto uses). Shows the tabbed
 * library with a chip quick-action strip and the now-playing view.
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "5-car-browse", device = CAR, showSystemUi = false)
    @Composable
    fun Preview5CarBrowse() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(
                    HostTemplate.Tabs(
                        tabs = listOf(
                            HostUiTab("Playlists", "tab_playlists"),
                            HostUiTab("Albums", "tab_albums"),
                            HostUiTab("Artists", "tab_artists"),
                            HostUiTab("Songs", "tab_songs"),
                            HostUiTab("Recent", "tab_recent"),
                        ),
                        activeContentId = "tab_songs",
                        content = HostTemplate.TemplateList(
                            sections = listOf(
                                HostUiSection(
                                    chips = true,
                                    rows = listOf(
                                        HostUiRow("Shuffle all") {},
                                        HostUiRow("Play all") {},
                                    ),
                                ),
                                HostUiSection(
                                    header = "Songs",
                                    rows = listOf(
                                        HostUiRow("Midnight City", listOf("M83 · Hurry Up, We're Dreaming")) {},
                                        HostUiRow("Redbone", listOf("Childish Gambino · Awaken, My Love!")) {},
                                        HostUiRow("Nightcall", listOf("Kavinsky · OutRun")) {},
                                        HostUiRow("Instant Crush", listOf("Daft Punk · Random Access Memories")) {},
                                        HostUiRow("The Less I Know the Better", listOf("Tame Impala · Currents")) {},
                                    ),
                                ),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "6-car-playback", device = CAR, showSystemUi = false)
    @Composable
    fun Preview6CarPlayback() {
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(HostTemplate.MediaPlayback(title = "Midnight City"))
            }
        }
    }
}
