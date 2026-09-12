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
import com.vayunmathur.appstore.data.RestrictedPackages
import com.vayunmathur.appstore.data.SandboxedGooglePlay
import com.vayunmathur.appstore.data.SettingsRepository
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.accrescent.AccrescentRepository
import com.vayunmathur.appstore.data.grapheneos.GrapheneOSRepository
import com.vayunmathur.appstore.data.grapheneos.toUnifiedApp
import com.vayunmathur.appstore.data.installer.InstallCoordinator
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
    internal val grapheneOS = GrapheneOSRepository(context)
    internal val installedRepo = InstalledAppsRepository(context)
    internal val settings = SettingsRepository(context, viewModelScope)
    internal val installer =
        InstallCoordinator(context, db, play, accrescent, grapheneOS) { ownSigningCertificates }

    /** Off-by-default: the periodic check may also download and install updates unattended. */
    val autoInstallUpdates: StateFlow<Boolean> = settings.autoInstallUpdates

    /** The sources the user has left switched on. See [AppSource.TOGGLEABLE]. */
    val enabledSources: StateFlow<Set<AppSource>> = settings.enabledSources

    /** SHA-256 of this app's own signing certificate — the Modern Apps trust root. */
    val ownSigningCertificates: Set<String> by lazy { ApkCertificates.selfSigners(context) }

    val repos = catalog.repos

    // --- Raw state ------------------------------------------------------------------

    internal val _statusMessage = MutableStateFlow("")

    /** Kept apart from [_statusMessage] so a transient sync line can't erase it. */
    private val _playError = MutableStateFlow("")
    internal val _isSyncing = MutableStateFlow(false)
    internal val _isLoadingHome = MutableStateFlow(false)
    internal val _isCheckingUpdates = MutableStateFlow(false)
    internal val _lastUpdateCheck = MutableStateFlow(0L)

    internal val _playSections = MutableStateFlow<List<AppSection>>(emptyList())
    internal val _recentlyUpdated = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /** Accrescent listings for the home carousel, from the gRPC listing API. */
    internal val _accrescentApps = MutableStateFlow<List<UnifiedApp>>(emptyList())

    /**
     * App ids Accrescent's signed allowlist vouches for. Drives library attribution for an
     * installed Accrescent app. Empty until the first repodata refresh populates it.
     */
    internal val _accrescentPackages = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The Sandboxed Google Play bundle rows. Seeded with stand-ins so the section is on
     * screen immediately; [loadHome] replaces them with rows built from GrapheneOS's signed
     * index. These install from GrapheneOS's release server, never Play.
     * Kept in [SandboxedGooglePlay.PACKAGES] order so the ordered install reads straight off it.
     *
     * Empty on stock Android. These packages only work alongside the gmscompat layer, which
     * is part of the OS, so on a device without it the section would offer three installs
     * that cannot function.
     */
    internal val _sandboxedGooglePlay = MutableStateFlow(
        if (RestrictedPackages.isGrapheneOS(context)) SandboxedGooglePlay.placeholders()
        else emptyList()
    )
    internal val _categories = MutableStateFlow<List<String>>(emptyList())
    internal val _selectedCategory = MutableStateFlow<String?>(null)
    internal val _categoryApps = MutableStateFlow<List<UnifiedApp>>(emptyList())

    internal val _query = MutableStateFlow("")
    internal val _searchResults = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val _searchFilter = MutableStateFlow(SourceFilter.ALL)
    internal val _isSearching = MutableStateFlow(false)
    internal val _hasSearched = MutableStateFlow(false)

    internal val _selectedApp = MutableStateFlow<UnifiedApp?>(null)
    internal val _isLoadingDetails = MutableStateFlow(false)

    internal val _catalogUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val _playUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val _accrescentUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    internal val _grapheneOSUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _libraryFilter = MutableStateFlow(SourceFilter.ALL)

    /**
     * Installed packages Play confirmed it actually hosts.
     *
     * Drives library attribution so a sideloaded app isn't labelled Play just for being
     * unrecognised. Empty until the first resolution; a package no offline source lists
     * stays out of the library until Play vouches for it. A failed lookup leaves the
     * previous answer in place rather than emptying it — see [refreshPlayInstalledPackages].
     */
    internal val _playInstalledPackages = MutableStateFlow<Set<String>>(emptySet())

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
        combine(_statusMessage, PlayHttpClient.throttled) { message, throttled ->
            if (throttled) context.getString(R.string.play_rate_limited) else message
        }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val updates: StateFlow<List<UnifiedApp>> = combine(
        _catalogUpdates,
        _playUpdates,
        _accrescentUpdates,
        _grapheneOSUpdates,
        installedRepo.apps,
    ) { catalogUpdates, playUpdates, accrescentUpdates, grapheneOSUpdates, installed ->
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        (catalogUpdates + playUpdates + accrescentUpdates + grapheneOSUpdates)
            // The surviving row's source decides which download-and-verify path the update
            // takes, so it has to be the same precedence search and the library use.
            .sortedBy { it.source.priority }
            .distinctBy { it.packageName }
            // Re-check against what is on the device rather than trusting the lists.
            // _playUpdates is a snapshot from the last network check, so without this a
            // Play app stays in the list after it has been updated, until the next check.
            .filter { app ->
                val installedVersion = installedVersions[app.packageName] ?: return@filter false
                app.versionCode > installedVersion
            }
            .sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val sections: StateFlow<List<AppSection>> = combine(
        catalog.modernApps,
        _playSections,
        _recentlyUpdated,
        _sandboxedGooglePlay,
        combine(
            _categoryApps,
            _selectedCategory,
            _accrescentApps,
        ) { apps, category, accrescent -> Triple(apps, category, accrescent) },
    ) { modern, playSections, recent, sandboxed, (categoryApps, category, accrescentApps) ->
        buildSections(modern, playSections, recent, sandboxed, accrescentApps, categoryApps, category)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val home: StateFlow<HomeUiState> = combine(
        sections,
        _categories,
        _selectedCategory,
        chrome,
        combine(
            updates,
            _isSyncing,
            _isLoadingHome,
            statusLine,
            _playError,
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
        _query,
        combine(_searchResults, _searchFilter) { results, filter ->
            results to filter
        },
        _isSearching,
        _hasSearched,
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
        _selectedApp,
        _isLoadingDetails,
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
        _isCheckingUpdates,
        _lastUpdateCheck,
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
        _playInstalledPackages,
        _accrescentPackages,
        _libraryFilter,
    ) { rows, index, playPackages, accrescentPackages, filter ->
        // Source attribution, highest priority first:
        //  1. GrapheneOS's Sandboxed Google Play components (GSF/GMS/Vending) are Google's
        //     APKs re-hosted by GrapheneOS and installed from its release server, so they
        //     are attributed to GrapheneOS — never Play — even though Play lists them too.
        //  2. Whatever the offline catalogue (F-Droid / Modern Apps) recorded.
        //  3. Accrescent, for packages its signed allowlist vouches for.
        //  4. Play, but only for packages Play confirmed it hosts (see _playInstalledPackages).
        // A package no source vouches for — sideloaded, or from a store we don't track — is
        // left out entirely rather than mislabelled as Play.
        fun sourceOf(pkg: String): AppSource? = when {
            pkg in SandboxedGooglePlay.PACKAGES -> AppSource.GRAPHENEOS
            else -> index[pkg]?.source?.let { runCatching { AppSource.valueOf(it) }.getOrNull() }
                ?: AppSource.ACCRESCENT.takeIf { pkg in accrescentPackages }
                ?: AppSource.PLAYSTORE.takeIf { pkg in playPackages }
        }

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
                .collect { installed -> _catalogUpdates.value = catalog.updatesFor(installed) }
        }
        viewModelScope.launch {
            _categories.value = catalog.categories()
        }
        viewModelScope.launch {
            // Say so when Play is unreachable. Without this the store just quietly shows
            // fewer results, which looks like the search finding nothing.
            play.authState.collect { state ->
                _playError.value = (state as? PlayAuthState.Error)
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
     * Only packages neither offline source lists and that aren't GrapheneOS components are
     * worth asking about — the rest are already attributed. Play returning nothing (no
     * account, no network) leaves the previous answer in place rather than emptying the
     * library, so a transient failure can't hide apps Play was known to host.
     */
    private suspend fun refreshPlayInstalledPackages(
        enabled: Set<AppSource> = enabledSources.value,
    ) {
        if (AppSource.PLAYSTORE !in enabled) {
            _playInstalledPackages.value = emptySet()
            return
        }
        val index = catalog.packageIndex.value
        val candidates = installedRepo.apps.value
            .map { it.packageName }
            .filter { it !in index && it !in SandboxedGooglePlay.PACKAGES }
        if (candidates.isEmpty()) {
            _playInstalledPackages.value = emptySet()
            return
        }
        val available = play.details(candidates).map { it.packageName }.toSet()
        if (available.isNotEmpty()) _playInstalledPackages.value = available
    }

    // (loadAccrescent/accrescentUpdate live in AppStoreUpdateOps.kt.)

    // --- Home ---------------------------------------------------------------------
    // Implementations live in AppStoreHomeOps.kt.

    override fun selectCategory(category: String?) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _categoryApps.value = if (category == null) emptyList() else catalog.byCategory(category)
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
            _categories.value = catalog.categories()
            loadHome(next)
            loadAccrescent(next)
        }
    }

    /** Drop what a source had already contributed to the screens. */
    private fun forgetSource(source: AppSource) {
        when (source) {
            AppSource.PLAYSTORE -> {
                _playSections.value = emptyList()
                _playUpdates.value = emptyList()
                _playInstalledPackages.value = emptySet()
            }
            AppSource.ACCRESCENT -> {
                _accrescentApps.value = emptyList()
                _accrescentPackages.value = emptySet()
                _accrescentUpdates.value = emptyList()
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
        _searchFilter.value = filter
    }

    // --- Detail -----------------------------------------------------------------------
    // Bodies live in AppStoreDetailOps.kt as public extensions
    // (called from MainActivity + AppDetailPage via the concrete class).

    // --- Actions ----------------------------------------------------------------------
    // Bodies live in AppStoreActionOps.kt; these overrides keep interface conformance.

    override fun install(app: UnifiedApp) = installImpl(app)

    override fun dismissInstallFailure(packageName: String) = installer.dismissFailure(packageName)

    /**
     * Install the Sandboxed Google Play bundle in dependency order.
     *
     * Sequential and awaited, like [updateAll]: Play Services provides the provider Vending
     * talks to, so it must land first, and each first-time install shows its own
     * PackageInstaller confirmation - firing them at once would bury the user in prompts and
     * let the store client install before the services it needs.
     */
    override fun installSandboxedGooglePlay() = installSandboxedGooglePlayImpl()

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
        _libraryFilter.value = filter
    }

    // --- Helpers --------------------------------------------------------------------------

    private fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }

    private fun String.toUri(): Uri = Uri.parse(this)

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
        internal const val RECENT_LIMIT = 30
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
