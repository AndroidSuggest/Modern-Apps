package com.vayunmathur.euicc.telephony

import android.content.Context
import android.telephony.IccOpenLogicalChannelResponse
import android.telephony.TelephonyManager
import com.vayunmathur.euicc.EuiccNative

/** Thrown when the eUICC cannot be reached (no eUICC, no privilege, or an APDU error). */
class EuiccException(message: String) : Exception(message)

/**
 * Opens and drives a logical channel to the eUICC's ISD-R applet.
 *
 * SGP.22 local (ES10) operations run over an ISO-7816 logical channel selected
 * onto the ISD-R AID. This wraps the telephony
 * `iccOpenLogicalChannel` / `iccTransmitApduLogicalChannel` /
 * `iccCloseLogicalChannel` APIs, which require the app to hold carrier
 * privileges or be a platform-signed system app (`MODIFY_PHONE_STATE`). The
 * native core builds the command APDUs and calls back into
 * [EuiccNative.transmitApdu]; [transmit] performs the field-based transmit and
 * handles `61xx` GET RESPONSE / `6Cxx` Le-correction chaining.
 */
class EuiccChannelManager(context: Context) {
    private val telephony: TelephonyManager =
        context.applicationContext.getSystemService(TelephonyManager::class.java)
            ?: throw EuiccException("TelephonyManager unavailable")

    /**
     * Opens the ISD-R channel, installs it as [EuiccNative.activeChannel] for the
     * duration of [block] (so native ops can transmit), and closes it afterward.
     */
    @Synchronized
    @Suppress("DEPRECATION")
    fun <T> withIsdrChannel(block: () -> T): T {
        val response: IccOpenLogicalChannelResponse =
            telephony.iccOpenLogicalChannel(ISDR_AID, /* p2 = */ 0)
        if (response.status != IccOpenLogicalChannelResponse.STATUS_NO_ERROR) {
            throw EuiccException("cannot open ISD-R channel (status=${response.status})")
        }
        val channel = response.channel
        if (channel <= 0) throw EuiccException("invalid ISD-R channel ($channel)")
        return try {
            EuiccNative.activeChannel = { apdu -> transmit(channel, apdu) }
            block()
        } finally {
            EuiccNative.activeChannel = null
            runCatching { telephony.iccCloseLogicalChannel(channel) }
        }
    }

    /**
     * Transmits one command APDU on [channel] using the field-based telephony
     * API, following `61xx`/`6Cxx` chaining, and returns the full response
     * (response data followed by the two status bytes).
     */
    @Suppress("DEPRECATION")
    private fun transmit(channel: Int, command: ByteArray): ByteArray {
        require(command.size >= 4) { "APDU too short (${command.size} bytes)" }
        val cla = command[0].toInt() and 0xFF
        val ins = command[1].toInt() and 0xFF
        val p1 = command[2].toInt() and 0xFF
        val p2 = command[3].toInt() and 0xFF
        val p3: Int
        val dataHex: String
        when {
            command.size == 4 -> {
                p3 = 0; dataHex = ""
            }
            command.size == 5 -> {
                // Case 2: the fifth byte is Le.
                p3 = command[4].toInt() and 0xFF; dataHex = ""
            }
            else -> {
                // Case 3/4: fifth byte is Lc; ignore any trailing Le.
                val lc = command[4].toInt() and 0xFF
                val end = (5 + lc).coerceAtMost(command.size)
                p3 = lc
                dataHex = command.copyOfRange(5, end).toHex()
            }
        }

        val out = StringBuilder()
        var response = telephony
            .iccTransmitApduLogicalChannel(channel, cla, ins, p1, p2, p3, dataHex).orEmpty()
        while (response.length >= 4) {
            val body = response.substring(0, response.length - 4)
            val sw1 = response.substring(response.length - 4, response.length - 2).toInt(16)
            val sw2 = response.substring(response.length - 2).toInt(16)
            when (sw1) {
                0x61 -> {
                    // More data available: GET RESPONSE for sw2 bytes.
                    out.append(body)
                    response = telephony
                        .iccTransmitApduLogicalChannel(channel, 0x00, 0xC0, 0x00, 0x00, sw2, "")
                        .orEmpty()
                }
                0x6C -> {
                    // Wrong Le: resend the original command with Le = sw2.
                    out.setLength(0)
                    response = telephony
                        .iccTransmitApduLogicalChannel(channel, cla, ins, p1, p2, sw2, dataHex)
                        .orEmpty()
                }
                else -> {
                    out.append(body)
                    out.append("%02X%02X".format(sw1, sw2))
                    return out.toString().hexToBytes()
                }
            }
        }
        return out.toString().hexToBytes()
    }

    companion object {
        /** ISD-R application identifier (SGP.22). */
        const val ISDR_AID = "A0000005591010FFFFFFFF8900000100"
    }
}

private fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) sb.append("%02X".format(b.toInt() and 0xFF))
    return sb.toString()
}

private fun String.hexToBytes(): ByteArray {
    if (length % 2 != 0) return ByteArray(0)
    return ByteArray(length / 2) { i ->
        substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
