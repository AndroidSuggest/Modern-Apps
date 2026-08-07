package com.vayunmathur.code.util

import com.vayunmathur.code.syntax.Language
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the pure diagnostics validators. JSON is validated via `org.json`, which is only
 * available on-device (stubbed in JVM unit tests), so it is exercised manually rather than here.
 */
class DiagnosticsTest {

    @Test
    fun mergeConflictMarkersAreErrors() {
        val text = "a\n<<<<<<< HEAD\nx\n=======\ny\n>>>>>>> other\nb"
        val diags = computeDiagnostics(text, Language.PLAINTEXT)
        assertEquals(1, diags.count { it.severity == DiagnosticSeverity.ERROR })
        assertEquals(1, diags.first().line)
        assertTrue(diags.first().message.contains("merge"))
    }

    @Test
    fun todoAndFixmeAreInfo() {
        val text = "line one\n// TODO clean up\n// FIXME later"
        val diags = computeDiagnostics(text, Language.PLAINTEXT)
        val infos = diags.filter { it.severity == DiagnosticSeverity.INFO }
        assertEquals(listOf("TODO", "FIXME"), infos.map { it.message })
        assertEquals(listOf(1, 2), infos.map { it.line })
    }

    @Test
    fun yamlTabIndentationIsError() {
        val text = "root:\n\tchild: 1"
        val diags = computeDiagnostics(text, Language.YAML)
        val err = diags.single { it.severity == DiagnosticSeverity.ERROR }
        assertEquals(1, err.line)
        assertEquals(0, err.startCol)
    }

    @Test
    fun balancedBracketsProduceNoDiagnostics() {
        val diags = computeDiagnostics("fun f() { val a = x[0] }", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "expected no diagnostics, got $diags")
    }

    @Test
    fun unmatchedCloserIsError() {
        val diags = computeDiagnostics("fun f() { )", Language.KOTLIN)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.ERROR && it.message.contains(")") })
    }

    @Test
    fun unclosedOpenerIsWarning() {
        val diags = computeDiagnostics("fun f( {", Language.KOTLIN)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.WARNING })
    }

    @Test
    fun bracketsInsideStringsAreIgnored() {
        val diags = computeDiagnostics("val s = \"a ( b [ c\"", Language.KOTLIN)
        assertTrue(diags.none { it.severity != DiagnosticSeverity.INFO }, "got $diags")
    }

    @Test
    fun bracketsInsideLineCommentsAreIgnored() {
        val diags = computeDiagnostics("val x = 1 // )]}\n", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun bracketsInsideBlockCommentsAreIgnored() {
        val diags = computeDiagnostics("/* ( [ { */\nval y = 2", Language.KOTLIN)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun wellFormedXmlHasNoDiagnostics() {
        val diags = computeDiagnostics("<root><child/></root>", Language.XML)
        assertTrue(diags.isEmpty(), "got $diags")
    }

    @Test
    fun malformedXmlIsReported() {
        val diags = computeDiagnostics("<root><child></root>", Language.XML)
        assertTrue(diags.any { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun htmlIsNotFlaggedAsMalformedXml() {
        val diags = computeDiagnostics("<!DOCTYPE html>\n<html><br></html>", Language.XML)
        assertTrue(diags.none { it.severity == DiagnosticSeverity.ERROR })
    }
}
