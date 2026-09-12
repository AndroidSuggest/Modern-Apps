package com.vayunmathur.auto.platform

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * What the phone status screen shows, driven by the projection session.
 *
 * The session runs on its own worker thread and publishes to [AutoSessionState]; this
 * ViewModel only re-exposes those flows on a scope the UI can collect. It never writes.
 */
class AutoViewModel : ViewModel() {
    val connection: StateFlow<AutoConnectionState> = AutoSessionState.connection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AutoConnectionState.Disconnected)

    val video: StateFlow<VideoInfo?> = AutoSessionState.video
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val focusMode: StateFlow<String?> = AutoSessionState.focusMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val framesSent: StateFlow<Long> = AutoSessionState.framesSent
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    val acksSeen: StateFlow<Long> = AutoSessionState.acksSeen
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    val ackMismatches: StateFlow<Long> = AutoSessionState.ackMismatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0L)

    val sessionStartedAt: StateFlow<Long?> = AutoSessionState.sessionStartedAt
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
