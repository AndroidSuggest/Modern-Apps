package com.vayunmathur.emergency.platform

import android.net.Uri
import com.vayunmathur.emergency.data.EmergencyContact
import com.vayunmathur.emergency.data.EmergencyInfo
import com.vayunmathur.emergency.data.ImportedAllergy
import com.vayunmathur.emergency.data.ImportedMedication

/**
 * The UI contract between [EmergencyViewModel]/[SosCoordinator] and the screens in `ui`.
 *
 * Pages take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` - which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives beside the ViewModel rather than in
 * `ui` so the dependency runs one way.
 */

/**
 * Allergies and current medications read from Health Connect, shown alongside the stored
 * fields. All read-only; empty and [available] false on devices without the PHR feature.
 */
data class HealthConnectMedicalState(
    /** The device's Health Connect module exposes Personal Health Records. */
    val available: Boolean = false,
    /** Both medical read permissions are granted. */
    val granted: Boolean = false,
    val allergies: List<ImportedAllergy> = emptyList(),
    val medications: List<ImportedMedication> = emptyList(),
)

/** Everything the view/edit screens draw. */
data class EmergencyUiState(
    val info: EmergencyInfo = EmergencyInfo(),
    /** True while the stored info is still being loaded. */
    val loading: Boolean = true,
    /** False until READ_CONTACTS is granted; the contact list must prompt, not show empty. */
    val hasContactsAccess: Boolean = false,
    /** Last add-contact failure, for a one-shot message; null when none. */
    val addFailed: Boolean = false,
    /** Allergies and current medications from Health Connect, when available and granted. */
    val health: HealthConnectMedicalState = HealthConnectMedicalState(),
)

interface EmergencyActions {
    /** Persists the manually-entered identity fields (allergies/medications come from HC). */
    fun saveInfo(
        name: String,
        address: String,
        bloodType: String,
        organDonor: String,
    ) {}

    /** Adds the picked contact URI; sets [EmergencyUiState.addFailed] when rejected. */
    fun addContact(phoneUri: Uri) {}

    fun removeContact(phoneUri: Uri) {}

    /** Re-reads stored info and re-checks the contacts grant. */
    fun refresh() {}

    /** Clears the one-shot add-failure flag after it has been shown. */
    fun clearAddFailed() {}

    /** Re-reads Health Connect allergies/medications after a permission grant. */
    fun refreshHealthConnect() {}

    companion object {
        val Noop: EmergencyActions = object : EmergencyActions {}
    }
}

/** Everything the SOS countdown screen draws. */
data class SosUiState(
    /** The number about to be dialled (override-aware). */
    val number: String = EmergencyNumberLookup.FALLBACK_NUMBER,
    /** Milliseconds left when the screen opened; the screen counts down from here. */
    val totalMillis: Long = 0,
    /** False when the gesture is off - the screen must exit, not count down. */
    val gestureEnabled: Boolean = false,
    /** True once the state above has been read. */
    val loaded: Boolean = false,
)

interface SosActions {
    /** Hands the remaining countdown to the foreground service and finishes the screen. */
    fun continueInBackground(remainingMillis: Long) {}

    /** Cancels the countdown entirely (alarm + service + sound). */
    fun cancel() {}

    companion object {
        val Noop: SosActions = object : SosActions {}
    }
}

/** Preview/sample contact for screenshots. */
fun sampleContact(): EmergencyContact = EmergencyContact(
    phoneUri = Uri.parse("content://com.android.contacts/data/1"),
    displayName = "Alex Rivera",
    phoneNumber = "(555) 010-2030",
    phoneType = "Mobile",
)

/** Preview/sample Health Connect data for screenshots. */
fun sampleHealthConnect(): HealthConnectMedicalState = HealthConnectMedicalState(
    available = true,
    granted = true,
    allergies = listOf(
        ImportedAllergy("Penicillin", com.vayunmathur.emergency.data.AllergyCriticality.High, "Anaphylaxis"),
        ImportedAllergy("Peanuts", com.vayunmathur.emergency.data.AllergyCriticality.High, "Hives"),
        ImportedAllergy("Pollen", com.vayunmathur.emergency.data.AllergyCriticality.Low, null),
    ),
    medications = listOf(
        ImportedMedication("Salbutamol 100mcg inhaler"),
        ImportedMedication("Lisinopril 10mg"),
    ),
)
