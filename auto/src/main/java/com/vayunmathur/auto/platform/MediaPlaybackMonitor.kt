package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/**
 * Watches the on-device media session and publishes snapshots.
 *
 * Binds a [MediaController] to MA Music's `PlaybackService` (or, if that is
 * not installed, the first declared `MediaBrowserService`) and forwards every
 * metadata / playback-state change as a [NowPlayingInfo] snapshot. The
 * controller is read-only here except for [toggle], [seekToPrevious] and
 * [seekToNext]: playback itself stays in the music app, and the GAL 11/12
 * media channels stay unspoken -- this is the Phase 4 replacement for them.
 *
 * All session work hops to the main thread; [onUpdate] may be invoked from any
 * thread, so sinks must be thread-safe (a `StateFlow` set, a main-posted view
 * update) rather than doing work inline.
 */
class MediaPlaybackMonitor(
    private val context: Context,
    private val onUpdate: (NowPlayingInfo) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(metadata: MediaMetadata) = publish()
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
        override fun onPlaybackStateChanged(playbackState: Int) = publish()
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = publish()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            publish()
    }

    /**
     * Binds to the media session. Safe from any thread; the bind hops to the
     * main thread because session binding is a lifecycle operation. With no
     * browser service on the phone this logs and leaves now-playing empty.
     */
    fun start() {
        mainHandler.post {
            val component = resolveMediaSession() ?: run {
                Log.w(TAG, "no media browser service; now-playing stays empty")
                return@post
            }
            val future =
                MediaController.Builder(context, SessionToken(context, component)).buildAsync()
            future.addListener(
                {
                    val connected = runCatching { future.get() }.getOrNull()
                    mainHandler.post { onConnected(connected) }
                },
                { it.run() },
            )
        }
    }

    /** Toggles phone playback from the car card's tap target. Safe from any thread. */
    fun toggle() {
        mainHandler.post {
            val active = controller ?: return@post
            if (active.isPlaying) active.pause() else active.play()
        }
    }

    /**
     * Skips to the previous queue item from the car card's prev button.
     * Mirrors [toggle]: posted to main, no-op with no controller. Safe from
     * any thread.
     */
    fun seekToPrevious() {
        mainHandler.post {
            val active = controller ?: return@post
            if (active.hasPreviousMediaItem()) active.seekToPrevious()
        }
    }

    /**
     * Skips to the next queue item from the car card's next button.
     * Mirrors [toggle]: posted to main, no-op with no controller. Safe from
     * any thread.
     */
    fun seekToNext() {
        mainHandler.post {
            val active = controller ?: return@post
            if (active.hasNextMediaItem()) active.seekToNext()
        }
    }

    /** Drops the controller. Safe from any thread. */
    fun stop() {
        mainHandler.post {
            controller?.removeListener(listener)
            runCatching { controller?.release() }
            controller = null
        }
    }

    private fun onConnected(connected: MediaController?) {
        if (connected == null) {
            Log.w(TAG, "media controller connect failed; now-playing stays empty")
            return
        }
        connected.addListener(listener)
        controller = connected
        publish()
    }

    private fun publish() {
        val active = controller ?: return
        val metadata = active.mediaMetadata
        // Source badge: the session owner's app icon, matching gearhead's
        // per-source badge. Null when the package has no icon to load.
        // (`MediaController` exposes the token, not the package directly;
        // the token is the service we bound in `start`.)
        val sourceIcon = runCatching {
            val token = active.connectedToken ?: return@runCatching null
            context.packageManager.getApplicationIcon(token.packageName)
        }.getOrNull()
        onUpdate(
            NowPlayingInfo(
                title = metadata.title?.toString(),
                artist = metadata.artist?.toString(),
                playing = active.isPlaying,
                positionMs = active.currentPosition.coerceAtLeast(0),
                durationMs = active.duration.takeIf { it >= 0 },
                artworkData = metadata.artworkData,
                displayIcon = sourceIcon,
                hasPrevious = active.hasPreviousMediaItem(),
                hasNext = active.hasNextMediaItem(),
            ),
        )
    }

    /**
     * Resolves the session to watch, preferring MA Music.
     *
     * The class name is a string on purpose: the auto module must not take a
     * compile dependency on the music app module, and the manifest already
     * queries this exact package plus the generic browse action, so both
     * lookups below stay visible to PackageManager.
     */
    private fun resolveMediaSession(): ComponentName? {
        val pm = context.packageManager
        val direct = ComponentName(MUSIC_PACKAGE, MUSIC_SERVICE_CLASS)
        if (runCatching { pm.getServiceInfo(direct, 0) }.isSuccess) return direct
        // Fallback: anything declaring a browsable media surface.
        val browse = Intent("android.media.browse.MediaBrowserService")
        val info = pm.queryIntentServices(browse, 0).firstOrNull()?.serviceInfo ?: return null
        return ComponentName(info.packageName, info.name)
    }

    private companion object {
        const val TAG = "MaAuto.Media"
        const val MUSIC_PACKAGE = "com.vayunmathur.music"
        const val MUSIC_SERVICE_CLASS = "com.vayunmathur.music.service.PlaybackService"
    }
}
