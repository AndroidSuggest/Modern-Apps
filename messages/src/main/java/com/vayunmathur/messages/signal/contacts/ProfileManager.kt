package com.vayunmathur.messages.signal.contacts

import android.util.Log
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ProfileUnauthorizedException : Exception("profile get returned 401")
class ProfileNotFoundException : Exception("profile get returned 404")
class ProfileInternalErrorException(status: Int) : Exception("profile get returned $status")

class ProfileManager(
    private val ws: SignalWebSocket,
    private val recipientStore: SignalRecipientStore,
    private val unauthedWs: SignalWebSocket? = null,
) {
    data class Profile(
        val name: String?,
        val about: String?,
        val aboutEmoji: String?,
        val avatarPath: String?,
        val credential: ByteArray? = null,
        val fetchedAt: Long = System.currentTimeMillis(),
    )

    private val profileCache = ConcurrentHashMap<String, Profile>()
    private val errorCache = ConcurrentHashMap<String, Exception>()
    private val lastFetched = ConcurrentHashMap<String, Long>()

    suspend fun retrieveProfileByID(
        aci: String,
        profileKey: ByteArray?,
        refreshAfter: Long = DEFAULT_PROFILE_REFRESH_AFTER_MS,
    ): Profile? {
        val fetchTime = lastFetched[aci]
        if (fetchTime != null && System.currentTimeMillis() - fetchTime < refreshAfter) {
            profileCache[aci]?.let { return it }
            errorCache[aci]?.let {
                Log.d(TAG, "Returning cached error for $aci: ${it.message}")
                return null
            }
        }
        val profile = fetchProfile(aci, profileKey)
        if (profile != null) {
            profileCache[aci] = profile
            errorCache.remove(aci)
            lastFetched[aci] = System.currentTimeMillis()
        }
        return profile
    }

    suspend fun fetchProfile(aci: String, profileKey: ByteArray?, credentialRequest: String? = null): Profile? {
        return try {
            val headers = mutableMapOf<String, String>()
            val profileKeyHex = profileKey?.let { pk ->
                try {
                    val profileKeyObj = org.signal.libsignal.zkgroup.profiles.ProfileKey(pk)
                    val version = profileKeyObj.getProfileKeyVersion(
                        org.signal.libsignal.protocol.ServiceId.Aci(java.util.UUID.fromString(aci))
                    )
                    val accessKey = profileKeyObj.deriveAccessKey()
                    headers["Unidentified-Access-Key"] = android.util.Base64.encodeToString(accessKey, android.util.Base64.NO_WRAP)
                    headers["Accept-Language"] = "en-US"
                    version.serialize()
                } catch (e: Exception) {
                    Log.w(TAG, "Could not derive profile key version", e)
                    null
                }
            }

            var path = "/v1/profile/$aci"
            if (profileKeyHex != null) {
                path += "/$profileKeyHex"
            }
            if (credentialRequest != null) {
                path += "/$credentialRequest"
                path += "?credentialType=expiringProfileKey"
            }

            val useUnidentified = profileKeyHex != null && headers.containsKey("Unidentified-Access-Key")
            val profileWs = if (useUnidentified && unauthedWs != null) unauthedWs else ws
            val response = profileWs.sendRequest("GET", path,
                headers = headers)

            val status = response.status
            if (status == 401) {
                cacheError(aci, ProfileUnauthorizedException())
                return null
            }
            if (status == 404) {
                throw ProfileNotFoundException()
            }
            if (status in 500..599) {
                cacheError(aci, ProfileInternalErrorException(status))
                return null
            }
            if (status !in 200..299) {
                Log.e(TAG, "Unexpected status code $status fetching profile for $aci")
                return null
            }

            val json = JSONObject(String(response.body.toByteArray()))
            val encryptedName = json.optString("name", null)
            val encryptedAbout = json.optString("about", null)
            val encryptedAboutEmoji = json.optString("aboutEmoji", null)
            val rawAvatar = json.optString("avatar", null)
            val credentialBase64 = json.optString("credential", null)
            val credential = credentialBase64?.takeIf { it.isNotEmpty() }?.let {
                android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
            }

            val name = if (encryptedName != null && profileKey != null) {
                decryptProfileString(profileKey,
                    android.util.Base64.decode(encryptedName, android.util.Base64.NO_WRAP)
                )?.replace('\u0000', ' ')
            } else null

            val about = if (encryptedAbout != null && profileKey != null) {
                decryptProfileString(profileKey,
                    android.util.Base64.decode(encryptedAbout, android.util.Base64.NO_WRAP))
            } else null

            val aboutEmoji = if (encryptedAboutEmoji != null && profileKey != null) {
                decryptProfileString(profileKey,
                    android.util.Base64.decode(encryptedAboutEmoji, android.util.Base64.NO_WRAP))
            } else null

            val avatarPath = if (rawAvatar.isNullOrEmpty()) "clear" else rawAvatar
            val fetchedAt = System.currentTimeMillis()

            val profile = Profile(
                name = name, about = about, aboutEmoji = aboutEmoji,
                avatarPath = avatarPath, credential = credential, fetchedAt = fetchedAt,
            )

            val existing = recipientStore.getRecipient(aci)
            if (existing != null) {
                recipientStore.storeRecipient(existing.copy(
                    profileName = name ?: existing.profileName,
                    profileAbout = about ?: existing.profileAbout,
                    profileAboutEmoji = aboutEmoji ?: existing.profileAboutEmoji,
                    profileAvatarPath = avatarPath,
                    profileFetchedAt = fetchedAt,
                ))
            }

            profile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch profile for $aci", e)
            null
        }
    }

    suspend fun downloadUserAvatar(
        avatarPath: String,
        profileKey: ByteArray,
        username: String? = null,
        password: String? = null,
    ): ByteArray? {
        return try {
            val resp = SignalHttpClient.request(
                host = SignalHttpClient.CDN1_HOST,
                method = "GET",
                path = avatarPath,
                username = username,
                password = password,
            )
            if (!resp.isSuccess) {
                Log.e(TAG, "Unexpected response status ${resp.status} downloading avatar")
                return null
            }
            val encryptedAvatar = resp.bytes
            decryptBytes(profileKey, encryptedAvatar)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download user avatar", e)
            null
        }
    }

    private fun cacheError(aci: String, err: Exception) {
        Log.e(TAG, err.message ?: "Profile error for $aci")
        errorCache[aci] = err
        lastFetched[aci] = System.currentTimeMillis()
    }

    private fun decryptBytes(profileKey: ByteArray, encryptedData: ByteArray): ByteArray? {
        return try {
            if (encryptedData.size < NONCE_LENGTH + TAG_LENGTH_BYTES + 1) return null
            val nonce = encryptedData.copyOfRange(0, NONCE_LENGTH)
            val ciphertext = encryptedData.copyOfRange(NONCE_LENGTH, encryptedData.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(profileKey, "AES")
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt", e)
            null
        }
    }

    private fun decryptProfileString(profileKey: ByteArray, encryptedData: ByteArray): String? {
        val data = decryptBytes(profileKey, encryptedData) ?: return null
        var end = data.size
        while (end > 0 && data[end - 1] == 0.toByte()) end--
        return String(data, 0, end)
    }

    fun encryptString(profileKey: ByteArray, plaintext: String, paddedLength: Int): ByteArray? {
        if (plaintext.length > paddedLength) return null
        val padded = plaintext.toByteArray() + ByteArray(paddedLength - plaintext.length)
        val nonce = ByteArray(NONCE_LENGTH).also { java.security.SecureRandom().nextBytes(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(profileKey, "AES"), GCMParameterSpec(TAG_LENGTH_BYTES * 8, nonce))
            nonce + cipher.doFinal(padded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt string", e)
            null
        }
    }

    companion object {
        private const val TAG = "ProfileManager"
        private const val NONCE_LENGTH = 12
        private const val TAG_LENGTH_BYTES = 16
        const val DEFAULT_PROFILE_REFRESH_AFTER_MS = 60 * 60 * 1000L
    }
}
