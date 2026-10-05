package com.vayunmathur.files.platform

import android.app.Application
import android.os.StatFs
import androidx.core.content.edit
import androidx.lifecycle.viewModelScope
import com.vayunmathur.files.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Home-screen state (storage meter, recents, bookmarks, categories) behind [FilesViewModel],
 * as a layer of the chain (see FilesCoreModel) so the ViewModel stays under the function cap.
 */
abstract class FilesHomeModel(application: Application) :
    FilesShareModel(application),
    HomeActions {

    internal val storageState = MutableStateFlow<StorageInfo?>(null)
    val storage: StateFlow<StorageInfo?> = storageState.asStateFlow()

    internal val recentsState = MutableStateFlow<List<FileBrowserItem>>(emptyList())
    val recents: StateFlow<List<FileBrowserItem>> = recentsState.asStateFlow()

    internal val bookmarksState = MutableStateFlow(loadBookmarks())
    val bookmarks: StateFlow<List<FileBrowserItem>> = bookmarksState.asStateFlow()

    override fun loadHome() {
        viewModelScope.launch(Dispatchers.IO) {
            storageState.value = readStorage()
            bookmarksState.value = loadBookmarks()
            recentsState.value = queryRecentItems(getApplication())
        }
    }

    override fun goHome() {
        clearCategoryMode()
        clearSelection()
        trashSelectionState.value = emptySet()
        pendingPermanentDeleteState.value = null
        cancelDirectoryObserver()
        loadHome()
    }

    override fun openInternalStorage() {
        categoryTitleState.value = null
        navigateTo(rootDirectory)
    }

    override fun openBookmark(path: File) {
        categoryTitleState.value = null
        navigateTo(path)
    }

    override fun openCategory(category: FileCategory) {
        if (category == FileCategory.DOWNLOADS) {
            val downloads = File(rootDirectory, "Download")
            openBookmark(if (downloads.isDirectory) downloads else rootDirectory)
            return
        }
        zipPathState.value = null
        val downloadsLabel = getApplication<Application>().getString(categoryLabel(category))
        categoryTitleState.value = downloadsLabel
        clearSelection()
        cancelDirectoryObserver()
        viewModelScope.launch(Dispatchers.IO) {
            entriesState.value = emptyList<FileBrowserItem>() to sortItemsList(
                queryCategoryItems(getApplication(), category),
                sortByState.value,
                sortAscendingState.value
            )
        }
    }

    override fun addBookmark(item: FileBrowserItem) {
        val path = item.realFile ?: return
        val set = (prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).toMutableSet()
        set.add(path.absolutePath)
        prefs.edit { putStringSet("bookmarks", set) }
        bookmarksState.value = loadBookmarks()
        emit(getApplication<Application>().getString(R.string.bookmark_added))
    }

    override fun removeBookmark(path: File) {
        val set = (prefs.getStringSet("bookmarks", emptySet()) ?: emptySet()).toMutableSet()
        set.remove(path.absolutePath)
        prefs.edit { putStringSet("bookmarks", set) }
        bookmarksState.value = loadBookmarks()
    }

    /** Clears category/zip state before a home-screen reload. */
    private fun clearCategoryMode() {
        categoryTitleState.value = null
        zipPathState.value = null
        zipInternalPathState.value = ""
    }

    private fun loadBookmarks(): List<FileBrowserItem> = loadBookmarkItems(prefs)

    private fun readStorage(): StorageInfo = try {
        val stat = StatFs(rootDirectory.absolutePath)
        StorageInfo(totalBytes = stat.totalBytes, freeBytes = stat.availableBytes)
    } catch (_: IllegalArgumentException) {
        StorageInfo(0, 0)
    } catch (_: SecurityException) {
        StorageInfo(0, 0)
    }
}

/** Label for a home-screen category shortcut. */
internal fun categoryLabel(category: FileCategory): Int = when (category) {
    FileCategory.IMAGES -> R.string.cat_images
    FileCategory.VIDEOS -> R.string.cat_videos
    FileCategory.AUDIO -> R.string.cat_audio
    FileCategory.DOCUMENTS -> R.string.cat_documents
    FileCategory.DOWNLOADS -> R.string.cat_downloads
}
