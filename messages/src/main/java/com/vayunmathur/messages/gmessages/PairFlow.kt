@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.messages.gmessages

import kotlin.uuid.Uuid
import android.util.Base64
import android.util.Log
import authentication.Authentication
import authentication.Authentication.AuthenticationContainer
import authentication.Authentication.AuthMessage
import authentication.Authentication.BrowserType
import authentication.Authentication.BrowserDetails
import authentication.Authentication.ConfigVersion
import authentication.Authentication.DeviceType
import authentication.Authentication.ECDSAKeys
import authentication.Authentication.KeyData
import authentication.Authentication.RegisterPhoneRelayResponse
import authentication.Authentication.RefreshPhoneRelayResponse
import authentication.Authentication.URLData

/**
 * Implements the QR-code pairing handshake. Direct port of
 * `pkg/libgm/pair.go`.
 *
 * Flow:
 *   1. POST to Pairing/RegisterPhoneRelay with our ECDSA public key
 *      (X.509 SubjectPublicKeyInfo encoding) — the relay returns an
 *      initial tachyon auth token and a one-shot pairing key.
 *   2. Build a URLData{ pairingKey, AESKey, HMACKey } protobuf,
 *      base64-encode it (standard, NOT url-safe), and prefix with
 *      [Endpoints.QrCodeBaseUrl].
 *   3. Render that URL as a QR code (caller's responsibility) and show
 *      it. The user scans it from their phone's Google Messages app
 *      (menu → Device pairing → Pair QR code scanner).
 *   4. The phone POSTs the pair completion to the relay, which pushes
 *      a Paired event back through our long-poll. [LongPoll] handles
 *      that and calls [AuthData.applyPaired].
 */
object PairFlow {

    private const val TAG = "GMessages/Pair"

    /** Network name on the QR-pair path (vs. "GDitto" for the
     *  Google-account-based GAIA path).
     *
     *  GAIA sign-in (libgm pair_google.go) is intentionally DEFERRED, not implemented here. It
     *  requires: (1) a full UKEY2 cryptographic handshake (CREATE_GAIA_PAIRING_CLIENT_INIT/
     *  _FINISHED), (2) Google-account cookie/OAuth acquisition which is inherently browser-driven
     *  and cannot be performed headlessly in this client, and (3) DestRegistrationIDs + GAIA key
     *  derivation plumbing. The supporting hooks exist (AuthData GAIA fields, SessionHandler action
     *  allow-list, LongPoll gaia-event handling), but the ~540-line handshake port is out of scope
     *  and unverifiable without a Google-account test rig. QR pairing ([QrNetwork]) is the supported
     *  path. */
    const val QrNetwork = "Bugle"

    /** Config version that messages.google.com/web is currently sending.
     *  Defaults to a hardcoded value kept in sync with libgm
     *  `util/config.go.ConfigMessage`, but is overwritten at runtime by
     *  [fetchConfig] which reads the live version from /web/config. */
    private val DefaultConfigVersion: ConfigVersion by lazy {
        authentication.Authentication.ConfigVersion.newBuilder()
            .setYear(2026).setMonth(3).setDay(18).setV1(4).setV2(6)
            .build()
    }

    @Volatile
    private var fetchedConfigVersion: ConfigVersion? = null

    val ConfigVersion: ConfigVersion
        get() = fetchedConfigVersion ?: DefaultConfigVersion

    /**
     * Fetches the live messages-for-web config and updates [ConfigVersion] from its
     * `clientVersion` string. Replaces the previously hardcoded version. Port of libgm
     * client.go FetchConfig + Config.ParsedClientVersion. Returns the parsed version, or null
     * (leaving the default in place) on any failure. // UNVERIFIED runtime
     */
    suspend fun fetchConfig(rpc: RpcClient): ConfigVersion? {
        return try {
            val config = rpc.getDecoded(
                url = Endpoints.ConfigUrl,
                responseTemplate = config.ConfigOuterClass.Config.getDefaultInstance(),
            )
            val parsed = parseClientVersion(config.clientVersion)
            if (parsed != null) {
                fetchedConfigVersion = parsed
                Log.i(TAG, "Fetched config version ${parsed.year}.${parsed.month}.${parsed.day}")
            } else {
                Log.w(TAG, "Could not parse clientVersion '${config.clientVersion}', keeping default")
            }
            parsed
        } catch (e: Exception) {
            Log.w(TAG, "fetchConfig failed, keeping default config version: ${e.message}")
            null
        }
    }

    /** Parses a "YYYYMMDD.." clientVersion string into a ConfigVersion (V1/V2 fixed at 4/6,
     *  matching libgm Config.ParsedClientVersion). */
    private fun parseClientVersion(version: String): ConfigVersion? {
        if (version.length < 8) return null
        val year = version.substring(0, 4).toIntOrNull() ?: return null
        val month = version.substring(4, 6).toIntOrNull() ?: return null
        val day = version.substring(6, 8).toIntOrNull() ?: return null
        return authentication.Authentication.ConfigVersion.newBuilder()
            .setYear(year).setMonth(month).setDay(day).setV1(4).setV2(6)
            .build()
    }

    /** Browser-identification protobuf sent on every auth request. */
    val BrowserDetails: BrowserDetails by lazy {
        authentication.Authentication.BrowserDetails.newBuilder()
            .setUserAgent(Endpoints.UserAgent)
            .setBrowserType(BrowserType.OTHER)
            .setOS("libgm")
            .setDeviceType(DeviceType.TABLET)
            .build()
    }

    /**
     * Step 1 + 2: register a phone-relay slot and return both the
     * initial tachyon token (so the long-poll can immediately connect
     * to receive the eventual Paired event) and the QR URL the user
     * should scan.
     */
    suspend fun registerAndBuildQrUrl(
        rpc: RpcClient,
        auth: AuthData,
    ): RegisterResult {
        val pubKey = auth.refreshKey.publicKeyDer()
        // Best-effort: refresh the config version from the live web config before pairing so we
        // advertise the version the server currently expects rather than a stale hardcoded one.
        fetchConfig(rpc)
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(
                AuthMessage.newBuilder()
                    .setRequestID(Uuid.random().toString())
                    .setNetwork(QrNetwork)
                    .setConfigVersion(ConfigVersion)
            )
            .setBrowserDetails(BrowserDetails)
            .setKeyData(
                KeyData.newBuilder()
                    .setEcdsaKeys(
                        ECDSAKeys.newBuilder()
                            .setField1(2)
                            .setEncryptedKeys(pubKey.toByteString())
                    )
            )
            .build()

        Log.i(TAG, "RegisterPhoneRelay …")
        val resp = rpc.postProtobuf(
            url = Endpoints.RegisterPhoneRelayUrl,
            body = req,
            responseTemplate = RegisterPhoneRelayResponse.getDefaultInstance(),
        )
        Log.i(TAG, "RegisterPhoneRelay OK; tachyon token ${resp.authKeyData.tachyonAuthToken.size()} bytes")

        val urlData = URLData.newBuilder()
            .setPairingKey(resp.pairingKey)
            .setAESKey(auth.crypto().aesKey.toByteString())
            .setHMACKey(auth.crypto().hmacKey.toByteString())
            .build()
        val qrPayload = Base64.encodeToString(urlData.toByteArray(), Base64.NO_WRAP)
        val qrUrl = Endpoints.QrCodeBaseUrl + qrPayload

        return RegisterResult(
            tachyonToken = resp.authKeyData.tachyonAuthToken.toByteArray(),
            tachyonTtlUs = resp.authKeyData.ttl,
            qrUrl = qrUrl,
        )
    }

    data class RegisterResult(
        val tachyonToken: ByteArray,
        val tachyonTtlUs: Long,
        val qrUrl: String,
    )

    /**
     * Refresh the tachyon token via Pairing/RefreshPhoneRelay using
     * the persisted ECDSA RefreshKey. Returns the new token + TTL.
     * Called periodically by the session manager before the current
     * TTL expires.
     */
    // refreshTachyonToken removed in v1 — the real refresh path uses
    // the separate `Registration.RegisterRefresh` endpoint (not
    // Pairing.RefreshPhoneRelay, which only regenerates a fresh QR for
    // re-pairing). Token TTL is several days; for v1 we surface a
    // re-pair prompt if the user gets logged out.

    /**
     * Refresh the QR code by calling RefreshPhoneRelay. Returns a new
     * QR URL. Port of Go's `Client.RefreshPhoneRelay` / `QRLoginProcess.Wait`.
     */
    suspend fun refreshPhoneRelay(
        rpc: RpcClient,
        auth: AuthData,
    ): RegisterResult {
        val tachyonToken = auth.tachyonToken() ?: error("no tachyon token for QR refresh")
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(
                AuthMessage.newBuilder()
                    .setRequestID(Uuid.random().toString())
                    .setTachyonAuthToken(com.google.protobuf.ByteString.copyFrom(tachyonToken))
                    .setNetwork(QrNetwork)
                    .setConfigVersion(ConfigVersion)
            )
            .build()

        Log.i(TAG, "RefreshPhoneRelay …")
        val resp = rpc.postProtobuf(
            url = Endpoints.RefreshPhoneRelayUrl,
            body = req,
            responseTemplate = RefreshPhoneRelayResponse.getDefaultInstance(),
        )
        Log.i(TAG, "RefreshPhoneRelay OK")

        val urlData = URLData.newBuilder()
            .setPairingKey(resp.pairKey)
            .setAESKey(auth.crypto().aesKey.toByteString())
            .setHMACKey(auth.crypto().hmacKey.toByteString())
            .build()
        val qrPayload = Base64.encodeToString(urlData.toByteArray(), Base64.NO_WRAP)
        val qrUrl = Endpoints.QrCodeBaseUrl + qrPayload

        // RefreshPhoneRelay only regenerates a QR pair key; it does NOT issue a new
        // tachyon token (cf libgm pair.go RefreshPhoneRelay). Return the existing
        // token unchanged so callers don't wipe it.
        return RegisterResult(
            tachyonToken = tachyonToken,
            tachyonTtlUs = 0,
            qrUrl = qrUrl,
        )
    }
}
