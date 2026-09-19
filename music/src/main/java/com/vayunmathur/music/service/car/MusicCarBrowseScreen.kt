package com.vayunmathur.music.service.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import com.vayunmathur.music.service.MusicLibraryTree

/**
 * One browse level: the tree node [nodeId] rendered read-only.
 *
 * Folders push a deeper [MusicCarBrowseScreen]; playable leaves resolve
 * through [MusicLibraryTree.resolveForPlayback] and play on the shared
 * session, then open [MusicCarPlaybackScreen]. A header play action plays
 * everything listed. Long lists stay parked-safe: rows are plain text,
 * artwork loads host-side from the item URIs.
 */
class MusicCarBrowseScreen(
    carContext: CarContext,
    private val nodeId: String,
    private val state: MusicCarState,
) : Screen(carContext) {

    init {
        state.observe { invalidate() }
    }

    override fun onGetTemplate(): Template {
        val tree = state.tree
        val title = tree.item(nodeId)?.mediaMetadata?.title?.toString() ?: "Music"
        val items = tree.children(nodeId)
        val list = ItemList.Builder()
        if (nodeId != MusicLibraryTree.ROOT && items.isEmpty()) {
            list.addItem(
                Row.Builder()
                    .setTitle("Loading…")
                    .build(),
            )
        }
        for (item in items) {
            val meta = item.mediaMetadata
            val browsable = meta.isBrowsable == true
            val playable = !browsable && meta.isPlayable == true
            val row = Row.Builder()
                .setTitle(meta.title?.toString() ?: "Unknown")
            val subtitle = listOfNotNull(
                meta.artist?.toString()?.takeIf { it.isNotBlank() },
                meta.albumTitle?.toString()?.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
            subtitle?.let { row.addText(it) }
            if (browsable) {
                val count = tree.childCount(item.mediaId)
                if (count > 0) row.addText("$count items")
            }
            row.setBrowsable(browsable)
            if (browsable) {
                row.setOnClickListener {
                    screenManager.push(MusicCarBrowseScreen(carContext, item.mediaId, state))
                }
            } else if (playable) {
                row.setOnClickListener { play(item.mediaId) }
            }
            list.addItem(row.build())
        }
        val builder = ListTemplate.Builder()
            .setSingleList(list.build())
            .setTitle(title)
            .setHeaderAction(Action.BACK)
        if (items.any { it.mediaMetadata.isPlayable == true && it.mediaMetadata.isBrowsable != true }) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Play")
                    .setOnClickListener { playAll(items.map { it.mediaId }) }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun play(mediaId: String) {
        val playable = state.tree.resolveForPlayback(mediaId) ?: return
        val controller = state.controller ?: return
        controller.setMediaItem(playable)
        controller.prepare()
        controller.play()
        state.tree.markPlayed(listOf(mediaId))
        screenManager.push(MusicCarPlaybackScreen(carContext, state))
    }

    private fun playAll(mediaIds: List<String>) {
        val playables = mediaIds.mapNotNull { state.tree.resolveForPlayback(it) }
        if (playables.isEmpty()) return
        val controller = state.controller ?: return
        controller.setMediaItems(playables)
        controller.prepare()
        controller.play()
        state.tree.markPlayed(mediaIds)
        screenManager.push(MusicCarPlaybackScreen(carContext, state))
    }
}
