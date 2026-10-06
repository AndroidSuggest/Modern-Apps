package com.vayunmathur.office

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.ui.OfficeEditorWideLayout
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.needsSaveAs
import com.vayunmathur.office.util.save
import kotlinx.coroutines.CoroutineScope

private val DrawerWidth = 280.dp
private val SavingSpinnerSize = 20.dp
private const val TIMER_TICK_MS = 1000L

/**
 * Error body for a document that failed to load.
 */
@Composable
internal fun MainActivity.EditorError(message: String, onOpenDocument: () -> Unit) {
    ErrorBody(message, onOpenDocument)
}

/** Error body content. */
@Composable
internal fun ErrorBody(message: String, onOpenDocument: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenDocument) {
            Text(stringResource(R.string.open_document))
        }
    }
}

/** Timer + back-handler effects for [DocumentScreen]. */
@Composable
internal fun DocumentEffects(
    s: DocumentScreenState,
    chrome: DocumentChrome,
    onBack: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
) {
    // Presentation timer
    LaunchedEffect(s.showTimer) {
        if (s.showTimer) {
            s.timerSeconds = 0
            while (s.showTimer) { kotlinx.coroutines.delay(TIMER_TICK_MS); s.timerSeconds++ }
        }
    }

    // Always intercept back inside a document so it returns to the home screen instead of exiting
    // the app. Online docs sync live (nothing to save); offline docs with edits prompt to save.
    BackHandler(enabled = true) {
        if (!chrome.isOnline && chrome.hasUnsavedChanges) onShowUnsavedDialog() else onBack()
    }
}

/** Navigation drawer + scaffold shell for [DocumentScreen]. */
@Composable
internal fun DocumentDrawerShell(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    drawerState: DrawerState,
    listState: LazyListState,
    onToggleSearch: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
    sel: ScreenSelection,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    focusedPara: Int,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit,
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome.isTextDoc &&
            (chrome.headings.isNotEmpty() || chrome.bookmarks.isNotEmpty()),
        drawerContent = {
            DrawerOutline(chrome, listState, drawerState, scope)
        }
    ) {
        DocumentScaffoldShell(
            s = s,
            document = document,
            viewModel = viewModel,
            activity = activity,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            drawerState = drawerState,
            listState = listState,
            onToggleSearch = onToggleSearch,
            onShowUnsavedDialog = onShowUnsavedDialog,
            sel = sel,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
            onBack = onBack,
        )
        DrawerScreenOverlays(
            s = s,
            document = document,
            viewModel = viewModel,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            listState = listState,
            focusedPara = focusedPara,
            onBack = onBack,
            onBecameOnline = onBecameOnline,
        )
    }
}

/** Outline drawer content. */
@Composable
internal fun DrawerOutline(
    chrome: DocumentChrome,
    listState: LazyListState,
    drawerState: DrawerState,
    scope: CoroutineScope,
) {
    if (!chrome.isTextDoc) return
    ModalDrawerSheet(Modifier.width(DrawerWidth)) {
        OfficeOutlinePane(
            chrome.bookmarks,
            chrome.headings,
            listState,
            drawerState,
            scope,
            Modifier.fillMaxWidth())
    }
}

/** Trailing overlays for the drawer shell. */
@Composable
internal fun DrawerScreenOverlays(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: CoroutineScope,
    listState: LazyListState,
    focusedPara: Int,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit,
) {
    DocumentOverlays(
            state = s,
            document = document,
            viewModel = viewModel,
            isPresentation = chrome.isPresentation,
            isOnline = chrome.isOnline,
            focusedPara = focusedPara,
            saveAsName = chrome.saveAsName,
            activeCell = s.activeCellState,
            activeSlide = s.activeSlideState,
            activeSlideEl = s.activeSlideElState,
            activeRunStart = s.activeRunStartState,
            activeRunEnd = s.activeRunEndState,
            selStart = s.selStartState,
            selEnd = s.selEndState,
            pendingReplace = s.pendingReplaceState,
            listState = listState,
            scope = scope,
            launchers = launchers,
            onBecameOnline = onBecameOnline,
            onBack = onBack,
        )
    }

/** App-bar scaffold with actions + bottom bar. */
@Composable
internal fun DocumentScaffoldShell(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggleSearch: () -> Unit,
    onShowUnsavedDialog: () -> Unit,
    sel: ScreenSelection,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    onBack: () -> Unit,
) {
    AppScaffold(
        title = { Text(
            document.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall) },
        navigationIcon = {
            IconButton(onClick = {
                if (!chrome.isOnline && chrome.hasUnsavedChanges) onShowUnsavedDialog() else onBack()
            }) {
                IconBack()
            }
        },
        actions = {
            DocumentScaffoldActions(
                chrome = chrome,
                viewModel = viewModel,
                canEdit = chrome.canEdit,
                saveAsName = chrome.saveAsName,
                launchers = launchers,
                onToggleSearch = onToggleSearch,
            )
        },
        bottomBar = {
            ScaffoldBottomBar(s, document, viewModel, chrome, launchers, sel)
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        DocumentScaffoldBody(
            s = s,
            document = document,
            viewModel = viewModel,
            activity = activity,
            chrome = chrome,
            launchers = launchers,
            scope = scope,
            drawerState = drawerState,
            listState = listState,
            contentPadding = paddingValues,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
        )
    }
}

/** Bottom bar for the document scaffold. */
@Composable
internal fun ScaffoldBottomBar(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    sel: ScreenSelection,
) {
    DocumentBottomBarHost(
        s, document, viewModel, chrome.canEdit, chrome.isTextDoc, chrome.isSpreadsheet, chrome.isPresentation,
        sel.activeRunStart, sel.activeRunEnd, sel.selStart, sel.selEnd, sel.activeCell,
        sel.activeSlide, sel.activeSlideEl, launchers,
        sel.activeTableBlock, sel.activeTableRow, sel.activeTableCol)
}

/** Top-app-bar actions for [DocumentScreen]. */
@Composable
internal fun DocumentScaffoldActions(
    chrome: DocumentChrome,
    viewModel: OfficeViewModel,
    canEdit: Boolean,
    saveAsName: String,
    launchers: DocumentLaunchers,
    onToggleSearch: () -> Unit,
) {
    if (canEdit) IconButton(onClick = { onToggleSearch() }) { IconSearch() }
    IconButton(onClick = { viewModel.undo() }, enabled = chrome.canUndo) { IconUndo() }
    IconButton(onClick = { viewModel.redo() }, enabled = chrome.canRedo) { IconRedo() }
    if (!chrome.isOnline) {
        SaveAction(viewModel, chrome, saveAsName, launchers)
    }
}

/** Save / save-as action (offline only). */
@Composable
internal fun SaveAction(
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    saveAsName: String,
    launchers: DocumentLaunchers,
) {
    IconButton(
        onClick = {
            if (viewModel.needsSaveAs()) {
                launchers.saveAs.launch(saveAsName)
            } else {
                viewModel.save()
            }
        },
        enabled = chrome.hasUnsavedChanges && !chrome.isSaving,
    ) {
        if (chrome.isSaving) CircularProgressIndicator(Modifier.size(SavingSpinnerSize)) else IconSave()
    }
}

/** Scaffold body: menu bar, find bars, and the editor pane. */
@Composable
internal fun DocumentScaffoldBody(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScaffoldMenuBars(
            s = s, document = document, viewModel = viewModel,
            activity = activity, chrome = chrome, launchers = launchers,
            scope = scope, drawerState = drawerState, listState = listState)
        // Timer/search/font/word bars: see DocumentFindBars (file-length split).
        DocumentEditorPane(
            s = s,
            document = document,
            viewModel = viewModel,
            chrome = chrome,
            listState = listState,
            scope = scope,
            drawerState = drawerState,
            onRunSelectionChange = onRunSelectionChange,
            onCellFocus = onCellFocus,
            onCellSelected = onCellSelected,
            onSlideChange = onSlideChange,
            onSlideElementSelected = onSlideElementSelected,
            modifier = modifier,
        )
    }
}

/** Menu + find bars for the scaffold body. */
@Composable
internal fun ScaffoldMenuBars(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    chrome: DocumentChrome,
    launchers: DocumentLaunchers,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    DocumentMenuBar(
        s = s,
        document = document,
        viewModel = viewModel,
        activity = activity,
        isTextDoc = chrome.isTextDoc,
        isSpreadsheet = chrome.isSpreadsheet,
        isPresentation = chrome.isPresentation,
        isOnline = chrome.isOnline,
        onlineEnabled = chrome.onlineEnabled,
        hasUnsavedChanges = chrome.hasUnsavedChanges,
        saveAsName = chrome.saveAsName,
        wordCount = chrome.wordCount,
        charCount = chrome.charCount,
        readingTime = chrome.readingTime,
        nightMode = chrome.nightMode,
        documentDarkMode = chrome.documentDarkMode,
        launchers = launchers,
        scope = scope,
        drawerState = drawerState,
    )
    DocumentFindBars(
        s = s,
        document = document,
        viewModel = viewModel,
        isTextDoc = chrome.isTextDoc,
        isPresentation = chrome.isPresentation,
        wordCount = chrome.wordCount,
        charCount = chrome.charCount,
        readingTime = chrome.readingTime,
        scope = scope,
        listState = listState,
    )
}

/** Editor pane with the wide-layout outline branch. */
@Composable
internal fun DocumentEditorPane(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    listState: androidx.compose.foundation.lazy.LazyListState,
    scope: kotlinx.coroutines.CoroutineScope,
    drawerState: com.vayunmathur.library.ui.DrawerState,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        val showOutline = isExpandedWidth() && chrome.isTextDoc &&
            (chrome.headings.isNotEmpty() || chrome.bookmarks.isNotEmpty())
        if (showOutline) {
            OfficeEditorWideLayout(
                outline = { OfficeOutlinePane(
                    chrome.bookmarks,
                    chrome.headings,
                    listState,
                    drawerState,
                    scope,
                    Modifier.fillMaxSize()) },
                document = {
                    EditorDocumentPane(
                        s = s, document = document, viewModel = viewModel, chrome = chrome,
                        listState = listState, onRunSelectionChange = onRunSelectionChange,
                        onCellFocus = onCellFocus, onCellSelected = onCellSelected,
                        onSlideChange = onSlideChange, onSlideElementSelected = onSlideElementSelected,
                        modifier = Modifier.fillMaxSize())
                },
            )
        } else {
            EditorDocumentPane(
                s = s, document = document, viewModel = viewModel, chrome = chrome,
                listState = listState, onRunSelectionChange = onRunSelectionChange,
                onCellFocus = onCellFocus, onCellSelected = onCellSelected,
                onSlideChange = onSlideChange, onSlideElementSelected = onSlideElementSelected,
                modifier = Modifier.fillMaxSize())
        }
    }
}

/** Single shared [OfficeDocumentPane] call (compact + wide layouts). */
@Composable
internal fun EditorDocumentPane(
    s: DocumentScreenState,
    document: OdfDocument,
    viewModel: OfficeViewModel,
    chrome: DocumentChrome,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRunSelectionChange: (Int, Int, Int, Int) -> Unit,
    onCellFocus: (Int, Int, Int) -> Unit,
    onCellSelected: (Int, Int, Int) -> Unit,
    onSlideChange: (Int) -> Unit,
    onSlideElementSelected: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeSlideEl by s.activeSlideElState
    OfficeDocumentPane(
        document = document,
        viewModel = viewModel,
        documentDarkMode = chrome.documentDarkMode,
        nightMode = chrome.nightMode,
        searchQuery = s.searchQuery,
        fontSizeMultiplier = s.fontSizeMultiplier,
        listState = listState,
        isEditMode = chrome.isEditMode,
        activeSlideEl = activeSlideEl,
        onRunSelectionChange = onRunSelectionChange,
        onCellFocus = onCellFocus,
        onCellSelected = onCellSelected,
        onSlideChange = onSlideChange,
        onSlideElementSelected = onSlideElementSelected,
        onChartClick = { bi -> s.editingChartBlock = bi; s.showChartEditor = true },
        onCropImage = { bi -> s.cropImageBlock = bi },
        onCropSlide = { si, e -> s.cropSlideTarget = si to e },
        onCropSheet = { si, e -> s.cropSheetTarget = si to e },
        modifier = modifier,
    )
}
