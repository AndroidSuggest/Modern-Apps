package com.vayunmathur.appstore.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreLinks
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallEvents
import com.vayunmathur.appstore.data.installer.InstallFailureBatch
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.play.PlayHttpClient
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.priority
import com.vayunmathur.appstore.data.searchCandidate
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.domain.SearchRanking
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One ViewModel, four screens, and no data-layer logic of its own.
 *
 * The previous version was 900 lines that owned the Play session, the PackageManager
 * sweep, the F-Droid sync and the download/verify/install pipeline all at once. Those now
 * live in [CatalogRepository], [PlayRepository], [InstalledAppsRepository] and
 * [InstallCoordinator]; what is left here is the job this class is actually for — turning
 * their flows into per-screen state and turning taps into calls.
 */
class AppStoreViewModel(
    internal val context: Application,
    db: AppDatabase,
) : ViewModel(), HomeActions, SearchActions, AppDetailActions, UpdatesActions, LibraryActions {

    internal val catalog = CatalogRepository(context, db, viewModelScope)
    internal val play = PlayRepository(context)
    internal val accrescent = AccrescentRepository(context, db)
    internal val installedRepo = InstalledAppsRepository(context)
    internal val settings = SettingsRepository(context, viewModelScope)
    internal val installer =
        InstallCoordinator(context, db, play, accrescent, viewModelScope) { ownSigningCertificates }

    /** Off-by-default: the periodic check may also download and install updates unattended. */
    val autoInstallUpdates: StateFlow<Boolean> = settings.autoInstallUpdates

    /** The sources the user has left switched on. See [AppSource.TOGGLEABLE]. */
    val enabledSources: StateFlow<Set<AppSource>> = settings.enabledSources

    /** SHA-256 of this app's own signing certificate — the Modern Apps trust root. */
    val ownSigningCertificates: Set<String> by lazy { ApkCertificates.selfSigners(context) }

    val repos = catalog.repos

    // --- Raw state ------------------------------------------------------------------

    internal val statusMessageFlow = MutableStateFlow("")

    /** Kept apart from [statusMessageFlow] so a transient sync line can't erase it. */
    private val playErrorFlow = MutableStateFlow("")
    internal val isSyncingFlow = MutableStateFlow(false)
    internal val isLoadingHomeFlow = MutableStateFlow(false)
    internal val isCheckingUpdatesFlow = MutableStateFlow(false)
    internal val lastUpdateCheckFlow = MutableStateFlow(0L)

    internal val playSectionsFlow = MutableStateFlow<List<AppSection>>(emptyList())
    internal val recentlyUpdatedFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /** Newest builds per offline repo: each one gets its own home row. */
    internal val modernRecentFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val proprietaryRecentFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val fdroidRecentFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /** Accrescent listings for the home carousel, from the gRPC listing API. */
    internal val accrescentAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /**
     * App ids Accrescent's signed allowlist vouches for. Drives library attribution for an
     * installed Accrescent app. Empty until the first repodata refresh populates it.
     */
    internal val accrescentPackagesFlow = MutableStateFlow<Set<String>>(emptySet())

    internal val categoriesFlow = MutableStateFlow<List<String>>(emptyList())
    internal val selectedCategoryFlow = MutableStateFlow<String?>(null)
    internal val categoryAppsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    internal val queryFlow = MutableStateFlow("")
    internal val searchResultsFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val searchFilterFlow = MutableStateFlow(SourceFilter.ALL)
    internal val isSearchingFlow = MutableStateFlow(false)
    internal val hasSearchedFlow = MutableStateFlow(false)

    internal val selectedAppFlow = MutableStateFlow<UnifiedApp?>(null)
    internal val isLoadingDetailsFlow = MutableStateFlow(false)

    internal val catalogUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val playUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val accrescentUpdatesFlow = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val libraryFilterFlow = MutableStateFlow(SourceFilter.ALL)

    /**
     * Installed packages Play confirmed it actually hosts.
     *
     * Drives library attribution so a sideloaded app isn't labelled Play just for being
     * unrecognised. Empty until the first resolution; a package no offline source lists
     * stays out of the library until Play vouches for it. A failed lookup leaves the
     * previous answer in place rather than emptying it — see [refreshPlayInstalledPackages].
     */
    internal val playInstalledPackagesFlow = MutableStateFlow<Set<String>>(emptySet())

    internal var searchJob: Job? = null
    internal var detailJob: Job? = null
    internal var updateAllJob: Job? = null

    // --- Derived state ----------------------------------------------------------------

    /** Everything every screen needs to draw a row: installed, its icon, its progress. */
    private val chrome: StateFlow<RowChrome> = combine(
        installedRepo.apps,
        installedRepo.icons,
        installer.stages,
    ) { installed, icons, stages ->
        RowChrome(installed, installed.map { it.packageName }.toSet(), icons, stages)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RowChrome())

    /**
     * The transient line under the top bar.
     *
     * Being rate-limited by Play outranks whatever else was being said, because it is the
     * reason nothing appears to be happening: the store is deliberately sitting still for
     * a few seconds rather than hammering an endpoint that has already refused it. Read
     * off the transport rather than the session — the limit is on the Play account and is
     * shared by every caller, including the background update worker's own stack.
     */
    private val statusLine: StateFlow<String> =
        combine(statusMessageFlow, PlayHttpClient.throttled) { message, throttled ->
            if (throttled) context.getString(R.string.play_rate_limited) else message
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val updates: StateFlow<List<UnifiedApp>> = combine(
        catalogUpdatesFlow,
        playUpdatesFlow,
        accrescentUpdatesFlow,
        installedRepo.apps,
    ) { catalogUpdates, playUpdates, accrescentUpdates, installed ->
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        (catalogUpdates + playUpdates + accrescentUpdates)
            // The surviving row's source decides which download-and-verify path the update
            // takes, so it has to be the same precedence search and the library use.
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            // Re-check against what is on the device rather than trusting the lists.
            // playUpdatesFlow is a snapshot from the last network check, so without this a
            // Play app stays in the list after it has been updated, until the next check.
            .filter { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@filter false
                app.versionCode > installedVersion
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val sections: StateFlow<List<AppSection>> = combine(
        catalog.modernApps,
        combine(
            modernRecentFlow,
            proprietaryRecentFlow,
            fdroidRecentFlow,
        ) { modernRecent, proprietaryRecent, fdroidRecent ->
            Triple(modernRecent, proprietaryRecent, fdroidRecent)
        },
        playSectionsFlow,
        combine(
            categoryAppsFlow,
            selectedCategoryFlow,
            accrescentAppsFlow,
        ) { apps, category, accrescent -> Triple(apps, category, accrescent) },
    ) { modern, (modernRecent, proprietaryRecent, fdroidRecent), playSections,
        (categoryApps, category, accrescentApps) ->
        buildSections(
            modern, modernRecent, proprietaryRecent, fdroidRecent,
            playSections, accrescentApps, categoryApps, category,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeUiState> = combine(
        sections,
        categoriesFlow,
        selectedCategoryFlow,
        chrome,
        combine(
            updates,
            isSyncingFlow,
            isLoadingHomeFlow,
            statusLine,
            playErrorFlow,
        ) { u, syncing, loading, msg, playError ->
            HomeChrome(u.size, syncing, loading, msg.ifBlank { playError })
        },
    ) { built, categories, category, rows, homeChrome ->
        HomeUiState(
            sections = built,
            categories = categories,
            selectedCategory = category,
            updateCount = homeChrome.updateCount,
            installedPackages = rows.installedPackages,
            installedIcons = rows.icons,
            stages = rows.stages,
            isLoading = homeChrome.isLoading,
            isSyncing = homeChrome.isSyncing,
            statusMessage = homeChrome.message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    val search: StateFlow<SearchUiState> = combine(
        queryFlow,
        combine(searchResultsFlow, searchFilterFlow) { results, filter ->
            results to filter
        },
        isSearchingFlow,
        hasSearchedFlow,
        chrome,
    ) { query, (results, filter), searching, searched, rows ->
        SearchUiState(
            query = query,
            results = results.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            isSearching = searching,
            hasSearched = searched,
            installedPackages = rows.installedPackages,
            installedIcons = rows.icons,
            stages = rows.stages,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    val detail: StateFlow<AppDetailUiState> = combine(
        selectedAppFlow,
        isLoadingDetailsFlow,
        chrome,
        installer.verification,
    ) { app, loading, rows, verification ->
        val pkg = app?.packageName
        AppDetailUiState(
            app = app,
            installedInfo = rows.installed.find { it.packageName == pkg },
            verification = verification[pkg],
            stage = rows.stages[pkg],
            installedIcon = rows.icons[pkg],
            isLoadingDetails = loading,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AppDetailUiState())

    val updatesUi: StateFlow<UpdatesUiState> = combine(
        updates,
        chrome,
        isCheckingUpdatesFlow,
        lastUpdateCheckFlow,
        statusLine,
    ) { list, rows, checking, checkedAt, message ->
        UpdatesUiState(
            updates = list,
            installedIcons = rows.icons,
            installedInfos = rows.installed.associateBy { it.packageName },
            stages = rows.stages,
            isChecking = checking,
            lastCheckedAt = checkedAt,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState())

    val library: StateFlow<LibraryUiState> = combine(
        chrome,
        catalog.packageIndex,
        playInstalledPackagesFlow,
        accrescentPackagesFlow,
        libraryFilterFlow,
    ) { rows, index, playPackages, accrescentPackages, filter ->
        // Source attribution, highest priority first:
        //  1. Whatever the offline catalogue (F-Droid / Modern Apps / Proprietary) recorded.
        //  2. Accrescent, for packages its signed allowlist vouches for.
        //  3. Play, but only for packages Play confirmed it hosts (see playInstalledPackagesFlow).
        // A package no source vouches for — sideloaded, or from a store we don't track — is
        // left out entirely rather than mislabelled as Play.
        fun sourceOf(pkg: String): AppSource? =
            index[pkg]?.source?.let { runCatching { AppSource.valueOf(it) }.getOrNull() }
                ?: AppSource.ACCRESCENT.takeIf { pkg in accrescentPackages }
                ?: AppSource.PLAYSTORE.takeIf { pkg in playPackages }

        val all = rows.installed.mapNotNull { info ->
            sourceOf(info.packageName)?.let { info.toUnifiedApp(it) }
        }
        LibraryUiState(
            apps = all.filter { filter.source == null || it.source == filter.source },
            filter = filter,
            counts = SourceFilter.entries.associateWith { f ->
                if (f.source == null) all.size else all.count { it.source == f.source }
            },
            installedIcons = rows.icons,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryUiState())

    // --- Lifecycle -------------------------------------------------------------------

    init {
        viewModelScope.launch {
            // The persisted choice rather than the flow's optimistic default: a source the
            // user switched off must not be contacted once on every cold start while the
            // stored value is still on its way.
            val enabled = settings.readEnabledSources()
            installedRepo.refresh()
            if (AppSource.PLAYSTORE in enabled) play.restore()
            loadHome(enabled)
            refreshPlayInstalledPackages(enabled)
            syncIfNeverSynced(enabled)
        }
        viewModelScope.launch { loadAccrescent(settings.readEnabledSources()) }
        viewModelScope.launch {
            // Recompute catalogue-side updates whenever either half changes. The Play
            // half needs a network call and is driven by checkForUpdates() instead.
            combine(catalog.packageIndex, installedRepo.updatable) { _, installed -> installed }
                .collect { installed -> catalogUpdatesFlow.value = catalog.updatesFor(installed) }
        }
        viewModelScope.launch {
            categoriesFlow.value = catalog.categories()
        }
        viewModelScope.launch {
            // The OS install finishes asynchronously, well after commit for a large app.
            // Re-read the device when it lands so the row switches to Open/Uninstall and
            // picks up the launcher icon, instead of waiting for the next onResume.
            InstallEvents.results.collect { result ->
                if (result.success) {
                    installedRepo.refresh()
                    refreshPlayInstalledPackages()
                }
            }
        }
        viewModelScope.launch {
            // Say so when Play is unreachable. Without this the store just quietly shows
            // fewer results, which looks like the search finding nothing.
            play.authState.collect { state ->
                playErrorFlow.value = (state as? PlayAuthState.Error)
                    ?.let { context.getString(R.string.play_unavailable, it.message) }
                    .orEmpty()
            }
        }
    }

    fun refreshInstalled() {
        viewModelScope.launch {
            installedRepo.refresh()
            refreshPlayInstalledPackages()
        }
    }

    override fun onCleared() {
        // Release the Accrescent gRPC channel (and its okhttp connection pool).
        accrescent.shutdown()
        super.onCleared()
    }

    /**
     * Ask Play which installed packages it actually hosts, for library attribution.
     *
     * Only packages no offline source lists are worth asking about — the rest are
     * already attributed. Play returning nothing (no account, no network) leaves the
     * previous answer in place rather than emptying the library, so a transient
     * failure can't hide apps Play was known to host.
     */
    private suspend fun refreshPlayInstalledPackages(
        enabled: Set<AppSource> = enabledSources.value,
    ) {
        if (AppSource.PLAYSTORE !in enabled) {
            playInstalledPackagesFlow.value = emptySet()
            return
        }
        val index = catalog.packageIndex.value
        val candidates = installedRepo.apps.value
            .map { it.packageName }
            .filter { it !in index }
        if (candidates.isEmpty()) {
            playInstalledPackagesFlow.value = emptySet()
            return
        }
        val available = play.details(candidates).map { it.packageName }.toSet()
        if (available.isNotEmpty()) playInstalledPackagesFlow.value = available
    }

    // (loadAccrescent/accrescentUpdate live in AppStoreUpdateOps.kt.)

    // --- Home ---------------------------------------------------------------------
    // Implementations live in AppStoreHomeOps.kt.

    override fun selectCategory(category: String?) {
        selectedCategoryFlow.value = category
        viewModelScope.launch {
            categoryAppsFlow.value = if (category == null) emptyList() else catalog.byCategory(category)
        }
    }

    override fun refresh() = syncSources()

    /** Re-download both offline catalogues, then reload the home rows from them. */
    // Implemented in AppStoreHomeOps.kt as a public extension (called from SourcesPage).

    /**
     * Turn a source on or off, and make the rest of the store agree immediately.
     *
     * Disabling drops the source's cached rows and whatever it had already contributed to the
     * screens, rather than waiting for the next sync: the point of the switch is that the store
     * stops offering that source's apps, and leaving them on screen until something else
     * happens to refresh would read as the switch not working.
     */
    fun setSourceEnabled(source: AppSource, enabled: Boolean) {
        viewModelScope.launch {
            settings.setSourceEnabled(source, enabled)
            // Computed here rather than re-read from the flow: the DataStore write has to make
            // a round trip before enabledSources reflects it, and everything below needs the
            // choice the user just made.
            val next =
                if (enabled) enabledSources.value + source else enabledSources.value - source
            catalog.purgeDisabled(next)
            if (!enabled) forgetSource(source)
            categoriesFlow.value = catalog.categories()
            loadHome(next)
            loadAccrescent(next)
        }
    }

    /** Drop what a source had already contributed to the screens. */
    private fun forgetSource(source: AppSource) {
        when (source) {
            AppSource.PLAYSTORE -> {
                playSectionsFlow.value = emptyList()
                playUpdatesFlow.value = emptyList()
                playInstalledPackagesFlow.value = emptySet()
            }
            AppSource.ACCRESCENT -> {
                accrescentAppsFlow.value = emptyList()
                accrescentPackagesFlow.value = emptySet()
                accrescentUpdatesFlow.value = emptyList()
            }
            // The offline sources have no in-memory rows of their own: browse, search and the
            // update check all read the Room cache that purgeDisabled just emptied.
            else -> Unit
        }
    }

    // --- Search -----------------------------------------------------------------------
    // Bodies live in AppStoreSearchOps.kt; these overrides keep interface conformance.

    override fun setSearch(query: String) = setSearchImpl(query)

    override fun setSearchFilter(filter: SourceFilter) {
        searchFilterFlow.value = filter
    }

    // --- Detail -----------------------------------------------------------------------
    // Bodies live in AppStoreDetailOps.kt as public extensions
    // (called from MainActivity + AppDetailPage via the concrete class).

    // --- Actions ----------------------------------------------------------------------
    // Bodies live in AppStoreActionOps.kt; these overrides keep interface conformance.

    override fun install(app: UnifiedApp) = installImpl(app)

    override fun dismissInstallFailure(packageName: String) = installer.dismissFailure(packageName)

    override fun openApp(packageName: String) = openAppImpl(packageName)

    override fun uninstallApp(packageName: String) = uninstallAppImpl(packageName)

    override fun openInPlayStore(packageName: String) = openInPlayStoreImpl(packageName)

    override fun openInBrowser(url: String) = openInBrowserImpl(url)

    override fun shareApp(app: UnifiedApp) = shareAppImpl(app)

    // --- Updates ----------------------------------------------------------------------
    // Bodies live in AppStoreUpdateOps.kt; these overrides keep interface conformance.

    override fun checkForUpdates() = checkForUpdatesImpl()

    /**
     * Update everything, and report the run once when it is over.
     *
     * Sequential on purpose: updates this store isn't the update owner of still get a
     * system confirmation dialog, and firing them concurrently buries the user in prompts.
     *
     * The reporting is the other half of issue #630. A run used to say nothing itself and
     * let each app's failure raise its own snackbar, so a phone whose updates were all
     * failing the same way — Play refusing the lot for going too fast, typically — showed
     * the user the same error once per app. Now the failures are collected and summarised,
     * and a reason that keeps coming back ends the run rather than being demonstrated
     * another dozen times.
     */
    override fun updateAll() = updateAllImpl()

    /** Turn fully unattended (no-tap) background update installation on or off. */
    fun setAutoInstallUpdates(enabled: Boolean) {
        viewModelScope.launch { settings.setAutoInstallUpdates(enabled) }
    }

    // --- Library ------------------------------------------------------------------------

    override fun setLibraryFilter(filter: SourceFilter) {
        libraryFilterFlow.value = filter
    }

    // --- Helpers --------------------------------------------------------------------------

    internal fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    internal fun String.toUri(): Uri = Uri.parse(this)

    /** An installed package as a listing, for the library screen. */
    private fun InstalledInfo.toUnifiedApp(source: AppSource) = UnifiedApp(
        packageName = packageName,
        source = source,
        name = name,
        versionName = versionName,
        versionCode = versionCode,
        lastUpdated = lastUpdateTime,
    )

    private data class RowChrome(
        val installed: List<InstalledInfo> = emptyList(),
        val installedPackages: Set<String> = emptySet(),
        val icons: Map<String, Drawable> = emptyMap(),
        val stages: Map<String, InstallStage> = emptyMap(),
    )

    private data class HomeChrome(
        val updateCount: Int,
        val isSyncing: Boolean,
        val isLoading: Boolean,
        val message: String,
    )

    internal companion object {
        internal const val SEARCH_DEBOUNCE_MS = 350L
        internal const val INSTALL_SETTLE_MS = 1_500L
        /** Per-repo cap for the three home rows; the three lists stay additive. */
        internal const val RECENT_PER_SOURCE_LIMIT = 20
        internal const val CAROUSEL_LIMIT = 20
        internal const val PLAY_CLUSTER_LIMIT = 4

        /**
         * How many times in a row an update may fail for the same reason before the rest
         * of the run is abandoned. Three is enough to tell a bad app from a bad afternoon.
         */
    internal const val REPEATED_FAILURE_LIMIT = 3
    }
}

class AppStoreViewModelFactory(
    private val context: Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppStoreViewModel(context.applicationContext as Application, db) as T
}
