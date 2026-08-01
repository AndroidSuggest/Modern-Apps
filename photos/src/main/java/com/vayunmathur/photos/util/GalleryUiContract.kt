package com.vayunmathur.photos.util

import com.vayunmathur.photos.data.Photo

/**
 * The UI contract between [GalleryViewModel] and the two screens the store listing is
 * captured from.
 *
 * Those screens take a state value plus an actions interface rather than the ViewModel
 * itself, so they can be rendered by a `@Preview` — see `src/screenshotTest`. It lives in
 * `util` rather than `ui` so the dependency runs one way: `ui` depends on `util`, and the
 * ViewModel implements the interface.
 *
 * Only the gallery and people grids are split this way. The rest of the app (the editor,
 * the map, the viewer) still takes the ViewModel directly — none of them can render
 * without a real image anyway.
 */

/** Everything the gallery grid draws. */
data class GalleryUiState(
    val photos: List<Photo> = emptyList(),
    val selectedIds: Set<Long> = emptySet(),
    val isRefreshing: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Photo> = emptyList(),
    val searchAiState: SearchAiState = SearchAiState.READY,
    val ocrCount: Int = 0,
    val ocrTargetCount: Int = 0,
    val clipCount: Int = 0,
    val clipTargetCount: Int = 0,
)

/** Everything the people grid draws. */
data class PeopleUiState(
    val people: List<PersonCluster> = emptyList(),
    /** False when the MobileFaceNet model is absent; the screen then explains why it is empty. */
    val modelsAvailable: Boolean = true,
    val indexing: Boolean = false,
    val faceScannedCount: Int = 0,
    val faceTargetCount: Int = 0,
)

/**
 * Gallery callbacks. Every method has a no-op default so a preview can render the screen
 * without supplying behaviour — [Noop] is the whole implementation a preview needs.
 *
 * [GalleryViewModel] implements the first four directly. The last two cannot live on a
 * ViewModel: both need an activity-result launcher (and, for the vault, a biometric
 * prompt), so the binder supplies them.
 */
interface GalleryActions {
    fun setSearchQuery(query: String) {}
    fun toggleSelection(id: Long) {}
    fun clearSelection() {}
    fun runSync() {}

    /** Biometric-unlock the vault, then move the current selection into it. */
    fun moveSelectionToSecureFolder() {}

    /** Ask MediaStore to trash the current selection. */
    fun trashSelection() {}

    companion object {
        val Noop: GalleryActions = object : GalleryActions {}
    }
}
