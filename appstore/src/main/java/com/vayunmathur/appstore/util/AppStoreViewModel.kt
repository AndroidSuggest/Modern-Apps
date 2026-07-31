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
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.CachedAppEntity
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.FDroidRepository
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.PlayStoreDataSource
import com.vayunmathur.appstore.data.RepoEntity
import com.vayunmathur.appstore.data.UnifiedApp
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

class AppStoreViewModel(
    private val context: Context,
    private val db: AppDatabase
) : ViewModel() {

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

    val repos = db.repoDao().allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val cachedApps = db.cachedAppDao().allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val combinedBrowse: StateFlow<List<UnifiedApp>> = combine(
        cachedApps, _playSearchResults, _topCharts, _searchQuery, _activeRepo
    ) { cached, playSearch, topCharts, query, activeRepo ->
        val fdroidAll = cached.map { it.toUnifiedApp() }
        val fdroid = if (activeRepo != null) fdroidAll.filter { it.repoUrl == activeRepo || activeRepo == "all" } else fdroidAll
        // Infer overlay: if package exists in any F-Droid repo, force FDROID source; otherwise PLAYSTORE
        val fdroidPkgs = fdroidAll.map { it.packageName }.toSet()
        val infPlaySearch = playSearch.map { it.copy(source = inferSource(it.packageName, fdroidPkgs)) }
        val infTop = topCharts.map { it.copy(source = inferSource(it.packageName, fdroidPkgs)) }

        if (query.isBlank()) {
            val all = fdroid + (if (infPlaySearch.isEmpty()) infTop else infPlaySearch)
            all.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
        } else {
            val q = query.lowercase()
            val filteredFdroid = fdroid.filter {
                it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) || it.summary.lowercase().contains(q)
            }
            val combined = (filteredFdroid + infPlaySearch).distinctBy { it.packageName }.map {
                it.copy(source = inferSource(it.packageName, fdroidPkgs))
            }
            combined.sortedBy { it.name.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun inferSource(pkg: String, fdroidPkgs: Set<String>): AppSource {
        return if (fdroidPkgs.contains(pkg)) AppSource.FDROID else AppSource.PLAYSTORE
    }

    fun inferSourceForInstalled(pkg: String): AppSource {
        val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
        return inferSource(pkg, fdroidPkgs)
    }

    var searchJob: Job? = null
    var topJob: Job? = null

    init {
        viewModelScope.launch {
            ensureDefaultRepos()
            refreshInstalled()
            loadTopCharts()
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
            val charts = PlayStoreDataSource.topCharts()
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
            val play = PlayStoreDataSource.search(q)
            _playSearchResults.value = play
            _syncMessage.value = ""
        }
    }

    fun syncRepos() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            _syncMessage.value = "Syncing repositories..."
            val repos = db.repoDao().all().filter { it.enabled }
            var fetched = 0
            for (repo in repos) {
                try {
                    _syncMessage.value = "Syncing ${repo.name}..."
                    val apps = FDroidRepository.fetchRepoIndex(repo.url)
                    val entities = apps.map { it.toEntity() }
                    db.cachedAppDao().deleteByRepo(repo.url.removeSuffix("/"))
                    db.cachedAppDao().upsertAll(entities)
                    db.repoDao().upsert(repo.copy(lastSync = System.currentTimeMillis()))
                    fetched += apps.size
                } catch (e: Exception) {
                    _syncMessage.value = "Failed ${repo.name}: ${e.message}"
                    kotlinx.coroutines.delay(1500)
                }
            }
            _syncMessage.value = if (fetched > 0) "Synced $fetched apps" else "Sync complete"
            kotlinx.coroutines.delay(2000)
            _syncMessage.value = ""
            _isSyncing.value = false
            refreshInstalled()
        }
    }

    fun refreshInstalled() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            // Only user apps: exclude system partition apps
            val allApps = pm.getInstalledApplications(PackageManager.MATCH_ALL)
            val userApps = allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            val installed = userApps.mapNotNull { ai ->
                try {
                    val pi = pm.getPackageInfo(ai.packageName, 0)
                    InstalledInfo(
                        packageName = ai.packageName,
                        name = pm.getApplicationLabel(ai).toString(),
                        versionName = pi.versionName,
                        versionCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong(),
                        isSystem = false
                    )
                } catch (_: Exception) { null }
            }.sortedBy { it.name.lowercase() }

            val icons = userApps.mapNotNull { ai ->
                try { ai.packageName to pm.getApplicationIcon(ai.packageName) } catch (_: Exception) { null }
            }.toMap()

            _installedApps.value = installed
            _installedIcons.value = icons
        }
    }

    fun selectApp(app: UnifiedApp) {
        // Re-infer source based on F-Droid cache at selection time too
        val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
        val inferred = app.copy(source = inferSource(app.packageName, fdroidPkgs))
        _selectedApp.value = inferred
        if (inferred.source == AppSource.PLAYSTORE && inferred.description.isBlank()) {
            viewModelScope.launch {
                val details = PlayStoreDataSource.appDetails(inferred.packageName)
                if (details != null) {
                    _selectedApp.value = details.copy(source = inferSource(details.packageName, fdroidPkgs))
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
            val cleanUrl = url.trim().trimEnd('/')
            db.repoDao().upsert(RepoEntity(cleanUrl, name.ifBlank { cleanUrl }, true))
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
        // Inferred source decides: FDROID = direct APK, PLAYSTORE = market intent
        val fdroidPkgs = cachedApps.value.map { it.packageName }.toSet()
        val src = inferSource(app.packageName, fdroidPkgs)
        if (src == AppSource.PLAYSTORE) {
            openInPlayStore(app.packageName)
            return
        }
        // Resolve APK from F-Droid cache if current listing lacks it
        val apkUrl = app.apkUrl ?: cachedApps.value.find { it.packageName == app.packageName }?.apkUrl ?: return
        viewModelScope.launch(Dispatchers.IO) {
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
