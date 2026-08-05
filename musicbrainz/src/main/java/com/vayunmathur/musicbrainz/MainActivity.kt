package com.vayunmathur.musicbrainz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.musicbrainz.ui.ArtistPage
import com.vayunmathur.musicbrainz.ui.DownloadsPage
import com.vayunmathur.musicbrainz.ui.ReleaseGroupPage
import com.vayunmathur.musicbrainz.ui.ReleasePage
import com.vayunmathur.musicbrainz.ui.SearchPage
import com.vayunmathur.musicbrainz.ui.SettingsPage
import com.vayunmathur.musicbrainz.util.MusicBrainzViewModel
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: MusicBrainzViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable
    data object Search : Route

    @Serializable
    data object Downloads : Route

    @Serializable
    data object Settings : Route

    @Serializable
    data class Artist(val artistId: String) : Route

    @Serializable
    data class ReleaseGroup(val releaseGroupId: String) : Route

    @Serializable
    data class Release(val releaseId: String) : Route
}

@Composable
fun Navigation(viewModel: MusicBrainzViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Search)
    MainNavigation(backStack) {
        entry<Route.Search> { SearchPage(backStack, viewModel) }
        entry<Route.Artist> { ArtistPage(backStack, viewModel, it.artistId) }
        entry<Route.ReleaseGroup> { ReleaseGroupPage(backStack, viewModel, it.releaseGroupId) }
        entry<Route.Release> { ReleasePage(backStack, viewModel, it.releaseId) }
        entry<Route.Downloads> { DownloadsPage(backStack, viewModel) }
        entry<Route.Settings> { SettingsPage(backStack, viewModel) }
    }
}
