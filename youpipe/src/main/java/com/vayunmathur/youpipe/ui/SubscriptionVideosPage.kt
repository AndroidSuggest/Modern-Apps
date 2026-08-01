package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.invisibleClickable
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.SubscriptionFeedActions
import com.vayunmathur.youpipe.util.SubscriptionFeedUiState
import com.vayunmathur.youpipe.util.YouPipeViewModel

/**
 * Binder for the subscription feed: newest uploads across every channel the user follows,
 * or across one category of them.
 */
@Composable
fun SubscriptionVideosPage(
    backStack: NavBackStack<Route>,
    youPipeViewModel: YouPipeViewModel,
    category: String?,
) {
    val videos by remember(category) { youPipeViewModel.subscriptionVideosFor(category) }
        .collectAsState(initial = emptyList())
    val fetchProgress by youPipeViewModel.fetchProgress.collectAsState()

    val history by youPipeViewModel.historyVideos.collectAsState()
    val deArrowEnabled by youPipeViewModel.deArrowEnabled.collectAsState()
    val deArrowCache by youPipeViewModel.deArrowCache.collectAsState()
    val progressById = remember(history) { history.associate { it.id to it.progress } }
    val context = LocalContext.current

    SubscriptionVideosScreen(
        backStack = backStack,
        state = SubscriptionFeedUiState(
            videos = videos.map { video ->
                val deArrow = if (deArrowEnabled) deArrowCache[video.videoID] else null
                val watched = progressById[video.videoID] ?: 0L
                videoRowState(
                    context = context,
                    videoInfo = video,
                    showAuthor = true,
                    percentWatched = if (video.duration > 0) (watched.toDouble() / video.duration).toFloat() else 0f,
                    deArrowTitle = deArrow?.title,
                    deArrowThumbnailURL = deArrow?.thumbnailUrl,
                )
            },
            fetchProgress = fetchProgress,
        ),
        actions = object : SubscriptionFeedActions {
            override fun openVideo(videoID: Long) {
                backStack.add(Route.VideoPage(videoID))
            }
        },
    )
}

/**
 * Stateless subscription feed. [backStack] is here only to drive [BottomNavBar]; taps on the
 * list itself go through [actions].
 */
@Composable
fun SubscriptionVideosScreen(
    backStack: NavBackStack<Route>,
    state: SubscriptionFeedUiState,
    actions: SubscriptionFeedActions,
) {
    Scaffold(bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.SubscriptionsPage) }) { paddingValues ->
        LazyColumn(Modifier.padding(paddingValues)) {
            if (state.fetchProgress in 0f..1f) {
                item {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator({ state.fetchProgress })
                    }
                }
            }
            items(state.videos, key = { it.videoID }) { row ->
                VideoRow(
                    row = row,
                    modifier = Modifier.invisibleClickable { actions.openVideo(row.videoID) },
                )
            }
        }
    }
}
