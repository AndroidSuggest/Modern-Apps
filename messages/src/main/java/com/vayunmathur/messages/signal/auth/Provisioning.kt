package com.vayunmathur.messages.signal.auth

import android.app.Application
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.vayunmathur.messages.signal.proto.ProvisioningProtos
import com.vayunmathur.messages.signal.proto.SignalServiceProtos
import com.vayunmathur.messages.signal.store.SignalDatabase
import com.vayunmathur.messages.signal.store.SignalDeviceData
import com.vayunmathur.messages.signal.store.SignalPreKeyStore
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.messages.signal.web.SignalWebSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.io.IOException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Provisioning {
    private const val TAG = "Provisioning"
    private const val WS_PROVISIONING_URL = "wss://chat.signal.org/v1/websocket/provisioning/"
    // How long to wait for the websocket to connect before giving up.
    private const val CONNECT_TIMEOUT_MS = 30_000L
    // How long to wait for the primary device to scan the QR and the server to
    // deliver the encrypted provisioning message. Signal's provisioning session
    // expires server-side, so we surface a refreshable error rather than hang.
    private const val SCAN_TIMEOUT_MS = 90_000L

    sealed interface ProvisioningEvent {
        data class QrUrl(val url: String) : ProvisioningEvent
        data class Success(val deviceData: SignalDeviceData) : ProvisioningEvent
        data class Error(val message: String) : ProvisioningEvent
    }

    // Must match Signal Desktop's link capabilities. The server refuses to add a linked
    // device that would downgrade a capability the account already has enabled across its
    // devices, returning a bare 409 Conflict. Desktop sends all three of these on
    // /v1/devices/link (ts/textsecure/WebAPI.preload.ts -> linkDevice). signalmeow omits
    // usernameChangeSyncMessage, which is why matching the bridge alone never linked.
    val signalCapabilities = JSONObject().apply {
        put("attachmentBackfill", true)
        put("spqr", true)
        put("usernameChangeSyncMessage", true)
    }

    fun startProvisioning(context: Application, allowBackup: Boolean = true, deviceName: String = "Android"): Flow<ProvisioningEvent> = channelFlow {
        val ws = SignalWebSocket(context)
        try {
            val cipher = ProvisioningCipher()
            val requests = Channel<com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketRequestMessage>(Channel.UNLIMITED)
            ws.incomingRequestHandler = { requests.trySend(it) }

            ws.connect(WS_PROVISIONING_URL, autoReconnect = false)
            Log.d(TAG, "Connecting to provisioning websocket…")
            val connectEvent = withTimeout(CONNECT_TIMEOUT_MS) {
                ws.connectionEvents.first {
                    it is SignalWebSocket.ConnectionEvent.Connected ||
                        it is SignalWebSocket.ConnectionEvent.Disconnected ||
                        it is SignalWebSocket.ConnectionEvent.Error ||
                        it is SignalWebSocket.ConnectionEvent.FatalError ||
                        it is SignalWebSocket.ConnectionEvent.LoggedOut
                }
            }
            if (connectEvent !is SignalWebSocket.ConnectionEvent.Connected) {
                throw IOException("Provisioning websocket failed to connect: $connectEvent")
            }
            Log.d(TAG, "Provisioning websocket connected")

            // If the socket drops at any point while we're waiting, close the request
            // channel so the pending receive() throws instead of hanging forever.
            val disconnectWatcher = launch {
                val ev = ws.connectionEvents.first {
                    it is SignalWebSocket.ConnectionEvent.Disconnected ||
                        it is SignalWebSocket.ConnectionEvent.Error ||
                        it is SignalWebSocket.ConnectionEvent.FatalError ||
                        it is SignalWebSocket.ConnectionEvent.LoggedOut
                }
                Log.w(TAG, "Provisioning websocket closed while waiting: $ev")
                requests.close(IOException("Provisioning websocket closed: $ev"))
            }

            // Step 1: Receive provisioning UUID via PUT /v1/address
            val addressReq = withTimeout(CONNECT_TIMEOUT_MS) { requests.receive() }
            if (addressReq.verb != "PUT" || addressReq.path != "/v1/address") {
                throw IOException("Expected PUT /v1/address, got ${addressReq.verb} ${addressReq.path}")
            }
            val provAddress = ProvisioningProtos.ProvisioningAddress.parseFrom(addressReq.body)
            ws.sendResponse(addressReq.id, 200)
            Log.d(TAG, "Got provisioning address: ${provAddress.address}")

            // Step 2: Emit QR code URL
            val pubKeyBase64 = Base64.encodeToString(cipher.getPublicKey().serialize(), Base64.NO_WRAP)
            val capabilitiesPart = if (allowBackup) "&capabilities=${Uri.encode("backup4,backup5")}" else ""
            val qrUrl = "sgnl://linkdevice?uuid=${Uri.encode(provAddress.address)}&pub_key=${Uri.encode(pubKeyBase64)}$capabilitiesPart"
            send(ProvisioningEvent.QrUrl(qrUrl))
            Log.d(TAG, "QR URL generated: $qrUrl")

            // Step 3: Receive encrypted provisioning message via PUT /v1/message
            Log.d(TAG, "Waiting for primary device to scan QR (timeout ${SCAN_TIMEOUT_MS}ms)…")
            val msgReq = withTimeout(SCAN_TIMEOUT_MS) { requests.receive() }
            disconnectWatcher.cancel()
            if (msgReq.verb != "PUT" || msgReq.path != "/v1/message") {
                throw IOException("Expected PUT /v1/message, got ${msgReq.verb} ${msgReq.path}")
            }
            Log.d(TAG, "Got provision envelope: ${msgReq.body.size()} bytes")
            val envelope = ProvisioningProtos.ProvisionEnvelope.parseFrom(msgReq.body)
            Log.d(TAG, "Envelope publicKey: ${envelope.publicKey.size()} bytes, body: ${envelope.body.size()} bytes")
            ws.sendResponse(msgReq.id, 200)
            ws.disconnect()

            // Step 4: Decrypt provisioning message
            val provMsg = cipher.decrypt(envelope)

            // Ensure HTTP client is initialized
            SignalHttpClient.init(context)

            // Step 5: Extract identity key pairs
            val aciPublicKey = ECPublicKey(provMsg.aciIdentityKeyPublic.toByteArray())
            val aciPrivateKey = ECPrivateKey(provMsg.aciIdentityKeyPrivate.toByteArray())
            val aciIdentityKeyPair = IdentityKeyPair(IdentityKey(aciPublicKey), aciPrivateKey)

            val pniPublicKey = ECPublicKey(provMsg.pniIdentityKeyPublic.toByteArray())
            val pniPrivateKey = ECPrivateKey(provMsg.pniIdentityKeyPrivate.toByteArray())
            val pniIdentityKeyPair = IdentityKeyPair(IdentityKey(pniPublicKey), pniPrivateKey)

            val number = provMsg.number
            val provisioningCode = provMsg.provisioningCode

            // Step 6: Generate credentials
            val random = SecureRandom()
            val alphanumeric = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val password = (1..22).map { alphanumeric[random.nextInt(alphanumeric.length)] }.joinToString("")
            val aciRegistrationId = random.nextInt(16383) + 1
            val pniRegistrationId = random.nextInt(16383) + 1

            // Step 7: Generate signed pre-keys and Kyber last-resort pre-keys
            val aciSignedPreKey = generateSignedPreKey(1, aciIdentityKeyPair)
            val pniSignedPreKey = generateSignedPreKey(1, pniIdentityKeyPair)
            val aciPqLastResort = generateKyberPreKey(1, aciIdentityKeyPair)
            val pniPqLastResort = generateKyberPreKey(1, pniIdentityKeyPair)

            // Store generated keys in per-service protocol stores. ACI and PNI each keep their own
            // key-id namespace (matching signalmeow), so both signed pre-keys live at id=1 in their
            // respective stores instead of relying on the old +1000000 id offset.
            val database = SignalDatabase.getInstance(context)
            database.sessionDao().deleteAllSessions()
            database.preKeyDao().deleteAllServices()
            database.signedPreKeyDao().deleteAllServices()
            database.kyberPreKeyDao().deleteAllServices()
            val aciPreKeyStore = SignalPreKeyStore(database, SignalPreKeyStore.SERVICE_ACI)
            val pniPreKeyStore = SignalPreKeyStore(database, SignalPreKeyStore.SERVICE_PNI)
            aciPreKeyStore.storeSignedPreKey(aciSignedPreKey.id, aciSignedPreKey)
            pniPreKeyStore.storeSignedPreKey(pniSignedPreKey.id, pniSignedPreKey)
            aciPreKeyStore.storeLastResortKyberPreKey(aciPqLastResort.id, aciPqLastResort)
            pniPreKeyStore.storeLastResortKyberPreKey(pniPqLastResort.id, pniPqLastResort)

            // Step 8: Encrypt device name
            val encryptedName = encryptDeviceName(deviceName, aciIdentityKeyPair.publicKey.publicKey)

            // Step 9: Build and send device confirmation
            val payload = JSONObject().apply {
                put("verificationCode", provisioningCode)
                put("accountAttributes", JSONObject().apply {
                    put("fetchesMessages", true)
                    put("name", encryptedName)
                    put("registrationId", aciRegistrationId)
                    put("pniRegistrationId", pniRegistrationId)
                    put("capabilities", signalCapabilities)
                })
                put("aciSignedPreKey", signedPreKeyToJson(aciSignedPreKey))
                put("pniSignedPreKey", signedPreKeyToJson(pniSignedPreKey))
                put("aciPqLastResortPreKey", kyberPreKeyToJson(aciPqLastResort))
                put("pniPqLastResortPreKey", kyberPreKeyToJson(pniPqLastResort))
            }

            // Send the device-link confirmation over the websocket, matching the bridge,
            // which sends /v1/devices/link over /v1/websocket/ rather than plain HTTPS.
            // The connection itself is unauthenticated (no creds in the URL); auth is
            // carried in the request headers, exactly like signalmeow's confirmDevice.
            val basicAuth = Base64.encodeToString("$number:$password".toByteArray(), Base64.NO_WRAP)
            val linkWs = SignalWebSocket(context)
            linkWs.incomingRequestHandler = { /* ignore server keepalive requests during linking */ }
            linkWs.connect("wss://${SignalHttpClient.API_HOST}/v1/websocket/", autoReconnect = false)
            val linkResp: com.vayunmathur.messages.signal.proto.WebSocketProtos.WebSocketResponseMessage = try {
                val linkConnect = withTimeout(CONNECT_TIMEOUT_MS) {
                    linkWs.connectionEvents.first {
                        it is SignalWebSocket.ConnectionEvent.Connected ||
                            it is SignalWebSocket.ConnectionEvent.Disconnected ||
                            it is SignalWebSocket.ConnectionEvent.Error ||
                            it is SignalWebSocket.ConnectionEvent.FatalError ||
                            it is SignalWebSocket.ConnectionEvent.LoggedOut
                    }
                }
                if (linkConnect !is SignalWebSocket.ConnectionEvent.Connected) {
                    throw IOException("Link websocket failed to connect: $linkConnect")
                }
                withTimeout(CONNECT_TIMEOUT_MS) {
                    linkWs.sendRequest(
                        method = "PUT",
                        path = "/v1/devices/link",
                        body = payload.toString().toByteArray(),
                        headers = mapOf(
                            "content-type" to "application/json; charset=utf-8",
                            "authorization" to "Basic $basicAuth",
                        ),
                    )
                }
            } finally {
                linkWs.disconnect()
            }
            val respBodyStr = linkResp.body?.toByteArray()?.decodeToString()
            if (linkResp.status < 200 || linkResp.status >= 300) {
                Log.e(TAG, "Device link failed: ${linkResp.status} ${linkResp.message} headers=${linkResp.headersList} body=$respBodyStr")
                throw IOException("Device link failed: ${linkResp.status} ${linkResp.message} body=$respBodyStr")
            }

            val respJson = JSONObject(respBodyStr ?: throw IOException("Empty response"))
            val aci = respJson.getString("uuid")
            val pni = respJson.optString("pni", "")
            val rawDeviceId = respJson.optInt("deviceId", 0)
            val deviceId = if (rawDeviceId != 0) rawDeviceId else 1

            // Store identity keys
            val identityKeyStore = com.vayunmathur.messages.signal.store.SignalIdentityKeyStore(
                database,
                Base64.encodeToString(aciIdentityKeyPair.serialize(), Base64.NO_WRAP),
                aciRegistrationId,
            )
            identityKeyStore.saveIdentity(
                SignalProtocolAddress(aci, deviceId),
                aciIdentityKeyPair.publicKey,
            )
            identityKeyStore.saveIdentity(
                SignalProtocolAddress(pni, deviceId),
                pniIdentityKeyPair.publicKey,
            )

            // Store own profile key as recipient (with PNI and E164, matching Go reference)
            val recipientStore = com.vayunmathur.messages.signal.store.SignalRecipientStore(database)
            recipientStore.storeRecipient(com.vayunmathur.messages.signal.store.SignalRecipientEntity(
                aci = aci,
                pni = pni,
                e164 = number,
                profileKey = provMsg.profileKey?.toByteArray(),
            ))

            // Step 10: Build and emit device data
            val accountEntropyPool = if (provMsg.hasAccountEntropyPool()) provMsg.accountEntropyPool else null

            // Extract backup keys from provisioning message (matches Go reference)
            val ephemeralBackupKey = if (provMsg.hasEphemeralBackupKey() && provMsg.ephemeralBackupKey.size() > 0)
                Base64.encodeToString(provMsg.ephemeralBackupKey.toByteArray(), Base64.NO_WRAP)
            else null
            val mediaRootBackupKey = if (provMsg.hasMediaRootBackupKey() && provMsg.mediaRootBackupKey.size() > 0)
                Base64.encodeToString(provMsg.mediaRootBackupKey.toByteArray(), Base64.NO_WRAP)
            else null

            // Derive master key from account entropy pool (matches Go reference)
            val derivedMasterKey = if (accountEntropyPool != null) {
                try {
                    deriveMasterKeyFromAEP(accountEntropyPool)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to derive master key from account entropy pool", e)
                    null
                }
            } else {
                Log.w(TAG, "No account entropy pool in provisioning message")
                null
            }

            val deviceData = SignalDeviceData(
                aci = aci,
                pni = pni,
                deviceId = deviceId,
                number = number,
                password = password,
                aciIdentityKeyPair = Base64.encodeToString(aciIdentityKeyPair.serialize(), Base64.NO_WRAP),
                pniIdentityKeyPair = Base64.encodeToString(pniIdentityKeyPair.serialize(), Base64.NO_WRAP),
                aciRegistrationId = aciRegistrationId,
                pniRegistrationId = pniRegistrationId,
                accountEntropyPool = accountEntropyPool,
                masterKey = derivedMasterKey?.let { Base64.encodeToString(it, Base64.NO_WRAP) },
                ephemeralBackupKey = ephemeralBackupKey,
                mediaRootBackupKey = mediaRootBackupKey,
            )
            send(ProvisioningEvent.Success(deviceData))
            Log.d(TAG, "Provisioning complete, deviceId=$deviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Provisioning failed", e)
            send(ProvisioningEvent.Error(e.message ?: "Unknown error"))
        } finally {
            ws.disconnect()
        }
    }

    private fun generateSignedPreKey(id: Int, identityKeyPair: IdentityKeyPair): SignedPreKeyRecord {
        val keyPair = ECKeyPair.generate()
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return SignedPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    private fun generateKyberPreKey(id: Int, identityKeyPair: IdentityKeyPair): KyberPreKeyRecord {
        val keyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val signature = identityKeyPair.privateKey.calculateSignature(keyPair.publicKey.serialize())
        return KyberPreKeyRecord(id, System.currentTimeMillis(), keyPair, signature)
    }

    private fun signedPreKeyToJson(record: SignedPreKeyRecord) = JSONObject().apply {
        put("keyId", record.id)
        put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
        put("signature", Base64.encodeToString(record.signature, Base64.NO_WRAP))
    }

    private fun kyberPreKeyToJson(record: KyberPreKeyRecord) = JSONObject().apply {
        put("keyId", record.id)
        put("publicKey", Base64.encodeToString(record.keyPair.publicKey.serialize(), Base64.NO_WRAP))
        put("signature", Base64.encodeToString(record.signature, Base64.NO_WRAP))
    }

    private fun deriveMasterKeyFromAEP(aep: String): ByteArray? {
        return try {
            org.signal.libsignal.messagebackup.AccountEntropyPool.deriveSvrKey(aep)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to derive SVR key from account entropy pool", e)
            null
        }
    }

    suspend fun unlink(ws: SignalWebSocket, deviceId: Int) {
        val response = ws.sendRequest("DELETE", "/v1/devices/$deviceId")
        if (response.status < 200 || response.status >= 300) {
            throw IOException("Failed to unlink device: status=${response.status}")
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private fun encryptDeviceName(name: String, identityPublicKey: ECPublicKey): String {
        val ephemeral = ECKeyPair.generate()
        val masterSecret = ephemeral.privateKey.calculateAgreement(identityPublicKey)

        val syntheticIvKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(masterSecret, "HmacSHA256"))
            doFinal("auth".toByteArray())
        }

        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val syntheticIv = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(syntheticIvKey, "HmacSHA256"))
            doFinal(nameBytes)
        }.copyOfRange(0, 16)

        val cipherKeyBase = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(masterSecret, "HmacSHA256"))
            doFinal("cipher".toByteArray())
        }
        val cipherKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(cipherKeyBase, "HmacSHA256"))
            doFinal(syntheticIv)
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cipherKey, "AES"), IvParameterSpec(ByteArray(16)))
        val ciphertext = cipher.doFinal(nameBytes)

        val deviceName = com.vayunmathur.messages.signal.proto.DeviceNameProtos.DeviceName.newBuilder()
            .setEphemeralPublic(com.google.protobuf.ByteString.copyFrom(ephemeral.publicKey.serialize()))
            .setSyntheticIv(com.google.protobuf.ByteString.copyFrom(syntheticIv))
            .setCiphertext(com.google.protobuf.ByteString.copyFrom(ciphertext))
            .build()

        return Base64.encodeToString(deviceName.toByteArray(), Base64.NO_WRAP)
    }
}
