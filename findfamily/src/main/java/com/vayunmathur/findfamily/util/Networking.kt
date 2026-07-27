package com.vayunmathur.findfamily.util
import android.util.Log
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeIdentity
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

object Networking {
    private const val URL = "https://findfamily.cc"
    private const val TAG = "FF-Networking"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Shared end-to-end-encryption identity (key generation/storage/crypto lives in :library:e2ee-p2p). */
    private lateinit var identity: E2eeIdentity
    private var network_is_down = false

    var userid = 0L
        private set

    private lateinit var userDao: UserDao
    private lateinit var dataStoreUtils: DataStoreUtils

    // init() is called from both the app UI (on launch) and the location
    // foreground service (which may start first). Guard it so only one coroutine
    // runs the identity/userid bootstrap at a time, and make it a no-op once done.
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    /** Adapts the app's encrypted DataStore to the e2ee module's storage abstraction. */
    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override suspend fun getBytes(name: String): ByteArray? = ds.getByteArrayAwait(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    suspend fun init(userDao: UserDao, dataStoreUtils: DataStoreUtils, meName: String) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            Networking.dataStoreUtils = dataStoreUtils
            Networking.userDao = userDao
            // Loads the persisted keypair (or generates+stores one on first launch) using the same
            // "publicKey"/"privateKey" datastore entries as before, so existing installs keep their key.
            // Uses suspend DataStore hydration (getByteArrayAwait) to avoid the cold-start race where
            // state.value is still emptyPreferences() → ephemeral identity → OAEP_DECODING_ERROR.
            identity = E2eeIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils))
            // Avoid negative IDs: server stores ULong but receive uses Long in request; generating
            // only positive IDs keeps both sides consistent and makes Base26 encoding stable.
            if (dataStoreUtils.getLongAwait("userid") == null) {
                dataStoreUtils.setLong("userid", Random.nextLong(from = 1, until = Long.MAX_VALUE), true)
            }
            userid = dataStoreUtils.getLongAwait("userid")!!

            if (userDao.getById(userid) == null) {
                userDao.upsert(
                    User(
                        meName,
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        userid,
                    )
                )
            }
            initialized = true
        }
    }

    private suspend fun <T> checkNetworkDown(makeRequest: suspend ()->T?): T? {
        try {
            val x = makeRequest()
            network_is_down = false
            return x
        } catch (e: CancellationException) {
            throw e
        } catch(e: Exception) {
            Log.w(TAG, "checkNetworkDown caught exception", e)
            network_is_down = true
        }
        return null
    }

    private suspend inline fun <reified T, reified I> makeRequest(path: String, body: I): T? {
        return checkNetworkDown {
            try {
                // Important: NetworkClient.callJson expects body:String/ByteArray to be sent raw.
                // If we pass a @Serializable object directly, HttpUrlEngine.toBodyBytes does
                // body.toString() → "Register(userid=...)" not JSON → server returns 400
                // "expected value at line 1 column 1" which is exactly what live logs showed.
                val encodedBody = when (body) {
                    is String -> body
                    else -> json.encodeToString(body)
                }
                NetworkClient.callJson<T>(
                    url = "$URL$path",
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = encodedBody
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "makeRequest $path failed", e)
                null
            }
        }
    }

    private suspend fun register(): Boolean {
        @Serializable
        data class Register(val userid: ULong, val key: String)
        val ok = makeRequest<Boolean, Register>("/api/register", Register(
            userid.toULong(),
            Base64.encode(identity.publicKeyPem)
        )
        ) ?: false
        Log.d(TAG, "register userid=${userid.toULong()} ok=$ok")
        return ok
    }

    suspend fun ensureUserExists() {
        val selfKey = getKey(userid)
        if(selfKey == null) {
            Log.w(TAG, "ensureUserExists: getKey failed for self ${userid.toULong()}, re-registering")
            register()
        } else {
            // Self-healing: if DataStore race previously registered an ephemeral key,
            // the server holds wrong key → peers encrypt to wrong pubkey → OAEP_DECODING_ERROR.
            // Detect mismatch and re-register correct key.
            val localB64 = Base64.encode(identity.publicKeyPem)
            val serverB64 = Base64.encode(selfKey)
            if (localB64 != serverB64) {
                Log.w(TAG, "ensureUserExists: server key mismatch for self ${userid.toULong()}, re-registering correct key localLen=${localB64.length} serverLen=${serverB64.length}")
                register()
            } else {
                Log.d(TAG, "ensureUserExists: self key exists and matches")
            }
        }
    }

    /** Fetches a peer's public key by id, returning its PEM bytes (or null if unknown/offline). */
    private suspend fun getKey(userid: Long): ByteArray? {
        val result = checkNetworkDown {
            val response = NetworkClient.performRequest(
                url = "$URL/api/getkey",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(GetKeyRequest(userid.toULong()))
            )
            Log.d(TAG, "getKey id=${userid.toULong()} status=${response.status}")
            if(response.status != 200) {
                return@checkNetworkDown null
            }
            return@checkNetworkDown Base64.decode(response.body)
        }
        if (result == null) Log.w(TAG, "getKey failed for ${userid.toULong()}")
        return result
    }

    /** Local platform tag included in outgoing heartbeat payloads so the peer learns we're on Android. */
    private const val PLATFORM = "android"

    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val keyPem = if (user.encryptionKey != null) {
            Base64.decode(user.encryptionKey)
        } else {
            getKey(user.id)?.also {
                userDao.upsert(user.copy(encryptionKey = Base64.encode(it)))
            }
        }
        if (keyPem == null) {
            Log.w(TAG, "publishLocation: no key for user ${user.id.toULong()} (${user.name})")
            return false
        }
        return try {
            val encrypted = encryptLocation(location, user.id, keyPem)
            val ok = makeRequest<Boolean, LocationSharingData>("/api/location/publish", encrypted) ?: false
            Log.d(TAG, "publishLocation to ${user.id.toULong()} (${user.name}) ok=$ok coord=${location.coord.lat},${location.coord.lon} encLen=${encrypted.encryptedLocation.length}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation to ${user.id.toULong()} failed with exception", e)
            false
        }
    }

    suspend fun publishLocation(location: LocationValue, user: TemporaryLink): Boolean {
        return try {
            val keyPem = Base64.decode(user.publicKey)
            val encrypted = encryptLocation(location, user.id, keyPem)
            val ok = makeRequest<Boolean, LocationSharingData>("/api/location/publish", encrypted) ?: false
            Log.d(TAG, "publishLocation to temp link ${user.id} ok=$ok encLen=${encrypted.encryptedLocation.length}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation temp link ${user.id} exception", e)
            false
        }
    }

    suspend fun receiveLocations(): List<LocationValue>? {
        // Use raw performRequest to correctly handle 204 No Content (empty queue) vs parsing error.
        val response = try {
            NetworkClient.performRequest(
                url = "$URL/api/location/receive",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(UserIdRequest(userid.toULong()))
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "receiveLocations: performRequest threw", e)
            return null
        }
        Log.d(TAG, "receiveLocations: status=${response.status} bodyLen=${response.body.length} for self ${userid.toULong()}")
        if (response.status == 204 || response.body.isBlank()) {
            Log.d(TAG, "receiveLocations: 204/empty, no pending")
            return emptyList()
        }
        if (response.status !in 200..299) {
            Log.w(TAG, "receiveLocations: server status ${response.status} body=${response.body.take(500)}")
            return null
        }
        val strings: List<String> = try {
            json.decodeFromString(response.body)
        } catch (e: Exception) {
            Log.w(TAG, "receiveLocations: failed to parse body ${response.body.take(500)}", e)
            return null
        }
        Log.d(TAG, "receiveLocations: got ${strings.size} raw blobs for self ${userid.toULong()}")
        // Per-message resilience: a single stale / undecryptable entry (e.g. encrypted to an old
        // rotated key) must not crash the heartbeat. Skip bad entries instead.
        var decryptFails = 0
        val decoded = strings.mapNotNull { enc ->
            runCatching { decryptLocation(enc) }.onFailure { e ->
                decryptFails++
                Log.w(TAG, "receiveLocations: decrypt failed (skipping) encLen=${enc.length}", e)
            }.getOrNull()
        }
        if (decryptFails > 0) Log.w(TAG, "receiveLocations: $decryptFails / ${strings.size} failed to decrypt")
        if (decoded.isNotEmpty()) {
            decoded.forEach { (loc, platform) ->
                if (platform != null) {
                    val u = userDao.getById(loc.userid)
                    if (u != null && u.platform != platform) {
                        userDao.upsert(u.copy(platform = platform))
                    }
                }
            }
        }
        Log.d(TAG, "receiveLocations: returning ${decoded.size} after decrypt")
        return decoded.map { it.first }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel
    //
    // Mirrors the location publish/receive flow but carries the small UWB
    // handshake envelopes (request / ack / config / cancel) end-to-end
    // encrypted. Each payload is at most a few hundred bytes; ranging samples
    // themselves never touch the server.
    // ----------------------------------------------------------------

    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val keyPem = if (recipient?.encryptionKey != null) {
            Base64.decode(recipient.encryptionKey)
        } else {
            getKey(recipientUserId) ?: return false
        }
        val str = json.encodeToString(envelope)
        // Use hybrid sealTo (AES+GCM payload + RSA-wrapped key) so we never hit the
        // 126-byte RSA-OAEP-SHA512 limit that silently broke larger UWB payloads.
        val encryptedData = Base64.encode(E2ee.sealTo(keyPem, str.encodeToByteArray()))
        // We can't reuse `makeRequest<Boolean, ...>` here because the server
        // returns `204 No Content` (no body) on success, and Ktor's content
        // negotiation throws `NoTransformationFoundException` trying to
        // deserialize an empty body into a `Boolean`. `performRequest` returns
        // the raw response and we treat any 2xx status as success.
        val bodyJson = json.encodeToString(LocationSharingData(recipientUserId.toULong(), encryptedData))
        return checkNetworkDown {
            val resp = NetworkClient.performRequest(
                url = "$URL/api/uwb/publish",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = bodyJson
            )
            resp.status in 200..299
        } ?: false
    }

    /**
     * Drains incoming UWB envelopes addressed to this user. The server queue
     * is cleared on receive (same semantics as `/api/location/receive`).
     * Supports both hybrid (sealTo) and legacy direct RSA for backward compat.
     */
    suspend fun receiveUwbMessages(): List<UwbEnvelope>? {
        val strings: List<String>? = makeRequest("/api/uwb/receive", json.encodeToString(UserIdRequest(userid.toULong())))
        return strings?.mapNotNull { b64 ->
            runCatching {
                val raw = Base64.decode(b64)
                // Try hybrid unseal first (new), fallback to direct decrypt (old peers).
                val plainBytes = runCatching { identity.unseal(raw) }.getOrElse { identity.decrypt(raw) }
                json.decodeFromString<UwbEnvelope>(plainBytes.decodeToString())
            }.getOrNull()
        }
    }

    private suspend fun encryptLocation(location: LocationValue, recipientUserID: Long, keyPem: ByteArray): LocationSharingData {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        // Hybrid encryption: avoids 126-byte RSA-OAEP limit; location JSON is ~150-250 bytes.
        val encryptedData = Base64.encode(E2ee.sealTo(keyPem, str.encodeToByteArray()))
        return LocationSharingData(recipientUserID.toULong(), encryptedData)
    }

    private suspend fun decryptLocation(encryptedLocation: String): Pair<LocationValue, String?> {
        val raw = Base64.decode(encryptedLocation)
        // Backward compat: try unseal (new encrypted form) then direct decrypt (legacy peers).
        val decryptedData = runCatching { identity.unseal(raw).decodeToString() }
            .getOrElse { identity.decrypt(raw).decodeToString() }
        val compat = json.decodeFromString<LocationValueCompatible>(decryptedData)
        return compat.toLocationValue() to compat.senderPlatform
    }

    /** Generates a fresh keypair (used for anonymous temporary share links). PEM bytes. */
    suspend fun generateKeyPair(): E2ee.KeyPairPem = E2ee.generateKeyPair()

    /**
     * Computes the verification "security code" for a connection: a fingerprint of *both* this
     * device's and [user]'s public keys. Identical on both peers' devices; comparing them confirms
     * the end-to-end channel isn't being intercepted. Returns null if the peer's key isn't known yet.
     */
    suspend fun securityCode(user: User): String? {
        val theirPem = peerPublicKeyPem(user) ?: return null
        return runCatching { E2ee.securityCode(identity.publicKeyPem, theirPem) }.getOrNull()
    }

    /** The peer's public key as PEM bytes — from the cached [User.encryptionKey] or fetched by id. */
    private suspend fun peerPublicKeyPem(user: User): ByteArray? {
        user.encryptionKey?.let { return Base64.decode(it) }
        val pem = getKey(user.id) ?: return null
        userDao.upsert(user.copy(encryptionKey = Base64.encode(pem)))
        return pem
    }

    @Serializable
    private data class LocationSharingData(val recipientUserID: ULong, val encryptedLocation: String)

    /** Unified to ULong to match server storage from register (toULong). Prevents negative-ID mismatch. */
    @Serializable
    private data class UserIdRequest(val userid: ULong)

    @Serializable
    private data class GetKeyRequest(val userid: ULong)
}
