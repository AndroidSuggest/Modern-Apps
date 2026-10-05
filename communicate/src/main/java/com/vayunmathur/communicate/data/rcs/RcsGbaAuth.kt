package com.vayunmathur.communicate.data.rcs

/**
 * GBA-shaped credentials + digest auth for FT-over-HTTP (3GPP TS 33.220 /
 * RFC 2617).
 *
 * Live bootstrapping lives in [RcsGbaBootstrap] (stub-linked
 * `TelephonyManager.bootstrapAuthenticationRequest`, SMS-role permission).
 * This object holds:
 * - [GbaCredentials]: bootstrapped NAF credentials (btId + key), from live
 *   bootstrap or privileged injection ([injected]).
 * - [digestResponse]/[digestAuthorizationHeader]: the qop=auth construction
 *   both the GBA and the plain-digest paths share, unit-tested against the
 *   RFC 2617 known-answer vector.
 *
 * When bootstrapping is unavailable (no privilege, modem refusal), uploads
 * use the plain-digest fallback (empty password), exactly like a client
 * whose GBA challenge fails — the server either accepts or the upload
 * degrades, never crashes.
 */
object RcsGbaAuth {
    /** Bootstrapped NAF credentials: digest username + raw key. */
    data class GbaCredentials(val btId: String, val key: ByteArray)

    /**
     * Injected GBA credentials (privileged builds only). Null in normal
     * dev builds — the uploader falls back to plain digest.
     */
    @Volatile
    var injected: GbaCredentials? = null

    /**
     * Digest `response` hash (RFC 2617 qop=auth) — pure, unit-tested against
     * the RFC's known-answer vector.
     */
    internal fun digestResponse(
        username: String,
        password: String,
        realm: String,
        nonce: String,
        method: String,
        uri: String,
        cnonce: String,
    ): String {
        val ha1 = md5("$username:$realm:$password")
        val ha2 = md5("$method:$uri")
        return md5("$ha1:$nonce:00000001:$cnonce:auth:$ha2")
    }

    /**
     * Full digest `Authorization` header *value* from a `WWW-Authenticate` /
     * `Proxy-Authenticate` challenge (RFC 2617 qop=auth). Shared by SIP
     * challenge retries ([RcsSipTransport]) and FT-over-HTTP
     * ([RcsFileTransferHttp]). Empty when the challenge isn't digest or
     * lacks realm/nonce.
     */
    private const val CNONCE_LENGTH = 16
    internal fun digestAuthorizationHeader(
        challenge: String,
        method: String,
        uri: String,
        username: String,
        password: String = "",
        cnonce: String = java.util.UUID.randomUUID().toString().replace("-", "").take(CNONCE_LENGTH),
    ): String {
        if (!challenge.contains("Digest", ignoreCase = true)) return ""
        fun param(name: String): String =
            Regex("$name=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(challenge)
                ?.groupValues?.getOrNull(1).orEmpty()
        val realm = param("realm")
        val nonce = param("nonce")
        if (realm.isBlank() || nonce.isBlank()) return ""
        val response = digestResponse(username, password, realm, nonce, method, uri, cnonce)
        return "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", " +
            "response=\"$response\", qop=auth, nc=00000001, cnonce=\"$cnonce\""
    }

    private fun md5(s: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
