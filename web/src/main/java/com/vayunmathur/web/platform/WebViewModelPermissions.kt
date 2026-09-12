package com.vayunmathur.web.platform

import androidx.lifecycle.viewModelScope
import com.vayunmathur.web.data.SitePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---- Site permissions ----

fun WebViewModel.requestWebPermission(
    origin: String,
    types: List<SitePermissionType>,
    grant: (List<SitePermissionType>) -> Unit,
    deny: () -> Unit
) {
    viewModelScope.launch {
        val saved = repository.sitePermissionByOrigin(origin)
        val (toAsk, preGranted) = if (saved != null) {
            val determined = types.mapNotNull { t ->
                when (t) {
                    SitePermissionType.CAMERA -> saved.cameraAllowed?.let { t to it }
                    SitePermissionType.MICROPHONE -> saved.microphoneAllowed?.let { t to it }
                    SitePermissionType.LOCATION -> saved.locationAllowed?.let { t to it }
                    SitePermissionType.NOTIFICATIONS -> saved.notificationsAllowed?.let { t to it }
                }
            }
            val grantedFromSaved = determined.filter { it.second }.map { it.first }
            val remaining = types.filter { type -> determined.none { it.first == type } }
            remaining to grantedFromSaved
        } else {
            types to emptyList()
        }

        if (toAsk.isEmpty()) {
            if (preGranted.isNotEmpty()) {
                withContext(Dispatchers.Main) { grant(preGranted) }
            } else {
                withContext(Dispatchers.Main) { deny() }
            }
            return@launch
        }

        withContext(Dispatchers.Main) {
            pendingPermissionPrompt = PermissionPrompt(
                origin = origin,
                types = toAsk,
                onGrant = { grantedNow ->
                    persistPermission(origin, grantedNow, toAsk)
                    grant(preGranted + grantedNow)
                },
                onDeny = {
                    persistPermission(origin, emptyList(), toAsk)
                    if (preGranted.isNotEmpty()) grant(preGranted) else deny()
                }
            )
        }
    }
}

internal fun WebViewModel.persistPermission(origin: String, granted: List<SitePermissionType>, requested: List<SitePermissionType>) {
    viewModelScope.launch {
        val existing = repository.sitePermissionByOrigin(origin) ?: SitePermission(origin = origin)
        var updated = existing
        requested.forEach { t ->
            val isGranted = t in granted
            updated = when (t) {
                SitePermissionType.CAMERA -> updated.copy(cameraAllowed = isGranted)
                SitePermissionType.MICROPHONE -> updated.copy(microphoneAllowed = isGranted)
                SitePermissionType.LOCATION -> updated.copy(locationAllowed = isGranted)
                SitePermissionType.NOTIFICATIONS -> updated.copy(notificationsAllowed = isGranted)
            }
        }
        repository.upsertSitePermission(updated.copy(updatedAt = System.currentTimeMillis()))
    }
}

fun WebViewModel.clearPermissionPrompt() { pendingPermissionPrompt = null }

fun WebViewModel.requestGeolocation(origin: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    viewModelScope.launch {
        val saved = repository.sitePermissionByOrigin(origin)
        when (saved?.locationAllowed) {
            true -> { withContext(Dispatchers.Main) { onAllow() }; return@launch }
            false -> { withContext(Dispatchers.Main) { onDeny() }; return@launch }
            null -> {}
        }
        withContext(Dispatchers.Main) {
            pendingGeolocationPrompt = Triple(origin, onAllow, onDeny)
        }
    }
}

fun WebViewModel.grantGeolocation(origin: String) {
    pendingGeolocationPrompt?.let { (orig, allow, _) ->
        persistPermission(orig, listOf(SitePermissionType.LOCATION), listOf(SitePermissionType.LOCATION))
        allow()
    }
    pendingGeolocationPrompt = null
}

fun WebViewModel.denyGeolocation() {
    pendingGeolocationPrompt?.let { (orig, _, deny) ->
        persistPermission(orig, emptyList(), listOf(SitePermissionType.LOCATION))
        deny()
    }
    pendingGeolocationPrompt = null
}

fun WebViewModel.revokePermission(origin: String, type: SitePermissionType) {
    viewModelScope.launch {
        val existing = repository.sitePermissionByOrigin(origin) ?: return@launch
        val updated = when (type) {
            SitePermissionType.CAMERA -> existing.copy(cameraAllowed = null)
            SitePermissionType.MICROPHONE -> existing.copy(microphoneAllowed = null)
            SitePermissionType.LOCATION -> existing.copy(locationAllowed = null)
            SitePermissionType.NOTIFICATIONS -> existing.copy(notificationsAllowed = null)
        }
        if (updated.cameraAllowed == null && updated.microphoneAllowed == null && updated.locationAllowed == null && updated.notificationsAllowed == null) {
            repository.deleteSitePermission(updated)
        } else {
            repository.upsertSitePermission(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }
}
