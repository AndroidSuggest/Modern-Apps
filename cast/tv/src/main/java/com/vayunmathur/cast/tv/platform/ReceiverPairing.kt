package com.vayunmathur.cast.tv.platform

import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.PairResult
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.e2ee.PqcIdentity
import kotlinx.coroutines.flow.update

private const val TAG = "ReceiverController"

/**
 * Pair, or prove a phone we already trust.
 *
 * The attempt loop stays open on a wrong code so the user can simply type it again; the socket's
 * read timeout is what ends a session nobody is completing.
 */
internal suspend fun ReceiverController.authenticate(
    channel: ControlChannel,
    store: PairingStore,
    keys: SessionKeys,
    transcript: ByteArray,
    greeting: Hello,
): Boolean {
    if (rememberedKeyAuthenticates(channel, store, keys, transcript, greeting)) return true

    return pairWithCode(channel, store, keys, transcript, greeting)
}

/**
 * Prove a phone we already trust, without a code.
 *
 * Returns true only when the remembered key verifies; false falls through to pairing with a
 * code rather than refusing, because the honest cause is a reinstall. Deliberately sends no
 * `PairFailed` on a failed proof: the `PairRequired` below is the one reply to that proof, and
 * sending both would leave a message in the phone's buffer that its next read would mistake for
 * the answer to its code.
 */
private suspend fun ReceiverController.rememberedKeyAuthenticates(
    channel: ControlChannel,
    store: PairingStore,
    keys: SessionKeys,
    transcript: ByteArray,
    greeting: Hello,
): Boolean {
    if (!greeting.paired) return false
    val remembered = store.deviceKey(greeting.senderId) ?: return false
    return tryRememberedKey(channel, keys, transcript, greeting, remembered)
}

/**
 * Offer the remembered-key proof round to a phone that claims to be paired.
 *
 * Exactly one message either way, so the phone never has to guess whether a code is coming.
 * `code = false` is what tells it to prove the key it holds.
 */
private suspend fun ReceiverController.tryRememberedKey(
    channel: ControlChannel,
    keys: SessionKeys,
    transcript: ByteArray,
    greeting: Hello,
    remembered: ByteArray,
): Boolean {
    // Exactly one message either way, so the phone never has to guess whether a code is coming.
    // `code = false` is what tells it to prove the key it holds.
    channel.send(PairRequired(code = false))
    val proof = channel.receive()?.message as? PairProof ?: return false
    val bytes = ProtocolBase64.decode(proof.proof) ?: return false
    if (pairingGate.verifyDevice(keys, transcript, remembered, bytes) !is PairResult.Ok) {
        Log.status(TAG, "'${greeting.senderName}' failed its device proof; asking for a code")
        return false
    }
    channel.send(PairOk())
    Log.status(TAG, "'${greeting.senderName}' authenticated with a remembered device key")
    return true
}

/**
 * Pair with a code, staying open on a wrong one so the user can simply type it again.
 *
 * The socket's read timeout is what ends a session nobody is completing.
 */
private suspend fun ReceiverController.pairWithCode(
    channel: ControlChannel,
    store: PairingStore,
    keys: SessionKeys,
    transcript: ByteArray,
    greeting: Hello,
): Boolean {

    pairingGate.reset()
    channel.send(PairRequired(code = true, attemptsLeft = pairingGate.attemptsLeft))
    mutableState.update {
        it.copy(
            phase = ReceiverPhase.Pairing(
                senderName = greeting.senderName,
                code = pairingGate.code,
                attemptsLeft = pairingGate.attemptsLeft,
            ),
        )
    }
    while (true) {
        val proof = channel.receive()?.message as? PairProof ?: return false
        val bytes = ProtocolBase64.decode(proof.proof) ?: return false
        when (val result = pairingGate.verifyCode(keys, transcript, bytes)) {
            is PairResult.Ok -> {
                val deviceKey = result.deviceKey ?: return false
                store.remember(greeting.senderId, deviceKey)
                channel.send(PairOk(deviceKey = ProtocolBase64.encode(deviceKey)))
                Log.status(TAG, "'${greeting.senderName}' paired; it will connect silently from now on")
                return true
            }
            is PairResult.Wrong -> {
                channel.send(
                    PairFailed(
                        attemptsLeft = result.attemptsLeft,
                        codeChanged = result.codeChanged,
                    ),
                )
                mutableState.update {
                    it.copy(
                        phase = ReceiverPhase.Pairing(
                            senderName = greeting.senderName,
                            code = pairingGate.code,
                            attemptsLeft = result.attemptsLeft,
                            codeChanged = result.codeChanged,
                        ),
                    )
                }
            }
        }
    }
}
