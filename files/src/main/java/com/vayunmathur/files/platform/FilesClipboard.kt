package com.vayunmathur.files.platform

import android.app.Application
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * The clipboard (copy/cut/paste), share and archive actions behind
 * [FilesViewModel], as `internal` extensions so FilesViewModel.kt stays under
 * the FileLength limit. The `FilesActions` overrides stay on the ViewModel and
 * delegate here.
 */

internal fun FilesViewModel.copyFilesToClipboard() {
    if (isZipMode()) return
    val files = _selectedPaths.value.mapNotNull { it.realFile }
    if (files.isEmpty()) return
    _clipboard.value = files
    _clipboardIsCut.value = false
    clearSelection()
    emit(getApplication<Application>().getString(R.string.copied_n, files.size))
}

internal fun FilesViewModel.cutFilesToClipboard() {
    if (isZipMode()) return
    val files = _selectedPaths.value.mapNotNull { it.realFile }
    if (files.isEmpty()) return
    _clipboard.value = files
    _clipboardIsCut.value = true
    clearSelection()
    emit(getApplication<Application>().getString(R.string.cut_n, files.size))
}

internal fun FilesViewModel.pasteClipboardHere() {
    if (isZipMode()) return
    val sources = _clipboard.value
    if (sources.isEmpty()) return
    val target = _currentDirectory.value
    val isCut = _clipboardIsCut.value
    viewModelScope.launch(Dispatchers.IO) {
        var lastError: Exception? = null
        sources.forEach { source ->
            try {
                if (!source.exists()) return@forEach
                // Don't paste a folder into itself or a descendant.
                if (target.absolutePath == source.absolutePath ||
                    target.absolutePath.startsWith(source.absolutePath + "/")
                ) return@forEach
                val dest = uniqueDestination(target, source.name)
                if (isCut) {
                    source.atomicMoveTo(dest)
                } else if (source.isDirectory) {
                    source.copyRecursively(dest, overwrite = false)
                } else {
                    source.copyTo(dest, overwrite = false)
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (isCut) clearClipboard()
        loadDirectory()
        lastError?.let { emitMoveFailed(it) }
    }
}

internal fun FilesViewModel.shareSelectedFiles() {
    val ctx = getApplication<Application>()
    val files = _selectedPaths.value.mapNotNull { it.realFile }.filter { it.isFile }
    if (files.isEmpty()) return
    val uris = try {
        ArrayList(files.map {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it)
        })
    } catch (e: Exception) {
        emitMoveFailed(e)
        return
    }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeForFile(files.first())
            putExtra(Intent.EXTRA_STREAM, uris.first())
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    }.apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
    clearSelection()
    viewModelScope.launch {
        _intents.emit(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun mimeForFile(file: File): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "*/*"

internal fun FilesViewModel.archiveSelection(archiveName: String) {
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
