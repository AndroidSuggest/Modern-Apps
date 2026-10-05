package com.vayunmathur.files.platform

import android.app.Application
import android.os.Environment
import androidx.core.content.edit
import com.vayunmathur.files.R
import java.io.File

/**
 * Selection, listing, sort/view/search, and structural edits behind [FilesViewModel].
 *
 * Second layer of the chain (see FilesCoreModel): navigation, selection, sort, and file
 * mutations. Sort persistence stays next to the comparators it feeds.
 */
abstract class FilesBrowseModel(application: Application) :
    FilesCoreModel(application),
    SelectionActions,
    NavigationActions,
    BrowseViewActions {

    // ---- Selection (only valid in real FS mode) ----
    override fun clearSelection() {
        if (selectedPathsState.value.isNotEmpty()) selectedPathsState.value = emptySet()
    }

    override fun addToSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        selectedPathsState.value = selectedPathsState.value + item
    }

    override fun toggleSelection(item: FileBrowserItem) {
        if (isZipMode()) return
        val current = selectedPathsState.value
        selectedPathsState.value = if (current.any { it.key == item.key }) {
            current.filterNot { it.key == item.key }.toSet()
        } else {
            current + item
        }
    }

    override fun selectAll() {
        if (isZipMode()) return
        val (dirs, files) = entriesState.value
        selectedPathsState.value = (dirs + files).toSet()
    }

    // ---- Sort, view mode, search ----
    override fun setSortBy(sort: SortBy) {
        if (sortByState.value == sort) return
        sortByState.value = sort
        prefs.edit { putString("sort_by", sort.name) }
        loadDirectory()
    }

    override fun toggleSortDirection() {
        setSortAscending(!sortAscendingState.value)
    }

    override fun setSortAscending(ascending: Boolean) {
        if (sortAscendingState.value == ascending) return
        sortAscendingState.value = ascending
        prefs.edit { putBoolean("sort_ascending", ascending) }
        loadDirectory()
    }

    override fun toggleViewMode() {
        val next = if (viewModeState.value == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        viewModeState.value = next
        prefs.edit { putString("view_mode", next.name) }
    }

    override fun setSearchActive(active: Boolean) {
        isSearchActiveState.value = active
        if (!active) searchQueryState.value = ""
    }

    override fun setSearchQuery(query: String) {
        searchQueryState.value = query
    }

    override fun toggleHidden() {
        val next = !showHiddenState.value
        showHiddenState.value = next
        prefs.edit { putBoolean("show_hidden", next) }
        loadDirectory()
    }

    /**
     * Apply the sensible default sort when *entering* a folder: the Downloads folder shows
     * newest-first (by date), which is what people usually want there; every other folder
     * uses the persisted global sort. This only sets the in-memory sort, so an explicit
     * choice via the menu (which persists) still wins while you stay in the folder.
     */
    private fun applyDirDefaults(path: File) {
        if (path.absolutePath == downloadsDir.absolutePath) {
            sortByState.value = SortBy.DATE
            sortAscendingState.value = false
        } else {
            sortByState.value = runCatching {
                SortBy.valueOf(prefs.getString("sort_by", null) ?: "NAME")
            }.getOrDefault(SortBy.NAME)
            sortAscendingState.value = prefs.getBoolean("sort_ascending", true)
        }
    }

    fun refreshPermissions() {
        val granted = Environment.isExternalStorageManager()
        if (isFilesGranted.value != granted) {
            setFilesGranted(granted)
            if (granted) {
                loadDirectory()
                loadHome()
            }
        }
    }

    fun setNotificationsPrompted() {
        prefs.edit { putBoolean("has_prompted_notifications", true) }
        setNotificationsPromptedState()
    }

    open fun loadHome() {}

    // ---- Navigation ----
    override fun navigateTo(path: File) {
        if (isZipMode()) {
            zipPathState.value = null
            zipInternalPathState.value = ""
        }
        categoryTitleState.value = null
        currentDirectoryState.value = path
        applyDirDefaults(path)
        clearSelection()
        trashSelectionState.value = emptySet()
        pendingPermanentDeleteState.value = null
        loadDirectory()
        restartObserver()
    }

    override fun navigateIntoZipDir(dirName: String) {
        val current = zipInternalPathState.value
        val newPath = if (current.isEmpty()) dirName else "$current/$dirName"
        zipInternalPathState.value = newPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipInternalPath(fullInternalPath: String) {
        zipInternalPathState.value = fullInternalPath
        clearSelection()
        loadDirectory()
    }

    override fun navigateToZipParentRealFolder(target: File) {
        // breadcrumb click on real-FS part while in zip mode → exit zip
        zipPathState.value = null
        zipInternalPathState.value = ""
        currentDirectoryState.value = target
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    /**
     * Back within a screen, not between screens.
     *
     * Directory, archive and category traversal used to live here, walking the location state by
     * hand. That is the navigation back stack's job now - one entry per level - and doing it in both
     * places meant the predictive-back preview showed one destination while this produced another.
     * What is left is the part that is genuinely not navigation: dismissing a selection.
     */
    override fun handleBack(): Boolean {
        if (selectedPathsState.value.isNotEmpty()) {
            clearSelection()
            return true
        }
        return false
    }

    /** Loads a location inside an archive. Called by the navigation layer for a `Route.Zip`. */
    override fun showZip(zip: File, internalPath: String) {
        categoryTitleState.value = null
        zipPathState.value = zip
        zipInternalPathState.value = internalPath
        currentDirectoryState.value = File("/") // placeholder not used in zip mode
        clearSelection()
        loadDirectory()
        restartObserver()
    }

    /** Reloads the system-trash listing. Called by the navigation layer for a `Route.Trash`. */
    override fun showTrash() {
        categoryTitleState.value = null
        zipPathState.value = null
        zipInternalPathState.value = ""
        clearSelection()
        cancelDirectoryObserver()
        loadTrash()
    }
}
