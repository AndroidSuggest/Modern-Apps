package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.MexEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Asserts the MEX-over-XMPP `<query>` envelope shape (w2.md §5.2):
 * `{"queryId":"<docId>","variables":{…}}`. Pure kotlinx — no Android/org.json.
 */
class MexEnvelopeTest {

    @Test
    fun buildQueryJson_wrapsVariablesWithStringQueryId() {
        val out = MexEnvelope.buildQueryJson("27462649126753603", """{"group_input":{"group_jid":"1@g.us"}}""")
        assertEquals(
            """{"queryId":"27462649126753603","variables":{"group_input":{"group_jid":"1@g.us"}}}""",
            out,
        )
    }

    @Test
    fun buildQueryJson_blankVariablesBecomesEmptyObject() {
        val out = MexEnvelope.buildQueryJson("123", "")
        assertEquals("""{"queryId":"123","variables":{}}""", out)
    }

    @Test
    fun buildQueryJson_malformedVariablesFallsBackToEmptyObject() {
        val out = MexEnvelope.buildQueryJson("123", "not json")
        assertEquals("""{"queryId":"123","variables":{}}""", out)
    }
}
