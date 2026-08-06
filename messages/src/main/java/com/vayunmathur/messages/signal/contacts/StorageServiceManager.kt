package com.vayunmathur.messages.signal.contacts

import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.ContactRecord
import com.vayunmathur.messages.signal.proto.GroupV2Record
import com.vayunmathur.messages.signal.proto.ManifestRecord
import com.vayunmathur.messages.signal.proto.ReadOperation
import com.vayunmathur.messages.signal.proto.StorageItems
import com.vayunmathur.messages.signal.proto.StorageManifest
import com.vayunmathur.messages.signal.proto.StorageRecord
import com.vayunmathur.messages.signal.store.SignalGroupEntity
import com.vayunmathur.messages.signal.store.SignalGroupStore
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import java.io.ByteArrayOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class StorageServiceManager(
    private val recipientStore: SignalRecipientStore,
    private val groupStore: SignalGroupStore,
    private val ws: com.vayunmathur.messages.signal.web.SignalWebSocket,
    private val onAccountRecord: (suspend (com.vayunmathur.messages.signal.proto.AccountRecord) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "StorageService"
        private const val MAX_READ_STORAGE_RECORDS = 2500
        private const val STORAGE_AUTH_TTL_MS = 23L * 60 * 60 * 1000
        private const val STORAGE_SERVICE_ITEM_KEY_INFO_PREFIX = "20240801_SIGNAL_STORAGE_SERVICE_ITEM_"
        private const val STORAGE_SERVICE_ITEM_KEY_LEN = 32
    }

    enum class PhoneNumberSharingMode { EVERYBODY, CONTACTS_ONLY, NOBODY }

    var phoneNumberSharingMode: PhoneNumberSharingMode = PhoneNumberSharingMode.NOBODY
        private set

    private var storageAuth: Pair<String, String>? = null
    private var storageAuthTimestamp = 0L

    private suspend fun getStorageCredentials(): Pair<String, String> {
        val cached = storageAuth
        if (cached != null && System.currentTimeMillis() - storageAuthTimestamp < STORAGE_AUTH_TTL_MS) {
            return cached
        }
        val resp = ws.sendRequest("GET", "/v1/storage/auth")
        if (resp.status !in 200..299) throw java.io.IOException("Storage auth failed: ${resp.status}")
        val json = org.json.JSONObject(resp.body.toStringUtf8())
        val creds = Pair(json.getString("username"), json.getString("password"))
        storageAuth = creds
        storageAuthTimestamp = System.currentTimeMillis()
        return creds
    }

    suspend fun syncStorage(masterKey: ByteArray) {
        try {
            val storageKey = deriveStorageServiceKey(masterKey)
            val manifest = fetchManifest(storageKey) ?: return
            val records = fetchRecords(storageKey, manifest)
            processRecords(records)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync storage", e)
        }
    }

    private suspend fun fetchManifest(storageKey: ByteArray, greaterThanVersion: Long = 0): ManifestRecord? {
        val creds = getStorageCredentials()
        var path = "/v1/storage/manifest"
        if (greaterThanVersion > 0) {
            path += "/version/$greaterThanVersion"
        }
        val resp = SignalHttpClient.request(
            host = SignalHttpClient.STORAGE_HOST, method = "GET",
            path = path,
            username = creds.first, password = creds.second,
            contentType = "application/x-protobuf",
        )
        if (resp.status == 204) return null
        if (resp.status != 200) throw java.io.IOException("Storage manifest fetch failed: ${resp.status}")
        val body = resp.bytes
        val enc = StorageManifest.parseFrom(body)
        val mKey = deriveManifestKey(storageKey, enc.version)
        val dec = decryptAESGCM(mKey, enc.value.toByteArray())
        return ManifestRecord.parseFrom(dec)
    }

    private suspend fun fetchRecords(
        storageKey: ByteArray,
        manifest: ManifestRecord,
    ): List<StorageRecord> {
        val recordIkm = manifest.recordIkm?.takeIf { !it.isEmpty }?.toByteArray()
        val allKeys = manifest.identifiersList.map { it.raw.toByteArray() }
        val results = mutableListOf<StorageRecord>()
        for (chunk in allKeys.chunked(MAX_READ_STORAGE_RECORDS)) {
            val readOp = ReadOperation.newBuilder()
            chunk.forEach { readOp.addReadKey(com.google.protobuf.ByteString.copyFrom(it)) }
            val creds = getStorageCredentials()
            val resp = SignalHttpClient.request(
                host = SignalHttpClient.STORAGE_HOST, method = "PUT",
                path = "/v1/storage/read",
                body = readOp.build().toByteArray(),
                username = creds.first, password = creds.second,
                contentType = "application/x-protobuf",
            )
            if (!resp.isSuccess) throw java.io.IOException("Storage read failed: ${resp.status}")
            if (resp.bytes.isEmpty()) continue
            val items = StorageItems.parseFrom(resp.bytes)
            items.itemsList.mapNotNull { item ->
                try {
                    val rawKey = item.key.toByteArray()
                    val b64 = Base64.encodeToString(rawKey, Base64.NO_WRAP)
                    val iKey = deriveItemKey(storageKey, recordIkm, rawKey, b64)
                    StorageRecord.parseFrom(decryptAESGCM(iKey, item.value.toByteArray()))
                } catch (e: Exception) { null }
            }.let { results.addAll(it) }
        }
        return results
    }

    private suspend fun processRecords(records: List<StorageRecord>) {
        for (r in records) {
            try {
                if (r.hasContact()) processContact(r.contact)
                else if (r.hasGroupV2()) processGroup(r.groupV2)
                else if (r.hasAccount()) {
                    Log.d(TAG, "Found account record, saving")
                    processAccountRecord(r.account)
                    try {
                        onAccountRecord?.invoke(r.account)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save account record", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to process record", e)
            }
        }
    }

    private fun processAccountRecord(account: com.vayunmathur.messages.signal.proto.AccountRecord) {
        phoneNumberSharingMode = when (account.phoneNumberSharingMode) {
            com.vayunmathur.messages.signal.proto.AccountRecord.PhoneNumberSharingMode.EVERYBODY ->
                PhoneNumberSharingMode.EVERYBODY
            else -> PhoneNumberSharingMode.NOBODY
        }
        Log.d(TAG, "PhoneNumberSharingMode: $phoneNumberSharingMode")
    }

    private suspend fun processContact(c: ContactRecord) {
        val aci = ContactManager.parseStringOrBinaryAci(
            c.aci.takeIf { it.isNotBlank() },
            c.aciBinary?.takeIf { !it.isEmpty }?.toByteArray(),
        )
        val pni = ContactManager.parseStringOrBinaryAci(
            c.pni.takeIf { it.isNotBlank() },
            c.pniBinary?.takeIf { !it.isEmpty }?.toByteArray(),
        )
        if (aci == null && pni == null) {
            Log.w(TAG, "Storage service has contact record with no ACI or PNI")
            return
        }
        val entityAci = aci ?: "PNI:$pni"
        val existing = recipientStore.getRecipient(entityAci)

        val pk = if (c.profileKey.size() == 32)
            c.profileKey.toByteArray()
        else existing?.profileKey

        // Profile name from given/family name - only set if currently empty (matching Go)
        val profileName = if (existing?.profileName.isNullOrBlank()) {
            listOf(c.givenName, c.familyName)
                .filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
        } else existing.profileName

        // Contact name from system given/family name (always update when present)
        val contactName = listOf(c.systemGivenName, c.systemFamilyName)
            .filter { it.isNotBlank() }.joinToString(" ")
            .takeIf { it.isNotBlank() } ?: existing?.contactName

        // Nickname handling
        val nickname = if (c.hasNickname() && (c.nickname.given.isNotBlank() || c.nickname.family.isNotBlank())) {
            listOf(c.nickname.given, c.nickname.family)
                .filter { it.isNotBlank() }.joinToString(" ")
                .takeIf { it.isNotBlank() }
        } else {
            null
        }

        recipientStore.storeRecipient(SignalRecipientEntity(
            aci = entityAci,
            pni = pni ?: existing?.pni,
            e164 = c.e164.takeIf { it.isNotBlank() } ?: existing?.e164,
            contactName = contactName,
            nickname = nickname,
            profileName = profileName ?: existing?.profileName,
            profileKey = pk,
            blocked = c.blocked,
            whitelisted = if (existing?.whitelisted == true) true else c.whitelisted,
        ))
    }

    private suspend fun processGroup(g: GroupV2Record) {
        if (g.masterKey.size() != 32) return
        val b64 = Base64.encodeToString(g.masterKey.toByteArray(), Base64.NO_WRAP)
        groupStore.storeGroup(SignalGroupEntity(groupId = b64, masterKey = g.masterKey.toByteArray(), revision = 0))
    }

    private fun deriveStorageServiceKey(masterKey: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(masterKey, "HmacSHA256"))
        return mac.doFinal("Storage Service Encryption".toByteArray())
    }

    private fun deriveManifestKey(storageKey: ByteArray, version: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(storageKey, "HmacSHA256"))
        return mac.doFinal("Manifest_$version".toByteArray())
    }

    private fun deriveItemKey(
        storageKey: ByteArray,
        recordIkm: ByteArray?,
        rawItemId: ByteArray,
        b64ItemId: String,
    ): ByteArray {
        if (recordIkm == null) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(storageKey, "HmacSHA256"))
            return mac.doFinal("Item_$b64ItemId".toByteArray())
        } else {
            val info = STORAGE_SERVICE_ITEM_KEY_INFO_PREFIX.toByteArray() + rawItemId
            return hkdfSha256(recordIkm, ByteArray(0), info, STORAGE_SERVICE_ITEM_KEY_LEN)
        }
    }

    private fun decryptAESGCM(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 12 + 16 + 1) throw IllegalArgumentException("Invalid encrypted data length")
        val nonce = data.copyOfRange(0, 12)
        val ciphertext = data.copyOfRange(12, data.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        val n = (length + 31) / 32
        var t = ByteArray(0)
        val okm = ByteArrayOutputStream()
        for (i in 1..n) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(byteArrayOf(i.toByte()))
            t = mac.doFinal()
            okm.write(t)
        }
        return okm.toByteArray().copyOfRange(0, length)
    }
}