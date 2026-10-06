package com.vayunmathur.contacts

import android.content.Intent
import android.provider.ContactsContract

internal fun isVcfIntent(intent: Intent): Boolean {
    val type = intent.type ?: ""
    return type.contains("vcard") ||
        type.contains("vcf") ||
        intent.data?.path?.endsWith(".vcf", ignoreCase = true) == true
}

/**
 * Reads a CommonDataKinds TYPE extra that may be an Int, a numeric String, or a custom label.
 * Returns (type, customLabel); a non-numeric String becomes TYPE_CUSTOM with that label.
 */
internal fun readType(intent: Intent, key: String): Pair<Int?, String?> {
    val extras = intent.extras ?: return null to null
    if (!extras.containsKey(key)) return null to null
    // Typed reads instead of the deprecated Bundle.get(String): CharSequence covers String
    // and styled text (numeric text parses, anything else becomes a TYPE_CUSTOM label),
    // Int covers the numeric form. Anything else is unknown, as before.
    extras.getCharSequence(key)?.let { raw ->
        val s = raw.toString()
        return s.toIntOrNull()?.let { it to null }
            ?: (ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM to s)
    }
    val intValue = extras.getInt(key, Int.MIN_VALUE)
    if (intValue != Int.MIN_VALUE) return intValue to null
    return null to null
}

/** Extracts a phone number from a `tel:` or `phone_lookup` data URI (not from extras). */
internal fun uriPhoneNumber(intent: Intent): String? {
    val uri = intent.data ?: return null
    if (uri.scheme == "tel") {
        return uri.schemeSpecificPart
    }
    if (uri.authority == ContactsContract.AUTHORITY && uri.path?.contains("phone_lookup") == true) {
        return uri.lastPathSegment
    }
    return null
}
