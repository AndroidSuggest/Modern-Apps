package com.vayunmathur.contacts.util
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import com.vayunmathur.contacts.data.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

object VcfUtils {
    suspend fun exportContacts(contacts: List<Contact>, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (contact in contacts) {
                    val details = contact.details
                    writeFolded(writer, "BEGIN:VCARD")
                    writeFolded(writer, "VERSION:3.0")

                    // Name (N) - family;given;additional;prefix;suffix
                    val name = details.names.firstOrNull()
                    val family = name?.lastName ?: ""
                    val given = name?.firstName ?: ""
                    val additional = name?.middleName ?: ""
                    val prefix = name?.namePrefix ?: ""
                    val suffix = name?.nameSuffix ?: ""
                    writeFolded(writer, "N:${escapeV(family)};${escapeV(given)};${escapeV(additional)};${escapeV(prefix)};${escapeV(suffix)}")

                    // FN
                    val fn = listOfNotNull(prefix.ifEmpty { null }, given.ifEmpty { null }, additional.ifEmpty { null }, family.ifEmpty { null }, suffix.ifEmpty { null })
                        .joinToString(" ")
                    val fnValue = fn.ifBlank { (details.names.firstOrNull()?.value ?: "") }
                    writeFolded(writer, "FN:${escapeV(fnValue)}")

                    // Phones - preserve custom label as X- token for roundtrip
                    for (phone in details.phoneNumbers) {
                        val typeToken = when (phone.type) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "FAX"
                            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM -> {
                                val sanitized = phone.label.ifBlank { "CUSTOM" }.replace(",", "").replace(";", "").replace(":", "")
                                "X-$sanitized"
                            }
                            else -> "VOICE"
                        }
                        writeFolded(writer, "TEL;TYPE=$typeToken:${escapeV(phone.number)}")
                    }

                    // Emails
                    for (email in details.emails) {
                        val typeToken = when (email.type) {
                            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
                            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
                            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM -> {
                                val sanitized = email.label.ifBlank { "CUSTOM" }.replace(",", "").replace(";", "").replace(":", "")
                                "X-$sanitized"
                            }
                            else -> "INTERNET"
                        }
                        writeFolded(writer, "EMAIL;TYPE=$typeToken:${escapeV(email.address)}")
                    }

                    // Addresses
                    for (addr in details.addresses) {
                        val formatted = addr.formattedAddress
                        val typeToken = if (addr.type == ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM) {
                            val sanitized = addr.label.ifBlank { "CUSTOM" }.replace(",", "").replace(";", "").replace(":", "")
                            "X-$sanitized"
                        } else "HOME"
                        writeFolded(writer, "ADR;TYPE=$typeToken:;;${escapeV(formatted)};;;;")
                    }

                    // Other dates (non-birthday) with custom label support
                    for (event in details.dates.filter { it.type != ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY }) {
                        val typeToken = if (event.type == ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM) {
                            val sanitized = event.label.ifBlank { "CUSTOM" }.replace(",", "").replace(";", "").replace(":", "")
                            "X-$sanitized"
                        } else "OTHER"
                        writeFolded(writer, "X-EVENT;TYPE=$typeToken:${escapeV(event.startDate.toString())}")
                    }

                    // Organization
                    val org = details.orgs.firstOrNull()?.company ?: ""
                    if (org.isNotEmpty()) writeFolded(writer, "ORG:${escapeV(org)}")

                    // Birthday
                    val bday = details.dates.firstOrNull { it.type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY }
                    if (bday != null) {
                        val bdayStr = if (bday.startDate.hasYear) bday.startDate.toString() else "--${bday.startDate.toString().substring(5)}"
                        writeFolded(writer, "BDAY:$bdayStr")
                    }

                    // Notes
                    for (note in details.notes) {
                        if (note.content.isNotEmpty()) writeFolded(writer, "NOTE:${escapeV(note.content)}")
                    }

                    // Photo (base64) - write as single line; large photos are written raw
                    val photo = details.photos.firstOrNull()
                    if (photo != null && photo.photo.isNotEmpty()) {
                        writeFolded(writer, "PHOTO;ENCODING=b:${photo.photo}")
                    }

                    writeFolded(writer, "END:VCARD")
                }
                writer.flush()
            }
        }
    }

    fun parseContacts(inputStream: InputStream): List<Contact> {
        val contactsToSave = mutableListOf<Contact>()
        val rawLines = inputStream.bufferedReader(Charsets.UTF_8).use { br ->
            buildList {
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    add(line!!)
                }
            }
        }
        val unfolded = mutableListOf<String>()
        var bufferLine: String? = null
        for (ln in rawLines) {
            if (ln.startsWith(" ") || ln.startsWith("\t")) {
                bufferLine = (bufferLine ?: "") + ln.trimStart()
            } else {
                if (bufferLine != null) unfolded.add(bufferLine)
                bufferLine = ln
            }
        }
        if (bufferLine != null) unfolded.add(bufferLine)

        var currentContact: ContactBuilder? = null

        for (raw in unfolded) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("BEGIN:VCARD", ignoreCase = true)) {
                currentContact = ContactBuilder()
                continue
            }
            if (line.startsWith("END:VCARD", ignoreCase = true)) {
                currentContact?.let { builder ->
                    val details = ContactDetails(
                        phoneNumbers = builder.phones.toList(),
                        emails = builder.emails.toList(),
                        addresses = builder.addresses.toList(),
                        dates = builder.dates.toList(),
                        photos = builder.photos.toList(),
                        names = builder.names.toList(),
                        orgs = builder.orgs.toList(),
                        notes = builder.notes.toList(),
                        nicknames = builder.nicknames.toList(),
                        groups = emptyList()
                    )
                    val newContact = Contact(
                        id = 0L,
                        null,
                        null,
                        isFavorite = false,
                        details
                    )
                    contactsToSave.add(newContact)
                }
                currentContact = null
                continue
            }

            if (currentContact == null) continue

            // Parse property line: NAME[;PARAMS]:VALUE
            val colonIndex = line.indexOf(':')
            if (colonIndex == -1) continue
            val nameAndParams = line.take(colonIndex)
            val valuePart = line.substring(colonIndex + 1)

            val segments = nameAndParams.split(';')
            val propName = segments.firstOrNull()?.uppercase() ?: continue
            val params = parseParams(segments.drop(1))

            // Handle QUOTED-PRINTABLE decoding
            val encodingVals = params["ENCODING"] ?: params["ENCOD"]
            val isQP = encodingVals?.any { it.equals("QUOTED-PRINTABLE", ignoreCase = true) } == true
            val charsetName = params["CHARSET"]?.firstOrNull() ?: params["CHARSET*"]?.firstOrNull()
            val value = if (isQP) decodeQuotedPrintable(valuePart, charsetName ?: "UTF-8") else valuePart

            when (propName) {
                "N" -> {
                    val comps = value.split(';')
                    val family = comps.getOrNull(0) ?: ""
                    val given = comps.getOrNull(1) ?: ""
                    val additional = comps.getOrNull(2) ?: ""
                    val prefix = comps.getOrNull(3) ?: ""
                    val suffix = comps.getOrNull(4) ?: ""
                    currentContact.names.clear()
                    currentContact.names.add(Name(0, prefix, given, additional, family, suffix))
                }
                "FN" -> {
                    if (currentContact.names.isEmpty()) {
                        val first = value.split(" ").firstOrNull() ?: value
                        val last = value.split(" ").drop(1).joinToString(" ")
                        currentContact.names.add(Name(0, "", first, "", last, ""))
                    }
                }
                "TEL" -> {
                    val (ttype, tlabel) = detectPhoneTypeWithLabel(params)
                    currentContact.phones.add(PhoneNumber(0, value, ttype, tlabel))
                }
                "EMAIL" -> {
                    val (etype, elabel) = detectEmailTypeWithLabel(params)
                    currentContact.emails.add(Email(0, value, etype, elabel))
                }
                "ADR" -> {
                    val comps = value.split(';')
                    val street = comps.getOrNull(2) ?: ""
                    val city = comps.getOrNull(3) ?: ""
                    val region = comps.getOrNull(4) ?: ""
                    val postal = comps.getOrNull(5) ?: ""
                    val country = comps.getOrNull(6) ?: ""
                    val formatted = listOfNotNull(street.ifEmpty { null }, city.ifEmpty { null }, region.ifEmpty { null }, postal.ifEmpty { null }, country.ifEmpty { null }).joinToString(", ")
                    val (atype, alabel) = detectAddressTypeWithLabel(params)
                    currentContact.addresses.add(Address(0, formatted, atype, alabel))
                }
                "X-EVENT" -> {
                    var dv = value
                    if (dv.matches(Regex("^\\d{8}"))) {
                        dv = dv.take(4) + "-" + dv.substring(4,6) + "-" + dv.substring(6,8)
                    } else if (dv.startsWith("--")) {
                        dv = "1604" + dv.substring(2)
                    }
                    try {
                        val date = LocalDate.parse(dv)
                        val (dtype, dlabel) = detectEventTypeWithLabel(params)
                        currentContact.dates.add(Event(0, date, dtype, dlabel))
                    } catch (_: Exception) { }
                }
                "ORG" -> {
                    currentContact.orgs.clear()
                    currentContact.orgs.add(Organization(0, value))
                }
                "BDAY" -> {
                    var dv = value
                    if (dv.matches(Regex("^\\d{8}"))) {
                        dv = dv.take(4) + "-" + dv.substring(4,6) + "-" + dv.substring(6,8)
                    } else if (dv.startsWith("--")) {
                        dv = "1604" + dv.substring(2)
                    }
                    try {
                        val date = LocalDate.parse(dv)
                        currentContact.dates.add(Event(0, date, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY))
                    } catch (_: Exception) {
                    }
                }
                "NOTE" -> {
                    currentContact.notes.add(Note(0, value))
                }
                "PHOTO" -> {
                    currentContact.photos.add(Photo(0, value))
                }
                "URL" -> {
                    currentContact.notes.add(Note(0, value))
                }
                else -> {}
            }
        }

        return contactsToSave
    }

    private class ContactBuilder {
        val phones: MutableList<PhoneNumber> = mutableListOf()
        val emails: MutableList<Email> = mutableListOf()
        val addresses: MutableList<Address> = mutableListOf()
        val dates: MutableList<Event> = mutableListOf()
        val photos: MutableList<Photo> = mutableListOf()
        val names: MutableList<Name> = mutableListOf()
        val orgs: MutableList<Organization> = mutableListOf()
        val notes: MutableList<Note> = mutableListOf()
        val nicknames: MutableList<Nickname> = mutableListOf()
    }

    private fun escapeV(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun parseParams(parts: List<String>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for (p in parts) {
            if (p.isEmpty()) continue
            val eq = p.indexOf('=')
            if (eq == -1) {
                val k = "TYPE"
                val vals = p.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                out.getOrPut(k) { mutableListOf() }.addAll(vals)
            } else {
                val k = p.take(eq).uppercase()
                val v = p.substring(eq + 1)
                val vals = v.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                out.getOrPut(k) { mutableListOf() }.addAll(vals)
            }
        }
        return out
    }

    private fun writeFolded(writer: Writer, line: String) {
        val maxLineLength = 75
        if (line.length <= maxLineLength) {
            writer.write(line)
            writer.write("\r\n")
            return
        }
        var idx = 0
        while (idx < line.length) {
            val end = kotlin.math.min(idx + maxLineLength, line.length)
            val part = line.substring(idx, end)
            if (idx == 0) {
                writer.write(part)
                writer.write("\r\n")
            } else {
                writer.write(" $part")
                writer.write("\r\n")
            }
            idx = end
        }
    }

    private fun decodeQuotedPrintable(input: String, charsetName: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '=') {
                if (i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3)
                    val byteVal = hex.toIntOrNull(16)
                    if (byteVal != null) {
                        out.write(byteVal)
                        i += 3
                        continue
                    }
                }
                i++
            } else {
                out.write(c.code)
                i++
            }
        }
        return String(out.toByteArray(), charset(charsetName))
    }

    private data class TypedLabel(val type: Int, val label: String)

    private fun extractCustomLabel(tokens: List<String>): String? {
        for (t in tokens) {
            if (t.startsWith("X-", ignoreCase = true) && t.length > 2) {
                return t.substring(2)
            }
        }
        return null
    }

    private fun detectPhoneTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
            tokenStr.contains("CELL", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            tokenStr.contains("HOME", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            tokenStr.contains("WORK", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            tokenStr.contains("FAX", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
            else -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectPhoneType(params: Map<String, List<String>>): Int = detectPhoneTypeWithLabel(params).type

    private fun detectEmailTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
            tokenStr.contains("WORK", ignoreCase = true) -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
            tokenStr.contains("HOME", ignoreCase = true) -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
            else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectEmailType(params: Map<String, List<String>>): Int = detectEmailTypeWithLabel(params).type

    private fun detectAddressTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM
            tokenStr.contains("HOME", ignoreCase = true) -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
            tokenStr.contains("WORK", ignoreCase = true) -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
            else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectEventTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        return if (customLabel != null) {
            TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM, customLabel)
        } else {
            TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_OTHER, "")
        }
    }
}
