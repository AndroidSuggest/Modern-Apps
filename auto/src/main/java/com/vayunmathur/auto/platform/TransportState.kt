package com.vayunmathur.auto.platform

import com.vayunmathur.auto.BuildConfig
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.TransportSelector
import com.vayunmathur.auto.protocol.UsbSession
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSession
import com.vayunmathur.auto.protocol.WirelessSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the pairing screen shows: USB + wireless bring-up state, the MAOS role,
 * and which transport won.
 *
 * Mirrors [AutoSessionState]: the connectors and receivers publish from whatever
 * thread they run on, and setting a [MutableStateFlow] value is thread-safe, so
 * these flows are the only thing shared with the UI. Selection itself is pure
 * ([TransportSelector]) over the published readiness — USB connected wins, then
 * wireless, then the TCP loopback fallback in dev builds only.
 */
object TransportState {
    private val _usbState = MutableStateFlow(UsbSessionState.DETACHED)
    val usbState: StateFlow<UsbSessionState> = _usbState.asStateFlow()

    /** Manufacturer + model the accessory announced; null until attached. */
    private val _usbLabel = MutableStateFlow<String?>(null)
    val usbLabel: StateFlow<String?> = _usbLabel.asStateFlow()

    private val _wirelessState = MutableStateFlow(WirelessSessionState.IDLE)
    val wirelessState: StateFlow<WirelessSessionState> = _wirelessState.asStateFlow()

    /** Whether this package currently holds `SYSTEM_AUTOMOTIVE_PROJECTION`. */
    private val _roleHeld = MutableStateFlow(false)
    val roleHeld: StateFlow<Boolean> = _roleHeld.asStateFlow()

    /** The winning transport; null when nothing may be used yet. */
    private val _selected = MutableStateFlow<TransportKind?>(null)
    val selected: StateFlow<TransportKind?> = _selected.asStateFlow()

    /** Mirrors a [UsbSession] after one of its transitions. */
    fun publishUsb(session: UsbSession) {
        _usbState.value = session.state
        _usbLabel.value = session.accessoryLabel
        refreshSelection()
    }

    /** Mirrors a [WirelessSession] after one of its transitions. */
    fun publishWireless(session: WirelessSession) {
        _wirelessState.value = session.state
        refreshSelection()
    }

    /** Records the platform role check; see `MaosRoleStatus`. */
    fun publishRole(held: Boolean) {
        _roleHeld.value = held
    }

    private fun refreshSelection() {
        _selected.value = TransportSelector.select(
            usbReady = _usbState.value == UsbSessionState.CONNECTED,
            wirelessReady = _wirelessState.value == WirelessSessionState.CONNECTED,
            devBuild = BuildConfig.DEV_BUILD,
        )
    }
}
