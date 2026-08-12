package com.vayunmathur.communicate.telephony

import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.vayunmathur.communicate.data.googlevoice.call.GoogleVoiceCallManager

/**
 * Self-managed [ConnectionService] that makes Google Voice behave like a second line. Outgoing
 * calls placed via [GoogleVoiceTelecom.placeOutgoing] and inbound calls surfaced via
 * [GoogleVoiceTelecom.addIncoming] both land here and are wired to [GoogleVoiceCallManager].
 */
class GoogleVoiceConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        GoogleVoiceCallManager.init(applicationContext)
        val number = request?.address?.schemeSpecificPart.orEmpty()
        val connection = GoogleVoiceConnection().apply {
            setAddress(request?.address ?: Uri.fromParts("tel", number, null), TelecomManager.PRESENTATION_ALLOWED)
            setDialing()
            connectionProperties = connectionProperties or Connection.PROPERTY_SELF_MANAGED
        }
        GoogleVoiceCallManager.connection = connection
        GoogleVoiceCallManager.placeCall(number)
        GoogleVoiceCallForegroundService.start(applicationContext)
        return connection
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ): Connection {
        GoogleVoiceCallManager.init(applicationContext)
        val address = request?.extras?.getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS) as? Uri
        val connection = GoogleVoiceConnection().apply {
            if (address != null) setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
            setRinging()
            connectionProperties = connectionProperties or Connection.PROPERTY_SELF_MANAGED
        }
        GoogleVoiceCallManager.connection = connection
        GoogleVoiceCallForegroundService.start(applicationContext)
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?,
    ) {
        GoogleVoiceCallManager.hangup()
    }
}
