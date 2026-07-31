package com.vayunmathur.files

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.SnackbarHost
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.vayunmathur.files.util.FileBrowserItem
import com.vayunmathur.files.util.FilesViewModel
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconArchive
import com.vayunmathur.library.ui.IconChevronRight
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconEdit
import com.vayunmathur.library.ui.IconFile
import com.vayunmathur.library.ui.IconFolder
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUnarchive
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: FilesViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        enableEdgeToEdge()
        setContent { DynamicTheme { HomeDirectoryPage(viewModel) } }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent?.takeIf { it.type != null } ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(listOf(it)) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { viewModel.setIncomingUris(it) }
        }
    }
}

@Composable
fun HomeDirectoryPage(viewModel: FilesViewModel) {
    val context = LocalContext.current
    val isFilesGranted by viewModel.isFilesGranted.collectAsState()
    val hasPromptedNotifications by viewModel.hasPromptedNotifications.collectAsState()
    var showNotificationDialog by remember { mutableStateOf(false) }

    val filesLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissions()
        }

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            viewModel.setNotificationsPrompted()
            showNotificationDialog = false
        }

    LaunchedEffect(isFilesGranted, hasPromptedNotifications) {
        if (isFilesGranted && !hasPromptedNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val isGranted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                showNotificationDialog = true
            } else {
                viewModel.setNotificationsPrompted()
            }
        }
    }

    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = {
                viewModel.setNotificationsPrompted()
                showNotificationDialog = false
            },
            title = { Text(stringResource(R.string.enable_notifications)) },
            text = { Text(stringResource(R.string.notification_permission_rationale)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationsLauncher.launch(
                                Manifest.permission.POST_NOTIFICATIONS
                            )
                        }
                    }) { Text(stringResource(R.string.enable)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.setNotificationsPrompted()
                        showNotificationDialog = false
                    }) { Text(stringResource(R.string.skip)) }
            })
    }

    if (!isFilesGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Button(
                onClick = {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts(
                                "package", context.packageName, null
                            )
                        }
                    filesLauncher.launch(intent)
                }) { Text(stringResource(R.string.grant_all_files_access)) }
        }
    } else {
        DirectoryPage(viewModel)
    }
}

private fun fileAncestors(from: File?, upTo: File?): List<File> = buildList {
    var p = from
    while (p != null) {
        add(0, p)
        if (upTo != null && p.absolutePath == upTo.absolutePath) break
        p = p.parentFile
        // Prevent infinite if upTo not in chain: stop when p becomes child of upTo? We'll just keep going up
        // but avoid adding files above upTo by breaking if upTo != null and p != null and !from!!.absolutePath.startsWith(p.absolutePath) etc not needed
        // Simpler: if upTo != null and p != null and !p.absolutePath.startsWith(upTo.absolutePath) and upTo.absolutePath != p.absolutePath? then break after adding upTo?
        // For our external storage use case it's fine.
    }
}

private data class Crumb(
    val displayName: String,
    val realFile: File?,
    val zipInternalPath: String?, // non-null = zip mode crumb
)

fun dropTarget(
    onDragStateChange: (Boolean) -> Unit,
    onDrop: (List<File>) -> Unit
) = object : DragAndDropTarget {
    override fun onDrop(event: DragAndDropEvent): Boolean {
        onDragStateChange(false)
        val clipData = event.toAndroidDragEvent().clipData ?: return false
        if (clipData.itemCount == 0) return false
        val files = (0 until clipData.itemCount).mapNotNull {
            val txt = clipData.getItemAt(it).text?.toString() ?: return@mapNotNull null
            val f = File(txt)
            if (f.exists()) f else null
        }
        if (files.isEmpty()) return false
        onDrop(files)
        return true
    }
    override fun onEntered(event: DragAndDropEvent) { onDragStateChange(true) }
    override fun onExited(event: DragAndDropEvent) { onDragStateChange(false) }
    override fun onEnded(event: DragAndDropEvent) { onDragStateChange(false) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryPage(viewModel: FilesViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbarHostState = remember { SnackbarHostState() }

    val currentDirectory by viewModel.currentDirectory.collectAsState()
    val zipPath by viewModel.zipPath.collectAsState()
    val zipInternalPath by viewModel.zipInternalPath.collectAsState()
    val selectedPaths by viewModel.selectedPaths.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val incomingUris by viewModel.incomingUris.collectAsState()

    val isReadOnly = zipPath != null

    var pathBeingRenamed by remember { mutableStateOf<FileBrowserItem?>(null) }
    var showArchiveDialog by remember { mutableStateOf(false) }
    var archiveName by remember { mutableStateOf("archive.zip") }

    LaunchedEffect(snackbarHostState) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.intents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (_: Exception) {
                viewModel.showMessage(resources.getString(R.string.no_app_found_to_open_file))
            }
        }
    }

    LaunchedEffect(selectedPaths) {
        if (selectedPaths.isEmpty()) pathBeingRenamed = null
    }

    if (showArchiveDialog) {
        AlertDialog(
            onDismissRequest = { showArchiveDialog = false },
            title = { Text(stringResource(R.string.archive_selection)) },
            text = {
                TextField(
                    value = archiveName,
                    onValueChange = { archiveName = it },
                    label = { Text(stringResource(R.string.zip_file_name_label)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.archive(archiveName)
                        showArchiveDialog = false
                    }) { Text(stringResource(R.string.archive)) }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            })
    }

    val zipToUnzip = remember(selectedPaths) {
        selectedPaths.singleOrNull()?.takeIf {
            !it.isDirectory && it.realFile != null && it.name.endsWith(".zip", ignoreCase = true)
        }
    }

    val treeLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null && zipToUnzip != null) {
                val path = uri.path?.split(":")?.lastOrNull()?.let {
                    File(Environment.getExternalStorageDirectory(), it)
                } ?: currentDirectory
                viewModel.unzip(zipToUnzip, path)
            }
        }

    val (directories, files) = entries

    val focusManager = LocalFocusManager.current

    val root = viewModel.rootDirectory

    val breadcrumbs = remember(currentDirectory, zipPath, zipInternalPath) {
        val crumbs = mutableListOf<Crumb>()
        if (zipPath == null) {
            fileAncestors(currentDirectory, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) Build.MODEL else f.name, realFile = f, zipInternalPath = null))
            }
        } else {
            val zp = zipPath!!
            val parent = zp.parentFile ?: root
            fileAncestors(parent, root).forEach { f ->
                crumbs.add(Crumb(displayName = if (f.absolutePath == root.absolutePath) Build.MODEL else f.name, realFile = f, zipInternalPath = null))
            }
            crumbs.add(Crumb(displayName = zp.name, realFile = null, zipInternalPath = ""))
            var accum = ""
            for (seg in zipInternalPath.split("/").filter { it.isNotEmpty() }) {
                accum = if (accum.isEmpty()) seg else "$accum/$seg"
                crumbs.add(Crumb(displayName = seg, realFile = null, zipInternalPath = accum))
            }
        }
        crumbs
    }

    BackHandler(currentDirectory.absolutePath != root.absolutePath || selectedPaths.isNotEmpty() || zipPath != null) {
        pathBeingRenamed = null
        viewModel.handleBack()
    }

    Scaffold(
        modifier = Modifier
            .imePadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) {
                focusManager.clearFocus()
                pathBeingRenamed = null
                viewModel.clearSelection()
            }, snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
            TopAppBar(title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    breadcrumbs.forEachIndexed { index, crumb ->
                        var isBreadcrumbDraggingOver by remember(crumb) {
                            mutableStateOf(false)
                        }

                        val canDrop = !isReadOnly && crumb.realFile != null && crumb.zipInternalPath == null

                        Box(
                            modifier = Modifier
                                .background(
                                    if (isBreadcrumbDraggingOver) MaterialTheme.colorScheme.primaryContainer.copy(
                                        alpha = 0.5f
                                    )
                                    else Color.Transparent, shape = MaterialTheme.shapes.small
                                )
                                .then(
                                    if (canDrop) {
                                        Modifier.dragAndDropTarget(
                                            shouldStartDragAndDrop = { event ->
                                                event.mimeTypes().contains(
                                                    ClipDescription.MIMETYPE_TEXT_PLAIN
                                                )
                                            },
                                            target = remember(crumb.realFile!!.absolutePath) {
                                                dropTarget(
                                                    onDragStateChange = { isBreadcrumbDraggingOver = it },
                                                    onDrop = { sources -> viewModel.moveToBreadcrumb(sources, crumb.realFile) }
                                                )
                                            })
                                    } else Modifier
                                )
                                .clickable {
                                    val rf = crumb.realFile
                                    if (rf != null) {
                                        if (zipPath != null) viewModel.navigateToZipParentRealFolder(rf)
                                        else viewModel.navigateTo(rf)
                                    } else {
                                        viewModel.navigateToZipInternalPath(crumb.zipInternalPath ?: "")
                                    }
                                }
                                .padding(4.dp)) {
                            Text(
                                text = crumb.displayName, style = MaterialTheme.typography.titleLarge
                            )
                        }
                        if (index < breadcrumbs.size - 1) {
                            IconChevronRight(tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }, actions = {
                if (selectedPaths.isNotEmpty()) {
                    IconButton(
                        onClick = { viewModel.clearSelection() }) { IconClose() }
                }
                if (incomingUris != null && !isReadOnly) {
                    IconButton(
                        onClick = { viewModel.saveIncomingUris() }) { IconSave() }
                }
                if (!isReadOnly) {
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                archiveName =
                                    if (selectedPaths.size == 1) "${selectedPaths.first().name}.zip"
                                    else "archive.zip"
                                showArchiveDialog = true
                            }) { IconArchive() }
                    }
                    if (zipToUnzip != null) {
                        IconButton(onClick = { treeLauncher.launch(null) }) {
                            IconUnarchive()
                        }
                    }
                    if (selectedPaths.size == 1) {
                        IconButton(
                            onClick = { pathBeingRenamed = selectedPaths.first() }) { IconEdit() }
                    }
                    if (selectedPaths.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.deleteSelection() }) { IconDelete() }
                    }
                }
            })
        }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            val allItems =
                directories.sortedBy { it.name.lowercase() } + files.sortedBy { it.name.lowercase() }

            items(allItems, key = { it.key }) { child ->
                val isSelected = selectedPaths.any { it.key == child.key }
                val isEditing = pathBeingRenamed?.key == child.key

                DirectoryItem(
                    file = child,
                    isEditing = isEditing,
                    isSelected = isSelected,
                    isReadOnly = isReadOnly,
                    onRename = { newName ->
                        pathBeingRenamed = null
                        viewModel.rename(child, newName)
                    },
                    onToggleSelection = {
                        if (isReadOnly) return@DirectoryItem
                        if (pathBeingRenamed != null) pathBeingRenamed = null
                        if (!isSelected) {
                            viewModel.addToSelection(child)
                        }
                    },
                    onClick = {
                        if (selectedPaths.isNotEmpty()) {
                            if (isSelected && pathBeingRenamed?.key == child.key) {
                                pathBeingRenamed = null
                            }
                            viewModel.toggleSelection(child)
                        } else if (child.isDirectory) {
                            if (child.realFile != null) viewModel.navigateTo(child.realFile)
                            else viewModel.navigateIntoZipDir(child.name)
                        } else if (child.name.endsWith(".zip", ignoreCase = true) && child.realFile != null) {
                            viewModel.openZipFile(child)
                        } else {
                            viewModel.openFile(child)
                        }
                    },
                    onMove = { sources ->
                        if (!isReadOnly && child.isDirectory && child.realFile != null) {
                            viewModel.moveInto(sources, child.realFile)
                        }
                    },
                    onStartDrag = {
                        if (isReadOnly) emptyList()
                        else if (selectedPaths.any { it.key == child.key }) selectedPaths.mapNotNull { it.realFile }.toList()
                        else listOfNotNull(child.realFile)
                    })
                HorizontalDivider(
                    thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryItem(
    file: FileBrowserItem,
    isEditing: Boolean,
    isSelected: Boolean,
    isReadOnly: Boolean,
    onRename: (String) -> Unit,
    onToggleSelection: () -> Unit,
    onClick: () -> Unit,
    onMove: (List<File>) -> Unit,
    onStartDrag: () -> List<File>
) {
    var editedName by remember(isEditing) { mutableStateOf(file.name) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var isDraggingOver by remember { mutableStateOf(false) }
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStartDrag by rememberUpdatedState(onStartDrag)

    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isDraggingOver) MaterialTheme.colorScheme.primaryContainer
                else if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                else Color.Transparent
            )
            .dragAndDropSource { _ ->
                val paths = currentOnStartDrag()
                if (paths.isEmpty()) return@dragAndDropSource null

                val uris = paths.map { path ->
                    FileProvider.getUriForFile(
                        context, "${context.packageName}.fileprovider", path
                    )
                }
                val mimeTypes = paths.map { path ->
                    val extension = path.name.substringAfterLast(
                        '.', ""
                    )
                    if (extension == "md") "text/markdown"
                    else MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                        extension
                    ) ?: "*/*"
                }.toMutableList().apply {
                    add(
                        ClipDescription.MIMETYPE_TEXT_PLAIN
                    )
                }.distinct().toTypedArray()

                val clipData = ClipData(
                    paths.first().name, mimeTypes, ClipData.Item(
                        paths.first().absolutePath, null, null, uris.first()
                    )
                )
                for (i in 1 until uris.size) {
                    clipData.addItem(
                        ClipData.Item(
                            paths[i].absolutePath, null, null, uris[i]
                        )
                    )
                }

                DragAndDropTransferData(
                    clipData = clipData,
                    flags = View.DRAG_FLAG_GLOBAL or View.DRAG_FLAG_GLOBAL_URI_READ
                )
            }
            .then(
                if (file.isDirectory && !isReadOnly && file.realFile != null) {
                    Modifier.dragAndDropTarget(shouldStartDragAndDrop = { event ->
                        event.mimeTypes().contains(
                            ClipDescription.MIMETYPE_TEXT_PLAIN
                        )
                    }, target = remember(file.key) {
                        dropTarget(
                            onDragStateChange = { isDraggingOver = it },
                            onDrop = { currentOnMove(it) }
                        )
                    })
                } else Modifier
            )
            .combinedClickable(onClick = onClick, onLongClick = onToggleSelection)
    ) {
        ListItem(
            content = {
                if (isEditing) {
                    TextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onRename(editedName) })
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                } else {
                    Text(file.name.ifEmpty { "/" })
                }
            }, leadingContent = {
                val iconTint = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline
                if (file.isDirectory) IconFolder(tint = iconTint)
                else IconFile(tint = iconTint)
            }, supportingContent = {
                if (!file.isDirectory) {
                    file.size?.let { size -> Text(Formatter.formatShortFileSize(context, size)) }
                }
            }, colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
