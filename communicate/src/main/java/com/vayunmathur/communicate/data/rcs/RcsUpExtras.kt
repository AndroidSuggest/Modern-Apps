package com.vayunmathur.communicate.data.rcs

/**
 * Universal Profile extras beyond 1:1 chat sessions.
 *
 * - Geolocation Push (RCC.07 Annex: `application/vnd.gsma.rcs.geopush+xml`,
 *   iari tag `...ims.iari.rcs.geosms` — already requested in
 *   [RcsSipTransport.CPM_FEATURE_TAGS]): RCC.07 §2.6 Geolocation Push format.
 * - Large Message (pager-mode `cpm.largemsg`): CPIM split across multiple SIP
 *   MESSAGEs with `Message-ID` correlation; reassembly on receipt.
 * - Message Revoke / Edit over pager-mode: `message/cpim` wrapping an IMDN
 *   `revoke` extension (UP 2.x) / replacement text with `Message-ID` match.
 * - Delivery-report correlation: outgoing CPIM carries `imdn.Message-ID`;
 *   inbound IMDN `positive-delivery`/`display` map back to our cached
 *   message rows for status ticks.
 *
 * All bodies are plain strings over the existing pager-mode path — no new
 * transport needed.
 */

/** Geolocation Push body (RCC.07 Geolocation Push schema, minimal subset). */
fun buildGeopushBody(latitude: Double, longitude: Double, label: String? = null): String =
    buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<rcsenvelope xmlns=\"urn:gsma:params:xml:ns:rcs:rcsenvelope\">\n")
        append("  <rcspushlocation>\n")
        append("    <latitude>$latitude</latitude>\n")
        append("    <longitude>$longitude</longitude>\n")
        if (!label.isNullOrBlank()) append("    <label>${label.xmlEscape()}</label>\n")
        append("    <radius>0</radius>\n")
        append("  </rcspushlocation>\n")
        append("</rcsenvelope>")
    }

/** Parse a Geolocation Push body into (lat, lon, label?), or null. */
fun parseGeopushBody(body: String): Triple<Double, Double, String?>? {
    if (!body.contains("rcspushlocation", ignoreCase = true)) return null
    val lat = Regex("<latitude>([^<]+)</latitude>", RegexOption.IGNORE_CASE).find(body)
        ?.groupValues?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
    val lon = Regex("<longitude>([^<]+)</longitude>", RegexOption.IGNORE_CASE).find(body)
        ?.groupValues?.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return null
    val label = Regex("<label>([^<]*)</label>", RegexOption.IGNORE_CASE).find(body)
        ?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
    return Triple(lat, lon, label)
}

private fun String.xmlEscape(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

/** Maximum single pager-mode payload before Large Message chunking. */
const val RCS_LARGE_MESSAGE_CHUNK = 8192

/**
 * Split [text] into Large Message chunks, each tagged with the shared
 * `imdn.Message-ID` ([messageId]) + `part/total` envelope headers so the far
 * end reassembles. Returns single-element list when under the limit.
 */
fun chunkLargeMessage(messageId: String, text: String): List<String> {
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (bytes.size <= RCS_LARGE_MESSAGE_CHUNK) return listOf(text)
    val parts = bytes.toList().chunked(RCS_LARGE_MESSAGE_CHUNK).map {
        it.toByteArray().toString(Charsets.UTF_8)
    }
    return parts.mapIndexed { index, part ->
        "Message-ID: $messageId\r\nPart: ${index + 1}/${parts.size}\r\n\r\n$part"
    }
}

/**
 * Reassemble Large Message chunks. [chunks] maps part number → text for one
 * `Message-ID`; returns the joined text when all [total] parts are present.
 */
fun reassembleLargeMessage(chunks: Map<Int, String>, total: Int): String? {
    if (chunks.size != total) return null
    return (1..total).map { chunks[it] ?: return null }.joinToString("")
}

/** Parse a chunk envelope header into (messageId, part, total). */
fun parseChunkHeader(body: String): Triple<String, Int, Int>? {
    val id = Regex("Message-ID:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(body)
        ?.groupValues?.getOrNull(1)?.trim() ?: return null
    val part = Regex("Part:\\s*(\\d+)/(\\d+)", RegexOption.IGNORE_CASE).find(body)
        ?: return null
    val (num, total) = part.destructured
    return Triple(id, num.toIntOrNull() ?: return null, total.toIntOrNull() ?: return null)
}

/**
 * Build a pager-mode revoke for [originalMessageId]: CPIM wrapping an IMDN
 * revoke extension body. The far end blanks the message row.
 */
fun buildRevokeBody(originalMessageId: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
    append("<imdn xmlns=\"urn:ietf:params:xml:ns:imdn\">\n")
    append("  <message-id>$originalMessageId</message-id>\n")
    append("  <datetime>${java.time.Instant.now()}</datetime>\n")
    append("  <status><revoked xmlns=\"urn:gsma:params:xml:ns:rcs:revoke\"/></status>\n")
    append("</imdn>")
}

/** True when [body] is a revoke report for some message; returns its id. */
fun parseRevokeBody(body: String): String? {
    if (!body.contains("revoked", ignoreCase = true)) return null
    return Regex("<message-id>([^<]+)</message-id>").find(body)
        ?.groupValues?.getOrNull(1)?.trim()
}

/**
 * Build a pager-mode edit: replacement [newText] with the original
 * `imdn.Message-ID` so the far end swaps the row body.
 */
fun buildEditBody(originalMessageId: String, newText: String): String = buildString {
    append("imdn.Message-ID: $originalMessageId\r\n")
    append("Edit-Type: replace\r\n")
    append("\r\n")
    append(newText)
}

/** Parse an edit body into (originalId, newText). */
fun parseEditBody(body: String): Pair<String, String>? {
    if (!body.contains("Edit-Type: replace", ignoreCase = true)) return null
    val id = Regex("imdn\\.Message-ID:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(body)
        ?.groupValues?.getOrNull(1)?.trim() ?: return null
    val text = body.substringAfter("\r\n\r\n", "").ifBlank {
        body.substringAfter("\n\n", "")
    }.trim()
    if (text.isEmpty()) return null
    return id to text
}
