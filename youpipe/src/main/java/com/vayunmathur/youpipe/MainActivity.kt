package com.vayunmathur.youpipe

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconList
import com.vayunmathur.library.ui.IconSubscriptions
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.youpipe.data.SubscriptionDatabase
import com.vayunmathur.youpipe.ui.ChannelPage
import com.vayunmathur.youpipe.ui.DownloadedVideosPage
import com.vayunmathur.youpipe.ui.HistoryPage
import com.vayunmathur.youpipe.util.PlaybackService
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModelFactory
import com.vayunmathur.youpipe.ui.SearchPage
import com.vayunmathur.youpipe.ui.SettingsPage
import com.vayunmathur.youpipe.ui.RecommendationSettingsPage
import com.vayunmathur.youpipe.ui.SubscriptionVideosPage
import com.vayunmathur.youpipe.ui.SubscriptionsPage
import com.vayunmathur.youpipe.ui.VideoPage
import com.vayunmathur.youpipe.ui.dialogs.CreateSubscriptionCategory
import kotlinx.serialization.Serializable
import com.vayunmathur.youpipe.util.videoURLtoID

internal fun Context.findActivity(): ComponentActivity {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) return context
        context = context.baseContext
    }
    throw IllegalStateException("Picture in picture should be called in the context of an Activity")
}

@Composable
fun rememberIsInPipMode(): Boolean {
    val activity = LocalContext.current.findActivity()
    var pipMode by remember { mutableStateOf(activity.isInPictureInPictureMode) }
    DisposableEffect(activity) {
        val observer = Consumer<PictureInPictureModeChangedInfo> { info ->
            pipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(
            observer
        )
        onDispose { activity.removeOnPictureInPictureModeChangedListener(observer) }
    }
    return pipMode
}



class MainActivity : ComponentActivity() {
    private val youPipeViewModel: YouPipeViewModel by viewModels {
        val db = buildDatabase<SubscriptionDatabase>()
        YouPipeViewModelFactory(
            application,
            db.subscriptionDao(),
            db.subscriptionCategoryDao(),
            db.subscriptionVideoDao(),
            db.historyVideoDao(),
            db.downloadedVideoDao(),
            db.cachedRelatedVideoDao(),
            db.recommendationImpressionDao(),
            db.recommendationPreferencesDao(),
            db.channelPreferenceDao(),
            db.keywordPreferenceDao(),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Touch the VM so its init { setupHourlyTask(...) } runs at startup
        // (replaces the previous LaunchedEffect(Unit) in setContent).
        youPipeViewModel
        setContent {
            DynamicTheme {
                OfflineAware {
                    Navigation(resolveInitialBackStack(intent.data), youPipeViewModel)
                }
            }
        }
    }

    /**
     * Decide the initial backstack at launch. A `watch` deep-link always wins;
     * otherwise fall back to the user's chosen default page (see
     * [DEFAULT_PAGE_KEY]), defaulting to Home. Read synchronously here because
     * this runs in [onCreate] before `setContent`.
     */
    private fun resolveInitialBackStack(uri: Uri?): List<Route> {
        if (uri != null && "watch" in uri.pathSegments && "v" in uri.queryParameterNames) {
            return listOf(Route.VideoPage(videoURLtoID(uri.toString())))
        }
        return defaultBackStack(DataStoreUtils.getInstance(this).getString(DEFAULT_PAGE_KEY))
    }
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        // Previously we stopped PlaybackService when PiP closed (isFinishing). New behavior:
        // Keep audio playing and switch to audio-only when PiP is dismissed. PlaybackService
        // handles the audio-only switch via track selection override; we do NOT stop it here.
    }

    override fun onDestroy() {
        // Only stop service when truly destroying the whole activity, not when entering PiP.
        // When the user swipes away PiP window, system destroys activity with isFinishing=true,
        // but we want to KEEP audio (background playback). So we do NOT stop service here.
        // PlaybackService's own onTaskRemoved will handle final cleanup when notification is
        // dismissed or user explicitly stops.
        super.onDestroy()
    }
}

// --- Default startup page ("Open to" setting) ---

/** DataStore key holding the persisted default-page choice. */
const val DEFAULT_PAGE_KEY = "default_page"

const val DEFAULT_PAGE_HOME = "home"
const val DEFAULT_PAGE_SUBSCRIPTIONS = "subscriptions"
const val DEFAULT_PAGE_ALL_SUBSCRIPTIONS = "all_subscriptions"
const val DEFAULT_PAGE_HISTORY = "history"
const val DEFAULT_PAGE_DOWNLOADS = "downloads"
const val DEFAULT_PAGE_SETTINGS = "settings"

/**
 * Ordered options for the "Open to" picker: (persisted key, label string res).
 * Stable string keys are used instead of [Route] objects so the choice
 * serializes cleanly and survives refactors.
 */
val DEFAULT_PAGE_OPTIONS: List<Pair<String, Int>> = listOf(
    DEFAULT_PAGE_HOME to R.string.page_home,
    DEFAULT_PAGE_SUBSCRIPTIONS to R.string.title_subscriptions,
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS to R.string.label_all_subscriptions,
    DEFAULT_PAGE_HISTORY to R.string.title_history,
    DEFAULT_PAGE_DOWNLOADS to R.string.page_downloads,
    DEFAULT_PAGE_SETTINGS to R.string.title_settings,
)

/** Map a persisted default-page key to the initial backstack to launch with. */
fun defaultBackStack(key: String?): List<Route> = when (key) {
    DEFAULT_PAGE_SUBSCRIPTIONS -> listOf(Route.SubscriptionsPage)
    // Seed the Subscriptions root beneath the all-subscriptions feed so Back
    // returns to the Subscriptions list instead of exiting the app.
    DEFAULT_PAGE_ALL_SUBSCRIPTIONS ->
        listOf(Route.SubscriptionsPage, Route.SubscriptionVideosPage(null))
    DEFAULT_PAGE_HISTORY -> listOf(Route.History)
    DEFAULT_PAGE_DOWNLOADS -> listOf(Route.Downloads)
    DEFAULT_PAGE_SETTINGS -> listOf(Route.Settings)
    else -> listOf(Route.SearchPage) // home / unset / unknown
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object SearchPage : Route

    @Serializable
    data class VideoPage(val videoID: Long) : Route

    @Serializable
    data class ChannelPage(val channelID: String): Route

    @Serializable
    data object SubscriptionsPage: Route

    @Serializable
    data class SubscriptionVideosPage(val category: String?): Route

    @Serializable
    data class CreateSubscriptionCategory(val id: String?): Route

    @Serializable
    data object History: Route

    @Serializable
    data object Downloads: Route

    @Serializable
    data object Settings: Route

    @Serializable
    data object RecommendationSettings: Route
}

@Composable
fun Navigation(initialBackStack: List<Route>, ypvm: YouPipeViewModel) {
    val backStack = rememberNavBackStack(initialBackStack)
    // Land on settings when opened from the system App Info page.
    backStack.openSettingsIfRequested(Route.Settings)
    MainNavigation(backStack) {
        entry<Route.SearchPage> {
            SearchPage(backStack, ypvm)
        }
        entry<Route.VideoPage> {
            VideoPage(backStack, ypvm, it.videoID)
        }
        entry<Route.ChannelPage> {
            ChannelPage(backStack, ypvm, it.channelID)
        }
        entry<Route.SubscriptionsPage> {
            SubscriptionsPage(backStack, ypvm)
        }
        entry<Route.SubscriptionVideosPage> {
            SubscriptionVideosPage(backStack, ypvm, it.category)
        }
        entry<Route.CreateSubscriptionCategory>(metadata = DialogPage()) {
            CreateSubscriptionCategory(backStack, ypvm, it.id)
        }
        entry<Route.History> {
            HistoryPage(backStack, ypvm)
        }
        entry<Route.Downloads> {
            DownloadedVideosPage(backStack, ypvm)
        }
        entry<Route.Settings> {
            SettingsPage(backStack, ypvm)
        }
        entry<Route.RecommendationSettings> {
            RecommendationSettingsPage(backStack, ypvm)
        }
    }
}

val MAIN_BOTTOM_BAR_ITEMS = listOf(
    BottomBarItem("Home", Route.SearchPage) { IconHome() },
    BottomBarItem("Subscriptions", Route.SubscriptionsPage) { IconSubscriptions() },
    BottomBarItem("History", Route.History) { IconHistory() },
    BottomBarItem("Downloads", Route.Downloads) { IconList() },
    BottomBarItem("Settings", Route.Settings) { IconSettings() }
)
