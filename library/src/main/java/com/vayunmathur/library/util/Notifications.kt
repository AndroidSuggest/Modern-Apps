package com.vayunmathur.library.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService

/**
 * Notification channel registration.
 *
 * Ten apps did this by hand and most wrapped it in
 * `if (nm.getNotificationChannel(id) == null)`. That guard looks like an
 * optimisation but is a bug: `createNotificationChannel` is idempotent and is
 * the call that *updates* a channel's name and description, so skipping it
 * means the channel keeps whatever name it was created with. Change the system
 * language and the app's notification settings stay in the old one, forever.
 *
 * What the guard is presumably trying to avoid - clobbering the user's own
 * choices - doesn't happen anyway: once a channel exists the system ignores
 * any attempt to change its importance or its sound/vibration settings, and
 * only the name and description are refreshed.
 *
 * So this always calls through.
 */
fun Context.ensureNotificationChannel(
    id: String,
    name: String,
    importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
    description: String? = null,
    configure: NotificationChannel.() -> Unit = {},
) {
    val manager = getSystemService<NotificationManager>() ?: return
    val channel = NotificationChannel(id, name, importance).apply {
        if (description != null) this.description = description
        configure()
    }
    // Idempotent: creates on first call, refreshes name/description after.
    manager.createNotificationChannel(channel)
}

/**
 * Remove a channel the app no longer uses.
 *
 * Worth doing when a feature is retired - an orphaned channel lingers in the
 * user's notification settings with no way for them to tell what it is for.
 */
fun Context.deleteNotificationChannel(id: String) {
    getSystemService<NotificationManager>()?.deleteNotificationChannel(id)
}
