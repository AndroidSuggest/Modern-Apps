package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.MexPersistedQueryProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Asserts the persist-ids JSON parser (w2.md §5.5 shape `{"version":1,"data":{name:docId}}`).
 * Pure kotlinx — no Android assets needed.
 */
class MexPersistedQueryProviderTest {

    @Test
    fun parsePersistIds_readsOperationNameToDocId() {
        val map = MexPersistedQueryProvider.parsePersistIds(
            """{"version":1,"data":{"QueryGroupInfo":"27462649126753603","UsernameSetMutation":"7225825540870559"}}""",
        )
        assertEquals("27462649126753603", map["QueryGroupInfo"])
        assertEquals("7225825540870559", map["UsernameSetMutation"])
        assertNull(map["NotAnOp"])
    }

    @Test
    fun parsePersistIds_emptyDataYieldsEmptyMap() {
        assertEquals(emptyMap(), MexPersistedQueryProvider.parsePersistIds("""{"version":1,"data":{}}"""))
    }

    @Test
    fun parsePersistIds_malformedYieldsEmptyMap() {
        assertEquals(emptyMap(), MexPersistedQueryProvider.parsePersistIds("not json at all"))
    }
}
