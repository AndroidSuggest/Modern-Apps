package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.auto.protocol.TransportKind
import com.vayunmathur.auto.protocol.UsbSessionState
import com.vayunmathur.auto.protocol.WirelessSessionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What the pairing screen shows, driven by the transport connectors.
 *
 * The connectors publish to [TransportState] from whatever thread they run on;
 * this ViewModel only re-exposes those flows on a scope the UI can collect. It
 * never writes — mirroring [AutoViewModel].
 */
class PairingViewModel : ViewModel() {
    val usbState: StateFlow<UsbSessionState> = TransportState.usbState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UsbSessionState.DETACHED)

    val usbLabel: StateFlow<String?> = TransportState.usbLabel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val wirelessState: StateFlow<WirelessSessionState> = TransportState.wirelessState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), WirelessSessionState.IDLE)

    val roleHeld: StateFlow<Boolean> = TransportState.roleHeld
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val selected: StateFlow<TransportKind?> = TransportState.selected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
