package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.auto.service.ProjectionService

/**
 * Re-starts projection after a reboot so the phone listens again without being opened.
 *
 * The session is bound to the cable or the wireless link, not to an activity, so
 * boot persistence means the listener — not a UI — comes back. Refreshes the role
 * read while at it: an OTA could have changed the holder without the app running.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        TransportState.publishRole(MaosRoleStatus.isProjectionRoleHeld(context))
        ProjectionService.start(context)
    }
}
