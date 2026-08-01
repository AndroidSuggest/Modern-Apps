package com.vayunmathur.files.util

import java.io.File

/**
 * The UI contract between [FilesViewModel] and the directory browser.
 *
 * The screen takes a state value plus an actions interface rather than the ViewModel
 * itself, so it can be rendered by a `@Preview` — which is what the store listing images
 * are generated from. It lives in `util` rather than next to the composables so the
 * dependency runs one way, and [FilesViewModel] implements [FilesActions] directly.
 */

/** Everything the directory browser draws. */
data class FilesUiState(
    val rootDirectory: File,
    /**
     * Label for the root breadcrumb — the device model. Passed in because a preview has no
     * device to read it from.
     */
    val rootDisplayName: String,
    val currentDirectory: File,
    /** Non-null while browsing inside a zip; [currentDirectory] is then meaningless. */
    val zipPath: File? = null,
    val zipInternalPath: String = "",
    val directories: List<FileBrowserItem> = emptyList(),
    val files: List<FileBrowserItem> = emptyList(),
    val selectedPaths: Set<FileBrowserItem> = emptySet(),
    /** True while files shared into the app are waiting to be saved here. */
    val hasIncomingUris: Boolean = false,
)

/**
 * Browser callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 */
interface FilesActions {
    fun navigateTo(path: File) {}
    fun navigateIntoZipDir(dirName: String) {}
    fun navigateToZipInternalPath(fullInternalPath: String) {}
    fun navigateToZipParentRealFolder(target: File) {}

    /** Returns true when the back press was consumed (selection cleared, or moved up). */
    fun handleBack(): Boolean = false

    fun clearSelection() {}
    fun addToSelection(item: FileBrowserItem) {}
    fun toggleSelection(item: FileBrowserItem) {}

    fun rename(item: FileBrowserItem, newName: String) {}
    fun deleteSelection() {}
    fun moveInto(sources: List<File>, target: File) {}
    fun moveToBreadcrumb(sources: List<File>, target: File) {}

    fun openZipFile(item: FileBrowserItem) {}
    fun openFile(item: FileBrowserItem) {}
    fun archive(archiveName: String) {}
    fun saveIncomingUris() {}

    companion object {
        val Noop: FilesActions = object : FilesActions {}
    }
}
