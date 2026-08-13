package com.vayunmathur.contacts.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * SIM ADN (Abbreviated Dialling Numbers) data source.
 *
 * Accessed via content://icc/adn and content://icc/adn/subId/<subId>.
 * Columns: name/tag, number, emails, _id (where present). Insert/delete are
 * supported via ContentResolver on most devices; queries may throw or return
 * null on emulators or devices without a physical SIM.
 */
data class SimContact(
    val id: Long = -1L,
    val name: String,
    val number: String,
    val emails: String? = null,
    val subscriptionId: Int? = null
)

object SimContactsDataSource {

    private const val TAG = "SimContacts"
    private const val BASE_URI = "content://icc/adn"

    fun getActiveSubscriptionIds(context: Context): List<Int> {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                // Still try — some devices allow reading without READ_PHONE_STATE
                // but SubscriptionManager will throw SecurityException otherwise.
            }
            // Prefer SubscriptionManager
            val subMgr = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val list = try {
                subMgr.activeSubscriptionInfoList
            } catch (e: SecurityException) {
                Log.w(TAG, "No permission for activeSubscriptionInfoList", e)
                null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get active subscriptions", e)
                null
            }
            list?.mapNotNull { it.subscriptionId } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getActiveSubscriptionIds failed", e)
            emptyList()
        }
    }

    fun listSimContacts(context: Context): List<SimContact> {
        val result = mutableListOf<SimContact>()
        try {
            val subIds = getActiveSubscriptionIds(context)
            val uris: List<Pair<Uri, Int?>> = if (subIds.isNotEmpty()) {
                subIds.map { subId -> Uri.parse("$BASE_URI/subId/$subId") to subId }
            } else {
                listOf(Uri.parse(BASE_URI) to null)
            }
            for ((uri, subId) in uris) {
                try {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        if (cursor.count == 0) continue
                        val nameIdx = when {
                            cursor.getColumnIndex("name") != -1 -> cursor.getColumnIndex("name")
                            cursor.getColumnIndex("tag") != -1 -> cursor.getColumnIndex("tag")
                            else -> -1
                        }
                        val numberIdx = when {
                            cursor.getColumnIndex("number") != -1 -> cursor.getColumnIndex("number")
                            cursor.getColumnIndex("newNumber") != -1 -> cursor.getColumnIndex("newNumber")
                            else -> -1
                        }
                        val emailsIdx = when {
                            cursor.getColumnIndex("emails") != -1 -> cursor.getColumnIndex("emails")
                            cursor.getColumnIndex("email") != -1 -> cursor.getColumnIndex("email")
                            else -> -1
                        }
                        val idIdx = cursor.getColumnIndex("_id")
                        while (cursor.moveToNext()) {
                            try {
                                val rawName = if (nameIdx != -1) cursor.getString(nameIdx) else null
                                val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx) else null
                                val rawEmails = if (emailsIdx != -1) cursor.getString(emailsIdx) else null
                                val name = rawName?.trim().orEmpty()
                                val number = rawNumber?.trim().orEmpty()
                                if (name.isEmpty() && number.isEmpty()) continue
                                val id = if (idIdx != -1) try { cursor.getLong(idIdx) } catch (_: Exception) { -1L } else -1L
                                result.add(
                                    SimContact(
                                        id = id,
                                        name = name,
                                        number = number,
                                        emails = rawEmails?.trim()?.takeIf { it.isNotEmpty() },
                                        subscriptionId = subId
                                    )
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Skipping SIM row", e)
                            }
                        }
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException querying $uri", e)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed querying $uri", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "listSimContacts failed", e)
        }
        // Deduplicate by (subId, name, number, emails)
        return result.distinctBy { "${it.subscriptionId}|${it.name}|${it.number}|${it.emails}" }
    }

    fun insertSimContact(
        context: Context,
        name: String,
        number: String,
        email: String? = null,
        subscriptionId: Int? = null
    ): Boolean {
        if (name.isBlank() && number.isBlank()) return false
        return try {
            val subIds = if (subscriptionId != null) listOf(subscriptionId) else getActiveSubscriptionIds(context)
            val targetSubId = subIds.firstOrNull() ?: subscriptionId
            val uri = if (targetSubId != null) Uri.parse("$BASE_URI/subId/$targetSubId") else Uri.parse(BASE_URI)
            val values = ContentValues().apply {
                // Most devices accept "tag" for name
                put("tag", name)
                put("number", number)
                // Some accept "name" alias
                if (!containsKey("tag")) put("name", name)
                if (!email.isNullOrBlank()) {
                    put("emails", email)
                    put("email", email)
                }
            }
            val inserted = context.contentResolver.insert(uri, values)
            inserted != null
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException inserting SIM contact", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "insertSimContact failed", e)
            false
        }
    }

    fun deleteSimContact(context: Context, simContact: SimContact): Boolean {
        return try {
            val uri = if (simContact.subscriptionId != null) {
                Uri.parse("$BASE_URI/subId/${simContact.subscriptionId}")
            } else {
                // Try all known URIs if subId unknown
                val subIds = getActiveSubscriptionIds(context)
                if (subIds.isNotEmpty()) {
                    var deletedAny = false
                    for (sid in subIds) {
                        if (deleteSingle(context, Uri.parse("$BASE_URI/subId/$sid"), simContact)) deletedAny = true
                    }
                    // Also try base
                    if (deleteSingle(context, Uri.parse(BASE_URI), simContact)) deletedAny = true
                    return deletedAny
                }
                Uri.parse(BASE_URI)
            }
            deleteSingle(context, uri, simContact)
        } catch (e: Exception) {
            Log.e(TAG, "deleteSimContact failed", e)
            false
        }
    }

    private fun deleteSingle(context: Context, uri: Uri, simContact: SimContact): Boolean {
        // ADN provider typically deletes via where tag=? AND number=?
        val variants = listOf(
            "tag=? AND number=?" to arrayOf(simContact.name, simContact.number),
            "name=? AND number=?" to arrayOf(simContact.name, simContact.number),
            "tag=? AND newTag=? AND number=? AND newNumber=?" to arrayOf(simContact.name, simContact.name, simContact.number, simContact.number),
            // Fallback: number only
            "number=?" to arrayOf(simContact.number),
        )
        for ((where, args) in variants) {
            try {
                val count = context.contentResolver.delete(uri, where, args)
                if (count > 0) return true
            } catch (_: Exception) { }
        }
        // Last resort: query then delete by encoding? Some providers expect content://icc/adn/#
        // Not supported uniformly — return false
        return false
    }

    fun hasSim(context: Context): Boolean {
        return try {
            getActiveSubscriptionIds(context).isNotEmpty()
        } catch (_: Exception) { false }
    }
}
