package com.vayunmathur.appstore.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CachedAppEntity
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.FDroidAppProvider
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreAppProvider
import com.vayunmathur.appstore.data.RepoEntity
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.PlayStoreDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class InstalledFilter {
    ALL,
    FDROID,
    PLAYSTORE
}

class AppStoreViewModel(
    private val context: Context,
    private val db: AppDatabase
) : ViewModel() {

    // Providers — app registers 2 providers, fdroid base repo + play store
    private val fdroidProvider = FDroidAppProvider(db)
    private val playProvider = PlayStoreAppProvider()
    private val providers: List<AppProvider> = listOf(fdroidProvider, playProvider)

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

    // Installed filtering: presence-resolved source per package, order F-Droid -> Play Store
    private val _installedSourceMap = MutableStateFlow<Map<String, AppSource>>(emptyMap())
    val installedSourceMap: StateFlow<Map<String, AppSource>> = _installedSourceMap

    private val _installedFilter = MutableStateFlow(InstalledFilter.ALL)
    val installedFilter: StateFlow<InstalledFilter> = _installedFilter

    val repos = db.repoDao().allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cachedApps = db.cachedAppDao().allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val combinedBrowse: StateFlow<List<UnifiedApp>> = combine(
        cachedApps, _playSearchResults, _topCharts, _searchQuery, _activeRepo
    ) { cached, playSearch, topCharts, query, activeRepo ->
        val fdroidAll = cached.map { it.toUnifiedApp() }
        val fdroidFiltered = if (activeRepo != null) fdroidAll.filter { it.repoUrl == activeRepo } else fdroidAll
        val fdroidPkgs = fdroidAll.map { it.packageName }.toSet()

        fun infer(pkg: String): AppSource = if (fdroidPkgs.contains(pkg)) AppSource.FDROID else AppSource.PLAYSTORE

        val infSearch = playSearch.map { it.copy(source = infer(it.packageName)) }
        val infCharts = topCharts.map { it.copy(source = infer(it.packageName)) }

        val list = if (query.isBlank()) {
            fdroidFiltered + (if (infSearch.isEmpty()) infCharts else infSearch)
        } else {
            val q = query.lowercase()
            val fd = fdroidFiltered.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) || it.summary.lowercase().contains(q)
            }
            (fd + infSearch).distinctBy { it.packageName }.map { it.copy(source = infer(it.packageName)) }
        }
        // Filter targetSdk < 35 for both sources (defense in depth)
        AppProvider.filterTargetSdk(list.distinctBy { it.packageName }).sortedBy { it.name.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // Presence-resolved installed list: only apps present in fdroid or play (order checked), with counts
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

    var searchJob: Job? = null
    var topJob: Job? = null

    init {
        viewModelScope.launch {
            ensureDefaultRepos()
            refreshInstalled()
            loadTopCharts()
        }
        // Keep fdroid provider cache synced with DB flow
        viewModelScope.launch {
            cachedApps.collect { list ->
                fdroidProvider.cachedPackageNames = list.map { it.packageName }.toSet()
            }
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
            val charts = playProvider.fetchAll()
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
            _syncMessage.value = "Searching Play Store..."
            val results = playProvider.search(q)
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
                val total = fdroidProvider.syncIntoDb()
                _syncMessage.value = if (total > 0) "Synced $total apps from F-Droid" else "Sync complete"
            } catch (e: Exception) {
                _syncMessage.value = "F-Droid sync failed: ${e.message}"
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
            // User apps only — not system partition
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
            // Resolve source presence for each installed app, order: fdroid -> play -> none (hide)
            resolveInstalledSources(installed.map { it.packageName })
        }
    }

    private fun resolveInstalledSources(packages: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val map = mutableMapOf<String, AppSource>()
            for (pkg in packages) {
                // Order: fdroid first, then play store
                if (fdroidProvider.isPresent(pkg)) {
                    map[pkg] = AppSource.FDROID
                } else if (playProvider.isPresent(pkg)) {
                    map[pkg] = AppSource.PLAYSTORE
                }
                // else not in any store -> hidden from installed list (per requirement)
            }
            _installedSourceMap.value = map
        }
    }

    fun setInstalledFilter(filter: InstalledFilter) {
        _installedFilter.value = filter
    }

    fun selectApp(app: UnifiedApp) {
        // Re-infer via providers order: fdroid check takes precedence
        val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
        val inferredSource = if (fdroidPkgs.contains(app.packageName)) AppSource.FDROID else AppSource.PLAYSTORE
        _selectedApp.value = app.copy(source = inferredSource)
        if (inferredSource == AppSource.PLAYSTORE && app.description.isBlank()) {
            viewModelScope.launch {
                val details = playProvider.getDetails(app.packageName)
                if (details != null) {
                    _selectedApp.value = details.copy(source = if (fdroidPkgs.contains(details.packageName)) AppSource.FDROID else AppSource.PLAYSTORE)
                }
            }
        }
    }

    fun openInPlayStore(pkg: String) {
        try {
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(market)
        } catch (_: Exception) {
            val web = Intent(Intent.ACTION_VIEW, Uri.parse(PlayStoreDataSource.playStoreUrl(pkg))).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(web)
        }
    }

    fun openInBrowser(url: String) {
        if (url.isBlank()) return
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(i)
        } catch (_: Exception) { }
    }

    fun addRepo(url: String, name: String) {
        viewModelScope.launch {
            val clean = url.trim().trimEnd('/')
            db.repoDao().upsert(RepoEntity(clean, name.ifBlank { clean }, true))
        }
    }

    fun toggleRepo(url: String) {
        viewModelScope.launch {
            val existing = db.repoDao().all().find { it.url == url } ?: return@launch
            db.repoDao().upsert(existing.copy(enabled = !existing.enabled))
        }
    }

    fun deleteRepo(url: String) {
        viewModelScope.launch {
            db.repoDao().deleteByUrl(url)
            db.cachedAppDao().deleteByRepo(url)
        }
    }

    fun downloadAndInstall(app: UnifiedApp) {
        // Use presence logic: fdroid present -> direct APK, else play -> market intent
        viewModelScope.launch {
            val srcMap = _installedSourceMap.value
            val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
            val isFdroid = fdroidPkgs.contains(app.packageName) || srcMap[app.packageName] == AppSource.FDROID || app.source == AppSource.FDROID
            if (!isFdroid) {
                openInPlayStore(app.packageName)
                return@launch
            }
            val apkUrl = app.apkUrl ?: cachedApps.value.find { it.packageName == app.packageName }?.apkUrl ?: return@launch
            withContext(Dispatchers.IO) {
                try {
                    _downloadProgress.value = _downloadProgress.value + (app.packageName to 0.01f)
                    val file = File(context.cacheDir, "${app.packageName}.apk")
                    val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20000
                        readTimeout = 60000
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
                                if (total > 0) {
                                    _downloadProgress.value = _downloadProgress.value + (app.packageName to (downloaded.toFloat() / total))
                                }
                            }
                        }
                    }
                    _downloadProgress.value = _downloadProgress.value - app.packageName
                    withContext(Dispatchers.Main) { installApk(file) }
                } catch (e: Exception) {
                    _downloadProgress.value = _downloadProgress.value - app.packageName
                    _syncMessage.value = "Download failed: ${e.message}"
                }
            }
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _syncMessage.value = "Install failed: ${e.message}"
        }
    }

    fun setActiveRepoFilter(repoUrl: String?) {
        _activeRepo.value = repoUrl
    }

    private fun UnifiedApp.toEntity(): CachedAppEntity = CachedAppEntity(
        packageName = packageName,
        source = source.name,
        name = name,
        summary = summary,
        description = description,
        iconUrl = iconUrl,
        author = author,
        categories = categories.joinToString(","),
        versionName = versionName,
        versionCode = versionCode,
        sizeBytes = sizeBytes,
        apkUrl = apkUrl,
        targetSdk = targetSdk,
        repoUrl = repoUrl?.removeSuffix("/") ?: DefaultRepos.FDROID_MAIN,
        lastUpdated = lastUpdated
    )

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

class AppStoreViewModelFactory(
    private val context: Context,
    private val db: AppDatabase
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppStoreViewModel(context.applicationContext, db) as T
    }
}
