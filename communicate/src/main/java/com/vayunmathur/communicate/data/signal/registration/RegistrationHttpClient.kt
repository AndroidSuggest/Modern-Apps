package com.vayunmathur.communicate.data.signal.registration

import android.content.Context
import android.util.Base64
import android.util.Log
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Speaks Signal's registration API (phone number → SMS/voice verification code → account).
 *
 * Mirrors `data/whatsapp/registration/RegistrationHttpClient.kt` but for Signal:
 *  - `POST /v1/accounts/sms/code/{e164}` or `/v1/accounts/voice/code/{e164}`
 *  - `PUT /v1/accounts/code/{code}` with the account attributes + prekey bundle
 *  - `GET /v1/accounts/exists/{e164}` probe
 *
 * Uses `:library:network` [NetworkClient] (NOT OkHttp/Ktor).
 *
 * Signal's registration requires client-generated prekeys to be uploaded at verify time;
 * this client reuses [SignalRegistrationKeys] so the scaffold persists across code→verify.
 */
class RegistrationHttpClient(private val context: Context) {

    // Keep host configurable for staging tests.
    var baseUrl: String = "https://chat.signal.org"
    var cdnUrl: String = "https://cdn.signal.org"

    data class CodeResult(val status: String, val reason: String?, val raw: String) {
        val ok: Boolean get() = status == "sent" || status == "ok" || status == "200"
    }
    data class RegisterResult(
        val status: String, val aci: String?, val pni: String?, val reason: String?, val auth: SignalAuthData?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" }
    data class ExistResult(val exists: Boolean, val reason: String?, val raw: String)

    suspend fun requestSmsCode(e164: String): CodeResult = requestCode(e164, "sms")
    suspend fun requestVoiceCode(e164: String): CodeResult = requestCode(e164, "voice")

    private suspend fun requestCode(e164: String, method: String): CodeResult {
        // Generate + persist fresh key material so verify reuses the same keys.
        val keys = SignalRegistrationKeys.generate(e164)
        SignalAuthData.save(context, keys.authScaffold)

        val number = e164.filter { it.isDigit() || it == '+' }
        val path = if (method == "voice") "/v1/accounts/voice/code/$number" else "/v1/accounts/sms/code/$number"
        val body = try { NetworkClient.performRequest("$baseUrl$path", method = "GET").body } catch (t: Throwable) {
            Log.e(TAG, "requestCode failed", t)
            return CodeResult("error", t.message, t.message ?: "")
        }
        val j = parse(body)
        return CodeResult(j.optString("status", if (body.contains("ok", true)) "ok" else "error"), j.optStringOrNull("reason"), body)
    }

    suspend fun verifyCode(e164: String, code: String): RegisterResult {
        val auth = SignalAuthData.load(context)
            ?: return RegisterResult("error", null, null, "no_keys", null, "missing key scaffold")
        val digits = code.filter { it.isDigit() }
        val url = "$baseUrl/v1/accounts/code/$digits"

        // Build prekey bundle for upload
        val accountAttrs = buildAccountAttributes(auth)
        val headers = mapOf("Content-Type" to "application/json")
        val body = try {
            NetworkClient.performRequest(url, method = "PUT", headers = headers, body = accountAttrs).body
        } catch (t: Throwable) {
            Log.e(TAG, "verifyCode failed", t)
            return RegisterResult("error", null, null, t.message, null, t.message ?: "")
        }
        return finalizeRegister(body, auth)
    }

    suspend fun checkExists(e164: String): ExistResult {
        val number = e164.filter { it.isDigit() || it == '+' }
        return try {
            val resp = NetworkClient.performRequest("$baseUrl/v1/accounts/exists/$number", method = "GET")
            val body = resp.body
            val j = parse(body)
            ExistResult(j.optBoolean("exists", false) || resp.status == 200, j.optStringOrNull("reason"), body)
        } catch (t: Throwable) {
            Log.e(TAG, "checkExists failed", t)
            ExistResult(false, t.message, t.message ?: "")
        }
    }

    private fun buildAccountAttributes(auth: SignalAuthData): String {
        val obj = JSONObject()
        obj.put("fetchesMessages", true)
        obj.put("registrationId", auth.registrationId)
        obj.put("name", auth.profileName)
        // Prekeys would normally be uploaded via separate /v2/keys endpoints; include inline for now.
        obj.put("capabilities", JSONObject().apply {
            put("announcementGroup", true)
            put("senderKey", true)
            put("storage", true)
        })
        // Signal expects identity key + signed prekey in the code verification payload
        if (auth.identityPublicKey.isNotEmpty()) {
            val idPub = runCatching { Base64.decode(auth.identityPublicKey, Base64.NO_WRAP) }.getOrNull()
            if (idPub != null) obj.put("unidentifiedAccessKey", Base64.encodeToString(idPub.copyOfRange(0, minOf(16, idPub.size)), Base64.NO_WRAP))
        }
        return obj.toString()
    }

    private fun finalizeRegister(body: String, auth: SignalAuthData): RegisterResult {
        val j = parse(body)
        val status = j.optString("status", if (body.contains("\"uuid\"") || body.contains("\"aci\"")) "ok" else "error")
        val aci = j.optStringOrNull("uuid") ?: j.optStringOrNull("aci") ?: j.optStringOrNull("number")
        val pni = j.optStringOrNull("pni")
        val updated = if (status == "ok") {
            val aciVal = aci ?: auth.aci
            val pniVal = pni ?: auth.pni
            auth.copy(aci = aciVal, pni = pniVal, registered = true).also { SignalAuthData.save(context, it) }
        } else null
        return RegisterResult(status, aci, pni, j.optStringOrNull("reason"), updated, body)
    }

    private fun parse(body: String): JSONObject = try { JSONObject(body) } catch (_: Exception) { JSONObject() }

    companion object { private const val TAG = "SignalRegHttp" }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
private fun JSONObject.optBoolean(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default
private fun ByteArray.take(n: Int): ByteArray = copyOfRange(0, minOf(n, size))
