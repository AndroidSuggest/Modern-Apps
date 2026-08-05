package com.vayunmathur.appstore.util

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
import com.vayunmathur.appstore.data.CatalogRepository
import com.vayunmathur.appstore.data.InstalledAppsRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreLinks
import com.vayunmathur.appstore.data.SyncStep
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.InstallCoordinator
import com.vayunmathur.appstore.data.installer.InstallStage
import com.vayunmathur.appstore.data.play.PlayAuthState
import com.vayunmathur.appstore.data.play.PlayRepository
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.VerificationResult
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
    private val context: Context,
    db: AppDatabase,
) : ViewModel(), HomeActions, SearchActions, AppDetailActions, UpdatesActions, LibraryActions {

    private val catalog = CatalogRepository(context, db, viewModelScope)
    private val play = PlayRepository(context)
    private val installedRepo = InstalledAppsRepository(context)
    private val installer = InstallCoordinator(context, db, play) { ownSigningCertificates }

    /** SHA-256 of this app's own signing certificate — the Modern Apps trust root. */
    val ownSigningCertificates: Set<String> by lazy { ApkCertificates.selfSigners(context) }

    val repos = catalog.repos

    // --- Raw state ------------------------------------------------------------------

    private val _statusMessage = MutableStateFlow("")

    /** Kept apart from [_statusMessage] so a transient sync line can't erase it. */
    private val _playError = MutableStateFlow("")
    private val _isSyncing = MutableStateFlow(false)
    private val _isLoadingHome = MutableStateFlow(false)
    private val _isCheckingUpdates = MutableStateFlow(false)
    private val _lastUpdateCheck = MutableStateFlow(0L)

    private val _playSections = MutableStateFlow<List<AppSection>>(emptyList())
    private val _recentlyUpdated = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _categories = MutableStateFlow<List<String>>(emptyList())
    private val _selectedCategory = MutableStateFlow<String?>(null)
    private val _categoryApps = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _query = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _searchFilter = MutableStateFlow(SourceFilter.ALL)
    private val _isSearching = MutableStateFlow(false)
    private val _hasSearched = MutableStateFlow(false)

    private val _selectedApp = MutableStateFlow<UnifiedApp?>(null)
    private val _isLoadingDetails = MutableStateFlow(false)

    private val _catalogUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _playUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _libraryFilter = MutableStateFlow(SourceFilter.ALL)

    private var searchJob: Job? = null
    private var detailJob: Job? = null

    // --- Derived state ----------------------------------------------------------------

    /** Everything every screen needs to draw a row: installed, its icon, its progress. */
    private val chrome: StateFlow<RowChrome> = combine(
        installedRepo.apps,
        installedRepo.icons,
        installer.stages,
    ) { installed, icons, stages ->
        RowChrome(installed, installed.map { it.packageName }.toSet(), icons, stages)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RowChrome())

    val updates: StateFlow<List<UnifiedApp>> = combine(
        _catalogUpdates,
        _playUpdates,
        installedRepo.apps,
    ) { catalogUpdates, playUpdates, installed ->
        val installedVersions = installed.associate { it.packageName to it.versionCode }
        (catalogUpdates + playUpdates)
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
        _categoryApps,
        _selectedCategory,
    ) { modern, playSections, recent, categoryApps, category ->
        buildSections(modern, playSections, recent, categoryApps, category)
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
            _statusMessage,
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
        _statusMessage,
    ) { list, rows, checking, checkedAt, message ->
        UpdatesUiState(
            updates = list,
            installedIcons = rows.icons,
            stages = rows.stages,
            isChecking = checking,
            lastCheckedAt = checkedAt,
            statusMessage = message,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState())

    val library: StateFlow<LibraryUiState> = combine(
        chrome,
        catalog.packageIndex,
        _libraryFilter,
    ) { rows, index, filter ->
        // Anything the catalogue has never heard of is attributed to Play: it is on the
        // device and neither offline source lists it. This is a display label, not a
        // provenance claim — the app may equally have been sideloaded.
        fun sourceOf(pkg: String): AppSource =
            index[pkg]?.source?.let { runCatching { AppSource.valueOf(it) }.getOrNull() }
                ?: AppSource.PLAYSTORE

        val all = rows.installed.map { it.toUnifiedApp(sourceOf(it.packageName)) }
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
            installedRepo.refresh()
            play.restore()
            loadHome()
        }
        viewModelScope.launch {
            // Recompute catalogue-side updates whenever either half changes. The Play
            // half needs a network call and is driven by checkForUpdates() instead.
            combine(catalog.packageIndex, installedRepo.apps) { _, installed -> installed }
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
        viewModelScope.launch { installedRepo.refresh() }
    }

    // --- Home ---------------------------------------------------------------------

    /**
     * Fill the home screen.
     *
     * The offline rows come straight from Room and are already on screen by the time this
     * runs; what it adds is Play's editorial clusters and top chart, which need an
     * anonymous account. Those failing is normal — no network, no account — and leaves the
     * offline rows exactly as they were rather than emptying the screen.
     */
    private suspend fun loadHome() {
        _isLoadingHome.value = true
        _recentlyUpdated.value = catalog.recentlyUpdated(RECENT_LIMIT)

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

    private fun buildSections(
        modern: List<UnifiedApp>,
        playSections: List<AppSection>,
        recent: List<UnifiedApp>,
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
        addAll(playSections)
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

    override fun selectCategory(category: String?) {
        _selectedCategory.value = category
        viewModelScope.launch {
            _categoryApps.value = if (category == null) emptyList() else catalog.byCategory(category)
        }
    }

    override fun refresh() = syncSources()

    /** Re-download both offline catalogues, then reload the home rows from them. */
    fun syncSources() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            val report = catalog.sync { step ->
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
            loadHome()
            installedRepo.refresh()
        }
    }

    // --- Search -----------------------------------------------------------------------

    override fun setSearch(query: String) {
        _query.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _hasSearched.value = false
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _isSearching.value = true

            // Local first and published immediately: the F-Droid catalogue is on disk, so
            // there is no reason to make the user wait on Play before seeing anything.
            val local = catalog.searchLocal(query)
            _searchResults.value = rank(local, query)

            val remote = play.search(query)
            _searchResults.value = rank(merge(local, remote), query)
            _isSearching.value = false
            _hasSearched.value = true
        }
    }

    override fun setSearchFilter(filter: SourceFilter) {
        _searchFilter.value = filter
    }

    /**
     * Combine catalogue and Play hits, one row per package.
     *
     * Where both offer a package the catalogue entry wins. That is a provenance
     * preference, not a security ranking (see
     * [com.vayunmathur.appstore.data.security.TrustProfile]): the F-Droid and Modern Apps
     * rows carry a publisher key and a hash this app can check the download against,
     * which is simply more to show on the detail page than a Play listing has.
     */
    private fun merge(local: List<UnifiedApp>, remote: List<UnifiedApp>): List<UnifiedApp> =
        (local + remote).distinctBy { it.packageName }

    /** Exact hits first, then name prefixes, then everything else alphabetically. */
    private fun rank(apps: List<UnifiedApp>, query: String): List<UnifiedApp> {
        val q = query.trim().lowercase()
        fun score(app: UnifiedApp): Int {
            val name = app.name.lowercase()
            return when {
                name == q || app.packageName.lowercase() == q -> 0
                name.startsWith(q) -> 1
                name.split(' ').any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                else -> 4
            }
        }
        return apps.sortedWith(compareBy({ score(it) }, { it.name.lowercase() }))
    }

    // --- Detail -----------------------------------------------------------------------

    fun selectApp(app: UnifiedApp) {
        detailJob?.cancel()
        _selectedApp.value = app
        detailJob = viewModelScope.launch {
            // The catalogue row wins whenever there is one, even if the user tapped a Play
            // tile for the same package. It is the row an install would actually use — it
            // carries the signer and hash an authenticated index published — so showing
            // the Play listing here would describe a download this store is not going to
            // make. Only F-Droid and Modern Apps rows are ever cached, so this never
            // replaces one Play listing with another.
            val cached = catalog.byPackage(app.packageName)
            if (cached != null) {
                _selectedApp.value = cached
                return@launch
            }
            // Play listings from a cluster are shells: no description, no
            // screenshots, no version code. Fill them in before the page settles.
            if (app.source == AppSource.PLAYSTORE && app.screenshots.isEmpty()) {
                _isLoadingDetails.value = true
                val details = play.details(app.packageName)
                if (details != null) _selectedApp.value = details
                _isLoadingDetails.value = false
            }
        }
    }

    /** Open a package the store only knows by name, e.g. from a `market://` link. */
    fun selectPackage(packageName: String) {
        viewModelScope.launch {
            val known = catalog.byPackage(packageName)
            if (known != null) {
                selectApp(known)
                return@launch
            }
            _isLoadingDetails.value = true
            _selectedApp.value = UnifiedApp(
                packageName = packageName,
                source = AppSource.PLAYSTORE,
                name = packageName.substringAfterLast('.'),
            )
            val details = play.details(packageName)
            if (details != null) _selectedApp.value = details
            _isLoadingDetails.value = false
        }
    }

    fun clearSelection() {
        detailJob?.cancel()
        _selectedApp.value = null
    }

    // --- Actions ----------------------------------------------------------------------

    override fun install(app: UnifiedApp) {
        viewModelScope.launch {
            val outcome = installer.install(app)
            AppMessages.show(
                when (val v = outcome.verification) {
                    is VerificationResult.Rejected ->
                        context.getString(R.string.install_blocked, app.name, v.reason)
                    is VerificationResult.Unverified ->
                        if (outcome.started) context.getString(R.string.install_started_unverified, app.name)
                        else context.getString(R.string.install_failed, app.name)
                    is VerificationResult.Verified ->
                        if (outcome.started) context.getString(R.string.install_started, app.name)
                        else context.getString(R.string.install_failed, app.name)
                }
            )
            if (outcome.started) {
                // PackageInstaller commits asynchronously; give it a moment before the
                // installed list is re-read, or the row still shows the old version.
                delay(INSTALL_SETTLE_MS)
                installedRepo.refresh()
            }
        }
    }

    override fun dismissInstallFailure(packageName: String) = installer.dismissFailure(packageName)

    override fun openApp(packageName: String) {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()
        if (launchIntent == null) {
            openInPlayStore(packageName)
            return
        }
        startActivity(launchIntent)
    }

    override fun uninstallApp(packageName: String) {
        val started = startActivity(
            Intent(Intent.ACTION_DELETE, "package:$packageName".toUri())
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        )
        if (!started) AppMessages.show(context.getString(R.string.uninstaller_unavailable))
    }

    override fun openInPlayStore(packageName: String) {
        if (startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))) return
        startActivity(Intent(Intent.ACTION_VIEW, PlayStoreLinks.playStoreUrl(packageName).toUri()))
    }

    override fun openInBrowser(url: String) {
        if (url.isBlank()) return
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }

    override fun shareApp(app: UnifiedApp) {
        val link = app.website ?: PlayStoreLinks.playStoreUrl(app.packageName)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, context.getString(R.string.share_app_text, app.name, link))
        }
        startActivity(Intent.createChooser(share, null))
    }

    // --- Updates ----------------------------------------------------------------------

    override fun checkForUpdates() {
        if (_isCheckingUpdates.value) return
        viewModelScope.launch {
            _isCheckingUpdates.value = true
            _statusMessage.value = context.getString(R.string.updates_checking)

            installedRepo.refresh()
            _catalogUpdates.value = catalog.updatesFor(installedRepo.apps.value)

            // Only ask Play about packages neither offline source lists — for the rest the
            // catalogue already answered, and Play would just re-answer it over the network.
            val index = catalog.packageIndex.value
            val installed = installedRepo.apps.value
            val playCandidates = installed
                .filter { it.packageName !in index }
                .map { it.packageName }

            val remote = play.details(playCandidates).associateBy { it.packageName }
            _playUpdates.value = installed.mapNotNull { inst ->
                remote[inst.packageName]?.takeIf { it.versionCode > inst.versionCode }
            }

            _lastUpdateCheck.value = System.currentTimeMillis()
            _statusMessage.value = ""
            _isCheckingUpdates.value = false
        }
    }

    override fun updateAll() {
        viewModelScope.launch {
            // Sequential on purpose: PackageInstaller shows a confirmation dialog per app
            // on most devices, and firing them concurrently buries the user in prompts.
            for (app in updates.value) {
                installer.install(app)
            }
            delay(INSTALL_SETTLE_MS)
            installedRepo.refresh()
        }
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

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
        const val INSTALL_SETTLE_MS = 1_500L
        const val RECENT_LIMIT = 30
        const val CAROUSEL_LIMIT = 20
        const val PLAY_CLUSTER_LIMIT = 4
    }
}

class AppStoreViewModelFactory(
    private val context: Context,
    private val db: AppDatabase,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AppStoreViewModel(context.applicationContext, db) as T
}
