package com.vayunmathur.communicate.data.googlevoice

import java.security.MessageDigest

/**
 * Builds the authentication material for Google Voice web RPCs.
 *
 * Google's browser RPCs authenticate with cookies plus a `SAPISIDHASH` Authorization
 * header (the same scheme used across Google web properties):
 *
 * ```
 * Authorization: SAPISIDHASH <ts>_<sha1hex(ts + " " + SAPISID + " " + origin)>
 * ```
 *
 * where `ts` is the current unix time in seconds and `origin` is `https://voice.google.com`.
 * See `voice-documentation.md` — the exact hash was not preserved in the HAR, but this is
 * the well-known, stable construction Google uses for these endpoints.
 *
 * The functions here are pure so they can be unit-tested without a device/session.
 */
object GoogleVoiceAuth {

    const val ORIGIN = "https://voice.google.com"
    const val REFERER = "https://voice.google.com/"
    const val CONTENT_TYPE = "application/json+protobuf"

    /** `<ts>_<sha1hex>` per the SAPISIDHASH scheme. */
    fun sapisidHash(timestampSeconds: Long, sapisid: String, origin: String = ORIGIN): String {
        val digestInput = "$timestampSeconds $sapisid $origin"
        val sha1 = sha1Hex(digestInput)
        return "${timestampSeconds}_$sha1"
    }

    /** Full `Authorization` header value. */
    fun authorization(timestampSeconds: Long, sapisid: String, origin: String = ORIGIN): String =
        "SAPISIDHASH ${sapisidHash(timestampSeconds, sapisid, origin)}"

    /**
     * Assemble the header set observed on Voice RPCs. [nowSeconds] is injectable for tests.
     */
    fun headers(
        cookieHeader: String,
        sapisid: String,
        authUser: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Map<String, String> = buildMap {
        put("Authorization", authorization(nowSeconds, sapisid))
        put("Cookie", cookieHeader)
        put("Content-Type", CONTENT_TYPE)
        put("Origin", ORIGIN)
        put("Referer", REFERER)
        put("X-Goog-AuthUser", authUser)
        put("X-Requested-With", "XMLHttpRequest")
        // Voice web sends a recent Chrome UA; keep it stable so the endpoints behave.
        put(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36",
        )
    }

    fun sha1Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
