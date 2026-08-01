package com.vayunmathur.appstore.util

import android.graphics.drawable.Drawable
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.VerificationResult

/**
 * The UI contract between [AppStoreViewModel] and the screens the store listing images
 * are rendered from.
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — see `src/screenshotTest`. It lives in
 * `util` rather than `ui` so the dependency runs one way: `ui` depends on `util`, and the
 * ViewModel implements these interfaces.
 *
 * Unlike a snapshot-state ViewModel, [AppStoreViewModel] publishes `StateFlow`s, so the
 * states below are assembled by the `…Page` binders — which are where the `collectAsState`
 * subscriptions have to live for recomposition to work — rather than by a getter.
 */

/** Everything the browse/search screen draws. */
data class BrowseUiState(
    val query: String = "",
    val apps: List<UnifiedApp> = emptyList(),
    val installedPackages: Set<String> = emptySet(),
    /** Download fraction per package, for the rows currently installing. */
    val downloadProgress: Map<String, Float> = emptyMap(),
    /** Launcher icons read back from PackageManager, keyed by package name. */
    val installedIcons: Map<String, Drawable> = emptyMap(),
    val syncMessage: String = "",
    val isSyncing: Boolean = false,
)

/** Everything the app detail screen draws. [app] is null until one has been selected. */
data class AppDetailUiState(
    val app: UnifiedApp? = null,
    /** The installed copy of [app], or null when the package isn't installed. */
    val installedInfo: InstalledInfo? = null,
    /** Verdict of the last install attempt for this package. */
    val verification: VerificationResult? = null,
    val progress: Float? = null,
    val installedIcon: Drawable? = null,
    val syncMessage: String = "",
)

/**
 * Browse-screen callbacks. Every method has a no-op default so a preview can render the
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface BrowseActions {
    fun setSearch(q: String) {}

    companion object {
        val Noop: BrowseActions = object : BrowseActions {}
    }
}

/** Detail-screen callbacks. Same no-op-default arrangement as [BrowseActions]. */
interface AppDetailActions {
    fun downloadAndInstall(app: UnifiedApp) {}
    fun openApp(packageName: String) {}
    fun uninstallApp(packageName: String) {}
    fun openInPlayStore(pkg: String) {}
    fun openInBrowser(url: String) {}

    companion object {
        val Noop: AppDetailActions = object : AppDetailActions {}
    }
}
