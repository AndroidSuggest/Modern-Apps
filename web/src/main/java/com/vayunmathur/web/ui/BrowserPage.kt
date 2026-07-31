package com.vayunmathur.web.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.LinearProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SearchBar
import com.vayunmathur.library.ui.SearchBarInputField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconMoreVert
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.util.BrowserUtils
import com.vayunmathur.web.util.WebViewModel
import com.vayunmathur.web.util.isNewTab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val webViewPool = remember { mutableStateMapOf<String, WebView>() }

    LaunchedEffect(viewModel.tabs.size) {
        if (viewModel.tabs.isEmpty()) viewModel.newTab()
    }

    val activeTab = viewModel.activeTab
    val canGoBack = activeTab?.let { viewModel.getCanGoBack(it.id) } ?: false
    val canGoForward = activeTab?.let { viewModel.getCanGoForward(it.id) } ?: false
    val progress = activeTab?.let { viewModel.getProgress(it.id) } ?: 0f
    val isNewTabActive = activeTab?.isNewTab ?: true

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val isCurrentBookmarked = activeTab?.url?.let { url -> url.isNotBlank() && bookmarks.any { it.url == url } } ?: false

    val multiDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.deliverFileChooserResult(uris.toTypedArray().takeIf { it.isNotEmpty() })
    }
    val singleDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.deliverFileChooserResult(uri?.let { arrayOf(it) })
    }

    var showMenu by remember { mutableStateOf(false) }

    BackHandler(enabled = viewModel.showTabSwitcher) { viewModel.showTabSwitcher = false }
    BackHandler(enabled = !viewModel.showTabSwitcher && viewModel.omniboxFocused) {
        viewModel.omniboxFocused = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = !viewModel.showTabSwitcher && !viewModel.omniboxFocused && canGoBack) {
        activeTab?.let { tab -> webViewPool[tab.id]?.goBack() }
    }

    // Hoist search filtering outside LazyListScope
    val currentDraft = viewModel.searchDraft
    val filteredBookmarks = remember(currentDraft, bookmarks) {
        if (currentDraft.isBlank()) bookmarks.take(5)
        else bookmarks.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(8)
    }
    val filteredHistory = remember(currentDraft, history) {
        if (currentDraft.isBlank()) history.take(10)
        else history.filter { it.url.contains(currentDraft, true) || it.title.contains(currentDraft, true) }.take(15)
    }

    Box(Modifier.fillMaxSize()) {
        if (viewModel.omniboxFocused) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                SearchBar(
                    inputField = {
                        SearchBarInputField(
                            query = viewModel.searchDraft,
                            onQueryChange = { viewModel.searchDraft = it },
                            onSearch = { q ->
                                if (q.isNotBlank()) viewModel.navigateActiveTab(q)
                                focusManager.clearFocus()
                                viewModel.omniboxFocused = false
                            },
                            expanded = true,
                            onExpandedChange = { expanded ->
                                if (!expanded) {
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }
                            },
                            placeholder = { Text("Search or enter address") },
                            leadingIcon = {
                                IconButton(onClick = {
                                    focusManager.clearFocus()
                                    viewModel.omniboxFocused = false
                                }) { IconBack() }
                            },
                            trailingIcon = if (viewModel.searchDraft.isNotEmpty()) {
                                {
                                    IconButton(onClick = { viewModel.searchDraft = "" }) { IconClose() }
                                }
                            } else null
                        )
                    },
                    expanded = true,
                    onExpandedChange = { expanded ->
                        if (!expanded) {
                            focusManager.clearFocus()
                            viewModel.omniboxFocused = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            activeTab?.let { webViewPool[it.id]?.reload() }
                                            focusManager.clearFocus()
                                            viewModel.omniboxFocused = false
                                        },
                                        enabled = !isNewTabActive,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        IconRefresh()
                                        Spacer(Modifier.width(6.dp))
                                        Text("Reload")
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.searchDraft = "" },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        IconClose()
                                        Spacer(Modifier.width(6.dp))
                                        Text("Clear")
                                    }
                                }
                            }
                        }

                        if (currentDraft.isNotBlank()) {
                            item {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.navigateActiveTab(currentDraft)
                                            focusManager.clearFocus()
                                            viewModel.omniboxFocused = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        IconSearch()
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(text = currentDraft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                text = BrowserUtils.hostFromUrl(BrowserUtils.toNavigationUrl(currentDraft)),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
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
                                        .clickable {
                                            viewModel.navigateActiveTab(bm.url)
                                            focusManager.clearFocus()
                                            viewModel.omniboxFocused = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconSearch()
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
                                        .clickable {
                                            viewModel.navigateActiveTab(h.url)
                                            focusManager.clearFocus()
                                            viewModel.omniboxFocused = false
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconSearch()
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
            }
        } else {
            Scaffold(
                topBar = {
                    Column {
                        TopAppBar(
                            navigationIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { if (canGoBack) activeTab?.let { webViewPool[it.id]?.goBack() } },
                                        enabled = canGoBack
                                    ) { IconBack() }
                                    IconButton(
                                        onClick = { if (canGoForward) activeTab?.let { webViewPool[it.id]?.goForward() } },
                                        enabled = canGoForward
                                    ) { IconArrowForward() }
                                }
                            },
                            title = {
                                DisplayOnlyAddressPill(
                                    fullUrl = viewModel.omniboxText,
                                    onClick = {
                                        val full = activeTab?.url?.let { if (it.isBlank() || it == "about:blank") "" else it } ?: ""
                                        viewModel.searchDraft = full
                                        viewModel.omniboxFocused = true
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            actions = {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { viewModel.showTabSwitcher = true }
                                ) {
                                    Text(
                                        text = viewModel.tabs.size.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { showMenu = true }) { IconMoreVert() }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (isCurrentBookmarked) "Remove bookmark" else "Add bookmark") },
                                        onClick = {
                                            showMenu = false
                                            activeTab?.let { tab ->
                                                if (tab.url.isBlank()) return@let
                                                if (isCurrentBookmarked) {
                                                    bookmarks.find { it.url == tab.url }?.let { viewModel.removeBookmark(it) }
                                                } else viewModel.addBookmark(tab.url, tab.title.ifBlank { tab.url })
                                            }
                                        }
                                    )
                                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                                        showMenu = false
                                        activeTab?.let { tab ->
                                            if (tab.url.isBlank()) return@let
                                            val sendIntent = android.content.Intent().apply {
                                                action = android.content.Intent.ACTION_SEND
                                                putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                                                type = "text/plain"
                                            }
                                            context.startActivity(android.content.Intent.createChooser(sendIntent, "Share link"))
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("New tab") }, onClick = { showMenu = false; viewModel.newTab() })
                                    DropdownMenuItem(text = { Text("New private tab") }, onClick = { showMenu = false; viewModel.newTab(isPrivate = true) })
                                    DropdownMenuItem(text = { Text("History") }, onClick = { showMenu = false; backStack.add(Route.History) })
                                    DropdownMenuItem(text = { Text("Bookmarks") }, onClick = { showMenu = false; backStack.add(Route.Bookmarks) })
                                    DropdownMenuItem(text = { Text("Downloads") }, onClick = { showMenu = false; backStack.add(Route.Downloads) })
                                    DropdownMenuItem(text = { Text("Site data") }, onClick = { showMenu = false; backStack.add(Route.SiteData) })
                                    DropdownMenuItem(text = { Text("Settings") }, onClick = { showMenu = false; backStack.add(Route.Settings) })
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
                        )
                        if (activeTab != null && !isNewTabActive && progress in 0.01f..0.99f) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
                        }
                    }
                }
            ) { paddingValues ->
                Column(Modifier.fillMaxSize().padding(paddingValues)) {
                    if (isNewTabActive) {
                        QuickAccess(
                            bookmarks = bookmarks.take(12),
                            history = history.take(8),
                            onOpenUrl = { url -> activeTab?.let { viewModel.onTabUrlChange(it.id, url) } },
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
                modifier = Modifier.fillMaxSize()
            )
        }

        viewModel.pendingPermissionPrompt?.let { prompt ->
            PermissionPromptSheet(
                origin = prompt.origin,
                types = prompt.types,
                onGrant = { granted ->
                    prompt.onGrant(granted)
                    viewModel.clearPermissionPrompt()
                },
                onDeny = {
                    prompt.onDeny()
                    viewModel.clearPermissionPrompt()
                }
            )
        }

        viewModel.pendingGeolocationPrompt?.let { (origin, _, _) ->
            GeolocationPromptSheet(
                origin = origin,
                onAllow = { viewModel.grantGeolocation(origin) },
                onDeny = { viewModel.denyGeolocation() }
            )
        }

        viewModel.pendingFileChooser?.let { (_, params) ->
            val mimeTypes = try { params.acceptTypes.toList() } catch (_: Exception) { emptyList() }
            val allowMultiple = try { params.mode == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE } catch (_: Exception) { false }
            FileChooserSheet(
                mimeTypes = mimeTypes,
                onFiles = { uris ->
                    if (uris == null) viewModel.clearFileChooser() else viewModel.deliverFileChooserResult(uris)
                },
                onCancel = { viewModel.clearFileChooser() },
                onTriggerPicker = {
                    try {
                        if (allowMultiple) {
                            multiDocLauncher.launch(mimeTypes.filter { it.isNotBlank() }.toTypedArray().takeIf { it.isNotEmpty() } ?: arrayOf("*/*"))
                        } else {
                            val mt = mimeTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                            singleDocLauncher.launch(arrayOf(mt))
                        }
                    } catch (_: Exception) { viewModel.clearFileChooser() }
                }
            )
        }
    }
}

@Composable
private fun DisplayOnlyAddressPill(
    fullUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)
    ) {
        val isPlaceholder = fullUrl.isBlank()
        Text(
            text = if (isPlaceholder) "Search or enter address" else fullUrl,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isPlaceholder) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
            else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun QuickAccess(
    bookmarks: List<com.vayunmathur.web.data.Bookmark>,
    history: List<com.vayunmathur.web.data.HistoryEntry>,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (bookmarks.isNotEmpty()) {
            item { Text("Bookmarks", style = MaterialTheme.typography.titleMedium) }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 16.dp)) {
                    items(bookmarks, key = { it.id }) { bm ->
                        Card(
                            modifier = Modifier.width(140.dp).clickable { onOpenUrl(bm.url) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
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
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onOpenUrl(entry.url) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
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
                "Blank New Tab — tap the address pill to search. Full URL visible in the pill. Reload/clear live inside expanded SearchBar card.",
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
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.98f))) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("${tabs.size} tabs") },
                navigationIcon = { IconButton(onClick = onDismiss) { IconClose() } },
                actions = { IconButton(onClick = onNewTab) { IconAdd() } }
            )
            LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    val displayTitle = when {
                        tab.isNewTab -> "New Tab"
                        tab.title.isNotBlank() -> tab.title
                        else -> BrowserUtils.prettyUrl(tab.url).ifBlank { "New Tab" }
                    }
                    val displayUrl = if (tab.isNewTab) "" else tab.url
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onSwitch(tab.id) },
                        colors = CardDefaults.cardColors(containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (tab.isPrivate) Text("Private • ", style = MaterialTheme.typography.labelSmall)
                                    Text(displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                }
                                if (displayUrl.isNotBlank()) {
                                    Text(displayUrl, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { onClose(tab.id) }) { IconClose() }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onNewTab, modifier = Modifier.weight(1f)) { Text("New tab") }
                        OutlinedButton(onClick = onNewPrivateTab, modifier = Modifier.weight(1f)) { Text("Private tab") }
                    }
                }
            }
        }
    }
}
