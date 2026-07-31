package com.vayunmathur.youpipe.util.sabr

import android.content.Context
import android.os.SystemClock

/**
 * Tracks the SABR server-wait state so the pump and session store can coordinate backoff.
 *
 * The upstream PipePipe implementation also published a status notification (via MainActivity
 * and app string/drawable resources). That UI is intentionally omitted in the youpipe port; only
 * the state-tracking API used by the SABR playback code is preserved.
 */
class SabrBackoffCoordinator private constructor() {

    private var owner: Any? = null
    private var deadlineElapsedMs = NO_DEADLINE
    private var playbackBlockedBeforeBuffering = false

    @Synchronized
    fun begin(context: Context, sourceOwner: Any, deadlineMs: Long) {
        begin(context, sourceOwner, deadlineMs, false)
    }

    @Synchronized
    fun beginPlaybackWait(context: Context, sourceOwner: Any, deadlineMs: Long) {
        begin(context, sourceOwner, deadlineMs, true)
    }

    @Synchronized
    private fun begin(
        context: Context,
        sourceOwner: Any,
        deadlineMs: Long,
        blocksPlaybackBeforeBuffering: Boolean
    ) {
        if (deadlineMs <= SystemClock.elapsedRealtime()) {
            clear(context, sourceOwner)
            return
        }
        if (owner !== sourceOwner) {
            owner = sourceOwner
            deadlineElapsedMs = deadlineMs
            playbackBlockedBeforeBuffering = blocksPlaybackBeforeBuffering
        } else {
            deadlineElapsedMs = maxOf(deadlineElapsedMs, deadlineMs)
            playbackBlockedBeforeBuffering =
                playbackBlockedBeforeBuffering || blocksPlaybackBeforeBuffering
        }
    }

    @Synchronized
    fun clear(context: Context, sourceOwner: Any) {
        if (owner !== sourceOwner) {
            return
        }
        owner = null
        deadlineElapsedMs = NO_DEADLINE
        playbackBlockedBeforeBuffering = false
    }

    @Synchronized
    fun setPlayerBuffering(context: Context, buffering: Boolean) {
        // Notification UI omitted in the youpipe port; nothing to update.
    }

    @Synchronized
    fun getRemainingMs(): Long =
        if (deadlineElapsedMs == NO_DEADLINE) {
            0L
        } else {
            maxOf(0L, deadlineElapsedMs - SystemClock.elapsedRealtime())
        }

    @Synchronized
    fun isWaiting(): Boolean = getRemainingMs() > 0L

    companion object {
        const val NO_DEADLINE = -1L

        private val INSTANCE = SabrBackoffCoordinator()

        @JvmStatic
        fun getInstance(): SabrBackoffCoordinator = INSTANCE
    }
}
