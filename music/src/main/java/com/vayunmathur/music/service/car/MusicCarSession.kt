package com.vayunmathur.music.service.car

import android.content.ComponentName
import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.music.service.MusicLibraryTree
import com.vayunmathur.music.service.PlaybackService

/**
 * The Car App Library [Session] for music.
 *
 * Owns one [MusicLibraryTree] (the same in-memory browse snapshot the
 * `MediaLibraryService` serves to Android Auto) and one `MediaController`
 * on the [PlaybackService] session. Screens read the tree for browse and
 * drive transport through the controller — the player, queue, and library
 * stay single-sourced in the music app.
 */
class MusicCarSession : Session() {

    private lateinit var state: MusicCarState

    override fun onCreateScreen(intent: Intent): Screen {
        val tree = MusicLibraryTree(carContext)
        state = MusicCarState(tree)
        tree.onLibraryChanged = { state.notifyChanged() }
        val token = SessionToken(
            carContext,
            ComponentName(carContext, PlaybackService::class.java),
        )
        val future = MediaController.Builder(carContext, token).buildAsync()
        future.addListener(
            {
                state.controller = runCatching { future.get() }.getOrNull()
                state.notifyChanged()
            },
            MoreExecutors.directExecutor(),
        )

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                runCatching { future.get() }.getOrNull()?.release()
                state.controller = null
                tree.release()
            }
        })

        return MusicCarBrowseScreen(carContext, MusicLibraryTree.ROOT, state)
    }

    // API 9: the host sends ACTION_SHOW_MEDIA_PLAYBACK to route the user to
    // the playback view (persistent entrypoint / mini-controller). Push the
    // playback screen on top of whatever is showing.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == androidx.car.app.media.MediaConstants.ACTION_SHOW_MEDIA_PLAYBACK &&
            ::state.isInitialized
        ) {
            runCatching {
                carContext.getCarService(
                    androidx.car.app.ScreenManager::class.java
                ).push(MusicCarPlaybackScreen(carContext, state))
            }
        }
    }
}
