package com.vayunmathur.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.ExperimentalMaterial3ExpressiveApi
import com.vayunmathur.library.ui.FlexibleBottomAppBar
import com.vayunmathur.library.ui.IconAlbum
import com.vayunmathur.library.ui.IconLibraryMusic
import com.vayunmathur.library.ui.IconPerson
import com.vayunmathur.library.ui.NavigationBarItem
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.music.R
import com.vayunmathur.music.Route
import com.vayunmathur.music.util.MusicViewModel
import com.vayunmathur.music.util.SyncWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Hosts the four main tabs (Songs / Albums / Artists / Playlists) inside a
 * single HorizontalPager so the user can swipe between them, and tapping a
 * bottom-nav item smoothly slides to the destination instead of replacing the
 * screen.
 *
 * Tab selection lives in [pagerState] (local Compose state), NOT in the nav
 * backstack — so deep navigation (e.g. tap an album → AlbumDetail → back)
 * returns the user to whatever tab they were on, with the pager's scroll
 * position preserved.
 *
 * The now-playing controls and the tab bar are hoisted to this screen's
 * Scaffold so they remain visible across all four tabs.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, com.vayunmathur.library.ui.ExperimentalMaterial3Api::class)
@Composable
fun MusicTabsScreen(
    backStack: NavBackStack<Route>,
    musicViewModel: MusicViewModel,
) {
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Single sync kickoff for all four tabs (the pager composes them lazily, so
    // doing this per-tab would fire the sync multiple times).
    LaunchedEffect(Unit) {
        SyncWorker.runOnce(context)
        SyncWorker.enqueue(context)
    }

    Scaffold(
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                PlayingBottomBar(musicViewModel, backStack)
                MusicTabsBar(
                    selectedTab = pagerState.currentPage,
                    onSelectTab = { index ->
                        if (pagerState.currentPage != index) {
                            scope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                )
            }
        }
    ) { padding ->
        // Inner pages each have their own Scaffold (ListPage owns a TopAppBar
        // and consumes the top system inset). Only the BOTTOM space taken by
        // this Scaffold's bottomBar needs to be forwarded — passing the full
        // PaddingValues here was adding the status-bar inset twice and pushing
        // the TopAppBar down.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
        ) { page ->
            when (page) {
                0 -> HomeTabContent(backStack, musicViewModel)
                1 -> AlbumsTabContent(backStack, musicViewModel)
                2 -> ArtistsTabContent(backStack, musicViewModel)
                3 -> PlaylistsTabContent(backStack, musicViewModel)
            }
        }
    }
}

/**
 * The four-tab bar. Split out of [MusicTabsScreen] so it takes a plain index plus a
 * callback rather than reaching into the pager — which is what lets the store-listing
 * previews render it without one.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MusicTabsBar(selectedTab: Int, onSelectTab: (Int) -> Unit) {
    val tabs = listOf<Triple<String, @Composable () -> Unit, Int>>(
        Triple(stringResource(R.string.nav_home), { IconLibraryMusic() }, 0),
        Triple(stringResource(R.string.nav_albums), { IconAlbum() }, 1),
        Triple(stringResource(R.string.nav_artists), { IconPerson() }, 2),
        Triple(stringResource(R.string.nav_playlists), { IconLibraryMusic() }, 3),
    )

    FlexibleBottomAppBar {
        tabs.forEach { (name, icon, index) ->
            NavigationBarItem(
                selected = selectedTab == index,
                onClick = { onSelectTab(index) },
                icon = icon,
                label = { Text(name) },
            )
        }
    }
}
