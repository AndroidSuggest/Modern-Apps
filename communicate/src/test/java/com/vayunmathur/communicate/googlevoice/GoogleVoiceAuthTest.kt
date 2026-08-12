package com.vayunmathur.communicate.googlevoice

import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GoogleVoiceAuthTest {

    @Test
    fun sha1Hex_matchesKnownVector() {
        // Standard SHA-1("abc") test vector.
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", GoogleVoiceAuth.sha1Hex("abc"))
    }

    @Test
    fun sapisidHash_hasTimestampPrefixAndSignsExpectedInput() {
        val ts = 1000L
        val sapisid = "SID"
        val origin = "https://voice.google.com"
        val hash = GoogleVoiceAuth.sapisidHash(ts, sapisid, origin)

        assertTrue(hash.startsWith("1000_"))
        val expected = "1000_" + GoogleVoiceAuth.sha1Hex("1000 SID https://voice.google.com")
        assertEquals(expected, hash)
    }

    @Test
    fun authorization_usesSapisidHashScheme() {
        val auth = GoogleVoiceAuth.authorization(1000L, "SID")
        assertTrue(auth.startsWith("SAPISIDHASH 1000_"))
    }

    @Test
    fun headers_containRequiredAuthFields() {
        val headers = GoogleVoiceAuth.headers(
            cookieHeader = "SAPISID=SID; OTHER=1",
            sapisid = "SID",
            authUser = "0",
            nowSeconds = 1000L,
        )
        assertEquals("SAPISID=SID; OTHER=1", headers["Cookie"])
        assertEquals("0", headers["X-Goog-AuthUser"])
        assertEquals("application/json+protobuf", headers["Content-Type"])
        assertEquals("https://voice.google.com", headers["Origin"])
        assertTrue(headers["Authorization"]!!.startsWith("SAPISIDHASH "))
    }
}
