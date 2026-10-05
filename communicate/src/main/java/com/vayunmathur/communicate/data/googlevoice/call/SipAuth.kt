package com.vayunmathur.communicate.data.googlevoice.call

/**
 * SIP digest authentication for [SipClient] (split for file length).
 * Extension functions on [SipClient]; behavior identical, call sites unchanged.
 */

/** RFC 2069-style MD5 SIP digest (no qop), matching Google's registrar challenge. */
internal fun SipClient.digestAuth(method: String, uri: String, nonce: String, realm: String): String {
    val ha1 = md5("$authUsername:$realm:$authPassword")
    val ha2 = md5("$method:$uri")
    val response = md5("$ha1:$nonce:$ha2")
    return "Digest algorithm=MD5, username=\"$authUsername\", realm=\"$realm\", " +
        "nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
}

internal fun SipClient.md5(input: String): String {
    val bytes = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
