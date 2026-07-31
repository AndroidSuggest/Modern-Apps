package com.vayunmathur.passwords.cable

import android.util.Log
import com.vayunmathur.passwords.data.PasskeyDao
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Handles decrypted CTAP2 commands for the caBLE authenticator and produces the response bytes
 * (`status || CBOR`). Supports `authenticatorGetInfo` and `authenticatorGetAssertion`; other
 * commands (notably `makeCredential`) return an error, as v1 is sign-in only.
 *
 * Credential lookup and signing reuse the shared [WebAuthnAuthenticator] core (Phase 0) and the
 * existing [PasskeyDao] store, so cross-device sign-in produces byte-identical assertions to the
 * same-device Credential Manager path.
 */
@OptIn(ExperimentalEncodingApi::class)
class CtapProcessor(
    private val passkeyDao: PasskeyDao,
    /** Whether the user was verified (biometric) when the session was approved. */
    private val userVerified: Boolean,
) {
    private val urlDecoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val urlEncoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Processes one CTAP command message; never throws (errors map to CTAP status bytes). */
    suspend fun process(command: ByteArray): ByteArray {
        if (command.isEmpty()) return Ctap.response(Ctap.ERR_INVALID_CBOR)
        val payload = command.copyOfRange(1, command.size)
        return try {
            when (command[0].toInt() and 0xFF) {
                Ctap.CMD_GET_INFO ->
                    Ctap.response(Ctap.OK, CtapGetInfoResponse().encode())
                Ctap.CMD_GET_ASSERTION -> handleGetAssertion(payload)
                else -> Ctap.response(Ctap.ERR_NOT_ALLOWED)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CTAP processing error", e)
            Ctap.response(Ctap.ERR_OTHER)
        }
    }

    private suspend fun handleGetAssertion(payload: ByteArray): ByteArray {
        val req = CtapGetAssertionRequest.parse(payload)
        Log.d(TAG, "getAssertion rpId=${req.rpId} allowList=${req.allowList.size} " +
            "up=${req.userPresenceRequired} uv=${req.userVerificationRequired} " +
            "clientDataHash=${req.clientDataHash.size}B")
        req.allowList.forEachIndexed { i, d -> Log.d(TAG, "  allow[$i] id=${hex(d.id)}") }

        if (req.userVerificationRequired && !userVerified) {
            Log.w(TAG, "UV required but user not verified")
            return Ctap.response(Ctap.ERR_OPERATION_DENIED)
        }

        val allForRp = passkeyDao.getByRpId(req.rpId)
        Log.d(TAG, "stored passkeys for ${req.rpId}: ${allForRp.size} " +
            allForRp.joinToString { "credId=${it.credentialId}" })

        val passkey = resolveCredential(req) ?: run {
            Log.w(TAG, "no matching credential -> ERR_NO_CREDENTIALS")
            return Ctap.response(Ctap.ERR_NO_CREDENTIALS)
        }
        Log.d(TAG, "using credential credId=${passkey.credentialId} rpId=${passkey.rpId} userId=${passkey.userId}")

        val assertion = WebAuthnAuthenticator.signAssertion(
            passkey = passkey,
            clientDataHash = req.clientDataHash,
            passkeyDao = passkeyDao,
            userPresent = req.userPresenceRequired,
            userVerified = userVerified,
        )

        val response = CtapGetAssertionResponse(
            credentialId = runCatching { urlDecoder.decode(passkey.credentialId) }.getOrElse { ByteArray(0) },
            authData = assertion.authenticatorData,
            signature = assertion.signature,
            userId = decodeUserId(passkey.userId),
        )
        Log.d(TAG, "assertion signed: authData=${assertion.authenticatorData.size}B sig=${assertion.signature.size}B")
        return Ctap.response(Ctap.OK, response.encode())
    }

    /** allowList (by credential id) takes precedence; otherwise the first passkey for the rpId. */
    private suspend fun resolveCredential(req: CtapGetAssertionRequest) =
        if (req.allowList.isNotEmpty()) {
            req.allowList.firstNotNullOfOrNull { desc ->
                passkeyDao.getByCredentialId(urlEncoder.encode(desc.id))
                    ?.takeIf { it.rpId == req.rpId }
            }
        } else {
            passkeyDao.getByRpId(req.rpId).maxByOrNull { it.lastUsedTime }
        }

    /** Stored user handles are base64url; fall back to raw UTF-8 if not decodable. */
    private fun decodeUserId(userId: String): ByteArray =
        runCatching { urlDecoder.decode(userId) }.getOrElse { userId.toByteArray() }

    companion object {
        private const val TAG = "CtapProcessor"

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
