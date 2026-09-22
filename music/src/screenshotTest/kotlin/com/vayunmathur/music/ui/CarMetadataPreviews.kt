package com.vayunmathur.music.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.graphics.drawable.IconCompat
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.carhost.CarTemplateView
import com.vayunmathur.library.carhost.HostTemplate
import com.vayunmathur.library.carhost.HostUiRow
import com.vayunmathur.library.carhost.HostUiSection
import com.vayunmathur.library.carhost.HostUiTab
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.music.R

/** Landscape head-unit display, ~Android Auto reference. */
private const val CAR = "spec:width=1024dp,height=600dp,dpi=160"

/**
 * Store-listing screenshots of the Android Auto car views for `:music`.
 *
 * Feeds representative [HostTemplate]s to the shared [CarTemplateView] renderer
 * from `:library:carhost` (the same renderer MA Auto uses). Shows the tabbed
 * library with an album art grid + chip quick actions, and the now-playing view.
 */
class CarMetadataPreviews {

    @PreviewTest
    @Preview(name = "5-car-browse", device = CAR, showSystemUi = false)
    @Composable
    fun Preview5CarBrowse() {
        val context = LocalContext.current
        val art = remember { IconCompat.createWithResource(context, R.drawable.car_sample_album_art) }
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
                        activeContentId = "tab_albums",
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
                                    header = "Albums",
                                    grid = true,
                                    rows = listOf(
                                        albumTile("Random Access Memories", "Daft Punk", art),
                                        albumTile("Currents", "Tame Impala", art),
                                        albumTile("Awaken, My Love!", "Childish Gambino", art),
                                        albumTile("OutRun", "Kavinsky", art),
                                        albumTile("Discovery", "Daft Punk", art),
                                        albumTile("In Colour", "Jamie xx", art),
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
        val context = LocalContext.current
        val art = remember { IconCompat.createWithResource(context, R.drawable.car_sample_album_art) }
        DynamicTheme(darkTheme = true) {
            Surface(Modifier.fillMaxSize()) {
                CarTemplateView(HostTemplate.MediaPlayback(title = "Instant Crush", image = art))
            }
        }
    }

    private fun albumTile(name: String, artist: String, art: IconCompat): HostUiRow =
        HostUiRow(title = name, texts = listOf(artist), image = art) {}
}
