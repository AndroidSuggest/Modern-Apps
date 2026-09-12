package com.vayunmathur.auto.protocol

/** Where a USB accessory connection has got to. */
enum class UsbSessionState {
    /** No accessory announced itself yet. */
    DETACHED,

    /** Attached, waiting on the user's permission grant. */
    AWAITING_PERMISSION,

    /** Permission denied; stays here until detach or a re-request. */
    PERMISSION_DENIED,

    /** Granted and opened: bytes may flow into [GalConnection]. */
    CONNECTED,

    /** The accessory went away (or open failed) after having been seen. */
    DISCONNECTED,
}

/**
 * The Android Open Accessory Protocol bring-up, as a pure state machine.
 *
 * On a real car the head unit puts the phone into accessory mode itself and Android
 * delivers `USB_ACCESSORY_ATTACHED` to whoever matches the accessory filter; on the
 * DHU path there is no USB at all and this never leaves [UsbSessionState.DETACHED].
 * The Android side owns the `UsbManager` calls — request permission, open the
 * accessory, hand the file descriptor over as a [StreamTransport] — and reports the
 * outcomes here. This only tracks what those outcomes mean for the session.
 *
 * No threads, no I/O, no retry: the reconnect owner (`ProjectionService`) decides
 * whether a [UsbSessionState.DISCONNECTED] is worth re-accepting on.
 */
class UsbSession {

    var state: UsbSessionState = UsbSessionState.DETACHED
        private set

    /**
     * The USB manufacturer/model the accessory announced, from the attach intent
     * (`UsbAccessory.getManufacturer()/getModel()`). Empty until attached. Used by
     * the pairing UI to name the car while USB is the active transport.
     */
    var accessoryLabel: String? = null
        private set

    /**
     * Set when the session ends abnormally — open failure, permission denial —
     * for the trace log. Null on clean detach.
     */
    var failure: String? = null
        private set

    /** The accessory filter match fired: an AOAP head unit is on the cable. */
    fun onAttached(manufacturer: String?, model: String?) {
        accessoryLabel = listOfNotNull(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifEmpty { null }
        failure = null
        state = UsbSessionState.AWAITING_PERMISSION
    }

    /**
     * Whether `UsbManager.hasPermission()` is already true at attach time. When it
     * is, the driver skips the permission round-trip and calls [onPermissionGranted]
     * directly; a persisted grant across replugs lands here.
     */
    fun onAttachedWithPermission(manufacturer: String?, model: String?, hasPermission: Boolean) {
        onAttached(manufacturer, model)
        if (hasPermission) onPermissionGranted()
    }

    /** The user granted accessory access: the driver may now open the accessory. */
    fun onPermissionGranted() {
        if (state != UsbSessionState.AWAITING_PERMISSION) return
        failure = null
        state = UsbSessionState.CONNECTED
    }

    /** The user denied accessory access: projection over this cable is refused. */
    fun onPermissionDenied() {
        if (state != UsbSessionState.AWAITING_PERMISSION) return
        failure = "USB accessory permission denied"
        state = UsbSessionState.PERMISSION_DENIED
    }

    /** Opening the accessory failed after a grant (fd null, ioctl error, ...). */
    fun onOpenFailed(reason: String) {
        failure = reason.ifBlank { "USB accessory open failed" }
        state = UsbSessionState.DISCONNECTED
    }

    /** The cable was unplugged, or the accessory otherwise went away. */
    fun onDetached() {
        accessoryLabel = null
        // A clean unplug after a good session is not a failure; a denial that ends
        // in unplug keeps its explanation only while the denial row is showing.
        if (state != UsbSessionState.PERMISSION_DENIED) failure = null
        state = UsbSessionState.DETACHED
    }

    /** Re-request permission after a denial without a physical replug. */
    fun retryPermission(): Boolean {
        if (state != UsbSessionState.PERMISSION_DENIED) return false
        failure = null
        state = UsbSessionState.AWAITING_PERMISSION
        return true
    }
}
