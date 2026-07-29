package com.vayunmathur.games.voxels.util

import android.content.Context
import android.media.MediaPlayer

// Looping jukebox music played from assets/music/*.ogg. Best-effort; never throws into the UI.
object MusicFx {
    private var mp: MediaPlayer? = null
    private var current: String? = null

    // Toggle a track: tapping the jukebox with the same disc stops it; a different disc switches;
    // a null asset (holding no disc) stops.
    fun toggle(ctx: Context, asset: String?) {
        if (asset == null || asset == current) { stop(); return }
        stop()
        try {
            val afd = ctx.assets.openFd("music/$asset")
            val p = MediaPlayer()
            p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            p.isLooping = true
            p.setOnPreparedListener { it.start() }
            p.setOnErrorListener { _, _, _ -> stop(); true }
            p.prepareAsync()
            mp = p
            current = asset
        } catch (_: Throwable) { stop() }
    }

    fun stop() {
        try { mp?.let { if (it.isPlaying) it.stop(); it.release() } } catch (_: Throwable) {}
        mp = null
        current = null
    }
}
