package com.vayunmathur.auto.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vayunmathur.auto.service.ProjectionService

/**
 * Re-starts projection after a reboot (or an app update replacing the
 * package) so the phone listens again without being opened.
 *
 * The session is bound to the cable or the wireless link, not to an
 * activity, so boot/update persistence means the listener -- not a UI --
 * comes back. Refreshes the role read while at it: an OTA could have
 * changed the holder without the app running.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // BOOT_COMPLETED restarts the listener; MY_PACKAGE_REPLACED does the
        // same after an update (the process died with the old APK and nothing
        // re-arms it otherwise). Both republish the role: either event could
        // have changed the holder.
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        TransportState.publishRole(MaosRoleStatus.isProjectionRoleHeld(context))
        ProjectionService.start(context)
    }
}
