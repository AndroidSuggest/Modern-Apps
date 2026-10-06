package com.vayunmathur.email.network.imap

import java.io.IOException

/**
 * IMAP FETCH/STORE/EXPUNGE pipeline. Extracted from [RawImapConnection] to keep
 * it under the function cap. Behavior identical — only moved.
 */
internal class ImapFetchPipeline(
    private val io: ImapWireIo,
    private val nextTag: () -> String,
) {
    companion object {
        const val HEADER_FIELDS = "From To Cc Subject Date Message-ID References In-Reply-To " +
            "List-Unsubscribe List-Unsubscribe-Post X-GM-THRID"
        private const val HEADER_SNIFF_LEN = 1024
        private const val NO_UID = -1L
        private const val HEADER_FROM_MARKER = "From:"
        private const val HEADER_SUBJECT_MARKER = "Subject:"
        private const val HEADER_FIELD_SEPARATOR = ":"
        private const val CRLF = "\r\n"

        fun looksLikeHeaderBlock(bytes: ByteArray): Boolean {
            if (bytes.isEmpty()) return false
            val sample = String(bytes, 0, minOf(bytes.size, HEADER_SNIFF_LEN), Charsets.UTF_8)
            return sample.contains(HEADER_FROM_MARKER) ||
                sample.contains(HEADER_SUBJECT_MARKER) ||
                (sample.contains(HEADER_FIELD_SEPARATOR) && sample.contains(CRLF))
        }

        fun isBodyLiteral(line: String): Boolean {
            if (line.contains("HEADER")) return false
            if (line.contains("BODY[]") || line.contains("BODY.PEEK[]")) return true
            return Regex("""BODY\[.*\]""").containsMatchIn(line)
        }
    }

    fun uidFetchHeaders(uidSet: String): List<ImapFetchResult> {
        if (uidSet.isBlank()) return emptyList()
        val tag = nextTag()
        io.sendLine("$tag UID FETCH $uidSet (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS ($HEADER_FIELDS)])")
        return collectFetch(tag)
    }

    fun fetchHeadersForSeq(seqSet: String): List<ImapFetchResult> {
        if (seqSet.isBlank()) return emptyList()
        val tag = nextTag()
        io.sendLine("$tag FETCH $seqSet (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS ($HEADER_FIELDS)])")
        return collectFetch(tag)
    }

    fun uidFetchFullSet(uidSet: String): List<ImapFetchResult> {
        if (uidSet.isBlank()) return emptyList()
        val tag = nextTag()
        io.sendLine("$tag UID FETCH $uidSet (UID FLAGS INTERNALDATE BODY.PEEK[])")
        return collectFetch(tag)
    }

    fun uidFetchFull(uid: Long): ImapFetchResult? = uidFetchFullSet(uid.toString()).firstOrNull()

    fun uidFetchPartBytes(uid: Long, section: String): ByteArray? {
        val tag = nextTag()
        io.sendLine("$tag UID FETCH $uid (BODY.PEEK[$section])")
        val results = collectFetch(tag)
        return results.firstOrNull()?.let { it.bodyBytes ?: it.headerBytes }
    }

    fun uidStoreFlags(uid: Long, flag: String, add: Boolean = true) {
        val tag = nextTag()
        val op = if (add) "+FLAGS" else "-FLAGS"
        io.sendLine("$tag UID STORE $uid $op ($flag)")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("STORE failed: $final")
    }

    fun uidStoreFlagsSet(uidSet: String, flag: String, add: Boolean = true) {
        if (uidSet.isBlank()) return
        val tag = nextTag()
        val op = if (add) "+FLAGS" else "-FLAGS"
        io.sendLine("$tag UID STORE $uidSet $op ($flag)")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("STORE $uidSet failed: $final")
    }

    fun expunge() {
        val tag = nextTag()
        io.sendLine("$tag EXPUNGE")
        val (final, _) = readResponse(tag)
        if (!final.uppercase().contains(" OK ")) throw IOException("EXPUNGE failed: $final")
    }

    fun uidExpunge(uid: Long): String {
        var tag = nextTag()
        io.sendLine("$tag UID EXPUNGE $uid")
        var (final, _) = readResponse(tag)
        if (final.uppercase().contains(" OK ")) return final
        // Fallback STORE \Deleted + EXPUNGE
        tag = nextTag()
        io.sendLine("$tag UID STORE $uid +FLAGS (\\Deleted)")
        readResponse(tag)
        tag = nextTag()
        io.sendLine("$tag EXPUNGE")
        val (final2, _) = readResponse(tag)
        return final2
    }

    internal fun collectFetch(tag: String): List<ImapFetchResult> {
        val accum = FetchAccum()
        var done = false
        while (!done) {
            val pair = io.readLineWithLiteral()
            if (pair == null) {
                done = true
            } else {
                val (line, literal) = pair
                if (line.startsWith(tag)) {
                    accum.flush()
                    done = true
                } else {
                    accum.absorb(line, literal)
                }
            }
        }
        return accum.results
    }

    private class FetchAccum {
        val results = mutableListOf<ImapFetchResult>()
        private var currentUid: Long = -1
        private var currentFlags: List<String> = emptyList()
        private var currentDate: String? = null
        private var currentHeaderBytes: ByteArray? = null
        private var currentBodyBytes: ByteArray? = null
        private var accumulating = false

        fun flush() {
            if (accumulating && currentUid != NO_UID) {
                results.add(
                    ImapFetchResult(
                        currentUid,
                        currentFlags,
                        currentDate,
                        currentHeaderBytes,
                        currentBodyBytes,
                    ),
                )
            }
        }

        fun absorb(line: String, literal: ByteArray?) {
            if (line.startsWith("*") && line.contains("FETCH")) {
                absorbFetchStart(line, literal)
            } else if (literal != null) {
                absorbContinuationLiteral(line, literal)
            } else {
                absorbContinuationLine(line)
            }
        }

        private fun absorbFetchStart(line: String, literal: ByteArray?) {
            flush()
            reset()
            accumulating = true

            ImapParser.parseUid(line)?.let { currentUid = it }
            ImapParser.parseFlagsFromFetch(line)?.let { currentFlags = it }
            ImapParser.parseInternalDate(line)?.let { currentDate = it }

            if (literal != null) {
                absorbStartLiteral(line, literal)
            }
        }

        private fun reset() {
            currentUid = -1
            currentFlags = emptyList()
            currentDate = null
            currentHeaderBytes = null
            currentBodyBytes = null
        }

        private fun absorbStartLiteral(line: String, literal: ByteArray) {
            // Distinguish BODY[] vs HEADER
            if (isBodyLiteral(line)) {
                currentBodyBytes = literal
            } else if (line.contains("HEADER")) {
                currentHeaderBytes = literal
            } else {
                if (looksLikeHeaderBlock(literal)) currentHeaderBytes = literal else currentBodyBytes = literal
            }
        }

        private fun absorbContinuationLiteral(line: String, literal: ByteArray) {
            if (looksLikeHeaderBlock(literal)) {
                if (currentHeaderBytes == null) {
                    currentHeaderBytes = literal
                } else {
                    currentBodyBytes = literal
                }
            } else {
                currentBodyBytes = literal
            }
            if (currentUid == NO_UID) ImapParser.parseUid(line)?.let { currentUid = it }
        }

        private fun absorbContinuationLine(line: String) {
            if (line.contains("UID") && currentUid == NO_UID) {
                ImapParser.parseUid(line)?.let { currentUid = it }
            }
            if (line.contains("FLAGS") && currentFlags.isEmpty()) {
                ImapParser.parseFlagsFromFetch(line)?.let { currentFlags = it }
            }
        }
    }

    private data class ResponseAccum(val lines: List<String>, val finalLine: String)

    private fun readUntilTag(tag: String): ResponseAccum {
        val lines = mutableListOf<String>()
        var done = false
        while (!done) {
            val pair = io.readLineWithLiteral()
            if (pair == null) {
                done = true
            } else {
                lines.add(pair.first)
                if (pair.first.startsWith(tag)) done = true
            }
        }
        return ResponseAccum(lines, lines.lastOrNull() ?: "")
    }

    private fun readResponse(tag: String): Pair<String, List<String>> {
        val lines = mutableListOf<String>()
        var final = ""
        var done = false
        while (!done) {
            val pair = io.readLineWithLiteral()
            if (pair == null) {
                done = true
            } else {
                lines.add(pair.first)
                if (pair.first.startsWith(tag)) {
                    final = pair.first
                    done = true
                }
            }
        }
        return (final.ifEmpty { lines.lastOrNull() ?: "" }) to lines
    }
}
