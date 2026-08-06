package com.vayunmathur.code.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for the pure unified-diff parser. */
class DiffModelTest {

    private val sample = """
        diff --git a/f.txt b/f.txt
        index 111..222 100644
        --- a/f.txt
        +++ b/f.txt
        @@ -1,3 +1,3 @@
         context
        -old line
        +new line
         tail
    """.trimIndent()

    @Test
    fun skipsMetadataBeforeHunk() {
        val rows = parseUnifiedDiff(sample)
        assertTrue(rows.none { it.text.startsWith("diff ") || it.text.startsWith("index ") })
        assertEquals(DiffRowType.HUNK, rows.first().type)
    }

    @Test
    fun classifiesRowsAndLineNumbers() {
        val rows = parseUnifiedDiff(sample).filter { it.type != DiffRowType.HUNK }
        assertEquals(DiffRowType.CONTEXT, rows[0].type)
        assertEquals(1, rows[0].leftNumber)
        assertEquals(1, rows[0].rightNumber)

        assertEquals(DiffRowType.REMOVE, rows[1].type)
        assertEquals("old line", rows[1].text)
        assertEquals(2, rows[1].leftNumber)
        assertEquals(null, rows[1].rightNumber)

        assertEquals(DiffRowType.ADD, rows[2].type)
        assertEquals("new line", rows[2].text)
        assertEquals(null, rows[2].leftNumber)
        assertEquals(2, rows[2].rightNumber)

        assertEquals(DiffRowType.CONTEXT, rows[3].type)
        assertEquals(3, rows[3].leftNumber)
        assertEquals(3, rows[3].rightNumber)
    }

    @Test
    fun removedLineStartingWithDashIsNotAHeader() {
        val diff = "@@ -1,1 +1,1 @@\n--- a dashed removed line\n+kept"
        val rows = parseUnifiedDiff(diff)
        val removed = rows.first { it.type == DiffRowType.REMOVE }
        assertEquals("-- a dashed removed line", removed.text)
    }

    @Test
    fun emptyDiffYieldsNoRows() {
        assertTrue(parseUnifiedDiff("").isEmpty())
    }
}
