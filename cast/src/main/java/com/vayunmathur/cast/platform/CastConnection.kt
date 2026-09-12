package com.vayunmathur.cast.platform

import android.content.Context
import android.util.Log
import com.vayunmathur.cast.R
import com.vayunmathur.cast.domain.CastDevice
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.domain.ClientState
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.platform.mirror.MirrorConsentActivity
import com.vayunmathur.cast.platform.mirror.MirrorPreferences
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.service.CastService
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

private const val TAG = "CastController"

/**
 * Open a control channel to [device] and pair with it.
 *
 * Connecting and mirroring are one action because there is nothing else this app does. A paired TV
 * that is not mirroring is a dead state - it is sitting on its idle screen - so a successful pair
 * goes straight on to asking for capture consent.
 *
 * A no-op for the device already connected to; switching TVs tears the old session down first, since
 * a phone holds one session at a time. A [ClientPhase.Failed] session is *not* treated as live, so
 * tapping the same device again retries rather than doing nothing.
 */
fun CastController.connect(context: Context, device: CastDevice, thenMirror: Boolean = true) {
    val phase = _sessionState.value.phase
    val live = phase != ClientPhase.Idle && phase != ClientPhase.Failed
    if (_device.value?.id == device.id && live) return
    val appContext = context.applicationContext
    pendingMirror = thenMirror
    scope.launch {
        teardown()
        _device.value = device
        _isConnecting.value = true
        _sessionState.value = ClientState(phase = ClientPhase.Connecting)

        if (device.protocolVersion != 0 && device.protocolVersion != PROTOCOL_VERSION) {
            // Said before connecting rather than after a failed handshake: the TXT record already
            // told us, and a clear message beats a socket that opens and then gives up.
            fail(appContext, ClientFailure.VersionMismatch)
            return@launch
        }

        val newSocket = ControlSocket(device.host, device.port)
        try {
            newSocket.connect()
        } catch (e: Exception) {
            Log.w(TAG, "could not open a control channel to ${device.host}:${device.port}", e)
            _isConnecting.value = false
            _device.value = null
            _sessionState.value = ClientState()
            AppMessages.show(
                appContext.getString(R.string.cast_connect_failed, device.friendlyName),
            )
            return@launch
        }
        socket = newSocket
        // Whatever the user last tapped is the tile's target from now on.
        MirrorPreferences.setTarget(appContext, device)

        val senderId = MirrorPreferences.senderId(appContext)
        val storedKey = MirrorPreferences.deviceKey(appContext, device.id)
        val newClient = MirrorClient(
            socket = newSocket,
            senderId = senderId,
            storedDeviceKey = storedKey,
        )
        client = newClient
        // Started only once a channel is actually open, so a failed connection does not leave a
        // notification behind.
        CastService.start(appContext)

        when (val outcome = mutex.withLock { newClient.begin() }) {
            is HandshakeOutcome.Paired -> {
                onPaired(appContext, device, newClient, outcome.deviceKey, thenMirror)
            }
            is HandshakeOutcome.NeedsCode -> {
                _sessionState.value = ClientState(
                    phase = ClientPhase.AwaitingCode,
                    receiverName = newClient.receiverName,
                    attemptsLeft = outcome.attemptsLeft,
                )
                _isConnecting.value = false
            }
            is HandshakeOutcome.Failed -> fail(appContext, outcome.reason)
            is HandshakeOutcome.Ready -> Unit // begin() cannot produce this.
        }
    }
}

/**
 * Submit the six digits the user read off the TV.
 *
 * A wrong code is a routine outcome and leaves the session open, so the user can simply try again -
 * which is also why this reports [ClientState.attemptsLeft] rather than just failing.
 */
fun CastController.submitPairCode(context: Context, code: String) {
    val appContext = context.applicationContext
    scope.launch {
        val activeClient = client ?: return@launch
        val device = _device.value ?: return@launch
        when (val outcome = mutex.withLock { activeClient.enterCode(code) }) {
            is HandshakeOutcome.Paired ->
                onPaired(appContext, device, activeClient, outcome.deviceKey, pendingMirror)
            is HandshakeOutcome.NeedsCode -> _sessionState.update {
                it.copy(
                    phase = ClientPhase.AwaitingCode,
                    // -1 means "that was not even six digits", so the allowance is unchanged.
                    attemptsLeft = if (outcome.attemptsLeft < 0) it.attemptsLeft else outcome.attemptsLeft,
                    codeChanged = outcome.codeChanged,
                )
            }
            is HandshakeOutcome.Failed -> fail(appContext, outcome.reason)
            is HandshakeOutcome.Ready -> Unit
        }
    }
}

internal suspend fun CastController.onPaired(
    context: Context,
    device: CastDevice,
    activeClient: MirrorClient,
    deviceKey: ByteArray?,
    thenMirror: Boolean,
) {
    // Persisted against the TV's own id rather than the mDNS instance name, so renaming the TV does
    // not make the phone ask for a code again.
    if (deviceKey != null) {
        val receiverId = activeClient.receiverId ?: device.id
        MirrorPreferences.rememberDeviceKey(context, receiverId, deviceKey)
        // A code pairing is a fresh start with this TV - it has been reset, or reinstalled, or is
        // simply a different box on the same name - so a codec remembered as broken against the old
        // one has nothing to say about this one. It is also the only reset a user can reach, which
        // is why the refusal message tells them to re-pair.
        MirrorPreferences.clearDemotedCodecs(context, receiverId)
    }
    _sessionState.value = ClientState(
        phase = ClientPhase.Paired,
        receiverName = activeClient.receiverName,
    )
    _isConnecting.value = false
    // **No watch loop yet.** `awaitEnd` reads the socket, and `configureStream` still has a
    // request/response to do on it - a second reader would consume the STREAM_READY that
    // negotiation is waiting for. The watch starts once the exchange is finished; until then a dead
    // TV surfaces as a failed configureStream, which is just as prompt.
    if (!thenMirror) return
    // Consent is asked for only now: there is no point recording the screen for a TV that was going
    // to reject the code.
    ExternalIntents.launch(context, MirrorConsentActivity.intent(context))
}

/** Say goodbye, close the channel and drop the session. */
fun CastController.disconnect(context: Context) {
    val appContext = context.applicationContext
    scope.launch {
        client?.let { mutex.withLock { it.sayGoodbye("user disconnected") } }
        teardown()
        CastService.stop(appContext)
    }
}

internal suspend fun CastController.fail(context: Context, reason: ClientFailure) {
    _sessionState.value = ClientState(
        phase = ClientPhase.Failed,
        receiverName = client?.receiverName,
        failure = reason,
    )
    _isConnecting.value = false
    teardown(keepFailure = true)
    CastService.stop(context)
}
