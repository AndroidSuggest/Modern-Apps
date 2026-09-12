package com.vayunmathur.auto.protocol

/**
 * The MAOS system-role integration, as pure policy + constants.
 *
 * Gearhead holds `android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION` (FINDINGS.md §1):
 * exclusive, static, systemOnly, no user picker — a build-time assignment pinned via
 * a `roles.xml` `defaultHolders="config_systemAutomotiveProjection"` entry plus a
 * privapp-permissions allowlist for the ~58 permissions the role itself does not
 * grant (`MANAGE_USB`, `MODIFY_AUDIO_ROUTING`, ...). Both halves are boot-fatal if
 * wrong, so they land one at a time on MAOS builds; on stock phones (including the
 * DHU loopback path) the role is simply absent and everything keeps working
 * degraded — private display, no trusted launch, no input injection.
 *
 * This file owns the strings both sides must agree on. The Android side
 * (`MaosRole`) only reads the platform state — `RoleManager.isRoleHeld()` — and
 * reports it here; the boot receiver re-starts the projection service after reboot.
 */
object MaosRole {

    /** The role MA Auto takes; MA Cast moves to `COMPANION_DEVICE_APP_STREAMING`. */
    const val PROJECTION_ROLE = "android.app.role.SYSTEM_AUTOMOTIVE_PROJECTION"

    /** The role config name the `roles.xml` patch pins via `defaultHolders`. */
    const val PROJECTION_ROLE_CONFIG = "config_systemAutomotiveProjection"

    /** Package name of the MAOS build this role work targets. */
    const val PACKAGE_NAME = "com.vayunmathur.auto"

    /**
     * What the role unlocks, so the UI can explain degraded state on stock phones.
     * Each maps to the permission set the role carries (FINDINGS.md §1).
     */
    enum class Capability {
        /** Launch other apps' activities onto the car display (`ADD_TRUSTED_DISPLAY`). */
        TRUSTED_DISPLAY,

        /** Inject head-unit input into the car display (`CREATE_VIRTUAL_DEVICE`). */
        INPUT_INJECTION,

        /** Read phone state / SMS / notifications for car templates. */
        PROJECTION_DATA,
    }

    /**
     * @param holdsRole whether the platform reports this package as the role holder.
     * @return the capabilities available in this install.
     */
    fun capabilities(holdsRole: Boolean): Set<Capability> =
        if (holdsRole) Capability.entries.toSet() else emptySet()
}
