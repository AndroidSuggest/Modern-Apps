package com.vayunmathur.office

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.createNewPresentation
import com.vayunmathur.office.util.createNewSpreadsheet
import com.vayunmathur.office.util.createNewTextDocument

/**
 * The two bottom-nav tabs, hosted in a swipeable pager (see [TabbedPagerScaffold]).
 * OfflineEditor and OnlineEditor are pushed on top of this host as ordinary routes.
 */
@Composable
internal fun OfficeTabs(
    viewModel: OfficeViewModel,
    onOpenDocument: () -> Unit,
    onNavigateEditor: () -> Unit,
    onOpenOnlineDoc: (com.vayunmathur.office.util.OfficeDocMeta) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val tabs = listOf(
        PagerTab("Offline", { IconHome() }) {
            InitialScreen(
                viewModel = viewModel,
                onOpenDocument = onOpenDocument,
                onNavigateEditor = onNavigateEditor
            )
        },
        PagerTab("Online", { IconDownload() }) {
            OnlineTab(viewModel = viewModel, onOpenDoc = onOpenOnlineDoc)
        },
    )
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}

@Composable
fun InitialScreen(viewModel: OfficeViewModel, onOpenDocument: () -> Unit, onNavigateEditor: () -> Unit) {
    HomeScreen(
        onOpenDocument = onOpenDocument,
        onNewTextDocument = { viewModel.createNewTextDocument(); onNavigateEditor() },
        onNewSpreadsheet = { viewModel.createNewSpreadsheet(); onNavigateEditor() },
        onNewPresentation = { viewModel.createNewPresentation(); onNavigateEditor() },
    )
}

/**
 * The home screen, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`, which is where the store listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: () -> Unit = {},
    onNewTextDocument: () -> Unit = {},
    onNewSpreadsheet: () -> Unit = {},
    onNewPresentation: () -> Unit = {},
) {
    AppScaffold(title = stringResource(R.string.app_name), scrollBehavior = appBarScrollBehavior()) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).background(MaterialTheme.colorScheme.background).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.open_document_format_viewer_editor), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(32.dp))

            Button(onClick = onOpenDocument, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.open_document)) }
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.opens_odf_word_excel_powerpoint_csv_tsv),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNewTextDocument, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_doc)) }
                OutlinedButton(onClick = onNewSpreadsheet, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_sheet)) }
                OutlinedButton(onClick = onNewPresentation, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_slides)) }
            }
        }
    }
}
