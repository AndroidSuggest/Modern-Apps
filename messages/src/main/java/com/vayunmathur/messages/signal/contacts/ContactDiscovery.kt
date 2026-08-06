@file:OptIn(
    kotlin.uuid.ExperimentalUuidApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)

package com.vayunmathur.messages.signal.contacts

import kotlin.uuid.Uuid
import kotlin.concurrent.atomics.*
import android.util.Log
import com.vayunmathur.messages.signal.store.SignalRecipientEntity
import com.vayunmathur.messages.signal.store.SignalRecipientStore
import com.vayunmathur.messages.signal.web.CertPinning
import com.vayunmathur.messages.signal.web.SignalHttpClient
import com.vayunmathur.library.network.WebSocketClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.signal.libsignal.cds2.Cds2Client
import signalservice.ContactDiscovery.CDSClientRequest
import signalservice.ContactDiscovery.CDSClientResponse
import java.nio.ByteBuffer
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ContactDiscoveryRateLimitError(val retryAfterSeconds: Long) : Exception(
    "contact discovery rate limited for ${retryAfterSeconds}s"
)

data class CDSResponseEntry(val aci: Uuid, val pni: Uuid)

class ContactDiscovery(
    private val recipientStore: SignalRecipientStore,
    private val ws: com.vayunmathur.messages.signal.web.SignalWebSocket,
    private val context: android.app.Application,
) {
    private var cachedCdsiUsername: String? = null
    private var cachedCdsiPassword: String? = null
    private var cdsiAuthExpiry: Long = 0L
    private var cdsiToken: ByteArray? = null

    suspend fun lookupPhones(vararg e164s: Long): Map<Long, CDSResponseEntry>? {
        if (e164s.isEmpty()) return null
        val requestData = ByteBuffer.allocate(e164s.size * 8)
        for (e164 in e164s) requestData.putLong(e164)
        val (cdsiUsername, cdsiPassword) = getCdsiAuth() ?: return null
        val (resp, token) = performCdsiLookup(requestData.array(), cdsiUsername, cdsiPassword)
            ?: return null
        if (token != null) cdsiToken = token
        return resp
    }

    suspend fun resolveE164(e164: String): String? {
        val cached = recipientStore.getByE164(e164)
        if (cached != null) {
            // Handle legacy cached entries that may have <PNI:uuid> or <ACI:uuid> format
            val aci = cached.aci
                .removePrefix("<").removeSuffix(">")
                .let { 
                    if (it.startsWith("PNI:") || it.startsWith("ACI:")) it.substringAfter(":") else it
                }
            Log.d(TAG, "Resolved $e164 from local store: $aci")
            return aci
        }
        val e164Num = e164.removePrefix("+").toLong()
        val results = try {
            lookupPhones(e164Num)
        } catch (e: Exception) {
            Log.e(TAG, "CDSI lookup failed for $e164", e)
            null
        } ?: return null
        val entry = results[e164Num] ?: return null
        val nilUUID = Uuid.NIL
        val aci = entry.aci.takeIf { it != nilUUID }?.toString()
        val pni = entry.pni.takeIf { it != nilUUID }?.toString()
        if (aci != null) {
            recipientStore.storeRecipient(SignalRecipientEntity(aci = aci, e164 = e164))
            Log.d(TAG, "CDSI resolved $e164 -> ACI $aci")
            return aci
        } else if (pni != null) {
            val pniWithPrefix = "PNI:$pni"
            recipientStore.storeRecipient(SignalRecipientEntity(aci = pniWithPrefix, e164 = e164))
            Log.d(TAG, "CDSI resolved $e164 -> PNI $pni (no ACI)")
            return pniWithPrefix
        }
        return null
    }

    private suspend fun getCdsiAuth(): Pair<String, String>? {
        val now = System.currentTimeMillis()
        val username = cachedCdsiUsername
        val password = cachedCdsiPassword
        if (username != null && password != null && now < cdsiAuthExpiry) {
            return username to password
        }
        val authResponse = ws.sendRequest(
            "GET",
            "/v2/directory/auth",
        )
        if (authResponse.status !in 200..299) {
            Log.e(TAG, "Directory auth failed: ${authResponse.status}")
            return null
        }
        val authJson = JSONObject(authResponse.body.toStringUtf8())
        val newUsername = authJson.getString("username")
        val newPassword = authJson.getString("password")
        cachedCdsiUsername = newUsername
        cachedCdsiPassword = newPassword
        cdsiAuthExpiry = now + CDSI_AUTH_TTL_MS
        return newUsername to newPassword
    }

    private suspend fun performCdsiLookup(
        newE164sData: ByteArray,
        cdsiUsername: String,
        cdsiPassword: String,
    ): Pair<Map<Long, CDSResponseEntry>, ByteArray?>? {
        val url = "wss://$CDSI_HOST/v1/$MRENCLAVE/discovery"
        val sslSocketFactory = CertPinning.createSslSocketFactory(context).first

        // WebSocketClient.connect completes the handshake before returning, so a
        // non-101 upgrade throws here instead of arriving via onFailure.
        val socket = WebSocketClient.connect(
            url,
            mapOf("Authorization" to SignalHttpClient.basicCredentials(cdsiUsername, cdsiPassword)),
            sslSocketFactory = sslSocketFactory,
        )

        val messages = LinkedBlockingQueue<ByteArray>()
        // Terminal condition seen by the reader (rate-limit close or read error);
        // the exchange below surfaces it instead of waiting out its poll timeout.
        val failure = AtomicReference<Throwable?>(null)

        fun nextMessage(missing: String): ByteArray {
            failure.load()?.let { throw it }
            val msg = messages.poll(10, TimeUnit.SECONDS)
            failure.load()?.let { throw it }
            return msg ?: throw IllegalStateException(missing)
        }

        return try {
            coroutineScope {
                val reader = launch(Dispatchers.IO) {
                    try {
                        socket.incomingFlow().collect { frame ->
                            when (frame) {
                                is WebSocketClient.WsFrame.Binary -> messages.put(frame.bytes)
                                is WebSocketClient.WsFrame.Text ->
                                    messages.put(frame.text.toByteArray(Charsets.UTF_8))
                                is WebSocketClient.WsFrame.Close -> {
                                    if (frame.code == RATE_LIMIT_CLOSE_CODE) {
                                        val retryAfter = try {
                                            JSONObject(frame.reason).optLong("retry_after", 0)
                                        } catch (_: Exception) { 0L }
                                        failure.compareAndSet(
                                            null, ContactDiscoveryRateLimitError(retryAfter),
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "CDSI WebSocket failure", e)
                        failure.compareAndSet(null, e)
                    }
                }

                try {
                    // The exchange drives blocking queue polls, so keep it on IO.
                    withTimeout(20000) {
                        withContext(Dispatchers.IO) {
                            val attestationMsg = nextMessage("No attestation")
                            val mrenclave = MRENCLAVE.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                            val cds2Client = Cds2Client(mrenclave, attestationMsg, Instant.now())
                            val initialRequest = cds2Client.initialRequest()
                            socket.send(initialRequest)
                            val handshakeFinish = nextMessage("No handshake finish")
                            cds2Client.completeHandshake(handshakeFinish)

                            val cdsiRequest = CDSClientRequest.newBuilder()
                                .setNewE164S(com.google.protobuf.ByteString.copyFrom(newE164sData))
                            if (cdsiToken != null) {
                                cdsiRequest.setToken(com.google.protobuf.ByteString.copyFrom(cdsiToken))
                            }
                            val encryptedReq = cds2Client.establishedSend(cdsiRequest.build().toByteArray())
                            socket.send(encryptedReq)

                            // Response loop matching Go's ReadResponse
                            var token: ByteArray? = null
                            var response: Map<Long, CDSResponseEntry>? = null
                            while (response == null) {
                                val msg = nextMessage("No CDSI response")
                                val decrypted = cds2Client.establishedRecv(msg)
                                val cdsiResp = CDSClientResponse.parseFrom(decrypted)

                                if (cdsiResp.hasToken()) {
                                    token = cdsiResp.token.toByteArray()
                                    val tokenAck = CDSClientRequest.newBuilder()
                                        .setTokenAck(true).build()
                                    val encAck = cds2Client.establishedSend(tokenAck.toByteArray())
                                    socket.send(encAck)
                                }

                                if (!cdsiResp.e164PniAciTriples.isEmpty) {
                                    response = parseTriples(cdsiResp.e164PniAciTriples.toByteArray())
                                }
                            }
                            Pair(response, token)
                        }
                    }
                } finally {
                    reader.cancel()
                }
            }
        } finally {
            runCatching { socket.close(3000, "Normal") }
        }
    }

    private fun parseTriples(triples: ByteArray): Map<Long, CDSResponseEntry> {
        val tripleSize = 8 + 16 + 16
        val pairCount = triples.size / tripleSize
        if (pairCount * tripleSize != triples.size) {
            throw IllegalStateException("Invalid response size ${triples.size} (not divisible by $tripleSize)")
        }
        val result = mutableMapOf<Long, CDSResponseEntry>()
        for (i in 0 until pairCount) {
            val offset = i * tripleSize
            val e164 = ByteBuffer.wrap(triples, offset, 8).long
            if (e164 == 0L) continue
            val pniBuf = ByteBuffer.wrap(triples, offset + 8, 16)
            val pni = Uuid.fromLongs(pniBuf.long, pniBuf.long)
            val aciBuf = ByteBuffer.wrap(triples, offset + 24, 16)
            val aci = Uuid.fromLongs(aciBuf.long, aciBuf.long)
            result[e164] = CDSResponseEntry(aci = aci, pni = pni)
        }
        return result
    }

    companion object {
        private const val TAG = "ContactDiscovery"
        private const val CDSI_HOST = "cdsi.signal.org"
        private const val MRENCLAVE = "15637fa1e54fe655176d3df1a9f94b87c01ed377acaa570682dc5d72c95ef07b"
        private const val CDSI_AUTH_TTL_MS = 23 * 60 * 60 * 1000L
        private const val RATE_LIMIT_CLOSE_CODE = 4008
    }
}
