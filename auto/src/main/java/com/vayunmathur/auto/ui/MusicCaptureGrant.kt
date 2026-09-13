package com.vayunmathur.auto.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager

/**
 * Holds the media-projection grant for music capture.
 *
 * The consent `Intent` result belongs to an activity (the card above), but
 * the capture runs in the projection service; this holder bridges the two.
 * The stored result code + data recreate the [MediaProjection] token on
 * demand, process-wide. Fail-closed: no stored grant means no capture, and
 * revoking in settings takes effect on the next token recreation.
 */
object MusicCaptureGrant {
    @Volatile
    private var resultCode: Int = Activity.RESULT_CANCELED

    @Volatile
    private var resultData: Intent? = null

    /** Whether a grant is stored. The in-app consent switch is checked separately. */
    fun hasGrant(context: Context): Boolean = resultData != null

    /** Stores the consent-activity result. Called from the capture card. */
    fun storeResult(context: Context, code: Int, data: Intent) {
        resultCode = code
        resultData = data
    }

    /** Clears the stored grant (consent revoked in-app). */
    fun clear() {
        resultCode = Activity.RESULT_CANCELED
        resultData = null
    }

    /** Recreates the projection token, or null with no stored grant. */
    fun projection(context: Context): MediaProjection? {
        val data = resultData ?: return null
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return null
        return runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
    }
}
