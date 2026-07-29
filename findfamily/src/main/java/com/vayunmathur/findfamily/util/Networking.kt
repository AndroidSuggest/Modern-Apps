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
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
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
    /**
     * Post-quantum identity — mirrors Office's `PqcIdentity.loadOrCreate` pattern.
     * Reuses the exact same library (`e2ee-p2p`) and store abstraction, but uses
     * a distinct DataStore prefix `ff_pqc` so FindFamily PQC keys don't collide
     * with Office's `office` prefix nor with classic RSA keys (`publicKey`/`privateKey`).
     *
     * Bundle format is identical to Office: [4B kemLen BE][kemPubDer][dsaPubDer],
     * encrypt layout [4B encapLen][encap][aesGCM] with SP800-56A KDF SHA256(BE32(1)||Z),
     * so iOS must implement the same framing to interoperate.
     */
    private lateinit var pqcIdentity: PqcIdentity
    @Volatile private var pqcReady = false
    @Volatile private var pqcInitAttempted = false
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
            // Loads the persisted RSA keypair (or generates+stores one on first launch) using the same
            // "publicKey"/"privateKey" datastore entries as before, so existing installs keep their key.
            // Uses suspend DataStore hydration (getByteArrayAwait) to avoid the cold-start race where
            // state.value is still emptyPreferences() → ephemeral identity → OAEP_DECODING_ERROR.
            identity = E2eeIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils))
            // PQC identity — same library as Office, distinct prefix so FF PQC keys are independent.
            // Wrapped in try/catch so devices where libe2ee_pqc.so fails to load still work with RSA only.
            if (!pqcInitAttempted) {
                pqcInitAttempted = true
                try {
                    pqcIdentity = PqcIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils), "ff_pqc")
                    pqcReady = true
                    Log.d(TAG, "PQC identity ready bundleLen=${pqcIdentity.publicBundle.size}")
                } catch (e: Throwable) {
                    Log.w(TAG, "PQC identity unavailable (native lib load failed), falling back to RSA only", e)
                    pqcReady = false
                }
            }
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

    private suspend fun registerPqc(): Boolean {
        if (!pqcReady) return false
        @Serializable
        data class Register(val userid: ULong, val key: String)
        val ok = makeRequest<Boolean, Register>("/api/pqc/register", Register(
            userid.toULong(),
            Base64.encode(pqcIdentity.publicBundle)
        )
        ) ?: false
        Log.d(TAG, "registerPqc userid=${userid.toULong()} ok=$ok bundleLen=${pqcIdentity.publicBundle.size}")
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
        // PQC self-healing: same race detection for the PQC bundle.
        if (pqcReady) {
            val selfPqcKey = getPqcKey(userid)
            if (selfPqcKey == null) {
                Log.w(TAG, "ensureUserExists: PQC getKey failed for self ${userid.toULong()}, re-registering PQC")
                registerPqc()
            } else {
                val localB64 = Base64.encode(pqcIdentity.publicBundle)
                val serverB64 = Base64.encode(selfPqcKey)
                if (localB64 != serverB64) {
                    Log.w(TAG, "ensureUserExists: PQC server mismatch self ${userid.toULong()}, re-registering len local=${localB64.length} server=${serverB64.length}")
                    registerPqc()
                } else {
                    Log.d(TAG, "ensureUserExists: PQC self key matches")
                }
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

    /** Fetches a peer's PQC public bundle by id, returning its raw bundle bytes (or null). Same key normalization as classic. */
    private suspend fun getPqcKey(userid: Long): ByteArray? {
        if (!pqcReady) return null
        val result = checkNetworkDown {
            val response = NetworkClient.performRequest(
                url = "$URL/api/pqc/getkey",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(GetKeyRequest(userid.toULong()))
            )
            Log.d(TAG, "getPqcKey id=${userid.toULong()} status=${response.status}")
            if (response.status != 200) return@checkNetworkDown null
            return@checkNetworkDown Base64.decode(response.body)
        }
        // Don't spam warn for users who haven't upgraded to PQC yet.
        if (result == null) Log.d(TAG, "getPqcKey: no PQC key for ${userid.toULong()} (peer may not support PQC yet)")
        return result
    }

    /** Local platform tag included in outgoing heartbeat payloads so the peer learns we're on Android. */
    private const val PLATFORM = "android"

    /**
     * Publish location to a connected user.
     * PQC routing: if the peer has a PQC bundle (cached `User.pqcEncryptionKey` or fetched
     * from `/api/pqc/getkey`), encrypt + publish ONLY to the higher PQC endpoint
     * (`/api/location/publish_pqc`). Otherwise fall back to classic RSA endpoint.
     */
    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        // Fast-path: check PQC capability first. The peer's PQC bundle is stored in
        // `pqcEncryptionKey` (base64 bundle). If present, we skip classic entirely.
        val pqcBundle = peerPqcBundle(user)
        if (pqcBundle != null) {
            return try {
                val encrypted = encryptLocationPqc(location, user.id, pqcBundle)
                val ok = makeRequest<Boolean, LocationSharingData>("/api/location/publish_pqc", encrypted) ?: false
                Log.d(TAG, "publishLocation PQC to ${user.id.toULong()} (${user.name}) ok=$ok encLen=${encrypted.encryptedLocation.length}")
                ok
            } catch (e: Exception) {
                Log.w(TAG, "publishLocation PQC to ${user.id.toULong()} exception, falling back to RSA", e)
                // Fallback to RSA on PQC encrypt failure.
                publishLocationClassic(location, user)
            }
        }
        return publishLocationClassic(location, user)
    }

    private suspend fun publishLocationClassic(location: LocationValue, user: User): Boolean {
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
            Log.d(TAG, "publishLocation RSA to ${user.id.toULong()} (${user.name}) ok=$ok encLen=${encrypted.encryptedLocation.length}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation RSA to ${user.id.toULong()} exception", e)
            false
        }
    }

    suspend fun publishLocation(location: LocationValue, user: TemporaryLink): Boolean {
        // TemporaryLink PQC: if pqcPublicKey present, publish to PQC endpoint only.
        if (user.pqcPublicKey != null) {
            return try {
                val bundle = Base64.decode(user.pqcPublicKey)
                val encrypted = encryptLocationPqc(location, user.id, bundle)
                val ok = makeRequest<Boolean, LocationSharingData>("/api/location/publish_pqc", encrypted) ?: false
                Log.d(TAG, "publishLocation PQC to temp link ${user.id} ok=$ok encLen=${encrypted.encryptedLocation.length}")
                ok
            } catch (e: Exception) {
                Log.w(TAG, "publishLocation PQC temp link ${user.id} exception, fallback RSA", e)
                publishLocationClassic(location, user)
            }
        }
        return publishLocationClassic(location, user)
    }

    private suspend fun publishLocationClassic(location: LocationValue, link: TemporaryLink): Boolean {
        return try {
            val keyPem = Base64.decode(link.publicKey)
            val encrypted = encryptLocation(location, link.id, keyPem)
            val ok = makeRequest<Boolean, LocationSharingData>("/api/location/publish", encrypted) ?: false
            Log.d(TAG, "publishLocation RSA to temp link ${link.id} ok=$ok encLen=${encrypted.encryptedLocation.length}")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "publishLocation RSA temp link ${link.id} exception", e)
            false
        }
    }

    /**
     * Receives locations from both classic and PQC queues and merges them.
     * Classic queue may be empty when all peers upgraded to PQC; PQC queue is drained
     * here too for backward compat during rollout.
     */
    suspend fun receiveLocations(): List<LocationValue>? {
        val classic = receiveLocationsClassic()
        val pqc = receiveLocationsPqc()
        // One null means network failure — if either failed we can't tell whether the other
        // is authoritative empty or partial. Return classic if pqc is null and vice versa,
        // but if classic is null return pqc (or null if both). This keeps heartbeat alive.
        if (classic == null && pqc == null) return null
        val merged = (classic ?: emptyList()) + (pqc ?: emptyList())
        Log.d(TAG, "receiveLocations merged total=${merged.size} classic=${classic?.size} pqc=${pqc?.size}")
        return merged
    }

    private suspend fun receiveLocationsClassic(): List<LocationValue>? {
        // Use raw performRequest to correctly handle 204 No Content (empty queue) vs parsing error.
        val response = try {
            NetworkClient.performRequest(
                url = "$URL/api/location/receive",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(UserIdRequest(userid.toULong()))
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "receiveLocationsClassic: performRequest threw", e)
            return null
        }
        Log.d(TAG, "receiveLocationsClassic: status=${response.status} bodyLen=${response.body.length} for self ${userid.toULong()}")
        if (response.status == 204 || response.body.isBlank()) {
            Log.d(TAG, "receiveLocationsClassic: 204/empty")
            return emptyList()
        }
        if (response.status !in 200..299) {
            Log.w(TAG, "receiveLocationsClassic: server status ${response.status} body=${response.body.take(500)}")
            return null
        }
        val strings: List<String> = try {
            json.decodeFromString(response.body)
        } catch (e: Exception) {
            Log.w(TAG, "receiveLocationsClassic: parse fail ${response.body.take(500)}", e)
            return null
        }
        Log.d(TAG, "receiveLocationsClassic: got ${strings.size} raw blobs")
        var decryptFails = 0
        val decoded = strings.mapNotNull { enc ->
            runCatching { decryptLocation(enc) }.onFailure { e ->
                decryptFails++
                Log.w(TAG, "receiveLocationsClassic: decrypt fail encLen=${enc.length}", e)
            }.getOrNull()
        }
        if (decryptFails > 0) Log.w(TAG, "receiveLocationsClassic: $decryptFails / ${strings.size} decrypt fail")
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
        Log.d(TAG, "receiveLocationsClassic: returning ${decoded.size}")
        return decoded.map { it.first }
    }

    private suspend fun receiveLocationsPqc(): List<LocationValue>? {
        if (!pqcReady) return emptyList()
        val response = try {
            NetworkClient.performRequest(
                url = "$URL/api/location/receive_pqc",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(UserIdRequest(userid.toULong()))
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Log.w(TAG, "receiveLocationsPqc: performRequest threw", e)
            return null
        }
        Log.d(TAG, "receiveLocationsPqc: status=${response.status} bodyLen=${response.body.length} for self ${userid.toULong()}")
        if (response.status == 204 || response.body.isBlank()) return emptyList()
        if (response.status !in 200..299) {
            Log.w(TAG, "receiveLocationsPqc: server status ${response.status} body=${response.body.take(500)}")
            return null
        }
        val strings: List<String> = try {
            json.decodeFromString(response.body)
        } catch (e: Exception) {
            Log.w(TAG, "receiveLocationsPqc: parse fail", e)
            return null
        }
        Log.d(TAG, "receiveLocationsPqc: got ${strings.size} raw blobs")
        var fails = 0
        val decoded = strings.mapNotNull { enc ->
            runCatching { decryptLocationPqc(enc) }.onFailure { e ->
                fails++
                Log.w(TAG, "receiveLocationsPqc: decrypt fail encLen=${enc.length}", e)
            }.getOrNull()
        }
        if (fails > 0) Log.w(TAG, "receiveLocationsPqc: $fails / ${strings.size} decrypt fail")
        decoded.forEach { (loc, platform) ->
            if (platform != null) {
                runCatching {
                    val u = userDao.getById(loc.userid)
                    if (u != null && u.platform != platform) userDao.upsert(u.copy(platform = platform))
                }
            }
        }
        Log.d(TAG, "receiveLocationsPqc: returning ${decoded.size}")
        return decoded.map { it.first }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel
    //
    // Mirrors the location publish/receive flow but carries the small UWB
    // handshake envelopes (request / ack / config / cancel) end-to-end
    // encrypted. Each payload is at most a few hundred bytes; ranging samples
    // themselves never touch the server. PQC routing: only bother publishing
    // to the higher PQC endpoint when peer supports it.
    // ----------------------------------------------------------------

    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        // Try PQC first if peer has bundle.
        val resolvedUser = recipient ?: userDao.getById(recipientUserId)
        val pqcBundle = resolvedUser?.let { peerPqcBundle(it) } ?: getPqcKey(recipientUserId)
        if (pqcBundle != null) {
            // Cache bundle if we just fetched.
            if (resolvedUser != null && resolvedUser.pqcEncryptionKey == null) {
                runCatching { userDao.upsert(resolvedUser.copy(pqcEncryptionKey = Base64.encode(pqcBundle))) }
            }
            return publishUwbMessagePqc(envelope, recipientUserId, pqcBundle)
        }
        return publishUwbMessageClassic(envelope, recipientUserId, resolvedUser)
    }

    private suspend fun publishUwbMessageClassic(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val keyPem = if (recipient?.encryptionKey != null) {
            Base64.decode(recipient.encryptionKey)
        } else {
            getKey(recipientUserId) ?: return false
        }
        val str = json.encodeToString(envelope)
        val encryptedData = Base64.encode(E2ee.sealTo(keyPem, str.encodeToByteArray()))
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

    private suspend fun publishUwbMessagePqc(envelope: UwbEnvelope, recipientUserId: Long, bundle: ByteArray): Boolean {
        return try {
            val str = json.encodeToString(envelope)
            val encryptedData = Base64.encode(Pqc.encryptTo(bundle, str.encodeToByteArray()))
            val bodyJson = json.encodeToString(LocationSharingData(recipientUserId.toULong(), encryptedData))
            checkNetworkDown {
                val resp = NetworkClient.performRequest(
                    url = "$URL/api/uwb/publish_pqc",
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = bodyJson
                )
                resp.status in 200..299
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "publishUwbMessagePqc to ${recipientUserId.toULong()} failed", e)
            false
        }
    }

    /**
     * Drains incoming UWB envelopes from both classic and PQC queues.
     * Supports both hybrid (sealTo) and legacy direct RSA for classic, and PQC bundle decrypt for PQC.
     */
    suspend fun receiveUwbMessages(): List<UwbEnvelope>? {
        val classic = receiveUwbMessagesClassic()
        val pqc = receiveUwbMessagesPqc()
        if (classic == null && pqc == null) return null
        return (classic ?: emptyList()) + (pqc ?: emptyList())
    }

    private suspend fun receiveUwbMessagesClassic(): List<UwbEnvelope>? {
        val strings: List<String>? = makeRequest("/api/uwb/receive", json.encodeToString(UserIdRequest(userid.toULong())))
        return strings?.mapNotNull { b64 ->
            runCatching {
                val raw = Base64.decode(b64)
                val plainBytes = runCatching { identity.unseal(raw) }.getOrElse { identity.decrypt(raw) }
                json.decodeFromString<UwbEnvelope>(plainBytes.decodeToString())
            }.getOrNull()
        }
    }

    private suspend fun receiveUwbMessagesPqc(): List<UwbEnvelope>? {
        if (!pqcReady) return emptyList()
        val strings: List<String>? = makeRequest("/api/uwb/receive_pqc", json.encodeToString(UserIdRequest(userid.toULong())))
        return strings?.mapNotNull { b64 ->
            runCatching {
                val raw = Base64.decode(b64)
                val plainBytes = pqcIdentity.decrypt(raw)
                json.decodeFromString<UwbEnvelope>(plainBytes.decodeToString())
            }.onFailure { e ->
                Log.w(TAG, "receiveUwbMessagesPqc: decrypt fail", e)
            }.getOrNull()
        }
    }

    // ----------------------------------------------------------------
    // Encryption helpers
    // ----------------------------------------------------------------

    private suspend fun encryptLocation(location: LocationValue, recipientUserID: Long, keyPem: ByteArray): LocationSharingData {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        // Hybrid encryption: avoids 126-byte RSA-OAEP limit; location JSON is ~150-250 bytes.
        val encryptedData = Base64.encode(E2ee.sealTo(keyPem, str.encodeToByteArray()))
        return LocationSharingData(recipientUserID.toULong(), encryptedData)
    }

    private fun encryptLocationPqc(location: LocationValue, recipientUserID: Long, bundle: ByteArray): LocationSharingData {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        // PQC hybrid: ML-KEM encapsulate → AES-256-GCM, layout [4B encapLen][encap][aes].
        val sealed = Pqc.encryptTo(bundle, str.encodeToByteArray())
        val encryptedData = Base64.encode(sealed)
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

    private fun decryptLocationPqc(encryptedLocation: String): Pair<LocationValue, String?> {
        val raw = Base64.decode(encryptedLocation)
        val plainBytes = pqcIdentity.decrypt(raw)
        val compat = json.decodeFromString<LocationValueCompatible>(plainBytes.decodeToString())
        return compat.toLocationValue() to compat.senderPlatform
    }

    /** Generates a fresh RSA keypair (used for anonymous temporary share links). PEM bytes. */
    suspend fun generateKeyPair(): E2ee.KeyPairPem = E2ee.generateKeyPair()

    /**
     * Generates a fresh PQC key bundle for an ephemeral temporary link.
     * Returns (publicBundleBase64, privateBundleBase64) where private bundle is
     * [4B kemPrivLen][kemPrivDer][dsaPrivDer] — DERs, BC-compatible, same KDF as Office.
     * This mirrors Office's generation but for anonymous links.
     */
    fun generatePqcKeyPair(): PqcLinkKeyPair {
        val (kemPub, kemPriv) = Pqc.generateKem()
        val (dsaPub, dsaPriv) = Pqc.generateDsa()
        val pubBundle = Pqc.bundle(kemPub, dsaPub)
        val privBundle = buildPrivBundle(kemPriv, dsaPriv)
        return PqcLinkKeyPair(
            publicBundleB64 = Base64.encode(pubBundle),
            privateBundleB64 = Base64.encode(privBundle)
        )
    }

    data class PqcLinkKeyPair(val publicBundleB64: String, val privateBundleB64: String)

    /** Private bundle layout: [4B kemPrivLen BE][kemPriv][dsaPriv] — mirrors public bundle. */
    private fun buildPrivBundle(kemPriv: ByteArray, dsaPriv: ByteArray): ByteArray {
        val out = ByteArray(4 + kemPriv.size + dsaPriv.size)
        out[0] = (kemPriv.size ushr 24).toByte()
        out[1] = (kemPriv.size ushr 16).toByte()
        out[2] = (kemPriv.size ushr 8).toByte()
        out[3] = kemPriv.size.toByte()
        kemPriv.copyInto(out, 4)
        dsaPriv.copyInto(out, 4 + kemPriv.size)
        return out
    }

    /**
     * Computes the verification "security code" for a connection: a fingerprint of *both* this
     * device's and [user]'s public keys. Identical on both peers' devices; comparing them confirms
     * the end-to-end channel isn't being intercepted. Returns null if the peer's key isn't known yet.
     * Uses RSA keys (existing behavior).
     */
    suspend fun securityCode(user: User): String? {
        val theirPem = peerPublicKeyPem(user) ?: return null
        return runCatching { E2ee.securityCode(identity.publicKeyPem, theirPem) }.getOrNull()
    }

    /** PQC safety number — same contract as classic but from PQC bundles. */
    fun securityCodePqc(user: User): String? {
        val theirBundle = user.pqcEncryptionKey?.let { Base64.decode(it) } ?: return null
        if (!pqcReady) return null
        return runCatching { Pqc.securityCode(pqcIdentity.publicBundle, theirBundle) }.getOrNull()
    }

    /** The peer's public key as PEM bytes — from the cached [User.encryptionKey] or fetched by id. */
    private suspend fun peerPublicKeyPem(user: User): ByteArray? {
        user.encryptionKey?.let { return Base64.decode(it) }
        val pem = getKey(user.id) ?: return null
        userDao.upsert(user.copy(encryptionKey = Base64.encode(pem)))
        return pem
    }

    /**
     * The peer's PQC public bundle — from cached `User.pqcEncryptionKey` (base64 bundle)
     * or fetched from `/api/pqc/getkey`. If fetched, caches it. This is the "higher"
     * endpoint check: non-null means peer supports PQC → caller should publish only to PQC.
     */
    private suspend fun peerPqcBundle(user: User): ByteArray? {
        if (!pqcReady) return null
        user.pqcEncryptionKey?.let { return Base64.decode(it) }
        val bundle = getPqcKey(user.id) ?: return null
        userDao.upsert(user.copy(pqcEncryptionKey = Base64.encode(bundle)))
        return bundle
    }

    /**
     * Whether a given [User] has a PQC key available (cached or fetchable). Used to decide
     * whether to skip classic endpoint.
     */
    suspend fun isPqcUser(user: User): Boolean {
        if (!pqcReady) return false
        if (user.pqcEncryptionKey != null) return true
        // Opportunistic fetch without caching twice (peerPqcBundle will cache if found).
        return getPqcKey(user.id) != null
    }

    @Serializable
    private data class LocationSharingData(val recipientUserID: ULong, val encryptedLocation: String)

    /** Unified to ULong to match server storage from register (toULong). Prevents negative-ID mismatch. */
    @Serializable
    private data class UserIdRequest(val userid: ULong)

    @Serializable
    private data class GetKeyRequest(val userid: ULong)
}
