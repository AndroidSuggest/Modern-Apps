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

class FilesViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs =
        application.getSharedPreferences("files_prefs", Context.MODE_PRIVATE)

    // ---- Permission state ----

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

    // ---- Navigation state ----

    val rootDirectory: File = Environment.getExternalStorageDirectory()

    private val _currentDirectory = MutableStateFlow(rootDirectory)
    val currentDirectory: StateFlow<File> = _currentDirectory.asStateFlow()

    /** The on-disk zip file when browsing inside an opened archive; null otherwise. */
    private val _zipPath = MutableStateFlow<File?>(null)
    val zipPath: StateFlow<File?> = _zipPath.asStateFlow()

    /** Relative path inside the opened zip, "" = root. */
    private val _zipInternalPath = MutableStateFlow("")
    val zipInternalPath: StateFlow<String> = _zipInternalPath.asStateFlow()

    fun isZipMode(): Boolean = _zipPath.value != null

    // ---- Directory listing ----

    private val _entries =
        MutableStateFlow<Pair<List<File>, List<File>>>(emptyList<File>() to emptyList())
    val entries: StateFlow<Pair<List<File>, List<File>>> = _entries.asStateFlow()

    // ---- Selection ----

    private val _selectedPaths = MutableStateFlow<Set<File>>(emptySet())
    val selectedPaths: StateFlow<Set<File>> = _selectedPaths.asStateFlow()

    fun clearSelection() {
        if (_selectedPaths.value.isNotEmpty()) _selectedPaths.value = emptySet()
    }

    fun addToSelection(path: File) {
        _selectedPaths.value = _selectedPaths.value + path
    }

    fun toggleSelection(path: File) {
        val current = _selectedPaths.value
        _selectedPaths.value = if (current.any { it.absolutePath == path.absolutePath && it.name == path.name }) {
            current.filterNot { it.absolutePath == path.absolutePath && it.name == path.name }.toSet()
        } else {
            current + path
        }
    }

    // ---- Incoming share-intent URIs ----

    private val _incomingUris = MutableStateFlow<List<Uri>?>(null)
    val incomingUris: StateFlow<List<Uri>?> = _incomingUris.asStateFlow()

    fun setIncomingUris(uris: List<Uri>) {
        _incomingUris.value = uris
    }

    fun clearIncomingUris() {
        _incomingUris.value = null
    }

    // ---- One-shot UI events ----

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
                val files = dir.listFiles()?.toList() ?: emptyList()
                _entries.value = files.partition { it.isDirectory }
            }
        } else {
            val internalPath = _zipInternalPath.value
            viewModelScope.launch(Dispatchers.IO) {
                _entries.value = listZipEntries(zipFile, internalPath)
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
            val observer = object : FileObserver(
                dir, CREATE or DELETE or MOVED_FROM or MOVED_TO,
            ) {
                override fun onEvent(event: Int, path: String?) {
                    loadDirectory()
                }
            }
            observer.startWatching()
            try {
                awaitCancellation()
            } finally {
                observer.stopWatching()
            }
        }
    }

    fun navigateTo(path: File) {
        if (isZipMode()) {
            // If navigating while in zip mode via breadcrumb real-FS part, exit zip mode
            _zipPath.value = null
            _zipInternalPath.value = ""
        }
        _currentDirectory.value = path
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    /** Navigate into a sub-directory inside the opened zip. */
    fun navigateIntoZipDir(dirName: String) {
        val current = _zipInternalPath.value
        val newPath = if (current.isEmpty()) dirName else "$current/$dirName"
        _zipInternalPath.value = newPath
        clearSelection()
        loadDirectory()
    }

    fun navigateToZipInternalPath(fullInternalPath: String) {
        // fullInternalPath is the directory path inside zip, e.g. "a/b"
        _zipInternalPath.value = fullInternalPath
        clearSelection()
        loadDirectory()
    }

    fun handleBack(): Boolean {
        if (_selectedPaths.value.isNotEmpty()) {
            clearSelection()
            return true
        }
        val z = _zipPath.value
        when {
            z != null -> {
                val internal = _zipInternalPath.value
                if (internal.isEmpty()) {
                    // Exit zip mode to parent of zip file
                    val parent = z.parentFile ?: rootDirectory
                    _zipPath.value = null
                    _currentDirectory.value = parent
                } else {
                    // Go up one level inside zip
                    val parentInternal = if (internal.contains("/")) internal.substringBeforeLast("/") else ""
                    _zipInternalPath.value = parentInternal
                }
                loadDirectory()
                restartObserver()
            }
            _currentDirectory.value != rootDirectory -> {
                _currentDirectory.value = _currentDirectory.value.parentFile ?: _currentDirectory.value
                loadDirectory()
                restartObserver()
            }
            else -> return false
        }
        return true
    }

    // ---- File ops ----

    fun rename(path: File, newName: String) {
        if (isZipMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = File(path.parentFile, newName)
                path.atomicMoveTo(target)
                clearSelection()
                loadDirectory()
            } catch (e: Exception) {
                emitMoveFailed(e)
            }
        }
    }

    fun deleteSelection() {
        if (isZipMode()) return
        val selection = _selectedPaths.value
        viewModelScope.launch(Dispatchers.IO) {
            selection.forEach { it.deleteRecursively() }
            clearSelection()
            loadDirectory()
        }
    }

    fun moveInto(sources: List<File>, target: File) {
        if (isZipMode()) return
        if (!target.isDirectory) return
        moveFiles(sources, target) { source ->
            source != target && !target.absolutePath.startsWith(source.absolutePath)
        }
    }

    fun moveToBreadcrumb(sources: List<File>, target: File) {
        if (isZipMode()) return
        moveFiles(sources, target) { source ->
            source.parentFile != target && source != target
        }
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
                    } catch (e: Exception) {
                        lastError = e
                    }
                }
            }
            if (movedAny) {
                clearSelection()
                loadDirectory()
            }
            lastError?.let { emitMoveFailed(it) }
        }
    }

    fun openZipFile(path: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Validate it can be opened
                ZipFile(path).use { _ -> }
                _zipPath.value = path
                _zipInternalPath.value = ""
                _currentDirectory.value = File("/") // fake placeholder for zip root display
                clearSelection()
                loadDirectory()
                restartObserver()
            } catch (e: Exception) {
                emit(
                    getApplication<Application>()
                        .getString(R.string.could_not_open_zip, e.localizedMessage),
                )
            }
        }
    }

    fun archive(archiveName: String) {
        if (isZipMode()) return
        val ctx = getApplication<Application>()
        val sources = _selectedPaths.value
        val destFileName = if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip"
        val destFile = File(_currentDirectory.value, destFileName)
        val zipWork = OneTimeWorkRequestBuilder<ZipWorker>().setInputData(
            workDataOf(
                "source_paths" to sources.map { it.absolutePath }.toTypedArray(),
                "dest_path" to destFile.absolutePath,
            ),
        ).build()
        WorkManager.getInstance(ctx).enqueue(zipWork)
        clearSelection()
        emit(ctx.getString(R.string.archiving_started))
    }

    fun unzip(zipPath: File, destPath: File) {
        val ctx = getApplication<Application>()
        val unzipWork = OneTimeWorkRequestBuilder<UnzipWorker>().setInputData(
            workDataOf(
                "zip_path" to zipPath.absolutePath,
                "dest_path" to destPath.absolutePath,
            ),
        ).build()
        WorkManager.getInstance(ctx).enqueue(unzipWork)
        clearSelection()
        emit(ctx.getString(R.string.unzipping_started_to, destPath.name))
    }

    fun saveIncomingUris() {
        val ctx = getApplication<Application>()
        val uris = _incomingUris.value ?: return
        val target = _currentDirectory.value
        if (isZipMode()) return
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri -> saveUriToPath(ctx, uri, target) }
            clearIncomingUris()
            loadDirectory()
            _snackbarMessages.emit(ctx.getString(R.string.files_saved))
        }
    }

    fun openFile(path: File) {
        val ctx = getApplication<Application>()
        if (isZipMode()) {
            emit(ctx.getString(R.string.zip_browse_only))
            return
        }
        val extension = path.extension
        val mimeType =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", path,
            )
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }
        viewModelScope.launch { _intents.emit(intent) }
    }

    fun showMessage(message: String) {
        emit(message)
    }

    private fun emit(message: String) {
        viewModelScope.launch { _snackbarMessages.emit(message) }
    }

    private fun emitMoveFailed(e: Exception) {
        emit(
            getApplication<Application>()
                .getString(R.string.move_failed, e.localizedMessage),
        )
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

    private fun listZipEntries(zipFile: File, internalDir: String): Pair<List<File>, List<File>> {
        return try {
            ZipFile(zipFile).use { zf ->
                val prefix = if (internalDir.isEmpty()) "" else "$internalDir/"
                val childDirNames = mutableSetOf<String>()
                val childFiles = mutableListOf<File>()

                // Collect entries
                val entries = zf.entries().toList()
                for (entry in entries) {
                    val name = entry.name
                    if (name == internalDir || name == prefix) continue
                    if (!name.startsWith(prefix)) continue
                    var remainder = name.removePrefix(prefix)
                    if (remainder.isEmpty()) continue
                    // Skip leading slash if any
                    remainder = remainder.trimStart('/')
                    if (remainder.isEmpty()) continue
                    if (remainder.contains("/")) {
                        val first = remainder.substringBefore("/")
                        if (first.isNotEmpty()) childDirNames.add(first)
                    } else {
                        // direct child
                        if (entry.isDirectory) {
                            childDirNames.add(remainder.trimEnd('/'))
                        } else {
                            // fake file with name = remainder, path inside zip = prefix+remainder
                            // Use a File whose name is remainder and absolutePath encodes internal path for navigation
                            val fake = object : File("/zip/$prefix$remainder") {
                                override fun getName(): String = remainder
                                override fun isDirectory(): Boolean = false
                                override fun length(): Long = entry.size.takeIf { it >= 0 } ?: 0L
                            }
                            childFiles.add(fake)
                        }
                    }
                }

                // For directories that are implicit (no explicit dir entry), we still have names
                val dirFiles = childDirNames.map { dirName ->
                    object : File("/zip/$prefix$dirName") {
                        override fun getName(): String = dirName
                        override fun isDirectory(): Boolean = true
                        override fun length(): Long = 0L
                    }
                }

                // For entries list we need real File objects for directories that may have size? we already
                dirFiles.toList() to childFiles.toList()
            }
        } catch (e: Exception) {
            emptyList<File>() to emptyList()
        }
    }

    private fun File.atomicMoveTo(target: File) {
        try {
            java.nio.file.Files.move(
                this.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            if (!this.renameTo(target)) {
                // fallback copy+delete
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
