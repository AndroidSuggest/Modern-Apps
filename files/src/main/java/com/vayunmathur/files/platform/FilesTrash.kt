package com.vayunmathur.files.platform

import android.app.Application
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * System-trash (MediaStore) helpers behind [FilesViewModel]'s trash-first delete.
 *
 * This is the platform trash, not an app-owned `.trash` folder: trashed items live in the
 * shared MediaStore trash with 30-day auto-delete, restore, and per-batch user-consent dialogs.
 * Only MediaStore-indexed files resolve to trashable Uris; directories and unindexed files
 * cannot be trashed and fall back to a permanent-delete confirmation.
 *
 * Split out like `FilesClipboard.kt` so FilesViewModel.kt stays under the FileLength limit.
 */

/** MediaStore Uris for the subset of [files] that the platform can trash. */
internal fun resolveTrashableUris(context: Context, files: List<File>): Map<File, Uri> {
    if (files.isEmpty()) return emptyMap()
    val collection = MediaStore.Files.getContentUri("external")
    val out = mutableMapOf<File, Uri>()
    for (file in files) {
        if (file.isDirectory) continue
        try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA}=?",
                arrayOf(file.absolutePath),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    out[file] = ContentUris.withAppendedId(collection, c.getLong(0))
                }
            }
        } catch (_: Exception) {
        }
    }
    return out
}

/** All items currently in the system trash, newest first. Files only — the platform trash. */
internal fun queryTrashedItems(context: Context, limit: Int = 2000): List<FileBrowserItem> {
    val collection = MediaStore.Files.getContentUri("external")
    val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.DATA,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
    )
    val queryArgs = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
    }
    val out = mutableListOf<FileBrowserItem>()
    try {
        context.contentResolver.query(collection, projection, queryArgs, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val dataIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            while (c.moveToNext()) {
                val data = c.getString(dataIdx) ?: continue
                val file = File(data)
                val displayName = c.getString(nameIdx) ?: file.name
                out.add(
                    FileBrowserItem(
                        name = displayName,
                        isDirectory = false,
                        size = c.getLong(sizeIdx).takeIf { !c.isNull(sizeIdx) },
                        realFile = file,
                        zipInnerPath = null,
                        key = "trash:${c.getLong(idIdx)}",
                        lastModified = c.getLong(dateIdx).takeIf { !c.isNull(dateIdx) }?.times(1000) ?: 0L,
                        mediaUri = ContentUris.withAppendedId(collection, c.getLong(idIdx)),
                    ),
                )
            }
        }
    } catch (_: Exception) {
    }
    return out
}

/** Consent-gated request to move [uris] to (or restore from) the system trash. */
internal fun trashPendingIntent(context: Context, uris: List<Uri>, trash: Boolean): PendingIntent =
    MediaStore.createTrashRequest(context.contentResolver, uris, trash)

/** Consent-gated request to permanently delete [uris] from the system trash. */
internal fun deleteForeverPendingIntent(context: Context, uris: List<Uri>): PendingIntent =
    MediaStore.createDeleteRequest(context.contentResolver, uris)

internal fun FilesViewModel.trashSelection() {
    val selection = _selectedPaths.value.toList()
    if (selection.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val trashable = resolveTrashableUris(ctx, selection.mapNotNull { it.realFile })
        val untrashable = selection.filter { it.realFile !in trashable }
        if (trashable.isNotEmpty()) {
            clearSelection()
            loadDirectory()
            _pendingTrashMessage = ctx.getString(R.string.moved_to_trash, trashable.size)
            _trashConsentRequests.emit(trashPendingIntent(ctx, trashable.values.toList(), true))
        }
        if (untrashable.isNotEmpty()) {
            _pendingPermanentDelete.value = untrashable
        }
    }
}

/**
 * Permanently deletes the items the system trash cannot take. Shown only after the user
 * confirms, and only reachable for folders / files with no MediaStore entry.
 */
internal fun FilesViewModel.confirmPendingPermanentDelete() {
    val pending = _pendingPermanentDelete.value ?: return
    _pendingPermanentDelete.value = null
    viewModelScope.launch(Dispatchers.IO) {
        pending.mapNotNull { it.realFile }.forEach { it.deleteRecursively() }
        clearSelection()
        loadDirectory()
    }
}

/** Reloads the system-trash listing. Called by the navigation layer for a `Route.Trash`. */
internal fun FilesViewModel.loadTrash() {
    _trashSelection.value = emptySet()
    viewModelScope.launch(Dispatchers.IO) {
        _trashedItems.value = queryTrashedItems(getApplication())
    }
}

internal fun FilesViewModel.restoreTrashed() {
    val selection = _trashSelection.value.toList()
    if (selection.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val uris = selection.mapNotNull { it.mediaUri ?: it.realFile?.let { f -> resolveTrashableUris(ctx, listOf(f))[f] } }
        if (uris.isEmpty()) return@launch
        _trashSelection.value = emptySet()
        _pendingTrashMessage = ctx.getString(R.string.restored_n, uris.size)
        _trashConsentRequests.emit(trashPendingIntent(ctx, uris, false))
    }
}

internal fun FilesViewModel.deleteTrashedForever() {
    val selection = _trashSelection.value.toList()
    if (selection.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val uris = selection.mapNotNull { it.mediaUri ?: it.realFile?.let { f -> resolveTrashableUris(ctx, listOf(f))[f] } }
        if (uris.isEmpty()) return@launch
        _trashSelection.value = emptySet()
        _pendingTrashMessage = ctx.getString(R.string.deleted_forever_n, uris.size)
        _trashConsentRequests.emit(deleteForeverPendingIntent(ctx, uris))
    }
}

internal fun FilesViewModel.emptySystemTrash() {
    viewModelScope.launch(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val items = queryTrashedItems(ctx)
        if (items.isEmpty()) return@launch
        val uris = items.mapNotNull { it.mediaUri }
        if (uris.isEmpty()) return@launch
        _trashSelection.value = emptySet()
        _pendingTrashMessage = ctx.getString(R.string.deleted_forever_n, uris.size)
        _trashConsentRequests.emit(deleteForeverPendingIntent(ctx, uris))
    }
}

/** Called after a trash/restore/delete consent dialog resolves; refreshes both listings. */
internal fun FilesViewModel.onTrashConsentResolved(granted: Boolean) {
    loadDirectory()
    viewModelScope.launch(Dispatchers.IO) {
        _trashedItems.value = queryTrashedItems(getApplication())
    }
    if (granted) {
        _pendingTrashMessage?.let { emit(it) }
    }
    _pendingTrashMessage = null
}
