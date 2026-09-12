package com.vayunmathur.cast.tv.platform

import android.util.Log
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
    val remembered = if (greeting.paired) store.deviceKey(greeting.senderId) else null
    if (remembered != null) {
        // Exactly one message either way, so the phone never has to guess whether a code is
        // coming. `code = false` is what tells it to prove the key it holds.
        channel.send(PairRequired(code = false))
        val proof = channel.receive()?.message as? PairProof ?: return false
        val bytes = ProtocolBase64.decode(proof.proof) ?: return false
        if (pairingGate.verifyDevice(keys, transcript, remembered, bytes) is PairResult.Ok) {
            channel.send(PairOk())
            Log.i(TAG, "'${greeting.senderName}' authenticated with a remembered device key")
            return true
        }
        // Not the phone we remember. The honest cause is a reinstall, so fall through to the code
        // rather than refusing. Deliberately **no PairFailed here**: the PairRequired below is the
        // one reply to that proof, and sending both would leave a message in the phone's buffer
        // that its next read would mistake for the answer to its code.
        Log.i(TAG, "'${greeting.senderName}' failed its device proof; asking for a code")
    }

    pairingGate.reset()
    channel.send(PairRequired(code = true, attemptsLeft = pairingGate.attemptsLeft))
    _state.update {
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
                Log.i(TAG, "'${greeting.senderName}' paired; it will connect silently from now on")
                return true
            }
            is PairResult.Wrong -> {
                channel.send(
                    PairFailed(
                        attemptsLeft = result.attemptsLeft,
                        codeChanged = result.codeChanged,
                    ),
                )
                _state.update {
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
