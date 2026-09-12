package com.vayunmathur.web

import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.ui.BookmarksPage
import com.vayunmathur.web.ui.BrowserPage
import com.vayunmathur.web.ui.DownloadsPage
import com.vayunmathur.web.ui.HistoryPage
import com.vayunmathur.web.ui.InstalledSitesPage
import com.vayunmathur.web.ui.SettingsPage
import com.vayunmathur.web.ui.ShieldsPage
import com.vayunmathur.web.ui.SiteDataPage

@Composable
fun Navigation(viewModel: WebViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Browser)
    backStack.openSettingsIfRequested(Route.Settings)
    // On expanded windows the browser stays visible while History/Bookmarks and
    // friends open beside it instead of covering it. MainNavigation only two-panes
    // when it sees list+detail metadata, so on smaller widths we pass none and every
    // destination stays a full-screen push exactly as before.
    val expanded = isExpandedWidth()
    val listPane = if (expanded) ListPage() else emptyMap<String, Any>()
    val detailPane = if (expanded) ListDetailPage() else emptyMap<String, Any>()
    MainNavigation(backStack) {
        entry<Route.Browser>(metadata = listPane) { BrowserPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.History>(metadata = detailPane) { HistoryPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Bookmarks>(metadata = detailPane) { BookmarksPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Settings>(metadata = detailPane) { SettingsPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Downloads>(metadata = detailPane) { DownloadsPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.SiteData>(metadata = detailPane) { SiteDataPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.InstalledSites>(metadata = detailPane) { InstalledSitesPage(viewModel = viewModel, backStack = backStack) }
        entry<Route.Shields>(metadata = detailPane) { ShieldsPage(viewModel = viewModel, backStack = backStack) }
    }
}
