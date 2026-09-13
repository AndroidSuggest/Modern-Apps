package com.vayunmathur.communicate.data.signal

import android.content.Context
import android.util.Log
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.transport.SignalSocket
import com.vayunmathur.library.network.NetworkClient
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * SignalClient send pipeline (split from SignalClient.kt for file length).
 *
 * Extension functions on [SignalClient]; behavior identical, call sites unchanged.
 */
// ---- internal single send path: Content -> encrypted -> PUT /v1/messages/{aci} ----

/**
 * The envelope timestamp for [content].
 *
 * Taken from the message rather than the clock: recipients reject a message whose
 * `DataMessage.timestamp` differs from the envelope's (`EnvelopeContentValidator`: "Timestamps don't
 * match!"). Generating the two independently means anything slow in between — a pre-key fetch on a
 * first message — silently produces a message the peer discards.
 */
private fun SignalClient.envelopeTimestampFor(content: SignalServiceProtos.Content): Long = when {
    content.hasDataMessage() && content.dataMessage.hasTimestamp() ->
        content.dataMessage.timestamp
    content.hasEditMessage() && content.editMessage.hasDataMessage() &&
        content.editMessage.dataMessage.hasTimestamp() ->
        content.editMessage.dataMessage.timestamp
    else -> System.currentTimeMillis()
}

/**
 * The `groupV2` context for [conversationId], or nulls when it is not a group.
 *
 * Every DataMessage sent to a group needs this or the recipient files it as a 1:1 message from the sender —
 * which is how reactions, edits, deletes and polls used to escape their group thread.
 */
internal suspend fun SignalClient.groupContextFor(conversationId: String): Pair<ByteArray?, Int?> {
    if (!SignalProtocol.isGroupConversation(conversationId)) return null to null
    val masterKey = groupMasterKeyForConversation(conversationId) ?: return null to null
    return masterKey to groupRevisionForConversation(conversationId)
}

internal suspend fun SignalClient.sendContent(
    destinationAci: String,
    content: SignalServiceProtos.Content,
    urgent: Boolean = true,
): Boolean {
    val aci = destinationAci.trim()
    if (aci.isEmpty()) return false
    val padded = SignalProtocol.padMessageBody(content.toByteArray())
    val timestamp = envelopeTimestampFor(content)

    // Group send: expand to participants and encrypt per recipient. There is no shared ciphertext
    // — sender keys would be the efficient path, but each member still needs their own envelope.
    if (aci.startsWith("group:") || aci.startsWith("group-")) {
        val participants: List<String> = try {
            db?.conversationDao()?.getConversation(aci)?.participants
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        } catch (_: Exception) { emptyList() }
        if (participants.isEmpty()) {
            Log.w(TAG, "group send has no known participants for $aci, dropping")
            return false
        }
        var allOk = true
        for (pid in participants) {
            if (!sendEncryptedTo(pid, padded, timestamp, urgent)) allOk = false
        }
        return allOk
    }

    return sendEncryptedTo(aci, padded, timestamp, urgent)
}

/**
 * Seed our own pre-keys into the protocol store and register anything the server does not yet have.
 *
 * After seeding, our stored signed pre-key is checked against the bundle the server actually hands
 * out. That is the only authoritative comparison: senders encrypt to the server's copy, so if it
 * differs from ours every inbound pre-key message fails in the key agreement with an error that names
 * neither half. A mismatch is repaired by rotating and re-registering.
 */
internal suspend fun SignalClient.ensureLocalPreKeys() {
    if (!preKeysPrepared.compareAndSet(false, true)) return
    val e = e2e ?: run {
        preKeysPrepared.set(false)
        return
    }
    var upload = try {
        e.ensureLocalPreKeys()
    } catch (t: Throwable) {
        preKeysPrepared.set(false)
        Log.w(TAG, "could not prepare local pre-keys", t)
        return
    }

    if (upload.signedPreKey == null && !signedPreKeyMatchesServer(e)) {
        upload = try {
            upload.copy(signedPreKey = e.rotateSignedPreKeyNow())
        } catch (t: Throwable) {
            Log.w(TAG, "could not rotate the signed pre-key", t)
            upload
        }
    }

    if (upload.isEmpty) return
    val ok = SignalKeysApi.uploadPreKeys(
        signedPreKey = upload.signedPreKey,
        lastResortKyber = upload.lastResortKyber,
        oneTimeEcPreKeys = upload.oneTimeEcPreKeys,
        authHeader = basicAuthHeader(),
        sslSocketFactory = signalTls(),
    )
    if (ok) {
        e.markPreKeysUploaded()
    } else {
        // Let a later start retry rather than leaving keys stored but unregistered.
        preKeysPrepared.set(false)
    }
}

/**
 * Whether the signed pre-key the server serves for us is one we hold the private half of. Returns true
 * when it cannot be checked, so a network problem does not trigger a needless rotation.
 */
private suspend fun SignalClient.signedPreKeyMatchesServer(e: SignalE2E): Boolean {
    val ourAci = authData?.aci?.takeIf { it.isNotEmpty() } ?: return true
    val bundles = try {
        SignalKeysApi.fetchPreKeys(ourAci, authData?.deviceId ?: PRIMARY_DEVICE_ID, basicAuthHeader(), signalTls())
    } catch (t: Throwable) {
        Log.i(TAG, "could not fetch our own bundle to verify the signed pre-key", t)
        return true
    }
    val ours = bundles.firstOrNull { it.deviceId == (authData?.deviceId ?: PRIMARY_DEVICE_ID) }
    if (ours == null) {
        Log.w(TAG, "the server has no bundle for our own device ${authData?.deviceId}")
        return true
    }
    val signedMatches = e.hasSignedPreKeyMatching(ours.bundle.signedPreKeyId, ours.bundle.signedPreKeyPublic)
    // The identity key is the other half of what a sender binds to; a mismatch here breaks every
    // inbound message and cannot be repaired by rotating pre-keys.
    val identityMatches = ours.bundle.identityKey.contentEquals(e.ownIdentityPublicKey)
    Log.i(
        TAG,
        "own bundle check: signedPreKeyId=${ours.bundle.signedPreKeyId} signedMatches=$signedMatches " +
            "identityMatches=$identityMatches kyberPreKeyId=${ours.bundle.kyberPreKeyId} " +
            "hasOneTime=${ours.bundle.preKeyId != null}",
    )
    if (!identityMatches) {
        Log.e(
            TAG,
            "our registered identity key differs from the one we hold; inbound messages cannot decrypt " +
                "and re-registration is required",
        )
    }
    if (!signedMatches) {
        Log.w(TAG, "the server serves signed pre-key ${ours.bundle.signedPreKeyId} that we cannot use; rotating")
    }
    return signedMatches
}

/**
 * Run contact discovery once. Returns whether it completed, so a caller waiting on a resolution knows
 * whether the result is meaningful or a concurrent run simply owned it.
 */
internal suspend fun SignalClient.runContactDiscovery(ctx: Context): Boolean {
    if (!discoveryRunning.compareAndSet(false, true)) return false
    return try {
        val result = SignalContactSync.sync(ctx)
        Log.i(
            TAG,
            "contact discovery: ${result.onSignalCount}/${result.e164Count} on Signal" +
                (result.transportError?.let { " ($it)" } ?: ""),
        )
        result.transportError == null
    } catch (t: Throwable) {
        Log.w(TAG, "contact discovery failed", t)
        false
    } finally {
        discoveryRunning.set(false)
    }
}

/**
 * The service id to address on the wire for [destination], which the app may hand us as a phone
 * number. May be an ACI (a bare UUID) or a PNI (`PNI:<uuid>`) — both are valid destinations.
 *
 * Contact discovery returns a PNI rather than an ACI unless we already hold the contact's profile
 * key, so a contact we have never messaged is addressed by PNI. Their ACI arrives with their reply.
 */
internal suspend fun SignalClient.resolveDestinationAci(destination: String): String? {
    val trimmed = destination.trim()
    if (trimmed.isEmpty()) return null
    if (ACI_REGEX.matches(trimmed) || trimmed.startsWith(PNI_PREFIX)) return trimmed

    knownServiceIdFor(trimmed)?.let { return it }
    // Not in the contact table: the number may have joined Signal since the last sync, or we may
    // never have synced. Discovery is the only way to find out.
    val ctx = appContext
    if (ctx != null) {
        runContactDiscovery(ctx)
        knownServiceIdFor(trimmed)?.let { return it }
    }
    Log.w(TAG, "$trimmed is not a registered Signal user, or discovery could not confirm it")
    return null
}

/** An ACI if we know one, otherwise the PNI, for [e164]. */
private suspend fun SignalClient.knownServiceIdFor(e164: String): String? {
    val contact = try { db?.contactDao()?.getByPhone(e164) } catch (_: Exception) { null } ?: return null
    if (!contact.onSignal) return null
    // The `aci` column falls back to the phone number when discovery returned no ACI.
    contact.aci.takeIf { ACI_REGEX.matches(it) }?.let { return it }
    return contact.pni.takeIf { it.isNotEmpty() }?.let { pni ->
        if (pni.startsWith(PNI_PREFIX)) pni else "$PNI_PREFIX$pni"
    }
}

private suspend fun SignalClient.sendEncryptedTo(
    destination: String,
    padded: ByteArray,
    timestamp: Long,
    urgent: Boolean = true,
): Boolean {
    val e = e2e
    if (e == null) {
        Log.w(TAG, "no protocol store, cannot send to $destination")
        return false
    }
    val aci = resolveDestinationAci(destination) ?: return false
    if (!e.hasSession(aci, PRIMARY_DEVICE_ID) && !establishSession(e, aci)) return false

    // Sealed sender when we can: a delivery certificate proves who we are to the recipient without
    // telling the server, and the access key authorises the unauthenticated send. Absent either,
    // fall back to an identified send rather than not sending.
    val sealedSender = sealedSenderFor(aci)

    // The server reports device-set disagreements as 409/410; correcting them changes which
    // devices we encrypt for, so the whole encrypt-and-send has to be redone. [timestamp] comes from
    // the message itself and is never regenerated — it is both the message's identity for dedup and a
    // value the recipient checks against the DataMessage.
    for (attempt in 1..SEND_ATTEMPTS) {
        val targets = e.deviceIdsWithSessions(aci)
        val messages = ArrayList<SignalPayload.OutgoingPushMessage>(targets.size)
        var primaryFailed = false
        for (deviceId in targets) {
            try {
                val enc = if (sealedSender != null) {
                    e.sealedSenderEncrypt(aci, deviceId, padded, sealedSender.certificate)
                } else {
                    e.encryptDM(aci, deviceId, padded)
                }
                messages.add(
                    SignalPayload.OutgoingPushMessage(
                        type = SignalPayload.envelopeTypeFor(enc.ciphertextType),
                        destinationDeviceId = deviceId,
                        destinationRegistrationId = enc.remoteRegistrationId,
                        content = enc.data,
                    ),
                )
            } catch (u: UntrustedIdentityException) {
                // Already reported by the store; abandon the send rather than encrypting to a key
                // the user has not accepted.
                Log.w(TAG, "untrusted identity for $aci:$deviceId, abandoning send")
                return false
            } catch (t: Throwable) {
                Log.w(TAG, "encrypt failed for $aci:$deviceId", t)
                if (deviceId == PRIMARY_DEVICE_ID) primaryFailed = true
            }
        }
        if (primaryFailed) {
            // Delivering to linked devices but not the recipient's primary is not a send.
            Log.w(TAG, "could not encrypt for $aci's primary device, abandoning send")
            return false
        }
        if (messages.isEmpty()) {
            Log.w(TAG, "no device of $aci could be encrypted for")
            return false
        }
        if (messages.size < targets.size) {
            Log.w(TAG, "sending to ${messages.size} of ${targets.size} devices for $aci")
        }

        val body = SignalPayload.buildPutMessagesBody(aci, messages, timestamp, urgent = urgent)
        when (val outcome = putMessages(aci, body, sealedSender?.accessKey)) {
            is SignalClient.SendOutcome.Success -> return true
            is SignalClient.SendOutcome.Failed -> return false
            is SignalClient.SendOutcome.DeviceSetChanged -> {
                if (!reconcileDevices(e, aci, outcome.status, outcome.body)) return false
                Log.i(TAG, "device set for $aci changed (${outcome.status}), retrying send")
            }
        }
    }
    Log.w(TAG, "giving up on $aci after $SEND_ATTEMPTS attempts to resolve its device set")
    return false
}

/**
 * The certificate and access key needed to send sealed to [aci], or null when either is missing and
 * the send must be identified instead.
 */
private suspend fun SignalClient.sealedSenderFor(aci: String): SignalClient.SealedSenderAccess? {
    val database = db ?: return null
    val accessKey = SignalSealedSender.accessKeyFor(database, aci) ?: return null
    val certificate = SignalSealedSender.senderCertificate(
        db = database,
        authHeader = basicAuthHeader(),
        sslSocketFactory = signalTls(),
    ) ?: return null
    return SignalClient.SealedSenderAccess(certificate, accessKey)
}

internal suspend fun SignalClient.putMessages(aci: String, jsonBody: ByteArray, accessKey: ByteArray?): SignalClient.SendOutcome {
    // Sealed sends go over the credential-free socket. A 401 means the access key was refused, so
    // retry over the authenticated socket with the same body — the recipient still receives a sealed
    // envelope, which official clients expect on an identified channel (SignalServiceCipher logs
    // exactly this case), but the server learns the sender. That is the intended degradation: the
    // alternative is not delivering at all.
    if (accessKey != null) {
        val outcome = putMessagesOverSocket(unauthSocket, aci, jsonBody, accessKey)
        if (outcome != null && !(outcome is SignalClient.SendOutcome.Failed && outcome.status == 401)) return outcome
        if (outcome != null) Log.i(TAG, "sealed send to $aci refused with 401, retrying authenticated")
    }
    val identified = putMessagesOverSocket(socket, aci, jsonBody, accessKey = null)
    if (identified != null) return identified
    return putMessagesOverRest(aci, jsonBody)
}

/** Null when the socket is absent or gave no response, so the caller can fall back. */
private suspend fun SignalClient.putMessagesOverSocket(
    sock: SignalSocket?,
    aci: String,
    jsonBody: ByteArray,
    accessKey: ByteArray?,
): SignalClient.SendOutcome? {
    if (sock == null) return null
    val headers = buildList {
        add("content-type:application/json")
        if (accessKey != null) add(SignalSealedSender.accessKeyHeader(accessKey))
    }
    val result = try {
        sock.sendRequestAwaitingResponse(
            SignalPayload.buildPutMessagesRequest(aci, jsonBody, headers = headers),
        )
    } catch (_: Exception) { null } ?: return null

    return when {
        result.isSuccess -> SignalClient.SendOutcome.Success
        result.status == 409 || result.status == 410 -> SignalClient.SendOutcome.DeviceSetChanged(result.status, result.body)
        else -> {
            Log.w(TAG, "PUT messages to $aci rejected: ${result.status} ${result.message}")
            SignalClient.SendOutcome.Failed(result.status)
        }
    }
}

/** Fallback for when neither socket is connected. Authenticated transport, whatever the body holds. */
private suspend fun SignalClient.putMessagesOverRest(aci: String, jsonBody: ByteArray): SignalClient.SendOutcome = try {
    val headers = mapOf(
        "Authorization" to "Basic ${basicAuthHeader()}",
        "Content-Type" to "application/json",
    )
    val resp = NetworkClient.execute(
        "https://chat.signal.org${SignalPayload.putMessagesPath(aci)}",
        method = "PUT",
        headers = headers,
        body = jsonBody,
        sslSocketFactory = signalTls(),
    )
    when {
        resp.isSuccess -> SignalClient.SendOutcome.Success
        resp.status == 409 || resp.status == 410 -> SignalClient.SendOutcome.DeviceSetChanged(resp.status, resp.bytes)
        else -> {
            Log.w(TAG, "PUT messages to $aci rejected: ${resp.status} ${resp.statusMessage}")
            SignalClient.SendOutcome.Failed(resp.status)
        }
    }
} catch (_: Exception) { SignalClient.SendOutcome.Failed(0) }

/**
 * Bring our device set for [aci] back in line with the server's. Returns whether anything actually
 * changed — retrying with an identical device set would just produce the same rejection.
 *
 * 409 reports `missingDevices` (fetch pre-keys and build sessions) and `extraDevices` (archive).
 * 410 reports `staleDevices`, which are archived and then rebuilt. Sessions are archived rather
 * than deleted so in-flight messages on the old chain stay decryptable.
 */
private suspend fun SignalClient.reconcileDevices(e: SignalE2E, aci: String, status: Int, body: ByteArray): Boolean {
    val devices = SignalDeviceMismatch.parse(status, body.toString(Charsets.UTF_8)) ?: run {
        Log.w(TAG, "could not parse $status device mismatch body for $aci")
        return false
    }
    var changed = devices.archive.count { e.archiveSession(aci, it) } > 0
    if (devices.fetch.isEmpty()) return changed

    val bundles = try {
        SignalKeysApi.fetchPreKeys(aci, 1, basicAuthHeader(), signalTls())
    } catch (t: Throwable) {
        Log.w(TAG, "prekey fetch for changed devices of $aci failed", t)
        return changed
    }
    for (device in bundles.filter { it.deviceId in devices.fetch }) {
        try {
            e.processPreKeyBundle(aci, device.deviceId, device.bundle)
            changed = true
        } catch (t: Throwable) {
            Log.w(TAG, "failed to build session for $aci:${device.deviceId}", t)
        }
    }
    return changed
}

/**
 * Fetch pre-keys for [aci] and build sessions for every device the server reports. Returns whether
 * device 1 ended up with a usable session, since that is the one this send targets.
 */
internal suspend fun SignalClient.establishSession(e: SignalE2E, aci: String): Boolean {
    val bundles = try {
        SignalKeysApi.fetchPreKeys(aci, 1, basicAuthHeader(), signalTls())
    } catch (u: SignalKeysApi.UnregisteredUserException) {
        Log.w(TAG, "cannot send to $aci: ${u.message}")
        return false
    } catch (t: Throwable) {
        Log.w(TAG, "prekey fetch failed for $aci", t)
        return false
    }
    if (bundles.isEmpty()) {
        Log.w(TAG, "no usable prekey bundles for $aci")
        return false
    }
    for (device in bundles) {
        try {
            e.processPreKeyBundle(aci, device.deviceId, device.bundle)
        } catch (u: UntrustedIdentityException) {
            // The store has already reported the change; a new session must not be built on a key
            // the user has not accepted.
            Log.w(TAG, "untrusted identity for $aci:${device.deviceId}, not building a session")
        } catch (t: Throwable) {
            Log.w(TAG, "failed to build session for $aci:${device.deviceId}", t)
        }
    }
    return e.hasSession(aci, 1)
}
