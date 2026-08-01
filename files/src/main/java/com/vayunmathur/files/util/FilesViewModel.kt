package com.vayunmathur.files.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.FileObserver
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.ZipFile

/**
 * Migrated from okio FileSystem/Path/openZip to java.io.File + java.util.zip.ZipFile.
 * Real FS uses File; zip browsing is virtual backed by ZipFile entries.
 */
data class FileBrowserItem(
    val name: String,
    val isDirectory: Boolean,
    val size: Long?,
    val realFile: File?,
    val zipInnerPath: String?,
    val key: String,
)

class FilesViewModel(application: Application) : AndroidViewModel(application), FilesActions {

    private val prefs =
        application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    private val _isFilesGranted = MutableStateFlow(Environment.isExternalStorageManager())
    val isFilesGranted: StateFlow<Boolean> = _isFilesGranted.asStateFlow()

    fun refreshPermissions() {
        val granted = Environment.isExternalStorageManager()
        if (_isFilesGranted.value != granted) {
            _isFilesGranted.value = granted
            if (granted) loadDirectory()
        }
    }

    private val _hasPromptedNotifications =
        MutableStateFlow(prefs.getBoolean("has_prompted_notifications", false))
    val hasPromptedNotifications: StateFlow<Boolean> = _hasPromptedNotifications.asStateFlow()

    fun setNotificationsPrompted() {
        prefs.edit { putBoolean("has_prompted_notifications", true) }
        _hasPromptedNotifications.value = true
    }

    // ---- Navigation ----
    val rootDirectory: File = Environment.getExternalStorageDirectory()

    private val _currentDirectory = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    private val _zipPath = MutableStateFlow<File?>(null)
    val zipPath: StateFlow<File?> = _zipPath.asStateFlow()

    private val _zipInternalPath = MutableStateFlow("")
    val zipInternalPath: StateFlow<String> = _zipInternalPath.asStateFlow()

    fun isZipMode(): Boolean = _zipPath.value != null

    // ---- Listing ----
    private val _entries =
        MutableStateFlow<Pair<List<FileBrowserItem>, List<FileBrowserItem>>>(emptyList<FileBrowserItem>() to emptyList())
    val entries: StateFlow<Pair<List<FileBrowserItem>, List<FileBrowserItem>>> = _entries.asStateFlow()

    // ---- Selection (only valid in real FS mode) ----
    private val _selectedPaths = MutableStateFlow<Set<FileBrowserItem>>(emptySet())
    val selectedPaths: StateFlow<Set<FileBrowserItem>> = _selectedPaths.asStateFlow()

    override fun clearSelection() {
        if (_selectedPaths.value.isNotEmpty()) _selectedPaths.value = emptySet()
    }

    override fun addToSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        _selectedPaths.value = _selectedPaths.value + item
    }

    override fun toggleSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        val current = _selectedPaths.value
        _selectedPaths.value = if (current.any { it.key == item.key }) {
            current.filterNot { it.key == item.key }.toSet()
        } else {
            current + item
        }
    }

    // ---- Share URIs ----
    private val _incomingUris = MutableStateFlow<List<Uri>?>(null)
    val incomingUris: StateFlow<List<Uri>?> = _incomingUris.asStateFlow()

    fun setIncomingUris(uris: List<Uri>) { _incomingUris.value = uris }
    fun clearIncomingUris() { _incomingUris.value = null }

    // ---- Events ----
    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)
    val intents: SharedFlow<Intent> = _intents.asSharedFlow()

    private var observerJob: Job? = null

    init {
        loadDirectory()
        restartObserver()
    }

    fun loadDirectory() {
        val zipFile = _zipPath.value
        if (zipFile == null) {
            val dir = _currentDirectory.value
            viewModelScope.launch(Dispatchers.IO) {
                _entries.value = listRealDir(dir)
            }
        } else {
            val inner = _zipInternalPath.value
            viewModelScope.launch(Dispatchers.IO) {
                _entries.value = listZipDir(zipFile, inner)
            }
        }
    }

    private fun restartObserver() {
        observerJob?.cancel()
        if (isZipMode()) {
            observerJob = null
            return
        }
        val dir = _currentDirectory.value
        observerJob = viewModelScope.launch {
            val observer = object : FileObserver(dir, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) { loadDirectory() }
            }
            observer.startWatching()
            try { awaitCancellation() } finally { observer.stopWatching() }
        }
    }

    override fun navigateTo(path: File) {
        if (isZipMode()) {
            _zipPath.value = null
            _zipInternalPath.value = ""
        }
        _currentDirectory.value = path
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    override fun navigateIntoZipDir(dirName: String) {
        val current = _zipInternalPath.value
        val newPath = if (current.isEmpty()) dirName else "$current/$dirName"
        _zipInternalPath.value = newPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipInternalPath(fullInternalPath: String) {
        _zipInternalPath.value = fullInternalPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipParentRealFolder(target: File) {
        // breadcrumb click on real-FS part while in zip mode → exit zip
        _zipPath.value = null
        _zipInternalPath.value = ""
        _currentDirectory.value = target
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    override fun handleBack(): Boolean {
        if (_selectedPaths.value.isNotEmpty()) { clearSelection(); return true }
        val z = _zipPath.value
        when {
            z != null -> {
                val internal = _zipInternalPath.value
                if (internal.isEmpty()) {
                    val parent = z.parentFile ?: rootDirectory
                    _zipPath.value = null
                    _currentDirectory.value = parent
                    restartObserver()
                } else {
                    val parentInternal = if (internal.contains("/")) internal.substringBeforeLast("/") else ""
                    _zipInternalPath.value = parentInternal
                }
                loadDirectory()
            }
            _currentDirectory.value.absolutePath != rootDirectory.absolutePath -> {
                _currentDirectory.value = _currentDirectory.value.parentFile ?: _currentDirectory.value
                loadDirectory()
                restartObserver()
            }
            else -> return false
        }
        return true
    }

    override fun rename(item: FileBrowserItem, newName: String) {
        if (isZipMode()) return
        val path = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(path.parentFile, newName)
                path.atomicMoveTo(target)
                clearSelection()
                loadDirectory()
            } catch (e: Exception) { emitMoveFailed(e) }
        }
    }

    override fun deleteSelection() {
        if (isZipMode()) return
        val selection = _selectedPaths.value.mapNotNull { it.realFile }
        viewModelScope.launch(Dispatchers.IO) {
            selection.forEach { it.deleteRecursively() }
            clearSelection()
            loadDirectory()
        }
    }

    override fun moveInto(sources: List<File>, target: File) {
        if (isZipMode()) return
        if (!target.isDirectory) return
        moveFiles(sources, target) { source -> source != target && !target.absolutePath.startsWith(source.absolutePath) }
    }

    override fun moveToBreadcrumb(sources: List<File>, target: File) {
        if (isZipMode()) return
        moveFiles(sources, target) { source -> source.parentFile != target && source != target }
    }

    private fun moveFiles(sources: List<File>, target: File, canMove: (File) -> Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            var movedAny = false
            var lastError: Exception? = null
            sources.forEach { source ->
                if (canMove(source)) {
                    try {
                        val dest = File(target, source.name)
                        source.atomicMoveTo(dest)
                        movedAny = true
                    } catch (e: Exception) { lastError = e }
                }
            }
            if (movedAny) { clearSelection(); loadDirectory() }
            lastError?.let { emitMoveFailed(it) }
        }
    }

    override fun openZipFile(item: FileBrowserItem) {
        val file = item.realFile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ZipFile(file).use { _ -> }
                _zipPath.value = file
                _zipInternalPath.value = ""
                _currentDirectory.value = File("/") // placeholder not used in zip mode
                clearSelection()
                loadDirectory()
                restartObserver()
            } catch (e: Exception) {
                emit(getApplication<Application>().getString(R.string.could_not_open_zip, e.localizedMessage))
            }
        }
    }

    override fun archive(archiveName: String) {
        if (isZipMode()) return
        val ctx = getApplication<Application>()
        val sources = _selectedPaths.value.mapNotNull { it.realFile?.absolutePath }.toTypedArray()
        val destFileName = if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip"
        val destFile = File(_currentDirectory.value, destFileName)
        if (sources.isEmpty()) return
        val zipWork = OneTimeWorkRequestBuilder<ZipWorker>().setInputData(
            workDataOf("source_paths" to sources, "dest_path" to destFile.absolutePath)
        ).build()
        WorkManager.getInstance(ctx).enqueue(zipWork)
        clearSelection()
        emit(ctx.getString(R.string.archiving_started))
    }

    fun unzip(zipItem: FileBrowserItem, destPath: File) {
        val zipFile = zipItem.realFile ?: return
        val ctx = getApplication<Application>()
        val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>().setInputData(
            workDataOf("zip_path" to zipFile.absolutePath, "dest_path" to destPath.absolutePath)
        ).build()
        WorkManager.getInstance(ctx).enqueue(unzipWork)
        clearSelection()
        emit(ctx.getString(R.string.unzipping_started_to, destPath.name))
    }

    override fun saveIncomingUris() {
        if (isZipMode()) return
        val ctx = getApplication<Application>()
        val uris = _incomingUris.value ?: return
        val target = _currentDirectory.value
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri -> saveUriToPath(ctx, uri, target) }
            clearIncomingUris()
            loadDirectory()
            _snackbarMessages.emit(ctx.getString(R.string.files_saved))
        }
    }

    override fun openFile(item: FileBrowserItem) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val file = item.realFile ?: return
        val extension = file.extension
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        viewModelScope.launch { _intents.emit(intent) }
    }

    fun showMessage(message: String) { emit(message) }
    private fun emit(message: String) { viewModelScope.launch { _snackbarMessages.emit(message) } }
    private fun emitMoveFailed(e: Exception) {
        emit(getApplication<Application>().getString(R.string.move_failed, e.localizedMessage))
    }

    private fun saveUriToPath(context: Context, uri: Uri, targetDir: File) {
        val name = getFileName(context, uri) ?: "shared_file_${System.currentTimeMillis()}"
        val targetFile = File(targetDir, name)
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { out -> input.copyTo(out) }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) return cursor.getString(nameIndex)
                }
            }
        }
        return uri.path?.substringAfterLast('/')
    }

    private fun listRealDir(dir: File): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
        val files = dir.listFiles()?.toList() ?: emptyList()
        val items = files.map { f ->
            FileBrowserItem(
                name = f.name,
                isDirectory = f.isDirectory,
                size = if (f.isFile) f.length() else null,
                realFile = f,
                zipInnerPath = null,
                key = f.absolutePath
            )
        }
        return items.partition { it.isDirectory }
    }

    private fun listZipDir(zipFile: File, internalDir: String): Pair<List<FileBrowserItem>, List<FileBrowserItem>> {
        return try {
            ZipFile(zipFile).use { zf ->
                val prefix = if (internalDir.isEmpty()) "" else "$internalDir/"
                val dirMap = mutableMapOf<String, FileBrowserItem>()
                val fileList = mutableListOf<FileBrowserItem>()
                for (entry in zf.entries()) {
                    val rawName = entry.name
                    val normalized = rawName.trimEnd('/')
                    if (normalized.isEmpty()) continue
                    if (normalized == internalDir) continue
                    if (internalDir.isNotEmpty() && !rawName.startsWith(prefix) && !normalized.startsWith(prefix)) continue
                    val remainder = if (prefix.isEmpty()) normalized else {
                        if (normalized.length <= prefix.length) continue
                        normalized.substring(prefix.length)
                    }
                    if (remainder.isEmpty()) continue
                    val slashIdx = remainder.indexOf('/')
                    if (slashIdx != -1) {
                        val first = remainder.substring(0, slashIdx)
                        if (first.isEmpty()) continue
                        if (!dirMap.containsKey(first)) {
                            val fullInner = if (internalDir.isEmpty()) first else "$internalDir/$first"
                            dirMap[first] = FileBrowserItem(
                                name = first,
                                isDirectory = true,
                                size = null,
                                realFile = null,
                                zipInnerPath = fullInner,
                                key = "zip:$fullInner"
                            )
                        }
                    } else {
                        if (entry.isDirectory) {
                            if (!dirMap.containsKey(remainder)) {
                                val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                                dirMap[remainder] = FileBrowserItem(
                                    name = remainder,
                                    isDirectory = true,
                                    size = null,
                                    realFile = null,
                                    zipInnerPath = fullInner,
                                    key = "zip:$fullInner"
                                )
                            }
                        } else {
                            val fullInner = if (internalDir.isEmpty()) remainder else "$internalDir/$remainder"
                            fileList.add(
                                FileBrowserItem(
                                    name = remainder,
                                    isDirectory = false,
                                    size = entry.size.takeIf { it >= 0 },
                                    realFile = null,
                                    zipInnerPath = fullInner,
                                    key = "zip:$fullInner"
                                )
                            )
                        }
                    }
                }
                dirMap.values.toList() to fileList
            }
        } catch (_: Exception) {
            emptyList<FileBrowserItem>() to emptyList()
        }
    }

    private fun File.atomicMoveTo(target: File) {
        try {
            java.nio.file.Files.move(this.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!this.renameTo(target)) {
                if (this.isDirectory) {
                    this.copyRecursively(target, overwrite = true)
                    this.deleteRecursively()
                } else {
                    this.copyTo(target, overwrite = true)
                    this.delete()
                }
            }
        }
    }
}
