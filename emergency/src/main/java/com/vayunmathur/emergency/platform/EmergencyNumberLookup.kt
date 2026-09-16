package com.vayunmathur.emergency.platform

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.emergency.EmergencyNumber
import android.util.Log
import androidx.core.content.getSystemService

private const val TAG = "EmergencyNumberLookup"

/**
 * Picks the number the SOS countdown is about to dial.
 *
 * Mirrors `EmergencyNumberUtils.getPoliceNumber`: the user override (see [GestureProvider])
 * wins; otherwise the first police-category number from the SIM emergency-number list
 * (database-sourced entries preferred); otherwise the `112` fallback. Needs
 * `READ_PHONE_STATE` for `getEmergencyNumberList` - without the grant (or without
 * telephony at all) this returns the fallback rather than failing.
 */
class EmergencyNumberLookup(private val context: Context) {

    fun policeNumber(override: String?): String {
        if (!override.isNullOrBlank()) return override
        return defaultPoliceNumber()
    }

    fun defaultPoliceNumber(): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return FALLBACK_NUMBER
        }
        if (!hasPhoneStateGrant()) return FALLBACK_NUMBER
        val telecom = context.getSystemService<TelephonyManager>() ?: return FALLBACK_NUMBER
        val lists = runCatching {
            telecom.getEmergencyNumberList(EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE)
        }.getOrElse {
            Log.w(TAG, "could not read the emergency-number list", it)
            return FALLBACK_NUMBER
        }
        if (lists.isNullOrEmpty()) return FALLBACK_NUMBER
        val subId = SubscriptionManager.getDefaultSubscriptionId()
        val numbers = lists[subId] ?: lists.values.firstOrNull().orEmpty()
        if (numbers.isEmpty()) return FALLBACK_NUMBER
        // Database-sourced entries are well-categorised; prefer them like SettingsLib does.
        val preferred = numbers.firstOrNull {
            EmergencyNumber.EMERGENCY_NUMBER_SOURCE_DATABASE in it.emergencyNumberSources
        } ?: numbers.first()
        return preferred.number.ifBlank { FALLBACK_NUMBER }
    }

    private fun hasPhoneStateGrant(): Boolean {
        val result = context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        return result == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /** SettingsLib `FALL_BACK_NUMBER`: the GSM emergency number, reachable everywhere. */
        const val FALLBACK_NUMBER = "112"
    }
}
