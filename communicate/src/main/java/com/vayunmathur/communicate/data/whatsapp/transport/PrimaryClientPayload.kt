package com.vayunmathur.communicate.data.whatsapp.transport

import android.os.Build
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppPayloadProto

/**
 * Builds the **primary-client** Noise `ClientPayload` (own phone-number login), replacing the
 * companion-web `buildClientPayload` from messages' WebViewWebSocket.
 *
 * Differences from the companion payload:
 *  - `UserAgent.Platform.ANDROID` with real Android device/os fields (not WEB / "Desktop").
 *  - `username` = the registered phone number (uint64), `device = 0` (the phone itself).
 *  - `passive = false`, `pull = false`.
 *  - **No** `WebInfo`, **no** `devicePairingData` (those are web/companion only).
 */
object PrimaryClientPayload {

    fun build(auth: WhatsAppAuthData): ByteArray {
        val appVersion = WhatsAppPayloadProto.ClientPayload.UserAgent.AppVersion.newBuilder()
            .setPrimary(WhatsAppProtocol.WA_VERSION[0])
            .setSecondary(WhatsAppProtocol.WA_VERSION[1])
            .setTertiary(WhatsAppProtocol.WA_VERSION[2])
            .setQuaternary(WhatsAppProtocol.WA_VERSION.getOrElse(3) { 0 })
            .build()

        val userAgent = WhatsAppPayloadProto.ClientPayload.UserAgent.newBuilder()
            .setPlatform(WhatsAppPayloadProto.ClientPayload.UserAgent.Platform.ANDROID)
            .setReleaseChannel(WhatsAppPayloadProto.ClientPayload.UserAgent.ReleaseChannel.RELEASE)
            .setAppVersion(appVersion)
            .setMcc("000")
            .setMnc("000")
            .setOsVersion(Build.VERSION.RELEASE ?: "13")
            .setManufacturer(Build.MANUFACTURER ?: "")
            .setDevice(Build.MODEL ?: "Android")
            .setOsBuildNumber(Build.DISPLAY ?: "")
            .setLocaleLanguageIso6391("en")
            .setLocaleCountryIso31661Alpha2("US")
            .build()

        // username = numeric phone only (strip any :device / .agent / @server suffixes).
        val widUser = auth.wid.substringBefore("@").substringBefore(":").substringBefore(".")
        val username = widUser.toLongOrNull()
            ?: auth.phoneNumber.filter { it.isDigit() }.toLongOrNull()
            ?: 0L

        return WhatsAppPayloadProto.ClientPayload.newBuilder()
            .setUsername(username)
            .setDevice(0)
            .setPassive(false)
            .setPull(false)
            .setUserAgent(userAgent)
            .setConnectType(WhatsAppPayloadProto.ClientPayload.ConnectType.WIFI_UNKNOWN)
            .setConnectReason(WhatsAppPayloadProto.ClientPayload.ConnectReason.USER_ACTIVATED)
            .build()
            .toByteArray()
    }
}
