package com.vayunmathur.communicate.data.whatsapp.registration

import android.content.Context
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.library.network.NetworkClient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Speaks WhatsApp's primary-registration `/v2/ endpoints` protocol (own phone-number registration).
 *
 * Flow: [requestCode] (→ SMS/voice OTP) → [register] (with the OTP) → persisted [WhatsAppAuthData]
 * with `new_jid`. [checkExist] probes state; [submitTwoFactor] handles the `/v2/security` PIN path.
 *
 * Parameter set + encodings are matched **exactly** to the pinned APK's `KotlinRegistrationBridge` +
 * `C34244EyE` request builder (see [RegEncoding]) to avoid `bad_param`:
 *  - plain `A01`: cc, in, lg, lc, fdid, token, method, code
 *  - `A03` (UUID→16B→url-b64): expid
 *  - `A05` (percent-encoded raw bytes, stored pre-encoded): id, backup_token
 *  - `A00` ("true"/"false"): clicked_education_link, manage_call_permission, call_log_permission
 *  - `A04` (url-b64) E2E bundle: authkey, e_ident, e_keytype, e_regid, e_skey_id, e_skey_val, e_skey_sig
 *
 * Body is `application/x-www-form-urlencoded`. Default is the PLAIN body (the official client falls
 * back to plain when its ENC layer fails, so the server accepts it); [useEncWrapper] adds ENC/H.
 */
class RegistrationHttpClient(
    private val context: Context,
    private val useEncWrapper: Boolean = false,
) {
    private val fingerprint = WhatsAppDeviceFingerprint.getOrCreate(context)

    // ------------------------------------------------------------------ results

    data class CodeResult(
        val status: String, val method: String?, val length: Int?,
        val retryAfter: Long?, val reason: String?, val raw: String,
    ) { val ok: Boolean get() = status == "sent" || status == "ok" }

    data class RegisterResult(
        val status: String, val newJid: String?, val login: String?, val serverTime: Long?,
        val reason: String?, val auth: WhatsAppAuthData?, val raw: String,
    ) { val ok: Boolean get() = status == "ok" }

    data class ExistResult(val status: String, val reason: String?, val raw: String)

    // ------------------------------------------------------------------ endpoints

    /** POST `/v2/code` — request an OTP. Generates + persists fresh key material first. */
    suspend fun requestCode(cc: String, number: String, method: String = "sms"): CodeResult {
        val keys = RegistrationKeys.generate("$cc$number")
        WhatsAppAuthData.save(context, keys.authScaffold)

        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        // Token uses the NATIONAL number only (APK: ES2.A00.A01(app, $phoneNumber); $countryCode is
        // a separate param, and server login = cc + in confirms `in`/$phoneNumber is national).
        p.a01("token", RegistrationAttestation.computeToken(context, number))
        p.a01("method", method)
        p.a00("clicked_education_link", false)
        p.a00("manage_call_permission", false)
        p.a00("call_log_permission", false)
        p.bundle(RegistrationKeys.bundleFields(keys.authScaffold))

        val body = send("code", p)
        val j = parse(body)
        return CodeResult(
            status = j.optString("status", "error"),
            method = j.optStringOrNull("method"),
            length = j.optIntOrNull("length"),
            retryAfter = j.optLongOrNull("retry_after"),
            reason = j.optStringOrNull("reason"),
            raw = body,
        )
    }

    /** POST `/v2/register` — submit the OTP and finalize the primary line. */
    suspend fun register(cc: String, number: String, code: String): RegisterResult {
        val auth = WhatsAppAuthData.load(context)
            ?: return RegisterResult("error", null, null, null, "no_keys", null, "missing key scaffold")
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("code", code.filter { it.isDigit() })
        p.bundle(RegistrationKeys.bundleFields(auth))

        val body = send("register", p)
        return finalizeRegister(body, auth)
    }

    /** POST `/v2/security` — submit the account's 2FA PIN when register returns `security_code`. */
    suspend fun submitTwoFactor(cc: String, number: String, pin: String): RegisterResult {
        val auth = WhatsAppAuthData.load(context)
            ?: return RegisterResult("error", null, null, null, "no_keys", null, "missing key scaffold")
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        p.a01("code", pin.filter { it.isDigit() })
        p.bundle(RegistrationKeys.bundleFields(auth))

        val body = send("security", p)
        return finalizeRegister(body, auth)
    }

    /** POST `/v2/exist` — probe whether the number is already registered on this key material. */
    suspend fun checkExist(cc: String, number: String): ExistResult {
        // DEBUG: log token variants so we can compare to the offline reference (no SMS sent).
        runCatching {
            Log.i(TAG, "debug token national=$number -> ${RegistrationAttestation.computeToken(context, number)}")
            Log.i(TAG, "debug token full=$cc$number -> ${RegistrationAttestation.computeToken(context, "$cc$number")}")
        }
        val auth = WhatsAppAuthData.load(context)
        val p = RegParams()
        p.a01("cc", cc)
        p.a01("in", number)
        p.addCommon()
        p.addDevice()
        if (auth != null) p.bundle(RegistrationKeys.bundleFields(auth))
        val body = send("exist", p)
        val j = parse(body)
        return ExistResult(j.optString("status", "error"), j.optStringOrNull("reason"), body)
    }

    // ------------------------------------------------------------------ internals

    private fun finalizeRegister(body: String, auth: WhatsAppAuthData): RegisterResult {
        val j = parse(body)
        val status = j.optString("status", "error")
        val newJid = j.optStringOrNull("new_jid")
        val login = j.optStringOrNull("login")
        val lid = j.optStringOrNull("lid")
        // Success may omit `new_jid` (e.g. type:"existing" re-registration); the server returns the
        // phone in `login`, so derive the JID from it. Registration is complete whenever status==ok.
        val updated = if (status == "ok") {
            val wid = when {
                newJid != null -> normalizeJid(newJid)
                login != null -> "$login@s.whatsapp.net"
                else -> auth.wid
            }
            auth.copy(
                wid = wid,
                lid = lid?.let { if (it.contains("@")) it else "$it@lid" } ?: auth.lid,
                serverTime = j.optLongOrNull("server_time") ?: 0L,
                registered = true,
                loggedInAt = System.currentTimeMillis() / 1000,
            ).also { WhatsAppAuthData.save(context, it) }
        } else {
            null
        }
        return RegisterResult(
            status = status,
            newJid = newJid ?: login?.let { "$it@s.whatsapp.net" },
            login = login,
            serverTime = j.optLongOrNull("server_time"),
            reason = j.optStringOrNull("reason"),
            auth = updated,
            raw = body,
        )
    }

    /**
     * Mirrors `C34244EyE`: values are stored either PLAIN (A00/A01/A02/A03/A04) or PRE-ENCODED via
     * percent-encoding (A05, tracked in [raw]). At query build the raw values are appended as-is; all
     * others are URL-encoded — exactly as the APK's request transform does.
     */
    private inner class RegParams {
        val map = LinkedHashMap<String, String>()
        val raw = HashSet<String>()

        fun a00(key: String, value: Boolean) { map[key] = if (value) "true" else "false" }
        fun a01(key: String, value: String) { map[key] = value }
        fun a02(key: String, value: String?) { if (value != null) map[key] = value }
        fun a03(key: String, uuid: String) { map[key] = RegEncoding.b64Url(RegEncoding.uuidToBytes(uuid)) }
        fun a05(key: String, bytes: ByteArray) { map[key] = RegEncoding.percentEncode(bytes); raw.add(key) }
        fun bundle(fields: Map<String, String>) { map.putAll(fields) } // already url-b64 (A04), non-raw

        fun addCommon() {
            a01("lg", context.resources.configuration.locales[0].language.ifEmpty { "en" })
            a01("lc", context.resources.configuration.locales[0].country.ifEmpty { "US" })
            a01("fdid", fingerprint.fdid)
            a03("expid", fingerprint.expid)
            a05("id", fingerprint.recoveryToken)
            a05("backup_token", fingerprint.backupToken)
        }

        /**
         * Common/device params added by the APK's `F4L` layer (merged via `A06(map)`). Confirmed
         * against the native param-key table in libwhatsappmerged.so: WhatsApp does NOT send a
         * `platform` form param — the server derives platform from the User-Agent — so we must not
         * send one. These device fields are the real F4L set; values are URL-safe.
         */
        fun addDevice() {
            val tm = runCatching { context.getSystemService(TelephonyManager::class.java) }.getOrNull()
            val netOp = tm?.networkOperator.orEmpty()
            val simOp = tm?.simOperator.orEmpty()
            a01("mcc", if (netOp.length >= 3) netOp.substring(0, 3) else "000")
            a01("mnc", if (netOp.length > 3) netOp.substring(3) else "000")
            a01("sim_mcc", if (simOp.length >= 3) simOp.substring(0, 3) else "000")
            a01("sim_mnc", if (simOp.length > 3) simOp.substring(3) else "000")
            a01("network_radio_type", "1")
            a01("simnum", "1")
            a01("hasinrc", "0")
            a01("pid", android.os.Process.myPid().toString())
            a01("rc", "0")
        }

        fun query(): String = map.entries.joinToString("&") { (k, v) ->
            val encoded = if (k in raw) v else URLEncoder.encode(v, "UTF-8")
            "$k=$encoded"
        }
    }

    private suspend fun send(path: String, params: RegParams): String {
        val query = params.query()
        val body = if (useEncWrapper) {
            val encv = RegistrationAttestation.encryptQueryString(query)
            if (encv != null) {
                val h = RegistrationAttestation.signWithAttestation(fingerprint.attestationKey, encv)
                "ENC=${URLEncoder.encode(encv, "UTF-8")}&H=${URLEncoder.encode(h, "UTF-8")}"
            } else {
                query
            }
        } else {
            query
        }

        val headers = mapOf(
            "User-Agent" to userAgent(),
            "Content-Type" to "application/x-www-form-urlencoded",
            "Accept" to "text/json",
        )
        return try {
            val resp = NetworkClient.performRequest(
                url = WhatsAppRegistrationConstants.endpoint(path),
                method = "POST",
                headers = headers,
                body = body,
                useSystemTrust = true,
            )
            Log.i(TAG, "/v2/$path -> ${resp.status}: ${resp.body.take(400)}")
            resp.body
        } catch (t: Throwable) {
            Log.e(TAG, "/v2/$path request failed", t)
            """{"status":"error","reason":"network:${t.message}"}"""
        }
    }

    /** WhatsApp/<ver> Android/<osrel> Device/<manufacturer>-<model> (server parses platform here). */
    private fun userAgent(): String {
        val device = "${Build.MANUFACTURER}-${Build.MODEL}".replace(' ', '_')
        return "WhatsApp/${WhatsAppProtocol.WA_VERSION_NAME} Android/${Build.VERSION.RELEASE} Device/$device"
    }

    private fun parse(body: String): JSONObject = try {
        JSONObject(body)
    } catch (_: Exception) {
        JSONObject().put("status", "error").put("reason", "unparseable")
    }

    private fun normalizeJid(jid: String): String =
        if (jid.contains("@")) jid else "$jid@s.whatsapp.net"

    companion object {
        private const val TAG = "WARegHttp"
    }
}

// -- small JSONObject null-safe helpers --
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null
private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null
