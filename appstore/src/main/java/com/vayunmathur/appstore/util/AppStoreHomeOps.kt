package com.vayunmathur.appstore.util

import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.SandboxedGooglePlay
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.grapheneos.toUnifiedApp
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

// ---- Home ----
// Moved from AppStoreViewModel.kt (FileLength split); behavior identical.

/**
 * Fill the home screen.
 *
 * The offline rows come straight from Room and are already on screen by the time this
 * runs; what it adds is Play's editorial clusters and top chart, which need an
 * anonymous account. Those failing is normal — no network, no account — and leaves the
 * offline rows exactly as they were rather than emptying the screen.
 */
internal suspend fun AppStoreViewModel.loadHome(enabled: Set<AppSource> = enabledSources.value) {
    _isLoadingHome.value = true
    _recentlyUpdated.value = catalog.recentlyUpdated(RECENT_LIMIT)

    // The Sandboxed Google Play components come from GrapheneOS's release server, not
    // Play. Refreshing its signed index is what turns the stand-ins into installable
    // rows: the version, file list, signer digests and per-APK hashes all come from
    // there. A failed refresh leaves the stand-ins, and the section, exactly as they were.
    if (_sandboxedGooglePlay.value.isNotEmpty()) {
        grapheneOS.refresh(SandboxedGooglePlay.PACKAGES).getOrNull()?.let { packages ->
            val byPackage = packages.associateBy { it.packageName }
            _sandboxedGooglePlay.value = _sandboxedGooglePlay.value.map { row ->
                byPackage[row.packageName]?.toUnifiedApp() ?: row
            }
        }
    }

    if (AppSource.PLAYSTORE !in enabled) {
        _isLoadingHome.value = false
        return
    }

    val clusters = play.homeClusters()
    _playSections.value = clusters
        .filter { it.apps.isNotEmpty() }
        .take(PLAY_CLUSTER_LIMIT)
        .map { AppSection("play-${it.title}", it.title, it.apps.take(CAROUSEL_LIMIT)) }

    if (_playSections.value.isEmpty()) {
        // No account, or Play changed its stream shape. A top chart is one request and
        // still gives the screen something beyond this repo's own dozen apps.
        val chart = play.topChart()
        if (chart.isNotEmpty()) {
            _playSections.value = listOf(
                AppSection(
                    id = "play-top",
                    title = context.getString(R.string.section_play_top_charts),
                    apps = chart.take(CAROUSEL_LIMIT),
                )
            )
        }
    }
    _isLoadingHome.value = false
}

internal fun AppStoreViewModel.buildSections(
    modern: List<UnifiedApp>,
    playSections: List<AppSection>,
    recent: List<UnifiedApp>,
    sandboxed: List<UnifiedApp>,
    accrescent: List<UnifiedApp>,
    categoryApps: List<UnifiedApp>,
    category: String?,
): List<AppSection> = buildList {
    // A chosen category replaces the browsing rows: the user asked a narrow question
    // and a wall of unrelated carousels underneath it is just noise.
    if (category != null) {
        add(
            AppSection(
                id = "category",
                title = category,
                apps = categoryApps,
                layout = SectionLayout.LIST,
                subtitle = context.getString(R.string.section_category_subtitle),
            )
        )
        return@buildList
    }
    if (modern.isNotEmpty()) {
        add(
            AppSection(
                id = "modern",
                title = context.getString(R.string.section_modern_apps),
                apps = modern,
                subtitle = context.getString(R.string.section_modern_apps_subtitle),
            )
        )
    }
    if (sandboxed.isNotEmpty()) {
        add(
            AppSection(
                id = SandboxedGooglePlay.SECTION_ID,
                title = context.getString(R.string.section_sandboxed_google_play),
                apps = sandboxed,
                subtitle = context.getString(R.string.section_sandboxed_google_play_subtitle),
            )
        )
    }
    addAll(playSections)
    if (accrescent.isNotEmpty()) {
        add(
            AppSection(
                id = "accrescent",
                title = context.getString(R.string.section_accrescent),
                apps = accrescent,
                subtitle = context.getString(R.string.section_accrescent_subtitle),
            )
        )
    }
    if (recent.isNotEmpty()) {
        add(
            AppSection(
                id = "recent",
                title = context.getString(R.string.section_recently_updated),
                apps = recent,
                subtitle = context.getString(R.string.section_recently_updated_subtitle),
            )
        )
    }
}

/**
 * Populate the offline catalogues the first time the store is opened.
 *
 * Nothing else fetches them on startup: [loadHome] only refreshes GrapheneOS's index, and
 * the periodic [com.vayunmathur.appstore.work.UpdateCheckWorker] may be hours away. That
 * left a fresh install showing an empty store until the user thought to pull to refresh.
 *
 * Keyed off the catalogue being empty rather than a "first run" flag, so it also recovers
 * a store whose first sync failed or whose data was cleared - and so it stays quiet on
 * every later launch, when re-fetching two full catalogues on the user's connection would
 * be a poor trade for data that is at most a few hours stale.
 */
internal fun AppStoreViewModel.syncIfNeverSynced(enabled: Set<AppSource>) {
    if (_recentlyUpdated.value.isNotEmpty()) return
    // Nothing to fetch if both offline sources are switched off; syncSources() would only
    // report "all sources off" at someone who never asked for a sync.
    val offlineSources = setOf(DefaultRepos.FDROID.source, DefaultRepos.MODERN_APPS.source)
    if (enabled.intersect(offlineSources).isEmpty()) return
    syncSources()
}

/** Re-download both offline catalogues, then reload the home rows from them. */
fun AppStoreViewModel.syncSources() {
    if (_isSyncing.value) return
    viewModelScope.launch {
        val enabled = enabledSources.value
        _isSyncing.value = true
        val report = catalog.sync(enabled) { step ->
            _statusMessage.value = context.getString(
                when (step) {
                    SyncStep.FDROID -> R.string.sync_step_fdroid
                    SyncStep.MODERN_APPS -> R.string.sync_step_modern_apps
                }
            )
        }
        _statusMessage.value = ""
        _isSyncing.value = false

        AppMessages.show(
            when {
                report.allSkipped -> context.getString(R.string.sync_all_sources_off)
                !report.anyFailed -> context.getString(
                    R.string.sync_done,
                    (report.fdroidCount ?: 0) + (report.modernCount ?: 0),
                )
                report.fdroidCount == null && report.modernCount == null ->
                    context.getString(R.string.sync_failed_all)
                report.fdroidCount == null -> context.getString(R.string.sync_failed_fdroid)
                else -> context.getString(R.string.sync_failed_modern_apps)
            }
        )

        _categories.value = catalog.categories()
        loadHome(enabled)
        loadAccrescent(enabled)
        installedRepo.refresh()
    }
}
