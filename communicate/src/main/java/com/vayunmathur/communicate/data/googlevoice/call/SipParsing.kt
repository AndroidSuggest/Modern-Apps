package com.vayunmathur.communicate.data.googlevoice.call

/**
 * SIP header parsing for SipClient (split for file length).
 * Extension functions on SipClient; behavior identical, call sites unchanged.
 */

internal fun SipClient.extractContact(message: String): String? {
    val line = message.lineSequence().firstOrNull { it.trim().startsWith("Contact:", true) } ?: return null
    return Regex("<([^>]+)>").find(line)?.groupValues?.getOrNull(1)
        ?: line.substringAfter(":").trim().ifBlank { null }
}

/** All Record-Route URIs (in order) from a response; the dialog route set is their reverse. */
internal fun SipClient.extractRecordRoutes(message: String): List<String> =
    message.lineSequence()
        .filter { it.trim().startsWith("Record-Route:", true) }
        .mapNotNull { Regex("<([^>]+)>").find(it)?.groupValues?.getOrNull(1)?.let { u -> "<$u>" } }
        .toList()

internal fun SipClient.parseHeaders(message: String): Map<String, String> {
    val out = mutableMapOf<String, String>()
    for (line in message.lineSequence()) {
        val trimmed = line.trimEnd()
        if (trimmed.isEmpty()) break
        val idx = trimmed.indexOf(':')
        if (idx > 0) {
            out[trimmed.substring(0, idx).trim().lowercase()] = trimmed.substring(idx + 1).trim()
        }
    }
    return out
}

internal fun SipClient.extractTag(headerValue: String): String? =
    Regex(";tag=([^;\\s]+)").find(headerValue)?.groupValues?.getOrNull(1)

/** Extract a quoted or bare param (e.g. nonce, realm) from a WWW-Authenticate header. */
internal fun SipClient.extractParam(header: String, name: String): String? =
    Regex("$name=\"([^\"]*)\"").find(header)?.groupValues?.getOrNull(1)
        ?: Regex("$name=([^,\\s]+)").find(header)?.groupValues?.getOrNull(1)

internal fun SipClient.extractUserFromHeader(headerValue: String): String? =
    Regex("sip:([^@>;\\s]+)@").find(headerValue)?.groupValues?.getOrNull(1)
