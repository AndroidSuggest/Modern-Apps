package com.vayunmathur.web.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.IconStarBorder
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.util.BrowserUtils
import com.vayunmathur.web.util.SearchEngine
import com.vayunmathur.web.util.WebViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    val webViewPool = remember { mutableStateMapOf<String, WebView>() }

    LaunchedEffect(viewModel.tabs.size) {
        if (viewModel.tabs.isEmpty()) {
            viewModel.newTab()
        }
    }

    val activeTab = viewModel.activeTab
    val canGoBack = activeTab?.let { viewModel.getCanGoBack(it.id) } ?: false
    val canGoForward = activeTab?.let { viewModel.getCanGoForward(it.id) } ?: false
    val progress = activeTab?.let { viewModel.getProgress(it.id) } ?: 0f

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

    val isCurrentBookmarked = activeTab?.url?.let { url ->
        bookmarks.any { it.url == url }
    } ?: false

    BackHandler(enabled = viewModel.showTabSwitcher) {
        viewModel.showTabSwitcher = false
    }
    BackHandler(enabled = !viewModel.showTabSwitcher && viewModel.omniboxFocused) {
        viewModel.omniboxFocused = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = !viewModel.showTabSwitcher && !viewModel.omniboxFocused && canGoBack) {
        activeTab?.let { tab ->
            webViewPool[tab.id]?.goBack()
        }
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Omnibox(
                            text = viewModel.omniboxText,
                            onTextChange = { viewModel.omniboxText = it },
                            onSubmit = {
                                if (viewModel.omniboxText.isNotBlank()) {
                                    viewModel.navigateActiveTab(viewModel.omniboxText)
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }
                            },
                            focused = viewModel.omniboxFocused,
                            onFocusChange = { viewModel.omniboxFocused = it },
                            focusRequester = focusRequester,
                            isSecure = activeTab?.url?.startsWith("https://") == true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    navigationIcon = {
                        if (canGoBack) {
                            IconButton(onClick = {
                                activeTab?.let { webViewPool[it.id]?.goBack() }
                            }) {
                                IconBack()
                            }
                        } else {
                            IconButton(onClick = { viewModel.newTab() }) {
                                IconGlobe()
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showMenu = true }) {
                            IconMoreVert()
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(if (canGoForward) "Forward" else "Forward (disabled)") },
                                onClick = {
                                    showMenu = false
                                    if (canGoForward) activeTab?.let { webViewPool[it.id]?.goForward() }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reload") },
                                onClick = {
                                    showMenu = false
                                    activeTab?.let { webViewPool[it.id]?.reload() }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (isCurrentBookmarked) "Remove bookmark" else "Add bookmark") },
                                onClick = {
                                    showMenu = false
                                    activeTab?.let { tab ->
                                        if (isCurrentBookmarked) {
                                            bookmarks.find { it.url == tab.url }?.let { viewModel.removeBookmark(it) }
                                        } else {
                                            viewModel.addBookmark(tab.url, tab.title.ifBlank { tab.url })
                                        }
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = {
                                    showMenu = false
                                    activeTab?.let { tab ->
                                        val sendIntent = android.content.Intent().apply {
                                            action = android.content.Intent.ACTION_SEND
                                            putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                                            type = "text/plain"
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(sendIntent, "Share link")
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New tab") },
                                onClick = {
                                    showMenu = false
                                    viewModel.newTab()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("New private tab") },
                                onClick = {
                                    showMenu = false
                                    viewModel.newTab(isPrivate = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("History") },
                                onClick = {
                                    showMenu = false
                                    backStack.add(Route.History)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Bookmarks") },
                                onClick = {
                                    showMenu = false
                                    backStack.add(Route.Bookmarks)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                onClick = {
                                    showMenu = false
                                    backStack.add(Route.Settings)
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                if (activeTab != null && progress < 1f && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                    )
                }

                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                if (canGoBack) activeTab?.let { webViewPool[it.id]?.goBack() }
                            },
                            enabled = canGoBack
                        ) {
                            IconBack()
                        }
                        IconButton(
                            onClick = {
                                if (canGoForward) activeTab?.let { webViewPool[it.id]?.goForward() }
                            },
                            enabled = canGoForward
                        ) {
                            IconArrowForward()
                        }
                        IconButton(onClick = {
                            activeTab?.let { webViewPool[it.id]?.reload() }
                        }) {
                            IconRefresh()
                        }
                        IconButton(onClick = {
                            activeTab?.let { webViewPool[it.id]?.loadUrl(viewModel.homepage) }
                        }) {
                            IconHome()
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { viewModel.showTabSwitcher = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = viewModel.tabs.size.toString(),
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = {
                            activeTab?.let { tab ->
                                if (isCurrentBookmarked) {
                                    bookmarks.find { it.url == tab.url }?.let { viewModel.removeBookmark(it) }
                                } else {
                                    viewModel.addBookmark(tab.url, tab.title.ifBlank { tab.url })
                                }
                            }
                        }) {
                            if (isCurrentBookmarked) IconStar() else IconStarBorder()
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (viewModel.omniboxFocused) {
                SuggestionOverlay(
                    query = viewModel.omniboxText,
                    history = history,
                    bookmarks = bookmarks,
                    onSuggestionClick = { suggestionUrl ->
                        viewModel.omniboxText = suggestionUrl
                        viewModel.navigateActiveTab(suggestionUrl)
                        focusManager.clearFocus()
                        viewModel.omniboxFocused = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (activeTab != null && (activeTab.url == viewModel.homepage || activeTab.url == "about:blank" || activeTab.url.isBlank())) {
                        QuickAccess(
                            bookmarks = bookmarks.take(12),
                            history = history.take(8),
                            onOpenUrl = { url ->
                                activeTab.let { viewModel.onTabUrlChange(it.id, url) }
                            },
                            onSearchEngineChange = { viewModel.updateSearchEngine(it) },
                            currentEngine = viewModel.searchEngine,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else if (activeTab != null) {
                        Box(Modifier.fillMaxSize()) {
                            WebViewBrowser(
                                tabId = activeTab.id,
                                initialUrl = activeTab.url,
                                viewModel = viewModel,
                                webViewPool = webViewPool,
                                onRequestNewTab = { url -> viewModel.newTab(url = url) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }

            if (viewModel.showTabSwitcher) {
                TabSwitcher(
                    tabs = viewModel.tabs,
                    activeTabId = viewModel.activeTabId,
                    onSwitch = { viewModel.switchToTab(it) },
                    onClose = { viewModel.closeTab(it) },
                    onNewTab = { viewModel.newTab() },
                    onNewPrivateTab = { viewModel.newTab(isPrivate = true) },
                    onDismiss = { viewModel.showTabSwitcher = false },
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun Omnibox(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    isSecure: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        singleLine = true,
        placeholder = { Text("Search or enter address") },
        leadingIcon = {
            if (isSecure) IconGlobe() else IconSearch()
        },
        trailingIcon = if (text.isNotEmpty()) {
            {
                IconButton(onClick = { onTextChange("") }) {
                    IconClose()
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocusChange(it.isFocused) }
    )
}

@Composable
private fun SuggestionOverlay(
    query: String,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    onSuggestionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredHistory = remember(query, history) {
        if (query.isBlank()) history.take(10)
        else history.filter {
            it.url.contains(query, true) || it.title.contains(query, true)
        }.take(15)
    }
    val filteredBookmarks = remember(query, bookmarks) {
        if (query.isBlank()) bookmarks.take(5)
        else bookmarks.filter { it.url.contains(query, true) || it.title.contains(query, true) }.take(8)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (query.isNotBlank()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onSuggestionClick(query) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconSearch()
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(text = query, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = "Search / Go to ${BrowserUtils.toNavigationUrl(query, SearchEngine.DUCKDUCKGO).let { BrowserUtils.hostFromUrl(it) }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (filteredBookmarks.isNotEmpty()) {
            item {
                Text("Bookmarks", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
            }
            items(filteredBookmarks, key = { "bm-${it.id}" }) { bm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSuggestionClick(bm.url) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconStar()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bm.title.ifBlank { bm.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(BrowserUtils.prettyUrl(bm.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (filteredHistory.isNotEmpty()) {
            item {
                Text("History", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            }
            items(filteredHistory, key = { "h-${it.id}" }) { h ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSuggestionClick(h.url) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconGlobe()
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title.ifBlank { h.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(BrowserUtils.prettyUrl(h.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAccess(
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    onOpenUrl: (String) -> Unit,
    onSearchEngineChange: (SearchEngine) -> Unit,
    currentEngine: SearchEngine,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Search with", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SearchEngine.entries) { engine ->
                        FilterChip(
                            selected = engine == currentEngine,
                            onClick = { onSearchEngineChange(engine) },
                            label = { Text(engine.displayName) }
                        )
                    }
                }
            }
        }

        if (bookmarks.isNotEmpty()) {
            item { Text("Bookmarks", style = MaterialTheme.typography.titleMedium) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Card(
                            modifier = Modifier
                                .width(140.dp)
                                .clickable { onOpenUrl(bm.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.surface),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(bm.title.take(1).uppercase().ifBlank { "B" }, style = MaterialTheme.typography.titleSmall)
                                }
                                Text(bm.title.ifBlank { BrowserUtils.hostFromUrl(bm.url) }, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleMedium) }
            items(history, key = { it.id }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenUrl(entry.url) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(entry.title.take(1).uppercase().ifBlank { "H" }, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title.ifBlank { entry.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(BrowserUtils.prettyUrl(entry.url), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        item {
            Text(
                "Web is a private browser built on Android System WebView. Your tabs and history stay on-device. Private tabs are not saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp)
            )
        }
    }
}

@Composable
private fun TabSwitcher(
    tabs: List<com.vayunmathur.web.util.BrowserTab>,
    activeTabId: String?,
    onSwitch: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewPrivateTab: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: WebViewModel,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("${tabs.size} tabs") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        IconClose()
                    }
                },
                actions = {
                    IconButton(onClick = onNewTab) {
                        IconAdd()
                    }
                }
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSwitch(tab.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tab.isPrivate) {
                                        Text("Private • ", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Text(
                                        tab.title.ifBlank { tab.url.ifBlank { "New Tab" } },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                }
                                Text(
                                    BrowserUtils.prettyUrl(tab.url),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onClose(tab.id) }) {
                                IconClose()
                            }
                        }
                    }
                }

                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        com.vayunmathur.library.ui.OutlinedButton(
                            onClick = onNewTab,
                            modifier = Modifier.weight(1f)
                        ) { Text("New tab") }
                        com.vayunmathur.library.ui.OutlinedButton(
                            onClick = onNewPrivateTab,
                            modifier = Modifier.weight(1f)
                        ) { Text("Private tab") }
                    }
                }
            }
        }
    }
}
