package com.vayunmathur.office

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.content.IntentCompat
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.NavigationBar
import com.vayunmathur.library.ui.NavigationBarItem
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.Typography
import com.vayunmathur.library.ui.dynamicDarkColorScheme
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.OfflineBanner
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.office.R
import com.vayunmathur.office.ui.BottomBarActions
import com.vayunmathur.office.ui.DocCaps
import com.vayunmathur.office.ui.FormatTarget
import com.vayunmathur.office.ui.OfficeBottomBar
import com.vayunmathur.office.ui.OfficeEditorWideLayout
import com.vayunmathur.office.ui.countChars
import com.vayunmathur.office.ui.countWords
import com.vayunmathur.office.ui.extractHeadings
import com.vayunmathur.office.ui.readingTimeMinutes
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.addShapeToSheet
import com.vayunmathur.office.util.addShapeToSlide
import com.vayunmathur.office.util.clearDocument
import com.vayunmathur.office.util.deleteSlideElement
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.insertImage
import com.vayunmathur.office.util.insertImageIntoSheet
import com.vayunmathur.office.util.insertImageIntoSlide
import com.vayunmathur.office.util.loadDocument
import com.vayunmathur.office.util.needsSaveAs
import com.vayunmathur.office.util.openOnlineDocument
import com.vayunmathur.office.util.requestToJoin
import com.vayunmathur.office.util.runParagraphIndexAt
import com.vayunmathur.office.util.save
import com.vayunmathur.office.util.setLocalCaret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.isExpandedWidth

// Public so `src/screenshotTest` can wrap a document exactly the way DocumentScreen does;
// without it the listing images would show the paper in the wrong scheme.
@Composable
fun OfficeLightTheme(content: @Composable () -> Unit) {
    // Light scheme — used only for the rendered document (the "paper"), which stays light in dark mode.
    val colorScheme = dynamicLightColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

// Inverts luminance (white paper <-> black) while preserving hue, matching the
// "invert(1) hue-rotate(180deg)" trick used for document dark modes like Google Docs.
private val ErrorPadding = 16.dp
private val DrawerWidth = 280.dp
private val SavingSpinnerSize = 20.dp
private const val TIMER_TICK_MS = 1000L

private val DocumentDarkModeColorMatrix = ColorMatrix(
    floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ),
)

/**
 * Renders the content into an offscreen layer and paints it back through a
 * luminance-inverting color filter, so the displayed document appears dark
 * without altering the underlying document colors. View-only (#479).
 */
fun Modifier.documentDarkModeInvert(): Modifier = this.drawWithContent {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(DocumentDarkModeColorMatrix)
    }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
        drawContent()
        canvas.restore()
    }
}

@Composable
private fun OfficeAppTheme(content: @Composable () -> Unit) {
    // The app chrome (menus, toolbars, home) is dark; the document content re-wraps in the light theme.
    val colorScheme = dynamicDarkColorScheme(LocalContext.current)
    MaterialTheme(colorScheme = colorScheme, typography = Typography(), content = content)
}

/** Top-level navigation routes for the Office app (shared nav framework). */
@Serializable
sealed interface OfficeRoute : NavKey {
    @Serializable data object Main : OfficeRoute
    /** Editing a local/offline document (optionally identified by its source uri). */
    @Serializable data class OfflineEditor(val uri: String? = null) : OfficeRoute
    /** Editing a cloud-synced document, identified by its document id. */
    @Serializable data class OnlineEditor(val docId: String) : OfficeRoute
}

class MainActivity : ComponentActivity() {
    private val viewModel: OfficeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FIRST_PARTY: api.vayunmathur.com + data.vayunmathur.com -> ISRG+GTS
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        enableEdgeToEdge()
        viewModel.loadSettings(this)

        // office://join links request access to someone else's doc — handle them here and keep
        // them out of the document-loading path (their scheme isn't content/file).
        val joinLink = parseJoinLink(intent)
        if (joinLink != null) handleJoinLink(joinLink)
        val intentUri: Uri? = if (joinLink == null) extractDocumentUri(intent) else null

        setContent {
            OfficeRoot(intentUri)
        }
    }

    /** Root content: nav graph over home + editor routes. */
    @Composable
    private fun OfficeRoot(intentUri: Uri?) {
        val startedWithIntent = intentUri != null
        var documentUri by rememberSaveable { mutableStateOf(intentUri) }
        val state by viewModel.state.collectAsState()
        val backStack = rememberNavBackStack<OfficeRoute>(
            if (intentUri != null) OfficeRoute.OfflineEditor(intentUri.toString()) else OfficeRoute.Main
        )

        LaunchedEffect(Unit) { viewModel.initSync() }

        if (documentUri != null && state is OfficeViewModel.ViewState.Empty) {
            viewModel.loadDocument(documentUri!!, resolveDisplayName(this@MainActivity, documentUri!!))
        }

        val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                persistUriGrant(it)
                documentUri = it
                viewModel.loadDocument(it, resolveDisplayName(this@MainActivity, it))
                backStack.add(OfficeRoute.OfflineEditor(it.toString()))
            }
        }

        fun leaveEditor() {
            if (startedWithIntent) finish()
            else {
                documentUri = null
                viewModel.clearDocument()
                if (backStack.backStack.size > 1) backStack.pop()
            }
        }

        OfficeAppTheme {
            OfficeNavGraph(state, filePickerLauncher, { leaveEditor() }, backStack)
        }
    }

    /**
     * Resolves a human-readable file name (with extension) for an opened uri. Content
     * uris from other apps often expose an opaque document id as their last path
     * segment with no extension; DocumentImporter routes by extension, so query
     * OpenableColumns.DISPLAY_NAME first to keep extension-based routing working for
     * files opened via intents (falling back to the last path segment).
     */
    private fun resolveDisplayName(context: android.content.Context, uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Navigation graph for home + editor routes. */
    @Composable
    private fun OfficeNavGraph(
        state: OfficeViewModel.ViewState,
        filePickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
        onLeaveEditor: () -> Unit,
        backStack: com.vayunmathur.library.util.NavBackStack<OfficeRoute>,
    ) {
        val editorContent: @Composable () -> Unit = {
            when (val s = state) {
                is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                    document = s.document, viewModel = viewModel, activity = this@MainActivity,
                    onBack = { onLeaveEditor() },
                    // When an offline doc is shared it becomes a cloud doc — switch its route.
                    onBecameOnline = { id -> backStack.setLast(OfficeRoute.OnlineEditor(id)) }
                )
                is OfficeViewModel.ViewState.Error -> EditorError(s.message, onLeaveEditor)
                else -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        MainNavigation(backStack) {
            entry<OfficeRoute.Main> {
                OfficeTabs(
                    viewModel = viewModel,
                    onOpenDocument = { filePickerLauncher.launch(odfMimeTypes) },
                    onNavigateEditor = { backStack.add(OfficeRoute.OfflineEditor()) },
                    onOpenOnlineDoc = { meta ->
                        viewModel.openOnlineDocument(meta)
                        backStack.add(OfficeRoute.OnlineEditor(meta.docId))
                    }
                )
            }
            entry<OfficeRoute.OfflineEditor> { editorContent() }
            entry<OfficeRoute.OnlineEditor> { editorContent() }
        }
    }

    /** MIME types accepted by the document picker. */
    private val odfMimeTypes = arrayOf(
        "application/vnd.oasis.opendocument.text",
        "application/vnd.oasis.opendocument.spreadsheet",
        "application/vnd.oasis.opendocument.presentation",
        "application/vnd.oasis.opendocument.graphics",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "text/csv",
        "text/comma-separated-values",
        "text/tab-separated-values",
        "text/markdown",
        "text/plain",
        "text/xml",
        "application/xml"
    )

    /**
     * Holds on to the picker's grant so Save can still write back to the chosen document after the
     * transient grant expires. Falls back to a read-only grant for providers that refuse write.
     */
    private fun persistUriGrant(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { contentResolver.takePersistableUriPermission(uri, readWrite) }.isFailure) {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start join links arrive here rather than through a fresh onCreate.
        setIntent(intent)
        parseJoinLink(intent)?.let { handleJoinLink(it) }
    }

    /** Fires a sealed join request to the owner and confirms with a toast. */
    private fun handleJoinLink(link: Pair<String, String>) {
        val (docId, ownerId) = link
        AppMessages.show(getString(R.string.join_request_sending))
        viewModel.requestToJoin(docId, ownerId) { ok ->
            AppMessages.show(
                getString(if (ok) R.string.join_request_sent else R.string.join_request_failed),
                duration = AppMessages.Duration.Long,
            )
        }
    }

    /** Parses `office://join/<docId>?owner=<ownerDeviceId>`; null for any other intent. */
    private fun parseJoinLink(intent: Intent?): Pair<String, String>? {
        val data = intent?.data ?: return null
        if (data.scheme != "office" || data.host != "join") return null
        val docId = data.lastPathSegment?.takeIf { it.isNotBlank() } ?: return null
        val ownerId = data.getQueryParameter("owner")?.takeIf { it.isNotBlank() } ?: return null
        return docId to ownerId
    }

    /**
     * Resolves the document uri to open from an intent. VIEW/edit intents carry it
     * in [Intent.getData]; SEND intents (files shared from other apps) carry it in
     * [Intent.EXTRA_STREAM].
     */
    private fun extractDocumentUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.data
    }
}

/**
 * Error body for a document that failed to load.
 */
@Composable
private fun MainActivity.EditorError(message: String, onOpenDocument: () -> Unit) {
    ErrorBody(message, onOpenDocument)
}

/** Error body content. */
@Composable
private fun ErrorBody(message: String, onOpenDocument: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    activity: ComponentActivity,
    onBack: () -> Unit,
    onBecameOnline: (String) -> Unit = {}) {
    val s = rememberDocumentScreenState()
    val sel = rememberScreenSelection(s)

    val onRunSelectionChange = rememberRunSelectionHandler(sel, viewModel)

    val chrome = rememberDocumentChrome(viewModel, document)
    val focusedPara = if (sel.activeRunStart >= 0 && chrome.isTextDoc) viewModel.runParagraphIndexAt(
        sel.activeRunStart,
        sel.activeRunEnd,
        sel.selStart) else -1

    DocumentEffects(s, chrome, onBack, { sel.showUnsavedDialog = true })

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val launchers = rememberDocumentLaunchers(
        viewModel = viewModel,
        holder = s,
        onPickImageBytes = rememberImagePickerHandler(
            document, viewModel, focusedPara, sel.activeSlide, sel.activeCell))

    DocumentDrawerShell(
        s = s,
        document = document,
        viewModel = viewModel,
        activity = activity,
        chrome = chrome,
        launchers = launchers,
        scope = scope,
        drawerState = drawerState,
        listState = listState,
        onToggleSearch = { sel.showSearch = !sel.showSearch },
        onShowUnsavedDialog = { sel.showUnsavedDialog = true },
        sel = ScreenSelection(
            sel.activeRunStart, sel.activeRunEnd, sel.activeTableBlock, sel.activeTableRow, sel.activeTableCol,
            sel.selStart, sel.selEnd, sel.activeCell, sel.activeSlide, sel.activeSlideEl),
        onRunSelectionChange = onRunSelectionChange,
        onCellFocus = { bi, r, c -> sel.activeTableBlock = bi; sel.activeTableRow = r; sel.activeTableCol = c },
        onCellSelected = { si, r, c -> sel.activeCell = Triple(si, r, c) },
        onSlideChange = { sel.activeSlide = it },
        onSlideElementSelected = { si, e -> sel.activeSlide = si; sel.activeSlideEl = e },
        focusedPara = focusedPara,
        onBack = onBack,
        onBecameOnline = onBecameOnline,
    )
}

/** Run-selection handler (clears table selection, moves the caret). */
@Composable
private fun rememberRunSelectionHandler(
    sel: ScreenSelectionHolder,
    viewModel: OfficeViewModel,
): (Int, Int, Int, Int) -> Unit = remember(sel, viewModel) {
    { rs: Int, re: Int, gs: Int, ge: Int ->
        sel.activeRunStart = rs
        sel.activeRunEnd = re
        sel.selStart = gs
        sel.selEnd = ge
        sel.activeTableBlock = -1
        sel.activeTableRow = -1
        sel.activeTableCol = -1
        viewModel.setLocalCaret(gs)
    }
}

/** Mutable screen selection state (hoisted from DocumentScreen). */
private class ScreenSelectionHolder(s: DocumentScreenState) {
    var showSearch by s.showSearchState
    var showUnsavedDialog by s.showUnsavedDialogState
    var activeRunStart by s.activeRunStartState
    var activeRunEnd by s.activeRunEndState
    var activeTableBlock by s.activeTableBlockState
    var activeTableRow by s.activeTableRowState
    var activeTableCol by s.activeTableColState
    var selStart by s.selStartState
    var selEnd by s.selEndState
    var activeCell by s.activeCellState
    var activeSlide by s.activeSlideState
    var activeSlideEl by s.activeSlideElState
}

/** Remembered screen selection holder. */
@Composable
private fun rememberScreenSelection(s: DocumentScreenState): ScreenSelectionHolder {
    return remember(s) { ScreenSelectionHolder(s) }
}

/** Timer + back-handler effects for [DocumentScreen]. */
@Composable
private fun DocumentEffects(
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
private fun DocumentDrawerShell(
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
private fun DrawerOutline(
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
private fun DrawerScreenOverlays(
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
private fun DocumentScaffoldShell(
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
            style = MaterialTheme.typography.titleMedium) },
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
private fun ScaffoldBottomBar(
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

/** Value snapshot of the hoisted selection (avoids 9-param plumbing). */
private class ScreenSelection(
    val activeRunStart: Int,
    val activeRunEnd: Int,
    val activeTableBlock: Int,
    val activeTableRow: Int,
    val activeTableCol: Int,
    val selStart: Int,
    val selEnd: Int,
    val activeCell: Triple<Int, Int, Int>?,
    val activeSlide: Int,
    val activeSlideEl: Int,
)

/** Collected + derived document-screen chrome state. */
private class DocumentChrome(
    val isEditMode: Boolean,
    val hasUnsavedChanges: Boolean,
    val isOnline: Boolean,
    val onlineEnabled: Boolean,
    val isSaving: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val nightMode: Boolean,
    val documentDarkMode: Boolean,
    val isTextDoc: Boolean,
    val isSpreadsheet: Boolean,
    val isPresentation: Boolean,
    val canEdit: Boolean,
    val saveAsName: String,
    val headings: List<com.vayunmathur.office.ui.HeadingItem>,
    val wordCount: Int,
    val charCount: Int,
    val readingTime: Int,
    val bookmarks: List<com.vayunmathur.library.ui.odf.OdfBookmark>,
)

/** Collects ViewModel flows + document-derived values for [DocumentScreen]. */
@Composable
private fun rememberDocumentChrome(viewModel: OfficeViewModel, document: OdfDocument): DocumentChrome {
    val isEditMode by viewModel.isEditMode.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val onlineEnabled by viewModel.onlineEnabled.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val documentThemeMode by viewModel.documentThemeMode.collectAsState()
    val documentDarkMode = when (documentThemeMode) {
        OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        OfficeViewModel.DocumentThemeMode.UNCHANGED -> false
    }
    val isTextDoc = document is OdfDocument.TextDocument
    val isSpreadsheet = document is OdfDocument.Spreadsheet
    val isPresentation = document is OdfDocument.Presentation
    val saveAsName = document.title.substringBeforeLast('.').ifBlank { "Untitled" } +
        when { isTextDoc -> ".odt"; isSpreadsheet -> ".ods"; isPresentation -> ".odp"; else -> ".odg" }
    return DocumentChrome(
        isEditMode = isEditMode,
        hasUnsavedChanges = hasUnsavedChanges,
        isOnline = isOnline,
        onlineEnabled = onlineEnabled,
        isSaving = isSaving,
        canUndo = canUndo,
        canRedo = canRedo,
        nightMode = nightMode,
        documentDarkMode = documentDarkMode,
        isTextDoc = isTextDoc,
        isSpreadsheet = isSpreadsheet,
        isPresentation = isPresentation,
        canEdit = isTextDoc || isSpreadsheet || isPresentation,
        saveAsName = saveAsName,
        headings = remember(document) {
            if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList()
        },
        wordCount = remember(document) {
            if (document is OdfDocument.TextDocument) countWords(document) else 0
        },
        charCount = remember(document) {
            if (document is OdfDocument.TextDocument) countChars(document) else 0
        },
        readingTime = remember(document) {
            if (document is OdfDocument.TextDocument) readingTimeMinutes(document) else 0
        },
        bookmarks = remember(document) {
            if (document is OdfDocument.TextDocument) document.bookmarks else emptyList()
        },
    )
}

/** Image-picker handler routing bytes to the active document kind. */
@Composable
private fun rememberImagePickerHandler(
    document: OdfDocument,
    viewModel: OfficeViewModel,
    focusedPara: Int,
    activeSlide: Int,
    activeCell: Triple<Int, Int, Int>?,
): (String, ByteArray) -> Unit = remember(document, focusedPara, activeSlide, activeCell) {
    { name, bytes ->
        when (document) {
            is OdfDocument.TextDocument -> if (focusedPara >= 0) viewModel.insertImage(focusedPara, name, bytes)
            is OdfDocument.Presentation -> viewModel.insertImageIntoSlide(activeSlide, name, bytes)
            is OdfDocument.Spreadsheet -> viewModel.insertImageIntoSheet(activeCell?.first ?: 0, name, bytes)
            else -> {}
        }
    }
}

/** Top-app-bar actions for [DocumentScreen]. */
@Composable
private fun DocumentScaffoldActions(
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
private fun SaveAction(
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
private fun DocumentScaffoldBody(
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
private fun ScaffoldMenuBars(
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
private fun DocumentEditorPane(
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
private fun EditorDocumentPane(
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
