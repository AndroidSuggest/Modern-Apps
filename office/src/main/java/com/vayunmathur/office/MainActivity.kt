package com.vayunmathur.office

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.core.content.IntentCompat
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import androidx.compose.foundation.pager.rememberPagerState
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.ExpandVisibility
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconSettings
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.LocalContentColor
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.util.OfflineBanner
import com.vayunmathur.library.util.rememberIsOnline
import com.vayunmathur.library.ui.NavigationBar
import com.vayunmathur.library.ui.NavigationBarItem
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.OutlinedTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import android.content.ClipData
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.zIndex
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TextFieldDefaults
import com.vayunmathur.library.ui.rememberDrawerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.stringResource
import com.vayunmathur.office.R
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.dynamicLightColorScheme
import com.vayunmathur.library.ui.dynamicDarkColorScheme
import com.vayunmathur.library.ui.Typography
import com.vayunmathur.office.odf.*
import com.vayunmathur.library.ui.odf.*
import com.vayunmathur.office.ui.*
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.addShapeToSheet
import com.vayunmathur.office.util.addShapeToSlide
import com.vayunmathur.office.util.deleteSlideElement
import com.vayunmathur.office.util.initSync
import com.vayunmathur.office.util.clearDocument
import com.vayunmathur.office.util.loadDocument
import com.vayunmathur.office.util.needsSaveAs
import com.vayunmathur.office.util.save
import com.vayunmathur.office.util.insertImage
import com.vayunmathur.office.util.insertImageIntoSheet
import com.vayunmathur.office.util.insertImageIntoSlide
import com.vayunmathur.office.util.openOnlineDocument
import com.vayunmathur.office.util.requestToJoin
import com.vayunmathur.office.util.runParagraphIndexAt
import com.vayunmathur.office.util.setLocalCaret
import kotlinx.coroutines.launch

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

            val odfMimeTypes = arrayOf(
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
                val editorContent: @Composable () -> Unit = {
                    when (val s = state) {
                        is OfficeViewModel.ViewState.Loaded -> DocumentScreen(
                            document = s.document, viewModel = viewModel, activity = this@MainActivity,
                            onBack = { leaveEditor() },
                            // When an offline doc is shared it becomes a cloud doc — switch its route.
                            onBecameOnline = { id -> backStack.setLast(OfficeRoute.OnlineEditor(id)) }
                        )
                        is OfficeViewModel.ViewState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(R.string.error_loading), style = MaterialTheme.typography.titleMedium)
                                Text(s.message, Modifier.padding(16.dp))
                                Button(onClick = { leaveEditor() }) { Text(stringResource(R.string.open_document)) }
                            }
                        }
                        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
        }
    }

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

// Home screens (OfficeTabs/InitialScreen/HomeScreen): see OfficeHomeScreens.kt (file-length split).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(document: OdfDocument, viewModel: OfficeViewModel, activity: ComponentActivity, onBack: () -> Unit, onBecameOnline: (String) -> Unit = {}) {
    val s = rememberDocumentScreenState()
    // Shell-local aliases for state the scaffold chrome reads directly.
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

    val isEditMode by viewModel.isEditMode.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val onlineEnabled by viewModel.onlineEnabled.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val autoSaveEnabled by viewModel.autoSaveEnabled.collectAsState()
    val nightMode by viewModel.nightMode.collectAsState()
    val documentThemeMode by viewModel.documentThemeMode.collectAsState()
    val isSystemDark = isSystemInDarkTheme()
    val documentDarkMode = when (documentThemeMode) {
        OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM -> isSystemDark
        OfficeViewModel.DocumentThemeMode.UNCHANGED -> false
    }
    val selectionInvalidation by viewModel.selectionInvalidation.collectAsState()

    // A4: reset hoisted selection whenever the document changes shape (undo/redo) so
    // formatting/color/delete never target a stale cell/element.
    LaunchedEffect(selectionInvalidation) {
        activeCell = null
        activeSlideEl = -1
        activeRunStart = -1; activeRunEnd = -1
        activeTableBlock = -1; activeTableRow = -1; activeTableCol = -1
    }

    val isTextDoc = document is OdfDocument.TextDocument
    val isSpreadsheet = document is OdfDocument.Spreadsheet
    val isPresentation = document is OdfDocument.Presentation
    val canEdit = isTextDoc || isSpreadsheet || isPresentation
    val saveAsName = document.title.substringBeforeLast('.').ifBlank { "Untitled" } +
        when { isTextDoc -> ".odt"; isSpreadsheet -> ".ods"; isPresentation -> ".odp"; else -> ".odg" }
    val focusedPara = if (activeRunStart >= 0 && isTextDoc) viewModel.runParagraphIndexAt(activeRunStart, activeRunEnd, selStart) else -1

    val headings = remember(document) { if (document is OdfDocument.TextDocument) extractHeadings(document) else emptyList() }
    val wordCount = remember(document) { if (document is OdfDocument.TextDocument) countWords(document) else 0 }
    val charCount = remember(document) { if (document is OdfDocument.TextDocument) countChars(document) else 0 }
    val readingTime = remember(document) { if (document is OdfDocument.TextDocument) readingTimeMinutes(document) else 0 }
    val bookmarks = remember(document) { if (document is OdfDocument.TextDocument) document.bookmarks else emptyList() }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val context = LocalContext.current

    val launchers = rememberDocumentLaunchers(
        viewModel = viewModel,
        holder = s,
        onPickImageBytes = { name, bytes ->
            when (document) {
                is OdfDocument.TextDocument -> if (focusedPara >= 0) viewModel.insertImage(focusedPara, name, bytes)
                is OdfDocument.Presentation -> viewModel.insertImageIntoSlide(activeSlide, name, bytes)
                is OdfDocument.Spreadsheet -> viewModel.insertImageIntoSheet(activeCell?.first ?: 0, name, bytes)
                else -> {}
            }
        },
    )

    // Presentation timer
    LaunchedEffect(s.showTimer) {
        if (s.showTimer) {
            s.timerSeconds = 0
            while (s.showTimer) { kotlinx.coroutines.delay(1000); s.timerSeconds++ }
        }
    }

    // Always intercept back inside a document so it returns to the home screen instead of exiting
    // the app. Online docs sync live (nothing to save); offline docs with edits prompt to save.
    BackHandler(enabled = true) {
        if (!isOnline && hasUnsavedChanges) showUnsavedDialog = true else onBack()
    }

    // Outline/document panes: see OfficeOutlinePane.kt / OfficeDocumentPane.kt (file-length split).

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty()),
        drawerContent = {
            if (isTextDoc) {
                ModalDrawerSheet(Modifier.width(280.dp)) {
                    OfficeOutlinePane(bookmarks, headings, listState, drawerState, scope, Modifier.fillMaxWidth())
                }
            }
        }
    ) {
        AppScaffold(
            title = { Text(document.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = { if (!isOnline && hasUnsavedChanges) showUnsavedDialog = true else onBack() }) {
                    IconBack()
                }
            },
            actions = {
                if (canEdit) IconButton(onClick = { showSearch = !showSearch }) { IconSearch() }
                IconButton(onClick = { viewModel.undo() }, enabled = canUndo) { IconUndo() }
                IconButton(onClick = { viewModel.redo() }, enabled = canRedo) { IconRedo() }
                if (!isOnline) {
                    IconButton(onClick = { if (viewModel.needsSaveAs()) launchers.saveAs.launch(saveAsName) else viewModel.save() }, enabled = hasUnsavedChanges && !isSaving) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(20.dp)) else IconSave()
                    }
                }
            },
            bottomBar = {
                if (canEdit) {
                    val formatTarget: FormatTarget = when {
                        isTextDoc && activeRunStart >= 0 -> FormatTarget.TextRun(activeRunStart, activeRunEnd, selStart, selEnd)
                        isSpreadsheet && (activeCell?.second ?: -1) >= 0 -> FormatTarget.Cell(activeCell!!.first, activeCell!!.second, activeCell!!.third)
                        isPresentation && activeSlideEl >= 0 -> FormatTarget.Element(activeSlide, activeSlideEl)
                        else -> FormatTarget.None
                    }
                    val caps = when {
                        isTextDoc -> DocCaps(insertImage = true, insertChart = true, insertTable = true)
                        isPresentation -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
                        isSpreadsheet -> DocCaps(insertImage = true, insertShape = true, insertChart = true)
                        else -> DocCaps()
                    }
                    val activeSheet = activeCell?.first ?: 0
                    val actions = BottomBarActions(
                        onTextColor = { s.showColorPicker = true },
                        onCellTextColor = { s.showCellTextColor = true },
                        onCellBgColor = { s.showCellBgColor = true },
                        onSlideTextColor = { s.showSlideTextColor = true },
                        onSlideFill = { s.showSlideFillColor = true },
                        onSlideStroke = { s.showSlideStrokeColor = true },
                        onFontSize = { s.showFontSizePicker = true },
                        onInsertImage = { launchers.imagePicker.launch("image/*") },
                        onInsertShape = { kind ->
                            when {
                                isPresentation -> viewModel.addShapeToSlide(activeSlide, kind.name.lowercase())
                                isSpreadsheet -> viewModel.addShapeToSheet(activeSheet, kind.name.lowercase())
                            }
                        },
                        onInsertChart = { s.editingChartBlock = -1; s.chartForSlide = isPresentation; s.chartForSheet = isSpreadsheet; s.showChartEditor = true },
                        onInsertTable = { s.showInsertTable = true },
                        onDeleteElement = { if (activeSlideEl >= 0) { viewModel.deleteSlideElement(activeSlide, activeSlideEl); activeSlideEl = -1 } },
                        onCellBorder = { s.showCellBorderColor = true },
                        onCellComment = { s.showCellComment = true },
                        onCellResize = { s.showCellResize = true },
                        onSlideNotes = { s.showSlideNotes = true },
                        onSlideBackground = { s.showSlideBackground = true },
                        onSlideTransition = { s.showSlideTransition = true }
                    )
                    OfficeBottomBar(document, formatTarget, caps, viewModel, actions, activeTableBlock, activeTableRow, activeTableCol)
                }
            },
            scrollBehavior = appBarScrollBehavior(),
        ) { paddingValues ->
            Column(Modifier.fillMaxSize().padding(paddingValues)) {
                    DocumentMenuBar(
                        s = s,
                        document = document,
                        viewModel = viewModel,
                        activity = activity,
                        isTextDoc = isTextDoc,
                        isSpreadsheet = isSpreadsheet,
                        isPresentation = isPresentation,
                        isOnline = isOnline,
                        onlineEnabled = onlineEnabled,
                        hasUnsavedChanges = hasUnsavedChanges,
                        focusedPara = focusedPara,
                        saveAsName = saveAsName,
                        wordCount = wordCount,
                        charCount = charCount,
                        readingTime = readingTime,
                        nightMode = nightMode,
                        documentDarkMode = documentDarkMode,
                        launchers = launchers,
                        scope = scope,
                        drawerState = drawerState,
                        listState = listState,
                    )
                    DocumentFindBars(
                        s = s,
                        document = document,
                        viewModel = viewModel,
                        isTextDoc = isTextDoc,
                        isPresentation = isPresentation,
                        wordCount = wordCount,
                        charCount = charCount,
                        readingTime = readingTime,
                        scope = scope,
                        listState = listState,
                    )
                    // Timer/search/font/word bars: see DocumentFindBars (file-length split).
            Box(Modifier.weight(1f)) {
                if (isExpandedWidth() && isTextDoc && (headings.isNotEmpty() || bookmarks.isNotEmpty())) {
                    OfficeEditorWideLayout(
                        outline = { OfficeOutlinePane(bookmarks, headings, listState, drawerState, scope, Modifier.fillMaxSize()) },
                        document = {
                            OfficeDocumentPane(
                                document = document,
                                viewModel = viewModel,
                                documentDarkMode = documentDarkMode,
                                nightMode = nightMode,
                                searchQuery = s.searchQuery,
                                fontSizeMultiplier = s.fontSizeMultiplier,
                                listState = listState,
                                isEditMode = isEditMode,
                                activeSlide = activeSlide,
                                activeSlideEl = activeSlideEl,
                                onBack = onBack,
                                onRunSelectionChange = { rs, re, gs, ge -> activeRunStart = rs; activeRunEnd = re; selStart = gs; selEnd = ge; activeTableBlock = -1; activeTableRow = -1; activeTableCol = -1; viewModel.setLocalCaret(gs) },
                                onCellFocus = { bi, r, c -> activeTableBlock = bi; activeTableRow = r; activeTableCol = c },
                                onCellSelected = { si, r, c -> activeCell = Triple(si, r, c) },
                                onSlideChange = { activeSlide = it },
                                onSlideElementSelected = { si, e -> activeSlide = si; activeSlideEl = e },
                                onChartClick = { bi -> s.editingChartBlock = bi; s.showChartEditor = true },
                                onCropImage = { bi -> s.cropImageBlock = bi },
                                onCropSlide = { si, e -> s.cropSlideTarget = si to e },
                                onCropSheet = { si, e -> s.cropSheetTarget = si to e },
                                modifier = Modifier.fillMaxSize(),
                            )
                        },
                    )
                } else {
                    OfficeDocumentPane(
                        document = document,
                        viewModel = viewModel,
                        documentDarkMode = documentDarkMode,
                        nightMode = nightMode,
                        searchQuery = s.searchQuery,
                        fontSizeMultiplier = s.fontSizeMultiplier,
                        listState = listState,
                        isEditMode = isEditMode,
                        activeSlide = activeSlide,
                        activeSlideEl = activeSlideEl,
                        onBack = onBack,
                        onRunSelectionChange = { rs, re, gs, ge -> activeRunStart = rs; activeRunEnd = re; selStart = gs; selEnd = ge; activeTableBlock = -1; activeTableRow = -1; activeTableCol = -1; viewModel.setLocalCaret(gs) },
                        onCellFocus = { bi, r, c -> activeTableBlock = bi; activeTableRow = r; activeTableCol = c },
                        onCellSelected = { si, r, c -> activeCell = Triple(si, r, c) },
                        onSlideChange = { activeSlide = it },
                        onSlideElementSelected = { si, e -> activeSlide = si; activeSlideEl = e },
                        onChartClick = { bi -> s.editingChartBlock = bi; s.showChartEditor = true },
                        onCropImage = { bi -> s.cropImageBlock = bi },
                        onCropSlide = { si, e -> s.cropSlideTarget = si to e },
                        onCropSheet = { si, e -> s.cropSheetTarget = si to e },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            }
        }
    }

    DocumentOverlays(
        state = s,
        document = document,
        viewModel = viewModel,
        isTextDoc = isTextDoc,
        isPresentation = isPresentation,
        isOnline = isOnline,
        focusedPara = focusedPara,
        saveAsName = saveAsName,
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
    // Crop/chart overlays moved to DocumentOverlays (file-length split).
}

// Metadata/print helpers moved to OfficeDocumentUtils.kt (file-length split).

// Metadata/print helpers moved to OfficeDocumentUtils.kt (file-length split).

// --- Print ---
