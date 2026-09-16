package com.vayunmathur.emergency.platform

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.emergency.data.EmergencyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EmergencyViewModel(app: Application) : AndroidViewModel(app), EmergencyActions {

    private val repository = EmergencyRepository.get(app)
    private val healthConnect = HealthConnectMedical(app)
    private val loaded = MutableStateFlow(false)
    private val hasContactsAccess = MutableStateFlow(false)
    private val addFailed = MutableStateFlow(false)
    private val health = MutableStateFlow(HealthConnectMedicalState())

    val state: StateFlow<EmergencyUiState> = combine(
        repository.info,
        loaded,
        hasContactsAccess,
        addFailed,
        health,
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val info = flows[0] as com.vayunmathur.emergency.data.EmergencyInfo
        EmergencyUiState(
            info = info,
            loading = !(flows[1] as Boolean),
            hasContactsAccess = flows[2] as Boolean,
            addFailed = flows[3] as Boolean,
            health = flows[4] as HealthConnectMedicalState,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), EmergencyUiState())

    init {
        refresh()
    }

    override fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            hasContactsAccess.value = checkContactsGrant()
            repository.load()
            loaded.value = true
        }
        refreshHealthConnect()
    }

    override fun refreshHealthConnect() {
        viewModelScope.launch(Dispatchers.IO) {
            val available = healthConnect.isAvailable()
            if (!available) {
                health.value = HealthConnectMedicalState(available = false)
                return@launch
            }
            val granted = healthConnect.hasPermissions()
            if (!granted) {
                health.value = HealthConnectMedicalState(available = true, granted = false)
                return@launch
            }
            health.value = HealthConnectMedicalState(
                available = true,
                granted = true,
                allergies = healthConnect.readAllergies(),
                medications = healthConnect.readCurrentMedications(),
            )
        }
    }

    override fun saveInfo(
        name: String,
        address: String,
        bloodType: String,
        organDonor: String,
    ) {
        viewModelScope.launch {
            repository.saveInfo(name, address, bloodType, organDonor)
        }
    }

    override fun addContact(phoneUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            addFailed.value = !repository.addContact(phoneUri)
        }
    }

    override fun removeContact(phoneUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeContact(phoneUri)
        }
    }

    override fun clearAddFailed() {
        addFailed.value = false
    }

    private fun checkContactsGrant(): Boolean =
        getApplication<Application>().checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
