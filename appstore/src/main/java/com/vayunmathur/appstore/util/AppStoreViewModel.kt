package com.vayunmathur.appstore.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.helpers.AuthHelper
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CachedAppEntity
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.FDroidAppProvider
import com.vayunmathur.appstore.data.FDroidRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreAppProvider
import com.vayunmathur.appstore.data.PlayStoreDataSource
import com.vayunmathur.appstore.data.ModernAppsProvider
import com.vayunmathur.appstore.data.ModernAppsRepo
import com.vayunmathur.appstore.data.RepoEntity
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.PlayDownloader
import com.vayunmathur.appstore.data.installer.SessionInstaller
import com.vayunmathur.appstore.data.security.ApkCertificates
import com.vayunmathur.appstore.data.security.InstallRequirement
import com.vayunmathur.appstore.data.security.SecurityTier
import com.vayunmathur.appstore.data.security.VerificationResult
import com.vayunmathur.appstore.data.toEntity
import com.vayunmathur.appstore.data.toUnifiedApp
import com.vayunmathur.appstore.data.play.AnonymousAuthRepository
import com.vayunmathur.appstore.data.play.CertUtil
import com.vayunmathur.appstore.data.play.DeviceInfoProvider
import com.vayunmathur.appstore.data.play.PlayHttpClient
import com.vayunmathur.appstore.data.play.PlayStoreApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private val Context.authDataStore by preferencesDataStore(name = "play_auth")
private val PLAY_AUTH_JSON_KEY = stringPreferencesKey("play_auth_json")
private val PLAY_AUTH_DISPENSED_AT_KEY = longPreferencesKey("play_auth_dispensed_at")

/**
 * How long a dispensed anonymous account is trusted before it is replaced.
 *
 * The dispenser hands out shared accounts that Google eventually invalidates,
 * and a dead one fails silently - Play calls just start erroring and the app
 * falls back to scraping. Cycling on age keeps that from being the way we find
 * out.
 */
private const val PLAY_AUTH_MAX_AGE_MS = 12L * 60 * 60 * 1000

enum class InstalledFilter { ALL, MODERN_APPS, FDROID, PLAYSTORE }

sealed class PlayAuthState {
    object Idle : PlayAuthState()
    object Authenticating : PlayAuthState()
    data class Authenticated(val authData: AuthData) : PlayAuthState()
    data class Error(val message: String) : PlayAuthState()
}

class AppStoreViewModel(
    private val context: Context,
    private val db: AppDatabase
) : ViewModel(), BrowseActions, AppDetailActions {

    private val modernProvider = ModernAppsProvider(context.applicationContext)
    private val fdroidProvider = FDroidAppProvider(db, context.applicationContext)
    private val playProvider = PlayStoreAppProvider()

    /**
     * Resolution order, best-verified first. Modern Apps wins any package it offers,
     * because it is the only source whose APKs are checked against this store's own
     * signing certificate; F-Droid (reproduced builds only) beats Play, which cannot be
     * verified against any publisher key at all.
     */
    val providers: List<AppProvider> get() = listOf(modernProvider, fdroidProvider, playProvider)

    /** Per-package result of the last install attempt's certificate/hash checks. */
    private val _verification = MutableStateFlow<Map<String, VerificationResult>>(emptyMap())
    val verification: StateFlow<Map<String, VerificationResult>> = _verification

    /** SHA-256 of this app's own signing certificate — the Modern Apps trust root. */
    val ownSigningCertificates: Set<String> by lazy { ApkCertificates.selfSigners(context) }

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _playSearchResults = MutableStateFlow<List<UnifiedApp>>(emptyList())
    private val _topCharts = MutableStateFlow<List<UnifiedApp>>(emptyList())

    private val _selectedApp = MutableStateFlow<UnifiedApp?>(null)
    val selectedApp: StateFlow<UnifiedApp?> = _selectedApp

    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress

    private val _installedApps = MutableStateFlow<List<InstalledInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledInfo>> = _installedApps

    private val _installedIcons = MutableStateFlow<Map<String, Drawable>>(emptyMap())
    val installedIcons: StateFlow<Map<String, Drawable>> = _installedIcons

    private val _activeRepo = MutableStateFlow<String?>(null)

    private val _installedSourceMap = MutableStateFlow<Map<String, AppSource>>(emptyMap())
    val installedSourceMap: StateFlow<Map<String, AppSource>> = _installedSourceMap

    private val _installedFilter = MutableStateFlow(InstalledFilter.ALL)
    val installedFilter: StateFlow<InstalledFilter> = _installedFilter

    // Play auth + updates
    private val _playAuthState = MutableStateFlow<PlayAuthState>(PlayAuthState.Idle)
    val playAuthState: StateFlow<PlayAuthState> = _playAuthState

    private val _playUpdates = MutableStateFlow<List<UnifiedApp>>(emptyList())
    val playUpdates: StateFlow<List<UnifiedApp>> = _playUpdates

    val repos = db.repoDao().allFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val cachedApps = db.cachedAppDao().allFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Play infrastructure
    private val anonAuthRepo = AnonymousAuthRepository()
    private val playHttpClient = PlayHttpClient()
    private val sessionInstaller = SessionInstaller(context)
    private val playDownloader = PlayDownloader(context)
    private var cachedAuthData: AuthData? = null

    /** When [cachedAuthData] was dispensed, for age-based cycling. */
    private var authDispensedAt: Long = 0L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val combinedBrowse: StateFlow<List<UnifiedApp>> = combine(
        cachedApps, _playSearchResults, _topCharts, _searchQuery, _activeRepo
    ) { cached, playSearch, topCharts, query, activeRepo ->
        val local = cached.map { it.toUnifiedApp() }
        val modernAll = local.filter { it.source == AppSource.MODERN_APPS }
        val fdroidAll = local.filter { it.source == AppSource.FDROID }
        val modernPkgs = modernAll.map { it.packageName }.toSet()
        val fdroidPkgs = fdroidAll.map { it.packageName }.toSet()

        // A package offered by more than one source is attributed to the best one.
        fun infer(pkg: String): AppSource = when {
            modernPkgs.contains(pkg) -> AppSource.MODERN_APPS
            fdroidPkgs.contains(pkg) -> AppSource.FDROID
            else -> AppSource.PLAYSTORE
        }

        val visible = if (activeRepo != null) local.filter { it.repoUrl == activeRepo } else local
        val modern = visible.filter { it.source == AppSource.MODERN_APPS }
        val fdroid = visible.filter { it.source == AppSource.FDROID }

        val infSearch = playSearch.map { it.copy(source = infer(it.packageName)) }
        val infCharts = topCharts.map { it.copy(source = infer(it.packageName)) }

        val list = if (query.isBlank()) {
            modern + fdroid + (if (infSearch.isEmpty()) infCharts else infSearch)
        } else {
            val q = query.lowercase()
            fun matches(app: UnifiedApp) = app.name.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                app.summary.lowercase().contains(q)
            modern.filter(::matches) + fdroid.filter(::matches) + infSearch
        }
        // distinctBy keeps the first occurrence, so the ordering above *is* the priority.
        AppProvider.filterTargetSdk(list.distinctBy { it.packageName })
            .sortedWith(compareBy({ SecurityTier.of(it.source).ordinal }, { it.name.lowercase() }))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredInstalled: StateFlow<List<InstalledInfo>> = combine(
        _installedApps, _installedSourceMap, _installedFilter
    ) { installed, srcMap, filter ->
        val inStore = installed.filter { srcMap.containsKey(it.packageName) }
        when (filter) {
            InstalledFilter.ALL -> inStore
            InstalledFilter.MODERN_APPS -> inStore.filter { srcMap[it.packageName] == AppSource.MODERN_APPS }
            InstalledFilter.FDROID -> inStore.filter { srcMap[it.packageName] == AppSource.FDROID }
            InstalledFilter.PLAYSTORE -> inStore.filter { srcMap[it.packageName] == AppSource.PLAYSTORE }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fdroidUpdates: StateFlow<List<UnifiedApp>> = combine(
        cachedApps, _installedApps, _installedSourceMap
    ) { cached, installed, srcMap ->
        cached.mapNotNull { entity ->
            val inst = installed.find { it.packageName == entity.packageName } ?: return@mapNotNull null
            if (srcMap[inst.packageName] == null) return@mapNotNull null
            if (entity.versionCode > inst.versionCode) {
                entity.toUnifiedApp()
            } else null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val combinedUpdates: StateFlow<List<UnifiedApp>> = combine(
        fdroidUpdates, _playUpdates
    ) { fdroid, play ->
        (fdroid + play).distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var searchJob: Job? = null
    private var topJob: Job? = null

    init {
        viewModelScope.launch {
            ensureDefaultRepos()
            refreshInstalled()
            loadTopCharts()
            restoreAuthData()
        }
        viewModelScope.launch {
            cachedApps.collect { list ->
                fdroidProvider.cachedPackageNames = list
                    .filter { it.source == AppSource.FDROID.name }
                    .map { it.packageName }
                    .toSet()
                modernProvider.primeFrom(list)
            }
        }
    }

    private suspend fun restoreAuthData() {
        try {
            val prefs = context.authDataStore.data.first()
            val jsonStr = prefs[PLAY_AUTH_JSON_KEY] ?: return
            if (jsonStr.isBlank()) return
            val dispensedAt = prefs[PLAY_AUTH_DISPENSED_AT_KEY] ?: 0L
            if (System.currentTimeMillis() - dispensedAt > PLAY_AUTH_MAX_AGE_MS) {
                // Past its useful life; ensurePlayAuth will dispense a new one
                // rather than spend a round-trip proving this is dead.
                invalidatePlayAuth()
                return
            }
            try {
                val authData = json.decodeFromString(AuthData.serializer(), jsonStr)
                // Validate quickly via isValid (network check)
                val valid = withContext(Dispatchers.IO) {
                    try {
                        AuthHelper.using(playHttpClient).isValid(authData)
                    } catch (_: Exception) { false }
                }
                if (valid) {
                    cachedAuthData = authData
                    authDispensedAt = dispensedAt
                    _playAuthState.value = PlayAuthState.Authenticated(authData)
                } else {
                    invalidatePlayAuth()
                }
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    /**
     * Forget the current anonymous account so the next Play call dispenses a
     * fresh one. Clears the persisted copy too, or a restart would restore the
     * dead credentials.
     */
    private suspend fun invalidatePlayAuth() {
        cachedAuthData = null
        authDispensedAt = 0L
        _playAuthState.value = PlayAuthState.Idle
        try {
            context.authDataStore.edit { prefs ->
                prefs.remove(PLAY_AUTH_JSON_KEY)
                prefs.remove(PLAY_AUTH_DISPENSED_AT_KEY)
            }
        } catch (_: Exception) { }
    }

    /**
     * Run a Play API call, cycling the anonymous account if it has died.
     *
     * Dispensed accounts are shared and Google invalidates them without
     * warning. At the call site that failure is indistinguishable from any
     * other error, which is why Play access used to stay broken until the app
     * was restarted - every later call just fell through to scraping. Here a
     * failure drops the account, dispenses another and retries once.
     *
     * Returns null when there is no account to use or both attempts failed, so
     * callers keep their existing fallback. Deliberately does *not* dispense
     * when nothing is cached: being signed out is the caller's cue to scrape,
     * and dispensing there would put a retrying network call in front of every
     * keystroke of search.
     */
    private suspend fun <T> withPlayApi(block: suspend (PlayStoreApi) -> T): T? {
        val cached = cachedAuthData ?: return null
        try {
            return block(PlayStoreApi(cached, playHttpClient))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AppStoreVM", "Play call failed; cycling anonymous account", e)
        }

        invalidatePlayAuth()
        val fresh = ensurePlayAuth().getOrNull() ?: return null
        return try {
            block(PlayStoreApi(fresh, playHttpClient))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("AppStoreVM", "Play call failed again after cycling", e)
            null
        }
    }

    private suspend fun persistAuthData(authData: AuthData) {
        try {
            val jsonStr = json.encodeToString(AuthData.serializer(), authData)
            context.authDataStore.edit { prefs ->
                prefs[PLAY_AUTH_JSON_KEY] = jsonStr
                prefs[PLAY_AUTH_DISPENSED_AT_KEY] = authDispensedAt
            }
        } catch (_: Exception) { }
    }

    private suspend fun ensurePlayAuth(): Result<AuthData> {
        // Return cached if present, young enough, and still valid.
        cachedAuthData?.let { cached ->
            if (System.currentTimeMillis() - authDispensedAt <= PLAY_AUTH_MAX_AGE_MS) {
                val valid = withContext(Dispatchers.IO) {
                    try { AuthHelper.using(playHttpClient).isValid(cached) } catch (_: Exception) { false }
                }
                if (valid) return Result.success(cached)
            }
            invalidatePlayAuth()
        }

        _playAuthState.value = PlayAuthState.Authenticating
        _syncMessage.value = "Connecting to the Play Store…"

        val deviceProps = DeviceInfoProvider.buildDeviceProperties(context)
        val result = anonAuthRepo.ensureAuthData(context, deviceProps)

        if (result.isSuccess) {
            val authData = result.getOrNull()!!
            cachedAuthData = authData
            authDispensedAt = System.currentTimeMillis()
            _playAuthState.value = PlayAuthState.Authenticated(authData)
            _syncMessage.value = "Connected to the Play Store"
            persistAuthData(authData)
            kotlinx.coroutines.delay(1000)
            _syncMessage.value = ""
            return Result.success(authData)
        } else {
            val err = result.exceptionOrNull() ?: Exception("Auth failed")
            val msg = anonAuthRepo.errorMessage(err)
            _playAuthState.value = PlayAuthState.Error(msg)
            _syncMessage.value = "Couldn't connect to the Play Store"
            return Result.failure(err)
        }
    }

    /**
     * There is exactly one supported F-Droid repository and it cannot be changed, so this
     * both seeds it and prunes anything else a previous version may have stored.
     */
    private suspend fun ensureDefaultRepos() {
        val existing = db.repoDao().all()
        existing.filter { it.url != DefaultRepos.FDROID_MAIN }.forEach {
            db.repoDao().deleteByUrl(it.url)
            db.cachedAppDao().deleteByRepo(it.url)
        }
        if (existing.none { it.url == DefaultRepos.FDROID_MAIN }) {
            db.repoDao().upsert(
                RepoEntity(
                    url = DefaultRepos.FDROID_MAIN,
                    name = "F-Droid",
                    enabled = true,
                    fingerprint = FDroidRepository.FDROID_SIGNING_CERT_SHA256,
                )
            )
        }
    }

    fun loadTopCharts() {
        topJob?.cancel()
        topJob = viewModelScope.launch {
            _syncMessage.value = "Loading top charts…"
            // If we have Play auth, try gplayapi search? For topCharts we keep scraping as fallback (cheap)
            val charts = playProvider.fetchAll()
            // If auth ready, optionally enrich via gplayapi top charts later – keep scraping for V1
            _topCharts.value = charts
            _syncMessage.value = ""
        }
    }

    override fun setSearch(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _playSearchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            _syncMessage.value = "Searching…"
            // If Play auth cached, use gplayapi search; else fallback to scraping provider
            // withPlayApi cycles the anonymous account if it has been
            // invalidated, so a dead one costs a retry rather than silently
            // demoting every later search to scraping.
            val gplay = withPlayApi { api -> api.search(q) }
            val results = if (!gplay.isNullOrEmpty()) gplay else playProvider.search(q)
            _playSearchResults.value = results
            _syncMessage.value = ""
        }
    }

    fun syncRepos() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            val notes = mutableListOf<String>()

            // Order matters: packageName is the cache table's primary key, so when a
            // package is published by both sources the *later* upsert wins the row.
            // Modern Apps must therefore go last — it outranks F-Droid, and every app in
            // this repo is also on F-Droid.
            // Downloads F-Droid's reproducibility feed (~24 MB) and the signed index
            // (~54 MB); both must verify or the existing catalogue is left alone.
            _syncMessage.value = "Checking F-Droid builds…"
            try {
                val total = fdroidProvider.syncIntoDb()
                notes += "$total apps from F-Droid" +
                    (", ${fdroidProvider.lastFilteredOut} skipped"
                        .takeIf { fdroidProvider.lastFilteredOut > 0 } ?: "")
            } catch (_: Exception) {
                notes += "F-Droid didn't sync"
            }

            _syncMessage.value = "Checking Modern Apps…"
            try {
                notes += "${modernProvider.syncIntoDb(db)} from Modern Apps"
            } catch (_: Exception) {
                notes += "Modern Apps didn't sync"
            }

            _syncMessage.value = notes.joinToString(" · ")
            kotlinx.coroutines.delay(2500)
            _syncMessage.value = ""
            _isSyncing.value = false
            refreshInstalled()
        }
    }

    fun refreshInstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val all = pm.getInstalledApplications(PackageManager.MATCH_ALL)
            val userApps = all.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            val installed = userApps.mapNotNull { ai ->
                try {
                    val pi = pm.getPackageInfo(ai.packageName, 0)
                    InstalledInfo(
                        packageName = ai.packageName,
                        name = pm.getApplicationLabel(ai).toString(),
                        versionName = pi.versionName,
                        versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                    )
                } catch (_: Exception) { null }
            }.sortedBy { it.name.lowercase() }

            val icons = userApps.mapNotNull { ai ->
                try { ai.packageName to pm.getApplicationIcon(ai.packageName) } catch (_: Exception) { null }
            }.toMap()

            withContext(Dispatchers.Main) {
                _installedApps.value = installed
                _installedIcons.value = icons
            }
            resolveInstalledSources(installed.map { it.packageName })
        }
    }

    /**
     * Attribute each installed package to the best-verified source that offers it.
     *
     * Reads the cached rows directly rather than going through the providers'
     * in-memory caches: those are primed asynchronously from the same table, so asking
     * them right after a sync could race and report a Modern Apps package as absent.
     */
    private fun resolveInstalledSources(packages: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try { db.cachedAppDao().all() } catch (_: Exception) { emptyList() }
            val bySource = rows.groupBy(
                { runCatching { AppSource.valueOf(it.source) }.getOrNull() },
                { it.packageName },
            ).mapValues { (_, v) -> v.toSet() }
            val modern = bySource[AppSource.MODERN_APPS].orEmpty()
            val fdroid = bySource[AppSource.FDROID].orEmpty()

            val map = mutableMapOf<String, AppSource>()
            for (pkg in packages) {
                map[pkg] = when {
                    pkg in modern -> AppSource.MODERN_APPS
                    pkg in fdroid -> AppSource.FDROID
                    playProvider.isPresent(pkg) -> AppSource.PLAYSTORE
                    else -> continue
                }
            }
            _installedSourceMap.value = map
        }
    }

    fun setInstalledFilter(filter: InstalledFilter) { _installedFilter.value = filter }

    fun selectApp(app: UnifiedApp) {
        // Prefer the cached row: it carries the signer/hash the authenticated index gave
        // us, which a UnifiedApp reconstructed from a Play listing would not have.
        val cachedRow = cachedApps.value.firstOrNull { it.packageName == app.packageName }
        val resolved = cachedRow?.toUnifiedApp() ?: app.copy(source = AppSource.PLAYSTORE)
        val inferredSource = resolved.source
        _selectedApp.value = resolved

        if (inferredSource == AppSource.PLAYSTORE && app.description.isBlank()) {
            // Try to fetch via gplayapi if auth available, else via scraping provider
            viewModelScope.launch {
                val details = withPlayApi { api -> api.getDetails(app.packageName) }
                    ?: playProvider.getDetails(app.packageName)
                if (details != null) {
                    _selectedApp.value = details.copy(source = AppSource.PLAYSTORE)
                }
            }
        }
    }

    override fun openApp(packageName: String) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            } else {
                openInPlayStore(packageName)
            }
        } catch (_: Exception) {
            openInPlayStore(packageName)
        }
    }

    override fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            _syncMessage.value = "Couldn't open the uninstaller"
        }
    }

    override fun openInPlayStore(pkg: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreDataSource.playStoreUrl(pkg))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) { }
        }
    }

    override fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
    }

    // Repositories are fixed: exactly one F-Droid repo with a hard-pinned certificate,
    // plus the built-in Modern Apps source. There is deliberately no add/remove/toggle —
    // an arbitrary user-added repo could not be held to the guarantees the tiers claim.

    fun syncPlayUpdates() {
        viewModelScope.launch {
            if (_installedApps.value.isEmpty()) {
                refreshInstalled()
                kotlinx.coroutines.delay(500)
            }
            val authResult = ensurePlayAuth()
            if (authResult.isFailure) return@launch

            val authData = authResult.getOrNull() ?: return@launch
            _syncMessage.value = "Checking the Play Store for updates…"
            try {
                val api = PlayStoreApi(authData, playHttpClient)
                val srcMap = _installedSourceMap.value
                val playInstalled = _installedApps.value.filter { srcMap[it.packageName] == AppSource.PLAYSTORE }
                val updates = mutableListOf<UnifiedApp>()
                // Batch to avoid hammering
                for (inst in playInstalled) {
                    try {
                        val remote = api.getDetails(inst.packageName)
                        if (remote != null && remote.versionCode > inst.versionCode) {
                            updates.add(remote)
                        }
                    } catch (_: Exception) { }
                    // Throttle
                    kotlinx.coroutines.delay(150)
                }
                _playUpdates.value = updates
                _syncMessage.value =
                    if (updates.isNotEmpty()) "${updates.size} update(s) on the Play Store"
                    else "No Play Store updates"
                kotlinx.coroutines.delay(1500)
                _syncMessage.value = ""
            } catch (_: Exception) {
                _syncMessage.value = "Couldn't check the Play Store for updates"
            }
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            val allUpdates = combinedUpdates.value
            for (app in allUpdates) {
                downloadAndInstall(app)
                // Wait for progress to clear before next
                var waited = 0
                while (_downloadProgress.value.containsKey(app.packageName) && waited < 120) {
                    kotlinx.coroutines.delay(1000)
                    waited++
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    /**
     * Apply an install outcome: surface the verdict, and persist a newly observed source
     * stamp so the next update for this package is pinned to it.
     */
    private suspend fun applyOutcome(app: UnifiedApp, outcome: SessionInstaller.Outcome) {
        _verification.value = _verification.value + (app.packageName to outcome.verification)
        outcome.verification.stamp?.let { stamp ->
            runCatching {
                db.pinnedStampDao().upsert(
                    com.vayunmathur.appstore.data.PinnedStampEntity(
                        packageName = app.packageName,
                        stampSha256 = stamp,
                        firstSeen = System.currentTimeMillis(),
                    )
                )
            }
        }
        _syncMessage.value = when (val v = outcome.verification) {
            is VerificationResult.Rejected -> "${app.name} was blocked: ${v.reason}"
            is VerificationResult.Unverified ->
                if (outcome.started) "Installing ${app.name}, unverified" else "Couldn't install ${app.name}"
            is VerificationResult.Verified ->
                if (outcome.started) "Installing ${app.name}" else "Couldn't install ${app.name}"
        }
    }

    override fun downloadAndInstall(app: UnifiedApp) {
        viewModelScope.launch {
            // Trust the cached row over the passed-in object: it carries the signer and
            // hash the authenticated index gave us.
            val cachedRow = cachedApps.value.find { it.packageName == app.packageName }
            val known = cachedRow?.toUnifiedApp() ?: app
            val source = known.source

            if (source == AppSource.MODERN_APPS || source == AppSource.FDROID) {
                val apkUrl = known.apkUrl
                if (apkUrl == null) {
                    _syncMessage.value = "No download available for ${app.name}"
                    return@launch
                }
                val requirement = when (source) {
                    // The trust root: whatever certificate this store is itself signed
                    // with, read back from PackageManager rather than hardcoded.
                    AppSource.MODERN_APPS -> InstallRequirement(
                        expectedPackage = known.packageName,
                        requiredSigners = ownSigningCertificates,
                        expectedSha256 = known.apkSha256
                            ?.let { mapOf("${known.packageName}.apk" to it) } ?: emptyMap(),
                        signerOrigin = "this store",
                    )
                    else -> InstallRequirement(
                        expectedPackage = known.packageName,
                        requiredSigners = known.expectedSigners.toSet(),
                        expectedSha256 = known.apkSha256
                            ?.let { mapOf("${known.packageName}.apk" to it) } ?: emptyMap(),
                        signerOrigin = "F-Droid's signed app list",
                    )
                }
                withContext(Dispatchers.IO) {
                    try {
                        _downloadProgress.value = _downloadProgress.value + (app.packageName to 0.01f)
                        val file = File(context.cacheDir, "${known.packageName}.apk")
                        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000; readTimeout = 60000
                            instanceFollowRedirects = true
                        }
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: known.sizeBytes
                        conn.inputStream.use { input ->
                            file.outputStream().use { out ->
                                val buf = ByteArray(8192)
                                var read: Int
                                var downloaded = 0L
                                while (input.read(buf).also { read = it } != -1) {
                                    out.write(buf, 0, read)
                                    downloaded += read
                                    if (total > 0) _downloadProgress.value = _downloadProgress.value + (app.packageName to (downloaded.toFloat() / total))
                                }
                            }
                        }
                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        val outcome = sessionInstaller.installSplits(
                            known.packageName, listOf(file), requirement, file.length()
                        )
                        withContext(Dispatchers.Main) { applyOutcome(known, outcome) }
                    } catch (_: Exception) {
                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        _syncMessage.value = "Couldn't download ${app.name}"
                    }
                }
            } else {
                // Play Store anonymous install pipeline
                withContext(Dispatchers.IO) {
                    try {
                        _downloadProgress.value = _downloadProgress.value + (app.packageName to 0.01f)
                        _syncMessage.value = "Connecting to the Play Store…"

                        val authResult = ensurePlayAuth()
                        if (authResult.isFailure) {
                            _downloadProgress.value = _downloadProgress.value - app.packageName
                            return@withContext
                        }
                        val authData = authResult.getOrNull()!!
                        val api = PlayStoreApi(authData, playHttpClient)

                        // Get details to get versionCode/offerType if not present
                        val details = api.getDetails(app.packageName) ?: app
                        val versionCode = details.versionCode.takeIf { it > 0 } ?: app.versionCode
                        val offerType = details.offerType

                        _syncMessage.value = "Getting ${details.name}…"

                        // Cert hash for already installed apps (key rotation)
                        var certHash: String? = null
                        if (_installedApps.value.any { it.packageName == app.packageName }) {
                            try {
                                val hashes = CertUtil.getEncodedCertificateHashes(context, app.packageName)
                                certHash = hashes.lastOrNull()
                            } catch (_: Exception) { }
                        }

                        var gplayFiles = try {
                            api.purchase(context, app.packageName, versionCode, offerType, certHash)
                        } catch (e: Exception) {
                            // If cert hash caused failure, retry without it
                            if (certHash != null) {
                                try { api.purchase(context, app.packageName, versionCode, offerType, null) }
                                catch (e2: Exception) {
                                    throw e2
                                }
                            } else throw e
                        }

                        if (gplayFiles.isEmpty()) {
                            throw Exception("Empty file list from purchase")
                        }

                        _syncMessage.value = "Downloading ${details.name}…"

                        // Download with progress, retry once on expired URL
                        var downloadResult = playDownloader.downloadFiles(
                            app.packageName, versionCode, gplayFiles
                        ) { fraction ->
                            _downloadProgress.value = _downloadProgress.value + (app.packageName to fraction)
                        }

                        if (downloadResult.isFailure) {
                            val ex = downloadResult.exceptionOrNull()
                            if (ex is PlayDownloader.ExpiredUrlException) {
                                _syncMessage.value = "Retrying…"
                                gplayFiles = api.purchase(context, app.packageName, versionCode, offerType, certHash)
                                downloadResult = playDownloader.downloadFiles(
                                    app.packageName, versionCode, gplayFiles
                                ) { fraction ->
                                    _downloadProgress.value = _downloadProgress.value + (app.packageName to fraction)
                                }
                            }
                            if (downloadResult.isFailure) throw downloadResult.exceptionOrNull()!!
                        }

                        val localFiles = downloadResult.getOrNull()!!
                        _syncMessage.value = "Checking ${details.name}…"

                        // Everything Play can actually give us, all enforced:
                        //  - per-split SHA-256 from the delivery response (PlayFile.sha256)
                        //  - the expected signing certificate from AppDetails.certificateSet
                        //  - the source stamp, pinned TOFU (survives Play App Signing)
                        //  - continuity with the installed copy (InstallVerifier, always)
                        // None of this survives a compromise of Google itself, which
                        // supplies both the bytes and the expectation — hence tier 3.
                        val expectedHashes = gplayFiles.mapNotNull { f ->
                            val name = f.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                            f.sha256?.takeIf { it.isNotBlank() }?.let { name to it }
                        }.toMap()
                        val requirement = InstallRequirement(
                            expectedPackage = app.packageName,
                            requiredSigners = details.expectedSigners.toSet(),
                            expectedSha256 = expectedHashes,
                            signerOrigin = "Google",
                            pinnedStamp = db.pinnedStampDao()
                                .byPackage(app.packageName)?.stampSha256,
                        )

                        val totalSize = localFiles.sumOf { it.length() }
                        val outcome = sessionInstaller.installSplits(
                            app.packageName, localFiles, requirement, totalSize
                        )

                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        applyOutcome(details, outcome)
                        // Refresh installed after a delay to catch install success
                        kotlinx.coroutines.delay(1500)
                        refreshInstalled()
                        kotlinx.coroutines.delay(1000)
                        _syncMessage.value = ""

                    } catch (e: Exception) {
                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        _syncMessage.value = "Couldn't install ${app.name}"
                        android.util.Log.e("AppStoreVM", "Play install failed", e)
                    }
                }
            }
        }
    }

    fun setActiveRepoFilter(repoUrl: String?) { _activeRepo.value = repoUrl }
}

class AppStoreViewModelFactory(private val context: Context, private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppStoreViewModel(context.applicationContext, db) as T
    }
}
