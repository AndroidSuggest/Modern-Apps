package com.vayunmathur.web.util

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkDao
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.HistoryDao
import com.vayunmathur.web.data.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

private const val TAG = "WebViewModel"
private const val P_SAVED_TABS = "web_saved_tabs"
private const val P_ACTIVE_TAB = "web_active_tab_id"
private const val P_SEARCH_ENGINE = "web_search_engine"
private const val P_HOMEPAGE = "web_homepage"

class WebViewModel(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val context: Context,
) : ViewModel() {

    // ---- Tabs ----
    val tabs = mutableStateListOf<BrowserTab>()
    var activeTabId by mutableStateOf<String?>(null)
        private set

    // ---- Omnibox ----
    var omniboxText by mutableStateOf("")
    var omniboxFocused by mutableStateOf(false)

    // ---- Settings ----
    var searchEngine by mutableStateOf(SearchEngine.DUCKDUCKGO)
    var homepage by mutableStateOf(SearchEngine.DUCKDUCKGO.homepage)

    // ---- Bookmarks ----
    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _folders = MutableStateFlow<List<BookmarkFolder>>(emptyList())
    val folders: StateFlow<List<BookmarkFolder>> = _folders

    // ---- History ----
    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    // ---- UI ----
    var showTabSwitcher by mutableStateOf(false)
    var showBookmarkSheet by mutableStateOf(false)
    var showMenu by mutableStateOf(false)

    // ---- Per-tab live WebView state tracked from callbacks ----
    private val tabTitles = mutableMapOf<String, String>()
    private val tabProgress = mutableMapOf<String, Float>()
    private val tabCanGoBack = mutableMapOf<String, Boolean>()
    private val tabCanGoForward = mutableMapOf<String, Boolean>()
    private val tabCurrentUrl = mutableMapOf<String, String>()

    private val json = Json { ignoreUnknownKeys = true }

    init {
        // Load persisted settings
        viewModelScope.launch {
            val prefsCtx = context
            // Load from SharedPreferences fallback wrapped in DataStore style (simpler: use context.files)
            withContext(Dispatchers.IO) {
                try {
                    val sp = prefsCtx.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                    val engineName = sp.getString(P_SEARCH_ENGINE, null)
                    val hp = sp.getString(P_HOMEPAGE, null)
                    val savedTabs = sp.getString(P_SAVED_TABS, null)
                    val activeId = sp.getString(P_ACTIVE_TAB, null)
                    viewModelScope.launch(Dispatchers.Main) {
                        engineName?.let {
                            runCatching { SearchEngine.valueOf(it) }.getOrNull()?.let { e -> searchEngine = e }
                        }
                        hp?.let { homepage = it }
                        if (savedTabs != null) {
                            runCatching {
                                val decoded = json.decodeFromString<List<BrowserTab>>(savedTabs)
                                if (decoded.isNotEmpty()) {
                                    tabs.clear()
                                    tabs.addAll(decoded)
                                }
                            }
                        }
                        if (tabs.isEmpty()) {
                            val tab = BrowserTab(id = UUID.randomUUID().toString(), url = homepage)
                            tabs.add(tab)
                            activeTabId = tab.id
                        } else {
                            activeTabId = activeId ?: tabs.firstOrNull()?.id
                        }
                        activeTab?.let {
                            omniboxText = BrowserUtils.prettyUrl(it.url)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load prefs", e)
                    viewModelScope.launch(Dispatchers.Main) {
                        if (tabs.isEmpty()) {
                            val tab = BrowserTab(id = UUID.randomUUID().toString(), url = homepage)
                            tabs.add(tab)
                            activeTabId = tab.id
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(
                bookmarkDao.allFlow(),
                bookmarkDao.foldersFlow(),
                historyDao.allFlow(),
            ) { bm, fo, hi ->
                Triple(bm, fo, hi)
            }.collect { (bm, fo, hi) ->
                _bookmarks.value = bm
                _folders.value = fo
                _history.value = hi
            }
        }
    }

    val activeTab: BrowserTab? get() = tabs.find { it.id == activeTabId }

    fun onTabUrlChange(tabId: String, url: String) {
        tabCurrentUrl[tabId] = url
        updateTab(tabId) { it.copy(url = url) }
        persistTabs()
        if (tabId == activeTabId && !omniboxFocused) {
            omniboxText = BrowserUtils.prettyUrl(url)
        }
    }

    fun onTabTitleChange(tabId: String, title: String) {
        tabTitles[tabId] = title
        updateTab(tabId) { it.copy(title = title) }
        persistTabs()
    }

    fun onTabProgress(tabId: String, progress: Float) {
        tabProgress[tabId] = progress
    }

    fun onTabCanGoBack(tabId: String, value: Boolean) { tabCanGoBack[tabId] = value }
    fun onTabCanGoForward(tabId: String, value: Boolean) { tabCanGoForward[tabId] = value }

    fun getTitle(tabId: String) = tabTitles[tabId] ?: ""
    fun getProgress(tabId: String) = tabProgress[tabId] ?: 0f
    fun getCanGoBack(tabId: String) = tabCanGoBack[tabId] ?: false
    fun getCanGoForward(tabId: String) = tabCanGoForward[tabId] ?: false
    fun getCurrentUrl(tabId: String) = tabCurrentUrl[tabId] ?: tabs.find { it.id == tabId }?.url ?: ""

    private fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) tabs[idx] = transform(tabs[idx])
    }

    fun newTab(url: String = homepage, makeActive: Boolean = true, isPrivate: Boolean = false) {
        val tab = BrowserTab(id = UUID.randomUUID().toString(), url = url, isPrivate = isPrivate)
        tabs.add(tab)
        if (makeActive) {
            activeTabId = tab.id
            omniboxFocused = false
            omniboxText = BrowserUtils.prettyUrl(url)
        }
        persistTabs()
    }

    fun closeTab(tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        tabs.removeAt(idx)
        tabTitles.remove(tabId)
        tabProgress.remove(tabId)
        tabCanGoBack.remove(tabId)
        tabCanGoForward.remove(tabId)
        tabCurrentUrl.remove(tabId)
        if (activeTabId == tabId) {
            activeTabId = when {
                tabs.isEmpty() -> {
                    val tab = BrowserTab(id = UUID.randomUUID().toString(), url = homepage)
                    tabs.add(tab)
                    tab.id
                }
                idx < tabs.size -> tabs[idx].id
                else -> tabs.last().id
            }
            activeTab?.let { omniboxText = BrowserUtils.prettyUrl(it.url) }
        }
        persistTabs()
    }

    fun switchToTab(tabId: String) {
        activeTabId = tabId
        activeTab?.let { omniboxText = BrowserUtils.prettyUrl(it.url) }
        showTabSwitcher = false
        persistTabs()
    }

    fun navigateActiveTab(input: String) {
        val active = activeTab ?: return
        val dest = BrowserUtils.toNavigationUrl(input, searchEngine)
        onTabUrlChange(active.id, dest)
        omniboxFocused = false
    }

    fun recordHistoryVisit(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        val active = activeTab
        if (active?.isPrivate == true) return
        viewModelScope.launch {
            runCatching {
                historyDao.upsert(HistoryEntry(url = url, title = title))
            }.onFailure { Log.e(TAG, "recordHistory", it) }
        }
    }

    fun addBookmark(url: String, title: String, folderId: Long? = null) {
        viewModelScope.launch {
            runCatching { bookmarkDao.upsert(Bookmark(url = url, title = title, folderId = folderId)) }
        }
    }

    fun removeBookmark(bookmark: Bookmark) {
        viewModelScope.launch { bookmarkDao.delete(bookmark) }
    }

    fun isBookmarkedFlow(url: String) = bookmarkDao.byUrlFlow(url)

    fun createFolder(name: String) {
        viewModelScope.launch {
            runCatching { bookmarkDao.upsertFolder(BookmarkFolder(name = name)) }
        }
    }

    fun deleteFolder(folder: BookmarkFolder) {
        viewModelScope.launch {
            runCatching {
                bookmarkDao.deleteByFolder(folder.id)
                bookmarkDao.deleteFolder(folder)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }

    fun updateSearchEngine(engine: SearchEngine) {
        searchEngine = engine
        homepage = engine.homepage
        persistPrefs()
    }

    fun updateHomepage(url: String) {
        if (url.isBlank()) return
        val normalized = if (url.startsWith("http")) url else "https://$url"
        homepage = normalized
        persistPrefs()
    }

    /** Called by Compose save — persisted to SharedPreferences, cheap + synchronous save. */
    fun onClearedPersist() {
        persistTabsSync()
    }

    override fun onCleared() {
        onClearedPersist()
        super.onCleared()
    }

    private fun persistTabs() {
        viewModelScope.launch(Dispatchers.IO) { persistTabsSync() }
    }

    private fun persistTabsSync() {
        try {
            val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
            // Do not persist incognito tabs.
            val toSave = tabs.filter { !it.isPrivate }
            sp.edit()
                .putString(P_SAVED_TABS, json.encodeToString(toSave))
                .putString(P_ACTIVE_TAB, activeTabId)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "persistTabs failed", e)
        }
    }

    private fun persistPrefs() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                sp.edit()
                    .putString(P_SEARCH_ENGINE, searchEngine.name)
                    .putString(P_HOMEPAGE, homepage)
                    .apply()
            } catch (e: Exception) {
                Log.e(TAG, "persistPrefs failed", e)
            }
        }
    }

    fun externalIntentUrl(url: String) {
        // If we only have one blank-ish tab pointing at homepage, reuse it; else new tab.
        if (tabs.size == 1 && (tabs[0].url == homepage || tabs[0].url.isEmpty())) {
            onTabUrlChange(tabs[0].id, url)
            activeTabId = tabs[0].id
        } else {
            newTab(url = url, makeActive = true)
        }
    }
}

class WebViewModelFactory(
    private val historyDao: HistoryDao,
    private val bookmarkDao: BookmarkDao,
    private val context: Context,
) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WebViewModel::class.java)) {
            return WebViewModel(historyDao, bookmarkDao, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel $modelClass")
    }
}
