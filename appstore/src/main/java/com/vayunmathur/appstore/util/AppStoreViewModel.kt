package com.vayunmathur.appstore.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.datastore.preferences.core.edit
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
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreAppProvider
import com.vayunmathur.appstore.data.PlayStoreDataSource
import com.vayunmathur.appstore.data.RepoEntity
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.installer.PlayDownloader
import com.vayunmathur.appstore.data.installer.SessionInstaller
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

enum class InstalledFilter { ALL, FDROID, PLAYSTORE }

sealed class PlayAuthState {
    object Idle : PlayAuthState()
    object Authenticating : PlayAuthState()
    data class Authenticated(val authData: AuthData) : PlayAuthState()
    data class Error(val message: String) : PlayAuthState()
}

class AppStoreViewModel(
    private val context: Context,
    private val db: AppDatabase
) : ViewModel() {

    private val fdroidProvider = FDroidAppProvider(db, context.applicationContext)
    private val playProvider = PlayStoreAppProvider()
    val providers: List<AppProvider> get() = listOf(fdroidProvider, playProvider)

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

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val combinedBrowse: StateFlow<List<UnifiedApp>> = combine(
        cachedApps, _playSearchResults, _topCharts, _searchQuery, _activeRepo
    ) { cached, playSearch, topCharts, query, activeRepo ->
        val fdroidAll = cached.map { it.toUnifiedApp() }
        val fdroid = if (activeRepo != null) fdroidAll.filter { it.repoUrl == activeRepo } else fdroidAll
        val fdroidPkgs = fdroidAll.map { it.packageName }.toSet()
        fun infer(pkg: String): AppSource = if (fdroidPkgs.contains(pkg)) AppSource.FDROID else AppSource.PLAYSTORE

        val infSearch = playSearch.map { it.copy(source = infer(it.packageName)) }
        val infCharts = topCharts.map { it.copy(source = infer(it.packageName)) }

        val list = if (query.isBlank()) {
            fdroid + (if (infSearch.isEmpty()) infCharts else infSearch)
        } else {
            val q = query.lowercase()
            val fd = fdroid.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) || it.summary.lowercase().contains(q)
            }
            (fd + infSearch).distinctBy { it.packageName }.map { it.copy(source = infer(it.packageName)) }
        }
        AppProvider.filterTargetSdk(list.distinctBy { it.packageName }).sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val filteredInstalled: StateFlow<List<InstalledInfo>> = combine(
        _installedApps, _installedSourceMap, _installedFilter
    ) { installed, srcMap, filter ->
        val inStore = installed.filter { srcMap.containsKey(it.packageName) }
        when (filter) {
            InstalledFilter.ALL -> inStore
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
                fdroidProvider.cachedPackageNames = list.map { it.packageName }.toSet()
            }
        }
    }

    private suspend fun restoreAuthData() {
        try {
            val prefs = context.authDataStore.data.first()
            val jsonStr = prefs[PLAY_AUTH_JSON_KEY] ?: return
            if (jsonStr.isBlank()) return
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
                    _playAuthState.value = PlayAuthState.Authenticated(authData)
                }
            } catch (_: Exception) { }
        } catch (_: Exception) { }
    }

    private suspend fun persistAuthData(authData: AuthData) {
        try {
            val jsonStr = json.encodeToString(AuthData.serializer(), authData)
            context.authDataStore.edit { prefs ->
                prefs[PLAY_AUTH_JSON_KEY] = jsonStr
            }
        } catch (_: Exception) { }
    }

    private suspend fun ensurePlayAuth(): Result<AuthData> {
        // Return cached if present and still valid
        cachedAuthData?.let { cached ->
            val valid = withContext(Dispatchers.IO) {
                try { AuthHelper.using(playHttpClient).isValid(cached) } catch (_: Exception) { false }
            }
            if (valid) return Result.success(cached)
        }

        _playAuthState.value = PlayAuthState.Authenticating
        _syncMessage.value = "Authenticating with Play (anonymous)..."

        val deviceProps = DeviceInfoProvider.buildDeviceProperties(context)
        val result = anonAuthRepo.ensureAuthData(context, deviceProps)

        if (result.isSuccess) {
            val authData = result.getOrNull()!!
            cachedAuthData = authData
            _playAuthState.value = PlayAuthState.Authenticated(authData)
            _syncMessage.value = "Play authentication successful"
            persistAuthData(authData)
            kotlinx.coroutines.delay(1000)
            _syncMessage.value = ""
            return Result.success(authData)
        } else {
            val err = result.exceptionOrNull() ?: Exception("Auth failed")
            val msg = anonAuthRepo.errorMessage(err)
            _playAuthState.value = PlayAuthState.Error(msg)
            _syncMessage.value = "Play auth failed: $msg"
            return Result.failure(err)
        }
    }

    private suspend fun ensureDefaultRepos() {
        val existing = db.repoDao().all()
        if (existing.isEmpty()) {
            db.repoDao().upsert(RepoEntity(DefaultRepos.FDROID_MAIN, "F-Droid", true))
            db.repoDao().upsert(RepoEntity(DefaultRepos.FDROID_ARCHIVE, "F-Droid Archive", false))
            db.repoDao().upsert(RepoEntity(DefaultRepos.IZVYZID, "IzzyOnDroid", true))
        }
    }

    fun loadTopCharts() {
        topJob?.cancel()
        topJob = viewModelScope.launch {
            _syncMessage.value = "Loading top charts..."
            // If we have Play auth, try gplayapi search? For topCharts we keep scraping as fallback (cheap)
            val charts = playProvider.fetchAll()
            // If auth ready, optionally enrich via gplayapi top charts later – keep scraping for V1
            _topCharts.value = charts
            _syncMessage.value = ""
        }
    }

    fun setSearch(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        if (q.isBlank()) {
            _playSearchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            _syncMessage.value = "Searching..."
            // If Play auth cached, use gplayapi search; else fallback to scraping provider
            val results = if (cachedAuthData != null) {
                try {
                    val api = PlayStoreApi(cachedAuthData!!, playHttpClient)
                    val gplay = api.search(q)
                    if (gplay.isNotEmpty()) gplay else playProvider.search(q)
                } catch (_: Exception) {
                    playProvider.search(q)
                }
            } else {
                playProvider.search(q)
            }
            _playSearchResults.value = results
            _syncMessage.value = ""
        }
    }

    fun syncRepos() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing F-Droid repositories..."
            try {
                val total = fdroidProvider.syncIntoDb(context.applicationContext)
                _syncMessage.value = if (total > 0) "Synced $total F-Droid apps" else "Sync complete"
            } catch (e: Exception) {
                _syncMessage.value = "Sync failed: ${e.message}"
            }
            kotlinx.coroutines.delay(1500)
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

    private fun resolveInstalledSources(packages: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, AppSource>()
            for (pkg in packages) {
                if (fdroidProvider.isPresent(pkg)) map[pkg] = AppSource.FDROID
                else if (playProvider.isPresent(pkg)) map[pkg] = AppSource.PLAYSTORE
            }
            _installedSourceMap.value = map
        }
    }

    fun setInstalledFilter(filter: InstalledFilter) { _installedFilter.value = filter }

    fun selectApp(app: UnifiedApp) {
        val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
        val inferredSource = if (fdroidPkgs.contains(app.packageName)) AppSource.FDROID else AppSource.PLAYSTORE
        _selectedApp.value = app.copy(source = inferredSource)

        if (inferredSource == AppSource.PLAYSTORE && app.description.isBlank()) {
            // Try to fetch via gplayapi if auth available, else via scraping provider
            viewModelScope.launch {
                val details = if (cachedAuthData != null) {
                    try {
                        val api = PlayStoreApi(cachedAuthData!!, playHttpClient)
                        api.getDetails(app.packageName)
                    } catch (_: Exception) {
                        playProvider.getDetails(app.packageName)
                    }
                } else {
                    playProvider.getDetails(app.packageName)
                }
                if (details != null) {
                    _selectedApp.value = details.copy(
                        source = if (fdroidPkgs.contains(details.packageName)) AppSource.FDROID else AppSource.PLAYSTORE
                    )
                }
            }
        }
    }

    fun openApp(packageName: String) {
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

    fun uninstallApp(packageName: String) {
        try {
            val intent = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _syncMessage.value = "Uninstall failed: ${e.message}"
        }
    }

    fun openInPlayStore(pkg: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreDataSource.playStoreUrl(pkg))).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) { }
        }
    }

    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) {}
    }

    fun addRepo(url: String, name: String) {
        viewModelScope.launch {
            db.repoDao().upsert(RepoEntity(url.trim().trimEnd('/'), name.ifBlank { url.trim() }, true))
        }
    }

    fun toggleRepo(url: String) {
        viewModelScope.launch {
            db.repoDao().all().find { it.url == url }?.let { db.repoDao().upsert(it.copy(enabled = !it.enabled)) }
        }
    }

    fun deleteRepo(url: String) {
        viewModelScope.launch {
            db.repoDao().deleteByUrl(url)
            db.cachedAppDao().deleteByRepo(url)
        }
    }

    fun syncPlayUpdates() {
        viewModelScope.launch {
            if (_installedApps.value.isEmpty()) {
                refreshInstalled()
                kotlinx.coroutines.delay(500)
            }
            val authResult = ensurePlayAuth()
            if (authResult.isFailure) return@launch

            val authData = authResult.getOrNull() ?: return@launch
            _syncMessage.value = "Checking Play updates..."
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
                _syncMessage.value = if (updates.isNotEmpty()) "${updates.size} Play updates" else "Play up to date"
                kotlinx.coroutines.delay(1500)
                _syncMessage.value = ""
            } catch (e: Exception) {
                _syncMessage.value = "Play update check failed: ${e.message}"
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

    fun downloadAndInstall(app: UnifiedApp) {
        viewModelScope.launch {
            val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
            val isFdroid = fdroidPkgs.contains(app.packageName) || app.source == AppSource.FDROID

            if (isFdroid) {
                // F-Droid path upgraded to SessionInstaller
                val apkUrl = app.apkUrl ?: cachedApps.value.find { it.packageName == app.packageName }?.apkUrl
                if (apkUrl == null) {
                    _syncMessage.value = "No APK URL for ${app.packageName}"
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    try {
                        _downloadProgress.value = _downloadProgress.value + (app.packageName to 0.01f)
                        val file = File(context.cacheDir, "${app.packageName}.apk")
                        val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 20000; readTimeout = 60000
                        }
                        val total = conn.contentLengthLong.takeIf { it > 0 } ?: app.sizeBytes
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
                        // Use SessionInstaller instead of FileProvider ACTION_VIEW
                        val success = sessionInstaller.installSplits(app.packageName, listOf(file), file.length())
                        withContext(Dispatchers.Main) {
                            if (success) {
                                _syncMessage.value = "Installing ${app.name}"
                            } else {
                                _syncMessage.value = "Install failed for ${app.name}"
                            }
                        }
                    } catch (e: Exception) {
                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        _syncMessage.value = "Download failed: ${e.message}"
                    }
                }
            } else {
                // Play Store anonymous install pipeline
                withContext(Dispatchers.IO) {
                    try {
                        _downloadProgress.value = _downloadProgress.value + (app.packageName to 0.01f)
                        _syncMessage.value = "Authenticating for ${app.name}..."

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

                        _syncMessage.value = "Purchasing ${details.name}..."

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

                        _syncMessage.value = "Downloading ${details.name}..."

                        // Download with progress, retry once on expired URL
                        var downloadResult = playDownloader.downloadFiles(
                            app.packageName, versionCode, gplayFiles
                        ) { fraction ->
                            _downloadProgress.value = _downloadProgress.value + (app.packageName to fraction)
                        }

                        if (downloadResult.isFailure) {
                            val ex = downloadResult.exceptionOrNull()
                            if (ex is PlayDownloader.ExpiredUrlException) {
                                _syncMessage.value = "Retrying purchase..."
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
                        _syncMessage.value = "Installing ${details.name}..."
                        val totalSize = localFiles.sumOf { it.length() }
                        val success = sessionInstaller.installSplits(app.packageName, localFiles, totalSize)

                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        _syncMessage.value = if (success) "Installing ${details.name}" else "Install failed for ${details.name}"
                        // Refresh installed after a delay to catch install success
                        kotlinx.coroutines.delay(1500)
                        refreshInstalled()
                        kotlinx.coroutines.delay(1000)
                        _syncMessage.value = ""

                    } catch (e: Exception) {
                        _downloadProgress.value = _downloadProgress.value - app.packageName
                        _syncMessage.value = "Play install failed: ${e.message}"
                        android.util.Log.e("AppStoreVM", "Play install failed", e)
                    }
                }
            }
        }
    }

    fun setActiveRepoFilter(repoUrl: String?) { _activeRepo.value = repoUrl }

    private fun CachedAppEntity.toUnifiedApp(): UnifiedApp = UnifiedApp(
        packageName = packageName,
        source = try { AppSource.valueOf(source) } catch (_: Exception) { AppSource.FDROID },
        name = name,
        summary = summary,
        description = description,
        iconUrl = iconUrl,
        author = author,
        categories = categories.split(",").filter { it.isNotBlank() },
        versionName = versionName,
        versionCode = versionCode,
        sizeBytes = sizeBytes,
        apkUrl = apkUrl,
        targetSdk = targetSdk,
        repoUrl = repoUrl,
        lastUpdated = lastUpdated
    )
}

class AppStoreViewModelFactory(private val context: Context, private val db: AppDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppStoreViewModel(context.applicationContext, db) as T
    }
}
