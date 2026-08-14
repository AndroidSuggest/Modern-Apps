package com.vayunmathur.communicate.data.signal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import com.google.i18n.phonenumbers.PhoneNumberUtil

/**
 * Device address-book → Signal contact sync.
 *
 * Mirrors [com.vayunmathur.communicate.data.whatsapp.WhatsAppContactSync] for Signal:
 *  - reads device contacts behind READ_CONTACTS
 *  - normalizes to E.164 via libphonenumber
 *  - de-dups + persists to SignalContact table
 *  - resolves Signal ACI/PNI mapping via CDS (Contact Discovery Service) when available;
 *    falls back to persisting with onSignal=false until a live CDS call succeeds
 *
 * Signal's CDS is the equivalent of WhatsApp's MEX contact discovery; it is the only
 * server that can map E.164 → ACI. Until the live CDS integration is validated against
 * `https://cms.smsfaith.org` / `https://chat.signal.org`, the sync persists locally
 * and marks contacts as not-yet-resolved.
 */
object SignalContactSync {
    private const val TAG = "SignalContactSync"

    data class DeviceContact(val e164: String, val name: String)

    data class SyncResult(
        val deviceCount: Int,
        val e164Count: Int,
        val onSignalCount: Int,
        val transportError: String? = null,
    )

    fun normalizeE164(raw: String, region: String): String? = runCatching {
        val util = PhoneNumberUtil.getInstance()
        val parsed = util.parse(raw, region)
        if (!util.isValidNumber(parsed)) return null
        util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }.getOrNull()

    fun defaultRegion(context: Context): String = runCatching {
        val tm = context.getSystemService(TelephonyManager::class.java)
        (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso)?.uppercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: context.resources.configuration.locales[0].country.ifEmpty { "US" }

    fun readDeviceContacts(context: Context): List<Pair<String, String>> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        return runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null,
            )?.use { cursor ->
                buildList {
                    val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
                    val numIdx = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val number = cursor.getString(numIdx).orEmpty().trim()
                        if (number.isEmpty()) continue
                        add(cursor.getString(nameIdx).orEmpty() to number)
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    suspend fun sync(context: Context): SyncResult {
        if (!SignalFeature.enabled) return SyncResult(0, 0, 0, "disabled")
        val region = defaultRegion(context)
        val device = readDeviceContacts(context)
        val byE164 = LinkedHashMap<String, String>()
        for ((name, number) in device) {
            val e164 = normalizeE164(number, region) ?: continue
            byE164.putIfAbsent(e164, name)
        }
        val e164s = byE164.keys.toList()
        if (e164s.isEmpty()) return SyncResult(device.size, 0, 0)

        val now = System.currentTimeMillis()
        val db = SignalDatabase.getDatabase(context)
        // Persist locally; CDS resolution is best-effort and may require live Signal server.
        db.contactDao().upsertAll(
            byE164.map { (e164, name) -> SignalContact(aci = e164, phoneE164 = e164, displayName = name, onSignal = false, updatedAt = now) },
        )
        // TODO: CDS discovery — POST to Signal CDS with sealed discovery; when live, update onSignal + aci.
        // Until then, contacts are stored locally and the inbox can still address by E.164.
        Log.i(TAG, "sync: device=${device.size} e164=${e164s.size} (CDS not yet live)")
        return SyncResult(device.size, e164s.size, 0)
    }
}
