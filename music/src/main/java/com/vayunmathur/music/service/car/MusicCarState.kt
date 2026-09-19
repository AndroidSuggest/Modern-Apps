package com.vayunmathur.music.service.car

import androidx.media3.session.MediaController
import com.vayunmathur.music.service.MusicLibraryTree

/**
 * Shared car-session state, passed into every screen.
 *
 * The tree is the same in-memory browse snapshot the `MediaLibraryService`
 * serves; the controller drives the shared `PlaybackService` session, so
 * car transport and phone transport converge on one player. Both fill in
 * asynchronously (Room snapshot, controller bind), so screens register for
 * [notifyChanged] and the session fires it as each source lands — a screen
 * rendered early refreshes instead of staying empty.
 */
class MusicCarState(
    val tree: MusicLibraryTree,
) {
    @Volatile var controller: MediaController? = null

    private val observers = mutableSetOf<() -> Unit>()

    @Synchronized
    fun observe(onChange: () -> Unit) {
        observers += onChange
    }

    @Synchronized
    fun notifyChanged() {
        val current = observers.toList()
        for (observer in current) {
            runCatching { observer() }
        }
    }
}
