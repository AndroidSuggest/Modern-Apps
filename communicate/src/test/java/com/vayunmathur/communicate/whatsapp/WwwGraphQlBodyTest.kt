package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.MexEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts the WWW GraphQL HTTP body shape (w2.md §4.1 / §5.3): `access_token` string, `doc_id` as a
 * JSON **number**, `lang`, the literal `Content-Type` JSON key, and raw `variables`.
 */
class WwwGraphQlBodyTest {

    @Test
    fun buildWwwBody_matchesDocumentedEnvelope() {
        val out = MexEnvelope.buildWwwBody(TOKEN, "27056625294008854", """{"input":{"key":"1@newsletter"}}""")
        assertEquals(
            """{"access_token":"$TOKEN","doc_id":27056625294008854,"lang":"en_US",""" +
                """"Content-Type":"application/json","variables":{"input":{"key":"1@newsletter"}}}""",
            out,
        )
    }

    @Test
    fun buildWwwBody_nonNumericDocIdStaysString() {
        val out = MexEnvelope.buildWwwBody(TOKEN, "abc", "{}")
        assertEquals(
            """{"access_token":"$TOKEN","doc_id":"abc","lang":"en_US",""" +
                """"Content-Type":"application/json","variables":{}}""",
            out,
        )
    }

    private companion object {
        const val TOKEN = "WA|1015890928915437|3201f239340c1c8ec6262a6dad04200e"
    }
}
