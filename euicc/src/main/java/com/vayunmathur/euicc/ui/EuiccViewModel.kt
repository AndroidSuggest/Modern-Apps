package com.vayunmathur.euicc.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.euicc.EuiccNative
import com.vayunmathur.euicc.data.EuiccInfo
import com.vayunmathur.euicc.telephony.EuiccChannelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** UI state for the EID / eUICC-info screen. */
sealed interface EuiccUiState {
    data object Loading : EuiccUiState
    data class Ready(val eid: String?, val info: EuiccInfo?) : EuiccUiState
    data class Error(val message: String) : EuiccUiState
}

class EuiccViewModel(app: Application) : AndroidViewModel(app) {
    private val channelManager = EuiccChannelManager(app)
    private val json = Json { ignoreUnknownKeys = true }

    var state by mutableStateOf<EuiccUiState>(EuiccUiState.Loading)
        private set

    init {
        refresh()
    }

    /** Opens the ISD-R channel and reads the EID + EUICCInfo1 off the eUICC. */
    fun refresh() {
        state = EuiccUiState.Loading
        viewModelScope.launch {
            state = withContext(Dispatchers.IO) {
                try {
                    channelManager.withIsdrChannel {
                        val eid = EuiccNative.nativeGetEid()
                        val info = EuiccNative.nativeGetEuiccInfo()
                            ?.let { json.decodeFromString<EuiccInfo>(it) }
                        EuiccUiState.Ready(eid, info)
                    }
                } catch (e: Exception) {
                    EuiccUiState.Error(e.message ?: "eUICC unavailable")
                }
            }
        }
    }
}
