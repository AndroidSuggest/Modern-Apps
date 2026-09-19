package com.vayunmathur.auto.data

import android.content.Context
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the car launcher's pinned dock: an ordered list of car-app
 * component ids (see `DiscoveredApp.id`), max 5.
 *
 * A single ordered delimited string, never a StringSet: sets lose the dock
 * order. Mirrors the `MessagingPrefs`/`MusicCapturePrefs` shape (device-level,
 * survives disconnects and restarts, fail-closed to empty). Stale ids (apps
 * no longer installed) are intersected out on load against the discovered set.
 */
object PinnedAppsPrefs {
    /** Max pinned apps in the car dock. Enforced on write. */
    const val MAX_PINNED = 5

    /** Ordered pinned ids; empty until the user pins something. */
    fun pinnedFlow(context: Context): Flow<List<String>> =
        DataStoreUtils.getInstance(context).stringFlow(KEY_PINNED).map { it.toIdList() }

    /** Synchronous snapshot for the car display's pin query. */
    fun pinnedNow(context: Context): List<String> =
        DataStoreUtils.getInstance(context).getString(KEY_PINNED)?.toIdList().orEmpty()

    /** Replaces the order wholesale, truncating to [MAX_PINNED]. */
    suspend fun setPinned(context: Context, ids: List<String>) {
        DataStoreUtils.getInstance(context).setString(KEY_PINNED, ids.take(MAX_PINNED).joinToString(SEPARATOR))
    }

    /** Pins [id] at the end; no-op when full or already pinned. Returns false when rejected. */
    suspend fun pin(context: Context, id: String): Boolean {
        val current = pinnedNow(context)
        if (id in current) return true
        if (current.size >= MAX_PINNED) return false
        setPinned(context, current + id)
        return true
    }

    /** Unpins [id]; no-op when absent. */
    suspend fun unpin(context: Context, id: String) {
        val current = pinnedNow(context)
        if (id !in current) return
        setPinned(context, current - id)
    }

    /** Moves [id] to [toIndex] within the pinned order; no-op when absent. */
    suspend fun move(context: Context, id: String, toIndex: Int) {
        val current = pinnedNow(context).toMutableList()
        if (!current.remove(id)) return
        current.add(toIndex.coerceIn(0, current.size), id)
        setPinned(context, current)
    }

    private fun String.toIdList(): List<String> =
        split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }.take(MAX_PINNED)

    private const val KEY_PINNED = "car_pinned_apps"
    private const val SEPARATOR = "\n"
}
