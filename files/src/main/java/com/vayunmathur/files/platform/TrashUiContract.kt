package com.vayunmathur.files.platform

/**
 * The UI contract for the system-trash screen.
 *
 * Mirrors [FilesUiState]: the screen takes this state plus the shared [FilesActions] rather
 * than the ViewModel itself, so it can be rendered by a `@Preview`.
 */
data class TrashUiState(
    /** Items currently in the system trash, newest first. */
    val items: List<FileBrowserItem> = emptyList(),
    val selectedItems: Set<FileBrowserItem> = emptySet(),
)
