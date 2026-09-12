package com.vayunmathur.auto.platform

import android.app.role.RoleManager
import android.content.Context
import com.vayunmathur.auto.protocol.MaosRole

/**
 * Reads whether this package holds the projection role.
 *
 * On MAOS builds the `roles.xml` patch pins `SYSTEM_AUTOMOTIVE_PROJECTION` to
 * this package; on stock phones (including the DHU loopback path) the role is
 * simply absent and the session runs degraded — private display, no trusted
 * launch, no input injection. A missing service or any failure reads as
 * not-held rather than crashing the caller.
 */
object MaosRoleStatus {

    fun isProjectionRoleHeld(context: Context): Boolean = runCatching {
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(MaosRole.PROJECTION_ROLE) == true
    }.getOrDefault(false)
}
